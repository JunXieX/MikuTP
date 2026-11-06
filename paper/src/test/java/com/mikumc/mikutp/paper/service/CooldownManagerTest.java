package com.mikumc.mikutp.paper.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A quit must not hand the player a free cooldown reset. */
class CooldownManagerTest {

    @Test
    void quitKeepsRunningCooldowns() {
        CooldownManager manager = new CooldownManager(kind -> 60);
        UUID player = UUID.randomUUID();

        assertEquals(0L, manager.tryConsume(player, CooldownManager.Kind.HOME));
        manager.clear(player); // PlayerLifecycle.onQuit

        assertTrue(manager.tryConsume(player, CooldownManager.Kind.HOME) > 0,
                "a running cooldown must survive the quit");
    }
}
