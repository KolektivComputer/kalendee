---
title: Troubleshooting
description: >-
  Diagnose configuration, database, migration, port, avatar, mail, OAuth,
  reverse-proxy, and health-check problems with concrete commands.
---

## Get the logs first

Almost every problem is visible in the server log. Logs go to stdout/stderr.

```bash
# Docker Compose
docker compose logs --tail=200 -f kalendee
docker compose logs --tail=100 postgres
docker compose ps                       # health and exit state

# systemd
journalctl -u kalendee -n 200 -f

# Is the process up and listening?
ss -ltnp | grep 8080
curl -v http://localhost:8080/api/v1/health
```

The container entrypoint logs which config file it selected, for example:

```text
kalendee-entrypoint: using mounted config /config/application.conf
kalendee-entrypoint: using baked config /app/application.conf
kalendee-entrypoint: no config file found; falling back to jar defaults
```

The default `logback.xml` logs at `INFO` and prints one access-log line per
request (method, path, response status); static pack assets and favicons are
excluded. To debug with `TRACE` (or per-logger `DEBUG`, e.g. `Exposed` for SQL),
mount or pass a logback config:

```bash
docker run ... -e JAVA_OPTS="-Dlogback.configurationFile=/config/logback.xml" ...
# or, for a plain Java run:
java -Dlogback.configurationFile=/etc/kalendee/logback.xml -jar server-all.jar -config=/etc/kalendee/application.conf
```

## Production container is compiling from source

**Symptom:** the container logs a Gradle task (`> Task :server:run`) or complains
about `/src/.env.local` / a missing source tree, even though it was started from
the published image.

The published `:latest` image is the Dockerfile's **runtime** stage: a fat jar
launched by `/app/entrypoint.sh`. A `:server:run` task means the container was
built from the `dev` stage instead. That stage is opt-in (`--target dev`,
`docker-compose.dev.yml`) and is never for production; a bare `docker build`
selects the final `runtime` stage.

Fix: pull the current image and recreate the container.

```bash
docker compose pull
docker compose up -d
docker inspect --format '{{.Config.Entrypoint}} {{.Config.User}}' \
    docker.yuri.capital/kolektiv/kalendee:latest
```

The last command should print `/app/entrypoint.sh 10001`.

## Configuration is not being picked up

Work through the precedence order, highest priority first. Editing the wrong
file is the most common cause:

| Priority | Source | How to confirm |
| --- | --- | --- |
| 1 | `-config=<path>` on the container command | `docker inspect` the container `Cmd`/`Entrypoint`. |
| 2 | `KALENDEE_CONFIG` | `docker compose exec kalendee printenv KALENDEE_CONFIG`. |
| 3 | `/config/application.conf` | `docker compose exec kalendee cat /config/application.conf`. |
| 4 | `/app/application.conf` (baked) | `docker compose exec kalendee head /app/application.conf`. |
| 5 | Jar defaults | Only if none of the above exist. |

Frequent mistakes:

- **You edited `application.conf` but value did not change.** The server reads it
  at startup; run `docker compose restart kalendee`. Changing `.env` requires
  `docker compose up -d` to recreate the container.
- **Compose fails on startup with a mount error.** `./application.conf` does not
  exist. Create it: `cp application.conf.example application.conf`.
- **A variable in `.env` seems ignored.** Only variables listed as `${?VAR}`
  fallbacks *inside the loaded HOCON file* are consumed. If the key is not in
  the file, setting the env var does nothing.
- **The mount is read-only.** The server never writes config; edit the host file.
- **You changed `app.baseUrl` and links are still wrong.** Confirm the value has
  no trailing slash and matches the exact public scheme/host; check that an old
  `KALENDEE_BASE_URL` is not set over an updated `app.baseUrl` line.

## Database connection and migrations

**Symptom:** startup fails with
`KALENDEE_DATABASE_PASSWORD is required (set an empty value for trust auth)`.

Set `KALENDEE_DATABASE_PASSWORD`. An empty value is accepted only for trust
authentication; in Compose it must match `POSTGRES_PASSWORD`:

```bash
grep -E 'POSTGRES_PASSWORD|KALENDEE_DATABASE_PASSWORD' .env
```

**Symptom:** `Connection refused`, `UnknownHostException`, or connection
timeouts.

- In Compose, the JDBC host is the service name `postgres`:
  `jdbc:postgresql://postgres:5432/kalendee`. For a binary on the host, use
  `127.0.0.1` or your real database host.
- Confirm the database accepted the connection:

  ```bash
  docker compose exec postgres pg_isready -U "${POSTGRES_USER:-kalendee}"
  docker compose logs postgres
  ```

