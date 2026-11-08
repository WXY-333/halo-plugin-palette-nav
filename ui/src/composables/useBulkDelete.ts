import type { Ref } from "vue";
import { ref } from "vue";
import { Dialog, Toast } from "@halo-dev/components";
import { deleteEntity } from "@/api/nav";

interface BulkDeleteItem {
  metadata: { name: string };
}

interface BulkDeleteOptions<T> {
  /** 单条删除请求；默认走通用 CRUD 接口，分类需要传级联删除。 */
  remove?: (name: string) => Promise<void>;
  /** 追加在确认文案前的提醒，例如分类会连带删除链接。 */
  warning?: (targets: T[]) => string;
}

/**
 * Shared bulk delete for the entity lists.
 *
 * Mirrors Halo's own batch delete (official post list): a `Dialog.warning` with
 * `confirmType: "danger"`, then the deletions run in small concurrent chunks,
 * followed by one refresh and a success toast. Halo exposes per-resource
 * deletes only, so there is no batch endpoint to call.
 */
export function useBulkDelete<T extends BulkDeleteItem>(
  plural: string,
  entityLabel: string,
  getItems: () => T[],
  selectedNames: Ref<string[]>,
  refresh: () => void,
  options: BulkDeleteOptions<T> = {}
) {
  const bulkDeleting = ref(false);

  function deleteSelected(): void {
    if (bulkDeleting.value) {
      return;
    }
    const targets = getItems().filter((item) =>
      selectedNames.value.includes(item.metadata.name)
    );
    if (targets.length === 0) {
      return;
    }

    const remove =
      options.remove ?? ((name: string) => deleteEntity(plural, name));
    const warning = options.warning?.(targets) ?? "";

    Dialog.warning({
      title: `批量删除${entityLabel}`,
      description: `${warning}确定删除选中的 ${targets.length} 个${entityLabel}吗？此操作不可撤销。`,
      confirmType: "danger",
      confirmText: "删除",
      cancelText: "取消",
      onConfirm: async () => {
        bulkDeleting.value = true;
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
                  await remove(item.metadata.name);
                  succeeded += 1;
                } catch {
                  failed += 1;
                }
              })
            );
          }
        } finally {
          bulkDeleting.value = false;
        }

        if (failed === 0) {
          Toast.success(`已删除 ${succeeded} 个${entityLabel}`);
        } else {
          Toast.warning(
            `删除完成：成功 ${succeeded} 项，失败 ${failed} 项，请刷新后重试`
          );
        }
        selectedNames.value = [];
        refresh();
      },
    });
  }

  return { bulkDeleting, deleteSelected };
}
