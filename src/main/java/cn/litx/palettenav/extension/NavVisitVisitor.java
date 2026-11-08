package cn.litx.palettenav.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * One hashed visitor of one day, used only for UV deduplication.
 *
 * <p>One resource per distinct visitor, named {@code
 * nav-visitors-yyyy-MM-dd-&lt;hash&gt;}. The create call doubles as the atomic
 * "first visit today" check: a duplicate name means the visitor is already counted.
 * The hash is a truncated SHA-256 of the day's random salt + {@code IP + User-Agent};
 * raw addresses are never stored, and the per-day salt keeps hashes from the same
 * visitor unrelated across days. The dashboard does not read this model, and resources
 * older than yesterday are deleted by the stats service.</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "palette-nav.halo.run", version = "v1alpha1", kind = "NavVisitVisitor",
    plural = "navvisitvisitors", singular = "navvisitvisitor")
public class NavVisitVisitor extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "NavVisitVisitorSpec")
    public static class Spec {

        @Schema(description = "Server-local date in yyyy-MM-dd",
            requiredMode = Schema.RequiredMode.REQUIRED)
        private String date;

        @Schema(description = "Truncated SHA-256 hash of the daily salt + IP + User-Agent")
        private String hash;
    }
}