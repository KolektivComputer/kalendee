import type { CollectionEntry } from "astro:content";

import { docs } from "./docs-chrome";

/** Collection id ("path/to/file.md") -> URL slug ("path/to/file"). */
export function slugFromId(id: string): string {
  return id.replace(/\.(md|mdx)$/i, "");
}

/** URL path for a content-collection slug, with a trailing slash. */
export function docsPath(slug: string): string {
  return `/docs/${slug}/`;
}

/**
 * Sidebar / nav href for a slug. Matches the hrefs declared in `docs-chrome.ts`
 * (`/docs/product/getting-started`) so the package sidebar can highlight it.
 */
export function navHref(slug: string): string {
  return `/docs/${slug}`;
}

function normalizeHref(href: string): string {
  const trimmed = href.replace(/\/+$/, "");
  return trimmed === "" ? "/" : trimmed;
}

// Order entries by their position in the docs-chrome nav, so the /docs/ index
// mirrors the sidebar. Pages outside the nav sort last, alphabetically.
const NAV_ORDER = new Map<string, number>();
{
  let index = 0;
  for (const section of docs.nav) {
    for (const item of section.items) {
      if (item.external) continue;
      NAV_ORDER.set(normalizeHref(item.href), index++);
    }
  }
}

export function navOrder(slug: string): number {
  return NAV_ORDER.get(normalizeHref(navHref(slug))) ?? Number.POSITIVE_INFINITY;
}

/** Sidebar / index order: listed nav first, then remaining pages A-Z. */
export function sortDocs(entries: CollectionEntry<"docs">[]): CollectionEntry<"docs">[] {
  return [...entries].sort((a, b) => {
    const order = navOrder(slugFromId(a.id)) - navOrder(slugFromId(b.id));
    if (order !== 0) return order;
    return a.data.title.localeCompare(b.data.title);
  });
}

/** GitHub source URL for this repository. */
export const REPO_URL = docs.repo?.url ?? "https://github.com/KolektivComputer/kalendee";

/** GitHub source URL for a rendered page, usable in "edit this page" links. */
export function editUrl(id: string): string {
  const base = docs.repo?.editBaseUrl ?? `${REPO_URL}/edit/main/docs/src/content/docs`;
  return `${base}/${id}`;
}
