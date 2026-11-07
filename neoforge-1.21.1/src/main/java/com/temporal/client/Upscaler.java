package com.temporal.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import com.temporal.Temporal;
import com.temporal.TemporalConfig;
import com.temporal.client.bridge.Bridge;
import com.temporal.client.nis.NisScaler;
import com.temporal.mixin.MinecraftAccessor;
import com.temporal.mixin.WindowAccessor;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

/**
 * Renders the world into a reduced-size target and upscales it into the real main target before the HUD is drawn.
 *
 * <p>While "scaling" is active (during level rendering, and while the level renderer builds its post chains),
 * {@code Minecraft.getMainRenderTarget()} and the window's framebuffer size report the scaled target instead, so
 * vanilla code (Fabulous transparency, entity outlines) sizes itself to the low-resolution image.
 */
public final class Upscaler {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable private static TextureTarget scaledTarget;
    @Nullable private static TextureTarget easuTarget;
    @Nullable private static ShaderInstance easuShader;
    @Nullable private static ShaderInstance rcasShader;
    private static boolean levelActive;
    private static boolean finishedEarly;
    private static boolean creatingTarget;
    private static int initDepth;
    /** Level passes under way: more than one while another mod renders the level again inside the frame's own. */
    private static int levelDepth;
    private static boolean warnedUnfinished;
    /** The main target's depth buffer when its stencil was last looked at (a new buffer means it was rebuilt). */
    private static int stencilCheckedFor = -1;

    public static boolean isScaling() {
        return levelActive || initDepth > 0;
    }

    /**
     * True during a level pass inside the frame's own one: Immersive Portals renders each portal's view like that. Such
     * a pass draws into the frame's scaled target like the rest of the scene; the frame is upscaled (and anti-aliased)
     * once, by the outer pass.
     */
    public static boolean isNestedLevel() {
        return levelDepth > 1;
    }

    /** A new frame: drop a level pass that never finished (another mod caught an exception mid-render). */
    public static void beginFrame() {
        if (levelDepth != 0) {
            if (!warnedUnfinished) {
                warnedUnfinished = true;
                LOGGER.warn("Temporal: a level render didn't finish; starting the next frame fresh");
            }
            levelDepth = 0;
            levelActive = false;
            finishedEarly = false;
        }
    }

    public static void pushInit() {
        initDepth++;
    }

    public static void popInit() {
        initDepth--;
    }

    public static double renderScale() {
        if (Bridge.isRequested()) {
            return DynamicResolution.scale(Bridge.renderScale());
        }
        try {
            return TemporalConfig.ENABLED.get() ? DynamicResolution.scale(TemporalConfig.RENDER_SCALE.get()) : 1.0;
        } catch (IllegalStateException notLoadedYet) {
            return 1.0;
        }
    }

    /** The largest render size for a window dimension under the current settings: dynamic resolution only goes below it. */
    public static int ceiling(int full) {
        double scale;
        if (Bridge.isRequested()) {
            scale = Bridge.renderScale();
        } else {
            try {
                scale = TemporalConfig.ENABLED.get() ? TemporalConfig.RENDER_SCALE.get() : 1.0;
            } catch (IllegalStateException notLoadedYet) {
                scale = 1.0;
            }
        }
        return Math.max(1, (int) Math.round(full * scale));
    }

    public static int scaled(int full) {
        return Math.max(1, (int) Math.round(full * renderScale()));
    }

    /** The low-resolution world target, created on first use at the current window size. */
    public static RenderTarget scaledTarget() {
        if (scaledTarget == null) {
            if (creatingTarget) {
                // Creating a target binds it, and Iris asks for the main target on every bind: that's this method
                // again before the target exists. The real main target is the right answer for that moment.
                return mainTarget();
            }
            creatingTarget = true;
            try {
                WindowAccessor window = (WindowAccessor) (Object) Minecraft.getInstance().getWindow();
                ensureSize(window.temporal$framebufferWidth(), window.temporal$framebufferHeight());
            } finally {
                creatingTarget = false;
            }
        }
        return scaledTarget;
    }

