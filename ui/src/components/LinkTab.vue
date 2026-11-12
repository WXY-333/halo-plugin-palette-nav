<script lang="ts" setup>
import { fetchNavSettings } from "@/api/settings";
import { computed, getCurrentInstance, ref, watch } from "vue";
import {
  Dialog,
  Toast,
  VAlert,
  VButton,
  VCard,
  VEmpty,
  VEntity,
  VEntityContainer,
  VEntityField,
  VModal,
  VPagination,
  VSwitch,
  VTag,
  IconAddCircle,
  IconDeleteBin,
  IconRiPencilFill,
  VDropdownDivider,
  VDropdownItem,
} from "@halo-dev/components";
import {
  LINK_PLURAL,
  createEntity,
  createName,
  deleteEntity,
  errorMessage,
  fetchLinkMeta,
  listLinks,
  updateEntity,
} from "@/api/nav";
import type { NavCategory, NavLink } from "@/types/nav";
import { useBulkCopy } from "@/composables/useBulkCopy";
import { useBulkDelete } from "@/composables/useBulkDelete";
import { useBulkMove } from "@/composables/useBulkMove";
import { useBulkVisibility } from "@/composables/useBulkVisibility";
import { useDragSort } from "@/composables/useDragSort";
import { useRowVisibility } from "@/composables/useRowVisibility";
import { useSortDraft } from "@/composables/useSortDraft";
import { isSafeHttpUrl, isSafeLinkUrl, isSafeImageUrl } from "@/utils/icon";
import { syncEntityVersion } from "@/utils/entity";
import { moveItem, nextSort, type SortUpdate } from "@/utils/reorder";
import DragHandle from "./DragHandle.vue";
import DragPlaceholder from "./DragPlaceholder.vue";
import IconPicker from "./IconPicker.vue";
import MediaPicker from "./MediaPicker.vue";
import IconPreview from "./IconPreview.vue";
import RefreshButton from "./RefreshButton.vue";
import SelectAllCheckbox from "./SelectAllCheckbox.vue";

const props = defineProps<{
  categories: NavCategory[];
  links: NavLink[];
}>();

const emit = defineEmits<{
  refresh: [];
  "dirty-change": [dirty: boolean];
}>();

const {
  selectedNames,
  bulkSaving,
  selectedCount,
  setSelectedVisible,
} = useBulkVisibility(LINK_PLURAL, () => props.links, () => emit("refresh"));

const { bulkDeleting, deleteSelected } = useBulkDelete(
  LINK_PLURAL,
  "链接",
  () => props.links,
  selectedNames,
  () => emit("refresh")
);

const {
  draftNames,
  orderDirty,
  orderSaving,
  applyDraft,
  discardDraft,
  persistOrder,
} = useSortDraft("NavLink", () => props.links, () => emit("refresh"), listLinks);

const { bulkMoving, moveSelected } = useBulkMove(
  LINK_PLURAL,
  () => props.links,
  selectedNames,
  () => emit("refresh")
);

const { bulkCopying, copySelected } = useBulkCopy(
  LINK_PLURAL,
  () => props.links,
  selectedNames,
  () => emit("refresh"),
  (link, categoryName, sort) => ({
    apiVersion: "palette-nav.halo.run/v1alpha1",
    kind: "NavLink",
    metadata: { name: createName("link") },
    spec: {
      categoryName,
      displayName: link.spec.displayName,
      url: link.spec.url,
      icon: link.spec.icon,
      coverUrl: link.spec.coverUrl,
      sort,
      visible: link.spec.visible !== false,
    },
  })
);

const LINK_FORM_ID = "pn-link-form";
const BATCH_FORM_ID = "pn-link-batch-form";
const MOVE_FORM_ID = "pn-link-move-form";
const COPY_FORM_ID = "pn-link-copy-form";

const sortedCategories = computed(() =>
  [...props.categories].sort(
    (a, b) =>
      (a.spec.sort ?? 0) - (b.spec.sort ?? 0) ||
      a.metadata.name.localeCompare(b.metadata.name)
  )
);

const categoryMap = computed(() => {
  const map = new Map<string, NavCategory>();
  for (const category of props.categories) {
    map.set(category.metadata.name, category);
  }
  return map;
});

const categoryOrder = computed(() => {
  const map = new Map<string, number>();
  sortedCategories.value.forEach((category, index) => {
    map.set(category.metadata.name, index);
  });
  return map;
});

const categoryOptions = computed(() =>
  sortedCategories.value.map((category) => ({
    label: category.spec.displayName,
    value: category.metadata.name,
  }))
);

const filterCategory = ref("");
const keyword = ref("");
const page = ref(1);
const pageSize = ref(20);

const orphanLinks = computed(() =>
  props.links.filter((link) => !categoryMap.value.has(link.spec.categoryName))
);

/** Pseudo value for the orphan entry; real categories use their metadata name. */
const ORPHAN_FILTER = "__orphan__";

