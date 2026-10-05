package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.net.ProxyMessages;
import com.mikumc.mikutp.common.sync.SyncEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 */
public final class NetworkService {

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final MessageService messages;
    private final Map<String, CompletableFuture<java.util.List<String>>>
            playerListFutures = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<UUID>> resolveFutures = new ConcurrentHashMap<>();
    private volatile Consumer<SyncEvent> eventHandler = event -> {
    };
    private volatile BiConsumer<Player, String> pendingHandler = (player, payload) -> {
    };

    public NetworkService(JavaPlugin plugin, Tasks tasks, MessageService messages) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.messages = messages;
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
        com.google.gson.JsonObject o = ProxyMessages.decode(bytes);
        if (o == null) {
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
            if (o.has("payload") && o.get("payload").isJsonPrimitive()) {
                String payload = o.get("payload").getAsString();
                pendingHandler.accept(carrier, payload);
            }
            return;
        }
        if (ProxyMessages.TYPE_RESOLVE_RESULT.equals(type)) {
            String id = o.has("id") && o.get("id").isJsonPrimitive() ? o.get("id").getAsString() : null;
            if (id != null) {
                var future = resolveFutures.remove(id);
                if (future != null) {
                    boolean found = o.has("found") && o.get("found").getAsBoolean();
                    if (found && o.has("uuid") && o.get("uuid").isJsonPrimitive()) {
                        try {
                            future.complete(UUID.fromString(o.get("uuid").getAsString()));
                        } catch (IllegalArgumentException e) {
                            future.complete(null);
                        }
                    } else {
                        future.complete(null);
                    }
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
    public CompletableFuture<java.util.List<String>> requestPlayerList(Player carrier, String serverName) {
        String id = UUID.randomUUID().toString();
        var future = new CompletableFuture<java.util.List<String>>();
        playerListFutures.put(id, future);
        tasks.asyncDelayed(() -> {
            var pending = playerListFutures.remove(id);
            if (pending != null && !pending.isDone()) {
                pending.complete(java.util.List.of());
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
        tasks.entity(carrier, () -> carrier.sendPluginMessage(plugin,
                ProxyMessages.CHANNEL, json.getBytes(StandardCharsets.UTF_8)));
    }
}
