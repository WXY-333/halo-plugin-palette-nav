#!/usr/bin/env python3
"""Browser end-to-end tests for the palette-nav console and public page.

Requires a Halo instance with the plugin STARTED (default http://localhost:8096)
and Playwright Chromium installed. The script seeds data through the REST API,
drives the real console UI, verifies the result through the API again, and
removes everything it created.

    python tools/test_console_e2e.py
"""

from __future__ import annotations

import base64
import json
import os
import sys
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable

import requests
from playwright.sync_api import Page, sync_playwright

BASE = os.environ.get("PN_BASE", "http://localhost:8096").rstrip("/")
USER = os.environ.get("PN_USER", "admin")
PASSWORD = os.environ.get("PN_PASSWORD", "admin123")
TAG = uuid.uuid4().hex[:8]
RUN_ID = f"uitest-{TAG}"

MODEL_API = "/apis/palette-nav.halo.run/v1alpha1"
CONSOLE_API = "/apis/console.api.palette-nav.halo.run/v1alpha1"
# Settings are business data in a standalone ConfigMap, not the plugin's
# spec.configMapName (see plugin.yaml): read/write the ConfigMap directly.
CONFIG_API = "/api/v1alpha1/configmaps/palette-nav-config"
CONFIG_MAP_BODY = {
    "apiVersion": "v1alpha1",
    "kind": "ConfigMap",
    "metadata": {"name": "palette-nav-config"},
}
PLURALS = {
    "NavCategory": "navcategories",
    "NavLink": "navlinks",
    "NavSearchEngine": "navsearchengines",
    "NavQuickLink": "navquicklinks",
}

api = requests.Session()
api.auth = (USER, PASSWORD)
api.headers.update({"Accept": "application/json"})
created: list[tuple[str, str]] = []
settings_backup: dict[str, Any] | None = None
page: Page


@dataclass
class Result:
    passed: int = 0
    failed: int = 0
    failures: list[str] = field(default_factory=list)

    def check(self, name: str, condition: bool, detail: str = "") -> bool:
        if condition:
            self.passed += 1
            print(f"  PASS  {name}")
            return True
        self.failed += 1
        self.failures.append(f"{name}: {detail}")
        print(f"  FAIL  {name}  {detail}")
        return False

    def equal(self, name: str, actual: Any, expected: Any) -> bool:
        return self.check(name, actual == expected,
                          f"expected={expected!r} actual={actual!r}")


R = Result()


# --------------------------------------------------------------------------- #
# REST helpers (setup + assertions)
# --------------------------------------------------------------------------- #

def api_request(method: str, path: str, **kwargs) -> requests.Response:
    kwargs.setdefault("timeout", 30)
    return api.request(method, f"{BASE}{path}", **kwargs)


def model_path(kind: str, name: str | None = None) -> str:
    path = f"{MODEL_API}/{PLURALS[kind]}"
    return path + (f"/{name}" if name else "")


def api_create(kind: str, name: str, spec: dict[str, Any]) -> dict[str, Any]:
    r = api_request("POST", model_path(kind), json={
        "apiVersion": "palette-nav.halo.run/v1alpha1",
        "kind": kind,
        "metadata": {"name": name},
        "spec": spec,
    })
    if r.status_code != 201:
        raise AssertionError(f"create {kind} -> {r.status_code}: {r.text[:300]}")
    created.append((PLURALS[kind], name))
    return r.json()


def api_get(kind: str, name: str) -> dict[str, Any] | None:
    r = api_request("GET", model_path(kind, name))
    return r.json() if r.status_code == 200 else None


def api_delete(kind: str, name: str, *, cascade: bool = False) -> None:
    if cascade:
        api_request("DELETE", f"{CONSOLE_API}/category/{name}")
    else:
        api_request("DELETE", model_path(kind, name))


def api_list(kind: str) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    page_number = 1
    while True:
        r = api_request("GET", model_path(kind),
                        params={"page": page_number, "size": 100})
        r.raise_for_status()
        body = r.json()
        items.extend(body.get("items") or [])
        if not body.get("hasNext"):
            return items
        page_number += 1


def find_entity(kind: str, display_name: str) -> dict[str, Any] | None:
    return next((item for item in api_list(kind)
                 if (item.get("spec") or {}).get("displayName") == display_name), None)


def find_link_by_url(url: str) -> dict[str, Any] | None:
    return next((item for item in api_list("NavLink")
                 if (item.get("spec") or {}).get("url") == url), None)


def config() -> dict[str, Any]:
    """Returns the flat settings groups stored as JSON strings in the ConfigMap."""
    r = api_request("GET", CONFIG_API)
    if r.status_code == 404:
        return {}
    r.raise_for_status()
    data = (r.json() or {}).get("data") or {}
    return {key: json.loads(value) for key, value in data.items()
            if isinstance(value, str) and value.strip()}


def save_config(raw: dict[str, Any]) -> None:
    """Replaces the ConfigMap data with the given flat settings groups."""
    r = api_request("GET", CONFIG_API)
    body = r.json() if r.status_code == 200 else dict(CONFIG_MAP_BODY)
    body["data"] = {key: json.dumps(value, ensure_ascii=False)
                    for key, value in raw.items()}
    r = api_request("PUT", CONFIG_API, json=body)
    if r.status_code not in (200, 204):
        raise AssertionError(f"save config -> {r.status_code}: {r.text[:200]}")


