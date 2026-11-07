package com.temporal.client;

import com.temporal.client.gui.IconButton;
import com.temporal.client.gui.TemporalScreen;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

/** Adds a "Temporal Settings" button under the pause menu. */
public final class PauseMenuButton {
    private static final int WIDTH = 204;
    private static final int HEIGHT = 20;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
                addButton(pause);
            }
        });
    }

    private static void addButton(PauseScreen pause) {
        // Sit just below the lowest full-width menu button; fall back to the top-left corner.
        int x = 6;
        int y = 6;
        int lowest = -1;
        List<AbstractWidget> buttons = Screens.getButtons(pause);
        for (AbstractWidget widget : buttons) {
            if (widget.getWidth() >= WIDTH && widget.getBottom() > lowest) {
                lowest = widget.getBottom();
                x = widget.getX();
            }
        }
        if (lowest >= 0 && lowest + 4 + HEIGHT <= pause.height) {
            y = lowest + 4;
        } else {
            x = 6;
        }

        buttons.add(new IconButton(x, y, WIDTH, HEIGHT, Component.translatable("temporal.pause.settings"),
                button -> Minecraft.getInstance().setScreen(new TemporalScreen(pause))));
    }

    private PauseMenuButton() {}
}
