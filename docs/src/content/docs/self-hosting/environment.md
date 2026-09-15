---
title: Environment variables
description: >-
  Every KALENDEE_* environment variable, the HOCON key it overrides, its
  default, and the compose-only variables consumed by docker-compose.yml.
---

Kalendee is HOCON-file-first. Environment variables are not a separate
configuration system: they are `${?VAR}` substitutions resolved *inside*
whichever HOCON file Ktor loads. See
[Configuration](/docs/self-hosting/configuration) for the file precedence and
the HOCON syntax.

## How environment variables work

Every server variable below appears in the baseline config as an optional
substitution:

```hocon
app {
    baseUrl = ""
    baseUrl = ${?KALENDEE_PUBLIC_URL}
    baseUrl = ${?KALENDEE_BASE_URL}
}
```

Consequences worth internalizing:

- **A variable only applies if the loaded HOCON file contains its `${?VAR}`
  line.** The baked `/app/application.conf` and both example files contain all
  of them, so this normally just works. A hand-written minimal config that omits
  a key cannot be set from the environment.
- **Environment variables win over the literal values in that same file.** In
  the snippet above, `KALENDEE_PUBLIC_URL` replaces `""`.
- **The file choice still wins at a higher level.** `-config=/path` beats
  `KALENDEE_CONFIG` beats `/config/application.conf` beats
  `/app/application.conf` beats the jar. Environment variables never change
  which file is read.
- **Compose-only variables are different.** Variables such as
  `KALENDEE_HOST_PORT`, `KALENDEE_IMAGE_TAG`, and `POSTGRES_*` are consumed by
  `docker-compose.yml` itself and are never seen by the server process.

### Recommend HOCON for non-secrets, env for secrets

The repository's own examples follow this split, and it is the recommended
practice:

| Setting kind | Where it belongs | Why |
| --- | --- | --- |
| Non-secret tuning (URLs, ports, policies, timeouts) | `application.conf` mounted at `/config/application.conf` | Versionable, diffable, documented inline, restartable without touching compose secrets. |
| Secrets (DB password, admin password, OAuth secrets, token keys, SMTP/S3/Mailgun credentials, Worker tokens) | `.env` (compose) or the process environment | Keeps secrets out of config files that may be committed or shared. |

Both surfaces end up in the same key space, so a setting can move between them
without changing behavior. The examples deliberately keep the `${?VAR}` lines in
the config file so either surface works.

## Server variables

All of the following are substitutions inside the loaded HOCON. The "HOCON key"
column is the key the variable overrides.

### Ktor / bind

| Variable | HOCON key | Default | Notes |
| --- | --- | --- | --- |
| `KALENDEE_HTTP_PORT` | `ktor.deployment.port` | `8080` | Port bound inside the container. Use `KALENDEE_HOST_PORT` to change the host-side mapping instead. |
| `KALENDEE_HTTP_HOST` | `ktor.deployment.host` | `0.0.0.0` | Interface to bind. |

### Database

| Variable | HOCON key | Default | Notes |
| --- | --- | --- | --- |
| `KALENDEE_DATABASE_URL` | `database.url` | `jdbc:postgresql://127.0.0.1:5432/kalendee` | JDBC URL. May embed `user:password@`. |
| `KALENDEE_DATABASE_USER` | `database.user` | `kalendee` | Database role. |
| `KALENDEE_DATABASE_PASSWORD` | `database.password` | *required* | No default. Empty is allowed, but the variable/value must be present. |

### Authentication and sessions

| Variable | HOCON key | Default | Notes |
| --- | --- | --- | --- |
| `KALENDEE_AUTH_REGISTRATION` | `auth.registration` | `first-user` | `first-user`, `open`, or `closed`. |
| `KALENDEE_AUTH_SESSION_DAYS` | `auth.sessionDays` | `30` | Session lifetime in days. |
| `KALENDEE_SESSION_COOKIE_NAME` | `auth.cookieName` | `kalendee_session` | Session cookie name. |
| `KALENDEE_COOKIE_SECURE` | `auth.cookieSecure` | secure outside development | Set `false` for plain-HTTP deployments. |
| `KALENDEE_ADMIN_USERNAME` | `auth.adminUsername` | `admin` | First-admin username. |
| `KALENDEE_ADMIN_PASSWORD` | `auth.adminPassword` | *unset* | Seeds the admin on startup; blank disables seeding. |
| `KALENDEE_SUPERADMIN_USERNAME` | `auth.superadminUsername` | *unset* | Username granted superadmin rights. |
| `KALENDEE_AUTH_EMAIL_VERIFICATION` | `auth.emailVerification` | `optional` | `optional`, `soft`, or `required`. |
| `KALENDEE_AUTH_EMAIL_VERIFICATION_TTL_HOURS` | `auth.emailVerificationTtlHours` | `24` | Verification-link lifetime. |
| `KALENDEE_AUTH_OAUTH_REGISTRATION` | `auth.oauthRegistration` | `false` | Allow OAuth sign-ups (still gated by `auth.registration`). |
| `KALENDEE_ARGON2_MEMORY_KIB` | `auth.argon2.memoryKib` | `19456` | Argon2id memory cost (KiB). |
| `KALENDEE_ARGON2_ITERATIONS` | `auth.argon2.iterations` | `2` | Argon2id iterations. |
| `KALENDEE_ARGON2_PARALLELISM` | `auth.argon2.parallelism` | `1` | Argon2id parallelism. |

