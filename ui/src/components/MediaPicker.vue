<script lang="ts" setup>
import { computed, ref, watch } from "vue";
import {
  Toast,
  VDropdown,
  VDropdownDivider,
  VDropdownItem,
} from "@halo-dev/components";
import { utils } from "@halo-dev/ui-shared";
import { isSafeImageUrl } from "@/utils/icon";
import {
  detectMediaKindFromUrl,
  isMediaKind,
  mediaKindFromMime,
  mediaKindFromUrl,
} from "@/utils/media";
import type { MediaKind } from "@/utils/media";
import {
  useAttachmentLibrary,
  useAttachmentUpload,
} from "@/composables/useMediaSources";
import MediaLinkInput from "./MediaLinkInput.vue";

const props = withDefaults(
  defineProps<{
    modelValue?: string;
    type?: MediaKind;
    kind?: "logo" | "background" | "preview";
    label?: string;
    help?: string;
    emptyHint?: string;
    /** Shown instead of the empty placeholder when no media is configured. */
    fallbackUrl?: string;
  }>(),
  {
    modelValue: "",
    type: "image",
    kind: "logo",
    label: "",
    help: "",
    emptyHint: "",
    fallbackUrl: "",
  }
);

const emit = defineEmits<{
  "update:modelValue": [value: string];
  "update:type": [value: MediaKind];
}>();

const current = computed(() => (props.modelValue ?? "").trim());
const allowsVideo = computed(() => props.kind === "background");
const currentKind = computed<MediaKind>(() =>
  allowsVideo.value && props.type === "video" ? "video" : "image"
);
const isVideo = computed(() => !!current.value && currentKind.value === "video");
const usingFallback = computed(() => !current.value && !!props.fallbackUrl);
const previewUrl = computed(() => {
  if (isVideo.value) {
    return "";
  }
  const candidate = current.value || props.fallbackUrl;
  return candidate && isSafeImageUrl(candidate) ? candidate : "";
});
const typeLabel = computed(() => {
  if (isVideo.value) {
    return "视频";
  }
  if (current.value) {
    return "图片";
  }
  return props.fallbackUrl ? "插件默认" : "未设置";
});
const valueLabel = computed(
  () => current.value || props.emptyHint || "未设置"
);
const acceptAttribute = computed(() =>
  allowsVideo.value
    ? "image/*,video/*,.svg,image/svg+xml"
    : "image/*,.svg,image/svg+xml"
);
const linkLabel = computed(() => (allowsVideo.value ? "媒体地址" : "图片地址"));

const mediaKindOptions = [
  { label: "图片", value: "image" },
  { label: "视频", value: "video" },
];

function applyValue(url: string, kind?: MediaKind) {
  const nextUrl = url.trim();
  emit("update:modelValue", nextUrl);
  if (allowsVideo.value) {
    emit(
      "update:type",
      kind ?? (nextUrl ? mediaKindFromUrl(nextUrl) : "image")
    );
  }
}

function clear() {
  applyValue("");
}

/* ----------------------------- 上传 / 附件库 ----------------------------- */

function fileKind(file: File): MediaKind | undefined {
  const mime = (file.type || "").toLowerCase();
  if (mime.startsWith("video/")) {
    return "video";
  }
  if (mime.startsWith("image/") || /\.svg$/i.test(file.name)) {
    return "image";
  }
  return undefined;
}

const { uploading, fileInputRef, pickFile, onFileChange } = useAttachmentUpload({
  validate(file) {
    const kind = fileKind(file);
    return kind !== undefined && (kind !== "video" || allowsVideo.value);
  },
  rejectMessage: () =>
    allowsVideo.value ? "请选择图片或视频文件" : "请选择图片文件",
  successMessage: (file) =>
    fileKind(file) === "video" ? "视频已上传" : "图片已上传",
  onUploaded(permalink, mediaType, file) {
    applyValue(
      permalink,
      mediaKindFromMime(mediaType) ?? fileKind(file) ?? "image"
    );
  },
});

