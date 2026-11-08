<script lang="ts" setup>
import { IconRefreshLine } from "@halo-dev/components";

const props = withDefaults(
  defineProps<{
    label?: string;
  }>(),
  {
    label: "刷新",
  }
);

const emit = defineEmits<{
  refresh: [];
}>();
</script>

<template>
  <button
    type="button"
    class="pn-refresh"
    :aria-label="props.label"
    v-tooltip="props.label"
    @click="emit('refresh')"
  >
    <IconRefreshLine class="pn-refresh-icon" />
  </button>
</template>
