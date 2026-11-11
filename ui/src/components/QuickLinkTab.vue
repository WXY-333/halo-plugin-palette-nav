<script lang="ts" setup>
import { computed, getCurrentInstance, ref, watch } from "vue";
import {
  Dialog,
  Toast,
  VAlert,
  VButton,
  VCard,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VModal,
  VSwitch,
  VTag,
  IconAddCircle,
  IconDeleteBin,
  IconRiPencilFill,
  VDropdownDivider,
  VDropdownItem,
} from "@halo-dev/components";
import {
  QUICK_LINK_PLURAL,
  createEntity,
  createName,
  deleteEntity,
  errorMessage,
  listQuickLinks,
  updateEntity,
} from "@/api/nav";
import type { NavQuickLink } from "@/types/nav";
import { useBulkDelete } from "@/composables/useBulkDelete";
import { useBulkVisibility } from "@/composables/useBulkVisibility";
import { useDragSort } from "@/composables/useDragSort";
import { useRowVisibility } from "@/composables/useRowVisibility";
import { applyDraftOrder, useSortDraft } from "@/composables/useSortDraft";
import DragHandle from "./DragHandle.vue";
import DragPlaceholder from "./DragPlaceholder.vue";
import RefreshButton from "./RefreshButton.vue";
import SelectAllCheckbox from "./SelectAllCheckbox.vue";
import IconPicker from "./IconPicker.vue";
import IconPreview from "./IconPreview.vue";
import { isSafeLinkUrl } from "@/utils/icon";
import { syncEntityVersion } from "@/utils/entity";
import { moveItem, nextSort, sortUpdatesForOrder } from "@/utils/reorder";

const props = defineProps<{
  quickLinks: NavQuickLink[];
}>();

const emit = defineEmits<{
  refresh: [];
  "dirty-change": [dirty: boolean];
}>();

const {
  selectedNames,
  bulkSaving,
  selectedCount,
  setSelectedVisible,
} = useBulkVisibility(
  QUICK_LINK_PLURAL,
  () => props.quickLinks,
  () => emit("refresh")
);

const { bulkDeleting, deleteSelected } = useBulkDelete(
  QUICK_LINK_PLURAL,
  "快捷导航",
  () => props.quickLinks,
  selectedNames,
  () => emit("refresh")
);

const FORM_ID = "pn-quick-link-form";

const {
  draftNames,
  orderDirty,
  orderSaving,
  applyDraft,
  discardDraft,
  persistOrder,
} = useSortDraft(
  "NavQuickLink",
  () => props.quickLinks,
  () => emit("refresh"),
  listQuickLinks
);

const baseSorted = computed(() =>
  [...props.quickLinks].sort(
    (a, b) =>
      (a.spec.sort ?? 0) - (b.spec.sort ?? 0) ||
      a.metadata.name.localeCompare(b.metadata.name)
  )
);

const sorted = computed(() => applyDraftOrder(baseSorted.value, draftNames.value));

watch(orderDirty, (dirty) => emit("dirty-change", dirty));

function saveOrder() {
  void persistOrder(sortUpdatesForOrder(sorted.value));
}

const selectableNames = computed(() =>
  sorted.value.map((link) => link.metadata.name)
);

const modalVisible = ref(false);
const editing = ref<NavQuickLink | null>(null);
const saving = ref(false);
const formError = ref("");
const iconValue = ref("");

const instance = getCurrentInstance();

function submitForm() {
  const formkit = (
    instance?.proxy as { $formkit?: { submit: (name: string) => void } } | null
  )?.$formkit;
  formkit?.submit(FORM_ID);
}

function openCreate() {
  iconValue.value = "";
  editing.value = null;
  formError.value = "";
  modalVisible.value = true;
}

function openEdit(link: NavQuickLink) {
  iconValue.value = link.spec.icon ?? "";
  editing.value = link;
  formError.value = "";
  modalVisible.value = true;
}

