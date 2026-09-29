package com.mikumc.mikutp.common.data;

public final class PlayerProfile {

    public String uuid;
    public String name;
    public long lastOnline;
    public boolean tpaEnabled;

    public PlayerProfile() {
    }

    public PlayerProfile(String uuid, String name, long lastOnline, boolean tpaEnabled) {
        this.uuid = uuid;
        this.name = name;
        this.lastOnline = lastOnline;
        this.tpaEnabled = tpaEnabled;
    }
}
