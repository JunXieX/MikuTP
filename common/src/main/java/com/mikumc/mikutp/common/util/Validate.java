package com.mikumc.mikutp.common.util;

import java.util.regex.Pattern;

/** Input validation helpers. */
public final class Validate {

    private Validate() {
    }

    public static boolean validName(String pattern, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        try {
            return Pattern.matches(pattern, name);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean validUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
