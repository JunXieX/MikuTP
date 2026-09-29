package com.mikumc.mikutp.common.data;

/** One player blocking teleport requests from another player. */
public final class IgnoreEntry {

    public static final long PERMANENT = -1L;

    public String blockerUuid;
    public String blockedUuid;
    /** Epoch millis when the block lifts; {@link #PERMANENT} never lifts. */
    public long expiresAt;
    public long createdAt;

    public IgnoreEntry() {
    }

    public IgnoreEntry(String blockerUuid, String blockedUuid, long expiresAt, long createdAt) {
        this.blockerUuid = blockerUuid;
        this.blockedUuid = blockedUuid;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }
}
