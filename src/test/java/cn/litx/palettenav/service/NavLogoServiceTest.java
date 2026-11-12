package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class NavLogoServiceTest {
    @Test
    void visitorsShareOneDownloadAndChangingSettingsCannotExceedOneDownload() {
        var meta = mock(NavLinkMetaService.class);
        var count = new AtomicInteger();
        var asset = new NavLinkMetaService.FetchedAsset(new byte[] {1}, "png", "image/png");
        when(meta.fetchLogoAsset(anyString())).thenAnswer(invocation -> Mono.defer(() -> {
            count.incrementAndGet();
            return Mono.delay(Duration.ofMillis(30)).thenReturn(asset);
        }));
        var service = new NavLogoService(meta);
        StepVerifier.create(Flux.range(0, 50)
            .flatMap(i -> service.fetch("https://example.com/logo.png"), 50).collectList())
            .assertNext(results -> assertEquals(50, results.size())).verifyComplete();
        assertEquals(1, count.get());

        var first = service.fetch("https://example.com/next.png");
        var second = service.fetch("https://example.com/other.png");
        StepVerifier.create(Flux.merge(first, second).collectList())
            .assertNext(results -> assertEquals(1, results.size())).verifyComplete();
        assertEquals(2, count.get());
    }

    @Test
    void rejectsPrivateLogoHostsBeforeDownloading() {
        var service = new NavLinkMetaService();
        for (var url : java.util.List.of("http://127.0.0.1/logo.png",
            "http://169.254.169.254/logo.svg", "http://[::1]/logo.png")) {
            StepVerifier.create(service.fetchLogoAsset(url)).verifyComplete();
        }
    }
}
