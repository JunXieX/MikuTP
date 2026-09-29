package com.mikumc.mikutp.paper.service;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/**
 * Isolated PlaceholderAPI calls. This class is only touched when PlaceholderAPI
 * is installed, so the plugin runs fine without it.
 */
final class PapiSupport {

    private PapiSupport() {
    }

    static String parse(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    static boolean containsPlaceholders(String text) {
        return PlaceholderAPI.containsPlaceholders(text);
    }
}
