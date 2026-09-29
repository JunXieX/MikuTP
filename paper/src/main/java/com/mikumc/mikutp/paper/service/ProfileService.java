package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.data.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player profile bookkeeping: name sync into the database, the tpa toggle
 * cache and cross-server name resolution.
 */
public final class ProfileService {

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final Database database;
    private final Map<UUID, Boolean> tpaEnabled = new ConcurrentHashMap<>();

    public ProfileService(JavaPlugin plugin, Tasks tasks, Database database) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.database = database;
    }

    /** Called on join: upserts the profile and caches the toggle. */
    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        tasks.async(() -> {
            try {
                database.upsertPlayer(uuid.toString(), player.getName(), System.currentTimeMillis());
                database.getPlayer(uuid.toString()).ifPresent(p -> tpaEnabled.put(uuid, p.tpaEnabled));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to update profile for {}", player.getName(), e);
            }
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
        tasks.async(() -> {
            try {
                database.setTpaEnabled(uuid.toString(), enabled);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to persist tpa toggle", e);
            }
        });
    }

    /** Online players resolve locally; everyone else through the shared database. */
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
