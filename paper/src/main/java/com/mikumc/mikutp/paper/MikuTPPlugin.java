package com.mikumc.mikutp.paper;

import com.mikumc.mikutp.common.config.ConfigIO;
import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.message.MessageBundle;
import com.mikumc.mikutp.common.sync.LoopbackSyncBus;
import com.mikumc.mikutp.common.sync.SyncBus;
import com.mikumc.mikutp.paper.service.VelocitySyncBus;
import com.mikumc.mikutp.paper.command.CommandRegistry;
import com.mikumc.mikutp.paper.dialog.ChatMenus;
import com.mikumc.mikutp.paper.dialog.DialogFactory;
import com.mikumc.mikutp.paper.hook.MikuTPExpansion;
import com.mikumc.mikutp.paper.listener.PlayerLifecycle;
import com.mikumc.mikutp.paper.listener.WarmupGuard;
import com.mikumc.mikutp.paper.service.CooldownManager;
import com.mikumc.mikutp.paper.service.Effects;
import com.mikumc.mikutp.paper.service.HomeService;
import com.mikumc.mikutp.paper.service.MessageService;
import com.mikumc.mikutp.paper.service.NetworkService;
import com.mikumc.mikutp.paper.service.OnlinePlayers;
import com.mikumc.mikutp.paper.service.ProfileService;
import com.mikumc.mikutp.paper.service.RequestService;
import com.mikumc.mikutp.paper.service.StorageFactory;
import com.mikumc.mikutp.paper.service.SyncCoordinator;
import com.mikumc.mikutp.paper.service.Tasks;
import com.mikumc.mikutp.paper.service.TeleportService;
import com.mikumc.mikutp.paper.service.WarpService;
import com.mikumc.mikutp.paper.service.WarmupManager;
import com.mikumc.mikutp.paper.service.WildService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Plugin entry point; wires storage, the sync bus, services, commands and hooks. */
public final class MikuTPPlugin extends JavaPlugin {

    private MikuTPConfig config;
    private MessageService messages;
    private Effects effects;
    private CooldownManager cooldowns;
    private Database database;
    private Tasks tasks;
    private SyncBus syncBus;
    private OnlinePlayers onlinePlayers;
    private NetworkService network;
    private ProfileService profiles;
    private TeleportService teleports;
    private HomeService homeService;
    private WarpService warpService;
    private RequestService requestService;
    private WildService wildService;
    private DialogFactory dialogs;
    private ChatMenus chats;
    private SyncCoordinator coordinator;
    private MikuTPExpansion expansion;
    private PlayerLifecycle lifecycle;
    private boolean papiAvailable;

