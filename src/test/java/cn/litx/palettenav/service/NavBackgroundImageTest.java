package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

class NavBackgroundImageTest {
    private String extract(String json, String path) {
        return NavLinkMetaService.extractBackgroundImage(JsonMapper.shared().readTree(json),
            URI.create("https://example.com/api/random"), path);
    }

    @Test
    void readsConfiguredPathsOnly() {
        assertEquals("https://example.com/root.jpg", extract("[{\"url\":\"/root.jpg\"}]", "$[0].url"));
        assertEquals("https://example.com/a.jpg", extract("{\"url\":\"/a.jpg\"}", "url"));
        assertEquals("https://example.com/b.jpg", extract("{\"data\":[{\"imgurl\":\"/b.jpg\"}]}", "data[0].imgurl"));
        assertEquals("https://example.com/c.jpg", extract("{\"custom\":{\"photo\":\"/c.jpg\"}}", "$.custom.photo"));
        assertEquals("https://example.com/d.jpg", extract("\"https://example.com/d.jpg\"", "$"));
        assertNull(extract("{\"url\":\"/a.jpg\"}", "image"));
        assertNull(extract("{\"url\":\"javascript:alert(1)\"}", "url"));
        assertNull(extract("{\"url\":\"//evil.example/a\"}", "url"));
        assertNull(extract("null", "url"));
        assertNull(extract("{}", "data[*].url"));
        assertNull(extract("{\"data\":[]}", "data[9999999999999999999999].url"));
    }

    @Test
    void discoversFieldsWithoutHardcodedNamesAndEverySuggestionCanBeExtracted() {
        var root = JsonMapper.shared().readTree("""
            {"custom":{"photos":[{"picture":"/one.jpg"},{"picture":"https://example.com/two.webp"}]},
             "text":"not an image", "unsafe":"javascript:alert(1)", "bad.key":"/ignored.jpg"}
            """);
        var base = URI.create("https://example.com/api");
        var fields = NavLinkMetaService.discoverBackgroundImages(root, base);
        assertEquals(java.util.List.of("custom.photos[0].picture", "custom.photos[1].picture"),
            fields.stream().map(cn.litx.palettenav.dto.NavBackgroundCandidate::path).toList());
        for (var field : fields) {
            assertEquals(field.url(), NavLinkMetaService.extractBackgroundImage(root, base, field.path()));
        }
        var array = JsonMapper.shared().readTree("[\"/one.jpg\"]");
        assertEquals("[0]", NavLinkMetaService.discoverBackgroundImages(array, base).getFirst().path());
        assertEquals("https://example.com/one.jpg", NavLinkMetaService.extractBackgroundImage(array, base, "[0]"));
    }

    @Test
    void candidateDiscoveryIsBoundedAndPrivateCandidatesAreNotReturned() {
        var root = JsonMapper.shared().createArrayNode();
        for (int i = 0; i < 1000; i++) root.add("https://example.com/" + i + ".jpg");
        assertEquals(12, NavLinkMetaService.discoverBackgroundImages(root, URI.create("https://example.com")).size());
        var candidate = new cn.litx.palettenav.dto.NavBackgroundCandidate("url", "http://127.0.0.1/private.png");
        var result = new cn.litx.palettenav.dto.NavBackgroundTestResult(false, "json", "", "",
            java.util.List.of(candidate), "{}", "");
        StepVerifier.create(new NavLinkMetaService().prepareBackgroundTest(result))
            .assertNext(filtered -> {
                assertTrue(filtered.candidates().isEmpty());
                assertFalse(filtered.success());
                assertEquals("", filtered.url());
            }).verifyComplete();
    }

    @Test
    void rejectsPrivateAndReservedJsonImageTargets() {
        var service = new NavLinkMetaService();
        for (var url : java.util.List.of("http://127.0.0.1/image", "http://10.0.0.1/image",
            "http://169.254.169.254/latest/meta-data", "http://192.0.2.1/image",
            "http://[::1]/image", "http://[fc00::1]/image", "http://localhost/image")) {
            StepVerifier.create(service.publicBackgroundUrl(url)).verifyComplete();
        }
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
