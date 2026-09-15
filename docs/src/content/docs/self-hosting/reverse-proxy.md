---
title: Reverse proxy
description: >-
  Terminate TLS for Kalendee with Caddy, nginx, or Traefik, and configure the
  public URL, secure cookies, and forwarded headers correctly.
---

## What the proxy must do

Kalendee speaks plain HTTP on `8080` and expects a TLS-terminating reverse proxy
in front of it. The proxy is responsible for:

- obtaining and renewing certificates and terminating TLS on `443`;
- forwarding requests to the Kalendee host/port (container `kalendee:8080` or
  `127.0.0.1:8080`);
- passing the `Host` header through;
- setting `X-Forwarded-For` and `X-Forwarded-Proto`;
- allowing request bodies at least as large as an avatar upload (2 MiB plus
  multipart overhead);
- optionally adding HSTS and other security headers.

Kalendee does **not** install Ktor's `XForwardedHeaders` plugin, so it does not
use `X-Forwarded-*` when computing absolute URLs or the client address. Absolute
URLs come from `app.baseUrl`; the built-in login throttle sees the proxy's
address. Set `app.baseUrl` correctly and add client-level rate limiting at the
proxy if you need it.

## Set the public URL and cookies

Two settings always matter behind a TLS proxy:

| Setting | HOCON key | Env fallback | Value |
| --- | --- | --- | --- |
| Public base URL | `app.baseUrl` | `KALENDEE_PUBLIC_URL` (or deprecated `KALENDEE_BASE_URL`) | The exact `https://` URL clients use, no trailing slash. |
| Secure cookies | `auth.cookieSecure` | `KALENDEE_COOKIE_SECURE` | `true`. |

`app.baseUrl` is used in verification and invite emails, RSS links, and OAuth
redirect URIs. A wrong value produces links with the wrong host or scheme, and
OAuth callbacks will fail. The server trims a trailing slash but does not infer
the scheme.

The session cookie is `HttpOnly` and `SameSite=Lax`, and is marked `Secure` when
`auth.cookieSecure = true`. A `Secure` cookie is silently dropped by browsers
over plain HTTP, which looks like "login does nothing" — see
[Troubleshooting](/docs/self-hosting/troubleshooting).

Example HOCON:

```hocon
app {
  baseUrl = "https://calendar.example.com"
  baseUrl = ${?KALENDEE_PUBLIC_URL}
}
auth {
  cookieSecure = true
  cookieSecure = ${?KALENDEE_COOKIE_SECURE}
}
```

## Caddy (recommended)

Caddy manages certificates automatically and sets the forwarded headers for you.
The smallest working Caddyfile:

```caddyfile
calendar.example.com {
    encode zstd gzip

    reverse_proxy 127.0.0.1:8080
}
```

If Kalendee runs in the same Compose network, proxy to `kalendee:8080` instead.
Caddy sets `X-Forwarded-For` and `X-Forwarded-Proto` itself; `Host` is preserved
by default.

## nginx

```nginx
server {
    listen 80;
    server_name calendar.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    http2 on;
    server_name calendar.example.com;

    ssl_certificate     /etc/letsencrypt/live/calendar.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/calendar.example.com/privkey.pem;

    # Avatars are capped at 2 MiB server-side; leave multipart headroom.
    client_max_body_size 4m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_read_timeout 90s;
    }
}
```

Keep `proxy_http_version 1.1`. Certificate issuance (for example with certbot)
is outside Kalendee's scope.

## Traefik

File-provider example:

```yaml
http:
  routers:
    kalendee:
      rule: "Host(`calendar.example.com`)"
      entryPoints: ["websecure"]
      service: kalendee
      tls:
        certResolver: letsencrypt

  services:
    kalendee:
      loadBalancer:
        servers:
          - url: "http://kalendee:8080"
```

Or with Docker labels on a Compose service:

```yaml
labels:
  - "traefik.enable=true"
  - "traefik.http.routers.kalendee.rule=Host(`calendar.example.com`)"
  - "traefik.http.routers.kalendee.entrypoints=websecure"
  - "traefik.http.routers.kalendee.tls.certresolver=letsencrypt"
  - "traefik.http.services.kalendee.loadbalancer.server.port=8080"
```

Traefik sets `X-Forwarded-*` automatically.

## Routes to be aware of

You can proxy every path to the server; there is no separate static host to
configure. These are the routes worth knowing when writing rules or cache
policies:

| Path | Purpose | Notes |
| --- | --- | --- |
| `/` | Keel web UI (week view, login, register, admin) | Auth handled by the server. |
| `/api/v1/*` | JSON API | Most routes require a session; do not cache. |
| `/api/v1/health` | Health check | Public; use it for proxy upstream checks if you want. |
| `/api/v1/auth/login`, `/register`, `/verify-email` | Auth entry points | Public. |
| `/api/v1/users/{id}/avatar` | Avatar read | Public. Returns a 302 to `storage.publicBaseUrl` when set, otherwise proxies the bytes. |
| `/__keel/pack/*` | Keel pack assets | Served by the host; excluded from the auth interceptor. Safe to cache by content hash. |
| `/favicon.svg`, `/favicon.ico` | Favicon | Public, cacheable. |
| `/rss/{token}.xml` | Public calendar RSS | Public with a token; `Content-Type: application/rss+xml`. |

If you set `storage.publicBaseUrl` to an R2 gateway or CDN (see
[Object storage](/docs/self-hosting/object-storage) and
[Cloudflare Workers](/docs/self-hosting/cloudflare-workers)), avatar responses
become redirects. The proxy must be able to send clients to that external host,
but it does not need to proxy it.

## Security headers

Kalendee does not add HSTS or CSP headers. Add them at the proxy. A conservative
start for nginx:

```nginx
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

Only enable HSTS once HTTPS works reliably; it is sticky in browsers. Review the
[Security](/docs/self-hosting/security) page for the rest of the hardening
checklist.
