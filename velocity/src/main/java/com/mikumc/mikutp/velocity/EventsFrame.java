package com.mikumc.mikutp.velocity;

import com.mikumc.mikutp.common.net.ProxyMessages;

import java.util.List;

/**
 * Builds the {@code t=events} plugin-message frame for the proxy-to-backend
 * direction. The {@code events} array holds event objects, exactly like
 * {@link ProxyMessages#encodeEventsBatch(List)}; the difference is that the
 * objects are inlined as already-encoded JSON instead of being parsed back into
 * a tree first, which removes one serialize/parse round trip per event.
 *
 * <p>Raw-byte equivalence with {@link ProxyMessages#encodeEventsBatch(List)} holds
 * for Gson default escaping output: both encoders inline the same escaped text,
 * so the frames match byte for byte (pinned by {@code EventsFrameTest}).
 * At the wire level the two are interchangeable regardless, because
 * {@code ProxyMessages.sign} re-serializes the whole message (decode -> canonical
 * form -> toString) before it is sent, so a receiver never depends on the raw
 * byte layout this class produces.
 *
 * <p>Only JSON produced by {@code Gson.toJson(SyncEvent)} may be passed in. The
 * strings are joined verbatim, so quotes and backslashes inside them are already
 * escaped exactly once; escaping them again would corrupt the frame.
 */
final class EventsFrame {

    private EventsFrame() {
    }

    /**
     * Builds the frame, preserving the given order. Entries that are not complete
     * JSON objects are skipped: the queue is only fed from Gson, so a malformed
     * entry signals a bug rather than network data, and skipping it keeps the
     * rest of the frame valid.
     */
    static String compose(List<String> eventJson) {
        StringBuilder frame = new StringBuilder(64 + Math.max(0, eventJson.size()) * 256);
        frame.append("{\"t\":\"").append(ProxyMessages.TYPE_EVENTS).append("\",\"events\":[");
        boolean first = true;
        for (String json : eventJson) {
            if (!isJsonObjectText(json)) {
                continue;
            }
            if (!first) {
                frame.append(',');
            }
            frame.append(json);
            first = false;
        }
        return frame.append("]}").toString();
    }

    /** Cheap structural check: a Gson-serialized event always starts and ends with a brace. */
    private static boolean isJsonObjectText(String json) {
        if (json == null || json.length() < 2) {
            return false;
        }
        return json.charAt(0) == '{' && json.charAt(json.length() - 1) == '}';
    }
}
