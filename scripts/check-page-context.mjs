#!/usr/bin/env node
// Guard against the v0.2.0 `/login` crash class: a page component that uses
// `ctx` in its template but never binds the `page<...>()` return value, so the
// rendered page throws at runtime. `pnpm typecheck` only runs tsc over `.ts`
// files, and `vite build` happily bundles the broken component, so this check
// walks every `+page.svelte` template itself.
//
// Run from anywhere: `node scripts/check-page-context.mjs`.

import { readdirSync, readFileSync, statSync } from "node:fs"
import { dirname, join, relative, resolve } from "node:path"
import { fileURLToPath } from "node:url"

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..")
const pagesRoot = join(repoRoot, "server/pack/src/pages")

const templateUsesCtx = /(?<![\w$.])ctx\b/
const scriptBindsCtx = /^\s*(?:const|let|var)\s+ctx\s*(?::[^=\n]+)?=/m

function findPages(dir) {
  const pages = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) pages.push(...findPages(path))
    else if (entry.name === "+page.svelte") pages.push(path)
  }
  return pages
}

if (!statSync(pagesRoot, { throwIfNoEntry: false })?.isDirectory()) {
  console.error(`page-context guard: no pages directory at ${relative(repoRoot, pagesRoot)}`)
  process.exit(1)
}

const offenders = []
const pages = findPages(pagesRoot)

for (const file of pages) {
  const source = readFileSync(file, "utf8")
  const scriptEnd = source.lastIndexOf("</script>")
  const script = scriptEnd === -1 ? "" : source.slice(0, scriptEnd)
  const template = scriptEnd === -1 ? source : source.slice(scriptEnd + "</script>".length)
  if (!templateUsesCtx.test(template)) continue
  if (!scriptBindsCtx.test(script)) offenders.push(relative(repoRoot, file))
}

if (offenders.length > 0) {
  console.error("page-context guard failed:")
  for (const file of offenders) {
    console.error(`${file}: template uses ctx but the script does not bind the page() return value`)
  }
  process.exit(1)
}

console.log(`page-context guard: ${pages.length} page components checked; every ctx reference is bound`)
