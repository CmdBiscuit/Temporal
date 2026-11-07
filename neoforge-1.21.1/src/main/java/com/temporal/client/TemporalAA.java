package com.temporal.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import com.temporal.Temporal;
import com.temporal.TemporalConfig;
import com.temporal.client.bridge.Bridge;
import com.temporal.client.bridge.FrameGeneration;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

/**
 * Temporal anti-aliasing at render resolution.
 *
 * <p>Each frame the projection is shifted by a sub-pixel Halton offset. After the level is drawn (before the hand),
 * the resolve shader reprojects last frame's result using the depth buffer and camera movement, clips it to the
 * current neighbourhood's colour range, and blends. The result is copied back into the scene so the hand, entity
 * outlines and the upscaler all see the anti-aliased image.
 */
public final class TemporalAA {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int JITTER_PHASES = 8;
    /** Fraction of the history kept each frame; higher is smoother but slower to react. */
    private static final float FEEDBACK = 0.9F;
    /** Camera jumps beyond this many blocks (teleports, respawns) discard the history. */
    private static final double RESET_DISTANCE = 32.0;

    /** Non-zero switches the resolve to a diagnostic view; for debugging. */
    public static int debugMode;

    @Nullable private static ShaderInstance resolveShader;
    @Nullable private static TextureTarget history;
    @Nullable private static TextureTarget output;
    @Nullable private static TextureTarget debugTarget;
    private static boolean historyValid;
    private static boolean bridgeHistoryValid;
    private static int phase;
    private static float jitterX;
    private static float jitterY;
    private static final Matrix4f unjitteredProjection = new Matrix4f();
    private static final Matrix4f previousViewProjection = new Matrix4f();
    private static Vec3 previousCamera = Vec3.ZERO;
    @Nullable private static ClientLevel previousLevel;

