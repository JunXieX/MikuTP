package com.mikumc.mikutp.paper.service;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Single entry point to the region schedulers so every task in the plugin is
 * Folia-compatible by construction. Entity work follows the entity; region
 * work stays at a location; blocking work goes to the async pool.
 */
public final class Tasks {

    private final Plugin plugin;
    /** Set once the plugin is disabling: new async work is refused from then on. */
    private volatile boolean shuttingDown;
    /** Async work currently executing; the shutdown drain waits for it to reach zero. */
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
    }

    public void async(Runnable run) {
        // Increment before the flag check: a task admitted just before shutdown must
        // still be seen by the bounded drain, otherwise it could run after storage
        // closes (I-R3-02).
        inFlight.incrementAndGet();
        if (shuttingDown) {
            inFlight.decrementAndGet();
            return;
        }
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> runGuarded(run));
        } catch (RuntimeException e) {
            // Only reachable in the check/disable race; the work is dropped anyway.
            inFlight.decrementAndGet();
        }
    }

    public void asyncDelayed(Runnable run, long delayMillis) {
        // Same ordering as async(): count first, then refuse (I-R3-02).
        inFlight.incrementAndGet();
        if (shuttingDown) {
            inFlight.decrementAndGet();
            return;
        }
        try {
            Bukkit.getAsyncScheduler().runDelayed(plugin, task -> runGuarded(run),
                    Math.max(1, delayMillis), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            inFlight.decrementAndGet();
        }
    }

    public ScheduledTask asyncRepeat(Runnable run, long initialDelay, long period, TimeUnit unit) {
        if (shuttingDown) {
            return null;
        }
        try {
            return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
                if (shuttingDown) {
                    task.cancel();
                    return;
                }
                // Pair the count: runGuarded always decrements (R3-02).
                inFlight.incrementAndGet();
                runGuarded(run);
            }, initialDelay, period, unit);
        } catch (RuntimeException e) {
            // Only reachable in the check/disable race: no task was scheduled.
            return null;
        }
    }

    private void runGuarded(Runnable run) {
        try {
            run.run();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * Refuses new async work and lets in-flight async work finish. Called first in
     * {@code onDisable}: storage is closed after the bounded drain, so a task that
     * still ran would otherwise fail against a closed pool (R2-12).
     */
    public void beginShutdown() {
        shuttingDown = true;
    }

    /**
     * Waits up to {@code maxMillis} for async work to drain.
     *
     * @return true when nothing is in flight any more
     */
    public boolean awaitQuiescence(long maxMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, maxMillis);
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return inFlight.get() == 0;
    }

    /** True once {@link #beginShutdown()} ran: {@link #async} then silently drops work. */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public void entity(Player player, Runnable run) {
        player.getScheduler().run(plugin, task -> run.run(), null);
    }

    /**
     * Entity work with a retirement callback. The callback runs when the entity
     * is removed before the task fires, which is the only chance to release
     * anything the caller parked on the task (a queued batch, for instance).
     */
    public void entity(Player player, Runnable run, Runnable retired) {
        player.getScheduler().run(plugin, task -> run.run(), retired);
    }

    /**
     * True when the calling thread is the player's region thread, i.e. work may
     * touch this player directly instead of hopping onto its scheduler.
     */
    public boolean owns(Player player) {
        try {
            return Bukkit.isOwnedByCurrentRegion(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Runs once per tick on the player's region thread until the consumer cancels
     * it. {@code retired} runs when the entity is removed before the task could
     * fire, so callers can clean up their own bookkeeping. Returns null when the
     * scheduler is already retired.
     */
    public ScheduledTask entityRepeat(Player player, Consumer<ScheduledTask> run, Runnable retired,
                                      long initialDelayTicks, long periodTicks) {
        return player.getScheduler().runAtFixedRate(plugin, run, retired, initialDelayTicks, periodTicks);
    }

    public void region(org.bukkit.World world, int chunkX, int chunkZ, Runnable run) {
        Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, task -> run.run());
    }

    /** Runs on the global region thread: the one place global state (the player
     * list) may be touched on Folia. */
    public void global(Runnable run) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> run.run());
    }
}
