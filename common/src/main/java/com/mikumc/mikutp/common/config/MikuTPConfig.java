package com.mikumc.mikutp.common.config;

import java.util.List;

/**
 * Root configuration model. Field names are serialized in snake_case.
 * Every field has a sensible default so a partially written config file still loads.
 */
public final class MikuTPConfig {

    public int configVersion = 2;

    /** Local SQLite storage settings. */
    public Storage storage = new Storage();

    /** Cross-server identity and request settings. */
    public CrossServer crossServer = new CrossServer();

    /** Cross-server sync settings. */
    public Sync sync = new Sync();

    /** Global teleport behaviour: warmup, cancel rules and cooldowns. */
    public Teleport teleport = new Teleport();

    /** Home feature settings. */
    public Home home = new Home();

    /** Warp feature settings (per-server scoped). */
    public Warp warp = new Warp();

    /** Teleport request (tpa) settings. */
    public Tpa tpa = new Tpa();

    /** /back settings. */
    public Back back = new Back();

    /** Random teleport (wild) settings. */
    public Wild wild = new Wild();

    /** Dialog menu settings. */
    public Dialogs dialogs = new Dialogs();

    /** Per-command switches and aliases. */
    public Commands commands = new Commands();

    /** Message file loaded from the plugin data folder. */
    public String languageFile = "messages_zh_cn.json";

    /** Parse PlaceholderAPI placeholders in player-visible messages. */
    public boolean parsePlaceholderApi = true;

    public static final class Commands {
        public CommandEntry home = new CommandEntry(List.of("homes"));
        public CommandEntry sethome = new CommandEntry(List.of());
        public CommandEntry delhome = new CommandEntry(List.of());
        public CommandEntry warp = new CommandEntry(List.of("warps"));
        public CommandEntry setwarp = new CommandEntry(List.of());
        public CommandEntry delwarp = new CommandEntry(List.of());
        public CommandEntry tpa = new CommandEntry(List.of());
        public CommandEntry tpahere = new CommandEntry(List.of());
        public CommandEntry tpaccept = new CommandEntry(List.of("tpyes"));
        public CommandEntry tpdeny = new CommandEntry(List.of("tpno"));
        public CommandEntry tpatoggle = new CommandEntry(List.of());
        public CommandEntry tpblock = new CommandEntry(List.of("tpignore"));
        public CommandEntry tpunblock = new CommandEntry(List.of());
        public CommandEntry wild = new CommandEntry(List.of("rtp"));
        public CommandEntry back = new CommandEntry(List.of());
        public CommandEntry dback = new CommandEntry(List.of());
        public CommandEntry otp = new CommandEntry(List.of());
        public CommandEntry otph = new CommandEntry(List.of());
        public CommandEntry outtp = new CommandEntry(List.of());
        public CommandEntry mtp = new CommandEntry(List.of("mikutp"));
        public CommandEntry ui = new CommandEntry(List.of());
    }

    public static final class CommandEntry {
        public boolean enabled = true;
        public List<String> aliases = List.of();

        public CommandEntry() {
        }

        public CommandEntry(List<String> aliases) {
            this.aliases = aliases;
        }
    }

    /** Copies every top-level section onto this instance so live references see a reload. */
    public void copyFrom(MikuTPConfig other) {
        this.configVersion = other.configVersion;
        this.storage = other.storage;
        this.crossServer = other.crossServer;
        this.sync = other.sync;
        this.teleport = other.teleport;
        this.home = other.home;
        this.warp = other.warp;
        this.tpa = other.tpa;
        this.back = other.back;
        this.wild = other.wild;
        this.dialogs = other.dialogs;
        this.commands = other.commands;
        this.languageFile = other.languageFile;
        this.parsePlaceholderApi = other.parsePlaceholderApi;
    }

    public static final class Storage {
        public String tablePrefix = "mikutp_";
    }

    public static final class CrossServer {
        /**
         * Identifier of this backend server: the origin tag on sync events.
         * Should match the proxy server name when this backend takes part in
         * a network.
         */
        public String serverId = "server";
    }

    public static final class Sync {
        /** NONE = single server (no proxy messaging); VELOCITY = local SQLite plus the Velocity hub. */
        public String mode = "NONE";
        /** True when cross-server features are switched on. */
        public boolean enabled() {
            return "VELOCITY".equalsIgnoreCase(mode);
        }
    }

    public static final class Teleport {
        /** Seconds to stand still before a teleport completes. 0 disables warmup. */
        public int warmupSeconds = 3;
        /** Blocks of movement that cancel the warmup. 0 means any movement cancels. */
        public double moveThresholdBlocks = 0.0;
        /** Taking damage cancels the pending teleport. */
        public boolean cancelOnDamage = true;
        /** Play sounds for warmup ticks, cancellations and arrivals. */
        public boolean sounds = true;
        public Cooldowns cooldowns = new Cooldowns();
    }

    public static final class Cooldowns {
        public int home = 5;
        public int warp = 5;
        public int tpa = 10;
        public int wild = 60;
        public int back = 10;
        public int dback = 30;
    }

    public static final class Home {
        /** Fallback home limit; overridden by the mikutp.homes.&lt;n&gt; permission. */
        public int defaultLimit = 5;
        public String namePattern = "[a-zA-Z0-9_-]{1,32}";
    }

    public static final class Warp {
        public boolean enabled = true;
    }

    public static final class Tpa {
        public boolean enabled = true;
        /** Seconds before an unanswered request expires. */
        public int requestExpirySeconds = 60;
        /** Minutes applied by the "block this player" dialog button. */
        public int blockDurationMinutes = 10;
    }

    public static final class Back {
        public boolean enabled = true;
        /** Update the /back position when the plugin teleports a player. */
        public boolean saveOnTeleport = true;
        /** Enable /dback (return to the last death location). */
        public boolean deathEnabled = true;
        /** Update the /dback position when a player dies. */
        public boolean deathSave = true;
    }

    public static final class Wild {
        public boolean enabled = true;
        /** CURRENT uses the player's world; LIST picks randomly from {@link #worlds}. */
        public String worldMode = "CURRENT";
        public List<String> worlds = List.of("world");
        /** Minimum distance from the search center, in blocks. */
        public int minRadius = 500;
        /** Maximum distance from the search center, in blocks. */
        public int maxRadius = 5000;
        /** SPAWN centers the search on the world spawn; ZERO uses 0,0. */
        public String center = "SPAWN";
        /** Random spots examined before giving up. */
        public int maxAttempts = 10;
        /** Vanilla biome tags that are rejected (resource-poor or hostile terrain). */
        public List<String> blockedBiomeTags = List.of("is_ocean", "is_deep_ocean", "is_river", "is_beach");
        /** Explicit biome keys rejected on top of the tags, e.g. "minecraft:stony_shore". */
        public List<String> blockedBiomes = List.of();
        /** Only land on solid, non-hazardous ground with headroom. */
        public boolean requireSafeLanding = true;
    }

    public static final class Dialogs {
        /** Master switch; when false every menu falls back to clickable chat. */
        public boolean enabled = true;
        /** Entries per page for home/warp list dialogs. */
        public int listPageSize = 12;
    }
}
