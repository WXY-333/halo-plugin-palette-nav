/**
 * Strict aliases of the generated dashboard DTOs.
 *
 * `generateApiClient` marks every DTO field optional, but the server always
 * fills `NavDashboardStats` / `NavDailyVisit` in full — the endpoint builds the
 * seven-day series explicitly. Deriving required variants here keeps the
 * dashboard free of defensive checks; the fields themselves stay generated.
 */
import type {
  NavDailyVisit as GeneratedNavDailyVisit,
  NavDashboardStats as GeneratedNavDashboardStats,
} from "@/api/generated/models";

/**
 * Daily distinct-visitor cap; after it is reached new visitors still count as PV but
 * no longer as UV. Must stay in sync with
 * `NavStatsService.MAX_VISITORS_PER_DAY` in the plugin backend.
 */
export const DAILY_UV_LIMIT = 20_000;

export type NavDailyVisit = Required<GeneratedNavDailyVisit>;

export type NavDashboardStats = Omit<
  Required<GeneratedNavDashboardStats>,
  "daily"
> & {
  daily: NavDailyVisit[];
};