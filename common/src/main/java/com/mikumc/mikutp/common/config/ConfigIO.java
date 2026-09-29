package com.mikumc.mikutp.common.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads JSON documents into plain models, merging over defaults so that
 * newly introduced keys survive config files written by older versions.
 */
public final class ConfigIO {

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private ConfigIO() {
    }

    public static Gson gson() {
        return GSON;
    }

    /** Reads {@code file}; writes the bundled default first when the file is absent. */
    public static <T> T loadOrCreate(Path file, String bundledDefault, Class<T> type) throws IOException {
        if (notExists(file)) {
            writeString(file, bundledDefault);
        }
        T model = newInstance(type);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String json = readAll(reader);
            if (json != null && !json.isBlank()) {
                model = GSON.fromJson(json, type);
            }
        }
        if (model == null) {
            model = newInstance(type);
        }
        return model;
    }

    public static void save(Path file, Object model) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(model, writer);
        }
    }

    /** Parses a JSON object member into a map-friendly string map, tolerating null. */
    public static java.util.Map<String, String> readStringMap(Path file, String member) throws IOException {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (notExists(file)) {
            return out;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            com.google.gson.JsonElement element = com.google.gson.JsonParser.parseReader(reader);
            if (element.isJsonObject()) {
                com.google.gson.JsonObject obj = element.getAsJsonObject().getAsJsonObject(member);
                if (obj != null) {
                    for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
                        if (e.getValue().isJsonPrimitive()) {
                            out.put(e.getKey(), e.getValue().getAsString());
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean notExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return Files.notExists(file);
    }

    private static void writeString(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = reader.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Config model needs a no-arg constructor: " + type, e);
        }
    }
}