def wait_until(predicate: Callable[[], bool], timeout: float = 12.0,
               interval: float = 0.2) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if predicate():
                return True
        except (requests.RequestException, AssertionError, ValueError):
            pass
        time.sleep(interval)
    return False


# --------------------------------------------------------------------------- #
# UI helpers
# --------------------------------------------------------------------------- #

def ui_tab(name: str) -> None:
    page.locator(".pn-page .tabbar-item-label", has_text=name).first.click()
    page.wait_for_timeout(650)


def toolbar() -> Any:
    return page.locator(".pn-toolbar:visible").first


def toolbar_button(name: str) -> Any:
    return toolbar().get_by_role("button", name=name).first


def rows() -> Any:
    return page.locator("tr[data-pn-index]:visible")


def row_containing(text: str) -> Any:
    return page.locator("tr[data-pn-index]:visible").filter(has_text=text).first


def modal(field_name: str) -> Any:
    return page.locator(".modal-content:visible").filter(
        has=page.locator(f'[name="{field_name}"]')).first


def save_modal(target: Any, label: str = "保存") -> None:
    target.locator(".modal-footer button").filter(has_text=label).first.click()


def dialog_button(label: str) -> None:
    page.locator(".modal-content:visible .modal-footer button:visible") \
        .filter(has_text=label).last.click()
    page.wait_for_timeout(600)


def dialog_text() -> str:
    dialogs = page.locator(".modal-content:visible")
    if dialogs.count() == 0:
        return ""
    return "\n".join(dialogs.all_inner_texts())


def select_filter(label: str) -> None:
    # Halo's FilterDropdown toggles the current value off when it is clicked
    # again, so only act when the requested category is not selected yet.
    if f"分类：{label}" in toolbar().inner_text():
        return
    trigger = page.locator(".pn-toolbar:visible .v-popper") \
        .filter(has_text="分类").first
    trigger.click()
    page.wait_for_timeout(300)
    page.locator(".dropdown-item-wrapper:visible").filter(has_text=label).last.click()
    page.wait_for_timeout(500)


def switch_in(target: Any) -> Any:
    return target.locator('button[role="switch"]').first


def open_row_dropdown(target: Any, label: str) -> None:
    target.locator(".entity-dropdown-trigger").click(force=True)
    page.wait_for_timeout(300)
    page.locator(".dropdown-item-wrapper:visible").filter(has_text=label).last.click()
    page.wait_for_timeout(400)


def drag(source: Any, target: Any, above: bool = True) -> None:
    # The drag handle is display:none until the row is hovered.
    source.hover()
    page.wait_for_timeout(250)
    handle = source.locator(".pn-drag-handle").first
    src_box = handle.bounding_box()
    dst_box = target.bounding_box()
    if not src_box or not dst_box:
        raise AssertionError("drag: missing bounding box")
    page.mouse.move(src_box["x"] + src_box["width"] / 2,
                    src_box["y"] + src_box["height"] / 2)
    page.mouse.down()
    page.mouse.move(src_box["x"] + src_box["width"] / 2, src_box["y"] + 12,
                    steps=4)
    dest_y = dst_box["y"] + (dst_box["height"] * 0.2 if above
                             else dst_box["height"] * 0.8)
    page.mouse.move(dst_box["x"] + dst_box["width"] / 2, dest_y, steps=12)
    page.wait_for_timeout(150)
    page.mouse.up()
    page.wait_for_timeout(400)


def refresh() -> None:
    page.locator(".pn-refresh:visible").first.click()
    page.wait_for_timeout(900)


# --------------------------------------------------------------------------- #
# Tests
# --------------------------------------------------------------------------- #

def test_dashboard_loads() -> None:
    print("\n[dashboard]")
    page.goto(f"{BASE}/console/palette-nav", wait_until="networkidle")
    page.wait_for_timeout(2500)
    R.check("dashboard is the default tab",
            "仪表盘" in page.locator(".tabbar-item-active").first.inner_text())
    R.equal("four stat cards", page.locator(".pn-stat-card").count(), 4)
    R.check("charts rendered", page.locator(".pn-dashboard-chart canvas").count() == 2,
            str(page.locator(".pn-dashboard-chart canvas").count()))
    stats_r = api_request("GET", f"{CONSOLE_API}/stats")
    stats = stats_r.json()
    card_values = page.locator(".pn-stat-card-value").all_inner_texts()
    R.equal("link count card matches the API", card_values[0] if card_values else None,
            f"{stats['linkCount']:,}")
    refresh()
    R.equal("refresh keeps four cards", page.locator(".pn-stat-card").count(), 4)


