package com.mikumc.mikutp.paper.service;

import com.mikumc.mikutp.common.message.MessageBundle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-visible message pipeline: language bundle → optional PlaceholderAPI
 * substitution → MiniMessage rendering with named placeholders.
 *
 * <p>Templates that take no arguments and need no placeholder resolution are
 * parsed once and reused: MiniMessage parsing is the cost in this pipeline, and
 * the countdown action bar is emitted every tick per warming player.
 */
public final class MessageService {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final boolean papiAvailable;
    private volatile boolean parsePapi;
    private volatile MessageBundle bundle;
    private volatile Component prefix;
    private volatile TagResolver prefixResolver;
    /** Parsed templates keyed by template text; cleared on reload. */
    private final Map<String, Component> parsedTemplates = new ConcurrentHashMap<>();

    public MessageService(MessageBundle bundle, boolean parsePapi, boolean papiAvailable) {
        this.bundle = bundle;
        this.parsePapi = parsePapi;
        this.papiAvailable = papiAvailable;
        this.prefix = MM.deserialize(bundle.raw("prefix"));
        this.prefixResolver = TagResolver.resolver(Placeholder.component("prefix", prefix));
    }

    /** Reloads the language file from disk and re-reads the switch. */
    public void reload(MessageBundle newBundle, boolean parsePapi) {
        this.bundle = newBundle;
        this.parsePapi = parsePapi;
        this.prefix = MM.deserialize(newBundle.raw("prefix"));
        this.prefixResolver = TagResolver.resolver(Placeholder.component("prefix", prefix));
        parsedTemplates.clear();
    }

    public String template(String key) {
        return bundle.raw(key);
    }

    /** Renders a message key with {@code key, value, key, value...} placeholders. */
    public Component render(CommandSender sender, String key, String... pairs) {
        String template = bundle.raw(key);
        boolean placeholderApiApplied = false;
        if (parsePapi && papiAvailable && sender instanceof Player player
                && PapiSupport.containsPlaceholders(template)) {
            template = PapiSupport.parse(player, template);
            placeholderApiApplied = true;
        }
        if (pairs.length == 0 && !placeholderApiApplied) {
            return parsedTemplates.computeIfAbsent(template, text -> MM.deserialize(text, prefixResolver));
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
        if (pairs.length == 0) {
            return prefixResolver;
        }
        List<TagResolver> resolvers = new ArrayList<>(pairs.length / 2 + 1);
        resolvers.add(prefixResolver);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            resolvers.add(Placeholder.unparsed(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]));
        }
        return TagResolver.resolver(resolvers);
    }
}