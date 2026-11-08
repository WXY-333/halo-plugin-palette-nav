export interface VersionedEntity {
  metadata: {
    version?: number | null;
  };
}

/**
 * Halo performs optimistic concurrency control on `metadata.version`.
 * Every successful update returns a new version; if it is not copied back to
 * the local object, the next save of that object is rejected with 409
 * "检测到冲突".
 */
export function syncEntityVersion<T extends VersionedEntity>(
  target: T,
  updated: T
): void {
  if (typeof updated.metadata?.version === "number") {
    target.metadata.version = updated.metadata.version;
  }
}
