package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.data.IgnoreEntry;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.data.Position;
import com.mikumc.mikutp.common.data.TpRequest;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Teleport request lifecycle. Local (same-server) requests are handled purely
 * in memory; cross-server requests go through the shared database with an
 * instant proxy delivery plus a polling fallback for robustness.
 */
public final class RequestService {

    public enum Response { ACCEPT, DENY, BLOCK }

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final MikuTPConfig config;
    private final Database database;
    private final MessageService messages;
    private final Effects effects;
    private final CooldownManager cooldowns;
    private final TeleportService teleports;
    private final ProfileService profiles;
    private final NetworkService network;
    private final String serverId;

    /** Renders an incoming request to its target (dialog or clickable chat). */
    private Consumer<TpRequest> showRequestHandler;

    private final ConcurrentHashMap<String, TpRequest> localPending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> localOutgoing = new ConcurrentHashMap<>();
    /** Most recent request id delivered to a player, for /tpaccept without arguments. */
    private final ConcurrentHashMap<UUID, String> lastIncoming = new ConcurrentHashMap<>();
    private final java.util.Set<String> shownRemote = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> handledRemote = ConcurrentHashMap.newKeySet();
    private volatile long watermark = System.currentTimeMillis();
    private volatile long lastHousekeeping = 0;
    private ScheduledTask sweepTask;
    private ScheduledTask pollTask;

