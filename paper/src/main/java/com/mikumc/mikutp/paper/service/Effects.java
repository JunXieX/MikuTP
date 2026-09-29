package com.mikumc.mikutp.paper.service;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;

/**
 * Sound effects, keyed by vanilla sound ids. All plays go through Adventure so
 * no org.bukkit.Sound enum lookups can break across versions.
 */
public final class Effects {

    private volatile boolean enabled;

    public Effects(boolean enabled) {
        this.enabled = enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void teleport(Player player) {
        play(player, "entity.enderman.teleport", 1.0f);
    }

    public void warmupTick(Player player) {
        play(player, "block.note_block.hat", 1.4f);
    }

    public void cancelled(Player player) {
        play(player, "entity.villager.no", 0.8f);
    }

    public void requestReceived(Player player) {
        play(player, "entity.experience_orb.pickup", 1.2f);
    }

    public void click(Player player) {
        play(player, "ui.button.click", 1.0f);
    }

    public void play(Player player, String soundKey, float pitch) {
        if (!enabled) {
            return;
        }
        player.playSound(Sound.sound(Key.key(soundKey), Sound.Source.MASTER, 0.6f, pitch));
    }
}