def test_categories_ui() -> dict[str, str]:
    print("\n[categories]")
    ui_tab("分类")
    names = {}

    for suffix in ("A", "B"):
        display = f"UI-CAT-{suffix}-{TAG}"
        toolbar_button("新增分类").click()
        form = modal("displayName")
        form.locator('input[name="displayName"]').fill(display)
        save_modal(form)
        page.wait_for_timeout(900)
        entity = find_entity("NavCategory", display)
        R.check(f"create category {suffix} through the UI",
                entity is not None, display)
        if entity is None:
            continue
        created.append(("navcategories", entity["metadata"]["name"]))
        names[suffix] = entity["metadata"]["name"]
        R.check(f"category {suffix} row rendered",
                row_containing(display).count() == 1)

    if len(names) < 2:
        return names

    # Rename
    new_display = f"UI-CAT-A-EDIT-{TAG}"
    row_containing(f"UI-CAT-A-{TAG}").locator(".entity-field-title").first.click()
    form = modal("displayName")
    form.locator('input[name="displayName"]').fill(new_display)
    save_modal(form)
    page.wait_for_timeout(900)
    R.check("rename category", (api_get("NavCategory", names["A"]) or {})
            .get("spec", {}).get("displayName") == new_display)
    R.check("renamed row rendered", row_containing(new_display).count() == 1)

    # Row visibility switch
    target = row_containing(new_display)
    switch_in(target).click()
    page.wait_for_timeout(800)
    R.equal("row switch hides the category",
            (api_get("NavCategory", names["A"]) or {}).get("spec", {}).get("visible"),
            False)
    switch_in(rows().filter(has_text=new_display).first).click()
    page.wait_for_timeout(800)
    R.equal("row switch shows it again",
            (api_get("NavCategory", names["A"]) or {}).get("spec", {}).get("visible"),
            True)

    # Drag sort -> save -> edit again to prove metadata.version was synced.
    before_sorts = {name: (api_get("NavCategory", name) or {})["spec"]["sort"]
                    for name in names.values()}
    drag(row_containing(f"UI-CAT-B-{TAG}"), row_containing(new_display), above=True)
    R.check("save-order bar appears after a drag",
            toolbar().get_by_role("button", name="保存排序").count() == 1)
    toolbar().get_by_role("button", name="保存排序").first.click()
    page.wait_for_timeout(1500)
    after_sorts = {name: (api_get("NavCategory", name) or {})["spec"]["sort"]
                   for name in names.values()}
    R.check("drag reordered the two categories",
            after_sorts[names["B"]] < after_sorts[names["A"]],
            f"before={before_sorts} after={after_sorts}")

    post_sort_display = f"UI-CAT-A-POSTSORT-{TAG}"
    row_containing(new_display).locator(".entity-field-title").first.click()
    form = modal("displayName")
    form.locator('input[name="displayName"]').fill(post_sort_display)
    save_modal(form)
    page.wait_for_timeout(1000)
    R.check("editing after reorder does not hit a version conflict",
            (api_get("NavCategory", names["A"]) or {})
            .get("spec", {}).get("displayName") == post_sort_display)

    # Unsaved-order guard.
    drag(row_containing(post_sort_display), row_containing(f"UI-CAT-B-{TAG}"),
         above=True)
    page.locator(".pn-page .tabbar-item-label", has_text="链接").first.click()
    page.wait_for_timeout(400)
    guard = dialog_text()
    R.check("switching tabs with an unsaved order warns",
            "放弃未保存的修改" in guard, guard[:120])
    dialog_button("留在本页")
    R.check("cancel keeps the category tab active",
            "分类" in page.locator(".tabbar-item-active").first.inner_text())
    toolbar().get_by_role("button", name="撤销").first.click()
    page.wait_for_timeout(400)
    R.check("discard removes the save-order bar",
            toolbar().get_by_role("button", name="保存排序").count() == 0)
    ui_tab("链接")

    # Bulk visibility.
    ui_tab("分类")
    for display in (post_sort_display, f"UI-CAT-B-{TAG}"):
        row_containing(display).locator('input[type="checkbox"]').check()
    page.wait_for_timeout(300)
    R.check("selection counter appears", "已选 2 项" in toolbar().inner_text(),
            toolbar().inner_text()[:120])
    toolbar().get_by_role("button", name="禁用").first.click()
    page.wait_for_timeout(1600)
    R.equal("bulk disable applied",
            {(api_get("NavCategory", names["A"]) or {}).get("spec", {}).get("visible"),
             (api_get("NavCategory", names["B"]) or {}).get("spec", {}).get("visible")},
            {False})
    for display in (post_sort_display, f"UI-CAT-B-{TAG}"):
        row_containing(display).locator('input[type="checkbox"]').check()
    page.wait_for_timeout(300)
    toolbar().get_by_role("button", name="启用").first.click()
    page.wait_for_timeout(1600)
    R.equal("bulk enable applied",
            {(api_get("NavCategory", names["A"]) or {}).get("spec", {}).get("visible"),
             (api_get("NavCategory", names["B"]) or {}).get("spec", {}).get("visible")},
            {True})

    # Single-row delete via the dropdown menu.
    display = f"UI-CAT-DEL-{TAG}"
    toolbar_button("新增分类").click()
    form = modal("displayName")
    form.locator('input[name="displayName"]').fill(display)
    save_modal(form)
    page.wait_for_timeout(900)
    entity = find_entity("NavCategory", display)
    R.check("create category for single delete", entity is not None)
    if entity:
        open_row_dropdown(row_containing(display), "删除")
        dialog_button("删除")
        R.check("single category delete removes it",
                wait_until(lambda: api_get("NavCategory",
                                           entity["metadata"]["name"]) is None))

    # Bulk delete the two sort categories.
    for display in (post_sort_display, f"UI-CAT-B-{TAG}"):
        row_containing(display).locator('input[type="checkbox"]').check()
    page.wait_for_timeout(300)
    toolbar().get_by_role("button", name="删除").first.click()
    page.wait_for_timeout(400)
    dialog_button("删除")
    R.check("bulk category delete removes both",
            wait_until(lambda: all(api_get("NavCategory", name) is None
                                   for name in names.values())))
    return names