- In Compose the app waits for the database health check. If `postgres` is
  unhealthy, fix it first.

**Symptom:** `password authentication failed for user "kalendee"`.

The role password and `KALENDEE_DATABASE_PASSWORD` disagree. They are not
synced automatically; set both to the same value and recreate the stack. If the
volume already exists, `POSTGRES_PASSWORD` alone does not change the role's
password — update it in SQL or recreate the volume from a backup.

**Symptom:** a Flyway error such as `Migration checksum mismatch`,
`Detected failed migration`, or `relation "..." does not exist`.

- Never edit a migration that has already run; its checksum is recorded in
  `flyway_schema_history`. Add a new `V<n>__...sql` migration instead.
- Check the current state:

  ```bash
  docker compose exec postgres \
      psql -U "${POSTGRES_USER:-kalendee}" -d "${POSTGRES_DB:-kalendee}" \
      -c 'SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;'
  ```

- If a migration failed, the database is in a partially applied state. Repair by
  restoring the pre-upgrade dump (see
  [Backups and upgrades](/docs/self-hosting/backups-and-upgrades)) and running a
  fixed release.
- If tables are missing entirely, the app role may lack `CREATE` on the
  database. Grant ownership: `CREATE DATABASE kalendee OWNER kalendee;`.

## Port conflicts

**Symptom:** `BindException: Address already in use`, or the container exits
immediately.

- The container always listens on `8080`; only the host side is configurable.
  Change the host port in `.env`:

  ```bash
  KALENDEE_HOST_PORT=8090
  ```

  Then `docker compose up -d` and use `http://localhost:8090`.

- To change the in-container port (`ktor.deployment.port`), you must also update
  the Compose mapping and the health check accordingly; changing only the HOCON
  key breaks the published port.
- Find the current owner:

  ```bash
  ss -ltnp | grep -E ':8080|:5432'
  ```

- PostgreSQL must not be exposed; only `kalendee` publishes a host port.

## Avatar uploads fail

The upload endpoint enforces type and size before storage:

| Constraint | Value |
| --- | --- |
| Allowed content types | `image/png`, `image/jpeg`, `image/webp`, `image/gif` |
| Maximum size | 2 MiB (2,097,152 bytes) |
| Field name | `file` (multipart form) |

Common causes and fixes:

- **`file must be a PNG, JPEG, WebP, or GIF image`.** The browser's declared
  MIME type was missing or different (for example `image/jpg`, `image/svg+xml`,
  or `application/octet-stream`). Re-export as PNG/JPEG/WebP/GIF; SVG is not
  accepted.
- **`file must be at most 2 MiB`.** Downscale or recompress the image.
- **`storage quota exceeded`.** The user's effective quota (the minimum across
  the `default` group and their groups) is too small. Raise it or grow the user
  group's quota. Admins are exempt. See
  [Administration](/docs/self-hosting/administration).
- **Upload fails only through the proxy.** Increase the proxy body limit
  (`client_max_body_size 4m;` in nginx).
- **Local storage fails.** The avatar directory must exist and be writable by
  the server user:

  ```bash
  docker compose exec kalendee sh -c 'ls -ld /data/avatars; touch /data/avatars/.wtest && rm /data/avatars/.wtest'
  ```

  On a host install, ensure `KALENDEE_AVATAR_DIR` is inside `ReadWritePaths`.
- **S3/R2 failures.** Verify `storage.s3.enabled`, endpoint, bucket, region
  `auto`, `pathStyle=true`, and credentials. See
  [Object storage](/docs/self-hosting/object-storage).
- **`PUT` returns `403 AccessDenied` against Cloudflare R2.** If the request
  debug log shows an `x-amz-checksum-crc32` trailer, the client is using the AWS
  SDK v2 default request checksums, which R2 rejects. Kalendee sets
  `requestChecksumCalculation = WHEN_REQUIRED` so this should not occur; make
  sure you are running a build that includes that fix.

## Mail is not sending

Mail is disabled by default, and when disabled the logging mailer writes the
message and its links to the server log. That is expected behavior, not a
failure. To send real mail:

```hocon
mail {
  enabled = true
  provider = "smtp"        # or "cloudflare"/"mailgun"; blank auto-detects
  host = "smtp.example.com"
  port = 587
  username = "calendar@example.com"
  password = ${?KALENDEE_SMTP_PASSWORD}
  from = "Kalendee <no-reply@example.com>"
  startTls = true
}
```

Checks:

- **`enabled = false`.** Nothing is sent; look for the logged message with its
  verification/invite link.
