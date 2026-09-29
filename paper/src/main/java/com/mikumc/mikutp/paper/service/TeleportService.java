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
    private final ProfileService profiles;
    private final String serverId;

    public TeleportService(JavaPlugin plugin, Tasks tasks, MikuTPConfig config, Database database,
                           MessageService messages, Effects effects, WarmupManager warmups, NetworkService network,
                           ProfileService profiles) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.config = config;
        this.database = database;
        this.messages = messages;
        this.effects = effects;
        this.warmups = warmups;
        this.network = network;
        this.profiles = profiles;
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

    /**
     * Teleports {@code mover} to {@code anchor}'s live location after warmup;
     * the position is read on the anchor's own region thread (Folia-safe).
     */
    public void sendLocalTo(Player mover, Player anchor, String label, boolean warmup) {
        tasks.entity(anchor, () -> {
            Player stable = Bukkit.getPlayer(anchor.getUniqueId());
            if (stable == null) {
                return;
            }
            Location location = stable.getLocation().clone();
            tasks.entity(mover, () -> {
                if (!mover.isOnline()) {
                    return;
                }
                recordBack(mover);
                if (warmup) {
                    startWarmup(mover, () -> teleportNow(mover, location, label));
                } else {
                    teleportNow(mover, location, label);
                }
            });
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
            if (pending.anchorUuid != null && !pending.anchorUuid.isBlank()) {
                UUID anchorId = parseUuid(pending.anchorUuid);
                Player anchor = anchorId == null ? null : Bukkit.getPlayer(anchorId);
                if (anchor != null) {
                    // Teleport to the anchor's live position, read on their region thread.
                    tasks.entity(anchor, () -> {
                        Player stable = Bukkit.getPlayer(anchorId);
                        if (stable == null) {
                            fallbackPendingArrival(playerUuid, pending);
                            return;
                        }
                        Location live = stable.getLocation().clone();
                        playerTeleportAsync(playerUuid, live, pending.source.name().toLowerCase());
                    });
                    return;
                }
                if (pending.position.world == null || pending.position.world.isBlank()) {
                    // Anchor gone and no stored position to fall back on.
                    Player arriving = Bukkit.getPlayer(playerUuid);
                    if (arriving != null) {
                        messages.send(arriving, "admin.target-left");
                    }
                    return;
                }
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

    /** /otp: forcibly travels to a player's side, locally or across the network. */
    public void adminGoto(Player admin, String targetName) {
        profiles.resolve(targetName).thenAccept(opt -> {
            if (opt.isEmpty()) {
                messages.send(admin, "common.player-not-found", "player", targetName);
                return;
            }
            ProfileService.PlayerRecord record = opt.get();
            if (record.uuid().equals(admin.getUniqueId())) {
                messages.send(admin, "admin.self");
                return;
            }
            if (record.local()) {
                Player target = Bukkit.getPlayer(record.uuid());
                if (target == null) {
                    messages.send(admin, "common.player-not-found", "player", targetName);
                    return;
                }
                messages.send(admin, "common.teleporting", "target", target.getName());
                sendLocalTo(admin, target, target.getName(), false);
                return;
            }
            if (!network.enabled()) {
                messages.send(admin, "common.cross-disabled");
                return;
            }
            tasks.async(() -> {
                try {
                    // The arrival server resolves the target's live position via the anchor.
                    PendingTeleport pending = new PendingTeleport(admin.getUniqueId().toString(),
                            new Position(null, "", 0, 0, 0, 0f, 0f), PendingTeleport.Source.ADMIN,
                            System.currentTimeMillis(), record.uuid().toString());
                    database.putPendingTeleport(pending);
                    tasks.entity(admin, () -> network.connectAnchor(admin, record.uuid().toString()));
                } catch (Exception e) {
                    plugin.getSLF4JLogger().warn("Admin goto dispatch failed", e);
                    messages.send(admin, "common.teleport-failed");
                }
            });
        });
    }

    /** /otph: forcibly brings a player to the admin's side, locally or across the network. */
    public void adminBring(Player admin, String targetName) {
        profiles.resolve(targetName).thenAccept(opt -> {
            if (opt.isEmpty()) {
                messages.send(admin, "common.player-not-found", "player", targetName);
                return;
            }
            ProfileService.PlayerRecord record = opt.get();
            if (record.uuid().equals(admin.getUniqueId())) {
                messages.send(admin, "admin.self");
                return;
            }
            if (record.local()) {
                Player target = Bukkit.getPlayer(record.uuid());
                if (target == null) {
                    messages.send(admin, "common.player-not-found", "player", targetName);
                    return;
                }
                messages.send(target, "admin.pulled-you", "player", admin.getName());
                sendLocalTo(target, admin, admin.getName(), false);
                return;
            }
            if (!network.enabled()) {
                messages.send(admin, "common.cross-disabled");
                return;
            }
            adminBringRemote(admin, record.uuid());
            messages.send(admin, "admin.bring-started", "player", record.name());
        });
    }

    /** /otph all [server]: pulls every matching online player to the admin's side. */
    public void adminBringAll(Player admin, String serverName) {
        if (!network.enabled()) {
            if (serverName != null) {
                messages.send(admin, "common.cross-disabled");
                return;
            }
            int count = 0;
            for (Player victim : Bukkit.getOnlinePlayers()) {
                if (victim.getUniqueId().equals(admin.getUniqueId())) {
                    continue;
                }
                messages.send(victim, "admin.pulled-you", "player", admin.getName());
                sendLocalTo(victim, admin, admin.getName(), false);
                count++;
            }
            messages.send(admin, "admin.bring-all-done", "count", String.valueOf(count));
            return;
        }
        network.requestPlayerList(admin, serverName).thenAccept(uuids -> {
            int count = 0;
            for (String uuid : uuids) {
                UUID victim;
                try {
                    victim = UUID.fromString(uuid);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (victim.equals(admin.getUniqueId())) {
                    continue;
                }
                Player local = Bukkit.getPlayer(victim);
                if (local != null) {
                    messages.send(local, "admin.pulled-you", "player", admin.getName());
                    sendLocalTo(local, admin, admin.getName(), false);
                } else {
                    adminBringRemote(admin, victim);
                }
                count++;
            }
            messages.send(admin, "admin.bring-all-done", "count", String.valueOf(count));
        });
    }

    private void adminBringRemote(Player admin, UUID victim) {
        tasks.entity(admin, () -> {
            Player stable = Bukkit.getPlayer(admin.getUniqueId());
            if (stable == null) {
                return;
            }
            Position here = positionOf(stable.getLocation());
            tasks.async(() -> {
                try {
                    PendingTeleport pending = new PendingTeleport(victim.toString(), here,
                            PendingTeleport.Source.ADMIN, System.currentTimeMillis(),
                            admin.getUniqueId().toString());
                    database.putPendingTeleport(pending);
                    tasks.entity(stable, () -> network.connectAnchor(stable, victim.toString()));
                } catch (Exception e) {
                    plugin.getSLF4JLogger().warn("Admin bring dispatch failed", e);
                }
            });
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
        goHistory(player, "back_", "back.none", "back.going", PendingTeleport.Source.BACK);
    }

    /** /dback entry point; the cooldown is enforced by the caller. */
    public void goDeathBack(Player player) {
        goHistory(player, "death_", "dback.none", "dback.going", PendingTeleport.Source.DEATH);
    }

    private void goHistory(Player player, String colPrefix, String emptyKey, String goingKey,
                           PendingTeleport.Source source) {
        tasks.async(() -> {
            Position back;
            try {
                back = database.getBack(player.getUniqueId().toString(), colPrefix).orElse(null);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to read back position", e);
                return;
            }
            if (back == null) {
                messages.send(player, emptyKey);
                return;
            }
            messages.send(player, goingKey);
            send(player, back, source, back.world, null);
        });
    }

    /** Records the /dback position from a death event (runs on the region thread). */
    public void recordDeathBack(Player player) {
        if (!config.back.enabled || !config.back.deathEnabled || !config.back.deathSave) {
            return;
        }
        Position pos = positionOf(player.getLocation());
        tasks.async(() -> {
            try {
                database.setBack(player.getUniqueId().toString(), pos, "death_");
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

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Anchor-based arrival fallback: use the stored position, else tell the player it failed. */
    private void fallbackPendingArrival(UUID playerUuid, PendingTeleport pending) {
        World world = Bukkit.getWorld(pending.position.world);
        Player arriving = Bukkit.getPlayer(playerUuid);
        if (world == null || arriving == null) {
            if (arriving != null) {
                messages.send(arriving, "admin.target-left");
            }
            return;
        }
        Location target = new Location(world, pending.position.x, pending.position.y,
                pending.position.z, pending.position.yaw, pending.position.pitch);
        playerTeleportAsync(playerUuid, target, pending.source.name().toLowerCase());
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
                database.setBack(player.getUniqueId().toString(), pos, "back_");
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
