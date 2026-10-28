package com.mikumc.mikutp.paper.command;

import com.mikumc.mikutp.common.config.MikuTPConfig;
import com.mikumc.mikutp.paper.dialog.ChatMenus;
import com.mikumc.mikutp.paper.dialog.DialogFactory;
import com.mikumc.mikutp.paper.service.HomeService;
import com.mikumc.mikutp.paper.service.MessageService;
import com.mikumc.mikutp.paper.service.OnlinePlayers;
import com.mikumc.mikutp.paper.service.RequestService;
import com.mikumc.mikutp.paper.service.Tasks;
import com.mikumc.mikutp.paper.service.TeleportService;
import com.mikumc.mikutp.paper.service.WarpService;
import com.mikumc.mikutp.paper.service.WildService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * All Brigadier commands. Every body only reads thread-safe caches and hands
 * real work to services, which hop onto the right schedulers themselves.
 */
public final class CommandRegistry {

    /**
     * Runs a configuration reload and reports the outcome through the callback.
     * The callback may run on any thread (the reload itself is asynchronous);
     * the reload command hops back to the global region before messaging.
     */
    @FunctionalInterface
    public interface ReloadAction {
        void reload(java.util.function.Consumer<Boolean> done);
    }

    /** How long /warp waits for the warp table's first asynchronous load. */
    private static final int WARP_LOAD_POLL_MS = 50;
    private static final int WARP_LOAD_MAX_POLLS = 20;

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final HomeService homeService;
    private final WarpService warpService;
    private final RequestService requestService;
    private final WildService wildService;
    private final TeleportService teleports;
    private final DialogFactory dialogs;
    private final ChatMenus chats;
    private final OnlinePlayers onlinePlayers;
    private final Tasks tasks;
    private final ReloadAction reloadAction;
    private final java.util.function.BooleanSupplier resyncAction;
    private final java.util.function.Supplier<String> infoSupplier;
    private final java.util.function.BooleanSupplier dialogsEnabledSupplier;
    private final MikuTPConfig config;

