---
title: Email
description: >-
  Configuring outbound mail for Kalendee: the smtp, cloudflare, mailgun, and log
  transports, the full key set, provider caveats, and how to test delivery.
---

Kalendee sends transactional notifications — email verification, new sign-in
alerts, calendar shares, organization invitations, follower notifications, friend
requests, time-slot requests/decisions, event invitations, and RSVP updates —
through one `Mailer` selected at startup.

## Choosing a transport

`mail.provider` selects the transport. When it is blank, the server
auto-detects; when it is set, it wins outright (including over
`mail.enabled`).

| `mail.provider` | Transport | When selected automatically |
| --- | --- | --- |
| `smtp` | SMTP submission via Jakarta Mail. | `mail.provider` blank, `mail.enabled = true`, and `mail.host` non-blank. |
| `cloudflare` | HTTP `POST` to a mailer Worker. | `mail.provider` blank, `mail.enabled = true`, `mail.host` blank, and `mail.cloudflare.endpoint` non-blank. |
| `mailgun` | HTTP `POST` to the Mailgun API. | `mail.provider` blank, `mail.enabled = true`, `mail.host` blank, `mail.cloudflare.endpoint` blank, and `mail.mailgun.apiKey` **and** `mail.mailgun.domain` non-blank. |
| `log` | Logs the message body and sends nothing. | `mail.provider` blank and `mail.enabled = false`; or blank and no transport is configured. |

Auto-detection summary: `mail.enabled = false` → `log`; otherwise host → `smtp`;
otherwise Cloudflare endpoint → `cloudflare`; otherwise Mailgun credentials →
`mailgun`; otherwise `log`.

Three safety nets run at startup:

- If `mail.provider = "smtp"` but `mail.host` is blank, the server logs a
  warning and uses the logging mailer.
- If `mail.provider = "cloudflare"` but the endpoint **and** token are not both
  set, the server logs a warning and uses the logging mailer.
- If `mail.provider = "mailgun"` but the API key **and** domain are not both
  set, the server logs a warning and uses the logging mailer.

Unknown `mail.provider` values warn and fall back to `log`; they never crash
startup. See [Configuration](/docs/self-hosting/configuration) for the full key
table.

## SMTP

| Key | Default | Env fallback | Meaning |
| --- | --- | --- | --- |
| `mail.host` | `""` | `KALENDEE_SMTP_HOST` | SMTP host. Blank disables SMTP in auto-detection. |
| `mail.port` | `587` | `KALENDEE_SMTP_PORT` | SMTP port. |
| `mail.username` | `""` | `KALENDEE_SMTP_USER` | Username. Blank disables SMTP authentication. |
| `mail.password` | `""` | `KALENDEE_SMTP_PASSWORD` | Password. |
| `mail.from` | `Kalendee <no-reply@localhost>` | `KALENDEE_MAIL_FROM` | `From` header. |
| `mail.startTls` | `true` | `KALENDEE_SMTP_STARTTLS` | Set `mail.smtp.starttls.enable` on the session. |

The implementation maps settings onto Jakarta Mail properties directly:

| Mail property | Value |
| --- | --- |
| `mail.smtp.host` | `mail.host` |
| `mail.smtp.port` | `mail.port` |
| `mail.smtp.auth` | `"true"` only when `mail.username` is non-blank |
| `mail.smtp.starttls.enable` | `mail.startTls` |

Behaviour:

- Authentication is enabled only when a username is set. A blank username is
  useful for a local relay that accepts unauthenticated mail from the server.
- When username is set, the password is whatever `mail.password` holds,
  including an empty string.
- Messages with HTML are sent as `multipart/alternative` (plain text first,
  HTML second); otherwise the plain-text body is sent alone.
- Messages are sent synchronously per recipient; the server sends one `to`
  address per message.

### SMTP caveats

- **No implicit TLS on port 465.** Only `mail.smtp.starttls.enable` is set; the
  server never sets `mail.smtp.ssl.enable`. Use STARTTLS (typically port 587),
  or a relay that upgrades via STARTTLS. Port 465 will not negotiate TLS as
  configured.
- **`mail.from` must be an address your provider allows.** Some providers reject
  a mismatched envelope/From address; align it with the authenticated account.
- **No connection pooling or retry policy.** A send either succeeds or throws;
  the caller decides how to surface the failure. There is no background queue.

## Cloudflare provider

