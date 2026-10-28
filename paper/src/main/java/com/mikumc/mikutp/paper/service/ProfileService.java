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
    /** How long an unknown toggle is remembered before the DB is consulted again. */
    private static final long TOGGLE_LOOKUP_TTL_MS = 120_000L;
    /** Bounded clock of last on-demand DB lookups for unknown toggles. */
    private final Map<UUID, Long> toggleLookups = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<UUID, Long>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Long> eldest) {
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

    /**
     * Called on join: upserts the profile, replicates the identity and marks
     * presence. Identity and the tpa toggle are replicated independently: every
     * backend needs the name row to resolve the player by name, while only a
     * backend that actually knows the toggle may publish it.
     */
    public void onJoin(Player player) {
        onJoin(player, true);
    }

    /**
     * Join-time profile bookkeeping.
     *
     * @param replicate true for a real join: the identity is published and, when
     *                  the toggle is unknown here, the network is asked for it.
     *                  false for a /mtp reload refresh, which only rebuilds the
     *                  local cache: a reload must not turn every online player
     *                  into a burst of PROFILE / PROFILE_REQUEST traffic.
     */
    public void onJoin(Player player, boolean replicate) {
        UUID uuid = player.getUniqueId();
        // Read entity state here: this runs on the join thread, the async body below
        // may not touch the Player on Folia.
        String name = player.getName();
        long now = System.currentTimeMillis();
        tasks.async(() -> {
            boolean known;
            long toggleStamp;
            try {
                if (replicate) {
                    database.upsertPlayer(uuid.toString(), name, now);
                }
                var profile = database.getPlayer(uuid.toString()).orElse(null);
                known = profile != null && profile.tpaKnown;
                toggleStamp = profile == null ? 0L : profile.tpaUpdatedAt;
                if (known) {
                    // Do not clobber a toggle the player flipped while this read
                    // was in flight.
                    tpaEnabled.putIfAbsent(uuid, profile.tpaEnabled);
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to update profile for {}", name, e);
                return;
            }
            if (!replicate) {
                return;
            }
            // Identity is always replicated (a cold backend must still be able to
            // resolve this player by name). The toggle value only travels as known
            // when this backend actually knows it.
            boolean value = known ? tpaEnabled.getOrDefault(uuid, true) : true;
            publishProfile(uuid, name, now, value, known, toggleStamp);
            if (!known) {
                // Ask the network for the toggle; a backend that knows it answers
                // with a known PROFILE instead of this backend guessing.
                publishProfileRequest(uuid);
            }
        });
    }

    /**
     * Rebuilds the local runtime state for a player who is already online (a server
     * reload never fires PlayerJoinEvent). Kept as the reload-facing name; it is
     * {@link #onJoin(Player, boolean)} with replication disabled.
     */
    public void refresh(Player player) {
        onJoin(player, false);
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
     * True when this backend holds a trustworthy tpa toggle for the player: hosted
     * here, learned through a replicated known PROFILE, or (lazily) read back from
     * the local replica. Never blocks the caller: a cold lookup hydrates the mirror
     * in the background and reports {@code false} this time, so a scoreboard thread
     * cannot block on SQLite. Rate-limited per player.
     */
    public boolean isTpaKnown(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (tpaEnabled.containsKey(uuid) || replicatedToggles.containsKey(uuid)) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long previous = toggleLookups.get(uuid);
        if (previous != null && now - previous < TOGGLE_LOOKUP_TTL_MS) {
            return false;
        }
        toggleLookups.put(uuid, now);
        tasks.async(() -> {
            try {
                var profile = database.getPlayer(uuid.toString()).orElse(null);
                if (profile != null && profile.tpaKnown) {
                    replicatedToggles.put(uuid, profile.tpaEnabled);
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to look up the tpa toggle of {}", uuid, e);
            }
        });
        return false;
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
        // Deliberately does NOT consult the replicated toggle mirror: request gating
        // must use the authoritative local replica (the mirror is best-effort and may
        // miss the newest snapshot after a dropped event). The mirror exists only for
        // read-only placeholders, which tolerate slight staleness.
        try {
            return database.getPlayer(uuid.toString()).map(p -> p.tpaEnabled).orElse(true);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Toggles the replicated flag with a caller-supplied monotonic stamp.
     *
     * <p>The stamp is the tpa_updated_at clock: callers that already hold a newer
     * stamp pass it in so a local flip cannot be rejected by a future-stamped
     * snapshot from another backend (R3-03). The identity clock stays local: it only
     * orders name/last-online replication.
     */
    public void setTpaEnabled(UUID uuid, boolean enabled, long stamp) {
        // Optimistic cache update; the previous value is kept so a failed write can
        // put it back instead of leaving the cache claiming a toggle the database
        // never stored (R2-08).
        Boolean previous = tpaEnabled.put(uuid, enabled);
        // Resolve the name on the caller's thread: this async task must not read
        // Bukkit's player view, which Folia confines to the owning region thread.
        String name = onlinePlayers.nameOf(uuid);
        long identityStamp = System.currentTimeMillis();
        tasks.async(() -> {
            // Upsert first: a toggle that races the join-time profile write must not
            // be lost to an UPDATE that matched no row yet. Only a known name is
            // written: an empty one would overwrite the stored identity of that uuid.
            try {
                if (name != null) {
                    database.upsertPlayer(uuid.toString(), name, identityStamp);
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to persist the profile of {}", uuid, e);
                rollbackToggle(uuid, enabled, previous);
                return;
            }
            long effectiveStamp = stamp;
            int updated;
            try {
                updated = writeToggle(uuid, enabled, effectiveStamp);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to persist tpa toggle", e);
                rollbackToggle(uuid, enabled, previous);
                return;
            }
            if (updated == 0) {
                // A snapshot carrying a newer tpa_updated_at (a future-stamped peer,
                // or a concurrent flip) won the SQL guard. Retry once with a stamp
                // that is guaranteed to win, so the cache and the broadcast never
                // disagree with the database about which write took effect (N-2).
                long retryStamp;
                try {
                    var row = database.getPlayer(uuid.toString()).orElse(null);
                    retryStamp = Math.max(System.currentTimeMillis(),
                            (row == null ? 0L : row.tpaUpdatedAt) + 1L);
                    updated = writeToggle(uuid, enabled, retryStamp);
                } catch (Exception e) {
                    plugin.getSLF4JLogger().warn("Failed to persist tpa toggle on retry", e);
                    rollbackToggle(uuid, enabled, previous);
                    return;
                }
                effectiveStamp = retryStamp;
                if (updated == 0) {
                    // Practically unreachable (the retry stamp is newer than the row
                    // we read); keep the cache and the broadcast on the same stamp and
                    // make the anomaly visible instead of silently diverging.
                    plugin.getSLF4JLogger().warn(
                            "Tpa toggle for {} could not be stored even after a stamped retry; "
                                    + "broadcasting it with stamp {}", uuid, effectiveStamp);
                }
            }
            publishProfile(uuid, name, identityStamp, enabled, true, effectiveStamp);
        });
    }

    /** One transactional write of the toggle; returns the number of updated rows. */
    private int writeToggle(UUID uuid, boolean enabled, long at) throws java.sql.SQLException {
        return database.transaction(tx -> tx.setTpaEnabled(uuid.toString(), enabled, at));
    }

    /** Restores the optimistic cache value when the write failed (only if still ours). */
    private void rollbackToggle(UUID uuid, boolean attempted, Boolean previous) {
        if (previous == null) {
            tpaEnabled.remove(uuid, attempted);
        } else {
            tpaEnabled.replace(uuid, attempted, previous);
        }
    }

    private void publishProfile(UUID uuid, String name, long lastOnline, boolean tpaEnabled,
                                boolean tpaKnown, long tpaUpdatedAt) {
        if (!syncBus.crossServer() || name == null) {
            return;
        }
        SyncEvent event = SyncEvent.create(SyncEvent.Type.PROFILE, serverId);
        event.playerUuid = uuid.toString();
        event.playerName = name;
        event.lastOnline = lastOnline;
        event.tpaEnabled = tpaEnabled;
        event.tpaKnown = tpaKnown;
        event.tpaUpdatedAt = tpaUpdatedAt;
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
        // Identity is always replicated, whether or not the sender knows the toggle:
        // a cold backend needs the name row to resolve this player by name. Identity
        // and the toggle use separate clocks, so this write cannot be "blocked" by a
        // newer identity stamp (that was the old M-03 bug).
        tx.upsertPlayer(event.playerUuid, event.playerName, event.lastOnline);
        if (!event.tpaKnown) {
            // No trustworthy toggle in this snapshot: leave the local value alone.
            return;
        }
        boolean written = tx.setTpaEnabled(event.playerUuid, event.tpaEnabled, event.tpaUpdatedAt) > 0;
        UUID uuid = parseUuid(event.playerUuid);
        // Mirror the flag after the commit: local players feed the live toggle
        // cache, remote/offline players feed the bounded placeholder mirror. Both
        // are plain map writes (no Bukkit player access from the applier thread).
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
