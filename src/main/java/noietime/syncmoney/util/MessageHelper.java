package noietime.syncmoney.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Message helper utility class.
 * Full support for MiniMessage format, compatible with legacy &/§ color codes.
 * <p>
 * Supported MiniMessage tags (StandardTags): color &lt;#RRGGBB&gt;, &lt;gradient&gt;, &lt;rainbow&gt;,
 * &lt;transition&gt;, &lt;bold&gt;/&lt;b&gt;, &lt;italic&gt;/&lt;i&gt;, &lt;hover&gt;, &lt;click&gt;,
 * &lt;keybind&gt;, &lt;insertion&gt;, etc. To display literal angle brackets use escape: {@code \&lt;} and {@code \&gt;}.
 * </p>
 *
 * [ThreadSafe] This class is thread-safe utility class.
 */
public final class MessageHelper {

    private static final Logger LOGGER = Logger.getLogger(MessageHelper.class.getName());

    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
            .tags(StandardTags.defaults())
            .build();

    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.builder()
                    .character('&')
                    .hexColors()
                    .build();

    private static final ANSIComponentSerializer ANSI_SERIALIZER = ANSIComponentSerializer.ansi();

    private static final Pattern LEGACY_PATTERN = Pattern.compile("[&§][0-9a-fk-or]");

    private static final Pattern BARE_HEX_COLOR_PATTERN = Pattern.compile("(?<![:<])#([0-9A-Fa-f]{6})(?![a-zA-Z0-9])");

    private static final ConcurrentHashMap<String, Component> componentCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 500;
    private static final AtomicLong componentCacheHits = new AtomicLong(0);
    private static final AtomicLong componentCacheMisses = new AtomicLong(0);

    private MessageHelper() {
    }

