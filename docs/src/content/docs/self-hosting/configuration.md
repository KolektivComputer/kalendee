---
title: Configuration
description: >-
  The HOCON reference for a self-hosted Kalendee server: config-file precedence,
  ${?ENV} substitutions, and every key in ktor, database, auth, app, oauth, mail,
  storage, and keel.
---

Kalendee is configured with a single [HOCON](https://github.com/lightbend/config/blob/main/HOCON.md)
file loaded by Ktor's `EngineMain`. The authoritative defaults live in
[`server/src/main/resources/application.conf`](https://github.com/KolektivComputer/kalendee/blob/main/server/src/main/resources/application.conf),
baked into the fat jar. Operators normally supply their own copy; the commented
starting points are
[`application.conf.example`](https://github.com/KolektivComputer/kalendee/blob/main/application.conf.example)
(production) and
[`application.conf.dev.example`](https://github.com/KolektivComputer/kalendee/blob/main/application.conf.dev.example)
(development).

For the environment-variable view of the same keys see
[Environment variables](/docs/self-hosting/environment).

## How configuration is loaded

### Precedence

The Docker entrypoint (`docker/entrypoint.sh`) resolves the config file before
starting the JVM. Highest priority first:

| Order | Source | Notes |
| --- | --- | --- |
| 1 | `-config=/path` passed after the image name | Explicit operator choice; passed through untouched. Both `-config=/p` and `-config /p` are detected. |
| 2 | `KALENDEE_CONFIG` environment variable | Set in `.env`; compose defaults it to `/config/application.conf`. |
| 3 | `/config/application.conf` | The file compose mounts read-only from `./application.conf`. |
| 4 | `/app/application.conf` | Baked into the image (a copy of `application.conf.example`). |
| 5 | Jar defaults | `server/src/main/resources/application.conf` on the classpath. |

`docker-compose.yml` mounts `./application.conf:/config/application.conf:ro`
and sets `KALENDEE_CONFIG=/config/application.conf`, so entry 2 normally selects
entry 3. With nothing mounted, the image still boots on the baked config. The
standalone (non-Docker) paths are covered in
[Standalone binary](/docs/self-hosting/binary) and
[Docker Compose](/docs/self-hosting/docker-compose).

When you run `./gradlew :server:run` directly, the Gradle `run` task loads
`.env.local` into the process (real environment variables win) and the
`-config` argument is threaded through `KALENDEE_CONFIG`.

### HOCON essentials

A few rules cover everything you will write:

- The file is a tree of objects. The top level is implicit; blocks use braces:
  `database { url = "..." }` is `database.url`.
- Strings must be quoted when they contain `:`, spaces, `@`, or other special
  characters. Booleans and numbers may be unquoted.
- Comments start with `#` or `//`.
- Arrays use square brackets, e.g.
  `modules = [ dev.kolektiv.kalendee.ApplicationKt.module ]`.
- Repeating the same key assigns twice. Combined with an optional substitution
  (below), the idiom `key = <default>` followed by `key = ${?ENV}` lets the
  environment override the default while leaving the default in place when the
  variable is unset.
- This server has no duration syntax: lifetimes are plain integers with a unit
  in the key name (`sessionDays`, `emailVerificationTtlHours`, `timeoutSeconds`).

### `${?ENV}` substitution

Every tunable in the baseline config ends with an optional substitution:

```hocon
auth {
    sessionDays = 30
    sessionDays = ${?KALENDEE_AUTH_SESSION_DAYS}
}
```

`${?VAR}` is Typesafe Config syntax and resolves from JVM system properties and
process environment variables. If `VAR` is unset the substitution is undefined
and the preceding value (`30`) remains; if it is set, its value replaces the
default. Because the substitution only overrides keys that already exist in the
loaded file, **environment variables cannot introduce keys your HOCON file does
not contain.** A fully standalone config must therefore carry every key you want
to set from the environment — which is why both example files repeat the
`${?VAR}` lines.

A standalone config file also has to carry the Ktor module entry point, because
it is loaded instead of the jar's config rather than merged with it:

```hocon
ktor {
    application {
        modules = [ dev.kolektiv.kalendee.ApplicationKt.module ]
    }
}
```

### `ktor.deployment.*`

| Key | Type | Default | Env fallback | Meaning |
| --- | --- | --- | --- | --- |
| `ktor.deployment.port` | integer | `8080` | `KALENDEE_HTTP_PORT` | TCP port the server binds. Can also be overridden with `-port=<n>`. |
| `ktor.deployment.host` | string | `"0.0.0.0"` | `KALENDEE_HTTP_HOST` | Interface to bind. `0.0.0.0` listens on all interfaces, correct for a container behind a proxy. |
| `ktor.application.modules` | array | `[ dev.kolektiv.kalendee.ApplicationKt.module ]` | — | Ktor module entry point. Required in a standalone config. |

The container always exposes port `8080`; the host-side mapping is the
compose-only `KALENDEE_HOST_PORT` (see
[Environment variables](/docs/self-hosting/environment)).

### `database.*`

Full operational detail is in [Database](/docs/self-hosting/database).

| Key | Type | Default | Env fallback | Meaning |
| --- | --- | --- | --- | --- |
| `database.url` | string | `"jdbc:postgresql://127.0.0.1:5432/kalendee"` | `KALENDEE_DATABASE_URL` | JDBC URL. May embed `user:password@`; the server splits and strips it. |
| `database.user` | string | `"kalendee"` (or parsed from the URL) | `KALENDEE_DATABASE_USER` | Database role. |
| `database.password` | string | *required, no default* | `KALENDEE_DATABASE_PASSWORD` | Database password. An empty value is allowed for trust/peer auth. |
| `database.migrations` | string | `"classpath:db/migration"` | — | Flyway migration location. Not present in the example config; only useful for tests or external migrations. Values without a `classpath:`/`filesystem:` prefix are treated as filesystem paths. |

If `database.password` is unset and the JDBC URL carries no `password`, startup
fails with `KALENDEE_DATABASE_PASSWORD is required`.

### `auth.*`

| Key | Type | Default | Env fallback | Meaning |
| --- | --- | --- | --- | --- |
| `auth.registration` | enum string | `"first-user"` | `KALENDEE_AUTH_REGISTRATION` | Public sign-up policy: `first-user`, `open`, or `closed`. Case-insensitive; `first_user`/`firstuser` also parse as `first-user`. |
| `auth.sessionDays` | integer | `30` | `KALENDEE_AUTH_SESSION_DAYS` | Session lifetime in days. |
| `auth.cookieName` | string | `"kalendee_session"` | `KALENDEE_SESSION_COOKIE_NAME` | Session cookie name. |
| `auth.cookieSecure` | boolean | `!development` | `KALENDEE_COOKIE_SECURE` | Require HTTPS for the session cookie. Defaults to secure outside development; set `false` only when serving plain HTTP. |
| `auth.adminUsername` | string | `"admin"` | `KALENDEE_ADMIN_USERNAME` | Username created as the first admin on startup. |
| `auth.adminPassword` | string | *unset* | `KALENDEE_ADMIN_PASSWORD` | Password for that admin. Blank/unset disables seeding. |
| `auth.superadminUsername` | string | *unset* | `KALENDEE_SUPERADMIN_USERNAME` | Username granted superadmin rights, if it exists. Lowercased. |
| `auth.emailVerification` | enum string | `"optional"` | `KALENDEE_AUTH_EMAIL_VERIFICATION` | Email verification policy: `optional`, `soft`, or `required`. Only `required` blocks login until verified. |
| `auth.emailVerificationTtlHours` | integer | `24` | `KALENDEE_AUTH_EMAIL_VERIFICATION_TTL_HOURS` | Verification-link lifetime in hours. Non-positive values fall back to `24`. |
| `auth.oauthRegistration` | boolean | `false` | `KALENDEE_AUTH_OAUTH_REGISTRATION` | Allow OAuth sign-ins to create accounts. Still gated by `auth.registration`. |
| `auth.argon2.memoryKib` | integer | `19456` | `KALENDEE_ARGON2_MEMORY_KIB` | Argon2id memory cost in KiB. |
| `auth.argon2.iterations` | integer | `2` | `KALENDEE_ARGON2_ITERATIONS` | Argon2id iteration count. |
| `auth.argon2.parallelism` | integer | `1` | `KALENDEE_ARGON2_PARALLELISM` | Argon2id parallelism factor. |

Notes:

- `auth.registration` is the **initial** policy. An admin can change it at
  runtime from the admin page; the stored value wins over the config. Likewise
  `emailVerification` and `oauthRegistration` have runtime overrides.
- `first-user` keeps sign-ups open only while the `users` table is empty, so
  seeding an admin with `auth.adminPassword` closes public registration.
- `soft` verification sends a verification link and shows a prompt but does not
  block login; `required` does. There is **no `off` value** despite older
  comments in the example files.
- Argon2id is used for password hashing with a 16-byte random salt and a
  32-byte hash. Raising the costs makes new hashes slower; existing hashes keep
  verifying with the parameters they were created with.

### `app.*`

| Key | Type | Default | Env fallback | Meaning |
| --- | --- | --- | --- | --- |
| `app.baseUrl` | string | `""` | `KALENDEE_PUBLIC_URL` and `KALENDEE_BASE_URL` | Canonical public URL used for email links, RSS, and OAuth redirect URIs. Trailing slash is trimmed. `KALENDEE_PUBLIC_URL` is the preferred spelling; `KALENDEE_BASE_URL` is a deprecated alias. If **both** are set, the later substitution (`KALENDEE_BASE_URL`) wins — set only `KALENDEE_PUBLIC_URL`. |
| `app.development` | boolean | `false` | `KALENDEE_DEVELOPMENT` | Pretty HTML error page and development `baseUrl` default. Must stay `false` in production. |
| `app.publicAccess` | enum string | `"public"` | `KALENDEE_PUBLIC_ACCESS` | Instance default for anonymous access to public calendars: `public` or `signed_in`. Per-user and per-calendar overrides win. |
| `app.seedDemo` | boolean | `false` | `KALENDEE_SEED_DEMO` | Seed demo users (`demo`, `sam`, `alex`) with sample data. Development/screenshots only. |
| `app.demoPassword` | string | `"demo"` | `KALENDEE_DEMO_PASSWORD` | Password for the seeded demo users. |
| `app.demoTimezone` | string | `"Europe/Berlin"` | `KALENDEE_DEMO_TIMEZONE` | Time zone for seeded demo data. |

`app.baseUrl` behavior when blank:

| Condition | Result |
| --- | --- |
| `app.development = true` | `http://127.0.0.1:<ktor.deployment.port>` (default `http://127.0.0.1:8080`). |
| `app.development = false` | Empty; email links are logged as relative paths and logged-out RSS/OAuth flows will not have an absolute origin. **Set it explicitly in production.** |

The value should start with `http://` or `https://`; the server logs a warning
but uses it as-is otherwise. Ktor's own development flag (for engine dev-mode
behaviour) is separate from `app.development`; the dev example sets
`ktor.development = true` as well.

### `oauth.*`

Discord is the only implemented provider. A blank `clientId` disables it. See
[Discord OAuth](/docs/self-hosting/oauth-discord) for the end-to-end setup.

| Key | Type | Default | Env fallback | Meaning |
| --- | --- | --- | --- | --- |
| `oauth.discord.clientId` | string | *unset (disabled)* | `KALENDEE_DISCORD_CLIENT_ID` | Discord OAuth application client id. Blank disables the provider. |
| `oauth.discord.clientSecret` | string | *unset* | `KALENDEE_DISCORD_CLIENT_SECRET` | Discord OAuth client secret. |
| `oauth.discord.botToken` | string | *unset* | `KALENDEE_DISCORD_BOT_TOKEN` | Bot token for reading guild scheduled events. Optional: account linking works without it, event import does not. |
| `oauth.secretKey` | string | *unset* | `KALENDEE_SECRET_KEY` | Base64 AES key (16/24/32 bytes) stored as token-vault version `1`. Required before any provider can be connected. Generate with `openssl rand -base64 32`. |
| `oauth.secretKeys` | JSON object string | *unset* | `KALENDEE_SECRET_KEYS` | Optional `{"<version>":"<base64>","<version>":"<base64>"}` map for key rotation. Highest version is the sealing key; older versions remain readable. |

Values are trimmed and blank strings are treated as unset. Losing every key
makes existing encrypted connection tokens unreadable; connections must be
re-authorized.

### `mail.*`

See [Email](/docs/self-hosting/email) for provider selection and testing.

| Key | Type | Default | Env fallback | Meaning |
| --- | --- | --- | --- | --- |
| `mail.enabled` | boolean | `false` | `KALENDEE_MAIL_ENABLED` | Input to provider auto-detection. When `false` and `mail.provider` is blank, the logging mailer is used. An explicit `mail.provider` overrides it. |
| `mail.provider` | enum string | `""` (auto) | `KALENDEE_MAIL_PROVIDER` | `smtp`, `cloudflare`, `mailgun`, or `log`. Unknown values warn and fall back to `log`. Blank auto-detects. |
| `mail.host` | string | `""` | `KALENDEE_SMTP_HOST` | SMTP host. Blank disables SMTP in auto-detection. |
| `mail.port` | integer | `587` | `KALENDEE_SMTP_PORT` | SMTP port. |
| `mail.username` | string | `""` | `KALENDEE_SMTP_USER` | SMTP username. Blank disables authentication (useful for a local relay). |
| `mail.password` | string | `""` | `KALENDEE_SMTP_PASSWORD` | SMTP password. Blank becomes absent; the authenticator sends an empty string. |
| `mail.from` | string | `"Kalendee <no-reply@localhost>"` | `KALENDEE_MAIL_FROM` | `From` header for every message. |
| `mail.startTls` | boolean | `true` | `KALENDEE_SMTP_STARTTLS` | Enable STARTTLS on the SMTP connection. |
| `mail.cloudflare.endpoint` | string | `""` | `KALENDEE_MAIL_CLOUDFLARE_ENDPOINT` | Base URL of the deployed mailer Worker. |
| `mail.cloudflare.token` | string | `""` | `KALENDEE_MAIL_CLOUDFLARE_TOKEN` | Bearer token sent in `Authorization`. Must equal the Worker's `MAILER_TOKEN`. |
| `mail.cloudflare.timeoutSeconds` | integer | `10` | `KALENDEE_MAIL_CLOUDFLARE_TIMEOUT_SECONDS` | HTTP connect/request timeout. Non-positive values fall back to `10`. |
| `mail.mailgun.apiKey` | string | *unset* | `KALENDEE_MAILGUN_API_KEY` | Mailgun private API key. Blank disables Mailgun in auto-detection. |
| `mail.mailgun.domain` | string | *unset* | `KALENDEE_MAILGUN_DOMAIN` | Verified Mailgun sending domain; also the `{domain}` segment of the request path. |
| `mail.mailgun.region` | string | `"us"` | `KALENDEE_MAILGUN_REGION` | `us` or `eu`. Picks `api.mailgun.net` or `api.eu.mailgun.net` when `baseUrl` is blank. Anything other than `eu` is treated as US. |
| `mail.mailgun.baseUrl` | string | `""` | `KALENDEE_MAILGUN_BASE_URL` | Optional API base URL override for a custom Mailgun endpoint. Trailing slashes are trimmed; blank derives the host from `region`. |
| `mail.mailgun.timeoutSeconds` | integer | `10` | `KALENDEE_MAILGUN_TIMEOUT_SECONDS` | HTTP connect/request timeout. Non-positive values fall back to `10`. |

Auto-detection order when `mail.provider` is blank:

1. `mail.enabled = false` → `log`.
2. `mail.host` set → `smtp`.
3. `mail.cloudflare.endpoint` set → `cloudflare`.
4. `mail.mailgun.apiKey` and `mail.mailgun.domain` set → `mailgun`.
5. Otherwise → `log`.

`mail.enabled` is **not** a hard kill switch: setting `mail.provider`
explicitly selects that transport even if `mail.enabled = false`. The server
also validates the chosen transport at startup and falls back to the logging
mailer if `smtp` has a blank host, `cloudflare` is missing its endpoint or
token, or `mailgun` is missing its API key or domain.

Cloudflare's `send_email` Email Routing binding is a paid feature, so
`mailgun` is the non-SMTP transport to use when it is unavailable.

### `storage.*`

See [Object storage](/docs/self-hosting/object-storage) and
[Cloudflare Workers](/docs/self-hosting/cloudflare-workers).

| Key | Type | Default | Env fallback | Meaning |
| --- | --- | --- | --- | --- |
| `storage.localDir` | string | `"build/avatars"` | `KALENDEE_AVATAR_DIR` | Directory for the local filesystem backend. The image sets this to `/data/avatars`. |
| `storage.publicBaseUrl` | string | `""` | `KALENDEE_STORAGE_PUBLIC_URL` | Optional public base URL for objects (R2/CDN). When set, avatar reads `302`-redirect to `${publicBaseUrl}/${key}?v=<version>` instead of proxying bytes. |
| `storage.s3.enabled` | boolean | `false` | `KALENDEE_S3_ENABLED` | Use the S3-compatible backend. When disabled (or `bucket` is blank) `storage.localDir` is used. |
| `storage.s3.endpoint` | string | `""` | `KALENDEE_S3_ENDPOINT` | S3 endpoint. Set for non-AWS providers such as Cloudflare R2. Blank uses the AWS default. |
| `storage.s3.region` | string | `"us-east-1"` | `KALENDEE_S3_REGION` | S3 region. R2 ignores it but the SDK requires one; use `auto`. |
| `storage.s3.bucket` | string | `""` | `KALENDEE_S3_BUCKET` | Bucket name. |
| `storage.s3.accessKey` | string | *unset* | `KALENDEE_S3_ACCESS_KEY` | Access key id. If both keys are blank the SDK default credential chain is used. |
| `storage.s3.secretKey` | string | *unset* | `KALENDEE_S3_SECRET_KEY` | Secret access key. |
| `storage.s3.pathStyle` | boolean | `true` | `KALENDEE_S3_PATH_STYLE` | Path-style addressing. Required by Cloudflare R2. |

If either S3 credential key is non-null, a static credentials provider is built
from both values (`accessKey.orEmpty()` / `secretKey.orEmpty()`), so supply both
or neither.

### `keel.*`

| Key | Type | Default | Env fallback | Meaning |
| --- | --- | --- | --- | --- |
| `keel.packDir` | string | *unset* | `KALENDEE_KEEL_PACK` | Exploded Keel pack (directory) or a `.feb` file to load instead of the pack bundled in the jar. Intended for development/watch mode; leave unset in production. |

## Complete production example

A standalone config ready to mount at `/config/application.conf`. Replace the
placeholder URL and keep secrets in `.env`.

```hocon
ktor {
    deployment {
        port = 8080
        port = ${?KALENDEE_HTTP_PORT}
        host = "0.0.0.0"
        host = ${?KALENDEE_HTTP_HOST}
    }
    application {
        modules = [ dev.kolektiv.kalendee.ApplicationKt.module ]
    }
}

database {
    url = "jdbc:postgresql://postgres:5432/kalendee"
    url = ${?KALENDEE_DATABASE_URL}
    user = "kalendee"
    user = ${?KALENDEE_DATABASE_USER}
    password = ${?KALENDEE_DATABASE_PASSWORD}
}

auth {
    registration = "first-user"
    registration = ${?KALENDEE_AUTH_REGISTRATION}
    sessionDays = 30
    sessionDays = ${?KALENDEE_AUTH_SESSION_DAYS}
    cookieName = "kalendee_session"
    cookieName = ${?KALENDEE_SESSION_COOKIE_NAME}
    cookieSecure = true
    cookieSecure = ${?KALENDEE_COOKIE_SECURE}
    adminUsername = "admin"
    adminUsername = ${?KALENDEE_ADMIN_USERNAME}
    adminPassword = ${?KALENDEE_ADMIN_PASSWORD}
    superadminUsername = ${?KALENDEE_SUPERADMIN_USERNAME}
    emailVerification = "optional"
    emailVerification = ${?KALENDEE_AUTH_EMAIL_VERIFICATION}
    emailVerificationTtlHours = 24
    emailVerificationTtlHours = ${?KALENDEE_AUTH_EMAIL_VERIFICATION_TTL_HOURS}
    oauthRegistration = false
    oauthRegistration = ${?KALENDEE_AUTH_OAUTH_REGISTRATION}
    argon2 {
        memoryKib = 19456
        memoryKib = ${?KALENDEE_ARGON2_MEMORY_KIB}
        iterations = 2
        iterations = ${?KALENDEE_ARGON2_ITERATIONS}
        parallelism = 1
        parallelism = ${?KALENDEE_ARGON2_PARALLELISM}
    }
}

app {
    baseUrl = "https://calendar.example.com"
    baseUrl = ${?KALENDEE_PUBLIC_URL}
    baseUrl = ${?KALENDEE_BASE_URL}
    development = false
    development = ${?KALENDEE_DEVELOPMENT}
    publicAccess = "public"
    publicAccess = ${?KALENDEE_PUBLIC_ACCESS}
    seedDemo = false
    seedDemo = ${?KALENDEE_SEED_DEMO}
    demoPassword = "demo"
    demoPassword = ${?KALENDEE_DEMO_PASSWORD}
    demoTimezone = "Europe/Berlin"
    demoTimezone = ${?KALENDEE_DEMO_TIMEZONE}
}

oauth {
    discord {
        clientId = ${?KALENDEE_DISCORD_CLIENT_ID}
        clientSecret = ${?KALENDEE_DISCORD_CLIENT_SECRET}
        botToken = ${?KALENDEE_DISCORD_BOT_TOKEN}
    }
    secretKey = ${?KALENDEE_SECRET_KEY}
    secretKeys = ${?KALENDEE_SECRET_KEYS}
}

mail {
    enabled = true
    enabled = ${?KALENDEE_MAIL_ENABLED}
    provider = "cloudflare"
    provider = ${?KALENDEE_MAIL_PROVIDER}
    host = ""
    host = ${?KALENDEE_SMTP_HOST}
    port = 587
    port = ${?KALENDEE_SMTP_PORT}
    username = ""
    username = ${?KALENDEE_SMTP_USER}
    password = ""
    password = ${?KALENDEE_SMTP_PASSWORD}
    from = "Kalendee <no-reply@example.com>"
    from = ${?KALENDEE_MAIL_FROM}
    startTls = true
    startTls = ${?KALENDEE_SMTP_STARTTLS}
    cloudflare {
        endpoint = "https://kalendee-mailer.<subdomain>.workers.dev"
        endpoint = ${?KALENDEE_MAIL_CLOUDFLARE_ENDPOINT}
        token = ${?KALENDEE_MAIL_CLOUDFLARE_TOKEN}
        timeoutSeconds = 10
        timeoutSeconds = ${?KALENDEE_MAIL_CLOUDFLARE_TIMEOUT_SECONDS}
    }
    mailgun {
        apiKey = ${?KALENDEE_MAILGUN_API_KEY}
        domain = ${?KALENDEE_MAILGUN_DOMAIN}
        region = "us"
        region = ${?KALENDEE_MAILGUN_REGION}
        baseUrl = ""
        baseUrl = ${?KALENDEE_MAILGUN_BASE_URL}
        timeoutSeconds = 10
        timeoutSeconds = ${?KALENDEE_MAILGUN_TIMEOUT_SECONDS}
    }
}

storage {
    localDir = "/data/avatars"
    localDir = ${?KALENDEE_AVATAR_DIR}
    publicBaseUrl = "https://kalendee-r2.<subdomain>.workers.dev"
    publicBaseUrl = ${?KALENDEE_STORAGE_PUBLIC_URL}
    s3 {
        enabled = true
        enabled = ${?KALENDEE_S3_ENABLED}
        endpoint = "https://<account-id>.r2.cloudflarestorage.com"
        endpoint = ${?KALENDEE_S3_ENDPOINT}
        region = "auto"
        region = ${?KALENDEE_S3_REGION}
        bucket = "<bucket-name>"
        bucket = ${?KALENDEE_S3_BUCKET}
        accessKey = ${?KALENDEE_S3_ACCESS_KEY}
        secretKey = ${?KALENDEE_S3_SECRET_KEY}
        pathStyle = true
        pathStyle = ${?KALENDEE_S3_PATH_STYLE}
    }
}

keel {
    packDir = ${?KALENDEE_KEEL_PACK}
}
```

## Complete development example

The dev example mirrors the same key set with local defaults. It is mounted at
`/config/application.conf` by `docker-compose.dev.yml`.

```hocon
ktor {
    development = true
    deployment {
        port = 8080
        port = ${?KALENDEE_HTTP_PORT}
        host = "0.0.0.0"
        host = ${?KALENDEE_HTTP_HOST}
    }
    application {
        modules = [ dev.kolektiv.kalendee.ApplicationKt.module ]
    }
}

database {
    url = "jdbc:postgresql://postgres:5432/kalendee"
    url = ${?KALENDEE_DATABASE_URL}
    user = "kalendee"
    user = ${?KALENDEE_DATABASE_USER}
    password = "kalendee"
    password = ${?KALENDEE_DATABASE_PASSWORD}
}

auth {
    registration = "first-user"
    registration = ${?KALENDEE_AUTH_REGISTRATION}
    sessionDays = 30
    sessionDays = ${?KALENDEE_AUTH_SESSION_DAYS}
    cookieName = "kalendee_session"
    cookieName = ${?KALENDEE_SESSION_COOKIE_NAME}
    cookieSecure = false
    cookieSecure = ${?KALENDEE_COOKIE_SECURE}
    adminUsername = "admin"
    adminUsername = ${?KALENDEE_ADMIN_USERNAME}
    adminPassword = "admin"
    adminPassword = ${?KALENDEE_ADMIN_PASSWORD}
    superadminUsername = ${?KALENDEE_SUPERADMIN_USERNAME}
    emailVerification = "optional"
    emailVerification = ${?KALENDEE_AUTH_EMAIL_VERIFICATION}
    emailVerificationTtlHours = 24
    emailVerificationTtlHours = ${?KALENDEE_AUTH_EMAIL_VERIFICATION_TTL_HOURS}
    oauthRegistration = false
    oauthRegistration = ${?KALENDEE_AUTH_OAUTH_REGISTRATION}
    argon2 {
        memoryKib = 19456
        memoryKib = ${?KALENDEE_ARGON2_MEMORY_KIB}
        iterations = 2
        iterations = ${?KALENDEE_ARGON2_ITERATIONS}
        parallelism = 1
        parallelism = ${?KALENDEE_ARGON2_PARALLELISM}
    }
}

app {
    baseUrl = "http://localhost:8080"
    baseUrl = ${?KALENDEE_PUBLIC_URL}
    baseUrl = ${?KALENDEE_BASE_URL}
    development = true
    development = ${?KALENDEE_DEVELOPMENT}
    publicAccess = "public"
    publicAccess = ${?KALENDEE_PUBLIC_ACCESS}
    seedDemo = true
    seedDemo = ${?KALENDEE_SEED_DEMO}
    demoPassword = "demo"
    demoPassword = ${?KALENDEE_DEMO_PASSWORD}
    demoTimezone = "Europe/Berlin"
    demoTimezone = ${?KALENDEE_DEMO_TIMEZONE}
}

oauth {
    discord {
        clientId = ${?KALENDEE_DISCORD_CLIENT_ID}
        clientSecret = ${?KALENDEE_DISCORD_CLIENT_SECRET}
        botToken = ${?KALENDEE_DISCORD_BOT_TOKEN}
    }
    secretKey = ${?KALENDEE_SECRET_KEY}
    secretKeys = ${?KALENDEE_SECRET_KEYS}
}

mail {
    enabled = false
    enabled = ${?KALENDEE_MAIL_ENABLED}
    provider = ""
    provider = ${?KALENDEE_MAIL_PROVIDER}
    host = ""
    host = ${?KALENDEE_SMTP_HOST}
    port = 587
    port = ${?KALENDEE_SMTP_PORT}
    username = ""
    username = ${?KALENDEE_SMTP_USER}
    password = ""
    password = ${?KALENDEE_SMTP_PASSWORD}
    from = "Kalendee <no-reply@localhost>"
    from = ${?KALENDEE_MAIL_FROM}
    startTls = true
    startTls = ${?KALENDEE_SMTP_STARTTLS}
    cloudflare {
        endpoint = ""
        endpoint = ${?KALENDEE_MAIL_CLOUDFLARE_ENDPOINT}
        token = ""
        token = ${?KALENDEE_MAIL_CLOUDFLARE_TOKEN}
        timeoutSeconds = 10
        timeoutSeconds = ${?KALENDEE_MAIL_CLOUDFLARE_TIMEOUT_SECONDS}
    }
    mailgun {
        apiKey = ${?KALENDEE_MAILGUN_API_KEY}
        domain = ${?KALENDEE_MAILGUN_DOMAIN}
        region = "us"
        region = ${?KALENDEE_MAILGUN_REGION}
        baseUrl = ""
        baseUrl = ${?KALENDEE_MAILGUN_BASE_URL}
        timeoutSeconds = 10
        timeoutSeconds = ${?KALENDEE_MAILGUN_TIMEOUT_SECONDS}
    }
}

storage {
    localDir = "/src/build/avatars"
    localDir = ${?KALENDEE_AVATAR_DIR}
    publicBaseUrl = ""
    publicBaseUrl = ${?KALENDEE_STORAGE_PUBLIC_URL}
    s3 {
        enabled = false
        enabled = ${?KALENDEE_S3_ENABLED}
        endpoint = ""
        endpoint = ${?KALENDEE_S3_ENDPOINT}
        region = "us-east-1"
        region = ${?KALENDEE_S3_REGION}
        bucket = ""
        bucket = ${?KALENDEE_S3_BUCKET}
        accessKey = ${?KALENDEE_S3_ACCESS_KEY}
        secretKey = ${?KALENDEE_S3_SECRET_KEY}
        pathStyle = true
        pathStyle = ${?KALENDEE_S3_PATH_STYLE}
    }
}

keel {
    packDir = ${?KALENDEE_KEEL_PACK}
}
```

## Related pages

- [Environment variables](/docs/self-hosting/environment) — the same keys as
  `KALENDEE_*` fallbacks.
- [Database](/docs/self-hosting/database) — PostgreSQL and migrations.
- [Object storage](/docs/self-hosting/object-storage) — local and S3 backends.
- [Email](/docs/self-hosting/email) — provider selection.
- [Cloudflare Workers](/docs/self-hosting/cloudflare-workers) — mailer and R2
  gateway.
- [Discord OAuth](/docs/self-hosting/oauth-discord) — provider setup and key
  rotation.