async function save(payload: {
  displayName?: string;
  url?: string;
  openInNewTab?: boolean;
}) {
  if (saving.value) {
    return;
  }
  const displayName = (payload.displayName ?? "").trim();
  const url = (payload.url ?? "").trim();
  const openInNewTab = payload.openInNewTab !== false;
  if (!displayName) {
    formError.value = "请输入名称";
    return;
  }
  if (!isSafeLinkUrl(url)) {
    formError.value = "请输入合法的网址（http/https 或以 / 开头的站内路径）";
    return;
  }
  formError.value = "";
  saving.value = true;
  try {
    if (editing.value) {
      const entity = JSON.parse(JSON.stringify(editing.value)) as NavQuickLink;
      entity.spec.displayName = displayName;
      entity.spec.url = url;
      entity.spec.icon = iconValue.value.trim();
      entity.spec.openInNewTab = openInNewTab;
      const updated = await updateEntity(QUICK_LINK_PLURAL, entity);
      syncEntityVersion(editing.value, updated);
      editing.value.spec = updated.spec ?? editing.value.spec;
      Toast.success("快捷导航已更新");
    } else {
      const entity: NavQuickLink = {
        apiVersion: "palette-nav.halo.run/v1alpha1",
        kind: "NavQuickLink",
        metadata: { name: createName("quick") },
        spec: {
          displayName,
          icon: iconValue.value.trim(),
          url,
          openInNewTab,
          sort: nextSort(props.quickLinks),
          visible: true,
        },
      };
      await createEntity(QUICK_LINK_PLURAL, entity);
      Toast.success("快捷导航已创建");
    }
    modalVisible.value = false;
    emit("refresh");
  } catch (error) {
    formError.value = errorMessage(error);
    Toast.error(formError.value);
  } finally {
    saving.value = false;
  }
}

function remove(link: NavQuickLink) {
  Dialog.warning({
    title: "删除快捷导航",
    description: `确定删除「${link.spec.displayName}」吗？`,
    confirmText: "删除",
    cancelText: "取消",
    confirmType: "danger",
    onConfirm: async () => {
      try {
        await deleteEntity(QUICK_LINK_PLURAL, link.metadata.name);
        Toast.success("快捷导航已删除");
        emit("refresh");
      } catch (error) {
        Toast.error(errorMessage(error));
      }
    },
  });
}

const { pendingVisibility, setVisible } = useRowVisibility<NavQuickLink>(
  QUICK_LINK_PLURAL
);

const { dropSlot, sourceHeight, handleProps, rowProps } = useDragSort({
  canDrag: () => !orderSaving.value,
  onDrop: (from, to) => {
    applyDraft(
      moveItem(sorted.value, from, to).map((item) => item.metadata.name),
      baseSorted.value.map((item) => item.metadata.name)
    );
  },
});
</script>

