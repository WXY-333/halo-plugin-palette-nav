package cn.litx.palettenav.service;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** A bounded, shared cache for the configured remote logo; never accepts visitor URLs. */
@Service
@RequiredArgsConstructor
public class NavLogoService {
    private final NavLinkMetaService metaService;
    private final java.util.concurrent.Semaphore slot = new java.util.concurrent.Semaphore(1);
    private String key;
    private Mono<NavLinkMetaService.FetchedAsset> cached;

    public synchronized Mono<NavLinkMetaService.FetchedAsset> fetch(String url) {
        if (!url.equals(key)) {
            key = url;
            cached = Mono.defer(() -> {
                if (!slot.tryAcquire()) return Mono.empty();
                return Mono.defer(() -> metaService.fetchLogoAsset(url))
                    .doFinally(signal -> slot.release());
            }).onErrorResume(error -> Mono.empty()).cache(Duration.ofMinutes(5));
        }
        return cached;
    }
}
