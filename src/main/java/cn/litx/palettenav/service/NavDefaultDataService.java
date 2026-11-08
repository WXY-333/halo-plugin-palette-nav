package cn.litx.palettenav.service;

import cn.litx.palettenav.dto.NavDefaultData;
import cn.litx.palettenav.extension.NavCategory;
import cn.litx.palettenav.extension.NavLink;
import cn.litx.palettenav.extension.NavQuickLink;
import cn.litx.palettenav.extension.NavSearchEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Extension;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds the bundled default navigation content on a fresh installation.
 *
 * <p>Halo's {@code extensions/*.yaml} resources are applied on every plugin start,
 * which would resurrect deleted entries and overwrite same-named resources on
 * upgrade. Default content is user-owned data, so it is seeded explicitly and
 * only once: when the plugin starts on an instance where all four kinds are
 * empty. A marker ConfigMap records that the decision has been made, so clearing
 * the data later never brings the defaults back.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NavDefaultDataService {

    static final String DEFAULTS_RESOURCE = "data/nav-defaults.json";
    static final String STATE_CONFIG_MAP = "palette-nav-defaults-state";
    static final String STATE_SEEDING = "seeding";
    static final String STATE_INITIALIZED = "initialized";

    /** Jackson 3's shared mapper, matching the console endpoint's JSON handling. */
    private static final JsonMapper JSON_MAPPER = JsonMapper.shared();

    private final ReactiveExtensionClient client;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile NavDefaultData cachedData;

    /**
     * Runs once per plugin start. The check and the seeding happen asynchronously
     * so plugin startup is never blocked on the extension store.
     */
    public void initialize() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        initializeOnce().subscribe(
            unused -> {
            },
            error -> {
                // A failed seed must be retryable: release the once-per-start guard so
                // another initialize() call in this context is not silently ignored.
                started.set(false);
                log.warn("Failed to initialize Palette Nav default data: {}",
                    error.toString());
            }
        );
    }

    /** Package-private so tests can drive the reactive flow without a scheduler. */
    Mono<Void> initializeOnce() {
        return client.fetch(ConfigMap.class, STATE_CONFIG_MAP)
            .map(marker -> isSeeding(marker)
                ? seedDefaults().then(writeMarker(STATE_INITIALIZED))
                : Mono.<Void>empty())
            .defaultIfEmpty(Mono.defer(this::seedWhenNoMarker))
            .flatMap(action -> action);
    }

    /**
     * A marker without a state is the legacy "initialized" marker, and a marker with
     * any other state means the decision has already been made.
     */
    private static boolean isSeeding(ConfigMap marker) {
        var data = marker.getData();
        return data != null && STATE_SEEDING.equals(data.get("state"));
    }

    private Mono<Void> seedWhenNoMarker() {
        return Mono.zip(
                listAll(NavCategory.class),
                listAll(NavLink.class),
                listAll(NavSearchEngine.class),
                listAll(NavQuickLink.class)
            )
            .flatMap(tuple -> isAllEmpty(tuple.getT1(), tuple.getT2(), tuple.getT3(),
                tuple.getT4())
                // The seeding marker is written before the first create, so a
                // transient failure halfway through the import is resumed on the
                // next start instead of leaving a permanently partial set. Existing
                // data means the admin already owns the instance: never mix defaults
                // into it.
                ? writeMarker(STATE_SEEDING)
                    .then(Mono.defer(this::seedDefaults))
                    .then(writeMarker(STATE_INITIALIZED))
                : writeMarker(STATE_INITIALIZED));
    }

    private <E extends Extension> Mono<List<E>> listAll(Class<E> type) {
        Comparator<E> order = Comparator.comparing(NavEntitySupport::nameOf);
        return client.list(type, item -> true, order).collectList();
    }

    private boolean isAllEmpty(List<?> categories, List<?> links, List<?> engines,
        List<?> quickLinks) {
        return categories.isEmpty() && links.isEmpty() && engines.isEmpty()
            && quickLinks.isEmpty();
    }

    private Mono<Void> seedDefaults() {
        var data = load();
        return createCategories(data)
            .then(createLinks(data))
            .then(createEngines(data))
            .then(createQuickLinks(data));
    }

    private Mono<Void> createCategories(NavDefaultData data) {
        return Flux.fromIterable(data.getCategories())
            .concatMap(category -> createIfAbsent(NavCategory.class, category.getName(),
                toCategory(category)))
            .then();
    }

    private Mono<Void> createLinks(NavDefaultData data) {
        return Flux.fromIterable(data.getCategories())
            .concatMap(category -> Flux.fromIterable(category.getLinks())
                .concatMap(link -> createIfAbsent(NavLink.class, link.getName(),
                    toLink(category.getName(), link))))
            .then();
    }

    private Mono<Void> createEngines(NavDefaultData data) {
        return Flux.fromIterable(data.getSearchEngines())
            .concatMap(engine -> createIfAbsent(NavSearchEngine.class, engine.getName(),
                toSearchEngine(engine)))
            .then();
    }

    private Mono<Void> createQuickLinks(NavDefaultData data) {
        return Flux.fromIterable(data.getQuickLinks())
            .concatMap(link -> createIfAbsent(NavQuickLink.class, link.getName(),
                toQuickLink(link)))
            .then();
    }

    /**
     * Creates a bundled default only when its name is still absent. Seeding can be
     * interrupted halfway and resumed on the next start, so entries written by the
     * previous attempt must not fail the retry.
     */
    private <E extends Extension> Mono<Void> createIfAbsent(Class<E> type, String name,
        E extension) {
        return client.fetch(type, name)
            .hasElement()
            .flatMap(exists -> exists ? Mono.<Void>empty() : client.create(extension).then());
    }

    /**
     * Persists the seeding decision. Failures propagate on purpose: continuing without
     * the marker would let a crash halfway through the import leave data that looks
     * user-owned on the next start, and the remaining defaults would never be written.
     * The caller logs a warning and releases the once-per-start guard so the decision
     * can be retried.
     */
    private Mono<Void> writeMarker(String state) {
        return client.fetch(ConfigMap.class, STATE_CONFIG_MAP)
            .flatMap(existing -> {
                existing.setData(markerData(state));
                return client.update(existing);
            })
            .switchIfEmpty(Mono.defer(() -> client.create(marker(state))))
            .then();
    }

    private static ConfigMap marker(String state) {
        var configMap = new ConfigMap();
        var metadata = new Metadata();
        metadata.setName(STATE_CONFIG_MAP);
        configMap.setMetadata(metadata);
        configMap.setData(markerData(state));
        return configMap;
    }

    private static Map<String, String> markerData(String state) {
        return Map.of("state", state, "version", "2");
    }

    private NavDefaultData load() {
        var data = cachedData;
        if (data != null) {
            return data;
        }
        synchronized (this) {
            if (cachedData == null) {
                var resource = new ClassPathResource(DEFAULTS_RESOURCE,
                    getClass().getClassLoader());
                try (var input = resource.getInputStream()) {
                    var json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    var parsed = JSON_MAPPER.readValue(json, NavDefaultData.class);
                    if (parsed == null) {
                        throw new IllegalStateException(
                            "Empty default data resource: " + DEFAULTS_RESOURCE);
                    }
                    cachedData = parsed;
                } catch (IOException error) {
                    throw new UncheckedIOException(
                        "Failed to read " + DEFAULTS_RESOURCE, error);
                }
            }
            return cachedData;
        }
    }

    static NavCategory toCategory(NavDefaultData.Category source) {
        var category = new NavCategory();
        category.setMetadata(metadata(source.getName()));
        var spec = new NavCategory.Spec();
        spec.setDisplayName(source.getDisplayName());
        spec.setIcon(source.getIcon());
        spec.setSort(source.getSort());
        spec.setVisible(source.getVisible());
        category.setSpec(spec);
        return category;
    }

    static NavLink toLink(String categoryName, NavDefaultData.Link source) {
        var link = new NavLink();
        link.setMetadata(metadata(source.getName()));
        var spec = new NavLink.Spec();
        spec.setCategoryName(categoryName);
        spec.setDisplayName(source.getDisplayName());
        spec.setUrl(source.getUrl());
        spec.setIcon(source.getIcon());
        spec.setSort(source.getSort());
        spec.setVisible(source.getVisible());
        link.setSpec(spec);
        return link;
    }

    static NavSearchEngine toSearchEngine(NavDefaultData.SearchEngine source) {
        var engine = new NavSearchEngine();
        engine.setMetadata(metadata(source.getName()));
        var spec = new NavSearchEngine.Spec();
        spec.setAlias(source.getAlias());
        spec.setDisplayName(source.getDisplayName());
        spec.setHint(source.getHint());
        spec.setUrlTemplate(source.getUrlTemplate());
        spec.setMobileUrlTemplate(source.getMobileUrlTemplate());
        spec.setIcon(source.getIcon());
        spec.setSort(source.getSort());
        spec.setVisible(source.getVisible());
        engine.setSpec(spec);
        return engine;
    }

    static NavQuickLink toQuickLink(NavDefaultData.QuickLink source) {
        var link = new NavQuickLink();
        link.setMetadata(metadata(source.getName()));
        var spec = new NavQuickLink.Spec();
        spec.setDisplayName(source.getDisplayName());
        spec.setUrl(source.getUrl());
        spec.setOpenInNewTab(source.getOpenInNewTab());
        spec.setSort(source.getSort());
        spec.setVisible(source.getVisible());
        link.setSpec(spec);
        return link;
    }

    private static Metadata metadata(String name) {
        var metadata = new Metadata();
        metadata.setName(name);
        return metadata;
    }
}