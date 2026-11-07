package com.temporal.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.temporal.client.Upscaler;
import com.temporal.client.bridge.FrameGeneration;
import com.temporal.client.bridge.Presenter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void temporal$scaledTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (Upscaler.isScaling()) {
            cir.setReturnValue(Upscaler.scaledTarget());
        }
    }

    /** A frame starts: Reflex sleeps here (frame generation) before input is read and the game ticks. */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void temporal$beginFrame(boolean renderLevel, CallbackInfo ci) {
        Upscaler.beginFrame();
        FrameGeneration.beginFrame();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void temporal$beforeRender(boolean renderLevel, CallbackInfo ci) {
        FrameGeneration.beforeRender();
    }

    /** While Vulkan presents, OpenGL's default framebuffer is never shown: skip copying the frame into it. */
    @Redirect(method = "runTick", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void temporal$blitToScreen(RenderTarget target, int width, int height) {
        if (!Presenter.isPresenting()) {
            target.blitToScreen(width, height);
        }
    }
}
