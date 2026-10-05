package com.mikumc.mikutp.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mikumc.mikutp.common.config.ConfigIO;
import com.mikumc.mikutp.common.data.Position;
import com.mikumc.mikutp.common.net.ProxyMessages;
import com.mikumc.mikutp.common.sync.SyncEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyMessagesTest {

    @Test
    void connectMessages() {
        JsonObject connect = ProxyMessages.decode(
                ProxyMessages.encodeConnect("uuid-1", "survival").getBytes(StandardCharsets.UTF_8));
        assertEquals(ProxyMessages.TYPE_CONNECT, ProxyMessages.type(connect));
        assertEquals("survival", connect.get("server").getAsString());

        JsonObject anchor = ProxyMessages.decode(
                ProxyMessages.encodeConnectAnchor("uuid-1", "uuid-2").getBytes(StandardCharsets.UTF_8));
        assertEquals(ProxyMessages.TYPE_CONNECT_ANCHOR, ProxyMessages.type(anchor));
        assertEquals("uuid-2", anchor.get("anchor").getAsString());

        JsonObject result = ProxyMessages.decode(
                ProxyMessages.encodeConnectResult(false, "anchor_offline").getBytes(StandardCharsets.UTF_8));
        assertEquals(ProxyMessages.TYPE_CONNECT_RESULT, ProxyMessages.type(result));
        assertTrue(!result.get("ok").getAsBoolean());
        assertEquals("anchor_offline", result.get("error").getAsString());
    }

    @Test
    void playerListMessages() {
        JsonObject request = ProxyMessages.decode(
                ProxyMessages.encodeListPlayers("req-1", "survival").getBytes(StandardCharsets.UTF_8));
        assertEquals(ProxyMessages.TYPE_LIST_PLAYERS, ProxyMessages.type(request));
        assertEquals("survival", request.get("server").getAsString());

        JsonObject answer = ProxyMessages.decode(
                ProxyMessages.encodePlayerList("req-1", List.of("uuid-1", "uuid-2")).getBytes(StandardCharsets.UTF_8));
        assertEquals(ProxyMessages.TYPE_PLAYER_LIST, ProxyMessages.type(answer));
        assertEquals(2, answer.getAsJsonArray("players").size());
    }

    @Test
    void decodeRejectsGarbage() {
        assertNull(ProxyMessages.decode("not json".getBytes(StandardCharsets.UTF_8)));
        assertNull(ProxyMessages.decode("[1,2,3]".getBytes(StandardCharsets.UTF_8)));
    }

    /** The proxy-to-backend batch must hold event objects; stringified events are unreadable. */
    @Test
    void eventsBatchHoldsEventObjects() {
        SyncEvent event = SyncEvent.create(SyncEvent.Type.HOME_SET, "s1");
        event.ownerUuid = "uuid-a";
        event.homeName = "base";
        event.homePosition = new Position("s1", "world", 1, 2, 3, 0f, 0f);

        JsonObject decoded = ProxyMessages.decode(
                ProxyMessages.encodeEventsBatch(List.of(ConfigIO.gson().toJson(event))).getBytes(StandardCharsets.UTF_8));
        assertEquals(ProxyMessages.TYPE_EVENTS, ProxyMessages.type(decoded));

        JsonElement first = decoded.getAsJsonArray("events").get(0);
        assertTrue(first.isJsonObject(), "events must carry objects, not stringified JSON");
        SyncEvent parsed = ConfigIO.gson().fromJson(first, SyncEvent.class);
        assertEquals(SyncEvent.Type.HOME_SET, parsed.type);
        assertEquals("base", parsed.homeName);
        assertEquals(1.0, parsed.homePosition.x);
    }

    @Test
    void signatureRoundTripsAndRejectsTampering() {
        String token = "shared-secret";
        String signed = ProxyMessages.sign(ProxyMessages.encodePending("uuid-a", "{}", 300), token);

        assertTrue(ProxyMessages.verify(ProxyMessages.decode(signed), token));
        assertFalse(ProxyMessages.verify(ProxyMessages.decode(signed), "other-token"));
        assertFalse(ProxyMessages.verify(ProxyMessages.decode(signed), ""));

        // Unsigned messages can be forged by a client, so they must never verify.
        JsonObject unsigned = ProxyMessages.decode(ProxyMessages.encodePending("uuid-a", "{}", 300));
        assertFalse(ProxyMessages.verify(unsigned, token));

        JsonObject tampered = ProxyMessages.decode(signed);
        tampered.addProperty("payload", "{\"evil\":true}");
        assertFalse(ProxyMessages.verify(tampered, token));
    }
}
