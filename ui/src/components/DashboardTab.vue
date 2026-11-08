<script lang="ts" setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import {
  Toast,
  VAlert,
  VButton,
  VCard,
  VLoading,
} from "@halo-dev/components";
import RiEyeLine from "~icons/ri/eye-line";
import RiGroupLine from "~icons/ri/group-line";
import RiLinksLine from "~icons/ri/links-line";
import RiUserLine from "~icons/ri/user-line";
import RefreshButton from "@/components/RefreshButton.vue";
import { errorMessage, fetchDashboardStats } from "@/api/nav";
import type { NavDailyVisit, NavDashboardStats } from "@/types/dashboard";
import { DAILY_UV_LIMIT } from "@/types/dashboard";

type EChartsType = import("echarts/core").EChartsType;
type EChartsCoreOption = import("echarts/core").EChartsCoreOption;

const stats = ref<NavDashboardStats | null>(null);
const loading = ref(true);
const loadError = ref("");

const uvChartEl = ref<HTMLDivElement | null>(null);
const pvChartEl = ref<HTMLDivElement | null>(null);

let uvChart: EChartsType | null = null;
let pvChart: EChartsType | null = null;
let resizeObserver: ResizeObserver | null = null;
let echartsModule: typeof import("@/utils/charts") | null = null;
let disposed = false;
/** Guards against an older refresh overwriting a newer one. */
let loadToken = 0;
/** Last rendered series, cached so a narrow/wide flip can rebuild the options. */
let lastDaily: NavDailyVisit[] | null = null;
let lastLabels: string[] | null = null;
let uvNarrow = false;
let pvNarrow = false;

/** Below this container width charts use tighter type and margins. */
const NARROW_CHART_WIDTH = 420;

const numberFormat = new Intl.NumberFormat("zh-CN");

const linkCount = computed(() => stats.value?.linkCount ?? 0);
const todayPv = computed(() => stats.value?.todayPv ?? 0);
const todayUv = computed(() => stats.value?.todayUv ?? 0);
const totalPv = computed(() => stats.value?.totalPv ?? 0);
const hasVisitData = computed(() => totalPv.value > 0);
const uvCapped = computed(() => todayUv.value >= DAILY_UV_LIMIT);

/**
 * Charts are the one place ECharts needs concrete colors, so read Halo's host tokens
 * at render time instead of hardcoding a second palette next to `--pn-*`.
 */
function cssVar(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement)
    .getPropertyValue(name)
    .trim();
  return value || fallback;
}

