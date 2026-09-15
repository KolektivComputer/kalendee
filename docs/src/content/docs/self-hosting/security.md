---
title: Security
description: >-
  Secrets handling, session cookies, password hashing, TLS, least privilege, API
  exposure, rate limiting, and the AGPL network-service source obligation.
---

## Secrets handling

Configuration is HOCON-file-first, but secrets should never be in the HOCON
file. The split is:

| Kind of value | Where it goes | Examples |
| --- | --- | --- |
| Non-secret settings | `application.conf` | `app.baseUrl`, `ktor.deployment.port`, `auth.registration`, `mail.host`, `storage.s3.bucket` |
| Secrets | Environment (`.env`, `EnvironmentFile`, `environmentFile`) | `KALENDEE_DATABASE_PASSWORD`, `KALENDEE_ADMIN_PASSWORD`, `KALENDEE_SECRET_KEY`, SMTP password, OAuth client secrets, S3 keys |

The HOCON file references secrets with `${?VAR}`, so the same file works with or
without an environment override. Rules of thumb:

- Never commit `.env`, `application.conf` with secrets, or any credential file.
  The repository's `.gitignore` covers `.env`, but verify.
- In the Docker image the config is bind-mounted read-only at
  `/config/application.conf`; keep it `0640` and owned by the operator, not
  world-readable.
- On NixOS, `configFile` is copied into the world-readable Nix store. Treat
  everything in it as public and put all secrets in `environmentFile`
  (sops-nix/agenix).
- `KALENDEE_DATABASE_PASSWORD` is required. An empty value is accepted only for
  trust authentication; do not use trust auth on a network-reachable database.
- `KALENDEE_SECRET_KEY` (base64; 32 bytes recommended — generate with
  `openssl rand -base64 32`; 16, 24, or 32 bytes are accepted) encrypts
  external-calendar tokens with AES-GCM. It is required before
  any OAuth provider can be connected. Losing it makes every token unreadable;
  there is no plaintext fallback. Keep a backup in your secret store.
- `KALENDEE_SECRET_KEYS` accepts a JSON rotation map,
  `{"1":"<base64>","2":"<base64>"}`; new writes use the highest version and old
  versions remain decryptable. Rotate by adding a key, restarting, and retiring
  the old version later.

## Session cookies

Session cookies are created with these attributes:

| Attribute | Value |
| --- | --- |
| Name | `auth.cookieName` / `KALENDEE_SESSION_COOKIE_NAME` (default `kalendee_session`) |
| `HttpOnly` | Always `true`. |
| `SameSite` | `Lax`. |
| `Path` | `/`. |
| `Secure` | `auth.cookieSecure` / `KALENDEE_COOKIE_SECURE` (default `true` outside development). |
| `Max-Age` | `auth.sessionDays` / `KALENDEE_AUTH_SESSION_DAYS` (default 30 days). |

Keep `cookieSecure = true` in production. `Lax` is what allows the OAuth
callback and top-level email links to carry the session without weakening CSRF
posture; leave it alone. Sessions are opaque random tokens stored hashed in the
`sessions` table, not signed cookies, so there is no signing key to rotate. To
log everyone out, delete the rows:

```sql
DELETE FROM sessions;
-- or a single user's sessions:
DELETE FROM sessions WHERE user_id = (SELECT id FROM users WHERE username = 'alice');
```

Restoring an earlier database likewise reverts session state.

Reduce `sessionDays` if your threat model wants shorter-lived sessions than 30
days; there is no refresh-token mechanism.

## Argon2 tuning

Passwords are hashed with Argon2id. The defaults are the OWASP baseline:

| Key | Env | Default | Meaning |
| --- | --- | --- | --- |
| `auth.argon2.memoryKib` | `KALENDEE_ARGON2_MEMORY_KIB` | `19456` (19 MiB) | Memory cost per hash. |
| `auth.argon2.iterations` | `KALENDEE_ARGON2_ITERATIONS` | `2` | Time cost. |
| `auth.argon2.parallelism` | `KALENDEE_ARGON2_PARALLELISM` | `1` | Threads per hash. |

Each concurrent login or registration allocates its memory cost, so a login
spike multiplies resident memory: at the default, ten simultaneous logins cost
roughly 190 MiB on top of the heap. Raise `iterations` or `memoryKib` only if
you have headroom and want stronger hashing; lowering them weakens offline
guessing resistance. Changes apply to new hashes; existing hashes keep working
because the parameters are encoded in each hash string.