**Cloudflare's `send_email` binding is a paid Email Routing feature.** It is not
available on the free plan, so this transport cannot be used unless your account
has the paid feature enabled. If it does not, use [Mailgun](#mailgun-provider) or
SMTP instead.

Send mail through Cloudflare Email Routing without running an SMTP relay by
deploying the mailer Worker in [`workers/mailer`](https://github.com/KolektivComputer/kalendee/tree/main/workers/mailer).
Deployment is covered in
[Cloudflare Workers](/docs/self-hosting/cloudflare-workers).

| Key | Default | Env fallback | Meaning |
| --- | --- | --- | --- |
| `mail.cloudflare.endpoint` | `""` | `KALENDEE_MAIL_CLOUDFLARE_ENDPOINT` | Base URL of the deployed Worker, e.g. `https://kalendee-mailer.<subdomain>.workers.dev`. |
| `mail.cloudflare.token` | `""` | `KALENDEE_MAIL_CLOUDFLARE_TOKEN` | Bearer token; must equal the Worker's `MAILER_TOKEN`. |
| `mail.cloudflare.timeoutSeconds` | `10` | `KALENDEE_MAIL_CLOUDFLARE_TIMEOUT_SECONDS` | HTTP connect and request timeout. Non-positive values fall back to `10`. |

What the server sends (the Worker's frozen wire contract):

```http
POST <mail.cloudflare.endpoint>
Authorization: Bearer <mail.cloudflare.token>
Content-Type: application/json

{
  "from": "<mail.from>",
  "to": "<recipient>",
  "subject": "<subject>",
  "text": "<plain body>",
  "html": "<html body>"   // omitted when the message has no HTML part
}
```

- Any `2xx` response is treated as success (the Worker returns `204`).
- A non-`2xx` response or a network/timeout failure throws, including the
  response body in the error message.
- The `from` header comes from `mail.from`, **not** from the Worker's
  `MAIL_FROM` variable (which the Worker does not read).

## Mailgun provider

Mailgun's HTTP API sends transactional mail without an SMTP relay and without
the paid Cloudflare Email Routing feature, which makes it the practical
non-SMTP option when Cloudflare's `send_email` binding is unavailable.

### Setting up Mailgun

1. **Add and verify a sending domain.** In the Mailgun dashboard, add a domain
   (for example `mg.example.com`) and create the DNS records Mailgun shows
   (SPF/DKIM, and MX if you will also receive mail). Wait until Mailgun reports
   the domain as verified.
2. **Create an API key.** Mailgun uses a private API key, not a password. Copy
   it from the dashboard's API keys page. Keep it in `.env`, never in a
   committed config file.
3. **Choose the region.** US domains use `api.mailgun.net`; EU domains use
   `api.eu.mailgun.net`. Set `mail.mailgun.region` to `us` or `eu` to match the
   region where the domain was created. Leaving it unset defaults to `us`.
4. **Set `mail.from` to an address on the verified domain.** For example
   `Kalendee <no-reply@mg.example.com>`. Mailgun rejects a sender outside the
   verified domain.
5. **Enable mail and select the provider.** Set `mail.enabled = true` and either
   `mail.provider = "mailgun"` or leave `mail.provider` blank and let
   auto-detection pick it up from the credentials.

| Key | Default | Env fallback | Meaning |
| --- | --- | --- | --- |
| `mail.provider` | `""` (auto) | `KALENDEE_MAIL_PROVIDER` | Set to `mailgun` to select this transport explicitly. |
| `mail.mailgun.apiKey` | *unset* | `KALENDEE_MAILGUN_API_KEY` | Private API key from the Mailgun dashboard. |
| `mail.mailgun.domain` | *unset* | `KALENDEE_MAILGUN_DOMAIN` | Verified sending domain; also used in the request path. |
| `mail.mailgun.region` | `us` | `KALENDEE_MAILGUN_REGION` | `us` or `eu`; picks `api.mailgun.net` or `api.eu.mailgun.net` when `baseUrl` is blank. |
| `mail.mailgun.baseUrl` | `""` | `KALENDEE_MAILGUN_BASE_URL` | Optional API base URL override (for a custom Mailgun endpoint). Blank derives it from `region`. |
| `mail.mailgun.timeoutSeconds` | `10` | `KALENDEE_MAILGUN_TIMEOUT_SECONDS` | HTTP connect and request timeout. Non-positive values fall back to `10`. |

```hocon
mail {
    enabled = true
    enabled = ${?KALENDEE_MAIL_ENABLED}
    provider = "mailgun"
    provider = ${?KALENDEE_MAIL_PROVIDER}
    from = "Kalendee <no-reply@mg.example.com>"
    from = ${?KALENDEE_MAIL_FROM}
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
```

What the server sends:

```http
POST https://api.mailgun.net/v3/<mail.mailgun.domain>/messages
Authorization: Basic base64("api:" + <mail.mailgun.apiKey>)
Content-Type: application/x-www-form-urlencoded

from=<mail.from>&to=<recipient>&subject=<subject>&text=<plain body>
```

- The `html` field is appended only when the message has an HTML part; the form
  values are percent-encoded (spaces become `+`, as
  `application/x-www-form-urlencoded` allows).
- The API key is sent as the password of the `api` user in a Basic
  authorization header; the server builds it as `base64("api:" + apiKey)`.
- Any `2xx` response is success. A non-`2xx` response or a network/timeout
  failure throws, including the status and response body in the error message.

### Mailgun caveats

- **Verify the domain first.** Sending from an unverified domain returns `401`
  or `403`; Mailgun will not relay the message.
- **Region mismatch.** A domain created in the EU region will not authenticate
  against `api.mailgun.net`. Set `mail.mailgun.region = "eu"` (or point
  `mail.mailgun.baseUrl` at the correct host) for EU domains.
- **`mail.from` must be on the verified domain.** A mismatched sender is
  rejected even when the API key is valid.
- **No retry policy.** A send either succeeds or throws; the caller decides how
  to surface the failure. There is no background queue.

## Logging transport

When no transport is fully configured, the server uses the logging mailer:

- Nothing leaves the process; the message is written at `INFO` level to the
  `dev.kolektiv.kalendee.mail.LoggingMailer` logger as
  `[mail:dev] to=<addr> subject=<subject>` followed by the plain-text body.
- Email-verification links, invitation links, and sign-in alerts therefore
  appear verbatim in the server log. This is intentional for development and
  for a deployment that has not yet configured mail.

Example log output:

```text
INFO  d.k.kalendee.mail.LoggingMailer - [mail:dev] to=alice@example.com subject=Verify your Kalendee email
Welcome to Kalendee.

Confirm this address to finish setting up your account:
http://localhost:8080/verify-email?token=...
```

## Testing

1. **Use the log transport.** Set `mail.provider = "log"` (or
   `KALENDEE_MAIL_PROVIDER=log`) and `app.baseUrl` to a reachable URL. Register
   an account, then read the verification link from the server log. Force `log`
   explicitly to keep stray SMTP environment values from taking over.
2. **Check the link origin.** Emails use `app.baseUrl` for absolute links. If
   `app.baseUrl` is blank in production, MailService logs a warning and writes
   relative links, which will not work in a mail client.
3. **Test SMTP against the real relay.** Configure `mail.host`/`port`/`username`/
   `password` and trigger a verification or invite. Failures throw; watch the
   server log for the Jakarta Mail exception.
4. **Test the Cloudflare path end to end.** Deploy the Worker, set the endpoint
   and token, then trigger a message. A `401` means the Worker's `MAILER_TOKEN`
   differs from `mail.cloudflare.token`; a `502` means Email Routing rejected
   the message (usually an unverified sender/recipient); a timeout points at
   `mail.cloudflare.timeoutSeconds` or the Worker URL.
5. **Test the Mailgun path end to end.** Verify the sending domain, set the API
   key and domain, and trigger a message. A `401` usually means a wrong API key
   or a region mismatch (an EU domain against `api.mailgun.net`); a `403` means
   the sender/domain is not verified; a timeout points at
   `mail.mailgun.timeoutSeconds` or `mail.mailgun.baseUrl`.
6. **Remember verification policy.** With `auth.emailVerification = "optional"`
   or `"soft"`, registration succeeds even if mail fails. Set
   `"required"` only after delivery works, or users will be locked out.

## Related pages

- [Configuration](/docs/self-hosting/configuration) — `mail.*` key reference.
- [Cloudflare Workers](/docs/self-hosting/cloudflare-workers) — deploy the
  mailer Worker.
- [Security](/docs/self-hosting/security) — secrets handling and TLS.
- [Troubleshooting](/docs/self-hosting/troubleshooting) — common operator
  errors.