// Links can only be browsed one category at a time: the list is always scoped
// to a concrete category (there is deliberately no "all links" entry). Orphan
// links (whose category was deleted outside the UI) get their own entry so they
// stay reachable and can be fixed.
const filterItems = computed(() => {
  const items = categoryOptions.value.map((option) => ({ ...option }));
  if (orphanLinks.value.length > 0) {
    items.push({
      label: `分类缺失（${orphanLinks.value.length}）`,
      value: ORPHAN_FILTER,
    });
  }
  return items;
});

function compareLinks(
  a: NavLink,
  b: NavLink,
  ranks?: Map<string, number>
): number {
  const categoryA = categoryOrder.value.get(a.spec.categoryName) ?? 9999;
  const categoryB = categoryOrder.value.get(b.spec.categoryName) ?? 9999;
  if (categoryA !== categoryB) {
    return categoryA - categoryB;
  }
  if (ranks) {
    const rankA = ranks.get(a.metadata.name);
    const rankB = ranks.get(b.metadata.name);
    if (rankA !== undefined && rankB !== undefined) {
      return rankA - rankB;
    }
    // Rows added after the drag was made keep their default place at the end
    // of the category until the draft is saved and renumbered.
    if (rankA !== undefined) {
      return -1;
    }
    if (rankB !== undefined) {
      return 1;
    }
  }
  return (
    (a.spec.sort ?? 0) - (b.spec.sort ?? 0) ||
    a.metadata.name.localeCompare(b.metadata.name)
  );
}

/** Full link list in rendered order, with the draft applied when present. */
function allLinksOrdered(): NavLink[] {
  const ranks = draftNames.value
    ? new Map(draftNames.value.map((name, index) => [name, index]))
    : undefined;
  return [...props.links].sort((a, b) => compareLinks(a, b, ranks));
}

/** The order currently persisted on the server. */
function savedLinksOrdered(): NavLink[] {
  return [...props.links].sort((a, b) => compareLinks(a, b));
}

const filteredLinks = computed(() => {
  const term = keyword.value.trim().toLowerCase();
  const selected = filterCategory.value;
  return allLinksOrdered().filter((link) => {
    const inCategory =
      selected === ORPHAN_FILTER
        ? !categoryMap.value.has(link.spec.categoryName)
        : link.spec.categoryName === selected;
    if (!inCategory) {
      return false;
    }
    if (!term) {
      return true;
    }
    return (
      (link.spec.displayName ?? "").toLowerCase().includes(term) ||
      (link.spec.url ?? "").toLowerCase().includes(term)
    );
  });
});

const pagedLinks = computed(() => {
  const start = (page.value - 1) * pageSize.value;
  return filteredLinks.value.slice(start, start + pageSize.value);
});

// Select-all covers the rows on screen only, so ticking it on page 2 does not
// quietly queue up every filtered link.
const selectableNames = computed(() =>
  pagedLinks.value.map((link) => link.metadata.name)
);

watch([filterCategory, keyword, pageSize], () => {
  page.value = 1;
});

// The selection survives filtering and paging only by accident: the toolbar
// keeps showing "已选 N 项" while those rows are no longer visible, so a bulk
// action would touch rows the admin cannot see. Drop the selection whenever the
// visible set changes.
watch([filterCategory, keyword, pageSize, page], () => {
  selectedNames.value = [];
});

// The filter must always point at an option that still exists. A selected
// category can disappear (deleted by another admin), or the orphan entry
// disappears once the last orphan link is fixed; fall back to the first
// available option instead of leaving an unmatched value that renders an empty
// list.
watch(
  () => filterItems.value.map((item) => item.value).join("\u0000"),
  () => {
    const values = filterItems.value.map((item) => item.value);
    if (values.includes(filterCategory.value)) {
      return;
    }
    filterCategory.value = values[0] ?? "";
  },
  { immediate: true }
);

watch(
  () => filteredLinks.value.length,
  (total) => {
    const maxPage = Math.max(1, Math.ceil(total / pageSize.value));
    if (page.value > maxPage) {
      page.value = maxPage;
    }
  }
);

const modalVisible = ref(false);
const editing = ref<NavLink | null>(null);
const initialCategory = ref("");
const urlValue = ref("");
const nameValue = ref("");
const iconValue = ref("");
const coverUrl = ref("");
const showLinkCoverField = ref(false);
let coverSettingRevision = 0;
async function loadCoverFieldSetting() {
  const revision = ++coverSettingRevision;
  showLinkCoverField.value = false;
  try {
    const settings = await fetchNavSettings();
    if (revision === coverSettingRevision) {
      showLinkCoverField.value = settings.appearance.showLinkCoverField;
    }
  } catch (error) {
    if (revision === coverSettingRevision) Toast.error(errorMessage(error));
  }
}
const fetchingMeta = ref(false);
/** Identifies the in-flight 获取 request so a late response cannot fill another form. */
const metaRequestId = ref(0);
const saving = ref(false);
const formError = ref("");

