---
title: Cloudflare Workers
description: >-
  Deploy the two Kalendee Workers — the Email Routing mailer and the private-R2
  read gateway — and wire the server to them with the exact HOCON keys.
---

The [`workers/`](https://github.com/KolektivComputer/kalendee/tree/main/workers)
directory contains two independent Cloudflare Workers that keep edge concerns
out of the Kotlin process:

| Worker | Directory | Purpose | Server keys |
| --- | --- | --- | --- |
| `kalendee-mailer` | `workers/mailer` | HTTP → Email Routing `send_email` bridge. | `mail.provider`, `mail.cloudflare.endpoint`, `mail.cloudflare.token`, `mail.cloudflare.timeoutSeconds` |
| `kalendee-r2` | `workers/r2` | Read gateway in front of a private R2 bucket. | `storage.publicBaseUrl` (reads), `storage.s3.*` (writes) |

Both are TypeScript ESM projects with their own `wrangler.toml`, package.json,
and pnpm lockfile. They must not import from `:core`, `:app`, or `:server`; the
server talks to them over HTTP.

## Prerequisites

- Cloudflare account with **Workers**, **Email Routing**, and **R2** available.
- Node 22+ and pnpm 11.25.0 (matching `server/pack`).
- Wrangler, installed per worker as a devDependency (`wrangler@4.x`).
- Authenticated Wrangler: `npx wrangler login`, or a `CLOUDFLARE_API_TOKEN` in
  the environment. Secrets are never committed.

The package scripts are the same in both workers:

| Script | Command | Purpose |
| --- | --- | --- |
| `pnpm install` | install devDependencies | One-time setup. |
| `pnpm run dev` | `wrangler dev` | Local Worker server. |
| `pnpm run typecheck` | `tsc --noEmit` | Type-check. |
| `pnpm run deploy` | `wrangler deploy` | Publish to Cloudflare. |

## Deploying the mailer Worker

### 1. Enable Email Routing and verify addresses

In the Cloudflare dashboard, enable **Email Routing** for the sending zone (this
provisions the MX records). Under **Email Routing → Settings**, verify the
sender domain/address. Cloudflare refuses recipients that are not verified — on
some plans each destination address must also be verified. Mail only flows once
the relevant addresses are verified.

### 2. Add the `send_email` binding

Uncomment the binding in `workers/mailer/wrangler.toml`:

```toml
[[send_email]]
name = "MAILER"
```

Until this is uncommented, `env.MAILER` is undefined and every send fails at
runtime with a `502`.

### 3. Set the shared secret

```bash
wrangler secret put MAILER_TOKEN
```

Use a long random value. For local development put it in a git-ignored
`.dev.vars` file instead:

```dotenv
MAILER_TOKEN=local-dev-token
```

The `[vars] MAIL_FROM` placeholder in `wrangler.toml` is **not read by the
Worker code**; the address is taken from the request body, which the server
fills from `mail.from`. Set `mail.from`, not `MAIL_FROM`.

### 4. Deploy

```bash
cd workers/mailer
pnpm install
pnpm run typecheck
pnpm run deploy
```

Wrangler prints the deployed URL, typically
`https://kalendee-mailer.<your-subdomain>.workers.dev`. Point the server at it:

```hocon
mail {
    enabled = true
    provider = "cloudflare"
    cloudflare {
        endpoint = "https://kalendee-mailer.<your-subdomain>.workers.dev"
        token    = "same-value-as-MAILER_TOKEN"
        timeoutSeconds = 10
    }
}
```

Or via the environment:

```dotenv
KALENDEE_MAIL_PROVIDER=cloudflare
KALENDEE_MAIL_CLOUDFLARE_ENDPOINT=https://kalendee-mailer.<subdomain>.workers.dev
KALENDEE_MAIL_CLOUDFLARE_TOKEN=<same-value-as-MAILER_TOKEN>
```

### Mailer behaviour and caveats

- `POST /` only; any other method returns `405`.
- Requires `Authorization: Bearer <MAILER_TOKEN>`; missing or wrong token is
  `401`. If `MAILER_TOKEN` is unset, the Worker logs an error and rejects.
- Returns `204` when the binding accepts the message, `400` for invalid JSON or
  a missing required field, `502` when `send_email` fails.
- `from`, `to`, `subject`, and `text` are required; `html` is forwarded only
  when it is a non-empty string.
- It is server-to-server only and sends **no CORS headers** — do not call it
  from a browser.

## Deploying the R2 read gateway

### 1. Create the bucket

```bash
wrangler r2 bucket create kalendee
```

The server writes to this bucket over R2's S3 API; see
[Object storage](/docs/self-hosting/object-storage) for the API token and
`storage.s3.*` settings.

### 2. Bind the bucket

Uncomment the binding in `workers/r2/wrangler.toml` and set the bucket name:

```toml
[[r2_buckets]]
binding = "BUCKET"
bucket_name = "kalendee"
```

### 3. Optionally require a read token

```bash
wrangler secret put READ_TOKEN
```

When set, every request must present it as `?token=<READ_TOKEN>` or
`Authorization: Bearer <READ_TOKEN>`; otherwise the response is `403`. When
unset, reads are anonymous. For local development, `.dev.vars`:

```dotenv
READ_TOKEN=local-dev-token
```

> **Do not set `READ_TOKEN` if you rely on `storage.publicBaseUrl` for avatars.**
> The server redirects clients to `${publicBaseUrl}/${key}?v=<version>` without
> the token, so those reads would be `403`. Leave `READ_TOKEN` unset for
> anonymous avatar serving, or serve through a public R2 custom domain.

### 4. Deploy

```bash
cd workers/r2
pnpm install
pnpm run typecheck
pnpm run deploy
```

Point `storage.publicBaseUrl` at the Worker URL:

```hocon
storage {
    publicBaseUrl = "https://kalendee-r2.<your-subdomain>.workers.dev"
}
```

### R2 gateway behaviour

- `GET` and `HEAD` only; anything else is `405`.
- The object key is the URL pathname minus the leading slash, so nested keys
  work: `/avatars/<userId>/<uuid>.webp`.
- Keys containing `..` or starting with `/` are rejected with `400`; an empty
  path is `404`; a missing object is `404`.
- Response headers: `Content-Type` (object metadata, then extension, then
  `application/octet-stream`), `ETag`, `Cache-Control`
  (`public, max-age=31536000, immutable` for content-hashed/UUID keys, otherwise
  `public, max-age=86400`), and `Access-Control-Allow-Origin: *` for `image/*`.
- `If-None-Match` matching the object ETag returns `304`.
- `GET` streams `object.body`; `HEAD` uses `BUCKET.head` and sends no body.

## Full wiring summary

| Direction | Server key | Value |
| --- | --- | --- |
| Outbound mail | `mail.provider` | `"cloudflare"` |
| Outbound mail | `mail.cloudflare.endpoint` | Mailer Worker URL |
| Outbound mail | `mail.cloudflare.token` | Same value as the Worker's `MAILER_TOKEN` |
| Avatar/object writes | `storage.s3.*` | R2 S3 endpoint, bucket, path-style, API token |
| Avatar/object reads | `storage.publicBaseUrl` | R2 Worker URL (no `READ_TOKEN`) or R2 custom domain |

## Related pages

- [Email](/docs/self-hosting/email) — the Cloudflare mail transport.
- [Object storage](/docs/self-hosting/object-storage) — R2 bucket and API token.
- [Configuration](/docs/self-hosting/configuration) — `mail.*` and `storage.*`.
- [Environment variables](/docs/self-hosting/environment) — the env fallbacks
  for the same keys.
