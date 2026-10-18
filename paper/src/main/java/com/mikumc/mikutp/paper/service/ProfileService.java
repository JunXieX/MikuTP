package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.sync.SyncBus;
import com.mikumc.mikutp.common.sync.SyncEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player profile bookkeeping: name sync into the local database (replicated to
 * every backend by the sync bus), the tpa toggle cache and cross-server
 * name resolution.
 */
public final class ProfileService {

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final Database database;
    private final SyncBus syncBus;
    private final OnlinePlayers onlinePlayers;
    private final String serverId;
    private final Map<UUID, Boolean> tpaEnabled = new ConcurrentHashMap<>();

    public ProfileService(JavaPlugin plugin, Tasks tasks, Database database, SyncBus syncBus,
                          OnlinePlayers onlinePlayers, String serverId) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.database = database;
        this.syncBus = syncBus;
        this.onlinePlayers = onlinePlayers;
        this.serverId = serverId;
    }

    /** Called on join: upserts the profile, replicates it and marks presence. */
    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        // Read entity state here: this runs on the join thread, the async body below
        // may not touch the Player on Folia.
        String name = player.getName();
        long now = System.currentTimeMillis();
        tasks.async(() -> {
            try {
                database.upsertPlayer(uuid.toString(), name, now);
                database.getPlayer(uuid.toString()).ifPresent(p ->
                        // Do not clobber a toggle the player flipped while this read
                        // was in flight.
                        tpaEnabled.putIfAbsent(uuid, p.tpaEnabled));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to update profile for {}", name, e);
                return;
            }
            publishProfile(uuid, name, now, tpaEnabled.getOrDefault(uuid, true));
        });
    }

    public void onQuit(UUID uuid) {
        tpaEnabled.remove(uuid);
    }

    public boolean isTpaEnabled(UUID uuid) {
        return tpaEnabled.getOrDefault(uuid, true);
    }

    public void setTpaEnabled(UUID uuid, boolean enabled) {
        tpaEnabled.put(uuid, enabled);
        // Resolve the name on the caller's thread: this async task must not read
        // Bukkit's player view, which Folia confines to the owning region thread.
        Player online = onlinePlayers.byId(uuid);
        String name = online == null ? null : online.getName();
        tasks.async(() -> {
            try {
                database.setTpaEnabled(uuid.toString(), enabled);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to persist tpa toggle", e);
                return;
            }
            publishProfile(uuid, name, System.currentTimeMillis(), enabled);
        });
    }

    private void publishProfile(UUID uuid, String name, long lastOnline, boolean tpaEnabled) {
        if (!syncBus.crossServer() || name == null) {
            return;
        }
        SyncEvent event = SyncEvent.create(SyncEvent.Type.PROFILE, serverId);
        event.playerUuid = uuid.toString();
        event.playerName = name;
        event.lastOnline = lastOnline;
        event.tpaEnabled = tpaEnabled;
        syncBus.publish(event);
    }

    /** Applies a replicated profile from another backend; the write joins the
     * caller's batched transaction. */
    public void applySync(SyncEvent event, Database.Tx tx) throws java.sql.SQLException {
        if (event.playerUuid == null || event.playerName == null) {
            return;
        }
        tx.upsertPlayer(event.playerUuid, event.playerName, event.lastOnline);
        tx.setTpaEnabled(event.playerUuid, event.tpaEnabled);
        UUID uuid = parseUuid(event.playerUuid);
        // Only mirror the flag for players this backend hosts; touching Bukkit's
        // player view from the sync applier thread is not allowed on Folia.
        if (uuid != null && onlinePlayers.byId(uuid) != null) {
            tpaEnabled.put(uuid, event.tpaEnabled);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Online players resolve locally; everyone else through the local replicated database. */
    public CompletableFuture<Optional<PlayerRecord>> resolve(String name) {
        // Uses the plugin registry rather than Bukkit's name lookup: callers run
        // this from async threads, where scanning the server player list is
        // undefined on Folia.
        Player online = onlinePlayers.byName(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(
                    new PlayerRecord(online.getUniqueId(), online.getName(), true)));
        }
        CompletableFuture<Optional<PlayerRecord>> future = new CompletableFuture<>();
        tasks.async(() -> {
            try {
                future.complete(database.getPlayerByName(name)
                        .map(p -> new PlayerRecord(UUID.fromString(p.uuid), p.name, false)));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Name lookup failed for {}", name, e);
                future.complete(Optional.empty());
            }
        });
        return future;
    }

    public record PlayerRecord(UUID uuid, String name, boolean local) {
    }
}
