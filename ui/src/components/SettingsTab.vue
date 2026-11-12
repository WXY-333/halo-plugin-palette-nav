<script lang="ts" setup>
import { computed, getCurrentInstance, onMounted, onUnmounted, ref, watch } from "vue";
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
import { CONTEXT_PATH, errorMessage, isNotFound, testBackgroundApi } from "@/api/nav";
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
const testingBackground = ref(false);
const backgroundTestMessage = ref("");
const backgroundTestSuccess = ref(false);
let backgroundMessageTimer: number | undefined;
let backgroundMessageRevision = 0;

function clearBackgroundTestMessage() {
  window.clearTimeout(backgroundMessageTimer);
  backgroundTestMessage.value = "";
  backgroundPreviewUrl.value = "";
  backgroundCandidates.value = [];
  backgroundResponse.value = "";
  backgroundMessageRevision += 1;
}

function expireBackgroundTestMessage() {
  window.clearTimeout(backgroundMessageTimer);
  backgroundMessageTimer = window.setTimeout(clearBackgroundTestMessage, 4000);
}

onUnmounted(clearBackgroundTestMessage);

function canLoadImage(url: string): Promise<boolean> {
  return new Promise((resolve) => {
    const image = new Image();
    const done = (success: boolean) => {
      window.clearTimeout(timer);
      image.onload = null;
      image.onerror = null;
      resolve(success);
    };
    const timer = window.setTimeout(() => done(false), 10000);
    image.onload = () => done(true);
    image.onerror = () => done(false);
    image.src = url;
  });
}

const backgroundCandidates = ref<Array<{ path: string; url: string }>>([]);
const backgroundResponse = ref("");
const backgroundPreviewUrl = ref("");
const backgroundDetectedType = ref("");
const manualBackgroundPath = ref(false);
let backgroundRequestRevision = 0;

async function selectBackgroundField(event: Event) {
  settings.value.appearance.randomImageJsonPath = (event.target as HTMLSelectElement).value;
  await testRandomBackground();
}

