package com.mikumc.mikutp.paper.listener;

import com.mikumc.mikutp.paper.service.CooldownManager;
import com.mikumc.mikutp.paper.service.HomeService;
import com.mikumc.mikutp.paper.service.OnlinePlayers;
import com.mikumc.mikutp.paper.service.ProfileService;
import com.mikumc.mikutp.paper.service.RequestService;
import com.mikumc.mikutp.paper.service.SyncCoordinator;
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
    private final SyncCoordinator coordinator;
    private final OnlinePlayers onlinePlayers;

    public PlayerLifecycle(ProfileService profiles, HomeService homeService,
                           RequestService requestService, TeleportService teleports,
                           CooldownManager cooldowns, SyncCoordinator coordinator,
                           OnlinePlayers onlinePlayers) {
        this.profiles = profiles;
        this.homeService = homeService;
        this.requestService = requestService;
        this.teleports = teleports;
        this.cooldowns = cooldowns;
        this.coordinator = coordinator;
        this.onlinePlayers = onlinePlayers;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        refresh(player);
        // A real join announces the profile; refresh() deliberately does not, so
        // /mtp reload cannot turn every online player into PROFILE traffic (R2-03).
        profiles.onJoin(player);
        coordinator.onPlayerJoin();
        // Answers that arrived while this backend still saw the player as absent
        // are replayed first: they may be the acceptance the player is waiting for.
        requestService.replayDeferred(player);
        requestService.redeliverPending(player);
        // Applying a buffered handoff moves the player; do it last so no dialog is
        // opened only to be closed by the teleport a moment later.
        teleports.applyPending(id);
    }

    /**
     * Rebuilds every piece of per-player runtime state for a player that is
     * already online. Called from the join handler and again after /mtp reload: a
     * server reload never fires PlayerJoinEvent, so without this the profile
     * toggle cache, cooldown bypass and home caches would stay empty.
     *
     * <p>Must run on the player's own entity thread.
     */
    public void refresh(Player player) {
        UUID id = player.getUniqueId();
        onlinePlayers.add(player);
        cooldowns.setBypass(id, player.hasPermission("mikutp.bypass.cooldown"));
        // Quiet variant: rebuilds the local tpa cache without publishing PROFILE /
        // PROFILE_REQUEST, so reload does not replicate anything (R2-03).
        profiles.refresh(player);
        homeService.onJoin(player);
        // Request dedupe state (answered/handled request ids) is intentionally not
        // reset here: the proxy re-delivers a pending TP_NEW after a server switch
        // and RequestService.onQuit() already dropped the session-local copy.
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        // Capture the logout position while the entity is still valid; the
        // PlayerQuitEvent MONITOR stage is the last point where that holds.
        teleports.recordLogout(player);
        onlinePlayers.remove(id);
        profiles.onQuit(id);
        homeService.onQuit(id);
        requestService.onQuit(id);
        coordinator.onPlayerQuit();
        teleports.warmups().drop(id);
        teleports.forgetArrival(id);
        cooldowns.clear(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        teleports.recordDeathBack(event.getEntity());
    }
}
