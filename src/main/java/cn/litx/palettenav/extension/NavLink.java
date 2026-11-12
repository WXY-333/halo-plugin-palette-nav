package cn.litx.palettenav.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * A link inside a navigation category.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "palette-nav.halo.run", version = "v1alpha1", kind = "NavLink",
    plural = "navlinks", singular = "navlink")
public class NavLink extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "NavLinkSpec")
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 253)
        private String categoryName;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 255)
        private String displayName;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2048)
        private String url;

        @Schema(maxLength = 65535, description = "SVG sprite reference, image URL or emoji")
        private String icon;

        @Schema(maxLength = 2048, description = "Optional homepage cover image URL")
        private String coverUrl;

        @Schema(maxLength = 2000, description = "Optional link description shown in hover preview")
        private String description;

        private Integer sort;

        private Boolean visible;
    }
}
