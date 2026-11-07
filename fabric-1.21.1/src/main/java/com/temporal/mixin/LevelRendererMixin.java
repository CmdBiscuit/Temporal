package com.temporal.mixin;

import com.temporal.client.Upscaler;
import com.temporal.client.bridge.ReactiveMask;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Builds and sizes the entity-outline and Fabulous transparency chains against the scaled target, and takes the reactive
 * mask's snapshots.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = {"initOutline", "initTransparency"}, at = @At("HEAD"))
    private void temporal$beginInit(CallbackInfo ci) {
        Upscaler.pushInit();
    }

    @Inject(method = {"initOutline", "initTransparency"}, at = @At("RETURN"))
    private void temporal$endInit(CallbackInfo ci) {
        Upscaler.popInit();
    }

    /** Where NeoForge's AFTER_BLOCK_ENTITIES stage fires: the opaque scene is done (FSR 3's reactive mask). */
    @Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=destroyProgress"))
    private void temporal$afterBlockEntities(CallbackInfo ci) {
        ReactiveMask.afterOpaque();
    }

    /** Right after the translucent blocks, in both the Fabulous and the plain path (tripwire is drawn next). */
    @Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=string"))
    private void temporal$afterTranslucentBlocks(CallbackInfo ci) {
        ReactiveMask.afterTranslucent();
    }

    // The chains' full-size targets take the size passed here, so it must be the scaled size, not the window's.
    @ModifyVariable(method = "resize", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int temporal$scaledChainWidth(int width) {
        return Upscaler.chainWidth(width);
    }

    @ModifyVariable(method = "resize", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int temporal$scaledChainHeight(int height) {
        return Upscaler.chainHeight(height);
    }
}
