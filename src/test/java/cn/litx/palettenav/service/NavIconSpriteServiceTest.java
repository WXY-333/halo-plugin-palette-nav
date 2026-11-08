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
        var html = service.render(Set.of("icon-remen", "lyicon-doubao"));

        assertTrue(html.startsWith("<svg class=\"icon-sprite\""));
        assertTrue(html.contains("id=\"icon-remen\""));
        assertTrue(html.contains("id=\"lyicon-doubao\""));
        assertFalse(html.contains("id=\"icon-video\""));
    }

    @Test
    void ignoresUnknownAndEmptyIds() {
        assertEquals("", service.render(Set.of()));
        assertEquals("", service.render(Set.of("missing-icon")));
    }

    @Test
    void emitsEachSymbolOnce() {
        var html = service.render(List.of("icon-remen", "icon-remen"));

        assertEquals(1, html.split("id=\"icon-remen\"", -1).length - 1);
    }
}