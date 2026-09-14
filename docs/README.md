# Kalendee docs site

The documentation and marketing site for Kalendee. It is an [Astro 5](https://astro.build)
static site: the landing page lives in `src/pages/index.astro` and the guides are Markdown
files in `src/content/docs/`. The published site is <https://kalendee-docs.pages.dev>;
repo-wide setup and server commands are in [DEVELOPING.md](../DEVELOPING.md).

The chrome (navbar, sidebar, footer, theme picker, "Built by Kolektiv Computing" mark,
Tailwind 4 + daisyUI 5 setup) comes from the shared
[`@kolektiv/common-docs-chrome`](https://github.com/KolektivComputer/common-docs-chrome)
package. Site-specific configuration lives in `src/docs-chrome.ts`; nothing in this repo
should re-declare daisyUI or import `@kolektiv/themes` directly.

The site builds to plain static files in `dist/`, so it is portable. Cloudflare Pages is the
default target (see `.github/workflows/docs.yml`), but the same `dist/` can be dropped on
GitHub Pages, S3, or any static host.

## Local development

Requires Node 22+ and pnpm 11.25.0 (matching `server/pack`).

```bash
pnpm install
pnpm run dev      # http://localhost:4321
```

Other scripts:

```bash
pnpm run build    # writes static output to dist/
pnpm run preview  # serves the built dist/ locally
pnpm run check    # astro check (types + content schema)
```

Do not commit `node_modules/`, `dist/`, `.astro/`, or `.wrangler/`; `docs/.gitignore`
covers them. Screenshots are the exception: they live in `public/screenshots/` and are
served at `/screenshots/` (the screenshot grid in `src/pages/index.astro` references them
there).

## Adding a docs page

Guides are files under `src/content/docs/`. The file path becomes the URL:

```
src/content/docs/product/external-calendars.md   ->   /docs/product/external-calendars/
```

Each file needs frontmatter:

```md
---
title: Configuration
description: Optional one-line summary shown in the docs index and meta tags.
---

Body content.
```

The `docs` collection is defined in `src/content.config.ts` with a glob loader and this
schema (`title` required, `description` optional). The page title is rendered by
`DocsLayout`, so do not repeat it as an `# H1` in the Markdown body.

To show the page in the sidebar and the `/docs/` index, add its href to the `nav` in
`src/docs-chrome.ts` (the collection slug maps 1:1 to the href):

```ts
nav: [
  {
    label: "Product",
    items: [
      { label: "External calendars", href: "/docs/product/external-calendars" },
      { label: "Configuration", href: "/docs/product/configuration" },
    ],
  },
];
```

Pages that exist in the collection but are not listed still render — they just do not
appear in the sidebar. The docs index (`/docs/`) lists every collection entry, ordered by
the `src/docs-chrome.ts` nav first and then alphabetically.

## Shared chrome dependency (TODO: publish)

`@kolektiv/common-docs-chrome` is not published to the registry yet, so `package.json`
links it from a sibling checkout on this machine:

```json
"@kolektiv/common-docs-chrome": "link:../../../../github.com/KolektivComputer/common-docs-chrome"
```

**TODO once `@kolektiv/common-docs-chrome@0.0.1-SNAPSHOT.1` is published to keel-npm:**

1. Replace the `link:` dependency with `"^0.0.1-SNAPSHOT.1"`.
2. Keep `docs/.npmrc` pointing `@kolektiv` at the aggregate read registry
   (`@kolektiv:registry=https://repo.yuri.capital/repository/npm-public/`).
3. Regenerate `docs/pnpm-lock.yaml` with `pnpm install` and drop the sibling checkout.

**CI note:** the GitHub runner does not have the sibling path above, so
`.github/workflows/docs.yml` cannot install or build until the package is published (or a
step checks out `common-docs-chrome` next to the repo). The workflow documents this with a
commented-out step.


## Deployment

The workflow in `.github/workflows/docs.yml` builds the site on pushes to the default
branch and on pull requests, then deploys with `wrangler pages deploy`:

- **push to the default branch** — production deploy to the `kalendee-docs` Pages project.
- **pull request** — preview deploy under the PR branch name, giving each PR a
  `*.kalendee-docs.pages.dev` preview URL.

Required repository secrets:

| Secret | Purpose |
| --- | --- |
| `CLOUDFLARE_API_TOKEN` | API token with the "Cloudflare Pages: Edit" permission |
| `CLOUDFLARE_ACCOUNT_ID` | The Cloudflare account id that owns the project |

The project name and output directory live in `wrangler.toml` (readable by
`wrangler pages deploy` locally too):

```bash
pnpm install
pnpm run build
pnpm exec wrangler pages deploy dist --project-name=kalendee-docs
```

To deploy the same build elsewhere, point the host at `docs/dist` (no server runtime is
needed). `astro.config.mjs` reads `PUBLIC_SITE_URL` for canonical and sitemap URLs and
falls back to `https://kalendee-docs.pages.dev`.
