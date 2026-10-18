package com.mikumc.mikutp.common.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.google.gson.Gson;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads the commented YAML config into plain models. Missing keys keep the
 * model defaults, so config files from older versions keep working.
 */
public final class ConfigIO {

    private static final Gson GSON = new Gson();

    private ConfigIO() {
    }

    /** Shared Gson instance for internal wire formats (sync events, pending teleports). */
    public static Gson gson() {
        return GSON;
    }

    /** Reads {@code file}; writes the bundled, commented default first when absent. */
    public static <T> T loadOrCreate(Path file, String bundledDefault, Class<T> type) throws IOException {
        if (notExists(file)) {
            Files.writeString(file, bundledDefault, StandardCharsets.UTF_8);
        }
        T model = newInstance(type);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            return model;
        }
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
        if (root instanceof Map<?, ?> map) {
            merge(model, map);
        }
        return model;
    }

    /** Copies matching YAML values onto the model's fields (snake_case or camelCase keys). */
    private static void merge(Object model, Map<?, ?> source) {
        for (Field field : model.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object raw = lookup(source, field.getName());
            if (raw == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                assign(model, field, raw);
            } catch (ReflectiveOperationException | IllegalArgumentException e) {
                // Unknown or malformed key: keep the field default.
            }
        }
    }

    private static void assign(Object model, Field field, Object raw) throws ReflectiveOperationException {
        Class<?> type = field.getType();
        if (type == String.class) {
            field.set(model, raw.toString());
        } else if (type == int.class || type == Integer.class) {
            field.set(model, (int) number(raw));
        } else if (type == long.class || type == Long.class) {
            field.set(model, (long) number(raw));
        } else if (type == double.class || type == Double.class) {
            field.set(model, number(raw));
        } else if (type == float.class || type == Float.class) {
            field.set(model, (float) number(raw));
        } else if (type == boolean.class || type == Boolean.class) {
            field.set(model, raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString().trim()));
        } else if (List.class.isAssignableFrom(type)) {
            List<String> values = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object element : list) {
                    values.add(element.toString());
                }
            } else {
                values.add(raw.toString());
            }
            field.set(model, values);
        } else if (raw instanceof Map<?, ?> nested && !type.isPrimitive() && hasNoArgConstructor(type)) {
            merge(field.get(model), nested);
        }
    }

    /**
     * Reads a numeric value, accepting the quoted form too ({@code "3"}).
     * YAML happily stores a quoted scalar as a String; a strict cast would throw
     * and the caller would silently keep the default, so the user's setting
     * would appear to be ignored.
     */
    private static double number(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(raw.toString().trim());
    }

    private static boolean hasNoArgConstructor(Class<?> type) {
        try {
            type.getDeclaredConstructor();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static Object lookup(Map<?, ?> source, String fieldName) {
        String snake = toSnake(fieldName);
        if (source.containsKey(snake)) {
            return source.get(snake);
        }
        return source.get(fieldName);
    }

    static String toSnake(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean notExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return Files.notExists(file);
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Config model needs a no-arg constructor: " + type, e);
        }
    }
}
