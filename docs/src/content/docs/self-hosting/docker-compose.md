---
title: Docker Compose
description: >-
  The primary Kalendee deployment path: a published image plus PostgreSQL,
  configured with a bind-mounted HOCON file and a secrets-only .env.
---

## Quick start

From a checkout of the repository (the Compose file lives at its root):

```bash
cp .env.example .env
cp application.conf.example application.conf
# Edit .env: set POSTGRES_PASSWORD / KALENDEE_DATABASE_PASSWORD (the same
# value), KALENDEE_ADMIN_PASSWORD, and any secrets.
# Edit application.conf: set app.baseUrl and any non-secret settings.
docker compose up -d
```

Then confirm the server is healthy and log in:

```bash
curl http://localhost:8080/api/v1/health   # {"status":"ok"}
```

Open `http://localhost:8080` and sign in as the seeded admin
(`auth.adminUsername` / `auth.adminPassword`, default username `admin`). Put a
TLS-terminating reverse proxy in front before exposing it to the internet — see
[Reverse proxy](/docs/self-hosting/reverse-proxy).

The important detail is that `docker compose up -d` requires
`./application.conf` to exist: the file is bind-mounted read-only at
`/config/application.conf`, and Compose fails on a missing bind source.

## Configuration model

This deployment is HOCON-file-first. Non-secret settings belong in
`application.conf`; secrets belong in `.env`.

```text
repo/
├── docker-compose.yml
├── application.conf          # operator config, bind-mounted read-only
├── application.conf.example  # starting point, committed
├── .env                      # secrets + compose knobs, git-ignored
└── .env.example              # starting point, committed
```

The container entrypoint resolves which HOCON file Ktor loads, highest priority
first:

| Priority | Source | How |
| --- | --- | --- |
| 1 | Explicit argument | `-config=/path/to/file` passed after the image name. |
| 2 | Environment | `KALENDEE_CONFIG` set and non-empty. |
| 3 | Mounted file | `/config/application.conf` exists (Compose mounts `./application.conf` here). |
| 4 | Baked file | `/app/application.conf`, copied from `application.conf.example` at image build. |
| 5 | Jar defaults | `server/src/main/resources/application.conf` inside the jar. |

Environment variables are `${?VAR}` substitutions *inside* whichever file wins.
An unset variable leaves the committed default; a set variable overrides it.
That means every `KALENDEE_*` variable in `.env.example` is optional — it only
changes behavior when set.

The Compose file exports `KALENDEE_CONFIG=/config/application.conf`, making the
mounted file explicit at priority 2. The full key list is in
[Configuration](/docs/self-hosting/configuration) and every variable in
[Environment variables](/docs/self-hosting/environment).

## Services, ports, and volumes

`docker compose up -d` starts two services:

| Service | Image | Purpose |
| --- | --- | --- |
| `kalendee` | `docker.yuri.capital/kolektiv/kalendee:${KALENDEE_IMAGE_TAG:-latest}` | The server. Waits for PostgreSQL to be healthy. |
| `postgres` | `postgres:17-alpine` | Database. `kalendee` connects to host `postgres:5432`. |

| Compose resource | Container path | Contents |
| --- | --- | --- |
| `./application.conf` (bind, read-only) | `/config/application.conf` | The HOCON config you edit. |
| `.env` (`env_file`) | environment | Secrets and compose-only knobs. |
| `kalendee-avatars` (named volume) | `/data` | Avatar files under `/data/avatars`. |
| `kalendee-postgres` (named volume) | `/var/lib/postgresql/data` | Database data. |

Ports: `${KALENDEE_HOST_PORT:-8080}` on the host maps to `8080` in the
container. The database is not published.

The image itself declares `VOLUME ["/data", "/config"]`, runs as UID/GID
`10001` with a `nologin` shell, and sets `KALENDEE_AVATAR_DIR=/data/avatars`.

## Health check

The image ships a health check:

