"""Dashboard smoke check for /console/palette-nav.

Requires the 8095 dev instance with the palette-nav plugin STARTED.
"""

from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "http://localhost:8095"
OUT = Path(".tools/halo/screenshots")
OUT.mkdir(parents=True, exist_ok=True)

console_errors: list[str] = []


def login(page):
    page.goto(f"{BASE}/login", wait_until="networkidle")
    page.fill("#username", "admin")
    page.fill("#plainPassword", "admin123")
    page.click("button[type=submit]")
    page.wait_for_load_state("networkidle")


def measure(page):
    return page.evaluate(
        """() => {
          const q = (s) => document.querySelector(s);
          const cs = (el, p) => (el ? getComputedStyle(el)[p] : null);
          const pageEl = q('.pn-page');
          const dashboard = q('.pn-dashboard');
          const stats = q('.pn-dashboard-stats');
          const card = q('.pn-stat-card');
          const chart = q('.pn-dashboard-chart');
          const refresh = q('.pn-dashboard .pn-refresh');
          const indicator = q('.tabbar-wrapper .indicator');
          const item = q('.tabbar-wrapper.tabbar-outline .tabbar-item');
          const items = q('.tabbar-wrapper .tabbar-items');
          const cardWrapper = q('.card-wrapper');
          const box = (el) =>
            el
              ? [Math.round(el.getBoundingClientRect().width), Math.round(el.getBoundingClientRect().height)]
              : null;
          // The phone gutter lives on the tab strip and the panel wrapper, not on
          // `.pn-page` itself, so measure how far the dashboard is inset from it
          // instead of reading `.pn-page`'s padding (which is 0 at every width).
          const pageGutter = dashboard && pageEl
            ? Math.round(dashboard.getBoundingClientRect().left
                - pageEl.getBoundingClientRect().left)
            : null;
          return {
            overflow: document.documentElement.scrollWidth - innerWidth,
            pageGutter,
            statsCols: stats ? getComputedStyle(stats).gridTemplateColumns.split(' ').length : 0,
            cardBox: box(card),
            chartHeight: cs(chart, 'height'),
            refreshBox: box(refresh),
            indicatorWidth: indicator ? Math.round(indicator.getBoundingClientRect().width) : null,
            itemPadLeft: cs(item, 'paddingLeft'),
            tabs: items ? [items.scrollWidth, items.clientWidth] : null,
            cardScroll: cardWrapper ? [cardWrapper.scrollWidth, cardWrapper.clientWidth] : null,
          };
        }"""
    )


