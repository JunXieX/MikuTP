package com.mikumc.mikutp.common.util;

/** Compact human-readable durations. */
public final class TimeText {

    private TimeText() {
    }

    /** Formats seconds as e.g. {@code 3s}, {@code 1m05s}, {@code 2h04m}. */
    public static String seconds(long totalSeconds) {
        long seconds = Math.max(0, totalSeconds);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        long rest = seconds % 60;
        if (minutes < 60) {
            return minutes + "m" + (rest > 0 ? String.format("%02ds", rest) : "");
        }
        long hours = minutes / 60;
        long restMinutes = minutes % 60;
        return hours + "h" + String.format("%02dm", restMinutes);
    }
}
