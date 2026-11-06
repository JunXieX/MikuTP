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
    /** Players whose cooldowns are tracked; a quit keeps live ones, so it is swept. */
    private static final int MAX_TRACKED_PLAYERS = 4096;
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

    /**
     * Monotonic milliseconds. Cooldowns are pure in-memory state, so a wall-clock
     * step (NTP correction, VM snapshot restore) must not be able to lock a player
     * out until the clock catches up again.
     */
    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Drops tracked players whose cooldowns all expired; only runs past the cap. */
    private void pruneExpired() {
        long at = now();
        until.entrySet().removeIf(entry -> {
            Map<Kind, Long> map = entry.getValue();
            map.entrySet().removeIf(kind -> kind.getValue() <= at);
            return map.isEmpty();
        });
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
        long rest = end - now();
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
        if (until.size() > MAX_TRACKED_PLAYERS) {
            pruneExpired();
        }
        long at = now();
        Map<Kind, Long> map = until.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
        long[] blocked = {0};
        map.compute(kind, (k, end) -> {
            if (end != null && end > at) {
                blocked[0] = (end - at + 999L) / 1000L;
                return end;
            }
            return at + seconds * 1000L;
        });
        return blocked[0];
    }

    /**
     * Drops the cached bypass flag and whatever already expired, keeping cooldowns
     * that are still running: wiping them on quit would let a player reset every
     * cooldown by reconnecting. A player who quits with a live cooldown keeps a small
     * entry until it expires; the map is swept past its cap.
     */
    public void clear(UUID player) {
        bypass.remove(player);
        Map<Kind, Long> map = until.get(player);
        if (map == null) {
            return;
        }
        long at = now();
        map.entrySet().removeIf(entry -> entry.getValue() <= at);
        if (map.isEmpty()) {
            until.remove(player);
        }
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
