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
        // An anchor-based handoff (/otp) legitimately carries no world; any other
        // payload without one has nothing to teleport to and is dropped here
        // rather than failing later on the player's join thread.
        boolean hasAnchor = pending != null && pending.anchorUuid != null && !pending.anchorUuid.isBlank();
        if (pending == null || pending.position == null || pending.source == null
                || (!hasAnchor && (pending.position.world == null || pending.position.world.isBlank()))) {
            return;
        }
        if (expired(pending)) {
            return;
        }
        UUID id = player.getUniqueId();
        // The proxy should only ever deliver this player's own handoff, but the
        // payload is the authority: a cross-wired or forged frame must not park a
        // teleport that fires on this player's next join.
        if (pending.playerUuid != null && !pending.playerUuid.equals(id.toString())) {
            plugin.getSLF4JLogger().debug("Dropping pending teleport for {} delivered to {}",
                    pending.playerUuid, id);
            return;
        }
        arriving.put(id, pending);
        // The proxy can deliver this after PlayerJoinEvent already ran; applying
        // right away keeps the handoff from waiting for the player's next login.
        // The online check goes through the registry: this runs on the plugin
        // message thread, not the player's region thread.
        if (onlinePlayers.byId(id) != null) {
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
            // the proxy delivered it slightly early. A permanent mismatch means
            // cross-server.server-id does not match the Velocity server name, which
            // would otherwise present as "the teleport silently did nothing".
            plugin.getSLF4JLogger().debug(
                    "Keeping cross-server handoff for {} (destination={}, this-server={})",
                    playerUuid, destServer, serverId);
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
                    playerTeleportAsync(playerUuid, live, pending.source.name().toLowerCase(java.util.Locale.ROOT));
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
            Player arrivingPlayer = onlinePlayers.byId(playerUuid);
            if (arrivingPlayer != null) {
                tasks.entity(arrivingPlayer, () -> messages.send(arrivingPlayer, "common.teleport-failed"));
            }
            return;
        }
        Location target = new Location(world, pending.position.x, pending.position.y,
                pending.position.z, pending.position.yaw, pending.position.pitch);
        playerTeleportAsync(playerUuid, target, pending.source.name().toLowerCase(java.util.Locale.ROOT));
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
                tasks.entity(player, () -> messages.send(player, "common.teleport-failed"));
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
        String uuid = player.getUniqueId().toString();
        // Version stamp published with the event. It only has to be monotonic with
        // respect to what the other backends already hold; the local row gets its
        // own stamp from the database.
        long updatedAt = System.currentTimeMillis();
        tasks.async(() -> {
            boolean written;
            try {
                // Same stamp as the event: the local row and the replicated version
                // must be the exact same write, otherwise a peer can win the LWW
                // comparison against the row it was derived from (X-06).
                written = database.setPositionIfNewer(uuid, pos, colPrefix, updatedAt);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to save {} position", kind, e);
                return;
            }
            if (!written) {
                // A newer position already won locally; publishing this older snapshot
                // would only be rejected on every receiver.
                plugin.getSLF4JLogger().debug("Skipped publishing an older {} position for {}", kind, uuid);
                return;
            }
            if (syncBus.crossServer()) {
                SyncEvent event = SyncEvent.create(SyncEvent.Type.BACK, serverId);
                event.playerUuid = uuid;
                event.backKind = kind;
                event.backPosition = pos;
                event.backUpdatedAt = updatedAt;
                syncBus.publish(event);
            }
        });
    }

    /** Applies a replicated return position from another backend; the write joins
     * the caller's batched transaction and is rejected when it is older than the
     * position this backend already holds. */
    public void applySyncBack(SyncEvent event, Database.Tx tx) throws java.sql.SQLException {
        if (event.playerUuid == null || event.backPosition == null) {
            return;
        }
        String prefix = "death".equals(event.backKind) ? Database.DEATH_PREFIX : Database.BACK_PREFIX;
        tx.setPositionIfNewer(event.playerUuid, event.backPosition, prefix, event.backUpdatedAt);
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

    /** Cooldown bucket that a stored position of this source was paid with. */
    private static CooldownManager.Kind cooldownKindOf(PendingTeleport.Source source) {
        return switch (source) {
            case HOME -> CooldownManager.Kind.HOME;
            case WARP -> CooldownManager.Kind.WARP;
            case BACK -> CooldownManager.Kind.BACK;
            case DEATH -> CooldownManager.Kind.DBACK;
            case WILD -> CooldownManager.Kind.WILD;
            // Admin and tpa flows do not charge a cooldown of their own.
            case ADMIN, TPA, TPA_HERE -> null;
        };
    }

    private void begin(Player player, Position dest, PendingTeleport.Source source, String label, Runnable afterDispatch) {
        boolean local = dest.server == null || dest.server.isBlank() || dest.server.equals(serverId);
        if (local) {
            World world = dest.world == null ? null : Bukkit.getWorld(dest.world);
            if (world == null) {
                // Nothing was dispatched: the caller already burned a cooldown, so
                // take it back rather than charging for a failed teleport.
                cooldowns.clear(player.getUniqueId(), cooldownKindOf(source));
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
            // Nothing was dispatched: mirror the local missing-world branch and give
            // the cooldown back instead of charging for a failed teleport (X-04).
            cooldowns.clear(player.getUniqueId(), cooldownKindOf(source));
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
        UUID id = player.getUniqueId();
        player.teleportAsync(target).thenAccept(ok -> reportTeleport(id, ok, label));
    }

    private void playerTeleportAsync(UUID playerUuid, Location target, String label) {
        Player player = onlinePlayers.byId(playerUuid);
        if (player == null) {
            return;
        }
        player.teleportAsync(target).thenAccept(ok -> reportTeleport(playerUuid, ok, label));
    }

    /**
     * Reports a finished teleport on the player's current thread. The completion
     * callback runs after the entity may already have moved to another region
     * (cross-world teleports do), so the player is looked up again from the
     * registry instead of reusing the captured scheduler.
     */
    private void reportTeleport(UUID playerUuid, Boolean ok, String label) {
        Player player = onlinePlayers.byId(playerUuid);
        if (player == null) {
            return;
        }
        tasks.entity(player, () -> {
            if (Boolean.TRUE.equals(ok)) {
                messages.send(player, "common.teleported", "target", label);
                effects.teleport(player);
            } else {
                messages.send(player, "common.teleport-failed");
            }
        });
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
        playerTeleportAsync(playerUuid, target, pending.source.name().toLowerCase(java.util.Locale.ROOT));
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
                tasks.entity(player, () -> messages.send(player, "common.teleport-failed"));
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
        stashPendingTeleport(playerToTeleport, positionOwner, source, onSuccess, null);
    }

    /**
     * Same as above, with an explicit failure callback. A handoff that could not be
     * stored must not be announced to the requester as an acceptance: the
     * destination has no payload to apply, so the caller reports the failure
     * instead of leaving the player waiting for a teleport that will not happen.
     */
    public void stashPendingTeleport(UUID playerToTeleport, UUID positionOwner,
                                     PendingTeleport.Source source,
                                     Runnable onSuccess, Runnable onFailure) {
        if (playerToTeleport == null) {
            runQuietly(onFailure);
            return;
        }
        Player owner = onlinePlayers.byId(positionOwner);
        if (owner == null) {
            // The anchor left before the position could be captured.
            runQuietly(onFailure);
            return;
        }
        tasks.entity(owner, () -> {
            Player stable = onlinePlayers.byId(positionOwner);
            if (stable == null) {
                runQuietly(onFailure);
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
                    runQuietly(onFailure);
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
