package com.temporal.client.bridge;

import com.mojang.logging.LogUtils;
import com.temporal.TemporalConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.slf4j.Logger;

/**
 * AMD FSR 3.1 upscaling through the FidelityFX API (amd_fidelityfx_vk.dll from FidelityFX SDK 1.1.4, the last release
 * with a Vulkan backend). Structure layouts mirror ffx_api.h, ffx_api_types.h, ffx_upscale.h and vk/ffx_api_vk.h for
 * Windows x64.
 */
final class Fsr3Backend implements Backend {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String VERSION = "1.1.4";
    private static final String DLL = "amd_fidelityfx_vk.dll";

    // ffx_api.h / ffx_upscale.h / ffx_api_vk.h
    private static final long DESC_CREATE_BACKEND_VK = 0x00000003L;
    private static final long DESC_CREATE_UPSCALE = 0x00010000L;
    private static final long DESC_DISPATCH_UPSCALE = 0x00010001L;
    private static final int ENABLE_NON_LINEAR_COLORSPACE = 1 << 8;
    private static final int ENABLE_DEBUG_CHECKING = 1 << 7;
    /** Dev switch: FidelityFX's runtime API validation, reported through the message callback. */
    private static final boolean DEBUG = Boolean.getBoolean("temporal.fsr3.debug");
    private static final int FLAG_NON_LINEAR_COLOR_SRGB = 1 << 1;
    private static final int RESOURCE_TYPE_TEXTURE2D = 2;
    private static final int RESOURCE_USAGE_UAV = 1 << 1;
    private static final int RESOURCE_STATE_UNORDERED_ACCESS = 1 << 1;
    static final int FORMAT_R8G8B8A8_UNORM = 10;
    static final int FORMAT_B8G8R8A8_UNORM = 14;
    static final int FORMAT_R16G16_FLOAT = 18;
    static final int FORMAT_R32_FLOAT = 28;

    private static final int SIZE_CREATE_UPSCALE = 48;
    private static final int SIZE_CREATE_BACKEND_VK = 40;
    private static final int SIZE_DISPATCH_UPSCALE = 432;

    private static final String[] RESULT_NAMES = {"OK", "ERROR", "ERROR_UNKNOWN_DESCTYPE", "ERROR_RUNTIME_ERROR",
            "NO_PROVIDER", "ERROR_MEMORY", "ERROR_PARAMETER"};
    private static SharedLibrary library;

    private final VulkanContext vulkan;
    private final float jitterSignX;
    private final float jitterSignY;
    private final long ffxCreateContext;
    private final long ffxDestroyContext;
    private final long ffxDispatch;
    /** Holds the ffxContext handle; the API takes a pointer to it. */
    private final long context = MemoryUtil.nmemCalloc(1, 8);
    private final long createDesc = MemoryUtil.nmemCalloc(1, SIZE_CREATE_UPSCALE);
    private final long backendDesc = MemoryUtil.nmemCalloc(1, SIZE_CREATE_BACKEND_VK);
    private final long dispatchDesc = MemoryUtil.nmemCalloc(1, SIZE_DISPATCH_UPSCALE);
    /** Kept for the life of the backend: FidelityFX holds on to the function pointer. */
    private final FfxMessageCallback messages = new FfxMessageCallback();
    private boolean created;
    private SharedImage createdFor;
    private int createdOutWidth;
    private int createdOutHeight;

    private Fsr3Backend(VulkanContext vulkan, SharedLibrary library, float[] jitterSign) {
        this.vulkan = vulkan;
        this.jitterSignX = jitterSign[0];
        this.jitterSignY = jitterSign[1];
        this.ffxCreateContext = library.getFunctionAddress("ffxCreateContext");
        this.ffxDestroyContext = library.getFunctionAddress("ffxDestroyContext");
        this.ffxDispatch = library.getFunctionAddress("ffxDispatch");
        if (ffxCreateContext == MemoryUtil.NULL || ffxDestroyContext == MemoryUtil.NULL || ffxDispatch == MemoryUtil.NULL) {
            throw new IllegalStateException(DLL + " is missing FidelityFX API entry points");
        }
    }

