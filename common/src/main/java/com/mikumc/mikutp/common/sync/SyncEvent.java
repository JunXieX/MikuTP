package com.mikumc.mikutp.common.sync;

import com.mikumc.mikutp.common.data.Position;
import com.mikumc.mikutp.common.data.TpRequest;

import java.util.UUID;

/**
 * One change that is published to every backend of the network. A single
 * class with nullable fields keeps the wire format trivial; the type decides
 * which fields are meaningful.
 */
public final class SyncEvent {

    public enum Type {
        HOME_SET, HOME_DELETE, PROFILE, BACK, IGNORE_SET, IGNORE_DELETE,
        TP_NEW, TP_RESPONDED, TP_READY, TP_CANCEL, RESYNC_REQUEST
    }

    /** Unique event id; consumers use it for idempotency. */
    public String id;
    /** Server that produced the event. */
    public String origin;
    public Type type;

    // HOME_SET / HOME_DELETE
    public String ownerUuid;
    public String homeName;
    public Position homePosition;
    public long homeCreatedAt;

    // PROFILE / BACK
    public String playerUuid;
    public String playerName;
    public long lastOnline;
    public boolean tpaEnabled;
    /** "back" or "death". */
    public String backKind;
    /** Null clears the stored position. */
    public Position backPosition;

    // IGNORE_SET / IGNORE_DELETE
    public String blockerUuid;
    public String blockedUuid;
    public long expiresAt;

    // TP_NEW / TP_RESPONDED / TP_READY
    public TpRequest request;
    /** "ACCEPT" | "DENY" | "BLOCK". */
    public String response;

    public static SyncEvent create(Type type, String origin) {
        SyncEvent event = new SyncEvent();
        event.id = UUID.randomUUID().toString();
        event.origin = origin;
        event.type = type;
        return event;
    }
}