def test_links_ui() -> None:
    print("\n[links]")
    category_display = f"UI-LINKCAT-{TAG}"
    category = api_create("NavCategory", f"{RUN_ID}-linkcat", {
        "displayName": category_display, "sort": 10, "visible": True})
    category_name = category["metadata"]["name"]

    page.reload(wait_until="networkidle")
    page.wait_for_timeout(1800)
    ui_tab("链接")
    select_filter(category_display)
    R.check("category filter selected",
            f"分类：{category_display}" in toolbar().inner_text(),
            toolbar().inner_text()[:160])

    # Create through the UI, using the 获取 button.
    target_url = f"https://www.example.com/{RUN_ID}"
    toolbar_button("新增链接").click()
    form = modal("url")
    form.locator('input[name="url"]').fill(target_url)
    form.locator(".pn-fetch-button").click()
    # The server may spend several seconds fetching the page and storing the icon.
    R.check("获取 fills the name field",
            wait_until(lambda: bool(form.locator('input[name="displayName"]')
                                    .input_value().strip()), timeout=20),
            form.locator('input[name="displayName"]').input_value())
    form.locator('input[name="displayName"]').fill(f"UI-LINK-{TAG}")
    save_modal(form)
    page.wait_for_timeout(1400)
    link = find_link_by_url(target_url)
    R.check("link created through the UI", link is not None, target_url)
    if link is None:
        return
    created.append(("navlinks", link["metadata"]["name"]))
    R.check("new link row rendered", row_containing(f"UI-LINK-{TAG}").count() == 1)

    # Invalid URL is stopped by the form.
    toolbar_button("新增链接").click()
    form = modal("url")
    form.locator('input[name="url"]').fill("javascript:alert(1)")
    form.locator('input[name="displayName"]').fill(f"BAD-LINK-{TAG}")
    save_modal(form)
    page.wait_for_timeout(700)
    error_text = form.inner_text()
    R.check("invalid URL shows a validation error",
            "合法的网址" in error_text, error_text[:160])
    R.check("invalid link was not created",
            find_entity("NavLink", f"BAD-LINK-{TAG}") is None)
    # Same escape as the server: browsers normalize "\" to "/", so "/\evil.com"
    # is really the protocol-relative "//evil.com".
    form.locator('input[name="url"]').fill("/\\evil.example.com")
    save_modal(form)
    page.wait_for_timeout(700)
    R.check("backslash site-relative URL is rejected by the form",
            "合法的网址" in form.inner_text(), form.inner_text()[:160])
    form.locator(".modal-header-actions span").first.click()
    page.wait_for_timeout(400)

    # Visibility switch.
    switch_in(row_containing(f"UI-LINK-{TAG}")).click()
    page.wait_for_timeout(900)
    R.equal("link switch hides the link",
            (api_get("NavLink", link["metadata"]["name"]) or {})
            .get("spec", {}).get("visible"), False)
    switch_in(rows().filter(has_text=f"UI-LINK-{TAG}").first).click()
    page.wait_for_timeout(900)

    # Edit.
    edited = f"UI-LINK-EDIT-{TAG}"
    row_containing(f"UI-LINK-{TAG}").locator(".entity-field-title").first.click()
    form = modal("displayName")
    form.locator('input[name="displayName"]').fill(edited)
    save_modal(form)
    page.wait_for_timeout(1000)
    R.equal("link rename persisted",
            (api_get("NavLink", link["metadata"]["name"]) or {})
            .get("spec", {}).get("displayName"), edited)

    # Batch add with a duplicate and a new URL.
    new_url = f"https://batch-{TAG}.example.com/page"
    before_count = sum(1 for item in api_list("NavLink")
                       if item["spec"].get("categoryName") == category_name)
    toolbar_button("批量添加").click()
    form = modal("urls")
    form.locator('textarea[name="urls"]').fill(f"{target_url}\n{new_url}")
    save_modal(form, "开始导入")
    page.wait_for_timeout(1600)
    after_count = sum(1 for item in api_list("NavLink")
                      if item["spec"].get("categoryName") == category_name)
    R.equal("batch add creates the new URL and skips the duplicate",
            after_count, before_count + 1)
    batch_link = find_link_by_url(new_url)
    R.check("batch link exists", batch_link is not None)
    if batch_link:
        created.append(("navlinks", batch_link["metadata"]["name"]))

    # Pagination: seed enough rows and check page boundaries plus select-all.
    seeded = []
    for index in range(21):
        link_name = f"{RUN_ID}-page-link-{index}"
        api_create("NavLink", link_name, {
            "categoryName": category_name,
            "displayName": f"PAGE-LINK-{index:02d}-{TAG}",
            "url": f"https://page-{TAG}-{index}.example.com",
            "sort": 1000 + index * 10, "visible": True})
        seeded.append(link_name)
    refresh()
    page.wait_for_timeout(500)
    total_links = sum(1 for item in api_list("NavLink")
                      if item["spec"].get("categoryName") == category_name)
    R.equal("first page shows 20 rows", rows().count(), 20)
    page.locator(".pagination__btn--next:visible").first.click()
    page.wait_for_timeout(500)
    R.equal("second page shows the remainder", rows().count(), total_links - 20)
    toolbar().locator(".pn-select-all input").check()
    page.wait_for_timeout(300)
    R.check("select-all only selects the visible page",
            f"已选 {total_links - 20} 项" in toolbar().inner_text(),
            toolbar().inner_text()[:120])
    selected_page = page.locator(
        'tr[data-pn-index]:visible input[type="checkbox"]') \
        .evaluate_all("els => els.map(el => el.value)")
    toolbar().get_by_role("button", name="删除").first.click()
    page.wait_for_timeout(400)
    dialog_button("删除")
    R.check("bulk link delete removed the selected page",
            wait_until(lambda: all(api_get("NavLink", name) is None
                                   for name in selected_page)),
            str(selected_page))
    R.check("rows on the previous page were not deleted",
            any(api_get("NavLink", name) is not None
                for name in seeded if name not in selected_page))
    page.wait_for_timeout(700)

    # Search (press Enter to apply).
    search = toolbar().locator('input[name="keyword"]')
    search.fill("BATCH")
    search.press("Enter")
    page.wait_for_timeout(600)
    expected = [item for item in api_list("NavLink")
                if "batch" in item["spec"].get("displayName", "").lower()]
    R.equal("search narrows the list", rows().count(), len(expected))

    # Orphan warning.
    orphan = api_create("NavLink", f"{RUN_ID}-orphan", {
        "categoryName": f"{RUN_ID}-missing", "displayName": f"UI-ORPHAN-{TAG}",
        "url": f"https://orphan-{TAG}.example.com", "sort": 0, "visible": True})
    refresh()
    page.wait_for_timeout(700)
    alert = "\n".join(page.locator(".pn-alert-wrap").all_inner_texts())
    R.check("orphan link warning appears", "不存在的分类" in alert, alert[:160])
    api_delete("NavLink", orphan["metadata"]["name"])

    # Single delete through the dropdown (clear the keyword filter first).
    search.fill("")
    search.press("Enter")
    page.wait_for_timeout(600)
    open_row_dropdown(row_containing(edited), "删除")
    dialog_button("删除")
    R.check("single link delete removes it",
            wait_until(lambda: api_get("NavLink",
                                       link["metadata"]["name"]) is None))