const {
  AttachmentSelectorModal,
  libraryVisible,
  openLibrary,
  onAttachmentSelect,
} = useAttachmentLibrary((first) => {
  const simple = utils.attachment.convertToSimple(first);
  const url = simple?.url ?? utils.attachment.getUrl(first);
  if (!url) {
    return;
  }
  const kind = mediaKindFromMime(simple?.mediaType) ?? mediaKindFromUrl(url);
  if (!allowsVideo.value && kind === "video") {
    Toast.warning("请选择图片附件");
    return;
  }
  applyValue(url, kind);
});

/* --------------------------- 输入链接 --------------------------- */

const linkInitial = computed(() =>
  isSafeImageUrl(current.value) ? current.value : ""
);
const linkKind = ref<MediaKind>("image");

watch(
  currentKind,
  (kind) => {
    linkKind.value = kind;
  },
  { immediate: true }
);

function onLinkUrlChange(url: string) {
  if (!allowsVideo.value) {
    return;
  }
  const detected = detectMediaKindFromUrl(url);
  if (detected) {
    linkKind.value = detected;
  }
}

function onLinkApply(value: string, kind: string) {
  applyValue(
    value,
    allowsVideo.value && isMediaKind(kind) ? kind : "image"
  );
}
</script>

<template>
  <div
    class="pn-media-field"
    :class="`pn-media-field--${kind}`"
  >
    <span v-if="label" class="pn-form-label">{{ label }}</span>
    <div class="pn-media-body">
      <div class="pn-media-thumb" :class="{ empty: !current }">
        <div class="pn-media-frame">
          <img
            v-if="previewUrl"
            :src="previewUrl"
            alt=""
            loading="lazy"
            decoding="async"
          />
          <span v-else-if="isVideo" class="pn-media-video">
            <svg
              viewBox="0 0 24 24"
              width="26"
              height="26"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              aria-hidden="true"
            >
              <rect x="3" y="5" width="18" height="14" rx="2" />
              <path d="M10 9.5v5l4.5-2.5L10 9.5Z" fill="currentColor" stroke="none" />
            </svg>
            <span>视频</span>
          </span>
          <span v-else class="pn-media-placeholder">未设置</span>
          <span v-if="usingFallback" class="pn-media-default-badge">默认</span>
        </div>

        <VDropdown :triggers="['click']" placement="bottom-start">
          <button
            type="button"
            class="pn-icon-trigger"
            title="更换媒体"
            aria-label="更换媒体"
          >
            <svg
              viewBox="0 0 24 24"
              width="13"
              height="13"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
              <path d="M12 20h9" />
              <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
            </svg>
          </button>
          <template #popper>
            <VDropdownItem @click="pickFile">上传</VDropdownItem>
            <VDropdownItem @click="openLibrary">从附件库选择</VDropdownItem>
            <MediaLinkInput
              :label="linkLabel"
              :value="linkInitial"
              :select-label="allowsVideo ? '媒体类型' : ''"
              :select-value="linkKind"
              :select-options="allowsVideo ? mediaKindOptions : []"
              @update:url="onLinkUrlChange"
              @apply="onLinkApply"
            />
            <template v-if="current">
              <VDropdownDivider />
              <VDropdownItem type="danger" @click="clear">移除</VDropdownItem>
            </template>
          </template>
        </VDropdown>

        <span v-if="uploading" class="pn-icon-uploading">上传中…</span>
      </div>

      <div class="pn-media-meta">
        <span class="pn-media-kind">{{ typeLabel }}</span>
        <span class="pn-media-value" :title="current || emptyHint">
          {{ valueLabel }}
        </span>
        <span v-if="help" class="pn-media-hint">{{ help }}</span>
      </div>
    </div>

    <input
      ref="fileInputRef"
      class="pn-icon-file"
      type="file"
      :accept="acceptAttribute"
      @change="onFileChange"
    />

    <!-- Mount only while open: the host modal renders its body with v-show, so a
         permanently mounted picker would query the attachment list on every
         settings render and may toast a 403 for roles without attachment access.
         The host component has no `visible` prop: it opens on mount and emits
         `close`, so mirror Halo's own AttachmentInput usage. -->
    <component
      :is="AttachmentSelectorModal"
      v-if="libraryVisible"
      :accepts="allowsVideo ? ['image/*', 'video/*'] : ['image/*']"
      :max="1"
      @select="onAttachmentSelect"
      @close="libraryVisible = false"
    />
  </div>
</template>
