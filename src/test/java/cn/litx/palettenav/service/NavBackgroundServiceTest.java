package cn.litx.palettenav.service;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class NavBackgroundServiceTest {
    @Test
    void directImageBytesAreCachedForConfiguredDuration() {
        var meta = mock(NavLinkMetaService.class);
        var asset = new NavLinkMetaService.FetchedAsset(new byte[] {1, 2}, "png", "image/png");
        when(meta.fetchBackgroundAsset(anyString())).thenReturn(Mono.just(asset));
        var service = new NavBackgroundService(meta);
        StepVerifier.withVirtualTime(() -> service.image("https://example.com/random", "", 5)
            .then(service.image("https://example.com/random", "", 5))
            .then(Mono.fromRunnable(() -> verify(meta, times(1)).fetchBackgroundAsset(anyString())))
            .then(Mono.delay(Duration.ofSeconds(6)))
            .then(service.image("https://example.com/random", "", 5)))
            .thenAwait(Duration.ofSeconds(6)).expectNext(asset).verifyComplete();
        verify(meta, times(2)).fetchBackgroundAsset(anyString());
    }

    @Test
    void concurrentVisitorsShareOneUpstreamRequest() {
        var meta = mock(NavLinkMetaService.class);
        var subscriptions = new AtomicInteger();
        when(meta.fetchBackgroundImage("https://example.com/api", "data.url"))
            .thenReturn(Mono.defer(() -> {
                subscriptions.incrementAndGet();
                return Mono.delay(Duration.ofMillis(30)).thenReturn("https://example.com/a.jpg");
            }));
        var service = new NavBackgroundService(meta);
        var results = Flux.range(0, 50)
            .flatMap(i -> service.resolve("https://example.com/api", "data.url", 60), 50)
            .collectList().block();
        assertEquals(50, results.size());
        assertEquals(1, subscriptions.get());
    }

    @Test
    void changingCacheDurationInvalidatesExistingEntry() {
        var meta = mock(NavLinkMetaService.class);
        when(meta.fetchBackgroundImage(anyString(), anyString())).thenReturn(Mono.just("https://example.com/a.jpg"));
        var service = new NavBackgroundService(meta);
        service.resolve("https://example.com/api", "url", 60).block();
        service.resolve("https://example.com/api", "url", 60).block();
        service.resolve("https://example.com/api", "url", 10).block();
        verify(meta, times(2)).fetchBackgroundImage(anyString(), anyString());
    }

    @Test
    void cachesFailuresAndRefreshesAfterTtl() {
        var meta = mock(NavLinkMetaService.class);
        when(meta.fetchBackgroundImage(anyString(), anyString())).thenReturn(Mono.empty());
        var service = new NavBackgroundService(meta);
        StepVerifier.withVirtualTime(() -> service.resolve("https://example.com/api", "url", 5)
            .then(Mono.delay(Duration.ofSeconds(4)))
            .then(service.resolve("https://example.com/api", "url", 5))
            .then(Mono.fromRunnable(() -> verify(meta, times(1)).fetchBackgroundImage(anyString(), anyString())))
            .then(Mono.delay(Duration.ofSeconds(2)))
            .then(service.resolve("https://example.com/api", "url", 5)))
            .thenAwait(Duration.ofSeconds(6)).verifyComplete();
        verify(meta, times(2)).fetchBackgroundImage(anyString(), anyString());
    }
}