def test_icon_picker_ui() -> None:
    print("\n[icon picker]")
    category_display = f"UI-ICONCAT-{TAG}"
    category = api_create("NavCategory", f"{RUN_ID}-iconcat", {
        "displayName": category_display, "sort": 10, "visible": True})
    icon_url = f"https://cdn.example.com/{TAG}-icon.png"
    link = api_create("NavLink", f"{RUN_ID}-iconlink", {
        "categoryName": category["metadata"]["name"],
        "displayName": f"UI-ICONLINK-{TAG}", "url": f"https://icon-{TAG}.example.com",
        "sort": 10, "visible": True})

    page.reload(wait_until="networkidle")
    page.wait_for_timeout(1600)
    ui_tab("链接")
    select_filter(category_display)

    def open_link_editor() -> Any:
        row_containing(f"UI-ICONLINK-{TAG}").locator(".entity-field-title").first.click()
        page.wait_for_timeout(600)
        return modal("url")

    def open_icon_menu(form: Any) -> None:
        form.locator(".pn-icon-trigger").click()
        page.wait_for_timeout(400)

    form = open_link_editor()
    open_icon_menu(form)
    menu = " | ".join(page.locator(".dropdown-item-wrapper:visible").all_inner_texts())
    R.check("icon menu offers upload/library/link/SVG",
            all(text in menu for text in ("上传", "从附件库选择", "输入链接", "SVG 代码")),
            menu)

    # 1) Image URL through the custom-link item.
    page.locator(".dropdown-item-wrapper:visible").filter(has_text="输入链接").last.click()
    page.wait_for_timeout(500)
    page.locator(".pn-link-popover input").first.fill(icon_url)
    page.locator(".pn-link-popover").get_by_role("button", name="保存").click()
    page.wait_for_timeout(500)
    R.equal("image URL fills the icon value",
            form.locator(".pn-icon-field-value").inner_text(), icon_url)
    save_modal(form)
    page.wait_for_timeout(1000)
    R.equal("image URL icon persisted",
            (api_get("NavLink", link["metadata"]["name"]) or {})
            .get("spec", {}).get("icon"), icon_url)

    # 2) Inline SVG through the SVG modal.
    form = open_link_editor()
    open_icon_menu(form)
    page.locator(".dropdown-item-wrapper:visible").filter(has_text="SVG 代码").last.click()
    page.wait_for_timeout(400)
    svg = ('<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/></svg>')
    page.locator(".pn-textarea.pn-icon-svg").fill(svg)
    page.get_by_role("button", name="确定").last.click()
    page.wait_for_timeout(400)
    R.equal("SVG value is summarized",
            form.locator(".pn-icon-field-type").inner_text(), "SVG 代码")
    save_modal(form)
    page.wait_for_timeout(1000)
    R.check("inline SVG icon persisted",
            ((api_get("NavLink", link["metadata"]["name"]) or {})
             .get("spec", {}).get("icon") or "").startswith("<svg"))
    nav = requests.get(f"{BASE}/nav", timeout=20).text
    R.check("inline SVG icon is rendered as a data URL", "data:image/svg+xml;" in nav)

    # 3) Unsafe SVG is refused before it can be stored.
    form = open_link_editor()
    open_icon_menu(form)
    page.locator(".dropdown-item-wrapper:visible").filter(has_text="SVG 代码").last.click()
    page.wait_for_timeout(400)
    page.locator(".pn-textarea.pn-icon-svg").fill(
        "<svg><script>alert(9)</script></svg>")
    page.get_by_role("button", name="确定").last.click()
    page.wait_for_timeout(600)
    R.check("unsafe SVG is rejected with a warning",
            "无法保存" in page.locator("body").inner_text())
    R.check("unsafe SVG was not applied", "<script" not in
            form.locator(".pn-icon-field-value").inner_text())
    # The SVG modal stays open on a rejected value. Its footer must remain
    # clickable even though the picker lives inside the edit modal (Teleport
    # regression: the outer layer used to intercept the buttons).
    svg_modal = page.locator(".modal-content:visible") \
        .filter(has=page.locator(".pn-textarea.pn-icon-svg")).first
    svg_modal.locator(".pn-modal-footer button").filter(has_text="取消").first.click()
    page.wait_for_timeout(400)
    R.check("SVG modal footer is clickable inside the edit modal",
            page.locator(".modal-content:visible")
            .filter(has=page.locator(".pn-textarea.pn-icon-svg")).count() == 0)
    save_modal(form)
    page.wait_for_timeout(900)
    R.check("stored icon is still the safe SVG",
            ((api_get("NavLink", link["metadata"]["name"]) or {})
             .get("spec", {}).get("icon") or "").startswith("<svg viewBox"))

    # 4) A value already stored through the API is sanitized in the preview.
    unsafe = api_create("NavLink", f"{RUN_ID}-unsafe-icon", {
        "categoryName": category["metadata"]["name"],
        "displayName": f"UI-UNSAFEICON-{TAG}", "url": f"https://unsafe-{TAG}.example.com",
        "icon": "<svg><script>alert(8)</script></svg>", "sort": 20, "visible": True})
    refresh()
    page.wait_for_timeout(500)
    row = row_containing(f"UI-UNSAFEICON-{TAG}")
    R.check("row preview drops script markup",
            "<script" not in row.locator(".pn-icon-tile").inner_html())
    row.locator(".entity-field-title").first.click()
    page.wait_for_timeout(600)
    form = modal("url")
    R.check("editor preview drops script markup",
            "<script" not in form.locator(".pn-icon-thumb").inner_html())
    form.locator(".modal-header-actions span").first.click()
    page.wait_for_timeout(400)

    # 5) Upload a generated PNG and keep the returned attachment path.
    form = open_link_editor()
    open_icon_menu(form)
    page.locator(".dropdown-item-wrapper:visible").filter(has_text="上传").last.click()
    page.wait_for_timeout(300)
    png = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8"
        "z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
    form.locator('input[type="file"].pn-icon-file').set_input_files({
        "name": f"icon-{TAG}.png", "mimeType": "image/png", "buffer": png})
    R.check("uploaded icon becomes an attachment path",
            wait_until(lambda: form.locator(".pn-icon-field-value").inner_text()
                       .startswith("/upload/"), timeout=20),
            form.locator(".pn-icon-field-value").inner_text())
    save_modal(form)
    page.wait_for_timeout(1000)
    R.check("uploaded icon persisted",
            ((api_get("NavLink", link["metadata"]["name"]) or {})
             .get("spec", {}).get("icon") or "").startswith("/upload/"))

    # 6) Pick an existing attachment from the Halo library.
    form = open_link_editor()
    open_icon_menu(form)
    page.locator(".dropdown-item-wrapper:visible").filter(has_text="从附件库选择").last.click()
    page.wait_for_timeout(2500)
    cards = page.locator(".ring-1.ring-gray-100:visible")
    if cards.count() > 0:
        cards.first.click()
        page.wait_for_timeout(400)
        page.locator(".modal-content:visible .modal-footer button:visible") \
            .filter(has_text="确定").last.click()
        page.wait_for_timeout(800)
        R.check("attachment picker fills the icon",
                form.locator(".pn-icon-field-value").inner_text().startswith("/upload/"),
                form.locator(".pn-icon-field-value").inner_text())
    else:
        R.check("attachment library has entries to pick", False, "no cards")
    form.locator(".modal-header-actions span").first.click()
    page.wait_for_timeout(400)

    # Clean up this category (deleting the links with it).
    api_delete("NavCategory", category["metadata"]["name"], cascade=True)
    created.append(("navlinks", link["metadata"]["name"]))
    created.append(("navlinks", unsafe["metadata"]["name"]))


