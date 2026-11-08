package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.litx.palettenav.dto.ReorderRequest;
import cn.litx.palettenav.extension.NavCategory;
import cn.litx.palettenav.extension.NavLink;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ReactiveExtensionClient;

class NavConsoleServiceTest {

    private ReactiveExtensionClient client;
    private NavConsoleService service;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        service = new NavConsoleService(client);
    }

    @Test
    void reorderRejectsMissingParameters() {
        assertThrows(IllegalArgumentException.class, () -> service.reorder(null).block());
        assertThrows(IllegalArgumentException.class,
            () -> service.reorder(request(" ", item("a", 10))).block());
        assertThrows(IllegalArgumentException.class,
            () -> service.reorder(request("NavCategory", (ReorderRequest.Item) null)).block());
        assertThrows(IllegalArgumentException.class,
            () -> service.reorder(request("NavCategory")).block());
        verify(client, never()).update(any(Extension.class));
    }

    @Test
    void reorderRejectsUnknownKind() {
        assertThrows(IllegalArgumentException.class,
            () -> service.reorder(request("NavPost", item("a", 10))).block());
        verify(client, never()).update(any(Extension.class));
    }

    @Test
    void reorderUpdatesEachEntityAndDefaultsNullSortToZero() {
        var first = NavEntitySupportTest.category("first", 0, null);
        var second = NavEntitySupportTest.category("second", 99, null);
        when(client.fetch(NavCategory.class, "first")).thenReturn(Mono.just(first));
        when(client.fetch(NavCategory.class, "second")).thenReturn(Mono.just(second));
        when(client.update(first)).thenReturn(Mono.just(first));
        when(client.update(second)).thenReturn(Mono.just(second));

        service.reorder(request("NavCategory", item("first", 10), item("second", null)))
            .block();

        assertEquals(10, first.getSpec().getSort());
        assertEquals(0, second.getSpec().getSort());
        verify(client).update(first);
        verify(client).update(second);
    }

    @Test
    void reorderSkipsBlankAndMissingItems() {
        var link = NavEntitySupportTest.link("link-1", 0, null);
        when(client.fetch(NavLink.class, "link-1")).thenReturn(Mono.just(link));
        when(client.update(link)).thenReturn(Mono.just(link));

        service.reorder(request("NavLink", null, item(" ", 5), item("link-1", 20)))
            .block();

        assertEquals(20, link.getSpec().getSort());
        verify(client, times(1)).update(any(NavLink.class));
    }

    @Test
    void reorderIgnoresEntitiesThatNoLongerExist() {
        when(client.fetch(NavLink.class, "gone")).thenReturn(Mono.empty());

        service.reorder(request("NavLink", item("gone", 10))).block();

        verify(client, never()).update(any(Extension.class));
    }

    @Test
    void deleteCategoryFailsWhenCategoryIsMissing() {
        when(client.fetch(NavCategory.class, "missing")).thenReturn(Mono.empty());

        assertThrows(NavNotFoundException.class,
            () -> service.deleteCategory("missing").block());
    }

    @Test
    void deleteCategoryCascadesLinksThenWaitsForGone() {
        var category = NavEntitySupportTest.category("cat", 0, null);
        var first = NavEntitySupportTest.link("link-1", 0, null);
        var second = NavEntitySupportTest.link("link-2", 10, null);
        when(client.fetch(NavCategory.class, "cat"))
            .thenReturn(Mono.just(category), Mono.empty());
        stubLinks(first, second);
        when(client.delete(first)).thenReturn(Mono.just(first));
        when(client.delete(second)).thenReturn(Mono.just(second));
        when(client.delete(category)).thenReturn(Mono.just(category));

        service.deleteCategory("cat").block();

        verify(client, times(2)).fetch(NavCategory.class, "cat");
        verify(client).delete(first);
        verify(client).delete(second);
        verify(client).delete(category);
    }

    @Test
    void deleteCategoryReportsTheLinkThatFailed() {
        var category = NavEntitySupportTest.category("cat", 0, null);
        var link = NavEntitySupportTest.link("link-1", 0, null);
        when(client.fetch(NavCategory.class, "cat")).thenReturn(Mono.just(category));
        stubLinks(link);
        when(client.delete(link)).thenReturn(Mono.error(new RuntimeException("boom")));
        // The category publisher is assembled eagerly, so stub it and assert it
        // was never subscribed to.
        var categoryDeleted = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(client.delete(category)).thenReturn(Mono.fromSupplier(() -> {
            categoryDeleted.set(true);
            return category;
        }));

        var error = assertThrows(IllegalStateException.class,
            () -> service.deleteCategory("cat").block());

        assertTrue(error.getMessage().contains("link-1"));
        assertFalse(categoryDeleted.get());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubLinks(NavLink... links) {
        when(client.list(eq(NavLink.class), any(Predicate.class), any(Comparator.class)))
            .thenReturn(Flux.fromIterable(Arrays.asList(links)));
    }

    private static ReorderRequest request(String kind, ReorderRequest.Item... items) {
        var request = new ReorderRequest();
        request.setKind(kind);
        // Arrays.asList, unlike List.of, tolerates the null entry used by a test.
        request.setItems(items == null ? null : Arrays.asList(items));
        return request;
    }

    private static ReorderRequest.Item item(String name, Integer sort) {
        var item = new ReorderRequest.Item();
        item.setName(name);
        item.setSort(sort);
        return item;
    }
}