<template>
  <VCard :body-class="['!p-0']">
    <div class="pn-toolbar">
      <SelectAllCheckbox v-model="selectedNames" :names="selectableNames" />
      <template v-if="selectedCount > 0">
        <span class="pn-toolbar-hint">已选 {{ selectedCount }} 项</span>
        <VButton
          size="sm"
          :loading="bulkSaving"
          :disabled="bulkSaving || bulkDeleting"
          @click="setSelectedVisible(true)"
        >
          启用
        </VButton>
        <VButton
          size="sm"
          :loading="bulkSaving"
          :disabled="bulkSaving || bulkDeleting"
          @click="setSelectedVisible(false)"
        >
          禁用
        </VButton>
        <VButton
          size="sm"
          type="danger"
          :loading="bulkDeleting"
          :disabled="bulkSaving || bulkDeleting"
          @click="deleteSelected"
        >
          删除
        </VButton>
      </template>
      <template v-if="orderDirty">
        <span class="pn-toolbar-hint">排序已修改，保存后生效。</span>
        <VButton size="sm" :disabled="orderSaving" @click="discardDraft">
          撤销
        </VButton>
        <VButton
          size="sm"
          type="danger"
          :loading="orderSaving"
          @click="saveOrder"
        >
          保存排序
        </VButton>
      </template>
      <span v-if="!orderDirty" class="pn-toolbar-hint">
        显示在导航页右上角「更多」菜单中；没有数据时该按钮自动隐藏。
      </span>
      <span class="pn-toolbar-spacer"></span>
      <div class="pn-toolbar-actions">
        <VButton size="sm" type="primary" @click="openCreate">
          <template #icon>
            <IconAddCircle />
          </template>
          新增快捷导航
        </VButton>
        <RefreshButton @refresh="emit('refresh')" />
      </div>
    </div>

    <VEmpty
      v-if="sorted.length === 0"
      title="还没有快捷导航"
      message="可以添加关于本站、申请收录等站内页面或常用外链。"
    >
      <template #actions>
        <VButton size="sm" @click="openCreate">新增快捷导航</VButton>
      </template>
    </VEmpty>

    <VEntityContainer v-else>
      <template v-for="(link, index) in sorted" :key="link.metadata.name">
        <DragPlaceholder v-if="dropSlot === index" :height="sourceHeight" />
        <VEntity
          v-bind="rowProps(index)"
          :is-selected="selectedNames.includes(link.metadata.name)"
        >
          <template #checkbox>
            <input
              v-model="selectedNames"
              type="checkbox"
              :value="link.metadata.name"
              :aria-label="`选择「${link.spec.displayName}」`"
            />
          </template>
          <template #start>
            <DragHandle
              v-bind="handleProps(index)"
              :label="`拖动调整「${link.spec.displayName}」的顺序`"
            />
            <button
              type="button"
              class="pn-edit-icon"
              :aria-label="`编辑「${link.spec.displayName}」`"
              @click="openEdit(link)"
            >
              <IconPreview :icon="link.spec.icon" />
            </button>
            <VEntityField
              class="pn-edit-target"
              :title="link.spec.displayName"
              :max-width="520"
              @click="openEdit(link)"
            >
              <template #description>
                <a
                  v-if="isSafeLinkUrl(link.spec.url)"
                  class="pn-entity-link"
                  :href="link.spec.url"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {{ link.spec.url }}
                </a>
                <span v-else class="pn-entity-link">{{ link.spec.url }}</span>
              </template>
            </VEntityField>
          </template>
          <template #end>
            <VTag class="pn-entity-tag">
              {{ link.spec.openInNewTab === false ? "当前窗口" : "新窗口" }}
            </VTag>
            <VSwitch
              :model-value="link.spec.visible !== false"
              :disabled="pendingVisibility.has(link.metadata.name)"
              :aria-label="`切换「${link.spec.displayName}」的可见性`"
              @update:model-value="(value) => setVisible(link, Boolean(value))"
            />
          </template>
          <template #dropdownItems>
            <VDropdownItem @click="openEdit(link)">
              <template #prefix-icon>
                <IconRiPencilFill />
              </template>
              编辑
            </VDropdownItem>
            <VDropdownDivider />
            <VDropdownItem type="danger" @click="remove(link)">
              <template #prefix-icon>
                <IconDeleteBin />
              </template>
              删除
            </VDropdownItem>
          </template>
        </VEntity>
      </template>
      <DragPlaceholder v-if="dropSlot === sorted.length" :height="sourceHeight" />
    </VEntityContainer>
  </VCard>

  <VModal
    v-if="modalVisible"
    :title="editing ? '编辑快捷导航' : '新增快捷导航'"
    :width="560"
    @close="modalVisible = false"
  >
    <FormKit :id="FORM_ID" type="form" :actions="false" @submit="save">
      <FormKit
        name="displayName"
        type="text"
        label="名称"
        placeholder="关于本站"
        validation="required"
        validation-label="名称"
        :value="editing?.spec.displayName ?? ''"
      />
      <FormKit
        name="url"
        type="text"
        label="网址"
        placeholder="https://example.com 或 /archives"
        validation="required"
        validation-label="网址"
        :value="editing?.spec.url ?? ''"
      />
      <FormKit
        name="openInNewTab"
        type="checkbox"
        label="新窗口打开"
        :value="editing ? editing.spec.openInNewTab !== false : true"
      />
      <IconPicker v-model="iconValue" />
    </FormKit>

    <VAlert
      v-if="formError"
      class="pn-form-block"
      type="error"
      :description="formError"
    />

    <template #footer>
      <div class="pn-modal-footer">
        <VButton size="sm" @click="modalVisible = false">取消</VButton>
        <VButton size="sm" type="primary" :loading="saving" @click="submitForm">
          保存
        </VButton>
      </div>
    </template>
  </VModal>
</template>
