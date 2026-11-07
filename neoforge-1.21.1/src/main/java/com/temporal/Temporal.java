package com.temporal;

import com.temporal.client.DynamicResolution;
import com.temporal.client.FirstLaunch;
import com.temporal.client.FpsCounter;
import com.temporal.client.Hotkeys;
import com.temporal.client.PauseMenuButton;
import com.temporal.client.TemporalAA;
import com.temporal.client.Upscaler;
import com.temporal.client.bridge.Bridge;
import com.temporal.client.bridge.FrameGeneration;
import com.temporal.client.bridge.Presenter;
import com.temporal.client.bridge.ReactiveMask;
import com.temporal.client.gui.TemporalScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

@Mod(value = Temporal.MOD_ID, dist = Dist.CLIENT)
public final class Temporal {
    public static final String MOD_ID = "temporal";

    public Temporal(IEventBus modBus, ModContainer container) {
        FirstLaunch.checkSettingsFile();
        container.registerConfig(ModConfig.Type.CLIENT, TemporalConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new TemporalScreen(parent));
        modBus.addListener(Upscaler::registerShaders);
        modBus.addListener(TemporalAA::registerShaders);
        modBus.addListener(Bridge::registerShaders);
        // Lowest priority so other mods' AFTER_LEVEL drawing is anti-aliased too.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, TemporalAA::onRenderStage);
        // Also lowest: the opaque and translucent snapshots should include what other mods draw at those stages.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ReactiveMask::onRenderStage);
        NeoForge.EVENT_BUS.addListener(FrameGeneration::onDebugText);
        NeoForge.EVENT_BUS.addListener(DynamicResolution::onDebugText);
        NeoForge.EVENT_BUS.addListener(FpsCounter::onRenderGui);
        NeoForge.EVENT_BUS.addListener(FirstLaunch::onClientTick);
        modBus.addListener(Hotkeys::register);
        NeoForge.EVENT_BUS.addListener(Hotkeys::onClientTick);
        // Stop presenting through Vulkan (and frame generation) while the window still exists.
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> Presenter.shutdown());
        PauseMenuButton.register(NeoForge.EVENT_BUS);
    }
}
