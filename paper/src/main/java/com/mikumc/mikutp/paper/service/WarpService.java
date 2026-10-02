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

    public void load() {
        tasks.async(() -> {
            try {
                warps = sorted(database.listWarps(serverId));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to load warps", e);
            }
        });
    }

    public List<Warp> warps() {
        return warps;
    }

    public void set(Player player, String name) {
        if (!Validate.validName(config.home.namePattern, name)) {
            messages.send(player, "common.invalid-name");
            return;
        }
        // Lowercase, like homes: identifiers must never diverge on case.
        String wanted = name.toLowerCase(java.util.Locale.ROOT);
        Location loc = player.getLocation().clone();
        tasks.async(() -> {
            try {
                for (Warp existing : warps) {
                    if (!existing.name.equals(wanted) && existing.name.equalsIgnoreCase(wanted)) {
                        database.deleteWarp(existing.name);
                    }
                }
                Position position = new Position(serverId, loc.getWorld() == null ? "world" : loc.getWorld().getName(),
                        loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
                Warp warp = new Warp(wanted, position, System.currentTimeMillis());
                database.saveWarp(warp);
                warps = sorted(replace(warps, warp));
                messages.send(player, "warp.set", "name", wanted);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to save warp", e);
            }
        });
    }

    public void delete(Player player, String name) {
        String wanted = name.toLowerCase(java.util.Locale.ROOT);
        tasks.async(() -> {
            try {
                boolean removed = database.deleteWarp(wanted);
                if (!removed) {
                    for (Warp warp : database.listWarps(serverId)) {
                        if (warp.name.equalsIgnoreCase(wanted)) {
                            removed = database.deleteWarp(warp.name);
                            break;
                        }
                    }
                }
                if (removed) {
                    warps = sorted(warps.stream().filter(w -> !w.name.equalsIgnoreCase(wanted)).toList());
                    messages.send(player, "warp.deleted", "name", wanted);
                } else {
                    messages.send(player, "warp.not-found", "name", wanted);
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to delete warp", e);
            }
        });
    }

    public void go(Player player, String name) {
        UUID id = player.getUniqueId();
        long remaining = cooldowns.remaining(id, CooldownManager.Kind.WARP);
        if (remaining > 0) {
            messages.send(player, "common.cooldown", "seconds", String.valueOf(remaining));
            return;
        }
        Warp warp = find(warps, name);
        if (warp == null) {
            messages.send(player, "warp.not-found", "name", name);
            return;
        }
        cooldowns.apply(id, CooldownManager.Kind.WARP);
        messages.send(player, "warp.going", "name", warp.name);
        teleports.send(player, warp.position, PendingTeleport.Source.WARP, warp.name, null);
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
        return warps.stream().sorted(Comparator.comparing(w -> w.name.toLowerCase())).toList();
    }
}
