package com.temporal.client.bridge;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import com.temporal.client.FullscreenPass;
import com.temporal.client.Upscaler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WinBase;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkWin32SurfaceCreateInfoKHR;
import org.slf4j.Logger;

/**
 * Shows Minecraft's finished frames through a Vulkan swapchain on Minecraft's own window instead of OpenGL's
 * SwapBuffers. Frame generation needs this: DLSS-G adds its frames to images presented through Streamline's swapchain
 * proxy, FSR 3 to images presented through FidelityFX's replacement swapchain, whichever is switched on. Otherwise
 * OpenGL presents as usual.
 *
 * <p>Each frame: OpenGL copies the main target (scene + HUD) into a shared image, flipping it top-down; Vulkan
 * acquires a swapchain image, blits the shared image into it and presents it. Three frames can be in flight.
 */
public final class Presenter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SLOTS = 3;
    private static final int VK_SUBOPTIMAL_KHR = 1000001003;
    private static final int VK_ERROR_OUT_OF_DATE_KHR = -1000001004;
    private static final int VK_NOT_READY = 1;
    private static final int WS_CHILD = 0x40000000;
    private static final int WS_VISIBLE = 0x10000000;
    private static final int SS_BLACKRECT = 0x4;
    /** Dev switch: "vulkan" presents through the driver's Vulkan swapchain even without frame generation. */
    public static boolean devVulkan = "vulkan".equals(System.getProperty("temporal.present", ""));
    /**
     * Debug switch: "clear" presents magenta (presentation alone), "source" has Vulkan fill the shared
     * frame with green before copying it (the copy alone), "copy" uses vkCmdCopyImage instead of a blit.
     */
    public static String debugMode = System.getProperty("temporal.present.debug", "");

    /** Whose swapchain shows the frames: the driver's (dev switch), Streamline's (DLSS-G) or FidelityFX's (FSR 3). */
    private enum Mode { DRIVER, STREAMLINE, FSR }

    /** The functions behind the current swapchain. */
    private record SwapchainApi(Mode mode, long createSurface, long destroySurface, long createSwapchain,
                                long destroySwapchain, long getImages, long acquire, long present, long waitIdle) {
        static SwapchainApi driver(VkApi vk) {
            return new SwapchainApi(Mode.DRIVER, vk.createWin32Surface, vk.destroySurface, vk.createSwapchain,
                    vk.destroySwapchain, vk.getSwapchainImages, vk.acquireNextImage, vk.queuePresent, vk.deviceWaitIdle);
        }

        /**
         * FidelityFX's replacements for images, acquire and present (only valid once its swapchain exists); it creates
         * and destroys the swapchain itself ({@link FsrFrameGeneration}), on a surface of the driver's.
         */
        static SwapchainApi fsr(VkApi vk, FsrFrameGeneration fsr) {
            return new SwapchainApi(Mode.FSR, vk.createWin32Surface, vk.destroySurface, MemoryUtil.NULL, MemoryUtil.NULL,
                    fsr.getImages, fsr.acquire, fsr.present, vk.deviceWaitIdle);
        }

        /** Everything Streamline hooks (sl_hooks.h) must go through its proxies so DLSS-G can take presentation over. */
        static SwapchainApi streamline(Streamline sl, VulkanContext vulkan) {
            SwapchainApi api = new SwapchainApi(Mode.STREAMLINE,
                    sl.instanceProxy(vulkan.instance, "vkCreateWin32SurfaceKHR"),
                    sl.instanceProxy(vulkan.instance, "vkDestroySurfaceKHR"),
                    sl.deviceProxy(vulkan.device, "vkCreateSwapchainKHR"),
                    sl.deviceProxy(vulkan.device, "vkDestroySwapchainKHR"),
                    sl.deviceProxy(vulkan.device, "vkGetSwapchainImagesKHR"),
                    sl.deviceProxy(vulkan.device, "vkAcquireNextImageKHR"),
                    sl.deviceProxy(vulkan.device, "vkQueuePresentKHR"),
                    sl.deviceProxy(vulkan.device, "vkDeviceWaitIdle"));
            for (long function : new long[]{api.createSurface, api.destroySurface, api.createSwapchain,
                    api.destroySwapchain, api.getImages, api.acquire, api.present, api.waitIdle}) {
                if (function == MemoryUtil.NULL) {
                    throw new IllegalStateException("Streamline returned no swapchain proxy");
                }
            }
            return api;
        }

        boolean streamline() {
            return mode == Mode.STREAMLINE;
        }
    }

    private static boolean failed;
    /** A Vulkan swapchain has been on the window this session. */
    private static boolean windowUsed;
    /**
     * Frames go to a window of ours laid over Minecraft's (see {@link #takeWindow}), because the driver keeps Minecraft's
     * own for OpenGL; -Dtemporal.present.overlay=true starts that way (dev switch).
     */
    private static boolean overlay = Boolean.getBoolean("temporal.present.overlay");
    private static long overlayWindow;
    /** Whether Vulkan took over presenting as soon as loading finished, decided once then (see {@link #wantsVulkan}). */
    private static boolean early;
    private static boolean earlyDecided;
    @Nullable private static FsrFrameGeneration fsr;
    @Nullable private static SwapchainApi api;
    /** The functions that created {@link #surface}, which can outlive its swapchain. */
    @Nullable private static SwapchainApi surfaceApi;
    private static long surface;
    private static long swapchain;
    private static long[] images = new long[0];
    private static long[] presentSemaphores = new long[0];
    @Nullable private static long[] acquireSemaphores;
    @Nullable private static VulkanContext.CommandSlot[] slots;
    private static int slot;
    private static int width;
    private static int height;
    private static boolean vsync;
    private static boolean recreate;
    @Nullable private static SharedImage frame;
    private static long presented;

    /** The dev switch asks for Vulkan presentation without frame generation (and it hasn't failed). */
    public static boolean isRequested() {
        return !failed && devVulkan;
    }

    /** Frames currently reach the screen through Vulkan rather than OpenGL. */
    public static boolean isPresenting() {
        return swapchain != 0;
    }

    /** Frames shown through Vulkan so far (for debugging). */
    public static long presentedFrames() {
        return presented;
    }

    /** The image last handed to Vulkan, as OpenGL sees it (for debugging); null while OpenGL presents. */
    @Nullable
    public static RenderTarget debugFrame() {
        return frame == null ? null : frame.target;
    }

    /**
     * Replaces {@code glfwSwapBuffers} at the end of {@code RenderSystem.flipFrame}.
     *
     * <p>Once frames have gone through a Vulkan swapchain, they keep going through one for the rest of the session,
     * with frame generation simply off while it's switched off (see {@link #wantedMode}).
     */
    public static void swapBuffers(long window) {
        // Before the first start, wait for the loading screen to finish: resources (shaders) and the window settle.
        if (!failed && (swapchain != 0 || Minecraft.getInstance().getOverlay() == null && wantsVulkan()
                && Bridge.readyForPresent())) {
            // Starting the bridge may just have found one kind of frame generation (or both) unavailable.
            Mode mode = wantedMode();
            if (mode != null) {
                try {
                    present(window, mode);
                    return;
                } catch (Throwable t) {
                    LOGGER.error("Temporal: Vulkan presentation failed - back to OpenGL", t);
                    if (windowUsed) {
                        // OpenGL presents from now on, after which no new Vulkan swapchain can be made (see above). The
                        // player hears why once frame generation is asked for, now or later.
                        failed = true;
                        FrameGeneration.markAllUnavailable("presenting through Vulkan failed (" + t.getMessage()
                                + "); frame generation can run again after restarting Minecraft");
                    } else if (FrameGeneration.wanted()) {
                        FrameGeneration.disable(mode == Mode.FSR ? FrameGeneration.Method.FSR : FrameGeneration.Method.DLSS,
                                "presenting through Vulkan failed", t);
                    }
                    releaseQuietly();
                }
            }
        }
        if (swapchain != 0) {
            releaseQuietly();
        }
        GLFW.glfwSwapBuffers(window);
    }

    /**
     * Whose swapchain should show this frame; null for OpenGL. Once frames go through Vulkan they keep doing so for the
     * session, frame generation or not (switching only whose swapchain it is), because a new Vulkan swapchain on a
     * window OpenGL has presented to since the last one fails: FidelityFX reports an error and Streamline crashes
     * (cloneFakeBuffers), whether or not DLSS-G was freed or unloaded first.
     */
    @Nullable
    private static Mode wantedMode() {
        FrameGeneration.Method method = FrameGeneration.wanted() ? FrameGeneration.method() : null;
        if (method == FrameGeneration.Method.FSR) {
            return Mode.FSR;
        }
        if (method == FrameGeneration.Method.DLSS && Bridge.streamline() != null) {
            return Mode.STREAMLINE;
        }
        if (swapchain != 0) {
            return api.mode();
        }
        return devVulkan || early ? Mode.DRIVER : null;
    }

    /**
     * Vulkan should present: for frame generation, or from the moment loading finishes when DLSS or FSR 3 upscaling is
     * chosen then. That early start keeps frames on Minecraft's own window when frame generation is switched on later:
     * once OpenGL has drawn the window at a new size (the player maximised it, say), NVIDIA's driver keeps it for OpenGL
     * for the rest of the session (see {@link #takeWindow}), while resizing a window Vulkan already presents to is fine.
     */
    private static boolean wantsVulkan() {
        if (!earlyDecided) {
            earlyDecided = true;
            early = Bridge.isRequested();
        }
        return FrameGeneration.wanted() || devVulkan || early;
    }

    private static void present(long window, Mode mode) {
        VulkanContext vulkan = Bridge.vulkan();
        VkApi vk = vulkan.vk;
        Streamline streamline = Bridge.streamline();
        RenderTarget main = Upscaler.mainTarget();
        if (main.width < 2 || main.height < 2) {
            return; // minimised: nothing to show, and no swapchain can be this small
        }
        if (swapchain == 0) {
            takeWindow(window, main.width, main.height);
        }
        if (swapchain != 0 && api.mode() != mode) {
            // Switching between the driver's, Streamline's and FidelityFX's swapchains: the new one is made in this same
            // frame, before OpenGL could present (see wantedMode).
            releaseQuietly();
        }
        // DLSS-G on Vulkan can't use V-Sync: it presents immediately while it's switched on. FSR 3 paces with it.
        boolean wantVsync = !(mode == Mode.STREAMLINE && FrameGeneration.wanted()) && Minecraft.getInstance().options.enableVsync().get();
        if (swapchain == 0 || recreate || main.width != width || main.height != height || wantVsync != vsync) {
            createSwapchain(window, main.width, main.height, wantVsync, mode);
        }

        FrameGeneration.Method method = switch (mode) {
            case STREAMLINE -> FrameGeneration.Method.DLSS;
            case FSR -> FrameGeneration.Method.FSR;
            case DRIVER -> null;
        };
        SharedImage[] inputs = method != null ? FrameGeneration.prepareForPresent(method, width, height) : new SharedImage[0];
        SharedImage[] handed = new SharedImage[1 + inputs.length];
        handed[0] = frame;
        System.arraycopy(inputs, 0, handed, 1, inputs.length);

        FullscreenPass.blitFlipped(main, frame.target, GL11.GL_NEAREST);
        Bridge.glSignalToVulkan(handed);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int s = slot;
            slot = (slot + 1) % SLOTS;
            VulkanContext.CommandSlot commands = slots[s];
            vulkan.beginCommands(stack, commands);
            long commandBuffer = commands.commandBuffer();

            // Streamline and FidelityFX read the inputs with whatever they like (transfer or compute): hand them over broadly.
            int stage = method != null ? VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT;
            int access = method != null ? VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT
                    : VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            Bridge.recordOwnership(stack, commandBuffer, true, stage, access, handed);
            if (mode == Mode.STREAMLINE) {
                FrameGeneration.recordTags(streamline, commandBuffer);
            } else if (mode == Mode.FSR) {
                FrameGeneration.recordFsr(stack, commandBuffer, fsr);
            }

            IntBuffer index = stack.callocInt(1);
            int result = NativeCall.callInt(api.acquire(), vulkan.device, swapchain, -1L, acquireSemaphores[s],
                    MemoryUtil.NULL, MemoryUtil.memAddress(index));
            boolean acquired = result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR;
            if (result == VK_SUBOPTIMAL_KHR || result == VK_ERROR_OUT_OF_DATE_KHR) {
                recreate = true;
            } else if (!acquired && !(mode == Mode.FSR && result == VK_NOT_READY)) {
                // (FidelityFX answers NOT_READY while all its images wait to be shown: this frame is skipped.)
                throw new IllegalStateException("vkAcquireNextImageKHR failed: VkResult " + result);
            }
            int image = index.get(0);
            if (acquired) {
                // FidelityFX takes its images back in SHADER_READ_ONLY_OPTIMAL, the layout it composes from.
                recordCopy(stack, vk, commandBuffer, images[image],
                        mode == Mode.FSR ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            }
            Bridge.recordOwnership(stack, commandBuffer, false, stage, access, handed);
            VkApi.check(vk.endCommandBuffer, "vkEndCommandBuffer", commandBuffer);

            // Always consume OpenGL's signal and answer it, even when this frame can't be shown.
            LongBuffer waits = acquired ? stack.longs(Bridge.glToVulkanSemaphore(), acquireSemaphores[s])
                    : stack.longs(Bridge.glToVulkanSemaphore());
            IntBuffer stages = acquired ? stack.ints(stage, VK_PIPELINE_STAGE_TRANSFER_BIT) : stack.ints(stage);
            LongBuffer signals = acquired ? stack.longs(presentSemaphores[image], Bridge.vulkanToGlSemaphore())
                    : stack.longs(Bridge.vulkanToGlSemaphore());
            VkApi.check(vk.queueSubmit, "vkQueueSubmit (present)", vulkan.queue, 1, VkSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreCount(waits.remaining())
                    .pWaitSemaphores(waits)
                    .pWaitDstStageMask(stages)
                    .pCommandBuffers(stack.pointers(commandBuffer))
                    .pSignalSemaphores(signals).address(), commands.fence());

            if (mode == Mode.STREAMLINE) {
                FrameGeneration.presentStart(streamline);
            }
            if (acquired) {
                VkPresentInfoKHR info = VkPresentInfoKHR.calloc(stack).sType$Default()
                        .pWaitSemaphores(stack.longs(presentSemaphores[image]))
                        .swapchainCount(1)
                        .pSwapchains(stack.longs(swapchain))
                        .pImageIndices(stack.ints(image));
                result = NativeCall.callInt(api.present(), vulkan.queue, info.address());
                if (result == VK_SUBOPTIMAL_KHR || result == VK_ERROR_OUT_OF_DATE_KHR) {
                    recreate = true;
                } else if (result != VK_SUCCESS) {
                    throw new IllegalStateException("vkQueuePresentKHR failed: VkResult " + result);
                }
                presented++;
            }
            if (mode == Mode.STREAMLINE) {
                FrameGeneration.presentEnd(streamline);
            }
        }
        Bridge.glWaitForVulkan(handed);
        if (mode == Mode.STREAMLINE) {
            FrameGeneration.afterPresent(streamline);
        } else if (mode == Mode.FSR) {
            FrameGeneration.afterFsrPresent(fsr);
        }
    }

    /** Swapchain image: undefined -> transfer destination, blit the shared frame in, -> {@code finalLayout}. */
    private static void recordCopy(MemoryStack stack, VkApi vk, long commandBuffer, long target, int finalLayout) {
        VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(1, stack);
        toTransfer.get(0).sType$Default()
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(target)
                .subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        NativeCall.callInt(vk.cmdPipelineBarrier, commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, 0, MemoryUtil.NULL, 0, MemoryUtil.NULL, 1, toTransfer.address());

        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        if (debugMode.equals("source")) {
            VkClearColorValue green = VkClearColorValue.calloc(stack).float32(0, 0.0F).float32(1, 1.0F).float32(2, 0.0F).float32(3, 1.0F);
            NativeCall.callInt(vk.cmdClearColorImage, commandBuffer, frame.image, VK_IMAGE_LAYOUT_GENERAL,
                    green.address(), 1, range.address());
            VkMemoryBarrier.Buffer cleared = VkMemoryBarrier.calloc(1, stack);
            cleared.get(0).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            NativeCall.callInt(vk.cmdPipelineBarrier, commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, 1, cleared.address(), 0, MemoryUtil.NULL, 0, MemoryUtil.NULL);
        }
        if (debugMode.equals("clear") || debugMode.equals("parity")) {
            // "parity" alternates magenta and green, so anything in between on screen must be a generated frame.
            boolean green = debugMode.equals("parity") && presented % 2 == 1;
            VkClearColorValue color = VkClearColorValue.calloc(stack).float32(0, green ? 0.0F : 1.0F).float32(1, green ? 1.0F : 0.0F)
                    .float32(2, green ? 0.0F : 1.0F).float32(3, 1.0F);
            NativeCall.callInt(vk.cmdClearColorImage, commandBuffer, target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    color.address(), 1, range.address());
        } else if (debugMode.equals("copy")) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).extent().set(width, height, 1);
            NativeCall.callInt(vk.cmdCopyImage, commandBuffer, frame.image, VK_IMAGE_LAYOUT_GENERAL, target,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region.address());
        } else {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).srcOffsets(1).set(width, height, 1);
            region.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).dstOffsets(1).set(width, height, 1);
            NativeCall.callInt(vk.cmdBlitImage, commandBuffer, frame.image, VK_IMAGE_LAYOUT_GENERAL, target,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region.address(), VK_FILTER_NEAREST);
        }

        VkImageMemoryBarrier.Buffer toPresent = VkImageMemoryBarrier.calloc(1, stack);
        toPresent.get(0).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(finalLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(target)
                .subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        NativeCall.callInt(vk.cmdPipelineBarrier, commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, MemoryUtil.NULL, 0, MemoryUtil.NULL, 1, toPresent.address());
    }

    /**
     * The first Vulkan swapchain of a session is always the driver's own, because Streamline doesn't check whether the
     * driver gave it one and crashes the game when it didn't. The driver can keep Minecraft's window for OpenGL
     * (VK_ERROR_NATIVE_WINDOW_IN_USE_KHR): AMD's always does, NVIDIA's once OpenGL has drawn it at a new size since the
     * Vulkan side started (in real launcher installs). Frames then go to a window of ours laid over it instead.
     */
    private static void takeWindow(long window, int width, int height) {
        try {
            createSwapchain(window, width, height, false, Mode.DRIVER);
        } catch (VkApi.Failure failure) {
            if (overlay || failure.result != KHRSurface.VK_ERROR_NATIVE_WINDOW_IN_USE_KHR) {
                throw failure;
            }
            LOGGER.info("Temporal: the graphics driver keeps the game window for OpenGL; presenting on a window laid over it");
            releaseQuietly();
            overlay = true;
            createSwapchain(window, width, height, false, Mode.DRIVER);
        }
    }

    /**
     * The window frames go to: Minecraft's, or the overlay over its whole client area (made here, or fitted to the new
     * size). The overlay is a plain STATIC child: OpenGL has never drawn it, it answers hit tests with HTTRANSPARENT so
     * mouse input still reaches Minecraft's window underneath, and it never takes keyboard focus.
     */
    private static long presentationWindow(long window, int width, int height) {
        long gameWindow = GLFWNativeWin32.glfwGetWin32Window(window);
        if (!overlay) {
            return gameWindow;
        }
        if (overlayWindow == 0) {
            overlayWindow = User32.CreateWindowEx(0, "STATIC", "", WS_CHILD | WS_VISIBLE | SS_BLACKRECT, 0, 0, width, height,
                    gameWindow, 0, WinBase.GetModuleHandle((CharSequence) null), 0);
            if (overlayWindow == 0) {
                throw new IllegalStateException("could not create a window over the game window");
            }
        } else {
            User32.SetWindowPos(overlayWindow, 0, 0, 0, width, height, User32.SWP_NOZORDER | User32.SWP_NOACTIVATE);
        }
        return overlayWindow;
    }

    private static void createSwapchain(long window, int newWidth, int newHeight, boolean newVsync, Mode mode) {
        VulkanContext vulkan = Bridge.vulkan();
        VkApi vk = vulkan.vk;
        if (swapchain == 0) {
            switch (mode) {
                case STREAMLINE -> {
                    FrameGeneration.start(Bridge.streamline()); // loads DLSS-G first if it was unloaded
                    api = SwapchainApi.streamline(Bridge.streamline(), vulkan);
                }
                case FSR -> {
                    if (fsr == null) {
                        try {
                            fsr = FsrFrameGeneration.create(vulkan);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    FrameGeneration.startFsr();
                    api = SwapchainApi.fsr(vk, fsr);
                    // FidelityFX presents from threads of its own: let them finish before anything waits for idle.
                    vulkan.beforeWaitIdle = fsr::waitForPresents;
                }
                case DRIVER -> api = SwapchainApi.driver(vk);
            }
            vulkan.waitIdleFunction = api.waitIdle();
        } else if (mode == Mode.STREAMLINE) {
            // DLSS-G must be off around window changes.
            FrameGeneration.suspend(Bridge.streamline());
        } else if (mode == Mode.FSR) {
            FrameGeneration.suspendFsr();
        }
        vulkan.waitIdle();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer handle = stack.callocPointer(1);
            if (surface != 0 && surfaceApi.streamline() != api.streamline()) {
                NativeCall.callInt(surfaceApi.destroySurface(), vulkan.instance, surface, MemoryUtil.NULL);
                surface = 0;
            }
            long hwnd = presentationWindow(window, newWidth, newHeight);
            if (surface == 0) {
                VkApi.check(api.createSurface(), "vkCreateWin32SurfaceKHR", vulkan.instance,
                        VkWin32SurfaceCreateInfoKHR.calloc(stack).sType$Default()
                                .hinstance(WinBase.GetModuleHandle((CharSequence) null)).hwnd(hwnd).address(),
                        MemoryUtil.NULL, handle.address());
                surface = handle.get(0);
                surfaceApi = api;
            }
            IntBuffer supported = stack.callocInt(1);
            VkApi.check(vk.getPhysicalDeviceSurfaceSupport, "vkGetPhysicalDeviceSurfaceSupportKHR", vulkan.physicalDevice,
                    vulkan.queueFamily, surface, MemoryUtil.memAddress(supported));
            if (supported.get(0) == 0) {
                throw new IllegalStateException("the GPU's graphics queue cannot present to this window");
            }

            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
            VkApi.check(vk.getPhysicalDeviceSurfaceCapabilities, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR",
                    vulkan.physicalDevice, surface, capabilities.address());
            if ((capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
                throw new IllegalStateException("swapchain images can't be copied into");
            }

            IntBuffer count = stack.callocInt(1);
            VkApi.check(vk.getPhysicalDeviceSurfaceFormats, "vkGetPhysicalDeviceSurfaceFormatsKHR", vulkan.physicalDevice,
                    surface, MemoryUtil.memAddress(count), MemoryUtil.NULL);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
            VkApi.check(vk.getPhysicalDeviceSurfaceFormats, "vkGetPhysicalDeviceSurfaceFormatsKHR", vulkan.physicalDevice,
                    surface, MemoryUtil.memAddress(count), formats.address());
            // Minecraft's colour values are already gamma-encoded, so present them through a UNORM format untouched,
            // exactly like OpenGL's default framebuffer.
            VkSurfaceFormatKHR format = formats.get(0);
            for (VkSurfaceFormatKHR candidate : formats) {
                if (candidate.format() == VK_FORMAT_B8G8R8A8_UNORM || candidate.format() == VK_FORMAT_R8G8B8A8_UNORM) {
                    format = candidate;
                    break;
                }
            }

            VkApi.check(vk.getPhysicalDeviceSurfacePresentModes, "vkGetPhysicalDeviceSurfacePresentModesKHR",
                    vulkan.physicalDevice, surface, MemoryUtil.memAddress(count), MemoryUtil.NULL);
            IntBuffer modes = stack.callocInt(count.get(0));
            VkApi.check(vk.getPhysicalDeviceSurfacePresentModes, "vkGetPhysicalDeviceSurfacePresentModesKHR",
                    vulkan.physicalDevice, surface, MemoryUtil.memAddress(count), MemoryUtil.memAddress(modes));
            int presentMode = KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
            if (!newVsync) {
                for (int preferred : new int[]{KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR, KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR}) {
                    if (contains(modes, preferred)) {
                        presentMode = preferred;
                        break;
                    }
                }
            }

            int imageCount = Math.max(capabilities.minImageCount(), 3);
            if (capabilities.maxImageCount() > 0) {
                imageCount = Math.min(imageCount, capabilities.maxImageCount());
            }
            int compositeAlpha = (capabilities.supportedCompositeAlpha() & KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) != 0
                    ? KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                    : Integer.lowestOneBit(capabilities.supportedCompositeAlpha());
            long old = swapchain;
            if (api.streamline() && old != 0) {
                // Streamline keeps its own swapchain behind the proxy: replace ours outright rather than handing over.
                NativeCall.callInt(api.destroySwapchain(), vulkan.device, old, MemoryUtil.NULL);
                swapchain = 0;
                old = 0;
            }
            if (mode == Mode.FSR) {
                old = 0; // FidelityFX replaces its whole swapchain (see below)
            }
            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(format.format())
                    .imageColorSpace(format.colorSpace())
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(capabilities.currentTransform())
                    .compositeAlpha(compositeAlpha)
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(old);
            info.imageExtent().set(newWidth, newHeight);
            windowUsed = true;
            if (mode == Mode.FSR) {
                // Destroys the previous one (and its frame generation context) first; the replacement functions are new.
                swapchain = fsr.createSwapchain(info);
                api = SwapchainApi.fsr(vk, fsr);
            } else {
                VkApi.check(api.createSwapchain(), "vkCreateSwapchainKHR", vulkan.device, info.address(), MemoryUtil.NULL, handle.address());
                swapchain = handle.get(0);
                if (old != 0) {
                    NativeCall.callInt(api.destroySwapchain(), vulkan.device, old, MemoryUtil.NULL);
                }
            }

            VkApi.check(api.getImages(), "vkGetSwapchainImagesKHR", vulkan.device, swapchain,
                    MemoryUtil.memAddress(count), MemoryUtil.NULL);
            LongBuffer imageHandles = stack.callocLong(count.get(0));
            VkApi.check(api.getImages(), "vkGetSwapchainImagesKHR", vulkan.device, swapchain,
                    MemoryUtil.memAddress(count), MemoryUtil.memAddress(imageHandles));
            images = new long[count.get(0)];
            imageHandles.get(0, images);

            destroySemaphores(presentSemaphores);
            presentSemaphores = new long[images.length];
            for (int i = 0; i < images.length; i++) {
                presentSemaphores[i] = createSemaphore(stack, vulkan);
            }
            if (acquireSemaphores == null) {
                acquireSemaphores = new long[SLOTS];
                slots = new VulkanContext.CommandSlot[SLOTS];
                for (int i = 0; i < SLOTS; i++) {
                    acquireSemaphores[i] = createSemaphore(stack, vulkan);
                    slots[i] = vulkan.newCommandSlot();
                }
            }
            if (frame == null || frame.width != newWidth || frame.height != newHeight) {
                if (frame != null) {
                    frame.close();
                }
                frame = new SharedImage(vulkan, newWidth, newHeight, VK_FORMAT_R8G8B8A8_UNORM, GL11.GL_RGBA8);
                Bridge.transitionToGeneral(frame);
            }
            width = newWidth;
            height = newHeight;
            vsync = newVsync;
            recreate = false;
            LOGGER.info("Temporal: presenting through {} {}x{}, {} images, format {}, present mode {}",
                    switch (mode) {
                        case STREAMLINE -> "Streamline (DLSS frame generation)";
                        case FSR -> "FidelityFX (FSR 3 frame generation)";
                        case DRIVER -> "Vulkan";
                    }, newWidth, newHeight, images.length, format.format(), presentMode);
        }
    }

    private static boolean contains(IntBuffer values, int value) {
        for (int i = 0; i < values.capacity(); i++) {
            if (values.get(i) == value) {
                return true;
            }
        }
        return false;
    }

    private static long createSemaphore(MemoryStack stack, VulkanContext vulkan) {
        PointerBuffer handle = stack.callocPointer(1);
        VkApi.check(vulkan.vk.createSemaphore, "vkCreateSemaphore", vulkan.device,
                VkSemaphoreCreateInfo.calloc(stack).sType$Default().address(), MemoryUtil.NULL, handle.address());
        return handle.get(0);
    }

    private static void destroySemaphores(long[] semaphores) {
        VulkanContext vulkan = Bridge.vulkan();
        for (long semaphore : semaphores) {
            NativeCall.callInt(vulkan.vk.destroySemaphore, vulkan.device, semaphore, MemoryUtil.NULL);
        }
    }

    /** The game is closing: stop presenting through Vulkan while the window and GL context still exist. */
    public static void shutdown() {
        releaseQuietly();
    }

    /** Hands the window back to OpenGL (and stops frame generation if it was presenting). */
    private static void releaseQuietly() {
        VulkanContext vulkan = Bridge.vulkan();
        try {
            if (swapchain != 0) {
                vulkan.waitIdle();
                if (api.streamline()) {
                    FrameGeneration.stop(Bridge.streamline());
                } else if (api.mode() == Mode.FSR) {
                    FrameGeneration.stopFsr();
                }
                destroySemaphores(presentSemaphores);
                presentSemaphores = new long[0];
                if (api.mode() == Mode.FSR) {
                    fsr.destroySwapchain();
                } else {
                    NativeCall.callInt(api.destroySwapchain(), vulkan.device, swapchain, MemoryUtil.NULL);
                }
                if (frame != null) {
                    frame.close();
                }
                LOGGER.info("Temporal: Vulkan swapchain released");
            }
            if (surface != 0) {
                NativeCall.callInt(surfaceApi.destroySurface(), vulkan.instance, surface, MemoryUtil.NULL);
            }
        } catch (Throwable t) {
            LOGGER.error("Temporal: could not release the Vulkan swapchain", t);
        } finally {
            if (fsr != null) {
                try {
                    fsr.close();
                } catch (Throwable t) {
                    LOGGER.error("Temporal: could not release FSR 3 frame generation", t);
                }
                fsr = null;
            }
            if (overlayWindow != 0) {
                // Uncovers Minecraft's window for whatever presents next (made again if Vulkan does).
                User32.DestroyWindow(overlayWindow);
                overlayWindow = 0;
            }
            swapchain = 0;
            surface = 0;
            surfaceApi = null;
            images = new long[0];
            frame = null;
            api = null;
            if (vulkan != null) {
                vulkan.waitIdleFunction = vulkan.vk.deviceWaitIdle;
                vulkan.beforeWaitIdle = null;
            }
        }
    }

    private Presenter() {}
}
