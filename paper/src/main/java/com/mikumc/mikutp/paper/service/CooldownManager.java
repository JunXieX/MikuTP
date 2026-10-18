package com.mikumc.mikutp.paper.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
        long rest = end - System.currentTimeMillis();
        if (rest <= 0) {
            return 0;
        }
        // Round up: truncating would report 0 while a fraction of a second is
        // still pending, letting the command through slightly early.
        return (rest + 999L) / 1000L;
    }

    /**
     * Atomically checks and starts the cooldown for {@code kind}. Returns the
     * seconds still remaining when it is active (nothing is consumed), or 0 when
     * it was started now. Two commands racing through a separate
     * {@link #remaining} check can therefore not both pass.
     */
    public long tryConsume(UUID player, Kind kind) {
        if (bypass.contains(player)) {
            return 0;
        }
        int seconds = secondsFor.applyAsInt(kind);
        if (seconds <= 0) {
            return 0; // this action has no cooldown configured
        }
        long now = System.currentTimeMillis();
        Map<Kind, Long> map = until.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        long[] blocked = {0};
        map.compute(kind, (k, end) -> {
            if (end != null && end > now) {
                blocked[0] = (end - now + 999L) / 1000L;
                return end;
            }
            return now + seconds * 1000L;
        });
        return blocked[0];
    }

    /** Clears every cooldown and the cached bypass flag (called on quit). */
    public void clear(UUID player) {
        until.remove(player);
        bypass.remove(player);
    }

    public void clear(UUID player, Kind kind) {
        if (kind == null) {
            return;
        }
        Map<Kind, Long> map = until.get(player);
        if (map != null) {
            map.remove(kind);
        }
    }
}