```text
HEALTHCHECK CMD curl -fsS http://127.0.0.1:8080/api/v1/health
interval=30s  timeout=5s  start-period=45s  retries=5
```

Inspect it and the current state:

```bash
docker compose ps                          # STATUS shows (healthy) once ready
docker inspect --format '{{json .State.Health}}' kalendee-kalendee-1
curl -fsS http://localhost:8080/api/v1/health
```

The endpoint pings the database, so it fails until migrations have run and
PostgreSQL is reachable.

## Logs

The server logs to stdout/stderr, which Compose captures:

```bash
docker compose logs -f kalendee       # follow server logs
docker compose logs --tail=200 kalendee
docker compose logs -f postgres       # database
```

Startup lines from the entrypoint name the config file it selected, e.g.
`kalendee-entrypoint: using mounted config /config/application.conf`. That is
the fastest way to confirm which file is loaded. The default log level is
`INFO`, with one access-log line per request; see
[Troubleshooting](/docs/self-hosting/troubleshooting) for how to enable `TRACE`
debug logging.

## Applying configuration changes

```bash
# After editing application.conf or .env:
docker compose restart kalendee
```

`restart` re-runs the entrypoint with the same container, so a changed
`application.conf` is picked up. Changing `.env` values that Compose consumes
(ports, image tag, database bootstrap) needs `docker compose up -d` to recreate
the containers. HOCON or env changes do not trigger an automatic restart.

## Building from source

The published image is used by default. To build this checkout instead, edit
`docker-compose.yml`, comment out `image:`, and uncomment the `build:` block:

```yaml
  kalendee:
    # image: docker.yuri.capital/kolektiv/kalendee:${KALENDEE_IMAGE_TAG:-latest}
    build:
      context: .
      args:
        VERSION: local
```

Then:

```bash
docker compose build kalendee
docker compose up -d
```

The Dockerfile builds the fat jar in a JDK 21 + Node stage
(`./gradlew :server:buildFatJar`, which also builds the Keel pack) and copies
only the jar and entrypoint into the runtime stage. The `runtime` stage is the
final stage in the Dockerfile, so a bare `docker build` (and this `build:`
block, which sets no `target:`) produces the production image. Builds from a git
checkout need network access to Maven/Nexus and the `@kolektiv` npm registry.

## Upgrades and rollback

1. Back up PostgreSQL and the avatar store first — see
   [Backups and upgrades](/docs/self-hosting/backups-and-upgrades).
2. Pin `KALENDEE_IMAGE_TAG` to a released version rather than tracking `latest`.
3. Pull and recreate:

   ```bash
   docker compose pull
   docker compose up -d
   ```

Flyway applies any pending migrations on startup. Migrations are forward-only:
rolling the image tag back does not roll the schema back. To recover, restore
the pre-upgrade database dump.

## Development stack

`docker-compose.dev.yml` is separate and not for production. It builds the
Dockerfile `dev` stage (`--target dev`), bind-mounts the source tree at `/src`,
and runs `./gradlew :server:run` (hot recompilation on restart). The production
image is the final `runtime` stage instead: a fat jar launched by
`docker/entrypoint.sh`, needing PostgreSQL and a config file.

```bash
cp .env.dev.example .env.dev
cp application.conf.dev.example application.conf.dev
docker compose -f docker-compose.dev.yml up
```

It uses `kalendee-dev-postgres` and mounts `application.conf.dev` at
`/config/application.conf`. On Docker Desktop (macOS/Windows), remove the
`user:` line so the container is not pinned to a Linux host UID/GID.

## Common operations

```bash
docker compose exec postgres psql -U "${POSTGRES_USER:-kalendee}" -d "${POSTGRES_DB:-kalendee}"
docker compose exec kalendee sh            # inspect the container
docker compose down                        # stop; volumes are kept
docker compose down -v                     # DESTROYS the database and avatars
```

`docker compose down -v` deletes the named volumes. Never run it on an instance
whose data you have not backed up.
