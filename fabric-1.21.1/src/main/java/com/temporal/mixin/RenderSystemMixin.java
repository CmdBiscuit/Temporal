package com.temporal.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.temporal.client.bridge.Presenter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets Temporal show the finished frame through Vulkan (needed for frame generation) instead of SwapBuffers. */
@Mixin(RenderSystem.class)
abstract class RenderSystemMixin {
    @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"))
    private static void temporal$swapBuffers(long window) {
        Presenter.swapBuffers(window);
    }
}
