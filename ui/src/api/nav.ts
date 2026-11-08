import { axiosInstance } from "@halo-dev/api-client";
import type { RawAxiosRequestConfig } from "axios";
import {
  NavCategoryV1alpha1Api,
  NavConsoleV1alpha1Api,
  NavLinkV1alpha1Api,
  NavQuickLinkV1alpha1Api,
  NavSearchEngineV1alpha1Api,
} from "./generated";
import type {
  NavCategory,
  NavLink,
  NavLinkMeta,
  NavQuickLink,
  NavSearchEngine,
  NavReorderItem,
} from "./generated";
import type { NavDashboardStats } from "@/types/dashboard";

const CONTEXT_PATH = window.location.pathname.replace(/\/console\/.*$/, "");

export const CATEGORY_PLURAL = "navcategories";
export const LINK_PLURAL = "navlinks";
export const ENGINE_PLURAL = "navsearchengines";
export const QUICK_LINK_PLURAL = "navquicklinks";

export type ManagedEntity = NavCategory | NavLink | NavSearchEngine | NavQuickLink;

/** Result of the console's "获取" action for the link form. */
export type LinkMeta = Required<NavLinkMeta>;

/**
 * Halo's console axios interceptor shows its own toast for every non-muted
 * error. The custom palette-nav endpoints return a plain `message` instead of
 * the standard title/detail, so the host toast ("400: Bad Request") would appear
 * next to the component's own Chinese error. `mute` is read by the host
 * interceptor but not part of the api-client types.
 */
const SILENT_REQUEST = { mute: true } as RawAxiosRequestConfig;

const categoryApi = new NavCategoryV1alpha1Api(undefined, "", axiosInstance);
const linkApi = new NavLinkV1alpha1Api(undefined, "", axiosInstance);
const engineApi = new NavSearchEngineV1alpha1Api(undefined, "", axiosInstance);
const quickLinkApi = new NavQuickLinkV1alpha1Api(undefined, "", axiosInstance);
const consoleApi = new NavConsoleV1alpha1Api(undefined, "", axiosInstance);

async function listAll<T>(
  fetchPage: (page: number) => Promise<{ items: T[]; hasNext: boolean }>
): Promise<T[]> {
  const items: T[] = [];
  // Halo's hasNext terminates a normal listing; the cap only exists so a broken
  // server response cannot spin this loop forever.
  const maxPages = 1000;
  for (let page = 1; page <= maxPages; page += 1) {
    const { items: pageItems, hasNext } = await fetchPage(page);
    items.push(...pageItems);
    if (!hasNext) {
      return items;
    }
  }
  throw new Error("分页数据超过安全上限，请检查服务端分页参数");
}

export const listCategories = (): Promise<NavCategory[]> =>
  listAll(async (page) => {
    const { data } = await categoryApi.listNavCategory({ page, size: 100 });
    return { items: data.items, hasNext: data.hasNext };
  });

export const listLinks = (): Promise<NavLink[]> =>
  listAll(async (page) => {
    const { data } = await linkApi.listNavLink({ page, size: 100 });
    return { items: data.items, hasNext: data.hasNext };
  });

export const listSearchEngines = (): Promise<NavSearchEngine[]> =>
  listAll(async (page) => {
    const { data } = await engineApi.listNavSearchEngine({ page, size: 100 });
    return { items: data.items, hasNext: data.hasNext };
  });

export const listQuickLinks = (): Promise<NavQuickLink[]> =>
  listAll(async (page) => {
    const { data } = await quickLinkApi.listNavQuickLink({ page, size: 100 });
    return { items: data.items, hasNext: data.hasNext };
  });

type EntityLike = { metadata: { name: string } };

