package com.temporal.client;

import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Iris shader packs, looked up reflectively (Temporal doesn't depend on Iris).
 *
 * <p>With a pack active, Iris draws the world through the pack and writes the finished image into the main target
 * before Temporal's level hooks run, so the upscalers and frame generation see the pack's final image and depth like
 * they see vanilla's. The difference that matters is the first-person hand: Iris draws it into that image itself,
 * with its depth squeezed into 0.4375..0.5625 ({@code HandRenderer.DEPTH} = 0.125) to keep it in front of the world,
 * instead of drawing it afterwards on a cleared depth buffer the way vanilla does.
 */
public final class ShaderPacks {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Depth below which a pixel is Iris's first-person hand (the same test shader packs use). */
    private static final float IRIS_HAND_DEPTH = 0.56F;

    /** Debug switch: give a shader pack's hand the world's motion (to see what recognising it does). */
    public static boolean debugIgnoreHand;

    @Nullable private static MethodHandle inUse;
    private static boolean looked;

    public static boolean inUse() {
        if (!looked) {
            looked = true;
            inUse = find();
        }
        if (inUse == null) {
            return false;
        }
        try {
            return (boolean) inUse.invokeExact();
        } catch (Throwable t) {
            LOGGER.warn("Temporal: could not ask Iris whether a shader pack is active", t);
            inUse = null;
            return false;
        }
    }

    /** For the motion shaders' {@code HandDepth}: pixels nearer than this move with the camera. 0 = no such pixels. */
    public static float handDepth() {
        return inUse() && !debugIgnoreHand ? IRIS_HAND_DEPTH : 0.0F;
    }

    @Nullable
    private static MethodHandle find() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup().findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class))
                    .bindTo(instance);
        } catch (ClassNotFoundException noIris) {
            return null;
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.warn("Temporal: Iris is installed but its API could not be used; treating shader packs as off", e);
            return null;
        }
    }

    private ShaderPacks() {}
}
