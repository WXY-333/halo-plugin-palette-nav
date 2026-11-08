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
  ENGINE_PLURAL,
  createEntity,
  createName,
  deleteEntity,
  errorMessage,
  listSearchEngines,
  updateEntity,
} from "@/api/nav";
import type { NavSearchEngine } from "@/types/nav";
import { useBulkDelete } from "@/composables/useBulkDelete";
import { useBulkVisibility } from "@/composables/useBulkVisibility";
import { useDragSort } from "@/composables/useDragSort";
import { useRowVisibility } from "@/composables/useRowVisibility";
import { applyDraftOrder, useSortDraft } from "@/composables/useSortDraft";
import { isSafeHttpUrl } from "@/utils/icon";
import { syncEntityVersion } from "@/utils/entity";
import { moveItem, nextSort, sortUpdatesForOrder } from "@/utils/reorder";
import DragHandle from "./DragHandle.vue";
import DragPlaceholder from "./DragPlaceholder.vue";
import IconPicker from "./IconPicker.vue";
import IconPreview from "./IconPreview.vue";
import RefreshButton from "./RefreshButton.vue";
import SelectAllCheckbox from "./SelectAllCheckbox.vue";

const props = defineProps<{
  engines: NavSearchEngine[];
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
} = useBulkVisibility(ENGINE_PLURAL, () => props.engines, () => emit("refresh"));

const { bulkDeleting, deleteSelected } = useBulkDelete(
  ENGINE_PLURAL,
  "搜索引擎",
  () => props.engines,
  selectedNames,
  () => emit("refresh")
);

const FORM_ID = "pn-engine-form";

const {
  draftNames,
  orderDirty,
  orderSaving,
  applyDraft,
  discardDraft,
  persistOrder,
} = useSortDraft(
  "NavSearchEngine",
  () => props.engines,
  () => emit("refresh"),
  listSearchEngines
);

