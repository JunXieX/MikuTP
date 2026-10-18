package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.ConfigIO;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.net.ProxyMessages;
import com.mikumc.mikutp.common.sync.SyncBus;
import com.mikumc.mikutp.common.sync.SyncEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Cross-server bus backed by the Velocity companion plugin: events and
 * coordination ops are sent through player connections, the proxy fans them
 * out to every server (queueing for empty ones until a player joins) and
 * routes them to the backend hosting the affected player.
 *
 * <p>Delivery is best-effort — a proxy restart loses in-flight events, which
 * the activation-time full-state resync (see SyncCoordinator) heals. The local
 * SQLite database stays the source of truth and never loses data.
 */
public final class VelocitySyncBus implements SyncBus {

    private static final int FLUSH_INTERVAL_MS = 150;
    private static final int MAX_OPS_PER_BATCH = 64;
    /** Upper bound for queued batches on a server with no other players; the
     * activation resync re-converges state, so dropping the oldest is safe. */
    private static final int MAX_QUEUED_BATCHES = 4096;

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final String token;
    private final OnlinePlayers onlinePlayers;
    private final ConcurrentLinkedDeque<com.google.gson.JsonObject> outbound = new ConcurrentLinkedDeque<>();
    /** Mirrors {@code outbound.size()}: ConcurrentLinkedDeque#size() is O(n) and
     * this runs on every publish, so the depth check must not traverse the queue. */
    private final java.util.concurrent.atomic.AtomicInteger queuedOps = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean running;
    private ScheduledTask flushTask;

    public VelocitySyncBus(JavaPlugin plugin, Tasks tasks, String token, OnlinePlayers onlinePlayers) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.token = token;
        this.onlinePlayers = onlinePlayers;
    }

    /**
     * Starts the flush loop. Incoming events never travel through this class:
     * the proxy answers on the plugin channel and {@link NetworkService} hands
     * them straight to the coordinator, so the applier argument is unused.
     */
    @Override
    public void start(Consumer<SyncEvent> applier) {
        running = true;
        flushTask = tasks.asyncRepeat(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void publish(SyncEvent event) {
        enqueue(ProxyMessages.eventOp(ConfigIO.gson().toJson(event)));
    }

    @Override
    public boolean crossServer() {
        return true;
    }

    @Override
    public void pendingStore(UUID player, PendingTeleport pending, int ttlSeconds) {
        enqueue(ProxyMessages.pendingOp(player.toString(),
                ConfigIO.gson().toJson(pending), ttlSeconds));
    }

    /** Stops the flush loop; queued events are dropped (a restart triggers a
     * full-state resync on first join, so nothing diverges permanently). */
    @Override
    public void close() {
        running = false;
        if (flushTask != null) {
            flushTask.cancel();
        }
    }

    // ------------------------------------------------------------------ internals

    /** Bounded by MAX_QUEUED_BATCHES*MAX_OPS_PER_BATCH ops; the oldest are shed
     * when the queue is full (the activation resync re-converges state). */
    private static final int MAX_QUEUED_OPS = MAX_QUEUED_BATCHES * MAX_OPS_PER_BATCH;

    private void enqueue(com.google.gson.JsonObject op) {
        outbound.addLast(op);
        int depth = queuedOps.incrementAndGet();
        // Over budget: shed the oldest ops. The activation resync re-converges
        // state, so a dropped event is not a permanent divergence.
        while (depth > MAX_QUEUED_OPS && outbound.pollFirst() != null) {
            depth = queuedOps.decrementAndGet();
        }
    }

    /** Drains queued ops through any online player's connection. */
    private void flush() {
        if (!running || outbound.isEmpty()) {
            return;
        }
        List<com.google.gson.JsonObject> ops = new ArrayList<>();
        while (ops.size() < MAX_OPS_PER_BATCH) {
            com.google.gson.JsonObject op = outbound.pollFirst();
            if (op == null) {
                break;
            }
            queuedOps.decrementAndGet();
            ops.add(op);
        }
        if (ops.isEmpty()) {
            return;
        }
        // The proxy authenticates every batch with the shared token.
        send(ProxyMessages.sign(ProxyMessages.encodeBusBatch(ops), token), ops);
    }

    /** Picks any online player as the carrier; without one the ops go back to
     * the front of the queue and ride along with the next activation resync.
     * The lookup uses the plugin's own registry: this runs on the async flush
     * thread, where Folia does not allow iterating Bukkit's player view. */
    private void send(String batchJson, List<com.google.gson.JsonObject> ops) {
        Player carrier = onlinePlayers.any();
        if (carrier == null) {
            requeue(ops);
            return;
        }
        tasks.entity(carrier, () -> {
            if (!carrier.isOnline()) {
                // The carrier vanished between the lookup and the send; without
                // this the batch would be lost instead of retried.
                requeue(ops);
                return;
            }
            carrier.sendPluginMessage(plugin, ProxyMessages.CHANNEL,
                    batchJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
    }

    /** Puts a drained batch back at the head of the queue, preserving order. */
    private void requeue(List<com.google.gson.JsonObject> ops) {
        for (int i = ops.size() - 1; i >= 0; i--) {
            outbound.addFirst(ops.get(i));
        }
        queuedOps.addAndGet(ops.size());
    }
}
