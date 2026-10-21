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
    /** 48 ops stay below Bukkit's 32767-byte plugin-message cap even for the largest
     * event kind (worst case ~27KB for a full TP_NEW batch). */
    private static final int MAX_OPS_PER_BATCH = 48;
    /** Bukkit caps one plugin message at 32767 bytes; stay clear of the edge. */
    private static final int MAX_FRAME_BYTES = 32_000;
    /** A batch that failed this often is dropped instead of retried forever. */
    private static final int MAX_SEND_ATTEMPTS = 3;
    /** Upper bound for queued ops on a server with no players to carry them: the
     * activation resync re-converges state, so dropping the oldest is safe. Kept
     * near the size of one full-state dump instead of the 262144 ops a
     * batch-count bound would allow (each op is a live JsonObject). */
    private static final int MAX_QUEUED_OPS = 8192;

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final String token;
    private final OnlinePlayers onlinePlayers;
    /** One queued wire op plus how many send attempts it already survived. */
    private record QueuedOp(com.google.gson.JsonObject json, int attempt) {
    }

    private final ConcurrentLinkedDeque<QueuedOp> outbound = new ConcurrentLinkedDeque<>();
    /** Mirrors {@code outbound.size()}; guarded by the queue monitor so it can
     * never drift from the real depth. */
    private int queuedOps;
    /** Carrier kept across flushes while it stays online, so consecutive batches
     * ride one connection and reach the proxy in order. */
    private volatile UUID stickyCarrier;
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
        // The proxy must know where the handoff applies: without the destination it
        // hands the payload to whichever server the player is still connected to
        // (the source), where it can never be applied and is then forgotten.
        String destServer = pending == null || pending.position == null
                ? null : pending.position.server;
        enqueue(ProxyMessages.pendingOp(player.toString(),
                ConfigIO.gson().toJson(pending), ttlSeconds, destServer));
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

    private void enqueue(com.google.gson.JsonObject op) {
        // Enqueue and shedding share one lock with the drain below, so the depth
        // counter always matches the queue. A torn counter would either shed
        // live events early or stop shedding altogether.
        int depth;
        synchronized (outbound) {
            outbound.addLast(new QueuedOp(op, 1));
            depth = ++queuedOps;
            while (depth > MAX_QUEUED_OPS) {
                if (outbound.pollFirst() == null) {
                    break;
                }
                depth = --queuedOps;
            }
        }
    }

    /** Drains queued ops through any online player's connection. */
    private void flush() {
        if (!running || outbound.isEmpty()) {
            return;
        }
        List<QueuedOp> ops = new ArrayList<>();
        synchronized (outbound) {
            while (ops.size() < MAX_OPS_PER_BATCH) {
                QueuedOp op = outbound.pollFirst();
                if (op == null) {
                    break;
                }
                queuedOps--;
                ops.add(op);
            }
        }
        if (ops.isEmpty()) {
            return;
        }
        send(ops);
    }

    /** Encodes and sends one drained batch; an oversized batch is split, not retried. */
    private void send(List<QueuedOp> ops) {
        // The proxy authenticates every batch with the shared token.
        String batchJson = ProxyMessages.sign(ProxyMessages.encodeBusBatch(jsonOf(ops)), token);
        byte[] payload = batchJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (payload.length > MAX_FRAME_BYTES) {
            if (ops.size() > 1) {
                // Bukkit rejects frames over 32767 bytes; hand the tail to the next
                // flush instead of retrying the same oversized poison pill forever.
                int half = ops.size() / 2;
                send(new ArrayList<>(ops.subList(0, half)));
                requeue(ops.subList(half, ops.size()), false);
                return;
            }
            plugin.getSLF4JLogger().warn("Dropping one oversized sync op ({} bytes)", payload.length);
            return;
        }
        sendEncoded(payload, ops);
    }

    /** Sends the batch through the sticky carrier; without one the ops go back to
     * the front of the queue and ride along with the next activation resync. The
     * lookup uses the plugin's own registry: this runs on the async flush thread,
     * where Folia does not allow iterating Bukkit's player view. */
    private void sendEncoded(byte[] payload, List<QueuedOp> ops) {
        int attempt = ops.stream().mapToInt(QueuedOp::attempt).max().orElse(1);
        Player carrier = stickyCarrierPlayer();
        if (carrier == null) {
            requeue(ops, false);
            return;
        }
        // A retired scheduler (entity removed before the task ran) means the carrier
        // is gone: the retired callback puts the batch back instead of letting it
        // vanish. A failing send is classified instead of blindly requeued: an
        // oversized frame can never succeed and would loop forever (V-R3-01).
        try {
            tasks.entity(carrier, () -> {
                if (!carrier.isOnline()) {
                    // The carrier vanished between the lookup and the send; retry on
                    // the next flush (the sticky carrier is re-picked then).
                    requeue(ops, false);
                    return;
                }
                try {
                    carrier.sendPluginMessage(plugin, ProxyMessages.CHANNEL, payload);
                } catch (Exception e) {
                    handleSendFailure(ops, attempt, e);
                }
            }, () -> requeue(ops, false));
        } catch (RuntimeException e) {
            handleSendFailure(ops, attempt, e);
        }
    }

    /** Drops poison pills (size failures / too many attempts) instead of looping. */
    private void handleSendFailure(List<QueuedOp> ops, int attempt, Exception e) {
        if (isSizeFailure(e) || attempt >= MAX_SEND_ATTEMPTS) {
            plugin.getSLF4JLogger().warn("Dropping {} sync op(s) after attempt {}: {}",
                    ops.size(), attempt, e.toString());
            return;
        }
        plugin.getSLF4JLogger().warn("Failed to send a sync batch ({} ops, attempt {}); re-queueing",
                ops.size(), attempt, e);
        requeue(ops, true);
    }

    private static boolean isSizeFailure(Throwable e) {
        if (e instanceof IllegalArgumentException) {
            return true;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("too big") || lower.contains("too large") || lower.contains("maximum")
                || lower.contains("length") || lower.contains("size");
    }

    private static List<com.google.gson.JsonObject> jsonOf(List<QueuedOp> ops) {
        List<com.google.gson.JsonObject> json = new ArrayList<>(ops.size());
        for (QueuedOp op : ops) {
            json.add(op.json());
        }
        return json;
    }

    /**
     * Keeps one carrier while it stays online: consecutive batches then travel on
     * the same connection and reach the proxy in order. Picking a different player
     * on every flush could reorder batches across Netty threads.
     */
    private Player stickyCarrierPlayer() {
        UUID current = stickyCarrier;
        if (current != null) {
            Player player = onlinePlayers.byId(current);
            if (player != null) {
                return player;
            }
        }
        Player next = onlinePlayers.any();
        stickyCarrier = next == null ? null : next.getUniqueId();
        return next;
    }

    /** Puts a drained batch back at the head of the queue, preserving order. */
    private void requeue(List<QueuedOp> ops, boolean retry) {
        synchronized (outbound) {
            for (int i = ops.size() - 1; i >= 0; i--) {
                QueuedOp op = ops.get(i);
                outbound.addFirst(retry ? new QueuedOp(op.json(), op.attempt() + 1) : op);
            }
            queuedOps += ops.size();
            // A retry must not push the queue past the same bound enqueue() uses.
            while (queuedOps > MAX_QUEUED_OPS) {
                if (outbound.pollLast() == null) {
                    break;
                }
                queuedOps--;
            }
        }
    }
}
