package com.temporal.client;

import com.temporal.TemporalConfig;
import com.temporal.client.bridge.Bridge;
import com.temporal.client.bridge.FrameGeneration;
import com.temporal.client.bridge.Presenter;
import com.temporal.client.bridge.ReactiveMask;
import com.temporal.client.gui.TemporalScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** Everything Temporal hooks up, on the client only (kept out of the mod class so a server never loads it). */
public final class TemporalClient {
    @SuppressWarnings("removal") // ModLoadingContext.get(), the only way before Forge 47.3.10
    public static void init(IEventBus modBus) {
        FirstLaunch.checkSettingsFile();
        ModLoadingContext context = ModLoadingContext.get();
        context.registerConfig(ModConfig.Type.CLIENT, TemporalConfig.SPEC);
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new TemporalScreen(parent)));
        modBus.addListener(Upscaler::registerShaders);
        modBus.addListener(TemporalAA::registerShaders);
        modBus.addListener(Bridge::registerShaders);
        // Lowest priority so other mods' AFTER_LEVEL drawing is anti-aliased too.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, TemporalAA::onRenderStage);
        // Also lowest: the opaque and translucent snapshots should include what other mods draw at those stages.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, ReactiveMask::onRenderStage);
        MinecraftForge.EVENT_BUS.addListener(FrameGeneration::onDebugText);
        MinecraftForge.EVENT_BUS.addListener(DynamicResolution::onDebugText);
        MinecraftForge.EVENT_BUS.addListener(FpsCounter::onRenderGui);
        MinecraftForge.EVENT_BUS.addListener(FirstLaunch::onClientTick);
        modBus.addListener(Hotkeys::register);
        MinecraftForge.EVENT_BUS.addListener(Hotkeys::onClientTick);
        // Stop presenting through Vulkan (and frame generation) while the window still exists.
        MinecraftForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> Presenter.shutdown());
        PauseMenuButton.register(MinecraftForge.EVENT_BUS);
    }

    private TemporalClient() {}
}
