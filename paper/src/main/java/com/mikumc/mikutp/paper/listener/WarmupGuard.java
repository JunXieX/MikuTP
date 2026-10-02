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
        if (threshold <= 0) {
            if (to.getX() != from.getX() || to.getY() != from.getY() || to.getZ() != from.getZ()) {
                warmups.cancel(event.getPlayer(), "common.warmup.cancelled-move");
            }
            return;
        }
        if (from.distanceSquared(to) >= threshold * threshold) {
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
