package com.mikumc.mikutp.paper.service;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;

/**
 * Sound effects, keyed by vanilla sound ids. All plays go through Adventure so
 * no org.bukkit.Sound enum lookups can break across versions.
 */
public final class Effects {

    private static final Sound TELEPORT =
            Sound.sound(Key.key("entity.enderman.teleport"), Sound.Source.MASTER, 0.6f, 1.0f);
    private static final Sound WARMUP_TICK =
            Sound.sound(Key.key("block.note_block.hat"), Sound.Source.MASTER, 0.6f, 1.4f);
    private static final Sound CANCELLED =
            Sound.sound(Key.key("entity.villager.no"), Sound.Source.MASTER, 0.6f, 0.8f);
    private static final Sound REQUEST =
            Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.MASTER, 0.6f, 1.2f);
    private static final Sound CLICK =
            Sound.sound(Key.key("ui.button.click"), Sound.Source.MASTER, 0.6f, 1.0f);

    private volatile boolean enabled;

    public Effects(boolean enabled) {
        this.enabled = enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void teleport(Player player) {
        play(player, TELEPORT);
    }

    public void warmupTick(Player player) {
        play(player, WARMUP_TICK);
    }

    public void cancelled(Player player) {
        play(player, CANCELLED);
    }

    public void requestReceived(Player player) {
        play(player, REQUEST);
    }

    public void click(Player player) {
        play(player, CLICK);
    }

    private void play(Player player, Sound sound) {
        if (enabled) {
            player.playSound(sound);
        }
    }
}
