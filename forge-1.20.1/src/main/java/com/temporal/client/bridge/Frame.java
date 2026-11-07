package com.temporal.client.bridge;

import com.temporal.TemporalConfig;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * Everything an upscaler backend needs for one frame. Images are the shared Vulkan/OpenGL images; matrices are
 * unjittered and camera-relative; jitter is in render pixels. {@code reactive} is FSR 3's reactive mask, or null when
 * there's none this frame. The input images keep their largest size; this frame's picture fills their top-left
 * {@code renderWidth} x {@code renderHeight} (smaller while dynamic resolution has lowered the scale).
 */
record Frame(SharedImage color, SharedImage depth, SharedImage motion, SharedImage output, @Nullable SharedImage reactive,
             int renderWidth, int renderHeight,
             Matrix4f projection, Matrix4f viewProjection, Matrix4f previousViewProjection, Vec3 cameraDelta,
             Camera camera, float jitterX, float jitterY, boolean reset, float frameTimeMs,
             TemporalConfig.QualityMode quality, float sharpness) {}
