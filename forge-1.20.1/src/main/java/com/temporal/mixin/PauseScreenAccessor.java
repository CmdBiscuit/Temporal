package com.temporal.mixin;

import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether the pause screen shows its menu (1.20.1 has no getter; F3+Esc pauses without one). */
@Mixin(PauseScreen.class)
public interface PauseScreenAccessor {
    @Accessor("showPauseMenu")
    boolean temporal$showPauseMenu();
}
