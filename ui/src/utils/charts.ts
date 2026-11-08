/**
 * Lazily loaded ECharts entry point for the dashboard.
 *
 * The dashboard used to dynamically import `echarts/charts`, `echarts/components`
 * and `echarts/renderers` as namespaces. Rollup cannot tree-shake a namespace
 * obtained from a dynamic import, so every chart and geo component shipped in a
 * chunk even though only two series types are registered. Static named imports in
 * this module are tree-shaken normally, and the module itself is still split into
 * its own chunk by the dashboard's `import("@/utils/charts")`.
 */
import { init, use } from "echarts/core";
import { BarChart, LineChart } from "echarts/charts";
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";

use([
  BarChart,
  LineChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
]);

export { init };
export type { EChartsCoreOption, EChartsType } from "echarts/core";