def assert_no_overflow(page, label):
    overflow = page.evaluate("() => document.documentElement.scrollWidth - window.innerWidth")
    assert overflow <= 0, f"{label}: horizontal overflow {overflow}px"
    print(f"{label}: overflow {overflow}px")


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(
            viewport={"width": 1440, "height": 1000},
            device_scale_factor=2,
        )
        page = context.new_page()
        page.on(
            "console",
            lambda msg: console_errors.append(f"[{msg.type}] {msg.text}")
            if msg.type == "error"
            else None,
        )
        page.on("pageerror", lambda error: console_errors.append(f"[pageerror] {error}"))

        login(page)
        page.goto(f"{BASE}/console/palette-nav", wait_until="networkidle")
        page.wait_for_timeout(2500)

        info = page.evaluate(
            """() => ({
              activeTab: document.querySelector('.tabbar-item-active')?.textContent?.trim(),
              cards: [...document.querySelectorAll('.pn-stat-card')].map(el => ({
                label: el.querySelector('.pn-stat-card-label')?.textContent?.trim(),
                value: el.querySelector('.pn-stat-card-value')?.textContent?.trim(),
              })),
              charts: [...document.querySelectorAll('.pn-dashboard-chart canvas')].length,
              alerts: [...document.querySelectorAll('.pn-dashboard .alert')].length,
            })"""
        )
        print("dashboard info:", info)
        page.screenshot(path=str(OUT / "dashboard-desktop.png"), full_page=True)

        # Refresh keeps the counters visible.
        page.locator(".pn-refresh").first.click()
        page.wait_for_timeout(1500)
        print("after refresh cards:", page.locator(".pn-stat-card").count())

        # Mobile layout: page gutter, compact 2x2 cards, shorter charts, touch
        # targets and narrowed tab indicators.
        expected = [
            (320, 700, 16, 2, "240px", 36, 40, "12px"),
            (360, 780, 16, 2, "240px", 36, 40, "12px"),
            (390, 844, 16, 2, "240px", 36, 40, "12px"),
            (640, 900, 16, 2, "260px", 36, 40, "12px"),
            (767, 900, 16, 2, "260px", 36, 40, "12px"),
            (768, 1000, 0, 2, "300px", 24, 40, "12px"),
            (844, 390, 0, 2, "300px", 24, 40, "12px"),
            (1024, 900, 0, 2, "300px", 24, 80, "24px"),
            (1440, 1000, 0, 4, "300px", 24, 80, "24px"),
        ]
        for width, height, pad, cols, chart_h, refresh, indicator, item_pad in expected:
            page.set_viewport_size({"width": width, "height": height})
            page.wait_for_timeout(700)
            m = measure(page)
            print(f"{width}x{height}:", m)
            assert m["overflow"] <= 0, f"{width}: horizontal overflow {m['overflow']}px"
            assert m["pageGutter"] == pad, f"{width}: page gutter {m['pageGutter']} != {pad}"
            assert m["statsCols"] == cols, f"{width}: stat columns {m['statsCols']} != {cols}"
            assert m["chartHeight"] == chart_h, f"{width}: chart height {m['chartHeight']} != {chart_h}"
            assert m["refreshBox"] == [refresh, refresh], f"{width}: refresh box {m['refreshBox']}"
            assert m["indicatorWidth"] == indicator, f"{width}: indicator {m['indicatorWidth']}"
            assert m["itemPadLeft"] == item_pad, f"{width}: tab padding {m['itemPadLeft']}"
            if width in (320, 390, 768):
                page.screenshot(path=str(OUT / f"dashboard-{width}.png"), full_page=True)

        # Narrow -> wide -> narrow flips rebuild the ECharts options without
        # leaking canvases or throwing.
        page.set_viewport_size({"width": 390, "height": 844})
        page.wait_for_timeout(700)
        page.set_viewport_size({"width": 1440, "height": 1000})
        page.wait_for_timeout(700)
        page.set_viewport_size({"width": 390, "height": 844})
        page.wait_for_timeout(700)
        print("charts after resize round-trip:", page.locator(".pn-dashboard-chart canvas").count())
        assert page.locator(".pn-dashboard-chart canvas").count() == 2

        # Tooltips must stay inside the chart box (VCard's body clips overflow).
        chart = page.locator(".pn-dashboard-chart").first
        box = chart.bounding_box()
        page.mouse.move(box["x"] + box["width"] * 0.8, box["y"] + box["height"] * 0.5)
        page.wait_for_timeout(500)
        tips = page.evaluate(
            """() => {
              const chart = document.querySelector('.pn-dashboard-chart');
              const cr = chart.getBoundingClientRect();
              return [...chart.querySelectorAll('div')]
                .filter(el => el.style.position === 'absolute' && el.textContent.trim()
                  && el.getBoundingClientRect().width > 0)
                .map(el => {
                  const r = el.getBoundingClientRect();
                  return {
                    text: el.textContent.trim().slice(0, 30),
                    inside: r.x >= cr.x - 1 && r.y >= cr.y - 1
                      && r.right <= cr.right + 1 && r.bottom <= cr.bottom + 1,
                  };
                });
            }"""
        )
        print("tooltips:", tips)
        assert tips and all(t["inside"] for t in tips), "tooltip escaped the chart box"

        # Every tab stays within the viewport on a phone.
        page.set_viewport_size({"width": 390, "height": 844})
        page.wait_for_timeout(500)
        tabs = page.locator(".tabs-bar-wrapper").first.locator(".tabbar-item")
        for index in range(tabs.count()):
            tabs.nth(index).click()
            page.wait_for_timeout(600)
            assert_no_overflow(page, f"tab {index}")

        # Back to the dashboard: charts survive a tab round-trip.
        page.set_viewport_size({"width": 1440, "height": 1000})
        tabs.nth(0).click()
        page.wait_for_timeout(2500)
        print("charts after tab round-trip:", page.locator(".pn-dashboard-chart canvas").count())
        assert page.locator(".pn-dashboard-chart canvas").count() == 2

        print("console errors:", console_errors or "none")
        assert not console_errors, f"console errors: {console_errors[:5]}"
        browser.close()


if __name__ == "__main__":
    main()