### Application

| Variable | HOCON key | Default | Notes |
| --- | --- | --- | --- |
| `KALENDEE_PUBLIC_URL` | `app.baseUrl` | `""` | Preferred canonical URL. |
| `KALENDEE_BASE_URL` | `app.baseUrl` | `""` | Deprecated alias. If set together with `KALENDEE_PUBLIC_URL`, this later substitution wins, so set only one. |
| `KALENDEE_DEVELOPMENT` | `app.development` | `false` | Pretty error page and dev `baseUrl` default. |
| `KALENDEE_PUBLIC_ACCESS` | `app.publicAccess` | `public` | `public` or `signed_in`. |
| `KALENDEE_SEED_DEMO` | `app.seedDemo` | `false` | Seed demo data. Never enable in production. |
| `KALENDEE_DEMO_PASSWORD` | `app.demoPassword` | `demo` | Demo-user password. |
| `KALENDEE_DEMO_TIMEZONE` | `app.demoTimezone` | `Europe/Berlin` | Demo-data time zone. |

### OAuth / external calendars

| Variable | HOCON key | Default | Notes |
| --- | --- | --- | --- |
| `KALENDEE_DISCORD_CLIENT_ID` | `oauth.discord.clientId` | *unset* | Blank disables Discord. |
| `KALENDEE_DISCORD_CLIENT_SECRET` | `oauth.discord.clientSecret` | *unset* | Discord client secret. |
| `KALENDEE_DISCORD_BOT_TOKEN` | `oauth.discord.botToken` | *unset* | Bot token for guild scheduled events. |
| `KALENDEE_SECRET_KEY` | `oauth.secretKey` | *unset* | Base64 token-vault key, version `1`. Required for connections. |
| `KALENDEE_SECRET_KEYS` | `oauth.secretKeys` | *unset* | JSON rotation map, e.g. `{"1":"<base64>","2":"<base64>"}`. |

### Mail

| Variable | HOCON key | Default | Notes |
| --- | --- | --- | --- |
| `KALENDEE_MAIL_ENABLED` | `mail.enabled` | `false` | Auto-detection input. |
| `KALENDEE_MAIL_PROVIDER` | `mail.provider` | `""` (auto) | `smtp`, `cloudflare`, `mailgun`, or `log`. |
| `KALENDEE_SMTP_HOST` | `mail.host` | `""` | SMTP host. |
| `KALENDEE_SMTP_PORT` | `mail.port` | `587` | SMTP port. |
| `KALENDEE_SMTP_USER` | `mail.username` | `""` | SMTP username; blank disables auth. |
| `KALENDEE_SMTP_PASSWORD` | `mail.password` | `""` | SMTP password. |
| `KALENDEE_MAIL_FROM` | `mail.from` | `Kalendee <no-reply@localhost>` | From header. |
| `KALENDEE_SMTP_STARTTLS` | `mail.startTls` | `true` | STARTTLS. |
| `KALENDEE_MAIL_CLOUDFLARE_ENDPOINT` | `mail.cloudflare.endpoint` | `""` | Mailer Worker base URL. |
| `KALENDEE_MAIL_CLOUDFLARE_TOKEN` | `mail.cloudflare.token` | `""` | Bearer token; matches the Worker's `MAILER_TOKEN`. |
| `KALENDEE_MAIL_CLOUDFLARE_TIMEOUT_SECONDS` | `mail.cloudflare.timeoutSeconds` | `10` | Worker request timeout. |
| `KALENDEE_MAILGUN_API_KEY` | `mail.mailgun.apiKey` | *unset* | Mailgun private API key. |
| `KALENDEE_MAILGUN_DOMAIN` | `mail.mailgun.domain` | *unset* | Verified Mailgun sending domain. |
| `KALENDEE_MAILGUN_REGION` | `mail.mailgun.region` | `us` | `us` or `eu`; selects the API host. |
| `KALENDEE_MAILGUN_BASE_URL` | `mail.mailgun.baseUrl` | `""` | Optional API base URL override. |
| `KALENDEE_MAILGUN_TIMEOUT_SECONDS` | `mail.mailgun.timeoutSeconds` | `10` | Mailgun request timeout. |

