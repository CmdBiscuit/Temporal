package com.temporal.client;

import com.mojang.blaze3d.platform.GlUtil;
import com.mojang.logging.LogUtils;
import com.temporal.Temporal;
import com.temporal.TemporalConfig;
import com.temporal.TemporalConfig.QualityMode;
import com.temporal.TemporalConfig.Upscaler;
import com.temporal.client.bridge.Bridge;
import java.nio.file.Files;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/**
 * A fresh install (no settings file yet) starts on the best upscaler this PC can run instead of the FSR 1 default:
 * DLSS on NVIDIA RTX cards, FSR 3 on other cards, FSR 1 when neither works. Existing settings are never changed.
 */
public final class FirstLaunch {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean fresh;
    private static boolean done;
    /** The upscaler in the settings is Temporal's pick, not the player's: if it can't run, try the next one quietly. */
    private static boolean picking;

    /** Before the settings file is loaded (and so created). */
    public static void checkSettingsFile() {
        fresh = !Files.exists(FabricLoader.getInstance().getConfigDir().resolve(Temporal.MOD_ID + "-client.toml"));
    }

    /** The first tick: OpenGL is up, so the graphics card is known. */
    public static void onClientTick() {
        if (!fresh || done) {
            return;
        }
        done = true;
        applyRecommended();
        LOGGER.info("Temporal: first launch on {} ({}), starting with {}", GlUtil.getRenderer(), GlUtil.getVendor(),
                TemporalConfig.UPSCALER.get());
    }

    /** The best upscaler for this PC at Quality (first launch, and the settings screen's Reset). */
    public static void applyRecommended() {
        Upscaler best = recommended();
        if (best != Upscaler.FSR) {
            TemporalConfig.UPSCALER.set(best);
            TemporalConfig.QUALITY_MODE.set(QualityMode.QUALITY);
            picking = true;
        }
        TemporalConfig.SPEC.save();
    }

    private static Upscaler recommended() {
        // DLSS and FSR 3 run through Vulkan with Windows-only interop; FSR 1 runs anywhere.
        if (!Bridge.osSupported()) {
            return Upscaler.FSR;
        }
        return GlUtil.getVendor().contains("NVIDIA") && GlUtil.getRenderer().contains("RTX") ? Upscaler.DLSS : Upscaler.FSR3;
    }

    /** The player changed a setting: what's selected is theirs now. */
    public static void settingsChanged() {
        picking = false;
    }

    /**
     * An upscaler can't run. If it was Temporal's pick, the settings move to the next best one and this returns true
     * (no need to tell the player); {@code fsr3Possible} is whether FSR 3 may still work here.
     */
    public static boolean fallBack(Upscaler failed, boolean fsr3Possible) {
        if (!picking || TemporalConfig.UPSCALER.get() != failed) {
            return false;
        }
        Upscaler next = failed == Upscaler.DLSS && fsr3Possible ? Upscaler.FSR3 : Upscaler.FSR;
        TemporalConfig.UPSCALER.set(next);
        picking = next != Upscaler.FSR;
        TemporalConfig.SPEC.save();
        LOGGER.info("Temporal: {} can't run on this PC, so it starts with {} instead", failed, next);
        return true;
    }

    private FirstLaunch() {}
}
