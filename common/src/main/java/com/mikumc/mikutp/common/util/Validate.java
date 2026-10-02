package com.mikumc.mikutp.common.util;

import java.util.regex.Pattern;

/** Input validation helpers. */
public final class Validate {

    private static final java.util.Map<String, java.util.regex.Pattern> PATTERNS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Validate() {
    }

    public static boolean validName(String pattern, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        try {
            return PATTERNS.computeIfAbsent(pattern, java.util.regex.Pattern::compile)
                    .matcher(name).matches();
        } catch (Exception e) {
            return false;
        }
    }
}
