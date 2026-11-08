import type { Ref } from "vue";
import { ref } from "vue";
import { createEntity, errorMessage } from "@/api/nav";
import { nextSort } from "@/utils/reorder";

interface CopyableItem {
  metadata: { name: string };
  spec: { categoryName?: string; sort?: number };
}

export interface BulkCopyResult {
  copied: number;
  failed: number;
  firstFailure: string;
}

/**
 * Shared multi-select copy for the link list: creates a new entity for every
 * selected link in the target category, appended after its last `sort`.
 *
 * Duplicates are intentional: a category may hold several links with the same
 * URL, and copying is also allowed into the source category itself. The entity
 * is built by the caller because a copy needs a fresh `metadata.name` and must
 * drop the server-assigned metadata of the source row.
 */
export function useBulkCopy<T extends CopyableItem>(
  plural: string,
  getItems: () => T[],
  selectedNames: Ref<string[]>,
  refresh: () => void,
  buildCopy: (item: T, categoryName: string, sort: number) => T
) {
  const bulkCopying = ref(false);

  async function copySelected(categoryName: string): Promise<BulkCopyResult> {
    const result: BulkCopyResult = {
      copied: 0,
      failed: 0,
      firstFailure: "",
    };
    if (bulkCopying.value || !categoryName || selectedNames.value.length === 0) {
      return result;
    }

    const targets = getItems().filter((item) =>
      selectedNames.value.includes(item.metadata.name)
    );
    if (targets.length === 0) {
      return result;
    }

    let sort = nextSort(
      getItems().filter((item) => item.spec.categoryName === categoryName)
    );
    const plan: Array<{ item: T; sort: number }> = targets.map((item) => {
      const entry = { item, sort };
      sort += 10;
      return entry;
    });

    bulkCopying.value = true;
    try {
      // Keep the request volume modest when a whole page is selected.
      const chunkSize = 8;
      for (let i = 0; i < plan.length; i += chunkSize) {
        const chunk = plan.slice(i, i + chunkSize);
        await Promise.all(
          chunk.map(async (entry) => {
            try {
              await createEntity(
                plural,
                buildCopy(entry.item, categoryName, entry.sort)
              );
              result.copied += 1;
            } catch (error) {
              result.failed += 1;
              if (!result.firstFailure) {
                result.firstFailure = errorMessage(error);
              }
            }
          })
        );
      }
    } finally {
      bulkCopying.value = false;
    }

    selectedNames.value = [];
    refresh();
    return result;
  }

  return { bulkCopying, copySelected };
}