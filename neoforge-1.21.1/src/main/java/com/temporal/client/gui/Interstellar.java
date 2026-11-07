package com.temporal.client.gui;

import java.util.Random;
import net.minecraft.client.gui.GuiGraphics;

/**
 * What the frame generation bar turns into at 6x: a window onto deep space with a black hole at the handle,
 * Interstellar's Gargantua style. Stars stream past in three layers and bend around the hole, matter from the
 * accretion disk pours along the bar into it, and the hole glows. Everything is drawn in whole GUI pixels so it
 * matches the pixel-art sprites.
 */
final class Interstellar {
    private static final int STAR_COUNT = 150;
    private static final float[] LAYER_SPEED = {3.0F, 9.0F, 26.0F};
    private static final int[] STAR_COLORS = {0xFFFFFFFF, 0xFFCFE4FF, 0xFFFFE9C4, 0xFF9FD8FF};
    private static final int[] DISK_RAMP = {Look.EMBERS, Look.DEEP_ORANGE, Look.ORANGE, Look.GOLD, Look.HOT_WHITE};
    /** Radius around the hole inside which starlight is bent outward (a rough Einstein ring). */
    private static final float LENS_RADIUS = 15.0F;

    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final int[] starLayer = new int[STAR_COUNT];
    private final float[] starPhase = new float[STAR_COUNT];
    private final int[] starColor = new int[STAR_COUNT];

    Interstellar(long seed) {
        Random random = new Random(seed);
        for (int i = 0; i < STAR_COUNT; i++) {
            starX[i] = random.nextFloat();
            starY[i] = random.nextFloat();
            float layer = random.nextFloat();
            starLayer[i] = layer < 0.55F ? 0 : layer < 0.88F ? 1 : 2;
            starPhase[i] = random.nextFloat() * 6.2832F;
            starColor[i] = STAR_COLORS[random.nextInt(STAR_COLORS.length)];
        }
    }

    /**
     * Space, stars and the infalling stream inside {@code x, y, width, height}; {@code holeX, holeY} is where the black
     * hole sits. {@code time} is in seconds.
     */
    void renderSpace(GuiGraphics graphics, int x, int y, int width, int height, int holeX, int holeY, double time) {
        int middle = y + height / 2;
        graphics.fillGradient(x, y, x + width, middle, 0xFF02030A, 0xFF0B1238);
        graphics.fillGradient(x, middle, x + width, y + height, 0xFF0B1238, 0xFF02030A);
        // A faint violet nebula band drifting slowly along the bar.
        for (int column = x; column < x + width; column += 2) {
            float wave = (float) Math.sin(column * 0.045 - time * 0.35) * 0.5F + 0.5F;
            int alpha = (int) (wave * 34);
            if (alpha > 4) {
                int half = 1 + (int) (wave * (height / 4.0F));
                graphics.fill(column, middle - half, column + 2, middle + half, (alpha << 24) | 0x5A2C8A);
            }
        }
        renderStars(graphics, x, y, width, height, holeX, holeY, time);
        renderStream(graphics, x, x + width, holeX, holeY, height, time);
    }

