package cn.litx.palettenav.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Aggregated data for the console dashboard.
 */
@Schema(name = "NavDashboardStats", description = "Palette Nav dashboard statistics.")
public record NavDashboardStats(
    @Schema(description = "Number of all nav links", example = "177")
    long linkCount,
    @Schema(description = "Page views of today", example = "397")
    long todayPv,
    @Schema(description = "Distinct visitors of today", example = "3")
    long todayUv,
    @Schema(description = "Accumulated page views", example = "15724")
    long totalPv,
    @Schema(description = "Daily statistics of the last seven days, oldest first")
    List<NavDailyVisit> daily
) {
}