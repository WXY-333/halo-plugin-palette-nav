package cn.litx.palettenav.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;

/**
 * Reads the Halo site title so the navigation page can fall back to it when the
 * plugin-specific setting is left empty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavSiteInfoService {

    private final ReactiveExtensionClient client;

    public Mono<SiteInfo> get() {
        return Mono.zip(
                fetchBasic(SystemSetting.SYSTEM_CONFIG_DEFAULT),
                fetchBasic(SystemSetting.SYSTEM_CONFIG)
            )
            .map(tuple -> new SiteInfo(
                firstText(tuple.getT2().getTitle(), tuple.getT1().getTitle())
            ))
            .onErrorResume(error -> {
                log.warn("Failed to read Halo site settings, using empty fallback: {}",
                    error.toString());
                return Mono.just(SiteInfo.EMPTY);
            });
    }

    private Mono<SystemSetting.Basic> fetchBasic(String configMapName) {
        return client.fetch(ConfigMap.class, configMapName)
            .flatMap(configMap -> {
                var data = configMap.getData();
                var json = data == null ? null : data.get(SystemSetting.Basic.GROUP);
                if (!StringUtils.hasText(json)) {
                    return Mono.empty();
                }
                return Mono.justOrEmpty(
                    SystemSetting.get(data, SystemSetting.Basic.GROUP,
                        SystemSetting.Basic.class));
            })
            .defaultIfEmpty(new SystemSetting.Basic());
    }

    private static String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first.trim()
            : (StringUtils.hasText(second) ? second.trim() : "");
    }

    public record SiteInfo(String title) {
        public static final SiteInfo EMPTY = new SiteInfo("");
    }
}
