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

    /** Bus events travel inlined; the reader still accepts the escaped legacy form. */
    @Test
    void busEventIsInlinedAndLegacyStringStillReadable() {
        SyncEvent event = SyncEvent.create(SyncEvent.Type.HOME_SET, "s1");
        event.ownerUuid = "uuid-a";
        event.homeName = "base";
        event.homePosition = new Position("s1", "world", 1, 2, 3, 0f, 0f);

        JsonObject inlined = ProxyMessages.eventOp(event);
        assertTrue(inlined.get("e").isJsonObject(), "the event must travel as an object");
        SyncEvent read = ProxyMessages.event(inlined);
        assertEquals(SyncEvent.Type.HOME_SET, read.type);
        assertEquals("base", read.homeName);

        // A backend that predates the inlined form still sends the escaped string.
        JsonObject legacy = new JsonObject();
        legacy.addProperty("k", "event");
        legacy.addProperty("e", ConfigIO.gson().toJson(event));
        assertEquals("base", ProxyMessages.event(legacy).homeName);

        // Garbage in the member is dropped instead of throwing.
        JsonObject broken = new JsonObject();
        broken.addProperty("e", "not json");
        assertNull(ProxyMessages.event(broken));
        assertNull(ProxyMessages.event(new JsonObject()));
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

    /** C-01: a pending handoff may carry the destination server it is meant for. */
    @Test
    void pendingOpCarriesDestinationServer() {
        JsonObject targeted = ProxyMessages.pendingOp("uuid-a", "{}", 300, "survival");
        assertEquals("pending", targeted.get("k").getAsString());
        assertEquals("uuid-a", targeted.get("uuid").getAsString());
        assertEquals("survival", targeted.get("server").getAsString());
        assertEquals(300, targeted.get("ttl").getAsInt());

        // Without a destination the member must be absent, not null.
        JsonObject broadcast = ProxyMessages.pendingOp("uuid-a", "{}", 300, null);
        assertFalse(broadcast.has("server"));
        assertFalse(ProxyMessages.pendingOp("uuid-a", "{}", 300).has("server"));
    }

    /** C-01: signing without a token must not throw and must not fake a signature. */
    @Test
    void signingWithoutTokenLeavesMessageUntouched() {
        String plain = ProxyMessages.encodePending("uuid-a", "{}", 300);
        assertEquals(plain, ProxyMessages.sign(plain, null));
        assertEquals(plain, ProxyMessages.sign(plain, ""));
        assertEquals(plain, ProxyMessages.sign(plain, "   "));
        assertNull(ProxyMessages.sign(null, "token"));

        // The untouched message carries no signature member and cannot verify.
        JsonObject decoded = ProxyMessages.decode(ProxyMessages.sign(plain, ""));
        assertFalse(decoded.has(ProxyMessages.SIGNATURE_MEMBER));
        assertFalse(ProxyMessages.verify(ProxyMessages.decode(decoded.toString()), "token"));
    }

    /** The signature must not depend on member insertion order. */
    @Test
    void signatureIgnoresMemberOrder() {
        String token = "shared-secret";
        String signed = ProxyMessages.sign(ProxyMessages.encodePending("uuid-a", "{}", 300), token);
        JsonObject original = ProxyMessages.decode(signed);

        // Rebuild the same members in a different order, keeping the signature.
        JsonObject reordered = new JsonObject();
        reordered.addProperty(ProxyMessages.SIGNATURE_MEMBER,
                original.get(ProxyMessages.SIGNATURE_MEMBER).getAsString());
        reordered.addProperty("ttl", original.get("ttl").getAsInt());
        reordered.addProperty("payload", original.get("payload").getAsString());
        reordered.addProperty("player", original.get("player").getAsString());
        reordered.addProperty("t", original.get("t").getAsString());

        assertTrue(ProxyMessages.verify(reordered, token));
    }
}
