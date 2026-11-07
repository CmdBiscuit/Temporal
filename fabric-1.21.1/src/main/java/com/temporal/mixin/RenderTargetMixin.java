package com.temporal.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.temporal.client.StencilTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** A stencil buffer for Temporal's scaled target when the main target has one (see Upscaler.matchStencil). */
@Mixin(RenderTarget.class)
abstract class RenderTargetMixin implements StencilTarget {
    @Shadow public int viewWidth;
    @Shadow public int viewHeight;

    @Unique private boolean temporal$stencil;

    @Shadow
    public abstract void resize(int width, int height, boolean clearError);

    @Override
    public boolean temporal$isStencilEnabled() {
        return temporal$stencil;
    }

    @Override
    public void temporal$enableStencil() {
        if (!temporal$stencil) {
            temporal$stencil = true;
            resize(viewWidth, viewHeight, Minecraft.ON_OSX);
        }
    }

    @ModifyArgs(method = "createBuffers", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/GlStateManager;_texImage2D(IIIIIIIILjava/nio/IntBuffer;)V"))
    private void temporal$depthStencilTexture(Args args) {
        if (temporal$stencil && (int) args.get(2) == GL11.GL_DEPTH_COMPONENT) {
            args.set(2, GL30.GL_DEPTH24_STENCIL8);
            args.set(6, GL30.GL_DEPTH_STENCIL);
            args.set(7, GL30.GL_UNSIGNED_INT_24_8);
        }
    }

    @ModifyArg(method = "createBuffers", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glFramebufferTexture2D(IIIII)V"), index = 1)
    private int temporal$depthStencilAttachment(int attachment) {
        return temporal$stencil && attachment == GL30.GL_DEPTH_ATTACHMENT ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : attachment;
    }
}
