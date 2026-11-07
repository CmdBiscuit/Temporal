package com.temporal.client.nis;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import com.temporal.Temporal;
import com.temporal.client.FullscreenPass;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

/**
 * NVIDIA Image Scaling: NVIDIA's own scaler shader (nis_scaler.glsl, NIS_Scaler.h, a compute shader) run through OpenGL 4.3. It
 * upscales by up to 2x and sharpens in the same pass; the constants come from NVScalerUpdateConfig in NIS_Config.h.
 */
public final class NisScaler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BLOCK_WIDTH = 32;
    private static final int BLOCK_HEIGHT = 24;
    /** The shader's const_buffer: 28 four-byte fields, std140 (scalars pack tightly). */
    private static final int CONSTANTS_SIZE = 28 * 4;

    private static boolean failed;
    private static int program;
    private static int constants;
    private static int linearClamp;
    private static int scaleFilter;
    private static int usmFilter;
    @Nullable private static TextureTarget copy;

    /** Why NIS can't run on this PC, or null (it needs OpenGL 4.3 compute shaders; macOS stops at 4.1). */
    @Nullable
    public static String unavailableReason() {
        if (failed) {
            return "NIS failed to start (see the log)";
        }
        GLCapabilities caps = GL.getCapabilities();
        // Fabric keeps vanilla's OpenGL 3.2 context (NeoForge's loading screen asks for the newest). NVIDIA then reports
        // 3.2 but still offers what NIS uses as extensions.
        boolean supported = caps.OpenGL43
                || caps.GL_ARB_compute_shader && caps.GL_ARB_shader_image_load_store && caps.GL_ARB_sampler_objects;
        return supported ? null : "NIS needs OpenGL 4.3, which this PC's driver doesn't have";
    }

    /** NIS upscales by at most 2x per axis; below that it isn't used. */
    public static boolean canScale(RenderTarget source, RenderTarget destination) {
        return source.width * 2 >= destination.width && source.height * 2 >= destination.height
                && source.width <= destination.width && source.height <= destination.height;
    }

    /** Upscales {@code source} into {@code destination}'s colour texture (RGBA8); false if NIS can't run. */
    public static boolean upscale(RenderTarget source, RenderTarget destination, float sharpness) {
        if (unavailableReason() != null || !canScale(source, destination) || !ensureReady()) {
            return false;
        }
        writeConstants(sharpness, source.width, source.height, destination.width, destination.height);

        GlStateManager._glUseProgram(program);
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, constants);
        bindTexture(0, source.getColorTextureId(), linearClamp);
        bindTexture(1, scaleFilter, 0);
        bindTexture(2, usmFilter, 0);
        GL42.glBindImageTexture(0, destination.getColorTextureId(), 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);

        GL43.glDispatchCompute((destination.width + BLOCK_WIDTH - 1) / BLOCK_WIDTH,
                (destination.height + BLOCK_HEIGHT - 1) / BLOCK_HEIGHT, 1);
        // What reads the result next: textures (the frame-generation copy, blits) and framebuffer drawing (the hand, the GUI).
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_FRAMEBUFFER_BARRIER_BIT
                | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
        for (int unit = 2; unit >= 0; unit--) {
            bindTexture(unit, 0, 0);
        }
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, 0);
        GlStateManager._glUseProgram(0);
        return true;
    }

    /**
     * Sharpens {@code target} in place at its own size (NIS at 1:1; it can't read and write one image, so it reads a
     * copy). For DLSS's output, which has no sharpening of its own. False if NIS can't run here.
     */
    public static boolean sharpen(RenderTarget target, float sharpness) {
        if (unavailableReason() != null) {
            return false;
        }
        if (copy == null) {
            copy = new TextureTarget(target.width, target.height, false, Minecraft.ON_OSX);
        } else if (copy.width != target.width || copy.height != target.height) {
            copy.resize(target.width, target.height, Minecraft.ON_OSX);
        }
        FullscreenPass.blit(target, copy, GL11.GL_NEAREST);
        return upscale(copy, target, sharpness);
    }

    /** Through GlStateManager so Minecraft's record of bound textures stays right; samplers it doesn't track. */
    private static void bindTexture(int unit, int texture, int sampler) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture);
        GL33.glBindSampler(unit, sampler);
        if (unit == 0) {
            return;
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    /** NVScalerUpdateConfig (NIS_Config.h) for SDR, the whole texture as the viewport. */
    private static void writeConstants(float sharpness, int inWidth, int inHeight, int outWidth, int outHeight) {
        float slider = Math.max(0.0F, Math.min(1.0F, sharpness)) - 0.5F;
        float maxScale = slider >= 0.0F ? 1.25F : 1.75F;
        float minScale = slider >= 0.0F ? 1.25F : 1.0F;
        float limitScale = slider >= 0.0F ? 1.25F : 1.0F;
        float detectRatio = 2 * 1127.0F / 1024.0F;
        float detectThreshold = 64.0F / 1024.0F;
        float minContrastRatio = 2.0F;
        float maxContrastRatio = 10.0F;
        float sharpStartY = 0.45F;
        float sharpEndY = 0.9F;
        float strengthMin = Math.max(0.0F, 0.4F + slider * minScale * 1.2F);
        float strengthMax = 1.6F + slider * maxScale * 1.8F;
        float limitMin = Math.max(0.1F, 0.14F + slider * limitScale * 0.32F);
        float limitMax = 0.5F + slider * limitScale * 0.6F;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.malloc(CONSTANTS_SIZE);
            data.putFloat(detectRatio).putFloat(detectThreshold).putFloat(minContrastRatio)
                    .putFloat(1.0F / (maxContrastRatio - minContrastRatio));
            data.putFloat(1.0F).putFloat(1.0F / 255.0F).putFloat(sharpStartY).putFloat(1.0F / (sharpEndY - sharpStartY));
            data.putFloat(strengthMin).putFloat(strengthMax - strengthMin).putFloat(limitMin).putFloat(limitMax - limitMin);
            data.putFloat(inWidth / (float) outWidth).putFloat(inHeight / (float) outHeight);
            data.putFloat(1.0F / outWidth).putFloat(1.0F / outHeight).putFloat(1.0F / inWidth).putFloat(1.0F / inHeight);
            data.putInt(0).putInt(0).putInt(inWidth).putInt(inHeight);
            data.putInt(0).putInt(0).putInt(outWidth).putInt(outHeight);
            data.putFloat(0.0F).putFloat(0.0F);
            data.flip();
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, constants);
            GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, data);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        }
    }

    private static boolean ensureReady() {
        if (program != 0) {
            return true;
        }
        try {
            program = compile();
            constants = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, constants);
            GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, CONSTANTS_SIZE, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
            linearClamp = GL33.glGenSamplers();
            GL33.glSamplerParameteri(linearClamp, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(linearClamp, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(linearClamp, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(linearClamp, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            scaleFilter = filterTexture(NisFilters.SCALE);
            usmFilter = filterTexture(NisFilters.USM);
            LOGGER.info("Temporal: NVIDIA Image Scaling ready");
            return true;
        } catch (RuntimeException | IOException e) {
            failed = true;
            program = 0;
            LOGGER.error("Temporal: NVIDIA Image Scaling could not start - using FSR 1 instead", e);
            return false;
        }
    }

    /** A 64 x 8 table as 2 x 64 RGBA32F texels, one phase per row (the shader reads it with texelFetch). */
    private static int filterTexture(float[] table) {
        int texture = GlStateManager._genTexture();
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer data = stack.mallocFloat(table.length);
            data.put(table).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 2, 64, 0, GL11.GL_RGBA, GL11.GL_FLOAT, data);
        }
        GlStateManager._bindTexture(0);
        return texture;
    }

    private static int compile() throws IOException {
        String scaler;
        try (InputStream in = Temporal.class.getResourceAsStream("/assets/temporal/shaders/nis/nis_scaler.glsl")) {
            if (in == null) {
                throw new IOException("nis_scaler.glsl is missing from the jar");
            }
            scaler = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String source = """
                #version 430
                #define NIS_GLSL 1
                #define NIS_SCALER 1
                #define NIS_HDR_MODE 0
                #define NIS_USE_HALF_PRECISION 0
                layout(std140, binding = 0) uniform const_buffer {
                    float kDetectRatio; float kDetectThres; float kMinContrastRatio; float kRatioNorm;
                    float kContrastBoost; float kEps; float kSharpStartY; float kSharpScaleY;
                    float kSharpStrengthMin; float kSharpStrengthScale; float kSharpLimitMin; float kSharpLimitScale;
                    float kScaleX; float kScaleY; float kDstNormX; float kDstNormY; float kSrcNormX; float kSrcNormY;
                    uint kInputViewportOriginX; uint kInputViewportOriginY; uint kInputViewportWidth; uint kInputViewportHeight;
                    uint kOutputViewportOriginX; uint kOutputViewportOriginY; uint kOutputViewportWidth; uint kOutputViewportHeight;
                    float reserved0; float reserved1;
                };
                layout(binding = 0) uniform sampler2D in_texture;
                layout(binding = 1) uniform sampler2D coef_scaler;
                layout(binding = 2) uniform sampler2D coef_usm;
                layout(rgba8, binding = 0) uniform writeonly image2D out_texture;
                """ + scaler + """

                layout(local_size_x = NIS_THREAD_GROUP_SIZE) in;
                void main() {
                    NVScaler(gl_WorkGroupID.xy, gl_LocalInvocationID.x);
                }
                """;
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("NIS shader didn't compile: " + log);
        }
        int linked = GL20.glCreateProgram();
        GL20.glAttachShader(linked, shader);
        GL20.glLinkProgram(linked);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(linked);
            GL20.glDeleteProgram(linked);
            throw new IllegalStateException("NIS shader didn't link: " + log);
        }
        return linked;
    }

    private NisScaler() {}
}
