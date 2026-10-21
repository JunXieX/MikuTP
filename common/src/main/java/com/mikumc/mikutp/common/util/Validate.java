package com.mikumc.mikutp.common.util;

import java.util.regex.Pattern;

/** Input validation helpers. */
public final class Validate {

    private static final java.util.Map<String, java.util.regex.Pattern> PATTERNS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Validate() {
    }

    /**
     * True when {@code pattern} is a usable regular expression. Startup/reload code
     * calls this so a broken pattern is reported once instead of silently making
     * every name look invalid.
     */
    public static boolean isValidPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        try {
            java.util.regex.Pattern.compile(pattern);
            return true;
        } catch (java.util.regex.PatternSyntaxException e) {
            return false;
        }
    }

    public static boolean validName(String pattern, String name) {
        if (name == null || name.isBlank() || pattern == null || pattern.isBlank()) {
            return false;
        }
        try {
            return PATTERNS.computeIfAbsent(pattern, java.util.regex.Pattern::compile)
                    .matcher(name).matches();
        } catch (Exception e) {
            // An invalid regex in config.yml must reject the input, not throw
            // out of the command handler.
            return false;
        }
    }
}
