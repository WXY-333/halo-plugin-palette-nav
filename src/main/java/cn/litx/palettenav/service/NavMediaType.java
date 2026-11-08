package cn.litx.palettenav.service;

import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * Classifies the merged background setting into an image or a video.
 *
 * <p>The front-end picker stores the kind explicitly; the extension check only
 * covers legacy configs and links typed without an explicit kind. Mirrors
 * {@code ui/src/utils/media.ts}.
 */
public final class NavMediaType {

    public static final String IMAGE = "image";
    public static final String VIDEO = "video";

    private static final Set<String> VIDEO_EXTENSIONS =
        Set.of("mp4", "webm", "ogg", "ogv", "mov", "m4v");

    private NavMediaType() {
    }

    public static boolean isValid(String type) {
        return IMAGE.equalsIgnoreCase(type) || VIDEO.equalsIgnoreCase(type);
    }

    public static boolean isVideoUrl(String url) {
        var path = StringUtils.hasText(url) ? url.trim() : "";
        if (path.isEmpty()) {
            return false;
        }
        var query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        var fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        var dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return false;
        }
        return VIDEO_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Keeps a valid explicit kind, otherwise infers it from the URL.
     */
    public static String normalize(String type, String url) {
        var candidate = StringUtils.hasText(type) ? type.trim() : "";
        if (isValid(candidate)) {
            return candidate.toLowerCase(Locale.ROOT);
        }
        return isVideoUrl(url) ? VIDEO : IMAGE;
    }
}