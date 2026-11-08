<script lang="ts" setup>
import { IconList } from "@halo-dev/components";

withDefaults(
  defineProps<{
    label?: string;
  }>(),
  {
    label: "拖动排序",
  }
);
</script>

<template>
  <span class="pn-drag-handle" role="img" :aria-label="label" :title="label">
    <IconList class="pn-drag-handle-icon" />
  </span>
</template>
