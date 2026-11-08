package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavUrlValidatorTest {

    @Test
    void acceptsHttpAndHttps() {
        assertTrue(NavUrlValidator.isSafeHttpUrl("https://www.example.com/path?q=1"));
        assertTrue(NavUrlValidator.isSafeHttpUrl("http://example.com"));
    }

    @Test
    void acceptsSiteRelativePaths() {
        assertTrue(NavUrlValidator.isSafeLinkUrl("/archives"));
        assertTrue(NavUrlValidator.isSafeLinkUrl("/nav?x=1"));
    }

    @Test
    void rejectsDangerousSchemes() {
        assertFalse(NavUrlValidator.isSafeLinkUrl("javascript:alert(1)"));
        assertFalse(NavUrlValidator.isSafeLinkUrl("data:text/html;base64,abc"));
        assertFalse(NavUrlValidator.isSafeLinkUrl("file:///etc/passwd"));
        assertFalse(NavUrlValidator.isSafeHttpUrl("javascript:alert(1)"));
    }

    @Test
    void rejectsProtocolRelativeAndBlankValues() {
        assertFalse(NavUrlValidator.isSafeLinkUrl("//evil.example.com"));
        assertFalse(NavUrlValidator.isSafeLinkUrl(""));
        assertFalse(NavUrlValidator.isSafeLinkUrl(null));
    }

    @Test
    void rejectsControlCharacters() {
        assertFalse(NavUrlValidator.isSafeLinkUrl("https://example.com/\u0000"));
    }

    @Test
    void acceptsUppercaseSchemesAndTrimsSurroundingWhitespace() {
        assertTrue(NavUrlValidator.isSafeHttpUrl("  HTTPS://Example.COM/Path  "));
        assertTrue(NavUrlValidator.isSafeLinkUrl("  /archives  "));
        assertTrue(NavUrlValidator.isSafeImageUrl("  https://cdn.example.com/a.png  "));
    }

    @Test
    void rejectsHostlessAndBackslashUrls() {
        assertFalse(NavUrlValidator.isSafeHttpUrl("https:///path"));
        assertFalse(NavUrlValidator.isSafeHttpUrl("https://"));
        assertFalse(NavUrlValidator.isSafeLinkUrl("\\evil.example.com"));
        assertFalse(NavUrlValidator.isSafeLinkUrl("/\\evil.example.com"));
        assertFalse(NavUrlValidator.isSafeLinkUrl("jav\tascript:alert(1)"));
    }

    @Test
    void trimsOnlyWhenThereIsText() {
        assertTrue(NavUrlValidator.trim(null).isEmpty());
        assertTrue(NavUrlValidator.trim("   ").isEmpty());
        assertEquals("x", NavUrlValidator.trim("  x  "));
    }

    @Test
    void imageUrlsRejectInPageAnchorsButLinksKeepThem() {
        // "#" is a valid link target but never a real image/background source.
        assertTrue(NavUrlValidator.isSafeLinkUrl("#"));
        assertFalse(NavUrlValidator.isSafeImageUrl("#"));
        assertFalse(NavUrlValidator.isSafeImageUrl("  #  "));
        assertTrue(NavUrlValidator.isSafeImageUrl("/upload/logo.png"));
        assertTrue(NavUrlValidator.isSafeImageUrl("https://cdn.example.com/bg.jpg"));
        assertFalse(NavUrlValidator.isSafeImageUrl("//cdn.example.com/bg.jpg"));
    }
}
