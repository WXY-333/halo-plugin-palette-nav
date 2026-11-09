package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavFooterSanitizerTest {

    private final NavFooterSanitizer sanitizer = new NavFooterSanitizer();

    @Test
    void removesScriptsAndEventHandlers() {
        var html = sanitizer.sanitize(
            "<script>alert(1)</script><b onclick=\"alert(2)\">ok</b>");

        assertFalse(html.contains("<script"));
        assertFalse(html.contains("alert(1)"));
        assertFalse(html.contains("onclick"));
        assertTrue(html.contains("<b>ok</b>"));
    }

    @Test
    void keepsSafeLinksAndSiteRelativeTargets() {
        var html = sanitizer.sanitize(
            "<a href=\"https://example.com\">site</a>"
                + "<a href=\"/upload/logo.png\">relative</a>"
                + "<a href=\"mailto:hi@example.com\">mail</a>");

        assertTrue(html.contains("href=\"https://example.com\""));
        assertTrue(html.contains("href=\"/upload/logo.png\""));
        assertTrue(html.contains("href=\"mailto:hi@example.com\""));
    }

    @Test
    void dropsUnsafeSchemesMediaAndStyles() {
        var html = sanitizer.sanitize(
            "<a href=\"javascript:alert(1)\">x</a>"
                + "<img src=\"https://example.com/tracker.png\">"
                + "<iframe src=\"https://example.com\"></iframe>"
                + "<p style=\"color:red\">styled</p>"
                + "<div class=\"safe\">text</div>");

        assertFalse(html.contains("javascript:"));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("<iframe"));
        assertFalse(html.contains("style="));
        assertTrue(html.contains("class=\"safe\""));
    }

    @Test
    void keepsPlainTextAndFormatting() {
        assertTrue(sanitizer.sanitize("Copyright © 2026").contains("Copyright"));
        var html = sanitizer.sanitize("<strong>Palette</strong> <em>Nav</em>");
        assertTrue(html.contains("<strong>Palette</strong>"));
        assertTrue(html.contains("<em>Nav</em>"));
    }

    @Test
    void handlesBlankInput() {
        assertEquals("", sanitizer.sanitize(null));
        assertEquals("", sanitizer.sanitize("   "));
    }
}