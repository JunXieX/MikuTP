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

    private final Tasks tasks;
    private final boolean papiAvailable;
    private volatile boolean parsePapi;
    private volatile MessageBundle bundle;
    private volatile Component prefix;
    private volatile TagResolver prefixResolver;
    /**
     * Parsed templates keyed by template text. Entries carry the reload generation
     * they were rendered under: a render racing a reload can install an entry built
     * with the old bundle/resolver after the cache was cleared, and the generation
     * check makes such an entry invalid instead of leaving a stale message cached
     * until the next reload.
     */
    private final Map<String, CachedTemplate> parsedTemplates = new ConcurrentHashMap<>();
    /** Bumped at the end of every reload, after the new bundle and resolver are in place. */
    private volatile long generation;

    private record CachedTemplate(Component component, long generation) {
    }

    public MessageService(Tasks tasks, MessageBundle bundle, boolean parsePapi, boolean papiAvailable) {
        this.tasks = tasks;
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
        // The generation is bumped last: a render that read the old generation must
        // not be able to publish an entry rendered with the old resolver as current.
        generation++;
        parsedTemplates.clear();
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
            long current = generation;
            CachedTemplate cached = parsedTemplates.get(template);
            if (cached != null && cached.generation() == current) {
                return cached.component();
            }
            Component rendered = MM.deserialize(template, prefixResolver);
            parsedTemplates.put(template, new CachedTemplate(rendered, current));
            return rendered;
        }
        return MM.deserialize(template, resolvers(pairs));
    }

    /** True when the bundle defines {@code key}; lets callers emit optional hints. */
    public boolean has(String key) {
        return bundle.has(key);
    }

    /**
     * Sends a message from any thread. Rendering (PlaceholderAPI included) and
     * delivery belong on the player's own thread: commands and entity callbacks
     * already run there and stay synchronous, while a caller coming from a database
     * callback is rescheduled instead of resolving placeholders off-region.
     */
    public void send(CommandSender sender, String key, String... pairs) {
        if (sender instanceof Player player && !tasks.owns(player)) {
            tasks.entity(player, () -> sender.sendMessage(render(sender, key, pairs)));
            return;
        }
        sender.sendMessage(render(sender, key, pairs));
    }

    public void actionBar(Player player, String key, String... pairs) {
        player.sendActionBar(render(player, key, pairs));
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