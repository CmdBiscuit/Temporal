package com.temporal.client.bridge;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.slf4j.Logger;

/**
 * Thin binding to NVIDIA Streamline (sl.interposer.dll) built on {@link NativeCall}.
 *
 * <p>Struct layouts mirror the SDK headers for Streamline 2.14.16 on Windows x64 (MSVC); the offsets checked by the
 * SDK's own static_asserts are noted where they apply. Every SL struct starts with a 32-byte {@code BaseStructure}:
 * next pointer, 16-byte GUID, then size_t version.
 */
final class Streamline {
    private static final Logger LOGGER = LogUtils.getLogger();

    static final String VERSION = "2.14.16";
    static final long SDK_VERSION = (2L << 48) | (14L << 32) | (16L << 16) | 0xfedcL;
    private static final String[] SHIPPED_FILES = {"sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "nvngx_dlss.dll",
            "sl.dlss_g.dll", "nvngx_dlssg.dll", "sl.reflex.dll", "sl.pcl.dll", "NvLowLatencyVk.dll"};

    static final int FEATURE_DLSS = 0;
    static final int FEATURE_REFLEX = 3;
    static final int FEATURE_PCL = 4;
    static final int FEATURE_DLSS_G = 1000;
    static final int BUFFER_DEPTH = 0;
    static final int BUFFER_MOTION_VECTORS = 1;
    static final int BUFFER_SCALING_INPUT_COLOR = 3;
    static final int BUFFER_SCALING_OUTPUT_COLOR = 4;
    static final int LIFECYCLE_VALID_UNTIL_EVALUATE = 2;

    static final byte FALSE = 0;
    static final byte TRUE = 1;

    // Preference flags
    private static final long DISABLE_CL_STATE_TRACKING = 1L;
    private static final long DISABLE_DEBUG_TEXT = 1L << 1;
    private static final long USE_MANUAL_HOOKING = 1L << 2;
    private static final long USE_FRAME_BASED_RESOURCE_TAGGING = 1L << 7;
    private static final int RENDER_API_VULKAN = 2;
    private static final int LOG_LEVEL_DEFAULT = 1;

    // Struct GUIDs and sizes
    static final Guid PREFERENCES = new Guid(0x1ca10965, 0xbf8e, 0x432b, 0x8d, 0xa1, 0x67, 0x16, 0xd8, 0x79, 0xfb, 0x14);
    static final Guid VULKAN_INFO = new Guid(0x0eed6fd5, 0x82cd, 0x43a9, 0xbd, 0xb5, 0x47, 0xa5, 0xba, 0x2f, 0x45, 0xd6);
    static final Guid FEATURE_REQUIREMENTS = new Guid(0x66714097, 0xac6d, 0x4bc6, 0x89, 0x15, 0x1e, 0x0f, 0x55, 0xa6, 0xb6, 0x1f);
    static final Guid RESOURCE = new Guid(0x3a9d70cf, 0x2418, 0x4b72, 0x83, 0x91, 0x13, 0xf8, 0x72, 0x1c, 0x72, 0x61);
    static final Guid RESOURCE_TAG = new Guid(0x4c6a5aad, 0xb445, 0x496c, 0x87, 0xff, 0x1a, 0xf3, 0x84, 0x5b, 0xe6, 0x53);
    static final Guid VIEWPORT_HANDLE = new Guid(0x171b6435, 0x9b3c, 0x4fc8, 0x99, 0x94, 0xfb, 0xe5, 0x25, 0x69, 0xaa, 0xa4);
    static final Guid CONSTANTS = new Guid(0xdcd35ad7, 0x4e4a, 0x4bad, 0xa9, 0x0c, 0xe0, 0xc4, 0x9e, 0xb2, 0x3a, 0xfe);
    static final Guid DLSS_OPTIONS = new Guid(0x6ac826e4, 0x4c61, 0x4101, 0xa9, 0x2d, 0x63, 0x8d, 0x42, 0x10, 0x57, 0xb8);
    static final Guid DLSS_OPTIMAL_SETTINGS = new Guid(0xef1d0957, 0xfd58, 0x4df7, 0xb5, 0x04, 0x8b, 0x69, 0xd8, 0xaa, 0x6b, 0x76);
    static final Guid ADAPTER_INFO = new Guid(0x0677315f, 0xa746, 0x4492, 0x9f, 0x42, 0xcb, 0x61, 0x42, 0xc9, 0xc3, 0xd4);
    static final Guid DLSSG_OPTIONS = new Guid(0xfac5f1cb, 0x2dfd, 0x4f36, 0xa1, 0xe6, 0x3a, 0x9e, 0x86, 0x52, 0x56, 0xc5);
    static final Guid DLSSG_STATE = new Guid(0xcc8ac8e1, 0xa179, 0x44f5, 0x97, 0xfa, 0xe7, 0x41, 0x12, 0xf9, 0xbc, 0x61);
    static final Guid REFLEX_OPTIONS = new Guid(0xf03af81a, 0x6d0b, 0x4902, 0xa6, 0x51, 0xc4, 0x96, 0x5e, 0x21, 0x54, 0x34);
    static final Guid REFLEX_STATE = new Guid(0xf0bb5985, 0xdaf9, 0x4728, 0xb2, 0xfd, 0xae, 0x80, 0xa2, 0xbd, 0x79, 0x89);