    @Override
    public void onEnable() {
        registerPermissions();
        // softdepend loads PlaceholderAPI before us when present; isPluginEnabled also
        // covers the case where it exists but failed to enable.
        papiAvailable = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        // Created before the message layer: MessageService needs the scheduler to
        // deliver a message onto the player's thread when its caller is an async one.
        tasks = new Tasks(this);
        try {
            config = parseConfig();
            messages = createMessageService(config);
        } catch (Exception e) {
            // Never continue on built-in defaults: a changed table prefix would open
            // a second database and split the player data. Fail loudly instead.
            getSLF4JLogger().error(
                    "Failed to load {}; the plugin will not start until the file is fixed "
                            + "(built-in defaults are intentionally not used as a fallback).",
                    getDataFolder().toPath().resolve("config.yml"), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logConfigNotes(config);
        logValidationProblems(config);

        try {
            database = StorageFactory.create(getDataFolder().toPath(), config);
            database.init();
        } catch (Exception e) {
            getSLF4JLogger().error("Failed to open local storage, disabling plugin", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        effects = new Effects(config.teleport.sounds);
        cooldowns = new CooldownManager(this::cooldownSeconds);
        onlinePlayers = new OnlinePlayers();
        syncBus = config.sync.enabled()
                ? new VelocitySyncBus(this, tasks, config.sync.token, onlinePlayers)
                : new LoopbackSyncBus();
        network = new NetworkService(this, tasks, messages, config);
        profiles = new ProfileService(this, tasks, database, syncBus, onlinePlayers, config.crossServer.serverId);
        teleports = new TeleportService(this, tasks, config, database, messages, effects,
                new WarmupManager(tasks, messages, effects, () -> config.teleport.warmupSeconds),
                network, profiles, cooldowns, syncBus, onlinePlayers);
        homeService = new HomeService(this, tasks, config, database, messages, cooldowns, teleports,
                syncBus, onlinePlayers);
        warpService = new WarpService(this, tasks, config, database, messages, cooldowns, teleports);
        requestService = new RequestService(this, tasks, config, database, messages, effects, cooldowns,
                teleports, profiles, network, syncBus, onlinePlayers);
        wildService = new WildService(tasks, config, messages, cooldowns, teleports, onlinePlayers);
        coordinator = new SyncCoordinator(this, tasks, config, database, syncBus,
                homeService, profiles, teleports, requestService);

        if (config.sync.enabled() && config.sync.token.isBlank()) {
            getSLF4JLogger().error("sync.mode=VELOCITY 但 sync.token 为空：所有跨服同步消息都会被拒绝。"
                    + "请在 config.yml 与代理端 MikuTP 配置里设置完全相同的 token。");
        }

        dialogs = new DialogFactory(tasks, messages, () -> config.dialogs.listPageSize,
                () -> config.tpa.blockDurationMinutes);
        chats = new ChatMenus(messages);
        requestService.setShowRequestHandler(this::showRequest);
        network.setEventHandler(coordinator::onEvent);
        network.setPendingHandler(teleports::bufferArrival);
        warpService.load();
        requestService.start();
        coordinator.start();

        new CommandRegistry(this, messages, homeService, warpService, requestService,
                wildService, teleports, dialogs, chats, onlinePlayers, tasks, this::reloadAll, coordinator::resync,
                this::infoLine, () -> config.dialogs.enabled, config).register();

        lifecycle = new PlayerLifecycle(profiles, homeService, requestService, teleports, cooldowns,
                coordinator, onlinePlayers);
        getServer().getPluginManager().registerEvents(lifecycle, this);
        getServer().getPluginManager().registerEvents(
                new WarmupGuard(teleports.warmups(), config), this);
        // A /reload or late enable finds players already online; rebuild their full
        // runtime state (profile cache, cooldown bypass, home caches), not just the
        // registry, because no PlayerJoinEvent fires for them.
        refreshOnlinePlayers();

        if (papiAvailable) {
            expansion = new MikuTPExpansion(this, homeService, warpService, profiles, cooldowns,
                    config.crossServer.serverId);
            expansion.register();
        }

        getSLF4JLogger().info("MikuTP ready: mode={}, server-id={}, storage=sqlite",
                config.sync.enabled() ? "cross-server (velocity)" : "local",
                config.crossServer.serverId);
    }

    @Override
    public void onDisable() {
        // Refuse new background work first, then give the in-flight database tasks a
        // bounded chance to finish before the pool closes: otherwise those writes
        // fail against a closed Hikari pool during shutdown (R2-12).
        if (tasks != null) {
            tasks.beginShutdown();
            if (!tasks.awaitQuiescence(500L)) {
                getSLF4JLogger().warn("Some background database work did not finish within 500ms of shutdown");
            }
        }
        // Stop producers first, then transport, then storage: closing the bus
        // before its flush loop is done would push messages onto a channel that
        // is about to be unregistered, and closing storage first would fail any
        // write still draining from the coordinator.
        if (requestService != null) {
            requestService.shutdown();
        }
        if (wildService != null) {
            wildService.shutdown();
        }
        if (teleports != null) {
            teleports.warmups().shutdown();
        }
        if (coordinator != null) {
            coordinator.close();
        }
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
        if (network != null) {
            network.shutdown();
        }
        if (syncBus != null) {
            try {
                syncBus.close();
            } catch (Exception e) {
                getSLF4JLogger().warn("Failed to close sync bus cleanly", e);
            }
        }
        if (database != null) {
            try {
                database.close();
            } catch (Exception e) {
                getSLF4JLogger().warn("Failed to close storage cleanly", e);
            }
        }
    }

    // ------------------------------------------------------------------ internals

    /** Registers every permission node with its Bukkit default. Belt and braces:
     * paper-plugin.yml also declares them; a duplicate registration is ignored. */
    private void registerPermissions() {
        var pm = getServer().getPluginManager();
        for (PermissionNodes.Node node : PermissionNodes.NODES) {
            try {
                pm.addPermission(new org.bukkit.permissions.Permission(node.node(), node.description(),
                        org.bukkit.permissions.PermissionDefault.valueOf(node.def().toUpperCase(java.util.Locale.ROOT))));
            } catch (IllegalArgumentException ignored) {
                // Already declared by paper-plugin.yml.
            }
        }
    }

    private void showRequest(com.mikumc.mikutp.common.data.TpRequest request) {
        // Runs on the target's entity thread; resolve through the registry so no
        // Bukkit player-list lookup happens on a region thread.
        UUID targetId = request.targetUuid == null ? null : parseUuid(request.targetUuid);
        Player target = targetId == null ? null : onlinePlayers.byId(targetId);
        if (target == null) {
            return;
        }
        if (config.dialogs.enabled) {
            dialogs.showRequest(target, request, requestService);
        } else {
            messages.send(target, request.type == com.mikumc.mikutp.common.data.TpRequest.Type.COME
                    ? "tpa.received-here-chat" : "tpa.received-chat", "player", request.requesterName);
            messages.send(target, "tpa.hint-chat", "id", request.id);
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int cooldownSeconds(CooldownManager.Kind kind) {
        return switch (kind) {
            case HOME -> config.teleport.cooldowns.home;
            case WARP -> config.teleport.cooldowns.warp;
            case TPA -> config.teleport.cooldowns.tpa;
            case WILD -> config.teleport.cooldowns.wild;
            case BACK -> config.teleport.cooldowns.back;
            case DBACK -> config.teleport.cooldowns.dback;
        };
    }

    private String infoLine() {
        return (config.sync.enabled() ? "cross-server (velocity)" : "local")
                + " | server=" + config.crossServer.serverId
                + " | storage=sqlite"
                + " | dialogs=" + (config.dialogs.enabled ? "on" : "off");
    }

    /**
     * Reloads config.yml and the language file. Parsing happens on the async pool;
     * the new configuration is only applied after it parsed successfully, so a
     * broken file always leaves the running configuration untouched.
     *
     * @param done receives {@code true} when the reload was applied, {@code false}
     *             when it failed (the caller reports the result to the sender)
     */
    private void reloadAll(java.util.function.Consumer<Boolean> done) {
        // Transport and storage settings are read once at startup by the running
        // services, so a reload cannot apply them. Parse first, then reject those
        // sections explicitly instead of letting config.yml claim new values.
        String oldMode = String.valueOf(config.sync.mode);
        String oldToken = String.valueOf(config.sync.token);
        String oldServerId = String.valueOf(config.crossServer.serverId);
        String oldPrefix = String.valueOf(config.storage.tablePrefix);
        String oldLanguage = String.valueOf(config.languageFile);
        if (tasks.isShuttingDown()) {
            // A refused async task would never answer the command callback.
            done.accept(false);
            return;
        }
        tasks.async(() -> {
            MikuTPConfig parsed;
            MessageBundle bundle;
            try {
                parsed = parseConfig();
                parsed.sync.mode = oldMode;
                parsed.sync.token = oldToken;
                parsed.crossServer.serverId = oldServerId;
                parsed.storage.tablePrefix = oldPrefix;
                if (!oldLanguage.equals(parsed.languageFile)) {
                    getSLF4JLogger().warn("language-file changed in config.yml; the running instance keeps "
                            + "{} until the server restarts", oldLanguage);
                    parsed.languageFile = oldLanguage;
                }
                // Semantic problems are reported, not repaired: the operator's file
                // stays authoritative and every offending value is named in the log.
                logValidationProblems(parsed);
                bundle = loadBundle(parsed.languageFile);
                // Validate the bundle before anything live is swapped: constructing the
                // service parses the MiniMessage prefix exactly like MessageService.reload
                // does, so a malformed prefix rejects the reload instead of leaving the
                // config applied while the message layer still holds the old prefix.
                new MessageService(tasks, bundle, parsed.parsePlaceholderApi, papiAvailable);
            } catch (Exception e) {
                // Keep the running configuration intact and report the failure.
                getSLF4JLogger().error("Reload failed; the running configuration was kept: {}", e.toString());
                done.accept(false);
                return;
            }
            // Apply on the global region thread so command/region readers never see
            // a configuration that has only been partially copied.
            try {
                tasks.global(() -> {
                    try {
                        config.copyFrom(parsed);
                        messages.reload(bundle, parsed.parsePlaceholderApi);
                        effects.setEnabled(parsed.teleport.sounds);
                        warpService.reload();
                        refreshOnlinePlayersNow();
                        getSLF4JLogger().info("MikuTP reloaded (sync mode, server-id, table prefix and "
                                + "language file need a restart)");
                        done.accept(true);
                    } catch (Exception e) {
                        // The bundle was pre-validated, so this is not expected; report it
                        // instead of leaving the command waiting for a callback forever.
                        getSLF4JLogger().error("Reload parsed but could not be applied", e);
                        done.accept(false);
                    }
                });
            } catch (RuntimeException e) {
                // A disabling plugin cannot schedule region work: answer the command
                // instead of leaving it without feedback (R3-06).
                getSLF4JLogger().error("Reload could not be scheduled; the running configuration was kept", e);
                done.accept(false);
            }
        });
    }

    /**
     * Rebuilds the per-player runtime state of everyone already online. A server
     * reload (or a late enable) never fires PlayerJoinEvent, so without this the
     * profile toggle cache, cooldown bypass and home caches would stay empty.
     */
    private void refreshOnlinePlayers() {
        tasks.global(this::refreshOnlinePlayersNow);
    }

    /** Must run on the global region thread (it reads the player view). */
    private void refreshOnlinePlayersNow() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.add(player);
            if (lifecycle != null) {
                tasks.entity(player, () -> lifecycle.refresh(player));
            }
        }
    }

    /** Parses config.yml, creating the commented default on first start. */
    private MikuTPConfig parseConfig() throws IOException {
        Path configFile = getDataFolder().toPath().resolve("config.yml");
        if (Files.notExists(configFile)) {
            return ConfigIO.loadOrCreate(configFile, readResource("config.yml"), MikuTPConfig.class);
        }
        // Strict parse: a malformed file throws instead of silently keeping defaults.
        return ConfigIO.loadStrict(configFile, MikuTPConfig.class);
    }

    /** Bundled message defaults plus the configured override file. */
    private MessageBundle loadBundle(String languageFile) throws IOException {
        Path overrides = getDataFolder().toPath().resolve(languageFile);
        MessageBundle bundle = new MessageBundle(readResource("messages_zh_cn.json"));
        if (!bundle.loadOverrides(overrides)) {
            // The bundled defaults stay active; surface the broken override instead
            // of silently rendering default texts for every key.
            getSLF4JLogger().warn("Language file {} could not be parsed; bundled default messages are used",
                    overrides);
        }
        return bundle;
    }

    private MessageService createMessageService(MikuTPConfig cfg) throws IOException {
        return new MessageService(tasks, loadBundle(cfg.languageFile), cfg.parsePlaceholderApi, papiAvailable);
    }

    /** Logs semantic config problems (bad pattern, negative cooldowns, blank token, ...).
     * Parsing already succeeded, so these are reported and the values used as written. */
    private void logValidationProblems(MikuTPConfig cfg) {
        List<String> problems = cfg.validationProblems();
        for (String problem : problems) {
            getSLF4JLogger().warn("config.yml: {}", problem);
        }
        if (!problems.isEmpty()) {
            getSLF4JLogger().warn("config.yml has {} problem(s) listed above; the values are used as written "
                    + "(fix the file and run /mikutp reload)", problems.size());
        }
    }

    /** First-start notes: config version and the dialog fallback. */
    private void logConfigNotes(MikuTPConfig cfg) {
        if (cfg.configVersion != MikuTPConfig.CURRENT_VERSION) {
            getSLF4JLogger().info("config.yml 的 config_version 为 {}（本版本为 {}），建议对照内置默认配置核对新增项",
                    cfg.configVersion, MikuTPConfig.CURRENT_VERSION);
        }
        if (!cfg.dialogs.enabled) {
            getSLF4JLogger().info("Dialog menus are disabled; falling back to clickable chat menus");
        }
    }

    private String readResource(String name) throws IOException {
        try (InputStream in = getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Bundled resource missing: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
