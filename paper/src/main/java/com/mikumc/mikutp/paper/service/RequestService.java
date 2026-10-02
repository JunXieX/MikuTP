package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.ConfigIO;
import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.data.IgnoreEntry;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.data.TpRequest;
import com.mikumc.mikutp.common.sync.SyncBus;
import com.mikumc.mikutp.common.sync.SyncEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Teleport request lifecycle. Requests live in memory; delivery and answers
 * travel over the sync bus, so local and cross-server requests share one code
 * path. The bus applies every event to every backend, and only the backend
 * currently hosting the affected player acts on it.
 *
 * <p>Cross-server flows (S = requester's backend, T = target's backend):
 * <pre>
 * GO (requester travels, /tpa):
 *   S: publish TP_NEW ──▶ T: show dialog ──▶ target accepts:
 *   T: stash pending(requester ← target's live pos)
 *      publish TP_RESPONDED(ACCEPT) ──▶ S: warmup requester ──▶
 *   S: connect_anchor(requester → target) ──▶ T join: apply pending
 *
 * COME (target travels, /tpahere):
 *   S: publish TP_NEW ──▶ T: show dialog ──▶ target accepts:
 *   T: publish TP_RESPONDED(ACCEPT) ──▶ S: stash pending(target ← requester's live pos)
 *      publish TP_READY ──▶ T: warmup target ──▶
 *   T: connect_anchor(target → requester) ──▶ S join: apply pending
 * </pre>
 * Every hop also writes the request into the target's mailbox so a player who
 * was offline during an event still receives it on rejoin. Sending a new
 * request cancels the sender's previous one via TP_CANCEL (the old target is
 * notified and their dialog is closed).
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
    private final SyncBus syncBus;
    private final String serverId;

    /** Renders an incoming request to its target (dialog or clickable chat). */
    private Consumer<TpRequest> showRequestHandler;

    /** Target player uuid -> request id -> request. Inner maps are concurrent:
     * consumer, sweep and command threads all touch them. */
    private final Map<UUID, Map<String, TpRequest>> incoming = new ConcurrentHashMap<>();
    private final Map<UUID, String> outgoing = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastIncoming = new ConcurrentHashMap<>();
    /** Request ids that were already answered; replayed TP_NEW events must not re-open them. */
    private final java.util.Set<String> answeredRequests = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> deliveredEvents = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> handledEvents = ConcurrentHashMap.newKeySet();
    private volatile long lastDump = 0;
    private ScheduledTask sweepTask;

    public RequestService(JavaPlugin plugin, Tasks tasks, MikuTPConfig config, Database database,
                          MessageService messages, Effects effects, CooldownManager cooldowns,
                          TeleportService teleports, ProfileService profiles, SyncBus syncBus) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.config = config;
        this.database = database;
        this.messages = messages;
        this.effects = effects;
        this.cooldowns = cooldowns;
        this.teleports = teleports;
        this.profiles = profiles;
        this.syncBus = syncBus;
        this.serverId = config.crossServer.serverId;
    }

    public void setShowRequestHandler(Consumer<TpRequest> handler) {
        this.showRequestHandler = handler;
    }

    public void start() {
        sweepTask = tasks.asyncRepeat(this::sweep, 5, 5, TimeUnit.SECONDS);
    }

    public void shutdown() {
        if (sweepTask != null) {
            sweepTask.cancel();
        }
    }

    public void onQuit(UUID player) {
        lastIncoming.remove(player);
        Map<String, TpRequest> mine = incoming.remove(player);
        if (mine != null) {
            for (TpRequest request : mine.values()) {
                outgoing.remove(UUID.fromString(request.requesterUuid), request.id);
            }
        }
        String outgoingId = outgoing.remove(player);
        if (outgoingId != null) {
            for (Map<String, TpRequest> requests : incoming.values()) {
                requests.values().removeIf(r -> r.id.equals(outgoingId));
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
        tasks.async(() -> {
            Player local = Bukkit.getPlayerExact(targetName);
            if (local != null) {
                validateAndSend(requester, local.getUniqueId(), local.getName(), here);
                return;
            }
            profiles.resolve(targetName).thenAccept(opt -> {
                if (opt.isEmpty()) {
                    messages.send(requester, "common.player-not-found", "player", targetName);
                    return;
                }
                ProfileService.PlayerRecord record = opt.get();
                if (record.local()) {
                    // Resolved locally but no longer online: treat as offline.
                    messages.send(requester, "common.player-not-found", "player", targetName);
                    return;
                }
                validateAndSend(requester, record.uuid(), record.name(), here);
            });
        });
    }

    private void validateAndSend(Player requester, UUID targetId, String targetName, boolean here) {
        if (targetId.equals(requester.getUniqueId())) {
            messages.send(requester, "tpa.self");
            return;
        }
        if (!profiles.isTpaEnabled(targetId)) {
            messages.send(requester, "tpa.target-toggled", "player", targetName);
            return;
        }
        if (ignored(targetId, requester.getUniqueId())) {
            messages.send(requester, "tpa.blocked-requester", "player", targetName);
            return;
        }
        boolean onlineHere = Bukkit.getPlayer(targetId) != null;
        if (!onlineHere && !isPresentRemotely(targetId)) {
            messages.send(requester, "common.player-not-found", "player", targetName);
            return;
        }
        // All checks passed: revoke the previous outgoing request (its target is
        // notified and their dialog closed), then burn the cooldown and send.
        cancelOutgoing(requester);
        cooldowns.apply(requester.getUniqueId(), CooldownManager.Kind.TPA);
        TpRequest request = newRequest(requester, targetId, targetName, here);
        outgoing.put(requester.getUniqueId(), request.id);
        publishRequest(request);
        messages.send(requester, here ? "tpa.sent-here" : "tpa.sent",
                "player", targetName, "seconds", String.valueOf(config.tpa.requestExpirySeconds));
    }

    private boolean isPresentRemotely(UUID targetId) {
        if (!syncBus.crossServer()) {
            return false;
        }
        String server = syncBus.presenceGet(targetId);
        return server != null && !server.isBlank();
    }

    /**
     * Revokes the requester's previous outgoing request: publishes TP_CANCEL so
     * the backend hosting the old target removes it, closes their dialog and
     * notifies them. A no-op when nothing is pending.
     */
    private void cancelOutgoing(Player requester) {
        String oldId = outgoing.remove(requester.getUniqueId());
        if (oldId == null) {
            return;
        }
        answeredRequests.add(oldId);
        prune(answeredRequests);
        SyncEvent event = SyncEvent.create(SyncEvent.Type.TP_CANCEL, serverId);
        event.playerUuid = requester.getUniqueId().toString();
        event.playerName = requester.getName();
        syncBus.publish(event);
    }

    /** The requester's previous outgoing request is being replaced. */
    private void publishRequest(TpRequest request) {
        SyncEvent event = SyncEvent.create(SyncEvent.Type.TP_NEW, serverId);
        event.request = request;
        syncBus.publish(event);
        if (syncBus.crossServer()) {
            // If the target happens to be offline while the event flies, the request
            // waits in their mailbox and is delivered the moment they rejoin.
            syncBus.mailboxAdd(request.targetUuid,
                    ConfigIO.gson().toJson(request), config.tpa.requestExpirySeconds);
        }
    }

    private TpRequest newRequest(Player requester, UUID targetId, String targetName, boolean here) {
        long now = System.currentTimeMillis();
        return new TpRequest(java.util.UUID.randomUUID().toString(),
                here ? TpRequest.Type.COME : TpRequest.Type.GO,
                requester.getUniqueId().toString(), requester.getName(), serverId,
                targetId.toString(), targetName, TpRequest.Status.PENDING, now, now);
    }

    // ------------------------------------------------------------------ responding

    public void respond(Player target, String requestId, Response response) {
        tasks.async(() -> {
            Map<String, TpRequest> mine = incoming.get(target.getUniqueId());
            TpRequest request = mine == null ? null : mine.remove(requestId);
            if (request == null) {
                messages.send(target, "tpa.no-pending");
                return;
            }
            outgoing.remove(UUID.fromString(request.requesterUuid), requestId);
            respondCommon(target, request, response);
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

    /** Responds to the pending request sent by the given player. */
    public void respondFromPlayer(Player target, String requesterName, Response response) {
        tasks.async(() -> {
            Map<String, TpRequest> mine = incoming.get(target.getUniqueId());
            if (mine != null) {
                for (TpRequest request : mine.values()) {
                    if (request.requesterName.equalsIgnoreCase(requesterName)) {
                        respond(target, request.id, response);
                        return;
                    }
                }
            }
            messages.send(target, "tpa.no-pending");
        });
    }

    private void respondCommon(Player target, TpRequest request, Response response) {
        effects.click(target);
        answeredRequests.add(request.id);
        prune(answeredRequests);
        // The request is answered: drop its rejoin-mailbox copy so quitting and
        // rejoining cannot replay an already-handled dialog.
        syncBus.mailboxRemove(request.targetUuid, ConfigIO.gson().toJson(request));
        switch (response) {
            case ACCEPT -> messages.send(target, "tpa.accepted-target", "player", request.requesterName);
            case DENY -> messages.send(target, "tpa.denied-target", "player", request.requesterName);
            case BLOCK -> messages.send(target, "tpa.blocked-target", "player", request.requesterName,
                    "minutes", String.valueOf(config.tpa.blockDurationMinutes));
        }
        if (response == Response.BLOCK) {
            storeIgnore(UUID.fromString(request.targetUuid), UUID.fromString(request.requesterUuid),
                    config.tpa.blockDurationMinutes);
        }
        Player requester = Bukkit.getPlayer(UUID.fromString(request.requesterUuid));
        if (requester != null) {
            // Both parties are on this server: teleport locally, no bus event needed.
            switch (response) {
                case ACCEPT -> {
                    Player mover = request.type == TpRequest.Type.GO ? requester : target;
                    Player anchor = request.type == TpRequest.Type.GO ? target : requester;
                    messages.send(requester, "tpa.accepted-requester", "player", request.targetName);
                    if (mover.isOnline()) {
                        teleports.sendLocalTo(mover, anchor, anchor.getName(), true);
                    }
                }
                case DENY -> messages.send(requester, "tpa.denied-requester", "player", request.targetName);
                case BLOCK -> messages.send(requester, "tpa.blocked-requester", "player", request.targetName);
            }
            return;
        }
        if (!syncBus.crossServer()) {
            return; // requester is gone and there is no cross-server path left
        }
        if (response == Response.ACCEPT && request.type == TpRequest.Type.GO) {
            // The requester travels here; stash our position for their arrival and
            // only announce the acceptance once the payload is durably stored.
            teleports.stashPendingTeleport(UUID.fromString(request.requesterUuid),
                    target.getUniqueId(), PendingTeleport.Source.TPA,
                    () -> publishResponded(request, response));
            return;
        }
        publishResponded(request, response);
    }

    private void publishResponded(TpRequest request, Response response) {
        SyncEvent event = SyncEvent.create(SyncEvent.Type.TP_RESPONDED, serverId);
        event.request = request;
        event.response = response.name();
        syncBus.publish(event);
    }

    private void storeIgnore(UUID blocker, UUID blocked, int minutes) {
        long expiresAt = minutes <= 0 ? IgnoreEntry.PERMANENT
                : System.currentTimeMillis() + minutes * 60_000L;
        applyIgnore(new IgnoreEntry(blocker.toString(), blocked.toString(), expiresAt, System.currentTimeMillis()));
        if (syncBus.crossServer()) {
            SyncEvent event = SyncEvent.create(SyncEvent.Type.IGNORE_SET, serverId);
            event.blockerUuid = blocker.toString();
            event.blockedUuid = blocked.toString();
            event.expiresAt = expiresAt;
            syncBus.publish(event);
        }
    }

    public void toggle(Player player) {
        boolean next = !profiles.isTpaEnabled(player.getUniqueId());
        profiles.setTpaEnabled(player.getUniqueId(), next);
        messages.send(player, next ? "tpa.toggled-on" : "tpa.toggled-off");
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
            applyIgnore(new IgnoreEntry(blocker.getUniqueId().toString(), blocked.toString(),
                    expiresAt, System.currentTimeMillis()));
            if (syncBus.crossServer()) {
                SyncEvent event = SyncEvent.create(SyncEvent.Type.IGNORE_SET, serverId);
                event.blockerUuid = blocker.getUniqueId().toString();
                event.blockedUuid = blocked.toString();
                event.expiresAt = expiresAt;
                syncBus.publish(event);
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
            boolean existed;
            try {
                existed = database.clearIgnore(blocker.getUniqueId().toString(), opt.get().uuid().toString());
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to clear ignore entry", e);
                return;
            }
            if (existed && syncBus.crossServer()) {
                SyncEvent event = SyncEvent.create(SyncEvent.Type.IGNORE_DELETE, serverId);
                event.blockerUuid = blocker.getUniqueId().toString();
                event.blockedUuid = opt.get().uuid().toString();
                syncBus.publish(event);
            }
            messages.send(blocker, existed ? "tpa.unblocked" : "tpa.not-blocked", "player", opt.get().name());
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

    // ------------------------------------------------------------------ sync events

    /** Entry point for request events arriving over the sync bus. */
    public void onSyncEvent(SyncEvent event) {
        switch (event.type) {
            case TP_NEW -> deliverRemote(event);
            case TP_RESPONDED -> processResponse(event);
            case TP_READY -> processReady(event);
            case TP_CANCEL -> processCancel(event);
            case IGNORE_SET, IGNORE_DELETE -> applyIgnoreSync(event);
            default -> {
            }
        }
    }

    /**
     * A requester sent a new request, revoking their previous one. Every backend
     * scans its incoming map; the one hosting the old target removes the request,
     * closes the dialog and notifies the target.
     */
    private void processCancel(SyncEvent event) {
        String requesterUuid = event.playerUuid;
        String requesterName = event.playerName;
        if (requesterUuid == null || requesterName == null) {
            return;
        }
        for (Map.Entry<UUID, Map<String, TpRequest>> entry : incoming.entrySet()) {
            List<TpRequest> revoked = new ArrayList<>();
            entry.getValue().values().removeIf(request -> {
                if (request.requesterUuid.equals(requesterUuid)) {
                    revoked.add(request);
                    return true;
                }
                return false;
            });
            if (revoked.isEmpty()) {
                continue;
            }
            Player target = Bukkit.getPlayer(entry.getKey());
            if (target == null) {
                continue;
            }
            for (TpRequest request : revoked) {
                syncBus.mailboxRemove(request.targetUuid, ConfigIO.gson().toJson(request));
            }
            tasks.entity(target, () -> {
                target.closeDialog();
                messages.send(target, "tpa.revoked-target", "player", requesterName);
            });
        }
    }

    private void deliverRemote(SyncEvent event) {
        TpRequest request = event.request;
        if (request == null || request.targetUuid == null) {
            return;
        }
        prune(deliveredEvents);
        if (!deliveredEvents.add(event.id)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (request.status != TpRequest.Status.PENDING
                || answeredRequests.contains(request.id)
                || request.createdAt + config.tpa.requestExpirySeconds * 1000L < now) {
            return;
        }
        if (trackIncoming(request)) {
            showToTarget(request);
        }
    }

    /** Delivers requests that were queued while the target was offline, on rejoin. */
    public void deliverMailbox(UUID targetId) {
        if (!syncBus.crossServer()) {
            return;
        }
        tasks.async(() -> {
            long now = System.currentTimeMillis();
            for (String payload : syncBus.mailboxTake(targetId.toString())) {
                TpRequest request;
                try {
                    request = ConfigIO.gson().fromJson(payload, TpRequest.class);
                } catch (Exception e) {
                    continue;
                }
                if (request == null || request.targetUuid == null
                        || !request.targetUuid.equals(targetId.toString())
                        || request.status != TpRequest.Status.PENDING
                        || answeredRequests.contains(request.id)
                        || request.createdAt + config.tpa.requestExpirySeconds * 1000L < now) {
                    continue;
                }
                if (trackIncoming(request)) {
                    showToTarget(request);
                }
            }
        });
    }

    /** Registers a request for its target; false when it is already tracked. */
    private boolean trackIncoming(TpRequest request) {
        UUID targetId = UUID.fromString(request.targetUuid);
        Map<String, TpRequest> mine = incoming.computeIfAbsent(targetId, k -> new ConcurrentHashMap<>());
        if (mine.containsKey(request.id)) {
            return false;
        }
        mine.put(request.id, request);
        lastIncoming.put(targetId, request.id);
        return true;
    }

    private void showToTarget(TpRequest request) {
        Player target = Bukkit.getPlayer(UUID.fromString(request.targetUuid));
        if (target == null) {
            return;
        }
        tasks.entity(target, () -> {
            Consumer<TpRequest> handler = showRequestHandler;
            if (handler != null) {
                handler.accept(request);
            }
            effects.requestReceived(target);
        });
    }

    private void processResponse(SyncEvent event) {
        TpRequest request = event.request;
        if (request == null) {
            return;
        }
        prune(handledEvents);
        if (!handledEvents.add(event.id)) {
            return;
        }
        Player requester = Bukkit.getPlayer(UUID.fromString(request.requesterUuid));
        if (requester == null) {
            return;
        }
        outgoing.remove(requester.getUniqueId(), request.id);
        tasks.entity(requester, () -> {
            Response response;
            try {
                response = Response.valueOf(event.response);
            } catch (IllegalArgumentException e) {
                return;
            }
            switch (response) {
                case ACCEPT -> {
                    messages.send(requester, "tpa.accepted-requester", "player", request.targetName);
                    if (request.type == TpRequest.Type.GO) {
                        // Prefer a direct teleport when the acceptor is on this server
                        // (covers players who switched servers while the event was in flight).
                        Player anchor = Bukkit.getPlayer(UUID.fromString(request.targetUuid));
                        if (anchor != null) {
                            teleports.sendLocalTo(requester, anchor, anchor.getName(), true);
                        } else {
                            teleports.dispatchToAnchor(requester, request.targetUuid, null);
                        }
                    } else {
                        // Come-here: the target travels. Stash our live position for the
                        // arriving target and only give the go-ahead once it is stored.
                        teleports.stashPendingTeleport(UUID.fromString(request.targetUuid),
                                requester.getUniqueId(), PendingTeleport.Source.TPA_HERE,
                                () -> {
                                    SyncEvent ready = SyncEvent.create(SyncEvent.Type.TP_READY, serverId);
                                    ready.request = request;
                                    syncBus.publish(ready);
                                });
                    }
                }
                case DENY -> messages.send(requester, "tpa.denied-requester", "player", request.targetName);
                case BLOCK -> messages.send(requester, "tpa.blocked-requester", "player", request.targetName);
            }
        });
    }

    private void processReady(SyncEvent event) {
        TpRequest request = event.request;
        if (request == null) {
            return;
        }
        prune(handledEvents);
        if (!handledEvents.add(event.id)) {
            return;
        }
        Player mover = Bukkit.getPlayer(UUID.fromString(request.targetUuid));
        if (mover == null) {
            return;
        }
        outgoing.remove(mover.getUniqueId(), request.id);
        messages.send(mover, "tpa.accepted-requester", "player", request.requesterName);
        Player anchor = Bukkit.getPlayer(UUID.fromString(request.requesterUuid));
        if (anchor != null) {
            teleports.sendLocalTo(mover, anchor, anchor.getName(), true);
        } else {
            teleports.dispatchToAnchor(mover, request.requesterUuid, null);
        }
    }

    private void applyIgnoreSync(SyncEvent event) {
        if (event.blockerUuid == null || event.blockedUuid == null) {
            return;
        }
        try {
            if (event.type == SyncEvent.Type.IGNORE_SET) {
                applyIgnore(new IgnoreEntry(event.blockerUuid, event.blockedUuid,
                        event.expiresAt, System.currentTimeMillis()));
            } else {
                database.clearIgnore(event.blockerUuid, event.blockedUuid);
            }
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Failed to apply replicated ignore entry", e);
        }
    }

    private void applyIgnore(IgnoreEntry entry) {
        try {
            database.addIgnore(entry);
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Failed to store ignore entry", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void sweep() {
        long now = System.currentTimeMillis();
        long expiryMs = config.tpa.requestExpirySeconds * 1000L;
        for (Map.Entry<UUID, Map<String, TpRequest>> entry : incoming.entrySet()) {
            entry.getValue().values().removeIf(request -> {
                if (request.createdAt + expiryMs >= now) {
                    return false;
                }
                outgoing.remove(UUID.fromString(request.requesterUuid), request.id);
                Player requester = Bukkit.getPlayer(UUID.fromString(request.requesterUuid));
                if (requester != null) {
                    tasks.entity(requester, () -> messages.send(requester, "tpa.expired-requester",
                            "player", request.targetName));
                }
                // Close the dialog the target left open.
                Player target = Bukkit.getPlayer(entry.getKey());
                if (target != null) {
                    tasks.entity(target, target::closeDialog);
                }
                return true;
            });
        }
    }

    private boolean ignored(UUID blocker, UUID blocked) {
        try {
            return database.isIgnored(blocker.toString(), blocked.toString(), System.currentTimeMillis());
        } catch (Exception e) {
            return false;
        }
    }

    private static void prune(java.util.Set<String> set) {
        if (set.size() > 512) {
            set.clear();
        }
    }
}
