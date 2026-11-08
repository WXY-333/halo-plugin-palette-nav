<script lang="ts" setup>
import { computed, ref } from "vue";
import {
  Toast,
  VButton,
  VDropdown,
  VDropdownDivider,
  VDropdownItem,
  VModal,
} from "@halo-dev/components";
import { utils } from "@halo-dev/ui-shared";
import { isSafeImageUrl, isSpriteIcon, isUnsafeIconMarkup } from "@/utils/icon";
import {
  useAttachmentLibrary,
  useAttachmentUpload,
} from "@/composables/useMediaSources";
import IconPreview from "./IconPreview.vue";
import MediaLinkInput from "./MediaLinkInput.vue";

const props = withDefaults(
  defineProps<{
    modelValue?: string;
  }>(),
  {
    modelValue: "",
  }
);

const emit = defineEmits<{
  "update:modelValue": [value: string];
}>();

const current = computed(() => (props.modelValue ?? "").trim());

const isSvgValue = (value: string) => value.toLowerCase().startsWith("<svg");

const typeLabel = computed(() => {
  if (!current.value) {
    return "未设置图标";
  }
  if (isSpriteIcon(current.value)) {
    return "";
  }
  if (isSvgValue(current.value)) {
    return "SVG 代码";
  }
  if (isSafeImageUrl(current.value)) {
    return "图片地址";
  }
  return "Emoji / 文本";
});

const valueLabel = computed(() => {
  if (!current.value) {
    return "保存后使用默认图标";
  }
  if (isSpriteIcon(current.value)) {
    const id = current.value.match(/#([A-Za-z0-9_-]+)/)?.[1] ?? "";
    return `#${id}`;
  }
  if (isSvgValue(current.value)) {
    return `已输入 ${current.value.length} 个字符`;
  }
  return current.value;
});

function updateValue(value: string) {
  emit("update:modelValue", value);
}

function clear() {
  updateValue("");
}

/* ----------------------------- 上传 / 附件库 ----------------------------- */

function isImageFile(file: File): boolean {
  return file.type.startsWith("image/") || /\.svg$/i.test(file.name);
}

const { uploading, fileInputRef, pickFile, onFileChange } = useAttachmentUpload({
  validate: isImageFile,
  rejectMessage: () => "请选择图片或 SVG 文件",
  successMessage: () => "图标已上传",
  onUploaded: (permalink) => updateValue(permalink),
});

const {
  AttachmentSelectorModal,
  libraryVisible,
  openLibrary,
  onAttachmentSelect,
} = useAttachmentLibrary((attachment) => {
  const url = utils.attachment.getUrl(attachment);
  if (url) {
    updateValue(url);
  }
});

/* --------------------------- 输入链接 --------------------------- */

const linkValue = computed(() =>
  isSafeImageUrl(current.value) ? current.value : ""
);

function applyLink(value: string) {
  updateValue(value);
}

/* --------------------------- SVG 代码 --------------------------- */

const inputVisible = ref(false);
const inputTitle = "输入 SVG 代码";
const svgValue = ref("");

function openSvgInput() {
  svgValue.value = isSvgValue(current.value) ? current.value : "";
  inputVisible.value = true;
}

function submitSvg() {
  const value = svgValue.value.trim();
  if (!isSvgValue(value) || !value.toLowerCase().includes("</svg>")) {
    Toast.warning("请输入完整的 SVG 代码");
    return;
  }
  if (isUnsafeIconMarkup(value)) {
    Toast.warning("SVG 代码包含脚本或事件属性，无法保存");
    return;
  }
  updateValue(value);
  inputVisible.value = false;
}
</script>

<template>
  <div class="pn-icon-field">
    <div class="pn-icon-thumb" :class="{ empty: !current }">
      <IconPreview :icon="current" :size="72" />
      <VDropdown :triggers="['click']" placement="bottom-start">
        <button type="button" class="pn-icon-trigger" title="更换图标" aria-label="更换图标">
          <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M12 20h9" />
            <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
          </svg>
        </button>
        <template #popper>
          <VDropdownItem @click="pickFile">上传</VDropdownItem>
          <VDropdownItem @click="openLibrary">从附件库选择</VDropdownItem>
          <MediaLinkInput :value="linkValue" @apply="applyLink" />
          <VDropdownItem @click="openSvgInput">SVG 代码</VDropdownItem>
          <template v-if="current">
            <VDropdownDivider />
            <VDropdownItem type="danger" @click="clear">移除</VDropdownItem>
          </template>
        </template>
      </VDropdown>
      <span v-if="uploading" class="pn-icon-uploading">上传中…</span>
    </div>
    <div class="pn-icon-field-meta">
      <span v-if="typeLabel" class="pn-icon-field-type">{{ typeLabel }}</span>
      <span class="pn-icon-field-value" :title="valueLabel">{{ valueLabel }}</span>
      <span class="pn-icon-field-hint">
        支持上传图片 / SVG、附件库、图片链接与 SVG 代码
      </span>
    </div>
    <input
      ref="fileInputRef"
      class="pn-icon-file"
      type="file"
      accept="image/*,.svg,image/svg+xml"
      @change="onFileChange"
    />
  </div>

  <!-- Halo 控制台全局组件，用法见官方文档。仅在打开时挂载：宿主弹窗内容用
       v-show 渲染，常驻挂载会在每次打开编辑弹窗时立即请求附件列表。
       宿主组件本身没有 visible 属性，挂载即打开并通过 close 事件关闭，
       与 Halo 自带的 AttachmentInput 用法一致。 -->
  <component
    :is="AttachmentSelectorModal"
    v-if="libraryVisible"
    :accepts="['image/*']"
    :max="1"
    @select="onAttachmentSelect"
    @close="libraryVisible = false"
  />

  <!-- 输入 SVG / 文本。Teleport 到 body：IconPicker 位于链接/分类的编辑弹窗内，
       嵌套渲染时 VModal 的遮罩会盖住它自己的底部按钮。 -->
  <Teleport to="body">
    <VModal v-model:visible="inputVisible" :title="inputTitle" :width="620">
      <div class="pn-form">
        <div class="pn-field">
          <label class="pn-label" for="pn-icon-svg">SVG 代码</label>
          <textarea
            id="pn-icon-svg"
            v-model="svgValue"
            class="pn-textarea pn-icon-svg"
            rows="8"
            placeholder='<svg viewBox="0 0 24 24"><path d="..."/></svg>'
          ></textarea>
          <span class="pn-hint">
            粘贴完整 SVG 代码，或雪碧图引用 &lt;svg&gt;&lt;use href="#lyicon-xxx"&gt;&lt;/use&gt;&lt;/svg&gt;。
            内联 SVG 会以图片形式安全渲染。
          </span>
        </div>

        <div class="pn-modal-footer">
          <VButton size="sm" @click="inputVisible = false">取消</VButton>
          <VButton size="sm" type="primary" @click="submitSvg">确定</VButton>
        </div>
      </div>
    </VModal>
  </Teleport>
</template>