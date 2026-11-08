/**
 * Shared media-kind helpers. Mirrors `service/NavMediaType.java`: video files are
 * recognised by extension, everything else is treated as an image.
 */
export type MediaKind = "image" | "video";

const VIDEO_EXTENSIONS = new Set(["mp4", "webm", "ogg", "ogv", "mov", "m4v"]);
const IMAGE_EXTENSIONS = new Set([
  "jpg",
  "jpeg",
  "png",
  "gif",
  "webp",
  "avif",
  "svg",
  "bmp",
  "ico",
]);

export function isMediaKind(value: unknown): value is MediaKind {
  return value === "image" || value === "video";
}

/** Returns undefined when the URL has no recognisable media extension. */
export function detectMediaKindFromUrl(url?: string | null): MediaKind | undefined {
  let path = (url ?? "").trim();
  if (!path) {
    return undefined;
  }
  const query = path.indexOf("?");
  if (query >= 0) {
    path = path.slice(0, query);
  }
  const fragment = path.indexOf("#");
  if (fragment >= 0) {
    path = path.slice(0, fragment);
  }
  const dot = path.lastIndexOf(".");
  if (dot < 0 || dot === path.length - 1) {
    return undefined;
  }
  const extension = path.slice(dot + 1).toLowerCase();
  if (VIDEO_EXTENSIONS.has(extension)) {
    return "video";
  }
  if (IMAGE_EXTENSIONS.has(extension)) {
    return "image";
  }
  return undefined;
}

export function mediaKindFromUrl(url?: string | null): MediaKind {
  return detectMediaKindFromUrl(url) ?? "image";
}

export function mediaKindFromMime(mime?: string | null): MediaKind | undefined {
  const value = (mime ?? "").trim().toLowerCase();
  if (!value) {
    return undefined;
  }
  if (value.startsWith("video/")) {
    return "video";
  }
  if (value.startsWith("image/")) {
    return "image";
  }
  return undefined;
}