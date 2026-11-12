package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

class NavBackgroundImageTest {
    private String extract(String json) {
        return NavLinkMetaService.extractBackgroundImage(JsonMapper.shared().readTree(json),
            URI.create("https://example.com/api/random"), 0);
    }

    @Test
    void readsCommonJsonShapes() {
        assertEquals("https://img.example.com/a.jpg", extract("{\"url\":\"https://img.example.com/a.jpg\"}"));
        assertEquals("https://example.com/a.jpg", extract("{\"data\":[{\"imgurl\":\"/a.jpg\"}]}"));
        assertEquals("https://example.com/b.jpg", extract("{\"images\":[\"/b.jpg\"]}"));
        assertEquals("https://example.com/c.jpg", extract("{\"data\":{\"image_url\":\"/c.jpg\"}}"));
        assertEquals("https://example.com/d.jpg", extract("\"https://example.com/d.jpg\""));
    }

    @Test
    void rejectsInvalidAndUnsafeResults() {
        assertNull(extract("{\"code\":200,\"message\":\"ok\"}"));
        assertNull(extract("{\"url\":\"javascript:alert(1)\"}"));
        assertNull(extract("{\"data\":[\"//evil.example/a\", \"data:image/png;base64,abc\"]}"));
        assertNull(extract("{\"url\":\"not an image\"}"));
        assertNull(extract("null"));
        assertEquals("https://example.com/ok.jpg", extract("{\"data\":[null,{\"url\":\"bad\"},{\"url\":\"/ok.jpg\"}]}"));
    }

    @Test
    void blocksPrivateNetworkBeforeConnection() {
        var service = new NavLinkMetaService();
        StepVerifier.create(service.testBackgroundImage("http://127.0.0.1/random"))
            .assertNext(result -> {
                assertFalse(result.success());
                assertTrue(result.message().contains("公网"));
            }).verifyComplete();
        StepVerifier.create(service.fetchBackgroundImage("http://127.0.0.1/random"))
            .verifyComplete();
    }
}
