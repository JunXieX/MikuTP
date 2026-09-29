package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.data.Position;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Central teleport execution: warmup, local transfers, cross-server dispatch
 * via pending-teleport rows plus proxy connect messages, and /back tracking.
 */
public final class TeleportService {

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final MikuTPConfig config;
    private final Database database;
    private final MessageService messages;
    private final Effects effects;
    private final WarmupManager warmups;
    private final NetworkService network;
    private final String serverId;

    public TeleportService(JavaPlugin plugin, Tasks tasks, MikuTPConfig config, Database database,
                           MessageService messages, Effects effects, WarmupManager warmups, NetworkService network) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.config = config;
        this.database = database;
        this.messages = messages;
        this.effects = effects;
        this.warmups = warmups;
        this.network = network;
        this.serverId = config.crossServer.serverId;
    }

    public WarmupManager warmups() {
        return warmups;
    }

    /**
     * Sends a player to a stored position, local or remote. Safe to call from
     * any thread. {@code afterDispatch} runs on the player's entity thread once
     * a cross-server transfer has been handed to the proxy.
     */
    public void send(Player player, Position dest, PendingTeleport.Source source, String label, Runnable afterDispatch) {
        tasks.entity(player, () -> begin(player, dest, source, label, afterDispatch));
    }

    /** Teleports to a live location after warmup; the position is read at warmup end. */
    public void sendLocal(Player player, Supplier<Location> target, String label) {
        tasks.entity(player, () -> {
            recordBack(player);
            startWarmup(player, () -> teleportNow(player, target.get(), label));
        });
    }

    /** Applies a cross-server handoff for a player who just arrived on this server. */
    public void applyPending(UUID playerUuid) {
        tasks.async(() -> {
            PendingTeleport pending;
            try {
                pending = database.takePendingTeleport(playerUuid.toString()).orElse(null);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to read pending teleport for {}", playerUuid, e);
                return;
            }
            if (pending == null || pending.position == null) {
                return;
            }
            String destServer = pending.position.server;
            if (destServer != null && !destServer.isBlank() && !destServer.equals(serverId)) {
                return;
            }
            World world = Bukkit.getWorld(pending.position.world);
            if (world == null) {
                plugin.getSLF4JLogger().warn("Pending teleport targets unknown world {}", pending.position.world);
                return;
            }
            Location target = new Location(world, pending.position.x, pending.position.y,
                    pending.position.z, pending.position.yaw, pending.position.pitch);
            playerTeleportAsync(playerUuid, target, pending.source.name().toLowerCase());
        });
    }

    /**
     * Warms up a mover and then hands them to the proxy to join the server of
     * {@code anchorUuid}; the destination position comes from the pending
     * teleport row the acceptor's backend wrote. Used by cross-server tpa.
     */
    public void dispatchToAnchor(Player mover, String anchorUuid, Runnable afterDispatch) {
        tasks.entity(mover, () -> {
            recordBack(mover);
            startWarmup(mover, () -> {
                network.connectAnchor(mover, anchorUuid);
                if (afterDispatch != null) {
                    afterDispatch.run();
                }
            });
        });
    }

    /** /back entry point; the cooldown is enforced by the caller. */
    public void goBack(Player player) {
        tasks.async(() -> {
            Position back;
            try {
                back = database.getBack(player.getUniqueId().toString()).orElse(null);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to read back position", e);
                return;
            }
            if (back == null) {
                messages.send(player, "back.none");
                return;
            }
            messages.send(player, "back.going");
            send(player, back, PendingTeleport.Source.BACK, back.world, null);
        });
    }

    /** Records the /back position from a death event (runs on the region thread). */
    public void recordDeathBack(Player player) {
        if (!config.back.enabled || !config.back.saveOnDeath) {
            return;
        }
        Position pos = positionOf(player.getLocation());
        tasks.async(() -> {
            try {
                database.setBack(player.getUniqueId().toString(), pos);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to save death back position", e);
            }
        });
    }

    private void begin(Player player, Position dest, PendingTeleport.Source source, String label, Runnable afterDispatch) {
        recordBack(player);
        boolean local = dest.server == null || dest.server.isBlank() || dest.server.equals(serverId);
        if (local) {
            World world = Bukkit.getWorld(dest.world);
            if (world == null) {
                messages.send(player, "common.teleport-failed");
                return;
            }
            Location target = new Location(world, dest.x, dest.y, dest.z, dest.yaw, dest.pitch);
            messages.send(player, "common.teleporting", "target", label);
            startWarmup(player, () -> teleportNow(player, target, label));
            return;
        }
        if (!network.enabled()) {
            messages.send(player, "common.cross-disabled");
            return;
        }
        messages.send(player, "common.teleporting", "target", label);
        startWarmup(player, () -> dispatchCross(player, dest, source, afterDispatch));
    }

    private void startWarmup(Player player, Runnable action) {
        if (player.hasPermission("mikutp.bypass.warmup")) {
            action.run();
            return;
        }
        if (config.teleport.warmupSeconds > 0) {
            messages.send(player, "common.warmup.started", "seconds", String.valueOf(config.teleport.warmupSeconds));
        }
        warmups.start(player, action);
    }

    private void teleportNow(Player player, Location target, String label) {
        if (target == null) {
            messages.send(player, "common.teleport-failed");
            return;
        }
        player.teleportAsync(target).thenAccept(ok -> tasks.entity(player, () -> {
            if (Boolean.TRUE.equals(ok)) {
                messages.send(player, "common.teleported", "target", label);
                effects.teleport(player);
            } else {
                messages.send(player, "common.teleport-failed");
            }
        }));
    }

    private void playerTeleportAsync(UUID playerUuid, Location target, String label) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return;
        }
        player.teleportAsync(target).thenAccept(ok -> tasks.entity(player, () -> {
            if (Boolean.TRUE.equals(ok)) {
                messages.send(player, "common.teleported", "target", label);
                effects.teleport(player);
            }
        }));
    }

    private void dispatchCross(Player player, Position dest, PendingTeleport.Source source, Runnable afterDispatch) {
        UUID uuid = player.getUniqueId();
        tasks.async(() -> {
            try {
                Position stamped = new Position(dest.server, dest.world, dest.x, dest.y, dest.z, dest.yaw, dest.pitch);
                database.putPendingTeleport(new PendingTeleport(uuid.toString(), stamped, source, System.currentTimeMillis()));
                tasks.entity(player, () -> {
                    network.connect(player, dest.server);
                    if (afterDispatch != null) {
                        afterDispatch.run();
                    }
                });
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Cross-server dispatch failed", e);
                messages.send(player, "common.teleport-failed");
            }
        });
    }

    private void recordBack(Player player) {
        if (!config.back.enabled || !config.back.saveOnTeleport) {
            return;
        }
        Position pos = positionOf(player.getLocation());
        tasks.async(() -> {
            try {
                database.setBack(player.getUniqueId().toString(), pos);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to save back position", e);
            }
        });
    }

    private Position positionOf(Location loc) {
        return new Position(serverId, loc.getWorld() == null ? "world" : loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }
}
