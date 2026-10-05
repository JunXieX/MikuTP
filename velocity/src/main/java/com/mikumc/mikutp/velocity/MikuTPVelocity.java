package com.mikumc.mikutp.velocity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.mikumc.mikutp.common.net.ProxyMessages;
import com.mikumc.mikutp.common.sync.SyncEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * backends.
 */
@Plugin(id = "mikutp", name = "MikuTP", version = "1.3.0",
        description = "Cross-server teleport hub for MikuTP backends. MikuMC original plugin by JunXieX, group 1105054380.",
        authors = {"JunXieX"})
public final class MikuTPVelocity {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("mikumc", "mikutp");
    private static final Gson GSON = new Gson();
    private static final int MAX_EVENTS_PER_BATCH = 64;
    private static final long MAILBOX_TTL_MS = 120_000;
    private static final long PENDING_TTL_MS = 300_000;

    private final ProxyServer server;
    private final Logger logger;

    /** Cross-server pending teleport handoffs, delivered on arrival. */
    private final Map<UUID, PendingEntry> pendingEntries = new ConcurrentHashMap<>();
    /** Request mailboxes for offline players, delivered on their next join. */
    private final Map<UUID, List<MailboxEntry>> mailboxes = new ConcurrentHashMap<>();
    /** Events queued for servers that currently have no players. */
    private final Map<String, List<String>> outbound = new ConcurrentHashMap<>();

    private record PendingEntry(String payload, long expiry) {
    }

    private record MailboxEntry(String json, long expiry) {
    }