def test_engines_ui() -> None:
    print("\n[search engines]")
    ui_tab("搜索引擎")
    display = f"UI-ENGINE-{TAG}"
    toolbar_button("新增搜索引擎").click()
    form = modal("urlTemplate")
    form.locator('input[name="alias"]').fill(f"ui{TAG}")
    form.locator('input[name="displayName"]').fill(display)
    form.locator('input[name="urlTemplate"]').fill(
        f"https://engine-{TAG}.example.com/s?q=")
    form.locator('input[name="mobileUrlTemplate"]').fill(
        f"https://m-engine-{TAG}.example.com/s?q=")
    save_modal(form)
    page.wait_for_timeout(1200)
    engine = find_entity("NavSearchEngine", display)
    R.check("create engine through the UI", engine is not None)
    if engine is None:
        return
    created.append(("navsearchengines", engine["metadata"]["name"]))
    R.check("mobile template saved",
            engine["spec"].get("mobileUrlTemplate", "").endswith("s?q="))

    row_containing(display).locator(".entity-field-title").first.click()
    form = modal("urlTemplate")
    form.locator('input[name="mobileUrlTemplate"]').fill("")
    save_modal(form)
    page.wait_for_timeout(1000)
    engine = api_get("NavSearchEngine", engine["metadata"]["name"]) or {}
    R.check("clearing the mobile template persists",
            not (engine.get("spec") or {}).get("mobileUrlTemplate"),
            str(engine.get("spec")))

    switch_in(row_containing(display)).click()
    page.wait_for_timeout(800)
    R.equal("engine switch hides it",
            (api_get("NavSearchEngine", engine["metadata"]["name"]) or {})
            .get("spec", {}).get("visible"), False)

    open_row_dropdown(row_containing(display), "删除")
    dialog_button("删除")
    R.check("engine delete removes it",
            wait_until(lambda: api_get("NavSearchEngine",
                                       engine["metadata"]["name"]) is None))


