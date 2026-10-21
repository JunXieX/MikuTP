package com.mikumc.mikutp.common.data;

public final class PlayerProfile {

    public String uuid;
    public String name;
    public long lastOnline;
    public boolean tpaEnabled;
    /**
     * True once this row carries an authoritative tpa toggle (set by
     * setTpaEnabled). A plain profile upsert never sets it, so a backend that
     * only knows the identity cannot publish the default value as the truth.
     */
    public boolean tpaKnown;
    /**
     * Version stamp of the toggle (0 = never toggled on any backend). Guards
     * toggle replication independently of {@link #lastOnline}, so identity updates
     * cannot reject a newer toggle snapshot and vice versa.
     */
    public long tpaUpdatedAt;

    public PlayerProfile() {
    }

    public PlayerProfile(String uuid, String name, long lastOnline, boolean tpaEnabled) {
        this(uuid, name, lastOnline, tpaEnabled, false, 0L);
    }

    public PlayerProfile(String uuid, String name, long lastOnline, boolean tpaEnabled, boolean tpaKnown) {
        this(uuid, name, lastOnline, tpaEnabled, tpaKnown, 0L);
    }

    public PlayerProfile(String uuid, String name, long lastOnline, boolean tpaEnabled,
                         boolean tpaKnown, long tpaUpdatedAt) {
        this.uuid = uuid;
        this.name = name;
        this.lastOnline = lastOnline;
        this.tpaEnabled = tpaEnabled;
        this.tpaKnown = tpaKnown;
        this.tpaUpdatedAt = tpaUpdatedAt;
    }
}
