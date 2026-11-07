package com.temporal.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * A setting with evenly spaced steps: a recessed track that fills with cyan up to a pixel handle, the value on the
 * right. The handle glides to new positions; drag it, click the track or use the arrow keys.
 */
class StepSliderRow extends OptionRow {
    private static final int VALUE_WIDTH = 38;
    private final int steps;
    private final IntSupplier value;
    private final IntConsumer set;
    private final IntFunction<Component> name;
    private boolean dragging;
    private float shown = Float.NaN;
    private long lastFrame;

    StepSliderRow(int x, int y, int width, Component label, Component description, BooleanSupplier applies,
                  Supplier<Component> whyNot, int steps, IntSupplier value, IntConsumer set, IntFunction<Component> name) {
        super(x, y, width, label, description, applies, whyNot);
        this.steps = steps;
        this.value = value;
        this.set = set;
        this.name = name;
    }

    private int trackX() {
        return controlX();
    }

    private int trackWidth() {
        return controlWidth() - VALUE_WIDTH - 4;
    }

    private int handleCenter(float step) {
        return trackX() + 4 + Math.round(step / (steps - 1) * (trackWidth() - 8));
    }

    private void setFromMouse(double mouseX) {
        double t = (mouseX - (trackX() + 4)) / (trackWidth() - 8);
        int step = Mth.clamp((int) Math.round(t * (steps - 1)), 0, steps - 1);
        if (step != value.getAsInt()) {
            set.accept(step);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (active && visible && button == 0 && mouseY >= getY() && mouseY < getY() + getHeight()
                && mouseX >= trackX() - 2 && mouseX < trackX() + trackWidth() + 2) {
            dragging = true;
            setFromMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        if (dragging) {
            setFromMouse(mouseX);
        }
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        if (dragging) {
            dragging = false;
            playDownSound(Minecraft.getInstance().getSoundManager());
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!active || keyCode != GLFW.GLFW_KEY_LEFT && keyCode != GLFW.GLFW_KEY_RIGHT) {
            return false;
        }
        int step = Mth.clamp(value.getAsInt() + (keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1), 0, steps - 1);
        if (step != value.getAsInt()) {
            set.accept(step);
            playDownSound(Minecraft.getInstance().getSoundManager());
        }
        return true;
    }

    @Override
    void renderControl(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        int target = value.getAsInt();
        long now = Util.getMillis();
        // The handle eases toward the value instead of jumping.
        if (Float.isNaN(shown)) {
            shown = target;
        } else {
            float dt = Math.min(0.1F, (now - lastFrame) / 1000.0F);
            shown += (target - shown) * (1.0F - (float) Math.exp(-dt * 18.0F));
            if (Math.abs(target - shown) < 0.01F) {
                shown = target;
            }
        }
        lastFrame = now;

        int x = trackX();
        int width = trackWidth();
        int y = getY() + (getHeight() - 8) / 2;
        Look.translucent(graphics, active ? 1.0F : 0.5F);
        graphics.blitSprite(Look.SLIDER_TRACK, x, y, width, 8);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        int handle = handleCenter(shown);
        renderFill(graphics, x + 2, y + 2, handle, active);
        if (steps <= 8) {
            for (int i = 0; i < steps; i++) {
                int tick = handleCenter(i);
                graphics.fill(tick, y + 9, tick + 1, y + 11, i <= target && active ? Look.CYAN_DIM : Look.SEPARATOR);
            }
        }
        Look.translucent(graphics, active ? 1.0F : 0.5F);
        graphics.blitSprite(lit() || dragging ? Look.SLIDER_HANDLE_HIGHLIGHTED : Look.SLIDER_HANDLE, handle - 3,
                getY() + (getHeight() - 14) / 2, 7, 14);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        Component text = valueText();
        int right = getX() + getWidth() - 6;
        renderScrollingString(graphics, font, text, right - VALUE_WIDTH, getY(), right, getY() + getHeight(),
                active ? Look.CYAN_LIGHT : Look.TEXT_DISABLED);
    }

    /** The lit part of the track, 4 pixel rows from a highlight down to a shadow. */
    static void renderFill(GuiGraphics graphics, int left, int top, int right, boolean active) {
        if (right <= left) {
            return;
        }
        int[] rows = active ? new int[]{Look.CYAN_LIGHT, Look.CYAN, Look.CYAN, Look.CYAN_DIM}
                : new int[]{0xFF5C6B80, 0xFF3A4558, 0xFF3A4558, 0xFF263040};
        for (int i = 0; i < rows.length; i++) {
            graphics.fill(left, top + i, right, top + i + 1, rows[i]);
        }
    }

    @Override
    Component valueText() {
        return name.apply(value.getAsInt());
    }
}
