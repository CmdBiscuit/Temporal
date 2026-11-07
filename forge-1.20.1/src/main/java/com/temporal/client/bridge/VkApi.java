package com.temporal.client.bridge;

import java.nio.ByteBuffer;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * The handful of Vulkan entry points the DLSS bridge needs, resolved through vkGetInstanceProcAddr and called with
 * {@link NativeCall}.
 *
 * <p>LWJGL's {@code VkInstance} wrapper can't be used: its constructor lists every device extension of every GPU on
 * LWJGL's fixed 64 KB thread stack, which overflows on laptops with two GPUs. LWJGL's struct classes are still used
 * to lay out the create-info structures; only dispatch goes through here.
 */
final class VkApi {
    private final long getInstanceProcAddr;

    long createInstance;
    long enumerateInstanceExtensionProperties;
    long destroyInstance;
    long enumeratePhysicalDevices;
    long getPhysicalDeviceProperties2;
    long getPhysicalDeviceFeatures2;
    long enumerateDeviceExtensionProperties;
    long getPhysicalDeviceQueueFamilyProperties;
    long getPhysicalDeviceMemoryProperties;
    long createDevice;
    long getDeviceProcAddr;
    // VK_KHR_surface / VK_KHR_win32_surface (present only when those instance extensions are enabled)
    long getPhysicalDeviceSurfaceSupport;
    long getPhysicalDeviceSurfaceCapabilities;
    long getPhysicalDeviceSurfaceFormats;
    long getPhysicalDeviceSurfacePresentModes;
    long createWin32Surface;
    long destroySurface;
    long getPhysicalDeviceWin32PresentationSupport;

    long destroyDevice;
    long deviceWaitIdle;
    long getDeviceQueue;
    long queueSubmit;
    long createCommandPool;
    long destroyCommandPool;
    long allocateCommandBuffers;
    long freeCommandBuffers;
    long resetCommandBuffer;
    long beginCommandBuffer;
    long endCommandBuffer;
    long cmdPipelineBarrier;
    long createFence;
    long destroyFence;
    long waitForFences;
    long resetFences;
    long createSemaphore;
    long destroySemaphore;
    long getSemaphoreWin32Handle;
    long createImage;
    long destroyImage;
    long getImageMemoryRequirements;
    long allocateMemory;
    long freeMemory;
    long bindImageMemory;
    long getMemoryWin32Handle;
    long createImageView;
    long destroyImageView;
    long cmdBlitImage;
    long cmdClearColorImage;
    long cmdCopyImage;
    // VK_KHR_swapchain
    long createSwapchain;
    long destroySwapchain;
    long getSwapchainImages;
    long acquireNextImage;
    long queuePresent;

    VkApi() {
        SharedLibrary loader = Library.loadNative(VkApi.class, "org.lwjgl", "vulkan-1");
        getInstanceProcAddr = loader.getFunctionAddress("vkGetInstanceProcAddr");
        createInstance = instanceProc(MemoryUtil.NULL, "vkCreateInstance");
        enumerateInstanceExtensionProperties = instanceProc(MemoryUtil.NULL, "vkEnumerateInstanceExtensionProperties");
    }

    void loadInstance(long instance) {
        destroyInstance = instanceProc(instance, "vkDestroyInstance");
        enumeratePhysicalDevices = instanceProc(instance, "vkEnumeratePhysicalDevices");
        getPhysicalDeviceProperties2 = instanceProc(instance, "vkGetPhysicalDeviceProperties2");
        getPhysicalDeviceFeatures2 = instanceProc(instance, "vkGetPhysicalDeviceFeatures2");
        enumerateDeviceExtensionProperties = instanceProc(instance, "vkEnumerateDeviceExtensionProperties");
        getPhysicalDeviceQueueFamilyProperties = instanceProc(instance, "vkGetPhysicalDeviceQueueFamilyProperties");
        getPhysicalDeviceMemoryProperties = instanceProc(instance, "vkGetPhysicalDeviceMemoryProperties");
        createDevice = instanceProc(instance, "vkCreateDevice");
        getDeviceProcAddr = instanceProc(instance, "vkGetDeviceProcAddr");
    }

