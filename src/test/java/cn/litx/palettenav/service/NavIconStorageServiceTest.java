package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavIconStorageServiceTest {

    @Test
    void filenameUsesTheIconHostAndDetectedExtension() {
        var filename = NavIconStorageService.filename(
            "https://www.example.com/favicon.ico?v=2", "png");
        assertTrue(filename.matches("www\\.example\\.com-icon-[0-9a-f]{8}\\.png"));
        assertTrue(filename.endsWith(".png"));
    }

    @Test
    void filenameIsUniqueForDifferentIconsOnTheSameHost() {
        // Both are PNG after signature detection; only the source hash keeps the
        // object keys apart on a storage policy that uses the filename as the key.
        var favicon = NavIconStorageService.filename(
            "https://www.example.com/favicon.ico", "png");
        var touchIcon = NavIconStorageService.filename(
            "https://www.example.com/apple-touch-icon.png", "png");
        assertNotEquals(favicon, touchIcon);
        assertTrue(favicon.startsWith("www.example.com-icon-"));
        assertTrue(touchIcon.startsWith("www.example.com-icon-"));
    }

    @Test
    void filenameSanitizesUnusableHosts() {
        var filename = NavIconStorageService.filename("not a url", "svg");
        assertTrue(filename.matches("site-icon-[0-9a-f]{8}\\.svg"));
    }

    @Test
    void filenameIsStableForTheSameIconUrl() {
        var first = NavIconStorageService.filename("https://example.com/a.png", "png");
        var second = NavIconStorageService.filename("https://example.com/a.png", "png");
        assertEquals(first, second);
    }
}