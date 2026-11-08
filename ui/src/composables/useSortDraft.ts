import { computed, ref } from "vue";
import { Toast } from "@halo-dev/components";
import { errorMessage, reorder } from "@/api/nav";
import type { SortUpdate } from "@/utils/reorder";

interface SortDraftItem {
  metadata: { name: string; version?: number | null };
  spec: { sort?: number };
}

/**
 * Holds an unsaved drag order until the user clicks 保存排序.
 *
 * The list renders in the draft order (`applyDraftOrder`, or the caller's own
 * rank comparator), and nothing reaches the server until `persistOrder`. After
 * a successful save the new `sort` values are written back to the in-memory
 * items before the refresh, so the list does not flash the old order while the
 * request is in flight.
 */
export function useSortDraft<T extends SortDraftItem>(
  kind: string,
  getItems: () => T[],
  refresh: () => void,
  /**
   * Refetches the list after a successful reorder. `/reorder` updates every
   * entity on the server and therefore advances `metadata.version`; without
   * syncing those versions back the next edit of a reordered row is rejected
   * with 409. Passing the list API keeps this composable entity-agnostic.
   */
  fetchLatest?: () => Promise<T[]>
) {
  const draftNames = ref<string[] | null>(null);
  const orderSaving = ref(false);
  const orderDirty = computed(() => draftNames.value !== null);

  /**
   * Stores the dropped order, or clears the draft when the list is back at its
   * saved order, so the save bar does not linger after a drag-and-undo.
   */
  function applyDraft(names: string[], baseline: string[]): void {
    const unchanged =
      names.length === baseline.length &&
      names.every((name, index) => name === baseline[index]);
    draftNames.value = unchanged ? null : names;
  }

  function discardDraft(): void {
    if (orderSaving.value) {
      return;
    }
    draftNames.value = null;
  }

  async function persistOrder(updates: SortUpdate[]): Promise<void> {
    if (!orderDirty.value || orderSaving.value) {
      return;
    }
    if (updates.length === 0) {
      // Dragging a row and then discarding the change can leave a draft with
      // nothing to renumber. Clear it, otherwise the save-order bar and the
      // leave-page guard stay active with no way to finish.
      draftNames.value = null;
      Toast.success("排序没有变化");
      return;
    }
    orderSaving.value = true;
    try {
      await reorder(kind, updates);
      const byName = new Map(
        getItems().map((item) => [item.metadata.name, item])
      );
      for (const update of updates) {
        const item = byName.get(update.name);
        if (item) {
          item.spec.sort = update.sort;
        }
      }
      let versionsSynced = true;
      if (fetchLatest) {
        try {
          const latest = await fetchLatest();
          const latestByName = new Map(
            latest.map((item) => [item.metadata.name, item])
          );
          for (const item of getItems()) {
            const version = latestByName.get(item.metadata.name)?.metadata.version;
            if (typeof version === "number") {
              item.metadata.version = version;
            }
          }
        } catch {
          versionsSynced = false;
        }
      }
      draftNames.value = null;
      if (versionsSynced) {
        Toast.success("排序已保存");
      } else {
        Toast.warning("排序已保存，但刷新失败，请手动刷新后再编辑");
      }
      refresh();
    } catch (error) {
      Toast.error(errorMessage(error));
    } finally {
      orderSaving.value = false;
    }
  }

  return {
    draftNames,
    orderDirty,
    orderSaving,
    applyDraft,
    discardDraft,
    persistOrder,
  };
}

/** Applies a draft order to the items, ignoring names that no longer exist. */
export function applyDraftOrder<T extends { metadata: { name: string } }>(
  items: T[],
  draftNames: string[] | null
): T[] {
  if (!draftNames) {
    return items;
  }
  const byName = new Map(items.map((item) => [item.metadata.name, item]));
  const ordered: T[] = [];
  for (const name of draftNames) {
    const item = byName.get(name);
    if (item) {
      ordered.push(item);
    }
  }
  // Items created after the drag was made keep their default position.
  const drafted = new Set(draftNames);
  for (const item of items) {
    if (!drafted.has(item.metadata.name)) {
      ordered.push(item);
    }
  }
  return ordered;
}
