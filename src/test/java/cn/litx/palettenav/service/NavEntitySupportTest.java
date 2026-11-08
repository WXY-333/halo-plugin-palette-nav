package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.litx.palettenav.extension.NavCategory;
import cn.litx.palettenav.extension.NavLink;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;

class NavEntitySupportTest {

    @Test
    void aliveRejectsNullsAndDeletingEntities() {
        assertFalse(NavEntitySupport.alive(null));
        assertFalse(NavEntitySupport.alive(new NavCategory()));

        var deleting = category("deleting", 0, null);
        deleting.getMetadata().setDeletionTimestamp(Instant.now());
        assertFalse(NavEntitySupport.alive(deleting));

        assertTrue(NavEntitySupport.alive(category("live", 0, null)));
    }

    @Test
    void visibleDefaultsToTrueForNullAndMissingFlags() {
        assertTrue(NavEntitySupport.isVisible(null));
        assertTrue(NavEntitySupport.isVisible(true));
        assertFalse(NavEntitySupport.isVisible(false));
    }

    @Test
    void sortDefaultsToZero() {
        assertEquals(0, NavEntitySupport.sortOf(null));
        assertEquals(0, NavEntitySupport.sortOf(0));
        assertEquals(-5, NavEntitySupport.sortOf(-5));
    }

    @Test
    void nameOfToleratesMissingMetadata() {
        assertEquals("", NavEntitySupport.nameOf(null));
        assertEquals("", NavEntitySupport.nameOf(new NavCategory()));
        assertEquals("cat", NavEntitySupport.nameOf(category("cat", 0, null)));
    }

    @Test
    void categoryOrderSortsBySortThenName() {
        var sorted = List.of(
                category("b", 20, null),
                category("c", 10, null),
                category("a", 10, null),
                category("d", 0, null))
            .stream()
            .sorted(NavEntitySupport.categoryOrder())
            .map(NavEntitySupport::nameOf)
            .toList();
        assertEquals(List.of("d", "a", "c", "b"), sorted);
    }

    @Test
    void linkOrderUsesSameRules() {
        var first = link("z", 5, null);
        var second = link("a", 5, null);
        var third = link("m", 1, null);
        var sorted = List.of(first, second, third).stream()
            .sorted(NavEntitySupport.linkOrder())
            .map(NavEntitySupport::nameOf)
            .toList();
        assertEquals(List.of("m", "a", "z"), sorted);
    }

    static NavCategory category(String name, Integer sort, Boolean visible) {
        var category = new NavCategory();
        category.setMetadata(metadata(name));
        var spec = new NavCategory.Spec();
        spec.setDisplayName(name);
        spec.setSort(sort);
        spec.setVisible(visible);
        category.setSpec(spec);
        return category;
    }

    static NavLink link(String name, Integer sort, Boolean visible) {
        var link = new NavLink();
        link.setMetadata(metadata(name));
        var spec = new NavLink.Spec();
        spec.setCategoryName("cat");
        spec.setDisplayName(name);
        spec.setUrl("https://example.com/" + name);
        spec.setSort(sort);
        spec.setVisible(visible);
        link.setSpec(spec);
        return link;
    }

    static Metadata metadata(String name) {
        var metadata = new Metadata();
        metadata.setName(name);
        return metadata;
    }
}