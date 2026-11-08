<script lang="ts" setup>
import { computed, ref, watch } from "vue";
import { DEFAULT_ICON_SVG, ensureIconSprite, isSpriteIcon, sanitizeIconHtml } from "@/utils/icon";

const props = withDefaults(
  defineProps<{
    icon?: string;
    size?: number;
  }>(),
  {
    icon: "",
    size: 32,
  }
);

const spriteReady = ref(false);

const html = computed(() => {
  void spriteReady.value;
  return sanitizeIconHtml(props.icon) || DEFAULT_ICON_SVG;
});

watch(
  () => props.icon,
  async (icon) => {
    if (!isSpriteIcon(icon)) {
      return;
    }
    await ensureIconSprite();
    spriteReady.value = true;
  },
  { immediate: true }
);
</script>

<template>
  <span
    class="pn-icon-tile"
    :style="{ width: `${size}px`, height: `${size}px` }"
    v-html="html"
  ></span>
</template>
