package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Page views and distinct visitors of one day.
 */
@Schema(name = "NavDailyVisit", description = "One day of Palette Nav visit statistics.")
public record NavDailyVisit(
    @Schema(description = "Date in yyyy-MM-dd", example = "2026-11-07")
    String date,
    @Schema(description = "Page views of the day", example = "397")
    long pv,
    @Schema(description = "Distinct visitors of the day", example = "3")
    long uv
) {
}