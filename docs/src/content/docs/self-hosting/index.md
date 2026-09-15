---
title: Self-hosting overview
description: >-
  What runs in a Kalendee deployment, the three supported ways to run it, and
  how to pick between them.
---

## What runs

Kalendee is one Ktor JVM service plus a PostgreSQL database. Everything else is
optional.

| Component | Required | Notes |
| --- | --- | --- |
| Kalendee server (Ktor + Netty, JVM 21) | Yes | Serves the web UI at `/`, the JSON API at `/api/v1`, and the built web pack bundled inside the jar. |
| PostgreSQL 17 | Yes | System of record. Flyway migrations run automatically on startup. |
| Object storage | No | Avatars. Defaults to a local directory (`/data/avatars` in the container); S3/R2 is optional. See [Object storage](/docs/self-hosting/object-storage). |
| Mail transport | No | SMTP or a Cloudflare Email Routing Worker. If unconfigured, mail is logged and dropped. See [Email](/docs/self-hosting/email). |
| Reverse proxy | No, but expected in production | Terminates TLS. See [Reverse proxy](/docs/self-hosting/reverse-proxy). |

The container image runs as an unprivileged user, binds `0.0.0.0:8080`, persists
avatars under `/data`, and reads an operator config file from
`/config/application.conf`. The web UI is not a separate process: the Svelte/
Keel pack (`kalendee.feb`) is compiled into the server jar at build time, so
there is no Node runtime in production.

## Deployment options

| Path | Best for | Entry point |
| --- | --- | --- |
| [Docker Compose](/docs/self-hosting/docker-compose) | Most self-hosters; the supported default | `docker compose up -d` with the published image and a bundled PostgreSQL container. |
| [Standalone binary](/docs/self-hosting/binary) | Hosts that do not want containers, or want to run under systemd | The fat jar (`server-all.jar`) or the `distZip` release, plus your own PostgreSQL. |
| [NixOS](/docs/self-hosting/nixos) | Declarative, reproducible fleets | The `services.kalendee` flake module running the published OCI image. |

All three run the same server artifact and accept the same configuration.

## Decision guide

- **You want the shortest working path.** Use Docker Compose: copy `.env.example`
  and `application.conf.example`, edit the secrets and public URL, and run
  `docker compose up -d`. See [Docker Compose](/docs/self-hosting/docker-compose).
- **You already run PostgreSQL and a process supervisor.** Use the standalone
  binary with a systemd unit. See [Standalone binary](/docs/self-hosting/binary).
- **You manage hosts with Nix flakes.** Use the NixOS module. It runs the
  published image and expects you to provide PostgreSQL yourself. See
  [NixOS](/docs/self-hosting/nixos).
- **You want a fully managed image on another orchestrator** (Kubernetes, Nomad,
  Docker Swarm). Run the published image directly; the Compose page documents
  the ports, volumes, health check, and configuration precedence you need.
- **You only need avatars and mail to stay on your host.** Any path works; S3
  and external mail are independent of how the server is deployed.

## What every deployment needs

1. **A PostgreSQL 17 database and a role/password for it.** The server refuses to
   start without `KALENDEE_DATABASE_PASSWORD` (an empty value is allowed for
   trust auth). See [Database](/docs/self-hosting/database).
2. **A public base URL.** Set `app.baseUrl` (or `KALENDEE_PUBLIC_URL`) to the
   externally reachable `https://` URL. It is used in verification and invite
   emails, RSS links, and OAuth redirect URIs. There is no safe inferred default
   in production.
3. **An admin seed.** Set `auth.adminPassword` (and optionally
   `auth.adminUsername`, default `admin`) so the first startup creates an admin.
   See [Administration](/docs/self-hosting/administration).
4. **Secrets kept out of source.** Put them in `.env` (Compose), a runtime
   environment file (NixOS), or the process environment (binary) — never in a
   committed HOCON file. See [Security](/docs/self-hosting/security).

## How configuration works

Configuration is HOCON-file-first. The server loads one HOCON file; environment
variables are `${?VAR}` fallbacks *inside* that file, so a setting can live in
either place, and an unset variable leaves the file's default in place.

For the container, the entrypoint chooses the file in this order (highest
priority first):

1. `-config=<path>` already present in the container arguments.
2. `KALENDEE_CONFIG` from the environment.
3. `/config/application.conf`, if mounted.
4. `/app/application.conf`, baked into the image.
5. Otherwise, defaults embedded in the jar.

Docker Compose mounts `./application.conf` at step 3. The full key reference and
precedence rules live in [Configuration](/docs/self-hosting/configuration) and
[Environment variables](/docs/self-hosting/environment).

## Where to go next

| Topic | Page |
| --- | --- |
| Host, database, JVM, and network prerequisites | [Requirements](/docs/self-hosting/requirements) |
| The primary deployment path | [Docker Compose](/docs/self-hosting/docker-compose) |
| Running the server without containers | [Standalone binary](/docs/self-hosting/binary) |
| Declarative NixOS deployment | [NixOS](/docs/self-hosting/nixos) |
| TLS, headers, and proxying | [Reverse proxy](/docs/self-hosting/reverse-proxy) |
| Data protection and version upgrades | [Backups and upgrades](/docs/self-hosting/backups-and-upgrades) |
| Users, groups, and registration | [Administration](/docs/self-hosting/administration) |
| Hardening and the AGPL source obligation | [Security](/docs/self-hosting/security) |
| When something is broken | [Troubleshooting](/docs/self-hosting/troubleshooting) |
