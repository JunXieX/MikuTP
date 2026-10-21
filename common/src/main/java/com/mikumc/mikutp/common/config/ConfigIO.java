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
 * Loads the commented YAML config into plain models.
 *
 * <p>Two entry points exist and their contracts differ deliberately:
 * {@link #loadOrCreate} is the first-start loader (creates the bundled default
 * when the file is absent and keeps model defaults for keys that cannot be
 * applied), while {@link #loadStrict} is the hot-reload loader: it never writes
 * to disk and fails with an {@link IOException} instead of silently replacing a
 * malformed value with a default, so the caller can keep the running config.
 */
public final class ConfigIO {

    private static final Gson GSON = new Gson();

    private ConfigIO() {
    }

    /** Shared Gson instance for internal wire formats (sync events, pending teleports). */
    public static Gson gson() {
        return GSON;
    }

    /**
     * First-start loader: writes the bundled, commented default when the file is
     * absent, then applies every key that can be applied (a malformed value keeps
     * the model default). A YAML syntax error is surfaced as an IOException.
     */
    public static <T> T loadOrCreate(Path file, String bundledDefault, Class<T> type) throws IOException {
        if (notExists(file)) {
            Files.writeString(file, bundledDefault, StandardCharsets.UTF_8);
        }
        T model = newInstance(type);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            return model;
        }
        merge(model, readRoot(file, content), false, "");
        return model;
    }

    /**
     * Strict loader used for hot reloads: the file must exist and every key that
     * is present must have the type the model expects. Nothing is written to disk
     * and no default is substituted, so a broken file cannot half-apply itself.
     *
     * @throws IOException when the file is missing, is not valid YAML, its root is
     *                     not a mapping, or a value has the wrong type
     */
    public static <T> T loadStrict(Path file, Class<T> type) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("config file does not exist: " + file);
        }
        T model = newInstance(type);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            return model;
        }
        merge(model, readRoot(file, content), true, "");
        return model;
    }

    /** Parses the YAML root; every failure becomes an IOException with context. */
    private static Map<?, ?> readRoot(Path file, String content) throws IOException {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
        } catch (RuntimeException e) {
            throw new IOException("invalid YAML in " + file + ": " + e.getMessage(), e);
        }
        if (root == null) {
            return Map.of();
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IOException("config root must be a mapping in " + file
                    + " (got " + root.getClass().getSimpleName() + ")");
        }
        return map;
    }

    /**
     * Copies matching YAML values onto the model's fields (snake_case or camelCase
     * keys). Unknown keys are ignored in both modes; in strict mode a present key
     * with an unusable value throws instead of silently keeping the default.
     */
    private static void merge(Object model, Map<?, ?> source, boolean strict, String path) throws IOException {
        for (Field field : model.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object raw = lookup(source, field.getName());
            if (raw == null) {
                continue;
            }
            String fieldPath = path.isEmpty()
                    ? toSnake(field.getName())
                    : path + "." + toSnake(field.getName());
            try {
                field.setAccessible(true);
                assign(model, field, raw, strict, fieldPath);
            } catch (ReflectiveOperationException | IllegalArgumentException e) {
                if (strict) {
                    throw new IOException("invalid value for " + fieldPath + ": " + describe(raw), e);
                }
                // Lenient mode: keep the field default (documented first-start behaviour).
            }
        }
    }

    private static void assign(Object model, Field field, Object raw, boolean strict, String path)
            throws ReflectiveOperationException, IOException {
        Class<?> type = field.getType();
        if (type == String.class) {
            if (strict && (raw instanceof Map<?, ?> || raw instanceof List<?>)) {
                throw new IOException("expected a string for " + path + " but got " + describe(raw));
            }
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
            if (raw instanceof Boolean b) {
                field.set(model, b);
            } else {
                String text = raw.toString().trim();
                if (strict && !text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                    throw new IOException("expected true/false for " + path + " but got " + describe(raw));
                }
                field.set(model, Boolean.parseBoolean(text));
            }
        } else if (List.class.isAssignableFrom(type)) {
            if (!(raw instanceof List<?> list)) {
                if (strict) {
                    throw new IOException("expected a list for " + path + " but got " + describe(raw));
                }
                List<String> single = new ArrayList<>(1);
                single.add(raw.toString());
                field.set(model, single);
                return;
            }
            List<String> values = new ArrayList<>(list.size());
            for (Object element : list) {
                if (strict && element instanceof Map<?, ?>) {
                    throw new IOException("expected a scalar list element in " + path
                            + " but got a mapping");
                }
                values.add(element == null ? "" : element.toString());
            }
            field.set(model, values);
        } else if (raw instanceof Map<?, ?> nested && !type.isPrimitive() && hasNoArgConstructor(type)) {
            Object nestedModel = field.get(model);
            if (nestedModel == null) {
                // A model section that was never initialised cannot receive values;
                // silently skipping is the old (surprising) behaviour, so report it.
                throw new IOException("missing nested section for " + path);
            }
            merge(nestedModel, nested, strict, path);
        } else if (strict) {
            throw new IOException("unsupported value for " + path + ": " + describe(raw));
        }
    }

    private static String describe(Object raw) {
        if (raw == null) {
            return "null";
        }
        String kind = raw instanceof Map<?, ?> ? "mapping"
                : raw instanceof List<?> ? "list"
                : raw.getClass().getSimpleName();
        return kind + " (" + raw + ")";
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
