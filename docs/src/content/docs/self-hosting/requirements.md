---
title: Requirements
description: >-
  Supported platforms, Java and PostgreSQL versions, ports, resource guidance,
  and outbound network access for a self-hosted Kalendee instance.
---

## Server host

| Item | Requirement |
| --- | --- |
| Operating system | Any OS that runs a JVM 21 (Linux, macOS, Windows, BSD). The published container image targets `linux/amd64` and `linux/arm64`. |
| CPU | 1 vCPU is enough to evaluate; 2+ for a small team. Argon2 password hashing is intentionally CPU- and memory-heavy. |
| RAM | 512 MiB minimum; 1 GiB+ is comfortable. The JVM and the connection pool are tunable via `JAVA_OPTS`. |
| Disk | A few hundred MiB for the jar/image and dependencies, plus database growth and avatar storage. Avatars are small (2 MiB max each). |
| Container runtime | Docker Engine with Compose v2, or Podman (the NixOS module defaults to Podman). Not required for the standalone binary. |

The server is a single JVM process. It is stateless apart from the database and
the avatar store, so horizontal scaling is possible but is not the supported
default; the built-in login throttle is per instance and in memory.

## Java

Java 21 is required. The fat jar runs on a JRE 21 (or newer within the same
LTS line); building from source needs a JDK 21, which the Gradle toolchain
downloads automatically. The container image is built on `eclipse-temurin:21-jdk`
and runs on `eclipse-temurin:21-jre`.

```bash
java -version   # must report 21
```

## PostgreSQL

PostgreSQL 17 is the supported and tested version; the Compose files run
`postgres:17-alpine`. The server connects with JDBC through a HikariCP pool
(maximum 10 connections, minimum idle 2) and applies Flyway migrations on every
startup.

- The app role needs `CREATE` on its database so Flyway can create and update
  the `flyway_schema_history` table and the application tables.
- There is no SQLite, MySQL, or embedded-database mode. `:server:test` uses an
  H2 shim, but production requires PostgreSQL.
- No `superuser` is required; an owner role for the database is.

See [Database](/docs/self-hosting/database) for setup and migration behavior.

## Ports

| Port | Where | Purpose |
| --- | --- | --- |
| `8080` | Container / process | HTTP listener. Inside the container it is always 8080; Docker Compose publishes it as `${KALENDEE_HOST_PORT:-8080}`. |
| `8080` (or a proxy port) | Host | What users reach, normally through a reverse proxy on `443`. |
| `5432` | PostgreSQL | Database. Must be reachable from the server; it does not need to be public. |

The bind address and port are HOCON keys (`ktor.deployment.host`,
`ktor.deployment.port`), overridable with `KALENDEE_HTTP_HOST` /
`KALENDEE_HTTP_PORT`. The default host is `0.0.0.0`.

## Reverse proxy expectations

TLS terminates at a reverse proxy; the server itself speaks plain HTTP. The
proxy must:

- preserve the `Host` header (Ktor builds absolute links from `app.baseUrl`, not
  from the request host, so a mismatch shows up as wrong links rather than a
  crash);
- forward `X-Forwarded-For` and `X-Forwarded-Proto` for your proxy's own logs
  and rate limiting (Kalendee does not currently parse them — see below);
- allow request bodies large enough for avatar uploads (up to 2 MiB plus
  multipart overhead) and long-lived responses for streaming/redirects.

`app.baseUrl` must be the public HTTPS URL and `auth.cookieSecure` must stay
`true`. See [Reverse proxy](/docs/self-hosting/reverse-proxy).

Kalendee does not install Ktor's `XForwardedHeaders` plugin, so the application
ignores `X-Forwarded-*` when determining the client address; the built-in login
throttle keys on the direct peer (the proxy). Add rate limiting at the proxy if
you rely on client-level throttling.

## Outbound network access

The server makes outbound connections only for the integrations you enable.
None are required for a standalone instance with local avatars and no mail.

| Destination | Port | When |
| --- | --- | --- |
| Your SMTP submission host | `587` (STARTTLS) or `465` (implicit TLS) | `mail.enabled = true` with the SMTP provider. |
| Cloudflare mailer Worker (`*.workers.dev` or your domain) | `443` | `mail.provider = "cloudflare"`. See [Cloudflare Workers](/docs/self-hosting/cloudflare-workers). |
| Mailgun API (`api.mailgun.net` or `api.eu.mailgun.net`) | `443` | `mail.provider = "mailgun"`. See [Email](/docs/self-hosting/email#mailgun-provider). |
| S3-compatible endpoint (e.g. `<account-id>.r2.cloudflarestorage.com`) | `443` | `storage.s3.enabled = true`. |
| Object read gateway / CDN | `443` | Only clients fetch this; the server redirects to it. |
| Provider OAuth and APIs (Discord today) | `443` | External calendar connections. See [Discord OAuth](/docs/self-hosting/oauth-discord). |

If you run behind an egress firewall, allow HTTPS to those hosts and DNS.

## Node and pnpm

Not required at runtime. Node 22+ and pnpm 11.25.0 are only needed to build the
Keel web pack when building from source (`./gradlew :server:buildPack`, which
`:server:run` and the jar depend on). The published image and the released jar
already contain the built pack.
