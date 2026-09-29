package com.mikumc.mikutp.common.data;

/** A teleport request between two players. */
public final class TpRequest {

    public enum Type {
        /** Target is asked to accept the requester travelling to them. */
        GO(0),
        /** Target is asked to accept travelling to the requester. */
        COME(1);

        private final int id;

        Type(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Type byId(int id) {
            for (Type t : values()) {
                if (t.id == id) {
                    return t;
                }
            }
            return GO;
        }
    }

    public enum Status {
        PENDING(0),
        ACCEPTED(1),
        DENIED(2),
        BLOCKED(3),
        EXPIRED(4),
        COMPLETED(5);

        private final int id;

        Status(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Status byId(int id) {
            for (Status s : values()) {
                if (s.id == id) {
                    return s;
                }
            }
            return PENDING;
        }
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
