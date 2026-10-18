package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.ConfigIO;
import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.data.Position;
import com.mikumc.mikutp.common.sync.SyncBus;
import com.mikumc.mikutp.common.sync.SyncEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Central teleport execution: warmup, local transfers, cross-server dispatch
 * via Redis pending keys plus proxy connect messages, and the back-family of
 * stored positions (/back, /dback, /outtp).
 */
public final class TeleportService {

    private static final int PENDING_TTL_SECONDS = 300;

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final MikuTPConfig config;
    private final Database database;
    private final MessageService messages;
    private final Effects effects;
    private final WarmupManager warmups;
    private final NetworkService network;
    private final ProfileService profiles;
    private final CooldownManager cooldowns;
    private final SyncBus syncBus;
    private final OnlinePlayers onlinePlayers;
    /** Pending teleports pushed by the proxy, applied on the player's join. */
    private final java.util.Map<UUID, PendingTeleport> arriving = new java.util.concurrent.ConcurrentHashMap<>();
    private final String serverId;

    public TeleportService(JavaPlugin plugin, Tasks tasks, MikuTPConfig config, Database database,
                           MessageService messages, Effects effects, WarmupManager warmups, NetworkService network,
                           ProfileService profiles, CooldownManager cooldowns, SyncBus syncBus,
                           OnlinePlayers onlinePlayers) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.config = config;
        this.database = database;
        this.messages = messages;
        this.effects = effects;
        this.warmups = warmups;
        this.network = network;
        this.profiles = profiles;
        this.cooldowns = cooldowns;
        this.syncBus = syncBus;
        this.onlinePlayers = onlinePlayers;
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
     * Teleports {@code mover} to {@code anchor}'s live location; the position is
     * read on the anchor's own region thread (Folia-safe).
     */
    public void sendLocalTo(Player mover, Player anchor, String label, boolean warmup) {
        tasks.entity(anchor, () -> {
            Player stable = onlinePlayers.byId(anchor.getUniqueId());
            if (stable == null) {
                return;
            }
            Location location = stable.getLocation().clone();
            tasks.entity(mover, () -> {
                if (!mover.isOnline()) {
                    return;
                }
                Runnable doTeleport = () -> {
                    recordBack(mover);
                    teleportNow(mover, location, label);
                };
                if (warmup) {
                    startWarmup(mover, doTeleport);
                } else {
                    doTeleport.run();
                }
            });
        });
    }

    /** Buffers a pending-teleport payload pushed by the proxy for a joining player. */
    public void bufferArrival(Player player, String payload) {
        PendingTeleport pending;
        try {
            pending = ConfigIO.gson().fromJson(payload, PendingTeleport.class);
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Malformed pending teleport payload", e);
            return;
        }
        // A payload missing its source cannot be labelled, and one missing its
        // world cannot be applied: drop both instead of failing later on the
        // player's join thread.
        if (pending == null || pending.position == null || pending.position.world == null
                || pending.position.world.isBlank() || pending.source == null) {
            return;
        }
        if (expired(pending)) {
            return;
        }
        UUID id = player.getUniqueId();
        arriving.put(id, pending);
        // The proxy can deliver this after PlayerJoinEvent already ran; applying
        // right away keeps the handoff from waiting for the player's next login.
        if (player.isOnline()) {
            tasks.entity(player, () -> applyPending(id));
        }
    }

    /** Drops any buffered handoff for a departing player so the map cannot grow
     * when a player disconnects between delivery and application. */
    public void forgetArrival(UUID player) {
        arriving.remove(player);
    }

    /** A buffered handoff is only useful while it is fresh. */
    private static boolean expired(PendingTeleport pending) {
        return pending.createdAt > 0
                && System.currentTimeMillis() - pending.createdAt > PENDING_TTL_SECONDS * 1000L;
    }

