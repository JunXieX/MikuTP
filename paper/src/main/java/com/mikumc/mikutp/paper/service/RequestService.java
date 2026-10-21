package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.data.IgnoreEntry;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.data.TpRequest;
import com.mikumc.mikutp.common.sync.SyncBus;
import com.mikumc.mikutp.common.sync.SyncEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** FAILED is only published by this backend (hand-off could not be stored). */
    public enum Response { ACCEPT, DENY, BLOCK, FAILED }

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
    private final SyncBus syncBus;
    private final OnlinePlayers onlinePlayers;
    private final String serverId;

    /** Renders an incoming request to its target (dialog or clickable chat). */
    private Consumer<TpRequest> showRequestHandler;

    /** Target player uuid -> request id -> request. Inner maps are concurrent:
     * consumer, sweep and command threads all touch them. */
    private final Map<UUID, Map<String, TpRequest>> incoming = new ConcurrentHashMap<>();
    private final Map<UUID, TpRequest> outgoingRequests = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastIncoming = new ConcurrentHashMap<>();
    /** Request ids that were already answered; replayed TP_NEW events must not re-open them. */
    private final BoundedIdSet answeredRequests = new BoundedIdSet();
    private final BoundedIdSet handledEvents = new BoundedIdSet();
    /** Request ids their requester withdrew (TP_CANCEL seen here): a TP_READY that
     * was already in flight must not fire a teleport for a withdrawn request. */
    private final BoundedIdSet revokedRequests = new BoundedIdSet();
    /** Striped monitors: two /tpatoggle commands must not both read the old value
     * and write the same flip (the read-modify-write now runs on the async pool). */
    private final Object[] toggleLocks = new Object[16];
    /** Request ids a client is answering right now; only one answer may win. */
    private final BoundedIdSet claimedRequests = new BoundedIdSet();
    /** Requests tracked while their target was not resolvable to a player yet. */
    private final Set<String> awaitingShow = ConcurrentHashMap.newKeySet();
    /**
     * Answers that arrived while the player they concern was connected to this
     * backend but not yet visible in the registry (the join window). The proxy
     * only mails events for players with no server connection at all, so an
     * event dropped here would have no replay path: keep it until the join
     * handler runs. Values are the raw events; capped so a broken join cannot
     * leak memory.
     */
    private static final int MAX_DEFERRED = 1024;
    private final Map<UUID, List<SyncEvent>> deferred = new ConcurrentHashMap<>();
    private ScheduledTask sweepTask;

    public RequestService(JavaPlugin plugin, Tasks tasks, MikuTPConfig config, Database database,
                          MessageService messages, Effects effects, CooldownManager cooldowns,
                          TeleportService teleports, ProfileService profiles, NetworkService network,
                          SyncBus syncBus, OnlinePlayers onlinePlayers) {
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
        this.syncBus = syncBus;
        this.onlinePlayers = onlinePlayers;
        this.serverId = config.crossServer.serverId;
        for (int i = 0; i < toggleLocks.length; i++) {
            toggleLocks[i] = new Object();
        }
    }

    private Object toggleLock(UUID player) {
        return toggleLocks[(player.hashCode() & 0x7fffffff) % toggleLocks.length];
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
        // Deferred answers die with the session: they describe a request the
        // player was waiting for while offline, and replaying them on a later
        // login of a stale copy would teleport them unexpectedly.
        deferred.remove(player);
        // The quitting player's incoming requests are dropped locally. Undelivered
        // requests live in the proxy mailbox; a request that was already delivered
        // is re-delivered by the proxy when the player switches servers, so a
        // dropped local copy is not lost.
        Map<String, TpRequest> mine = incoming.remove(player);
        if (mine != null) {
            // Deferred-show marks only make sense while the request is tracked;
            // leaving them behind would grow the set for players who never return.
            mine.keySet().forEach(awaitingShow::remove);
        }
        // The requester's own outgoing entry is dropped too, but the request it
        // referred to may still be live on the target's backend. Publish a cancel
        // first, otherwise the target keeps a dialog that can teleport a player
        // who is no longer waiting for it.
        TpRequest revoked = outgoingRequests.remove(player);
        if (revoked != null) {
            markAnswered(revoked.id);
            SyncEvent event = SyncEvent.create(SyncEvent.Type.TP_CANCEL, serverId);
            event.request = revoked;
            syncBus.publish(event);
        }
    }

    // ------------------------------------------------------------------ sending

    /** Sends a request from {@code requester} to {@code targetName}. */
    public void send(Player requester, String targetName, boolean here) {
        if (featureDisabled(requester)) {
            return;
        }
        tasks.async(() -> {
            // Registry lookup, not Bukkit's: this runs off the region thread.
            Player local = onlinePlayers.byName(targetName);
            if (local != null) {
                validateAndSend(requester, local.getUniqueId(), onlinePlayers.nameOf(local.getUniqueId()), here);
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
        if (!profiles.tpaEnabledFor(targetId)) {
            messages.send(requester, "tpa.target-toggled", "player", targetName);
            return;
        }
        if (ignored(targetId, requester.getUniqueId())) {
            messages.send(requester, "tpa.blocked-requester", "player", targetName);
            return;
        }
        boolean onlineHere = onlinePlayers.byId(targetId) != null;
        if (!onlineHere && !syncBus.crossServer()) {
            messages.send(requester, "common.player-not-found", "player", targetName);
            return;
        }
        if (onlineHere) {
            proceed(requester, targetId, targetName, here);
            return;
        }
        // The target is elsewhere on the network: confirm they are still online
        // via the proxy before sending.
        network.resolveRemote(requester, targetName).thenAccept(found -> {
            if (found == null || !found.equals(targetId)) {
                messages.send(requester, "common.player-not-found", "player", targetName);
                return;
            }
            proceed(requester, targetId, targetName, here);
        });
    }

    private void proceed(Player requester, UUID targetId, String targetName, boolean here) {
        // The target lookup may have taken an async hop: a player who quit in the
        // meantime already had their outgoing request cancelled by onQuit, and a
        // new one must not be resurrected for them.
        if (onlinePlayers.byId(requester.getUniqueId()) == null) {
            return;
        }
        // All checks passed: burn the cooldown atomically (two /tpa in flight must
        // not both pass), then revoke the previous outgoing request and send.
        long remaining = cooldowns.tryConsume(requester.getUniqueId(), CooldownManager.Kind.TPA);
        if (remaining > 0) {
            messages.send(requester, "common.cooldown", "seconds", String.valueOf(remaining));
            return;
        }
        cancelOutgoing(requester);
        TpRequest request = newRequest(requester, targetId, targetName, here);
        outgoingRequests.put(requester.getUniqueId(), request);
        publishRequest(request);
        messages.send(requester, here ? "tpa.sent-here" : "tpa.sent",
                "player", targetName, "seconds", String.valueOf(config.tpa.requestExpirySeconds));
    }

    /**
     * Revokes the requester's previous outgoing request: publishes TP_CANCEL
     * carrying the full request, so the backend hosting the old target removes
     * it, closes their dialog, notifies them and cleans the mailbox copy. A
     * no-op when nothing is pending.
     */
    private void cancelOutgoing(Player requester) {
        TpRequest revoked = outgoingRequests.remove(requester.getUniqueId());
        if (revoked == null) {
            return;
        }
        markAnswered(revoked.id);
        SyncEvent event = SyncEvent.create(SyncEvent.Type.TP_CANCEL, serverId);
        event.request = revoked;
        syncBus.publish(event);
    }

    /** Records a request id as final so a replayed TP_NEW cannot re-open it. */
    private void markAnswered(String requestId) {
        answeredRequests.add(requestId);
        // A final request can never be shown later; drop the deferred-show mark.
        awaitingShow.remove(requestId);
    }

    /**
     * Clears the requester's outgoing entry only when it still refers to
     * {@code requestId}. An unconditional remove would drop a newer request the
     * same player sent while this answer was in flight, losing its tracking.
     */
    private void clearOutgoing(String requesterUuid, String requestId) {
        UUID requester = parseUuid(requesterUuid);
        if (requester == null) {
            return;
        }
        outgoingRequests.computeIfPresent(requester, (k, current) ->
                current.id.equals(requestId) ? null : current);
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The requester's previous outgoing request is being replaced. */
    private void publishRequest(TpRequest request) {
        SyncEvent event = SyncEvent.create(SyncEvent.Type.TP_NEW, serverId);
        event.request = request;
        syncBus.publish(event);
    }

    private TpRequest newRequest(Player requester, UUID targetId, String targetName, boolean here) {
        long now = System.currentTimeMillis();
        String requesterName = onlinePlayers.nameOf(requester.getUniqueId());
        return new TpRequest(java.util.UUID.randomUUID().toString(),
                here ? TpRequest.Type.COME : TpRequest.Type.GO,
                requester.getUniqueId().toString(),
                requesterName == null ? "" : requesterName, serverId,
                targetId.toString(), targetName, TpRequest.Status.PENDING, now, now);
    }

    // ------------------------------------------------------------------ responding

    public void respond(Player target, String requestId, Response response) {
        if (featureDisabled(target)) {
            return;
        }
        tasks.async(() -> {
            Map<String, TpRequest> mine = incoming.get(target.getUniqueId());
            if (mine == null) {
                messages.send(target, "tpa.no-pending");
                return;
            }
            // Claim the id before removing the entry: the sweep decides expiry on
            // the same id under this guard, so an accept racing the expiry cannot
            // produce both "expired" and a teleport.
            if (!claimedRequests.add(requestId)) {
                messages.send(target, "tpa.no-pending");
                return;
            }
            TpRequest request = mine.remove(requestId);
            if (request == null) {
                claimedRequests.remove(requestId);
                messages.send(target, "tpa.no-pending");
                return;
            }
            // Once answered, the request must never be re-delivered: the proxy can
            // replay TP_NEW (mailbox redelivery, fan-out retry) to a backend that
            // missed it the first time.
            markAnswered(request.id);
            clearOutgoing(request.requesterUuid, request.id);
            // Keep the "latest" pointer off a request that no longer exists.
            lastIncoming.remove(target.getUniqueId(), request.id);
            if (response == Response.BLOCK) {
                // A blocking answer also writes an ignore entry: database work
                // belongs on this async thread, not the target's region thread.
                UUID blocker = parseUuid(request.targetUuid);
                UUID blocked = parseUuid(request.requesterUuid);
                if (blocker != null && blocked != null) {
                    storeIgnore(blocker, blocked, config.tpa.blockDurationMinutes);
                }
            }
            // Feedback (sound + messages) touches the target's entity state, so it
            // runs on the target's own thread (unless we are already on it).
            if (tasks.owns(target)) {
                respondCommon(target, request, response);
            } else {
                // A retiring entity must not swallow the answer: the player did click
                // the button, so the hand-off still has to reach the requester.
                tasks.entity(target,
                        () -> respondCommon(target, request, response),
                        () -> respondDetached(target.getUniqueId(), request, response));
            }
        });
    }

    /** Responds to the most recent pending request delivered to the player. */
    public void respondLatest(Player target, Response response) {
        if (featureDisabled(target)) {
            return;
        }
        UUID targetId = target.getUniqueId();
        String id = lastIncoming.get(targetId);
        if (id == null) {
            messages.send(target, "tpa.no-pending");
            return;
        }
        // The remembered id can outlive its request (swept elsewhere, or answered
        // through another path). Clear it so a stale pointer is not reused.
        Map<String, TpRequest> mine = incoming.get(targetId);
        if (mine == null || !mine.containsKey(id)) {
            lastIncoming.remove(targetId, id);
            messages.send(target, "tpa.no-pending");
            return;
        }
        respond(target, id, response);
    }

    /** Responds to the pending request sent by the given player. */
    public void respondFromPlayer(Player target, String requesterName, Response response) {
        if (featureDisabled(target)) {
            return;
        }
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

    /** Shows the answer to the target and hands the accepted teleport off.
     * Runs on the target's entity thread: it plays sounds and touches the dialog. */
    private void respondCommon(Player target, TpRequest request, Response response) {
        // The request was answered: the dialog must not stay clickable.
        target.closeDialog();
        effects.click(target);
        switch (response) {
            case ACCEPT -> messages.send(target, "tpa.accepted-target", "player", request.requesterName);
            case DENY -> messages.send(target, "tpa.denied-target", "player", request.requesterName);
            case BLOCK -> messages.send(target, "tpa.blocked-target", "player", request.requesterName,
                    "minutes", blockMinutesLabel());
            case FAILED -> {
                // Never produced by an answer; handled for exhaustiveness.
            }
        }
        finishResponse(target.getUniqueId(), request, response);
    }

    /**
     * Applies an answer whose target session retired before it could run on the
     * entity thread. Nothing here needs entity state, so the hand-off survives a
     * quit that raced the click.
     */
    private void respondDetached(UUID targetId, TpRequest request, Response response) {
        finishResponse(targetId, request, response);
    }

    /** Feedback to the requester and the actual hand-off (local teleport or bus). */
    private void finishResponse(UUID targetId, TpRequest request, Response response) {
        UUID requesterId = parseUuid(request.requesterUuid);
        Player requester = requesterId == null ? null : onlinePlayers.byId(requesterId);
        if (requester != null) {
            // Both parties are on this server: teleport locally, no bus event needed.
            switch (response) {
                case ACCEPT -> {
                    messages.send(requester, "tpa.accepted-requester", "player", request.targetName);
                    boolean moved = false;
                    if (request.type == TpRequest.Type.GO) {
                        Player anchor = onlinePlayers.byId(targetId);
                        if (anchor != null) {
                            teleports.sendLocalTo(requester, anchor, request.targetName, true);
                            moved = true;
                        }
                    } else {
                        // Come-here: the target travels to the requester.
                        Player mover = onlinePlayers.byId(targetId);
                        if (mover != null) {
                            teleports.sendLocalTo(mover, requester, request.requesterName, true);
                            moved = true;
                        }
                    }
                    if (!moved) {
                        // The other side retired while the answer was hopping onto its
                        // thread: say so instead of leaving the requester waiting.
                        messages.send(requester, "common.teleport-failed");
                    }
                }
                case DENY -> messages.send(requester, "tpa.denied-requester", "player", request.targetName);
                case BLOCK -> messages.send(requester, "tpa.blocked-requester", "player", request.targetName);
                case FAILED -> messages.send(requester, "common.teleport-failed");
            }
            return;
        }
        finishAccept(targetId, requesterId, request, response);
    }

    /** The requester is not on this backend: hand the answer to the bus. */
    private void finishAccept(UUID targetId, UUID requesterId, TpRequest request, Response response) {
        if (!syncBus.crossServer()) {
            return; // requester is gone and there is no cross-server path left
        }
        if (response == Response.ACCEPT && request.type == TpRequest.Type.GO) {
            // The requester travels here; stash our position for their arrival and
            // only announce the acceptance once the payload is durably stored.
            teleports.stashPendingTeleport(requesterId, targetId, PendingTeleport.Source.TPA,
                    () -> publishResponded(request, response),
                    () -> publishResponded(request, Response.FAILED));
            return;
        }
        publishResponded(request, response);
    }

    /** True while the request is still inside its configured lifetime. */
    private boolean withinLifetime(TpRequest request) {
        long expiryMs = config.tpa.requestExpirySeconds * 1000L;
        return request.id != null
                && request.createdAt + expiryMs >= System.currentTimeMillis();
    }

    /** Placeholder text for a block duration; 0 means "until unblocked". */
    private String blockMinutesLabel() {
        int minutes = config.tpa.blockDurationMinutes;
        return String.valueOf(Math.max(0, minutes));
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
        long createdAt = System.currentTimeMillis();
        applyIgnore(new IgnoreEntry(blocker.toString(), blocked.toString(), expiresAt, createdAt));
        if (syncBus.crossServer()) {
            SyncEvent event = SyncEvent.create(SyncEvent.Type.IGNORE_SET, serverId);
            event.blockerUuid = blocker.toString();
            event.blockedUuid = blocked.toString();
            event.expiresAt = expiresAt;
            event.createdAt = createdAt;
            syncBus.publish(event);
        }
    }

    public void toggle(Player player) {
        if (featureDisabled(player)) {
            return;
        }
        // tpaEnabledFor() falls back to a database read when the runtime cache is
        // cold (right after /reload): that read must not run on the region thread.
        UUID id = player.getUniqueId();
        tasks.async(() -> {
            boolean next;
            // Serialise the read-modify-write per player: two rapid /tpatoggle calls
            // would otherwise both read the same value and write the same flip.
            synchronized (toggleLock(id)) {
                next = !profiles.tpaEnabledFor(id);
                profiles.setTpaEnabled(id, next);
            }
            boolean flipped = next;
            tasks.entity(player,
                    () -> messages.send(player, flipped ? "tpa.toggled-on" : "tpa.toggled-off"));
        });
    }

    public void block(Player blocker, String targetName) {
        if (featureDisabled(blocker)) {
            return;
        }
        UUID blockerId = blocker.getUniqueId();
        // 0 (or less) means "until unblocked", the same rule the request dialog's
        // block button uses through storeIgnore(). The configured duration is the
        // single source of truth, so /tpblock no longer silently becomes permanent
        // when the config asks for a timed block.
        int minutes = config.tpa.blockDurationMinutes;
        boolean permanentBlock = minutes <= 0;
        long expiresAt = permanentBlock ? IgnoreEntry.PERMANENT
                : System.currentTimeMillis() + minutes * 60_000L;
        // resolve() completes synchronously for a local player, so the database
        // write must be pushed onto the async pool explicitly: on Folia a region
        // thread must never block on SQLite.
        profiles.resolve(targetName).thenAccept(opt -> tasks.async(() -> {
            if (opt.isEmpty()) {
                messages.send(blocker, "common.player-not-found", "player", targetName);
                return;
            }
            UUID blocked = opt.get().uuid();
            if (blocked.equals(blockerId)) {
                messages.send(blocker, "tpa.self");
                return;
            }
            // The local row and the replicated event must carry the same creation
            // stamp, otherwise the origin and its peers disagree on the LWW order.
            long createdAt = System.currentTimeMillis();
            applyIgnore(new IgnoreEntry(blockerId.toString(), blocked.toString(), expiresAt, createdAt));
            if (syncBus.crossServer()) {
                SyncEvent event = SyncEvent.create(SyncEvent.Type.IGNORE_SET, serverId);
                event.blockerUuid = blockerId.toString();
                event.blockedUuid = blocked.toString();
                event.expiresAt = expiresAt;
                event.createdAt = createdAt;
                syncBus.publish(event);
            }
            if (permanentBlock) {
                messages.send(blocker, "tpa.blocked-permanent-target", "player", opt.get().name());
            } else {
                messages.send(blocker, "tpa.blocked-target", "player", opt.get().name(),
                        "minutes", String.valueOf(Math.max(0, minutes)));
            }
        }));
    }

    public void unblock(Player blocker, String targetName) {
        if (featureDisabled(blocker)) {
            return;
        }
        UUID blockerId = blocker.getUniqueId();
        profiles.resolve(targetName).thenAccept(opt -> tasks.async(() -> {
            if (opt.isEmpty()) {
                messages.send(blocker, "common.player-not-found", "player", targetName);
                return;
            }
            boolean existed;
            try {
                existed = database.clearIgnore(blockerId.toString(), opt.get().uuid().toString());
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to clear ignore entry", e);
                return;
            }
            if (existed && syncBus.crossServer()) {
                SyncEvent event = SyncEvent.create(SyncEvent.Type.IGNORE_DELETE, serverId);
                event.blockerUuid = blockerId.toString();
                event.blockedUuid = opt.get().uuid().toString();
                event.deletedAt = System.currentTimeMillis();
                syncBus.publish(event);
            }
            messages.send(blocker, existed ? "tpa.unblocked" : "tpa.not-blocked", "player", opt.get().name());
        }));
    }

    public void listBlocks(Player blocker) {
        if (featureDisabled(blocker)) {
            return;
        }
        tasks.async(() -> {
            try {
                List<Database.IgnoreView> entries =
                        database.listIgnores(blocker.getUniqueId().toString(), System.currentTimeMillis());
                if (entries.isEmpty()) {
                    messages.send(blocker, "tpa.block-list-empty");
                    return;
                }
                List<String> names = new ArrayList<>();
                for (Database.IgnoreView entry : entries) {
                    String name = entry.name() == null ? entry.blockedUuid() : entry.name();
                    names.add(entry.expiresAt() == IgnoreEntry.PERMANENT ? name : name + "*");
                }
                messages.send(blocker, "tpa.block-list", "count", String.valueOf(names.size()),
                        "players", String.join(", ", names));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to list ignores", e);
            }
        });
    }

    /** True (and tells the player) when the request feature is switched off. */
    private boolean featureDisabled(Player player) {
        if (config.tpa.enabled) {
            return false;
        }
        messages.send(player, "tpa.disabled");
        return true;
    }

    // ------------------------------------------------------------------ sync events

    /** Entry point for request events arriving over the sync bus. */
    public void onSyncEvent(SyncEvent event) {
        if (event.type == null) {
            return;
        }
        // TP_RESPONDED / TP_READY dedupe on the event id; without one they could be
        // applied an unbounded number of times. TP_CANCEL and TP_NEW carry their
        // own request id and are idempotent, so they do not need the event id.
        if (event.id == null
                && (event.type == SyncEvent.Type.TP_RESPONDED || event.type == SyncEvent.Type.TP_READY)) {
            return;
        }
        switch (event.type) {
            case TP_NEW -> deliverRemote(event);
            case TP_RESPONDED -> processResponse(event);
            case TP_READY -> processReady(event);
            case TP_CANCEL -> processCancel(event);
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
        TpRequest revokedRequest = event.request;
        if (revokedRequest == null) {
            return;
        }
        String requesterName = revokedRequest.requesterName;
        String revokedId = revokedRequest.id;
        if (requesterName == null || revokedId == null) {
            return;
        }
        // Remember the withdrawal on every backend: the backend hosting the mover of
        // a COME flow no longer has an incoming copy to remove, yet it still has to
        // reject a TP_READY that is already in flight.
        revokedRequests.add(revokedId);
        for (Map.Entry<UUID, Map<String, TpRequest>> entry : incoming.entrySet()) {
            List<TpRequest> found = new ArrayList<>();
            // Match the exact request id, not every request from this requester:
            // the requester may already have sent a newer request, whose cancel
            // event would otherwise wipe this one here.
            entry.getValue().values().removeIf(request -> {
                if (revokedId.equals(request.id)) {
                    found.add(request);
                    return true;
                }
                return false;
            });
            if (found.isEmpty()) {
                continue;
            }
            // Mark them final: a mailbox redelivery of the revoked TP_NEW must not
            // re-open a request the requester already withdrew.
            for (TpRequest request : found) {
                markAnswered(request.id);
                lastIncoming.remove(entry.getKey(), request.id);
            }
            Player target = onlinePlayers.byId(entry.getKey());
            if (target == null) {
                continue;
            }
            tasks.entity(target, () -> {
                target.closeDialog();
                messages.send(target, "tpa.revoked-target", "player", requesterName);
            });
        }
    }

    private void deliverRemote(SyncEvent event) {
        TpRequest request = event.request;
        if (request == null || request.id == null || request.targetUuid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (request.status != TpRequest.Status.PENDING
                || answeredRequests.contains(request.id)
                || request.createdAt + config.tpa.requestExpirySeconds * 1000L < now) {
            return;
        }
        // Dedupe by request id, never by event id: the proxy re-delivers the last
        // pending TP_NEW when the target switches servers (and after the local copy
        // was dropped on quit), so an event-id guard would hide a still-live request.
        // trackIncoming() is the request-level idempotency point.
        if (trackIncoming(request)) {
            showToTarget(request);
        }
    }

    /**
     * Re-renders requests that were tracked while their target could not be
     * resolved to a player yet (the proxy can push a mailbox entry before the
     * join handler or the player object is visible). Only those requests are
     * re-shown: re-rendering everything that is still pending would pop a
     * second dialog for requests the target already saw.
     */
    public void redeliverPending(Player player) {
        Map<String, TpRequest> mine = incoming.get(player.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (TpRequest request : new ArrayList<>(mine.values())) {
            if (!awaitingShow.contains(request.id)) {
                continue;
            }
            if (request.status != TpRequest.Status.PENDING
                    || answeredRequests.contains(request.id)
                    || request.createdAt + config.tpa.requestExpirySeconds * 1000L < now) {
                awaitingShow.remove(request.id);
                continue;
            }
            awaitingShow.remove(request.id);
            showToTarget(request);
        }
    }

    /**
     * Keeps an answer that arrived before its player was visible in the registry.
     * Bounded: anything beyond the cap is dropped and will be recovered by the
     * request expiring, never by unbounded growth.
     */
    private void defer(UUID player, SyncEvent event) {
        if (player == null || event.id == null) {
            return;
        }
        deferred.compute(player, (key, queue) -> {
            if (queue == null) {
                queue = new ArrayList<>();
            }
            if (queue.size() < MAX_DEFERRED) {
                queue.add(event);
            }
            return queue;
        });
    }

    /** Re-applies answers that were deferred while the player was joining. */
    public void replayDeferred(Player player) {
        List<SyncEvent> queued = deferred.remove(player.getUniqueId());
        if (queued == null) {
            return;
        }
        // Skip answers whose request has already been answered, finalised or
        // expired: a stale acceptance would teleport the player somewhere they
        // are no longer waiting to go.
        long now = System.currentTimeMillis();
        long expiryMs = config.tpa.requestExpirySeconds * 1000L;
        for (SyncEvent event : queued) {
            TpRequest request = event.request;
            if (request == null || request.id == null || answeredRequests.contains(request.id)) {
                continue;
            }
            if (request.createdAt + expiryMs < now) {
                continue;
            }
            if (onlinePlayers.byId(player.getUniqueId()) == null) {
                return;
            }
            try {
                onSyncEvent(event);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to replay a deferred request event", e);
            }
        }
    }

    /** Registers a request for its target; false when it is already tracked. */
    private boolean trackIncoming(TpRequest request) {
        UUID targetId = parseUuid(request.targetUuid);
        if (targetId == null) {
            return false;
        }
        boolean[] added = {false};
        // compute(), not computeIfAbsent + put: the sweep detaches empty inner
        // maps through the same key, so both paths must serialise or a request
        // could be inserted into a map that was just removed from the outer map.
        incoming.compute(targetId, (key, current) -> {
            Map<String, TpRequest> mine = current == null ? new ConcurrentHashMap<>() : current;
            if (mine.putIfAbsent(request.id, request) != null) {
                return mine;
            }
            added[0] = true;
            // "Latest" must mean the newest request, not the last one delivered:
            // events can arrive out of order, and /tpaccept without arguments
            // would otherwise answer an older request than the player just got.
            lastIncoming.merge(targetId, request.id, (currentId, incomingId) -> {
                TpRequest currentRequest = mine.get(currentId);
                return currentRequest == null || currentRequest.createdAt <= request.createdAt
                        ? incomingId : currentId;
            });
            return mine;
        });
        return added[0];
    }

    private void showToTarget(TpRequest request) {
        UUID targetId = parseUuid(request.targetUuid);
        Player target = targetId == null ? null : onlinePlayers.byId(targetId);
        if (target == null) {
            // Tracked but not displayable yet: remember it so the join handler
            // can surface it once the player exists.
            awaitingShow.add(request.id);
            return;
        }
        if (tasks.owns(target)) {
            showNow(target, request);
            return;
        }
        // If the session retires before the task runs the request stays tracked, so
        // remember it for re-delivery on the next join instead of losing it.
        tasks.entity(target, () -> showNow(target, request),
                () -> awaitingShow.add(request.id));
    }

    /** Renders the request to its target; runs on the target's own thread. */
    private void showNow(Player target, TpRequest request) {
        Consumer<TpRequest> handler = showRequestHandler;
        if (handler != null) {
            handler.accept(request);
        }
        effects.requestReceived(target);
    }

    private void processResponse(SyncEvent event) {
        TpRequest request = event.request;
        if (request == null) {
            return;
        }
        UUID requesterId = parseUuid(request.requesterUuid);
        Player requester = requesterId == null ? null : onlinePlayers.byId(requesterId);
        if (requester == null) {
            // The requester is not on this backend right now (switching servers or
            // still joining). Leave the event unhandled: the proxy mails it for a
            // player with no connection, and a player mid-join is queued locally.
            defer(requesterId, event);
            return;
        }
        // Parse before claiming the event id: a payload that cannot be understood
        // must not burn the id, otherwise a later well-formed retry (mailbox
        // replay) would be discarded as handled and the request would hang.
        Response parsed;
        try {
            parsed = Response.valueOf(event.response);
        } catch (IllegalArgumentException | NullPointerException e) {
            return;
        }
        if (event.id == null || !handledEvents.add(event.id)) {
            return;
        }
        // TP_CANCEL and TP_RESPONDED can cross on the bus: an answer for a request
        // that was withdrawn, replaced or has already expired must not teleport
        // anyone.
        if (!withinLifetime(request) || answeredRequests.contains(request.id)) {
            return;
        }
        clearOutgoing(request.requesterUuid, request.id);
        tasks.entity(requester, () -> {
            // Same-request identity is re-checked at execution time: the answer may
            // have been superseded while the task waited for the requester's thread.
            if (parsed == Response.ACCEPT) {
                TpRequest tracked = requesterId == null ? null : outgoingRequests.get(requesterId);
                if (tracked == null || !tracked.id.equals(request.id)) {
                    return;
                }
            }
            switch (parsed) {
                case ACCEPT -> {
                    messages.send(requester, "tpa.accepted-requester", "player", request.targetName);
                    if (request.type == TpRequest.Type.GO) {
                        // Prefer a direct teleport when the acceptor is on this server
                        // (covers players who switched servers while the event was in flight).
                        UUID anchorId = parseUuid(request.targetUuid);
                        Player anchor = anchorId == null ? null : onlinePlayers.byId(anchorId);
                        if (anchor != null) {
                            teleports.sendLocalTo(requester, anchor, request.targetName, true);
                        } else {
                            teleports.dispatchToAnchor(requester, request.targetUuid, null);
                        }
                    } else {
                        // Come-here: the target travels. Stash our live position for the
                        // arriving target and only give the go-ahead once it is stored.
                        UUID moverId = parseUuid(request.targetUuid);
                        if (moverId != null) {
                            teleports.stashPendingTeleport(moverId, requesterId,
                                    PendingTeleport.Source.TPA_HERE,
                                    () -> {
                                        SyncEvent ready = SyncEvent.create(SyncEvent.Type.TP_READY, serverId);
                                        ready.request = request;
                                        syncBus.publish(ready);
                                    },
                                    () -> messages.send(requester, "common.teleport-failed"));
                        }
                    }
                }
                case DENY -> messages.send(requester, "tpa.denied-requester", "player", request.targetName);
                case BLOCK -> messages.send(requester, "tpa.blocked-requester", "player", request.targetName);
                case FAILED -> messages.send(requester, "common.teleport-failed");
            }
        });
    }

    private void processReady(SyncEvent event) {
        TpRequest request = event.request;
        if (request == null) {
            return;
        }
        // A ready signal for a request that has already expired or was withdrawn
        // must not fire a teleport on a player who is no longer waiting for it.
        if (!withinLifetime(request) || revokedRequests.contains(request.id)) {
            return;
        }
        UUID moverId = parseUuid(request.targetUuid);
        Player mover = moverId == null ? null : onlinePlayers.byId(moverId);
        if (mover == null) {
            // Same reasoning as processResponse: keep the event until the player
            // is visible so the acceptance is not lost in the join window.
            defer(moverId, event);
            return;
        }
        if (event.id == null || !handledEvents.add(event.id)) {
            return;
        }
        // outgoingRequests is keyed by the requester, not by the mover (R2-07).
        clearOutgoing(request.requesterUuid, request.id);
        UUID anchorId = parseUuid(request.requesterUuid);
        tasks.entity(mover, () -> {
            // Re-check right before the teleport starts: a withdrawal may have arrived
            // while this task was queued behind the mover's region thread.
            if (!withinLifetime(request) || revokedRequests.contains(request.id)) {
                return;
            }
            messages.send(mover, "tpa.accepted-requester", "player", request.requesterName);
            Player anchor = anchorId == null ? null : onlinePlayers.byId(anchorId);
            if (anchor != null) {
                teleports.sendLocalTo(mover, anchor, request.requesterName, true);
            } else {
                teleports.dispatchToAnchor(mover, request.requesterUuid, null);
            }
        });
    }

    /** Applies a replicated ignore change; the write joins the caller's batched
     * transaction. */
    public void applyIgnoreSync(SyncEvent event, Database.Tx tx) throws java.sql.SQLException {
        if (event.blockerUuid == null || event.blockedUuid == null) {
            return;
        }
        if (event.type == SyncEvent.Type.IGNORE_SET) {
            tx.addIgnore(new IgnoreEntry(event.blockerUuid, event.blockedUuid,
                    event.expiresAt,
                    // Use the origin's creation stamp so two backends converge on
                    // the same last-writer-wins decision.
                    event.createdAt > 0 ? event.createdAt : System.currentTimeMillis()));
        } else {
            long deletedAt = event.deletedAt > 0 ? event.deletedAt : System.currentTimeMillis();
            tx.clearIgnore(event.blockerUuid, event.blockedUuid, deletedAt);
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
            UUID targetId = entry.getKey();
            Map<String, TpRequest> mine = entry.getValue();
            mine.values().removeIf(request -> {
                if (request.createdAt + expiryMs >= now) {
                    return false;
                }
                // Sweeping keeps the expired entry in the map; the claim guard is
                // what decides which side wins an answer racing this expiry.
                if (!claimedRequests.add(request.id)) {
                    return false;
                }
                markAnswered(request.id);
                clearOutgoing(request.requesterUuid, request.id);
                UUID requesterId = parseUuid(request.requesterUuid);
                Player requester = requesterId == null ? null : onlinePlayers.byId(requesterId);
                if (requester != null) {
                    tasks.entity(requester, () -> messages.send(requester, "tpa.expired-requester",
                            "player", request.targetName));
                }
                // Close the dialog the target left open.
                Player target = onlinePlayers.byId(targetId);
                if (target != null) {
                    tasks.entity(target, target::closeDialog);
                }
                return true;
            });
            // Drop the inner map only when it is still the one we swept and is
            // still empty. Both tests run inside compute(), so a request that
            // trackIncoming adds concurrently (same instance, after removeIf)
            // keeps the entry alive instead of being detached with it.
            boolean[] dropped = {false};
            incoming.compute(targetId, (key, current) -> {
                if (current == null) {
                    return null;
                }
                if (current == mine && current.isEmpty()) {
                    dropped[0] = true;
                    return null;
                }
                return current;
            });
            if (dropped[0]) {
                // Clear the pointer only when it does not name a live request: a
                // request tracked concurrently after the sweep must keep its
                // pointer, otherwise /tpaccept answers a dead id.
                Map<String, TpRequest> live = incoming.get(targetId);
                lastIncoming.computeIfPresent(targetId,
                        (key, id) -> live != null && live.containsKey(id) ? id : null);
            }
        }
        sweepOutgoing(now, expiryMs);
    }

    /**
     * Drops the requester's own copy of an expired request. Without this the
     * entry would sit in the map until the player quits, and the requester would
     * never learn that their request expired.
     */
    private void sweepOutgoing(long now, long expiryMs) {
        for (Map.Entry<UUID, TpRequest> entry : outgoingRequests.entrySet()) {
            TpRequest request = entry.getValue();
            if (request.createdAt + expiryMs >= now) {
                continue;
            }
            // Claim before removing. Losing the claim means the target is answering
            // right now: leave the entry in place so its response still finds the
            // request, and let that path publish the outcome.
            if (!claimedRequests.add(request.id)) {
                continue;
            }
            if (!outgoingRequests.remove(entry.getKey(), request)) {
                // A newer request replaced this one; the stale id needs no notice.
                continue;
            }
            markAnswered(request.id);
            Player requester = onlinePlayers.byId(entry.getKey());
            if (requester != null) {
                tasks.entity(requester, () -> messages.send(requester, "tpa.expired-requester",
                        "player", request.targetName));
            }
        }
    }

    private boolean ignored(UUID blocker, UUID blocked) {
        try {
            return database.isIgnored(blocker.toString(), blocked.toString(), System.currentTimeMillis());
        } catch (Exception e) {
            // Fail closed: when the block list cannot be read, honour the block
            // rather than letting a request through that the target forbade.
            plugin.getSLF4JLogger().warn("Failed to read ignore list, treating as blocked", e);
            return true;
        }
    }
}
