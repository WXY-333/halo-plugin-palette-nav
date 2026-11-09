package cn.litx.palettenav.service;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Sanitizes the admin-provided copyright HTML before it is rendered into the public
 * {@code /nav} page with {@code th:utext}.
 *
 * <p>The manage role can write the plugin settings ConfigMap, so raw HTML here would
 * let any manager inject scripts into a public page. Only a small, explicit allow-list
 * of text-level tags and links survives; scripts, event handlers and {@code javascript:}
 * URLs are removed. The sanitizer runs at render time, so values written directly to the
 * ConfigMap are neutralized as well.</p>
 */
@Service
public class NavFooterSanitizer {

    /**
     * Container elements whose content is removed before cleaning. jsoup would drop
     * the tags themselves, but removing the nodes first also discards their text
     * content (for example the body of a {@code <script>}).
     */
    private static final String REMOVED_ELEMENTS =
        "script, style, iframe, object, embed, form, input, button, textarea, select, "
            + "meta, link, base, svg, math, template";

    /**
     * A deliberately smaller allow-list than {@link Safelist#relaxed()}: no images,
     * no inline styles, no {@code target} and no embedded media.
     */
    private static final Safelist SAFELIST = new Safelist()
        .addTags("a", "b", "strong", "em", "i", "u", "p", "div", "span", "br")
        .addAttributes("a", "href", "title")
        .addAttributes("div", "class")
        .addAttributes("span", "class")
        .addProtocols("a", "href", "http", "https", "mailto")
        .preserveRelativeLinks(true);

    /**
     * The sanitizer runs on the {@code /nav} render path (Netty event loop), while
     * the copyright value changes only when a manager saves the settings. A single
     * memoized result keeps the CPU-bound jsoup parse off that path; the settings
     * value is the only input, so a one-entry cache is sufficient.
     */
    private volatile Cached cached;

    public String sanitize(String html) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        var current = cached;
        if (current != null && html.equals(current.input())) {
            return current.output();
        }
        var cleaned = clean(html);
        cached = new Cached(html, cleaned);
        return cleaned;
    }

    private static String clean(String html) {
        var document = Jsoup.parseBodyFragment(html);
        document.select(REMOVED_ELEMENTS).remove();
        return Jsoup.clean(document.body().html(), SAFELIST);
    }

    private record Cached(String input, String output) {
    }
}