- **Provider detection.** With `enabled = true` and `provider` blank: SMTP if
  `host` is set, else Cloudflare if endpoint and token are set, else Mailgun if
  API key and domain are set, else the log mailer. Set `provider` explicitly
  when you configure more than one.
- **SMTP auth.** A blank `username` disables authentication (useful for a local
  relay). Port 587 generally needs STARTTLS; port 465 uses implicit TLS and may
  need `startTls = false`.
- **From address rejected.** Many providers refuse a sender that does not belong
  to the authenticated domain.
- **Cloudflare mailer.** The endpoint must be the deployed Worker URL and the
  token must equal the Worker's `MAILER_TOKEN`; the sender domain and recipient
  must be verified in Cloudflare Email Routing. Note that the `send_email`
  binding is a paid Email Routing feature. See
  [Email](/docs/self-hosting/email) and
  [Cloudflare Workers](/docs/self-hosting/cloudflare-workers).
- **Mailgun.** The API key and verified domain must both be set, `mail.from`
  must be on that domain, and the region must match where the domain was created
  (`us` vs `eu`). A `401` usually means a wrong key or region mismatch; a `403`
  means the sender/domain is not verified. See
  [Email](/docs/self-hosting/email#mailgun-provider).
- **`auth.emailVerification = required` without mail.** New users cannot
  complete signup. Configure mail or set the policy to `optional`.

## OAuth callbacks fail

The redirect URI is built from `app.baseUrl`:

```text
{app.baseUrl}/api/v1/oauth/discord/callback
```

- Register that exact URI with the provider. Scheme, host, port, and path must
  match character for character, including `https`.
- `app.baseUrl` must be the public URL users reach, not the internal
  `http://kalendee:8080`.
- External calendar connections need `KALENDEE_SECRET_KEY`; without it the
  token vault fails closed and connecting is impossible. Generate one with
  `openssl rand -base64 32`.
- The client id/secret must belong to the same provider application as the
  registered redirect. See [Discord OAuth](/docs/self-hosting/oauth-discord).
- Provider-side rate limits and `needs_reauth` states are reported in
  **Settings → Connected Accounts**.

## Reverse proxy, HTTPS, and cookies

**Symptom:** login succeeds but every request redirects back to `/login`; the
session never sticks.

- `auth.cookieSecure` is `true` but the browser is on plain HTTP, so it drops
  the `Secure` cookie. Serve HTTPS, or set `KALENDEE_COOKIE_SECURE=false` only
  for a deliberate plain-HTTP deployment.
- `app.baseUrl` does not match the actual public URL.
- The proxy strips `Set-Cookie` or uses a different host than users visited.
  Preserve `Host` and cookies.

**Symptom:** absolute links in emails or RSS point at the wrong host.

Set `app.baseUrl` (or `KALENDEE_PUBLIC_URL`) to the exact public HTTPS URL with
no trailing slash.

**Symptom:** avatar uploads or large requests return `413`.

Raise `client_max_body_size` (nginx) or the equivalent on your proxy.

Note that Kalendee does not consume `X-Forwarded-*` itself; configure the proxy
to set them anyway for logging and for your own rate limiting.

## Health check fails or the container is not healthy

```bash
docker compose ps
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/health
docker inspect --format '{{json .State.Health}}' kalendee-kalendee-1 | jq
```

The health endpoint pings PostgreSQL, so it fails while the database is down or
migrations are still running. The image allows a 45-second start period and
retries 5 times; a first start on a slow disk can briefly report `starting`.

## "too many login attempts"

The built-in throttle allows 5 failed logins per 60 seconds per client address
and then returns `429`. Wait a minute. Because the app keys on the direct peer,
all users behind a proxy share that bucket; put a proper rate limiter at the
proxy. Restarting the server clears the in-memory counters. See
[Security](/docs/self-hosting/security).

## Recover or reset an admin

The startup seeding is idempotent. Set the admin password and restart:

```bash
# .env
KALENDEE_ADMIN_PASSWORD=new-strong-password
```

```bash
docker compose up -d
docker compose logs kalendee | grep -i "admin user"
```

For a binary, set the environment variable and restart the service. Then remove
the temporary password from the environment if you do not want it retained. See
[Administration](/docs/self-hosting/administration).

## Web UI looks stale after an upgrade

The web pack is built into the jar and served from the classpath. There is no
separate pack to update. Clear the browser cache, or verify the running artifact
is the one you intend:

```bash
docker compose images kalendee
docker compose exec kalendee sh -c 'ls -l /app/server-all.jar'
```

If you intentionally run an exploded pack (`keel.packDir` /
`KALENDEE_KEEL_PACK`), point it at a freshly built `server/pack/dist` and
restart.