    static final int SIZE_PREFERENCES = 144;
    static final int SIZE_VULKAN_INFO = 96;
    static final int SIZE_FEATURE_REQUIREMENTS = 184;
    static final int SIZE_RESOURCE = 112;
    static final int SIZE_RESOURCE_TAG = 64;
    static final int SIZE_VIEWPORT_HANDLE = 40;
    static final int SIZE_CONSTANTS = 456;
    static final int SIZE_DLSS_OPTIONS = 88;
    static final int SIZE_DLSS_OPTIMAL_SETTINGS = 64;
    static final int SIZE_ADAPTER_INFO = 56;
    static final int SIZE_DLSSG_OPTIONS = 120;
    static final int SIZE_DLSSG_STATE = 88;
    static final int SIZE_REFLEX_OPTIONS = 48;
    /** ReflexState v2 carries 64 frame reports of each kind; only the leading fields are read. */
    static final int SIZE_REFLEX_STATE = 12848;

    private static final String[] RESULT_NAMES = {
            "eOk", "eErrorIO", "eErrorDriverOutOfDate", "eErrorOSOutOfDate", "eErrorOSDisabledHWS", "eErrorDeviceNotCreated",
            "eErrorNoSupportedAdapterFound", "eErrorAdapterNotSupported", "eErrorNoPlugins", "eErrorVulkanAPI", "eErrorDXGIAPI",
            "eErrorD3DAPI", "eErrorNRDAPI", "eErrorNVAPI", "eErrorReflexAPI", "eErrorNGXFailed", "eErrorJSONParsing",
            "eErrorMissingProxy", "eErrorMissingResourceState", "eErrorInvalidIntegration", "eErrorMissingInputParameter",
            "eErrorNotInitialized", "eErrorComputeFailed", "eErrorInitNotCalled", "eErrorExceptionHandler",
            "eErrorInvalidParameter", "eErrorMissingConstants", "eErrorDuplicatedConstants", "eErrorMissingOrInvalidAPI",
            "eErrorCommonConstantsMissing", "eErrorUnsupportedInterface", "eErrorFeatureMissing", "eErrorFeatureNotSupported",
            "eErrorFeatureMissingHooks", "eErrorFeatureFailedToLoad", "eErrorFeatureWrongPriority",
            "eErrorFeatureMissingDependency", "eErrorFeatureManagerInvalidState", "eErrorInvalidState", "eWarnOutOfVRAM"};

    record Guid(int data1, int data2, int data3, int... data4) {
        void write(long address) {
            MemoryUtil.memPutInt(address, data1);
            MemoryUtil.memPutShort(address + 4, (short) data2);
            MemoryUtil.memPutShort(address + 6, (short) data3);
            for (int i = 0; i < 8; i++) {
                MemoryUtil.memPutByte(address + 8 + i, (byte) data4[i]);
            }
        }
    }

