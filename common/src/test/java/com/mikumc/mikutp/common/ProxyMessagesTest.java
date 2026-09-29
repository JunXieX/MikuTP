package com.mikumc.mikutp.common;

import com.google.gson.JsonObject;
import com.mikumc.mikutp.common.net.ProxyMessages;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
