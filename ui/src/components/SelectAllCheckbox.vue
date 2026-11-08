<script lang="ts" setup>
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    names: string[];
    modelValue: string[];
  }>(),
  {
    names: () => [],
    modelValue: () => [],
  }
);

const emit = defineEmits<{
  "update:modelValue": [value: string[]];
}>();

const allSelected = computed(
  () =>
    props.names.length > 0 &&
    props.names.every((name) => props.modelValue.includes(name))
);

const partiallySelected = computed(
  () =>
    !allSelected.value &&
    props.names.some((name) => props.modelValue.includes(name))
);

function onChange(event: Event) {
  const checked = (event.target as HTMLInputElement).checked;
  if (checked) {
    emit("update:modelValue", [...new Set([...props.modelValue, ...props.names])]);
    return;
  }
  const removing = new Set(props.names);
  emit(
    "update:modelValue",
    props.modelValue.filter((name) => !removing.has(name))
  );
}
</script>

<template>
  <label class="pn-select-all">
    <input
      type="checkbox"
      :checked="allSelected"
      :indeterminate.prop="partiallySelected"
      :disabled="names.length === 0"
      aria-label="全选"
      @change="onChange"
    />
    <span>全选</span>
  </label>
</template>