    /** Matches the scaled target to a window size; returns true when an existing target changed size. */
    private static boolean ensureSize(int fullWidth, int fullHeight) {
        int width = scaled(fullWidth);
        int height = scaled(fullHeight);
        if (scaledTarget == null) {
            scaledTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            scaledTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            if (mainTarget().isStencilEnabled()) {
                scaledTarget.enableStencil();
            }
            return false;
        }
        if (scaledTarget.width != width || scaledTarget.height != height) {
            scaledTarget.resize(width, height, Minecraft.ON_OSX);
            return true;
        }
        return false;
    }

    /** Minecraft's real full-resolution main target, even while the scaled redirect is active. */
    public static RenderTarget mainTarget() {
        return ((MinecraftAccessor) Minecraft.getInstance()).temporal$mainRenderTarget();
    }

    /**
     * The scene has already been upscaled into the main target (DLSS): stop redirecting now, so the rest of the level
     * pass (the first-person hand) draws straight into the full-resolution target.
     */
    public static void finishLevelEarly() {
        levelActive = false;
        finishedEarly = true;
        mainTarget().bindWrite(true);
    }

    /**
     * The scene is done (anti-aliased, frame generation's inputs taken): upscale it now, so the first-person hand is
     * drawn straight into the full-resolution main target. Mods that keep hold of the main target's textures for the
     * hand pass (Veil, with Sable / Create Aeronautics) then draw it where it's shown, not into a stale scaled image.
     */
    public static void finishLevel() {
        if (finishedEarly || !levelActive) {
            return;
        }
        upscale(scaledTarget, mainTarget());
        finishLevelEarly();
    }

    /** Where the end of the level pass (the first-person hand) is drawn: the main target once a GPU upscaler ran. */
    public static RenderTarget levelTarget() {
        return finishedEarly ? mainTarget() : scaledTarget();
    }

    /** Size the level renderer's post chains must use: the scaled target's, whatever size the caller asked for. */
    public static int chainWidth(int requested) {
        return scaledTarget != null ? scaledTarget.width : requested;
    }

    public static int chainHeight(int requested) {
        return scaledTarget != null ? scaledTarget.height : requested;
    }

    /** Window resized: resize before the level renderer resizes its post chains to match us. */
    public static void onResize(int fullWidth, int fullHeight) {
        if (scaledTarget != null) {
            ensureSize(fullWidth, fullHeight);
        }
    }

    public static void beginLevel() {
        if (++levelDepth > 1) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        WindowAccessor window = (WindowAccessor) (Object) minecraft.getWindow();
        int fullWidth = window.temporal$framebufferWidth();
        int fullHeight = window.temporal$framebufferHeight();
        scaledTarget();
        if (ensureSize(fullWidth, fullHeight)) {
            minecraft.levelRenderer.resize(scaledTarget.width, scaledTarget.height);
        }
        matchStencil();
        levelActive = true;
        scaledTarget.bindWrite(true);
        DynamicResolution.beginLevel();
    }

