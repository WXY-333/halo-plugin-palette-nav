package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NavIconSpriteServiceTest {

    private final NavIconSpriteService service = new NavIconSpriteService();

    @Test
    void rendersOnlyTheRequestedSymbols() {
        var html = service.render(Set.of("pn-common", "pn-dev"));

        assertTrue(html.startsWith("<svg class=\"icon-sprite\""));
        assertTrue(html.contains("id=\"pn-common\""));
        assertTrue(html.contains("id=\"pn-dev\""));
        assertFalse(html.contains("id=\"pn-video\""));
    }

    @Test
    void ignoresUnknownAndEmptyIds() {
        assertEquals("", service.render(Set.of()));
        assertEquals("", service.render(Set.of("missing-icon")));
    }

    @Test
    void emitsCleanSvgWithoutJsConcatenationArtifacts() {
        var html = service.render(Set.of("pn-cat-games", "pn-video"));

        assertTrue(html.contains("fill=\"#8B5CF6\""));
        assertFalse(html.contains("' + '"),
            "public page sprite must not contain JS string concatenation");
        assertFalse(html.contains("</symbol>'"));
    }

    @Test
    void emitsEachSymbolOnce() {
        var html = service.render(List.of("pn-common", "pn-common"));

        assertEquals(1, html.split("id=\"pn-common\"", -1).length - 1);
    }

    @Test
    void mapsLegacyIdsToCategoryLogosOrTheGenericLinkGlyph() {
        var html = service.render(Set.of("lyicon-doubao", "icon-remen"));

        assertTrue(html.contains("id=\"lyicon-doubao\""));
        assertTrue(html.contains("id=\"icon-remen\""));
        // The seeded category id gets the matching coloured logo.
        assertTrue(html.contains("fill=\"#5B6CFF\""));
        // The generic glyph is emitted under the legacy id, not as pn-link.
        assertFalse(html.contains("id=\"pn-link\""));
    }
}