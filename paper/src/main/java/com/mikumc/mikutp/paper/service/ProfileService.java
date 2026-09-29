package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.sync.SyncBus;
import com.mikumc.mikutp.common.sync.SyncEvent;
import org.bukkit.Bukkit;
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
    private final String serverId;
    private final Map<UUID, Boolean> tpaEnabled = new ConcurrentHashMap<>();

    public ProfileService(JavaPlugin plugin, Tasks tasks, Database database, SyncBus syncBus, String serverId) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.database = database;
        this.syncBus = syncBus;
        this.serverId = serverId;
    }

    /** Called on join: upserts the profile, replicates it and marks presence. */
    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        tasks.async(() -> {
            try {
                database.upsertPlayer(uuid.toString(), player.getName(), System.currentTimeMillis());
                database.getPlayer(uuid.toString()).ifPresent(p -> tpaEnabled.put(uuid, p.tpaEnabled));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to update profile for {}", player.getName(), e);
                return;
            }
            publishProfile(uuid, player.getName(), System.currentTimeMillis(),
                    tpaEnabled.getOrDefault(uuid, true));
        });
        if (syncBus.crossServer()) {
            syncBus.presencePut(uuid, serverId, 15);
        }
    }

    public void onQuit(UUID uuid) {
        tpaEnabled.remove(uuid);
        if (syncBus.crossServer()) {
            syncBus.presenceForget(uuid);
        }
    }

    public boolean isTpaEnabled(UUID uuid) {
        return tpaEnabled.getOrDefault(uuid, true);
    }

    public void setTpaEnabled(UUID uuid, boolean enabled) {
        tpaEnabled.put(uuid, enabled);
        tasks.async(() -> {
            try {
                database.setTpaEnabled(uuid.toString(), enabled);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to persist tpa toggle", e);
                return;
            }
            String name = Bukkit.getPlayer(uuid) == null ? null : Bukkit.getPlayer(uuid).getName();
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

    /** Applies a replicated profile from another backend. */
    public void applySync(SyncEvent event) {
        if (event.playerUuid == null || event.playerName == null) {
            return;
        }
        try {
            database.upsertPlayer(event.playerUuid, event.playerName, event.lastOnline);
            database.setTpaEnabled(event.playerUuid, event.tpaEnabled);
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Failed to apply replicated profile", e);
        }
        UUID uuid = UUID.fromString(event.playerUuid);
        if (Bukkit.getPlayer(uuid) != null) {
            tpaEnabled.put(uuid, event.tpaEnabled);
        }
    }

    /** Online players resolve locally; everyone else through the local replicated database. */
    public CompletableFuture<Optional<PlayerRecord>> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
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