export async function createEntity<T extends EntityLike>(
  plural: string,
  entity: T
): Promise<T> {
  switch (plural) {
    case CATEGORY_PLURAL:
      return (
        await categoryApi.createNavCategory({
          navCategory: entity as unknown as NavCategory,
        })
      ).data as unknown as T;
    case LINK_PLURAL:
      return (
        await linkApi.createNavLink({ navLink: entity as unknown as NavLink })
      ).data as unknown as T;
    case ENGINE_PLURAL:
      return (
        await engineApi.createNavSearchEngine({
          navSearchEngine: entity as unknown as NavSearchEngine,
        })
      ).data as unknown as T;
    case QUICK_LINK_PLURAL:
      return (
        await quickLinkApi.createNavQuickLink({
          navQuickLink: entity as unknown as NavQuickLink,
        })
      ).data as unknown as T;
    default:
      throw new Error(`不支持的实体类型: ${plural}`);
  }
}

export async function updateEntity<T extends EntityLike>(
  plural: string,
  entity: T
): Promise<T> {
  const name = entity.metadata.name;
  switch (plural) {
    case CATEGORY_PLURAL:
      return (
        await categoryApi.updateNavCategory({
          name,
          navCategory: entity as unknown as NavCategory,
        })
      ).data as unknown as T;
    case LINK_PLURAL:
      return (
        await linkApi.updateNavLink({ name, navLink: entity as unknown as NavLink })
      ).data as unknown as T;
    case ENGINE_PLURAL:
      return (
        await engineApi.updateNavSearchEngine({
          name,
          navSearchEngine: entity as unknown as NavSearchEngine,
        })
      ).data as unknown as T;
    case QUICK_LINK_PLURAL:
      return (
        await quickLinkApi.updateNavQuickLink({
          name,
          navQuickLink: entity as unknown as NavQuickLink,
        })
      ).data as unknown as T;
    default:
      throw new Error(`不支持的实体类型: ${plural}`);
  }
}

/** Shared by the entity helpers here and the settings page (`SettingsTab.vue`). */
export function isNotFound(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    (error as { response?: { status?: number } }).response?.status === 404
  );
}

/**
 * Reports whether the entity is still listed; null when the check itself failed.
 *
 * Uses a filtered list instead of a single get: Halo's console axios shows a
 * toast for every 4xx response, and a get on an already deleted entity would
 * raise a 404 even though "not found" is a normal answer here.
 */
async function entityExists(
  plural: string,
  name: string
): Promise<boolean | null> {
  const fieldSelector = [`metadata.name=${name}`];
  const page = 1;
  const size = 1;
  try {
    switch (plural) {
      case CATEGORY_PLURAL: {
        const { data } = await categoryApi.listNavCategory({
          page,
          size,
          fieldSelector,
        });
        return data.items.length > 0;
      }
      case LINK_PLURAL: {
        const { data } = await linkApi.listNavLink({ page, size, fieldSelector });
        return data.items.length > 0;
      }
      case ENGINE_PLURAL: {
        const { data } = await engineApi.listNavSearchEngine({
          page,
          size,
          fieldSelector,
        });
        return data.items.length > 0;
      }
      case QUICK_LINK_PLURAL: {
        const { data } = await quickLinkApi.listNavQuickLink({
          page,
          size,
          fieldSelector,
        });
        return data.items.length > 0;
      }
      default:
        throw new Error(`不支持的实体类型: ${plural}`);
    }
  } catch (error) {
    return isNotFound(error) ? false : null;
  }
}

const GONE_POLL_INTERVAL_MS = 150;
const GONE_WAIT_TIMEOUT_MS = 5000;

/**
 * Halo applies DELETE asynchronously through finalizers: the response already
 * carries `metadata.deletionTimestamp`, but list keeps returning the entity for
 * another few hundred milliseconds. Poll until it is really gone so the refresh
 * after a delete actually drops the row (same strategy as
 * `NavConsoleService#awaitCategoryGone`).
 */
async function waitForEntityGone(
  plural: string,
  name: string
): Promise<boolean> {
  const deadline = Date.now() + GONE_WAIT_TIMEOUT_MS;
  while (Date.now() < deadline) {
    await new Promise((resolve) =>
      window.setTimeout(resolve, GONE_POLL_INTERVAL_MS)
    );
    if ((await entityExists(plural, name)) === false) {
      return true;
    }
  }
  return false;
}

