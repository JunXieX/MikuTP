package com.mikumc.mikutp.paper.service;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
    }

    public void async(Runnable run) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> run.run());
    }

    public void asyncDelayed(Runnable run, long delayMillis) {
        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> run.run(), Math.max(1, delayMillis), TimeUnit.MILLISECONDS);
    }

    public ScheduledTask asyncRepeat(Runnable run, long initialDelay, long period, TimeUnit unit) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> run.run(), initialDelay, period, unit);
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

    /** Runs once per tick on the player's region thread until the consumer cancels it. */
    public ScheduledTask entityRepeat(Player player, Consumer<ScheduledTask> run, long initialDelayTicks, long periodTicks) {
        return player.getScheduler().runAtFixedRate(plugin, run, null, initialDelayTicks, periodTicks);
    }

    /**
     * Like {@link #entityRepeat}, but {@code retired} runs when the entity is
     * removed before the task could fire, so callers can clean up their own
     * bookkeeping. Returns null when the scheduler is already retired.
     */
    public ScheduledTask entityRepeat(Player player, Consumer<ScheduledTask> run, Runnable retired,
                                      long initialDelayTicks, long periodTicks) {
        return player.getScheduler().runAtFixedRate(plugin, run, retired, initialDelayTicks, periodTicks);
    }

    public void region(Location location, Runnable run) {
        Bukkit.getRegionScheduler().run(plugin, location, task -> run.run());
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
