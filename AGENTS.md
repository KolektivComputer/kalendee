# Kalendee

This file is for coding agents working in this repository. Humans should start
with [DEVELOPING.md](./DEVELOPING.md). Product intent lives in
[README.md](./README.md).

Kalendee is a Kotlin Multiplatform CalDAV **server and client**. The long-term
shape is a self-hosted server with bespoke clients (Notion Calendar / Cron
inspired), a themeable Compose UI, a Keel web UI, and a separate pure-KMP
CalDAV protocol library. The repo is currently the JetBrains KMP scaffold
(Android, iOS, Desktop JVM, Ktor server) plus a `:core` module shared by
every target.

Sibling theming project: [keel](https://github.com/lizainslie/keel), checked
out at `../keel`. Do not vendor or rewrite Keel here. The server consumes
published `dev.kolektiv.keel:ktor` (Maven releases) and the web pack consumes
`@kolektiv/keel*` from Nexus `keel-npm`.

## Layout

| Path | Gradle | What |
| --- | --- | --- |
| `core/` | `:core` | Code shared by **all** targets (server + every client). Prefer `commonMain`. Platform source sets exist if a target API is required. No Compose. |
| `app/shared/` | `:app:shared` | Compose Multiplatform UI and client logic shared by Android, iOS, and Desktop. `api(project(":core"))`. Exports a static iOS framework named `Shared`. |
| `app/androidApp/` | `:app:androidApp` | Android application entry (`MainActivity`). |
| `app/desktopApp/` | `:app:desktopApp` | Desktop (JVM) application entry (`MainKt`). |
| `app/iosApp/` | *(Xcode, not Gradle)* | iOS application entry. Even with a shared Compose UI, this Swift/Xcode project is required. Put SwiftUI-only code here. |
| `server/` | `:server` | Ktor JVM server (`ApplicationKt`). `api(project(":core"))`. JSON API at `/api/v1`. Keel MPA at `/`. Listens on `0.0.0.0:8080`. |
| `server/pack/` | *(pnpm, not Gradle)* | Default Svelte pack (`@kolektiv/kalendee-pack`). Implements page ids; Gradle `buildPack` zips `kalendee.feb` onto the server classpath. |
| `workers/` | *(pnpm, not Gradle)* | Standalone Cloudflare Workers: `mailer/` (HTTP → email bridge) and `r2/` (private-bucket read gateway). TypeScript ESM, one pnpm project each, own `wrangler.toml`. Never import from `:core`, `:app`, or `:server`; the server talks to them over HTTP. |
| `docs/` | *(pnpm, not Gradle)* | Astro 5 docs and marketing site. Guides are Markdown under `docs/src/content/docs/`; chrome and theme config come from `@kolektiv/common-docs-chrome`. Do not edit `docs/dist/`. |
| `gradle/libs.versions.toml` | — | Version catalog. New coordinates go here, not as string literals in module scripts. |

`app/iosApp` is **not** a Gradle included build. `:app:shared` compiles the
`Shared` framework; Xcode consumes it.

### Source sets (`:core` and `:app:shared`)

- `commonMain` — code common to every target of that module.
- `commonTest` — tests common to every target of that module.
- Platform folders (`androidMain`, `iosMain`, `jvmMain`, and matching `*Test`)
  compile only for that target. Use `expect`/`actual` for platform APIs (see
  `Platform` in `:app:shared`). Example: Apple CoreCrypto belongs in
  `iosMain`; Desktop JVM-only code belongs in `jvmMain`.
- `:app:shared` also has `androidHostTest` (host-side Android tests).

Kotlin package is `dev.kolektiv.kalendee` everywhere. Android application id
is `dev.kolektiv.kalendee`. Compose resources for `:app:shared` generate under
`kalendee.app.shared.generated.resources`.

## Commands

When the IntelliJ `idea` MCP is connected, drive Gradle, compile, tests, and
run configurations through `idea__execute_tool` (`build_project`,
`execute_run_configuration`, IDE `execute_terminal_command`). Do not run
`./gradlew` in Grok bash as the first choice.

Human / fallback wrapper commands (full toolchain notes:
[DEVELOPING.md](./DEVELOPING.md)):

```bash
./gradlew :app:androidApp:assembleDebug
./gradlew :app:desktopApp:hotRun --auto    # Compose hot reload
./gradlew :app:desktopApp:run
./gradlew :server:run                      # http://0.0.0.0:8080

./gradlew :app:shared:testAndroidHostTest
./gradlew :app:shared:jvmTest
./gradlew :server:test
./gradlew :app:shared:iosSimulatorArm64Test   # macOS + Xcode
```

Standalone pnpm projects (not Gradle; Node 22+ / pnpm 11.25.0):

```bash
pnpm --dir docs dev        # Astro docs site at http://localhost:4321
pnpm --dir docs build      # static output in docs/dist/

cd workers/r2 && pnpm run typecheck   # or workers/mailer
```

iOS app: open `app/iosApp` in Xcode and run from there.

## Conventions

- Kotlin official code style (`kotlin.code.style=official` in `gradle.properties`).
- Match the file you are in. Do not reformat unrelated code or add dependencies
  without a need. Declare libraries and plugins in `gradle/libs.versions.toml`.
- Shared **non-UI** logic goes in `:core`. Shared **Compose UI** goes in
  `:app:shared`. Platform entry points stay thin (activity / `main` /
  `MainViewController` / Ktor `module()`).
- `:core` must stay usable from the server. Do not put Android, Compose, Keel, or
  UI toolkit types in `:core`.
- The CalDAV protocol library is meant to be a **separate** pure multiplatform
  Kotlin library. Do not dump a full RFC 4791 implementation into `:app:shared`
  or `:server` unless the task is explicitly to start that library.
- Server config is **HOCON-file-first**. Add settings as HOCON keys in
  `server/src/main/resources/application.conf` (with a `${?VAR}` env fallback)
  and mirror them in `application.conf.example` / `application.conf.dev.example`
  — do not create a parallel env-var-only namespace. Runtime resolution:
  `-config=` > `KALENDEE_CONFIG` > `/config/application.conf` >
  `/app/application.conf` > jar defaults.
- The web UI is a Keel pack. The host owns URLs, page ids, and payload types
  (`dev.kolektiv.kalendee.web`). Packs implement ids — never paths — and must
  not fetch `/api/v1` for page data. Resolve `@kolektiv/*` from
  `https://repo.yuri.capital/repository/keel-npm/` (see `server/pack/.npmrc`).
- The docs site (`docs/`) is an Astro site whose chrome comes from
  `@kolektiv/common-docs-chrome` (currently a local `link:` until it is
  published to keel-npm). Do not re-declare daisyUI/Tailwind or import
  `@kolektiv/themes` directly; site config lives in `docs/src/docs-chrome.ts`.
  Do not edit generated `docs/dist/` or the guide Markdown unless the task is
  about the docs site.
- Web UI icons use `@lucide/svelte` with deep imports
  (e.g. `@lucide/svelte/icons/calendar-plus`); do not hand-inline SVGs; size
  with Tailwind classes. Icons inside buttons are decorative (`aria-hidden`
  default) with the label or tooltip on the button; standalone meaningful icons
  get an `aria-label`.
- License is AGPL-3.0-only (see `LICENSE`). Do not relicense or add conflicting
  license headers.
- Do not commit `local.properties`, `**/build/`, `.gradle/`, `.idea/`,
  `.kotlin/`, `xcuserdata`, or signing material.

## Current scaffold (do not treat as product code)

Replace template code in place as features land. Do not keep a parallel
hello-world app. `:server` serves the JSON API under `/api/v1` and a Keel
MPA at `/` (login, register, week view, not-found). If `KALENDEE_ADMIN_PASSWORD`
is set, startup seeds user `admin`. Compose clients are still the KMP
template (`App()`, `Greeting`, `Platform`).

## Goals (when implementing features)

- Self-hosted CalDAV server with first-party clients.
- Themeable clients, and a themeable web UI via Keel.
- Implement the CalDAV protocol in a dedicated pure-KMP library.
- Calendar linking plus scheduling proposals, with configurable anonymous vs
  logged-in access.
