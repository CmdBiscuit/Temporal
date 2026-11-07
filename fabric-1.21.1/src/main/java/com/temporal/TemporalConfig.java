package com.temporal;

import com.temporal.config.ConfigSpec;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Client settings, edited from the pause-menu button or the mod list. */
public final class TemporalConfig {
    public enum Upscaler {
        /** FSR 1 (EASU + RCAS), spatial; runs anywhere. */
        FSR,
        FSR3,
        DLSS,
        /** NVIDIA Image Scaling: spatial, up to 2x, OpenGL 4.3 compute (any vendor, not macOS). */
        NIS,
        BILINEAR,
        NEAREST;
        public Component getTranslatedName() {
            return Component.translatable("temporal.configuration.upscaler." + name().toLowerCase(Locale.ROOT));
        }
    }

    public enum AntiAliasing {
        OFF,
        TAA;
        public Component getTranslatedName() {
            return Component.translatable("temporal.configuration.antiAliasing." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Shared by DLSS and FSR 3. NATIVE is DLAA / FSR Native AA (anti-aliasing at full resolution). */
    public enum QualityMode {
        NATIVE,
        QUALITY,
        BALANCED,
        PERFORMANCE,
        ULTRA_PERFORMANCE;
        public Component getTranslatedName() {
            return Component.translatable("temporal.configuration.qualityMode." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Which DLSS model ("render preset") runs; AUTOMATIC lets NVIDIA pick per quality mode (Transformer K/M/L). */
    public enum DlssModel {
        AUTOMATIC(0),
        TRANSFORMER_K(11),
        TRANSFORMER_L(12),
        TRANSFORMER_M(13),
        TRANSFORMER_J(10),
        CNN(5);

        /** The {@code sl::DLSSPreset} value. */
        public final int preset;

        DlssModel(int preset) {
            this.preset = preset;
        }
        public Component getTranslatedName() {
            return Component.translatable("temporal.configuration.dlssModel." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Frame generation: how many frames are shown per rendered frame. */
    public enum FrameGenerationMode {
        OFF,
        X2,
        X3,
        X4,
        X5,
        X6;

        /** Generated frames inserted after each rendered one (0 when off). */
        public int generatedFrames() {
            return ordinal();
        }
        public Component getTranslatedName() {
            return Component.translatable("temporal.configuration.frameGeneration." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Which frame generation runs. AUTOMATIC: DLSS-G where the GPU has it, FSR 3 everywhere else. */
    public enum FrameGenerationMethod {
        AUTOMATIC,
        DLSS,
        FSR;
        public Component getTranslatedName() {
            return Component.translatable("temporal.configuration.frameGenerationMethod." + name().toLowerCase(Locale.ROOT));
        }
    }

    public static final ConfigSpec SPEC;
    public static final ConfigSpec.BooleanValue ENABLED;
    public static final ConfigSpec.DoubleValue RENDER_SCALE;
    public static final ConfigSpec.EnumValue<Upscaler> UPSCALER;
    public static final ConfigSpec.DoubleValue SHARPNESS;
    public static final ConfigSpec.DoubleValue DLSS_SHARPNESS;
    public static final ConfigSpec.EnumValue<AntiAliasing> ANTI_ALIASING;
    public static final ConfigSpec.EnumValue<QualityMode> QUALITY_MODE;
    public static final ConfigSpec.EnumValue<DlssModel> DLSS_MODEL;
    public static final ConfigSpec.EnumValue<FrameGenerationMode> FRAME_GENERATION;
    public static final ConfigSpec.EnumValue<FrameGenerationMethod> FRAME_GENERATION_METHOD;
    public static final ConfigSpec.BooleanValue FPS_COUNTER;
    public static final ConfigSpec.IntValue DYNAMIC_RESOLUTION_TARGET;
    /** The targets Dynamic Resolution offers (0 = off). */
    public static final int[] DYNAMIC_RESOLUTION_TARGETS = {0, 30, 45, 60, 75, 90, 120, 144, 165, 240};

    static {
        ConfigSpec.Builder builder = new ConfigSpec.Builder();
        ENABLED = builder
                .comment("Render the world at a reduced resolution and upscale it.")
                .translation("temporal.configuration.enabled")
                .define("enabled", true);
        RENDER_SCALE = builder
                .comment("Fraction of the window resolution the world is rendered at. 1.0 = native.")
                .translation("temporal.configuration.renderScale")
                .defineInRange("renderScale", 0.75, 0.25, 1.0);
        UPSCALER = builder
                .comment("How the low-resolution image is scaled back up to the window.")
                .translation("temporal.configuration.upscaler")
                .defineEnum("upscaler", Upscaler.FSR);
        SHARPNESS = builder
                .comment("FSR 1 / FSR 3 / NIS sharpening strength. 0 disables sharpening. Not used by DLSS.")
                .translation("temporal.configuration.sharpness")
                .defineInRange("sharpness", 0.8, 0.0, 1.0);
        DLSS_SHARPNESS = builder
                .comment("Sharpening after DLSS (NVIDIA Image Scaling's sharpener; DLSS has none of its own). 0 = off.")
                .translation("temporal.configuration.dlssSharpness")
                .defineInRange("dlssSharpness", 0.0, 0.0, 1.0);
        ANTI_ALIASING = builder
                .comment("Temporal anti-aliasing, applied at render resolution before upscaling.")
                .translation("temporal.configuration.antiAliasing")
                .defineEnum("antiAliasing", AntiAliasing.TAA);
        QUALITY_MODE = builder
                .comment("Render resolution for DLSS and FSR 3. NATIVE = DLAA / FSR Native AA (anti-aliasing only).")
                .translation("temporal.configuration.qualityMode")
                .defineEnum("qualityMode", QualityMode.QUALITY);
        DLSS_MODEL = builder
                .comment("DLSS model. AUTOMATIC = NVIDIA's pick per quality mode (Transformer K, M or L).")
                .translation("temporal.configuration.dlssModel")
                .defineEnum("dlssModel", DlssModel.AUTOMATIC);
        FRAME_GENERATION = builder
                .comment("Frame generation: frames shown per rendered frame. Works with any upscaler. DLSS Frame "
                        + "Generation needs NVIDIA RTX 40 or newer (3x-6x: RTX 50) and replaces V-Sync with a frame cap; "
                        + "FSR 3 Frame Generation runs on most GPUs, up to 2x.")
                .translation("temporal.configuration.frameGeneration")
                .defineEnum("frameGeneration", FrameGenerationMode.OFF);
        FRAME_GENERATION_METHOD = builder
                .comment("Which frame generation runs. AUTOMATIC = DLSS Frame Generation where the GPU supports it, "
                        + "AMD FSR 3 everywhere else.")
                .translation("temporal.configuration.frameGenerationMethod")
                .defineEnum("frameGenerationMethod", FrameGenerationMethod.AUTOMATIC);
        FPS_COUNTER = builder
                .comment("Shows the frame rate in the top-left corner: the frames that reach the screen, and with frame "
                        + "generation the rendered frames too (Minecraft's own counter only counts rendered frames).")
                .translation("temporal.configuration.fpsCounter")
                .define("fpsCounter", false);
        DYNAMIC_RESOLUTION_TARGET = builder
                .comment("Dynamic resolution: the rendered frame rate to hold by lowering the render resolution when the GPU "
                        + "can't keep up (down to 50%; Render Scale, or DLSS / FSR 3's Quality Mode, is the most it uses). "
                        + "0 = off.")
                .translation("temporal.configuration.dynamicResolution")
                .defineInRange("dynamicResolutionTarget", 0, 0, 360);
        SPEC = builder.build();
    }

    private TemporalConfig() {}
}
