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
  IconAddCircle,
  IconDeleteBin,
  IconRiPencilFill,
  VDropdownDivider,
  VDropdownItem,
} from "@halo-dev/components";
import {
  CATEGORY_PLURAL,
  createEntity,
  createName,
  deleteCategory,
  errorMessage,
  listCategories,
  updateEntity,
} from "@/api/nav";
import type { NavCategory, NavLink } from "@/types/nav";
import { useBulkDelete } from "@/composables/useBulkDelete";
import { useBulkVisibility } from "@/composables/useBulkVisibility";
import { useDragSort } from "@/composables/useDragSort";
import { useRowVisibility } from "@/composables/useRowVisibility";
import { applyDraftOrder, useSortDraft } from "@/composables/useSortDraft";
import { syncEntityVersion } from "@/utils/entity";
import { moveItem, nextSort, sortUpdatesForOrder } from "@/utils/reorder";
import DragHandle from "./DragHandle.vue";
import DragPlaceholder from "./DragPlaceholder.vue";
import IconPicker from "./IconPicker.vue";
import IconPreview from "./IconPreview.vue";
import RefreshButton from "./RefreshButton.vue";
import SelectAllCheckbox from "./SelectAllCheckbox.vue";

const props = defineProps<{
  categories: NavCategory[];
  links: NavLink[];
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
} = useBulkVisibility(CATEGORY_PLURAL, () => props.categories, () => emit("refresh"));

const FORM_ID = "pn-category-form";

const {
  draftNames,
  orderDirty,
  orderSaving,
  applyDraft,
  discardDraft,
  persistOrder,
} = useSortDraft(
  "NavCategory",
  () => props.categories,
  () => emit("refresh"),
  listCategories
);

