package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.data.TpRequest;
import com.mikumc.mikutp.common.net.ProxyMessages;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Bridge to the Velocity companion plugin. Backend-to-proxy messages ride a
 * player connection; the proxy intercepts them, executes server connects and
 * routes request deliveries to the target player's backend.
 */
public final class NetworkService {

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final MessageService messages;
    private volatile Consumer<TpRequest> requestHandler;
    private volatile Consumer<TpRequest> tpGoHandler;
    private volatile boolean enabled;
    private final java.util.Map<String, java.util.concurrent.CompletableFuture<java.util.List<String>>>
            playerListFutures = new java.util.concurrent.ConcurrentHashMap<>();

    public NetworkService(JavaPlugin plugin, Tasks tasks, MessageService messages) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.messages = messages;
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, ProxyMessages.CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, ProxyMessages.CHANNEL,
                (channel, player, bytes) -> onIncoming(player, bytes));
    }

    /** True when cross-server mode is switched on in the config. */
    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void shutdown() {
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, ProxyMessages.CHANNEL);
        messenger.unregisterOutgoingPluginChannel(plugin, ProxyMessages.CHANNEL);
    }

    /** Receives remote teleport requests routed through the proxy. */
    public void setRequestHandler(Consumer<TpRequest> handler) {
        this.requestHandler = handler;
    }

    /** Receives "go ahead" notifications for accepted come-here requests. */
    public void setTpGoHandler(Consumer<TpRequest> handler) {
        this.tpGoHandler = handler;
    }

    private void onIncoming(Player carrier, byte[] bytes) {
        com.google.gson.JsonObject o = ProxyMessages.decode(bytes);
        if (o == null) {
            return;
        }
        String type = ProxyMessages.type(o);
        if (ProxyMessages.TYPE_TP_REQUEST.equals(type)) {
            TpRequest request = ProxyMessages.requestFromJson(o);
            Consumer<TpRequest> handler = requestHandler;
            if (request != null && handler != null) {
                handler.accept(request);
            }
            return;
        }
        if (ProxyMessages.TYPE_TP_GO.equals(type)) {
            TpRequest request = ProxyMessages.requestFromJson(o);
            Consumer<TpRequest> handler = tpGoHandler;
            if (request != null && handler != null) {
                handler.accept(request);
            }
            return;
        }
        if (ProxyMessages.TYPE_PLAYER_LIST.equals(type)) {
            String id = o.has("id") && o.get("id").isJsonPrimitive() ? o.get("id").getAsString() : null;
            java.util.List<String> players = new java.util.ArrayList<>();
            if (o.has("players") && o.get("players").isJsonArray()) {
                for (var element : o.getAsJsonArray("players")) {
                    players.add(element.getAsString());
                }
            }
            if (id != null) {
                var future = playerListFutures.remove(id);
                if (future != null) {
                    future.complete(players);
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

    public void connectAnchor(Player carrier, String anchorUuid) {
        send(carrier, ProxyMessages.encodeConnectAnchor(carrier.getUniqueId().toString(), anchorUuid));
    }

    /** Asks the proxy to deliver a request to the target player's backend. */
    public void routeTpRequest(TpRequest request, Player carrier) {
        route(request.targetUuid, ProxyMessages.encodeTpRequest(request), carrier);
    }

    /** Asks the proxy to deliver a go-ahead to the requester's backend. */
    public void routeTpGo(TpRequest request, Player carrier) {
        route(request.requesterUuid, ProxyMessages.encodeTpGo(request), carrier);
    }

    private void route(String to, String bodyJson, Player carrier) {
        send(carrier, ProxyMessages.encodeRoute(to, bodyJson));
    }

    /**
     * Asks the proxy for the online players of one server, or of the whole
     * network when {@code serverName} is null. Completes with an empty list on
     * timeout (roughly 5 seconds).
     */
    public java.util.concurrent.CompletableFuture<java.util.List<String>> requestPlayerList(Player carrier, String serverName) {
        String id = java.util.UUID.randomUUID().toString();
        var future = new java.util.concurrent.CompletableFuture<java.util.List<String>>();
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

    private void send(Player carrier, String json) {
        tasks.entity(carrier, () -> carrier.sendPluginMessage(plugin, ProxyMessages.CHANNEL, json.getBytes(StandardCharsets.UTF_8)));
    }
}
