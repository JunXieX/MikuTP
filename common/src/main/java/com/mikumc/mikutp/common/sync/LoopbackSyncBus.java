package com.mikumc.mikutp.common.sync;

import com.mikumc.mikutp.common.data.PendingTeleport;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Single-server bus: every published event is applied in-process right away.
 * Cross-server-only coordination state (pending teleports) never occurs in
 * this mode, so the store is a no-op.
 */
public final class LoopbackSyncBus implements SyncBus {

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
    public void pendingStore(UUID player, PendingTeleport pending, int ttlSeconds) {
    }

    @Override
    public void close() {
    }
}
