package com.mikumc.mikutp.common.sync;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Single-server bus: every published event is applied in-process right away,
 * coordination state lives in memory.
 */
public final class LoopbackSyncBus implements SyncBus {

    private final Map<String, String> pending = new ConcurrentHashMap<>();
    private volatile Consumer<SyncEvent> applier = event -> {
    };

    @Override
    public void start(Consumer<SyncEvent> applier) {
        this.applier = applier;
    }

    @Override
    public void publish(SyncEvent event) {
        applier.accept(event);
    }

    @Override
    public boolean crossServer() {
        return false;
    }

    @Override
    public String presenceGet(UUID player) {
        return null;
    }

    @Override
    public void presencePut(UUID player, String serverId, int ttlSeconds) {
    }

    @Override
    public void presenceForget(UUID player) {
    }

    @Override
    public void pendingPut(String playerUuid, String payloadJson, int ttlSeconds) {
        pending.put(playerUuid, payloadJson);
    }

    @Override
    public String pendingTake(String playerUuid) {
        return pending.remove(playerUuid);
    }

    @Override
    public void close() {
    }
}
