package cn.litx.palettenav.config;

import cn.litx.palettenav.config.NavSettings.Appearance;
import cn.litx.palettenav.config.NavSettings.General;
import cn.litx.palettenav.config.NavSettings.Icons;
import cn.litx.palettenav.config.NavSettings.Search;
import cn.litx.palettenav.config.NavSettings.Snapshot;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the four setting groups from the plugin-owned business ConfigMap and always
 * falls back to safe defaults.
 *
 * <p>The ConfigMap is read directly instead of through Halo's
 * {@code ReactiveSettingFetcher}: that fetcher is bound to {@code spec.configMapName},
 * and this plugin intentionally does not declare it, because Halo's "uninstall and
 * delete config" deletes that ConfigMap while these settings are business data that
 * must survive an uninstall / reinstall.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavSettingsService {

    /** Jackson 3's shared mapper, matching how Halo's own setting fetcher parses groups. */
    private static final JsonMapper JSON_MAPPER = JsonMapper.shared();

    private final ReactiveExtensionClient client;

    public Mono<Snapshot> get() {
        return client.fetch(ConfigMap.class, NavSettings.CONFIG_MAP_NAME)
            .map(configMap -> snapshot(configMap.getData()))
            .defaultIfEmpty(Snapshot.defaults())
            .onErrorResume(error -> {
                log.warn("Failed to read Palette Nav settings, using defaults: {}",
                    error.toString());
                return Mono.just(Snapshot.defaults());
            });
    }

    private static Snapshot snapshot(Map<String, String> data) {
        var values = data == null ? Map.<String, String>of() : data;
        return new Snapshot(
            group(values, NavSettings.GROUP_GENERAL, General.class, General.defaults())
                .normalized(),
            group(values, NavSettings.GROUP_APPEARANCE, Appearance.class,
                Appearance.defaults()).normalized(),
            group(values, NavSettings.GROUP_SEARCH, Search.class, Search.defaults())
                .normalized(),
            group(values, NavSettings.GROUP_ICONS, Icons.class, Icons.defaults()).normalized()
        );
    }

    /**
     * Parses one config group; a missing, blank or malformed value falls back to the
     * group's defaults instead of failing the whole page.
     */
    private static <T> T group(Map<String, String> data, String group, Class<T> type,
        T fallback) {
        var json = data.get(group);
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            var parsed = JSON_MAPPER.readValue(json, type);
            return parsed == null ? fallback : parsed;
        } catch (JacksonException error) {
            log.warn("Failed to parse setting group {}, using defaults: {}", group,
                error.toString());
            return fallback;
        }
    }
}