const baseSorted = computed(() =>
  [...props.engines].sort(
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
  sorted.value.map((engine) => engine.metadata.name)
);

const modalVisible = ref(false);
const editing = ref<NavSearchEngine | null>(null);
const iconValue = ref("");
const saving = ref(false);
const formError = ref("");

const instance = getCurrentInstance();

function submitForm() {
  const formkit = (
    instance?.proxy as { $formkit?: { submit: (name: string) => void } } | null
  )?.$formkit;
  formkit?.submit(FORM_ID);
}

function openCreate() {
  editing.value = null;
  iconValue.value = "";
  formError.value = "";
  modalVisible.value = true;
}

function openEdit(engine: NavSearchEngine) {
  editing.value = engine;
  iconValue.value = engine.spec.icon ?? "";
  formError.value = "";
  modalVisible.value = true;
}

async function save(payload: {
  alias?: string;
  displayName?: string;
  hint?: string;
  urlTemplate?: string;
  mobileUrlTemplate?: string;
}) {
  if (saving.value) {
    return;
  }
  const alias = (payload.alias ?? "").trim();
  const displayName = (payload.displayName ?? "").trim();
  const hint = (payload.hint ?? "").trim();
  const urlTemplate = (payload.urlTemplate ?? "").trim();
  const mobileUrlTemplate = (payload.mobileUrlTemplate ?? "").trim();
  if (!alias) {
    formError.value = "请输入别名";
    return;
  }
  if (!displayName) {
    formError.value = "请输入名称";
    return;
  }
  if (!isSafeHttpUrl(urlTemplate)) {
    formError.value = "搜索地址必须是 http(s) 开头的完整地址";
    return;
  }
  if (mobileUrlTemplate && !isSafeHttpUrl(mobileUrlTemplate)) {
    formError.value = "移动端搜索地址必须是 http(s) 开头的完整地址";
    return;
  }
  formError.value = "";
  saving.value = true;
  try {
    if (editing.value) {
      const entity = JSON.parse(JSON.stringify(editing.value)) as NavSearchEngine;
      entity.spec.alias = alias;
      entity.spec.displayName = displayName;
      entity.spec.hint = hint;
      entity.spec.urlTemplate = urlTemplate;
      entity.spec.mobileUrlTemplate = mobileUrlTemplate || undefined;
      entity.spec.icon = iconValue.value.trim();
      const updated = await updateEntity(ENGINE_PLURAL, entity);
      syncEntityVersion(editing.value, updated);
      editing.value.spec = updated.spec ?? editing.value.spec;
      Toast.success("搜索引擎已更新");
    } else {
      const entity: NavSearchEngine = {
        apiVersion: "palette-nav.halo.run/v1alpha1",
        kind: "NavSearchEngine",
        metadata: { name: createName("se") },
        spec: {
          alias,
          displayName,
          hint,
          urlTemplate,
          mobileUrlTemplate: mobileUrlTemplate || undefined,
          icon: iconValue.value.trim(),
          sort: nextSort(props.engines),
          visible: true,
        },
      };
      await createEntity(ENGINE_PLURAL, entity);
      Toast.success("搜索引擎已创建");
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

function remove(engine: NavSearchEngine) {
  Dialog.warning({
    title: "删除搜索引擎",
    description: `确定删除「${engine.spec.displayName}」吗？`,
    confirmText: "删除",
    cancelText: "取消",
    confirmType: "danger",
    onConfirm: async () => {
      try {
        await deleteEntity(ENGINE_PLURAL, engine.metadata.name);
        Toast.success("搜索引擎已删除");
        emit("refresh");
      } catch (error) {
        Toast.error(errorMessage(error));
      }
    },
  });
}

const { pendingVisibility, setVisible } = useRowVisibility<NavSearchEngine>(
  ENGINE_PLURAL
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
        搜索时会把关键词拼接到所选地址后面。
      </span>
      <span class="pn-toolbar-spacer"></span>
      <div class="pn-toolbar-actions">
        <VButton size="sm" type="primary" @click="openCreate">
          <template #icon>
            <IconAddCircle />
          </template>
          新增搜索引擎
        </VButton>
        <RefreshButton @refresh="emit('refresh')" />
      </div>
    </div>

    <VEmpty
      v-if="sorted.length === 0"
      title="还没有搜索引擎"
      message="添加后导航页会显示搜索框与引擎切换器。"
    >
      <template #actions>
        <VButton size="sm" @click="openCreate">新增搜索引擎</VButton>
      </template>
    </VEmpty>

    <VEntityContainer v-else>
      <template v-for="(engine, index) in sorted" :key="engine.metadata.name">
        <DragPlaceholder v-if="dropSlot === index" :height="sourceHeight" />
        <VEntity
          v-bind="rowProps(index)"
          :is-selected="selectedNames.includes(engine.metadata.name)"
        >
          <template #checkbox>
            <input
              v-model="selectedNames"
              type="checkbox"
              :value="engine.metadata.name"
              :aria-label="`选择「${engine.spec.displayName}」`"
            />
          </template>
          <template #start>
            <DragHandle
              v-bind="handleProps(index)"
              :label="`拖动调整「${engine.spec.displayName}」的顺序`"
            />
            <button
              type="button"
              class="pn-edit-icon"
              :aria-label="`编辑「${engine.spec.displayName}」`"
              :title="`编辑「${engine.spec.displayName}」`"
              @click="openEdit(engine)"
            >
              <IconPreview :icon="engine.spec.icon" />
            </button>
            <VEntityField
              class="pn-edit-target"
              :title="engine.spec.displayName"
              :description="`${engine.spec.alias} · ${engine.spec.urlTemplate}`"
              :max-width="420"
              @click="openEdit(engine)"
            />
          </template>
          <template #end>
            <VSwitch
              :model-value="engine.spec.visible !== false"
              :disabled="pendingVisibility.has(engine.metadata.name)"
              :aria-label="`切换「${engine.spec.displayName}」的可见性`"
              @update:model-value="(value) => setVisible(engine, Boolean(value))"
            />
          </template>
          <template #dropdownItems>
            <VDropdownItem @click="openEdit(engine)">
              <template #prefix-icon>
                <IconRiPencilFill />
              </template>
              编辑
            </VDropdownItem>
            <VDropdownDivider />
            <VDropdownItem type="danger" @click="remove(engine)">
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
    :title="editing ? '编辑搜索引擎' : '新增搜索引擎'"
    :width="640"
    @close="modalVisible = false"
  >
    <FormKit :id="FORM_ID" type="form" :actions="false" @submit="save">
      <FormKit
        name="alias"
        type="text"
        label="别名"
        placeholder="baidu"
        validation="required"
        validation-label="别名"
        :value="editing?.spec.alias ?? ''"
      />
      <FormKit
        name="displayName"
        type="text"
        label="名称"
        placeholder="百度一下"
        validation="required"
        validation-label="名称"
        :value="editing?.spec.displayName ?? ''"
      />
      <FormKit
        name="hint"
        type="text"
        label="提示文字"
        placeholder="百度一下，你就知道"
        :value="editing?.spec.hint ?? ''"
      />
      <FormKit
        name="urlTemplate"
        type="text"
        label="搜索地址"
        placeholder="https://www.baidu.com/s?word="
        validation="required"
        validation-label="搜索地址"
        help="关键词会直接拼接到该地址末尾，请确认地址以查询参数结尾。"
        :value="editing?.spec.urlTemplate ?? ''"
      />
      <FormKit
        name="mobileUrlTemplate"
        type="text"
        label="移动端搜索地址（可选）"
        placeholder="https://m.baidu.com/s?word="
        :value="editing?.spec.mobileUrlTemplate ?? ''"
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
