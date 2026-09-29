package com.mikumc.mikutp.paper.listener;

import com.mikumc.mikutp.paper.service.WarmupManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/** Cancels pending teleports on movement or damage. */
public final class WarmupGuard implements Listener {

    private final WarmupManager warmups;
    private final double moveThresholdBlocks;
    private final boolean cancelOnDamage;

    public WarmupGuard(WarmupManager warmups, double moveThresholdBlocks, boolean cancelOnDamage) {
        this.warmups = warmups;
        this.moveThresholdBlocks = Math.max(0, moveThresholdBlocks);
        this.cancelOnDamage = cancelOnDamage;
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
        if (moveThresholdBlocks <= 0) {
            if (to.getX() != from.getX() || to.getY() != from.getY() || to.getZ() != from.getZ()) {
                warmups.cancel(event.getPlayer(), "common.warmup.cancelled-move");
            }
            return;
        }
        if (from.distanceSquared(to) >= moveThresholdBlocks * moveThresholdBlocks) {
            warmups.cancel(event.getPlayer(), "common.warmup.cancelled-move");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!cancelOnDamage || warmups.isEmpty()) {
            return;
        }
        if (event.getEntity() instanceof Player player && warmups.isWarming(player.getUniqueId())) {
            warmups.cancel(player, "common.warmup.cancelled-damage");
        }
    }
}