    /** What a feature needs from our Vulkan instance and device. */
    record Requirements(List<String> instanceExtensions, List<String> deviceExtensions, List<String> features12,
                        List<String> features13, int extraGraphicsQueues, int extraComputeQueues, int opticalFlowQueues) {
        /** Used when Streamline isn't loaded (non-NVIDIA GPUs): the device only needs what Temporal itself uses. */
        static final Requirements NONE = new Requirements(List.of(), List.of(), List.of(), List.of(), 0, 0, 0);

        /** What two features need together: every extension and feature of both, and each one's own extra queues. */
        Requirements plus(Requirements other) {
            return new Requirements(union(instanceExtensions, other.instanceExtensions),
                    union(deviceExtensions, other.deviceExtensions), union(features12, other.features12),
                    union(features13, other.features13), extraGraphicsQueues + other.extraGraphicsQueues,
                    extraComputeQueues + other.extraComputeQueues, opticalFlowQueues + other.opticalFlowQueues);
        }

        private static List<String> union(List<String> a, List<String> b) {
            List<String> all = new ArrayList<>(a);
            b.stream().filter(name -> !all.contains(name)).forEach(all::add);
            return all;
        }
    }

    private final Path directory;
    private final SharedLibrary interposer;
    private final long slInit;
    private final long slShutdown;
    private final long slSetVulkanInfo;
    private final long slGetFeatureRequirements;
    private final long slGetFeatureFunction;
    private final long slGetNewFrameToken;
    private final long slSetConstants;
    private final long slEvaluateFeature;
    private final long slFreeResources;
    private final long slIsFeatureSupported;
    private final long slSetTagForFrame;
    /** The interposer's own vkGet*ProcAddr: return Streamline's proxies for the functions it hooks (sl_hooks.h). */
    final long proxyGetInstanceProcAddr;
    final long proxyGetDeviceProcAddr;
    private long slDLSSSetOptions;
    private long slDLSSGetOptimalSettings;
    private long slDLSSGSetOptions;
    private long slDLSSGGetState;
    private long slReflexSetOptions;
    private long slReflexSleep;
    private long slReflexGetState;
    private long slPCLSetMarker;

    private Streamline(Path directory, SharedLibrary interposer) {
        this.directory = directory;
        this.interposer = interposer;
        this.slInit = interposer.getFunctionAddress("slInit");
        this.slShutdown = interposer.getFunctionAddress("slShutdown");
        this.slSetVulkanInfo = interposer.getFunctionAddress("slSetVulkanInfo");
        this.slGetFeatureRequirements = interposer.getFunctionAddress("slGetFeatureRequirements");
        this.slGetFeatureFunction = interposer.getFunctionAddress("slGetFeatureFunction");
        this.slGetNewFrameToken = interposer.getFunctionAddress("slGetNewFrameToken");
        this.slSetConstants = interposer.getFunctionAddress("slSetConstants");
        this.slEvaluateFeature = interposer.getFunctionAddress("slEvaluateFeature");
        this.slFreeResources = interposer.getFunctionAddress("slFreeResources");
        this.slIsFeatureSupported = interposer.getFunctionAddress("slIsFeatureSupported");
        this.slSetTagForFrame = interposer.getFunctionAddress("slSetTagForFrame");
        this.proxyGetInstanceProcAddr = interposer.getFunctionAddress("vkGetInstanceProcAddr");
        this.proxyGetDeviceProcAddr = interposer.getFunctionAddress("vkGetDeviceProcAddr");
    }

    /** Extracts the shipped DLLs next to the game and loads the interposer. */
    static Streamline load(Path gameDirectory) throws IOException {
        Path directory = gameDirectory.resolve("temporal").resolve("streamline-" + VERSION).toAbsolutePath();
        Files.createDirectories(directory);
        for (String file : SHIPPED_FILES) {
            try (InputStream in = Streamline.class.getResourceAsStream("/natives/streamline/" + file)) {
                if (in == null) {
                    throw new IOException("missing bundled " + file);
                }
                Path target = directory.resolve(file);
                byte[] data = in.readAllBytes();
                if (!Files.exists(target) || Files.size(target) != data.length) {
                    Files.write(target, data);
                }
            }
        }
        SharedLibrary interposer = Library.loadNative(Streamline.class, "org.lwjgl", directory.resolve("sl.interposer.dll").toString());
        return new Streamline(directory, interposer);
    }

