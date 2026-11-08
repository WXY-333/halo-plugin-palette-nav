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
 * <p>The console still loads the full iconfont sprite scripts for its preview and
 * picker, but the public page only needs the handful of symbols referenced by the
 * current data. Loading {@code icon.js} (about 220 KB gzipped) on every visit just to
 * satisfy a few {@code <use>} references was the largest static payload of the page.
 * The sprite sources are parsed once, then only the referenced symbols are inlined into
 * the page. The markup comes from the bundled files and symbol ids are already
 * restricted to {@code [A-Za-z0-9_-]} by {@link NavIconRenderer}, so nothing here is
 * user-controlled.</p>
 */
@Slf4j
@Service
public class NavIconSpriteService {

    /**
     * The console-facing sprite scripts are the source of truth, so the public page
     * cannot drift from the icon picker after an icon set update.
     */
    private static final String[] SPRITE_RESOURCES = {
        "static/nav/vendor/icon.js",
        "static/nav/vendor/svg.js"
    };

    private static final Pattern SYMBOL = Pattern.compile("(?is)<symbol\\b[^>]*>.*?</symbol>");
    private static final Pattern SYMBOL_ID = Pattern.compile(
        "(?is)<symbol\\b[^>]*\\bid\\s*=\\s*[\"']([A-Za-z0-9_-]+)[\"']");

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