    void loadSurface(long instance) {
        getPhysicalDeviceSurfaceSupport = instanceProc(instance, "vkGetPhysicalDeviceSurfaceSupportKHR");
        getPhysicalDeviceSurfaceCapabilities = instanceProc(instance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
        getPhysicalDeviceSurfaceFormats = instanceProc(instance, "vkGetPhysicalDeviceSurfaceFormatsKHR");
        getPhysicalDeviceSurfacePresentModes = instanceProc(instance, "vkGetPhysicalDeviceSurfacePresentModesKHR");
        createWin32Surface = instanceProc(instance, "vkCreateWin32SurfaceKHR");
        destroySurface = instanceProc(instance, "vkDestroySurfaceKHR");
        getPhysicalDeviceWin32PresentationSupport = instanceProc(instance, "vkGetPhysicalDeviceWin32PresentationSupportKHR");
    }

    void loadSwapchain(long device) {
        createSwapchain = deviceProc(device, "vkCreateSwapchainKHR");
        destroySwapchain = deviceProc(device, "vkDestroySwapchainKHR");
        getSwapchainImages = deviceProc(device, "vkGetSwapchainImagesKHR");
        acquireNextImage = deviceProc(device, "vkAcquireNextImageKHR");
        queuePresent = deviceProc(device, "vkQueuePresentKHR");
    }

    void loadDevice(long device) {
        destroyDevice = deviceProc(device, "vkDestroyDevice");
        deviceWaitIdle = deviceProc(device, "vkDeviceWaitIdle");
        getDeviceQueue = deviceProc(device, "vkGetDeviceQueue");
        queueSubmit = deviceProc(device, "vkQueueSubmit");
        createCommandPool = deviceProc(device, "vkCreateCommandPool");
        destroyCommandPool = deviceProc(device, "vkDestroyCommandPool");
        allocateCommandBuffers = deviceProc(device, "vkAllocateCommandBuffers");
        freeCommandBuffers = deviceProc(device, "vkFreeCommandBuffers");
        resetCommandBuffer = deviceProc(device, "vkResetCommandBuffer");
        beginCommandBuffer = deviceProc(device, "vkBeginCommandBuffer");
        endCommandBuffer = deviceProc(device, "vkEndCommandBuffer");
        cmdPipelineBarrier = deviceProc(device, "vkCmdPipelineBarrier");
        createFence = deviceProc(device, "vkCreateFence");
        destroyFence = deviceProc(device, "vkDestroyFence");
        waitForFences = deviceProc(device, "vkWaitForFences");
        resetFences = deviceProc(device, "vkResetFences");
        createSemaphore = deviceProc(device, "vkCreateSemaphore");
        destroySemaphore = deviceProc(device, "vkDestroySemaphore");
        getSemaphoreWin32Handle = deviceProc(device, "vkGetSemaphoreWin32HandleKHR");
        createImage = deviceProc(device, "vkCreateImage");
        destroyImage = deviceProc(device, "vkDestroyImage");
        getImageMemoryRequirements = deviceProc(device, "vkGetImageMemoryRequirements");
        allocateMemory = deviceProc(device, "vkAllocateMemory");
        freeMemory = deviceProc(device, "vkFreeMemory");
        bindImageMemory = deviceProc(device, "vkBindImageMemory");
        getMemoryWin32Handle = deviceProc(device, "vkGetMemoryWin32HandleKHR");
        createImageView = deviceProc(device, "vkCreateImageView");
        destroyImageView = deviceProc(device, "vkDestroyImageView");
        cmdBlitImage = deviceProc(device, "vkCmdBlitImage");
        cmdClearColorImage = deviceProc(device, "vkCmdClearColorImage");
        cmdCopyImage = deviceProc(device, "vkCmdCopyImage");
    }

    private long instanceProc(long instance, String name) {
        return resolve(getInstanceProcAddr, instance, name);
    }

    private long deviceProc(long device, String name) {
        return resolve(getDeviceProcAddr, device, name);
    }

    private static long resolve(long getProcAddr, long handle, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer utf8 = stack.UTF8(name);
            long address = NativeCall.callLong(getProcAddr, handle, MemoryUtil.memAddress(utf8));
            if (address == MemoryUtil.NULL) {
                throw new IllegalStateException("Vulkan function unavailable: " + name);
            }
            return address;
        }
    }

    /** A Vulkan call that didn't return VK_SUCCESS. */
    static final class Failure extends IllegalStateException {
        final int result;

        Failure(String what, int result) {
            super(what + " failed: VkResult " + result);
            this.result = result;
        }
    }

    /** Calls a VkResult-returning function and throws on failure. */
    static void check(long function, String what, long... args) {
        int result = NativeCall.callInt(function, args);
        if (result != 0) {
            throw new Failure(what, result);
        }
    }
}
