import { computed, ref } from "vue";
import { Toast } from "@halo-dev/components";
import { updateEntity } from "@/api/nav";
import { syncEntityVersion } from "@/utils/entity";

interface BulkVisibilityItem {
  metadata: { name: string; version?: number | null };
  spec: { visible?: boolean };
}

/**
 * Shared multi-select state and bulk enable/disable action for the entity lists.
 *
 * Items are updated one by one because Halo exposes per-resource updates only.
 * Each successful response carries a new `metadata.version`, which is synced back
 * so a later single toggle on the same row does not hit a 409 conflict.
 */
export function useBulkVisibility<T extends BulkVisibilityItem>(
  plural: string,
  getItems: () => T[],
  refresh: () => void
) {
  const selectedNames = ref<string[]>([]);
  const bulkSaving = ref(false);

  const selectedCount = computed(() => selectedNames.value.length);

  function clearSelection(): void {
    selectedNames.value = [];
  }

  async function setSelectedVisible(visible: boolean): Promise<void> {
    if (bulkSaving.value || selectedNames.value.length === 0) {
      return;
    }
    const targets = getItems().filter((item) =>
      selectedNames.value.includes(item.metadata.name)
    );
    if (targets.length === 0) {
      return;
    }

    bulkSaving.value = true;
    let succeeded = 0;
    let failed = 0;
    try {
      // Keep the request volume modest when a whole page is selected.
      const chunkSize = 8;
      for (let i = 0; i < targets.length; i += chunkSize) {
        const chunk = targets.slice(i, i + chunkSize);
        await Promise.all(
          chunk.map(async (item) => {
            try {
              const entity = JSON.parse(JSON.stringify(item)) as T;
              entity.spec.visible = visible;
              const updated = await updateEntity(plural, entity);
              syncEntityVersion(item, updated);
              item.spec.visible = visible;
              succeeded += 1;
            } catch {
              failed += 1;
            }
          })
        );
      }
    } finally {
      bulkSaving.value = false;
    }

    const action = visible ? "启用" : "禁用";
    if (failed === 0) {
      Toast.success(`已${action} ${succeeded} 项`);
    } else {
      Toast.warning(
        `${action}完成：成功 ${succeeded} 项，失败 ${failed} 项，请刷新后重试`
      );
    }
    clearSelection();
    refresh();
  }

  return {
    selectedNames,
    bulkSaving,
    selectedCount,
    clearSelection,
    setSelectedVisible,
  };
}