def test_quick_links_ui() -> None:
    print("\n[quick links]")
    ui_tab("快捷导航")
    display = f"UI-QUICK-{TAG}"
    toolbar_button("新增快捷导航").click()
    form = modal("displayName")
    form.locator('input[name="displayName"]').fill(display)
    form.locator('input[name="url"]').fill(f"/about-{TAG}")
    checkbox = form.locator('input[name="openInNewTab"]')
    if checkbox.is_checked():
        checkbox.click()
    save_modal(form)
    page.wait_for_timeout(1000)
    quick = find_entity("NavQuickLink", display)
    R.check("create quick link through the UI", quick is not None)
    if quick is None:
        return
    created.append(("navquicklinks", quick["metadata"]["name"]))
    R.equal("same-tab option persisted",
            quick["spec"].get("openInNewTab"), False)

    open_row_dropdown(row_containing(display), "删除")
    dialog_button("删除")
    R.check("quick link delete removes it",
            wait_until(lambda: api_get("NavQuickLink",
                                       quick["metadata"]["name"]) is None))


def test_settings_ui() -> None:
    print("\n[settings]")
    global settings_backup
    settings_backup = config()

    # The previous test left the browser on the public /nav page.
    page.goto(f"{BASE}/console/palette-nav", wait_until="networkidle")
    page.wait_for_timeout(1800)
    ui_tab("设置")
    page.wait_for_timeout(1200)
    brand = f"UI-BRAND-{TAG}"
    site = f"UI-SITE-{TAG}"
    page.locator('.pn-settings input[name="brandText"]').fill(brand)
    page.locator('.pn-settings input[name="siteName"]').fill(site)

    stats_field = page.locator(".pn-settings .formkit-outer",
                               has_text="启用访问统计").first
    stats_field.locator('button[role="switch"]').click()
    page.wait_for_timeout(300)

    page.locator(".pn-settings-footer").get_by_role("button", name="保存设置") \
        .first.click()
    page.wait_for_timeout(1600)
    saved = config()
    R.equal("settings save writes brandText", saved.get("general", {}).get("brandText"),
            brand)
    R.equal("settings save writes the stats switch",
            saved.get("general", {}).get("enableVisitStats"), False)

    nav = requests.get(f"{BASE}/nav", timeout=20)
    R.check("saved brand is visible on /nav", brand in nav.text)
    before = api_request("GET", f"{CONSOLE_API}/stats").json()
    requests.get(f"{BASE}/nav", headers={"User-Agent": f"pn-ui-settings-{TAG}"},
                 timeout=20)
    time.sleep(2.2)
    after = api_request("GET", f"{CONSOLE_API}/stats").json()
    R.equal("disabled statistics do not count console-side visits",
            after["todayPv"], before["todayPv"])

    # Unsaved-change guard.
    page.locator('.pn-settings input[name="brandText"]').fill("UNSAVED BRAND")
    page.locator(".pn-page .tabbar-item-label", has_text="分类").first.click()
    page.wait_for_timeout(400)
    guard = dialog_text()
    R.check("unsaved settings warn before leaving", "尚未保存" in guard or
            "放弃未保存的修改" in guard, guard[:160])
    dialog_button("放弃并切换")
    R.check("discarding leaves the settings tab",
            "分类" in page.locator(".tabbar-item-active").first.inner_text())
    # VTabs keeps the settings component mounted, so reload to drop the draft
    # and verify the persisted value is what a fresh visit shows.
    page.reload(wait_until="networkidle")
    page.wait_for_timeout(1600)
    ui_tab("设置")
    page.wait_for_timeout(900)
    R.equal("fresh load shows the stored value",
            page.locator('.pn-settings input[name="brandText"]').input_value(), brand)

    # The settings tab intentionally has no reset-to-defaults button; the host
    # "reset config" action is gone too because the plugin declares no Setting.
    R.equal("no reset-defaults button in the settings footer",
            page.locator(".pn-settings-footer").get_by_role(
                "button", name="恢复默认").count(), 0)

    # Restore the pre-test configuration.
    save_config(settings_backup)
    settings_backup = None
    page.reload(wait_until="networkidle")
    page.wait_for_timeout(1500)
    R.check("settings restored after the test",
            brand not in requests.get(f"{BASE}/nav", timeout=20).text)


