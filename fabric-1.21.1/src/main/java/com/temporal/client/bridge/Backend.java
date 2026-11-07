package com.temporal.client.bridge;

/** An upscaler that runs on the shared Vulkan device (DLSS through Streamline, FSR 3 through FidelityFX). */
interface Backend {
    String displayName();

    /** Makes the upscaler match this frame's sizes and quality mode, recreating its instance when they change. */
    void configure(Frame frame);

    /** Records the upscale into the command buffer; returns 0 on success or the library's error code. */
    int record(long commandBuffer, Frame frame);

    String resultName(int result);

    /** Frees the upscaler instance. The caller guarantees the GPU is idle. */
    void release();
}
