package com.mikumc.mikutp.paper.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/** Per-player command cooldowns, in-memory only. */
public final class CooldownManager {

    public enum Kind {
        HOME, WARP, TPA, WILD, BACK, DBACK
    }

    private final Map<UUID, Map<Kind, Long>> until = new ConcurrentHashMap<>();
    private final Set<UUID> bypass = ConcurrentHashMap.newKeySet();
    private final java.util.function.ToIntFunction<Kind> secondsFor;

    public CooldownManager(java.util.function.ToIntFunction<Kind> secondsFor) {
        this.secondsFor = secondsFor;
    }

    /** Marks a player exempt from cooldowns (mikutp.bypass.cooldown). */
    public void setBypass(UUID player, boolean exempt) {
        if (exempt) {
            bypass.add(player);
        } else {
            bypass.remove(player);
        }
    }

    /** Remaining seconds before {@code kind} may be used again; 0 when ready. */
    public long remaining(UUID player, Kind kind) {
        if (bypass.contains(player)) {
            return 0;
        }
        Map<Kind, Long> map = until.get(player);
        if (map == null) {
            return 0;
        }
        Long end = map.get(kind);
        if (end == null) {
            return 0;
        }
        long rest = (end - System.currentTimeMillis()) / 1000L;
        return Math.max(0, rest);
    }

    public void apply(UUID player, Kind kind) {
        int seconds = secondsFor.applyAsInt(kind);
        if (seconds <= 0) {
            return;
        }
        until.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                .put(kind, System.currentTimeMillis() + seconds * 1000L);
    }

    /** Clears every cooldown and the cached bypass flag (called on quit). */
    public void clear(UUID player) {
        until.remove(player);
        bypass.remove(player);
    }

    public void clear(UUID player, Kind kind) {
        Map<Kind, Long> map = until.get(player);
        if (map != null) {
            map.remove(kind);
        }
    }
}