def test_responsive() -> None:
    print("\n[responsive]")
    page.goto(f"{BASE}/console/palette-nav", wait_until="networkidle")
    page.wait_for_timeout(2000)
    page.set_viewport_size({"width": 390, "height": 844})
    page.wait_for_timeout(700)
    overflow = page.evaluate(
        "() => document.documentElement.scrollWidth - window.innerWidth")
    R.equal("console dashboard has no horizontal overflow", overflow, 0)
    page.goto(f"{BASE}/nav", wait_until="networkidle")
    page.wait_for_timeout(1200)
    overflow = page.evaluate(
        "() => document.documentElement.scrollWidth - window.innerWidth")
    R.equal("nav page has no horizontal overflow", overflow, 0)
    page.set_viewport_size({"width": 1440, "height": 1000})


def test_public_page_interactions() -> None:
    print("\n[public page]")
    page.goto(f"{BASE}/nav", wait_until="networkidle")
    page.wait_for_timeout(1200)
    R.check("nav page has the clock", page.locator("#clockTime").count() == 1)
    page.locator("#nightBtn").click()
    page.wait_for_timeout(300)
    R.check("night mode toggles",
            page.evaluate("() => document.documentElement.classList.contains('night-mode')"))
    page.locator("#nightBtn").click()
    page.locator("#paletteBtn").click()
    page.wait_for_timeout(300)
    page.locator('.color-opt[data-color="teal"]').click()
    page.wait_for_timeout(300)
    R.check("palette switch applies the theme",
            page.evaluate("() => document.documentElement.getAttribute('data-theme')")
            == "teal")


# --------------------------------------------------------------------------- #
# Lifecycle
# --------------------------------------------------------------------------- #

def cleanup() -> None:
    seen: set[tuple[str, str]] = set()
    for plural, name in reversed(created):
        if (plural, name) in seen:
            continue
        seen.add((plural, name))
        try:
            if plural == "navcategories":
                api_request("DELETE", f"{CONSOLE_API}/category/{name}")
            else:
                api_request("DELETE", f"{MODEL_API}/{plural}/{name}")
        except requests.RequestException:
            pass
    for item in api_list("NavCategory"):
        if "UITEST" in (item["spec"].get("displayName") or "").upper():
            api_request("DELETE", f"{CONSOLE_API}/category/{item['metadata']['name']}")
    for item in api_list("NavLink"):
        display = item["spec"].get("displayName") or ""
        if "UITEST" in display.upper() or "UI-LINK" in display.upper() \
                or "PAGE-LINK" in display.upper():
            api_request("DELETE", model_path("NavLink", item["metadata"]["name"]))
    for kind, prefix in (("NavSearchEngine", "UI-ENGINE"),
                         ("NavQuickLink", "UI-QUICK")):
        for item in api_list(kind):
            display = item["spec"].get("displayName") or ""
            if prefix in display.upper():
                api_request("DELETE", model_path(kind, item["metadata"]["name"]))
    if settings_backup is not None:
        try:
            save_config(settings_backup)
        except requests.RequestException:
            pass


def main() -> int:
    global page
    print(f"base={BASE} run={RUN_ID}")
    console_errors: list[str] = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1500, "height": 1000})
        page.on("pageerror", lambda error: console_errors.append(f"[pageerror] {error}"))
        page.on("console", lambda msg: console_errors.append(f"[{msg.type}] {msg.text}")
                if msg.type == "error" else None)

        try:
            page.goto(f"{BASE}/login", wait_until="networkidle")
            page.fill("#username", USER)
            page.fill("#plainPassword", PASSWORD)
            page.click("button[type=submit]")
            page.wait_for_load_state("networkidle")

            test_dashboard_loads()
            test_categories_ui()
            test_links_ui()
            test_icon_picker_ui()
            test_engines_ui()
            test_quick_links_ui()
            test_public_page_interactions()
            test_settings_ui()
            test_responsive()
        finally:
            browser.close()
            cleanup()

    # Ignore the console's own benign warnings; only real errors matter.
    # Test links point at unresolvable hosts; the browser logs those image
    # failures, which are unrelated to the plugin's own scripts.
    ignored = ("favicon", "ERR_NETWORK", "ERR_NAME_NOT_RESOLVED",
               "net::ERR_CONNECTION")
    significant = [error for error in console_errors
                   if not any(token in error for token in ignored)]
    R.check("no browser console errors", not significant,
            " | ".join(significant[:5]))

    print(f"\n{R.passed} passed, {R.failed} failed")
    if R.failures:
        print("Failures:")
        for failure in R.failures:
            print(f"  - {failure}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())