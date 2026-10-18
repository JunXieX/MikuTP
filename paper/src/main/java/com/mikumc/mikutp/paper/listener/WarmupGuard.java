package com.mikumc.mikutp.paper.listener;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.paper.service.WarmupManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/** Cancels pending teleports on movement or damage. Reads the config live so
 * /mtp reload takes effect without a restart. */
public final class WarmupGuard implements Listener {

    /**
     * Movement below this many blocks is ignored even in the strictest mode.
     * Comparing raw doubles would cancel on sub-millimetre drift caused by the
     * client's own position corrections, which the player cannot prevent.
     */
    private static final double EPSILON_SQUARED = 1.0e-4;

    private final WarmupManager warmups;
    private final MikuTPConfig config;

    public WarmupGuard(WarmupManager warmups, MikuTPConfig config) {
        this.warmups = warmups;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (warmups.isEmpty() || !warmups.isWarming(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        double threshold = Math.max(0, config.teleport.moveThresholdBlocks);
        double limit = threshold <= 0 ? EPSILON_SQUARED : threshold * threshold;
        // Locations in different worlds cannot be measured: distanceSquared
        // throws IllegalArgumentException. A world change during warmup is a
        // real move (portal, /execute in, another plugin), so cancel.
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            warmups.cancel(event.getPlayer(), "common.warmup.cancelled-move");
            return;
        }
        if (from.distanceSquared(to) >= limit) {
            warmups.cancel(event.getPlayer(), "common.warmup.cancelled-move");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!config.teleport.cancelOnDamage || warmups.isEmpty()) {
            return;
        }
        if (event.getEntity() instanceof Player player && warmups.isWarming(player.getUniqueId())) {
            warmups.cancel(player, "common.warmup.cancelled-damage");
        }
    }
}