const baseSorted = computed(() =>
  [...props.categories].sort(
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
  sorted.value.map((category) => category.metadata.name)
);

const linkCounts = computed(() => {
  const counts = new Map<string, number>();
  for (const link of props.links) {
    const key = link.spec.categoryName;
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
});

const { bulkDeleting, deleteSelected } = useBulkDelete(
  CATEGORY_PLURAL,
  "分类",
  () => props.categories,
  selectedNames,
  () => emit("refresh"),
  {
    // Categories cascade their links server-side, so use that endpoint and warn
    // about the links that go with them.
    remove: deleteCategory,
    warning: (targets) => {
      const count = targets.reduce(
        (total, category) =>
          total + (linkCounts.value.get(category.metadata.name) ?? 0),
        0
      );
      return count > 0 ? `将同时删除这些分类下的 ${count} 个链接。` : "";
    },
  }
);

const modalVisible = ref(false);
const editing = ref<NavCategory | null>(null);
const iconValue = ref("");
const saving = ref(false);
const formError = ref("");

const instance = getCurrentInstance();

function openCreate() {
  editing.value = null;
  iconValue.value = "";
  formError.value = "";
  modalVisible.value = true;
}

function openEdit(category: NavCategory) {
  editing.value = category;
  iconValue.value = category.spec.icon ?? "";
  formError.value = "";
  modalVisible.value = true;
}

function submitForm() {
  const formkit = (
    instance?.proxy as { $formkit?: { submit: (name: string) => void } } | null
  )?.$formkit;
  formkit?.submit(FORM_ID);
}

async function save(payload: { displayName?: string }) {
  // FormKit can submit again with Enter while the request is in flight; a
  // second PUT would reuse the same metadata.version and end in a 409.
  if (saving.value) {
    return;
  }
  const displayName = (payload.displayName ?? "").trim();
  if (!displayName) {
    formError.value = "请输入分类名称";
    return;
  }
  formError.value = "";
  saving.value = true;
  try {
    if (editing.value) {
      const entity = JSON.parse(JSON.stringify(editing.value)) as NavCategory;
      entity.spec.displayName = displayName;
      entity.spec.icon = iconValue.value.trim();
      const updated = await updateEntity(CATEGORY_PLURAL, entity);
      // Keep the local row current even if the following refresh fails.
      syncEntityVersion(editing.value, updated);
      editing.value.spec = updated.spec ?? editing.value.spec;
      Toast.success("分类已更新");
    } else {
      const entity: NavCategory = {
        apiVersion: "palette-nav.halo.run/v1alpha1",
        kind: "NavCategory",
        metadata: { name: createName("cat") },
        spec: {
          displayName,
          icon: iconValue.value.trim(),
          sort: nextSort(props.categories),
          visible: true,
        },
      };
      await createEntity(CATEGORY_PLURAL, entity);
      Toast.success("分类已创建");
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

function remove(category: NavCategory) {
  const count = linkCounts.value.get(category.metadata.name) ?? 0;
  Dialog.warning({
    title: "删除分类",
    description: `将删除「${category.spec.displayName}」及其下 ${count} 个链接，此操作不可撤销。`,
    confirmText: "删除",
    cancelText: "取消",
    confirmType: "danger",
    onConfirm: async () => {
      try {
        await deleteCategory(category.metadata.name);
        Toast.success("分类已删除");
        emit("refresh");
      } catch (error) {
        Toast.error(errorMessage(error));
      }
    },
  });
}

const { pendingVisibility, setVisible } = useRowVisibility<NavCategory>(
  CATEGORY_PLURAL
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
        分类顺序决定导航页的分组顺序。
      </span>
      <span class="pn-toolbar-spacer"></span>
      <div class="pn-toolbar-actions">
        <VButton size="sm" type="primary" @click="openCreate">
          <template #icon>
            <IconAddCircle />
          </template>
          新增分类
        </VButton>
        <RefreshButton @refresh="emit('refresh')" />
      </div>
    </div>

    <VEmpty
      v-if="sorted.length === 0"
      title="还没有分类"
      message="先创建一个分类，再向其中添加链接。"
    >
      <template #actions>
        <VButton size="sm" @click="openCreate">新增分类</VButton>
      </template>
    </VEmpty>

    <VEntityContainer v-else>
      <template v-for="(category, index) in sorted" :key="category.metadata.name">
        <DragPlaceholder v-if="dropSlot === index" :height="sourceHeight" />
        <VEntity
          v-bind="rowProps(index)"
          :is-selected="selectedNames.includes(category.metadata.name)"
        >
          <template #checkbox>
            <input
              v-model="selectedNames"
              type="checkbox"
              :value="category.metadata.name"
              :aria-label="`选择「${category.spec.displayName}」`"
            />
          </template>
          <template #start>
            <DragHandle
              v-bind="handleProps(index)"
              :label="`拖动调整「${category.spec.displayName}」的顺序`"
            />
            <button
              type="button"
              class="pn-edit-icon"
              :aria-label="`编辑「${category.spec.displayName}」`"
              :title="`编辑「${category.spec.displayName}」`"
              @click="openEdit(category)"
            >
              <IconPreview :icon="category.spec.icon" />
            </button>
            <VEntityField
              class="pn-edit-target"
              :title="category.spec.displayName"
              :description="`${linkCounts.get(category.metadata.name) ?? 0} 个链接`"
              :max-width="420"
              @click="openEdit(category)"
            />
          </template>
          <template #end>
            <VSwitch
              :model-value="category.spec.visible !== false"
              :disabled="pendingVisibility.has(category.metadata.name)"
              :aria-label="`切换「${category.spec.displayName}」的可见性`"
              @update:model-value="(value) => setVisible(category, Boolean(value))"
            />
          </template>
          <template #dropdownItems>
            <VDropdownItem @click="openEdit(category)">
              <template #prefix-icon>
                <IconRiPencilFill />
              </template>
              编辑
            </VDropdownItem>
            <VDropdownDivider />
            <VDropdownItem type="danger" @click="remove(category)">
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
    :title="editing ? '编辑分类' : '新增分类'"
    :width="560"
    @close="modalVisible = false"
  >
    <FormKit :id="FORM_ID" type="form" :actions="false" @submit="save">
      <FormKit
        name="displayName"
        type="text"
        label="分类名称"
        placeholder="例如：常用导航"
        validation="required"
        validation-label="分类名称"
        :value="editing?.spec.displayName ?? ''"
      />
    </FormKit>

    <div class="pn-form-block">
      <span class="pn-form-label">图标</span>
      <IconPicker v-model="iconValue" />
    </div>

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
