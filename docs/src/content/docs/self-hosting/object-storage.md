---
title: Object storage
description: >-
  How Kalendee stores user-uploadable objects: the local filesystem backend, the
  S3-compatible backend, a complete Cloudflare R2 setup, and redirect-based
  public serving.
---

Kalendee stores user-uploadable bytes (profile pictures, with room for future
attachments) through a small `ObjectStorage` abstraction with two backends: a
local filesystem directory and any S3-compatible service. Cloudflare R2 is
supported through the S3-compatible backend.

## What is stored

| Property | Value |
| --- | --- |
| Object type today | Profile pictures (avatars). |
| Max size | 2 MiB per upload. |
| Accepted types | PNG, JPEG, WebP, GIF. |
| Object key | `avatars/<userId>/<uuid>.<ext>` (extension derived from the MIME type). |
| Read path | `GET /api/v1/users/{id}/avatar`: either the server streams the bytes, or it `302`-redirects to `storage.publicBaseUrl`. |
| Write path | `POST /api/v1/auth/me/avatar` (multipart `file`), `DELETE /api/v1/auth/me/avatar`. |

Each user row stores the current `avatarKey` and an `avatarVersion`; changing
the picture deletes the previous object and bumps the version. When
`storage.publicBaseUrl` is set, the redirect includes `?v=<version>` so caches
invalidate, and the server sets `ETag`/`Cache-Control` when it proxies locally.

## Local filesystem backend

Active when `storage.s3.enabled` is `false` **or** `storage.s3.bucket` is blank.

| Key | Default | Env fallback | Meaning |
| --- | --- | --- | --- |
| `storage.localDir` | `"build/avatars"` | `KALENDEE_AVATAR_DIR` | Root directory for objects. The image sets `KALENDEE_AVATAR_DIR=/data/avatars`. |

The local backend writes the object bytes under the key and a sibling
`<key>.type` file containing the content type. On read it returns the bytes and
the stored type (falling back to `application/octet-stream`). Keys are validated
to stay inside the root: blank keys, absolute paths, and `..` segments are
rejected.

In the Docker image, `/data` is a declared volume and `docker-compose.yml`
mounts the named volume `kalendee-avatars` there, so avatars persist across
container upgrades. Back up this volume as part of
[Backups and upgrades](/docs/self-hosting/backups-and-upgrades) when using the
local backend.

## S3-compatible backend

Active when `storage.s3.enabled = true` **and** `storage.s3.bucket` is non-blank;
otherwise the server silently falls back to the local backend. It is built on
the AWS SDK v2 S3 client with static credentials when supplied, or the SDK's
default credential chain when both keys are blank.

| Key | Type | Default | Env fallback | Meaning |
| --- | --- | --- | --- | --- |
| `storage.s3.enabled` | boolean | `false` | `KALENDEE_S3_ENABLED` | Enable the backend. |
| `storage.s3.endpoint` | string | `""` | `KALENDEE_S3_ENDPOINT` | Endpoint URL. Blank uses the AWS default endpoint. |
| `storage.s3.region` | string | `"us-east-1"` | `KALENDEE_S3_REGION` | Region. R2 ignores the value but the SDK requires one. |
| `storage.s3.bucket` | string | `""` | `KALENDEE_S3_BUCKET` | Bucket name. |
| `storage.s3.accessKey` | string | *unset* | `KALENDEE_S3_ACCESS_KEY` | Access key id. |
| `storage.s3.secretKey` | string | *unset* | `KALENDEE_S3_SECRET_KEY` | Secret access key. |
| `storage.s3.pathStyle` | boolean | `true` | `KALENDEE_S3_PATH_STYLE` | Path-style addressing. Required by R2. |

The client applies `forcePathStyle(pathStyle)`, `endpointOverride(endpoint)`
when a non-blank endpoint is set, and a static credentials provider built from
both keys when either is present — so supply both or neither.

## Cloudflare R2 walkthrough

R2 exposes an S3-compatible API, so Kalendee writes to R2 over S3 and reads
through an optional Worker
([Cloudflare Workers](/docs/self-hosting/cloudflare-workers)).

### 1. Create the bucket

In the Cloudflare dashboard: **R2 → Create bucket**. Note the bucket name. You
can also create it with Wrangler:

```bash
wrangler r2 bucket create kalendee
```

### 2. Create an API token

**R2 → API → Manage API tokens → Create API token**, scoped to the bucket with
**Object Read & Write**. This yields an **Access Key ID** and **Secret Access
Key**. These go in `.env`, never in a committed config file:

```dotenv
KALENDEE_S3_ACCESS_KEY=<access-key-id>
KALENDEE_S3_SECRET_KEY=<secret-access-key>
```

### 3. Point the server at R2

The endpoint is `https://<account-id>.r2.cloudflarestorage.com`, where
`<account-id>` is your Cloudflare account id. Region must be `auto` and
path-style must be `true`:

