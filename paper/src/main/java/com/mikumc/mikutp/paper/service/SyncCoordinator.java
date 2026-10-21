package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.sync.SyncBus;
import com.mikumc.mikutp.common.sync.SyncEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the sync bus to the services: routes incoming events to their owners
 * and triggers full-state resyncs so backends that missed events (proxy
 * restarts, empty-server gaps) re-converge.
 *
 * <p>Replicated state is queued and applied in bulk: a resync on a large
 * network produces one write per home, profile, position and ignore entry, and
 * committing each of them separately would hammer the single SQLite
 * connection. The queue is drained into one transaction per flush instead,
 * preserving arrival order.
 */
public final class SyncCoordinator {

    private static final long DUMP_GUARD_MS = 30_000;
    private static final long FLUSH_INTERVAL_MS = 250;
    private static final int MAX_OPS_PER_FLUSH = 2048;
    /** Cap on queued replicated writes; a full-network resync is far smaller. */
    private static final int MAX_PENDING_WRITES = 65_536;
    /** Upper bound for the shutdown drain: it runs on the disabling region thread. */
    private static final long SHUTDOWN_FLUSH_BUDGET_MS = 500;
    private static final long SHUTDOWN_FLUSH_POLL_MS = 25;
    /** How often a PROFILE_REQUEST for the same player is answered, at most. */
    private static final long PROFILE_ANNOUNCE_DEBOUNCE_MS = 2_000;

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final MikuTPConfig config;
    private final Database database;
    private final SyncBus syncBus;
    private final HomeService homeService;
    private final ProfileService profileService;
    private final TeleportService teleports;
    private final RequestService requestService;
    private final String serverId;
    private final Queue<PendingWrite> pendingWrites = new ConcurrentLinkedQueue<>();
    /** Guard stamp for {@link #dumpSelf()}; CAS so concurrent resync requests
     * cannot both pass the check and publish two full dumps. */
    private final java.util.concurrent.atomic.AtomicLong lastDump =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final AtomicInteger activePlayers = new AtomicInteger();
    /** Serialises flushes so the periodic task cannot interleave with a drain. */
    private final java.util.concurrent.atomic.AtomicBoolean flushing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Mirrors the queue depth so the cap check is O(1). */
    private final AtomicInteger queuedWrites = new AtomicInteger();
    /** Counts shed writes; only the first one is logged. */
    private final AtomicInteger shedWrites = new AtomicInteger();
    /** Set once the plugin is disabling: new replicated writes are refused so
     * the drain below is guaranteed to terminate. */
    private volatile boolean shuttingDown;
    /** Cache updates produced by the batch currently being applied. Only the
     * flush thread touches this; they run after the transaction committed. */
    private final java.util.Deque<Runnable> stagedCacheUpdates = new java.util.ArrayDeque<>();
    /** Last time a PROFILE_REQUEST for a player was answered; keeps joins from storming. */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> profileAnnouncements =
            new java.util.concurrent.ConcurrentHashMap<>();
    private ScheduledTask flushTask;

