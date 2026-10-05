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
    private volatile long lastDump = 0;
    private final AtomicInteger activePlayers = new AtomicInteger();
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
        flushPendingWrites();
    }

    /** /mtp resync: asks every backend to re-publish its full local state. */
    public void resync() {
        if (syncBus.crossServer()) {
            publishResync();
        }
    }

    /** Called on join: the first player after a quiet period triggers a resync. */
    public void onPlayerJoin() {
        if (syncBus.crossServer() && activePlayers.incrementAndGet() == 1) {
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
            case HOME_SET -> enqueue(tx -> homeService.applyRemoteSet(event, tx));
            case HOME_DELETE -> enqueue(tx -> homeService.applyRemoteDelete(event, tx));
            case PROFILE -> enqueue(tx -> profileService.applySync(event, tx));
            case BACK -> enqueue(tx -> teleports.applySyncBack(event, tx));
            case IGNORE_SET, IGNORE_DELETE -> enqueue(tx -> requestService.applyIgnoreSync(event, tx));
            case TP_NEW, TP_RESPONDED, TP_READY, TP_CANCEL -> requestService.onSyncEvent(event);
            case RESYNC_REQUEST -> dumpSelf();
        }
    }

    private void enqueue(PendingWrite write) {
        pendingWrites.add(write);
    }

    /** One replicated state change bound to the batch transaction. */
    @FunctionalInterface
    private interface PendingWrite {
        void apply(Database.Tx tx) throws java.sql.SQLException;
    }

    /** Applies every queued write in arrival order inside one transaction. */
    private void flushPendingWrites() {
        if (pendingWrites.isEmpty()) {
            return;
        }
        List<PendingWrite> batch = new ArrayList<>();
        PendingWrite op;
        while (batch.size() < MAX_OPS_PER_FLUSH && (op = pendingWrites.poll()) != null) {
            batch.add(op);
        }
        try {
            database.runTransaction(tx -> {
                for (PendingWrite write : batch) {
                    write.apply(tx);
                }
            });
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Failed to apply {} replicated changes", batch.size(), e);
        }
    }

    /** Publishes this server's full local state; rate-limited to tame event storms. */
    private void dumpSelf() {
        long now = System.currentTimeMillis();
        if (now - lastDump < DUMP_GUARD_MS) {
            return;
        }
        lastDump = now;
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