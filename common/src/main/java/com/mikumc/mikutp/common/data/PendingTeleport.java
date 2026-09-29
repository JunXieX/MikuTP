package com.mikumc.mikutp.common.data;

/**
 * A teleport waiting to be applied once its player arrives on the destination
 * server. This is the handoff used by every cross-server transfer.
 */
public final class PendingTeleport {

    public enum Source {
        HOME, WARP, TPA, TPA_HERE, BACK, DEATH, WILD, ADMIN
    }

    public String playerUuid;
    public Position position;
    public Source source;
    public long createdAt;

    public PendingTeleport() {
    }

    public PendingTeleport(String playerUuid, Position position, Source source, long createdAt) {
        this.playerUuid = playerUuid;
        this.position = position;
        this.source = source;
        this.createdAt = createdAt;
    }
}
