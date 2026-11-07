package com.temporal.client;

import com.temporal.TemporalConfig;
import net.minecraft.Util;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Dynamic resolution: lowers the render scale when the GPU can't draw the world fast enough for the target frame rate,
 * and raises it again (up to the Render Scale setting) when there's room. It goes by the GPU's own time for the level
 * pass (timer queries, read a few frames late so nothing waits), not by FPS: when the CPU holds the game back, a lower
 * resolution wouldn't help, so it stays put.
 */
public final class DynamicResolution {
    /** Never below this: NIS can't go further, and the picture gets too soft to be worth it. */
    static final double MIN_SCALE = 0.5;
    private static final double STEP = 0.05;
    /** Below this share of the frame budget the GPU has room to spare; above the upper one it's falling behind. */
    private static final double RAISE_BELOW = 0.70;
    private static final double LOWER_ABOVE = 0.92;
    private static final long RAISE_AFTER_MS = 1500;
    private static final long LOWER_AFTER_MS = 300;
    private static final int QUERIES = 4;

    /** Debug: GPU milliseconds as a function of the scale, in place of the measured time (a pretend heavy load). */
    public static java.util.function.DoubleUnaryOperator debugSimulatedGpu;

    private static int[] starts;
    private static int[] ends;
    private static int written;
    private static int read;
    private static double gpuMs = -1.0;
    private static double scale = -1.0;
    private static double ceiling = 1.0;
    private static long lastChange;
    private static boolean timing;

    /** The scale to render at, given the most it may use (Render Scale, or DLSS / FSR 3's Quality Mode). */
    static double scale(double ceiling) {
        int target = target();
        if (target == 0 || !applies()) {
            scale = -1.0;
            return ceiling;
        }
        DynamicResolution.ceiling = ceiling;
        if (scale < 0.0 || scale > ceiling) {
            scale = ceiling;
        }
        return Math.max(Math.min(MIN_SCALE, ceiling), scale);
    }

    static int target() {
        try {
            return TemporalConfig.DYNAMIC_RESOLUTION_TARGET.get();
        } catch (IllegalStateException notLoadedYet) {
            return 0;
        }
    }

    /** Whenever upscaling is on: every upscaler takes a render size below its usual one. */
    public static boolean applies() {
        try {
            return TemporalConfig.ENABLED.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    /** The outer level pass starts (after the scaled target is sized for this frame). */
    static void beginLevel() {
        if (target() == 0 || !applies()) {
            timing = false;
            return;
        }
        if (starts == null) {
            starts = new int[QUERIES];
            ends = new int[QUERIES];
            GL15.glGenQueries(starts);
            GL15.glGenQueries(ends);
        }
        if (written - read >= QUERIES) {
            timing = false; // the GPU is far behind: skip timing this frame rather than wait
            return;
        }
        GL33.glQueryCounter(starts[written % QUERIES], GL33.GL_TIMESTAMP);
        timing = true;
    }

    /** The outer level pass ends (scene drawn and upscaled): record, then read whatever finished and adjust. */
    static void endLevel() {
        if (!timing) {
            return;
        }
        GL33.glQueryCounter(ends[written % QUERIES], GL33.GL_TIMESTAMP);
        written++;
        timing = false;
        while (read < written && GL15.glGetQueryObjecti(ends[read % QUERIES], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            long start = GL33.glGetQueryObjecti64(starts[read % QUERIES], GL15.GL_QUERY_RESULT);
            long end = GL33.glGetQueryObjecti64(ends[read % QUERIES], GL15.GL_QUERY_RESULT);
            double ms = (end - start) / 1.0e6;
            if (debugSimulatedGpu != null) {
                ms = debugSimulatedGpu.applyAsDouble(scale);
            }
            gpuMs = gpuMs < 0.0 ? ms : gpuMs * 0.85 + ms * 0.15;
            read++;
        }
        adjust();
    }

    private static void adjust() {
        int target = target();
        if (gpuMs < 0.0 || scale < 0.0 || target == 0) {
            return;
        }
        double budget = 1000.0 / target;
        long now = Util.getMillis();
        double setting = ceiling;
        if (gpuMs > budget * LOWER_ABOVE && scale > MIN_SCALE + 1.0e-6 && now - lastChange >= LOWER_AFTER_MS) {
            // GPU time grows roughly with the pixel count, so aim straight for the scale that fits, a step at least.
            double fits = scale * Math.sqrt(budget * 0.85 / gpuMs);
            scale = Math.max(MIN_SCALE, Math.min(scale - STEP, Math.floor(fits / STEP) * STEP));
            lastChange = now;
        } else if (gpuMs < budget * RAISE_BELOW && scale < setting - 1.0e-6 && now - lastChange >= RAISE_AFTER_MS) {
            scale = Math.min(setting, scale + STEP);
            lastChange = now;
        }
    }

    /** The current scale, for debugging (-1 while it isn't running). */
    public static double currentScale() {
        return scale;
    }

    /** Timer queries issued and read so far (debugging). */
    public static String debugCounters() {
        return "written=" + written + " read=" + read;
    }

    /** GPU milliseconds for the level pass (smoothed), or -1 before the first reading. */
    public static double gpuMs() {
        return gpuMs;
    }

    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        int target = target();
        if (target == 0) {
            return;
        }
        event.getLeft().add(!applies() ? "Temporal Dynamic Resolution: off (upscaling is off)"
                : String.format("Temporal Dynamic Resolution: %d%% (target %d fps, GPU %.1f ms for the world)",
                        Math.round(Math.max(0.0, scale) * 100), target, gpuMs));
    }

    private DynamicResolution() {}
}