export async function deleteEntity(plural: string, name: string): Promise<void> {
  // A stale row (another session deleted it, or a category cascade removed the
  // link) would make DELETE answer 404 and the console's axios would surface
  // that as an error toast. Nothing to do in that case.
  if ((await entityExists(plural, name)) === false) {
    return;
  }
  try {
    switch (plural) {
      case CATEGORY_PLURAL:
        await categoryApi.deleteNavCategory({ name });
        break;
      case LINK_PLURAL:
        await linkApi.deleteNavLink({ name });
        break;
      case ENGINE_PLURAL:
        await engineApi.deleteNavSearchEngine({ name });
        break;
      case QUICK_LINK_PLURAL:
        await quickLinkApi.deleteNavQuickLink({ name });
        break;
      default:
        throw new Error(`不支持的实体类型: ${plural}`);
    }
  } catch (error) {
    // DELETE is idempotent while the entity is still being finalized, so a 404
    // means it is already gone (stale row from another session, or a link
    // removed by a category cascade). That is exactly the state the caller
    // wants, so keep going and let the refresh drop the row.
    if (!isNotFound(error)) {
      throw error;
    }
  }
  await waitForEntityGone(plural, name);
}

export async function reorder(kind: string, items: NavReorderItem[]): Promise<void> {
  await consoleApi.reorderPaletteNavData(
    { navReorderRequest: { kind, items } },
    SILENT_REQUEST
  );
}

export async function deleteCategory(name: string): Promise<void> {
  // Same pre-check as deleteEntity: without it a stale category makes the console
  // endpoint answer 404, and Halo's axios interceptor already raised an error toast
  // by the time the catch below swallows it.
  if ((await entityExists(CATEGORY_PLURAL, name)) === false) {
    return;
  }
  try {
    await consoleApi.deletePaletteNavCategory({ name }, SILENT_REQUEST);
  } catch (error) {
    // The server answers 404 when the category was already removed (for example
    // by another session). That is the state the caller wants, so treat it as
    // success instead of failing the whole bulk-delete batch.
    if (!isNotFound(error)) {
      throw error;
    }
  }
}

export async function fetchDashboardStats(): Promise<NavDashboardStats> {
  const { data } = await consoleApi.fetchPaletteNavStats(SILENT_REQUEST);
  return {
    linkCount: data.linkCount ?? 0,
    todayPv: data.todayPv ?? 0,
    todayUv: data.todayUv ?? 0,
    totalPv: data.totalPv ?? 0,
    daily: (data.daily ?? []).map((day) => ({
      date: day.date ?? "",
      pv: day.pv ?? 0,
      uv: day.uv ?? 0,
    })),
  };
}

export async function fetchLinkMeta(url: string): Promise<LinkMeta> {
  const { data } = await consoleApi.fetchPaletteNavLinkMeta(
    { navLinkMetaRequest: { url } },
    SILENT_REQUEST
  );
  return {
    url: data.url || url,
    name: data.name ?? "",
    icon: data.icon ?? "",
    fetched: Boolean(data.fetched),
  };
}

export function createName(prefix: string): string {
  const random =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID().replace(/-/g, "").slice(0, 10)
      : Math.random().toString(36).slice(2, 12);
  return `${prefix}-${random}`;
}

export function errorMessage(error: unknown): string {
  if (typeof error === "object" && error !== null) {
    const candidate = error as {
      message?: string;
      response?: { data?: { message?: string; detail?: string; title?: string } };
    };
    return (
      candidate.response?.data?.message ||
      candidate.response?.data?.detail ||
      candidate.response?.data?.title ||
      candidate.message ||
      "请求失败，请稍后重试"
    );
  }
  return String(error);
}

export { CONTEXT_PATH };
