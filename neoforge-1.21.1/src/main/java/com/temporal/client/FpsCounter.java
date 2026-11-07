package com.temporal.client;

import com.temporal.TemporalConfig;
import com.temporal.client.bridge.FrameGeneration;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The optional FPS counter: frames that reach the screen, which with frame generation is the rendered rate times the
 * frames shown per rendered frame (Minecraft's own counter only sees rendered frames).
 */
public final class FpsCounter {
    /** Long enough that the number can be read, short enough to follow a change. */
    private static final long REFRESH_MS = 500;

    private static Component text = Component.empty();
    private static long refreshedAt;

    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled() || minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        long now = Util.getMillis();
        if (now - refreshedAt >= REFRESH_MS) {
            refreshedAt = now;
            int rendered = minecraft.getFps();
            int perFrame = FrameGeneration.isActive() ? Math.max(1, FrameGeneration.framesPresentedPerFrame()) : 1;
            text = perFrame > 1 ? Component.translatable("temporal.fps.generated", rendered * perFrame, rendered)
                    : Component.translatable("temporal.fps", rendered);
        }
        GuiGraphics graphics = event.getGuiGraphics();
        Font font = minecraft.font;
        graphics.fill(2, 2, 6 + font.width(text), 4 + font.lineHeight, 0x90000000);
        graphics.drawString(font, text, 4, 4, 0xFFFFFF, false);
    }

    private static boolean enabled() {
        try {
            return TemporalConfig.FPS_COUNTER.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    private FpsCounter() {}
}
