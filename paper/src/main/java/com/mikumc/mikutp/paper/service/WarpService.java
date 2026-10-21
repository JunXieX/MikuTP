package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.data.Position;
import com.mikumc.mikutp.common.data.Warp;
import com.mikumc.mikutp.common.util.Validate;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Warps are scoped to the server that defines them; on a network every backend
 * keeps its own set.
 */
public final class WarpService {

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final MikuTPConfig config;
    private final Database database;
    private final MessageService messages;
    private final CooldownManager cooldowns;
    private final TeleportService teleports;
    private final String serverId;
    private volatile List<Warp> warps = List.of();
    /** True once the list has been read from the database. */
    private volatile boolean loaded;
    /** Warps are one shared list: serialise mutations so two admins cannot race it. */
    private final Object mutationLock = new Object();

    public WarpService(JavaPlugin plugin, Tasks tasks, MikuTPConfig config, Database database,
                       MessageService messages, CooldownManager cooldowns, TeleportService teleports) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.config = config;
        this.database = database;
        this.messages = messages;
        this.cooldowns = cooldowns;
        this.teleports = teleports;
        this.serverId = config.crossServer.serverId;
    }

    /** Initial load; never overwrites a list that a command already mutated. */
    public void load() {
        reload(false);
    }

    /** /mtp reload: re-reads the table, discarding the in-memory copy. */
    public void reload() {
        reload(true);
    }

    private void reload(boolean force) {
        tasks.async(() -> {
            try {
                synchronized (mutationLock) {
                    // Read inside the lock: a /setwarp that commits between the
                    // query and this assignment would otherwise be overwritten by
                    // the older snapshot the query already captured.
                    if (force || !loaded) {
                        warps = sorted(database.listWarps(serverId));
                        loaded = true;
                    }
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to load warps", e);
            }
        });
    }

    public List<Warp> warps() {
        return warps;
    }

    /** True once the warps table has been read; callers on an async thread may use
     * this to wait for the startup load before opening a menu. */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Warp list for display. The startup load runs async, so a menu opened in the
     * first moments may see an empty cache; kick a reload rather than blocking
     * the command thread on database I/O.
     */
    public List<Warp> warpsForDisplay() {
        if (!loaded) {
            load();
        }
        return warps;
    }

    /**
     * Authoritative warp list for a mutation or a lookup that must not miss a
     * row, reading the database on first use. Callers already hold the mutation
     * lock where applicable.
     */
    private List<Warp> warpsForUse() throws java.sql.SQLException {
        if (!loaded) {
            warps = sorted(database.listWarps(serverId));
            loaded = true;
        }
        return warps;
    }

    public void set(Player player, String name) {
        if (disabled(player)) {
            return;
        }
        if (!Validate.validName(config.home.namePattern, name)) {
            messages.send(player, "common.invalid-name");
            return;
        }
        // Lowercase, like homes: identifiers must never diverge on case.
        String wanted = name.toLowerCase(java.util.Locale.ROOT);
        // The position is entity state: capture it on the player's own thread.
        tasks.entity(player, () -> {
            Location loc = player.getLocation().clone();
            tasks.async(() -> {
                synchronized (mutationLock) {
                    try {
                        List<Warp> current = warpsForUse();
                        for (Warp existing : current) {
                            if (!existing.name.equals(wanted) && existing.name.equalsIgnoreCase(wanted)) {
                                database.deleteWarp(existing.name);
                            }
                        }
                        Position position = new Position(serverId, loc.getWorld() == null ? "world" : loc.getWorld().getName(),
                                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
                        Warp warp = new Warp(wanted, position, System.currentTimeMillis());
                        database.saveWarp(warp);
                        warps = sorted(replace(current, warp));
                        loaded = true;
                        messages.send(player, "warp.set", "name", wanted);
                    } catch (Exception e) {
                        plugin.getSLF4JLogger().warn("Failed to save warp", e);
                    }
                }
            });
        });
    }

    public void delete(Player player, String name) {
        if (disabled(player)) {
            return;
        }
        String wanted = name.toLowerCase(java.util.Locale.ROOT);
        tasks.async(() -> {
            synchronized (mutationLock) {
                try {
                    List<Warp> current = warpsForUse();
                    Warp match = null;
                    for (Warp warp : current) {
                        if (warp.name.equalsIgnoreCase(wanted)) {
                            match = warp;
                            break;
                        }
                    }
                    if (match == null || !database.deleteWarp(match.name)) {
                        messages.send(player, "warp.not-found", "name", wanted);
                        return;
                    }
                    warps = sorted(current.stream()
                            .filter(w -> !w.name.equalsIgnoreCase(wanted)).toList());
                    messages.send(player, "warp.deleted", "name", wanted);
                } catch (Exception e) {
                    plugin.getSLF4JLogger().warn("Failed to delete warp", e);
                }
            }
        });
    }

    public void go(Player player, String name) {
        if (disabled(player)) {
            return;
        }
        UUID id = player.getUniqueId();
        // Atomic check-and-burn: a separate remaining() check lets two racing
        // commands both through.
        long remaining = cooldowns.tryConsume(id, CooldownManager.Kind.WARP);
        if (remaining > 0) {
            messages.send(player, "common.cooldown", "seconds", String.valueOf(remaining));
            return;
        }
        tasks.async(() -> {
            Warp warp;
            try {
                synchronized (mutationLock) {
                    warp = find(warpsForUse(), name);
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to read warps", e);
                cooldowns.clear(id, CooldownManager.Kind.WARP);
                messages.send(player, "warp.not-found", "name", name);
                return;
            }
            if (warp == null) {
                // Nothing was teleported: give the cooldown back.
                cooldowns.clear(id, CooldownManager.Kind.WARP);
                messages.send(player, "warp.not-found", "name", name);
                return;
            }
            messages.send(player, "warp.going", "name", warp.name);
            teleports.send(player, warp.position, PendingTeleport.Source.WARP, warp.name, null);
        });
    }

    /** True (and tells the player) when the warp feature is switched off. */
    private boolean disabled(Player player) {
        if (config.warp.enabled) {
            return false;
        }
        messages.send(player, "warp.disabled");
        return true;
    }

    private static Warp find(List<Warp> warps, String name) {
        for (Warp warp : warps) {
            if (warp.name.equals(name)) {
                return warp;
            }
        }
        for (Warp warp : warps) {
            if (warp.name.equalsIgnoreCase(name)) {
                return warp;
            }
        }
        return null;
    }

    private static List<Warp> replace(List<Warp> warps, Warp warp) {
        List<Warp> out = new java.util.ArrayList<>(warps.stream()
                .filter(w -> !w.name.equalsIgnoreCase(warp.name)).toList());
        out.add(warp);
        return out;
    }

    private static List<Warp> sorted(List<Warp> warps) {
        return warps.stream()
                .sorted(Comparator.comparing(w -> w.name.toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }
}
