package com.mikumc.mikutp.common;

import com.mikumc.mikutp.common.message.MessageBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageBundleTest {

    private static final String BUNDLED = """
            {"prefix": "<gray>[MikuTP] </gray>",
             "home.set": "<prefix>saved <name>",
             "home.not-found": "<prefix>missing <name>"}
            """;

    @TempDir
    Path dir;

    @Test
    void overrideWinsAndBundledStaysAsFallback() throws Exception {
        Path file = dir.resolve("messages.json");
        Files.writeString(file, "{\"home.set\": \"<prefix>override <name>\"}",
                StandardCharsets.UTF_8);

        MessageBundle bundle = new MessageBundle(BUNDLED);
        assertTrue(bundle.loadOverrides(file));

        assertEquals("<prefix>override <name>", bundle.raw("home.set"));
        // A key the override file does not contain still comes from the bundled layer.
        assertEquals("<prefix>missing <name>", bundle.raw("home.not-found"));
        // Unknown keys must not silently return an empty string.
        assertEquals("no.such.key", bundle.raw("no.such.key"));
        assertTrue(bundle.has("home.set"));
        assertFalse(bundle.has("no.such.key"));
    }

    @Test
    void missingOverrideFileIsNotAnError() throws Exception {
        MessageBundle bundle = new MessageBundle(BUNDLED);
        assertTrue(bundle.loadOverrides(dir.resolve("absent.json")));
        assertEquals("<prefix>saved <name>", bundle.raw("home.set"));
    }

    @Test
    void brokenOverrideKeepsBundledLayer() throws Exception {
        Path file = dir.resolve("messages.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        MessageBundle bundle = new MessageBundle(BUNDLED);
        assertFalse(bundle.loadOverrides(file), "a parse failure must be reported to the caller");
        assertEquals("<prefix>saved <name>", bundle.raw("home.set"));
    }

    /** A bundle whose root is not an object cannot be applied and must be reported. */
    @Test
    void nonObjectOverrideIsReportedAsFailure() throws Exception {
        Path file = dir.resolve("array.json");
        Files.writeString(file, "[1, 2]", StandardCharsets.UTF_8);

        MessageBundle bundle = new MessageBundle(BUNDLED);
        assertFalse(bundle.loadOverrides(file), "a non-object bundle root must be reported");
        assertEquals("<prefix>saved <name>", bundle.raw("home.set"));
    }

    /** Reloading replaces the override layer atomically instead of mutating the map. */
    @Test
    void secondOverrideReplacesTheFirst() throws Exception {
        Path first = dir.resolve("first.json");
        Files.writeString(first, "{\"home.set\": \"first\"}", StandardCharsets.UTF_8);
        Path second = dir.resolve("second.json");
        Files.writeString(second, "{\"home.set\": \"second\"}", StandardCharsets.UTF_8);

        MessageBundle bundle = new MessageBundle(BUNDLED);
        assertTrue(bundle.loadOverrides(first));
        assertEquals("first", bundle.raw("home.set"));
        assertTrue(bundle.loadOverrides(second));
        assertEquals("second", bundle.raw("home.set"));
    }
}
