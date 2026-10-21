package com.mikumc.mikutp.velocity;

import com.google.gson.Gson;
import com.mikumc.mikutp.common.data.Position;
import com.mikumc.mikutp.common.net.ProxyMessages;
import com.mikumc.mikutp.common.sync.SyncEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the event-frame contract between this module and
 * {@link ProxyMessages#encodeEventsBatch(List)}: for Gson's default escaping
 * output both must produce the exact same bytes, otherwise a backend would have
 * to trust two different encoders.
 *
 * <p>This byte-level equality is a producer-side guarantee, not a wire
 * requirement: {@code ProxyMessages.sign} re-serializes the message (decode ->
 * canonical form -> toString) after this frame is composed, so the receiver
 * always sees a frame produced by the signer. The assertions here keep the two
 * encoders aligned for the case where the frame is used without re-signing.
 */
class EventsFrameTest {

    private static final Gson GSON = new Gson();

    @Test
    void matchesCommonEncoderForGsonProducedEvents() {
        List<String> events = new ArrayList<>();
        events.add(GSON.toJson(profileEvent("first")));
        events.add(GSON.toJson(homeEvent()));
        assertEquals(ProxyMessages.encodeEventsBatch(events), EventsFrame.compose(events));
    }

    @Test
    void keepsEscapingExactlyOnce() {
        SyncEvent event = profileEvent("names");
        // Quotes, backslashes, a newline and non-ASCII text: all of them are escaped
        // by Gson once, and the frame must inline the escaped text verbatim.
        event.playerName = "a\"b\\c\n中";
        String json = GSON.toJson(event);
        String frame = EventsFrame.compose(List.of(json));
        assertEquals(ProxyMessages.encodeEventsBatch(List.of(json)), frame);
        assertTrue(frame.contains(json), "event JSON must be inlined verbatim");
    }

    @Test
    void emptyBatchIsStillValidJson() {
        assertEquals("{\"t\":\"events\",\"events\":[]}", EventsFrame.compose(List.of()));
        assertEquals(ProxyMessages.encodeEventsBatch(List.of()), EventsFrame.compose(List.of()));
    }

    @Test
    void skipsEntriesThatAreNotCompleteJsonObjects() {
        List<String> bad = new ArrayList<>();
        bad.add("");
        bad.add("not json");
        bad.add("{\"t\":\"truncated\"");
        assertEquals("{\"t\":\"events\",\"events\":[]}", EventsFrame.compose(bad));
    }

    @Test
    void preservesOrder() {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(GSON.toJson(profileEvent("e" + i)));
        }
        String frame = EventsFrame.compose(events);
        int previous = -1;
        for (int i = 0; i < 5; i++) {
            int index = frame.indexOf("\"e" + i + "\"");
            assertTrue(index > previous, "event e" + i + " out of order");
            previous = index;
        }
    }

    private static SyncEvent profileEvent(String name) {
        SyncEvent event = SyncEvent.create(SyncEvent.Type.PROFILE, "s1");
        event.playerUuid = "uuid-" + name;
        event.playerName = name;
        event.lastOnline = 42L;
        return event;
    }

    private static SyncEvent homeEvent() {
        SyncEvent event = SyncEvent.create(SyncEvent.Type.HOME_SET, "s1");
        event.ownerUuid = "owner";
        event.homeName = "base";
        event.homePosition = new Position("s1", "world", 1.5, 64, -2.5, 90f, 0f);
        event.homeCreatedAt = 7L;
        return event;
    }
}
