package com.mikumc.mikutp.common.sync;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The cross-server synchronization bus. Local SQLite stays the source of
 * truth; the bus only carries change events and short-lived coordination
 * state (pending teleports, presence, request mailboxes).
 */
public interface SyncBus extends AutoCloseable {

    /** One row of the local outbox, waiting to be published. */
    record OutboxRow(long seq, String payload) {
    }

    /** Reads and deletes outbox rows; implemented on top of the local database. */
    interface OutboxStore {
        long append(String payload);

        List<OutboxRow> take(int max);

        void remove(List<Long> seqs);
    }

    /** Starts background workers; {@code applier} runs for every incoming event. */
    void start(Consumer<SyncEvent> applier);

    /** Queues an event for delivery to every backend, including this one. */
    void publish(SyncEvent event);

    /** True when the bus talks to Redis (cross-server mode). */
    boolean crossServer();

    /** Player-to-server presence for cross-server lookups; null when unknown. */
    String presenceGet(UUID player);

    void presencePut(UUID player, String serverId, int ttlSeconds);

    /** Refreshes presence for many players at once; implementations should batch. */
    default void presencePutAll(Map<UUID, String> players, int ttlSeconds) {
        players.forEach((player, serverId) -> presencePut(player, serverId, ttlSeconds));
    }

    void presenceForget(UUID player);

    /** Short-lived handoff payload for an arriving player (pending teleport). */
    void pendingPut(String playerUuid, String payloadJson, int ttlSeconds);

    /** Atomically reads and removes the payload; null when absent. */
    String pendingTake(String playerUuid);

    /** Queues a request for a player who may be offline right now; expires with ttl. */
    default void mailboxAdd(String playerUuid, String payloadJson, int ttlSeconds) {
    }

    /** Removes one previously queued payload (e.g. after the request was answered). */
    default void mailboxRemove(String playerUuid, String payloadJson) {
    }

    /** Reads and clears everything queued for the player. */
    default List<String> mailboxTake(String playerUuid) {
        return List.of();
    }

    @Override
    void close();
}