    private void renderStars(GuiGraphics graphics, int x, int y, int width, int height, int holeX, int holeY, double time) {
        for (int i = 0; i < STAR_COUNT; i++) {
            int layer = starLayer[i];
            // Everything streams away from the hole we're falling toward: right to left.
            float sx = (float) (((starX[i] * width - time * LAYER_SPEED[layer]) % width + width) % width);
            float sy = 1 + starY[i] * (height - 2);
            float px = x + sx;
            float py = y + sy;
            // Light passing close to the hole is bent outward into a ring around it.
            float dx = px - holeX;
            float dy = (py - holeY) * 1.6F;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance < LENS_RADIUS) {
                if (distance < 2.0F) {
                    continue;
                }
                float pushed = distance + (LENS_RADIUS - distance) * 0.65F;
                px = holeX + dx / distance * pushed;
                py = holeY + dy / distance * pushed / 1.6F;
            }
            float twinkle = 0.55F + 0.45F * (float) Math.sin(time * (1.3 + starPhase[i] * 0.4) + starPhase[i]);
            float brightness = (0.35F + 0.3F * layer) * twinkle + (distance < LENS_RADIUS ? 0.35F : 0.0F);
            int color = Look.fade(starColor[i], Math.min(1.0F, brightness));
            int ix = (int) px;
            int iy = (int) py;
            if (iy < y || iy >= y + height) {
                continue;
            }
            graphics.fill(ix, iy, ix + 1, iy + 1, color);
            if (layer == 2) {
                // The nearest stars streak.
                graphics.fill(ix + 1, iy, ix + 3, iy + 1, Look.fade(color, 0.45F));
                graphics.fill(ix + 3, iy, ix + 5, iy + 1, Look.fade(color, 0.18F));
            } else if (layer == 1 && starPhase[i] < 0.5F && twinkle > 0.8F) {
                // Now and then a bright one flares into a four-point sparkle.
                int glint = Look.fade(color, (twinkle - 0.8F) * 2.5F);
                graphics.fill(ix - 1, iy, ix, iy + 1, glint);
                graphics.fill(ix + 1, iy, ix + 2, iy + 1, glint);
                graphics.fill(ix, iy - 1, ix + 1, iy, glint);
                graphics.fill(ix, iy + 1, ix + 1, iy + 2, glint);
            }
        }
    }

    /** Matter spiralling in along the bar, brightening and thickening toward the hole, in flowing clumps. */
    private void renderStream(GuiGraphics graphics, int left, int right, int holeX, int holeY, int height, double time) {
        int reach = Math.max(1, holeX - left);
        int maxHalf = Math.max(0, Math.min(2, height / 2 - 3));
        for (int column = left; column < right; column++) {
            float along = column <= holeX ? 1.0F - (holeX - column) / (float) reach : 1.0F - (column - holeX) / 18.0F;
            if (along <= 0.0F) {
                continue;
            }
            float base = along * along;
            // Clumps flow toward the hole and speed up as they get close.
            float flow = 0.5F + 0.5F * (float) Math.sin(column * 0.42 - time * (6.0 + 6.0 * base) + Math.sin(column * 0.07 + time) * 2.0);
            float intensity = base * (0.6F + 0.4F * flow);
            if (intensity < 0.06F) {
                continue;
            }
            int shade = intensity > 0.82F ? 4 : intensity > 0.58F ? 3 : intensity > 0.36F ? 2 : intensity > 0.18F ? 1 : 0;
            graphics.fill(column, holeY, column + 1, holeY + 1, DISK_RAMP[shade]);
            int half = intensity > 0.75F ? maxHalf : intensity > 0.35F ? Math.min(1, maxHalf) : 0;
            for (int row = 1; row <= half; row++) {
                int edge = DISK_RAMP[Math.max(0, shade - row)];
                graphics.fill(column, holeY - row, column + 1, holeY - row + 1, Look.fade(edge, 0.85F));
                graphics.fill(column, holeY + row, column + 1, holeY + row + 1, Look.fade(edge, 0.85F));
            }
        }
    }

    /** The warm glow around the hole, breathing slowly; {@code strength} 0..1. */
    static void renderGlow(GuiGraphics graphics, int holeX, int holeY, double time, float strength) {
        float pulse = 0.8F + 0.2F * (float) Math.sin(time * 2.4);
        int[] radii = {18, 14, 11, 8};
        float[] alphas = {0.06F, 0.09F, 0.14F, 0.22F};
        for (int i = 0; i < radii.length; i++) {
            int color = Look.fade(Look.ORANGE, alphas[i] * pulse * strength);
            int r = radii[i];
            for (int dy = -r / 2; dy <= r / 2; dy++) {
                // The glow is squashed vertically like the disk.
                int half = (int) Math.sqrt(Math.max(0, r * r - 4 * dy * dy));
                graphics.fill(holeX - half, holeY + dy, holeX + half + 1, holeY + dy + 1, color);
            }
        }
    }

    /** A few hot pixels racing along the hole's own disk, so the sprite never looks frozen. */
    static void renderDiskSparks(GuiGraphics graphics, int holeX, int holeY, double time, float alpha) {
        for (int i = 0; i < 3; i++) {
            double t = (time * 1.7 + i / 3.0) % 1.0;
            int offset = (int) Math.round((t * 2.0 - 1.0) * 8.0);
            int color = Look.fade(i == 0 ? Look.HOT_WHITE : Look.GOLD, alpha * (float) Math.sin(t * Math.PI));
            graphics.fill(holeX + offset, holeY, holeX + offset + 1, holeY + 1, color);
        }
    }

    /**
     * The leading edge while the bar is turning into space, travelling from the front of the bar to the back: a hot
     * white seam with light streaking back from it into the space it has opened, and a few sparkles.
     */
    static void renderSeam(GuiGraphics graphics, int seamX, int top, int bottom, double time) {
        for (int i = 0; i < 6; i++) {
            graphics.fill(seamX + i, top, seamX + i + 1, bottom, Look.fade(Look.HOT_WHITE, 0.55F * (1.0F - i / 6.0F)));
        }
        graphics.fill(seamX - 1, top - 2, seamX + 1, bottom + 2, Look.HOT_WHITE);
        Random random = new Random((long) (time * 30.0));
        for (int i = 0; i < 4; i++) {
            int y = top + random.nextInt(Math.max(1, bottom - top));
            int length = 6 + random.nextInt(18);
            graphics.fill(seamX + 1, y, seamX + length, y + 1, Look.fade(i % 2 == 0 ? Look.CYAN_LIGHT : Look.HOT_WHITE, 0.6F));
        }
        for (int i = 0; i < 3; i++) {
            int x = seamX - 2 + random.nextInt(5);
            int y = top - 3 + random.nextInt(Math.max(1, bottom - top + 6));
            int color = i == 0 ? Look.HOT_WHITE : Look.CYAN_LIGHT;
            graphics.fill(x - 1, y, x + 2, y + 1, color);
            graphics.fill(x, y - 1, x + 1, y + 2, color);
        }
    }
}
