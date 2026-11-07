package com.temporal.client.bridge;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

import com.mojang.logging.LogUtils;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.KHRExternalSemaphoreWin32;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.slf4j.Logger;

/**
 * The Vulkan device behind the bridge: it runs DLSS, FSR 3 and frame generation and presents to Minecraft's window.
 * It lives on the same GPU as Minecraft's OpenGL context (matched by device UUID) so images can be shared between the
 * two APIs without going through the CPU. Handles are raw native values; calls go through {@link VkApi}.
 */
final class VulkanContext implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    // VK_NV_optical_flow (written by hand: not every LWJGL build has the struct classes)
    private static final int VK_QUEUE_OPTICAL_FLOW_BIT_NV = 0x100;
    private static final int STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV = 1000464000;
    private static final int VK_QUEUE_VIDEO_DECODE_BIT_KHR = 0x20;
    private static final int VK_QUEUE_VIDEO_ENCODE_BIT_KHR = 0x40;

    final VkApi vk;
    final long instance;
    final long physicalDevice;
    final long device;
    final long queue;
    final int queueFamily;
    final int computeFamily;
    final int computeQueueStart;
    final int graphicsQueueStart;
    /** Family of DLSS-G's native optical-flow queue (its queue 0), or -1 without one. */
    final int opticalFlowFamily;
    final String deviceName;
    final long commandPool;
    final long commandBuffer;
    final long fence;
    /** VK_KHR_swapchain and the Win32 surface extensions are enabled. */
    boolean canPresent;
    /**
     * Queues reserved for FSR 3 frame generation's swapchain: one that can present to a window and one for its image
     * acquires. Null when this GPU has no spare queues for it (or no timeline semaphores).
     */
    @Nullable Queue fsrPresentQueue;
    @Nullable Queue fsrAcquireQueue;
    /**
     * vkDeviceWaitIdle to use: Streamline's proxy while it presents for us (its present thread works on queues of this
     * device, so only it can wait for everything safely), otherwise the driver's.
     */
    long waitIdleFunction;
    /**
     * Run before every wait for idle: FSR 3 frame generation's swapchain presents from threads of its own, which must
     * have finished before anything waits on all queues.
     */
    @Nullable Runnable beforeWaitIdle;

    /** A command buffer with its own fence, for work that runs independently of the upscaler's command buffer. */
    record CommandSlot(long commandBuffer, long fence) {}

    record Queue(long handle, int family) {}

    private VulkanContext(VkApi vk, long instance, long physicalDevice, long device, int queueFamily, int computeFamily,
                          int computeQueueStart, int opticalFlowFamily, String deviceName) {
        this.vk = vk;
        this.instance = instance;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.queueFamily = queueFamily;
        this.computeFamily = computeFamily;
        this.computeQueueStart = computeQueueStart;
        this.graphicsQueueStart = 1;
        this.opticalFlowFamily = opticalFlowFamily;
        this.deviceName = deviceName;
        this.waitIdleFunction = vk.deviceWaitIdle;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer handle = stack.callocPointer(1);
            NativeCall.callInt(vk.getDeviceQueue, device, queueFamily, 0, handle.address());
            this.queue = handle.get(0);

            VkApi.check(vk.createCommandPool, "vkCreateCommandPool", device, VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamily).address(), MemoryUtil.NULL, handle.address());
            this.commandPool = handle.get(0);

            VkApi.check(vk.allocateCommandBuffers, "vkAllocateCommandBuffers", device, VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1).address(), handle.address());
            this.commandBuffer = handle.get(0);

            VkApi.check(vk.createFence, "vkCreateFence", device, VkFenceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT).address(), MemoryUtil.NULL, handle.address());
            this.fence = handle.get(0);
        }
    }

    static VulkanContext create(byte[] glDeviceUuid, Streamline.Requirements requirements) {
        VkApi vk = new VkApi();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Set<String> instanceExtensions = new LinkedHashSet<>(requirements.instanceExtensions());
            // The loader hands out non-null debug-utils entry points even when the extension is off, and calling them
            // jumps to address 0. FidelityFX names its resources through them whenever they look available, so turn
            // the extension on (it also gives Streamline/NGX readable names in GPU tools).
            Set<String> availableInstance = availableInstanceExtensions(stack, vk);
            if (availableInstance.contains("VK_EXT_debug_utils")) {
                instanceExtensions.add("VK_EXT_debug_utils");
            }
            // Presenting to Minecraft's window (needed for frame generation).
            boolean surfaces = availableInstance.contains("VK_KHR_surface") && availableInstance.contains("VK_KHR_win32_surface");
            if (surfaces) {
                instanceExtensions.add("VK_KHR_surface");
                instanceExtensions.add("VK_KHR_win32_surface");
            }
            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(VkApplicationInfo.calloc(stack)
                            .sType$Default()
                            .pApplicationName(stack.UTF8("Temporal"))
                            .pEngineName(stack.UTF8("Minecraft"))
                            .apiVersion(VK_API_VERSION_1_3))
                    .ppEnabledExtensionNames(names(stack, instanceExtensions));
            PointerBuffer handle = stack.callocPointer(1);
            VkApi.check(vk.createInstance, "vkCreateInstance", instanceInfo.address(), MemoryUtil.NULL, handle.address());
            long instance = handle.get(0);
            vk.loadInstance(instance);
            if (surfaces) {
                vk.loadSurface(instance);
            }

            long physical = pickPhysicalDevice(stack, vk, instance, glDeviceUuid);
            VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            NativeCall.callInt(vk.getPhysicalDeviceProperties2, physical, properties.address());
            String name = properties.properties().deviceNameString();

            // Queue layout: our queue is index 0 of the graphics family; Streamline's extra queues follow it.
            VkQueueFamilyProperties.Buffer families = queueFamilies(stack, vk, physical);
            int graphicsFamily = -1;
            int computeFamily = -1;
            for (int i = 0; i < families.capacity(); i++) {
                int flags = families.get(i).queueFlags();
                if (graphicsFamily < 0 && (flags & VK_QUEUE_GRAPHICS_BIT) != 0 && (flags & VK_QUEUE_COMPUTE_BIT) != 0) {
                    graphicsFamily = i;
                }
                if (computeFamily < 0 && (flags & VK_QUEUE_COMPUTE_BIT) != 0 && (flags & VK_QUEUE_GRAPHICS_BIT) == 0) {
                    computeFamily = i;
                }
            }
            if (graphicsFamily < 0) {
                throw new IllegalStateException("no graphics queue family");
            }
            int graphicsCount = 1 + requirements.extraGraphicsQueues();
            int computeQueueStart;
            boolean separateCompute = computeFamily >= 0 && requirements.extraComputeQueues() > 0;
            if (separateCompute) {
                computeQueueStart = 0;
            } else {
                computeFamily = graphicsFamily;
                computeQueueStart = graphicsCount;
                graphicsCount += requirements.extraComputeQueues();
            }
            graphicsCount = Math.min(graphicsCount, families.get(graphicsFamily).queueCount());

            // What the GPU supports, to enable the optional features below only where they exist.
            long supportedOpticalFlow = opticalFlowFeatures(stack, MemoryUtil.NULL, false);
            VkPhysicalDeviceVulkan13Features supported13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default()
                    .pNext(supportedOpticalFlow);
            VkPhysicalDeviceVulkan12Features supported12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default()
                    .pNext(supported13.address());
            VkPhysicalDeviceVulkan11Features supported11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default()
                    .pNext(supported12.address());
            VkPhysicalDeviceFeatures2 supported = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                    .pNext(supported11.address());
            NativeCall.callInt(vk.getPhysicalDeviceFeatures2, physical, supported.address());

            // DLSS-G runs optical flow natively on the first queue of the first optical-flow family, which must not be
            // a family our other queues use; without it Streamline falls back to a slower CUDA interop path.
            int opticalFlowFamily = -1;
            Set<String> available = availableDeviceExtensions(vk, physical);
            if (requirements.opticalFlowQueues() > 0 && available.contains("VK_NV_optical_flow")
                    && supported13.synchronization2() && MemoryUtil.memGetInt(supportedOpticalFlow + 16) != 0) {
                for (int i = 0; i < families.capacity(); i++) {
                    if ((families.get(i).queueFlags() & VK_QUEUE_OPTICAL_FLOW_BIT_NV) != 0) {
                        if (i != graphicsFamily && !(separateCompute && i == computeFamily)) {
                            opticalFlowFamily = i;
                        }
                        break;
                    }
                }
            }

            // Queues per family: ours and Streamline's as above, then the two FSR 3 frame generation's swapchain uses
            // besides ours (FidelityFX wants every queue it is given to be distinct): one that can present to a window,
            // and one more for its image acquires.
            int[] counts = new int[families.capacity()];
            counts[graphicsFamily] = graphicsCount;
            if (separateCompute) {
                counts[computeFamily] = Math.min(requirements.extraComputeQueues(), families.get(computeFamily).queueCount());
            }
            if (opticalFlowFamily >= 0) {
                counts[opticalFlowFamily] = Math.min(requirements.opticalFlowQueues(), families.get(opticalFlowFamily).queueCount());
            }
            int special = VK_QUEUE_VIDEO_DECODE_BIT_KHR | VK_QUEUE_VIDEO_ENCODE_BIT_KHR | VK_QUEUE_OPTICAL_FLOW_BIT_NV;
            int[] computeOnly = familiesWith(families, VK_QUEUE_COMPUTE_BIT, VK_QUEUE_GRAPHICS_BIT | special);
            int[] transferOnly = familiesWith(families, VK_QUEUE_TRANSFER_BIT, VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | special);
            int[] fsrPresent = !surfaces ? null : takeQueue(families, counts, concat(new int[]{graphicsFamily}, computeOnly, transferOnly),
                    family -> NativeCall.callInt(vk.getPhysicalDeviceWin32PresentationSupport, physical, family) != 0);
            int[] fsrAcquire = fsrPresent == null ? null
                    : takeQueue(families, counts, concat(transferOnly, computeOnly, new int[]{graphicsFamily}), family -> true);

            int familiesUsed = (int) Arrays.stream(counts).filter(count -> count > 0).count();
            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(familiesUsed, stack);
            int next = 0;
            for (int family = 0; family < counts.length; family++) {
                if (counts[family] > 0) {
                    queues.get(next++).sType$Default().queueFamilyIndex(family).pQueuePriorities(priorities(stack, counts[family]));
                }
            }

            Set<String> deviceExtensions = new LinkedHashSet<>(requirements.deviceExtensions());
            deviceExtensions.add(KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME);
            deviceExtensions.add(KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME);
            // FidelityFX calls vkGetBufferMemoryRequirements2KHR by its KHR name whenever the GPU advertises dedicated
            // allocations; the loader only resolves KHR-suffixed names for extensions enabled on the device (even
            // though the function is core since 1.1), otherwise it returns null and the call jumps to address 0.
            for (String extension : new String[]{"VK_KHR_get_memory_requirements2", "VK_KHR_dedicated_allocation"}) {
                if (available.contains(extension)) {
                    deviceExtensions.add(extension);
                }
            }
            boolean swapchains = surfaces && available.contains("VK_KHR_swapchain");
            if (swapchains) {
                deviceExtensions.add("VK_KHR_swapchain");
            }

            if (opticalFlowFamily < 0) {
                deviceExtensions.remove("VK_NV_optical_flow");
            }
            // Any extension a feature asks for that this driver lacks would fail device creation for everything, so
            // drop it here; the feature that needed it reports itself unavailable later.
            List<String> missing = deviceExtensions.stream().filter(extension -> !available.contains(extension)).toList();
            if (!missing.isEmpty()) {
                LOGGER.warn("Temporal: Vulkan device extensions not available: {}", missing);
                missing.forEach(deviceExtensions::remove);
            }

            VkPhysicalDeviceVulkan13Features features13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default()
                    .pNext(opticalFlowFamily >= 0 ? opticalFlowFeatures(stack, MemoryUtil.NULL, true) : MemoryUtil.NULL);
            VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default()
                    .pNext(features13.address());
            VkPhysicalDeviceVulkan11Features features11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default()
                    .pNext(features12.address());
            enable(features12, requirements.features12());
            enable(features13, requirements.features13());
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                    .pNext(features11.address());
            // FidelityFX picks FP16, wave-size and sync2 code paths from what the GPU *supports*, so the device must
            // actually enable them wherever they exist.
            features.features().shaderInt16(supported.features().shaderInt16());
            features11.storageBuffer16BitAccess(supported11.storageBuffer16BitAccess())
                    .uniformAndStorageBuffer16BitAccess(supported11.uniformAndStorageBuffer16BitAccess());
            features12.shaderFloat16(supported12.shaderFloat16())
                    .shaderInt8(supported12.shaderInt8())
                    .shaderStorageBufferArrayNonUniformIndexing(supported12.shaderStorageBufferArrayNonUniformIndexing());
            features13.subgroupSizeControl(supported13.subgroupSizeControl())
                    .computeFullSubgroups(supported13.computeFullSubgroups())
                    .synchronization2(supported13.synchronization2());
            // FSR 3 frame generation's swapchain synchronises its queues with timeline semaphores.
            boolean timelineSemaphores = supported12.timelineSemaphore();
            if (timelineSemaphores) {
                features12.timelineSemaphore(true);
            }

            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(features.address())
                    .pQueueCreateInfos(queues)
                    .ppEnabledExtensionNames(names(stack, deviceExtensions));
            VkApi.check(vk.createDevice, "vkCreateDevice", physical, deviceInfo.address(), MemoryUtil.NULL, handle.address());
            long device = handle.get(0);
            vk.loadDevice(device);
            if (swapchains) {
                vk.loadSwapchain(device);
            }
            LOGGER.info("Temporal: Vulkan device on {} (instance ext {}, device ext {}, Streamline features12 {}, features13 {}, "
                            + "extra queues g{} c{}, optical flow family {}, FSR 3 frame generation queues {} {}, fp16 {}, "
                            + "sync2 {}, timeline semaphores {})",
                    name, instanceExtensions, deviceExtensions, requirements.features12(), requirements.features13(),
                    requirements.extraGraphicsQueues(), requirements.extraComputeQueues(), opticalFlowFamily,
                    Arrays.toString(fsrPresent), Arrays.toString(fsrAcquire), features12.shaderFloat16(),
                    features13.synchronization2(), timelineSemaphores);
            VulkanContext context = new VulkanContext(vk, instance, physical, device, graphicsFamily, computeFamily,
                    computeQueueStart, opticalFlowFamily, name);
            context.canPresent = swapchains;
            if (swapchains && timelineSemaphores && fsrPresent != null && fsrAcquire != null) {
                context.fsrPresentQueue = new Queue(context.deviceQueue(fsrPresent[0], fsrPresent[1]), fsrPresent[0]);
                context.fsrAcquireQueue = new Queue(context.deviceQueue(fsrAcquire[0], fsrAcquire[1]), fsrAcquire[0]);
            }
            return context;
        }
    }

    private static long pickPhysicalDevice(MemoryStack stack, VkApi vk, long instance, byte[] glDeviceUuid) {
        IntBuffer count = stack.callocInt(1);
        VkApi.check(vk.enumeratePhysicalDevices, "vkEnumeratePhysicalDevices", instance, MemoryUtil.memAddress(count), MemoryUtil.NULL);
        PointerBuffer devices = stack.callocPointer(count.get(0));
        VkApi.check(vk.enumeratePhysicalDevices, "vkEnumeratePhysicalDevices", instance, MemoryUtil.memAddress(count), devices.address());
        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDeviceIDProperties ids = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(ids.address());
            NativeCall.callInt(vk.getPhysicalDeviceProperties2, devices.get(i), properties.address());
            byte[] uuid = new byte[16];
            ids.deviceUUID().get(0, uuid);
            if (Arrays.equals(uuid, glDeviceUuid)) {
                return devices.get(i);
            }
        }
        throw new IllegalStateException("no Vulkan device matches the OpenGL device");
    }

    private static Set<String> availableInstanceExtensions(MemoryStack stack, VkApi vk) {
        IntBuffer count = stack.callocInt(1);
        VkApi.check(vk.enumerateInstanceExtensionProperties, "vkEnumerateInstanceExtensionProperties",
                MemoryUtil.NULL, MemoryUtil.memAddress(count), MemoryUtil.NULL);
        // Heap, not the 64 KB LWJGL stack: each entry is 260 bytes.
        org.lwjgl.vulkan.VkExtensionProperties.Buffer properties = org.lwjgl.vulkan.VkExtensionProperties.calloc(count.get(0));
        try {
            VkApi.check(vk.enumerateInstanceExtensionProperties, "vkEnumerateInstanceExtensionProperties",
                    MemoryUtil.NULL, MemoryUtil.memAddress(count), properties.address());
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < count.get(0); i++) {
                names.add(properties.get(i).extensionNameString());
            }
            return names;
        } finally {
            properties.free();
        }
    }

    private static Set<String> availableDeviceExtensions(VkApi vk, long physical) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            VkApi.check(vk.enumerateDeviceExtensionProperties, "vkEnumerateDeviceExtensionProperties",
                    physical, MemoryUtil.NULL, MemoryUtil.memAddress(count), MemoryUtil.NULL);
            org.lwjgl.vulkan.VkExtensionProperties.Buffer properties = org.lwjgl.vulkan.VkExtensionProperties.calloc(count.get(0));
            try {
                VkApi.check(vk.enumerateDeviceExtensionProperties, "vkEnumerateDeviceExtensionProperties",
                        physical, MemoryUtil.NULL, MemoryUtil.memAddress(count), properties.address());
                Set<String> names = new LinkedHashSet<>();
                for (int i = 0; i < count.get(0); i++) {
                    names.add(properties.get(i).extensionNameString());
                }
                return names;
            } finally {
                properties.free();
            }
        }
    }

    private static VkQueueFamilyProperties.Buffer queueFamilies(MemoryStack stack, VkApi vk, long physical) {
        IntBuffer count = stack.callocInt(1);
        NativeCall.callInt(vk.getPhysicalDeviceQueueFamilyProperties, physical, MemoryUtil.memAddress(count), MemoryUtil.NULL);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        NativeCall.callInt(vk.getPhysicalDeviceQueueFamilyProperties, physical, MemoryUtil.memAddress(count), families.address());
        return families;
    }

    /** VkPhysicalDeviceOpticalFlowFeaturesNV: sType, pNext, VkBool32 opticalFlow. */
    private static long opticalFlowFeatures(MemoryStack stack, long next, boolean enable) {
        long address = stack.ncalloc(8, 1, 24);
        MemoryUtil.memPutInt(address, STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV);
        MemoryUtil.memPutAddress(address + 8, next);
        MemoryUtil.memPutInt(address + 16, enable ? VK_TRUE : VK_FALSE);
        return address;
    }

    /** Families whose queues can do all of {@code required} and none of {@code forbidden}, in index order. */
    private static int[] familiesWith(VkQueueFamilyProperties.Buffer families, int required, int forbidden) {
        return IntStream.range(0, families.capacity())
                .filter(i -> (families.get(i).queueFlags() & required) == required && (families.get(i).queueFlags() & forbidden) == 0)
                .toArray();
    }

    /**
     * Takes the next unused queue of the first family in {@code order} that has one left and passes {@code test}, and
     * counts it as used: returns {family, queue index}, or null.
     */
    @Nullable
    private static int[] takeQueue(VkQueueFamilyProperties.Buffer families, int[] counts, int[] order, IntPredicate test) {
        for (int family : order) {
            if (counts[family] < families.get(family).queueCount() && test.test(family)) {
                return new int[]{family, counts[family]++};
            }
        }
        return null;
    }

    private static int[] concat(int[]... parts) {
        return Arrays.stream(parts).flatMapToInt(Arrays::stream).toArray();
    }

    long deviceQueue(int family, int index) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer handle = stack.callocPointer(1);
            NativeCall.callInt(vk.getDeviceQueue, device, family, index, handle.address());
            return handle.get(0);
        }
    }

    private static FloatBuffer priorities(MemoryStack stack, int count) {
        FloatBuffer priorities = stack.mallocFloat(count);
        for (int i = 0; i < count; i++) {
            priorities.put(i, 1.0F);
        }
        return priorities;
    }

    private static PointerBuffer names(MemoryStack stack, Set<String> names) {
        PointerBuffer buffer = stack.mallocPointer(names.size());
        int i = 0;
        for (String name : names) {
            buffer.put(i++, stack.UTF8(name));
        }
        return buffer;
    }

    /** Sets the boolean feature fields Streamline asks for by name (e.g. "timelineSemaphore") via the LWJGL setters. */
    private static void enable(Object features, List<String> names) {
        for (String name : names) {
            try {
                features.getClass().getMethod(name, boolean.class).invoke(features, true);
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("Temporal DLSS: unknown Vulkan feature {}", name);
            }
        }
    }

    /** Index of a device-local memory type allowed by {@code typeBits}. */
    int deviceLocalMemoryType(int typeBits) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
            NativeCall.callInt(vk.getPhysicalDeviceMemoryProperties, physicalDevice, memory.address());
            for (int i = 0; i < memory.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0
                        && (memory.memoryTypes(i).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("no device-local memory type");
    }

    void waitIdle() {
        if (beforeWaitIdle != null) {
            beforeWaitIdle.run();
        }
        NativeCall.callInt(waitIdleFunction, device);
    }

    /** Waits for the previous submission, then resets and begins the command buffer for one-time use. */
    void beginCommands(MemoryStack stack) {
        LongBufferHolder fences = new LongBufferHolder(stack, fence);
        NativeCall.callInt(vk.waitForFences, device, 1, fences.address, VK_TRUE, Long.MAX_VALUE);
        NativeCall.callInt(vk.resetFences, device, 1, fences.address);
        NativeCall.callInt(vk.resetCommandBuffer, commandBuffer, 0);
        VkApi.check(vk.beginCommandBuffer, "vkBeginCommandBuffer", commandBuffer,
                org.lwjgl.vulkan.VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT).address());
    }

    void waitForFence(MemoryStack stack) {
        NativeCall.callInt(vk.waitForFences, device, 1, new LongBufferHolder(stack, fence).address, VK_TRUE, Long.MAX_VALUE);
    }

    CommandSlot newCommandSlot() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer handle = stack.callocPointer(1);
            VkApi.check(vk.allocateCommandBuffers, "vkAllocateCommandBuffers", device, VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1).address(), handle.address());
            long buffer = handle.get(0);
            VkApi.check(vk.createFence, "vkCreateFence", device, VkFenceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT).address(), MemoryUtil.NULL, handle.address());
            return new CommandSlot(buffer, handle.get(0));
        }
    }

    /** Waits for the slot's previous submission, then resets and begins its command buffer. */
    void beginCommands(MemoryStack stack, CommandSlot slot) {
        long fences = new LongBufferHolder(stack, slot.fence()).address;
        NativeCall.callInt(vk.waitForFences, device, 1, fences, VK_TRUE, Long.MAX_VALUE);
        NativeCall.callInt(vk.resetFences, device, 1, fences);
        NativeCall.callInt(vk.resetCommandBuffer, slot.commandBuffer(), 0);
        VkApi.check(vk.beginCommandBuffer, "vkBeginCommandBuffer", slot.commandBuffer(),
                org.lwjgl.vulkan.VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT).address());
    }

    void destroyCommandSlot(CommandSlot slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NativeCall.callInt(vk.waitForFences, device, 1, new LongBufferHolder(stack, slot.fence()).address, VK_TRUE, Long.MAX_VALUE);
            NativeCall.callInt(vk.destroyFence, device, slot.fence(), MemoryUtil.NULL);
            org.lwjgl.PointerBuffer buffers = stack.pointers(slot.commandBuffer());
            NativeCall.callInt(vk.freeCommandBuffers, device, commandPool, 1, buffers.address());
        }
    }

    @Override
    public void close() {
        waitIdle();
        NativeCall.callInt(vk.destroyFence, device, fence, MemoryUtil.NULL);
        NativeCall.callInt(vk.destroyCommandPool, device, commandPool, MemoryUtil.NULL);
        NativeCall.callInt(vk.destroyDevice, device, MemoryUtil.NULL);
        NativeCall.callInt(vk.destroyInstance, instance, MemoryUtil.NULL);
    }

    /** A single 64-bit handle stored on the stack, for Vulkan's pointer-to-array parameters. */
    static final class LongBufferHolder {
        final long address;

        LongBufferHolder(MemoryStack stack, long value) {
            this.address = MemoryUtil.memAddress(stack.longs(value));
        }
    }
}
