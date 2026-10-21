package com.mikumc.mikutp.velocity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.net.ProxyMessages;
import com.mikumc.mikutp.common.sync.SyncEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Velocity companion plugin doubles as the in-memory coordination hub for
 * the cross-server features:
 *
 * <ul>
 *   <li><b>event fan-out</b>: sync events published by a backend are fanned
 *       out to every server (queueing for empty ones until a player joins),
 *       and request events are routed to the backend hosting the affected
 *       player;</li>
 *   <li><b>pending teleports</b>: cross-server handoff payloads are kept per
 *       player and delivered to the destination backend as soon as the player
 *       connects there;</li>
 *   <li><b>request mailboxes</b>: requests for offline players wait here and
 *       are delivered on their next join (until they expire);</li>
 *   <li><b>player directory</b>: online lookups and per-server player lists.</li>
 * </ul>
 *
 * <p>All state is in memory: backend SQLite databases stay the source of
 * truth, and a proxy restart is healed by the activation-time resync of the
 * backends. Every message is authenticated with the shared token from
 * {@code plugins/MikuTP/config.properties}; without a token the hub refuses
 * backend traffic so a modified client can never drive it.
 */
@Plugin(id = "mikutp", name = "MikuTP", version = MikuTPVelocity.VERSION,
        description = "Cross-server teleport hub for MikuTP backends. MikuMC original plugin by JunXieX, group 1105054380.",
        authors = {"JunXieX"})
public final class MikuTPVelocity {

    /**
     * Must equal the Gradle {@code version} in the root build script; the CI
     * "Verify versions agree" step fails the build when the two drift apart.
     */
    public static final String VERSION = "1.6.2";

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("mikumc", "mikutp");
    private static final Gson GSON = new Gson();
    private static final int MAX_EVENTS_PER_BATCH = 64;
    /** Cap per-server backlog: a server that stays empty must not grow the queue
     * without bound. Older events are shed — the activation resync re-converges. */
    private static final int MAX_QUEUED_EVENTS = 4096;
    /** Hard cap on how many events one player's mailbox may hold (oldest shed). */
    private static final int MAX_MAILBOX_ENTRIES = 32;
    /** Hard cap on how many distinct players may hold a mailbox at once. */
    private static final int MAX_MAILBOX_KEYS = 1024;
    /** Hard cap on how many players may have a pending handoff at once. */
    private static final int MAX_PENDING_KEYS = 1024;
    /** How many unanswered TP_NEW copies are kept per player for switch migration. */
    private static final int MAX_RECENT_REQUESTS = 8;
    /** Hard cap on how many players may have recent TP_NEW copies at once. */
    private static final int MAX_RECENT_REQUEST_KEYS = 512;
    private static final long MAILBOX_TTL_MS = 120_000;
    private static final long PENDING_TTL_MS = 300_000;
    /**
     * A TP_NEW copy is re-delivered to whatever server the target moves to, so a
     * request that arrived just before a server switch is not lost. The backend
     * deduplicates by event id / request id, so re-delivery is safe.
     */
    private static final long RECENT_REQUEST_TTL_MS = 120_000;
    /**
     * Backlog pump period. Events are drained in batches instead of one message
     * per publish; 100ms keeps the perceived latency acceptable.
     */
    private static final long FLUSH_INTERVAL_MS = 100;
    private static final String CONFIG_FILE = "config.properties";
    /**
     * Folder for this plugin's data. Velocity derives the injected
     * {@code @DataDirectory} from the plugin id, which yields
     * {@code plugins/mikutp}; the proxy keeps the same folder name as the
     * backends, {@code plugins/MikuTP}.
     */
    private static final String DATA_DIRECTORY = "MikuTP";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    /**
     * Velocity's id-based directory ({@code plugins/mikutp}); only consulted
     * once to carry an existing configuration over to {@link #dataDirectory}.
     */
    private final Path defaultDataDirectory;

    /** Cross-server pending teleport handoffs, delivered on arrival. */
    private final Map<UUID, PendingEntry> pendingEntries = new ConcurrentHashMap<>();
    /** Request mailboxes for offline players, delivered on their next join. */
    private final Map<UUID, List<MailboxEntry>> mailboxes = new ConcurrentHashMap<>();
    /** Recent TP_NEW copies, keyed by target uuid; re-delivered after a switch. */
    private final Map<UUID, List<RecentRequest>> recentRequests = new ConcurrentHashMap<>();
    /** Events queued for servers that currently have no players. */
    private final Map<String, java.util.ArrayDeque<String>> outbound = new ConcurrentHashMap<>();
    /** One monitor per server queue: draining and sending must be atomic with
     * respect to each other, otherwise two senders can emit batches out of order
     * and a HOME_SET/HOME_DELETE pair lands reversed. */
    private final Map<String, Object> queueLocks = new ConcurrentHashMap<>();
    private volatile String token = "";
    private volatile boolean warnedRejected = false;
    private volatile boolean warnedSendFailure = false;
    private volatile boolean warnedMalformed = false;
    /** Periodic expiry sweep; cancelled on shutdown. */
    private com.velocitypowered.api.scheduler.ScheduledTask sweepTask;
    /** Periodic backlog pump; cancelled on shutdown. */
    private com.velocitypowered.api.scheduler.ScheduledTask flushTask;

