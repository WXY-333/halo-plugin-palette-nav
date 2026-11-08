package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavIconRendererTest {

    private final NavIconRenderer renderer = new NavIconRenderer();

    @Test
    void rendersSpriteReference() {
        var html = renderer.render(
            "<svg class=\"icon\"><use xlink:href=\"#lyicon-doubao\">\r\n</use></svg>",
            "/default.png");
        assertTrue(html.contains("#lyicon-doubao"));
        assertFalse(html.contains("script"));
    }

    @Test
    void rejectsInlineSvgWithEventHandler() {
        var html = renderer.render(
            "<svg onload=\"alert(1)\"><use xlink:href=\"#lyicon-doubao\"></use></svg>",
            "/default.png");
        assertTrue(html.contains("/default.png"));
        assertFalse(html.contains("onload"));
    }

    @Test
    void rejectsScriptInjection() {
        var html = renderer.render(
            "<svg><script>alert(1)</script><use xlink:href=\"#x\"></use></svg>",
            "/default.png");
        assertTrue(html.contains("/default.png"));
        assertFalse(html.contains("<script"));
    }

    @Test
    void rendersInlineSvgAsDataUrlImage() {
        var html = renderer.render(
            "<svg viewBox=\"0 0 24 24\" xmlns=\"http://www.w3.org/2000/svg\">"
                + "<circle cx=\"12\" cy=\"12\" r=\"10\"/></svg>",
            "/default.png");
        assertTrue(html.startsWith("<img src=\"data:image/svg+xml;base64,"));
        assertFalse(html.contains("<circle"));
        assertFalse(html.contains("/default.png"));
    }

    @Test
    void rejectsInlineSvgWithoutClosingTag() {
        var html = renderer.render("<svg viewBox=\"0 0 24 24\"><circle r=\"10\"/>", "/default.png");
        assertTrue(html.contains("/default.png"));
    }

    @Test
    void rendersImageUrl() {
        var html = renderer.render("https://cdn.example.com/icon.png", "/default.png");
        assertTrue(html.contains("<img"));
        assertTrue(html.contains("https://cdn.example.com/icon.png"));
    }

    @Test
    void rejectsJavascriptUrl() {
        var html = renderer.render("javascript:alert(1)", "/default.png");
        assertTrue(html.contains("/default.png"));
        assertFalse(html.contains("javascript:"));
    }

    @Test
    void rendersEmojiAsText() {
        var html = renderer.render("🚀", "/default.png");
        assertTrue(html.contains("icon-text"));
        assertTrue(html.contains("🚀"));
    }

    @Test
    void usesDefaultForEmptyIcon() {
        var html = renderer.render("", "/default.png");
        assertTrue(html.contains("/default.png"));
    }

    @Test
    void emptyDefaultIconRendersNothing() {
        assertEquals("", renderer.render("", ""));
        assertEquals("", renderer.render("javascript:alert(1)", " "));
    }

    @Test
    void longTextIsNotRenderedAsAnIcon() {
        var html = renderer.render("this text is far too long", "/default.png");
        assertTrue(html.contains("/default.png"));
        assertFalse(html.contains("icon-text"));
    }

    @Test
    void markupOutsideSvgFallsBackToTheDefaultIcon() {
        var html = renderer.render("<img src=x onerror=alert(1)>", "/default.png");
        assertTrue(html.contains("/default.png"));
        assertFalse(html.contains("onerror"));
    }

    @Test
    void escapesAmpersandsInImageUrls() {
        var html = renderer.render("https://cdn.example.com/i.png?a=1&b=2", "/default.png");
        assertTrue(html.contains("https://cdn.example.com/i.png?a=1&amp;b=2"));
    }

    @Test
    void spriteReferencesOnlyKeepSafeIdentifiers() {
        // The emitted id is restricted to [A-Za-z0-9_-], so markup in the id
        // never reaches the attribute.
        var quoted = renderer.render("<svg><use href=\"#a'b\"/></svg>", "/default.png");
        assertFalse(quoted.contains("a'b"));

        // An identifier outside the allow-list is embedded as a data URL.
        var invalid = renderer.render("<svg><use href=\"#bad:id\"/></svg>", "/default.png");
        assertTrue(invalid.startsWith("<img src=\"data:image/svg+xml;base64,"));
    }

    @Test
    void rejectsEventHandlerMarkersCaseInsensitively() {
        var html = renderer.render(
            "<svg ONLOAD=\"alert(1)\"><circle r=\"1\"/></svg>", "/default.png");
        assertTrue(html.contains("/default.png"));
        assertFalse(html.contains("ONLOAD"));
    }
}
