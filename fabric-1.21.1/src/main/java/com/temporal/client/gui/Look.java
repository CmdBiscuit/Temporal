package com.temporal.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.temporal.Temporal;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * The settings screen's sprites and colours. The sprites are hand-drawn pixel grids in tools/Make-GuiArt.ps1 (which
 * writes them to textures/gui/sprites); the colours here match that palette.
 */
final class Look {
    static final ResourceLocation PANEL = sprite("panel");
    static final ResourceLocation INSET = sprite("inset");
    static final ResourceLocation BUTTON = sprite("button");
    static final ResourceLocation BUTTON_HIGHLIGHTED = sprite("button_highlighted");
    static final ResourceLocation BUTTON_DISABLED = sprite("button_disabled");
    static final ResourceLocation TAB = sprite("tab");
    static final ResourceLocation TAB_HIGHLIGHTED = sprite("tab_highlighted");
    static final ResourceLocation TAB_SELECTED = sprite("tab_selected");
    static final ResourceLocation SLIDER_TRACK = sprite("slider_track");
    static final ResourceLocation SLIDER_HANDLE = sprite("slider_handle");
    static final ResourceLocation SLIDER_HANDLE_HIGHLIGHTED = sprite("slider_handle_highlighted");
    static final ResourceLocation TOGGLE_OFF = sprite("toggle_off");
    static final ResourceLocation TOGGLE_OFF_HIGHLIGHTED = sprite("toggle_off_highlighted");
    static final ResourceLocation TOGGLE_ON = sprite("toggle_on");
    static final ResourceLocation TOGGLE_ON_HIGHLIGHTED = sprite("toggle_on_highlighted");
    static final ResourceLocation ARROW_LEFT = sprite("arrow_left");
    static final ResourceLocation ARROW_RIGHT = sprite("arrow_right");
    static final ResourceLocation ICON = sprite("icon");
    static final ResourceLocation BLACK_HOLE = sprite("black_hole");
    static final ResourceLocation VIEWPORT_FRAME = sprite("viewport_frame");

    static final int TEXT = 0xFFE8F1FA;
    static final int TEXT_DIM = 0xFF9FB0C8;
    static final int TEXT_DISABLED = 0xFF56647A;
    static final int CYAN = 0xFF3FC6D9;
    static final int CYAN_LIGHT = 0xFF8FEAF5;
    static final int CYAN_DIM = 0xFF237F91;
    static final int CYAN_DARK = 0xFF164E5C;
    static final int ROW_HOVER = 0x223FC6D9;
    static final int SEPARATOR = 0xFF26354D;
    static final int GOLD = 0xFFFFD27A;
    static final int HOT_WHITE = 0xFFFFF6E0;
    static final int ORANGE = 0xFFFF9F3D;
    static final int DEEP_ORANGE = 0xFFC2561E;
    static final int EMBERS = 0xFF6E2A10;

    static final int ROW_HEIGHT = 20;

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath(Temporal.MOD_ID, name);
    }

    /**
     * Sprites drawn from here on are see-through by {@code alpha} (and by their own alpha). Blending has to be switched
     * on for that: sprite blits don't, and the menu background switches it off.
     */
    static void translucent(GuiGraphics graphics, float alpha) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
    }

    /**
     * Text centred between {@code left} and {@code right}, scrolling back and forth when it doesn't fit (vanilla's
     * {@code AbstractWidget.renderScrollingString}, which only widgets may call).
     */
    static void scrollingString(GuiGraphics graphics, Font font, Component text, int left, int top, int right,
                                int bottom, int color) {
        int width = font.width(text);
        int y = (top + bottom - font.lineHeight) / 2 + 1;
        int room = right - left;
        if (width <= room) {
            graphics.drawCenteredString(font, text, (left + right) / 2, y, color);
            return;
        }
        int overflow = width - room;
        double seconds = Util.getMillis() / 1000.0;
        double period = Math.max(overflow * 0.5, 3.0);
        double phase = Math.sin(Math.PI / 2.0 * Math.cos(Math.PI * 2.0 * seconds / period)) / 2.0 + 0.5;
        graphics.enableScissor(left, top, right, bottom);
        graphics.drawString(font, text, left - (int) Mth.lerp(phase, 0.0, overflow), y, color);
        graphics.disableScissor();
    }

    /** {@code color} with its alpha multiplied by {@code alpha} (0..1). */
    static int fade(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, alpha)));
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** Linear blend of two opaque colours. */
    static int mix(int from, int to, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private Look() {}
}
