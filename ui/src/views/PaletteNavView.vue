<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import RiNavigationLine from "~icons/ri/navigation-line";
import {
  Dialog,
  Toast,
  VAlert,
  VButton,
  VLoading,
  VPageHeader,
  VTabItem,
  VTabs,
  IconExternalLinkLine,
} from "@halo-dev/components";
import {
  CONTEXT_PATH,
  errorMessage,
  listCategories,
  listLinks,
  listQuickLinks,
  listSearchEngines,
} from "@/api/nav";
import type {
  NavCategory,
  NavLink,
  NavQuickLink,
  NavSearchEngine,
} from "@/types/nav";
import CategoryTab from "@/components/CategoryTab.vue";
import DashboardTab from "@/components/DashboardTab.vue";
import LinkTab from "@/components/LinkTab.vue";
import SearchEngineTab from "@/components/SearchEngineTab.vue";
import QuickLinkTab from "@/components/QuickLinkTab.vue";
import SettingsTab from "@/components/SettingsTab.vue";

const categories = ref<NavCategory[]>([]);
const links = ref<NavLink[]>([]);
const engines = ref<NavSearchEngine[]>([]);
const quickLinks = ref<NavQuickLink[]>([]);

const loading = ref(true);
const loadError = ref("");
/** Ignores a slow response when a newer load has already started. */
let loadToken = 0;
const activeTab = ref("dashboard");
/** Tab that currently holds an unsaved drag order. */
const dirtyTab = ref<string | null>(null);

function onDirtyChange(id: string, dirty: boolean) {
  if (dirty) {
    dirtyTab.value = id;
  } else if (dirtyTab.value === id) {
    dirtyTab.value = null;
  }
}

function switchTab(value: string | number) {
  const next = String(value);
  if (next === activeTab.value) {
    return;
  }
  if (dirtyTab.value && dirtyTab.value === activeTab.value) {
    Dialog.warning({
      title: "放弃未保存的修改？",
      description: "当前页面的修改尚未保存，切换标签后这些改动会丢失。",
      confirmType: "danger",
      confirmText: "放弃并切换",
      cancelText: "留在本页",
      onConfirm: () => {
        dirtyTab.value = null;
        activeTab.value = next;
      },
    });
    return;
  }
  activeTab.value = next;
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirtyTab.value) {
    return;
  }
  event.preventDefault();
  event.returnValue = "";
}

onMounted(() => {
  window.addEventListener("beforeunload", handleBeforeUnload);
});

onBeforeUnmount(() => {
  window.removeEventListener("beforeunload", handleBeforeUnload);
});

let leaveConfirmed = false;

onBeforeRouteLeave((_to, _from, next) => {
  if (leaveConfirmed || !dirtyTab.value) {
    next();
    return;
  }
  Dialog.warning({
    title: "放弃未保存的修改？",
    description: "当前页面的修改尚未保存，离开页面后这些改动会丢失。",
    confirmType: "danger",
    confirmText: "离开页面",
    cancelText: "留在本页",
    onConfirm: () => {
      leaveConfirmed = true;
      next();
    },
    onCancel: () => {
      next(false);
    },
  });
});

const hasData = computed(
  () =>
    categories.value.length +
      links.value.length +
      engines.value.length +
      quickLinks.value.length >
    0
);

async function load() {
  const token = ++loadToken;
  loading.value = true;
  loadError.value = "";
  try {
    const [nextCategories, nextLinks, nextEngines, nextQuickLinks] = await Promise.all([
      listCategories(),
      listLinks(),
      listSearchEngines(),
      listQuickLinks(),
    ]);
    // A refresh started later owns the state; a stale response must not
    // overwrite newer data or clear the newer loading flag.
    if (token !== loadToken) {
      return;
    }
    categories.value = nextCategories;
    links.value = nextLinks;
    engines.value = nextEngines;
    quickLinks.value = nextQuickLinks;
  } catch (error) {
    if (token !== loadToken) {
      return;
    }
    loadError.value = errorMessage(error);
    Toast.error(loadError.value);
  } finally {
    if (token === loadToken) {
      loading.value = false;
    }
  }
}

onMounted(load);

function openPreview() {
  window.open(`${CONTEXT_PATH}/nav`, "_blank", "noopener");
}
</script>

<template>
  <VPageHeader title="Palette Nav 上网导航">
    <template #icon>
      <RiNavigationLine class="pn-page-icon" />
    </template>
    <template #actions>
      <VButton size="sm" @click="openPreview">
        <template #icon>
          <IconExternalLinkLine />
        </template>
        打开网页
      </VButton>
    </template>
  </VPageHeader>

  <div class="pn-page">
    <!-- A failed first load must not look like an empty navigation: every tab
         would show its empty state with no way to recover. -->
    <VAlert
      v-if="loadError && !hasData"
      type="error"
      title="读取导航数据失败"
      :description="loadError"
      :closable="false"
    >
      <template #actions>
        <VButton size="sm" @click="load">重试</VButton>
      </template>
    </VAlert>

    <!-- Only block the first load; later refreshes must keep the tabs mounted so
         that filters, pagination and drag state survive an update. -->
    <div v-else-if="loading && !hasData" class="pn-loading">
      <VLoading />
    </div>

    <VTabs v-else :active-id="activeTab" type="outline" @update:active-id="switchTab">
      <VTabItem id="dashboard" label="仪表盘">
        <DashboardTab />
      </VTabItem>
      <VTabItem id="categories" :label="`分类 ${categories.length}`">
        <CategoryTab
          :categories="categories"
          :links="links"
          @refresh="load"
          @dirty-change="(dirty) => onDirtyChange('categories', dirty)"
        />
      </VTabItem>
      <VTabItem id="links" :label="`链接 ${links.length}`">
        <LinkTab
          :categories="categories"
          :links="links"
          @refresh="load"
          @dirty-change="(dirty) => onDirtyChange('links', dirty)"
        />
      </VTabItem>
      <VTabItem id="engines" :label="`搜索引擎 ${engines.length}`">
        <SearchEngineTab
          :engines="engines"
          @refresh="load"
          @dirty-change="(dirty) => onDirtyChange('engines', dirty)"
        />
      </VTabItem>
      <VTabItem id="quick-links" :label="`快捷导航 ${quickLinks.length}`">
        <QuickLinkTab
          :quick-links="quickLinks"
          @refresh="load"
          @dirty-change="(dirty) => onDirtyChange('quick-links', dirty)"
        />
      </VTabItem>
      <VTabItem id="settings" label="设置">
        <SettingsTab
          @dirty-change="(dirty) => onDirtyChange('settings', dirty)"
        />
      </VTabItem>
    </VTabs>
  </div>
</template>
