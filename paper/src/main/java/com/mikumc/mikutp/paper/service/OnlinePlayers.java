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
 * <p>Entries (player object plus name) are maintained by the lifecycle listener,
 * which runs on the player's own region thread. Reading a {@link Player}'s name
 * is entity state access like any other, so names are captured at registration
 * time and served from this map — a lookup here never calls into a Player.
 */
public final class OnlinePlayers {

    /** Player reference plus the name captured on their region thread. */
    private record Entry(Player player, String name) {
    }

    private final Map<UUID, Entry> players = new ConcurrentHashMap<>();

    public void add(Player player) {
        players.put(player.getUniqueId(), new Entry(player, player.getName()));
    }

    public void remove(UUID player) {
        players.remove(player);
    }

    /** Any tracked player, or null when nobody is online. */
    public Player any() {
        for (Entry entry : players.values()) {
            return entry.player();
        }
        return null;
    }

    /**
     * Player with this id, or null. Safe from any thread: a plain map lookup.
     * Bukkit's own lookups iterate the live player list, which Folia confines
     * to the thread owning that region.
     */
    public Player byId(UUID player) {
        Entry entry = players.get(player);
        return entry == null ? null : entry.player();
    }

    /** Name captured when the player joined, or null when they are offline. */
    public String nameOf(UUID player) {
        Entry entry = players.get(player);
        return entry == null ? null : entry.name();
    }

    /** Case-insensitive name lookup over this registry; safe from any thread. */
    public Player byName(String name) {
        if (name == null) {
            return null;
        }
        for (Entry entry : players.values()) {
            if (entry.name().equalsIgnoreCase(name)) {
                return entry.player();
            }
        }
        return null;
    }

    /** Snapshot of the tracked players; safe to iterate from any thread. */
    public List<Player> snapshot() {
        List<Player> out = new ArrayList<>(players.size());
        for (Entry entry : players.values()) {
            out.add(entry.player());
        }
        return out;
    }

    /** Names captured at join time; used by command suggestions, which run off
     * the players' region threads. */
    public List<String> names() {
        List<String> out = new ArrayList<>(players.size());
        for (Entry entry : players.values()) {
            out.add(entry.name());
        }
        return out;
    }
}
