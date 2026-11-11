<script lang="ts" setup>
import { computed, getCurrentInstance, onMounted, ref, watch } from "vue";
import {
  Toast,
  VAlert,
  VButton,
  VCard,
  VLoading,
} from "@halo-dev/components";
import {
  NIGHT_MODES,
  THEME_COLORS,
  fetchNavSettings,
  listAttachmentGroupOptions,
  listStoragePolicyOptions,
  normalizeSettings,
  saveNavSettings,
} from "@/api/settings";
import type { NavSettingsValues, StorageOption } from "@/api/settings";
import { CONTEXT_PATH, errorMessage, isNotFound } from "@/api/nav";
import MediaPicker from "./MediaPicker.vue";
import IconPicker from "./IconPicker.vue";
import { isSafeImageUrl } from "@/utils/icon";

const DEFAULT_LOGO_URL = `${CONTEXT_PATH}/plugins/palette-nav/assets/nav/img/logo-default.png`;
const DEFAULT_BACKGROUND_URL = `${CONTEXT_PATH}/plugins/palette-nav/assets/nav/img/background.jpg`;

const emit = defineEmits<{
  "dirty-change": [dirty: boolean];
}>();

const FORM_ID = "pn-settings-form";

const loading = ref(true);
const loadError = ref("");
const saving = ref(false);
const dirty = ref(false);
/**
 * A missing ConfigMap is recreated by the plugin on its next start. The manage
 * role cannot create ConfigMaps, so until then the page must not pretend the
 * defaults are savable: make the state visible at load time instead of failing
 * only after the manager clicks 保存.
 */
const configMissing = ref(false);

/**
 * The form is its own reactive model (FormKit fields bind with v-model) so the
 * dirty state can compare it against the last loaded / saved snapshot. That is
 * the only reliable way to catch every FormKit input type: switches render a
 * button and selects a custom popper, neither of which emit native change
 * events on the wrapping form.
 */
const settings = ref<NavSettingsValues>(normalizeSettings(null));
const savedSnapshot = ref(JSON.stringify(settings.value));
const instance = getCurrentInstance();

function setFormValues(next: NavSettingsValues) {
  Object.assign(settings.value.general, next.general);
  Object.assign(settings.value.appearance, next.appearance);
  Object.assign(settings.value.search, next.search);
  Object.assign(settings.value.icons, next.icons);
}

function applyValues(next: NavSettingsValues) {
  savedSnapshot.value = JSON.stringify(next);
  setFormValues(next);
  setDirty(false);
}

async function load() {
  loading.value = true;
  loadError.value = "";
  configMissing.value = false;
  try {
    applyValues(await fetchNavSettings());
  } catch (error) {
    // A missing config map means "never saved", not a failure: fall back to
    // the same defaults as NavSettings.normalized(). The plugin recreates the
    // ConfigMap on its next start, so this only happens after a manual delete.
    if (isNotFound(error)) {
      configMissing.value = true;
      applyValues(normalizeSettings(null));
    } else {
      loadError.value = errorMessage(error);
    }
  } finally {
    loading.value = false;
  }
}

const policyOptionsRaw = ref<StorageOption[]>([]);
const groupOptionsRaw = ref<StorageOption[]>([]);
const storageOptionsLoading = ref(false);
const storageOptionsError = ref("");

/**
 * Options for the icon storage target. A configured value that no longer exists
 * is kept as a disabled-looking entry so the select does not silently drop it;
 * the backend falls back to the automatic policy / no group in that case.
 */
const policyOptions = computed<StorageOption[]>(() => {
  // No synthetic empty entry: an unset value renders blank, and once a policy is
  // chosen the host select shows its clear (×) control on hover, so the field is
  // genuinely optional instead of looking like a required choice.
  const options: StorageOption[] = [...policyOptionsRaw.value];
  const current = settings.value.icons.policyName;
  if (current && !options.some((option) => option.value === current)) {
    options.push({ label: `${current}（已失效，保存后自动选择）`, value: current });
  }
  return options;
});

const groupOptions = computed<StorageOption[]>(() => {
  const options: StorageOption[] = [...groupOptionsRaw.value];
  const current = settings.value.icons.groupName;
  if (current && !options.some((option) => option.value === current)) {
    options.push({ label: `${current}（已失效，保存后不分组）`, value: current });
  }
  return options;
});