const instance = getCurrentInstance();

function submitForm(formId: string) {
  if (formId === LINK_FORM_ID && !isSafeLinkUrl(urlValue.value.trim())) {
    formError.value = "请输入合法的网址（http/https 或以 / 开头的站内路径）";
    Toast.warning(formError.value);
    return;
  }
  const formkit = (
    instance?.proxy as { $formkit?: { submit: (name: string) => void } } | null
  )?.$formkit;
  formkit?.submit(formId);
}

function defaultCategory(): string {
  if (categoryMap.value.has(filterCategory.value)) {
    return filterCategory.value;
  }
  return sortedCategories.value[0]?.metadata.name ?? "";
}

/** Drops any in-flight 获取 response; used when the form is reset or closed. */
function cancelMetaFetch() {
  metaRequestId.value += 1;
  fetchingMeta.value = false;
}

function closeModal() {
  cancelMetaFetch();
  modalVisible.value = false;
}

function openCreate() {
  void loadCoverFieldSetting();
  cancelMetaFetch();
  editing.value = null;
  initialCategory.value = defaultCategory();
  urlValue.value = "";
  nameValue.value = "";
  iconValue.value = "";
  coverUrl.value = "";
  formError.value = "";
  modalVisible.value = true;
}

function openEdit(link: NavLink) {
  void loadCoverFieldSetting();
  cancelMetaFetch();
  editing.value = link;
  urlValue.value = link.spec.url ?? "";
  nameValue.value = link.spec.displayName ?? "";
  iconValue.value = link.spec.icon ?? "";
  coverUrl.value = link.spec.coverUrl ?? "";
  formError.value = "";
  modalVisible.value = true;
}

/**
 * Reads the target page and fills the name/icon fields. Site-relative links cannot
 * be read by the server, so they only get the path as their name.
 */
async function fillFromUrl() {
  const url = urlValue.value.trim();
  if (!url) {
    Toast.warning("请先输入网址");
    return;
  }
  if (!isSafeHttpUrl(url)) {
    if (isSafeLinkUrl(url)) {
      if (!nameValue.value.trim()) {
        nameValue.value = url;
      }
      Toast.warning("站内路径无法自动获取名称和图标");
      return;
    }
    Toast.warning("请输入合法的网址（http/https 或以 / 开头的站内路径）");
    return;
  }
  const requestId = ++metaRequestId.value;
  fetchingMeta.value = true;
  try {
    const meta = await fetchLinkMeta(url);
    // The URL may have been edited (or the dialog closed and reopened) while the
    // request was in flight; a stale response must not overwrite the current form.
    if (requestId !== metaRequestId.value || urlValue.value.trim() !== url) {
      return;
    }
    if (meta.name) {
      nameValue.value = meta.name;
    }
    if (meta.icon) {
      iconValue.value = meta.icon;
    }
    if (meta.fetched) {
      Toast.success("已获取名称和图标");
    } else {
      Toast.warning("未能读取网页内容，已按域名填充");
    }
  } catch (error) {
    if (requestId !== metaRequestId.value || urlValue.value.trim() !== url) {
      return;
    }
    if (!nameValue.value.trim()) {
      nameValue.value = defaultDisplayName(url);
    }
    Toast.error(errorMessage(error));
  } finally {
    if (requestId === metaRequestId.value) {
      fetchingMeta.value = false;
    }
  }
}

async function save(payload: {
  categoryName?: string;
  displayName?: string;
  url?: string;
}) {
  if (saving.value) {
    return;
  }
  const categoryName = payload.categoryName ?? "";
  const displayName = (payload.displayName ?? "").trim();
  const url = (payload.url ?? "").trim();
  if (!categoryName) {
    formError.value = "请选择分类";
    Toast.warning(formError.value);
    return;
  }
  if (!displayName) {
    formError.value = "请输入链接名称";
    Toast.warning(formError.value);
    return;
  }
  if (!isSafeLinkUrl(url)) {
    formError.value = "请输入合法的网址（http/https 或以 / 开头的站内路径）";
    Toast.warning(formError.value);
    return;
  }
  formError.value = "";
  saving.value = true;
  if (coverUrl.value.trim() && !isSafeImageUrl(coverUrl.value.trim())) {
    formError.value = "主页封面请输入 http/https 或以 / 开头的图片地址";
    Toast.warning(formError.value);
    saving.value = false;
    return;
  }
  try {
    if (editing.value) {
      const entity = JSON.parse(JSON.stringify(editing.value)) as NavLink;
      const categoryChanged = editing.value.spec.categoryName !== categoryName;
      entity.spec.categoryName = categoryName;
      entity.spec.displayName = displayName;
      entity.spec.url = url;
      entity.spec.icon = iconValue.value.trim();
      entity.spec.coverUrl = coverUrl.value.trim();
      if (categoryChanged) {
        // The old sort belongs to the previous category and may collide with (or
        // jump ahead of) the target category's order. Append it instead.
        entity.spec.sort = nextSort(
          props.links.filter(
            (link) =>
              link.spec.categoryName === categoryName &&
              link.metadata.name !== entity.metadata.name
          )
        );
      }
      const updated = await updateEntity(LINK_PLURAL, entity);
      // Keep the local row current even if the following refresh fails.
      syncEntityVersion(editing.value, updated);
      editing.value.spec = updated.spec ?? editing.value.spec;
      Toast.success("链接已更新");
    } else {
      const entity: NavLink = {
        apiVersion: "palette-nav.halo.run/v1alpha1",
        kind: "NavLink",
        metadata: { name: createName("link") },
        spec: {
          categoryName,
          displayName,
          url,
          icon: iconValue.value.trim(),
          coverUrl: coverUrl.value.trim(),
          sort: nextSort(
            props.links.filter(
              (link) => link.spec.categoryName === categoryName
            )
          ),
          visible: true,
        },
      };
      await createEntity(LINK_PLURAL, entity);
      Toast.success("链接已创建");
    }
    cancelMetaFetch();
    modalVisible.value = false;
    emit("refresh");
  } catch (error) {
    formError.value = errorMessage(error);
    Toast.error(formError.value);
  } finally {
    saving.value = false;
  }
}

