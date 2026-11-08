from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "http://localhost:8095"
OUT = Path(".tools/halo/screenshots")
OUT.mkdir(parents=True, exist_ok=True)

console_errors = []


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1600, "height": 1000})
        page.on("console", lambda msg: console_errors.append(f"[{msg.type}] {msg.text}")
                if msg.type == "error" else None)
        page.on("pageerror", lambda error: console_errors.append(f"[pageerror] {error}"))

        page.goto(f"{BASE}/login", wait_until="networkidle")
        page.fill("#username", "admin")
        page.fill("#plainPassword", "admin123")
        page.click("button[type=submit]")
        page.wait_for_load_state("networkidle")

        page.goto(f"{BASE}/console/palette-nav", wait_until="networkidle")
        page.wait_for_timeout(3000)

        page.locator(".tabbar-item-label", has_text="链接").click()
        page.wait_for_timeout(800)
        page.get_by_role("button", name="新增链接").click()
        page.wait_for_timeout(800)
        page.screenshot(path=str(OUT / "icon-field.png"), full_page=True)
        edit_body = page.locator(".modal-content").last.inner_text()
        print("has 描述:", "描述" in edit_body, "has 关键词:", "关键词" in edit_body)

        trigger = page.locator(".pn-icon-trigger")
        print("icon trigger count:", trigger.count())
        if trigger.count() == 0:
            print("old UI still served")
            browser.close()
            return
        trigger.first.click()
        page.wait_for_timeout(300)
        print(
            "menu items:",
            page.locator(".dropdown-item-wrapper").all_inner_texts(),
        )

        page.locator(".dropdown-item-wrapper", has_text="输入链接").first.click()
        page.wait_for_timeout(800)
        page.screenshot(path=str(OUT / "input-link.png"), full_page=True)
        print("link input:", page.locator(".pn-link-popover input").count())
        page.locator(".pn-link-popover input").first.fill("https://example.com/icon.png")
        page.locator(".pn-link-popover").get_by_role("button", name="保存").click()
        page.wait_for_timeout(600)
        print("icon value label:", page.locator(".pn-icon-field-value").inner_text())

        page.locator(".modal-header-title", has_text="新增链接").click()
        page.wait_for_timeout(300)
        trigger.first.click()
        page.wait_for_timeout(300)
        page.get_by_text("从附件库选择", exact=True).click()
        page.wait_for_timeout(2500)
        page.screenshot(path=str(OUT / "attachment-selector.png"), full_page=True)

        print("modal title:", page.locator(".modal-header-title").last.inner_text()
              if page.locator(".modal-header-title").count() else "none")
        body = page.locator("body").inner_text()
        for keyword in ["附件库", "存储策略：", "分组：", "共 3 项数据"]:
            print(f"selector contains {keyword}: {keyword in body}")
        print("official grid cards:", page.locator(".ring-1.ring-gray-100").count())
        print("legacy grid cards:", page.locator(".pn-attach-card").count())

        page.locator(".ring-1.ring-gray-100").first.click()
        page.wait_for_timeout(300)
        page.locator(".modal-footer").last.get_by_role("button", name="确定").click()
        page.wait_for_timeout(800)
        print("icon value after select:", page.locator(".pn-icon-field-value").inner_text())

        for err in console_errors[:20]:
            print("console:", err)
        browser.close()


if __name__ == "__main__":
    main()