    public static boolean isEnabled() {
        try {
            return TemporalConfig.ENABLED.get() && TemporalConfig.ANTI_ALIASING.get() == TemporalConfig.AntiAliasing.TAA;
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    /** Applies this frame's sub-pixel jitter to the level projection, in place. */
    public static Matrix4f jitterProjection(Matrix4f projection) {
        if (Upscaler.isNestedLevel()) {
            // A portal's view drawn into this frame (Immersive Portals) shares the frame's jitter.
            return applyJitter(projection);
        }
        unjitteredProjection.set(projection);
        boolean bridge = Bridge.isRequested();
        if (!bridge && (!isEnabled() || resolveShader == null)) {
            jitterX = 0.0F;
            jitterY = 0.0F;
            return projection;
        }
        phase = (phase + 1) % (bridge ? Bridge.jitterPhases() : JITTER_PHASES);
        jitterX = halton(phase + 1, 2) - 0.5F;
        jitterY = halton(phase + 1, 3) - 0.5F;
        return applyJitter(projection);
    }

    private static Matrix4f applyJitter(Matrix4f projection) {
        RenderTarget scene = Upscaler.scaledTarget();
        return projection.mulLocal(new Matrix4f().translation(
                2.0F * jitterX / scene.width, 2.0F * jitterY / scene.height, 0.0F));
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        // Only the frame's own level pass: a portal's view (Immersive Portals) is part of the scene it's drawn into.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || Upscaler.isNestedLevel()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f viewProjection = new Matrix4f(unjitteredProjection).mul(event.getModelViewMatrix());
        EntityMotion.update(event.getCamera(), event.getPartialTick(), event.getFrustum());
        ClientLevel level = Minecraft.getInstance().level;
        boolean cut = level != previousLevel || camera.distanceToSqr(previousCamera) > RESET_DISTANCE * RESET_DISTANCE;
        if (!Bridge.isRequested()) {
            Bridge.releaseIfIdle();
        }

        if (Bridge.isRequested() && Bridge.evaluate(Upscaler.scaledTarget(), Upscaler.mainTarget(), unjitteredProjection,
                event.getModelViewMatrix(), previousViewProjection, camera.subtract(previousCamera), event.getCamera(),
                jitterX, jitterY, cut || !bridgeHistoryValid)) {
            // DLSS / FSR 3 wrote the full-resolution frame; the hand is drawn on top of it at full resolution.
            bridgeHistoryValid = true;
            historyValid = false;
            Upscaler.finishLevelEarly();
        } else if (!isEnabled() || resolveShader == null) {
            historyValid = false;
        } else {
            bridgeHistoryValid = false;
            if (cut) {
                historyValid = false;
            }
            resolve(Upscaler.scaledTarget(), viewProjection, camera);
        }
        if (FrameGeneration.wanted()) {
            // Frame generation needs depth and motion vectors every frame, whichever upscaler (if any) runs.
            Bridge.prepareFrameGeneration(Upscaler.scaledTarget(), Upscaler.mainTarget(), unjitteredProjection,
                    event.getModelViewMatrix(), previousViewProjection, camera.subtract(previousCamera), event.getCamera(),
                    jitterX, jitterY, cut);
        }
        // The hand is drawn next, at full resolution into the main target (this also rebinds it: the bridge's passes
        // leave their own images bound, and the hand would land in frame generation's motion vectors).
        Upscaler.finishLevel();
        Upscaler.levelTarget().bindWrite(true);

        previousViewProjection.set(viewProjection);
        previousCamera = camera;
        previousLevel = level;
    }

    private static void resolve(RenderTarget scene, Matrix4f viewProjection, Vec3 camera) {
        history = ensureTarget(history, scene);
        output = ensureTarget(output, scene);

        resolveShader.setSampler("CurrentSampler", scene.getColorTextureId());
        resolveShader.setSampler("DepthSampler", scene.getDepthTextureId());
        resolveShader.setSampler("HistorySampler", history.getColorTextureId());
        resolveShader.safeGetUniform("InvViewProj").set(new Matrix4f(viewProjection).invert());
        resolveShader.safeGetUniform("PrevViewProj").set(previousViewProjection);
        resolveShader.safeGetUniform("CameraDelta").set(
                (float) (camera.x - previousCamera.x), (float) (camera.y - previousCamera.y), (float) (camera.z - previousCamera.z));
        resolveShader.safeGetUniform("Jitter").set(2.0F * jitterX / scene.width, 2.0F * jitterY / scene.height);
        resolveShader.safeGetUniform("HistoryValid").set(historyValid ? 1.0F : 0.0F);
        resolveShader.safeGetUniform("Feedback").set(FEEDBACK);
        resolveShader.safeGetUniform("DebugMode").set(0.0F);
        EntityMotion.apply(resolveShader);
        FullscreenPass.draw(resolveShader, output);

        if (debugMode != 0) {
            // Show the diagnostic, but keep the real resolve as next frame's history.
            debugTarget = ensureTarget(debugTarget, scene);
            resolveShader.safeGetUniform("DebugMode").set((float) debugMode);
            FullscreenPass.draw(resolveShader, debugTarget);
            FullscreenPass.blit(debugTarget, scene, GL30.GL_NEAREST);
        } else {
            FullscreenPass.blit(output, scene, GL30.GL_NEAREST);
        }
        scene.bindWrite(true);

        TextureTarget swap = history;
        history = output;
        output = swap;
        historyValid = true;
    }

    private static TextureTarget ensureTarget(@Nullable TextureTarget target, RenderTarget match) {
        if (target == null) {
            target = new TextureTarget(match.width, match.height, false, Minecraft.ON_OSX);
        } else if (target.width != match.width || target.height != match.height) {
            target.resize(match.width, match.height, Minecraft.ON_OSX);
        } else {
            return target;
        }
        FullscreenPass.makeHalfFloat(target);
        historyValid = false;
        return target;
    }

    private static float halton(int index, int base) {
        float result = 0.0F;
        float fraction = 1.0F;
        while (index > 0) {
            fraction /= base;
            result += fraction * (index % base);
            index /= base;
        }
        return result;
    }

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Temporal.MOD_ID, "temporal_taa"), DefaultVertexFormat.BLIT_SCREEN),
                    shader -> resolveShader = shader);
        } catch (IOException e) {
            LOGGER.error("Temporal: could not load the TAA shader, anti-aliasing disabled", e);
        }
    }

    private TemporalAA() {}
}