async function testRandomBackground() {
  const requestRevision = ++backgroundRequestRevision;
  clearBackgroundTestMessage();
  const messageRevision = backgroundMessageRevision;
  const url = settings.value.appearance.randomImageApi.trim();
  let expectedPath = settings.value.appearance.randomImageJsonPath.trim();
  const current = () => requestRevision === backgroundRequestRevision
    && messageRevision === backgroundMessageRevision
    && settings.value.appearance.randomImageApi.trim() === url
    && settings.value.appearance.randomImageJsonPath.trim() === expectedPath;
  backgroundPreviewUrl.value = "";
  backgroundTestSuccess.value = false;
  if (!url || !isSafeImageUrl(url)) {
    backgroundTestMessage.value = "请先填写随机图片 API 的接口网址，再点击测试连接";
    expireBackgroundTestMessage();
    return;
  }
  if (/^https?:\/\//i.test(expectedPath)) {
    manualBackgroundPath.value = true;
    backgroundTestMessage.value = "字段路径不是网址，请填写 url、data.url 等路径，或清空后测试以自动识别";
    expireBackgroundTestMessage();
    return;
  }
  testingBackground.value = true;
  backgroundTestMessage.value = "正在识别接口返回内容…";
  try {
    if (url.startsWith("/")) {
      const directUrl = `${CONTEXT_PATH}${url}`;
      const loaded = await canLoadImage(directUrl);
      if (!current()) return;
      if (loaded) {
        expectedPath = "";
        settings.value.appearance.randomImageJsonPath = "";
        backgroundDetectedType.value = "image";
        backgroundCandidates.value = [];
        backgroundResponse.value = "";
        backgroundPreviewUrl.value = directUrl;
        backgroundTestSuccess.value = true;
        backgroundTestMessage.value = "接口直接返回图片，预览成功，保存设置后生效";
      } else {
        backgroundTestMessage.value = "站内图片加载失败；JSON 接口请填写完整的公网 http(s) 网址";
      }
      return;
    }
    const result = await testBackgroundApi(url, expectedPath);
    if (!current()) return;
    backgroundDetectedType.value = result.type || "";
    backgroundCandidates.value = (result.candidates || []).flatMap(candidate =>
      candidate.path && candidate.url ? [{ path: candidate.path, url: candidate.url }] : []);
    backgroundResponse.value = result.jsonPreview || "";
    if (result.type === "image") {
      expectedPath = "";
      settings.value.appearance.randomImageJsonPath = "";
      manualBackgroundPath.value = false;
    } else if (result.type === "json") {
      if (result.jsonPath) {
        expectedPath = result.jsonPath;
        settings.value.appearance.randomImageJsonPath = expectedPath;
      }
      if (!backgroundCandidates.value.length) manualBackgroundPath.value = true;
    }
    if (result.success && result.url) {
      backgroundTestMessage.value = "已识别图片地址，正在验证图片加载…";
      const loaded = await canLoadImage(result.url);
      if (!current()) return;
      backgroundTestSuccess.value = loaded;
      backgroundPreviewUrl.value = loaded ? result.url : "";
      backgroundTestMessage.value = loaded
        ? `${result.type === "json" ? `已选择字段 ${expectedPath}` : "接口直接返回图片"}，预览成功，保存设置后生效`
        : "已识别图片地址，但图片加载失败或超时，请检查防盗链、HTTPS 限制或选择其他字段";
    } else {
      backgroundTestMessage.value = result.message || "未找到可用图片，请检查接口返回内容";
    }
  } catch (error) {
    if (current()) backgroundTestMessage.value = errorMessage(error) || "测试失败，请检查网络连接或管理权限";
  } finally {
    if (requestRevision === backgroundRequestRevision) testingBackground.value = false;
    if (current()) expireBackgroundTestMessage();
  }
}
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
watch(() => settings.value.appearance.randomImageApi, () => {
  if (loading.value) return;
  backgroundRequestRevision += 1;
  testingBackground.value = false;
  clearBackgroundTestMessage();
  backgroundCandidates.value = [];
  backgroundResponse.value = "";
  backgroundPreviewUrl.value = "";
  backgroundDetectedType.value = "";
  manualBackgroundPath.value = false;
  settings.value.appearance.randomImageJsonPath = "";
}, { flush: "sync" });
watch(() => settings.value.appearance.randomImageJsonPath, () => {
  backgroundPreviewUrl.value = "";
  backgroundTestSuccess.value = false;
}, { flush: "sync" });
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
  clearBackgroundTestMessage();
  const cacheSeconds = Number(settings.value.appearance.randomImageCacheSeconds);
  if (String(settings.value.appearance.randomImageCacheSeconds).trim() === "" || !Number.isInteger(cacheSeconds) || cacheSeconds < 0 || cacheSeconds > 86400) {
    Toast.warning("API 缓存时长请填写 0–86400 之间的整数秒数");
    return;
  }
  settings.value.appearance.randomImageCacheSeconds = cacheSeconds;
  const randomImageApi = settings.value.appearance.randomImageApi.trim();
  if (randomImageApi && backgroundDetectedType.value === "json"
    && !settings.value.appearance.randomImageJsonPath.trim()) {
    Toast.warning("此接口返回 JSON，请先选择图片地址字段，或展开高级配置手动填写字段路径");
    return;
  }
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
            <div class="pn-settings-logo-row">
            <MediaPicker
              v-model="settings.general.logoUrl"
              kind="logo"
              label="LOGO 地址"
              empty-hint="留空使用插件默认 LOGO"
              :fallback-url="DEFAULT_LOGO_URL"
              help="支持上传图片、从附件库选择，或输入 https:// 与 /upload/... 地址。"
            />
            <FormKit
              v-model="settings.appearance.roundedLogo"
              name="roundedLogo"
              type="switch"
              label="LOGO 圆角"
              help="开启后同步为页面 LOGO 和 favicon 添加圆角。"
            />
            </div>
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
                help="鼠标悬停链接时展示主页封面或简介，仅在支持鼠标悬停的设备启用。"
              />
              <FormKit
                v-model="settings.appearance.showLinkCoverField"
                name="showLinkCoverField"
                type="switch"
                label="链接新增/编辑页面显示主页封面"
                help="开启后在链接新增和编辑弹窗显示主页封面配置；关闭不清空已有封面。"
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
                  help="支持图片与视频（MP4 / WebM / OGG / MOV）；可上传、从附件库选择或直接输入随机图片地址。返回 JSON 的接口请使用下方随机图片 API 并配置字段路径。"
                />
                <MediaPicker
                  v-model="settings.appearance.previewLoadingImage"
                  kind="preview"
                  label="鼠标预览加载动画"
                  empty-hint="留空使用默认加载动画"
                  help="支持上传 GIF、从附件库选择或输入图片链接；仅在封面加载时显示。"
                />
              </div>
              <div>
              <label class="formkit-label" for="pn-random-image-api">随机图片 API</label>
              <div class="pn-background-api-test">
              <FormKit
                id="pn-random-image-api"
                v-model="settings.appearance.randomImageApi"
                name="randomImageApi"
                type="text"
                placeholder="https://example.com/random-image"
                aria-describedby="pn-random-image-api-help"
              />
              <VButton :loading="testingBackground" :disabled="testingBackground" @click="testRandomBackground">
                测试连接
              </VButton>
              </div>
              <p id="pn-random-image-api-help" class="formkit-help">填写随机图片接口网址，支持直接返回图片或 JSON。点击「测试连接」自动识别返回内容、选择图片地址并预览，确认后保存设置。</p>
              </div>
              <div v-if="backgroundDetectedType === 'json' || settings.appearance.randomImageJsonPath" class="pn-background-result">
                <p class="formkit-help">{{ backgroundCandidates.length ? '接口返回 JSON，请确认用作背景的图片地址字段。' : settings.appearance.randomImageJsonPath ? '已配置 JSON 图片字段，点击测试连接可重新识别和预览。' : '尚未选择图片字段，点击测试连接重新识别，或手动填写字段路径。' }}</p>
                <template v-if="backgroundCandidates.length">
                  <label class="formkit-label" for="pn-background-field">图片地址字段</label>
                  <select
                    id="pn-background-field"
                    class="pn-background-field-select"
                    :value="settings.appearance.randomImageJsonPath"
                    :disabled="testingBackground"
                    @change="selectBackgroundField"
                  >
                    <option value="" disabled>请选择图片地址字段</option>
                    <option
                      v-if="settings.appearance.randomImageJsonPath && !backgroundCandidates.some(candidate => candidate.path === settings.appearance.randomImageJsonPath)"
                      :value="settings.appearance.randomImageJsonPath"
                    >手动路径：{{ settings.appearance.randomImageJsonPath }}</option>
                    <option v-for="candidate in backgroundCandidates" :key="candidate.path" :value="candidate.path">
                      {{ candidate.path }} — {{ candidate.url }}
                    </option>
                  </select>
                  <p class="formkit-help">只有一个候选字段时会自动选中；切换字段后自动验证图片，保存设置后生效。</p>
                </template>
                <p v-else-if="settings.appearance.randomImageJsonPath" class="formkit-help">当前图片字段：{{ settings.appearance.randomImageJsonPath }}</p>
              </div>
              <div v-if="backgroundPreviewUrl" class="pn-background-preview">
                <span class="formkit-label">图片预览</span>
                <img :src="backgroundPreviewUrl" alt="随机背景图片预览" />
                <p class="formkit-help">图片加载成功，保存设置后生效。随机接口的实际背景图片可能与本次预览不同。</p>
              </div>
              <div>
                <div class="pn-background-options-row">
                <VButton class="pn-background-manual-toggle" @click="manualBackgroundPath = !manualBackgroundPath">
                  {{ manualBackgroundPath ? '收起手动配置' : '手动填写字段路径（高级）' }}
                </VButton>
                <label class="pn-background-cache" for="pn-background-cache-seconds">
                  <span>API 缓存时长（秒）</span>
                  <input id="pn-background-cache-seconds" v-model.number="settings.appearance.randomImageCacheSeconds"
                    type="number" min="0" max="86400" step="1" required
                    title="默认 60 秒，可填 0–86400 秒；0 表示每次刷新重新请求" />
                </label>
                </div>
                <p class="formkit-help">适用于图片直返和 JSON 随机图片 API，默认 60 秒，可填 0–86400 秒。0 表示每次刷新重新请求；缓存期间共用同一张图片。实际换图也取决于第三方接口，时间越短请求越频繁。</p>
                <div v-if="manualBackgroundPath" class="pn-background-manual">
                  <FormKit
                    id="pn-random-image-json-path"
                    v-model="settings.appearance.randomImageJsonPath"
                    name="randomImageJsonPath"
                    type="text"
                    label="JSON 图片字段路径"
                    placeholder="例如 url 或 data[0].url"
                    help="这里填字段路径，不是接口网址。例如返回 {&quot;url&quot;:&quot;图片地址&quot;} 时填 url；$ 表示响应本身是图片地址字符串。修改后点击上方「测试连接」验证，留空重新自动识别。"
                  />
                </div>
              </div>
              <details v-if="backgroundResponse" class="pn-background-response">
                <summary>查看接口返回内容（JSON）</summary>
                <pre>{{ backgroundResponse }}</pre>
              </details>
              <p v-if="backgroundTestMessage" class="pn-background-api-feedback" :class="{ 'is-success': backgroundTestSuccess }" role="status" aria-live="polite">
                {{ backgroundTestMessage }}
              </p>
              <FormKit
                v-model="settings.appearance.defaultBackgroundMedia"
                name="defaultBackgroundMedia"
                type="switch"
                label="首次访问默认显示背景"
                help="开启后，没有保存过背景偏好的访客进入页面时自动显示背景。配置视频时优先播放视频；播放失败或 900 毫秒内未启动时保留主题色。图片模式优先加载随机图片 API，加载期间显示主题色；API 失败时依次使用已设置图片和默认图片。访客手动关闭背景后仍保持关闭。"
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
