package cn.litx.palettenav.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * A search engine shown in the hero search box.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "palette-nav.halo.run", version = "v1alpha1", kind = "NavSearchEngine",
    plural = "navsearchengines", singular = "navsearchengine")
public class NavSearchEngine extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "NavSearchEngineSpec")
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 30)
        private String alias;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 30)
        private String displayName;

        @Schema(maxLength = 60)
        private String hint;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2048,
            description = "Search URL prefix, the keyword is appended after it")
        private String urlTemplate;

        @Schema(maxLength = 2048)
        private String mobileUrlTemplate;

        @Schema(maxLength = 65535, description = "SVG sprite reference, image URL or emoji")
        private String icon;

        private Integer sort;

        private Boolean visible;
    }
}
