"""One-off: verify the settings save bar clears Halo's mobile bottom nav."""

from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "http://localhost:8095"
OUT = Path(".tools/halo/screenshots")
OUT.mkdir(parents=True, exist_ok=True)

errors = []


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": 390, "height": 844},
            device_scale_factor=2,
            is_mobile=True,
            has_touch=True,
        )
        page = context.new_page()
        page.on(
            "console",
            lambda msg: errors.append(f"[{msg.type}] {msg.text}")
            if msg.type == "error"
            else None,
        )

        page.goto(f"{BASE}/login", wait_until="networkidle")
        page.fill("#username", "admin")
        page.fill("#plainPassword", "admin123")
        page.click("button[type=submit]")
        page.wait_for_load_state("networkidle")

        page.goto(f"{BASE}/console/palette-nav", wait_until="networkidle")
        page.wait_for_timeout(2500)
        page.locator(".tabbar-item-label", has_text="设置").first.click()
        page.wait_for_timeout(2000)

        # Mid-scroll is where the sticky bar is pinned; also check the very end.
        page.evaluate("window.scrollTo(0, 900)")
        page.wait_for_timeout(500)
        page.screenshot(path=str(OUT / "settings-mobile-mid.png"))

        page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
        page.wait_for_timeout(600)
        page.screenshot(path=str(OUT / "settings-mobile.png"))

        nav = page.query_selector(".mobile-nav")
        footer = page.query_selector(".pn-settings-footer")
        print("mobile-nav visible:", bool(nav and nav.is_visible()))
        if nav:
            print("mobile-nav box:", nav.bounding_box())
        if footer:
            print("footer box:", footer.bounding_box())
            print(
                "footer z-index:",
                footer.evaluate("el => getComputedStyle(el).zIndex"),
                "bottom:",
                footer.evaluate("el => getComputedStyle(el).bottom"),
            )
        print("console errors:", errors[:10])
        browser.close()


if __name__ == "__main__":
    main()