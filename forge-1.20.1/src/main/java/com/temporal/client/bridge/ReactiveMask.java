package com.temporal.client.bridge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.temporal.TemporalConfig;
import com.temporal.client.FullscreenPass;
import com.temporal.client.ShaderPacks;
import com.temporal.client.Upscaler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

/**
 * FSR 3's reactive mask: snapshots of the scene after the opaque blocks and entities, and after the translucent
 * blocks, compared with the finished scene (temporal_reactive.fsh). Off with an Iris shader pack, which draws in its
 * own order.
 */
public final class ReactiveMask {
    /** Debug switch: no mask, to compare against. */
    public static boolean debugDisabled;

    @Nullable private static TextureTarget opaque;
    @Nullable private static TextureTarget translucent;
    private static int opaqueFrame = -1;
    private static int translucentFrame = -1;

    public static void onRenderStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        boolean opaqueDone = stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        if (!opaqueDone && stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || Upscaler.isNestedLevel() || !Upscaler.isScaling() || !wanted()) {
            return;
        }
        RenderTarget scene = Upscaler.scaledTarget();
        if (opaqueDone) {
            opaque = snapshot(opaque, scene);
            opaqueFrame = FrameGeneration.frameIndex();
        } else {
            translucent = snapshot(translucent, scene);
            translucentFrame = FrameGeneration.frameIndex();
        }
        scene.bindWrite(false);
    }

    private static boolean wanted() {
        try {
            return !debugDisabled && TemporalConfig.ENABLED.get() && TemporalConfig.UPSCALER.get() == TemporalConfig.Upscaler.FSR3
                    && Bridge.isRequested() && !ShaderPacks.inUse();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    private static TextureTarget snapshot(@Nullable TextureTarget target, RenderTarget scene) {
        if (target == null) {
            target = new TextureTarget(scene.width, scene.height, false, Minecraft.ON_OSX);
        } else if (target.width != scene.width || target.height != scene.height) {
            target.resize(scene.width, scene.height, Minecraft.ON_OSX);
        }
        FullscreenPass.blit(scene, target, GL11.GL_NEAREST);
        return target;
    }

    /**
     * Writes this frame's mask into {@code mask} (top-down), or returns false when there's no opaque snapshot from this
     * frame (another upscaler, a shader pack, or a renderer that skips those stages). Without a translucent-blocks
     * snapshot, everything after the opaque scene counts as an effect.
     */
    static boolean write(RenderTarget scene, SharedImage mask, @Nullable ShaderInstance shader) {
        int frame = FrameGeneration.frameIndex();
        if (shader == null || opaque == null || opaqueFrame != frame || opaque.width != scene.width
                || opaque.height != scene.height) {
            return false;
        }
        boolean split = translucent != null && translucentFrame == frame && translucent.width == scene.width
                && translucent.height == scene.height;
        shader.setSampler("SceneSampler", scene.getColorTextureId());
        shader.setSampler("OpaqueSampler", opaque.getColorTextureId());
        shader.setSampler("TranslucentSampler", (split ? translucent : opaque).getColorTextureId());
        FullscreenPass.draw(shader, mask.target, scene.width, scene.height);
        return true;
    }

    private ReactiveMask() {}
}
