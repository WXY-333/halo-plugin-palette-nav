package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps the seeded navigation data and the bundled palette sprite in sync.
 */
class NavDefaultIconsTest {

    private static final Pattern SPRITE_REFERENCE =
        Pattern.compile("href=\\\\?\"#([A-Za-z0-9_-]+)");
    private static final Pattern SYMBOL_ID =
        Pattern.compile("<symbol\\b[^>]*\\bid\\s*=\\s*[\"']([A-Za-z0-9_-]+)[\"']");

    @Test
    void everyDefaultIconExistsInThePaletteSprite() throws IOException {
        var defaults = resource("data/nav-defaults.json");
        var sprite = resource("static/nav/vendor/palette-icons.js");

        var referenced = ids(SPRITE_REFERENCE.matcher(defaults));
        var available = ids(SYMBOL_ID.matcher(sprite));

        assertFalse(referenced.isEmpty(), "default data must reference the palette sprite");
        for (var id : referenced) {
            assertTrue(available.contains(id), "missing sprite symbol: " + id);
            assertTrue(id.startsWith("pn-"), "unexpected non-palette icon id: " + id);
        }
        assertFalse(defaults.contains("lyicon-"), "default data still references the old sprite");
    }

    private static Set<String> ids(java.util.regex.Matcher matcher) {
        var result = new LinkedHashSet<String>();
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = NavDefaultIconsTest.class.getClassLoader()
            .getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing classpath resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}