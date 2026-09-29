package com.mikumc.mikutp.velocity;

import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.mikumc.mikutp.common.net.ProxyMessages;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Proxy-side companion of the MikuTP backends. Intercepts the shared plugin
 * message channel, executes server connects and routes deliveries between
 * backends. State lives in the shared database; this plugin stays stateless.
 */
@Plugin(id = "mikutp", name = "MikuTP", version = "1.0.0",
        description = "Cross-server teleport bridge for MikuTP backends.",
        authors = {"JunXieX"})
public final class MikuTPVelocity {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("mikumc", "mikutp");

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public MikuTPVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("MikuTP bridge ready on channel {}", CHANNEL.getId());
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
        String type = ProxyMessages.type(message);
        if (type == null) {
            return;
        }
        switch (type) {
            case ProxyMessages.TYPE_CONNECT -> connect(message, source);
            case ProxyMessages.TYPE_CONNECT_ANCHOR -> connectAnchor(message, source);
            case ProxyMessages.TYPE_LIST_PLAYERS -> listPlayers(message, source);
            default -> logger.debug("Unknown MikuTP message type {}", type);
        }
    }

    /** Answers a backend's request for the online players of one server or the whole network. */
    private void listPlayers(JsonObject message, ServerConnection source) {
        String id = string(message, "id");
        if (id == null) {
            return;
        }
        String serverName = string(message, "server");
        java.util.List<String> uuids = new java.util.ArrayList<>();
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

    private void connect(JsonObject message, ServerConnection source) {
        String playerName = string(message, "player");
        String serverName = string(message, "server");
        if (playerName == null || serverName == null) {
            return;
        }
        Optional<Player> player = server.getPlayer(java.util.UUID.fromString(playerName));
        Optional<com.velocitypowered.api.proxy.server.RegisteredServer> target = server.getServer(serverName);
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
        Optional<Player> player = server.getPlayer(java.util.UUID.fromString(playerName));
        Optional<Player> anchor = server.getPlayer(java.util.UUID.fromString(anchorName));
        Optional<ServerConnection> anchorServer = anchor.flatMap(Player::getCurrentServer);
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