function primaryColor(alpha?: number): string {
  // Halo injects `--colors-primary` as space-separated channels ("76 203 160").
  // Canvas accepts the CSS Color 4 form `rgb(76 203 160 / .55)` for the initial
  // paint, but zrender (ECharts' renderer) only parses comma-separated
  // rgb()/rgba() — on hover it recomputes the emphasis color, fails to parse,
  // and drops the hovered series. Always hand ECharts a legacy comma form.
  const channels = cssVar("--colors-primary", "76 203 160")
    .split(/[\s,]+/)
    .filter(Boolean);
  const [r, g, b] =
    channels.length === 3 ? channels : ["76", "203", "160"];
  return alpha === undefined ? `rgb(${r}, ${g}, ${b})` : `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function mutedTextColor(): string {
  return cssVar("--pn-text-muted", "#9ca3af");
}

function borderColor(): string {
  return cssVar("--pn-border", "#eaecf0");
}

function formatCount(value: number): string {
  return numberFormat.format(value);
}

/** Keeps the Y axis readable when the PV total reaches five digits. */
function formatAxisValue(value: number): string {
  if (value >= 10000) {
    const wan = value / 10000;
    return `${Number.isInteger(wan) ? wan : wan.toFixed(1)}万`;
  }
  return String(value);
}

function dayLabel(daily: NavDailyVisit[], index: number): string {
  const fromEnd = daily.length - 1 - index;
  if (fromEnd === 0) {
    return "今天";
  }
  if (fromEnd === 1) {
    return "昨天";
  }
  if (fromEnd === 2) {
    return "前天";
  }
  return daily[index]?.date.slice(5) ?? "";
}

function isNarrowChart(el: HTMLDivElement | null): boolean {
  // A zero-width container is transient (hidden or not laid out yet); treat it
  // as normal so narrow options are never baked in before a real measurement.
  return !!el && el.clientWidth > 0 && el.clientWidth < NARROW_CHART_WIDTH;
}

function tooltipOption(axisPointerType?: "shadow") {
  return {
    trigger: "axis",
    // VCard's `.card-body` is overflow:hidden, so keep the tooltip inside the
    // chart box instead of letting a wide tooltip spill into the clipped edge.
    confine: true,
    ...(axisPointerType ? { axisPointer: { type: axisPointerType } } : {}),
    valueFormatter: (value: unknown) =>
      typeof value === "number" ? numberFormat.format(value) : String(value ?? ""),
  };
}

function legendOption(data: string[], narrow = false) {
  return {
    top: narrow ? 8 : 10,
    left: "center",
    icon: "rect",
    itemWidth: narrow ? 10 : 12,
    itemHeight: narrow ? 7 : 8,
    itemGap: narrow ? 6 : 8,
    textStyle: { color: mutedTextColor(), fontSize: narrow ? 11 : 12 },
    data,
  };
}

function baseOption(labels: string[], narrow = false): EChartsCoreOption {
  const axisFontSize = narrow ? 11 : 12;
  return {
    animationDuration: 300,
    grid: narrow
      ? { left: 8, right: 12, top: 40, bottom: 4, containLabel: true }
      : { left: 12, right: 18, top: 48, bottom: 8, containLabel: true },
    legend: legendOption([], narrow),
    tooltip: tooltipOption(),
    xAxis: {
      type: "category",
      data: labels,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: borderColor() } },
      axisLabel: { color: mutedTextColor(), fontSize: axisFontSize },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
      splitLine: { lineStyle: { color: borderColor() } },
      axisLabel: {
        color: mutedTextColor(),
        fontSize: axisFontSize,
        formatter: formatAxisValue,
      },
    },
  };
}

function uvOption(
  daily: NavDailyVisit[],
  labels: string[],
  narrow = false
): EChartsCoreOption {
  const barColor = primaryColor(0.55);
  return {
    ...baseOption(labels, narrow),
    color: [barColor],
    tooltip: tooltipOption("shadow"),
    legend: legendOption(["独立访客"], narrow),
    series: [
      {
        name: "独立访客",
        type: "bar",
        data: daily.map((item) => item.uv),
        barMaxWidth: 28,
        itemStyle: { color: barColor, borderRadius: [2, 2, 0, 0] },
      },
    ],
  };
}

function pvOption(
  daily: NavDailyVisit[],
  labels: string[],
  narrow = false
): EChartsCoreOption {
  const lineColor = primaryColor();
  return {
    ...baseOption(labels, narrow),
    color: [lineColor],
    legend: legendOption(["浏览量(PV)"], narrow),
    series: [
      {
        name: "浏览量(PV)",
        type: "line",
        data: daily.map((item) => item.pv),
        symbol: "circle",
        symbolSize: 6,
        lineStyle: { width: 2, color: lineColor },
        itemStyle: { color: lineColor },
        areaStyle: { color: lineColor, opacity: 0.1 },
      },
    ],
  };
}

async function ensureCharts() {
  if (disposed) {
    return;
  }
  let echarts = echartsModule;
  if (!echarts) {
    echarts = await import("@/utils/charts");
    echartsModule = echarts;
  }
  if (disposed) {
    return;
  }
  if (uvChartEl.value && !uvChart) {
    uvChart = echarts.init(uvChartEl.value);
  }
  if (pvChartEl.value && !pvChart) {
    pvChart = echarts.init(pvChartEl.value);
  }
  if (!resizeObserver) {
    resizeObserver = new ResizeObserver(() => {
      if (disposed) {
        return;
      }
      uvChart?.resize();
      pvChart?.resize();
      // Charts only resize themselves, so a breakpoint flip has to rebuild the
      // options to switch typography and margins. Guard on the cached series,
      // which is null until renderCharts has run (observer fires on observe()).
      if (!lastDaily || !lastLabels) {
        return;
      }
      const nextUvNarrow = isNarrowChart(uvChartEl.value);
      if (nextUvNarrow !== uvNarrow) {
        uvNarrow = nextUvNarrow;
        uvChart?.setOption(uvOption(lastDaily, lastLabels, uvNarrow), true);
      }
      const nextPvNarrow = isNarrowChart(pvChartEl.value);
      if (nextPvNarrow !== pvNarrow) {
        pvNarrow = nextPvNarrow;
        pvChart?.setOption(pvOption(lastDaily, lastLabels, pvNarrow), true);
      }
    });
    if (uvChartEl.value) {
      resizeObserver.observe(uvChartEl.value);
    }
    if (pvChartEl.value) {
      resizeObserver.observe(pvChartEl.value);
    }
  }
}

async function renderCharts() {
  if (!stats.value) {
    return;
  }
  await nextTick();
  if (disposed) {
    return;
  }
  await ensureCharts();
  if (disposed) {
    return;
  }
  const daily: NavDailyVisit[] = stats.value.daily ?? [];
  const labels = daily.map((_, index) => dayLabel(daily, index));
  lastDaily = daily;
  lastLabels = labels;
  uvNarrow = isNarrowChart(uvChartEl.value);
  pvNarrow = isNarrowChart(pvChartEl.value);
  uvChart?.setOption(uvOption(daily, labels, uvNarrow), true);
  pvChart?.setOption(pvOption(daily, labels, pvNarrow), true);
}

async function load() {
  const token = ++loadToken;
  loading.value = true;
  loadError.value = "";
  try {
    const next = await fetchDashboardStats();
    if (disposed || token !== loadToken) {
      return;
    }
    stats.value = next;
  } catch (error) {
    if (disposed || token !== loadToken) {
      return;
    }
    const message = errorMessage(error);
    if (stats.value) {
      // Keep the last successful snapshot on screen and surface the failure
      // without replacing the whole page.
      Toast.error(message);
    } else {
      loadError.value = message;
    }
    loading.value = false;
    return;
  }
  loading.value = false;
  try {
    await renderCharts();
  } catch (error) {
    // Charts are an enhancement; keep the counters visible if ECharts fails.
    console.error("Failed to render dashboard charts", error);
  }
}

onMounted(load);

onBeforeUnmount(() => {
  disposed = true;
  resizeObserver?.disconnect();
  resizeObserver = null;
  uvChart?.dispose();
  pvChart?.dispose();
  uvChart = null;
  pvChart = null;
});
</script>

<template>
  <div class="pn-dashboard">
    <div class="pn-dashboard-head">
      <span class="pn-dashboard-hint">
        {{
          loading
            ? "正在刷新统计…"
            : "统计自 /nav 页面访问，每日 0 点后重新累计"
        }}
      </span>
      <RefreshButton label="刷新统计" @refresh="load" />
    </div>

    <div v-if="loading && !stats" class="pn-loading">
      <VLoading />
    </div>

    <VAlert
      v-else-if="loadError"
      type="error"
      title="读取统计数据失败"
      :description="loadError"
      :closable="false"
    >
      <template #actions>
        <VButton size="sm" @click="load">重试</VButton>
      </template>
    </VAlert>

    <template v-else-if="stats">
      <div class="pn-dashboard-stats">
        <div class="pn-stat-card">
          <span class="pn-stat-card-icon">
            <RiLinksLine />
          </span>
          <span class="pn-stat-card-meta">
            <span class="pn-stat-card-label">链接数量</span>
            <span class="pn-stat-card-value" :title="String(linkCount)">
              {{ formatCount(linkCount) }}
            </span>
          </span>
        </div>
        <div class="pn-stat-card">
          <span class="pn-stat-card-icon">
            <RiEyeLine />
          </span>
          <span class="pn-stat-card-meta">
            <span class="pn-stat-card-label">今日浏览量</span>
            <span class="pn-stat-card-value" :title="String(todayPv)">
              {{ formatCount(todayPv) }}
            </span>
          </span>
        </div>
        <div class="pn-stat-card">
          <span class="pn-stat-card-icon">
            <RiUserLine />
          </span>
          <span class="pn-stat-card-meta">
            <span class="pn-stat-card-label">今日独立访客</span>
            <span class="pn-stat-card-value" :title="String(todayUv)">
              {{ formatCount(todayUv) }}
            </span>
          </span>
        </div>
        <div class="pn-stat-card">
          <span class="pn-stat-card-icon">
            <RiGroupLine />
          </span>
          <span class="pn-stat-card-meta">
            <span class="pn-stat-card-label">累计浏览量</span>
            <span class="pn-stat-card-value" :title="String(totalPv)">
              {{ formatCount(totalPv) }}
            </span>
          </span>
        </div>
      </div>

      <!-- 常驻提示：关闭按钮不会清理任何状态，与 Halo 自身用法一致地隐藏它 -->
      <VAlert
        v-if="!hasVisitData"
        type="info"
        title="还没有访问数据"
        description="打开 /nav 页面后，统计会从今天开始累计。"
        :closable="false"
      />

      <VAlert
        v-if="uvCapped"
        type="info"
        title="今日独立访客已达统计上限"
        :description="`每日最多统计 ${formatCount(DAILY_UV_LIMIT)} 位独立访客，之后的新访客仍会累计浏览量（PV），但不再计入独立访客。`"
        :closable="false"
      />

      <div class="pn-dashboard-charts">
        <VCard title="近 7 天独立访客统计">
          <div ref="uvChartEl" class="pn-dashboard-chart"></div>
        </VCard>
        <VCard title="近 7 天浏览量统计">
          <div ref="pvChartEl" class="pn-dashboard-chart"></div>
        </VCard>
      </div>
    </template>
  </div>
</template>