    @Inject
    public MikuTPVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("MikuTP hub ready on channel {}", CHANNEL.getId());
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
        try {
            handle(message, source);
        } catch (Exception e) {
            logger.warn("Dropped malformed MikuTP message: {}", e.toString());
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
            JsonObject op = element.getAsJsonObject();
            String kind = string(op, "k");
            if (kind == null) {
                continue;
            }
            switch (kind) {
                case "event" -> routeEvent(parseEvent(op));
                case "pending" -> storePending(op);
                default -> logger.debug("Unknown MikuTP bus op {}", kind);
            }
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
        String uuid = string(op, "uuid");
        String payload = string(op, "payload");
        if (uuid == null || payload == null) {
            return;
        }
        try {
            pendingEntries.put(UUID.fromString(uuid),
                    new PendingEntry(payload, System.currentTimeMillis() + PENDING_TTL_MS));
        } catch (IllegalArgumentException ignored) {
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
            }
            case TP_RESPONDED -> {
                if (event.request == null) {
                    return;
                }
                purgeMailbox(event.request.targetUuid, event.request.id);
                deliverToPlayer(event.request.requesterUuid, event);
            }
            case TP_READY -> {
                if (event.request == null) {
                    return;
                }
                deliverToPlayer(event.request.targetUuid, event);
            }
            case TP_CANCEL -> {
                if (event.request == null) {
                    return;
                }
                purgeMailbox(event.request.targetUuid, event.request.id);
                broadcast(event);
            }
            case HOME_SET, HOME_DELETE, PROFILE, BACK, IGNORE_SET, IGNORE_DELETE, RESYNC_REQUEST -> broadcast(event);
        }
    }

    /** Deliver a wire event to the backend currently hosting the player. */
    private boolean deliverToPlayer(String playerUuid, SyncEvent event) {
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
        sendEvents(target.get(), List.of(event));
        return true;
    }

    private void broadcast(SyncEvent event) {
        for (RegisteredServer target : server.getAllServers()) {
            enqueue(target, event);
        }
    }

    // ------------------------------------------------------------------ queues

    private void enqueue(RegisteredServer target, SyncEvent event) {
        String name = target.getServerInfo().getName();
        List<String> queue = outbound.computeIfAbsent(name, n -> new ArrayList<>());
        synchronized (queue) {
            queue.add(GSON.toJson(event));
        }
        tryFlush(target);
    }

    private void tryFlush(RegisteredServer target) {
        String name = target.getServerInfo().getName();
        List<String> queue = outbound.get(name);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        Optional<Player> carrier = target.getPlayersConnected().stream().findFirst();
        if (carrier.isEmpty()) {
            return; // empty server: flushed when someone joins
        }
        Optional<ServerConnection> connection = carrier.get().getCurrentServer();
        if (connection.isEmpty()) {
            return;
        }
        List<SyncEvent> batch = takeBatch(queue);
        if (!batch.isEmpty()) {
            sendEvents(connection.get(), batch);
        }
    }

    private List<SyncEvent> takeBatch(List<String> queue) {
        List<SyncEvent> batch = new ArrayList<>();
        synchronized (queue) {
            while (!queue.isEmpty() && batch.size() < MAX_EVENTS_PER_BATCH) {
                String json = queue.remove(0);
                try {
                    SyncEvent event = GSON.fromJson(json, SyncEvent.class);
                    if (event != null && event.type != null) {
                        batch.add(event);
                    }
                } catch (Exception ignored) {
                    // Skip malformed entries.
                }
            }
        }
        return batch;
    }

    private void sendEvents(ServerConnection connection, List<SyncEvent> events) {
        JsonArray array = new JsonArray();
        for (SyncEvent event : events) {
            array.add(GSON.toJson(event));
        }
        JsonObject o = new JsonObject();
        o.addProperty("t", ProxyMessages.TYPE_EVENTS);
        o.add("events", array);
        connection.sendPluginMessage(CHANNEL, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ player-scoped delivery

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Optional<ServerConnection> connection = event.getPlayer().getCurrentServer();
        if (connection.isEmpty()) {
            return;
        }
        flushServerQueues(event.getServer().getServerInfo().getName(), connection.get());
        deliverPending(uuid, connection.get());
        deliverMailbox(uuid, connection.get());
    }

    /** Flushes the events queued for a server that just became non-empty. */
    private void flushServerQueues(String serverName, ServerConnection connection) {
        List<String> queue = outbound.remove(serverName);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        List<SyncEvent> batch = new ArrayList<>();
        for (String json : queue) {
            try {
                SyncEvent event = GSON.fromJson(json, SyncEvent.class);
                if (event != null && event.type != null) {
                    batch.add(event);
                }
            } catch (Exception ignored) {
            }
        }
        if (!batch.isEmpty()) {
            sendEvents(connection, batch);
        }
    }

    private void deliverPending(UUID uuid, ServerConnection connection) {
        PendingEntry entry = pendingEntries.remove(uuid);
        if (entry == null || entry.expiry() <= System.currentTimeMillis()) {
            return;
        }
        connection.sendPluginMessage(CHANNEL, ProxyMessages
                .encodePending(uuid.toString(), entry.payload(), 0)
                .getBytes(StandardCharsets.UTF_8));
    }

    private void deliverMailbox(UUID uuid, ServerConnection connection) {
        List<MailboxEntry> mailbox = mailboxes.remove(uuid);
        if (mailbox == null || mailbox.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<SyncEvent> events = new ArrayList<>();
        for (MailboxEntry entry : mailbox) {
            if (entry.expiry() <= now) {
                continue;
            }
            try {
                SyncEvent event = GSON.fromJson(entry.json(), SyncEvent.class);
                if (event != null && event.type != null) {
                    events.add(event);
                }
            } catch (Exception ignored) {
            }
        }
        if (!events.isEmpty()) {
            sendEvents(connection, events);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        pendingEntries.remove(event.getPlayer().getUniqueId());
        // Mailboxes survive disconnects: that is their purpose.
    }

    private void mailboxAdd(String targetUuid, SyncEvent event) {
        try {
            UUID target = UUID.fromString(targetUuid);
            List<MailboxEntry> mailbox = mailboxes.computeIfAbsent(target, k -> new ArrayList<>());
            long expiry = System.currentTimeMillis() + MAILBOX_TTL_MS;
            synchronized (mailbox) {
                mailbox.add(new MailboxEntry(GSON.toJson(event), expiry));
                mailbox.removeIf(entry -> entry.expiry() <= System.currentTimeMillis());
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void purgeMailbox(String targetUuid, String requestId) {
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
                mailboxes.remove(target);
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

    // ------------------------------------------------------------------ connects

    private void connect(JsonObject message, ServerConnection source) {
        String playerName = string(message, "player");
        String serverName = string(message, "server");
        if (playerName == null || serverName == null) {
            return;
        }
        Optional<Player> player = parsePlayer(playerName);
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
        String playerName = string(message, "player");
        String anchorName = string(message, "anchor");
        if (playerName == null || anchorName == null) {
            return;
        }
        Optional<Player> player = parsePlayer(playerName);
        Optional<ServerConnection> anchorServer = parsePlayer(anchorName).flatMap(Player::getCurrentServer);
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
        String id = string(message, "id");
        if (id == null) {
            return;
        }
        String serverName = string(message, "server");
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
        String id = string(message, "id");
        String name = string(message, "name");
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

    private void send(ServerConnection connection, String json) {
        connection.sendPluginMessage(CHANNEL, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String string(JsonObject o, String member) {
        if (!o.has(member) || !o.get(member).isJsonPrimitive()) {
            return null;
        }
        return o.get(member).getAsString();
    }
}
