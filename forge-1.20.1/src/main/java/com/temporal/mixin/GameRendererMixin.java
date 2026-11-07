package com.temporal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.temporal.client.ShaderPacks;
import com.temporal.client.TemporalAA;
import com.temporal.client.Upscaler;
import com.temporal.client.bridge.FrameGeneration;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Shadow private boolean renderHand;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void temporal$beginLevel(CallbackInfo ci) {
        Upscaler.beginLevel();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void temporal$upscaleLevel(CallbackInfo ci) {
        if (renderHand && !ShaderPacks.inUse() && !Upscaler.isNestedLevel()) {
            // The depth buffer was cleared for the hand, so it now holds only the hand. (A shader pack's hand is
            // already in the scene; the motion shader recognises it by depth.)
            FrameGeneration.maskHand(Upscaler.levelTarget());
        }
        Upscaler.endLevel();
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lorg/joml/Matrix4f;)V"))
    private Matrix4f temporal$jitterProjection(Matrix4f projection) {
        return TemporalAA.jitterProjection(projection);
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"))
    private void temporal$captureView(float partialTick, long finishTimeNano, PoseStack poseStack, CallbackInfo ci) {
        TemporalAA.captureView(poseStack.last().pose());
    }

    @Inject(method = "resize", at = @At("HEAD"))
    private void temporal$resize(int width, int height, CallbackInfo ci) {
        Upscaler.onResize(width, height);
    }

    /** The world, hand and post effects are done and the GUI is next: frame generation keeps the HUD-less frame. */
    @Inject(method = "render", at = @At(value = "NEW", target = "net/minecraft/client/gui/GuiGraphics"))
    private void temporal$beforeGui(CallbackInfo ci) {
        FrameGeneration.captureHudless();
    }
}