    Path directory() {
        return directory;
    }

    static String resultName(int result) {
        return result >= 0 && result < RESULT_NAMES.length ? RESULT_NAMES[result] : "result " + result;
    }

    static void header(long address, Guid guid, long version) {
        MemoryUtil.memPutAddress(address, MemoryUtil.NULL);
        guid.write(address + 8);
        MemoryUtil.memPutLong(address + 24, version);
    }

    static long allocate(int size, Guid guid, long version) {
        long address = MemoryUtil.nmemCalloc(1, size);
        header(address, guid, version);
        return address;
    }

    int init() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pluginPath = stack.UTF16(directory.toString());
            ByteBuffer logPath = stack.UTF16(directory.toString());
            PointerBuffer pluginPaths = stack.pointers(MemoryUtil.memAddress(pluginPath));
            // DLSS-G needs Reflex and PCL (PCL loads by default; listing it keeps the dependency explicit).
            var features = stack.ints(FEATURE_DLSS, FEATURE_DLSS_G, FEATURE_REFLEX, FEATURE_PCL);
            ByteBuffer engineVersion = stack.UTF8("1.20.1");
            ByteBuffer projectId = stack.UTF8("6f1c2a9e-3b7d-4e58-9a21-7c4d0e8b5f13");

            long prefs = stack.ncalloc(8, 1, SIZE_PREFERENCES);
            header(prefs, PREFERENCES, 1);
            MemoryUtil.memPutByte(prefs + 32, FALSE);                         // showConsole
            // Dev switch -Dtemporal.sl.verbose=true: Streamline's verbose log level (2) in sl.log.
            MemoryUtil.memPutInt(prefs + 36, Boolean.getBoolean("temporal.sl.verbose") ? 2 : LOG_LEVEL_DEFAULT);
            MemoryUtil.memPutAddress(prefs + 40, pluginPaths.address());      // pathsToPlugins
            MemoryUtil.memPutInt(prefs + 48, 1);                              // numPathsToPlugins
            MemoryUtil.memPutAddress(prefs + 56, MemoryUtil.memAddress(logPath)); // pathToLogsAndData
            // No OTA: Temporal ships a fixed DLSS build and never downloads code at runtime.
            MemoryUtil.memPutLong(prefs + 88, DISABLE_CL_STATE_TRACKING | DISABLE_DEBUG_TEXT | USE_MANUAL_HOOKING
                    | USE_FRAME_BASED_RESOURCE_TAGGING);                      // flags
            MemoryUtil.memPutAddress(prefs + 96, MemoryUtil.memAddress(features)); // featuresToLoad
            MemoryUtil.memPutInt(prefs + 104, features.remaining());          // numFeaturesToLoad
            MemoryUtil.memPutInt(prefs + 108, 0);                             // applicationId
            MemoryUtil.memPutInt(prefs + 112, 0);                             // engine = eCustom
            MemoryUtil.memPutAddress(prefs + 120, MemoryUtil.memAddress(engineVersion));
            MemoryUtil.memPutAddress(prefs + 128, MemoryUtil.memAddress(projectId));
            MemoryUtil.memPutInt(prefs + 136, RENDER_API_VULKAN);             // renderAPI (offset asserted by the SDK)
            return NativeCall.callInt(slInit, prefs, SDK_VERSION);
        }
    }

    Requirements requirements(int feature) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long reqs = stack.ncalloc(8, 1, SIZE_FEATURE_REQUIREMENTS);
            header(reqs, FEATURE_REQUIREMENTS, 2);
            int result = NativeCall.callInt(slGetFeatureRequirements, feature, reqs);
            if (result != 0) {
                throw new IllegalStateException("slGetFeatureRequirements(" + feature + "): " + resultName(result));
            }
            Requirements requirements = new Requirements(
                    strings(MemoryUtil.memGetAddress(reqs + 136), MemoryUtil.memGetInt(reqs + 128)),
                    strings(MemoryUtil.memGetAddress(reqs + 120), MemoryUtil.memGetInt(reqs + 112)),
                    strings(MemoryUtil.memGetAddress(reqs + 152), MemoryUtil.memGetInt(reqs + 144)),
                    strings(MemoryUtil.memGetAddress(reqs + 168), MemoryUtil.memGetInt(reqs + 160)),
                    MemoryUtil.memGetInt(reqs + 108),
                    MemoryUtil.memGetInt(reqs + 104),
                    MemoryUtil.memGetInt(reqs + 176));
            // flags: 1 D3D11, 2 D3D12, 4 Vulkan, 8 V-Sync must be off, 16 hardware GPU scheduling required
            LOGGER.info("Temporal: Streamline feature {} needs {} (flags {}, OS {}.{}.{} / {}.{}.{}, driver {}.{}.{} / {}.{}.{})",
                    feature, requirements, MemoryUtil.memGetInt(reqs + 32),
                    MemoryUtil.memGetInt(reqs + 56), MemoryUtil.memGetInt(reqs + 60), MemoryUtil.memGetInt(reqs + 64),
                    MemoryUtil.memGetInt(reqs + 68), MemoryUtil.memGetInt(reqs + 72), MemoryUtil.memGetInt(reqs + 76),
                    MemoryUtil.memGetInt(reqs + 80), MemoryUtil.memGetInt(reqs + 84), MemoryUtil.memGetInt(reqs + 88),
                    MemoryUtil.memGetInt(reqs + 92), MemoryUtil.memGetInt(reqs + 96), MemoryUtil.memGetInt(reqs + 100));
            return requirements;
        }
    }

    /** slIsFeatureSupported for our Vulkan GPU: 0 (eOk) when the feature can run there, otherwise why not. */
    int isFeatureSupported(int feature, long physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long adapter = stack.ncalloc(8, 1, SIZE_ADAPTER_INFO);
            header(adapter, ADAPTER_INFO, 1);
            MemoryUtil.memPutAddress(adapter + 48, physicalDevice); // vkPhysicalDevice (LUID ignored when set)
            return NativeCall.callInt(slIsFeatureSupported, feature, adapter);
        }
    }

    private static List<String> strings(long array, int count) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count && array != MemoryUtil.NULL; i++) {
            list.add(MemoryUtil.memUTF8(MemoryUtil.memGetAddress(array + 8L * i)));
        }
        return list;
    }

    /** {@code opticalFlowFamily} is the family whose queue 0 DLSS-G may use for native optical flow, or -1. */
    int setVulkanInfo(long device, long instance, long physicalDevice, int computeFamily, int computeIndex,
                      int graphicsFamily, int graphicsIndex, int opticalFlowFamily) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long info = stack.ncalloc(8, 1, SIZE_VULKAN_INFO);
            header(info, VULKAN_INFO, 3);
            MemoryUtil.memPutAddress(info + 32, device);
            MemoryUtil.memPutAddress(info + 40, instance);
            MemoryUtil.memPutAddress(info + 48, physicalDevice);
            MemoryUtil.memPutInt(info + 56, computeIndex);
            MemoryUtil.memPutInt(info + 60, computeFamily);
            MemoryUtil.memPutInt(info + 64, graphicsIndex);
            MemoryUtil.memPutInt(info + 68, graphicsFamily);
            if (opticalFlowFamily >= 0) {
                MemoryUtil.memPutInt(info + 72, 0);                      // opticalFlowQueueIndex
                MemoryUtil.memPutInt(info + 76, opticalFlowFamily);      // opticalFlowQueueFamily
                MemoryUtil.memPutByte(info + 80, TRUE);                  // useNativeOpticalFlowMode (asserted offset)
            }
            int result = NativeCall.callInt(slSetVulkanInfo, info);
            if (result == 0) {
                slDLSSSetOptions = featureFunction(FEATURE_DLSS, "slDLSSSetOptions");
                slDLSSGetOptimalSettings = featureFunction(FEATURE_DLSS, "slDLSSGetOptimalSettings");
            }
            return result;
        }
    }

    /** Looks up DLSS-G, Reflex and PCL entry points (after slSetVulkanInfo); throws if any of them is missing. */
    void loadFrameGeneration() {
        slDLSSGSetOptions = featureFunction(FEATURE_DLSS_G, "slDLSSGSetOptions");
        slDLSSGGetState = featureFunction(FEATURE_DLSS_G, "slDLSSGGetState");
        slReflexSetOptions = featureFunction(FEATURE_REFLEX, "slReflexSetOptions");
        slReflexSleep = featureFunction(FEATURE_REFLEX, "slReflexSleep");
        slReflexGetState = featureFunction(FEATURE_REFLEX, "slReflexGetState");
        slPCLSetMarker = featureFunction(FEATURE_PCL, "slPCLSetMarker");
    }

    private long featureFunction(int feature, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer function = stack.callocPointer(1);
            int result = NativeCall.callInt(slGetFeatureFunction, feature, MemoryUtil.memAddress(stack.UTF8(name)), function.address());
            if (result != 0 || function.get(0) == MemoryUtil.NULL) {
                throw new IllegalStateException(name + ": " + resultName(result));
            }
            return function.get(0);
        }
    }

    /** Returns the FrameToken pointer for a new frame. */
    long newFrameToken() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer token = stack.callocPointer(1);
            int result = NativeCall.callInt(slGetNewFrameToken, token.address(), MemoryUtil.NULL);
            if (result != 0) {
                throw new IllegalStateException("slGetNewFrameToken: " + resultName(result));
            }
            return token.get(0);
        }
    }

    /** Returns the FrameToken pointer for frame {@code index} (our own counter, so markers and constants agree). */
    long frameToken(int index) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer token = stack.callocPointer(1);
            int result = NativeCall.callInt(slGetNewFrameToken, token.address(), MemoryUtil.memAddress(stack.ints(index)));
            if (result != 0) {
                throw new IllegalStateException("slGetNewFrameToken: " + resultName(result));
            }
            return token.get(0);
        }
    }

    int setTagForFrame(long frameToken, long viewport, long tags, int count, long commandBuffer) {
        return NativeCall.callInt(slSetTagForFrame, frameToken, viewport, tags, count, commandBuffer);
    }

    int dlssgSetOptions(long viewport, long options) {
        return NativeCall.callInt(slDLSSGSetOptions, viewport, options);
    }

    int dlssgGetState(long viewport, long state) {
        return NativeCall.callInt(slDLSSGGetState, viewport, state, MemoryUtil.NULL);
    }

    int reflexSetOptions(long options) {
        return NativeCall.callInt(slReflexSetOptions, options);
    }

    int reflexSleep(long frameToken) {
        return NativeCall.callInt(slReflexSleep, frameToken);
    }

    int reflexGetState(long state) {
        return NativeCall.callInt(slReflexGetState, state);
    }

    int pclSetMarker(int marker, long frameToken) {
        return NativeCall.callInt(slPCLSetMarker, marker, frameToken);
    }

    /** Streamline's proxy for a hooked device function (e.g. vkQueuePresentKHR). */
    long deviceProxy(long device, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return NativeCall.callLong(proxyGetDeviceProcAddr, device, MemoryUtil.memAddress(stack.UTF8(name)));
        }
    }

    /** Streamline's proxy for a hooked instance function (e.g. vkCreateWin32SurfaceKHR). */
    long instanceProxy(long instance, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return NativeCall.callLong(proxyGetInstanceProcAddr, instance, MemoryUtil.memAddress(stack.UTF8(name)));
        }
    }

    int setConstants(long constants, long frameToken, long viewport) {
        return NativeCall.callInt(slSetConstants, constants, frameToken, viewport);
    }

    int evaluate(int feature, long frameToken, long inputs, int inputCount, long commandBuffer) {
        return NativeCall.callInt(slEvaluateFeature, feature, frameToken, inputs, inputCount, commandBuffer);
    }

    int dlssSetOptions(long viewport, long options) {
        return NativeCall.callInt(slDLSSSetOptions, viewport, options);
    }

    int dlssGetOptimalSettings(long options, long settings) {
        return NativeCall.callInt(slDLSSGetOptimalSettings, options, settings);
    }

    int freeResources(int feature, long viewport) {
        return NativeCall.callInt(slFreeResources, feature, viewport);
    }

    int shutdown() {
        return NativeCall.callInt(slShutdown);
    }
}
