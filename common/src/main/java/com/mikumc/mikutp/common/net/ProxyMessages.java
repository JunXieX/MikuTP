package com.mikumc.mikutp.common.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Wire format of the plugin message channel shared by the backend and the
 * proxy. Payloads are single JSON objects in UTF-8.
 *
 * <p>Backend to proxy: {@code connect} (move a player to a named server),
 * {@code connect_anchor} (move a player to the server of another player),
 * {@code list_players} (query online players of one server or the network).
 *
 * <p>Proxy to backend: {@code connect_result} reported back to the originating
 * backend when a connect request fails, and {@code player_list} answers.
 */
public final class ProxyMessages {

    public static final String CHANNEL = "mikumc:mikutp";

    public static final String TYPE_CONNECT = "connect";
    public static final String TYPE_CONNECT_ANCHOR = "connect_anchor";
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
