import { consoleApiClient } from "@halo-dev/api-client";

const delay = (ms: number) =>
  new Promise((resolve) => window.setTimeout(resolve, ms));

/**
 * Halo may create the attachment before the storage backend finishes resolving
 * its permalink, so poll the attachment list for a short while.
 */
export async function resolveAttachmentPermalink(name: string): Promise<string> {
  for (let attempt = 0; attempt < 10; attempt += 1) {
    await delay(350);
    try {
      // Filter by name instead of scanning the newest page: concurrent uploads can
      // push the target attachment out of the first N results before the poll runs.
      const { data } = await consoleApiClient.storage.attachment.searchAttachments({
        page: 1,
        size: 20,
        fieldSelector: [`metadata.name=${name}`],
        sort: ["metadata.creationTimestamp,desc"],
      });
      const match = data.items?.find((item) => item.metadata.name === name);
      const permalink = match?.status?.permalink;
      if (permalink) {
        return permalink;
      }
    } catch {
      // Keep polling: object storage may still be resolving the permalink.
    }
  }
  return "";
}