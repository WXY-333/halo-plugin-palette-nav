<script lang="ts" setup>
import { getCurrentInstance, ref, resolveComponent, watch } from "vue";
import { Toast, VButton, VDropdown, VDropdownItem } from "@halo-dev/components";
import { isSafeImageUrl } from "@/utils/icon";

/**
 * Nested "输入链接" dropdown shared by IconPicker and MediaPicker.
 *
 * The optional select is rendered inside the same FormKit form on purpose: slot
 * content keeps its owner's injection context, so a FormKit `select` passed in
 * through a slot would not see the surrounding form's node.
 */
const props = withDefaults(
  defineProps<{
    label?: string;
    /** Initial value shown when the popper opens. */
    value?: string;
    itemText?: string;
    selectLabel?: string;
    selectValue?: string;
    selectOptions?: Array<{ label: string; value: string }>;
  }>(),
  {
    label: "",
    value: "",
    itemText: "输入链接",
    selectLabel: "",
    selectValue: "",
    selectOptions: () => [],
  }
);

const emit = defineEmits<{
  apply: [value: string, selectValue: string];
  "update:url": [value: string];
}>();

const FormKit = resolveComponent("FormKit");
const instance = getCurrentInstance();
const formId = `pn-link-form-${instance?.uid ?? 0}`;
const inputId = `pn-link-input-${instance?.uid ?? 0}`;

const dropdownRef = ref<{ hide: () => void } | null>(null);
const urlValue = ref("");
const selectModel = ref(props.selectValue);

watch(urlValue, (value) => emit("update:url", value));
watch(
  () => props.selectValue,
  (value) => {
    selectModel.value = value;
  }
);

function onShow() {
  urlValue.value = props.value ?? "";
  selectModel.value = props.selectValue;
  window.setTimeout(() => {
    document.getElementById(inputId)?.focus();
  }, 100);
}

function apply(raw: string) {
  const value = (raw ?? "").trim();
  if (!value) {
    return;
  }
  if (!isSafeImageUrl(value)) {
    Toast.warning("请输入 http(s) 地址或以 / 开头的站内路径");
    return;
  }
  emit(
    "apply",
    value,
    props.selectOptions.length > 0 ? selectModel.value : ""
  );
  dropdownRef.value?.hide();
}

function onSubmit(payload: { value?: string }) {
  apply(payload?.value ?? urlValue.value);
}

function submitForm() {
  const formkit = (
    instance?.proxy as { $formkit?: { submit: (name: string) => void } } | null
  )?.$formkit;
  if (formkit?.submit) {
    formkit.submit(formId);
    return;
  }
  apply(urlValue.value);
}
</script>

<template>
  <VDropdown ref="dropdownRef" :triggers="['click']" @show="onShow">
    <VDropdownItem>{{ itemText }}</VDropdownItem>
    <template #popper>
      <div class="pn-link-popover">
        <component
          :is="FormKit"
          :id="formId"
          type="form"
          :ignore="true"
          :name="formId"
          @submit="onSubmit"
        >
          <component
            :is="FormKit"
            :id="inputId"
            type="text"
            name="value"
            v-model="urlValue"
            :label="label || undefined"
            validation="required"
            :validation-label="label || '链接'"
          />
          <component
            v-if="selectOptions.length > 0"
            :is="FormKit"
            type="select"
            name="kind"
            :label="selectLabel"
            v-model="selectModel"
            :options="selectOptions"
          />
        </component>
        <div class="pn-link-actions">
          <VButton type="secondary" @click="submitForm">保存</VButton>
        </div>
      </div>
    </template>
  </VDropdown>
</template>