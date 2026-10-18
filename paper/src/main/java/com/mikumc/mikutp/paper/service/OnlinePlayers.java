package com.mikumc.mikutp.paper.service;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of online players for contexts that must not touch Bukkit's player
 * view: the sync bus flushes from an async thread, and Folia declares iteration
 * of {@code Bukkit.getOnlinePlayers()} undefined outside self-contained
 * main-thread uses.
 *
 * <p>Entries are maintained by the lifecycle listener, which runs on the
 * player's own region thread, so this map is the only place the plugin reads
 * "who is online" from an arbitrary thread.
 */
public final class OnlinePlayers {

    private final Map<UUID, Player> players = new ConcurrentHashMap<>();

    public void add(Player player) {
        players.put(player.getUniqueId(), player);
    }

    public void remove(UUID player) {
        players.remove(player);
    }

    /** Any tracked player, or null when nobody is online. */
    public Player any() {
        for (Player player : players.values()) {
            return player;
        }
        return null;
    }

    /**
     * Player with this id, or null. Safe from any thread: a plain map lookup.
     * Bukkit's own lookups iterate the live player list, which Folia confines
     * to the thread owning that region.
     */
    public Player byId(UUID player) {
        return players.get(player);
    }

    /** Case-insensitive name lookup over this registry; safe from any thread. */
    public Player byName(String name) {
        for (Player player : players.values()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    /** Snapshot of the tracked players; safe to iterate from any thread. */
    public List<Player> snapshot() {
        return new ArrayList<>(players.values());
    }
}
