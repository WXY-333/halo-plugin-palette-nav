package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavMediaTypeTest {

    @Test
    void recognizesSupportedVideoExtensionsCaseInsensitively() {
        assertTrue(NavMediaType.isVideoUrl("https://example.com/clip.MP4"));
        assertTrue(NavMediaType.isVideoUrl("/upload/bg.webm"));
        assertTrue(NavMediaType.isVideoUrl("https://cdn.example.com/a.ogg?token=1"));
        assertTrue(NavMediaType.isVideoUrl("https://cdn.example.com/a.MOV#t=1"));
    }

    @Test
    void treatsNonVideoExtensionsAsImages() {
        assertFalse(NavMediaType.isVideoUrl("https://example.com/bg.jpg"));
        assertFalse(NavMediaType.isVideoUrl("https://example.com/bg.png?format=webp"));
        assertFalse(NavMediaType.isVideoUrl("https://example.com/no-extension"));
        assertFalse(NavMediaType.isVideoUrl("https://example.com/trailing."));
        assertFalse(NavMediaType.isVideoUrl("/upload/a.mp4/"));
        assertFalse(NavMediaType.isVideoUrl(""));
        assertFalse(NavMediaType.isVideoUrl(null));
    }

    @Test
    void explicitKindWinsAndIsNormalized() {
        assertEquals("video", NavMediaType.normalize("VIDEO", "/bg.jpg"));
        assertEquals("image", NavMediaType.normalize(" image ", "/bg.mp4"));
        assertEquals("video", NavMediaType.normalize("vIdEo", ""));
    }

    @Test
    void invalidExplicitKindIsInferredFromUrl() {
        assertEquals("video", NavMediaType.normalize("gif", "/bg.mp4"));
        assertEquals("image", NavMediaType.normalize("gif", "/bg.jpg"));
        assertEquals("image", NavMediaType.normalize(null, null));
        assertEquals("image", NavMediaType.normalize("", "https://example.com/background"));
    }

    @Test
    void isValidOnlyAcceptsTheTwoKinds() {
        assertTrue(NavMediaType.isValid("image"));
        assertTrue(NavMediaType.isValid("VIDEO"));
        assertFalse(NavMediaType.isValid("gif"));
        assertFalse(NavMediaType.isValid(""));
        assertFalse(NavMediaType.isValid(null));
    }
}