function remove(link: NavLink) {
  Dialog.warning({
    title: "删除链接",
    description: `确定删除「${link.spec.displayName}」吗？此操作不可撤销。`,
    confirmText: "删除",
    cancelText: "取消",
    confirmType: "danger",
    onConfirm: async () => {
      try {
        await deleteEntity(LINK_PLURAL, link.metadata.name);
        Toast.success("链接已删除");
        emit("refresh");
      } catch (error) {
        Toast.error(errorMessage(error));
      }
    },
  });
}

const { pendingVisibility, setVisible } = useRowVisibility<NavLink>(LINK_PLURAL);

function rowIndex(index: number): number {
  return (page.value - 1) * pageSize.value + index;
}

const { dropSlot, sourceHeight, handleProps, rowProps } = useDragSort({
  // Sorting inside a keyword result set would renumber non-contiguous rows, so
  // dragging is only offered while no keyword filter is applied.
  canDrag: () => !keyword.value.trim() && !orderSaving.value,
  canDrop: (from, to) => {
    const list = filteredLinks.value;
    const source = list[from];
    const target = list[to];
    return (
      !!source && !!target && source.spec.categoryName === target.spec.categoryName
    );
  },
  onDrop: (from, to) => {
    const list = filteredLinks.value;
    const moved = list[from];
    if (!moved) {
      return;
    }
    const reordered = moveItem(list, from, to);
    const category = moved.spec.categoryName;
    const categoryNames = reordered
      .filter((link) => link.spec.categoryName === category)
      .map((link) => link.metadata.name);
    // Merge the new in-category order into the whole-list draft so a change
    // made under one category filter does not drop drafts from other
    // categories. Only 保存排序 sends it to the server.
    let index = 0;
    const merged = allLinksOrdered().map((link) =>
      link.spec.categoryName === category
        ? categoryNames[index++] ?? link.metadata.name
        : link.metadata.name
    );
    applyDraft(
      merged,
      savedLinksOrdered().map((link) => link.metadata.name)
    );
  },
});

/**
 * Builds the renumbering payload: every category touched by the draft is
 * renumbered from 10, ordered by the draft position. Untouched categories keep
 * their existing `sort` values because links are only comparable in-category.
 */
function linkOrderUpdates(): SortUpdate[] {
  if (!draftNames.value) {
    return [];
  }
  const ranks = new Map(draftNames.value.map((name, index) => [name, index]));
  const categories = new Set<string>();
  for (const link of props.links) {
    if (ranks.has(link.metadata.name)) {
      categories.add(link.spec.categoryName);
    }
  }
  const groups = new Map<string, NavLink[]>();
  for (const link of props.links) {
    if (!categories.has(link.spec.categoryName)) {
      continue;
    }
    const group = groups.get(link.spec.categoryName);
    if (group) {
      group.push(link);
    } else {
      groups.set(link.spec.categoryName, [link]);
    }
  }
  const updates: SortUpdate[] = [];
  for (const group of groups.values()) {
    const next = [...group].sort((a, b) => {
      const rankA = ranks.get(a.metadata.name);
      const rankB = ranks.get(b.metadata.name);
      if (rankA !== undefined && rankB !== undefined) {
        return rankA - rankB;
      }
      if (rankA !== undefined) {
        return -1;
      }
      if (rankB !== undefined) {
        return 1;
      }
      return (
        (a.spec.sort ?? 0) - (b.spec.sort ?? 0) ||
        a.metadata.name.localeCompare(b.metadata.name)
      );
    });
    // Only categories whose order actually changed need renumbering; other
    // categories keep their sort values untouched.
    const current = [...group]
      .sort(
        (a, b) =>
          (a.spec.sort ?? 0) - (b.spec.sort ?? 0) ||
          a.metadata.name.localeCompare(b.metadata.name)
      )
      .map((link) => link.metadata.name);
    if (next.every((link, index) => link.metadata.name === current[index])) {
      continue;
    }
    next.forEach((link, index) => {
      updates.push({ name: link.metadata.name, sort: (index + 1) * 10 });
    });
  }
  return updates;
}

