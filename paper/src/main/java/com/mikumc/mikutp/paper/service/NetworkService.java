package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.net.ProxyMessages;
import com.mikumc.mikutp.common.sync.SyncEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Bridge to the Velocity companion plugin. Backend-to-proxy messages ride a
 * player connection; the proxy fans sync events out to every server, hands
 * pending teleports to the arriving player's backend, executes server
 * connects and answers player lookups.
 *
 * <p>The channel is registered as an incoming plugin channel, so it also
 * receives payloads a modified client can forge. Every message is therefore
 * authenticated with the shared {@code sync.token}: unsigned or wrongly signed
 * payloads are dropped, and in single-server mode nothing is accepted at all.
 */
public final class NetworkService {

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final MessageService messages;
    private final boolean crossServer;
    private final String token;
    private final Map<String, CompletableFuture<List<String>>>
            playerListFutures = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<UUID>> resolveFutures = new ConcurrentHashMap<>();
    private volatile Consumer<SyncEvent> eventHandler = event -> {
    };
    private volatile BiConsumer<Player, String> pendingHandler = (player, payload) -> {
    };
    private volatile boolean warnedRejected = false;

    public NetworkService(JavaPlugin plugin, Tasks tasks, MessageService messages, MikuTPConfig config) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.messages = messages;
        this.crossServer = config.sync.enabled();
        this.token = config.sync.token;
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, ProxyMessages.CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, ProxyMessages.CHANNEL,
                (channel, player, bytes) -> onIncoming(player, bytes));
    }

    public void shutdown() {
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, ProxyMessages.CHANNEL);
        messenger.unregisterOutgoingPluginChannel(plugin, ProxyMessages.CHANNEL);
    }

    /** Receives sync events routed or fanned out by the proxy. */
    public void setEventHandler(Consumer<SyncEvent> handler) {
        this.eventHandler = handler;
    }

    /** Receives pending-teleport payloads pushed for a joining player. */
    public void setPendingHandler(BiConsumer<Player, String> handler) {
        this.pendingHandler = handler;
    }

    private void onIncoming(Player carrier, byte[] bytes) {
        // Without a proxy in the picture no legitimate sender exists, and a
        // modified client can reach this channel: refuse everything.
        if (!crossServer) {
            return;
        }
        com.google.gson.JsonObject o = ProxyMessages.decode(bytes);
        if (o == null) {
            return;
        }
        if (!ProxyMessages.verify(o, token)) {
            warnRejected();
            return;
        }
        String type = ProxyMessages.type(o);
        if (ProxyMessages.TYPE_EVENTS.equals(type)) {
            if (o.has("events") && o.get("events").isJsonArray()) {
                for (var element : o.getAsJsonArray("events")) {
                    try {
                        SyncEvent event = com.mikumc.mikutp.common.config.ConfigIO.gson()
                                .fromJson(element, SyncEvent.class);
                        if (event != null && event.type != null) {
                            eventHandler.accept(event);
                        }
                    } catch (Exception ignored) {
                        // One malformed event must not stop the batch.
                    }
                }
            }
            return;
        }
        if (ProxyMessages.TYPE_PENDING.equals(type)) {
            String payload = ProxyMessages.string(o, "payload");
            if (payload != null) {
                pendingHandler.accept(carrier, payload);
            }
            return;
        }
        if (ProxyMessages.TYPE_PLAYER_LIST.equals(type)) {
            String id = ProxyMessages.string(o, "id");
            if (id != null) {
                var future = playerListFutures.remove(id);
                if (future != null) {
                    future.complete(ProxyMessages.stringList(o, "players"));
                }
            }
            return;
        }
        if (ProxyMessages.TYPE_RESOLVE_RESULT.equals(type)) {
            String id = ProxyMessages.string(o, "id");
            if (id != null) {
                var future = resolveFutures.remove(id);
                if (future != null) {
                    boolean found = o.has("found") && o.get("found").isJsonPrimitive() && o.get("found").getAsBoolean();
                    String uuid = ProxyMessages.string(o, "uuid");
                    UUID resolved = null;
                    if (found && uuid != null) {
                        try {
                            resolved = UUID.fromString(uuid);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    future.complete(resolved);
                }
            }
            return;
        }
        if (ProxyMessages.TYPE_CONNECT_RESULT.equals(type)) {
            boolean ok = o.has("ok") && o.get("ok").isJsonPrimitive() && o.get("ok").getAsBoolean();
            if (!ok) {
                tasks.entity(carrier, () -> messages.send(carrier, "common.cross-connect-failed"));
            }
        }
    }

    private void warnRejected() {
        if (!warnedRejected) {
            warnedRejected = true;
            plugin.getSLF4JLogger().warn("Dropped an unauthenticated plugin message on {}; "
                    + "check that sync.token matches the proxy token", ProxyMessages.CHANNEL);
        }
    }

    public void connect(Player carrier, String serverName) {
        send(carrier, ProxyMessages.encodeConnect(carrier.getUniqueId().toString(), serverName));
    }

    /**
     * Asks the proxy to move {@code playerToMove} to the server of {@code anchor}.
     * {@code carrier} is only the connection the request rides on and is usually
     * (but not necessarily) the player being moved.
     */
    public void connectAnchor(Player carrier, String playerToMove, String anchorUuid) {
        send(carrier, ProxyMessages.encodeConnectAnchor(playerToMove, anchorUuid));
    }

    /** Asks the proxy for the online players of one server, or the whole network when null. */
    public CompletableFuture<List<String>> requestPlayerList(Player carrier, String serverName) {
        String id = UUID.randomUUID().toString();
        var future = new CompletableFuture<List<String>>();
        playerListFutures.put(id, future);
        tasks.asyncDelayed(() -> {
            var pending = playerListFutures.remove(id);
            if (pending != null && !pending.isDone()) {
                pending.complete(List.of());
            }
        }, 5000);
        send(carrier, ProxyMessages.encodeListPlayers(id, serverName));
        return future;
    }

    /** Asks the proxy whether a player is online anywhere on the network. */
    public CompletableFuture<UUID> resolveRemote(Player carrier, String name) {
        String id = UUID.randomUUID().toString();
        var future = new CompletableFuture<UUID>();
        resolveFutures.put(id, future);
        tasks.asyncDelayed(() -> {
            var pending = resolveFutures.remove(id);
            if (pending != null && !pending.isDone()) {
                pending.complete(null);
            }
        }, 5000);
        send(carrier, ProxyMessages.encodeResolvePlayer(id, name));
        return future;
    }

    private void send(Player carrier, String json) {
        String signed = ProxyMessages.sign(json, token);
        tasks.entity(carrier, () -> {
            if (carrier.isOnline()) {
                carrier.sendPluginMessage(plugin, ProxyMessages.CHANNEL, signed.getBytes(StandardCharsets.UTF_8));
            }
        });
    }
}