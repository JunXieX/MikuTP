package com.mikumc.mikutp.common.data;

/** A teleport request between two players. */
public final class TpRequest {

    public enum Type {
        /** Target is asked to accept the requester travelling to them. */
        GO,
        /** Target is asked to accept travelling to the requester. */
        COME
    }

    public enum Status {
        PENDING, ACCEPTED, DENIED, BLOCKED, EXPIRED, COMPLETED
    }

    public String id;
    public Type type;
    public String requesterUuid;
    public String requesterName;
    public String requesterServer;
    public String targetUuid;
    public String targetName;
    public Status status;
    public long createdAt;
    public long updatedAt;

    public TpRequest() {
    }

    public TpRequest(String id, Type type, String requesterUuid, String requesterName,
                     String requesterServer, String targetUuid, String targetName, Status status,
                     long createdAt, long updatedAt) {
        this.id = id;
        this.type = type;
        this.requesterUuid = requesterUuid;
        this.requesterName = requesterName;
        this.requesterServer = requesterServer;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
