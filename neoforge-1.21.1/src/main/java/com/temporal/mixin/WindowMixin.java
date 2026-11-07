package com.temporal.mixin;

import com.mojang.blaze3d.platform.Window;
import com.temporal.client.Upscaler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
abstract class WindowMixin {
    @Shadow private int framebufferWidth;
    @Shadow private int framebufferHeight;

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void temporal$scaledWidth(CallbackInfoReturnable<Integer> cir) {
        if (Upscaler.isScaling()) {
            cir.setReturnValue(Upscaler.scaled(this.framebufferWidth));
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void temporal$scaledHeight(CallbackInfoReturnable<Integer> cir) {
        if (Upscaler.isScaling()) {
            cir.setReturnValue(Upscaler.scaled(this.framebufferHeight));
        }
    }
}
