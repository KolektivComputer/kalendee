# Developing Kalendee

Setup, module map, and how to run and test. Product intent is in [README.md](./README.md); contribution rules (including AI assistance and licensing) are in [CONTRIBUTING.md](./CONTRIBUTING.md). Coding agents should also read [AGENTS.md](./AGENTS.md).

This is a Kotlin Multiplatform project targeting Android, iOS, Desktop (JVM), and a Ktor server.

## Table of contents

- [Quick start](#quick-start)
- [Prerequisites](#prerequisites)
- [Getting the code](#getting-the-code)
- [Modules](#modules)
- [Running the server](#running-the-server): [PostgreSQL](#postgresql) · [`.env.local`](#envlocal) · [Admin seeding](#admin-seeding-and-registration) · [Configuration](#configuration)
- [Running the clients](#running-the-clients)
- [The web UI (Keel pack)](#the-web-ui-keel-pack)
- [Object storage (S3/R2)](#object-storage-s3r2)
- [Email (SMTP/Cloudflare)](#email-smtpcloudflare)
- [Cloudflare Workers (`workers/`)](#cloudflare-workers-workers)
- [The docs site (Astro)](#the-docs-site-astro)
- [Demo data and screenshots](#demo-data-and-screenshots)
- [Docker](#docker)
- [NixOS](#nixos)
- [Pre-publish verification](#pre-publish-verification)
- [Releasing](#releasing)
- [Running tests](#running-tests)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [Further reading](#further-reading)

## Quick start

```bash
git clone <fork-or-upstream-url> kalendee
cd kalendee
```

1. **Database.** Use a local PostgreSQL 17:

   ```bash
   createuser --pwprompt kalendee   # prompts for the role password
   createdb --owner=kalendee kalendee
   ```

   or start only the compose database with `docker compose -f docker-compose.dev.yml up -d postgres`. The compose database listens only inside the compose network, so pair it with the full dev stack or publish a port (see [PostgreSQL](#postgresql)).

2. **Config.** Create the gitignored `.env.local` that `:server:run` injects, pointing the JDBC URL at your database. A full example is in [`.env.local`](#envlocal); the minimum for local development is:

   ```bash
   KALENDEE_DATABASE_PASSWORD=kalendee
   KALENDEE_ADMIN_PASSWORD=admin
   KALENDEE_DEVELOPMENT=true
   KALENDEE_COOKIE_SECURE=false
   KALENDEE_PUBLIC_URL=http://localhost:8080
   ```

3. **Run.** `./gradlew :server:run`, then open <http://localhost:8080> and log in as `admin` / `admin`. The first startup applies the Flyway migrations and seeds the admin user.

For a server full of sample data, set `KALENDEE_SEED_DEMO=true` and log in as `demo` / `demo` (see [Demo data and screenshots](#demo-data-and-screenshots)).

## Prerequisites

| Tool | Why |
| --- | --- |
| **JDK 21** | Required. The Gradle daemon is Azul 21 via `gradle/gradle-daemon-jvm.properties`; the Foojay resolver in `settings.gradle.kts` downloads it if needed. |
| **Gradle wrapper** | Use `./gradlew`; no system Gradle needed. Wrapper: Gradle 9.7.1. |
| **PostgreSQL 17** (or Docker) | The server's database. The compose files run `postgres:17-alpine`. |
| **Docker + Compose** | Optional: the dev and production compose stacks. |
| **Android SDK** | Android builds and `:app:shared:testAndroidHostTest`; SDK path in gitignored `local.properties`. |
| **Xcode** (macOS only) | The iOS app and `:app:shared` iOS tests. |
| **pnpm + Node 22+** | Builds the Keel Svelte pack in `server/pack`; `:server:run` and `:server:buildPack` require it. |

Current catalog versions are in `gradle/libs.versions.toml` (Kotlin, AGP, Compose Multiplatform, Ktor). Kotlin official code style is on (`kotlin.code.style=official`).

The server's Discord REST client is Kord (soft fork), `dev.kord:kord-rest:0.18.1-kalendee.2`, resolved from `mavenLocal()` during development. The fork lives at https://github.com/KolektivComputer/kord (branch `kalendee/recurrence`; local clone `../kord`, patch `/tmp/kord-kalendee.patch`). A fresh checkout or CI build needs the fork artifact first: run `./gradlew :server:publishToMavenLocal` (or `:common:publishToMavenLocal :rest:publishToMavenLocal`) in the clone; CI/Docker needs the fork's artifacts published to `repo.yuri.capital` maven-releases.

## Getting the code

Clone your fork or this checkout. Some checkouts have no configured remote, so fork/remote setup is up to you:

```bash
git clone <fork-or-upstream-url> kalendee && cd kalendee
git remote -v                 # may be empty
git remote add origin <url>   # a fork you can push to
```

## Modules

* [`app/iosApp`](./app/iosApp/iosApp) is the iOS application entry point, even when the UI is shared with Compose Multiplatform. Put SwiftUI-only code here. It is an Xcode project, not a Gradle included build.
* [`app/shared`](./app/shared/src) is Compose Multiplatform UI and client logic shared by Android, iOS, and Desktop. [`commonMain`](./app/shared/src/commonMain/kotlin) is common to all targets; other folders compile for one target only (Apple CoreCrypto belongs in [`iosMain`](./app/shared/src/iosMain/kotlin), Desktop JVM-only code in [`jvmMain`](./app/shared/src/jvmMain/kotlin)). `:app:shared` depends on `:core` and produces a static iOS framework named `Shared` (`iosArm64` and `iosSimulatorArm64`).
* [`core`](./core/src) is code shared between all targets (clients **and** the server). The most important subfolder is [`commonMain`](./core/src/commonMain/kotlin); platform folders exist too. Keep Compose and Android UI types out of `:core`.
* [`server`](./server/src/main/kotlin) is the Ktor server application. JSON API: `/api/v1`. Web UI: Keel host plus the Svelte pack in [`server/pack`](./server/pack).
* [`app/androidApp`](./app/androidApp) and [`app/desktopApp`](./app/desktopApp) are thin platform entry points over `:app:shared`.
* [`workers`](./workers) holds standalone Cloudflare Workers (`mailer`, `r2`): TypeScript ESM, one pnpm project each, not Gradle modules. They must not import from `:core`, `:app`, or `:server`; the server talks to them over plain HTTP.
* [`docs`](./docs) is the Astro documentation and marketing site (also not a Gradle module). Guides live under [`docs/src/content/docs`](./docs/src/content/docs); site configuration is `docs/src/docs-chrome.ts`.

New library and plugin coordinates belong in `gradle/libs.versions.toml`.

## Running the server

### PostgreSQL

The server needs PostgreSQL 17 (the compose files use `postgres:17-alpine`):

```bash
createuser --pwprompt kalendee   # prompts for the role password
createdb --owner=kalendee kalendee
```

Or as a superuser:

```sql
CREATE ROLE kalendee LOGIN PASSWORD 'kalendee';
CREATE DATABASE kalendee OWNER kalendee;
```

If you would rather not install PostgreSQL, run the full dev stack under [Docker](#docker).

### `.env.local`

`.env.local` is gitignored and read by `:server:run`, which injects each `KEY=value` line into the server process (real environment variables win over the file). Minimal example:

```bash
KALENDEE_DATABASE_URL="jdbc:postgresql://localhost:5432/kalendee"
KALENDEE_DATABASE_USER=kalendee
KALENDEE_DATABASE_PASSWORD=kalendee
KALENDEE_ADMIN_USERNAME=admin
KALENDEE_ADMIN_PASSWORD=admin
KALENDEE_DEVELOPMENT=true
KALENDEE_COOKIE_SECURE=false
KALENDEE_PUBLIC_URL=http://localhost:8080
```

`KALENDEE_DATABASE_PASSWORD` is required; an empty value is allowed for trust authentication. `KALENDEE_DEVELOPMENT=true` enables the pretty HTML 500 page and the development `baseUrl` default; `KALENDEE_COOKIE_SECURE=false` is required to log in over plain HTTP. Optional mail, S3, registration, and Argon2 keys are in [`.env.example`](./.env.example) and [`application.conf.example`](./application.conf.example).

Run `./gradlew :server:run` (binds `0.0.0.0:8080`, builds the Keel pack, and needs pnpm). Health check: `curl http://localhost:8080/api/v1/health` returns `{"status":"ok"}`.

### Admin seeding and registration

Set `KALENDEE_ADMIN_PASSWORD` (and optionally `KALENDEE_ADMIN_USERNAME`, default `admin`) to seed the first admin user on startup. `KALENDEE_AUTH_REGISTRATION` is `first-user` by default, so that seed closes public registration; the other values are `open` and `closed`.

### Configuration

Server config is **HOCON-file-first**. The committed defaults live in `server/src/main/resources/application.conf`; deployment starting points are [`application.conf.example`](./application.conf.example) (production) and [`application.conf.dev.example`](./application.conf.dev.example) (development). Ktor resolves the config in this order, highest priority first:

1. An explicit `-config=/path` argument.
2. `KALENDEE_CONFIG`, when set.
3. A mounted `/config/application.conf`.
4. The baked `/app/application.conf` in the Docker image.
5. The defaults embedded in the jar (`server/src/main/resources/application.conf`).

Environment variables appear only as `${?VAR}` substitutions *inside* whichever file wins, so a set variable overrides that file's committed default and an unset one falls back to it. Non-secret settings belong in the HOCON file; secrets stay in the environment (`.env`, `environment`/`environmentFile`). The full key reference is at <https://kalendee.kolektiv.computer/docs/self-hosting/configuration>.

Notable blocks, including keys added in this round:

- `database` — JDBC URL, user, and password (the password is required).
- `auth` — registration policy, session and cookie settings (`cookieSecure` defaults to secure outside development), Argon2 costs, and email verification: `emailVerification` (`optional` by default) and `emailVerificationTtlHours` (24). Also `auth.sessionDays` (`KALENDEE_AUTH_SESSION_DAYS`, default 30) and `auth.cookieName` (`KALENDEE_SESSION_COOKIE_NAME`, default `kalendee_session`).
- `app` — `baseUrl`, the canonical public URL used for verification, invite, and follow emails and RSS links. Set it via `app.baseUrl`, `KALENDEE_PUBLIC_URL`, or `KALENDEE_BASE_URL`. When blank, development defaults to `http://127.0.0.1:<ktor.deployment.port>`; set it explicitly in production. `app.development` (or `KALENDEE_DEVELOPMENT=true`) enables the pretty HTML 500 page and the development base URL default.
  It is independent of Ktor's `ktor.development` auto-reload: reload restarts the app in the same JVM, which used to leave Exposed's default database pointing at the previous, closed pool. The server now explicitly installs the newly connected database as Exposed's default on every startup.
- `ktor.deployment` — bind `port` (`KALENDEE_HTTP_PORT`, default 8080) and `host` (`KALENDEE_HTTP_HOST`, default `0.0.0.0`).
- `mail` — transport selection via `mail.provider` (`KALENDEE_MAIL_PROVIDER`): `smtp`, `cloudflare`, or `log`. See [Email (SMTP/Cloudflare)](#email-smtpcloudflare).
- `storage` — generic object storage: a local directory (`localDir`) or S3 (`s3.enabled`, endpoint, bucket, credentials, `pathStyle`). `storage.publicBaseUrl` (`KALENDEE_STORAGE_PUBLIC_URL`) redirects object reads at a CDN/R2 domain. See [Object storage (S3/R2)](#object-storage-s3r2).
- `keel.packDir` — optional exploded Keel pack directory; otherwise the pack bundled on the classpath is used.

Flyway migrations V1–V14 in `server/src/main/resources/db/migration` run at startup. The H2 test shim in `TestSupport.writeH2Migrations` lists migration filenames manually, so add new migrations there as well.

Docker deployments combine [`.env.example`](./.env.example) / [`.env.dev.example`](./.env.dev.example) (secrets and a few container knobs consumed by compose) with a mounted HOCON file copied from [`application.conf.example`](./application.conf.example) / [`application.conf.dev.example`](./application.conf.dev.example). The entrypoint selects that file via `KALENDEE_CONFIG` (defaulting to the mounted `/config/application.conf`); see [Docker](#docker).

## Running the clients

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Desktop app:
    - Hot reload: `./gradlew :app:desktopApp:hotRun --auto`
    - Standard run: `./gradlew :app:desktopApp:run`
- iOS app: open the [`app/iosApp`](./app/iosApp) directory in Xcode and run it from there.

The clients still point at the Compose template (`App()`, `Greeting`, `Platform`) while the real UI lands.

## The web UI (Keel pack)

The web UI uses [keel](https://github.com/lizainslie/keel). The default pack lives in `server/pack` (`@kolektiv/kalendee-pack`) and is built into `kalendee.feb`, which `processResources` bundles onto the server classpath under `keel/`.

- Dependencies: npm `@kolektiv/keel*` from `https://repo.yuri.capital/repository/keel-npm/` (see [`server/pack/.npmrc`](./server/pack/.npmrc)); Maven `dev.kolektiv.keel:ktor` from `https://repo.yuri.capital/repository/maven-releases/`.
- Build once: `./gradlew :server:buildPack` (runs `pnpm install --frozen-lockfile` and `pnpm build`); `:server:run` depends on it.
- Watch mode: `pnpm --dir server/pack dev` (`vite build --watch`) with the server pointing at the exploded output via `KALENDEE_KEEL_PACK=/path/to/server/pack/dist`. `docker-compose.dev.yml` has a commented-out `pack` service for the same job.
- Types: `./gradlew :server:generateKeelTypes` regenerates `server/pack/src/lib/page-types.ts` and `page-types.json` from the `@KeelType`/`@KeelAction` declarations in `server/src/main/kotlin/dev/kolektiv/kalendee/web`; `pnpm --dir server/pack typecheck` checks the pack.
- The host owns URLs, page ids, and payload types (`dev.kolektiv.kalendee.web`). Packs implement ids such as `kalendee.home`, never paths, and must not fetch `/api/v1` for page data. See [AGENTS.md](./AGENTS.md) for the full rule.

## Object storage (S3/R2)

Avatar storage is the generic [`ObjectStorage`](./server/src/main/kotlin/dev/kolektiv/kalendee/storage/ObjectStorage.kt) abstraction with two backends: `LocalObjectStorage` (filesystem under `storage.localDir`) and `S3ObjectStorage` (any S3-compatible API). R2 works through the S3 API. Configure it under `storage` in HOCON; the annotated example is [`application.conf.example`](./application.conf.example).

- **Local (default).** `storage.localDir` (`KALENDEE_AVATAR_DIR`); the Docker image uses `/data/avatars` on the `kalendee-avatars` volume.
- **S3-compatible.** `storage.s3.enabled = true` plus `endpoint`, `bucket`, `region`, `accessKey`/`secretKey`, and `pathStyle`. For Cloudflare R2 set `endpoint = "https://<account-id>.r2.cloudflarestorage.com"`, `region = "auto"`, `pathStyle = true`, and provide an R2 API token.
- **Redirect reads.** `storage.publicBaseUrl` (`KALENDEE_STORAGE_PUBLIC_URL`) makes `GET /api/v1/users/{id}/avatar` respond `302` to `${publicBaseUrl}/<key>?v=<version>` instead of proxying bytes, so a CDN or the [`workers/r2`](./workers/r2) gateway serves images directly.

`workers/r2` is a read gateway for a private bucket: the server writes through the S3 API and clients read through the Worker with correct caching, content types, conditional requests, and an optional read token. See [Cloudflare Workers (`workers/`)](#cloudflare-workers-workers).

## Email (SMTP/Cloudflare)

The `mail` block selects one of three transports via `mail.provider` (`KALENDEE_MAIL_PROVIDER`):

- `log` (default when mail is disabled or no transport is configured) prints the message and its links instead of sending.
- `smtp` sends through `mail.host`/`port`/`username`/`password` with `startTls`; a blank host disables SMTP.
- `cloudflare` `POST`s a JSON envelope to a deployed mailer Worker (`mail.cloudflare.endpoint`, bearer `token`, `timeoutSeconds`). Use it to send without an SMTP relay.

When `mail.provider` is blank the server auto-detects: SMTP when `host` is set, otherwise Cloudflare when its endpoint is set, otherwise the logging mailer. `mail.enabled` is the master switch. Because mail carries generated links, set `app.baseUrl` (`KALENDEE_PUBLIC_URL`) correctly. The [`workers/mailer`](./workers/mailer) Worker bridges the Cloudflare provider to Cloudflare Email Routing's `send_email` binding; see [`workers/mailer/README.md`](./workers/mailer/README.md).

## Cloudflare Workers (`workers/`)

[`workers/`](./workers) holds two independent TypeScript/ESM Cloudflare Workers deployed with Wrangler. They are separate pnpm projects (Node 22+, pnpm 11.25.0) and must not import from `:core`, `:app`, or `:server` — the server talks to them over plain HTTP.

- [`workers/mailer`](./workers/mailer) (`kalendee-mailer`) — HTTP → email bridge for the `cloudflare` mail provider. Secret `MAILER_TOKEN`; binding `[[send_email]]` named `MAILER`.
- [`workers/r2`](./workers/r2) (`kalendee-r2`) — read gateway in front of a private R2 bucket, pointed at by `storage.publicBaseUrl`. Optional secret `READ_TOKEN`; binding `[[r2_buckets]]` named `BUCKET`.

```sh
cd workers/<name>
pnpm install
pnpm run dev        # local wrangler dev
pnpm run typecheck  # tsc --noEmit
pnpm run deploy     # wrangler deploy
```

Each worker has its own `wrangler.toml`; bindings that need real Cloudflare resources are commented out until you supply the bucket or zone. Never commit secrets — use `wrangler secret put` or a local `.dev.vars`. See [`workers/README.md`](./workers/README.md) for the wire contract and setup.

## The docs site (Astro)

[`docs/`](./docs) is the documentation and marketing site: an [Astro 5](https://astro.build) static site with the landing page in `docs/src/pages/index.astro` and guides as Markdown under [`docs/src/content/docs/`](./docs/src/content/docs). The navbar, sidebar, footer, theme picker, and daisyUI/Tailwind setup come from the shared [`@kolektiv/common-docs-chrome`](https://github.com/KolektivComputer/common-docs-chrome) package; site-specific config is `docs/src/docs-chrome.ts`.

```sh
pnpm --dir docs install
pnpm --dir docs dev      # http://localhost:4321
pnpm --dir docs build    # static output in docs/dist/
pnpm --dir docs check    # astro check (types + content schema)
```

The site builds to portable static files and is deployed to Cloudflare Pages through its Git integration on push to `main` (project `kalendee-docs`, canonical <https://kalendee.kolektiv.computer>). `@kolektiv/common-docs-chrome` is not published yet, so `docs/package.json` uses a local `link:` to a sibling `common-docs-chrome` checkout and CI cannot install until it is published to keel-npm and the dependency is switched to `^0.0.1-SNAPSHOT.1`. The default site URL is <https://kalendee.kolektiv.computer>; `PUBLIC_SITE_URL` overrides it. See [`docs/README.md`](./docs/README.md) for authoring and the publish TODO.

The moved external-calendars guide now lives here as `docs/src/content/docs/product/external-calendars.md` and is served at <https://kalendee.kolektiv.computer/docs/product/external-calendars>.

## Demo data and screenshots

The server can seed an idempotent demo dataset on startup. Set `KALENDEE_SEED_DEMO=true` and run the server (or put the variables in `.env.local`; the Docker dev example already sets them):

```bash
KALENDEE_SEED_DEMO=true KALENDEE_DEMO_PASSWORD=demo ./gradlew :server:run
```

Log in as `demo` / `demo`; restarting does not duplicate data. The demo users are `demo`, `sam`, and `alex` (all with the demo password). Seeded data includes four colored calendars, a full week of events, invites, shares, a followed public calendar, friendships, reminders, and holidays.

| Variable | Default | Meaning |
| --- | --- | --- |
| `KALENDEE_SEED_DEMO` | `false` | Enables the demo seeder. |
| `KALENDEE_DEMO_PASSWORD` | `demo` | Password for the seeded users. |
| `KALENDEE_DEMO_TIMEZONE` | `Europe/Berlin` | Time zone of seeded users and events. |

### Capturing screenshots

README screenshots are captured from the seeded demo data.

1. Start a server with demo data: `docker compose -f docker-compose.dev.yml up` (the dev env sets the seed variables) or `KALENDEE_SEED_DEMO=true ./gradlew :server:run`.
2. Log in as `demo` / `demo`.
3. Capture the week view, event dialog, notifications, settings, and a public calendar page. Suggested filenames under [`docs/public/screenshots/`](./docs/public/screenshots) (served by the Astro site at `/screenshots/`): `week-view.png`, `event-dialog.png`, `notifications.png`, `settings.png`, `public-calendar.png`.
4. Wire them into [README.md](./README.md) where marked; the week-view image is already commented out and waiting to be uncommented after capture. Keep screenshots free of real personal data.

## Docker

Configuration is HOCON-file-first. Production compose ([`docker-compose.yml`](./docker-compose.yml)) pulls the published image from docker.yuri.capital, bind-mounts `./application.conf` read-only at `/config/application.conf`, sets `KALENDEE_CONFIG` to that path, and runs PostgreSQL 17 next to it:

```bash
cp .env.example .env
cp application.conf.example application.conf
# edit both: strong POSTGRES_PASSWORD / KALENDEE_DATABASE_PASSWORD,
# KALENDEE_ADMIN_PASSWORD, and KALENDEE_PUBLIC_URL / app.baseUrl at minimum
docker compose up -d
```

Copying `application.conf.example` is required: the compose mount fails if `./application.conf` does not exist.

- Image `docker.yuri.capital/kolektiv/kalendee:${KALENDEE_IMAGE_TAG:-latest}`; to build the checkout instead, comment out `image:` and uncomment the `build:` block in `docker-compose.yml`.
- The published `:latest` image is the Dockerfile's final `runtime` stage: the fat jar launched by `/app/entrypoint.sh`, needing PostgreSQL and a HOCON config. The `dev` stage (Gradle `:server:run`, bind-mounted source) is opt-in via `--target dev` and is never what `:latest` should be.
- Host port `${KALENDEE_HOST_PORT:-8080}` maps to container port 8080.
- TLS terminates at a reverse proxy (Caddy, Traefik, nginx, ...). Keep `auth.cookieSecure = true` in `application.conf` and set `app.baseUrl` (or `KALENDEE_PUBLIC_URL`) to the public https URL; leave `cookieSecure` alone unless you serve plain HTTP on purpose.
- Health check: `curl http://localhost:8080/api/v1/health` → `{"status":"ok"}`.
- Secrets stay in `.env`; the mounted `application.conf` picks them up through its `${?VAR}` fallbacks. To use a different config file, set `KALENDEE_CONFIG` (or pass `-config=/path` as a container argument); its resolution order is described under [Configuration](#configuration).

Development compose ([`docker-compose.dev.yml`](./docker-compose.dev.yml)) builds the `dev` stage (`--target dev`), bind-mounts the source tree, mounts `./application.conf.dev` at `/config/application.conf`, exports `KALENDEE_CONFIG` to the Gradle-launched server, and runs `./gradlew :server:run` inside the container:

```bash
cp .env.dev.example .env.dev
cp application.conf.dev.example application.conf.dev
docker compose -f docker-compose.dev.yml up
```

Gradle recompiles on restart, so stop and start the `kalendee` service after code changes. On Docker Desktop (macOS/Windows), remove the `user:` line so the container is not restricted to a Linux host UID. For a host run (`./gradlew :server:run`), Gradle loads `.env.local` itself; an exported `KALENDEE_CONFIG` works there too because process environment variables win.

## NixOS

The flake exports `nixosModules.default` and `nixosModules.kalendee`. The module runs the published OCI image and requires a reachable PostgreSQL; create the database separately (for example with `services.postgresql` or another container) and point `KALENDEE_DATABASE_URL` / `KALENDEE_DATABASE_USER` at it.

Configuration is HOCON-file-first there too: `configFile` mounts an operator-managed HOCON file at `/config/application.conf` and selects it via `KALENDEE_CONFIG`, and `environment`/`environmentFile` values are substituted through the file's `${?VAR}` fallbacks.

```nix
{
  inputs.kalendee.url = "github:KolektivComputer/kalendee";

  # in your configuration:
  modules = [ inputs.kalendee.nixosModules.default ];

  services.kalendee = {
    enable = true;
    publicUrl = "https://calendar.example.com";
    configFile = "/etc/kalendee/application.conf";
    environmentFile = config.sops.secrets."kalendee-env".path;
  };
}
```

Options: `enable` (bool), `image` (`docker.yuri.capital/kolektiv/kalendee`), `imageTag` (`latest`; pin a release for reproducibility), `port` (8080; host port to container 8080), `publicUrl` (exported as `KALENDEE_PUBLIC_URL`), `configFile` (HOCON file mounted read-only at `/config/application.conf` and exported as `KALENDEE_CONFIG`; it is copied into the Nix store, so never put secrets in it — start from [`application.conf.example`](./application.conf.example)), `environment` (extra non-secret variables), `environmentFile` (runtime secrets file; never put secrets in the Nix store), `volumes` (extra mounts; a named volume `kalendee-data` is always mounted at `/data`), `extraOptions` (podman/docker flags), and `openFirewall`. Podman is the default container backend; set `virtualisation.oci-containers.backend = "docker";` for Docker. Plain-HTTP deployments need `KALENDEE_COOKIE_SECURE = "false"` via `environment`.

## Pre-publish verification

Run `scripts/pre-publish-checks.sh` before publishing. It runs, in order:

- `scripts/check-page-context.mjs` — static guard for the v0.2.0 `+page.svelte` bug class: a template that uses `ctx` while the script never binds the `page<...>()` return value. `pnpm typecheck` only covers `.ts` files and `vite build` happily bundles the broken page, so this inspects the page templates directly.
- `scripts/check-flake.sh` — `nix flake check --no-build` then `nix flake show`; requires Nix (install it or skip this check).
- `scripts/smoke-image.sh` — builds the Dockerfile's `runtime` stage, boots it with a disposable PostgreSQL, then checks `/api/v1/health`, the `/login` HTML shell, the served `bootstrap.js` and `kalendee.login.js` pack modules, an end-to-end admin login, and an authenticated `/api/v1/calendars` fetch. Prerequisites: docker and curl. `--no-build` reuses `IMAGE_TAG` (default `kalendee-smoke:local`) instead of building; `--keep` leaves the containers and network up on failure for debugging; `HOST_PORT` (default `18080`) and `PG_IMAGE` (default `postgres:17-alpine`) are configurable.

`.github/workflows/docker.yml` runs all three gates (`pack-check`, `flake-check`, `smoke-image`) before the multi-arch build and push, so nothing is published unless they pass.

## Releasing

Pushing a `v*` tag runs both release workflows; the version is the tag without the leading `v`:

```bash
git tag v1.2.3
git push origin v1.2.3
```

- `.github/workflows/docker.yml` — builds a multi-arch (`linux/amd64`, `linux/arm64`) image and pushes it to docker.yuri.capital with semver and SHA tags; also supports manual `workflow_dispatch`.
- `.github/workflows/publish.yml` — publishes `:core` and `:app:shared` to the Nexus `maven-releases` (or `maven-snapshots` for `SNAPSHOT` versions) with `-Pversion=<version>`, then creates a GitHub Release with `kalendee-server-<version>.jar`, `kalendee-server-<version>.zip`, and `kalendee-<version>.feb`. Manual `workflow_dispatch` takes a `version` input; snapshots are refused for GitHub Releases.

Required secrets: `YURI_CAPITAL_REPO_USERNAME` / `YURI_CAPITAL_REPO_PASSWORD` for Nexus; without them the Maven job skips publication with a warning. The Docker workflow logs in to `docker.yuri.capital` with `YURI_CAPITAL_DOCKER_USERNAME` / `YURI_CAPITAL_DOCKER_PASSWORD`, falling back to the `YURI_CAPITAL_REPO_*` secrets when the docker-specific secrets are absent.

For local consumption by sibling projects, publish the Kotlin modules to Maven Local: `./gradlew :core:publishToMavenLocal` or `./gradlew publishAllToMavenLocal` (`:core` and `:app:shared`).

## Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :app:shared:testAndroidHostTest`
- Desktop tests: `./gradlew :app:shared:jvmTest`
- Server tests: `./gradlew :server:test`
- iOS tests: `./gradlew :app:shared:iosSimulatorArm64Test` (macOS + Xcode)

`:core` has a `commonTest` source set; add tests there when shared non-UI logic grows.

`:server:test` runs against an H2 shim instead of PostgreSQL. The shim in `TestSupport.writeH2Migrations` lists the Flyway migration filenames manually, so every new migration must be added there as well or the test schema will not match production.

## Troubleshooting

- **Android build fails: SDK not found.** Set `sdk.dir` in the gitignored `local.properties` (for example `sdk.dir=/home/you/Android/Sdk`).
- **Pack build fails: pnpm missing or npm 401/404.** Install pnpm and Node 22+; the pack resolves `@kolektiv/*` from `https://repo.yuri.capital/repository/keel-npm/` (see [`server/pack/.npmrc`](./server/pack/.npmrc)).
- **Server fails to start with a missing database password.** `KALENDEE_DATABASE_PASSWORD` is required; set it in `.env.local` or the environment (empty is allowed for trust auth).
- **Login redirects back or the session cookie never sticks over plain HTTP.** Set `KALENDEE_COOKIE_SECURE=false` and a matching `KALENDEE_PUBLIC_URL=http://localhost:8080`.
- **Web UI changes do not show up.** Rebuild the pack (`./gradlew :server:buildPack` or `pnpm --dir server/pack build`) and restart the server, or run `pnpm --dir server/pack dev` with `KALENDEE_KEEL_PACK` pointing at the exploded `server/pack/dist`.
- **Docs site install/build fails on `@kolektiv/common-docs-chrome`.** The package is not published yet, so `docs/package.json` uses a local `link:` to a sibling `common-docs-chrome` checkout and CI cannot install until it lands on keel-npm; see [`docs/README.md`](./docs/README.md).

## Contributing

Read [CONTRIBUTING.md](./CONTRIBUTING.md) for the contribution workflow, code style, AI-assistance policy, and licensing terms. Coding agents must also follow [AGENTS.md](./AGENTS.md).

## Further reading

- [Kalendee docs site](https://kalendee.kolektiv.computer) — product guides and the self-hosting reference; source in [`docs/`](./docs)
- [External calendar connections](https://kalendee.kolektiv.computer/docs/product/external-calendars) — provider matrix, security model, and roadmap for linking external calendars
- [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)
- [keel](https://github.com/lizainslie/keel) — theming and web UI framework used by the server's web UI and clients
- [`@kolektiv/common-docs-chrome`](https://github.com/KolektivComputer/common-docs-chrome) — shared chrome for the Astro docs site
