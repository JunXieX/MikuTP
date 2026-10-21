package com.mikumc.mikutp.common.message;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Message lookup with a built-in default bundle. A file in the plugin default
 * folder may override any key individually.
 *
 * <p>Both layers are immutable once published: {@link #loadOverrides} parses into
 * a fresh map and swaps it in atomically, so a concurrent render can never observe
 * a half-populated bundle (a reloaded bundle must not be mutated in place).
 */
public final class MessageBundle {

    private final Map<String, String> bundled;
    private volatile Map<String, String> overrides = Map.of();

    public MessageBundle(String bundledDefaults) {
        this.bundled = parse(new StringReader(bundledDefaults), Map.of());
    }

    /**
     * Merges overrides from {@code file} when it exists.
     *
     * @return false when the file exists but could not be parsed (the bundled
     *         defaults stay active, and the caller can report the failure)
     */
    public boolean loadOverrides(Path file) {
        if (!Files.isRegularFile(file)) {
            return true;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            overrides = parse(reader, bundled);
            return true;
        } catch (Exception e) {
            // Keep the bundled layer; the caller decides whether to surface it.
            return false;
        }
    }

    /** Looks a key up in the override layer first, then in the bundled defaults. */
    public String raw(String key) {
        String value = overrides.get(key);
        if (value != null) {
            return value;
        }
        value = bundled.get(key);
        return value != null ? value : key;
    }

    public boolean has(String key) {
        return overrides.containsKey(key) || bundled.containsKey(key);
    }

    private static Map<String, String> parse(Reader reader, Map<String, String> fallback) {
        Map<String, String> parsed = new HashMap<>();
        com.google.gson.JsonElement root = com.google.gson.JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            return fallback;
        }
        for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonPrimitive()) {
                continue;
            }
            parsed.put(e.getKey(), e.getValue().getAsString());
        }
        return Map.copyOf(parsed);
    }
}
