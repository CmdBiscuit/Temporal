package com.temporal.client.gui;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * The frame generation setting as one wide slider, Off to 6x. At 6x the handle collapses into a black hole and the bar
 * turns into space from there backward: a white-hot seam sweeps from the front of the bar to the back, opening the
 * track into a window of streaming stars with matter pouring into the hole ({@link Interstellar}). Leaving 6x pulls it
 * all back into the hole.
 */
final class FrameGenerationSlider extends AbstractWidget implements Explained {
    static final int HEIGHT = 58;
    /** How tall the bar gets once it has opened up into space. */
    private static final int SPACE_HEIGHT = 26;
    private static final int STEPS = 6;
    private static final int MAX = STEPS - 1;
    private static final float WARP_SECONDS = 0.9F;
    private static final float UNWARP_SECONDS = 0.5F;

    private final Component label;
    private final Component description;
    private final IntSupplier value;
    private final IntConsumer set;
    private final IntFunction<Component> name;
    /** The highest multiplier this PC can show (0 while that's unknown). */
    private final IntSupplier supported;
    private final Interstellar space = new Interstellar(0x6A7E5L);
    private boolean dragging;
    private float shown = Float.NaN;
    private float warp;
    private long lastFrame;

    FrameGenerationSlider(int x, int y, int width, Component label, Component description, IntSupplier value,
                          IntConsumer set, IntFunction<Component> name, IntSupplier supported) {
        super(x, y, width, HEIGHT, label);
        this.label = label;
        this.description = description;
        this.value = value;
        this.set = set;
        this.name = name;
        this.supported = supported;
    }

    @Override
    public Component explanation() {
        return description;
    }

    private int trackX() {
        return getX() + 8;
    }

    /** Leaves room on the right for the black hole, which is wider than the handle. */
    private int trackWidth() {
        return getWidth() - 26;
    }

    private int centerY() {
        return getY() + 28;
    }

    private int handleCenter(float step) {
        return trackX() + 5 + Math.round(step / MAX * (trackWidth() - 10));
    }