    /**
     * Mods can give Minecraft's main target a stencil buffer their own way (Immersive Portals clips each portal's view
     * with one), without NeoForge's flag: the scaled target the level is drawn into instead needs one as well.
     */
    private static void matchStencil() {
        RenderTarget main = mainTarget();
        if (scaledTarget.isStencilEnabled() || main.getDepthTextureId() == stencilCheckedFor) {
            return;
        }
        stencilCheckedFor = main.getDepthTextureId();
        // Bound only to ask; the caller binds the scaled target next.
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.frameBufferId);
        int stencil = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_STENCIL_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (main.isStencilEnabled() || stencil != GL11.GL_NONE) {
            scaledTarget.enableStencil();
        }
    }

    public static void endLevel() {
        if (levelDepth > 1) {
            levelDepth--;
            return;
        }
        levelDepth = 0;
        levelActive = false;
        RenderTarget main = mainTarget();
        if (finishedEarly) {
            finishedEarly = false;
        } else {
            upscale(scaledTarget, main);
        }
        main.bindWrite(true);
        DynamicResolution.endLevel();
    }

    private static void upscale(RenderTarget source, RenderTarget main) {
        boolean sameSize = source.width == main.width && source.height == main.height;
        TemporalConfig.Upscaler mode;
        float sharpness;
        try {
            mode = TemporalConfig.ENABLED.get() ? TemporalConfig.UPSCALER.get() : TemporalConfig.Upscaler.NEAREST;
            sharpness = TemporalConfig.ENABLED.get() ? TemporalConfig.SHARPNESS.get().floatValue() : 0.0F;
        } catch (IllegalStateException notLoadedYet) {
            mode = TemporalConfig.Upscaler.NEAREST;
            sharpness = 0.0F;
        }
        if (mode == TemporalConfig.Upscaler.DLSS || mode == TemporalConfig.Upscaler.FSR3) {
            // Only reached when that upscaler can't run here (off Windows, or it failed): FSR 1 stands in.
            mode = TemporalConfig.Upscaler.FSR;
        }
        if (mode == TemporalConfig.Upscaler.NIS) {
            if (NisScaler.upscale(source, main, sharpness)) {
                return;
            }
            // No OpenGL 4.3 (macOS), or more than 2x to upscale: FSR 1 stands in.
            mode = TemporalConfig.Upscaler.FSR;
        }

        if (mode != TemporalConfig.Upscaler.FSR || easuShader == null || rcasShader == null) {
            int filter = mode == TemporalConfig.Upscaler.NEAREST || sameSize ? GL30.GL_NEAREST : GL30.GL_LINEAR;
            FullscreenPass.blit(source, main, filter);
            return;
        }

        boolean sharpen = sharpness > 0.0F;
        if (sameSize) {
            if (sharpen) {
                rcas(source, main, sharpness);
            } else {
                FullscreenPass.blit(source, main, GL30.GL_NEAREST);
            }
        } else if (sharpen) {
            if (easuTarget == null) {
                easuTarget = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            } else if (easuTarget.width != main.width || easuTarget.height != main.height) {
                easuTarget.resize(main.width, main.height, Minecraft.ON_OSX);
            }
            easu(source, easuTarget);
            rcas(easuTarget, main, sharpness);
        } else {
            easu(source, main);
        }
    }

    private static void easu(RenderTarget source, RenderTarget destination) {
        easuShader.setSampler("InSampler", source.getColorTextureId());
        easuShader.safeGetUniform("OutSize").set((float) destination.width, (float) destination.height);
        FullscreenPass.draw(easuShader, destination);
    }

    private static void rcas(RenderTarget source, RenderTarget destination, float sharpness) {
        // AMD's RCAS takes sharpness in stops (0 = strongest); map the 0..1 slider onto 2..0 stops.
        float stops = 2.0F * (1.0F - sharpness);
        rcasShader.setSampler("InSampler", source.getColorTextureId());
        rcasShader.safeGetUniform("Sharpness").set((float) Math.pow(2.0, -stops));
        FullscreenPass.draw(rcasShader, destination);
    }

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Temporal.MOD_ID, "temporal_easu"), DefaultVertexFormat.BLIT_SCREEN),
                    shader -> easuShader = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Temporal.MOD_ID, "temporal_rcas"), DefaultVertexFormat.BLIT_SCREEN),
                    shader -> rcasShader = shader);
        } catch (IOException e) {
            LOGGER.error("Temporal: could not load FSR shaders, falling back to bilinear upscaling", e);
        }
    }

    private Upscaler() {}
}