    private record PendingEntry(String payload, long expiry, String destServer, String anchorServer) {
    }

    private record MailboxEntry(String json, long expiry) {
    }

    private record RecentRequest(String requestId, String json, long expiry) {
    }

    @Inject
    public MikuTPVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.defaultDataDirectory = dataDirectory;
        // The parent of the injected path is the proxy's plugins directory.
        Path pluginsDirectory = dataDirectory.getParent();
        this.dataDirectory = pluginsDirectory == null
                ? dataDirectory
                : pluginsDirectory.resolve(DATA_DIRECTORY);
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        loadConfig();
        server.getChannelRegistrar().register(CHANNEL);
        // Expired mailboxes and handoffs are normally drained on the player's
        // next join; a player who never returns would otherwise leave their
        // entries in memory forever. The handle is kept so the task is cancelled
        // when the plugin unloads.
        sweepTask = server.getScheduler().buildTask(this, this::sweepExpired)
                .repeat(60, java.util.concurrent.TimeUnit.SECONDS).schedule();
        // One frame per publish would turn a resync into thousands of messages;
        // the pump drains every server queue in batches instead.
        flushTask = server.getScheduler().buildTask(this, this::flushAllQueues)
                .repeat(FLUSH_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS).schedule();
        logger.info("MikuTP hub ready on channel {}", CHANNEL.getId());
    }

    /** Stops the periodic sweep and drops the in-memory state with the plugin. */
    @Subscribe
    public void onShutdown(com.velocitypowered.api.event.proxy.ProxyShutdownEvent event) {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        pendingEntries.clear();
        mailboxes.clear();
        recentRequests.clear();
        outbound.clear();
    }

    /** Drops expired entries so long-lived proxies do not accumulate state. */
    private void sweepExpired() {
        try {
            sweepExpiredLocked();
        } catch (Exception e) {
            logger.warn("MikuTP expiry sweep failed: {}", e.toString());
        }
    }

    private void sweepExpiredLocked() {
        long now = System.currentTimeMillis();
        pendingEntries.entrySet().removeIf(entry -> entry.getValue().expiry() <= now);
        mailboxes.entrySet().removeIf(entry -> {
            List<MailboxEntry> mailbox = entry.getValue();
            synchronized (mailbox) {
                mailbox.removeIf(m -> m.expiry() <= now);
                return mailbox.isEmpty();
            }
        });
        recentRequests.entrySet().removeIf(entry -> {
            List<RecentRequest> list = entry.getValue();
            synchronized (list) {
                list.removeIf(r -> r.expiry() <= now);
                return list.isEmpty();
            }
        });
    }

    /** Reads (and creates a documented template for) {@code plugins/MikuTP/config.properties}. */
    private void loadConfig() {
        Path file = dataDirectory.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(dataDirectory);
            migrateFromDefaultDirectory(file);
            if (Files.notExists(file)) {
                try (OutputStream out = Files.newOutputStream(file)) {
                    out.write(("""
                            # MikuTP 跨服代理配置
                            # token：跨服通信的共享密钥，必须与每台后端 config.yml 中的 sync.token 完全一致。
                            # 留空时本插件会拒绝所有后端消息（防止被改造过的客户端伪造同步数据）。
                            token=
                            """).getBytes(StandardCharsets.UTF_8));
                }
            }
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
            token = properties.getProperty("token", "").trim();
        } catch (IOException e) {
            logger.warn("Failed to read {}", file, e);
            token = "";
        }
        if (token.isEmpty()) {
            logger.error("No sync token configured: set 'token' in {} to the same value as sync.token "
                    + "in every backend config.yml, otherwise cross-server sync is refused.", file);
        }
    }

    /**
     * Carries the token over from Velocity's id-based directory
     * ({@code plugins/mikutp}) the first time the renamed directory is used, so
     * an upgraded proxy keeps syncing instead of silently refusing every
     * backend. The old file is left untouched: deleting a server's
     * configuration is not this plugin's call.
     */
    private void migrateFromDefaultDirectory(Path file) {
        Path legacy = defaultDataDirectory.resolve(CONFIG_FILE);
        if (legacy.equals(file) || Files.exists(file) || Files.notExists(legacy)) {
            return;
        }
        try {
            Files.copy(legacy, file);
            logger.info("Copied the proxy configuration from {} to {}; the old directory can be removed.",
                    legacy, file);
        } catch (IOException e) {
            logger.warn("Could not copy the proxy configuration from {} to {}", legacy, file, e);
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        // Swallow everything on our channel so clients can never forge or snoop it.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) {
            return;
        }
        JsonObject message = ProxyMessages.decode(event.getData());
        if (message == null) {
            return;
        }
        if (!ProxyMessages.verify(message, token)) {
            warnRejected();
            return;
        }
        try {
            handle(message, source);
        } catch (Exception e) {
            warnMalformed("message", e);
        }
    }

    private void warnMalformed(String what, Exception e) {
        if (warnedMalformed) {
            logger.debug("Dropped malformed MikuTP {}: {}", what, e.toString());
        } else {
            warnedMalformed = true;
            logger.warn("Dropped malformed MikuTP {}: {}", what, e.toString());
        }
    }

    private void warnRejected() {
        if (!warnedRejected) {
            warnedRejected = true;
            logger.warn("Dropped an unauthenticated MikuTP message; the backend token does not match this proxy's.");
        }
    }

    private void handle(JsonObject message, ServerConnection source) {
        String type = ProxyMessages.type(message);
        if (type == null) {
            return;
        }
        switch (type) {
            case ProxyMessages.TYPE_BUS -> handleBus(message);
            case ProxyMessages.TYPE_CONNECT -> connect(message, source);
            case ProxyMessages.TYPE_CONNECT_ANCHOR -> connectAnchor(message, source);
            case ProxyMessages.TYPE_LIST_PLAYERS -> listPlayers(message, source);
            case ProxyMessages.TYPE_RESOLVE_PLAYER -> resolvePlayer(message, source);
            default -> logger.debug("Unknown MikuTP message type {}", type);
        }
    }

    // ------------------------------------------------------------------ bus

    private void handleBus(JsonObject message) {
        if (!message.has("m") || !message.get("m").isJsonArray()) {
            return;
        }
        for (JsonElement element : message.getAsJsonArray("m")) {
            if (!element.isJsonObject()) {
                continue;
            }
            // One malformed op must not drop the rest of the batch.
            try {
                handleBusOp(element.getAsJsonObject());
            } catch (Exception e) {
                warnMalformed("bus op", e);
            }
        }
    }

    private void handleBusOp(JsonObject op) {
        String kind = ProxyMessages.string(op, "k");
        if (kind == null) {
            return;
        }
        switch (kind) {
            case "event" -> routeEvent(parseEvent(op));
            case "pending" -> storePending(op);
            default -> logger.debug("Unknown MikuTP bus op {}", kind);
        }
    }

    private SyncEvent parseEvent(JsonObject op) {
        JsonElement element = op.get("e");
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return GSON.fromJson(element.getAsString(), SyncEvent.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void storePending(JsonObject op) {
        String uuid = ProxyMessages.string(op, "uuid");
        String payload = ProxyMessages.string(op, "payload");
        if (uuid == null || payload == null) {
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        // Optional destination server. Without it the handoff is only delivered
        // when the player connects somewhere: the player is usually still on the
        // source server while the handoff travels, so delivering it to whatever
        // server they are on right now would lose it on that server.
        String destServer = ProxyMessages.string(op, "server");
        // Anchor-only handoffs (/otp) carry no destination server. The connect
        // request that follows targets the anchor's server, so snapshot it now and
        // deliver there (a later anchor move must not strand the player elsewhere).
        String anchorServer = destServer == null ? anchorServerName(pendingAnchorUuid(payload)) : null;
        PendingEntry entry = new PendingEntry(payload,
                System.currentTimeMillis() + pendingTtlMillis(op), destServer, anchorServer);
        putPending(id, entry);
        // The connect request and the handoff travel on different paths, so the
        // player may already have arrived. Hand it over immediately only when
        // the current server *is* the destination.
        if (destServer != null) {
            server.getPlayer(id).flatMap(Player::getCurrentServer).ifPresent(connection -> {
                if (destServer.equals(connection.getServerInfo().getName())
                        && pendingEntries.remove(id, entry)
                        && !send(connection, ProxyMessages.encodePending(uuid, payload, 0))) {
                    pendingEntries.putIfAbsent(id, entry);
                }
            });
        }
    }

    /**
     * Reads the optional {@code ttl} of a pending op (seconds) and clamps it, so
     * the sender's TTL is honoured instead of silently replaced by the default.
     */
    private long pendingTtlMillis(JsonObject op) {
        JsonElement ttl = op.get("ttl");
        if (ttl == null || !ttl.isJsonPrimitive()) {
            return PENDING_TTL_MS;
        }
        try {
            long seconds = ttl.getAsLong();
            return Math.min(PENDING_TTL_MS, Math.max(1_000L, seconds * 1_000L));
        } catch (Exception e) {
            return PENDING_TTL_MS;
        }
    }

    /** Reads the anchor of an anchor-only handoff; null when absent or malformed. */
    private String pendingAnchorUuid(String payload) {
        try {
            PendingTeleport pending = GSON.fromJson(payload, PendingTeleport.class);
            return pending == null ? null : pending.anchorUuid;
        } catch (Exception e) {
            return null;
        }
    }

    /** Server the anchor is on right now, or null when it cannot be resolved. */
    private String anchorServerName(String anchorUuid) {
        if (anchorUuid == null || anchorUuid.isEmpty()) {
            return null;
        }
        return parsePlayer(anchorUuid)
                .flatMap(Player::getCurrentServer)
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
    }

    /** Stores a handoff, keeping the map bounded (oldest expiry goes first). */
    private void putPending(UUID id, PendingEntry entry) {
        PendingEntry previous = pendingEntries.put(id, entry);
        if (previous != null || pendingEntries.size() <= MAX_PENDING_KEYS) {
            return;
        }
        UUID victim = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<UUID, PendingEntry> candidate : pendingEntries.entrySet()) {
            if (candidate.getKey().equals(id) || candidate.getValue().expiry() >= oldest) {
                continue;
            }
            oldest = candidate.getValue().expiry();
            victim = candidate.getKey();
        }
        if (victim != null) {
            pendingEntries.remove(victim);
        }
    }

    /**
     * Routes one sync event: player-scoped events go to the backend hosting
     * the affected player (or the mailbox when offline), state replication
     * events are fanned out to every server, cancels go everywhere.
     */
    private void routeEvent(SyncEvent event) {
        if (event == null || event.type == null) {
            return;
        }
        switch (event.type) {
            case TP_NEW -> {
                if (event.request == null) {
                    return;
                }
                if (!deliverToPlayer(event.request.targetUuid, event)) {
                    mailboxAdd(event.request.targetUuid, event);
                }
                // Keep a copy: a request that arrives just before the target
                // switches servers would otherwise be lost with the old server.
                rememberRecentRequest(event);
            }
            case TP_RESPONDED -> {
                if (event.request == null) {
                    return;
                }
                purgeMailbox(event.request.targetUuid, event.request.id);
                purgeRecentRequest(event.request.targetUuid, event.request.id);
                // A requester mid-switch cannot be resolved right now; the mailbox
                // keeps the answer for their next join instead of dropping it (there
                // is no other replay path for this event).
                if (!deliverToPlayer(event.request.requesterUuid, event)) {
                    mailboxAdd(event.request.requesterUuid, event);
                }
            }
            case TP_READY -> {
                if (event.request == null) {
                    return;
                }
                // A ready signal is applied once and only makes sense on arrival:
                // purge any stale copy instead of mail-adding, which would fire the
                // teleport again on a later join.
                purgeMailbox(event.request.targetUuid, event.request.id);
                deliverToPlayer(event.request.targetUuid, event);
            }
            case TP_CANCEL -> {
                if (event.request == null) {
                    return;
                }
                purgeMailbox(event.request.targetUuid, event.request.id);
                purgeRecentRequest(event.request.targetUuid, event.request.id);
                // The requester may also hold a mailboxed TP_RESPONDED for the very
                // request being withdrawn; delivering it later would teleport them
                // to a target they already gave up on.
                purgeMailbox(event.request.requesterUuid, event.request.id);
                purgeRecentRequest(event.request.requesterUuid, event.request.id);
                broadcast(event);
            }
            case HOME_SET, HOME_DELETE, PROFILE, BACK, IGNORE_SET, IGNORE_DELETE, RESYNC_REQUEST,
                    PROFILE_REQUEST -> broadcast(event);
        }
    }

    /** Deliver a wire event to the backend currently hosting the player. */
    private boolean deliverToPlayer(String playerUuid, SyncEvent event) {
        if (playerUuid == null) {
            return false;
        }
        UUID id;
        try {
            id = UUID.fromString(playerUuid);
        } catch (IllegalArgumentException e) {
            return false;
        }
        Optional<ServerConnection> target = server.getPlayer(id).flatMap(Player::getCurrentServer);
        if (target.isEmpty()) {
            return false;
        }
        return sendEvents(target.get(), List.of(event));
    }

    private void broadcast(SyncEvent event) {
        // Encode once: every server queue receives the same immutable JSON object.
        // The queue keeps the encoded form, so the pump never has to
        // deserialize a SyncEvent and serialize it again.
        String json = GSON.toJson(event);
        for (RegisteredServer target : server.getAllServers()) {
            enqueue(target, json);
        }
    }

    // ------------------------------------------------------------------ queues

    private void enqueue(RegisteredServer target, String eventJson) {
        String name = target.getServerInfo().getName();
        java.util.ArrayDeque<String> queue = outbound.computeIfAbsent(name, n -> new java.util.ArrayDeque<>());
        synchronized (lockFor(name)) {
            queue.addLast(eventJson);
            while (queue.size() > MAX_QUEUED_EVENTS) {
                queue.pollFirst();
            }
        }
        // Drained by flushAllQueues() on a fixed period. Flushing here would emit
        // one message per event and turn a resync into thousands of frames.
    }

    /** Periodic backlog pump: drains every server queue in batches. */
    private void flushAllQueues() {
        for (RegisteredServer target : server.getAllServers()) {
            try {
                tryFlush(target);
            } catch (Exception e) {
                logger.warn("MikuTP queue flush for {} failed: {}",
                        target.getServerInfo().getName(), e.toString());
            }
        }
    }

    private Object lockFor(String serverName) {
        return queueLocks.computeIfAbsent(serverName, n -> new Object());
    }

    private void tryFlush(RegisteredServer target) {
        String name = target.getServerInfo().getName();
        java.util.ArrayDeque<String> queue = outbound.get(name);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        Optional<Player> carrier = target.getPlayersConnected().stream().findFirst();
        if (carrier.isEmpty()) {
            return; // empty server: flushed when someone joins
        }
        // Drain and send under the queue lock: two senders draining the same
        // queue concurrently could emit their batches in the wrong order, which
        // would leave a HOME_SET/HOME_DELETE pair reversed on the receiver.
        synchronized (lockFor(name)) {
            // Re-resolve the carrier under the lock: it may have switched servers
            // since the lookup, and this batch belongs to the queue's own server.
            Optional<ServerConnection> connection = carrier.get().getCurrentServer();
            if (connection.isEmpty() || !connection.get().getServerInfo().getName().equals(name)) {
                return; // carrier moved away: the pump retries with the next one
            }
            List<String> batch = takeBatch(queue);
            if (!batch.isEmpty()) {
                sendEncodedBatch(connection.get(), batch);
            }
        }
    }

    /**
     * Polls up to one batch worth of already-encoded event objects, preserving
     * order. The queue only ever holds JSON produced by Gson, so the pump does
     * not parse and re-serialize each event.
     */
    private List<String> takeBatch(java.util.ArrayDeque<String> queue) {
        List<String> batch = new ArrayList<>();
        while (!queue.isEmpty() && batch.size() < MAX_EVENTS_PER_BATCH) {
            String json = queue.pollFirst();
            if (json != null && !json.isEmpty()) {
                batch.add(json);
            }
        }
        return batch;
    }

    /** Sends an events batch; each element is an event object, matching the backend parser. */
    private boolean sendEvents(ServerConnection connection, List<SyncEvent> events) {
        List<String> json = new ArrayList<>(events.size());
        for (SyncEvent event : events) {
            json.add(GSON.toJson(event));
        }
        return sendEncodedBatch(connection, json);
    }

    /** Sends one frame for already-encoded event objects. */
    private boolean sendEncodedBatch(ServerConnection connection, List<String> eventJson) {
        return send(connection, composeEventsFrame(eventJson));
    }

    /**
     * Builds the {@code t=events} frame with the event objects inlined. The objects
     * come straight from Gson, so joining them yields exactly what
     * ProxyMessages.encodeEventsBatch produces, without parsing each object back
     * into a tree. Keep the envelope in sync with that method if it ever changes.
     */
    private static String composeEventsFrame(List<String> eventJson) {
        StringBuilder frame = new StringBuilder(64 + eventJson.size() * 256);
        frame.append("{\"t\":\"").append(ProxyMessages.TYPE_EVENTS).append("\",\"events\":[");
        for (int i = 0; i < eventJson.size(); i++) {
            if (i > 0) {
                frame.append(',');
            }
            frame.append(eventJson.get(i));
        }
        return frame.append("]}").toString();
    }

    // ------------------------------------------------------------------ player-scoped delivery

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Optional<ServerConnection> connection = event.getPlayer().getCurrentServer();
        if (connection.isEmpty()) {
            return;
        }
        String serverName = event.getServer().getServerInfo().getName();
        // Each step is isolated: a failure in one (a blank token, a dead
        // connection, an oversized frame) must not swallow the handoff and the
        // mailbox that belong to this join. The backlog pump runs last: it can
        // emit many frames and must never gate the player-scoped delivery.
        runIsolated("pending", () -> deliverPending(uuid, connection.get()));
        runIsolated("mailbox", () -> deliverMailbox(uuid, connection.get()));
        runIsolated("recent-requests", () -> redeliverRecentRequests(uuid, connection.get()));
        runIsolated("queue-flush", () -> flushServerQueues(serverName, connection.get()));
    }

    private void runIsolated(String step, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            logger.warn("MikuTP join step {} failed: {}", step, e.toString());
        }
    }

    /** Flushes the events queued for a server that just became non-empty. */
    private void flushServerQueues(String serverName, ServerConnection connection) {
        // Never remove the queue from the map: enqueue may already hold a
        // reference to this list, and detaching it would orphan that event.
        // Queues are therefore permanent and bounded by the server count.
        java.util.ArrayDeque<String> queue = outbound.get(serverName);
        if (queue == null) {
            return;
        }
        // Drain everything, not just one batch: after a long empty period the
        // backlog can exceed MAX_EVENTS_PER_BATCH, and waiting for the next
        // event to trigger another flush would leave the rest stuck. Held under
        // the queue lock so this drain cannot interleave with tryFlush.
        synchronized (lockFor(serverName)) {
            List<String> batch;
            do {
                batch = takeBatch(queue);
                if (!batch.isEmpty()) {
                    sendEncodedBatch(connection, batch);
                }
            } while (!batch.isEmpty() && !queue.isEmpty());
        }
    }

    private void deliverPending(UUID uuid, ServerConnection connection) {
        PendingEntry entry = pendingEntries.get(uuid);
        if (entry == null) {
            return;
        }
        // Every other server keeps the handoff and retries on the next connect;
        // delivering it anywhere else would consume it on the wrong backend. An
        // anchor-only handoff is delivered where the connect request was aimed;
        // when the anchor was offline at handoff time, deliver anyway so the
        // backend can report the failure.
        String destServer = entry.destServer() != null ? entry.destServer() : entry.anchorServer();
        if (destServer != null && !destServer.equals(connection.getServerInfo().getName())) {
            return;
        }
        // Conditional remove: storePending() may have handed the same payload to
        // this player concurrently, and delivering it twice would teleport twice.
        if (!pendingEntries.remove(uuid, entry)) {
            return;
        }
        if (entry.expiry() <= System.currentTimeMillis()) {
            return;
        }
        if (!send(connection, ProxyMessages.encodePending(uuid.toString(), entry.payload(), 0))) {
            pendingEntries.putIfAbsent(uuid, entry);
        }
    }

    private void deliverMailbox(UUID uuid, ServerConnection connection) {
        List<MailboxEntry> mailbox = mailboxes.get(uuid);
        if (mailbox == null) {
            return;
        }
        List<MailboxEntry> drained;
        synchronized (mailbox) {
            // Detach and copy under the same monitor: a concurrent mailboxAdd
            // holds this monitor too, and it re-checks the map before inserting,
            // so an entry can neither be added to a container we are discarding
            // nor be dropped between the copy and the removal.
            drained = new ArrayList<>(mailbox);
            mailbox.clear();
            mailboxes.remove(uuid, mailbox);
        }
        long now = System.currentTimeMillis();
        List<SyncEvent> events = new ArrayList<>();
        List<MailboxEntry> leftover = new ArrayList<>();
        for (MailboxEntry entry : drained) {
            if (entry.expiry() <= now) {
                continue;
            }
            if (events.size() >= MAX_EVENTS_PER_BATCH) {
                // One frame per batch: keep the rest for the next join.
                leftover.add(entry);
                continue;
            }
            try {
                SyncEvent event = GSON.fromJson(entry.json(), SyncEvent.class);
                if (event != null && event.type != null) {
                    events.add(event);
                }
            } catch (Exception ignored) {
                // Skip malformed entries.
            }
        }
        if (!events.isEmpty() && !sendEvents(connection, events)) {
            // The frame was lost: put everything back for the next attempt.
            requeueMailbox(uuid, drained);
            return;
        }
        requeueMailbox(uuid, leftover);
    }

    /** Prepends entries that did not fit into one frame; keeps the mailbox bounded. */
    private void requeueMailbox(UUID uuid, List<MailboxEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        while (true) {
            List<MailboxEntry> mailbox = mailboxes.computeIfAbsent(uuid, k -> new ArrayList<>());
            synchronized (mailbox) {
                if (mailboxes.get(uuid) != mailbox) {
                    continue;
                }
                mailbox.addAll(0, entries);
                while (mailbox.size() > MAX_MAILBOX_ENTRIES) {
                    mailbox.remove(0);
                }
                return;
            }
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        pendingEntries.remove(event.getPlayer().getUniqueId());
        // Mailboxes survive disconnects: that is their purpose.
    }

    private void mailboxAdd(String targetUuid, SyncEvent event) {
        if (targetUuid == null) {
            return;
        }
        UUID target;
        try {
            target = UUID.fromString(targetUuid);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        String json = GSON.toJson(event);
        long expiry = System.currentTimeMillis() + MAILBOX_TTL_MS;
        boolean newKey = false;
        while (true) {
            List<MailboxEntry> mailbox = mailboxes.get(target);
            if (mailbox == null) {
                List<MailboxEntry> fresh = new ArrayList<>();
                mailbox = mailboxes.putIfAbsent(target, fresh);
                if (mailbox == null) {
                    mailbox = fresh;
                    newKey = true;
                }
            }
            synchronized (mailbox) {
                // delivery detaches the mailbox from the map under this monitor
                // before draining it; re-check so the entry never lands in a
                // list that is no longer reachable.
                if (mailboxes.get(target) != mailbox) {
                    newKey = false;
                    continue;
                }
                mailbox.add(new MailboxEntry(json, expiry));
                // Bounded mailbox: the oldest entries go first. Expiry is left to
                // the periodic sweep, so adding never scans the whole list.
                while (mailbox.size() > MAX_MAILBOX_ENTRIES) {
                    mailbox.remove(0);
                }
                break;
            }
        }
        if (newKey) {
            evictOldestMailboxIfNeeded(target);
        }
    }

    /** Keeps the mailbox key space bounded; only runs when a new key was added. */
    private void evictOldestMailboxIfNeeded(UUID keep) {
        if (mailboxes.size() <= MAX_MAILBOX_KEYS) {
            return;
        }
        UUID victim = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<UUID, List<MailboxEntry>> candidate : mailboxes.entrySet()) {
            if (candidate.getKey().equals(keep)) {
                continue;
            }
            long newest = Long.MIN_VALUE;
            synchronized (candidate.getValue()) {
                for (MailboxEntry entry : candidate.getValue()) {
                    newest = Math.max(newest, entry.expiry());
                }
            }
            if (newest < oldest) {
                oldest = newest;
                victim = candidate.getKey();
            }
        }
        if (victim != null) {
            mailboxes.remove(victim);
        }
    }

    private void purgeMailbox(String targetUuid, String requestId) {
        if (targetUuid == null || requestId == null) {
            return;
        }
        UUID target;
        try {
            target = UUID.fromString(targetUuid);
        } catch (IllegalArgumentException e) {
            return;
        }
        List<MailboxEntry> mailbox = mailboxes.get(target);
        if (mailbox == null) {
            return;
        }
        synchronized (mailbox) {
            mailbox.removeIf(entry -> entry.expiry() <= System.currentTimeMillis()
                    || isRequest(entry, requestId));
            if (mailbox.isEmpty()) {
                mailboxes.remove(target, mailbox);
            }
        }
    }

    private boolean isRequest(MailboxEntry entry, String requestId) {
        try {
            SyncEvent event = GSON.fromJson(entry.json(), SyncEvent.class);
            return event != null && event.request != null && event.request.id.equals(requestId);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------- recent TP_NEW copies

    /**
     * Keeps a copy of a TP_NEW so it follows the target to a new server. Without
     * this, a request delivered to the server the target was leaving is lost: the
     * backend drops its incoming map on quit and the proxy has no second copy.
     */
    private void rememberRecentRequest(SyncEvent event) {
        if (event.request == null || event.request.targetUuid == null) {
            return;
        }
        UUID target;
        try {
            target = UUID.fromString(event.request.targetUuid);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        String json = GSON.toJson(event);
        String requestId = event.request.id == null ? "" : event.request.id;
        long expiry = System.currentTimeMillis() + RECENT_REQUEST_TTL_MS;
        while (true) {
            List<RecentRequest> list = recentRequests.computeIfAbsent(target, k -> new ArrayList<>());
            synchronized (list) {
                if (recentRequests.get(target) != list) {
                    continue;
                }
                list.add(new RecentRequest(requestId, json, expiry));
                while (list.size() > MAX_RECENT_REQUESTS) {
                    list.remove(0);
                }
                break;
            }
        }
        trimRecentRequestKeys(target);
    }

    /** Drops the recent copy of a request that was answered or withdrawn. */
    private void purgeRecentRequest(String targetUuid, String requestId) {
        if (targetUuid == null || requestId == null) {
            return;
        }
        UUID target;
        try {
            target = UUID.fromString(targetUuid);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        List<RecentRequest> list = recentRequests.get(target);
        if (list == null) {
            return;
        }
        synchronized (list) {
            list.removeIf(entry -> requestId.equals(entry.requestId()));
            if (list.isEmpty()) {
                recentRequests.remove(target, list);
            }
        }
    }

    /** Bounded key space for the recent copies; only acts when the cap is exceeded. */
    private void trimRecentRequestKeys(UUID keep) {
        if (recentRequests.size() <= MAX_RECENT_REQUEST_KEYS) {
            return;
        }
        UUID victim = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<UUID, List<RecentRequest>> candidate : recentRequests.entrySet()) {
            if (candidate.getKey().equals(keep)) {
                continue;
            }
            long newest = Long.MIN_VALUE;
            synchronized (candidate.getValue()) {
                for (RecentRequest entry : candidate.getValue()) {
                    newest = Math.max(newest, entry.expiry());
                }
            }
            if (newest < oldest) {
                oldest = newest;
                victim = candidate.getKey();
            }
        }
        if (victim != null) {
            recentRequests.remove(victim);
        }
    }

    /**
     * Re-delivers recent TP_NEW events to the server a player just connected to.
     * The copies stay until their TTL so repeated switches keep working; backends
     * ignore requests they already answered (event id / request id), so this is
     * idempotent.
     */
    private void redeliverRecentRequests(UUID uuid, ServerConnection connection) {
        List<RecentRequest> list = recentRequests.get(uuid);
        if (list == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<SyncEvent> events = new ArrayList<>();
        synchronized (list) {
            for (RecentRequest entry : list) {
                if (entry.expiry() <= now) {
                    continue;
                }
                try {
                    SyncEvent event = GSON.fromJson(entry.json(), SyncEvent.class);
                    if (event != null && event.type != null) {
                        events.add(event);
                    }
                } catch (Exception ignored) {
                    // Skip malformed copies.
                }
                if (events.size() >= MAX_EVENTS_PER_BATCH) {
                    break;
                }
            }
        }
        if (!events.isEmpty()) {
            sendEvents(connection, events);
        }
    }

    // ------------------------------------------------------------------ connects

    private void connect(JsonObject message, ServerConnection source) {
        String playerUuid = ProxyMessages.string(message, "player");
        String serverName = ProxyMessages.string(message, "server");
        if (playerUuid == null || serverName == null) {
            return;
        }
        Optional<Player> player = parsePlayer(playerUuid);
        Optional<RegisteredServer> target = server.getServer(serverName);
        if (player.isEmpty()) {
            return;
        }
        if (target.isEmpty()) {
            logger.warn("Connect requested for unknown server {}", serverName);
            send(source, ProxyMessages.encodeConnectResult(false, "unknown_server"));
            return;
        }
        player.get().createConnectionRequest(target.get()).connect().whenComplete((result, error) -> {
            if (error != null) {
                logger.warn("Connect of {} to {} failed: {}", player.get().getUsername(), serverName,
                        error.getMessage());
                send(source, ProxyMessages.encodeConnectResult(false, "error"));
            } else if (!result.isSuccessful()) {
                send(source, ProxyMessages.encodeConnectResult(false, result.getStatus().name()));
            }
        });
    }

    private void connectAnchor(JsonObject message, ServerConnection source) {
        String playerUuid = ProxyMessages.string(message, "player");
        String anchorUuid = ProxyMessages.string(message, "anchor");
        if (playerUuid == null || anchorUuid == null) {
            return;
        }
        Optional<Player> player = parsePlayer(playerUuid);
        Optional<ServerConnection> anchorServer = parsePlayer(anchorUuid).flatMap(Player::getCurrentServer);
        if (player.isEmpty() || anchorServer.isEmpty()) {
            send(source, ProxyMessages.encodeConnectResult(false, "anchor_offline"));
            return;
        }
        player.get().createConnectionRequest(anchorServer.get().getServer()).connect().whenComplete((result, error) -> {
            if (error != null) {
                logger.warn("Anchor connect of {} failed: {}", player.get().getUsername(), error.getMessage());
                send(source, ProxyMessages.encodeConnectResult(false, "error"));
            } else if (!result.isSuccessful()) {
                send(source, ProxyMessages.encodeConnectResult(false, result.getStatus().name()));
            }
        });
    }

    private void listPlayers(JsonObject message, ServerConnection source) {
        String id = ProxyMessages.string(message, "id");
        if (id == null) {
            return;
        }
        String serverName = ProxyMessages.string(message, "server");
        List<String> uuids = new ArrayList<>();
        if (serverName == null) {
            for (Player player : server.getAllPlayers()) {
                uuids.add(player.getUniqueId().toString());
            }
        } else {
            server.getServer(serverName).ifPresent(target ->
                    target.getPlayersConnected().forEach(player -> uuids.add(player.getUniqueId().toString())));
        }
        send(source, ProxyMessages.encodePlayerList(id, uuids));
    }

    private void resolvePlayer(JsonObject message, ServerConnection source) {
        String id = ProxyMessages.string(message, "id");
        String name = ProxyMessages.string(message, "name");
        if (id == null || name == null) {
            return;
        }
        Optional<Player> player = server.getPlayer(name);
        send(source, ProxyMessages.encodeResolveResult(id, player.isPresent(),
                player.map(p -> p.getUniqueId().toString()).orElse(null)));
    }

    // ------------------------------------------------------------------ helpers

    private Optional<Player> parsePlayer(String uuid) {
        try {
            return server.getPlayer(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Signs and sends one message to a backend. Never throws: a failed send must
     * not kill a scheduler task or abort the rest of a batch.
     *
     * @return true when the frame was handed to the connection
     */
    private boolean send(ServerConnection connection, String json) {
        try {
            connection.sendPluginMessage(CHANNEL,
                    ProxyMessages.sign(json, token).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            warnSendFailure(e);
            return false;
        }
    }

    private void warnSendFailure(Exception e) {
        if (warnedSendFailure) {
            logger.debug("Failed to send a MikuTP message to a backend: {}", e.toString());
        } else {
            warnedSendFailure = true;
            logger.warn("Failed to send a MikuTP message to a backend: {}", e.toString());
        }
    }
}