## TLS and transport

The server speaks HTTP and must sit behind a TLS-terminating proxy in
production:

- Keep `auth.cookieSecure = true`; a `Secure` cookie is dropped over plain HTTP.
- Set `app.baseUrl` to the public `https://` URL so emailed links and OAuth
  redirect URIs use HTTPS.
- Add HSTS and the other security headers at the proxy; the server does not set
  them. See [Reverse proxy](/docs/self-hosting/reverse-proxy).
- Do not publish port `8080` to the internet; bind it to localhost or keep it on
  an internal network and expose only the proxy.
- Forward `X-Forwarded-For` and `X-Forwarded-Proto`. Kalendee does not install
  Ktor's `XForwardedHeaders` plugin, so it ignores them itself, but your proxy
  and any WAF in front benefit from them.

## Least privilege

| Surface | Posture |
| --- | --- |
| Container user | UID/GID `10001`, `nologin` shell, no `root`. |
| Container filesystem | Only `/data` (avatars) and, if mounted, `/config` need to be writable; the config mount is read-only. |
| Database role | The app role owns its database and nothing else. Do not use a cluster superuser. |
| systemd service | Run as a dedicated user with `NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome`, and `ReadWritePaths` limited to the avatar directory. See [Standalone binary](/docs/self-hosting/binary). |
| Outbound | Allow only the mail, storage, and OAuth endpoints you use (see [Requirements](/docs/self-hosting/requirements)). |
| Secrets at rest | Root-owned `0600` environment files, or a secret manager; never in the image or the Nix store. |

## Exposing `/api/v1`

The JSON API requires a valid session for almost everything. The paths that are
intentionally unauthenticated are:

| Path | Purpose |
| --- | --- |
| `GET /api/v1` and `/api/v1/health` | Discovery and health. |
| `POST /api/v1/auth/login`, `/register`, `/verify-email`, `/resend-verification` | Authentication flows. |
| `GET /api/v1/users/{id}/avatar` | Public avatar read. |
| `/api/v1/public/*` | Public calendar and profile views. |
| `GET /api/v1/oauth/{provider}/callback` | OAuth redirect target. |

Everything else returns `401`/`403` without a session. Admin routes additionally
require the admin flag. This is authorization at the application layer; it is not
a substitute for network controls, so keep the API behind the proxy and a
firewall. If you front it with a CDN or cache, exclude `/api/v1/*` from caching
except for the explicitly public avatar and pack assets.

## Rate limiting

The server has one built-in limiter: failed logins are throttled to 5 attempts
per 60 seconds per client address (in memory, per instance, reset on restart).
Because the app does not read forwarded headers, that address is the **proxy**,
not the end user, so it throttles everyone behind the proxy together and can be
bypassed by hitting another instance. Add real rate limiting at the proxy.

For example, nginx:

```nginx
limit_req_zone $binary_remote_addr zone=kalendee_login:10m rate=10r/m;

location = /api/v1/auth/login {
    limit_req zone=kalendee_login burst=5 nodelay;
    proxy_pass http://127.0.0.1:8080;
    # ... the usual proxy headers ...
}
```

Apply stricter limits to `/api/v1/auth/login`, `/register`, and
`/resend-verification`; a broad per-IP limit across `/api/v1` is reasonable too.
Caddy and Traefik have equivalent plugins/middlewares.

## AGPL-3.0-only network obligation

Kalendee is licensed **AGPL-3.0-only**. The AGPL's Section 13 extends the GPL to
network use: if you run a **modified** version of Kalendee and let users interact
with it over a network, you must offer those users the Corresponding Source of
your modified version. Running an unmodified build does not trigger an
obligation beyond the license terms, but you must still preserve notices and
make the source available on request consistent with the license.

Practical implications for operators:

- Keep a link to the source of the version you run, and to your modifications if
  any, somewhere users can find it.
- If you patch Kalendee for your deployment, publish the patch (a fork, a public
  diff, or a source offer) to avoid violating the license.
- Do not remove license or copyright headers. See the repository
  [`LICENSE`](https://github.com/kolektivdev/kalendee/blob/main/LICENSE).

This is a plain-language summary, not legal advice; consult the license text and
counsel for your situation.
