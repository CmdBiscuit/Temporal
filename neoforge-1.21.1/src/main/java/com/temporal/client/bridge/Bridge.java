package com.temporal.client.bridge;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import com.temporal.Temporal;
import com.temporal.TemporalConfig;
import com.temporal.client.EntityMotion;
import com.temporal.client.FirstLaunch;
import com.temporal.client.FullscreenPass;
import com.temporal.client.Upscaler;
import com.temporal.client.nis.NisScaler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.slf4j.Logger;

/**
 * Runs vendor upscalers (DLSS, FSR 3) on a private Vulkan device that shares images with Minecraft's OpenGL context.
 *
 * <p>Per frame: OpenGL copies the jittered scene colour and writes depth and motion vectors into the shared images,
 * then signals a semaphore; Vulkan runs the selected backend into a shared output image and signals back; OpenGL waits
 * on the GPU and copies the full-resolution result into Minecraft's main target. Everything is set up lazily the first
 * time a backend is selected. A failure disables that backend (or the whole bridge) for the session with a chat
 * message, and Temporal falls back to FSR 1.
 */
public final class Bridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int IMAGE_COUNT = 4;

    private enum State { UNTRIED, READY, FAILED }

    private static State state = State.UNTRIED;
    private static final Map<TemporalConfig.Upscaler, String> unavailable = new EnumMap<>(TemporalConfig.Upscaler.class);
    @Nullable private static Streamline streamline;
    @Nullable private static VulkanContext vulkan;
    @Nullable private static DlssBackend dlss;
    @Nullable private static Fsr3Backend fsr3;
    @Nullable private static Backend active;
    @Nullable private static ShaderInstance depthShader;
    @Nullable private static ShaderInstance motionShader;
    @Nullable private static ShaderInstance handShader;
    private static long glToVulkan;
    private static long vulkanToGl;
    private static int glToVulkanGl;
    private static int vulkanToGlGl;
    @Nullable private static SharedImage color;
    @Nullable private static SharedImage depth;
    @Nullable private static SharedImage motion;
    @Nullable private static SharedImage output;
    /** FSR 3's reactive mask (render size, one channel). */
    @Nullable private static SharedImage reactive;
    @Nullable private static ShaderInstance reactiveShader;
    private static int failures;
    private static long lastFrameNanos;
    @Nullable private static Frame lastFrame;
    private static int lastFrameIndex = -1;

    /** Why DLSS, FSR 3 and frame generation can't run off Windows (the Vulkan interop and the natives are Windows-only). */
    public static final String WINDOWS_ONLY = "DLSS, FSR 3 and frame generation only work on Windows";

    static {
        if (!osSupported()) {
            unavailable.put(TemporalConfig.Upscaler.DLSS, WINDOWS_ONLY);
            unavailable.put(TemporalConfig.Upscaler.FSR3, WINDOWS_ONLY);
        }
    }

    /** Whether this OS can run the bridge at all; {@code -Dtemporal.debug.notWindows} pretends not (debugging). */
    public static boolean osSupported() {
        return Util.getPlatform() == Util.OS.WINDOWS && !Boolean.getBoolean("temporal.debug.notWindows");
    }

    /** The selected upscaler runs on the bridge and hasn't failed this session. */
    public static boolean isRequested() {
        TemporalConfig.Upscaler upscaler = selected();
        return upscaler != null && state != State.FAILED && !unavailable.containsKey(upscaler);
    }

    @Nullable
    private static TemporalConfig.Upscaler selected() {
        try {
            if (!TemporalConfig.ENABLED.get()) {
                return null;
            }
            TemporalConfig.Upscaler upscaler = TemporalConfig.UPSCALER.get();
            return upscaler == TemporalConfig.Upscaler.DLSS || upscaler == TemporalConfig.Upscaler.FSR3 ? upscaler : null;
        } catch (IllegalStateException notLoadedYet) {
            return null;
        }
    }

    private static TemporalConfig.QualityMode quality() {
        try {
            return TemporalConfig.QUALITY_MODE.get();
        } catch (IllegalStateException notLoadedYet) {
            return TemporalConfig.QualityMode.QUALITY;
        }
    }

    private static float sharpness() {
        try {
            return TemporalConfig.SHARPNESS.get().floatValue();
        } catch (IllegalStateException notLoadedYet) {
            return 0.0F;
        }
    }

    /**
     * Render resolution as a fraction of the output for the selected upscaler and quality mode: FSR's per-dimension
     * ratios are 1.5x, 1.7x, 2x and 3x; DLSS's Balanced renders slightly larger.
     */
    public static double renderScale() {
        TemporalConfig.QualityMode quality = quality();
        if (selected() == TemporalConfig.Upscaler.FSR3) {
            return switch (quality) {
                case NATIVE -> 1.0;
                case QUALITY -> 1.0 / 1.5;
                case BALANCED -> 1.0 / 1.7;
                case PERFORMANCE -> 0.5;
                case ULTRA_PERFORMANCE -> 1.0 / 3.0;
            };
        }
        return switch (quality) {
            case NATIVE -> 1.0;
            case QUALITY -> 2.0 / 3.0;
            case BALANCED -> 0.58;
            case PERFORMANCE -> 0.5;
            case ULTRA_PERFORMANCE -> 1.0 / 3.0;
        };
    }

    /** NVIDIA and AMD both recommend a jitter sequence of at least 8 x (output/render)^2 phases. */
    public static int jitterPhases() {
        // The scale actually rendered at (dynamic resolution may be below the Quality Mode's).
        double ratio = 1.0 / Upscaler.renderScale();
        return Math.max(8, (int) Math.ceil(8.0 * ratio * ratio));
    }

    /**
     * Upscales the jittered scene with the selected backend and writes the full-resolution result into {@code main}.
     *
     * @return false if the backend is unavailable, in which case the caller falls back to its own anti-aliasing
     */
    public static boolean evaluate(RenderTarget scene, RenderTarget main, Matrix4f projection, Matrix4f view,
                                   Matrix4f previousViewProjection, Vec3 cameraDelta, Camera camera,
                                   float jitterX, float jitterY, boolean reset) {
        TemporalConfig.Upscaler upscaler = selected();
        if (upscaler == null || !ensureReady()) {
            return false;
        }
        Backend backend = backend(upscaler);
        if (backend == null) {
            return false;
        }
        if (depthShader == null || motionShader == null) {
            disable(upscaler, "the upscaler input shaders failed to load", null);
            return false;
        }
        try {
            if (active != null && active != backend) {
                active.release();
            }
            active = backend;
            ensureImages(scene, main);
            Matrix4f viewProjection = new Matrix4f(projection).mul(view);
            long now = System.nanoTime();
            float frameTimeMs = lastFrameNanos == 0 ? 16.7F : Math.min(100.0F, (now - lastFrameNanos) / 1.0e6F);
            lastFrameNanos = now;
            SharedImage mask = upscaler == TemporalConfig.Upscaler.FSR3 && ReactiveMask.write(scene, reactive, reactiveShader)
                    ? reactive : null;
            Frame frame = new Frame(color, depth, motion, output, mask, scene.width, scene.height, projection, viewProjection,
                    previousViewProjection, cameraDelta, camera, jitterX, jitterY, reset, frameTimeMs, quality(), sharpness());
            backend.configure(frame);
            prepareInputs(scene, frame);
            remember(frame);
            if (!runOnVulkan(backend, frame, upscaler)) {
                return false;
            }
            FullscreenPass.blitFlipped(output.target, main, GL11.GL_NEAREST);
            if (upscaler == TemporalConfig.Upscaler.DLSS) {
                // DLSS has no sharpening of its own: NIS's sharpener on the result, if the slider asks for any.
                float sharpness = TemporalConfig.DLSS_SHARPNESS.get().floatValue();
                if (sharpness > 0.0F) {
                    NisScaler.sharpen(main, sharpness);
                }
            }
            failures = 0;
            return true;
        } catch (Throwable t) {
            disable(upscaler, backend.displayName() + " stopped working", t);
            return false;
        }
    }

    /**
     * Frame generation needs depth and motion vectors every frame. When no GPU upscaler wrote them this frame (FSR 1,
     * TAA, or no upscaling), writes them here; it's GL work only, Vulkan reads them at present.
     */
    public static void prepareFrameGeneration(RenderTarget scene, RenderTarget main, Matrix4f projection, Matrix4f view,
                                              Matrix4f previousViewProjection, Vec3 cameraDelta, Camera camera,
                                              float jitterX, float jitterY, boolean reset) {
        if (currentFrame() != null || !ensureReady() || depthShader == null || motionShader == null) {
            return;
        }
        try {
            ensureImages(scene, main);
            Frame frame = new Frame(color, depth, motion, output, null, scene.width, scene.height, projection,
                    new Matrix4f(projection).mul(view),
                    previousViewProjection, cameraDelta, camera, jitterX, jitterY, reset, 0.0F, quality(), sharpness());
            prepareDepthAndMotion(scene, frame);
            remember(frame);
        } catch (Throwable t) {
            FrameGeneration.disable("its depth and motion inputs could not be prepared", t);
        }
    }

    /**
     * Frame generation: where the first-person hand was drawn (its depth buffer is cleared just before the hand),
     * motion vectors become zero and depth becomes the hand's, so generated frames keep the hand with the camera.
     */
    static void maskHand(RenderTarget handTarget) {
        Frame frame = currentFrame();
        if (frame == null || handShader == null) {
            return;
        }
        handShader.setSampler("HandDepthSampler", handTarget.getDepthTextureId());
        handShader.safeGetUniform("OutSize").set((float) frame.renderWidth(), (float) frame.renderHeight());
        handShader.safeGetUniform("Mode").set(0.0F);
        FullscreenPass.draw(handShader, frame.motion().target, frame.renderWidth(), frame.renderHeight());
        handShader.safeGetUniform("Mode").set(1.0F);
        FullscreenPass.draw(handShader, frame.depth().target, frame.renderWidth(), frame.renderHeight());
    }

    private static void remember(Frame frame) {
        lastFrame = frame;
        lastFrameIndex = FrameGeneration.frameIndex();
    }

    /** This frame's shared depth/motion images and camera data, or null if none were prepared this frame. */
    @Nullable
    static Frame currentFrame() {
        return lastFrame != null && lastFrameIndex == FrameGeneration.frameIndex() ? lastFrame : null;
    }

    /** The motion vectors of the last frame that prepared any (debugging only), or null. */
    @Nullable
    public static RenderTarget debugMotion() {
        return lastFrame != null ? lastFrame.motion().target : null;
    }

    /** FSR 3's reactive mask from the last frame that had one (debugging only, top-down), or null. */
    @Nullable
    public static RenderTarget debugReactive() {
        return lastFrame != null && lastFrame.reactive() != null ? lastFrame.reactive().target : null;
    }

    @Nullable
    static Streamline streamline() {
        return streamline;
    }

    @Nullable
    private static Backend backend(TemporalConfig.Upscaler upscaler) {
        if (upscaler == TemporalConfig.Upscaler.DLSS) {
            if (dlss == null) {
                disable(upscaler, "DLSS is not available", null);
            }
            return dlss;
        }
        if (fsr3 == null) {
            try {
                fsr3 = Fsr3Backend.load(vulkan, fsr3JitterSign());
            } catch (Throwable t) {
                disable(upscaler, "FSR 3 is not available", t);
                return null;
            }
        }
        return fsr3;
    }

    private static boolean ensureReady() {
        if (state == State.READY) {
            return true;
        }
        if (state == State.FAILED) {
            return false;
        }
        try {
            initialize();
            state = State.READY;
            return true;
        } catch (Throwable t) {
            state = State.FAILED;
            FrameGeneration.markAllUnavailable("the Vulkan bridge could not start");
            TemporalConfig.Upscaler upscaler = selected();
            if (upscaler != null && FirstLaunch.fallBack(upscaler, false)) {
                LOGGER.warn("Temporal: upscaling on the GPU is not available", t);
            } else {
                report("upscaling on the GPU is not available", t);
            }
            return false;
        }
    }

    private static void initialize() throws IOException {
        GLCapabilities caps = GL.getCapabilities();
        if (!caps.GL_EXT_memory_object_win32 || !caps.GL_EXT_semaphore_win32) {
            throw new IllegalStateException("this OpenGL driver cannot share images with Vulkan");
        }
        byte[] uuid = new byte[16];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(16);
            EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, 0, buffer);
            buffer.get(0, uuid);
        }

        // Streamline must be initialised before the Vulkan device exists, and the device needs DLSS's extensions.
        // It's optional: without it (non-NVIDIA GPU, or any Streamline failure) FSR 3 still works.
        Streamline.Requirements requirements = Streamline.Requirements.NONE;
        String vendor = GL11.glGetString(GL11.GL_VENDOR);
        if (vendor != null && vendor.contains("NVIDIA")) {
            try {
                Streamline loaded = Streamline.load(Minecraft.getInstance().gameDirectory.toPath());
                int result = loaded.init();
                if (result == 0) {
                    requirements = loaded.requirements(Streamline.FEATURE_DLSS);
                    streamline = loaded;
                    // Frame generation's needs go into the same device; if any part is missing, only it is lost.
                    try {
                        Streamline.Requirements frameGeneration = loaded.requirements(Streamline.FEATURE_DLSS_G)
                                .plus(loaded.requirements(Streamline.FEATURE_REFLEX))
                                .plus(loaded.requirements(Streamline.FEATURE_PCL));
                        requirements = requirements.plus(frameGeneration);
                    } catch (IllegalStateException e) {
                        FrameGeneration.markUnavailable("DLSS Frame Generation is not available: " + e.getMessage());
                    }
                } else {
                    unavailable.put(TemporalConfig.Upscaler.DLSS, "Streamline init failed: " + Streamline.resultName(result));
                }
            } catch (Throwable t) {
                LOGGER.warn("Temporal: Streamline unavailable, DLSS disabled", t);
                unavailable.put(TemporalConfig.Upscaler.DLSS, "Streamline unavailable: " + t);
            }
        } else {
            unavailable.put(TemporalConfig.Upscaler.DLSS, "DLSS needs an NVIDIA RTX GPU");
        }
        if (streamline == null) {
            FrameGeneration.markUnavailable("DLSS Frame Generation needs an NVIDIA RTX 40 or newer GPU");
        }

        vulkan = VulkanContext.create(uuid, requirements);
        if (!vulkan.canPresent) {
            FrameGeneration.markAllUnavailable("frame generation can't present through Vulkan on this PC");
        } else if (vulkan.fsrPresentQueue == null) {
            FrameGeneration.markUnavailable(FrameGeneration.Method.FSR,
                    "FSR 3 Frame Generation needs spare GPU queues and timeline semaphores, which this GPU lacks");
        }
        if (streamline != null) {
            int result = streamline.setVulkanInfo(vulkan.device, vulkan.instance, vulkan.physicalDevice,
                    vulkan.computeFamily, vulkan.computeQueueStart, vulkan.queueFamily, vulkan.graphicsQueueStart,
                    vulkan.opticalFlowFamily);
            if (result == 0) {
                dlss = new DlssBackend(streamline, vulkan, dlssJitterSign());
                FrameGeneration.probe(streamline, vulkan);
            } else {
                unavailable.put(TemporalConfig.Upscaler.DLSS, "slSetVulkanInfo failed: " + Streamline.resultName(result));
            }
        }

        glToVulkan = exportedSemaphore();
        vulkanToGl = exportedSemaphore();
        glToVulkanGl = importSemaphore(glToVulkan);
        vulkanToGlGl = importSemaphore(vulkanToGl);
        LOGGER.info("Temporal: upscaler bridge ready on {} (DLSS: {})", vulkan.deviceName,
                dlss != null ? "Streamline " + Streamline.VERSION : unavailable.get(TemporalConfig.Upscaler.DLSS));
    }

    /**
     * Jitter sign convention handed to a backend, with an optional dev override "x,y" (each 1 or -1). Both DLSS and
     * FSR 3 take the offset in pixels with Y pointing down the (top-down) image, while our projection shift is in GL
     * clip space with Y up, so Y flips (confirmed by full-resolution diffs against native: 5.4 vs 10.8 for DLSS Quality).
     */
    /** DLSS's jitter convention, also used for the constants frame generation sets when DLSS isn't running. */
    static float[] dlssJitterSign() {
        return jitterSign("temporal.dlss.jitter");
    }

    /** FSR 3's jitter convention (upscaling and frame generation). */
    static float[] fsr3JitterSign() {
        return jitterSign("temporal.fsr3.jitter");
    }

    private static float[] jitterSign(String property) {
        String value = System.getProperty(property, "");
        if (!value.contains(",")) {
            return new float[]{1.0F, -1.0F};
        }
        String[] parts = value.split(",");
        return new float[]{Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim())};
    }

    private static long exportedSemaphore() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExportSemaphoreCreateInfo export = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default()
                    .handleTypes(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            PointerBuffer handle = stack.callocPointer(1);
            VkApi.check(vulkan.vk.createSemaphore, "vkCreateSemaphore", vulkan.device, VkSemaphoreCreateInfo.calloc(stack)
                    .sType$Default().pNext(export.address()).address(), MemoryUtil.NULL, handle.address());
            return handle.get(0);
        }
    }

    private static int importSemaphore(long semaphore) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer win32 = stack.callocPointer(1);
            VkApi.check(vulkan.vk.getSemaphoreWin32Handle, "vkGetSemaphoreWin32HandleKHR", vulkan.device,
                    VkSemaphoreGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                            .semaphore(semaphore)
                            .handleType(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT).address(), win32.address());
            int glSemaphore = EXTSemaphore.glGenSemaphoresEXT();
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(glSemaphore, EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, win32.get(0));
            return glSemaphore;
        }
    }

    /**
     * Sizes the input images for the largest render size the current settings allow (the Quality Mode's, or Render
     * Scale), so dynamic resolution can lower the scale frame by frame without rebuilding anything.
     */
    private static void ensureImages(RenderTarget scene, RenderTarget main) {
        ensureImages(Math.max(scene.width, Upscaler.ceiling(main.width)), Math.max(scene.height, Upscaler.ceiling(main.height)),
                main.width, main.height);
    }

    /** (Re)creates the shared images whenever the largest render size or the output size changes. */
    private static void ensureImages(int inWidth, int inHeight, int outWidth, int outHeight) {
        if (color != null && color.width == inWidth && color.height == inHeight
                && output.width == outWidth && output.height == outHeight) {
            return;
        }
        if (active != null) {
            active.release();
        }
        vulkan.waitIdle();
        closeImages();
        color = new SharedImage(vulkan, inWidth, inHeight, VK_FORMAT_R8G8B8A8_UNORM, GL11.GL_RGBA8);
        depth = new SharedImage(vulkan, inWidth, inHeight, VK_FORMAT_R32_SFLOAT, GL30.GL_R32F);
        motion = new SharedImage(vulkan, inWidth, inHeight, VK_FORMAT_R16G16_SFLOAT, GL30.GL_RG16F);
        output = new SharedImage(vulkan, outWidth, outHeight, VK_FORMAT_R8G8B8A8_UNORM, GL11.GL_RGBA8);
        // The same format as depth: every Vulkan GPU can use it as a storage image (8-bit single channel isn't guaranteed).
        reactive = new SharedImage(vulkan, inWidth, inHeight, VK_FORMAT_R32_SFLOAT, GL30.GL_R32F);
        transitionToGeneral(color, depth, motion, output, reactive);
        LOGGER.info("Temporal: upscaler images {}x{} -> {}x{}", inWidth, inHeight, outWidth, outHeight);
    }

    static void transitionToGeneral(SharedImage... images) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vulkan.beginCommands(stack);
            VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(images.length, stack);
            for (int i = 0; i < images.length; i++) {
                // New images start out owned by OpenGL (the external side), which writes them first.
                VkImageMemoryBarrier barrier = barriers.get(i).sType$Default()
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(vulkan.queueFamily)
                        .dstQueueFamilyIndex(VK11.VK_QUEUE_FAMILY_EXTERNAL)
                        .image(images[i].image);
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            }
            NativeCall.callInt(vulkan.vk.cmdPipelineBarrier, vulkan.commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, MemoryUtil.NULL, 0, MemoryUtil.NULL, images.length, barriers.address());
            VkApi.check(vulkan.vk.endCommandBuffer, "vkEndCommandBuffer", vulkan.commandBuffer);
            VkApi.check(vulkan.vk.queueSubmit, "vkQueueSubmit (layout)", vulkan.queue, 1, VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(vulkan.commandBuffer)).address(), vulkan.fence);
            vulkan.waitForFence(stack);
        }
    }

    /** GL side: fills the shared input images from the scene, top-down like everything on the Vulkan side. */
    private static void prepareInputs(RenderTarget scene, Frame frame) {
        FullscreenPass.blitFlippedIntoCorner(scene, color.target, GL11.GL_NEAREST);
        prepareDepthAndMotion(scene, frame);
    }

    private static void prepareDepthAndMotion(RenderTarget scene, Frame frame) {
        depthShader.setSampler("DepthSampler", scene.getDepthTextureId());
        FullscreenPass.draw(depthShader, depth.target, scene.width, scene.height);

        Vec3 cameraDelta = frame.cameraDelta();
        motionShader.setSampler("DepthSampler", scene.getDepthTextureId());
        motionShader.safeGetUniform("InvViewProj").set(new Matrix4f(frame.viewProjection()).invert());
        motionShader.safeGetUniform("PrevViewProj").set(frame.previousViewProjection());
        motionShader.safeGetUniform("CameraDelta").set((float) cameraDelta.x, (float) cameraDelta.y, (float) cameraDelta.z);
        motionShader.safeGetUniform("Jitter").set(2.0F * frame.jitterX() / scene.width, 2.0F * frame.jitterY() / scene.height);
        EntityMotion.apply(motionShader);
        FullscreenPass.draw(motionShader, motion.target, scene.width, scene.height);
    }

    /** The bridge is (or can be) initialised: used by the presenter, which needs the device but no upscaler. */
    static boolean readyForPresent() {
        return ensureReady() && vulkan.canPresent;
    }

    static VulkanContext vulkan() {
        return vulkan;
    }

    static long glToVulkanSemaphore() {
        return glToVulkan;
    }

    static long vulkanToGlSemaphore() {
        return vulkanToGl;
    }

    /**
     * Records the Vulkan half of a hand-over with OpenGL: a queue family ownership transfer from
     * (acquire) or to (release) {@code VK_QUEUE_FAMILY_EXTERNAL}, layout staying GENERAL. Without it the driver may
     * keep using its own cached (e.g. compressed) view of an image and never see what OpenGL wrote.
     * {@code stages}/{@code access} describe Vulkan's use of the images in between.
     */
    static void recordOwnership(MemoryStack stack, long commandBuffer, boolean acquire, int stages, int access,
                                SharedImage... images) {
        VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(images.length, stack);
        for (int i = 0; i < images.length; i++) {
            VkImageMemoryBarrier barrier = barriers.get(i).sType$Default()
                    .srcAccessMask(acquire ? 0 : access)
                    .dstAccessMask(acquire ? access : 0)
                    .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(acquire ? VK11.VK_QUEUE_FAMILY_EXTERNAL : vulkan.queueFamily)
                    .dstQueueFamilyIndex(acquire ? vulkan.queueFamily : VK11.VK_QUEUE_FAMILY_EXTERNAL)
                    .image(images[i].image);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        }
        NativeCall.callInt(vulkan.vk.cmdPipelineBarrier, commandBuffer, stages,
                acquire ? stages : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, MemoryUtil.NULL, 0, MemoryUtil.NULL,
                images.length, barriers.address());
    }

    /** OpenGL is done writing these images: hand them to Vulkan (GPU-side) and flush so Vulkan can start. */
    static void glSignalToVulkan(SharedImage... images) {
        EXTSemaphore.glSignalSemaphoreEXT(glToVulkanGl, new int[0], textures(images), generalLayouts(images.length));
        GL11.glFlush();
    }

    /** Makes OpenGL's later commands wait (on the GPU) until Vulkan has finished with these images. */
    static void glWaitForVulkan(SharedImage... images) {
        EXTSemaphore.glWaitSemaphoreEXT(vulkanToGlGl, new int[0], textures(images), generalLayouts(images.length));
    }

    private static int[] textures(SharedImage... images) {
        int[] textures = new int[images.length];
        for (int i = 0; i < images.length; i++) {
            textures[i] = images[i].glTexture;
        }
        return textures;
    }

    private static int[] generalLayouts(int count) {
        int[] layouts = new int[count];
        Arrays.fill(layouts, EXTSemaphore.GL_LAYOUT_GENERAL_EXT);
        return layouts;
    }

    /** Hands the inputs to Vulkan, runs the backend, and makes OpenGL wait for the result on the GPU. */
    private static boolean runOnVulkan(Backend backend, Frame frame, TemporalConfig.Upscaler upscaler) {
        glSignalToVulkan(color, depth, motion, output, reactive);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vulkan.beginCommands(stack);
            int access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            recordOwnership(stack, vulkan.commandBuffer, true, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, access,
                    color, depth, motion, output, reactive);
            int result = backend.record(vulkan.commandBuffer, frame);
            recordOwnership(stack, vulkan.commandBuffer, false, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, access,
                    color, depth, motion, output, reactive);
            VkApi.check(vulkan.vk.endCommandBuffer, "vkEndCommandBuffer", vulkan.commandBuffer);
            // Submit even after an error so the semaphores stay paired with OpenGL's signal and wait.
            VkApi.check(vulkan.vk.queueSubmit, "vkQueueSubmit", vulkan.queue, 1, VkSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(glToVulkan))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                    .pCommandBuffers(stack.pointers(vulkan.commandBuffer))
                    .pSignalSemaphores(stack.longs(vulkanToGl)).address(), vulkan.fence);
            glWaitForVulkan(color, depth, motion, output, reactive);

            if (result != 0) {
                failures++;
                LOGGER.warn("Temporal {}: evaluate failed: {}", backend.displayName(), backend.resultName(result));
                if (failures >= 10) {
                    disable(upscaler, backend.displayName() + " keeps failing (" + backend.resultName(result) + ")", null);
                }
                return false;
            }
            return true;
        }
    }

    /**
     * No GPU upscaler is selected: give back its VRAM (the upscaler instance, and the shared images unless frame
     * generation still needs depth and motion vectors from them).
     */
    public static void releaseIfIdle() {
        boolean keepImages = FrameGeneration.wanted();
        if (state != State.READY || (active == null && (color == null || keepImages))) {
            return;
        }
        try {
            if (active != null) {
                active.release();
                active = null;
            }
            if (!keepImages) {
                vulkan.waitIdle();
                closeImages();
            }
        } catch (Throwable t) {
            state = State.FAILED;
            report("the GPU upscaler could not be released", t);
        }
    }

    private static void closeImages() {
        for (SharedImage image : new SharedImage[]{color, depth, motion, output, reactive}) {
            if (image != null) {
                image.close();
            }
        }
        color = null;
        depth = null;
        motion = null;
        output = null;
        reactive = null;
        lastFrame = null;
    }

    private static void disable(TemporalConfig.Upscaler upscaler, String message, @Nullable Throwable cause) {
        unavailable.put(upscaler, message);
        if (active != null && active == (upscaler == TemporalConfig.Upscaler.DLSS ? dlss : fsr3)) {
            try {
                active.release();
            } catch (Throwable ignored) {
                // Already failing; the instance is abandoned.
            }
            active = null;
        }
        if (FirstLaunch.fallBack(upscaler, state != State.FAILED && !unavailable.containsKey(TemporalConfig.Upscaler.FSR3))) {
            LOGGER.warn("Temporal: {}", message, cause);
        } else {
            report(message, cause);
        }
    }

    private static void report(String message, @Nullable Throwable cause) {
        LOGGER.error("Temporal: {} - falling back to FSR 1", message, cause);
        String reason = cause != null && cause.getMessage() != null ? ": " + cause.getMessage() : "";
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.translatable("temporal.upscaler.unavailable", message + reason));
        }
    }

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Temporal.MOD_ID, "temporal_dlss_depth"), DefaultVertexFormat.BLIT_SCREEN),
                    shader -> depthShader = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Temporal.MOD_ID, "temporal_dlss_motion"), DefaultVertexFormat.BLIT_SCREEN),
                    shader -> motionShader = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Temporal.MOD_ID, "temporal_fg_hand"), DefaultVertexFormat.BLIT_SCREEN),
                    shader -> handShader = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Temporal.MOD_ID, "temporal_reactive"), DefaultVertexFormat.BLIT_SCREEN),
                    shader -> reactiveShader = shader);
        } catch (IOException e) {
            LOGGER.error("Temporal: could not load the upscaler input shaders", e);
        }
    }

    private Bridge() {}
}
