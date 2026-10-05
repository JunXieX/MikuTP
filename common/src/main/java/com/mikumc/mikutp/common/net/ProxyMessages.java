package com.mikumc.mikutp.common.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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
 * player arrives on the destination server), {@code player_list},
 * {@code resolve_result}, and {@code connect_result}.
 *
 * <p>Every message carries an HMAC-SHA256 signature over its canonical JSON
 * form ({@code sig} member). The channel is registered as an incoming plugin
 * channel, which also receives payloads a modified client may forge; the
 * signature is what separates genuine proxy traffic from forged traffic, so
 * both ends must share the same token.
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

    /** Member holding the HMAC-SHA256 signature of the message without it. */
    public static final String SIGNATURE_MEMBER = "sig";

    private static final Gson GSON = new Gson();

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

    public static String encodePlayerList(String id, List<String> playerUuids) {
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

    /**
     * One events batch: the {@code events} array holds event objects (not
     * stringified JSON), so a receiver can deserialize each element straight
     * into a sync event.
     */
    public static String encodeEventsBatch(List<String> eventJson) {
        JsonObject o = new JsonObject();
        o.addProperty("t", TYPE_EVENTS);
        JsonArray array = new JsonArray();
        for (String json : eventJson) {
            JsonElement element = JsonParser.parseString(json);
            array.add(element.isJsonObject() ? element : new JsonObject());
        }
        o.add("events", array);
        return o.toString();
    }

    /** One backend-to-proxy bus batch; ops are pre-built wire objects. */
    public static String encodeBusBatch(List<JsonObject> ops) {
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
            return decode(new String(data, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Returns null when the text is not a valid message object. */
    public static JsonObject decode(String text) {
        try {
            JsonElement el = JsonParser.parseString(text);
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

    public static String string(JsonObject o, String member) {
        return o.has(member) && o.get(member).isJsonPrimitive() ? o.get(member).getAsString() : null;
    }

    public static List<String> stringList(JsonObject o, String member) {
        List<String> out = new ArrayList<>();
        if (o.has(member) && o.get(member).isJsonArray()) {
            for (JsonElement element : o.getAsJsonArray(member)) {
                if (element.isJsonPrimitive()) {
                    out.add(element.getAsString());
                }
            }
        }
        return out;
    }

    /**
     * Signs {@code json} in place: the signature covers the canonical JSON form
     * of the message without the {@code sig} member. A blank token produces an
     * unauthenticated message, which every receiver rejects.
     */
    public static String sign(String json, String token) {
        JsonObject o = decode(json);
        if (o == null) {
            return json;
        }
        o.remove(SIGNATURE_MEMBER);
        o.addProperty(SIGNATURE_MEMBER, signature(o, token));
        return o.toString();
    }

    /**
     * Verifies the signature of a decoded message. Must be called before the
     * message is used: it consumes the {@code sig} member.
     */
    public static boolean verify(JsonObject message, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String received = string(message, SIGNATURE_MEMBER);
        if (received == null || received.isBlank()) {
            return false;
        }
        message.remove(SIGNATURE_MEMBER);
        String expected = signature(message, token);
        return constantTimeEquals(received, expected);
    }

    private static String signature(JsonObject message, String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(GSON.toJson(message).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}