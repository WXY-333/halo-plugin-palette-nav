import { ref } from "vue";
import { Toast } from "@halo-dev/components";
import { errorMessage, updateEntity } from "@/api/nav";
import { syncEntityVersion } from "@/utils/entity";

interface VisibilityItem {
  metadata: { name: string; version?: number | null };
  spec: { visible?: boolean };
}

/**
 * Per-row visibility toggle shared by the four entity tabs.
 *
 * The switch flips optimistically and is rolled back on failure. A successful
 * update returns a new `metadata.version`, which is written back to the local
 * item so the next save of that row does not hit a 409 conflict.
 */
export function useRowVisibility<T extends VisibilityItem>(plural: string) {
  const pendingVisibility = ref(new Set<string>());

  async function setVisible(item: T, value: boolean): Promise<void> {
    const name = item.metadata.name;
    if (pendingVisibility.value.has(name)) {
      return;
    }
    pendingVisibility.value.add(name);
    const previous = item.spec.visible !== false;
    item.spec.visible = value;
    try {
      const entity = JSON.parse(JSON.stringify(item)) as T;
      const updated = await updateEntity(plural, entity);
      syncEntityVersion(item, updated);
    } catch (error) {
      item.spec.visible = previous;
      Toast.error(errorMessage(error));
    } finally {
      pendingVisibility.value.delete(name);
    }
  }

  return { pendingVisibility, setVisible };
}
