package com.mikumc.mikutp.common.message;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Message lookup with a built-in default bundle. A file in the plugin data
 * folder may override any key individually.
 */
public final class MessageBundle {

    private final Map<String, String> values = new HashMap<>();

    public MessageBundle(String bundledDefaults) {
        parse(new StringReader(bundledDefaults), false);
    }

    /** Merges overrides from {@code file} when it exists. */
    public void loadOverrides(Path file) {
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                parse(reader, true);
            } catch (Exception e) {
                // Keep defaults; the platform layer reports the parse failure.
            }
        }
    }

    public String raw(String key) {
        String value = values.get(key);
        return value != null ? value : key;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    private void parse(Reader reader, boolean override) {
        com.google.gson.JsonElement root = com.google.gson.JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonPrimitive()) {
                continue;
            }
            if (override || !values.containsKey(e.getKey())) {
                values.put(e.getKey(), e.getValue().getAsString());
            }
        }
    }
}
