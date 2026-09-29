package com.mikumc.mikutp.common.data;

public final class Warp {

    public String name;
    public Position position;
    public long createdAt;

    public Warp() {
    }

    public Warp(String name, Position position, long createdAt) {
        this.name = name;
        this.position = position;
        this.createdAt = createdAt;
    }
}
