package cn.litx.palettenav.service;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** One bounded cache entry; concurrent visitors share one upstream subscription. */
@Service
@RequiredArgsConstructor
public class NavBackgroundService {
    private final NavLinkMetaService metaService;
    private String cacheKey;
    private Mono<String> cached;
    private Mono<NavLinkMetaService.FetchedAsset> cachedImage;

    public synchronized Mono<String> resolve(String url, String jsonPath, int cacheSeconds) {
        int ttl = Math.max(0, Math.min(86400, cacheSeconds));
        var key = url + "\n" + jsonPath + "\n" + ttl;
        if (!key.equals(cacheKey)) {
            cacheKey = key;
            cached = Mono.defer(() -> metaService.fetchBackgroundImage(url, jsonPath))
                .onErrorResume(error -> Mono.empty())
                // Empty/error outcomes are cached too: outages cannot trigger a request storm.
                .cache(Duration.ofSeconds(ttl));
            cachedImage = Mono.defer(() -> jsonPath.isBlank()
                ? metaService.fetchBackgroundAsset(url)
                : metaService.fetchBackgroundImage(url, jsonPath).flatMap(metaService::fetchBackgroundAsset))
                .onErrorResume(error -> Mono.empty()).cache(Duration.ofSeconds(ttl));
        }
        return cached;
    }

    public synchronized Mono<NavLinkMetaService.FetchedAsset> image(String url, String jsonPath, int cacheSeconds) {
        resolve(url, jsonPath, cacheSeconds);
        return cachedImage;
    }
}
