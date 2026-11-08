package cn.litx.palettenav.config;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Makes sure the plugin-owned business settings ConfigMap exists.
 *
 * <p>The ConfigMap is intentionally not declared as {@code spec.configMapName} in
 * plugin.yaml: Halo's "uninstall and delete config" action deletes
 * {@code spec.configMapName} / {@code spec.settingName}, but these navigation
 * settings are business data and must survive an uninstall or reinstall. Because
 * Halo therefore never creates it, this initializer creates it on every plugin
 * start (fresh install, or after the ConfigMap was deleted manually).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavSettingsConfigInitializer {

    private final ReactiveExtensionClient client;

    /**
     * Runs once per plugin start. The check and creation happen asynchronously so
     * plugin startup is never blocked on the extension store.
     */
    public void initialize() {
        initializeOnce().subscribe(
            unused -> {
            },
            error -> log.warn("Failed to initialize Palette Nav settings ConfigMap: {}",
                error.toString())
        );
    }

    /** Package-private so tests can drive the reactive flow without a scheduler. */
    Mono<Void> initializeOnce() {
        return client.fetch(ConfigMap.class, NavSettings.CONFIG_MAP_NAME)
            .switchIfEmpty(Mono.defer(
                () -> client.create(empty(NavSettings.CONFIG_MAP_NAME))))
            .then();
    }

    private static ConfigMap empty(String name) {
        var configMap = new ConfigMap();
        var metadata = new Metadata();
        metadata.setName(name);
        configMap.setMetadata(metadata);
        // Empty settings groups fall back to NavSettings.defaults() on the server and
        // DEFAULT_VALUES on the console; no defaults need to be persisted here.
        configMap.setData(Map.of());
        return configMap;
    }
}
