package com.mikumc.mikutp.common.sync;

import com.mikumc.mikutp.common.data.PendingTeleport;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The cross-server synchronization bus. Local SQLite stays the source of
 * truth; the bus only carries change events and short-lived coordination
 * state (pending teleports).
 *
 * <p>{@link #publish} delivers the event to every backend, including the one
 * that published it — consumers apply events idempotently.
 */
public interface SyncBus extends AutoCloseable {

    /** Starts background workers; {@code applier} runs for every incoming event. */
    void start(Consumer<SyncEvent> applier);

    /** Queues an event for delivery to every backend, including this one. */
    void publish(SyncEvent event);

    /** True when the bus talks to the proxy (cross-server mode). */
    boolean crossServer();

    /**
     * Hands a pending teleport to the proxy. The proxy keeps it in memory and
     * delivers it to the destination backend as soon as {@code player} connects
     * there, where it is applied on join.
     */
    void pendingStore(UUID player, PendingTeleport pending, int ttlSeconds);

    @Override
    void close();
}