    public SyncCoordinator(JavaPlugin plugin, Tasks tasks, MikuTPConfig config, Database database,
                           SyncBus syncBus, HomeService homeService, ProfileService profileService,
                           TeleportService teleports, RequestService requestService) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.config = config;
        this.database = database;
        this.syncBus = syncBus;
        this.homeService = homeService;
        this.profileService = profileService;
        this.teleports = teleports;
        this.requestService = requestService;
        this.serverId = config.crossServer.serverId;
    }

    public void start() {
        syncBus.start(this::onEvent);
        flushTask = tasks.asyncRepeat(this::flushPendingWrites, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        if (syncBus.crossServer()) {
            publishResync();
        }
    }

    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        // Drain what is left so a clean stop does not lose replicated state.
        // The callback API is not cancellable, so an in-flight flush can still
        // hold the flag: wait for it instead of abandoning the queue, then drain
        // what it left behind. The wait is explicitly capped because this runs on
        // the disabling thread (the global region thread on Folia): a long stall
        // here would hold up the whole shutdown.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_FLUSH_BUDGET_MS);
        for (int attempt = 0; attempt < 100 && !pendingWrites.isEmpty(); attempt++) {
            if (flushing.get()) {
                if (System.nanoTime() > deadline) {
                    break;
                }
                try {
                    Thread.sleep(SHUTDOWN_FLUSH_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            flushPendingWrites();
        }
        if (!pendingWrites.isEmpty()) {
            plugin.getSLF4JLogger().warn("{} replicated changes could not be flushed before shutdown",
                    queuedWrites.get());
        }
    }

    /**
     * Stops accepting new writes and drains the queue. Called on disable before
     * the database closes; the wait is bounded by the caller's shutdown window.
     */
    public void close() {
        shuttingDown = true;
        shutdown();
    }

    /**
     * /mtp resync: asks every backend to re-publish its full local state.
     *
     * @return true when the request was broadcast; false in single-server mode,
     *         where there is nothing to resynchronise. The command reports the
     *         real outcome instead of always claiming success.
     */
    public boolean resync() {
        if (!syncBus.crossServer()) {
            return false;
        }
        publishResync();
        return true;
    }

    /** Called on join: the first player after a quiet period triggers a resync. */
    public void onPlayerJoin() {
        // Increment unconditionally so the counter stays symmetric with
        // onPlayerQuit; only the resync itself depends on cross-server mode.
        if (activePlayers.incrementAndGet() == 1 && syncBus.crossServer()) {
            publishResync();
        }
    }

    public void onPlayerQuit() {
        activePlayers.decrementAndGet();
    }

    /** Seeds the presence counter with players already online at enable time, so
     * a reload does not mistake the next join for the first one. */
    public void seedActivePlayers(int count) {
        activePlayers.set(Math.max(0, count));
    }

    private void publishResync() {
        SyncEvent event = SyncEvent.create(SyncEvent.Type.RESYNC_REQUEST, serverId);
        syncBus.publish(event);
    }

    /** Routes one sync event to the owning service. */
    public void onEvent(SyncEvent event) {
        if (event.type == null) {
            return;
        }
        // The proxy fans state events out to every backend, including the one that
        // published them. Re-applying our own write would double the SQLite work and
        // can race a concurrent local delete (the staged cache update would then
        // resurrect the deleted row), so self-originated *state replication* events
        // are dropped here. TP_* must NOT be dropped: the proxy routes them back to
        // this backend when both players live here, and a local TP_CANCEL still has
        // to close the target's dialog. RESYNC_REQUEST must not be dropped either:
        // its self-echo is what triggers our own dump.
        if (isOwnStateEvent(event)) {
            return;
        }
        switch (event.type) {
            case HOME_SET -> enqueue(tx -> homeService.applyRemoteSet(event, tx, this::afterCommit));
            case HOME_DELETE -> enqueue(tx -> homeService.applyRemoteDelete(event, tx, this::afterCommit));
            case PROFILE -> enqueue(tx -> profileService.applySync(event, tx, this::afterCommit));
            case BACK -> enqueue(tx -> teleports.applySyncBack(event, tx));
            case IGNORE_SET, IGNORE_DELETE -> enqueue(tx -> requestService.applyIgnoreSync(event, tx));
            case PROFILE_REQUEST -> announceKnownProfile(event);
            case TP_NEW, TP_RESPONDED, TP_READY, TP_CANCEL -> requestService.onSyncEvent(event);
            case RESYNC_REQUEST -> dumpSelf();
        }
    }

    /** True for broadcast state-replication events whose origin already applied them. */
    private boolean isOwnStateEvent(SyncEvent event) {
        if (event.origin == null || !event.origin.equals(serverId)) {
            return false;
        }
        return switch (event.type) {
            case HOME_SET, HOME_DELETE, PROFILE, PROFILE_REQUEST, BACK, IGNORE_SET, IGNORE_DELETE -> true;
            case TP_NEW, TP_RESPONDED, TP_READY, TP_CANCEL, RESYNC_REQUEST -> false;
        };
    }

    /**
     * A backend joined a player whose local profile does not know the replicated
     * tpa toggle yet and asked the network for it. If this backend does know the
     * value, re-announce the profile so the newcomer converges. A short debounce
     * keeps a burst of joins (or a flapping player) from generating a publish storm.
     */
    private void announceKnownProfile(SyncEvent event) {
        String uuid = event.playerUuid;
        if (uuid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = profileAnnouncements.get(uuid);
        if (previous != null && now - previous < PROFILE_ANNOUNCE_DEBOUNCE_MS) {
            return;
        }
        profileAnnouncements.put(uuid, now);
        pruneProfileAnnouncements(now);
        tasks.async(() -> {
            try {
                var profile = database.getPlayer(uuid).orElse(null);
                if (profile == null || !profile.tpaKnown) {
                    // This backend does not know the toggle either; stay silent and
                    // let a backend that does answer the request.
                    return;
                }
                SyncEvent reply = SyncEvent.create(SyncEvent.Type.PROFILE, serverId);
                reply.playerUuid = profile.uuid;
                reply.playerName = profile.name;
                reply.lastOnline = profile.lastOnline;
                reply.tpaEnabled = profile.tpaEnabled;
                // This backend is the one that knows the toggle: the answer carries
                // it as known with its own update stamp, so the asking backend can
                // apply it without touching its identity clock.
                reply.tpaKnown = true;
                reply.tpaUpdatedAt = profile.tpaUpdatedAt;
                syncBus.publish(reply);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to answer a replicated profile request", e);
            }
        });
    }

    /** Drops stale debounce entries once the map grows; it never needs to be large. */
    private void pruneProfileAnnouncements(long now) {
        if (profileAnnouncements.size() < 512) {
            return;
        }
        profileAnnouncements.entrySet().removeIf(
                entry -> now - entry.getValue() > PROFILE_ANNOUNCE_DEBOUNCE_MS);
        // A burst of distinct players (many joins at once) could keep every entry
        // fresh forever; the debounce is only an optimisation, so a hard reset is a
        // safe answer and keeps the map strictly bounded.
        if (profileAnnouncements.size() > 4096) {
            profileAnnouncements.clear();
        }
    }

    /**
     * Registers an in-memory update to run once the surrounding batch committed.
     * Cache state must never lead the database: a rolled-back or individually
     * dropped write would otherwise leave the two permanently diverged.
     */
    private void afterCommit(Runnable update) {
        stagedCacheUpdates.add(update);
    }

    private void enqueue(PendingWrite write) {
        if (shuttingDown) {
            // The queue is being drained for shutdown; accepting here would make
            // the drain a moving target and the write would be lost anyway.
            return;
        }
        // Bound the queue: a large resync arriving faster than the flush can
        // commit would otherwise grow it without limit. Shedding the newest is
        // safe because the activation resync re-publishes full state.
        if (queuedWrites.incrementAndGet() > MAX_PENDING_WRITES) {
            queuedWrites.decrementAndGet();
            if (shedWrites.getAndIncrement() == 0) {
                plugin.getSLF4JLogger().warn("Replicated-change queue is full ({} entries); "
                        + "dropping new writes until it drains. A resync restores full state.",
                        MAX_PENDING_WRITES);
            }
            return;
        }
        pendingWrites.add(write);
    }

    /** One replicated state change bound to the batch transaction. */
    @FunctionalInterface
    private interface PendingWrite {
        void apply(Database.Tx tx) throws java.sql.SQLException;
    }

    /** Applies every queued write in arrival order inside one transaction. */
    private void flushPendingWrites() {
        // One flush at a time: the periodic task and the shutdown drain would
        // otherwise split one logical batch across two transactions and lose
        // commit ordering between a SET and a DELETE for the same row.
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            flushPendingWritesLocked();
        } finally {
            // Never leave staged updates behind: an Error thrown mid-transaction
            // would otherwise be replayed against the next batch's result.
            stagedCacheUpdates.clear();
            flushing.set(false);
        }
    }

    private void flushPendingWritesLocked() {
        if (pendingWrites.isEmpty()) {
            return;
        }
        List<PendingWrite> batch = new ArrayList<>();
        PendingWrite op;
        while (batch.size() < MAX_OPS_PER_FLUSH && (op = pendingWrites.poll()) != null) {
            queuedWrites.decrementAndGet();
            batch.add(op);
        }
        stagedCacheUpdates.clear();
        try {
            database.runTransaction(tx -> {
                for (PendingWrite write : batch) {
                    write.apply(tx);
                }
            });
            // The batch committed: publish its cache updates.
            runStagedCacheUpdates();
        } catch (Exception e) {
            stagedCacheUpdates.clear();
            // One malformed event must not discard the whole batch: re-apply the
            // entries one by one so only the truly broken write is dropped.
            plugin.getSLF4JLogger().warn("Batched apply of {} replicated changes failed, retrying individually",
                    batch.size(), e);
            int dropped = 0;
            for (PendingWrite write : batch) {
                // Each retry keeps only its own cache updates: a successful one is
                // published right away, a failed one contributes nothing, so the
                // two can never be confused.
                stagedCacheUpdates.clear();
                try {
                    database.runTransaction(write::apply);
                } catch (Exception single) {
                    dropped++;
                    stagedCacheUpdates.clear();
                    plugin.getSLF4JLogger().warn("Dropped one replicated change", single);
                    continue;
                }
                runStagedCacheUpdates();
            }
            if (dropped > 0) {
                plugin.getSLF4JLogger().warn("Dropped {} of {} replicated changes", dropped, batch.size());
            }
        }
    }

    private void runStagedCacheUpdates() {
        for (Runnable update : stagedCacheUpdates) {
            try {
                update.run();
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Post-commit cache update failed", e);
            }
        }
        stagedCacheUpdates.clear();
        // The queue drained below its cap: re-arm the one-shot warning.
        shedWrites.set(0);
    }

    /** Publishes this server's full local state; rate-limited to tame event storms. */
    private void dumpSelf() {
        long now = System.currentTimeMillis();
        long previous = lastDump.get();
        if (now - previous < DUMP_GUARD_MS) {
            plugin.getSLF4JLogger().debug("Skipped a requested state dump: the previous one is younger than {}ms",
                    DUMP_GUARD_MS);
            return;
        }
        if (!lastDump.compareAndSet(previous, now)) {
            return; // another thread just claimed this window
        }
        tasks.async(() -> {
            try {
                for (var home : database.listAllHomes()) {
                    SyncEvent event = SyncEvent.create(SyncEvent.Type.HOME_SET, serverId);
                    event.ownerUuid = home.ownerUuid;
                    event.homeName = home.name;
                    event.homePosition = home.position;
                    event.homeCreatedAt = home.createdAt;
                    publishQuietly(event);
                }
                for (var profile : database.listAllProfiles()) {
                    // Identity rows are always republished: a backend that never saw
                    // this player still needs the name row to resolve them by name.
                    // Placeholder rows (created by a replicated position, name "")
                    // carry no identity and are skipped.
                    if (profile.name == null || profile.name.isBlank()) {
                        continue;
                    }
                    SyncEvent event = SyncEvent.create(SyncEvent.Type.PROFILE, serverId);
                    event.playerUuid = profile.uuid;
                    event.playerName = profile.name;
                    event.lastOnline = profile.lastOnline;
                    event.tpaEnabled = profile.tpaEnabled;
                    // The toggle carries its own clock and a known flag; receivers
                    // only apply it when known, so a guessed default can never
                    // overwrite the value the owning backend already has.
                    event.tpaKnown = profile.tpaKnown;
                    event.tpaUpdatedAt = profile.tpaUpdatedAt;
                    publishQuietly(event);
                }
                for (var stored : database.listAllStoredPositions()) {
                    // Carry the per-position update stamps: the receiving side only
                    // overwrites a row when the replicated stamp is not older, so a
                    // dump without a stamp could not propagate positions at all.
                    publishBack(stored.uuid(), "back", stored.back(), stored.backUpdatedAt());
                    publishBack(stored.uuid(), "death", stored.death(), stored.deathUpdatedAt());
                }
                for (var ignore : database.listAllIgnores()) {
                    SyncEvent event = SyncEvent.create(SyncEvent.Type.IGNORE_SET, serverId);
                    event.blockerUuid = ignore.blockerUuid;
                    event.blockedUuid = ignore.blockedUuid;
                    event.expiresAt = ignore.expiresAt;
                    event.createdAt = ignore.createdAt;
                    publishQuietly(event);
                }
                // Deletions are part of the state: a backend that was offline when
                // a home or block was removed would otherwise keep (and re-publish)
                // the vanished row. Tombstones make the dump self-healing.
                for (var tombstone : database.listTombstones()) {
                    SyncEvent event = SyncEvent.create(
                            "home".equals(tombstone.kind()) ? SyncEvent.Type.HOME_DELETE : SyncEvent.Type.IGNORE_DELETE,
                            serverId);
                    if ("home".equals(tombstone.kind())) {
                        event.ownerUuid = tombstone.owner();
                        event.homeName = tombstone.name();
                    } else {
                        event.blockerUuid = tombstone.owner();
                        event.blockedUuid = tombstone.name();
                    }
                    event.deletedAt = tombstone.deletedAt();
                    publishQuietly(event);
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("State dump failed", e);
            }
        });
    }

    /**
     * Publishes one replayed record. A single record that cannot be serialized or
     * queued must not abort the rest of the dump: a partial dump silently leaves
     * the network with half the state.
     */
    private void publishQuietly(SyncEvent event) {
        try {
            syncBus.publish(event);
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Dropped one resync record ({} for {})",
                    event.type, event.playerUuid, e);
        }
    }

    private void publishBack(String uuid, String kind, com.mikumc.mikutp.common.data.Position position,
                             long updatedAt) {
        if (position == null) {
            return;
        }
        SyncEvent event = SyncEvent.create(SyncEvent.Type.BACK, serverId);
        event.playerUuid = uuid;
        event.backKind = kind;
        event.backPosition = position;
        event.backUpdatedAt = updatedAt;
        publishQuietly(event);
    }
}