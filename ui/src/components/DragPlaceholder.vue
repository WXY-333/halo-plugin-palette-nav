<script lang="ts" setup>
defineProps<{
  height: number;
}>();
</script>

<template>
  <tr class="pn-drop-slot">
    <td colspan="2">
      <div class="pn-drop-placeholder" :style="{ height: `${height}px` }"></div>
    </td>
  </tr>
</template>
