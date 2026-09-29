package com.mikumc.mikutp.common.data;

public final class Home {

    public String ownerUuid;
    public String name;
    public Position position;
    public long createdAt;

    public Home() {
    }

    public Home(String ownerUuid, String name, Position position, long createdAt) {
        this.ownerUuid = ownerUuid;
        this.name = name;
        this.position = position;
        this.createdAt = createdAt;
    }
}