    private void change(int step) {
        int old = value.getAsInt();
        if (step == old) {
            return;
        }
        set.accept(step);
        if (step == MAX) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_POWER_SELECT, 0.7F, 0.45F));
        }
    }

    private void setFromMouse(double mouseX) {
        double t = (mouseX - (trackX() + 5)) / (trackWidth() - 10);
        change(Mth.clamp((int) Math.round(t * MAX), 0, MAX));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (active && visible && button == 0 && mouseY >= getY() + 12 && mouseY < getY() + 44
                && mouseX >= trackX() - 4 && mouseX < trackX() + trackWidth() + 8) {
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
        int step = Mth.clamp(value.getAsInt() + (keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1), 0, MAX);
        if (step != value.getAsInt()) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            change(step);
        }
        return true;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = lastFrame == 0 ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        double time = (now % 3_600_000L) / 1000.0;
        int target = value.getAsInt();
        if (Float.isNaN(shown)) {
            shown = target;
            warp = target == MAX ? 1.0F : 0.0F;
        }
        shown += (target - shown) * (1.0F - (float) Math.exp(-dt * 14.0F));
        if (Math.abs(target - shown) < 0.01F) {
            shown = target;
        }
        // Greyed out (it can't run here): no space window, whatever the setting says.
        warp = target == MAX && active ? Math.min(1.0F, warp + dt / WARP_SECONDS) : Math.max(0.0F, warp - dt / UNWARP_SECONDS);
        float eased = warp * warp * (3.0F - 2.0F * warp);

        Font font = Minecraft.getInstance().font;
        boolean lit = active && (isHoveredOrFocused() || dragging);
        if (lit) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), Look.ROW_HOVER);
            graphics.fill(getX(), getY(), getX() + 1, getY() + getHeight(), eased > 0.5F ? Look.GOLD : Look.CYAN);
        }
        graphics.drawString(font, label, getX() + 8, getY() + 5, active ? Look.TEXT : Look.TEXT_DISABLED);

        int x = trackX();
        int width = trackWidth();
        int center = centerY();
        int handle = handleCenter(shown);
        graphics.blitSprite(Look.SLIDER_TRACK, x, center - 5, width, 10);
        if (!active) {
            graphics.blitSprite(Look.SLIDER_HANDLE, handleCenter(0) - 3, center - 7, 7, 14);
            renderTicks(graphics, font, -1, 0.0F);
            graphics.fill(x - 4, center - 8, x + width + 12, center + 20, 0xA0101622);
            return;
        }
        renderFill(graphics, x + 2, center - 3, handle, time);
        renderTicks(graphics, font, target, eased);
        if (eased > 0.0F) {
            renderWarp(graphics, x, width, center, handle, eased, time);
        }
        // The black hole forms at the handle first and the space opens out behind it; leaving, it all falls back in.
        float pop = Mth.clamp(warp / 0.35F, 0.0F, 1.0F);
        if (pop < 1.0F) {
            Look.translucent(graphics, 1.0F - pop);
            graphics.blitSprite(lit ? Look.SLIDER_HANDLE_HIGHLIGHTED : Look.SLIDER_HANDLE, handle - 3, center - 7, 7, 14);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        if (pop > 0.0F) {
            float scale = easeOutBack(pop);
            graphics.pose().pushPose();
            graphics.pose().translate(handle, center, 0.0F);
            graphics.pose().scale(scale, scale, 1.0F);
            Look.translucent(graphics, Math.min(1.0F, pop * 1.5F));
            graphics.blitSprite(Look.BLACK_HOLE, -8, -8, 17, 17);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            graphics.pose().popPose();
            if (pop >= 1.0F) {
                Interstellar.renderDiskSparks(graphics, handle, center, time, 1.0F);
            }
        }
    }

    /** The lit part of the normal track, with a pulse running along it like frames being made. */
    private static void renderFill(GuiGraphics graphics, int left, int top, int right, double time) {
        if (right <= left) {
            return;
        }
        int[] rows = {Look.CYAN_LIGHT, Look.CYAN, Look.CYAN, Look.CYAN, Look.CYAN_DIM, Look.CYAN_DARK};
        for (int i = 0; i < rows.length; i++) {
            graphics.fill(left, top + i, right, top + i + 1, rows[i]);
        }
        int span = right - left;
        int pulse = left + (int) ((time * 90.0) % (span + 40)) - 20;
        int from = Math.max(left, pulse);
        int to = Math.min(right, pulse + 6);
        if (to > from) {
            graphics.fill(from, top, to, top + rows.length, 0x50FFFFFF);
            graphics.fill(Math.max(left, from - 2), top, from, top + rows.length, 0x22FFFFFF);
            graphics.fill(to, top, Math.min(right, to + 2), top + rows.length, 0x22FFFFFF);
        }
    }

    private void renderTicks(GuiGraphics graphics, Font font, int target, float eased) {
        int center = centerY();
        // The labels make way as the bar opens up.
        int labelY = center + 10 + Math.round(eased * (SPACE_HEIGHT / 2 + 6 - 10));
        int most = supported.getAsInt();
        for (int step = 0; step < STEPS; step++) {
            int x = handleCenter(step);
            boolean unsupported = step > 0 && most > 0 && step + 1 > most;
            int color;
            if (step == target) {
                color = step == MAX ? Look.mix(Look.CYAN_LIGHT, Look.GOLD, eased) : Look.CYAN_LIGHT;
            } else {
                color = unsupported ? Look.TEXT_DISABLED : Look.TEXT_DIM;
            }
            graphics.fill(x, center + 6, x + 1, center + 8, step <= target ? Look.CYAN_DIM : Look.SEPARATOR);
            Component text = name.apply(step);
            graphics.drawString(font, text, x - font.width(text) / 2 + (step == 0 ? 2 : 0), labelY, color);
        }
    }

    /** The bar as a window onto space, opened from the front (the 6x end) backward; {@code eased} 0..1. */
    private void renderWarp(GuiGraphics graphics, int x, int width, int center, int hole, float eased, double time) {
        int height = Math.round(Mth.lerp(eased, 10.0F, SPACE_HEIGHT));
        int top = center - height / 2;
        int left = x - 1;
        int right = x + width + 1;
        int seam = right - Math.round((right - left + 14) * eased);
        int clip = Math.max(seam, left);
        if (clip < right) {
            graphics.enableScissor(clip, top, right, top + height);
            space.renderSpace(graphics, left, top, right - left, height, hole, center, time);
            Interstellar.renderGlow(graphics, hole, center, time, eased);
            graphics.disableScissor();

            graphics.enableScissor(Math.max(seam - 2, left - 2), top - 2, right + 2, top + height + 2);
            // The frame firms up as the window opens, then breathes.
            float pulse = 0.75F + 0.25F * (float) Math.sin(time * 3.0);
            Look.translucent(graphics, (float) Math.pow(eased, 1.5) * pulse);
            graphics.blitSprite(Look.VIEWPORT_FRAME, left - 2, top - 2, right - left + 4, height + 4);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            graphics.disableScissor();
        }
        if (eased < 1.0F && seam >= left - 6) {
            Interstellar.renderSeam(graphics, seam, top, top + height, time);
        }
    }

    private static float easeOutBack(float t) {
        float c = 1.70158F;
        float u = t - 1.0F;
        return 1.0F + (c + 1.0F) * u * u * u + c * u * u;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.translatable("temporal.gui.narration", label, name.apply(value.getAsInt())));
        output.add(NarratedElementType.HINT, description);
        output.add(NarratedElementType.USAGE, Component.translatable("temporal.gui.narration.usage"));
    }
}