```hocon
storage {
    s3 {
        enabled = true
        enabled = ${?KALENDEE_S3_ENABLED}
        endpoint = "https://<account-id>.r2.cloudflarestorage.com"
        endpoint = ${?KALENDEE_S3_ENDPOINT}
        region = "auto"
        region = ${?KALENDEE_S3_REGION}
        bucket = "kalendee"
        bucket = ${?KALENDEE_S3_BUCKET}
        accessKey = ${?KALENDEE_S3_ACCESS_KEY}
        secretKey = ${?KALENDEE_S3_SECRET_KEY}
        pathStyle = true
        pathStyle = ${?KALENDEE_S3_PATH_STYLE}
    }
}
```

Equivalent environment variables:

```dotenv
KALENDEE_S3_ENABLED=true
KALENDEE_S3_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
KALENDEE_S3_REGION=auto
KALENDEE_S3_BUCKET=kalendee
KALENDEE_S3_PATH_STYLE=true
```

### 4. Serve reads publicly

Keep the bucket private. Two ways to serve avatar reads without proxying them
through the Kotlin server:

| Approach | How | Trade-offs |
| --- | --- | --- |
| R2 public bucket / custom domain | Enable a public R2.dev URL or attach a custom domain to the bucket, then set `storage.publicBaseUrl` to it. | Reads are anonymous; anyone with a key can fetch the object. Simplest. |
| R2 read-gateway Worker | Deploy `workers/r2`, bind it to the bucket, set `storage.publicBaseUrl` to the Worker URL. | Keeps the bucket private, adds caching/ETag/CORS, and optionally a read token. Recommended. |

Set the read base URL in HOCON:

```hocon
storage {
    publicBaseUrl = "https://kalendee-r2.<your-subdomain>.workers.dev"
    publicBaseUrl = ${?KALENDEE_STORAGE_PUBLIC_URL}
}
```

When set, the server answers `GET /api/v1/users/{id}/avatar` with
`302 Location: ${publicBaseUrl}/${key}?v=<version>` instead of streaming bytes,
so image traffic never touches the app. The key is the same one written over
S3, e.g. `avatars/<userId>/<uuid>.webp` resolving at
`https://kalendee-r2.<subdomain>.workers.dev/avatars/<userId>/<uuid>.webp`.

> **Read token caveat.** If the R2 Worker is configured with `READ_TOKEN`, the
> server's redirect does **not** include it, so avatar reads will get `403`
> unless clients append `?token=`. For redirect-based avatar serving, leave
> `READ_TOKEN` unset so the Worker serves anonymous reads. Use `READ_TOKEN` only
> when the Worker is consumed by something that can attach the token.

## Permissions, CORS, and lifecycle

- **R2 API token permissions.** Minimum for Kalendee is *Object Read & Write*
  on the bucket. It does not need bucket administration.
- **CORS.** The server redirects the browser to the public base URL, so the
  image response is a cross-origin request; the Worker sends
  `Access-Control-Allow-Origin: *` for `image/*` responses. A bare R2 public
  bucket needs its own CORS rule allowing `GET`/`HEAD` from the Kalendee origin.
  Object writes (`PutObject`) are server-to-server and need no CORS rule.
- **Caching.** The Worker sets `Cache-Control` (`max-age=31536000, immutable`
  for content-hashed/UUID keys, otherwise `max-age=86400`) and a strong `ETag`,
  and honours `If-None-Match` with `304`. Avatar keys are regenerated per
  upload and versioned with `?v=`, so `immutable` is safe.
- **Lifecycle.** Because each avatar change writes a new key and deletes the old
  one, the object count tracks the user count. If a delete fails, an orphaned
  object can remain; add an R2 lifecycle rule if you want a safety net. Never
  expire keys that are still referenced by `users.avatar_key`.
- **Encryption/TLS.** The SDK talks HTTPS to the R2 endpoint. Server-side
  encryption is managed by R2; the server does not set SSE headers.

## Quota interaction

Avatar storage counts against per-user quotas. Before storing an upload, the
server calls `assertCanStore(userId, bytes)`:

- Admins and superadmins are exempt.
- Otherwise the effective quota is the smallest non-null
  `storage_quota_bytes` among the user's groups plus the system **default**
  group; no quota configured means unlimited.
- Usage is `users.avatar_bytes`; replacing an avatar deletes the old object but
  the quota check runs before that, so a user at the limit may need to delete
  first.

The quota check applies to both backends; object storage backend choice does
not change quota accounting. See
[Groups and quotas](/docs/product/groups-and-quotas) for the admin UI.

## Related pages

- [Configuration](/docs/self-hosting/configuration) — `storage.*` keys.
- [Environment variables](/docs/self-hosting/environment) — `KALENDEE_S3_*`.
- [Cloudflare Workers](/docs/self-hosting/cloudflare-workers) — deploy the R2
  read gateway and mailer.
- [Backups and upgrades](/docs/self-hosting/backups-and-upgrades) — back up
  local avatars or the R2 bucket.
