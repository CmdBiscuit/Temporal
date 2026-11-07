package com.temporal.client;

import com.temporal.client.gui.IconButton;
import com.temporal.client.gui.TemporalScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Adds a "Temporal Settings" button under the pause menu. */
public final class PauseMenuButton {
    private static final int WIDTH = 204;
    private static final int HEIGHT = 20;

    public static void register(IEventBus gameBus) {
        gameBus.addListener(PauseMenuButton::onScreenInit);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pause) || !pause.showsPauseMenu()) {
            return;
        }

        // Sit just below the lowest full-width menu button; fall back to the top-left corner.
        int x = 6;
        int y = 6;
        int lowest = -1;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget widget && widget.getWidth() >= WIDTH && widget.getBottom() > lowest) {
                lowest = widget.getBottom();
                x = widget.getX();
            }
        }
        if (lowest >= 0 && lowest + 4 + HEIGHT <= pause.height) {
            y = lowest + 4;
        } else {
            x = 6;
        }

        event.addListener(new IconButton(x, y, WIDTH, HEIGHT, Component.translatable("temporal.pause.settings"),
                button -> pause.getMinecraft().setScreen(new TemporalScreen(pause))));
    }

    private PauseMenuButton() {}
}
