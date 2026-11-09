#!/usr/bin/env python3
"""Replace the bundled brand icons in nav-defaults.json with the palette sprite.

Older releases shipped an iconfont sprite and 82 inline brand SVGs with no recorded
license. The project now bundles only original line icons (palette-icons.js), so the
default navigation data references those generic glyphs instead.

Run from the repository root:

    python tools/replace-default-icons.py

The script is idempotent; re-running it keeps every icon on the same generic glyph.
"""

from __future__ import annotations

import json
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
DEFAULTS = ROOT / "src" / "main" / "resources" / "data" / "nav-defaults.json"

CATEGORY_LOGOS = {
    "cat-common": "pn-cat-common",
    "cat-design": "pn-cat-design",
    "cat-social-storage": "pn-cat-cloud",
    "cat-tools": "pn-cat-tools",
    "cat-dev": "pn-cat-dev",
    "cat-games": "pn-cat-games",
}

# Links without a more specific domain rule keep a monochrome line glyph instead of
# repeating the category's coloured badge.
CATEGORY_LINK_ICONS = {
    "cat-common": "pn-common",
    "cat-design": "pn-design",
    "cat-social-storage": "pn-cloud",
    "cat-tools": "pn-tools",
    "cat-dev": "pn-dev",
    "cat-games": "pn-game",
}

# Ordered from the most specific host to the fallback. A link matches when its
# hostname ends with the listed domain.
DOMAIN_RULES: list[tuple[str, tuple[str, ...]]] = [
    ("pn-mail", (
        "mail.qq.com", "mail.google.com", "outlook.live.com", "mail.163.com",
        "mail.sina.com.cn", "mail.aliyun.com",
    )),
    ("pn-music", ("kugou.com", "y.qq.com", "music.163.com")),
    ("pn-video", (
        "v.qq.com", "egame.qq.com", "douyu.com", "huya.com", "kuaishou.com",
        "douyin.com", "tv.cctv.com", "bilibili.com", "iqiyi.com", "youku.com",
    )),
    ("pn-cloud", (
        "aliyundrive.com", "pan.baidu.com", "lanzou.com", "pan.xunlei.com",
        "onedrive.live.com", "cloud.189.cn", "yun.cn", "cloud.tencent.com",
        "aliyun.com",
    )),
    ("pn-message", (
        "tieba.baidu.com", "weibo.com", "zhihu.com", "douban.com", "jianshu.com",
    )),
    ("pn-shopping", ("taobao.com",)),
    ("pn-search", ("baidu.com",)),
    ("pn-game", (
        "4399.com", "7k7k.com", "lol.qq.com", "yjwujian.cn", "steampowered.com",
        "pvp.qq.com", "3dmgame.com",
    )),
    ("pn-design", (
        "officeplus.cn", "gaoding.com", "sucaitianxia.net", "588ku.com", "818ps.com",
        "zcool.com.cn", "iconfont.cn", "iconfinder.com", "uiiiuiii.com",
        "photopea.com",
    )),
    ("pn-tools", (
        "tool.lu", "ip.cn", "xpdf.net", "translate.google.cn", "fanyi.youdao.com",
        "wepe.com.cn", "chinaz.com", "runoob.com",
    )),
    ("pn-dev", (
        "github.com", "gitee.com", "csdn.net", "cdnjs.com", "cdn.bytedance.com",
        "fontawesome.com", "itellyou.cn", "w3school.com.cn", "tool.oschina.net",
    )),
]


def sprite(icon_id: str) -> str:
    return (
        f'<svg class="icon" aria-hidden="true">'
        f'<use xlink:href="#{icon_id}"></use></svg>'
    )


def icon_for_link(url: str, fallback: str) -> str:
    host = (urlparse(url).hostname or "").lower()
    for icon_id, domains in DOMAIN_RULES:
        if any(host == domain or host.endswith("." + domain) for domain in domains):
            return sprite(icon_id)
    return sprite(fallback)


def main() -> None:
    data = json.loads(DEFAULTS.read_text(encoding="utf-8"))
    replaced = 0

    for category in data.get("categories", []):
        category_logo = CATEGORY_LOGOS.get(category["name"], "pn-cat-common")
        category["icon"] = sprite(category_logo)
        replaced += 1
        link_fallback = CATEGORY_LINK_ICONS.get(category["name"], "pn-link")
        for link in category.get("links", []):
            link["icon"] = icon_for_link(link["url"], link_fallback)
            replaced += 1

    for engine in data.get("searchEngines", []):
        engine["icon"] = sprite("pn-search")
        replaced += 1

    for quick_link in data.get("quickLinks", []):
        quick_link["icon"] = sprite("pn-code")
        replaced += 1

    DEFAULTS.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"updated {replaced} icon fields in {DEFAULTS.relative_to(ROOT)}")


if __name__ == "__main__":
    main()