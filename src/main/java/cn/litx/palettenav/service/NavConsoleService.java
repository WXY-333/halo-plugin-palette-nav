package cn.litx.palettenav.service;

import cn.litx.palettenav.dto.ReorderRequest;
import cn.litx.palettenav.extension.NavCategory;
import cn.litx.palettenav.extension.NavLink;
import cn.litx.palettenav.extension.NavQuickLink;
import cn.litx.palettenav.extension.NavSearchEngine;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Console-only bulk operations that the generated CRUD API cannot express:
 * reordering sort values and cascade-deleting a category.
 */
@Service
@RequiredArgsConstructor
public class NavConsoleService {

    private final ReactiveExtensionClient client;

    public Mono<Void> reorder(ReorderRequest request) {
        if (request == null || !StringUtils.hasText(request.getKind())
            || request.getItems() == null || request.getItems().isEmpty()) {
            return Mono.error(new IllegalArgumentException("排序参数不能为空"));
        }
        // Reactor rejects null elements emitted by an Iterable, so drop them before
        // building the flux; otherwise one null in the JSON array would answer 500
        // instead of skipping the invalid entry.
        var items = request.getItems().stream().filter(Objects::nonNull).toList();
        if (items.isEmpty()) {
            return Mono.error(new IllegalArgumentException("排序参数不能为空"));
        }
        return Flux.fromIterable(items)
            .concatMap(item -> {
                if (!StringUtils.hasText(item.getName())) {
                    return Mono.empty();
                }
                return applySort(request.getKind(), item.getName().trim(),
                    item.getSort() == null ? 0 : item.getSort());
            })
            .then();
    }

    public Mono<Void> deleteCategory(String name) {
        return client.fetch(NavCategory.class, name)
            .switchIfEmpty(Mono.error(new NavNotFoundException("分类不存在: " + name)))
            .flatMap(category -> client.list(NavLink.class,
                    link -> link.getSpec() != null
                        && name.equals(link.getSpec().getCategoryName()),
                    NavEntitySupport.linkOrder())
                .concatMap(link -> client.delete(link)
                    .onErrorMap(error -> new IllegalStateException(
                        "删除链接 " + NavEntitySupport.nameOf(link) + " 失败: "
                            + message(error), error)))
                .then(client.delete(category)))
            // Halo applies deletion asynchronously through finalizers. Poll briefly so the
            // console does not immediately list a category that is already being deleted.
            .then(awaitCategoryGone(name));
    }

    private Mono<Void> awaitCategoryGone(String name) {
        return Flux.range(0, 25)
            .concatMap(attempt -> Mono.delay(Duration.ofMillis(100))
                .then(client.fetch(NavCategory.class, name).hasElement()
                    // The delete already succeeded; a transient read failure must not turn it
                    // into an error response.
                    .onErrorReturn(false)))
            .takeWhile(Boolean::booleanValue)
            .then();
    }

    private <E extends Extension, S> Mono<Void> applySort(
        Class<E> type, String name, int sort, Function<E, S> specGetter,
        BiConsumer<E, S> specSetter, Supplier<S> specFactory,
        BiConsumer<S, Integer> sortSetter) {
        return client.fetch(type, name)
            // Halo deletes asynchronously; do not update an entity that is already
            // being finalized by a concurrent delete.
            .filter(NavEntitySupport::alive)
            .flatMap(entity -> {
                var spec = specGetter.apply(entity);
                if (spec == null) {
                    spec = specFactory.get();
                    specSetter.accept(entity, spec);
                }
                sortSetter.accept(spec, sort);
                return client.update(entity);
            })
            .then();
    }

    private Mono<Void> applySort(String kind, String name, int sort) {
        return switch (kind) {
            case "NavCategory" -> applySort(NavCategory.class, name, sort,
                NavCategory::getSpec, NavCategory::setSpec, NavCategory.Spec::new,
                NavCategory.Spec::setSort);
            case "NavLink" -> applySort(NavLink.class, name, sort,
                NavLink::getSpec, NavLink::setSpec, NavLink.Spec::new, NavLink.Spec::setSort);
            case "NavSearchEngine" -> applySort(NavSearchEngine.class, name, sort,
                NavSearchEngine::getSpec, NavSearchEngine::setSpec,
                NavSearchEngine.Spec::new, NavSearchEngine.Spec::setSort);
            case "NavQuickLink" -> applySort(NavQuickLink.class, name, sort,
                NavQuickLink::getSpec, NavQuickLink::setSpec, NavQuickLink.Spec::new,
                NavQuickLink.Spec::setSort);
            default -> Mono.error(new IllegalArgumentException("不支持的排序类型: " + kind));
        };
    }

    private String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}