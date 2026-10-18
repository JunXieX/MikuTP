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
    private final OnlinePlayers onlinePlayers;
    private final String serverId;
    private final Map<UUID, List<Home>> cache = new ConcurrentHashMap<>();
    /** Players whose cache entry came from a full database read. */
    private final java.util.Set<UUID> loadedPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Home limits captured on the join thread; PlaceholderAPI reads these off-thread. */
    private final Map<UUID, Integer> limitCache = new ConcurrentHashMap<>();
    /** Striped monitors: two /sethome in flight must not both pass the limit check. */
    private final Object[] locks = new Object[64];

    public HomeService(JavaPlugin plugin, Tasks tasks, MikuTPConfig config, Database database,
                       MessageService messages, CooldownManager cooldowns, TeleportService teleports,
                       SyncBus syncBus, OnlinePlayers onlinePlayers) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.config = config;
        this.database = database;
        this.messages = messages;
        this.cooldowns = cooldowns;
        this.teleports = teleports;
        this.syncBus = syncBus;
        this.onlinePlayers = onlinePlayers;
        this.serverId = config.crossServer.serverId;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    private Object lockFor(UUID player) {
        return locks[(player.hashCode() & 0x7fffffff) % locks.length];
    }

    public void onJoin(Player player) {
        UUID id = player.getUniqueId();
        tasks.entity(player, () -> limitCache.put(id, computeLimit(player)));
        loadAsync(id);
    }

    /** Reads the player's homes and publishes them unless a mutation already did. */
    private void loadAsync(UUID id) {
        tasks.async(() -> {
            // The read happens under the same stripe the mutations use: a remote
            // HOME_SET merged into the cache while this query was in flight would
            // otherwise be overwritten by the older snapshot.
            synchronized (lockFor(id)) {
                if (loadedPlayers.contains(id) || onlinePlayers.byId(id) == null) {
                    return;
                }
                try {
                    List<Home> loaded = sort(database.listHomes(id.toString()));
                    cache.put(id, loaded);
                    loadedPlayers.add(id);
                } catch (Exception e) {
                    plugin.getSLF4JLogger().warn("Failed to load homes", e);
                }
            }
        });
    }

    public void onQuit(UUID player) {
        // Memory only: this runs on the player's region thread (Folia confines
        // PlayerQuitEvent there), and the load path holds this stripe while it
        // queries the single SQLite connection. Waiting for that lock here would
        // stall the whole region. A load that finishes afterwards re-checks the
        // registry under the same lock and refuses to write for an offline player.
        cache.remove(player);
        limitCache.remove(player);
        loadedPlayers.remove(player);
    }

    /**
     * Authoritative home list for a player, loading it synchronously when the
     * join-time load has not completed yet. Callers hold the player's lock, so
     * a mutation can never observe a half-populated cache: an empty list would
     * otherwise let /sethome exceed the limit and drop the existing homes.
     */
    private List<Home> homesForMutation(UUID player) throws java.sql.SQLException {
        List<Home> cached = cache.get(player);
        if (cached != null && loadedPlayers.contains(player)) {
            return cached;
        }
        List<Home> loaded = sort(database.listHomes(player.toString()));
        cache.put(player, loaded);
        loadedPlayers.add(player);
        return loaded;
    }

    /** Cached limit for off-thread consumers (PlaceholderAPI). */
    public int cachedLimit(UUID player) {
        return limitCache.getOrDefault(player, Math.max(0, config.home.defaultLimit));
    }

    public List<Home> homes(UUID player) {
        List<Home> homes = cache.get(player);
        return homes == null ? List.of() : homes;
    }

    /**
     * Home list for a menu. The join-time load is asynchronous, so a menu opened
     * before it finished would show an empty list; this loads synchronously (the
     * caller is already on an async thread) and returns the real list. Callers
     * must not run this on a region thread.
     */
    public List<Home> homesForDisplay(UUID player) {
        if (loadedPlayers.contains(player)) {
            return homes(player);
        }
        synchronized (lockFor(player)) {
            if (loadedPlayers.contains(player)) {
                return homes(player);
            }
            try {
                List<Home> loaded = sort(database.listHomes(player.toString()));
                cache.put(player, loaded);
                loadedPlayers.add(player);
                return loaded;
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to load homes for display", e);
                return homes(player);
            }
        }
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
        // Names are identifiers: store and match them lowercase so `Base` and
        // `base` can never diverge between cache and database.
        String wanted = name.toLowerCase(java.util.Locale.ROOT);
        UUID id = player.getUniqueId();
        // Both the position and the permission-derived limit are per-entity state:
        // read them on the player's own thread, never on the command thread.
        tasks.entity(player, () -> {
            int maxHomes = limit(player);
            Location loc = player.getLocation().clone();
            tasks.async(() -> {
                synchronized (lockFor(id)) {
                    try {
                        List<Home> current = new ArrayList<>(homesForMutation(id));
                        boolean exists = current.stream().anyMatch(h -> h.name.equalsIgnoreCase(wanted));
                        if (!exists && current.size() >= maxHomes) {
                            messages.send(player, "home.limit-reached", "limit", String.valueOf(maxHomes));
                            return;
                        }
                        // Pre-lowercase rows from older versions: remove case variants first.
                        for (Home existing : current) {
                            if (!existing.name.equals(wanted) && existing.name.equalsIgnoreCase(wanted)) {
                                database.deleteHome(id.toString(), existing.name);
                            }
                        }
                        Home home = new Home(id.toString(), wanted, positionOf(loc), System.currentTimeMillis());
                        database.saveHome(home);
                        cache.put(id, sort(replace(current, home)));
                        messages.send(player, "home.set", "name", wanted);
                        publishHomeSet(home);
                    } catch (Exception e) {
                        plugin.getSLF4JLogger().warn("Failed to save home", e);
                        messages.send(player, "home.save-failed");
                    }
                }
            });
        });
    }

    public void delete(Player player, String name) {
        UUID id = player.getUniqueId();
        String wanted = name.toLowerCase(java.util.Locale.ROOT);
        tasks.async(() -> {
            synchronized (lockFor(id)) {
                try {
                    // Load through the same path mutations use: when the join-time
                    // load has not finished, deleting straight from the cache would
                    // miss the entry and leave a stale snapshot to be written later.
                    List<Home> current = homesForMutation(id);
                    Home match = null;
                    for (Home home : current) {
                        if (home.name.equalsIgnoreCase(wanted)) {
                            match = home;
                            break;
                        }
                    }
                    long deletedAt = System.currentTimeMillis();
                    if (match == null || !database.deleteHome(id.toString(), match.name)) {
                        messages.send(player, "home.not-found", "name", wanted);
                        return;
                    }
                    cache.put(id, sort(current.stream()
                            .filter(h -> !h.name.equalsIgnoreCase(wanted)).toList()));
                    messages.send(player, "home.deleted", "name", wanted);
                    // Publish the stored spelling so the other backends delete the
                    // exact row rather than relying on case-insensitive matching.
                    publishHomeDelete(id.toString(), match.name, deletedAt);
                } catch (Exception e) {
                    plugin.getSLF4JLogger().warn("Failed to delete home", e);
                }
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

    private void publishHomeDelete(String ownerUuid, String name, long deletedAt) {
        if (!syncBus.crossServer()) {
            return;
        }
        var event = com.mikumc.mikutp.common.sync.SyncEvent.create(
                com.mikumc.mikutp.common.sync.SyncEvent.Type.HOME_DELETE, serverId);
        event.ownerUuid = ownerUuid;
        event.homeName = name;
        event.deletedAt = deletedAt;
        syncBus.publish(event);
    }

    /**
     * Applies a replicated home change from another backend; the write joins the
     * caller's batched transaction and the cache update is deferred until that
     * transaction has committed, so a rollback cannot leave the cache ahead of
     * the database.
     */
    public void applyRemoteSet(com.mikumc.mikutp.common.sync.SyncEvent event, Database.Tx tx,
                               java.util.function.Consumer<Runnable> afterCommit) throws java.sql.SQLException {
        if (event.ownerUuid == null || event.homeName == null || event.homePosition == null) {
            return;
        }
        Home home = new Home(event.ownerUuid, event.homeName, event.homePosition, event.homeCreatedAt);
        boolean written = tx.saveHome(home) > 0;
        UUID owner = parseUuid(event.ownerUuid);
        // A rejected write (blocked by a newer deletion tombstone, or older than
        // the row already stored) must not reach the cache either.
        if (owner != null && written) {
            // Runs after the commit, so taking the stripe here cannot invert the
            // lock order (mutations hold the stripe while acquiring a connection,
            // never the other way round).
            afterCommit.accept(() -> {
                synchronized (lockFor(owner)) {
                    List<Home> current = cache.get(owner);
                    if (current != null) {
                        cache.put(owner, sort(replace(current, home)));
                    }
                }
            });
        }
    }

    public void applyRemoteDelete(com.mikumc.mikutp.common.sync.SyncEvent event, Database.Tx tx,
                                  java.util.function.Consumer<Runnable> afterCommit) throws java.sql.SQLException {
        if (event.ownerUuid == null || event.homeName == null) {
            return;
        }
        long deletedAt = event.deletedAt > 0 ? event.deletedAt : System.currentTimeMillis();
        tx.deleteHome(event.ownerUuid, event.homeName, deletedAt);
        UUID owner = parseUuid(event.ownerUuid);
        if (owner != null) {
            afterCommit.accept(() -> {
                synchronized (lockFor(owner)) {
                    List<Home> current = cache.get(owner);
                    if (current == null) {
                        return;
                    }
                    // Never drop a home re-created after the delete: only entries
                    // that predate the deletion stamp go.
                    List<Home> kept = current.stream()
                            .filter(h -> !h.name.equalsIgnoreCase(event.homeName) || h.createdAt > deletedAt)
                            .toList();
                    cache.put(owner, sort(kept));
                }
            });
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void go(Player player, String name) {
        UUID id = player.getUniqueId();
        // Check and burn in one step: two /home commands arriving together would
        // otherwise both pass a separate remaining() check and teleport twice.
        long remaining = cooldowns.tryConsume(id, CooldownManager.Kind.HOME);
        if (remaining > 0) {
            messages.send(player, "common.cooldown", "seconds", String.valueOf(remaining));
            return;
        }
        tasks.async(() -> {
            Home home = find(homes(id), name);
            if (home == null) {
                try {
                    home = database.getHome(id.toString(), name.toLowerCase(java.util.Locale.ROOT)).orElse(null);
                } catch (Exception e) {
                    plugin.getSLF4JLogger().warn("Failed to read home", e);
                }
            }
            if (home == null) {
                // Nothing was teleported: give the cooldown back.
                cooldowns.clear(id, CooldownManager.Kind.HOME);
                messages.send(player, "home.not-found", "name", name);
                return;
            }
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
