package com.mikumc.mikutp.paper.listener;

import com.mikumc.mikutp.paper.service.CooldownManager;
import com.mikumc.mikutp.paper.service.HomeService;
import com.mikumc.mikutp.paper.service.ProfileService;
import com.mikumc.mikutp.paper.service.RequestService;
import com.mikumc.mikutp.paper.service.TeleportService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/** Join/quit bookkeeping and death position tracking for /back. */
public final class PlayerLifecycle implements Listener {

    private final ProfileService profiles;
    private final HomeService homeService;
    private final RequestService requestService;
    private final TeleportService teleports;
    private final CooldownManager cooldowns;

    public PlayerLifecycle(ProfileService profiles, HomeService homeService,
                           RequestService requestService, TeleportService teleports,
                           CooldownManager cooldowns) {
        this.profiles = profiles;
        this.homeService = homeService;
        this.requestService = requestService;
        this.teleports = teleports;
        this.cooldowns = cooldowns;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        cooldowns.setBypass(id, player.hasPermission("mikutp.bypass.cooldown"));
        profiles.onJoin(player);
        homeService.onJoin(player);
        teleports.applyPending(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        teleports.recordLogout(event.getPlayer());
        profiles.onQuit(id);
        homeService.onQuit(id);
        requestService.onQuit(id);
        teleports.warmups().drop(id);
        cooldowns.clear(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        teleports.recordDeathBack(event.getEntity());
    }
}