function saveOrder() {
  void persistOrder(linkOrderUpdates());
}

watch(orderDirty, (dirty) => emit("dirty-change", dirty));

const batchVisible = ref(false);
const batchCategory = ref("");
const batchSaving = ref(false);
const batchError = ref("");

function openBatch() {
  batchCategory.value = defaultCategory();
  batchError.value = "";
  batchVisible.value = true;
}

/**
 * Derives a default link name from a URL. Relative paths keep the path itself:
 * resolving them against the console origin would make every "/about"-style
 * entry share the site's own hostname as its name.
 */
function defaultDisplayName(url: string): string {
  if (url.startsWith("/") || url.startsWith("#")) {
    return url;
  }
  try {
    return new URL(url).hostname || url;
  } catch {
    return url;
  }
}

async function saveBatch(payload: { categoryName?: string; urls?: string }) {
  const categoryName = payload.categoryName ?? "";
  if (!categoryName) {
    batchError.value = "请选择分类";
    return;
  }
  const urls = (payload.urls ?? "")
    .split(/\r?\n/)
    .map((value) => value.trim())
    .filter(Boolean);
  if (urls.length === 0) {
    batchError.value = "请输入至少一个网址";
    return;
  }
  batchError.value = "";
  const existing = new Set(
    props.links
      .filter((link) => link.spec.categoryName === categoryName)
      .map((link) => link.spec.url)
  );
  let created = 0;
  let skipped = 0;
  let failed = 0;
  let firstFailure = "";
  // Append after the largest existing sort instead of pinning every new link to 0.
  let nextLinkSort = nextSort(
    props.links.filter((link) => link.spec.categoryName === categoryName)
  );
  batchSaving.value = true;
  try {
    for (const url of urls) {
      if (!isSafeLinkUrl(url) || existing.has(url)) {
        skipped += 1;
        continue;
      }
      const displayName = defaultDisplayName(url);
      const entity: NavLink = {
        apiVersion: "palette-nav.halo.run/v1alpha1",
        kind: "NavLink",
        metadata: { name: createName("link") },
        spec: {
          categoryName,
          displayName,
          url,
          sort: nextLinkSort,
          visible: true,
        },
      };
      try {
        await createEntity(LINK_PLURAL, entity);
        existing.add(url);
        created += 1;
        nextLinkSort += 10;
      } catch (error) {
        // A server error is not a duplicate; keep it visible instead of
        // reporting the whole batch as a success.
        failed += 1;
        if (!firstFailure) {
          firstFailure = errorMessage(error);
        }
      }
    }
    if (failed > 0) {
      batchError.value =
        `新增 ${created} 个，失败 ${failed} 个，跳过 ${skipped} 个。${firstFailure}`;
      Toast.error(batchError.value);
      emit("refresh");
    } else {
      Toast.success(`新增 ${created} 个，跳过 ${skipped} 个`);
      batchVisible.value = false;
      emit("refresh");
    }
  } finally {
    batchSaving.value = false;
  }
}

const moveVisible = ref(false);
const moveCategory = ref("");
const moveError = ref("");

const copyVisible = ref(false);
const copyCategory = ref("");
const copyError = ref("");

const selectedCategoryNames = computed(
  () =>
    new Set(
      props.links
        .filter((link) => selectedNames.value.includes(link.metadata.name))
        .map((link) => link.spec.categoryName)
    )
);

/**
 * Move targets exclude the selection's only category: moving every row into the
 * category it already lives in would be a no-op.
 */
const moveTargetOptions = computed(() => {
  const sources = selectedCategoryNames.value;
  return categoryOptions.value.filter(
    (option) => !(sources.size === 1 && sources.has(option.value))
  );
});

// Copy may target every category, including the selection's own one: a
// category is allowed to hold several links with the same URL.
const copyTargetOptions = computed(() => categoryOptions.value);

function defaultMoveCategory(): string {
  const options = moveTargetOptions.value;
  const preferred = defaultCategory();
  if (options.some((option) => option.value === preferred)) {
    return preferred;
  }
  return options[0]?.value ?? "";
}

function defaultCopyCategory(): string {
  const options = copyTargetOptions.value;
  if (options.length === 0) {
    return "";
  }
  // Prefer another category when the whole selection sits in one, so the
  // default does not silently duplicate rows within their own category.
  if (selectedCategoryNames.value.size === 1) {
    const other = options.find(
      (option) => !selectedCategoryNames.value.has(option.value)
    );
    if (other) {
      return other.value;
    }
  }
  const preferred = defaultCategory();
  if (options.some((option) => option.value === preferred)) {
    return preferred;
  }
  return options[0]?.value ?? "";
}

