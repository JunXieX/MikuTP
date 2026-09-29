package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.message.MessageBundle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Player-visible message pipeline: language bundle → optional PlaceholderAPI
 * substitution → MiniMessage rendering with named placeholders.
 */
public final class MessageService {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final boolean papiAvailable;
    private volatile boolean parsePapi;
    private volatile MessageBundle bundle;
    private volatile Component prefix;

    public MessageService(JavaPlugin plugin, MessageBundle bundle, boolean parsePapi, boolean papiAvailable) {
        this.plugin = plugin;
        this.bundle = bundle;
        this.parsePapi = parsePapi;
        this.papiAvailable = papiAvailable;
        this.prefix = MM.deserialize(bundle.raw("prefix"));
    }

    /** Reloads the language file from disk and re-reads the switch. */
    public void reload(MessageBundle newBundle, boolean parsePapi) {
        this.bundle = newBundle;
        this.parsePapi = parsePapi;
        this.prefix = MM.deserialize(newBundle.raw("prefix"));
    }

    public String template(String key) {
        return bundle.raw(key);
    }

    /** Renders a message key with {@code key, value, key, value...} placeholders. */
    public Component render(CommandSender sender, String key, String... pairs) {
        String template = bundle.raw(key);
        if (parsePapi && papiAvailable && sender instanceof Player player && PapiSupport.containsPlaceholders(template)) {
            template = PapiSupport.parse(player, template);
        }
        return MM.deserialize(template, resolvers(pairs));
    }

    public void send(CommandSender sender, String key, String... pairs) {
        sender.sendMessage(render(sender, key, pairs));
    }

    public void actionBar(Player player, String key, String... pairs) {
        player.sendActionBar(render(player, key, pairs));
    }

    public Component prefix() {
        return prefix;
    }

    private TagResolver resolvers(String... pairs) {
        List<TagResolver> resolvers = new ArrayList<>();
        resolvers.add(Placeholder.component("prefix", prefix));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            resolvers.add(Placeholder.unparsed(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]));
        }
        return TagResolver.resolver(resolvers);
    }

    public Path languageFile() {
        return plugin.getDataFolder().toPath().resolve(bundle.raw("language_file"));
    }
}
