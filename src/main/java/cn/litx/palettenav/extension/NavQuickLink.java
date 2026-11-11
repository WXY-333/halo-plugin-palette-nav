package cn.litx.palettenav.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * A quick link shown in the "more" dropdown of the top bar.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "palette-nav.halo.run", version = "v1alpha1", kind = "NavQuickLink",
    plural = "navquicklinks", singular = "navquicklink")
public class NavQuickLink extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "NavQuickLinkSpec")
    public static class Spec {

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 30)
        private String displayName;

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2048)
        private String url;

        private String icon;

        private Boolean openInNewTab;

        private Integer sort;

        private Boolean visible;
    }
}