### Storage

| Variable | HOCON key | Default | Notes |
| --- | --- | --- | --- |
| `KALENDEE_AVATAR_DIR` | `storage.localDir` | `build/avatars` (`/data/avatars` in the image) | Local object directory. |
| `KALENDEE_STORAGE_PUBLIC_URL` | `storage.publicBaseUrl` | `""` | Public object base URL for redirect-based serving. |
| `KALENDEE_S3_ENABLED` | `storage.s3.enabled` | `false` | Enable the S3-compatible backend. |
| `KALENDEE_S3_ENDPOINT` | `storage.s3.endpoint` | `""` | S3 endpoint (R2: `https://<account>.r2.cloudflarestorage.com`). |
| `KALENDEE_S3_REGION` | `storage.s3.region` | `us-east-1` | Region; `auto` for R2. |
| `KALENDEE_S3_BUCKET` | `storage.s3.bucket` | `""` | Bucket name. |
| `KALENDEE_S3_ACCESS_KEY` | `storage.s3.accessKey` | *unset* | Access key id. |
| `KALENDEE_S3_SECRET_KEY` | `storage.s3.secretKey` | *unset* | Secret access key. |
| `KALENDEE_S3_PATH_STYLE` | `storage.s3.pathStyle` | `true` | Path-style addressing; required by R2. |

### Keel web pack

| Variable | HOCON key | Default | Notes |
| --- | --- | --- | --- |
| `KALENDEE_KEEL_PACK` | `keel.packDir` | *unset* | Exploded pack directory or `.feb` file. Leave unset in production. |

## Compose-only variables

These are read by `docker-compose.yml` / `docker-compose.dev.yml`, not by the
server. They configure the container runtime, the port mapping, and the
PostgreSQL service.

| Variable | Used by | Default | Notes |
| --- | --- | --- | --- |
| `KALENDEE_CONFIG` | entrypoint / dev compose | `/config/application.conf` | Path to the HOCON file the container loads. See precedence above. |
| `KALENDEE_HOST_PORT` | compose port mapping | `8080` | Host-side port published to container port `8080`. |
| `KALENDEE_IMAGE_TAG` | compose `image:` | `latest` | Tag of `docker.yuri.capital/kolektiv/kalendee`. Pin a release for reproducibility. |
| `KALENDEE_UID` | dev compose `user:` | `1000` | Host UID the dev container runs as (Linux only; remove `user:` on Docker Desktop). |
| `KALENDEE_GID` | dev compose `user:` | `1000` | Host GID for the dev container. |
| `POSTGRES_DB` | postgres service | `kalendee` | Database bootstrapped in the postgres container. Match `KALENDEE_DATABASE_URL`. |
| `POSTGRES_USER` | postgres service | `kalendee` | PostgreSQL role bootstrapped. Match `KALENDEE_DATABASE_USER`. |
| `POSTGRES_PASSWORD` | postgres service | *required in `.env`* | Composes `.env.example` sets this equal to `KALENDEE_DATABASE_PASSWORD`; both must match. |
| `JAVA_OPTS` | entrypoint | *unset* | Extra JVM flags, e.g. `-Xmx512m`. Word-split deliberately by the entrypoint. |

`POSTGRES_PASSWORD` is required by `docker-compose.yml`
(`${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD in .env}`), and the server's
`KALENDEE_DATABASE_PASSWORD` must be the same value.

## Minimal production `.env`

The secrets the examples expect to live in `.env`:

```dotenv
POSTGRES_PASSWORD=change-me-strong-database-password
KALENDEE_DATABASE_PASSWORD=change-me-strong-database-password
KALENDEE_ADMIN_PASSWORD=change-me-strong-admin-password
KALENDEE_SECRET_KEY=
# KALENDEE_MAIL_CLOUDFLARE_TOKEN=
# KALENDEE_MAILGUN_API_KEY=
# KALENDEE_S3_ACCESS_KEY=
# KALENDEE_S3_SECRET_KEY=
```

Generate the token-vault key with `openssl rand -base64 32`. Leave
`KALENDEE_SECRET_KEY` empty until you need external-calendar connections.

## Related pages

- [Configuration](/docs/self-hosting/configuration) — the HOCON reference.
- [Database](/docs/self-hosting/database) — JDBC and migrations.
- [Email](/docs/self-hosting/email) — mail transports.
- [Object storage](/docs/self-hosting/object-storage) — local/S3 and R2.
- [Docker Compose](/docs/self-hosting/docker-compose) — the compose files these
  variables feed.
