import type { Ref } from "vue";
import { ref } from "vue";
import { errorMessage, updateEntity } from "@/api/nav";
import { syncEntityVersion } from "@/utils/entity";
import { nextSort } from "@/utils/reorder";

interface MovableItem {
  metadata: { name: string; version?: number | null };
  spec: { categoryName?: string; sort?: number };
}

export interface BulkMoveResult {
  moved: number;
  /** Selected links that were already in the target category. */
  skipped: number;
  failed: number;
  firstFailure: string;
}

/**
 * Shared multi-select move for the link list: rewrites `spec.categoryName` on
 * every selected link and appends it to the end of the target category.
 *
 * Links already in the target category are skipped instead of being updated,
 * otherwise they would be renumbered for no reason. New `sort` values are
 * planned up front (the target category's max + 10 per link) so the
 * concurrently executed chunk requests keep the selection order.
 */
export function useBulkMove<T extends MovableItem>(
  plural: string,
  getItems: () => T[],
  selectedNames: Ref<string[]>,
  refresh: () => void
) {
  const bulkMoving = ref(false);

  async function moveSelected(categoryName: string): Promise<BulkMoveResult> {
    const result: BulkMoveResult = {
      moved: 0,
      skipped: 0,
      failed: 0,
      firstFailure: "",
    };
    if (bulkMoving.value || !categoryName || selectedNames.value.length === 0) {
      return result;
    }

    const targets = getItems().filter((item) =>
      selectedNames.value.includes(item.metadata.name)
    );
    if (targets.length === 0) {
      return result;
    }

    const toMove = targets.filter(
      (item) => item.spec.categoryName !== categoryName
    );
    result.skipped = targets.length - toMove.length;
    if (toMove.length === 0) {
      selectedNames.value = [];
      return result;
    }

    let sort = nextSort(
      getItems().filter((item) => item.spec.categoryName === categoryName)
    );
    const plan = toMove.map((item) => {
      const itemSort = sort;
      sort += 10;
      return { item, sort: itemSort };
    });

    bulkMoving.value = true;
    try {
      // Keep the request volume modest when a whole page is selected.
      const chunkSize = 8;
      for (let i = 0; i < plan.length; i += chunkSize) {
        const chunk = plan.slice(i, i + chunkSize);
        await Promise.all(
          chunk.map(async (entry) => {
            try {
              const entity = JSON.parse(JSON.stringify(entry.item)) as T;
              entity.spec.categoryName = categoryName;
              entity.spec.sort = entry.sort;
              const updated = await updateEntity(plural, entity);
              syncEntityVersion(entry.item, updated);
              entry.item.spec.categoryName = categoryName;
              entry.item.spec.sort = entry.sort;
              result.moved += 1;
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
      bulkMoving.value = false;
    }

    selectedNames.value = [];
    refresh();
    return result;
  }

  return { bulkMoving, moveSelected };
}