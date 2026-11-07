package com.temporal.client;

import com.temporal.Temporal;
import com.temporal.TemporalConfig;
import com.temporal.client.bridge.Bridge;
import com.temporal.client.bridge.Presenter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;

/** Fabric's client entrypoint: loads the settings and hooks everything up. */
public final class TemporalClient implements ClientModInitializer {
    /** A phase after every other mod's: their end-of-level drawing is anti-aliased and upscaled too. */
    private static final ResourceLocation LAST = ResourceLocation.fromNamespaceAndPath(Temporal.MOD_ID, "last");

    @Override
    public void onInitializeClient() {
        FirstLaunch.checkSettingsFile();
        TemporalConfig.SPEC.load(FabricLoader.getInstance().getConfigDir().resolve(Temporal.MOD_ID + "-client.toml"));
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            Upscaler.registerShaders(context);
            TemporalAA.registerShaders(context);
            Bridge.registerShaders(context);
        });
        WorldRenderEvents.END.addPhaseOrdering(Event.DEFAULT_PHASE, LAST);
        WorldRenderEvents.END.register(LAST, TemporalAA::onLevelRendered);
        HudRenderCallback.EVENT.register(FpsCounter::onRenderGui);
        Hotkeys.register();
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            FirstLaunch.onClientTick();
            Hotkeys.onClientTick();
        });
        // Stop presenting through Vulkan (and frame generation) while the window still exists.
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> Presenter.shutdown());
        PauseMenuButton.register();
    }
}
