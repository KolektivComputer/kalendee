# kalendee-r2

Read gateway for a private Cloudflare R2 bucket. It serves user-uploadable
objects (avatars and similar) directly from the edge with correct caching,
content types, conditional requests, and an optional read token — so the
Kotlin server does not have to proxy every avatar.

## Why a Worker instead of a public bucket?

- **The bucket stays private.** Writes go through the S3 API with credentials;
  reads go through this Worker. There is no anonymous bucket access to
  misconfigure.
- **Caching.** Objects are served with long `Cache-Control` lifetimes (and
  `immutable` for content-hashed keys), so clients and Cloudflare's cache do
  the heavy lifting.
- **Optional auth.** A shared `READ_TOKEN` can gate reads when objects should
  not be world-readable.
- **Correct headers.** Content type, ETag, and CORS for images are set
  consistently, including `304 Not Modified` via `If-None-Match`.

**Custom domain vs. worker route.** A Worker on a `workers.dev` (or custom)
domain is the simplest choice and keeps the gateway address stable for
`storage.publicBaseUrl`. A *custom domain* gives you a friendly hostname and
Cloudflare-managed TLS; a *worker route* on a zone lets the Worker share a host
with other routes. Either works — pick the custom domain if clients should see
a branded URL, and remember to update `storage.publicBaseUrl` if it changes.

## Behavior

`GET` / `HEAD` only; anything else is `405`.

- Object key is the URL pathname with the leading `/` removed, so nested keys
  work: `/avatars/<userId>/<uuid>.webp`.
- Keys containing `..` or a leading `/` are rejected (`400`).
- Lookup uses the `BUCKET` R2 binding; a miss is `404`.
- If `READ_TOKEN` is set, the request must present it as `?token=<READ_TOKEN>`
  or `Authorization: Bearer <READ_TOKEN>`; otherwise `403`. If unset, reads are
  anonymous.
- Response headers: `Content-Type` (object metadata, then extension, then
  `application/octet-stream`), `ETag`, `Cache-Control`
  (`public, max-age=31536000, immutable` for content-hashed/immutable keys,
  else `public, max-age=86400`), and `Access-Control-Allow-Origin: *` for image
  responses.
- `If-None-Match` matching the object ETag returns `304`.
- `GET` streams `object.body`; files are never buffered into memory. `HEAD`
  uses `BUCKET.head` and returns no body.

## Setup

1. **Create the bucket:**

   ```sh
   wrangler r2 bucket create <bucket-name>
   ```

2. **Bind it.** Uncomment the `[[r2_buckets]]` block in `wrangler.toml` and set
   the bucket name:

   ```toml
   [[r2_buckets]]
   binding = "BUCKET"
   bucket_name = "<bucket-name>"
   ```

3. **(Optional) require a read token:**

   ```sh
   wrangler secret put READ_TOKEN
   ```

   For local dev, put it in `.dev.vars` as `READ_TOKEN=...`.

## Deploy

```sh
pnpm install
pnpm run deploy
```

`pnpm run dev` starts a local `wrangler dev` server. `pnpm run typecheck`
runs `tsc --noEmit`.

## Point the Kalendee server at it

Two separate settings matter: writes use the S3 API, reads use the Worker.

*Writes* — point `storage.s3.*` at R2's S3-compatible endpoint with an R2 API
token (access key / secret key) that can write to the bucket:

```hocon
storage {
  s3 {
    enabled   = true
    endpoint  = "https://<account-id>.r2.cloudflarestorage.com"
    region    = "auto"
    bucket    = "<bucket-name>"
    accessKey = "<r2-access-key-id>"
    secretKey = "<r2-secret-access-key>"
    pathStyle = true
  }
}
```

*Reads* — set `storage.publicBaseUrl` to this Worker's URL. The server responds
to clients with a redirect to `${publicBaseUrl}/${objectKey}` instead of
streaming the object itself:

```hocon
storage {
  publicBaseUrl = "https://kalendee-r2.<your-subdomain>.workers.dev"
}
```

Object keys written by the server (for example `avatars/<userId>/<uuid>.webp`)
then resolve at
`https://kalendee-r2.<your-subdomain>.workers.dev/avatars/<userId>/<uuid>.webp`.

## License

AGPL-3.0-only. See the repository [`LICENSE`](../../LICENSE).
