package com.mikumc.mikutp.common;

import com.google.gson.JsonObject;
import com.mikumc.mikutp.common.data.TpRequest;
import com.mikumc.mikutp.common.net.ProxyMessages;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyMessagesTest {

    @Test
    void requestRoundTrip() {
        long now = System.currentTimeMillis();
        TpRequest request = new TpRequest(UUID.randomUUID().toString(), TpRequest.Type.COME,
                "req-uuid", "Alice", "s1", "tgt-uuid", "Bob", TpRequest.Status.PENDING, now, now);

        String routed = ProxyMessages.encodeRoute("tgt-uuid", ProxyMessages.encodeTpRequest(request));
        JsonObject envelope = ProxyMessages.decode(routed.getBytes(StandardCharsets.UTF_8));
        assertEquals(ProxyMessages.TYPE_ROUTE, ProxyMessages.type(envelope));
        assertEquals("tgt-uuid", envelope.get("to").getAsString());

        JsonObject body = ProxyMessages.decode(envelope.get("body").getAsString().getBytes(StandardCharsets.UTF_8));
        assertEquals(ProxyMessages.TYPE_TP_REQUEST, ProxyMessages.type(body));
        TpRequest parsed = ProxyMessages.requestFromJson(body);
        assertEquals(request.id, parsed.id);
        assertEquals(TpRequest.Type.COME, parsed.type);
        assertEquals("Alice", parsed.requesterName);
        assertEquals("Bob", parsed.targetName);
        assertEquals("s1", parsed.requesterServer);
    }

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
        assertTrue(result.get("ok").getAsBoolean() == false);
        assertEquals("anchor_offline", result.get("error").getAsString());
    }

    @Test
    void decodeRejectsGarbage() {
        assertNull(ProxyMessages.decode("not json".getBytes(StandardCharsets.UTF_8)));
        assertNull(ProxyMessages.decode("[1,2,3]".getBytes(StandardCharsets.UTF_8)));
    }
}