    /** Consumes a buffered cross-server handoff for a player who just arrived. */
    public void applyPending(UUID playerUuid) {
        PendingTeleport pending = arriving.get(playerUuid);
        if (pending == null || pending.position == null || pending.source == null) {
            return;
        }
        // Anchor-based handoffs (/otp) deliberately carry no world; every other
        // source needs one, otherwise there is nothing to apply.
        boolean hasAnchor = pending.anchorUuid != null && !pending.anchorUuid.isBlank();
        boolean hasWorld = pending.position.world != null && !pending.position.world.isBlank();
        if (!hasAnchor && !hasWorld) {
            return;
        }
        if (expired(pending)) {
            arriving.remove(playerUuid, pending);
            return;
        }
        String destServer = pending.position.server;
        if (destServer != null && !destServer.isBlank() && !destServer.equals(serverId)) {
            // Not our handoff: keep it buffered. The payload is only meant for the
            // destination server, and dropping it here would lose the teleport if
            // the proxy delivered it slightly early.
            return;
        }
        arriving.remove(playerUuid, pending);
        if (pending.anchorUuid != null && !pending.anchorUuid.isBlank()) {
            UUID anchorId = parseUuid(pending.anchorUuid);
            Player anchor = anchorId == null ? null : onlinePlayers.byId(anchorId);
            if (anchor != null) {
                // Teleport to the anchor's live position, read on their region thread.
                tasks.entity(anchor, () -> {
                    Player stable = onlinePlayers.byId(anchorId);
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
                Player arrivingPlayer = onlinePlayers.byId(playerUuid);
                if (arrivingPlayer != null) {
                    tasks.entity(arrivingPlayer,
                            () -> messages.send(arrivingPlayer, "admin.target-left"));
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
    }

    /**
     * Warms up a mover and then hands them to the proxy to join the server of
     * {@code anchorUuid}; the destination position comes from the pending
     * teleport the acceptor's side stashed. Used by cross-server tpa.
     */
    public void dispatchToAnchor(Player mover, String anchorUuid, Runnable afterDispatch) {
        tasks.entity(mover, () -> {
            startWarmup(mover, () -> {
                recordBack(mover);
                network.connectAnchor(mover, mover.getUniqueId().toString(), anchorUuid);
                if (afterDispatch != null) {
                    afterDispatch.run();
                }
            });
        });
    }

    /** /back entry point; the cooldown only burns when a position exists. */
    public void goBack(Player player) {
        goHistory(player, CooldownManager.Kind.BACK, Database.BACK_PREFIX, "back.none", "back.going",
                PendingTeleport.Source.BACK);
    }

    /** /dback entry point; the cooldown only burns when a position exists. */
    public void goDeathBack(Player player) {
        goHistory(player, CooldownManager.Kind.DBACK, Database.DEATH_PREFIX, "dback.none", "dback.going",
                PendingTeleport.Source.DEATH);
    }

    private void goHistory(Player player, CooldownManager.Kind kind, String colPrefix, String emptyKey,
                           String goingKey, PendingTeleport.Source source) {
        UUID id = player.getUniqueId();
        // Check and burn atomically; the cooldown is refunded if there is
        // nothing to return to.
        long remaining = cooldowns.tryConsume(id, kind);
        if (remaining > 0) {
            messages.send(player, "common.cooldown", "seconds", String.valueOf(remaining));
            return;
        }
        tasks.async(() -> {
            Position back;
            try {
                back = database.getPosition(player.getUniqueId().toString(), colPrefix).orElse(null);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to read back position", e);
                cooldowns.clear(id, kind);
                return;
            }
            if (back == null) {
                cooldowns.clear(id, kind);
                messages.send(player, emptyKey);
                return;
            }
            messages.send(player, goingKey);
            send(player, back, source, back.world, null);
        });
    }

    /** /outtp: travels to where a player last logged out on this server. */
    public void adminLastLogout(Player admin, String targetName) {
        tasks.async(() -> {
            String uuid;
            Position logout = null;
            try {
                uuid = database.getPlayerByName(targetName).map(p -> p.uuid).orElse(null);
                if (uuid != null) {
                    logout = database.getPosition(uuid, Database.LOGOUT_PREFIX).orElse(null);
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to read logout position", e);
                return;
            }
            if (logout == null) {
                messages.send(admin, "outtp.none", "player", targetName);
                return;
            }
            messages.send(admin, "back.going");
            send(admin, logout, PendingTeleport.Source.ADMIN, logout.world, null);
        });
    }

    /** Records the /back position when the plugin teleports a player (region thread). */
    public void recordBack(Player player) {
        if (!config.back.enabled || !config.back.saveOnTeleport) {
            return;
        }
        storeAndPublish(player, Database.BACK_PREFIX, "back");
    }

    /** Records the /dback position from a death event (region thread). */
    public void recordDeathBack(Player player) {
        if (!config.back.enabled || !config.back.deathEnabled || !config.back.deathSave) {
            return;
        }
        storeAndPublish(player, Database.DEATH_PREFIX, "death");
    }

    /** Records where a player logged out, local to this server only. */
    public void recordLogout(Player player) {
        Position pos = positionOf(player.getLocation());
        tasks.async(() -> {
            try {
                database.setPosition(player.getUniqueId().toString(), pos, Database.LOGOUT_PREFIX);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to save logout position", e);
            }
        });
    }

    private void storeAndPublish(Player player, String colPrefix, String kind) {
        Position pos = positionOf(player.getLocation());
        tasks.async(() -> {
            try {
                database.setPosition(player.getUniqueId().toString(), pos, colPrefix);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to save {} position", kind, e);
                return;
            }
            if (syncBus.crossServer()) {
                SyncEvent event = SyncEvent.create(SyncEvent.Type.BACK, serverId);
                event.playerUuid = player.getUniqueId().toString();
                event.backKind = kind;
                event.backPosition = pos;
                syncBus.publish(event);
            }
        });
    }

    /** Applies a replicated return position from another backend; the write joins
     * the caller's batched transaction. */
    public void applySyncBack(SyncEvent event, Database.Tx tx) throws java.sql.SQLException {
        if (event.playerUuid == null || event.backPosition == null) {
            return;
        }
        String prefix = "death".equals(event.backKind) ? Database.DEATH_PREFIX : Database.BACK_PREFIX;
        tx.setPosition(event.playerUuid, event.backPosition, prefix);
    }

    // ------------------------------------------------------------------ admin commands

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
                Player target = onlinePlayers.byId(record.uuid());
                if (target == null) {
                    messages.send(admin, "common.player-not-found", "player", targetName);
                    return;
                }
                messages.send(admin, "common.teleporting", "target", record.name());
                sendLocalTo(admin, target, record.name(), false);
                return;
            }
            if (!syncBus.crossServer()) {
                messages.send(admin, "common.cross-disabled");
                return;
            }
            tasks.async(() -> {
                try {
                    // The admin travels: their handoff carries the target's uuid as
                    // the anchor, and the connect request must move the admin, not
                    // the target (moving the wrong side would bring the target over).
                    PendingTeleport pending = new PendingTeleport(admin.getUniqueId().toString(),
                            new Position(null, "", 0, 0, 0, 0f, 0f), PendingTeleport.Source.ADMIN,
                            System.currentTimeMillis(), record.uuid().toString());
                    syncBus.pendingStore(admin.getUniqueId(), pending, PENDING_TTL_SECONDS);
                    tasks.entity(admin, () -> network.connectAnchor(admin,
                            admin.getUniqueId().toString(), record.uuid().toString()));
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
                Player target = onlinePlayers.byId(record.uuid());
                if (target == null) {
                    messages.send(admin, "common.player-not-found", "player", targetName);
                    return;
                }
                String adminName = nameOf(admin);
                messages.send(target, "admin.pulled-you", "player", adminName);
                sendLocalTo(target, admin, adminName, false);
                return;
            }
            if (!syncBus.crossServer()) {
                messages.send(admin, "common.cross-disabled");
                return;
            }
            adminBringRemote(admin, record.uuid());
            messages.send(admin, "admin.bring-started", "player", record.name());
        });
    }

    /** /otph all [server]: pulls every matching online player to the admin's side. */
    public void adminBringAll(Player admin, String serverName) {
        if (!syncBus.crossServer()) {
            if (serverName != null) {
                messages.send(admin, "common.cross-disabled");
                return;
            }
            // Reads the plugin's own registry: the online-player view may only be
            // iterated on the global region thread on Folia.
            int count = 0;
            String adminName = nameOf(admin);
            for (Player victim : onlinePlayers.snapshot()) {
                if (victim.getUniqueId().equals(admin.getUniqueId())) {
                    continue;
                }
                messages.send(victim, "admin.pulled-you", "player", adminName);
                sendLocalTo(victim, admin, adminName, false);
                count++;
            }
            messages.send(admin, "admin.bring-all-done", "count", String.valueOf(count));
            return;
        }
        network.requestPlayerList(admin, serverName).thenAccept(uuids -> {
            int count = 0;
            String adminName = nameOf(admin);
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
                Player local = onlinePlayers.byId(victim);
                if (local != null) {
                    messages.send(local, "admin.pulled-you", "player", adminName);
                    sendLocalTo(local, admin, adminName, false);
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
            Player stable = onlinePlayers.byId(admin.getUniqueId());
            if (stable == null) {
                return;
            }
            Position here = positionOf(stable.getLocation());
            try {
                PendingTeleport pending = new PendingTeleport(victim.toString(), here,
                        PendingTeleport.Source.ADMIN, System.currentTimeMillis(),
                        admin.getUniqueId().toString());
                syncBus.pendingStore(victim, pending, PENDING_TTL_SECONDS);
                tasks.entity(stable, () -> network.connectAnchor(stable,
                        victim.toString(), stable.getUniqueId().toString()));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Admin bring dispatch failed", e);
            }
        });
    }

    // ------------------------------------------------------------------ internals

    private void begin(Player player, Position dest, PendingTeleport.Source source, String label, Runnable afterDispatch) {
        boolean local = dest.server == null || dest.server.isBlank() || dest.server.equals(serverId);
        if (local) {
            World world = Bukkit.getWorld(dest.world);
            if (world == null) {
                messages.send(player, "common.teleport-failed");
                return;
            }
            Location target = new Location(world, dest.x, dest.y, dest.z, dest.yaw, dest.pitch);
            messages.send(player, "common.teleporting", "target", label);
            startWarmup(player, () -> {
                recordBack(player);
                teleportNow(player, target, label);
            });
            return;
        }
        if (!syncBus.crossServer()) {
            messages.send(player, "common.cross-disabled");
            return;
        }
        messages.send(player, "common.teleporting", "target", label);
        startWarmup(player, () -> {
            recordBack(player);
            dispatchCross(player, dest, source, afterDispatch);
        });
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
        Player player = onlinePlayers.byId(playerUuid);
        if (player == null) {
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

    /** Anchor-based arrival fallback: use the stored position, else tell the player it failed. */
    private void fallbackPendingArrival(UUID playerUuid, PendingTeleport pending) {
        World world = Bukkit.getWorld(pending.position.world);
        Player arriving = onlinePlayers.byId(playerUuid);
        if (world == null || arriving == null) {
            if (arriving != null) {
                tasks.entity(arriving, () -> messages.send(arriving, "admin.target-left"));
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
                PendingTeleport pending = new PendingTeleport(uuid.toString(), stamped, source, System.currentTimeMillis());
                syncBus.pendingStore(uuid, pending, PENDING_TTL_SECONDS);
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

    /** Captures {@code positionOwner}'s live position and stores it as the
     * pending teleport of {@code playerToTeleport} (used by the request flow). */
    public void stashPendingTeleport(UUID playerToTeleport, UUID positionOwner, PendingTeleport.Source source) {
        stashPendingTeleport(playerToTeleport, positionOwner, source, null);
    }

    /** Same as above; {@code onSuccess} runs once the payload is durably stored. */
    public void stashPendingTeleport(UUID playerToTeleport, UUID positionOwner,
                                     PendingTeleport.Source source, Runnable onSuccess) {
        Player owner = onlinePlayers.byId(positionOwner);
        if (owner == null) {
            // The anchor left before the position could be captured. The caller
            // uses onSuccess to announce the acceptance, so still run it: silence
            // here would leave the requester waiting for an answer that never comes.
            runQuietly(onSuccess);
            return;
        }
        tasks.entity(owner, () -> {
            Player stable = onlinePlayers.byId(positionOwner);
            if (stable == null) {
                runQuietly(onSuccess);
                return;
            }
            Position here = positionOf(stable.getLocation());
            tasks.async(() -> {
                try {
                    PendingTeleport pending = new PendingTeleport(playerToTeleport.toString(), here,
                            source, System.currentTimeMillis(), positionOwner.toString());
                    syncBus.pendingStore(playerToTeleport, pending, PENDING_TTL_SECONDS);
                    runQuietly(onSuccess);
                } catch (Exception e) {
                    plugin.getSLF4JLogger().warn("Failed to stash cross-server destination", e);
                    runQuietly(onSuccess);
                }
            });
        });
    }

    /** Runs a callback, containing any failure so it cannot break the caller. */
    private void runQuietly(Runnable action) {
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Teleport callback failed", e);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Name captured at join time; never reads the Player from an arbitrary thread. */
    private String nameOf(Player player) {
        String name = onlinePlayers.nameOf(player.getUniqueId());
        return name == null ? "" : name;
    }

    private Position positionOf(Location loc) {
        return new Position(serverId, loc.getWorld() == null ? "world" : loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }
}
