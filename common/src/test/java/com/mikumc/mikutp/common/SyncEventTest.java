package com.mikumc.mikutp.common;

import com.mikumc.mikutp.common.config.ConfigIO;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.data.Position;
import com.mikumc.mikutp.common.data.TpRequest;
import com.mikumc.mikutp.common.sync.SyncEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncEventTest {

    @Test
    void requestEventRoundTrip() {
        long now = System.currentTimeMillis();
        SyncEvent event = SyncEvent.create(SyncEvent.Type.TP_NEW, "s1");
        event.request = new TpRequest("req-1", TpRequest.Type.COME, "uuid-a", "Alice", "s1",
                "uuid-b", "Bob", TpRequest.Status.PENDING, now, now);

        String json = ConfigIO.gson().toJson(event);
        SyncEvent parsed = ConfigIO.gson().fromJson(json, SyncEvent.class);

        assertEquals(SyncEvent.Type.TP_NEW, parsed.type);
        assertEquals("s1", parsed.origin);
        assertEquals("req-1", parsed.request.id);
        assertEquals(TpRequest.Type.COME, parsed.request.type);
        assertEquals("Bob", parsed.request.targetName);
    }

    @Test
    void homeAndBackEventsRoundTrip() {
        SyncEvent home = SyncEvent.create(SyncEvent.Type.HOME_SET, "s2");
        home.ownerUuid = "uuid-a";
        home.homeName = "base";
        home.homePosition = new Position("s2", "world", 1.5, 64, -2.5, 90f, 0f);
        home.homeCreatedAt = 7L;

        SyncEvent parsedHome = ConfigIO.gson().fromJson(ConfigIO.gson().toJson(home), SyncEvent.class);
        assertEquals("uuid-a", parsedHome.ownerUuid);
        assertEquals("base", parsedHome.homeName);
        assertEquals(1.5, parsedHome.homePosition.x);
        assertEquals(90f, parsedHome.homePosition.yaw);

        SyncEvent back = SyncEvent.create(SyncEvent.Type.BACK, "s2");
        back.playerUuid = "uuid-a";
        back.backKind = "death";
        back.backPosition = null;

        SyncEvent parsedBack = ConfigIO.gson().fromJson(ConfigIO.gson().toJson(back), SyncEvent.class);
        assertEquals("death", parsedBack.backKind);
        assertNull(parsedBack.backPosition);
    }

    /** M-02: the position version stamp must survive the wire round trip. */
    @Test
    void backEventCarriesPositionStamp() {
        SyncEvent back = SyncEvent.create(SyncEvent.Type.BACK, "s1");
        back.playerUuid = "uuid-a";
        back.backKind = "back";
        back.backPosition = new Position("s1", "world", 1, 2, 3, 0f, 0f);
        back.backUpdatedAt = 1_234_567_890L;

        SyncEvent parsed = ConfigIO.gson().fromJson(ConfigIO.gson().toJson(back), SyncEvent.class);
        assertEquals(1_234_567_890L, parsed.backUpdatedAt);
        assertEquals("back", parsed.backKind);
    }

    /** Legacy senders omit the field: it must default to 0, never to "now". */
    @Test
    void missingPositionStampDefaultsToZero() {
        SyncEvent legacy = SyncEvent.create(SyncEvent.Type.BACK, "s1");
        legacy.playerUuid = "uuid-a";
        legacy.backUpdatedAt = 0L;

        SyncEvent parsed = ConfigIO.gson().fromJson(ConfigIO.gson().toJson(legacy), SyncEvent.class);
        assertEquals(0L, parsed.backUpdatedAt);
    }

    /**
     * R2-01/R2-02: a profile event distinguishes "identity only" from an
     * authoritative toggle, and carries a toggle stamp independent of lastOnline.
     */
    @Test
    void profileEventCarriesToggleAuthorityAndStamp() {
        SyncEvent identity = SyncEvent.create(SyncEvent.Type.PROFILE, "s1");
        identity.playerUuid = "uuid-a";
        identity.playerName = "Alice";
        identity.lastOnline = 123L;
        identity.tpaKnown = false;
        identity.tpaUpdatedAt = 0L;

        SyncEvent parsedIdentity = ConfigIO.gson().fromJson(ConfigIO.gson().toJson(identity), SyncEvent.class);
        assertFalse(parsedIdentity.tpaKnown, "identity-only snapshot must not claim toggle authority");
        assertEquals(0L, parsedIdentity.tpaUpdatedAt);
        assertEquals(123L, parsedIdentity.lastOnline);

        SyncEvent toggle = SyncEvent.create(SyncEvent.Type.PROFILE, "s1");
        toggle.playerUuid = "uuid-a";
        toggle.playerName = "Alice";
        toggle.lastOnline = 4_000L;
        toggle.tpaEnabled = false;
        toggle.tpaKnown = true;
        toggle.tpaUpdatedAt = 2_000L;

        SyncEvent parsedToggle = ConfigIO.gson().fromJson(ConfigIO.gson().toJson(toggle), SyncEvent.class);
        assertTrue(parsedToggle.tpaKnown);
        assertFalse(parsedToggle.tpaEnabled);
        assertEquals(2_000L, parsedToggle.tpaUpdatedAt);
        assertEquals(4_000L, parsedToggle.lastOnline);
    }

    /** Absent field means "known": legacy senders only publish an authoritative toggle. */
    @Test
    void missingToggleAuthorityDefaultsToKnown() {
        SyncEvent legacy = SyncEvent.create(SyncEvent.Type.PROFILE, "s1");
        legacy.playerUuid = "uuid-a";
        legacy.playerName = "Alice";

        SyncEvent parsed = ConfigIO.gson().fromJson(ConfigIO.gson().toJson(legacy), SyncEvent.class);
        assertTrue(parsed.tpaKnown);
        assertEquals(0L, parsed.tpaUpdatedAt);
    }

    /** M-03: the profile-authority request event must round trip like the others. */
    @Test
    void profileRequestRoundTrips() {
        SyncEvent request = SyncEvent.create(SyncEvent.Type.PROFILE_REQUEST, "s2");
        request.playerUuid = "uuid-a";

        SyncEvent parsed = ConfigIO.gson().fromJson(ConfigIO.gson().toJson(request), SyncEvent.class);
        assertEquals(SyncEvent.Type.PROFILE_REQUEST, parsed.type);
        assertEquals("uuid-a", parsed.playerUuid);
    }

    @Test
    void pendingTeleportRoundTripWithAnchor() {
        PendingTeleport pending = new PendingTeleport("uuid-a",
                new Position("s2", "world", 1, 2, 3, 0f, 0f), PendingTeleport.Source.TPA, 9L, "uuid-anchor");

        PendingTeleport parsed = ConfigIO.gson().fromJson(ConfigIO.gson().toJson(pending), PendingTeleport.class);
        assertEquals("uuid-anchor", parsed.anchorUuid);
        assertEquals(PendingTeleport.Source.TPA, parsed.source);
        assertEquals("world", parsed.position.world);
    }
}