function openMove() {
  // An unsaved drag order already owns the sort values of the affected
  // categories; moving links would make that draft ambiguous. Save it first.
  if (orderDirty.value) {
    return;
  }
  moveCategory.value = defaultMoveCategory();
  moveError.value = "";
  moveVisible.value = true;
}

async function saveMove(payload: { categoryName?: string }) {
  const categoryName = (payload.categoryName ?? "").trim();
  if (!categoryName) {
    moveError.value = "请选择目标分类";
    return;
  }
  if (bulkMoving.value) {
    return;
  }
  moveError.value = "";
  const result = await moveSelected(categoryName);
  moveVisible.value = false;
  if (result.failed > 0) {
    Toast.error(
      `移动完成：成功 ${result.moved} 个，失败 ${result.failed} 个。${result.firstFailure}`
    );
    return;
  }
  if (result.moved === 0) {
    Toast.warning("选中的链接已在目标分类中");
    return;
  }
  Toast.success(
    `已移动 ${result.moved} 个链接${
      result.skipped > 0 ? `，跳过 ${result.skipped} 个` : ""
    }`
  );
}

function openCopy() {
  copyCategory.value = defaultCopyCategory();
  copyError.value = "";
  copyVisible.value = true;
}

async function saveCopy(payload: { categoryName?: string }) {
  const categoryName = (payload.categoryName ?? "").trim();
  if (!categoryName) {
    copyError.value = "请选择目标分类";
    return;
  }
  if (bulkCopying.value) {
    return;
  }
  copyError.value = "";
  const result = await copySelected(categoryName);
  copyVisible.value = false;
  if (result.failed > 0) {
    Toast.error(
      `复制完成：成功 ${result.copied} 个，失败 ${result.failed} 个。${result.firstFailure}`
    );
    return;
  }
  if (result.copied === 0) {
    Toast.warning("没有可复制的链接");
    return;
  }
  Toast.success(`已复制 ${result.copied} 个链接`);
}

</script>

