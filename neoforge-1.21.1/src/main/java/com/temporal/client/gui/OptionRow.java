package com.temporal.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * One setting: its name on the left and its control on the right, the whole row hoverable and focusable (the screen's
 * tooltip explains the setting). Rows that don't apply with the current settings are greyed out, with the reason first
 * in their explanation.
 */
abstract class OptionRow extends AbstractWidget implements Explained {
    private final Component label;
    private final Component description;
    private final BooleanSupplier applies;
    private final Supplier<Component> whyNot;
    private Component explanation;

    OptionRow(int x, int y, int width, Component label, Component description, BooleanSupplier applies,
              Supplier<Component> whyNot) {
        super(x, y, width, Look.ROW_HEIGHT, label);
        this.label = label;
        this.description = description;
        this.applies = applies;
        this.whyNot = whyNot;
        refresh();
    }

    /** Whether the row applies depends on other settings: re-check after any change. */
    void refresh() {
        active = applies.getAsBoolean();
        @Nullable Component reason = active ? null : whyNot.get();
        explanation = reason == null ? description
                : Component.empty().append(reason.copy().withStyle(ChatFormatting.GOLD)).append("\n\n").append(description);
    }

    @Override
    public Component explanation() {
        return explanation;
    }

    int controlWidth() {
        return Math.min(150, getWidth() / 2);
    }

    int controlX() {
        return getX() + getWidth() - controlWidth() - 6;
    }

    boolean lit() {
        return active && isHoveredOrFocused();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (lit()) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), Look.ROW_HOVER);
            graphics.fill(getX(), getY(), getX() + 1, getY() + getHeight(), Look.CYAN);
        }
        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, label, getX() + 8, getY() + (getHeight() - 8) / 2, active ? Look.TEXT : Look.TEXT_DISABLED);
        renderControl(graphics, font, mouseX, mouseY);
    }

    abstract void renderControl(GuiGraphics graphics, Font font, int mouseX, int mouseY);

    /** The current value, for narration. */
    abstract Component valueText();

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.translatable("temporal.gui.narration", label, valueText()));
        output.add(NarratedElementType.HINT, explanation);
        if (active) {
            output.add(NarratedElementType.USAGE, Component.translatable("temporal.gui.narration.usage"));
        }
    }
}
