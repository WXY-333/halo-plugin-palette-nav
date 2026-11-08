package cn.litx.palettenav.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * Converts the admin-provided icon value into sanitized HTML.
 *
 * <p>Four shapes are accepted: an SVG sprite reference
 * ({@code <svg><use href="#lyicon-x"/></svg>}), arbitrary inline SVG source (embedded
 * as a base64 data URL image, so scripts inside it never execute), an http(s)/
 * site-relative image URL, or a short text/emoji. Everything else falls back to the
 * default icon, which prevents stored XSS through {@code th:utext}.</p>
 */
@Component
public class NavIconRenderer {

    private static final Pattern SPRITE_SVG = Pattern.compile(
        "(?is)^\\s*<svg\\b[^>]*>\\s*<use\\b[^>]*(?:xlink:href|href)\\s*=\\s*[\"']"
            + "#([A-Za-z0-9_-]+)[\"'][^>]*>\\s*(?:</use\\s*>)?</svg>\\s*$");

    private static final Pattern UNSAFE_MARKERS = Pattern.compile(
        "(?is)<script\\b|</script\\s*>|on[a-z]+\\s*=|javascript:");

    private static final String SVG_DATA_URL_PREFIX = "data:image/svg+xml;base64,";

    private static final int MAX_TEXT_LENGTH = 16;

    public String render(String icon, String defaultIconUrl) {
        var value = NavUrlValidator.trim(icon);
        if (value.isEmpty()) {
            return defaultImage(defaultIconUrl);
        }

        if (value.regionMatches(true, 0, "<svg", 0, 4)) {
            return renderSvg(value, defaultIconUrl);
        }

        if (NavUrlValidator.isSafeImageUrl(value)) {
            return image(value);
        }

        if (isShortText(value)) {
            return "<span class=\"icon-text\">" + HtmlUtils.htmlEscape(value) + "</span>";
        }

        return defaultImage(defaultIconUrl);
    }

    /**
     * Returns the sprite symbol id when the icon is a safe sprite reference, otherwise
     * an empty string. The public page uses this to inline only the symbols it renders.
     */
    public String spriteId(String icon) {
        var value = NavUrlValidator.trim(icon);
        if (value.isEmpty()) {
            return "";
        }
        var sprite = SPRITE_SVG.matcher(value);
        return sprite.matches() ? sprite.group(1) : "";
    }

    private String renderSvg(String value, String defaultIconUrl) {
        if (UNSAFE_MARKERS.matcher(value).find() || !value.toLowerCase().contains("</svg")) {
            return defaultImage(defaultIconUrl);
        }
        var sprite = SPRITE_SVG.matcher(value);
        if (sprite.matches()) {
            var id = sprite.group(1);
            return "<svg class=\"icon\" aria-hidden=\"true\"><use href=\"#" + id
                + "\" xlink:href=\"#" + id + "\"></use></svg>";
        }
        var encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return "<img src=\"" + SVG_DATA_URL_PREFIX + encoded
            + "\" alt=\"\" loading=\"lazy\" decoding=\"async\">";
    }

    private boolean isShortText(String value) {
        return !value.contains("<") && !value.contains(">")
            && value.codePointCount(0, value.length()) <= MAX_TEXT_LENGTH;
    }

    private String image(String url) {
        return "<img src=\"" + HtmlUtils.htmlEscape(url)
            + "\" alt=\"\" loading=\"lazy\" decoding=\"async\">";
    }

    private String defaultImage(String defaultIconUrl) {
        if (!StringUtils.hasText(defaultIconUrl)) {
            return "";
        }
        return image(defaultIconUrl);
    }
}
