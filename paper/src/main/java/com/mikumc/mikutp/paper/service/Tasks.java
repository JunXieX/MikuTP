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

    public void entityDelayed(Player player, Runnable run, long delayTicks) {
        player.getScheduler().runDelayed(plugin, task -> run.run(), null, delayTicks);
    }

    /** Runs once per tick on the player's region thread until the consumer cancels it. */
    public ScheduledTask entityRepeat(Player player, Consumer<ScheduledTask> run, long initialDelayTicks, long periodTicks) {
        return player.getScheduler().runAtFixedRate(plugin, run, null, initialDelayTicks, periodTicks);
    }

    public void region(Location location, Runnable run) {
        Bukkit.getRegionScheduler().run(plugin, location, task -> run.run());
    }

    public void region(org.bukkit.World world, int chunkX, int chunkZ, Runnable run) {
        Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, task -> run.run());
    }

    public Plugin plugin() {
        return plugin;
    }
}
