package com.temporal.client;

/**
 * A render target that can have a stencil buffer (added to RenderTarget by RenderTargetMixin: NeoForge has this
 * built in, vanilla Minecraft doesn't).
 */
public interface StencilTarget {
    boolean temporal$isStencilEnabled();

    /** Recreates the target's depth buffer with stencil bits. */
    void temporal$enableStencil();
}