    public CommandRegistry(JavaPlugin plugin, MessageService messages,
                           HomeService homeService, WarpService warpService, RequestService requestService,
                           WildService wildService, TeleportService teleports, DialogFactory dialogs,
                           ChatMenus chats, OnlinePlayers onlinePlayers, Tasks tasks,
                           ReloadAction reloadAction, java.util.function.BooleanSupplier resyncAction,
                           java.util.function.Supplier<String> infoSupplier,
                           java.util.function.BooleanSupplier dialogsEnabledSupplier,
                           MikuTPConfig config) {
        this.plugin = plugin;
        this.messages = messages;
        this.homeService = homeService;
        this.warpService = warpService;
        this.requestService = requestService;
        this.wildService = wildService;
        this.teleports = teleports;
        this.dialogs = dialogs;
        this.chats = chats;
        this.onlinePlayers = onlinePlayers;
        this.tasks = tasks;
        this.reloadAction = reloadAction;
        this.resyncAction = resyncAction;
        this.infoSupplier = infoSupplier;
        this.dialogsEnabledSupplier = dialogsEnabledSupplier;
        this.config = config;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var registrar = event.registrar();
            // 命令可按 config.yml 的 commands 段单独关闭或改别名；绝不覆盖原版指令。
            // Literals owned by this plugin: an alias must never shadow one of them,
            // and no alias may be used twice (the registrar rejects duplicates).
            java.util.Set<String> reserved = new java.util.LinkedHashSet<>(List.of(
                    "home", "sethome", "delhome", "warp", "setwarp", "delwarp",
                    "tpa", "tpahere", "tpaccept", "tpdeny", "tpatoggle", "tpblock",
                    "tpunblock", "wild", "back", "dback", "otp", "otph", "outtp",
                    "mikutp-ui", "mtp"));
            java.util.function.Consumer<Registration> reg = r -> {
                var entry = entry(r.key());
                String literal = r.node().getLiteral();
                if (entry != null && !entry.enabled) {
                    plugin.getSLF4JLogger().info("Command /{} disabled in config.yml", literal);
                    return;
                }
                List<String> aliases = entry != null && entry.aliases != null ? entry.aliases : r.defaultAliases();
                List<String> safe = safeAliases(literal, aliases, reserved);
                try {
                    registrar.register(r.node(), r.description(), safe);
                } catch (RuntimeException e) {
                    // One conflicting alias must not abort the whole lifecycle event
                    // and take every other command down with it.
                    plugin.getSLF4JLogger().warn("Could not register /{} (alias conflict?): {}",
                            literal, e.getMessage());
                }
            };
            reg.accept(new Registration("home", home(), "传送到你的家", List.of("homes")));
            reg.accept(new Registration("sethome", sethome(), "设置一个家", List.of()));
            reg.accept(new Registration("delhome", delhome(), "删除一个家", List.of()));
            reg.accept(new Registration("warp", warp(), "传送到服务器地标", List.of("warps")));
            reg.accept(new Registration("setwarp", setwarp(), "创建服务器地标", List.of()));
            reg.accept(new Registration("delwarp", delwarp(), "删除服务器地标", List.of()));
            reg.accept(new Registration("tpa", tpa(), "请求传送到玩家身边", List.of()));
            reg.accept(new Registration("tpahere", tpahere(), "邀请玩家传送到你身边", List.of()));
            reg.accept(new Registration("tpaccept", tpaccept(), "接受传送请求", List.of("tpyes")));
            reg.accept(new Registration("tpdeny", tpdeny(), "拒绝传送请求", List.of("tpno")));
            reg.accept(new Registration("tpatoggle", tpatoggle(), "开关传送请求接收", List.of()));
            reg.accept(new Registration("tpblock", tpblock(), "屏蔽玩家的传送请求", List.of("tpignore")));
            reg.accept(new Registration("tpunblock", tpunblock(), "解除屏蔽", List.of()));
            reg.accept(new Registration("wild", wild(), "随机传送", List.of("rtp")));
            reg.accept(new Registration("back", back(), "返回上次传送位置", List.of()));
            reg.accept(new Registration("dback", dback(), "返回死亡地点", List.of()));
            reg.accept(new Registration("otp", otp(), "强制传送到玩家身边", List.of()));
            reg.accept(new Registration("otph", otph(), "强制将玩家传送过来", List.of()));
            reg.accept(new Registration("outtp", outtp(), "传送到玩家最后下线的位置", List.of()));
            reg.accept(new Registration("ui", ui(), "传送请求快捷响应", List.of()));
            reg.accept(new Registration("mtp", admin(), "MikuTP 管理命令", List.of("mikutp")));
        });
    }

    private MikuTPConfig.CommandEntry entry(String key) {
        MikuTPConfig.Commands commands = config.commands;
        return switch (key) {
            case "home" -> commands.home;
            case "sethome" -> commands.sethome;
            case "delhome" -> commands.delhome;
            case "warp" -> commands.warp;
            case "setwarp" -> commands.setwarp;
            case "delwarp" -> commands.delwarp;
            case "tpa" -> commands.tpa;
            case "tpahere" -> commands.tpahere;
            case "tpaccept" -> commands.tpaccept;
            case "tpdeny" -> commands.tpdeny;
            case "tpatoggle" -> commands.tpatoggle;
            case "tpblock" -> commands.tpblock;
            case "tpunblock" -> commands.tpunblock;
            case "wild" -> commands.wild;
            case "back" -> commands.back;
            case "dback" -> commands.dback;
            case "otp" -> commands.otp;
            case "otph" -> commands.otph;
            case "outtp" -> commands.outtp;
            case "mtp" -> commands.mtp;
            case "ui" -> commands.ui;
            default -> null;
        };
    }

    private record Registration(String key, LiteralCommandNode<CommandSourceStack> node,
                                String description, List<String> defaultAliases) {
    }

    /**
     * Filters aliases that cannot be registered: blank entries, aliases that shadow
     * one of this plugin's literals, and aliases already used by another command.
     * Duplicates make the registrar throw, which would otherwise abort the whole
     * COMMANDS lifecycle.
     */
    private List<String> safeAliases(String literal, List<String> aliases, java.util.Set<String> reserved) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        List<String> safe = new java.util.ArrayList<>(aliases.size());
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            String normalized = alias.toLowerCase(java.util.Locale.ROOT);
            if (normalized.equals(literal) || !reserved.add(normalized)) {
                plugin.getSLF4JLogger().warn("Ignoring alias '{}' of /{}: already used", alias, literal);
                continue;
            }
            safe.add(alias);
        }
        return safe;
    }

    // ------------------------------------------------------------------ home

    private LiteralCommandNode<CommandSourceStack> home() {
        return playerRoot("home", "mikutp.home",
                player -> openHomeMenu(player, false),
                (player, name) -> homeService.go(player, name),
                player -> homeService.homes(player.getUniqueId()).stream().map(h -> h.name).toList());
    }

    private LiteralCommandNode<CommandSourceStack> sethome() {
        return playerRoot("sethome", "mikutp.home.set",
                player -> {
                    if (dialogsEnabled()) {
                        // Opening a dialog touches the player: render it on their thread.
                        tasks.entity(player, () -> dialogs.showNameInput(player, "dialog.name.title",
                                name -> homeService.set(player, name)));
                    } else {
                        // Without dialogs the name has to come from the command itself.
                        messages.send(player, "home.usage");
                    }
                },
                (player, name) -> homeService.set(player, name),
                null);
    }

    private LiteralCommandNode<CommandSourceStack> delhome() {
        return playerRoot("delhome", "mikutp.home.delete",
                player -> openHomeMenu(player, true),
                (player, name) -> homeService.delete(player, name),
                player -> homeService.homes(player.getUniqueId()).stream().map(h -> h.name).toList());
    }

    private void openHomeMenu(Player player, boolean delete) {
        // The menu needs the full list, which may require a database read: do the
        // lookup on the async pool and render on the player's own thread.
        tasks.async(() -> {
            var homes = homeService.homesForDisplay(player.getUniqueId());
            tasks.entity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (dialogsEnabled()) {
                    if (delete) {
                        dialogs.showHomeDelete(player, homes, 1, name -> homeService.delete(player, name));
                    } else {
                        dialogs.showHomeList(player, homes, 1, name -> homeService.go(player, name));
                    }
                } else {
                    chats.homeList(player, homes, delete);
                }
            });
        });
    }

    // ------------------------------------------------------------------ warp

    private LiteralCommandNode<CommandSourceStack> warp() {
        return playerRoot("warp", "mikutp.warp",
                player -> openWarpMenu(player, false),
                (player, name) -> warpService.go(player, name),
                player -> warpService.warpsForDisplay().stream().map(w -> w.name).toList());
    }

    private LiteralCommandNode<CommandSourceStack> setwarp() {
        return playerRoot("setwarp", "mikutp.warp.manage",
                player -> {
                    if (dialogsEnabled()) {
                        tasks.entity(player, () -> dialogs.showNameInput(player, "dialog.name.title",
                                name -> warpService.set(player, name)));
                    } else {
                        // Without dialogs the name has to come from the command itself.
                        messages.send(player, "warp.usage");
                    }
                },
                (player, name) -> warpService.set(player, name),
                null);
    }

    private LiteralCommandNode<CommandSourceStack> delwarp() {
        return playerRoot("delwarp", "mikutp.warp.manage",
                player -> openWarpMenu(player, true),
                (player, name) -> warpService.delete(player, name),
                player -> warpService.warpsForDisplay().stream().map(w -> w.name).toList());
    }

    /**
     * Opens the warp menu once the warp table has been read. The initial load runs
     * asynchronously at enable time, so a player who runs /warp right after a
     * restart would otherwise get an empty menu (or a misleading "no warps" line).
     * The wait is bounded, runs on the async pool only, and then hops back to the
     * player's own thread to render.
     */
    private void openWarpMenu(Player player, boolean delete) {
        // A list of warps that every click refuses is worse than the plain notice.
        if (!warpService.isEnabled()) {
            messages.send(player, "warp.disabled");
            return;
        }
        if (warpService.isLoaded()) {
            showWarpMenu(player, delete);
            return;
        }
        // Kick the load in case the enable-time one has not started yet; WarpService
        // ignores the call when a load already completed.
        warpService.load();
        tasks.async(() -> awaitWarpLoad(player, delete, 0));
    }

    private void awaitWarpLoad(Player player, boolean delete, int attempt) {
        if (warpService.isLoaded() || attempt >= WARP_LOAD_MAX_POLLS) {
            tasks.entity(player, () -> {
                if (player.isOnline()) {
                    showWarpMenu(player, delete);
                }
            });
            return;
        }
        tasks.asyncDelayed(() -> awaitWarpLoad(player, delete, attempt + 1), WARP_LOAD_POLL_MS);
    }

    private void showWarpMenu(Player player, boolean delete) {
        var warps = warpService.warpsForDisplay();
        if (dialogsEnabled()) {
            if (delete) {
                dialogs.showWarpDelete(player, warps, 1, name -> warpService.delete(player, name));
            } else {
                dialogs.showWarpList(player, warps, 1, name -> warpService.go(player, name));
            }
        } else {
            chats.warpList(player, warps, delete);
        }
    }

    // ------------------------------------------------------------------ tpa

    private LiteralCommandNode<CommandSourceStack> tpa() {
        return playerRoot("tpa", "mikutp.tpa",
                player -> {
                    if (dialogsEnabled()) {
                        tasks.entity(player, () -> dialogs.showTpaTarget(player, false,
                                name -> requestService.send(player, name, false)));
                    } else {
                        messages.send(player, "tpa.usage");
                    }
                },
                (player, name) -> requestService.send(player, name, false),
                player -> onlinePlayerNames());
    }

    private LiteralCommandNode<CommandSourceStack> tpahere() {
        return playerRoot("tpahere", "mikutp.tpa",
                player -> {
                    if (dialogsEnabled()) {
                        tasks.entity(player, () -> dialogs.showTpaTarget(player, true,
                                name -> requestService.send(player, name, true)));
                    } else {
                        messages.send(player, "tpahere.usage");
                    }
                },
                (player, name) -> requestService.send(player, name, true),
                player -> onlinePlayerNames());
    }

    private LiteralCommandNode<CommandSourceStack> tpaccept() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tpaccept")
                .requires(requiresPlayer("mikutp.tpa"))
                .executes(ctx -> {
                    Player player = asPlayer(ctx.getSource());
                    requestService.respondLatest(player, RequestService.Response.ACCEPT);
                    return 1;
                });
        root.then(Commands.argument("player", StringArgumentType.word())
                .executes(ctx -> {
                    Player player = asPlayer(ctx.getSource());
                    requestService.respondFromPlayer(player, StringArgumentType.getString(ctx, "player"),
                            RequestService.Response.ACCEPT);
                    return 1;
                }));
        return root.build();
    }

    private LiteralCommandNode<CommandSourceStack> tpdeny() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tpdeny")
                .requires(requiresPlayer("mikutp.tpa"))
                .executes(ctx -> {
                    Player player = asPlayer(ctx.getSource());
                    requestService.respondLatest(player, RequestService.Response.DENY);
                    return 1;
                });
        root.then(Commands.argument("player", StringArgumentType.word())
                .executes(ctx -> {
                    Player player = asPlayer(ctx.getSource());
                    requestService.respondFromPlayer(player, StringArgumentType.getString(ctx, "player"),
                            RequestService.Response.DENY);
                    return 1;
                }));
        return root.build();
    }

    private LiteralCommandNode<CommandSourceStack> tpatoggle() {
        return Commands.literal("tpatoggle")
                .requires(requiresPlayer("mikutp.tpa"))
                .executes(ctx -> {
                    requestService.toggle(asPlayer(ctx.getSource()));
                    return 1;
                })
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> tpblock() {
        return playerRoot("tpblock", "mikutp.tpa",
                player -> requestService.listBlocks(player),
                (player, name) -> requestService.block(player, name),
                null);
    }

    private LiteralCommandNode<CommandSourceStack> tpunblock() {
        return playerRoot("tpunblock", "mikutp.tpa",
                player -> requestService.listBlocks(player),
                (player, name) -> requestService.unblock(player, name),
                null);
    }

    private LiteralCommandNode<CommandSourceStack> wild() {
        return Commands.literal("wild")
                .requires(requiresPlayer("mikutp.wild"))
                .executes(ctx -> {
                    wildService.start(asPlayer(ctx.getSource()));
                    return 1;
                })
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> back() {
        return historyCommand("back", "mikutp.back", teleports::goBack);
    }

    private LiteralCommandNode<CommandSourceStack> dback() {
        return historyCommand("dback", "mikutp.dback", teleports::goDeathBack);
    }

    private LiteralCommandNode<CommandSourceStack> otp() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("otp")
                .requires(requiresPlayer("mikutp.otp"))
                .executes(ctx -> {
                    messages.send(asPlayer(ctx.getSource()), "otp.usage");
                    return 1;
                });
        root.then(Commands.argument("player", StringArgumentType.word())
                .suggests(onlinePlayerSuggestions())
                .executes(ctx -> {
                    teleports.adminGoto(asPlayer(ctx.getSource()), StringArgumentType.getString(ctx, "player"));
                    return 1;
                }));
        return root.build();
    }

    private LiteralCommandNode<CommandSourceStack> otph() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("otph")
                .requires(requiresPlayer("mikutp.otp"))
                .executes(ctx -> {
                    messages.send(asPlayer(ctx.getSource()), "otph.usage");
                    return 1;
                });
        root.then(Commands.literal("all")
                .executes(ctx -> {
                    teleports.adminBringAll(asPlayer(ctx.getSource()), null);
                    return 1;
                })
                .then(Commands.argument("server", StringArgumentType.word())
                        .executes(ctx -> {
                            teleports.adminBringAll(asPlayer(ctx.getSource()),
                                    StringArgumentType.getString(ctx, "server"));
                            return 1;
                        })));
        root.then(Commands.argument("player", StringArgumentType.word())
                .suggests(onlinePlayerSuggestions())
                .executes(ctx -> {
                    teleports.adminBring(asPlayer(ctx.getSource()), StringArgumentType.getString(ctx, "player"));
                    return 1;
                }));
        return root.build();
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> onlinePlayerSuggestions() {
        return (ctx, builder) -> {
            for (String name : onlinePlayers.names()) {
                builder.suggest(name);
            }
            return builder.buildFuture();
        };
    }

    /** Online names from the plugin registry: the Bukkit player view may only be
     * iterated on the global region thread under Folia. */
    private List<String> onlinePlayerNames() {
        return onlinePlayers.names();
    }

    private LiteralCommandNode<CommandSourceStack> historyCommand(String name, String permission,
                                                                  java.util.function.Consumer<Player> handler) {
        return Commands.literal(name)
                .requires(requiresPlayer(permission))
                .executes(ctx -> {
                    // The service checks and burns the cooldown atomically, and
                    // refunds it when there is no stored position to return to.
                    handler.accept(asPlayer(ctx.getSource()));
                    return 1;
                })
                .build();
    }

    /** Hidden helper behind the clickable chat buttons. */
    private LiteralCommandNode<CommandSourceStack> ui() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mikutp-ui")
                .requires(requiresPlayer("mikutp.tpa"));
        root.then(Commands.literal("accept").then(Commands.argument("id", StringArgumentType.word())
                .executes(ctx -> {
                    requestService.respond(asPlayer(ctx.getSource()), StringArgumentType.getString(ctx, "id"),
                            RequestService.Response.ACCEPT);
                    return 1;
                })));
        root.then(Commands.literal("deny").then(Commands.argument("id", StringArgumentType.word())
                .executes(ctx -> {
                    requestService.respond(asPlayer(ctx.getSource()), StringArgumentType.getString(ctx, "id"),
                            RequestService.Response.DENY);
                    return 1;
                })));
        root.then(Commands.literal("block").then(Commands.argument("id", StringArgumentType.word())
                .executes(ctx -> {
                    requestService.respond(asPlayer(ctx.getSource()), StringArgumentType.getString(ctx, "id"),
                            RequestService.Response.BLOCK);
                    return 1;
                })));
        return root.build();
    }

    private LiteralCommandNode<CommandSourceStack> outtp() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("outtp")
                .requires(requiresPlayer("mikutp.otp"))
                .executes(ctx -> {
                    messages.send(asPlayer(ctx.getSource()), "outtp.usage");
                    return 1;
                });
        root.then(Commands.argument("player", StringArgumentType.word())
                .executes(ctx -> {
                    teleports.adminLastLogout(asPlayer(ctx.getSource()),
                            StringArgumentType.getString(ctx, "player"));
                    return 1;
                }));
        return root.build();
    }

    private LiteralCommandNode<CommandSourceStack> admin() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mtp")
                .requires(src -> src.getSender().hasPermission("mikutp.admin"))
                .executes(ctx -> {
                    messages.send(ctx.getSource().getSender(), "admin.info", "info", infoSupplier.get());
                    return 1;
                });
        root.then(Commands.literal("reload").executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            // The reload runs asynchronously; report the real outcome when it is done
            // rather than claiming success before anything was read.
            reloadAction.reload(ok -> tasks.global(() -> {
                if (Boolean.TRUE.equals(ok)) {
                    messages.send(sender, "common.reload-done");
                    // Command literals are registered once at startup: config.yml
                    // changes to aliases can only take effect after a restart.
                    if (messages.has("admin.commands-need-restart")) {
                        messages.send(sender, "admin.commands-need-restart");
                    }
                } else {
                    messages.send(sender, "common.reload-failed", "error", "config.yml");
                }
            }));
            return 1;
        }));
        root.then(Commands.literal("resync").executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            // Report what actually happened: single-server mode has nothing to resync.
            boolean dispatched = resyncAction.getAsBoolean();
            messages.send(sender, dispatched ? "admin.resync-done" : "common.cross-disabled");
            return 1;
        }));
        root.then(Commands.literal("permissions").executes(ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            var permissions = new java.util.ArrayList<>(plugin.getPluginMeta().getPermissions());
            if (permissions.isEmpty()) {
                // paper-plugin.yml permissions unavailable: fall back to the static registry.
                for (var node : com.mikumc.mikutp.paper.PermissionNodes.NODES) {
                    permissions.add(new org.bukkit.permissions.Permission(node.node(), node.description(),
                            org.bukkit.permissions.PermissionDefault.valueOf(node.def().toUpperCase(java.util.Locale.ROOT))));
                }
            }
            messages.send(sender, "admin.permissions", "count", String.valueOf(permissions.size()));
            for (var permission : permissions) {
                messages.send(sender, "admin.permissions-line",
                        "node", permission.getName(),
                        "default", permission.getDefault().toString().toLowerCase(),
                        "description", permission.getDescription() == null ? "" : permission.getDescription());
            }
            return 1;
        }));
        root.then(Commands.literal("info").executes(ctx -> {
            messages.send(ctx.getSource().getSender(), "admin.info", "info", infoSupplier.get());
            return 1;
        }));
        return root.build();
    }

    // ------------------------------------------------------------------ helpers

    private LiteralCommandNode<CommandSourceStack> playerRoot(String name, String permission,
                                                              Consumer<Player> noArg,
                                                              BiConsumer<Player, String> withArg,
                                                              Function<Player, Iterable<String>> suggestions) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name)
                .requires(requiresPlayer(permission))
                .executes(ctx -> {
                    noArg.accept(asPlayer(ctx.getSource()));
                    return 1;
                });
        if (withArg != null) {
            var arg = Commands.argument("target", StringArgumentType.word());
            if (suggestions != null) {
                arg.suggests((ctx, builder) -> {
                    Player player = playerOrNull(ctx.getSource());
                    if (player != null) {
                        for (String option : suggestions.apply(player)) {
                            builder.suggest(option);
                        }
                    }
                    return builder.buildFuture();
                });
            }
            arg.executes(ctx -> {
                withArg.accept(asPlayer(ctx.getSource()), StringArgumentType.getString(ctx, "target"));
                return 1;
            });
            root.then(arg);
        }
        return root.build();
    }

    private boolean dialogsEnabled() {
        return dialogsEnabledSupplier.getAsBoolean();
    }

    private static Predicate<CommandSourceStack> requiresPlayer(String permission) {
        return src -> src.getSender() instanceof Player player && player.hasPermission(permission);
    }

    private static Player asPlayer(CommandSourceStack src) {
        return (Player) src.getSender();
    }

    private static Player playerOrNull(CommandSourceStack src) {
        return src.getSender() instanceof Player player ? player : null;
    }
}
