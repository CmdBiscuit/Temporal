package com.temporal.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The real framebuffer size, bypassing the scaled redirect in {@link WindowMixin}. */
@Mixin(Window.class)
public interface WindowAccessor {
    @Accessor("framebufferWidth")
    int temporal$framebufferWidth();

    @Accessor("framebufferHeight")
    int temporal$framebufferHeight();
}
