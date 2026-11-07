package com.temporal.client;

import com.temporal.client.gui.TemporalScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** The settings button in Mod Menu's mod list (loaded only when Mod Menu is installed). */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TemporalScreen::new;
    }
}
