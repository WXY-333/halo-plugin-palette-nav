import { CONTEXT_PATH } from "@/api/nav";

const SPRITE_SVG =
  /^\s*<svg\b[^>]*>\s*<use\b[^>]*(?:xlink:href|href)\s*=\s*["']#([A-Za-z0-9_-]+)["'][^>]*>\s*(?:<\/use\s*>)?<\/svg>\s*$/i;
const UNSAFE = /<script\b|<\/script\s*>|on[a-z]+\s*=|javascript:/i;
/** Ids from the iconfont sprite that older releases bundled. */
const LEGACY_SPRITE_ID = /^(?:lyicon|icon)-[A-Za-z0-9_-]+$/;
/**
 * Legacy seeded category ids upgraded to the matching coloured category logo.
 * A `Map` (not a plain object) is required: `id` is user data, and an object
 * lookup would return inherited prototype members for `#constructor` or
 * `#__proto__` instead of a string.
 */
const LEGACY_SPRITE_MAP = new Map<string, string>([
  ["icon-remen", "pn-cat-common"],
  ["icon-sheji", "pn-cat-design"],
  ["icon-msg", "pn-cat-cloud"],
  ["icon-ai-tool", "pn-cat-tools"],
  ["icon-kongzhitai", "pn-cat-dev"],
  ["icon-game00", "pn-cat-games"],
]);

let spritePromise: Promise<void> | null = null;

export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/**
 * `java.net.URI` (the server-side {@code NavUrlValidator}) rejects these ASCII
 * characters in any component, while the WHATWG `URL` parser silently
 * percent-encodes them. Accepting a value here that the server later rejects
 * means the link is saved from the console but hidden on /nav.
 */
const ILLEGAL_URI_CHARS = /[\u0000-\u0020\u007f<>"\\^`{|}]/;
/** Mirrors `NavUrlValidator`'s control-character guard for site-relative paths. */
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;
/** `java.net.URI` also rejects a bare or truncated percent escape. */
const ILLEGAL_PERCENT_ESCAPE = /%(?![0-9a-fA-F]{2})/;
const NON_ASCII = /[^\x00-\x7f]/;
const HOST_LABEL = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i;

function rawAuthority(candidate: string): string | null {
  const match = /^[a-z][a-z0-9+.-]*:\/\/([^/?#]*)/i.exec(candidate);
  return match?.[1] ?? null;
}

/**
 * Mirrors the host rules of `java.net.URI#getHost()`: non-ASCII, underscore and
 * empty labels yield a null host on the server even though WHATWG punycodes
 * them, so those values must be rejected here as well.
 */
function isValidHost(authority: string): boolean {
  const hostPort = authority.slice(authority.lastIndexOf("@") + 1);
  if (hostPort.startsWith("[")) {
    return hostPort.includes("]");
  }
  const portIndex = hostPort.lastIndexOf(":");
  const host = portIndex >= 0 ? hostPort.slice(0, portIndex) : hostPort;
  if (!host || NON_ASCII.test(host)) {
    return false;
  }
  const labels = (host.endsWith(".") ? host.slice(0, -1) : host).split(".");
  return labels.every((label) => HOST_LABEL.test(label));
}

export function isSafeHttpUrl(value?: string | null): boolean {
  if (!value || !value.trim()) {
    return false;
  }
  const candidate = value.trim();
  if (ILLEGAL_URI_CHARS.test(candidate) || ILLEGAL_PERCENT_ESCAPE.test(candidate)) {
    return false;
  }
  const authority = rawAuthority(candidate);
  if (!authority || !isValidHost(authority)) {
    return false;
  }
  // Brackets are legal in the authority (IPv6) and in the query/fragment, but
  // not in the path.
  const path = /^[a-z][a-z0-9+.-]*:\/\/[^/?#]*(\/[^?#]*)?/i.exec(candidate)?.[1] ?? "";
  if (/[\[\]]/.test(path)) {
    return false;
  }
  try {
    const url = new URL(candidate);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

export function isSafeLinkUrl(value?: string | null): boolean {
  if (!value || !value.trim()) {
    return false;
  }
  const candidate = value.trim();
  if (candidate === "#") {
    return true;
  }
  if (candidate.startsWith("/")) {
    // Mirrors NavUrlValidator: a backslash is normalized to "/" by browsers, so
    // "/\evil.example.com" would be read as "//evil.example.com".
    return (
      !candidate.startsWith("//") &&
      !candidate.includes("\\") &&
      !CONTROL_CHARS.test(candidate)
    );
  }
  return isSafeHttpUrl(candidate);
}

/**
 * Image and background sources accept the same http(s)/site-relative forms as
 * links but not the in-page anchor `#`, which is never a real media resource.
 * Mirrors `NavUrlValidator.isSafeImageUrl`.
 */
export function isSafeImageUrl(value?: string | null): boolean {
  if (!value || !value.trim()) {
    return false;
  }
  const candidate = value.trim();
  if (CONTROL_CHARS.test(candidate)) {
    return false;
  }
  if (candidate.startsWith("/")) {
    // See isSafeLinkUrl: "//" and "\" both escape the site origin.
    return !candidate.startsWith("//") && !candidate.includes("\\");
  }
  return isSafeHttpUrl(candidate);
}

/**
 * A sprite reference is the only inline SVG shape rendered as markup. Any other
 * SVG source is embedded as a data URL image so it can never execute scripts.
 */
export function isSpriteIcon(icon?: string | null): boolean {
  const value = (icon ?? "").trim();
  return !!value && SPRITE_SVG.test(value);
}

export function isUnsafeIconMarkup(icon?: string | null): boolean {
  return UNSAFE.test((icon ?? "").trim());
}

/**
 * Maps an icon id from the removed iconfont sprite to the generic link glyph that
 * the new palette sprite provides, so existing data still previews in the console.
 * Mirrors `NavIconSpriteService`'s legacy fallback on the server.
 */
export function resolveSpriteId(id: string): string {
  return LEGACY_SPRITE_MAP.get(id) ?? (LEGACY_SPRITE_ID.test(id) ? "pn-link" : id);
}

function svgDataUrl(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return `data:image/svg+xml;base64,${btoa(binary)}`;
}

/**
 * Mirrors the server-side icon allow-list. The result is safe for v-html.
 */
export function sanitizeIconHtml(icon?: string | null): string {
  const value = (icon ?? "").trim();
  if (!value) {
    return "";
  }
  if (value.toLowerCase().startsWith("<svg")) {
    if (UNSAFE.test(value) || !value.toLowerCase().includes("</svg")) {
      return "";
    }
    const match = value.match(SPRITE_SVG);
    if (match) {
      const id = resolveSpriteId(match[1] ?? "");
      if (!id) {
        return "";
      }
      return `<svg class="icon" aria-hidden="true"><use href="#${id}" xlink:href="#${id}"></use></svg>`;
    }
    return `<img src="${svgDataUrl(value)}" alt="" loading="lazy" decoding="async">`;
  }
  if (isSafeImageUrl(value)) {
    return `<img src="${escapeHtml(value)}" alt="" loading="lazy" decoding="async">`;
  }
  const codePoints = Array.from(value).length;
  if (!value.includes("<") && !value.includes(">") && codePoints <= 16) {
    return `<span class="icon-text">${escapeHtml(value)}</span>`;
  }
  return "";
}

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = src;
    script.async = false;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`Failed to load ${src}`));
    document.head.appendChild(script);
  });
}

/**
 * Loads the bundled palette sprite once so admin previews can render
 * <use href="#pn-*"> icons. Legacy iconfont ids are resolved to `pn-link` by
 * {@link resolveSpriteId}.
 */
export function ensureIconSprite(): Promise<void> {
  if (!spritePromise) {
    const base = `${CONTEXT_PATH}/plugins/palette-nav/assets/nav/vendor`;
    spritePromise = loadScript(`${base}/palette-icons.js`).catch(() => {
      spritePromise = null;
    });
  }
  return spritePromise;
}

export const DEFAULT_ICON_SVG =
  '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M10 13a5 5 0 0 0 7.07 0l2.83-2.83a5 5 0 0 0-7.07-7.07L11 5"/><path d="M14 11a5 5 0 0 0-7.07 0L4.1 13.83a5 5 0 0 0 7.07 7.07L13 19"/></svg>';
