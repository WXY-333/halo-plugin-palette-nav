package cn.litx.palettenav.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * Daily visit counters for the public {@code /nav} page.
 *
 * <p>One resource per day, named {@code nav-visit-yyyy-MM-dd}. The dashboard
 * aggregates these small resources; visitor hashes live in {@link NavVisitVisitor}
 * so listing counters never loads the deduplication set.</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "palette-nav.halo.run", version = "v1alpha1", kind = "NavVisitStat",
    plural = "navvisitstats", singular = "navvisitstat")
public class NavVisitStat extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "NavVisitStatSpec")
    public static class Spec {

        @Schema(description = "Server-local date in yyyy-MM-dd",
            requiredMode = Schema.RequiredMode.REQUIRED)
        private String date;

        /**
         * Random per-day salt used when hashing visitor identities. Storing it with the
         * counters lets a restart keep deduplicating, while the value changes every day
         * so equal visitors produce unrelated hashes on different days.
         */
        @Schema(description = "Random per-day salt for visitor hashing")
        private String salt;

        @Schema(description = "Page views of the day")
        private Long pv;

        @Schema(description = "Distinct visitors of the day")
        private Long uv;
    }
}