<template>
  <VCard :body-class="['!p-0']">
    <div class="pn-toolbar">
      <SelectAllCheckbox v-model="selectedNames" :names="selectableNames" />
      <template v-if="selectedCount > 0">
        <span class="pn-toolbar-hint">已选 {{ selectedCount }} 项</span>
        <VButton
          size="sm"
          :loading="bulkSaving"
          :disabled="bulkSaving || bulkDeleting || bulkMoving || bulkCopying"
          @click="setSelectedVisible(true)"
        >
          启用
        </VButton>
        <VButton
          size="sm"
          :loading="bulkSaving"
          :disabled="bulkSaving || bulkDeleting || bulkMoving || bulkCopying"
          @click="setSelectedVisible(false)"
        >
          禁用
        </VButton>
        <VButton
          size="sm"
          :loading="bulkMoving"
          :disabled="
            bulkSaving ||
            bulkDeleting ||
            bulkMoving ||
            bulkCopying ||
            orderDirty ||
            moveTargetOptions.length === 0
          "
          :title="
            orderDirty
              ? '请先保存排序'
              : moveTargetOptions.length === 0
                ? '没有可移动到的其他分类'
                : undefined
          "
          @click="openMove"
        >
          移动
        </VButton>
        <VButton
          size="sm"
          :loading="bulkCopying"
          :disabled="
            bulkSaving ||
            bulkDeleting ||
            bulkMoving ||
            bulkCopying ||
            copyTargetOptions.length === 0
          "
          :title="
            copyTargetOptions.length === 0
              ? '没有可复制到的分类'
              : undefined
          "
          @click="openCopy"
        >
          复制
        </VButton>
        <VButton
          size="sm"
          type="danger"
          :loading="bulkDeleting"
          :disabled="bulkSaving || bulkDeleting || bulkMoving || bulkCopying"
          @click="deleteSelected"
        >
          删除
        </VButton>
      </template>
      <template v-if="orderDirty">
        <span class="pn-toolbar-hint">排序已修改，保存后生效。</span>
        <VButton size="sm" :disabled="orderSaving" @click="discardDraft">
          撤销
        </VButton>
        <VButton
          size="sm"
          type="danger"
          :loading="orderSaving"
          @click="saveOrder"
        >
          保存排序
        </VButton>
      </template>
      <div class="pn-toolbar-search">
        <SearchInput v-model="keyword" placeholder="搜索名称或网址，回车确认" />
      </div>
      <FilterDropdown v-model="filterCategory" label="分类" :items="filterItems" />
      <span class="pn-toolbar-spacer"></span>
      <div class="pn-toolbar-actions">
        <VButton
          size="sm"
          :disabled="sortedCategories.length === 0"
          @click="openBatch"
        >
          <template #icon>
            <IconAddCircle />
          </template>
          批量添加
        </VButton>
        <VButton
          size="sm"
          type="primary"
          :disabled="sortedCategories.length === 0"
          @click="openCreate"
        >
          <template #icon>
            <IconAddCircle />
          </template>
          新增链接
        </VButton>
        <RefreshButton @refresh="emit('refresh')" />
      </div>
    </div>

    <div v-if="orphanLinks.length > 0" class="pn-alert-wrap">
      <VAlert
        type="warning"
        :description="`有 ${orphanLinks.length} 个链接引用了不存在的分类，导航页会隐藏它们。请在编辑弹窗中重新选择分类。`"
      />
    </div>

    <VEmpty
      v-if="filteredLinks.length === 0"
      :title="
        props.links.length === 0
          ? '还没有链接'
          : keyword.trim()
            ? '没有匹配的链接'
            : '该分类下还没有链接'
      "
      :message="
        props.links.length === 0
          ? '先新增链接，或使用「批量添加」一次添加多个网址。'
          : keyword.trim()
            ? '试试更换关键词。'
            : '切换到其他分类查看，或新增链接。'
      "
    >
      <template #actions>
        <VButton
          v-if="props.links.length === 0"
          size="sm"
          :disabled="sortedCategories.length === 0"
          @click="openCreate"
        >
          新增链接
        </VButton>
      </template>
    </VEmpty>

    <VEntityContainer v-else>
      <template v-for="(link, index) in pagedLinks" :key="link.metadata.name">
        <DragPlaceholder
          v-if="dropSlot === rowIndex(index)"
          :height="sourceHeight"
        />
        <VEntity
          v-bind="rowProps(rowIndex(index))"
          :is-selected="selectedNames.includes(link.metadata.name)"
        >
          <template #checkbox>
            <input
              v-model="selectedNames"
              type="checkbox"
              :value="link.metadata.name"
              :aria-label="`选择「${link.spec.displayName}」`"
            />
          </template>
          <template #start>
            <DragHandle
              v-bind="handleProps(rowIndex(index))"
              :label="`拖动调整「${link.spec.displayName}」的顺序`"
            />
            <button
              type="button"
              class="pn-edit-icon"
              :aria-label="`编辑「${link.spec.displayName}」`"
              :title="`编辑「${link.spec.displayName}」`"
              @click="openEdit(link)"
            >
              <IconPreview :icon="link.spec.icon" />
            </button>
            <VEntityField
              class="pn-edit-target"
              :title="link.spec.displayName"
              :max-width="420"
              @click="openEdit(link)"
            >
              <template #description>
                <a
                  v-if="isSafeLinkUrl(link.spec.url)"
                  class="pn-entity-link"
                  :href="link.spec.url"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {{ link.spec.url }}
                </a>
                <span v-else class="pn-entity-link">{{ link.spec.url }}</span>
              </template>
            </VEntityField>
          </template>
          <template #end>
            <VTag
              class="pn-entity-tag"
              :theme="categoryMap.has(link.spec.categoryName) ? 'default' : 'danger'"
            >
              {{ categoryMap.get(link.spec.categoryName)?.spec.displayName ?? "分类缺失" }}
            </VTag>
            <VSwitch
              :model-value="link.spec.visible !== false"
              :disabled="pendingVisibility.has(link.metadata.name)"
              :aria-label="`切换「${link.spec.displayName}」的可见性`"
              @update:model-value="(value) => setVisible(link, Boolean(value))"
            />
          </template>
          <template #dropdownItems>
            <VDropdownItem @click="openEdit(link)">
              <template #prefix-icon>
                <IconRiPencilFill />
              </template>
              编辑
            </VDropdownItem>
            <VDropdownDivider />
            <VDropdownItem type="danger" @click="remove(link)">
              <template #prefix-icon>
                <IconDeleteBin />
              </template>
              删除
            </VDropdownItem>
          </template>
        </VEntity>
      </template>
      <DragPlaceholder
        v-if="dropSlot === rowIndex(pagedLinks.length)"
        :height="sourceHeight"
      />
    </VEntityContainer>

    <template v-if="filteredLinks.length > 0" #footer>
      <VPagination
        v-model:page="page"
        v-model:size="pageSize"
        :total="filteredLinks.length"
        :size-options="[10, 20, 50, 100]"
      />
    </template>
  </VCard>

  <VModal
    v-if="modalVisible"
    :title="editing ? '编辑链接' : '新增链接'"
    :width="640"
    @close="closeModal"
  >
    <FormKit :id="LINK_FORM_ID" type="form" :actions="false" @submit="save" @submit-invalid="Toast.warning('请检查分类、网址和名称是否填写正确')">
      <FormKit
        name="categoryName"
        type="select"
        label="分类"
        placeholder="请选择分类"
        validation="required"
        validation-label="分类"
        :options="categoryOptions"
        :value="editing?.spec.categoryName ?? initialCategory"
      />
      <FormKit
        v-model="urlValue"
        name="url"
        type="text"
        label="网址"
        placeholder="https://example.com 或 /archives"
        validation="required"
        validation-label="网址"
      >
        <template #suffix>
          <button
            type="button"
            class="pn-fetch-button"
            :disabled="fetchingMeta"
            title="读取网页名称与图标"
            @click="fillFromUrl"
          >
            {{ fetchingMeta ? "获取中…" : "获取" }}
          </button>
        </template>
      </FormKit>
      <FormKit
        v-model="nameValue"
        name="displayName"
        type="text"
        label="名称"
        placeholder="例如：豆包，或点击网址右侧「获取」自动填写"
        validation="required"
        validation-label="名称"
      />
    </FormKit>

    <div class="pn-form-block">
      <span class="pn-form-label">图标</span>
      <IconPicker v-model="iconValue" />
      <div v-if="showLinkCoverField" class="pn-form-block">
        <MediaPicker
          v-model="coverUrl"
          label="主页封面"
          help="上传网站首页截图、从 Halo 附件库选择或输入图片链接，用于前端鼠标悬停预览。"
        />
      </div>
    </div>

    <VAlert
      v-if="formError"
      class="pn-form-block"
      type="error"
      :description="formError"
    />

    <template #footer>
      <div class="pn-modal-footer">
        <VButton size="sm" @click="closeModal">取消</VButton>
        <VButton size="sm" type="primary" :loading="saving" @click="submitForm(LINK_FORM_ID)">
          保存
        </VButton>
      </div>
    </template>
  </VModal>

  <VModal
    v-if="batchVisible"
    title="批量添加链接"
    :width="640"
    @close="batchVisible = false"
  >
    <FormKit :id="BATCH_FORM_ID" type="form" :actions="false" @submit="saveBatch">
      <FormKit
        name="categoryName"
        type="select"
        label="分类"
        placeholder="请选择分类"
        validation="required"
        validation-label="分类"
        :options="categoryOptions"
        :value="batchCategory"
      />
      <FormKit
        name="urls"
        type="textarea"
        label="网址列表"
        :rows="8"
        placeholder="每行一个网址，例如：&#10;https://www.example.com&#10;https://www.example.org"
        validation="required"
        validation-label="网址列表"
        help="名称默认取域名；同一分类下已存在的网址会跳过。不会抓取外部网站内容。"
      />
    </FormKit>

    <VAlert
      v-if="batchError"
      class="pn-form-block"
      type="error"
      :description="batchError"
    />

    <template #footer>
      <div class="pn-modal-footer">
        <VButton size="sm" @click="batchVisible = false">取消</VButton>
        <VButton
          size="sm"
          type="primary"
          :loading="batchSaving"
          @click="submitForm(BATCH_FORM_ID)"
        >
          开始导入
        </VButton>
      </div>
    </template>
  </VModal>

  <VModal
    v-if="moveVisible"
    title="移动链接"
    :width="480"
    @close="moveVisible = false"
  >
    <FormKit
      :id="MOVE_FORM_ID"
      type="form"
      :actions="false"
      @submit="saveMove"
    >
      <FormKit
        name="categoryName"
        type="select"
        label="目标分类"
        placeholder="请选择目标分类"
        validation="required"
        validation-label="目标分类"
        :options="moveTargetOptions"
        :value="moveCategory"
        help="选中的链接会追加到目标分类末尾；已在目标分类中的链接会被跳过。"
      />
    </FormKit>

    <VAlert
      v-if="moveError"
      class="pn-form-block"
      type="error"
      :description="moveError"
    />

    <template #footer>
      <div class="pn-modal-footer">
        <VButton size="sm" @click="moveVisible = false">取消</VButton>
        <VButton
          size="sm"
          type="primary"
          :loading="bulkMoving"
          @click="submitForm(MOVE_FORM_ID)"
        >
          移动
        </VButton>
      </div>
    </template>
  </VModal>

  <VModal
    v-if="copyVisible"
    title="复制链接"
    :width="480"
    @close="copyVisible = false"
  >
    <FormKit
      :id="COPY_FORM_ID"
      type="form"
      :actions="false"
      @submit="saveCopy"
    >
      <FormKit
        name="categoryName"
        type="select"
        label="目标分类"
        placeholder="请选择目标分类"
        validation="required"
        validation-label="目标分类"
        :options="copyTargetOptions"
        :value="copyCategory"
        help="选中的链接会复制一份到目标分类末尾，允许与分类中已有链接完全相同。"
      />
    </FormKit>

    <VAlert
      v-if="copyError"
      class="pn-form-block"
      type="error"
      :description="copyError"
    />

    <template #footer>
      <div class="pn-modal-footer">
        <VButton size="sm" @click="copyVisible = false">取消</VButton>
        <VButton
          size="sm"
          type="primary"
          :loading="bulkCopying"
          @click="submitForm(COPY_FORM_ID)"
        >
          复制
        </VButton>
      </div>
    </template>
  </VModal>
</template>
