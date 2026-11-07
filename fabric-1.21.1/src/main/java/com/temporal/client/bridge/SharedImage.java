package com.temporal.client.bridge;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkMemoryRequirements;

/**
 * A 2D image whose memory is allocated by Vulkan and imported into OpenGL, so both APIs read and write the same pixels.
 * It is kept in {@code VK_IMAGE_LAYOUT_GENERAL} for its whole life; ownership moves between the APIs via semaphores.
 */
final class SharedImage implements AutoCloseable {
    private static final int USAGE = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    private static long closeHandle;

    final int width;
    final int height;
    final int vkFormat;
    final long image;
    final long memory;
    final long view;
    final int glMemoryObject;
    final int glTexture;
    final int glFramebuffer;
    /** A Minecraft-style handle on the GL side, so existing blit/draw helpers can target this image. */
    final RenderTarget target;
    private final VulkanContext vulkan;

    SharedImage(VulkanContext vulkan, int width, int height, int vkFormat, int glInternalFormat) {
        this.vulkan = vulkan;
        this.width = width;
        this.height = height;
        this.vkFormat = vkFormat;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
                    .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .pNext(external.address())
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(vkFormat)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(USAGE)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, 1);
            VkApi vk = vulkan.vk;
            PointerBuffer handle = stack.callocPointer(1);
            VkApi.check(vk.createImage, "vkCreateImage", vulkan.device, imageInfo.address(), MemoryUtil.NULL, handle.address());
            this.image = handle.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            NativeCall.callInt(vk.getImageMemoryRequirements, vulkan.device, image, requirements.address());
            VkMemoryDedicatedAllocateInfo dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image);
            VkExportMemoryAllocateInfo export = VkExportMemoryAllocateInfo.calloc(stack).sType$Default()
                    .pNext(dedicated.address())
                    .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            VkApi.check(vk.allocateMemory, "vkAllocateMemory", vulkan.device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .pNext(export.address())
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(vulkan.deviceLocalMemoryType(requirements.memoryTypeBits())).address(),
                    MemoryUtil.NULL, handle.address());
            this.memory = handle.get(0);
            VkApi.check(vk.bindImageMemory, "vkBindImageMemory", vulkan.device, image, memory, 0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(vkFormat);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VkApi.check(vk.createImageView, "vkCreateImageView", vulkan.device, viewInfo.address(), MemoryUtil.NULL, handle.address());
            this.view = handle.get(0);

            PointerBuffer win32Handle = stack.callocPointer(1);
            VkApi.check(vk.getMemoryWin32Handle, "vkGetMemoryWin32HandleKHR", vulkan.device,
                    VkMemoryGetWin32HandleInfoKHR.calloc(stack).sType$Default()
                            .memory(memory)
                            .handleType(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT).address(), win32Handle.address());

            this.glMemoryObject = EXTMemoryObject.glCreateMemoryObjectsEXT();
            EXTMemoryObject.glMemoryObjectParameteriEXT(glMemoryObject, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL11.GL_TRUE);
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(glMemoryObject, requirements.size(),
                    EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, win32Handle.get(0));

            this.glTexture = GlStateManager._genTexture();
            GlStateManager._bindTexture(glTexture);
            EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D, 1, glInternalFormat, width, height, glMemoryObject, 0);
            // GL does not take ownership of an opaque Win32 handle; the importer must close it - but only once the
            // import has really happened (a threaded GL driver may still be working through its command queue).
            GL11.glFinish();
            closeHandle(win32Handle.get(0));
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);
            GlStateManager._bindTexture(0);

            this.glFramebuffer = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, glFramebuffer);
            GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, glTexture, 0);
            int status = GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("shared framebuffer incomplete: 0x" + Integer.toHexString(status));
            }
            this.target = new ExternalTarget(glFramebuffer, glTexture, width, height);
        }
    }

    private static void closeHandle(long handle) {
        if (closeHandle == 0) {
            SharedLibrary kernel32 = Library.loadNative(SharedImage.class, "org.lwjgl", "kernel32");
            closeHandle = kernel32.getFunctionAddress("CloseHandle");
        }
        NativeCall.callInt(closeHandle, handle);
    }

    /** Writes this image into a Streamline {@code sl::Resource} at {@code address} (size {@link Streamline#SIZE_RESOURCE}). */
    void describe(long address) {
        Streamline.header(address, Streamline.RESOURCE, 1);
        MemoryUtil.memPutByte(address + 32, (byte) 0);          // type = eTex2d
        MemoryUtil.memPutLong(address + 40, image);             // native VkImage
        MemoryUtil.memPutLong(address + 48, memory);            // VkDeviceMemory
        MemoryUtil.memPutLong(address + 56, view);              // VkImageView
        MemoryUtil.memPutInt(address + 64, VK_IMAGE_LAYOUT_GENERAL); // state
        MemoryUtil.memPutInt(address + 68, width);
        MemoryUtil.memPutInt(address + 72, height);
        MemoryUtil.memPutInt(address + 76, vkFormat);           // nativeFormat
        MemoryUtil.memPutInt(address + 80, 1);                  // mipLevels
        MemoryUtil.memPutInt(address + 84, 1);                  // arrayLayers
        MemoryUtil.memPutLong(address + 88, 0);                 // gpuVirtualAddress
        MemoryUtil.memPutInt(address + 96, 0);                  // VkImageCreateFlags
        MemoryUtil.memPutInt(address + 100, USAGE);             // VkImageUsageFlags
        MemoryUtil.memPutShort(address + 104, (short) 0);       // internalFlags (asserted offset)
        MemoryUtil.memPutShort(address + 106, (short) 0);       // reserved
    }

    @Override
    public void close() {
        GlStateManager._glDeleteFramebuffers(glFramebuffer);
        GlStateManager._deleteTexture(glTexture);
        EXTMemoryObject.glDeleteMemoryObjectsEXT(glMemoryObject);
        NativeCall.callInt(vulkan.vk.destroyImageView, vulkan.device, view, MemoryUtil.NULL);
        NativeCall.callInt(vulkan.vk.destroyImage, vulkan.device, image, MemoryUtil.NULL);
        NativeCall.callInt(vulkan.vk.freeMemory, vulkan.device, memory, MemoryUtil.NULL);
    }

    /** RenderTarget facade over an existing framebuffer/texture pair; never resized or recreated by Minecraft. */
    private static final class ExternalTarget extends RenderTarget {
        ExternalTarget(int framebuffer, int texture, int width, int height) {
            super(false);
            this.frameBufferId = framebuffer;
            this.colorTextureId = texture;
            this.width = width;
            this.height = height;
            this.viewWidth = width;
            this.viewHeight = height;
        }
    }
}
