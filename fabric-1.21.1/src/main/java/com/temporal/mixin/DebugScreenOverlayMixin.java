package com.temporal.mixin;

import com.temporal.client.DynamicResolution;
import com.temporal.client.bridge.FrameGeneration;
import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Temporal's lines on the F3 screen's left side (NeoForge has an event for this; Fabric doesn't). */
@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenOverlayMixin {
    @Inject(method = "getGameInformation", at = @At("RETURN"))
    private void temporal$debugText(CallbackInfoReturnable<List<String>> cir) {
        List<String> left = cir.getReturnValue();
        FrameGeneration.appendDebugText(left);
        DynamicResolution.appendDebugText(left);
    }
}
