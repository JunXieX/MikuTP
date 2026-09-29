package com.mikumc.mikutp.paper.hook;

import com.mikumc.mikutp.paper.service.CooldownManager;
import com.mikumc.mikutp.paper.service.HomeService;
import com.mikumc.mikutp.paper.service.ProfileService;
import com.mikumc.mikutp.paper.service.WarpService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** PlaceholderAPI placeholders under the mikutp identifier. */
public final class MikuTPExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final HomeService homeService;
    private final WarpService warpService;
    private final ProfileService profiles;
    private final CooldownManager cooldowns;
    private final String serverId;

    public MikuTPExpansion(JavaPlugin plugin, HomeService homeService, WarpService warpService,
                           ProfileService profiles, CooldownManager cooldowns, String serverId) {
        this.plugin = plugin;
        this.homeService = homeService;
        this.warpService = warpService;
        this.profiles = profiles;
        this.cooldowns = cooldowns;
        this.serverId = serverId;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "mikutp";
    }

    @Override
    public @NotNull String getAuthor() {
        return "JunXieX";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase();
        UUID id = player == null ? null : player.getUniqueId();
        return switch (key) {
            case "homes_used" -> id == null ? "" : String.valueOf(homeService.homes(id).size());
            case "homes_max" -> player instanceof Player online ? String.valueOf(homeService.limit(online)) : "";
            case "homes_left" -> player instanceof Player online
                    ? String.valueOf(Math.max(0, homeService.limit(online) - homeService.homes(id).size()))
                    : "";
            case "warps" -> String.valueOf(warpService.warps().size());
            case "tpa_enabled" -> id == null ? "" : String.valueOf(profiles.isTpaEnabled(id));
            case "cooldown_home" -> id == null ? "" : String.valueOf(cooldowns.remaining(id, CooldownManager.Kind.HOME));
            case "cooldown_warp" -> id == null ? "" : String.valueOf(cooldowns.remaining(id, CooldownManager.Kind.WARP));
            case "cooldown_tpa" -> id == null ? "" : String.valueOf(cooldowns.remaining(id, CooldownManager.Kind.TPA));
            case "cooldown_wild" -> id == null ? "" : String.valueOf(cooldowns.remaining(id, CooldownManager.Kind.WILD));
            case "cooldown_back" -> id == null ? "" : String.valueOf(cooldowns.remaining(id, CooldownManager.Kind.BACK));
            case "server_id" -> serverId;
            default -> null;
        };
    }
}