    /** Binds the FidelityFX runtime to the shared Vulkan device. */
    static Fsr3Backend load(VulkanContext vulkan, float[] jitterSign) throws IOException {
        return new Fsr3Backend(vulkan, library(), jitterSign);
    }

    /** The FidelityFX runtime (upscaling and frame generation), extracted next to the game on first use. */
    static synchronized SharedLibrary library() throws IOException {
        if (library != null) {
            return library;
        }
        Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("temporal").resolve("fidelityfx-" + VERSION)
                .toAbsolutePath();
        Files.createDirectories(directory);
        Path target = directory.resolve(DLL);
        try (InputStream in = Fsr3Backend.class.getResourceAsStream("/natives/fidelityfx/" + DLL)) {
            if (in == null) {
                throw new IOException("missing bundled " + DLL);
            }
            byte[] data = in.readAllBytes();
            if (!Files.exists(target) || Files.size(target) != data.length) {
                Files.write(target, data);
            }
        }
        library = Library.loadNative(Fsr3Backend.class, "org.lwjgl", target.toString());
        LOGGER.info("Temporal: loaded FidelityFX {} from {}", VERSION, target);
        return library;
    }

    static String resultText(int result) {
        return result >= 0 && result < RESULT_NAMES.length ? RESULT_NAMES[result] : "error " + result;
    }

    @Override
    public String displayName() {
        return "FSR 3";
    }

    /**
     * The context is created for a maximum render and output size, so it is recreated whenever the shared images are
     * (any render-size or window change). Quality modes only change the render size, which the images already track.
     */
    @Override
    public void configure(Frame frame) {
        if (created && frame.color() == createdFor && frame.output().width == createdOutWidth
                && frame.output().height == createdOutHeight) {
            return;
        }
        release();

        MemoryUtil.memPutLong(backendDesc, DESC_CREATE_BACKEND_VK);
        MemoryUtil.memPutAddress(backendDesc + 8, MemoryUtil.NULL);
        MemoryUtil.memPutAddress(backendDesc + 16, vulkan.device);
        MemoryUtil.memPutAddress(backendDesc + 24, vulkan.physicalDevice);
        MemoryUtil.memPutAddress(backendDesc + 32, vulkan.vk.getDeviceProcAddr);

        MemoryUtil.memPutLong(createDesc, DESC_CREATE_UPSCALE);
        MemoryUtil.memPutAddress(createDesc + 8, backendDesc);
        // Minecraft's colour is gamma-encoded sRGB; debug checking reports API misuse through the message callback.
        MemoryUtil.memPutInt(createDesc + 16, ENABLE_NON_LINEAR_COLORSPACE | (DEBUG ? ENABLE_DEBUG_CHECKING : 0));
        MemoryUtil.memPutInt(createDesc + 20, frame.color().width);          // maxRenderSize
        MemoryUtil.memPutInt(createDesc + 24, frame.color().height);
        MemoryUtil.memPutInt(createDesc + 28, frame.output().width);         // maxUpscaleSize
        MemoryUtil.memPutInt(createDesc + 32, frame.output().height);
        MemoryUtil.memPutAddress(createDesc + 40, messages.address());       // fpMessage

        MemoryUtil.memPutAddress(context, MemoryUtil.NULL);
        int result = NativeCall.callInt(ffxCreateContext, context, createDesc, MemoryUtil.NULL);
        if (result != 0) {
            throw new IllegalStateException("ffxCreateContext: " + resultName(result));
        }
        created = true;
        createdFor = frame.color();
        createdOutWidth = frame.output().width;
        createdOutHeight = frame.output().height;
        LOGGER.info("Temporal FSR 3: context {}x{} -> {}x{}", frame.color().width, frame.color().height,
                frame.output().width, frame.output().height);
    }

