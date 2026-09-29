package com.mikumc.mikutp.common.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mikumc.mikutp.common.data.TpRequest;

/**
 * Wire format of the plugin message channel shared by the backend and the
 * proxy. Payloads are single JSON objects in UTF-8.
 *
 * <p>Backend to proxy: {@code connect} (move a player to a named server),
 * {@code connect_anchor} (move a player to the server of another player),
 * {@code route} (deliver a body to the backend of a specific player).
 *
 * <p>Proxy to backend: the routed bodies, plus {@code connect_result} reported
 * back to the originating backend when a connect request fails.
 */
public final class ProxyMessages {

    public static final String CHANNEL = "mikumc:mikutp";

    public static final String TYPE_CONNECT = "connect";
    public static final String TYPE_CONNECT_ANCHOR = "connect_anchor";
    public static final String TYPE_ROUTE = "route";
    public static final String TYPE_TP_REQUEST = "tp_request";
    public static final String TYPE_TP_GO = "tp_go";
    public static final String TYPE_CONNECT_RESULT = "connect_result";
    public static final String TYPE_LIST_PLAYERS = "list_players";
    public static final String TYPE_PLAYER_LIST = "player_list";

    private ProxyMessages() {
    }

    public static String encodeConnect(String playerUuid, String serverName) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_CONNECT);
        o.addProperty("player", playerUuid);
        o.addProperty("server", serverName);
        return o.toString();
    }

    public static String encodeConnectAnchor(String playerUuid, String anchorUuid) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_CONNECT_ANCHOR);
        o.addProperty("player", playerUuid);
        o.addProperty("anchor", anchorUuid);
        return o.toString();
    }

    /** Wraps {@code bodyJson} so the proxy forwards it to the backend of {@code to}. */
    public static String encodeRoute(String to, String bodyJson) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_ROUTE);
        o.addProperty("to", to);
        o.addProperty("body", bodyJson);
        return o.toString();
    }

    public static String encodeTpRequest(TpRequest request) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_TP_REQUEST);
        o.add("request", toJson(request));
        return o.toString();
    }

    /**
     * Tells the requester's backend that a "come here" request was accepted, so
     * it can stash the requester's position before the mover departs.
     */
    public static String encodeTpGo(TpRequest request) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_TP_GO);
        o.add("request", toJson(request));
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
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (String uuid : playerUuids) {
            array.add(uuid);
        }
        o.add("players", array);
        return o.toString();
    }

    public static TpRequest requestFromJson(JsonObject o) {
        JsonObject r = o.getAsJsonObject("request");
        if (r == null) {
            return null;
        }
        TpRequest req = new TpRequest();
        req.id = r.get("id").getAsString();
        req.type = TpRequest.Type.byId(r.get("type").getAsInt());
        req.requesterUuid = r.get("requester_uuid").getAsString();
        req.requesterName = r.get("requester_name").getAsString();
        req.requesterServer = r.get("requester_server").getAsString();
        req.targetUuid = r.get("target_uuid").getAsString();
        com.google.gson.JsonElement targetName = r.get("target_name");
        req.targetName = targetName == null ? "" : targetName.getAsString();
        req.status = TpRequest.Status.byId(r.get("status").getAsInt());
        req.createdAt = r.get("created_at").getAsLong();
        req.updatedAt = r.get("updated_at").getAsLong();
        return req;
    }

    private static JsonObject toJson(TpRequest req) {
        JsonObject r = new JsonObject();
        r.addProperty("id", req.id);
        r.addProperty("type", req.type.id());
        r.addProperty("requester_uuid", req.requesterUuid);
        r.addProperty("requester_name", req.requesterName);
        r.addProperty("requester_server", req.requesterServer);
        r.addProperty("target_uuid", req.targetUuid);
        r.addProperty("target_name", req.targetName == null ? "" : req.targetName);
        r.addProperty("status", req.status.id());
        r.addProperty("created_at", req.createdAt);
        r.addProperty("updated_at", req.updatedAt);
        return r;
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
}
