package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.sync.SyncBus;
import com.mikumc.mikutp.common.sync.SyncEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Wires the sync bus to the services: routes incoming events to their owners,
 * publishes heartbeats and answers resync requests by dumping local state.
 */
public final class SyncCoordinator {

    private static final long DUMP_GUARD_MS = 30_000;

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
    private volatile long lastDump = 0;

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
        if (syncBus.crossServer()) {
            // Ask the network for a full state dump so a fresh or long-offline
            // backend converges, then keep presence fresh.
            publishResync();
            tasks.asyncRepeat(this::heartbeat, 5, 5, TimeUnit.SECONDS);
        }
    }

    /** /mtp resync: asks every backend to re-publish its full local state. */
    public void resync() {
        if (syncBus.crossServer()) {
            publishResync();
        }
    }

    private void publishResync() {
        SyncEvent event = SyncEvent.create(SyncEvent.Type.RESYNC_REQUEST, serverId);
        syncBus.publish(event);
    }

    private void heartbeat() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        Map<UUID, String> presence = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            presence.put(player.getUniqueId(), serverId);
        }
        syncBus.presencePutAll(presence, 15);
    }

    private void onEvent(SyncEvent event) {
        if (event.type == null) {
            return;
        }
        switch (event.type) {
            case HOME_SET -> homeService.applyRemoteSet(event);
            case HOME_DELETE -> homeService.applyRemoteDelete(event);
            case PROFILE -> profileService.applySync(event);
            case BACK -> teleports.applySyncBack(event);
            case IGNORE_SET, IGNORE_DELETE, TP_NEW, TP_RESPONDED, TP_READY -> requestService.onSyncEvent(event);
            case RESYNC_REQUEST -> dumpSelf();
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