async function loadStorageOptions() {
  storageOptionsLoading.value = true;
  storageOptionsError.value = "";
  const [policies, groups] = await Promise.allSettled([
    listStoragePolicyOptions(),
    listAttachmentGroupOptions(),
  ]);
  if (policies.status === "fulfilled") {
    policyOptionsRaw.value = policies.value;
  } else {
    storageOptionsError.value = errorMessage(policies.reason);
  }
  if (groups.status === "fulfilled") {
    groupOptionsRaw.value = groups.value;
  } else {
    storageOptionsError.value = [storageOptionsError.value, errorMessage(groups.reason)]
      .filter(Boolean)
      .join("；");
  }
  storageOptionsLoading.value = false;
}

onMounted(() => {
  void load();
  void loadStorageOptions();
});

watch(
  settings,
  () => {
    if (loading.value || loadError.value) {
      return;
    }
    setDirty(JSON.stringify(settings.value) !== savedSnapshot.value);
  },
  { deep: true }
);

async function save() {
  const randomImageApi = settings.value.appearance.randomImageApi.trim();
  const previewLoadingImage = settings.value.appearance.previewLoadingImage.trim();
  if (previewLoadingImage && !isSafeImageUrl(previewLoadingImage)) {
    Toast.error("预览加载动画请输入 http/https 或以 / 开头的图片地址");
    return;
  }
  if (randomImageApi && !isSafeImageUrl(randomImageApi)) {
    Toast.error("随机图片 API 请输入 http/https 或以 / 开头的站内图片地址");
    return;
  }
  if (configMissing.value) {
    Toast.error("设置配置不存在，请重启插件后重试");
    return;
  }
  saving.value = true;
  try {
    const next: NavSettingsValues = {
      general: { ...settings.value.general },
      appearance: { ...settings.value.appearance },
      search: { ...settings.value.search },
      icons: { ...settings.value.icons },
    };
    await saveNavSettings(next);
    // The snapshot must describe what the server accepted, not the form's current
    // state: edits made while the PUT was in flight are still unsaved and must
    // keep the dirty flag (and the leave-page warning) alive.
    savedSnapshot.value = JSON.stringify(next);
    setDirty(JSON.stringify(settings.value) !== savedSnapshot.value);
    Toast.success("设置已保存，前台 /nav 页面已生效");
  } catch (error) {
    Toast.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

function setDirty(next: boolean) {
  if (dirty.value === next) {
    return;
  }
  dirty.value = next;
  emit("dirty-change", next);
}

function submit() {
  const formkit = (
    instance?.proxy as { $formkit?: { submit: (name: string) => void } } | null
  )?.$formkit;
  formkit?.submit(FORM_ID);
}
</script>

<template>
  <div class="pn-settings">
    <div v-if="loading" class="pn-loading">
      <VLoading />
    </div>

    <VAlert
      v-else-if="loadError"
      type="error"
      title="读取设置失败"
      :description="loadError"
    >
      <template #actions>
        <VButton size="sm" @click="load">重试</VButton>
      </template>
    </VAlert>

    <VAlert
      v-else-if="configMissing"
      type="warning"
      title="设置配置不存在"
      description="插件的设置 ConfigMap 已被删除。请重启插件让 Palette Nav 重新创建配置文件后刷新此页；在此之前设置无法保存。"
    >
      <template #actions>
        <VButton size="sm" @click="load">重新读取</VButton>
      </template>
    </VAlert>

    <FormKit
      v-else
      :id="FORM_ID"
      type="form"
      :actions="false"
      @submit="save"
    >
      <div class="pn-settings-sections">
        <VCard title="基本设置">
          <p class="pn-settings-description">
            站点的标题、描述、品牌与页脚信息，用于前台导航页的 SEO 与页脚展示。
          </p>
          <div class="pn-settings-grid">
            <FormKit
              v-model="settings.general.siteName"
              name="siteName"
              type="text"
              label="导航页标题"
              placeholder="留空时使用 Halo 站点标题"
              help="同时用于页面 title 与顶部品牌文字。"
            />
            <FormKit
              v-model="settings.general.brandText"
              name="brandText"
              type="text"
              label="品牌文字"
              placeholder="留空时取标题中「-」前的部分"
              help="显示在顶部 LOGO 右侧。"
            />
            <FormKit
              v-model="settings.general.siteDescription"
              name="siteDescription"
              type="text"
              label="页面描述"
              placeholder="留空时使用 Halo 站点副标题"
              help="输出到 meta description，便于搜索引擎收录。"
            />
            <FormKit
              v-model="settings.general.siteKeywords"
              name="siteKeywords"
              type="text"
              label="页面关键词"
              placeholder="导航,上网导航,常用网站"
              help="输出到 meta keywords，多个关键词用英文逗号分隔。"
            />
            <MediaPicker
              v-model="settings.general.logoUrl"
              kind="logo"
              label="LOGO 地址"
              empty-hint="留空使用插件默认 LOGO"
              :fallback-url="DEFAULT_LOGO_URL"
              help="支持上传图片、从附件库选择，或输入 https:// 与 /upload/... 地址。"
            />
            <FormKit
              v-model="settings.general.copyright"
              name="copyright"
              type="text"
              label="版权信息"
              placeholder="Copyright © 2026 Your Name"
              help="支持链接、加粗等安全 HTML；脚本、事件属性和样式会被服务端过滤。"
            />
            <FormKit
              v-model="settings.general.icp"
              name="icp"
              type="text"
              label="ICP 备案号"
              placeholder="京ICP备xxxxxxxx号"
            />
            <FormKit
              v-model="settings.general.gonganbei"
              name="gonganbei"
              type="text"
              label="公安备案号"
              placeholder="京公网安备xxxxxxxxxx号"
              help="留空不显示；数字部分会自动生成查询链接。"
            />
            <div class="pn-settings-filing-icon">
              <p class="pn-form-block">ICP 备案图标</p>
              <IconPicker v-model="settings.general.icpIcon" />
            </div>
            <div class="pn-settings-filing-icon">
              <p class="pn-form-block">公安备案图标</p>
              <IconPicker v-model="settings.general.gonganbeiIcon" />
            </div>
          </div>
        </VCard>

        <VCard title="访问统计">
          <p class="pn-settings-description">
            仪表盘数据来自 /nav 页面的访问记录；独立访客按 IP 与 User-Agent 的加盐哈希按天去重，不保存原始地址，跨天无法关联同一访客。
          </p>
          <div class="pn-settings-grid">
            <div>
              <FormKit
                v-model="settings.general.enableVisitStats"
                name="enableVisitStats"
                type="switch"
                label="启用访问统计"
                help="关闭后不再记录新的访问，已有历史数据仍会在仪表盘展示。"
              />
              <FormKit
                v-model="settings.general.showFooterStats"
                name="showFooterStats"
                type="switch"
                label="在前台页脚显示访问统计"
                help="在备案信息上方展示今日浏览量、今日访客和累计浏览量；统计关闭后仍可展示历史数据。"
              />
            </div>
            <FormKit
              v-model="settings.general.trustedProxyIps"
              name="trustedProxyIps"
              type="text"
              label="可信反向代理地址"
              placeholder="例如 172.18.0.2, 10.0.0.5"
              help="只有这些地址（以及本机回环地址）发来的 X-Forwarded-For / X-Real-IP 才会被采信。留空表示只信任本机代理；Halo 直接暴露给内网访客时请保持留空，否则访客可伪造 IP 刷高独立访客数。多个地址用英文逗号分隔。"
            />
          </div>
        </VCard>

        <VCard title="外观设置">
          <p class="pn-settings-description">
            主题色、明暗模式与背景会作为访客的默认外观，背景支持图片或视频，访客仍可在前台自行切换。
          </p>
          <div class="pn-settings-grid">
            <FormKit
              v-model="settings.appearance.defaultColor"
              name="defaultColor"
              type="select"
              label="默认主题色"
              :options="THEME_COLORS"
              help="访客仍可在页面右上角切换并记住自己的选择。"
            />
            <FormKit
              v-model="settings.appearance.defaultNightMode"
              name="defaultNightMode"
              type="select"
              label="默认明暗模式"
              :options="NIGHT_MODES"
              help="访客手动切换后会覆盖此默认值。"
            />
            <div class="pn-settings-wide pn-settings-switches">
              <FormKit
                v-model="settings.appearance.showClock"
                name="showClock"
                type="switch"
                label="显示时钟与问候"
              />
              <FormKit
                v-model="settings.appearance.showYiyan"
                name="showYiyan"
                type="switch"
                label="显示随机一言"
              />
              <FormKit
                v-model="settings.appearance.showCategoryIcons"
                name="showCategoryIcons"
                type="switch"
                label="显示前端导航栏分类图标"
                help="在前端分类导航栏的分类名称前显示对应图标。"
              />
              <FormKit
                v-model="settings.appearance.enableHoverPreview"
                name="enableHoverPreview"
                type="switch"
                label="开启鼠标悬停预览"
                help="鼠标悬停链接时展示主页封面，仅在支持鼠标悬停的设备启用。"
              />
            </div>
            <div class="pn-settings-wide pn-settings-background">
              <div class="pn-settings-grid">
                <MediaPicker
                  v-model="settings.appearance.backgroundUrl"
                  v-model:type="settings.appearance.backgroundType"
                  kind="background"
                  label="背景媒体"
                  empty-hint="留空使用插件内置背景图"
                  :fallback-url="DEFAULT_BACKGROUND_URL"
                  help="支持图片与视频（MP4 / WebM / OGG / MOV）；可上传、从附件库选择或输入链接。"
                />
                <MediaPicker
                  v-model="settings.appearance.previewLoadingImage"
                  kind="preview"
                  label="鼠标预览加载动画"
                  empty-hint="留空使用默认加载动画"
                  help="支持上传 GIF、从附件库选择或输入图片链接；仅在封面加载时显示。"
                />
              </div>
              <FormKit
                v-model="settings.appearance.randomImageApi"
                name="randomImageApi"
                type="text"
                label="随机图片 API"
                placeholder="https://example.com/random-image"
                help="填写直接返回图片或重定向到图片的 API（不支持 JSON 响应）。填写后优先使用随机图片，每次进入页面重新请求；留空使用上方背景媒体。"
              />
              <FormKit
                v-model="settings.appearance.defaultBackgroundMedia"
                name="defaultBackgroundMedia"
                type="switch"
                label="首次访问默认显示图片背景"
                help="开启后新访客直接显示背景，随机图片 API 优先；上方选择视频时显示视频。访客自行选择的背景模式仍会保留。"
              />
            </div>
          </div>
        </VCard>

        <VCard title="附件存储">
          <p class="pn-settings-description">
            新增/编辑链接时点击网址右侧「获取」，抓取到的网站图标会先转存到 Halo 附件再引用，
            避免原站点更换图标或开启防盗链后图标失效；控制台中通过「上传」添加的图标、LOGO
            与背景也会存入这里指定的策略与分组。
          </p>
          <VAlert
            v-if="storageOptionsError"
            type="warning"
            :description="`读取存储策略或分组失败：${storageOptionsError}`"
          />
          <div class="pn-settings-grid">
            <FormKit
              v-model="settings.icons.policyName"
              name="iconPolicyName"
              type="select"
              label="存储策略"
              :clearable="true"
              :auto-select="false"
              :options="policyOptions"
              :disabled="storageOptionsLoading"
              help="留空时使用 Halo 上传界面中优先级最高的存储策略。"
            />
            <FormKit
              v-model="settings.icons.groupName"
              name="iconGroupName"
              type="select"
              label="附件分组"
              :clearable="true"
              :auto-select="false"
              :options="groupOptions"
              :disabled="storageOptionsLoading"
              help="留空时不分组。"
            />
          </div>
        </VCard>

        <VCard title="搜索设置">
          <p class="pn-settings-description">
            控制前台搜索框的联想建议行为。
          </p>
          <FormKit
            v-model="settings.search.enableBaiduSuggestions"
            name="enableBaiduSuggestions"
            type="switch"
            label="启用百度搜索建议"
            help="仅在选用百度引擎时请求 suggestion.baidu.com，可关闭以满足离线或隐私要求。"
          />
        </VCard>
      </div>

      <div class="pn-settings-footer">
        <span class="pn-settings-footer-hint">
          保存后立即应用到前台 /nav 页面
        </span>
        <VButton size="sm" type="primary" :loading="saving" @click="submit">
          保存设置
        </VButton>
      </div>
    </FormKit>
  </div>
</template>
