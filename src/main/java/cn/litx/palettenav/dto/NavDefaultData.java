package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Bundled default navigation content.
 *
 * <p>This is the on-disk format of {@code data/nav-defaults.json}, generated from a
 * running instance by {@code tools/export-nav-defaults.py}. Visit statistics are
 * intentionally not part of this document.</p>
 */
@Data
@Schema(name = "NavDefaultData", description = "Bundled Palette Nav default content.")
public class NavDefaultData {

    @Schema(description = "Document version", example = "1")
    private Integer version;

    private List<Category> categories = new ArrayList<>();

    private List<SearchEngine> searchEngines = new ArrayList<>();

    private List<QuickLink> quickLinks = new ArrayList<>();

    @Data
    @Schema(name = "NavDefaultCategory")
    public static class Category {

        private String name;
        private String displayName;
        private String icon;
        private Integer sort;
        private Boolean visible;
        private List<Link> links = new ArrayList<>();
    }

    @Data
    @Schema(name = "NavDefaultLink")
    public static class Link {

        private String name;
        private String displayName;
        private String url;
        private String icon;
        private Integer sort;
        private Boolean visible;
    }

    @Data
    @Schema(name = "NavDefaultSearchEngine")
    public static class SearchEngine {

        private String name;
        private String alias;
        private String displayName;
        private String hint;
        private String urlTemplate;
        private String mobileUrlTemplate;
        private String icon;
        private Integer sort;
        private Boolean visible;
    }

    @Data
    @Schema(name = "NavDefaultQuickLink")
    public static class QuickLink {

        private String name;
        private String displayName;
        private String url;
        private Boolean openInNewTab;
        private Integer sort;
        private Boolean visible;
    }
}