    /**
     * Internal parse: executes MiniMessage or Legacy parsing on normalized string.
     * Only for cache callbacks, avoids creating duplicate parsing results.
     */
    private static Component parseMessageInternal(String normalized) {
        try {
            Component result = MINI_MESSAGE.deserialize(normalized);
            return result != null ? result : Component.empty();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "MiniMessage parse failed, using legacy fallback", e);
        }
        return LEGACY_SERIALIZER.deserialize(normalized);
    }

    /**
     * Normalize raw message to cache key (bare #RRGGBB to &lt;#RRGGBB&gt;, § to &amp;).
     */
    private static String normalizeForCache(String message) {
        String processed = BARE_HEX_COLOR_PATTERN.matcher(message).replaceAll("<#$1>");
        return processed.replace('§', '&');
    }

    /**
     * Parse message string, supports MiniMessage and Legacy formats (&amp; and §).
     * Prefers MiniMessage, falls back to Legacy on failure.
     * Results are cached by normalized string to reduce repeated parsing and mitigate TextColorReplacerImpl memory leak.
     * <p>
     * Preprocessing: only converts "standalone" bare color #RRGGBB to &lt;#RRGGBB&gt;; colors inside tags are not replaced.
     * For literal angle brackets use {@code \&lt;}, {@code \&gt;}.
     * </p>
     */
    public static Component parseMessage(String message) {
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }
        String normalized = normalizeForCache(message);
        if (componentCache.size() >= MAX_CACHE_SIZE) {
            componentCache.clear();
        }
        Component cached = componentCache.get(normalized);
        if (cached != null) {
            componentCacheHits.incrementAndGet();
            return cached;
        }
        componentCacheMisses.incrementAndGet();
        return componentCache.computeIfAbsent(normalized, MessageHelper::parseMessageInternal);
    }

    /**
     * Clear message Component cache (e.g., can be called after config reload to release old parsing results).
     */
    public static void clearComponentCache() {
        componentCache.clear();
    }

    /**
     * Return current Component cache size (for monitoring).
     */
    public static int getComponentCacheSize() {
        return componentCache.size();
    }

    /**
     * Return Component cache hit count (for monitoring).
     */
    public static long getComponentCacheHits() {
        return componentCacheHits.get();
    }

    /**
     * Return Component cache miss count (for monitoring).
     */
    public static long getComponentCacheMisses() {
        return componentCacheMisses.get();
    }

    /**
     * Parse message and replace variables.
     */
    public static Component parseMessage(String message, Map<String, String> variables) {
        Component component = parseMessage(message);

        if (variables != null && !variables.isEmpty()) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                Component valueComponent = parseMessage(entry.getValue());
                component = component.replaceText(text ->
                        text.matchLiteral("{" + entry.getKey() + "}")
                                .replacement(valueComponent)
                );
            }
        }

        return component;
    }

    /**
     * Send message to CommandSender.
     */
    public static void sendMessage(CommandSender sender, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        Component component = parseMessage(message);

        if (sender instanceof Player) {
            ((Player) sender).sendMessage(component);
        } else {
            sender.sendMessage(ANSI_SERIALIZER.serialize(component));
        }
    }

    /**
     * Send message to CommandSender (with variables).
     */
    public static void sendMessage(CommandSender sender, String message, Map<String, String> variables) {
        if (message == null || message.isEmpty()) {
            return;
        }
        Component component = parseMessage(message, variables);

        if (sender instanceof Player) {
            ((Player) sender).sendMessage(component);
        } else {
            sender.sendMessage(ANSI_SERIALIZER.serialize(component));
        }
    }

    /**
     * Send parsed Component to CommandSender (can avoid repeated parsing, used with cache).
     */
    public static void sendMessage(CommandSender sender, Component component) {
        if (component == null || component.equals(Component.empty())) {
            return;
        }
        if (sender instanceof Player) {
            ((Player) sender).sendMessage(component);
        } else {
            sender.sendMessage(ANSI_SERIALIZER.serialize(component));
        }
    }

    /**
     * Convert Component to plain text (remove all colors and formatting).
     * Note: Legacy color codes are & or § followed by a single character (0-9, a-f, k-o, r)
     * Hex format: §x§R§G§B§A (5 chars after §x)
     */
    private static String componentToPlainText(Component component) {
        if (component == null) {
            return "";
        }
        String legacy = LEGACY_SERIALIZER.serialize(component);

        legacy = legacy.replaceAll("<[^>]+>", "");
        legacy = legacy.replaceAll("#[0-9A-Fa-f]{6}", "");
        legacy = legacy.replaceAll("[&§]x[&§][0-9A-Fa-f][&§][0-9A-Fa-f][&§][0-9A-Fa-f][&§][0-9A-Fa-f][&§][0-9A-Fa-f][&§][0-9A-Fa-f]", "");
        legacy = legacy.replaceAll("[&§](?=[0-9a-fk-orx])[0-9a-fk-orx]", "");

        legacy = legacy.replaceAll("\\s+", " ");

        return legacy.trim();
    }

    /**
     * Get parsed Component.
     */
    public static Component getComponent(String message) {
        return parseMessage(message);
    }

    /**
     * Check if contains Legacy color codes.
     */
    public static boolean containsLegacy(String message) {
        return message != null && LEGACY_PATTERN.matcher(message).find();
    }

    /**
     * Strip color codes (for plain text comparison).
     */
    public static String stripColor(String message) {
        if (message == null) {
            return "";
        }
        String result = message;
        result = result.replaceAll("<[^>]+>", "");
        result = result.replaceAll("#[0-9A-Fa-f]{6}", "");
        result = result.replaceAll("[&§]x[&§][0-9A-Fa-f][&§][0-9A-Fa-f][&§][0-9A-Fa-f][&§][0-9A-Fa-f][&§][0-9A-Fa-f][&§][0-9A-Fa-f]", "");
        result = result.replaceAll("[&§](?=[0-9a-fk-orx])[0-9a-fk-orx]", "");
        result = result.replaceAll("\\s+", " ");

        return result.trim();
    }

    /**
     * Convert Component to Legacy format string.
     */
    public static String toLegacyString(Component component) {
        return LEGACY_SERIALIZER.serialize(component);
    }

    /**
     * Replace variables in Component.
     */
    public static Component replaceVariables(Component component, Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return component;
        }

        Component result = component;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replaceText(text ->
                    text.matchLiteral("{" + entry.getKey() + "}")
                            .replacement(entry.getValue())
            );
        }

        return result;
    }

    /**
     * Send action bar message.
     */
    public static void sendActionBar(Player player, String message, int duration) {
        if (player == null || message == null) {
            return;
        }
        Component component = parseMessage(message);
        player.sendActionBar(component);
    }

    /**
     * Send action bar message (using default duration).
     */
    public static void sendActionBar(Player player, String message) {
        sendActionBar(player, message, 80);
    }
}
