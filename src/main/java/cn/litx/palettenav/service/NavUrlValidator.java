package cn.litx.palettenav.service;

import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * URL allow-list used by both the page renderer and the import validation.
 */
public final class NavUrlValidator {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");

    private NavUrlValidator() {
    }

    /**
     * Allows absolute http(s) URLs only.
     */
    public static boolean isSafeHttpUrl(String value) {
        if (!hasText(value)) {
            return false;
        }
        if (CONTROL_CHARS.matcher(value).find()) {
            return false;
        }
        var candidate = value.trim();
        try {
            var uri = URI.create(candidate);
            var scheme = uri.getScheme();
            return uri.getHost() != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Allows http(s) URLs and site-relative paths such as {@code /archives}.
     */
    public static boolean isSafeLinkUrl(String value) {
        if (!hasText(value)) {
            return false;
        }
        if (CONTROL_CHARS.matcher(value).find()) {
            return false;
        }
        var candidate = value.trim();
        if ("#".equals(candidate)) {
            return true;
        }
        if (candidate.startsWith("/")) {
            // Browsers normalize a backslash to "/" for special schemes, so
            // "/\evil.example.com" is interpreted as the protocol-relative
            // "//evil.example.com". Reject it instead of leaving the origin.
            return !candidate.startsWith("//") && candidate.indexOf('\\') < 0;
        }
        return isSafeHttpUrl(candidate);
    }

    /**
     * Allows the same http(s) and site-relative forms as {@link #isSafeLinkUrl(String)},
     * but not the in-page anchor {@code #}: an image/background source must point at a
     * real resource, otherwise the configured media silently renders as nothing.
     */
    public static boolean isSafeImageUrl(String value) {
        if (!hasText(value) || CONTROL_CHARS.matcher(value).find()) {
            return false;
        }
        var candidate = value.trim();
        if (candidate.startsWith("/")) {
            // See isSafeLinkUrl: a backslash would be normalized to "/" by the
            // browser and turn a site-relative media path into another origin.
            return !candidate.startsWith("//") && candidate.indexOf('\\') < 0;
        }
        return isSafeHttpUrl(candidate);
    }

    public static String trim(String value) {
        return hasText(value) ? value.trim() : "";
    }

    private static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }
}
