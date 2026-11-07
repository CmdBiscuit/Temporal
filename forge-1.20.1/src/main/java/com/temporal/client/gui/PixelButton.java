package com.temporal.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** A button in the screen's own pixel style; {@code tab} buttons are flat and show which page is open. */
final class PixelButton extends AbstractButton {
    private final Runnable action;
    private final boolean tab;
    private boolean selected;

    PixelButton(int x, int y, int width, int height, Component message, Runnable action) {
        this(x, y, width, height, message, action, false);
    }

    PixelButton(int x, int y, int width, int height, Component message, Runnable action, boolean tab) {
        super(x, y, width, height, message);
        this.action = action;
        this.tab = tab;
    }

    PixelButton selected(boolean value) {
        selected = value;
        return this;
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean lit = active && isHoveredOrFocused();
        Look.Sprite sprite;
        if (tab) {
            sprite = selected ? Look.TAB_SELECTED : lit ? Look.TAB_HIGHLIGHTED : Look.TAB;
        } else {
            sprite = !active ? Look.BUTTON_DISABLED : lit ? Look.BUTTON_HIGHLIGHTED : Look.BUTTON;
        }
        Look.blit(graphics, sprite, getX(), getY(), getWidth(), getHeight());
        int color = !active ? Look.TEXT_DISABLED : tab && !selected && !lit ? Look.TEXT_DIM : Look.TEXT;
        // Text sits a pixel high on the tabs, above their underline.
        renderScrollingString(graphics, Minecraft.getInstance().font, getMessage(), getX() + 4, getY() - (tab ? 1 : 0),
                getX() + getWidth() - 4, getY() + getHeight() - (tab ? 1 : 0), color);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
