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
    /** Cache updates produced by the batch currently being applied. Only the
     * flush thread touches this; they run after the transaction committed. */
    private final java.util.Deque<Runnable> stagedCacheUpdates = new java.util.ArrayDeque<>();
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
        // Repeated because a flush that is skipped while another one is in
        // progress would otherwise leave its batch behind.
        while (!pendingWrites.isEmpty()) {
            int before = queuedWrites.get();
            flushPendingWrites();
            if (queuedWrites.get() >= before) {
                break;
            }
        }
    }

    /** /mtp resync: asks every backend to re-publish its full local state. */
    public void resync() {
        if (syncBus.crossServer()) {
            publishResync();
        }
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

    private void publishResync() {
        SyncEvent event = SyncEvent.create(SyncEvent.Type.RESYNC_REQUEST, serverId);
        syncBus.publish(event);
    }

    /** Routes one sync event to the owning service. */
    public void onEvent(SyncEvent event) {
        if (event.type == null) {
            return;
        }
        switch (event.type) {
            case HOME_SET -> enqueue(tx -> homeService.applyRemoteSet(event, tx, this::afterCommit));
            case HOME_DELETE -> enqueue(tx -> homeService.applyRemoteDelete(event, tx, this::afterCommit));
            case PROFILE -> enqueue(tx -> profileService.applySync(event, tx, this::afterCommit));
            case BACK -> enqueue(tx -> teleports.applySyncBack(event, tx));
            case IGNORE_SET, IGNORE_DELETE -> enqueue(tx -> requestService.applyIgnoreSync(event, tx));
            case TP_NEW, TP_RESPONDED, TP_READY, TP_CANCEL -> requestService.onSyncEvent(event);
            case RESYNC_REQUEST -> dumpSelf();
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
            // One malformed event must not discard the whole batch: re-apply the
            // entries one by one so only the truly broken write is dropped.
            plugin.getSLF4JLogger().warn("Batched apply of {} replicated changes failed, retrying individually",
                    batch.size(), e);
            stagedCacheUpdates.clear();
            int dropped = 0;
            for (PendingWrite write : batch) {
                try {
                    database.runTransaction(write::apply);
                } catch (Exception single) {
                    dropped++;
                    plugin.getSLF4JLogger().warn("Dropped one replicated change", single);
                }
            }
            if (dropped > 0) {
                plugin.getSLF4JLogger().warn("Dropped {} of {} replicated changes", dropped, batch.size());
            }
            // Committed and dropped writes are indistinguishable here, so the
            // cache is left alone rather than showing rows that may not exist.
            // The next activation resync refreshes it.
            stagedCacheUpdates.clear();
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
                    syncBus.publish(event);
                }
                for (var profile : database.listAllProfiles()) {
                    SyncEvent event = SyncEvent.create(SyncEvent.Type.PROFILE, serverId);
                    event.playerUuid = profile.uuid;
                    event.playerName = profile.name;
                    event.lastOnline = profile.lastOnline;
                    event.tpaEnabled = profile.tpaEnabled;
                    syncBus.publish(event);
                }
                for (var stored : database.listAllStoredPositions()) {
                    publishBack(stored.uuid(), "back", stored.back());
                    publishBack(stored.uuid(), "death", stored.death());
                }
                for (var ignore : database.listAllIgnores()) {
                    SyncEvent event = SyncEvent.create(SyncEvent.Type.IGNORE_SET, serverId);
                    event.blockerUuid = ignore.blockerUuid;
                    event.blockedUuid = ignore.blockedUuid;
                    event.expiresAt = ignore.expiresAt;
                    event.createdAt = ignore.createdAt;
                    syncBus.publish(event);
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
                    syncBus.publish(event);
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("State dump failed", e);
            }
        });
    }

    private void publishBack(String uuid, String kind, com.mikumc.mikutp.common.data.Position position) {
        if (position == null) {
            return;
        }
        SyncEvent event = SyncEvent.create(SyncEvent.Type.BACK, serverId);
        event.playerUuid = uuid;
        event.backKind = kind;
        event.backPosition = position;
        syncBus.publish(event);
    }
}