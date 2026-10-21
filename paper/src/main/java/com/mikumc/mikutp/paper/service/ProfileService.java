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
    /** How many replicated (non-local) toggles are mirrored for placeholder lookups. */
    private static final int REPLICATED_TOGGLE_CACHE_SIZE = 4096;
    /**
     * Toggles replicated from other backends for players this server does not host.
     * The placeholder layer must answer from memory (it can run on a region thread,
     * where a SQLite read is not acceptable), and the local cache only covers local
     * players; this bounded LRU is the fallback between the two.
     */
    private final Map<UUID, Boolean> replicatedToggles = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<UUID, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > REPLICATED_TOGGLE_CACHE_SIZE;
                }
            });

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
            boolean known;
            try {
                database.upsertPlayer(uuid.toString(), name, now);
                var profile = database.getPlayer(uuid.toString()).orElse(null);
                known = profile != null && profile.tpaKnown;
                if (known) {
                    // Do not clobber a toggle the player flipped while this read
                    // was in flight.
                    tpaEnabled.putIfAbsent(uuid, profile.tpaEnabled);
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to update profile for {}", name, e);
                return;
            }
            if (known) {
                publishProfile(uuid, name, now, tpaEnabled.getOrDefault(uuid, true));
            } else {
                // This backend has no trustworthy toggle for the player yet (fresh
                // row, or a replica that never received one). Ask the network
                // instead of publishing a guessed default: a real snapshot may
                // already carry "disabled" and must not be overwritten by "enabled".
                publishProfileRequest(uuid);
            }
        });
    }

    public void onQuit(UUID uuid) {
        Boolean last = tpaEnabled.remove(uuid);
        if (last != null) {
            // Keep the last known value readable for placeholders after the player
            // leaves this backend (their local cache entry is gone).
            replicatedToggles.put(uuid, last);
        }
    }

    public boolean isTpaEnabled(UUID uuid) {
        Boolean local = tpaEnabled.get(uuid);
        if (local != null) {
            return local;
        }
        return replicatedToggles.getOrDefault(uuid, true);
    }

    /**
     * Toggle state for any player, local or remote. The in-memory value only
     * covers players this backend hosts, so a remote target falls back to the
     * replicated profile row — otherwise a cross-server /tpa would ignore a
     * toggle the target switched off. Safe to call from an async thread.
     */
    public boolean tpaEnabledFor(UUID uuid) {
        Boolean cached = tpaEnabled.get(uuid);
        if (cached != null) {
            return cached;
        }
        Boolean replicated = replicatedToggles.get(uuid);
        if (replicated != null) {
            return replicated;
        }
        try {
            return database.getPlayer(uuid.toString()).map(p -> p.tpaEnabled).orElse(true);
        } catch (Exception e) {
            return true;
        }
    }

    public void setTpaEnabled(UUID uuid, boolean enabled) {
        tpaEnabled.put(uuid, enabled);
        // Resolve the name on the caller's thread: this async task must not read
        // Bukkit's player view, which Folia confines to the owning region thread.
        String name = onlinePlayers.nameOf(uuid);
        long stamped = System.currentTimeMillis();
        tasks.async(() -> {
            try {
                // Upsert first: a toggle that races the join-time profile write must
                // not be lost to an UPDATE that matched no row yet. The timestamp
                // stamps the flip as the newest profile change, so a replicated
                // snapshot produced before it is rejected in SQL.
                String known = name == null ? "" : name;
                database.upsertPlayer(uuid.toString(), known, stamped);
                // The toggle is the newest profile change as of "now": move the
                // stamp forward past whatever the upsert wrote, otherwise the guard
                // in Tx#setTpaEnabled (last_online <= at) could reject this write.
                long applied = System.currentTimeMillis();
                database.runTransaction(tx -> tx.setTpaEnabled(uuid.toString(), enabled, applied));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to persist tpa toggle", e);
                return;
            }
            publishProfile(uuid, name, stamped, enabled);
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

    /**
     * Asks the network to re-announce a profile whose replicated tpa toggle this
     * backend does not know yet. A backend that does know it answers with a
     * PROFILE event, so the local row (and the cache) converge without guessing.
     */
    private void publishProfileRequest(UUID uuid) {
        if (!syncBus.crossServer()) {
            return;
        }
        SyncEvent event = SyncEvent.create(SyncEvent.Type.PROFILE_REQUEST, serverId);
        event.playerUuid = uuid.toString();
        syncBus.publish(event);
    }

    /**
     * Applies a replicated profile from another backend; the write joins the
     * caller's batched transaction and only touches the in-memory toggle after
     * that transaction committed. Ordering is enforced in SQL by the profile
     * timestamp, so a snapshot produced before a local flip cannot undo it.
     */
    public void applySync(SyncEvent event, Database.Tx tx,
                          java.util.function.Consumer<Runnable> afterCommit) throws java.sql.SQLException {
        if (event.playerUuid == null || event.playerName == null) {
            return;
        }
        tx.upsertPlayer(event.playerUuid, event.playerName, event.lastOnline);
        boolean written = tx.setTpaEnabled(event.playerUuid, event.tpaEnabled, event.lastOnline) > 0;
        UUID uuid = parseUuid(event.playerUuid);
        // Mirror the flag after the commit: local players feed the live toggle
        // cache, remote/offline players feed the bounded placeholder mirror. Both
        // are plain map writes (no Bukkit player access from the applier thread).
        // A snapshot older than the stored row is rejected in SQL and skipped here
        // as well, so it cannot roll the toggle back.
        if (written && uuid != null) {
            if (onlinePlayers.byId(uuid) != null) {
                afterCommit.accept(() -> tpaEnabled.put(uuid, event.tpaEnabled));
            } else {
                // Mirror the replicated value so %mikutp_tpa_enabled% is truthful for
                // players hosted on another backend, without a database read on the
                // (possibly region) thread that asks for the placeholder.
                afterCommit.accept(() -> replicatedToggles.put(uuid, event.tpaEnabled));
            }
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
            String resolved = onlinePlayers.nameOf(online.getUniqueId());
            return CompletableFuture.completedFuture(Optional.of(
                    new PlayerRecord(online.getUniqueId(), resolved == null ? name : resolved, true)));
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
