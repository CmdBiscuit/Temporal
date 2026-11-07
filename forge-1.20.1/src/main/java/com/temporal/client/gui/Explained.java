package com.temporal.client.gui;

import net.minecraft.network.chat.Component;

/** A setting's widget that says what the setting does (and why it's greyed out); the settings screen shows it. */
interface Explained {
    Component explanation();
}
