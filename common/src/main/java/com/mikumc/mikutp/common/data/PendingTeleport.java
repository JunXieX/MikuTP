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
    /**
     * When set, the arrival server teleports the player to this player's live
     * position instead of the stored coordinates (admin goto / pulls).
     */
    public String anchorUuid;

    public PendingTeleport() {
    }

    public PendingTeleport(String playerUuid, Position position, Source source, long createdAt) {
        this.playerUuid = playerUuid;
        this.position = position;
        this.source = source;
        this.createdAt = createdAt;
    }

    public PendingTeleport(String playerUuid, Position position, Source source, long createdAt, String anchorUuid) {
        this(playerUuid, position, source, createdAt);
        this.anchorUuid = anchorUuid;
    }
}