    @Override
    public int record(long commandBuffer, Frame frame) {
        long d = dispatchDesc;
        MemoryUtil.memSet(d, 0, SIZE_DISPATCH_UPSCALE);
        MemoryUtil.memPutLong(d, DESC_DISPATCH_UPSCALE);
        MemoryUtil.memPutAddress(d + 16, commandBuffer);
        putResource(d + 24, frame.color(), FORMAT_R8G8B8A8_UNORM);
        putResource(d + 72, frame.depth(), FORMAT_R32_FLOAT);
        putResource(d + 120, frame.motion(), FORMAT_R16G16_FLOAT);
        // exposure (168) and transparencyAndComposition (264) stay empty; reactive (216) when the mask was made.
        if (frame.reactive() != null) {
            putResource(d + 216, frame.reactive(), FORMAT_R32_FLOAT);
        }
        putResource(d + 312, frame.output(), FORMAT_R8G8B8A8_UNORM);
        MemoryUtil.memPutFloat(d + 360, jitterSignX * frame.jitterX());        // jitterOffset, pixels
        MemoryUtil.memPutFloat(d + 364, jitterSignY * frame.jitterY());
        MemoryUtil.memPutFloat(d + 368, frame.renderWidth());                  // motionVectorScale: UV -> pixels
        MemoryUtil.memPutFloat(d + 372, frame.renderHeight());
        // renderSize: this frame's part of the inputs (the context was made for their full size, the most it takes)
        MemoryUtil.memPutInt(d + 376, frame.renderWidth());
        MemoryUtil.memPutInt(d + 380, frame.renderHeight());
        MemoryUtil.memPutInt(d + 384, frame.output().width);                   // upscaleSize
        MemoryUtil.memPutInt(d + 388, frame.output().height);
        MemoryUtil.memPutByte(d + 392, (byte) (frame.sharpness() > 0.0F ? 1 : 0)); // enableSharpening
        MemoryUtil.memPutFloat(d + 396, frame.sharpness());
        MemoryUtil.memPutFloat(d + 400, frame.frameTimeMs());
        MemoryUtil.memPutFloat(d + 404, 1.0F);                                 // preExposure
        MemoryUtil.memPutByte(d + 408, (byte) (frame.reset() ? 1 : 0));
        MemoryUtil.memPutFloat(d + 412, 0.05F);                                // cameraNear
        MemoryUtil.memPutFloat(d + 416, Minecraft.getInstance().gameRenderer.getDepthFar()); // cameraFar
        MemoryUtil.memPutFloat(d + 420, (float) (2.0 * Math.atan(1.0 / frame.projection().m11()))); // vertical FOV
        MemoryUtil.memPutFloat(d + 424, 1.0F);                                 // viewSpaceToMetersFactor: 1 block = 1 m
        MemoryUtil.memPutInt(d + 428, FLAG_NON_LINEAR_COLOR_SRGB);
        return NativeCall.callInt(ffxDispatch, context, d);
    }

    /** Writes an FfxApiResource for a shared image (48 bytes). */
    static void putResource(long address, SharedImage image, int format) {
        putResource(address, image.image, image.width, image.height, format);
    }

    /**
     * Writes an FfxApiResource: VkImage, description (type, format, size, mips, flags, usage), state. The image must
     * have storage usage and live in VK_IMAGE_LAYOUT_GENERAL, like the shared images: FidelityFX maps UNORDERED_ACCESS
     * to GENERAL and returns every imported resource to this state at the end of a dispatch.
     */
    static void putResource(long address, long image, int width, int height, int format) {
        MemoryUtil.memPutAddress(address, image);
        MemoryUtil.memPutInt(address + 8, RESOURCE_TYPE_TEXTURE2D);
        MemoryUtil.memPutInt(address + 12, format);
        MemoryUtil.memPutInt(address + 16, width);
        MemoryUtil.memPutInt(address + 20, height);
        MemoryUtil.memPutInt(address + 24, 1);                 // depth (array layers)
        MemoryUtil.memPutInt(address + 28, 1);                 // mipCount
        MemoryUtil.memPutInt(address + 32, 0);                 // flags
        MemoryUtil.memPutInt(address + 36, RESOURCE_USAGE_UAV); // created with storage usage
        MemoryUtil.memPutInt(address + 40, RESOURCE_STATE_UNORDERED_ACCESS);
    }

    @Override
    public String resultName(int result) {
        return resultText(result);
    }

    @Override
    public void release() {
        if (!created) {
            return;
        }
        vulkan.waitIdle();
        NativeCall.callInt(ffxDestroyContext, context, MemoryUtil.NULL);
        created = false;
        createdFor = null;
    }
}
