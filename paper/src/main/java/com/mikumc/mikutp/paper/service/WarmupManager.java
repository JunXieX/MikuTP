package com.mikumc.mikutp.paper.service;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Warmup countdowns before a teleport fires. The countdown runs on the
 * player's entity scheduler; movement or damage cancels through
 * {@link #cancel(Player, String)}.
 */
public final class WarmupManager {

    private final Tasks tasks;
    private final MessageService messages;
    private final Effects effects;
    private final int warmupSeconds;
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();

    private static final class Active {
        volatile ScheduledTask task;
        final Location origin;

        Active(Location origin) {
            this.origin = origin;
        }
    }

    public WarmupManager(Tasks tasks, MessageService messages, Effects effects, int warmupSeconds) {
        this.tasks = tasks;
        this.messages = messages;
        this.effects = effects;
        this.warmupSeconds = Math.max(0, warmupSeconds);
    }

    /** Location the player stood at when the warmup began (for /back). */
    public Location origin(UUID player) {
        Active a = active.get(player);
        return a == null ? null : a.origin.clone();
    }

    public boolean isWarming(UUID player) {
        return active.containsKey(player);
    }

    /** Starts the countdown; {@code onComplete} runs on the player's entity thread. */
    public void start(Player player, Runnable onComplete) {
        if (warmupSeconds <= 0) {
            onComplete.run();
            return;
        }
        if (active.containsKey(player.getUniqueId())) {
            return;
        }
        Active a = new Active(player.getLocation().clone());
        active.put(player.getUniqueId(), a);
        long finishAt = System.currentTimeMillis() + warmupSeconds * 1000L;
        a.task = tasks.entityRepeat(player, task -> {
            long remaining = finishAt - System.currentTimeMillis();
            if (remaining <= 0) {
                task.cancel();
                if (active.remove(player.getUniqueId()) != null) {
                    onComplete.run();
                }
                return;
            }
            long seconds = (remaining + 999) / 1000;
            messages.actionBar(player, "common.warmup.countdown", "seconds", String.valueOf(seconds));
            effects.warmupTick(player);
        }, 1L, 20L);
    }

    /** Cancels a pending teleport and tells the player why. */
    public void cancel(Player player, String reasonKey) {
        Active a = active.remove(player.getUniqueId());
        if (a == null) {
            return;
        }
        if (a.task != null) {
            a.task.cancel();
        }
        messages.send(player, reasonKey);
        effects.cancelled(player);
    }

    /** Silent cancel used on quit and plugin shutdown. */
    public void drop(UUID player) {
        Active a = active.remove(player);
        if (a != null && a.task != null) {
            a.task.cancel();
        }
    }

    public boolean isEmpty() {
        return active.isEmpty();
    }
}
