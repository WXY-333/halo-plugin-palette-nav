import { onUnmounted, ref } from "vue";

export interface UseDragSortOptions {
  /** `to` is the index the item should occupy once it has been removed from `from`. */
  onDrop: (from: number, to: number) => void | Promise<void>;
  canDrag?: (index: number) => boolean;
  canDrop?: (from: number, to: number) => boolean;
}

const DRAG_THRESHOLD = 4;

/**
 * Pointer-driven row sorting for `VEntity` tables.
 *
 * Mirrors the console's own menu editor (he-tree): the dragged row leaves the
 * flow while a copy of it follows the cursor, and a dashed placeholder marks
 * where it will land. Pointer events are used instead of the HTML5
 * drag-and-drop API because the native API does not fire on touch devices, so
 * the floating copy has to be built by hand (`createGhost`).
 */
export function useDragSort(options: UseDragSortOptions) {
  /** Index of the row being dragged. */
  const draggingIndex = ref<number | null>(null);
  /** Insertion index (0..n) the placeholder is rendered at. */
  const dropSlot = ref<number | null>(null);
  /** Row the drag cannot be dropped on, e.g. a different category. */
  const blockedIndex = ref<number | null>(null);
  /** Height of the dragged row, used to size the placeholder. */
  const sourceHeight = ref(48);

  let pressed: {
    index: number;
    x: number;
    y: number;
    height: number;
    row: HTMLTableRowElement;
  } | null = null;
  let active = false;
  /** Floating copy of the dragged row that follows the pointer. */
  let ghost: HTMLTableElement | null = null;

  function handleProps(index: number) {
    const disabled = options.canDrag ? !options.canDrag(index) : false;
    return {
      class: { "pn-drag-disabled": disabled },
      onPointerdown: (event: PointerEvent) => startDrag(index, event),
    };
  }

  function rowProps(index: number) {
    return {
      "data-pn-index": index,
      class: {
        "pn-drag-source": draggingIndex.value === index,
        "pn-drop-blocked": blockedIndex.value === index,
      },
    };
  }

  function startDrag(index: number, event: PointerEvent) {
    if (event.pointerType === "mouse" && event.button !== 0) {
      return;
    }
    if (options.canDrag && !options.canDrag(index)) {
      return;
    }
    const handle = event.currentTarget as HTMLElement | null;
    const row = handle?.closest<HTMLTableRowElement>("tr");
    if (!row) {
      return;
    }
    event.preventDefault();
    pressed = {
      index,
      x: event.clientX,
      y: event.clientY,
      height: Math.round(row.getBoundingClientRect().height),
      row,
    };
    // Listeners live on the document: the handle leaves the flow together with
    // its row as soon as the drag starts, so capture on it would not survive.
    document.addEventListener("pointermove", onPointerMove);
    document.addEventListener("pointerup", onPointerUp);
    document.addEventListener("pointercancel", onPointerCancel);
  }

  function activate() {
    if (!pressed) {
      return;
    }
    active = true;
    sourceHeight.value = pressed.height;
    // Built before the source row is hidden, otherwise the clone would carry
    // `pn-drag-source` and start out invisible.
    createGhost(pressed.row);
    document.body.classList.add("pn-dragging");
    draggingIndex.value = pressed.index;
    dropSlot.value = pressed.index;
  }

  /**
   * The row itself leaves the flow, so a detached copy is what the user keeps
   * seeing while sorting. It is wrapped in a single-row table because a `<tr>`
   * only renders correctly inside one.
   */
  function createGhost(row: HTMLTableRowElement) {
    destroyGhost();
    const rect = row.getBoundingClientRect();
    const table = document.createElement("table");
    table.className = "pn-drag-ghost";
    table.style.width = `${rect.width}px`;
    table.style.left = `${rect.left}px`;
    table.style.top = `${rect.top}px`;
    const tbody = document.createElement("tbody");
    const clone = row.cloneNode(true) as HTMLTableRowElement;
    clone.removeAttribute("data-pn-index");
    const sourceCells = Array.from(row.cells);
    Array.from(clone.cells).forEach((cell, index) => {
      const width = sourceCells[index]?.getBoundingClientRect().width;
      if (width) {
        cell.style.width = `${width}px`;
      }
    });
    // The handle is an absolute overlay inside the row and the controls are
    // inert in a ghost; neither belongs in the copy.
    clone.querySelectorAll(".pn-drag-handle").forEach((element) => element.remove());
    clone.querySelectorAll("input, button, a").forEach((element) => {
      element.setAttribute("tabindex", "-1");
    });
    tbody.appendChild(clone);
    table.appendChild(tbody);
    document.body.appendChild(table);
    ghost = table;
  }

  function moveGhost(event: PointerEvent) {
    if (!ghost || !pressed) {
      return;
    }
    const dx = event.clientX - pressed.x;
    const dy = event.clientY - pressed.y;
    ghost.style.transform = `translate3d(${dx}px, ${dy}px, 0)`;
  }

  function destroyGhost() {
    ghost?.remove();
    ghost = null;
    document.body.classList.remove("pn-dragging");
  }

  function onPointerMove(event: PointerEvent) {
    if (!pressed) {
      return;
    }
    if (!active) {
      // Ignore the jitter of a plain click on the handle.
      if (Math.hypot(event.clientX - pressed.x, event.clientY - pressed.y) < DRAG_THRESHOLD) {
        return;
      }
      activate();
    }
    moveGhost(event);
    const from = draggingIndex.value;
    if (from === null) {
      return;
    }
    const hit = document.elementFromPoint(event.clientX, event.clientY) as
      | HTMLElement
      | null;
    // The placeholder itself has no index; keeping the current slot avoids the
    // placeholder flickering away the moment it lands under the cursor.
    if (hit?.closest(".pn-drop-slot")) {
      return;
    }
    const row = hit?.closest<HTMLElement>("tr[data-pn-index]");
    if (!row) {
      return;
    }
    const index = Number(row.dataset.pnIndex);
    if (!Number.isInteger(index)) {
      return;
    }
    const rect = row.getBoundingClientRect();
    const edge = event.clientY - rect.top < rect.height / 2 ? "before" : "after";
    const slot = index + (edge === "after" ? 1 : 0);
    const to = slot > from ? slot - 1 : slot;
    if (to !== from && options.canDrop && !options.canDrop(from, to)) {
      blockedIndex.value = index;
      dropSlot.value = from;
      return;
    }
    blockedIndex.value = null;
    dropSlot.value = slot;
  }

  function onPointerUp() {
    const from = draggingIndex.value;
    const slot = dropSlot.value;
    const dropped = active;
    cleanup();
    if (!dropped || from === null || slot === null) {
      return;
    }
    const to = slot > from ? slot - 1 : slot;
    if (to === from || (options.canDrop && !options.canDrop(from, to))) {
      return;
    }
    Promise.resolve(options.onDrop(from, to)).catch(() => undefined);
  }

  function onPointerCancel() {
    cleanup();
  }

  function cleanup() {
    pressed = null;
    active = false;
    destroyGhost();
    draggingIndex.value = null;
    dropSlot.value = null;
    blockedIndex.value = null;
    document.removeEventListener("pointermove", onPointerMove);
    document.removeEventListener("pointerup", onPointerUp);
    document.removeEventListener("pointercancel", onPointerCancel);
  }

  // A drag in progress when the view unmounts would otherwise leave the
  // document listeners, the floating row and `body.pn-dragging` behind until
  // the next pointerup anywhere in the console.
  onUnmounted(cleanup);

  return { draggingIndex, dropSlot, sourceHeight, handleProps, rowProps };
}
