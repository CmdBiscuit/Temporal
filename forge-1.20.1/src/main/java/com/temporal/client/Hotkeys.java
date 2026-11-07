package com.temporal.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.temporal.TemporalConfig;
import com.temporal.TemporalConfig.FrameGenerationMode;
import com.temporal.client.bridge.FrameGeneration;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;

/** Keys that switch upscaling and frame generation on and off in-game, for quick before/after comparisons. */
public final class Hotkeys {
    private static final String CATEGORY = "key.categories.temporal";
    private static final KeyMapping TOGGLE_UPSCALING = new KeyMapping("key.temporal.toggleUpscaling",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY);
    private static final KeyMapping TOGGLE_FRAME_GENERATION = new KeyMapping("key.temporal.toggleFrameGeneration",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY);

    /** The multiplier frame generation goes back to when switched on again (this session). */
    private static FrameGenerationMode lastFrameGeneration = FrameGenerationMode.X2;

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_UPSCALING);
        event.register(TOGGLE_FRAME_GENERATION);
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (TOGGLE_UPSCALING.consumeClick()) {
            boolean on = !TemporalConfig.ENABLED.get();
            change(TemporalConfig.ENABLED, on);
            tell(Component.translatable("temporal.hotkey.upscaling", on
                    ? Component.translatable("temporal.hotkey.on", TemporalConfig.UPSCALER.get().getTranslatedName())
                    : Component.translatable("temporal.hotkey.off")));
        }
        while (TOGGLE_FRAME_GENERATION.consumeClick()) {
            FrameGenerationMode current = TemporalConfig.FRAME_GENERATION.get();
            if (current != FrameGenerationMode.OFF) {
                lastFrameGeneration = current;
                change(TemporalConfig.FRAME_GENERATION, FrameGenerationMode.OFF);
                tell(Component.translatable("temporal.hotkey.frameGeneration", Component.translatable("temporal.hotkey.off")));
            } else if (FrameGeneration.method() == null) {
                tell(Component.translatable("temporal.hotkey.frameGeneration.unavailable", FrameGeneration.unavailableReason())
                        .withStyle(ChatFormatting.GOLD));
            } else {
                change(TemporalConfig.FRAME_GENERATION, lastFrameGeneration);
                tell(Component.translatable("temporal.hotkey.frameGeneration", lastFrameGeneration.getTranslatedName()));
            }
        }
    }

    private static <T> void change(net.minecraftforge.common.ForgeConfigSpec.ConfigValue<T> setting, T value) {
        FirstLaunch.settingsChanged();
        setting.set(value);
        TemporalConfig.SPEC.save();
    }

    private static void tell(Component message) {
        Minecraft.getInstance().gui.setOverlayMessage(message, false);
    }

    private Hotkeys() {}
}
