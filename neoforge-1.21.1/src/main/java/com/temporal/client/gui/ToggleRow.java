package com.temporal.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.network.chat.Component;

/** An on/off setting with a pixel switch; the whole row toggles it. */
final class ToggleRow extends OptionRow {
    private static final long FADE_MS = 120;
    private final BooleanSupplier value;
    private final Consumer<Boolean> set;
    private long changedAt;

    ToggleRow(int x, int y, int width, Component label, Component description, BooleanSupplier applies,
              Supplier<Component> whyNot, BooleanSupplier value, Consumer<Boolean> set) {
        super(x, y, width, label, description, applies, whyNot);
        this.value = value;
        this.set = set;
    }

    private void toggle() {
        set.accept(!value.getAsBoolean());
        changedAt = Util.getMillis();
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        toggle();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (active && CommonInputs.selected(keyCode)) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            toggle();
            return true;
        }
        return false;
    }

    @Override
    void renderControl(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        boolean on = value.getAsBoolean();
        int x = getX() + getWidth() - 30;
        int y = getY() + (getHeight() - 12) / 2;
        // A quick cross-fade from the old switch position to the new one.
        float t = Math.min(1.0F, (Util.getMillis() - changedAt) / (float) FADE_MS);
        float dim = active ? 1.0F : 0.4F;
        if (t < 1.0F) {
            Look.translucent(graphics, dim * (1.0F - t));
            graphics.blitSprite(on ? Look.TOGGLE_OFF : Look.TOGGLE_ON, x, y, 24, 12);
        }
        Look.translucent(graphics, dim * t);
        graphics.blitSprite(on ? (lit() ? Look.TOGGLE_ON_HIGHLIGHTED : Look.TOGGLE_ON)
                : (lit() ? Look.TOGGLE_OFF_HIGHLIGHTED : Look.TOGGLE_OFF), x, y, 24, 12);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        Component text = valueText();
        graphics.drawString(font, text, x - 6 - font.width(text), getY() + (getHeight() - 8) / 2,
                !active ? Look.TEXT_DISABLED : on ? Look.CYAN : Look.TEXT_DIM);
    }

    @Override
    Component valueText() {
        return Component.translatable(value.getAsBoolean() ? "temporal.gui.on" : "temporal.gui.off");
    }
}
