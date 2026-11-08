export interface SortableItem {
  metadata: { name: string };
  spec: { sort?: number };
}

export interface SortUpdate {
  name: string;
  sort: number;
}

/**
 * Next sort value after the largest one in `list` (empty list → 10).
 *
 * Counting items (`length * 10`) looks equivalent but collides once the list has
 * gaps: with sort 10/30 and one deleted entry, a new item would also get 30 and
 * the order would fall back to the name tie-break.
 */
export function nextSort<T extends SortableItem>(list: T[]): number {
  return list.reduce((max, item) => Math.max(max, item.spec.sort ?? 0), 0) + 10;
}

/**
 * Moves the item at `from` to `to`, where `to` is the index the item should
 * occupy after it has been removed from its original position.
 */
export function moveItem<T>(list: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || from >= list.length || to < 0) {
    return [...list];
  }
  const next = [...list];
  const [item] = next.splice(from, 1);
  if (item === undefined) {
    return next;
  }
  next.splice(Math.min(to, next.length), 0, item);
  return next;
}

/**
 * Renumbers the whole list in one pass, stepping by 10. The console endpoint
 * accepts one request with every item, so a full renumber is both cheap and
 * immune to duplicate `sort` values (batch-created links all start at 0).
 */
export function sortUpdatesForOrder<T extends SortableItem>(
  list: T[]
): SortUpdate[] {
  return list.map((entry, index) => ({
    name: entry.metadata.name,
    sort: (index + 1) * 10,
  }));
}

