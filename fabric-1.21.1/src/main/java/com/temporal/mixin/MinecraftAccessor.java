package com.temporal.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The real main target, bypassing the scaled redirect in {@link MinecraftMixin}. */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("mainRenderTarget")
    RenderTarget temporal$mainRenderTarget();
}
