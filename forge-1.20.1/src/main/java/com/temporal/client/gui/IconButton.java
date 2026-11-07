package com.temporal.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** The pause menu's "Temporal Settings" button: a normal menu button with Temporal's icon on its left. */
public final class IconButton extends Button {
    public IconButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        Look.blit(graphics, Look.ICON, getX() + 3, getY() + (getHeight() - 16) / 2, 16, 16);
    }
}
