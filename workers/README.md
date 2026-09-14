# Kalendee Workers

Cloudflare Workers that complement the Kalendee server with edge-side
infrastructure the Kotlin/JVM process should not own: outbound email and public
reads of private object storage.

Both are TypeScript, ESM, and deployed with [Wrangler](https://developers.cloudflare.com/workers/wrangler/).
They are independent pnpm projects — install and deploy each one from its own
directory. Node 22+ and pnpm 11.25.0 (matching `server/pack`) are expected.

## [`mailer/`](./mailer) — kalendee-mailer

An HTTP → email bridge. The server `POST`s a JSON envelope with a bearer token
and the Worker delivers it through Cloudflare Email Routing's `send_email`
binding. Use it when you want the server to send verification and notification
mail without configuring SMTP.

- Wire contract, response codes, and Email Routing setup: [`mailer/README.md`](./mailer/README.md)
- Secret: `MAILER_TOKEN` (`wrangler secret put MAILER_TOKEN`)
- Binding: `[[send_email]]` named `MAILER`

## [`r2/`](./r2) — kalendee-r2

A read gateway in front of a private R2 bucket. The server writes objects via
R2's S3 API and points `storage.publicBaseUrl` at this Worker; clients are then
redirected to `https://<worker>/<objectKey>` and served straight from R2 with
correct caching, content types, conditional requests, and an optional read
token.

- Behavior and setup: [`r2/README.md`](./r2/README.md)
- Optional secret: `READ_TOKEN` (`wrangler secret put READ_TOKEN`)
- Binding: `[[r2_buckets]]` with `binding = "BUCKET"`

## Working in a worker

```sh
cd workers/<name>
pnpm install
pnpm run dev        # local wrangler dev
pnpm run typecheck  # tsc --noEmit
pnpm run deploy     # wrangler deploy
```

Each directory has its own `wrangler.toml`; bindings that need real Cloudflare
resources are commented out until you uncomment them and supply the bucket or
zone. Secrets are never committed — use `wrangler secret put` (or a local
`.dev.vars`).

## Layout / conventions

- `src/index.ts` holds the Worker entry point (`main` in `wrangler.toml`).
- `wrangler.toml` holds bindings, compatibility date, and non-secret `[vars]`.
- `node_modules/`, `.wrangler/`, and `dist/` are git-ignored per worker.
- Keep the worker self-contained; it must not import from `:core`, `:app`, or
  the server. The server talks to these workers over plain HTTP.

## License

AGPL-3.0-only. See the repository [`LICENSE`](../LICENSE).
