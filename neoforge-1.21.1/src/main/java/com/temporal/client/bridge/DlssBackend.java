package com.temporal.client.bridge;

import com.temporal.TemporalConfig;
import org.lwjgl.system.MemoryUtil;

/** DLSS Super Resolution / DLAA through NVIDIA Streamline. */
final class DlssBackend implements Backend {
    /** Viewport 0, shared with frame generation (which must use the viewport the resources are tagged for). */
    static final int VIEWPORT_ID = 0;
    private static final int TAG_COUNT = 4;

    private final Streamline streamline;
    private final VulkanContext vulkan;
    private final float jitterSignX;
    private final float jitterSignY;
    private final long viewport;
    private final long options;
    private final long constants;
    private final long resources;
    private final long tags;
    private final long inputs;
    private int appliedMode = -1;
    private int appliedPreset = -1;
    private int appliedWidth;
    private int appliedHeight;
    private SharedImage taggedColor;
    private boolean imagesChanged;

    DlssBackend(Streamline streamline, VulkanContext vulkan, float[] jitterSign) {
        this.streamline = streamline;
        this.vulkan = vulkan;
        this.jitterSignX = jitterSign[0];
        this.jitterSignY = jitterSign[1];
        viewport = Streamline.allocate(Streamline.SIZE_VIEWPORT_HANDLE, Streamline.VIEWPORT_HANDLE, 1);
        MemoryUtil.memPutInt(viewport + 32, VIEWPORT_ID);
        options = Streamline.allocate(Streamline.SIZE_DLSS_OPTIONS, Streamline.DLSS_OPTIONS, 3);
        constants = Streamline.allocate(Streamline.SIZE_CONSTANTS, Streamline.CONSTANTS, 2);
        resources = MemoryUtil.nmemCalloc(TAG_COUNT, Streamline.SIZE_RESOURCE);
        tags = MemoryUtil.nmemCalloc(TAG_COUNT, Streamline.SIZE_RESOURCE_TAG);
        inputs = MemoryUtil.nmemCalloc(1 + TAG_COUNT, 8);
    }

    @Override
    public String displayName() {
        return "DLSS";
    }

    private static int slMode(TemporalConfig.QualityMode quality) {
        return switch (quality) {
            case PERFORMANCE -> 1;
            case BALANCED -> 2;
            case QUALITY -> 3;
            case ULTRA_PERFORMANCE -> 4;
            case NATIVE -> 6; // DLAA
        };
    }

    private static int preset() {
        try {
            return TemporalConfig.DLSS_MODEL.get().preset;
        } catch (IllegalStateException notLoadedYet) {
            return TemporalConfig.DlssModel.AUTOMATIC.preset;
        }
    }

