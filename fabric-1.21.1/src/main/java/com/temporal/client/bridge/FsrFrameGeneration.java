package com.temporal.client.bridge;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.slf4j.Logger;

/**
 * AMD FSR 3 frame generation through the FidelityFX API (FidelityFX SDK 1.1.4, Vulkan): one generated frame between
 * each pair of rendered ones, for GPUs without DLSS Frame Generation.
 *
 * <p>FidelityFX replaces the swapchain: {@link Presenter} creates it through {@link #createSwapchain} and then gets
 * its images, acquires and presents through the replacement functions here. While each frame's present command buffer
 * is recorded, {@link #record} hands FidelityFX the frame's depth and motion vectors (it keeps processed copies) and
 * copies the HUD-less frame into an image of its own, because the interpolation itself runs later: inside the present
 * call, FidelityFX asks for that work through {@link DispatchCallback} (on this thread), runs it on our queue, and
 * paces the generated and rendered frames to the display from its own threads and queues.
 *
 * <p>Structure layouts mirror ffx_api.h, ffx_api_types.h, ffx_framegeneration.h and vk/ffx_api_vk.h for Windows x64.
 */
final class FsrFrameGeneration implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    // structure types
    private static final long CREATE_BACKEND_VK = 0x00000003L;
    private static final long CREATE_FRAME_GENERATION = 0x00020001L;
    private static final long CONFIGURE_FRAME_GENERATION = 0x00020002L;
    private static final long DISPATCH_PREPARE = 0x00020004L;
    private static final long CREATE_HUDLESS = 0x00020009L;
    private static final long PREPARE_CAMERA_INFO = 0x0002000AL;
    private static final long CREATE_SWAPCHAIN_VK = 0x00040001L;
    private static final long QUERY_SWAPCHAIN_FUNCTIONS_VK = 0x00040005L;
    private static final long DISPATCH_WAIT_FOR_PRESENTS_VK = 0x00040007L;
    // structure sizes
    private static final int SIZE_BACKEND_VK = 40;
    private static final int SIZE_CREATE_FRAME_GENERATION = 40;
    private static final int SIZE_CREATE_HUDLESS = 24;
    private static final int SIZE_CREATE_SWAPCHAIN_VK = 248;
    private static final int SIZE_QUERY_FUNCTIONS = 72;
    private static final int SIZE_CONFIGURE = 144;
    private static final int SIZE_PREPARE = 184;
    private static final int SIZE_CAMERA_INFO = 64;
    private static final int SIZE_HEADER = 16;
    /** {@code reset} in the ffxDispatchDescFrameGeneration FidelityFX hands the dispatch callback. */
    private static final int DISPATCH_RESET = 268;
    /** The HUD-less copy is read by the interpolation one present later, so two take turns. */
    private static final int HUDLESS_COPIES = 2;

    private final VulkanContext vulkan;
    private final long ffxCreateContext;
    private final long ffxDestroyContext;
    private final long ffxConfigure;
    private final long ffxQuery;
    private final long ffxDispatch;
    /** Hold the ffxContext handles; the API takes pointers to them. */
    private final long swapchainContext = MemoryUtil.nmemCalloc(1, 8);
    private final long frameGenerationContext = MemoryUtil.nmemCalloc(1, 8);
    private final long configure = MemoryUtil.nmemCalloc(1, SIZE_CONFIGURE);
    private final long prepare = MemoryUtil.nmemCalloc(1, SIZE_PREPARE);
    private final long cameraInfo = MemoryUtil.nmemCalloc(1, SIZE_CAMERA_INFO);
    /** Kept for the life of this object: FidelityFX holds on to the function pointer. */
    private final DispatchCallback dispatchCallback = new DispatchCallback();

    // FidelityFX's replacements for the swapchain functions, valid while its swapchain exists
    long getImages;
    long acquire;
    long present;
    private long getLastPresentCount;
    private long swapchain;
    private boolean frameGenerationCreated;

    private final long[] hudlessImages = new long[HUDLESS_COPIES];
    private final long[] hudlessMemory = new long[HUDLESS_COPIES];
    private final boolean[] hudlessFresh = new boolean[HUDLESS_COPIES];
    private int hudlessWidth;
    private int hudlessHeight;

    /** Increases by exactly one per presented frame, as FidelityFX requires. */
    private long frameId;
    private boolean enabled;
    private boolean resetNext;
    private long lastFrameNanos;
    private int failedDispatches;

    private FsrFrameGeneration(VulkanContext vulkan, SharedLibrary library) {
        this.vulkan = vulkan;
        this.ffxCreateContext = library.getFunctionAddress("ffxCreateContext");
        this.ffxDestroyContext = library.getFunctionAddress("ffxDestroyContext");
        this.ffxConfigure = library.getFunctionAddress("ffxConfigure");
        this.ffxQuery = library.getFunctionAddress("ffxQuery");
        this.ffxDispatch = library.getFunctionAddress("ffxDispatch");
        if (ffxCreateContext == MemoryUtil.NULL || ffxDestroyContext == MemoryUtil.NULL || ffxConfigure == MemoryUtil.NULL
                || ffxQuery == MemoryUtil.NULL || ffxDispatch == MemoryUtil.NULL) {
            throw new IllegalStateException("the FidelityFX runtime is missing API entry points");
        }
    }

    /** Loads FidelityFX for frame generation, or throws saying why this GPU can't run it. */
    static FsrFrameGeneration create(VulkanContext vulkan) throws IOException {
        if (vulkan.fsrPresentQueue == null || vulkan.fsrAcquireQueue == null) {
            throw new IllegalStateException("this GPU has no spare queues (or timeline semaphores) for it");
        }
        return new FsrFrameGeneration(vulkan, Fsr3Backend.library());
    }

    /**
     * Replaces any previous swapchain with a new FidelityFX one for {@code info} (it creates the real swapchain on
     * {@code info.surface} itself) and creates the frame generation context for its size and format. Returns the
     * swapchain handle to use with {@link #getImages}, {@link #acquire} and {@link #present}.
     */
    long createSwapchain(VkSwapchainCreateInfoKHR info) {
        destroySwapchain();
        int width = info.imageExtent().width();
        int height = info.imageExtent().height();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long handle = stack.ncalloc(8, 1, 8);
            long desc = stack.ncalloc(8, 1, SIZE_CREATE_SWAPCHAIN_VK);
            MemoryUtil.memPutLong(desc, CREATE_SWAPCHAIN_VK);
            MemoryUtil.memPutAddress(desc + 16, vulkan.physicalDevice);
            MemoryUtil.memPutAddress(desc + 24, vulkan.device);
            MemoryUtil.memPutAddress(desc + 32, handle);                  // VkSwapchainKHR* to replace (none) / the new one
            MemoryUtil.memCopy(info.address(), desc + 48, VkSwapchainCreateInfoKHR.SIZEOF);
            putQueue(desc + 152, vulkan.queue, vulkan.queueFamily);       // gameQueue: where we present
            // asyncComputeQueue (176) stays empty: the interpolation runs on our queue
            putQueue(desc + 200, vulkan.fsrPresentQueue.handle(), vulkan.fsrPresentQueue.family());
            putQueue(desc + 224, vulkan.fsrAcquireQueue.handle(), vulkan.fsrAcquireQueue.family());
            MemoryUtil.memPutAddress(swapchainContext, MemoryUtil.NULL);
            check(NativeCall.callInt(ffxCreateContext, swapchainContext, desc, MemoryUtil.NULL), "creating its swapchain");
            swapchain = MemoryUtil.memGetLong(handle);

            long query = stack.ncalloc(8, 1, SIZE_QUERY_FUNCTIONS);
            MemoryUtil.memPutLong(query, QUERY_SWAPCHAIN_FUNCTIONS_VK);
            check(NativeCall.callInt(ffxQuery, swapchainContext, query), "querying its swapchain functions");
            getImages = MemoryUtil.memGetAddress(query + 32);
            acquire = MemoryUtil.memGetAddress(query + 40);
            present = MemoryUtil.memGetAddress(query + 48);
            getLastPresentCount = MemoryUtil.memGetAddress(query + 64);

            long backend = stack.ncalloc(8, 1, SIZE_BACKEND_VK);
            MemoryUtil.memPutLong(backend, CREATE_BACKEND_VK);
            MemoryUtil.memPutAddress(backend + 16, vulkan.device);
            MemoryUtil.memPutAddress(backend + 24, vulkan.physicalDevice);
            MemoryUtil.memPutAddress(backend + 32, vulkan.vk.getDeviceProcAddr);
            // The HUD-less copy is RGBA like everything OpenGL hands over; the back buffer may well be BGRA.
            long hudless = stack.ncalloc(8, 1, SIZE_CREATE_HUDLESS);
            MemoryUtil.memPutLong(hudless, CREATE_HUDLESS);
            MemoryUtil.memPutAddress(hudless + 8, backend);
            MemoryUtil.memPutInt(hudless + 16, Fsr3Backend.FORMAT_R8G8B8A8_UNORM);
            long create = stack.ncalloc(8, 1, SIZE_CREATE_FRAME_GENERATION);
            MemoryUtil.memPutLong(create, CREATE_FRAME_GENERATION);
            MemoryUtil.memPutAddress(create + 8, hudless);
            // flags (16) stay 0: depth runs 0 (near) to 1 (far plane), motion vectors are render-size and unjittered.
            MemoryUtil.memPutInt(create + 20, width);                     // displaySize
            MemoryUtil.memPutInt(create + 24, height);
            MemoryUtil.memPutInt(create + 28, width);                     // maxRenderSize
            MemoryUtil.memPutInt(create + 32, height);
            MemoryUtil.memPutInt(create + 36, surfaceFormat(info.imageFormat()));
            MemoryUtil.memPutAddress(frameGenerationContext, MemoryUtil.NULL);
            check(NativeCall.callInt(ffxCreateContext, frameGenerationContext, create, MemoryUtil.NULL),
                    "creating its frame generation context");
            frameGenerationCreated = true;
        }
        enabled = false;
        failedDispatches = 0;
        LOGGER.info("Temporal: FSR 3 frame generation swapchain {}x{} (present queue family {}, acquire queue family {})",
                width, height, vulkan.fsrPresentQueue.family(), vulkan.fsrAcquireQueue.family());
        return swapchain;
    }

    private static void putQueue(long address, long queue, int family) {
        MemoryUtil.memPutAddress(address, queue);
        MemoryUtil.memPutInt(address + 8, family);
        MemoryUtil.memPutAddress(address + 16, MemoryUtil.NULL);          // submitFunc: we never submit concurrently
    }

    private static int surfaceFormat(int vkFormat) {
        return switch (vkFormat) {
            case VK_FORMAT_B8G8R8A8_UNORM -> Fsr3Backend.FORMAT_B8G8R8A8_UNORM;
            case VK_FORMAT_R8G8B8A8_UNORM -> Fsr3Backend.FORMAT_R8G8B8A8_UNORM;
            default -> throw new IllegalStateException("swapchain format " + vkFormat + " is not supported");
        };
    }

    /**
     * Records this frame's part of the present command buffer, with its shared images already acquired from OpenGL,
     * and configures frame generation for the coming present. With {@code frame} (and {@code hudless}) given, a frame
     * is generated before this one: FidelityFX takes its depth and motion vectors now and gets a copy of the HUD-less
     * frame, which the interpolation reads inside the present call. Without, the frame is just shown.
     */
    void record(MemoryStack stack, long commandBuffer, @Nullable Frame frame, @Nullable SharedImage hudless,
                boolean onlyGenerated) {
        frameId++;
        boolean active = frame != null && hudless != null;
        int copy = (int) (frameId % HUDLESS_COPIES);
        if (active) {
            ensureHudlessCopies(hudless.width, hudless.height);
            recordHudlessCopy(stack, commandBuffer, hudless, copy);
            recordPrepare(commandBuffer, frame);
            if (frame.reset()) {
                resetNext = true;
            }
        }
        long c = configure;
        MemoryUtil.memSet(c, 0, SIZE_CONFIGURE);
        MemoryUtil.memPutLong(c, CONFIGURE_FRAME_GENERATION);
        MemoryUtil.memPutAddress(c + 16, swapchain);
        // presentCallback (24) stays empty: FidelityFX copies the frames to the screen itself (no separate UI layer).
        // The dispatch callback stays set while generation is off too: changing it resets FidelityFX's pacing.
        MemoryUtil.memPutAddress(c + 40, dispatchCallback.address());
        MemoryUtil.memPutAddress(c + 48, frameGenerationContext);
        MemoryUtil.memPutByte(c + 56, (byte) (active ? 1 : 0));            // frameGenerationEnabled
        // allowAsyncWorkloads (57) stays false
        if (active) {
            Fsr3Backend.putResource(c + 64, hudlessImages[copy], hudlessWidth, hudlessHeight, Fsr3Backend.FORMAT_R8G8B8A8_UNORM);
        }
        MemoryUtil.memPutByte(c + 116, (byte) (onlyGenerated ? 1 : 0));    // onlyPresentGenerated
        // generationRect (120) stays empty: the whole frame
        MemoryUtil.memPutLong(c + 136, frameId);
        check(NativeCall.callInt(ffxConfigure, frameGenerationContext, c), "configuring frame generation");
        if (active != enabled) {
            LOGGER.info("Temporal: FSR 3 Frame Generation {}{}", active ? "2x" : "off", onlyGenerated ? " (showing generated frames only)" : "");
            enabled = active;
        }
    }

    /** FidelityFX's preparation pass: its dilated copies of depth and motion vectors for this frame, on our queue. */
    private void recordPrepare(long commandBuffer, Frame frame) {
        long now = System.nanoTime();
        float frameTimeMs = lastFrameNanos == 0 ? 16.7F : Math.min(100.0F, (now - lastFrameNanos) / 1.0e6F);
        lastFrameNanos = now;
        float[] sign = Bridge.fsr3JitterSign();
        // This frame's part of the depth and motion images (smaller while dynamic resolution has lowered the scale).
        int renderWidth = frame.renderWidth();
        int renderHeight = frame.renderHeight();

        long p = prepare;
        MemoryUtil.memSet(p, 0, SIZE_PREPARE);
        MemoryUtil.memPutLong(p, DISPATCH_PREPARE);
        MemoryUtil.memPutAddress(p + 8, cameraInfo);
        MemoryUtil.memPutLong(p + 16, frameId);
        // flags (24) stay 0
        MemoryUtil.memPutAddress(p + 32, commandBuffer);
        MemoryUtil.memPutInt(p + 40, renderWidth);                          // renderSize
        MemoryUtil.memPutInt(p + 44, renderHeight);
        MemoryUtil.memPutFloat(p + 48, sign[0] * frame.jitterX());          // jitterOffset, pixels
        MemoryUtil.memPutFloat(p + 52, sign[1] * frame.jitterY());
        MemoryUtil.memPutFloat(p + 56, renderWidth);                        // motionVectorScale: UV -> pixels
        MemoryUtil.memPutFloat(p + 60, renderHeight);
        MemoryUtil.memPutFloat(p + 64, frameTimeMs);
        MemoryUtil.memPutFloat(p + 72, 0.05F);                              // cameraNear
        MemoryUtil.memPutFloat(p + 76, Minecraft.getInstance().gameRenderer.getDepthFar());
        MemoryUtil.memPutFloat(p + 80, (float) (2.0 * Math.atan(1.0 / frame.projection().m11()))); // vertical FOV
        MemoryUtil.memPutFloat(p + 84, 1.0F);                               // viewSpaceToMetersFactor: 1 block = 1 m
        Fsr3Backend.putResource(p + 88, frame.depth(), Fsr3Backend.FORMAT_R32_FLOAT);
        Fsr3Backend.putResource(p + 136, frame.motion(), Fsr3Backend.FORMAT_R16G16_FLOAT);

        // Camera position and orientation in world space (FSR 3.1.4 and later use them for better quality).
        Camera camera = frame.camera();
        long i = cameraInfo;
        MemoryUtil.memSet(i, 0, SIZE_CAMERA_INFO);
        MemoryUtil.memPutLong(i, PREPARE_CAMERA_INFO);
        Vec3 position = camera.getPosition();
        putVector(i + 16, (float) position.x, (float) position.y, (float) position.z);
        Vector3f up = camera.getUpVector();
        putVector(i + 28, up.x(), up.y(), up.z());
        Vector3f left = camera.getLeftVector();
        putVector(i + 40, -left.x(), -left.y(), -left.z());
        Vector3f forward = camera.getLookVector();
        putVector(i + 52, forward.x(), forward.y(), forward.z());
        check(NativeCall.callInt(ffxDispatch, frameGenerationContext, p), "preparing frame generation");
    }

    private static void putVector(long address, float x, float y, float z) {
        MemoryUtil.memPutFloat(address, x);
        MemoryUtil.memPutFloat(address + 4, y);
        MemoryUtil.memPutFloat(address + 8, z);
    }

    /** Copies the shared HUD-less frame into this frame's copy, which stays in GENERAL like the shared images. */
    private void recordHudlessCopy(MemoryStack stack, long commandBuffer, SharedImage hudless, int copy) {
        long target = hudlessImages[copy];
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcAccessMask(hudlessFresh[copy] ? 0 : VK_ACCESS_SHADER_READ_BIT)   // the interpolation that last read it
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(hudlessFresh[copy] ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(target)
                .subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        NativeCall.callInt(vulkan.vk.cmdPipelineBarrier, commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, MemoryUtil.NULL, 0, MemoryUtil.NULL, 1, barrier.address());
        hudlessFresh[copy] = false;

        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        region.get(0).extent().set(hudlessWidth, hudlessHeight, 1);
        NativeCall.callInt(vulkan.vk.cmdCopyImage, commandBuffer, hudless.image, VK_IMAGE_LAYOUT_GENERAL, target,
                VK_IMAGE_LAYOUT_GENERAL, 1, region.address());

        barrier.get(0)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_GENERAL);
        NativeCall.callInt(vulkan.vk.cmdPipelineBarrier, commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, MemoryUtil.NULL, 0, MemoryUtil.NULL, 1, barrier.address());
    }

    private void ensureHudlessCopies(int width, int height) {
        if (hudlessImages[0] != 0 && width == hudlessWidth && height == hudlessHeight) {
            return;
        }
        vulkan.waitIdle();
        destroyHudlessCopies();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApi vk = vulkan.vk;
            for (int i = 0; i < HUDLESS_COPIES; i++) {
                VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(VK_FORMAT_R8G8B8A8_UNORM)
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                info.extent().set(width, height, 1);
                PointerBuffer handle = stack.callocPointer(1);
                VkApi.check(vk.createImage, "vkCreateImage", vulkan.device, info.address(), MemoryUtil.NULL, handle.address());
                hudlessImages[i] = handle.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                NativeCall.callInt(vk.getImageMemoryRequirements, vulkan.device, hudlessImages[i], requirements.address());
                VkApi.check(vk.allocateMemory, "vkAllocateMemory", vulkan.device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(requirements.size())
                        .memoryTypeIndex(vulkan.deviceLocalMemoryType(requirements.memoryTypeBits())).address(),
                        MemoryUtil.NULL, handle.address());
                hudlessMemory[i] = handle.get(0);
                VkApi.check(vk.bindImageMemory, "vkBindImageMemory", vulkan.device, hudlessImages[i], hudlessMemory[i], 0);
                hudlessFresh[i] = true;
            }
        }
        hudlessWidth = width;
        hudlessHeight = height;
    }

    private void destroyHudlessCopies() {
        for (int i = 0; i < HUDLESS_COPIES; i++) {
            if (hudlessImages[i] != 0) {
                NativeCall.callInt(vulkan.vk.destroyImage, vulkan.device, hudlessImages[i], MemoryUtil.NULL);
                NativeCall.callInt(vulkan.vk.freeMemory, vulkan.device, hudlessMemory[i], MemoryUtil.NULL);
                hudlessImages[i] = 0;
                hudlessMemory[i] = 0;
            }
        }
    }

    /** Frames FidelityFX's swapchain has put on screen since it was created, generated ones included. */
    long presentCount() {
        return swapchain == 0 || getLastPresentCount == MemoryUtil.NULL ? 0 : NativeCall.callLong(getLastPresentCount, swapchain);
    }

    /** Interpolation work FidelityFX asked for that failed since the last call. */
    int takeFailedDispatches() {
        int failed = failedDispatches;
        failedDispatches = 0;
        return failed;
    }

    /** Waits until FidelityFX's own threads have presented everything handed to them (before any wait for idle). */
    void waitForPresents() {
        if (swapchain == 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long desc = stack.ncalloc(8, 1, SIZE_HEADER);
            MemoryUtil.memPutLong(desc, DISPATCH_WAIT_FOR_PRESENTS_VK);
            NativeCall.callInt(ffxDispatch, swapchainContext, desc);
        }
    }

    /** Frame generation off, then its context and FidelityFX's swapchain (with the real one) go, in FidelityFX's order. */
    void destroySwapchain() {
        if (frameGenerationCreated) {
            MemoryUtil.memSet(configure, 0, SIZE_CONFIGURE);
            MemoryUtil.memPutLong(configure, CONFIGURE_FRAME_GENERATION);
            MemoryUtil.memPutAddress(configure + 16, swapchain);
            MemoryUtil.memPutLong(configure + 136, ++frameId);
            NativeCall.callInt(ffxConfigure, frameGenerationContext, configure);
            NativeCall.callInt(ffxDestroyContext, frameGenerationContext, MemoryUtil.NULL);
            frameGenerationCreated = false;
        }
        if (swapchain != 0) {
            NativeCall.callInt(ffxDestroyContext, swapchainContext, MemoryUtil.NULL);
            swapchain = 0;
        }
        getImages = MemoryUtil.NULL;
        acquire = MemoryUtil.NULL;
        present = MemoryUtil.NULL;
        getLastPresentCount = MemoryUtil.NULL;
        enabled = false;
    }

    @Override
    public void close() {
        destroySwapchain();
        destroyHudlessCopies();
        dispatchCallback.free();
        MemoryUtil.nmemFree(swapchainContext);
        MemoryUtil.nmemFree(frameGenerationContext);
        MemoryUtil.nmemFree(configure);
        MemoryUtil.nmemFree(prepare);
        MemoryUtil.nmemFree(cameraInfo);
    }

    private static void check(int result, String what) {
        if (result != 0) {
            throw new IllegalStateException("FSR 3 frame generation failed " + what + ": " + Fsr3Backend.resultText(result));
        }
    }

    /**
     * {@code FfxApiFrameGenerationDispatchFunc(ffxDispatchDescFrameGeneration* params, void* userContext)}: FidelityFX
     * asks for the interpolation work from inside the present call, on this (the render) thread, with everything filled
     * in except a reset after a camera cut.
     */
    private final class DispatchCallback extends Callback implements CallbackI {
        private static final FFICIF CIF = APIUtil.apiCreateCIF(LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_uint32,
                LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer);

        DispatchCallback() {
            super(CIF);
        }

        @Override
        public FFICIF getCallInterface() {
            return CIF;
        }

        @Override
        public void callback(long ret, long args) {
            int result;
            try {
                long params = MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(args));
                if (resetNext) {
                    MemoryUtil.memPutByte(params + DISPATCH_RESET, (byte) 1);
                    resetNext = false;
                }
                result = NativeCall.callInt(ffxDispatch, frameGenerationContext, params);
            } catch (Throwable t) {
                LOGGER.error("Temporal: FSR 3 frame generation could not record its interpolation", t);
                result = 1;
            }
            if (result != 0) {
                failedDispatches++;
            }
            // libffi returns integers narrower than a register as a full 64-bit slot.
            MemoryUtil.memPutLong(ret, result & 0xFFFFFFFFL);
        }
    }
}
