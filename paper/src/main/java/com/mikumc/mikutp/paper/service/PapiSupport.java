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

    /** Parses on the calling thread; a broken third-party expansion must never eat the message. */
    static String parse(Player player, String text) {
        try {
            return PlaceholderAPI.setPlaceholders(player, text);
        } catch (Exception e) {
            return text;
        }
    }

    static boolean containsPlaceholders(String text) {
        return PlaceholderAPI.containsPlaceholders(text);
    }
}
