package com.temporal.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.IntBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Shared helpers for drawing a shader over a whole render target. */
public final class FullscreenPass {
    /** Draws {@code shader} over all of {@code destination}; the caller sets samplers and uniforms first. */
    public static void draw(ShaderInstance shader, RenderTarget destination) {
        draw(shader, destination, destination.width, destination.height);
    }

    /**
     * Draws into the first {@code width} x {@code height} pixels only (the top-left corner of a top-down shared image):
     * with dynamic resolution the shared images keep their largest size and each frame fills part of them.
     */
    public static void draw(ShaderInstance shader, RenderTarget destination, int width, int height) {
        destination.bindWrite(true);
        GlStateManager._viewport(0, 0, width, height);
        GlStateManager._colorMask(true, true, true, false);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._disableBlend();
        shader.apply();
        BufferBuilder buffer = RenderSystem.renderThreadTesselator().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.vertex(0.0, 0.0, 0.0).endVertex();
        buffer.vertex(1.0, 0.0, 0.0).endVertex();
        buffer.vertex(1.0, 1.0, 0.0).endVertex();
        buffer.vertex(0.0, 1.0, 0.0).endVertex();
        BufferUploader.draw(buffer.end());
        shader.clear();
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._enableDepthTest();
    }

    public static void blit(RenderTarget source, RenderTarget destination, int filter) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, source.width, source.height, 0, 0, destination.width, destination.height,
                GL30.GL_COLOR_BUFFER_BIT, filter);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** Copies colour with the rows reversed: OpenGL images start at the bottom row, Vulkan/DXGI images at the top. */
    public static void blitFlipped(RenderTarget source, RenderTarget destination, int filter) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, source.width, source.height, 0, destination.height, destination.width, 0,
                GL30.GL_COLOR_BUFFER_BIT, filter);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** Like {@link #blitFlipped}, into the top-left corner of a larger top-down image, at the source's size. */
    public static void blitFlippedIntoCorner(RenderTarget source, RenderTarget destination, int filter) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, source.width, source.height, 0, source.height, source.width, 0,
                GL30.GL_COLOR_BUFFER_BIT, filter);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** Re-specifies a target's color texture as 16-bit float with linear filtering (for accumulation buffers). */
    static void makeHalfFloat(RenderTarget target) {
        GlStateManager._bindTexture(target.getColorTextureId());
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, target.width, target.height, 0,
                GL11.GL_RGBA, GL11.GL_FLOAT, (IntBuffer) null);
        GlStateManager._bindTexture(0);
        target.setFilterMode(GL11.GL_LINEAR);
    }

    private FullscreenPass() {}
}
