package cn.litx.palettenav.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the least-privilege promise of the marketplace role template.
 *
 * <p>The plugin only needs to read and update its own settings ConfigMap. A
 * resourceNames-less {@code configmaps} rule (especially {@code create}) would let the
 * manage role touch every ConfigMap in the installation, which the app store review
 * explicitly rejected.</p>
 */
class NavRoleTemplateTest {

    @Test
    @SuppressWarnings("unchecked")
    void configMapRulesOnlyTouchThePluginConfigMap() throws Exception {
        var path = Path.of("src/main/resources/extensions/role-templates.yaml");
        var root = (Map<String, Object>) new Yaml()
            .load(Files.readString(path, StandardCharsets.UTF_8));
        var rules = (List<Map<String, Object>>) root.get("rules");
        assertFalse(rules.isEmpty());

        var configMapRules = rules.stream()
            .filter(rule -> {
                var groups = (List<String>) rule.get("apiGroups");
                var resources = (List<String>) rule.get("resources");
                return groups != null && groups.contains("")
                    && resources != null && resources.contains("configmaps");
            })
            .toList();

        assertTrue(configMapRules.stream().anyMatch(rule ->
            List.of("get", "update").equals(rule.get("verbs"))));
        for (var rule : configMapRules) {
            assertEquals(List.of(NavSettings.CONFIG_MAP_NAME), rule.get("resourceNames"),
                "configmaps rules must be scoped with resourceNames");
            var verbs = (List<String>) rule.get("verbs");
            assertFalse(verbs.contains("create"), "create cannot be scoped by name");
            assertFalse(verbs.contains("delete"));
        }
    }
}