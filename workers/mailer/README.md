# kalendee-mailer

HTTP → email bridge. The Kalendee server POSTs a small JSON envelope to this
Worker, and the Worker hands it to Cloudflare Email Routing's `send_email`
binding for outbound delivery. This lets a deployment send mail without running
an SMTP relay.

This Worker is server-to-server only; it does not send CORS headers.

## Wire contract (frozen)

**Request**

```
POST /
Authorization: Bearer <MAILER_TOKEN>
Content-Type: application/json

{
  "from": string,
  "to": string,
  "subject": string,
  "text": string,
  "html"?: string | null
}
```

`to` is a single address; the server sends one recipient per call.

**Responses**

| Status | Meaning |
| --- | --- |
| `204` | Mail handed to the binding. |
| `400` | Body was not valid JSON, or a required field was missing/invalid. |
| `401` | `Authorization` header missing or token wrong. |
| `405` | Method other than `POST`. |
| `502` | The `send_email` binding rejected or failed the message. Body is `{"error": "..."}`; no stack traces are returned. |

`html` is only forwarded when it is a non-empty string.

## Setup

1. **Enable Email Routing** for the zone that will send mail (Cloudflare
   dashboard → *Email* → *Email Routing*). This also provisions the MX records
   Email Routing needs.
2. **Verify the sender domain.** Under *Email Routing* → *Settings* →
   *Email Addresses* / *Destination addresses*, add and verify the sender
   address (and on some plans each destination address you send to). Cloudflare
   refuses recipients that are not verified yet — mail only flows once the
   relevant addresses/domains are verified.
3. **Add the `send_email` binding.** Uncomment the `[[send_email]]` block in
   `wrangler.toml`:

   ```toml
   [[send_email]]
   name = "MAILER"
   ```

4. **Set the shared secret.** Use a long random value and store it as a
   Worker secret — never in `[vars]` or committed files:

   ```sh
   wrangler secret put MAILER_TOKEN
   ```

   For local dev, put it in a `.dev.vars` file (git-ignored) as
   `MAILER_TOKEN=...`.

## Deploy

```sh
pnpm install
pnpm run deploy
```

`pnpm run dev` starts a local `wrangler dev` server. `pnpm run typecheck`
runs `tsc --noEmit`.

## Point the Kalendee server at it

In the server's HOCON (`application.conf` / `application.conf.dev`), select the
Cloudflare provider and give it the Worker URL and the same token:

```hocon
mail {
  enabled = true
  provider = "cloudflare"
  cloudflare {
    endpoint = "https://kalendee-mailer.<your-subdomain>.workers.dev"
    token    = "same-value-as-MAILER_TOKEN"
  }
}
```

The server resolves the endpoint, sends `POST`s with
`Authorization: Bearer <token>`, and treats `204` as success.

## License

AGPL-3.0-only. See the repository [`LICENSE`](../../LICENSE).
