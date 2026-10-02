package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.PendingTeleport;
import com.mikumc.mikutp.common.data.Position;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Random teleport ("wild"): uniform ring sampling around a center, biome tag
 * filtering against resource-poor terrain and a safe-landing check, all on
 * region-owned threads with async chunk loading.
 */
public final class WildService {

    private static final Set<Material> HAZARDS = EnumSet.of(
            Material.LAVA, Material.MAGMA_BLOCK, Material.CACTUS, Material.FIRE, Material.SOUL_FIRE,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW,
            Material.POINTED_DRIPSTONE, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE);

    private final JavaPlugin plugin;
    private final Tasks tasks;
    private final MikuTPConfig config;
    private final MessageService messages;
    private final CooldownManager cooldowns;
    private final TeleportService teleports;
    private final Map<String, java.util.Optional<Tag<Biome>>> tagCache = new ConcurrentHashMap<>();

    public WildService(JavaPlugin plugin, Tasks tasks, MikuTPConfig config, MessageService messages,
                       CooldownManager cooldowns, TeleportService teleports) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.config = config;
        this.messages = messages;
        this.cooldowns = cooldowns;
        this.teleports = teleports;
    }

    public void start(Player player) {
        if (!config.wild.enabled) {
            messages.send(player, "wild.disabled");
            return;
        }
        UUID id = player.getUniqueId();
        long remaining = cooldowns.remaining(id, CooldownManager.Kind.WILD);
        if (remaining > 0) {
            messages.send(player, "common.cooldown", "seconds", String.valueOf(remaining));
            return;
        }
        tasks.entity(player, () -> {
            World world = resolveWorld(player);
            if (world == null) {
                messages.send(player, "wild.failed");
                return;
            }
            double centerX;
            double centerZ;
            if ("ZERO".equalsIgnoreCase(config.wild.center)) {
                centerX = 0;
                centerZ = 0;
            } else {
                Location spawn = world.getSpawnLocation();
                centerX = spawn.getX();
                centerZ = spawn.getZ();
            }
            long maxRadius = effectiveMaxRadius(world);
            long minRadius = Math.min(Math.max(0, config.wild.minRadius), maxRadius);
            cooldowns.apply(id, CooldownManager.Kind.WILD);
            messages.send(player, "wild.searching");
            AtomicInteger attempts = new AtomicInteger(Math.max(1, config.wild.maxAttempts));
            attempt(player, world, centerX, centerZ, minRadius, maxRadius, attempts);
        });
    }

    private World resolveWorld(Player player) {
        if (!"LIST".equalsIgnoreCase(config.wild.worldMode) || config.wild.worlds.isEmpty()) {
            return player.getWorld();
        }
        java.util.List<World> candidates = new java.util.ArrayList<>();
        for (String name : config.wild.worlds) {
            World world = Bukkit.getWorld(name);
            if (world != null) {
                candidates.add(world);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    /** Clamps the configured radius to the world border where possible. */
    private long effectiveMaxRadius(World world) {
        long max = Math.max(1, config.wild.maxRadius);
        try {
            double border = world.getWorldBorder().getSize() / 2.0 - 32;
            if (border > 16) {
                max = Math.min(max, (long) border);
            }
        } catch (Throwable ignored) {
            // Border read unavailable; keep the configured radius.
        }
        return max;
    }

    private void attempt(Player player, World world, double centerX, double centerZ,
                         long minRadius, long maxRadius, AtomicInteger attempts) {
        tasks.async(() -> {
            if (!player.isOnline()) {
                return;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double minSq = (double) minRadius * minRadius;
            double maxSq = (double) maxRadius * maxRadius;
            double distance = Math.sqrt(minSq + (maxSq - minSq) * random.nextDouble());
            double angle = random.nextDouble() * Math.PI * 2;
            int x = (int) Math.round(centerX + distance * Math.cos(angle));
            int z = (int) Math.round(centerZ + distance * Math.sin(angle));
            world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk ->
                    tasks.region(world, x >> 4, z >> 4, () -> evaluate(player, world, x, z, centerX, centerZ,
                            minRadius, maxRadius, attempts)))
                    .exceptionally(ex -> {
                        // Chunk load failed (world unloaded mid-search, etc.): count it
                        // as a spent attempt and carry on.
                        if (attempts.decrementAndGet() > 0) {
                            tasks.asyncDelayed(() -> attempt(player, world, centerX, centerZ,
                                    minRadius, maxRadius, attempts), 100);
                        } else {
                            giveUp(player);
                        }
                        return null;
                    });
        });
    }

    private void giveUp(Player player) {
        cooldowns.clear(player.getUniqueId(), CooldownManager.Kind.WILD);
        messages.send(player, "wild.failed");
    }

    /** Runs on the region thread that owns the candidate chunk. */
    private void evaluate(Player player, World world, int x, int z,
                          double centerX, double centerZ, long minRadius, long maxRadius, AtomicInteger attempts) {
        if (!player.isOnline()) {
            return;
        }
        int y = world.getHighestBlockYAt(x, z);
        Block block = world.getBlockAt(x, y, z);
        if (y > world.getMinHeight() && y < world.getMaxHeight() - 2
                && !blockedBiome(block.getBiome())
                && isSafeLanding(world, x, y, z, block)) {
            float yaw = ThreadLocalRandom.current().nextFloat() * 360f;
            Position spot = Position.local(world.getName(), x + 0.5, y + 1.0, z + 0.5, yaw, 0f);
            messages.send(player, "wild.searching-done");
            teleports.send(player, spot, PendingTeleport.Source.WILD,
                    world.getName() + " " + x + ", " + z, null);
            return;
        }
        if (attempts.decrementAndGet() > 0) {
            tasks.asyncDelayed(() -> attempt(player, world, centerX, centerZ, minRadius, maxRadius, attempts), 100);
            return;
        }
        giveUp(player);
    }

    private boolean isSafeLanding(World world, int x, int y, int z, Block ground) {
        if (!config.wild.requireSafeLanding) {
            return true;
        }
        if (!ground.getType().isSolid() || HAZARDS.contains(ground.getType())) {
            return false;
        }
        Material lower = world.getBlockAt(x, y + 1, z).getType();
        Material upper = world.getBlockAt(x, y + 2, z).getType();
        return lower.isAir() && upper.isAir();
    }

    private boolean blockedBiome(Biome biome) {
        for (String tagName : config.wild.blockedBiomeTags) {
            Tag<Biome> tag = tagCache.computeIfAbsent(tagName.toLowerCase(),
                    name -> java.util.Optional.ofNullable(loadTag(name))).orElse(null);
            if (tag != null && tag.isTagged(biome)) {
                return true;
            }
        }
        String key = biome.key().toString();
        for (String blocked : config.wild.blockedBiomes) {
            if (blocked.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private Tag<Biome> loadTag(String name) {
        try {
            NamespacedKey key = name.contains(":")
                    ? NamespacedKey.fromString(name)
                    : NamespacedKey.minecraft(name);
            if (key == null) {
                return null;
            }
            return Bukkit.getTag("biome", key, Biome.class);
        } catch (Throwable e) {
            return null;
        }
    }
}
