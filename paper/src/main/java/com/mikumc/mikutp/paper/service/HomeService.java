package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.data.Home;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.data.Position;
import com.mikumc.mikutp.common.sync.SyncBus;
import com.mikumc.mikutp.common.util.Validate;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Homes: create, delete and travel (locally or across the network). */
public final class HomeService {

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final MikuTPConfig config;
    private final Database database;
    private final MessageService messages;
    private final CooldownManager cooldowns;
    private final TeleportService teleports;
    private final SyncBus syncBus;
    private final String serverId;
    private final Map<UUID, List<Home>> cache = new ConcurrentHashMap<>();
    /** Home limits captured on the join thread; PlaceholderAPI reads these off-thread. */
    private final Map<UUID, Integer> limitCache = new ConcurrentHashMap<>();

    public HomeService(JavaPlugin plugin, Tasks tasks, MikuTPConfig config, Database database,
                       MessageService messages, CooldownManager cooldowns, TeleportService teleports,
                       SyncBus syncBus) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.config = config;
        this.database = database;
        this.messages = messages;
        this.cooldowns = cooldowns;
        this.teleports = teleports;
        this.syncBus = syncBus;
        this.serverId = config.crossServer.serverId;
    }

    public void onJoin(Player player) {
        tasks.entity(player, () -> limitCache.put(player.getUniqueId(), computeLimit(player)));
        tasks.async(() -> {
            try {
                cache.put(player.getUniqueId(), database.listHomes(player.getUniqueId().toString()));
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to load homes", e);
            }
        });
    }

    public void onQuit(UUID player) {
        cache.remove(player);
        limitCache.remove(player);
    }

    /** Cached limit for off-thread consumers (PlaceholderAPI). */
    public int cachedLimit(UUID player) {
        return limitCache.getOrDefault(player, Math.max(0, config.home.defaultLimit));
    }

    public List<Home> homes(UUID player) {
        List<Home> homes = cache.get(player);
        return homes == null ? List.of() : homes;
    }

    /** Home limit from mikutp.homes.&lt;n&gt; permissions; must run on the player's thread. */
    public int limit(Player player) {
        return computeLimit(player);
    }

    private int computeLimit(Player player) {
        if (player.hasPermission("mikutp.homes.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int best = Math.max(0, config.home.defaultLimit);
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission().toLowerCase();
            if (info.getValue() && perm.startsWith("mikutp.homes.")) {
                try {
                    best = Math.max(best, Integer.parseInt(perm.substring("mikutp.homes.".length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return best;
    }

    public void set(Player player, String name) {
        if (!Validate.validName(config.home.namePattern, name)) {
            messages.send(player, "common.invalid-name");
            return;
        }
        int maxHomes = limit(player);
        Location loc = player.getLocation().clone();
        UUID id = player.getUniqueId();
        tasks.async(() -> {
            try {
                List<Home> current = new ArrayList<>(homes(id));
                boolean exists = current.stream().anyMatch(h -> h.name.equalsIgnoreCase(name));
                if (!exists && current.size() >= maxHomes) {
                    messages.send(player, "home.limit-reached", "limit", String.valueOf(maxHomes));
                    return;
                }
                Home home = new Home(id.toString(), name, positionOf(loc), System.currentTimeMillis());
                database.saveHome(home);
                cache.put(id, sort(replace(current, home)));
                messages.send(player, "home.set", "name", name);
                publishHomeSet(home);
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to save home", e);
                messages.send(player, "common.teleport-failed");
            }
        });
    }

    public void delete(Player player, String name) {
        UUID id = player.getUniqueId();
        tasks.async(() -> {
            try {
                if (database.deleteHome(id.toString(), name)) {
                    cache.computeIfPresent(id, (k, list) -> sort(list.stream()
                            .filter(h -> !h.name.equalsIgnoreCase(name)).toList()));
                    messages.send(player, "home.deleted", "name", name);
                    publishHomeDelete(id.toString(), name);
                } else {
                    messages.send(player, "home.not-found", "name", name);
                }
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to delete home", e);
            }
        });
    }

    private void publishHomeSet(Home home) {
        if (!syncBus.crossServer()) {
            return;
        }
        var event = com.mikumc.mikutp.common.sync.SyncEvent.create(
                com.mikumc.mikutp.common.sync.SyncEvent.Type.HOME_SET, serverId);
        event.ownerUuid = home.ownerUuid;
        event.homeName = home.name;
        event.homePosition = home.position;
        event.homeCreatedAt = home.createdAt;
        syncBus.publish(event);
    }

    private void publishHomeDelete(String ownerUuid, String name) {
        if (!syncBus.crossServer()) {
            return;
        }
        var event = com.mikumc.mikutp.common.sync.SyncEvent.create(
                com.mikumc.mikutp.common.sync.SyncEvent.Type.HOME_DELETE, serverId);
        event.ownerUuid = ownerUuid;
        event.homeName = name;
        syncBus.publish(event);
    }

    /** Applies a replicated home change from another backend. */
    public void applyRemoteSet(com.mikumc.mikutp.common.sync.SyncEvent event) {
        if (event.ownerUuid == null || event.homeName == null || event.homePosition == null) {
            return;
        }
        try {
            Home home = new Home(event.ownerUuid, event.homeName, event.homePosition, event.homeCreatedAt);
            database.saveHome(home);
            cache.computeIfPresent(UUID.fromString(event.ownerUuid), (k, list) -> sort(replace(list, home)));
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Failed to apply replicated home", e);
        }
    }

    public void applyRemoteDelete(com.mikumc.mikutp.common.sync.SyncEvent event) {
        if (event.ownerUuid == null || event.homeName == null) {
            return;
        }
        try {
            database.deleteHome(event.ownerUuid, event.homeName);
            cache.computeIfPresent(UUID.fromString(event.ownerUuid), (k, list) -> sort(list.stream()
                    .filter(h -> !h.name.equalsIgnoreCase(event.homeName)).toList()));
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Failed to apply replicated home deletion", e);
        }
    }

    public void go(Player player, String name) {
        UUID id = player.getUniqueId();
        long remaining = cooldowns.remaining(id, CooldownManager.Kind.HOME);
        if (remaining > 0) {
            messages.send(player, "common.cooldown", "seconds", String.valueOf(remaining));
            return;
        }
        tasks.async(() -> {
            Home home = find(homes(id), name);
            if (home == null) {
                try {
                    home = database.getHome(id.toString(), name).orElse(null);
                } catch (Exception e) {
                    plugin.getSLF4JLogger().warn("Failed to read home", e);
                }
            }
            if (home == null) {
                messages.send(player, "home.not-found", "name", name);
                return;
            }
            // Only burn the cooldown on a real, reachable home.
            cooldowns.apply(id, CooldownManager.Kind.HOME);
            messages.send(player, "home.going", "name", home.name);
            teleports.send(player, home.position, PendingTeleport.Source.HOME, home.name, null);
        });
    }

    private static Home find(List<Home> homes, String name) {
        for (Home home : homes) {
            if (home.name.equals(name)) {
                return home;
            }
        }
        for (Home home : homes) {
            if (home.name.equalsIgnoreCase(name)) {
                return home;
            }
        }
        return null;
    }

    private static List<Home> replace(List<Home> homes, Home home) {
        List<Home> out = new ArrayList<>(homes.stream()
                .filter(h -> !h.name.equalsIgnoreCase(home.name)).toList());
        out.add(home);
        return out;
    }

    private static List<Home> sort(List<Home> homes) {
        return homes.stream().sorted(Comparator.comparing(h -> h.name.toLowerCase())).toList();
    }

    private Position positionOf(Location loc) {
        return new Position(serverId, loc.getWorld() == null ? "world" : loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }
}
