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
    private boolean papiAvailable;

    @Override
    public void onEnable() {
        registerPermissions();
        // softdepend loads PlaceholderAPI before us when present; isPluginEnabled also
        // covers the case where it exists but failed to enable.
        papiAvailable = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        try {
            loadConfiguration(true);
        } catch (IOException e) {
            getSLF4JLogger().error("Failed to load configuration, disabling plugin", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            database = StorageFactory.create(getDataFolder().toPath(), config);
            database.init();
        } catch (Exception e) {
            getSLF4JLogger().error("Failed to open local storage, disabling plugin", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        tasks = new Tasks(this);
        effects = new Effects(config.teleport.sounds);
        cooldowns = new CooldownManager(this::cooldownSeconds);
        syncBus = config.sync.enabled()
                ? new VelocitySyncBus(this, tasks, config.sync.token)
                : new LoopbackSyncBus();
        network = new NetworkService(this, tasks, messages, config);
        profiles = new ProfileService(this, tasks, database, syncBus, config.crossServer.serverId);
        teleports = new TeleportService(this, tasks, config, database, messages, effects,
                new WarmupManager(tasks, messages, effects, () -> config.teleport.warmupSeconds),
                network, profiles, cooldowns, syncBus);
        homeService = new HomeService(this, tasks, config, database, messages, cooldowns, teleports, syncBus);
        warpService = new WarpService(this, tasks, config, database, messages, cooldowns, teleports);
        requestService = new RequestService(this, tasks, config, database, messages, effects, cooldowns,
                teleports, profiles, network, syncBus);
        wildService = new WildService(this, tasks, config, messages, cooldowns, teleports);
        coordinator = new SyncCoordinator(this, tasks, config, database, syncBus,
                homeService, profiles, teleports, requestService);

        if (config.sync.enabled() && config.sync.token.isBlank()) {
            getSLF4JLogger().error("sync.mode=VELOCITY 但 sync.token 为空：所有跨服同步消息都会被拒绝。"
                    + "请在 config.yml 与代理端 MikuTP 配置里设置完全相同的 token。");
        }

        dialogs = new DialogFactory(tasks, messages, () -> config.dialogs.listPageSize);
        chats = new ChatMenus(messages);
        requestService.setShowRequestHandler(this::showRequest);
        network.setEventHandler(coordinator::onEvent);
        network.setPendingHandler(teleports::bufferArrival);
        warpService.load();
        requestService.start();
        coordinator.start();

        new CommandRegistry(this, messages, cooldowns, homeService, warpService, requestService,
                wildService, teleports, dialogs, chats, this::reloadAll, coordinator::resync,
                this::infoLine, () -> config.dialogs.enabled, config).register();

        getServer().getPluginManager().registerEvents(
                new PlayerLifecycle(profiles, homeService, requestService, teleports, cooldowns, coordinator), this);
        getServer().getPluginManager().registerEvents(
                new WarmupGuard(teleports.warmups(), config), this);

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
        if (requestService != null) {
            requestService.shutdown();
        }
        if (coordinator != null) {
            coordinator.shutdown();
        }
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
        if (syncBus != null) {
            try {
                syncBus.close();
            } catch (Exception e) {
                getSLF4JLogger().warn("Failed to close sync bus cleanly", e);
            }
        }
        if (network != null) {
            network.shutdown();
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
        Player target = request.targetUuid == null ? null
                : Bukkit.getPlayer(UUID.fromString(request.targetUuid));
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

    private void reloadAll() {
        try {
            loadConfiguration(false);
            effects.setEnabled(config.teleport.sounds);
            warpService.load();
            getSLF4JLogger().info("MikuTP reloaded (sync mode, server-id and storage changes need a restart)");
        } catch (IOException e) {
            getSLF4JLogger().error("Reload failed: {}", e.getMessage());
        }
    }

    /** Reads or creates config.yml and the language file. */
    private void loadConfiguration(boolean initial) throws IOException {
        Path configFile = getDataFolder().toPath().resolve("config.yml");
        MikuTPConfig loaded;
        try {
            loaded = ConfigIO.loadOrCreate(configFile, readResource("config.yml"), MikuTPConfig.class);
        } catch (Exception e) {
            // A broken YAML must not take the whole plugin down: fall back to defaults.
            getSLF4JLogger().error("config.yml 解析失败，已回退到内置默认配置：{}", e.toString());
            loaded = new MikuTPConfig();
        }
        if (config == null) {
            config = loaded;
        } else {
            config.copyFrom(loaded);
        }
        if (loaded.configVersion != MikuTPConfig.CURRENT_VERSION) {
            getSLF4JLogger().info("config.yml 的 config_version 为 {}（本版本为 {}），建议对照内置默认配置核对新增项",
                    loaded.configVersion, MikuTPConfig.CURRENT_VERSION);
        }
        MessageBundle bundle = new MessageBundle(readResource("messages_zh_cn.json"));
        bundle.loadOverrides(getDataFolder().toPath().resolve(config.languageFile));
        if (messages == null) {
            messages = new MessageService(bundle, config.parsePlaceholderApi, papiAvailable);
        } else {
            messages.reload(bundle, config.parsePlaceholderApi);
        }
        if (initial && !config.dialogs.enabled) {
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