    public RequestService(JavaPlugin plugin, Tasks tasks, MikuTPConfig config, Database database,
                          MessageService messages, Effects effects, CooldownManager cooldowns,
                          TeleportService teleports, ProfileService profiles, NetworkService network) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.config = config;
        this.database = database;
        this.messages = messages;
        this.effects = effects;
        this.cooldowns = cooldowns;
        this.teleports = teleports;
        this.profiles = profiles;
        this.network = network;
        this.serverId = config.crossServer.serverId;
    }

    public void setShowRequestHandler(Consumer<TpRequest> handler) {
        this.showRequestHandler = handler;
    }

    public void start(boolean networked) {
        long sweepInterval = 5;
        sweepTask = tasks.asyncRepeat(this::sweep, sweepInterval, sweepInterval, TimeUnit.SECONDS);
        if (networked) {
            long interval = Math.max(200, config.crossServer.pollIntervalMs);
            pollTask = tasks.asyncRepeat(this::poll, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    public void shutdown() {
        if (sweepTask != null) {
            sweepTask.cancel();
        }
        if (pollTask != null) {
            pollTask.cancel();
        }
    }

    public void onQuit(UUID player) {
        String pid = player.toString();
        String outgoing = localOutgoing.remove(player);
        if (outgoing != null) {
            localPending.remove(outgoing);
        }
        for (TpRequest request : localPending.values()) {
            if (request.targetUuid.equals(pid) && localPending.remove(request.id, request)) {
                localOutgoing.remove(UUID.fromString(request.requesterUuid), request.id);
            }
        }
    }

    // ------------------------------------------------------------------ sending

    /** Sends a request from {@code requester} to {@code targetName}. */
    public void send(Player requester, String targetName, boolean here) {
        UUID id = requester.getUniqueId();
        long remaining = cooldowns.remaining(id, CooldownManager.Kind.TPA);
        if (remaining > 0) {
            messages.send(requester, "common.cooldown", "seconds", String.valueOf(remaining));
            return;
        }
        cooldowns.apply(id, CooldownManager.Kind.TPA);
        tasks.async(() -> {
            Player local = Bukkit.getPlayerExact(targetName);
            if (local != null) {
                sendLocal(requester, local, here);
            } else {
                sendRemoteOrOffline(requester, targetName, here);
            }
        });
    }

    /** Responds to the most recent pending request delivered to the player. */
    public void respondLatest(Player target, Response response) {
        String id = lastIncoming.get(target.getUniqueId());
        if (id == null) {
            messages.send(target, "tpa.no-pending");
            return;
        }
        respond(target, id, response);
    }

    /** Responds to the pending request sent by the given player (local requests only). */
    public void respondFromPlayer(Player target, String requesterName, Response response) {
        tasks.async(() -> {
            for (TpRequest request : localPending.values()) {
                if (request.targetUuid.equals(target.getUniqueId().toString())
                        && request.requesterName.equalsIgnoreCase(requesterName)) {
                    respond(target, request.id, response);
                    return;
                }
            }
            messages.send(target, "tpa.no-pending");
        });
    }

    private void sendLocal(Player requester, Player target, boolean here) {
        UUID requesterId = requester.getUniqueId();
        UUID targetId = target.getUniqueId();
        if (requesterId.equals(targetId)) {
            messages.send(requester, "tpa.self");
            return;
        }
        if (localOutgoing.containsKey(requesterId) || hasCrossOutgoing(requesterId)) {
            messages.send(requester, "tpa.already-pending");
            return;
        }
        if (!profiles.isTpaEnabled(targetId)) {
            messages.send(requester, "tpa.target-toggled", "player", target.getName());
            return;
        }
        if (ignored(targetId, requesterId)) {
            messages.send(requester, "tpa.blocked-requester", "player", target.getName());
            return;
        }
        TpRequest request = newRequest(requester, target, here);
        localPending.put(request.id, request);
        localOutgoing.put(requesterId, request.id);
        lastIncoming.put(targetId, request.id);
        messages.send(requester, here ? "tpa.sent-here" : "tpa.sent",
                "player", target.getName(), "seconds", String.valueOf(config.tpa.requestExpirySeconds));
        Player stableTarget = target;
        tasks.entity(stableTarget, () -> {
            Consumer<TpRequest> handler = showRequestHandler;
            if (handler != null) {
                handler.accept(request);
            }
            effects.requestReceived(stableTarget);
        });
    }

    private void sendRemoteOrOffline(Player requester, String targetName, boolean here) {
        profiles.resolve(targetName).thenAccept(opt -> {
            if (opt.isEmpty()) {
                messages.send(requester, "common.player-not-found", "player", targetName);
                return;
            }
            ProfileService.PlayerRecord record = opt.get();
            if (record.uuid().equals(requester.getUniqueId())) {
                messages.send(requester, "tpa.self");
                return;
            }
            if (localOutgoing.containsKey(requester.getUniqueId()) || hasCrossOutgoing(requester.getUniqueId())) {
                messages.send(requester, "tpa.already-pending");
                return;
            }
            if (!network.enabled()) {
                messages.send(requester, "common.cross-disabled");
                return;
            }
            try {
                boolean toggled = database.getPlayer(record.uuid().toString())
                        .map(p -> !p.tpaEnabled)
                        .orElse(false);
                if (toggled) {
                    messages.send(requester, "tpa.target-toggled", "player", record.name());
                    return;
                }
                if (ignored(record.uuid(), requester.getUniqueId())) {
                    messages.send(requester, "tpa.blocked-requester", "player", record.name());
                    return;
                }
                TpRequest request = newRequest(requester, record.uuid(), record.name(), here);
                database.insertRequest(request);
                network.routeTpRequest(request, requester);
                messages.send(requester, here ? "tpa.sent-here" : "tpa.sent",
                        "player", record.name(), "seconds", String.valueOf(config.tpa.requestExpirySeconds));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to send cross-server request", e);
                messages.send(requester, "common.teleport-failed");
            }
        });
    }

    private TpRequest newRequest(Player requester, Player target, boolean here) {
        long now = System.currentTimeMillis();
        return new TpRequest(UUID.randomUUID().toString(), here ? TpRequest.Type.COME : TpRequest.Type.GO,
                requester.getUniqueId().toString(), requester.getName(), serverId,
                target.getUniqueId().toString(), target.getName(), TpRequest.Status.PENDING, now, now);
    }

    private TpRequest newRequest(Player requester, UUID targetId, String targetName, boolean here) {
        long now = System.currentTimeMillis();
        return new TpRequest(UUID.randomUUID().toString(), here ? TpRequest.Type.COME : TpRequest.Type.GO,
                requester.getUniqueId().toString(), requester.getName(), serverId,
                targetId.toString(), targetName, TpRequest.Status.PENDING, now, now);
    }

    // ------------------------------------------------------------------ responding

    public void respond(Player target, String requestId, Response response) {
        tasks.async(() -> {
            TpRequest local = localPending.remove(requestId);
            if (local != null) {
                if (!local.targetUuid.equals(target.getUniqueId().toString())) {
                    localPending.put(requestId, local);
                    messages.send(target, "tpa.no-pending");
                    return;
                }
                localOutgoing.remove(UUID.fromString(local.requesterUuid), requestId);
                respondLocal(target, local, response);
                return;
            }
            if (!network.enabled()) {
                messages.send(target, "tpa.no-pending");
                return;
            }
            try {
                TpRequest request = database.getRequest(requestId).orElse(null);
                if (request == null || !request.targetUuid.equals(target.getUniqueId().toString())
                        || request.status != TpRequest.Status.PENDING) {
                    messages.send(target, "tpa.no-pending");
                    return;
                }
                TpRequest.Status newStatus = switch (response) {
                    case ACCEPT -> TpRequest.Status.ACCEPTED;
                    case DENY -> TpRequest.Status.DENIED;
                    case BLOCK -> TpRequest.Status.BLOCKED;
                };
                database.setRequestStatus(requestId, newStatus, System.currentTimeMillis());
                if (response == Response.BLOCK) {
                    addIgnore(UUID.fromString(request.targetUuid), UUID.fromString(request.requesterUuid),
                            config.tpa.blockDurationMinutes);
                }
                if (response == Response.ACCEPT && request.type == TpRequest.Type.GO) {
                    // The requester travels here; stash our position for their arrival.
                    UUID requesterId = UUID.fromString(request.requesterUuid);
                    tasks.entity(target, () -> {
                        Position here = new Position(serverId, target.getWorld().getName(),
                                target.getLocation().getX(), target.getLocation().getY(),
                                target.getLocation().getZ(), target.getLocation().getYaw(),
                                target.getLocation().getPitch());
                        tasks.async(() -> {
                            try {
                                database.putPendingTeleport(new PendingTeleport(requesterId.toString(), here,
                                        PendingTeleport.Source.TPA, System.currentTimeMillis()));
                            } catch (Exception e) {
                                plugin.getSLF4JLogger().warn("Failed to stash cross-server destination", e);
                            }
                        });
                    });
                }
                effects.click(target);
                switch (response) {
                    case ACCEPT -> messages.send(target, "tpa.accepted-target", "player", request.requesterName);
                    case DENY -> messages.send(target, "tpa.denied-target", "player", request.requesterName);
                    case BLOCK -> messages.send(target, "tpa.blocked-target", "player", request.requesterName,
                            "minutes", String.valueOf(config.tpa.blockDurationMinutes));
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to respond to request", e);
            }
        });
    }

    private void respondLocal(Player target, TpRequest request, Response response) {
        Player requester = Bukkit.getPlayer(UUID.fromString(request.requesterUuid));
        switch (response) {
            case ACCEPT -> {
                messages.send(target, "tpa.accepted-target", "player", request.requesterName);
                if (requester == null) {
                    messages.send(target, "common.player-not-found", "player", request.requesterName);
                    return;
                }
                Player mover = request.type == TpRequest.Type.GO ? requester : target;
                Player anchor = request.type == TpRequest.Type.GO ? target : requester;
                if (!mover.isOnline()) {
                    return;
                }
                messages.send(requester, "tpa.accepted-requester", "player", request.targetName);
                teleports.sendLocal(mover, anchor::getLocation, anchor.getName());
            }
            case DENY -> {
                messages.send(target, "tpa.denied-target", "player", request.requesterName);
                if (requester != null) {
                    messages.send(requester, "tpa.denied-requester", "player", request.targetName);
                }
            }
            case BLOCK -> {
                addIgnore(target.getUniqueId(), UUID.fromString(request.requesterUuid), config.tpa.blockDurationMinutes);
                messages.send(target, "tpa.blocked-target", "player", request.requesterName,
                        "minutes", String.valueOf(config.tpa.blockDurationMinutes));
                if (requester != null) {
                    messages.send(requester, "tpa.blocked-requester", "player", request.targetName);
                }
            }
        }
    }

    // ------------------------------------------------------------------ remote delivery & polling

    /** Instant delivery path from the proxy. */
    public void deliverRemote(TpRequest request) {
        if (request == null || request.targetUuid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (request.createdAt + config.tpa.requestExpirySeconds * 1000L < now) {
            return;
        }
        if (!shownRemote.add(request.id)) {
            return;
        }
        prune(shownRemote);
        Player target = Bukkit.getPlayer(UUID.fromString(request.targetUuid));
        if (target == null) {
            return;
        }
        lastIncoming.put(UUID.fromString(request.targetUuid), request.id);
        tasks.entity(target, () -> {
            Consumer<TpRequest> handler = showRequestHandler;
            if (handler != null) {
                handler.accept(request);
            }
            effects.requestReceived(target);
        });
    }

    private void poll() {
        try {
            long now = System.currentTimeMillis();
            List<String> online = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                online.add(p.getUniqueId().toString());
            }
            long expiryMs = config.tpa.requestExpirySeconds * 1000L;
            if (!online.isEmpty()) {
                for (TpRequest request : database.listPendingForTargets(online, now - expiryMs)) {
                    deliverRemote(request);
                }
                long since = watermark;
                watermark = now - 500;
                for (TpRequest request : database.listUpdatesSince(online, since)) {
                    processUpdate(request, online, now);
                }
            } else {
                watermark = now - 500;
            }
            if (now - lastHousekeeping > 60_000) {
                lastHousekeeping = now;
                database.expireStale(now, expiryMs);
                database.purgeFinished(now, 3_600_000);
            }
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Cross-server poll failed", e);
        }
    }

    private void processUpdate(TpRequest request, List<String> online, long now) {
        boolean requesterOurs = online.contains(request.requesterUuid);
        boolean targetOurs = online.contains(request.targetUuid);
        if (!handledRemote.add(request.id)) {
            return;
        }
        prune(handledRemote);
        if (requesterOurs && request.status == TpRequest.Status.ACCEPTED && request.type == TpRequest.Type.GO) {
            Player mover = Bukkit.getPlayer(UUID.fromString(request.requesterUuid));
            markCompleted(request.id, now);
            if (mover != null) {
                tasks.entity(mover, () -> messages.send(mover, "tpa.accepted-requester", "player", request.targetName));
                teleports.dispatchToAnchor(mover, request.targetUuid, null);
            }
            return;
        }
        if (targetOurs && request.status == TpRequest.Status.ACCEPTED && request.type == TpRequest.Type.COME) {
            Player mover = Bukkit.getPlayer(UUID.fromString(request.targetUuid));
            markCompleted(request.id, now);
            if (mover != null) {
                tasks.entity(mover, () -> messages.send(mover, "tpa.accepted-requester", "player", request.requesterName));
                teleports.dispatchToAnchor(mover, request.requesterUuid, null);
            }
            return;
        }
        if (requesterOurs) {
            Player requester = Bukkit.getPlayer(UUID.fromString(request.requesterUuid));
            if (requester == null) {
                return;
            }
            tasks.entity(requester, () -> {
                switch (request.status) {
                    case DENIED -> messages.send(requester, "tpa.denied-requester", "player", request.targetName);
                    case BLOCKED -> messages.send(requester, "tpa.blocked-requester", "player", request.targetName);
                    case EXPIRED -> messages.send(requester, "tpa.expired-requester", "player", request.targetName);
                    default -> {
                    }
                }
            });
        }
    }

    private void markCompleted(String id, long now) {
        tasks.async(() -> {
            try {
                database.setRequestStatus(id, TpRequest.Status.COMPLETED, now);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to mark request completed", e);
            }
        });
    }

    // ------------------------------------------------------------------ blocks & toggle

    public void toggle(Player player) {
        boolean nowEnabled = !profiles.isTpaEnabled(player.getUniqueId());
        profiles.setTpaEnabled(player.getUniqueId(), nowEnabled);
        messages.send(player, nowEnabled ? "tpa.toggled-on" : "tpa.toggled-off");
    }

    public void block(Player blocker, String targetName, boolean permanent) {
        profiles.resolve(targetName).thenAccept(opt -> {
            if (opt.isEmpty()) {
                messages.send(blocker, "common.player-not-found", "player", targetName);
                return;
            }
            UUID blocked = opt.get().uuid();
            if (blocked.equals(blocker.getUniqueId())) {
                messages.send(blocker, "tpa.self");
                return;
            }
            long expiresAt = permanent ? IgnoreEntry.PERMANENT
                    : System.currentTimeMillis() + Math.max(1, config.tpa.blockDurationMinutes) * 60_000L;
            try {
                database.addIgnore(new IgnoreEntry(blocker.getUniqueId().toString(), blocked.toString(),
                        expiresAt, System.currentTimeMillis()));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to store ignore entry", e);
                return;
            }
            if (permanent) {
                messages.send(blocker, "tpa.blocked-permanent-target", "player", opt.get().name());
            } else {
                messages.send(blocker, "tpa.blocked-target", "player", opt.get().name(),
                        "minutes", String.valueOf(config.tpa.blockDurationMinutes));
            }
        });
    }

    public void unblock(Player blocker, String targetName) {
        profiles.resolve(targetName).thenAccept(opt -> {
            if (opt.isEmpty()) {
                messages.send(blocker, "common.player-not-found", "player", targetName);
                return;
            }
            try {
                if (database.clearIgnore(blocker.getUniqueId().toString(), opt.get().uuid().toString())) {
                    messages.send(blocker, "tpa.unblocked", "player", opt.get().name());
                } else {
                    messages.send(blocker, "tpa.not-blocked", "player", opt.get().name());
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to clear ignore entry", e);
            }
        });
    }

    public void listBlocks(Player blocker) {
        tasks.async(() -> {
            try {
                List<IgnoreEntry> entries = database.listIgnores(blocker.getUniqueId().toString(), System.currentTimeMillis());
                if (entries.isEmpty()) {
                    messages.send(blocker, "tpa.block-list-empty");
                    return;
                }
                List<String> names = new ArrayList<>();
                for (IgnoreEntry entry : entries) {
                    String name = database.getPlayer(entry.blockedUuid).map(p -> p.name).orElse(entry.blockedUuid);
                    names.add(entry.expiresAt == IgnoreEntry.PERMANENT ? name : name + "*");
                }
                messages.send(blocker, "tpa.block-list", "count", String.valueOf(names.size()),
                        "players", String.join(", ", names));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to list ignores", e);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private void sweep() {
        try {
            long now = System.currentTimeMillis();
            long expiryMs = config.tpa.requestExpirySeconds * 1000L;
            for (TpRequest request : localPending.values()) {
                if (request.createdAt + expiryMs < now && localPending.remove(request.id, request)) {
                    localOutgoing.remove(UUID.fromString(request.requesterUuid), request.id);
                    Player requester = Bukkit.getPlayer(UUID.fromString(request.requesterUuid));
                    if (requester != null) {
                        tasks.entity(requester, () -> messages.send(requester, "tpa.expired-requester",
                                "player", request.targetName));
                    }
                }
            }
            if (network.enabled()) {
                database.expireStale(now, expiryMs);
            }
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Request sweep failed", e);
        }
    }

    private boolean hasCrossOutgoing(UUID requester) {
        try {
            return database.hasPendingFromRequester(requester.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean ignored(UUID blocker, UUID blocked) {
        try {
            return database.isIgnored(blocker.toString(), blocked.toString(), System.currentTimeMillis());
        } catch (Exception e) {
            return false;
        }
    }

    private void addIgnore(UUID blocker, UUID blocked, int minutes) {
        long expiresAt = minutes <= 0 ? IgnoreEntry.PERMANENT
                : System.currentTimeMillis() + minutes * 60_000L;
        tasks.async(() -> {
            try {
                database.addIgnore(new IgnoreEntry(blocker.toString(), blocked.toString(),
                        expiresAt, System.currentTimeMillis()));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to store ignore entry", e);
            }
        });
    }

    private static void prune(java.util.Set<String> set) {
        if (set.size() > 512) {
            set.clear();
        }
    }
}
