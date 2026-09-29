package com.mikumc.mikutp.common.config;

import java.util.List;

/**
 * Root configuration model. Field names are serialized in snake_case.
 * Every field has a sensible default so a partially written config file still loads.
 */
public final class MikuTPConfig {

    public int configVersion = 1;

    /** Where homes, warps, requests and player data are stored. */
    public Storage storage = new Storage();

    /** Cross-server (proxy network) mode settings. */
    public CrossServer crossServer = new CrossServer();

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

    /** Message file loaded from the plugin data folder. */
    public String languageFile = "messages_zh_cn.json";

    /** Parse PlaceholderAPI placeholders in player-visible messages. */
    public boolean parsePlaceholderApi = true;

    /** Copies every top-level section onto this instance so live references see a reload. */
    public void copyFrom(MikuTPConfig other) {
        this.configVersion = other.configVersion;
        this.storage = other.storage;
        this.crossServer = other.crossServer;
        this.teleport = other.teleport;
        this.home = other.home;
        this.warp = other.warp;
        this.tpa = other.tpa;
        this.back = other.back;
        this.wild = other.wild;
        this.dialogs = other.dialogs;
        this.languageFile = other.languageFile;
        this.parsePlaceholderApi = other.parsePlaceholderApi;
    }

    public static final class Storage {
        /** SQLITE for single servers, MYSQL for proxy networks. */
        public String type = "SQLITE";
        public String tablePrefix = "mikutp_";
        public MySql mysql = new MySql();
        /** Only used for MYSQL. SQLite always uses a single connection. */
        public int poolSize = 8;
    }

    public static final class MySql {
        public String host = "localhost";
        public int port = 3306;
        public String database = "mikutp";
        public String user = "mikutp";
        public String password = "";
        public boolean useSsl = false;
    }

    public static final class CrossServer {
        /**
         * When true, homes/tpa/back work across the proxy network.
         * Requires the Velocity-side companion plugin and a shared MYSQL database.
         */
        public boolean enabled = false;
        /**
         * Identifier of this backend server. Must match the server name used by
         * the proxy when this backend takes part in a network.
         */
        public String serverId = "server";
        /** How often the backend polls the database for cross-server events, in milliseconds. */
        public long pollIntervalMs = 1000;
        /** Seconds after which an unanswered cross-server request expires. */
        public int requestExpirySeconds = 60;
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
        /** Update the /back position when a player dies. */
        public boolean saveOnDeath = true;
        /** Update the /back position when the plugin teleports a player. */
        public boolean saveOnTeleport = true;
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