    @Override
    public void configure(Frame frame) {
        // DLAA only takes a full-size picture (anything smaller comes out black): while dynamic resolution renders
        // below full size, DLSS runs in Quality mode instead, and back in DLAA once it's at full size again.
        TemporalConfig.QualityMode quality = frame.quality() == TemporalConfig.QualityMode.NATIVE
                && frame.renderWidth() < frame.output().width ? TemporalConfig.QualityMode.QUALITY : frame.quality();
        int mode = slMode(quality);
        int preset = preset();
        int outWidth = frame.output().width;
        int outHeight = frame.output().height;
        if (frame.color() != taggedColor) {
            describeTag(0, frame.color(), Streamline.BUFFER_SCALING_INPUT_COLOR);
            describeTag(1, frame.depth(), Streamline.BUFFER_DEPTH);
            describeTag(2, frame.motion(), Streamline.BUFFER_MOTION_VECTORS);
            describeTag(3, frame.output(), Streamline.BUFFER_SCALING_OUTPUT_COLOR);
            MemoryUtil.memPutAddress(inputs, viewport);
            for (int i = 0; i < TAG_COUNT; i++) {
                MemoryUtil.memPutAddress(inputs + 8L * (i + 1), tags + (long) i * Streamline.SIZE_RESOURCE_TAG);
            }
            taggedColor = frame.color();
            imagesChanged = true;
        }
        // This frame's picture is the top-left part of the input images (dynamic resolution); DLSS takes it per frame.
        for (int i = 0; i < 3; i++) {
            long tag = tags + (long) i * Streamline.SIZE_RESOURCE_TAG;
            MemoryUtil.memPutInt(tag + 56, frame.renderWidth());
            MemoryUtil.memPutInt(tag + 60, frame.renderHeight());
        }
        if (imagesChanged || mode != appliedMode || preset != appliedPreset || outWidth != appliedWidth || outHeight != appliedHeight) {
            release();
            Streamline.header(options, Streamline.DLSS_OPTIONS, 3);
            MemoryUtil.memPutInt(options + 32, mode);
            MemoryUtil.memPutInt(options + 36, outWidth);
            MemoryUtil.memPutInt(options + 40, outHeight);
            MemoryUtil.memPutFloat(options + 44, 0.0F);         // sharpness (deprecated)
            MemoryUtil.memPutFloat(options + 48, 1.0F);         // preExposure
            MemoryUtil.memPutFloat(options + 52, 1.0F);         // exposureScale
            MemoryUtil.memPutByte(options + 56, Streamline.FALSE); // colorBuffersHDR: Minecraft renders LDR
            // Render presets for DLAA, Quality, Balanced, Performance, Ultra Performance, Ultra Quality: the chosen
            // model everywhere, or 0 (eDefault) for NVIDIA's per-mode pick.
            for (int offset = 60; offset <= 80; offset += 4) {
                MemoryUtil.memPutInt(options + offset, preset);
            }
            MemoryUtil.memPutByte(options + 84, Streamline.FALSE); // useAutoExposure
            MemoryUtil.memPutByte(options + 85, Streamline.FALSE); // alphaUpscalingEnabled
            int result = streamline.dlssSetOptions(viewport, options);
            if (result != 0) {
                throw new IllegalStateException("slDLSSSetOptions: " + Streamline.resultName(result));
            }
            appliedMode = mode;
            appliedPreset = preset;
            appliedWidth = outWidth;
            appliedHeight = outHeight;
            imagesChanged = false;
        }
    }

    private void describeTag(int index, SharedImage image, int bufferType) {
        long resource = resources + (long) index * Streamline.SIZE_RESOURCE;
        image.describe(resource);
        long tag = tags + (long) index * Streamline.SIZE_RESOURCE_TAG;
        Streamline.header(tag, Streamline.RESOURCE_TAG, 1);
        MemoryUtil.memPutAddress(tag + 32, resource);
        MemoryUtil.memPutInt(tag + 40, bufferType);
        MemoryUtil.memPutInt(tag + 44, Streamline.LIFECYCLE_VALID_UNTIL_EVALUATE);
        MemoryUtil.memPutInt(tag + 48, 0);                  // extent: whole image (asserted offset 48)
        MemoryUtil.memPutInt(tag + 52, 0);
        MemoryUtil.memPutInt(tag + 56, image.width);
        MemoryUtil.memPutInt(tag + 60, image.height);
    }

    @Override
    public int record(long commandBuffer, Frame frame) {
        // One token and one set of constants per frame, shared with frame generation when it runs.
        long token = FrameGeneration.frameToken(streamline);
        SlConstants.write(constants, frame, jitterSignX, jitterSignY);
        int result = streamline.setConstants(constants, token, viewport);
        if (result == 0) {
            FrameGeneration.constantsSet();
            result = streamline.evaluate(Streamline.FEATURE_DLSS, token, inputs, 1 + TAG_COUNT, commandBuffer);
        }
        return result;
    }

    @Override
    public String resultName(int result) {
        return Streamline.resultName(result);
    }

    /**
     * Frees the DLSS instance immediately. Left to itself, Streamline defers releasing a replaced instance to its
     * present-time housekeeping, which only runs when frames are presented through Streamline (frame generation), so
     * otherwise every mode or size change would leak the old instance's VRAM.
     */
    @Override
    public void release() {
        if (appliedMode < 0) {
            return;
        }
        vulkan.waitIdle();
        streamline.freeResources(Streamline.FEATURE_DLSS, viewport);
        appliedMode = -1;
    }
}
