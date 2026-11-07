package com.temporal.client.gui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * A setting with a few named values: arrows either side of the value step through them (left half / right-click
 * back, right half forward; the arrow keys too).
 */
final class CycleRow<T> extends OptionRow {
    private final List<T> values;
    private final Supplier<T> value;
    private final Consumer<T> set;
    private final Function<T, Component> name;

    CycleRow(int x, int y, int width, Component label, Component description, BooleanSupplier applies,
             Supplier<Component> whyNot, List<T> values, Supplier<T> value, Consumer<T> set, Function<T, Component> name) {
        super(x, y, width, label, description, applies, whyNot);
        this.values = values;
        this.value = value;
        this.set = set;
        this.name = name;
    }

    private void step(int direction) {
        int index = values.indexOf(value.get());
        set.accept(values.get(Math.floorMod(index + direction, values.size())));
    }

    @Override
    protected boolean isValidClickButton(int button) {
        return button == 0 || button == 1;
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        boolean back = button == 1 || mouseX >= controlX() && mouseX < controlX() + controlWidth() / 2;
        step(back ? -1 : 1);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!active) {
            return false;
        }
        int direction = keyCode == GLFW.GLFW_KEY_LEFT ? -1 : keyCode == GLFW.GLFW_KEY_RIGHT || CommonInputs.selected(keyCode) ? 1 : 0;
        if (direction == 0) {
            return false;
        }
        playDownSound(Minecraft.getInstance().getSoundManager());
        step(direction);
        return true;
    }

    @Override
    void renderControl(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        int x = controlX();
        int width = controlWidth();
        int y = getY() + 2;
        int height = getHeight() - 4;
        graphics.blitSprite(!active ? Look.BUTTON_DISABLED : lit() ? Look.BUTTON_HIGHLIGHTED : Look.BUTTON, x, y, width, height);
        int arrowY = y + (height - 7) / 2;
        boolean overLeft = lit() && mouseX < x + width / 2 && mouseX >= x;
        tint(graphics, !active ? Look.TEXT_DISABLED : overLeft ? Look.CYAN_LIGHT : lit() ? Look.CYAN : Look.TEXT_DIM);
        graphics.blitSprite(Look.ARROW_LEFT, x + 5, arrowY, 4, 7);
        tint(graphics, !active ? Look.TEXT_DISABLED : lit() && !overLeft && mouseX < x + width ? Look.CYAN_LIGHT
                : lit() ? Look.CYAN : Look.TEXT_DIM);
        graphics.blitSprite(Look.ARROW_RIGHT, x + width - 9, arrowY, 4, 7);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        renderScrollingString(graphics, font, valueText(), x + 12, y, x + width - 12, y + height,
                active ? Look.TEXT : Look.TEXT_DISABLED);
    }

    private static void tint(GuiGraphics graphics, int color) {
        graphics.setColor(((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, 1.0F);
    }

    @Override
    Component valueText() {
        return name.apply(value.get());
    }
}
