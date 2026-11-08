from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "http://localhost:8095"
OUT = Path(".tools/halo/screenshots")
OUT.mkdir(parents=True, exist_ok=True)

console_errors = []


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1000})
        page.on("console", lambda msg: console_errors.append(f"[{msg.type}] {msg.text}")
                if msg.type == "error" else None)
        page.on("pageerror", lambda error: console_errors.append(f"[pageerror] {error}"))

        page.goto(f"{BASE}/login", wait_until="networkidle")
        page.fill("#username", "admin")
        page.fill("#plainPassword", "admin123")
        page.click("button[type=submit]")
        page.wait_for_load_state("networkidle")
        print("after login:", page.url)

        page.goto(f"{BASE}/console/palette-nav", wait_until="networkidle")
        page.wait_for_timeout(3000)
        print("console url:", page.url)
        print("console title:", page.title())
        body = page.locator("body").inner_text()
        for keyword in ["Palette Nav", "分类", "链接", "搜索引擎", "快捷导航", "设置"]:
            print(f"  console contains {keyword}: {keyword in body}")
        page.screenshot(path=str(OUT / "console.png"), full_page=True)

        page.goto(f"{BASE}/nav", wait_until="networkidle")
        page.wait_for_timeout(1500)
        info = page.evaluate(
            """() => ({
              title: document.title,
              cards: document.querySelectorAll('.link-card').length,
              categories: document.querySelectorAll('.category-item').length,
              engines: document.querySelectorAll('.engine-option').length,
              groups: document.querySelectorAll('.link-group').length,
              clock: document.querySelector('#clockTime')?.textContent,
              yiyan: document.querySelector('.yiyan-text')?.textContent,
              bodyWidth: document.body.scrollWidth,
            })"""
        )
        print("nav info:", info)
        page.screenshot(path=str(OUT / "nav-desktop.png"), full_page=True)

        # Category filter
        page.locator(".category-item").nth(1).click()
        page.wait_for_timeout(300)
        visible_groups = page.evaluate(
            "() => [...document.querySelectorAll('.link-group')]"
            ".filter(el => el.style.display !== 'none').length"
        )
        print("visible groups after category click:", visible_groups)

        # Palette + night mode
        page.locator("#paletteBtn").click()
        page.locator('.color-opt[data-color="teal"]').click()
        page.wait_for_timeout(200)
        theme = page.evaluate("() => document.documentElement.getAttribute('data-theme')")
        page.locator("#nightBtn").click()
        page.wait_for_timeout(200)
        night = page.evaluate("() => document.documentElement.classList.contains('night-mode')")
        print("theme after click:", theme, "night:", night)
        page.screenshot(path=str(OUT / "nav-teal-night.png"), full_page=True)

        # Mobile
        mobile = browser.new_page(viewport={"width": 390, "height": 844})
        mobile.goto(f"{BASE}/nav", wait_until="networkidle")
        mobile.wait_for_timeout(1000)
        mobile.screenshot(path=str(OUT / "nav-mobile.png"), full_page=True)
        print("mobile cards:", mobile.locator(".link-card").count())

        browser.close()

    print("console errors:", len(console_errors))
    for error in console_errors[:20]:
        print(" ", error)


if __name__ == "__main__":
    main()
