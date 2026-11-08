package cn.litx.palettenav.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * A navigation category (group).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "palette-nav.halo.run", version = "v1alpha1", kind = "NavCategory",
    plural = "navcategories", singular = "navcategory")
public class NavCategory extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "NavCategorySpec")
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 50)
        private String displayName;

        @Schema(maxLength = 65535, description = "SVG sprite reference, image URL or emoji")
        private String icon;

        private Integer sort;

        private Boolean visible;
    }
}
