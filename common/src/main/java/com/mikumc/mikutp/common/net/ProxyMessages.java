package com.mikumc.mikutp.common.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Wire format of the plugin message channel shared by the backends and the
 * proxy. Payloads are single JSON objects in UTF-8.
 *
 * <p>Backend to proxy: {@code bus} (a batch of sync events and coordination
 * ops, routed by the proxy), {@code connect} (move a player to a named
 * server), {@code connect_anchor} (move a player to the server of another
 * player), {@code list_players} (query online players of one server or the
 * whole network), {@code resolve_player} (name to uuid/online lookup).
 *
 * <p>Proxy to backend: {@code events} (sync events routed or fanned out to
 * every server), {@code pending} (a cross-server handoff delivered as the
 * player arrives on the destination server), {@code resolve_result}, and
 * {@code connect_result} reported back to the originating backend when a
 * connect request fails.
 */
public final class ProxyMessages {

    public static final String CHANNEL = "mikumc:mikutp";

    public static final String TYPE_CONNECT = "connect";
    public static final String TYPE_CONNECT_ANCHOR = "connect_anchor";
    public static final String TYPE_CONNECT_RESULT = "connect_result";
    public static final String TYPE_LIST_PLAYERS = "list_players";
    public static final String TYPE_PLAYER_LIST = "player_list";
    public static final String TYPE_RESOLVE_PLAYER = "resolve_player";
    public static final String TYPE_RESOLVE_RESULT = "resolve_result";
    public static final String TYPE_BUS = "bus";
    public static final String TYPE_EVENTS = "events";
    public static final String TYPE_PENDING = "pending";

    private ProxyMessages() {
    }

    public static String encodeConnect(String playerUuid, String serverName) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_CONNECT);
        o.addProperty("player", playerUuid);
        o.addProperty("server", serverName);
        return o.toString();
    }

    public static String encodeConnectAnchor(String playerToMove, String anchorUuid) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_CONNECT_ANCHOR);
        o.addProperty("player", playerToMove);
        o.addProperty("anchor", anchorUuid);
        return o.toString();
    }

    public static String encodeConnectResult(boolean ok, String error) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_CONNECT_RESULT);
        o.addProperty("ok", ok);
        if (error != null) {
            o.addProperty("error", error);
        }
        return o.toString();
    }

    /** Asks the proxy for the online players of one server, or the whole network when null. */
    public static String encodeListPlayers(String id, String serverName) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_LIST_PLAYERS);
        o.addProperty("id", id);
        if (serverName != null) {
            o.addProperty("server", serverName);
        }
        return o.toString();
    }

    public static String encodePlayerList(String id, java.util.List<String> playerUuids) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_PLAYER_LIST);
        o.addProperty("id", id);
        JsonArray array = new JsonArray();
        for (String uuid : playerUuids) {
            array.add(uuid);
        }
        o.add("players", array);
        return o.toString();
    }

    /** Name-based online lookup answered by the proxy (it sees every backend). */
    public static String encodeResolvePlayer(String id, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_RESOLVE_PLAYER);
        o.addProperty("id", id);
        o.addProperty("name", name);
        return o.toString();
    }

    public static String encodeResolveResult(String id, boolean found, String playerUuid) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_RESOLVE_RESULT);
        o.addProperty("id", id);
        o.addProperty("found", found);
        if (playerUuid != null) {
            o.addProperty("uuid", playerUuid);
        }
        return o.toString();
    }

    /** One bus batch: a list of sync events routed/fanned out by the proxy. */
    public static String encodeEventsBatch(java.util.List<SyncEventWire> events) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_EVENTS);
        JsonArray array = new JsonArray();
        for (SyncEventWire wire : events) {
            array.add(wire.toJson());
        }
        o.add("events", array);
        return o.toString();
    }

    /** One backend-to-proxy bus batch; ops are pre-built wire objects. */
    public static String encodeBusBatch(java.util.List<JsonObject> ops) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_BUS);
        JsonArray array = new JsonArray();
        for (JsonObject op : ops) {
            array.add(op);
        }
        o.add("m", array);
        return o.toString();
    }

    /** Bus op carrying one sync event. */
    public static JsonObject eventOp(String eventJson) {
        JsonObject op = new JsonObject();
        op.addProperty("k", "event");
        op.addProperty("e", eventJson);
        return op;
    }

    /** Bus op handing a pending teleport payload to the proxy. */
    public static JsonObject pendingOp(String playerUuid, String payloadJson, int ttlSeconds) {
        JsonObject op = new JsonObject();
        op.addProperty("k", "pending");
        op.addProperty("uuid", playerUuid);
        op.addProperty("payload", payloadJson);
        op.addProperty("ttl", ttlSeconds);
        return op;
    }

    /** Hands a pending teleport payload to the proxy for the arriving player. */
    public static String encodePending(String playerUuid, String payloadJson, int ttlSeconds) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_PENDING);
        o.addProperty("player", playerUuid);
        o.addProperty("payload", payloadJson);
        o.addProperty("ttl", ttlSeconds);
        return o.toString();
    }

    /** Returns null when the payload is not a valid message object. */
    public static JsonObject decode(byte[] data) {
        try {
            com.google.gson.JsonElement el = JsonParser.parseString(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            if (el.isJsonObject()) {
                return el.getAsJsonObject();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String type(JsonObject o) {
        return o.has("t") && o.get("t").isJsonPrimitive() ? o.get("t").getAsString() : null;
    }

    /** Minimal wire shape of a sync event, shared by backend and proxy. */
    public record SyncEventWire(String json) {
        public JsonObject toJson() {
            com.google.gson.JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        }
    }
}
