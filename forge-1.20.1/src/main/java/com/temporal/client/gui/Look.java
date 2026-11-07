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
    /**
     * A sprite in its own texture. 1.20.1 has no GUI sprite atlas, so the size and nine-slice border (the same on
     * every side) that the 1.20.2+ build reads from the .mcmeta files are given here; a border of 0 means stretched.
     */
    record Sprite(ResourceLocation texture, int width, int height, int border) {}

    static final Sprite PANEL = sprite("panel", 16, 16, 4);
    static final Sprite INSET = sprite("inset", 16, 16, 2);
    static final Sprite BUTTON = sprite("button", 16, 16, 3);
    static final Sprite BUTTON_HIGHLIGHTED = sprite("button_highlighted", 16, 16, 3);
    static final Sprite BUTTON_DISABLED = sprite("button_disabled", 16, 16, 3);
    static final Sprite TAB = sprite("tab", 16, 16, 3);
    static final Sprite TAB_HIGHLIGHTED = sprite("tab_highlighted", 16, 16, 3);
    static final Sprite TAB_SELECTED = sprite("tab_selected", 16, 16, 3);
    static final Sprite SLIDER_TRACK = sprite("slider_track", 16, 8, 2);
    static final Sprite SLIDER_HANDLE = sprite("slider_handle", 7, 14, 0);
    static final Sprite SLIDER_HANDLE_HIGHLIGHTED = sprite("slider_handle_highlighted", 7, 14, 0);
    static final Sprite TOGGLE_OFF = sprite("toggle_off", 24, 12, 0);
    static final Sprite TOGGLE_OFF_HIGHLIGHTED = sprite("toggle_off_highlighted", 24, 12, 0);
    static final Sprite TOGGLE_ON = sprite("toggle_on", 24, 12, 0);
    static final Sprite TOGGLE_ON_HIGHLIGHTED = sprite("toggle_on_highlighted", 24, 12, 0);
    static final Sprite ARROW_LEFT = sprite("arrow_left", 4, 7, 0);
    static final Sprite ARROW_RIGHT = sprite("arrow_right", 4, 7, 0);
    static final Sprite ICON = sprite("icon", 16, 16, 0);
    static final Sprite BLACK_HOLE = sprite("black_hole", 17, 17, 0);
    static final Sprite VIEWPORT_FRAME = sprite("viewport_frame", 16, 16, 3);

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

    private static Sprite sprite(String name, int width, int height, int border) {
        return new Sprite(Temporal.id("textures/gui/sprites/" + name + ".png"), width, height,
                border);
    }

    /**
     * Draws {@code sprite} at the given size, as 1.21.1's {@code blitSprite} does: stretched, or with a border as nine
     * slices whose corners keep their size and whose edges and middle repeat.
     */
    static void blit(GuiGraphics graphics, Sprite sprite, int x, int y, int width, int height) {
        ResourceLocation texture = sprite.texture();
        int w = sprite.width();
        int h = sprite.height();
        if (sprite.border() == 0 || width == w && height == h) {
            graphics.blit(texture, x, y, width, height, 0.0F, 0.0F, w, h, w, h);
            return;
        }
        int side = Math.min(sprite.border(), width / 2);
        int end = Math.min(sprite.border(), height / 2);
        int middleWidth = width - 2 * side;
        int middleHeight = height - 2 * end;
        int sourceWidth = w - 2 * side;
        int sourceHeight = h - 2 * end;
        int right = x + width - side;
        int bottom = y + height - end;
        graphics.blit(texture, x, y, 0, 0, side, end, w, h);
        graphics.blit(texture, right, y, w - side, 0, side, end, w, h);
        graphics.blit(texture, x, bottom, 0, h - end, side, end, w, h);
        graphics.blit(texture, right, bottom, w - side, h - end, side, end, w, h);
        tile(graphics, sprite, x + side, y, middleWidth, end, side, 0, sourceWidth, end);
        tile(graphics, sprite, x + side, bottom, middleWidth, end, side, h - end, sourceWidth, end);
        tile(graphics, sprite, x, y + end, side, middleHeight, 0, end, side, sourceHeight);
        tile(graphics, sprite, right, y + end, side, middleHeight, w - side, end, side, sourceHeight);
        tile(graphics, sprite, x + side, y + end, middleWidth, middleHeight, side, end, sourceWidth, sourceHeight);
    }

    /** Fills an area with one part of a sprite, repeated from the top-left (the last row and column cut short). */
    private static void tile(GuiGraphics graphics, Sprite sprite, int x, int y, int width, int height, int u, int v,
                             int tileWidth, int tileHeight) {
        if (tileWidth <= 0 || tileHeight <= 0) {
            return;
        }
        for (int dx = 0; dx < width; dx += tileWidth) {
            for (int dy = 0; dy < height; dy += tileHeight) {
                graphics.blit(sprite.texture(), x + dx, y + dy, u, v, Math.min(tileWidth, width - dx),
                        Math.min(tileHeight, height - dy), sprite.width(), sprite.height());
            }
        }
    }

    /** Text centred between {@code left} and {@code right}, scrolling back and forth when it doesn't fit. */
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

    /**
     * Sprites drawn from here on are see-through by {@code alpha} (and by their own alpha). Blending has to be switched
     * on for that: sprite blits don't, and the menu background switches it off.
     */
    static void translucent(GuiGraphics graphics, float alpha) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
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
