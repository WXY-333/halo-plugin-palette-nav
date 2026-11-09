package cn.litx.palettenav.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Builds the inline SVG sprite used by the public {@code /nav} page.
 *
 * <p>The console loads the same {@code palette-icons.js} file for its preview, but the
 * public page only needs the handful of symbols referenced by the current data. The
 * sprite is parsed once, then only the referenced symbols are inlined into the page.
 * The markup comes from a bundled file of original line icons and symbol ids are
 * already restricted to {@code [A-Za-z0-9_-]} by {@link NavIconRenderer}, so nothing
 * here is user-controlled.</p>
 */
@Slf4j
@Service
public class NavIconSpriteService {

    /**
     * The console-facing sprite file is the source of truth, so the public page
     * cannot drift from the console preview after an icon set update.
     */
    private static final String[] SPRITE_RESOURCES = {
        "static/nav/vendor/palette-icons.js"
    };

    private static final Pattern SYMBOL = Pattern.compile("(?is)<symbol\\b[^>]*>.*?</symbol>");
    private static final Pattern SYMBOL_ID = Pattern.compile(
        "(?is)<symbol\\b[^>]*\\bid\\s*=\\s*[\"']([A-Za-z0-9_-]+)[\"']");
    /**
     * Ids shipped by the old iconfont sprite. They are no longer present in the
     * bundled file, but existing installations may still reference them.
     */
    private static final Pattern LEGACY_ID = Pattern.compile(
        "^(?:lyicon|icon)-[A-Za-z0-9_-]+$");
    private static final String GENERIC_LINK_ID = "pn-link";
    /**
     * Legacy ids used by the seeded categories (and by some saved data). They are
     * upgraded to the matching coloured category logo instead of the generic link.
     */
    private static final Map<String, String> LEGACY_CATEGORY_IDS = Map.of(
        "icon-remen", "pn-cat-common",
        "icon-sheji", "pn-cat-design",
        "icon-msg", "pn-cat-cloud",
        "icon-ai-tool", "pn-cat-tools",
        "icon-kongzhitai", "pn-cat-dev",
        "icon-game00", "pn-cat-games"
    );

    private volatile Map<String, String> symbols;

    /**
     * Parses the bundled sprite sources during plugin startup. The first {@code /nav}
     * render would otherwise read ~900 KB from the classpath and run the regex scans
     * on the event-loop thread, stalling every request that shares the thread.
     */
    public void initialize() {
        symbols();
    }

    /**
     * Returns an inline sprite containing only the requested symbols, or an empty
     * string when none of them exist. Unknown ids are ignored, matching the behavior of
     * a missing sprite reference.
     */
    public String render(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        var available = symbols();
        var emitted = new LinkedHashSet<String>();
        var builder = new StringBuilder(1024);
        for (var id : ids) {
            if (id == null || !emitted.add(id)) {
                continue;
            }
            var markup = available.get(id);
            if (markup == null && LEGACY_ID.matcher(id).matches()) {
                var target = LEGACY_CATEGORY_IDS.getOrDefault(id, GENERIC_LINK_ID);
                markup = legacySymbol(id, available.get(target));
            }
            if (markup != null) {
                builder.append(markup);
            }
        }
        if (builder.isEmpty()) {
            return "";
        }
        return "<svg class=\"icon-sprite\" aria-hidden=\"true\" focusable=\"false\">"
            + builder + "</svg>";
    }

    /**
     * Emits the mapped glyph under an old iconfont id, so data saved before the icon
     * set was replaced keeps rendering instead of disappearing.
     */
    private static String legacySymbol(String id, String generic) {
        if (generic == null) {
            return null;
        }
        return generic.replaceFirst("id=\"[A-Za-z0-9_-]+\"", "id=\"" + id + "\"");
    }

    private Map<String, String> symbols() {
        var cached = symbols;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (symbols == null) {
                symbols = load();
            }
            return symbols;
        }
    }

    private static Map<String, String> load() {
        var result = new LinkedHashMap<String, String>();
        for (var resourcePath : SPRITE_RESOURCES) {
            var resource = new ClassPathResource(resourcePath,
                NavIconSpriteService.class.getClassLoader());
            if (!resource.exists()) {
                log.warn("Palette Nav icon sprite {} is missing", resourcePath);
                continue;
            }
            try (InputStream input = resource.getInputStream()) {
                var content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                var matcher = SYMBOL.matcher(content);
                while (matcher.find()) {
                    var markup = matcher.group();
                    var idMatcher = SYMBOL_ID.matcher(markup);
                    if (idMatcher.find()) {
                        result.putIfAbsent(idMatcher.group(1), markup);
                    }
                }
            } catch (IOException error) {
                log.warn("Failed to read Palette Nav icon sprite {}", resourcePath,
                    error);
            }
        }
        return Map.copyOf(result);
    }
}