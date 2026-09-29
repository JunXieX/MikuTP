package com.mikumc.mikutp.paper;

import com.mikumc.mikutp.common.config.ConfigIO;
import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.common.data.Database;
import com.mikumc.mikutp.common.message.MessageBundle;
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
import com.mikumc.mikutp.paper.service.Tasks;
import com.mikumc.mikutp.paper.service.TeleportService;
import com.mikumc.mikutp.paper.service.WarpService;
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

/** Plugin entry point; wires storage, services, commands, listeners and hooks. */
public final class MikuTPPlugin extends JavaPlugin {

    private MikuTPConfig config;
    private MessageService messages;
    private Effects effects;
    private CooldownManager cooldowns;
    private Database database;
    private Tasks tasks;
    private ProfileService profiles;
    private NetworkService network;
    private TeleportService teleports;
    private HomeService homeService;
    private WarpService warpService;
    private RequestService requestService;
    private WildService wildService;
    private DialogFactory dialogs;
    private ChatMenus chats;
    private MikuTPExpansion expansion;
    private boolean papiAvailable;

    @Override
    public void onEnable() {
        papiAvailable = getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
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
            getSLF4JLogger().error("Failed to open storage, disabling plugin", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        tasks = new Tasks(this);
        effects = new Effects(config.teleport.sounds);
        cooldowns = new CooldownManager(this::cooldownSeconds);
        network = new NetworkService(this, tasks, messages);
        network.setEnabled(config.crossServer.enabled);
        profiles = new ProfileService(this, tasks, database);
        teleports = new TeleportService(this, tasks, config, database, messages, effects,
                new com.mikumc.mikutp.paper.service.WarmupManager(tasks, messages, effects, config.teleport.warmupSeconds),
                network);
        homeService = new HomeService(this, tasks, config, database, messages, cooldowns, teleports);
        warpService = new WarpService(this, tasks, config, database, messages, cooldowns, teleports);
        requestService = new RequestService(this, tasks, config, database, messages, effects, cooldowns,
                teleports, profiles, network);
        wildService = new WildService(this, tasks, config, messages, cooldowns, teleports);

        dialogs = new DialogFactory(tasks, messages, config.dialogs.listPageSize);
        chats = new ChatMenus(messages);
        requestService.setShowRequestHandler(this::showRequest);
        network.setRequestHandler(requestService::deliverRemote);
        network.setTpGoHandler(requestService::deliverTpGo);
        warpService.load();
        requestService.start(config.crossServer.enabled);

        new CommandRegistry(this, messages, cooldowns, homeService, warpService, requestService,
                wildService, teleports, dialogs, chats, this::reloadAll, this::infoLine,
                () -> config.dialogs.enabled).register();

        getServer().getPluginManager().registerEvents(
                new PlayerLifecycle(profiles, homeService, requestService, teleports, cooldowns), this);
        getServer().getPluginManager().registerEvents(
                new WarmupGuard(teleports.warmups(), config.teleport.moveThresholdBlocks, config.teleport.cancelOnDamage), this);

        if (papiAvailable) {
            expansion = new MikuTPExpansion(this, homeService, warpService, profiles, cooldowns,
                    config.crossServer.serverId);
            expansion.register();
        }

        getSLF4JLogger().info("MikuTP ready: mode={}, server-id={}, storage={}",
                config.crossServer.enabled ? "cross-server" : "local",
                config.crossServer.serverId, config.storage.type.toUpperCase());
    }

    @Override
    public void onDisable() {
        if (requestService != null) {
            requestService.shutdown();
        }
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
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
        };
    }

    private String infoLine() {
        return (config.crossServer.enabled ? "cross-server" : "local")
                + " | server=" + config.crossServer.serverId
                + " | storage=" + config.storage.type.toUpperCase()
                + " | dialogs=" + (config.dialogs.enabled ? "on" : "off");
    }

    private void reloadAll() {
        try {
            loadConfiguration(false);
            effects.setEnabled(config.teleport.sounds);
            network.setEnabled(config.crossServer.enabled);
            warpService.load();
            getSLF4JLogger().info("MikuTP reloaded (server-id and storage changes need a restart)");
        } catch (IOException e) {
            getSLF4JLogger().error("Reload failed: {}", e.getMessage());
        }
    }

    /** Reads or creates config.json and the language file. */
    private void loadConfiguration(boolean initial) throws IOException {
        Path configFile = getDataFolder().toPath().resolve("config.json");
        MikuTPConfig loaded = ConfigIO.loadOrCreate(configFile, readResource("config.json"), MikuTPConfig.class);
        if (config == null) {
            config = loaded;
        } else {
            config.copyFrom(loaded);
        }
        MessageBundle bundle = new MessageBundle(readResource("messages_zh_cn.json"));
        bundle.loadOverrides(getDataFolder().toPath().resolve(config.languageFile));
        if (messages == null) {
            messages = new MessageService(this, bundle, config.parsePlaceholderApi, papiAvailable);
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
