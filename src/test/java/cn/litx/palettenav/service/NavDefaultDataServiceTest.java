package cn.litx.palettenav.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.litx.palettenav.dto.NavDefaultData;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NavDefaultDataServiceTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.shared();

    @Test
    void bundledDefaultsAreConsistent() throws Exception {
        var data = load();

        assertNotNull(data.getVersion());
        assertFalse(data.getCategories().isEmpty());
        assertFalse(data.getSearchEngines().isEmpty());
        assertFalse(data.getQuickLinks().isEmpty());

        var categoryNames = new HashSet<String>();
        for (var category : data.getCategories()) {
            assertTrue(categoryNames.add(category.getName()),
                "duplicate category name " + category.getName());
            assertTrue(category.getLinks() != null && !category.getLinks().isEmpty(),
                "category " + category.getName() + " has no links");
        }

        var linkNames = new HashSet<String>();
        for (var category : data.getCategories()) {
            for (var link : category.getLinks()) {
                assertTrue(linkNames.add(link.getName()),
                    "duplicate link name " + link.getName());
                assertNotNull(link.getUrl());
            }
        }

        var engineNames = new HashSet<String>();
        var engineAliases = new HashSet<String>();
        for (var engine : data.getSearchEngines()) {
            assertTrue(engineNames.add(engine.getName()),
                "duplicate search engine name " + engine.getName());
            assertTrue(engineAliases.add(engine.getAlias()),
                "duplicate search engine alias " + engine.getAlias());
            assertNotNull(engine.getUrlTemplate());
        }

        var quickLinkNames = new HashSet<String>();
        for (var quickLink : data.getQuickLinks()) {
            assertTrue(quickLinkNames.add(quickLink.getName()),
                "duplicate quick link name " + quickLink.getName());
            assertNotNull(quickLink.getUrl());
        }
    }

    @Test
    void defaultsMapToExtensions() throws Exception {
        var data = load();
        var category = data.getCategories().getFirst();
        var link = category.getLinks().getFirst();
        var engine = data.getSearchEngines().getFirst();
        var quickLink = data.getQuickLinks().getFirst();

        var mappedCategory = NavDefaultDataService.toCategory(category);
        assertEquals(category.getName(), mappedCategory.getMetadata().getName());
        assertEquals(category.getDisplayName(), mappedCategory.getSpec().getDisplayName());
        assertEquals(category.getSort(), mappedCategory.getSpec().getSort());

        var mappedLink = NavDefaultDataService.toLink(category.getName(), link);
        assertEquals(link.getName(), mappedLink.getMetadata().getName());
        assertEquals(category.getName(), mappedLink.getSpec().getCategoryName());
        assertEquals(link.getUrl(), mappedLink.getSpec().getUrl());

        var mappedEngine = NavDefaultDataService.toSearchEngine(engine);
        assertEquals(engine.getName(), mappedEngine.getMetadata().getName());
        assertEquals(engine.getUrlTemplate(), mappedEngine.getSpec().getUrlTemplate());

        var mappedQuickLink = NavDefaultDataService.toQuickLink(quickLink);
        assertEquals(quickLink.getName(), mappedQuickLink.getMetadata().getName());
        assertEquals(quickLink.getUrl(), mappedQuickLink.getSpec().getUrl());
    }

    private NavDefaultData load() throws Exception {
        try (var input = getClass().getClassLoader()
            .getResourceAsStream(NavDefaultDataService.DEFAULTS_RESOURCE)) {
            assertNotNull(input, "missing " + NavDefaultDataService.DEFAULTS_RESOURCE);
            var json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return JSON_MAPPER.readValue(json, NavDefaultData.class);
        }
    }
}