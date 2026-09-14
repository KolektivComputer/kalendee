---
title: Database
description: >-
  PostgreSQL is the only supported database for Kalendee: JDBC settings, the
  Hikari pool, startup Flyway migrations, version requirements, and connection
  troubleshooting.
---

Kalendee stores all state in **PostgreSQL**. There is no SQLite, MySQL, or other
backend: the code connects with the PostgreSQL JDBC driver, Exposed, and startup
[Flyway](https://documentation.red-gate.com/flyway) migrations written for
PostgreSQL. Any non-PostgreSQL JDBC URL is outside the supported configuration.

## Connection keys

| Key | Type | Default | Env fallback | Meaning |
| --- | --- | --- | --- | --- |
| `database.url` | string | `jdbc:postgresql://127.0.0.1:5432/kalendee` | `KALENDEE_DATABASE_URL` | JDBC URL. May embed credentials in the userinfo section. |
| `database.user` | string | `kalendee` (or parsed from the URL) | `KALENDEE_DATABASE_USER` | Role used to connect. |
| `database.password` | string | *required* | `KALENDEE_DATABASE_PASSWORD` | Password. An empty value is allowed for trust/peer auth. |
| `database.migrations` | string | `classpath:db/migration` | — | Flyway location. Only needed for tests or external migration directories. |

The password has no default and startup fails without it:

```text
KALENDEE_DATABASE_PASSWORD is required (set an empty value for trust auth)
```

Setting `KALENDEE_DATABASE_PASSWORD=` (empty) satisfies the requirement, which
is what you want behind `trust`/local peer authentication.

## Credentials embedded in the JDBC URL

If the URL starts with `jdbc:postgresql://` and contains a `user:password@`
userinfo segment, the server splits it off and rewrites the URL. For example:

| Input `database.url` | Rewritten JDBC URL | Parsed user | Parsed password |
| --- | --- | --- | --- |
| `jdbc:postgresql://alice:s3cret@db:5432/kalendee` | `jdbc:postgresql://db:5432/kalendee` | `alice` | `s3cret` |
| `jdbc:postgresql://db:5432/kalendee` | unchanged | *none* | *none* |
| `jdbc:postgresql://alice@db:5432/kalendee` | `jdbc:postgresql://db:5432/kalendee` | `alice` | *none* |

Resolution order:

1. `database.user` if set, otherwise the URL user, otherwise `kalendee`.
2. `database.password` if set, otherwise the URL password; if neither exists,
   startup fails.

Embedding credentials in the URL is supported but not recommended — prefer the
dedicated keys so the password can come from the environment. URLs that do not
begin with `jdbc:postgresql://` are passed through untouched.

## Connection pool (HikariCP)

The pool is created in code and is **not configurable from HOCON**; only the
URL, user, and password come from configuration:

| Setting | Value |
| --- | --- |
| `maximumPoolSize` | `10` |
| `minimumIdle` | `2` |
| `autoCommit` | `false` |
| `transactionIsolation` | `TRANSACTION_REPEATABLE_READ` |

Size PostgreSQL's `max_connections` for at least 10 connections per Kalendee
instance, plus whatever else shares the server. If you run multiple app
replicas, budget `10 × replicas` connections against the same database.

## Migrations

Flyway runs automatically on startup, before the server accepts traffic. It
creates the `flyway_schema_history` table and applies any migration whose
version is newer than what the database has recorded. Migrations live in
[`server/src/main/resources/db/migration`](https://github.com/KolektivComputer/kalendee/tree/main/server/src/main/resources/db/migration)
and are named `V<n>__<description>.sql`, from `V1__calendars_and_events.sql`
through `V19__discord_event_routes.sql`.

- Migrations are forward-only and must not be edited after release.
- Upgrading the image applies new migrations on the next boot; see
  [Backups and upgrades](/docs/self-hosting/backups-and-upgrades).
- The configured `database.migrations` location is used verbatim if it starts
  with `classpath:` or `filesystem:`; any other value is prefixed with
  `filesystem:`. The default `classpath:db/migration` reads the migrations
  bundled in the jar.

## Required PostgreSQL version

The repository does not perform a version check in code; the supported target
is whatever the project ships and tests against. `docker-compose.yml` and
`docker-compose.dev.yml` both use **`postgres:17-alpine`**, and the bundled
driver is PostgreSQL JDBC `42.7.7`.

For a self-managed server, PostgreSQL 17 is the tested baseline. Newer major
versions generally work because the schema uses standard SQL types (`UUID`,
`TIMESTAMPTZ`, `BOOLEAN`, `TEXT`, `BIGINT`), but the project only guarantees the
version used in its images.

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `KALENDEE_DATABASE_PASSWORD is required` at startup | No password key and no password in the URL. | Set `KALENDEE_DATABASE_PASSWORD` (empty is allowed for trust auth). |
| `Connection refused` / `Connection to ... refused` | Wrong host, port, or database not started. | Verify `database.url`; in compose the host is the `postgres` service name, not `localhost`. For a host-run server use `127.0.0.1`. |
| `FATAL: password authentication failed` | Credentials mismatch. | Ensure `KALENDEE_DATABASE_PASSWORD` equals `POSTGRES_PASSWORD`, and `KALENDEE_DATABASE_USER` matches `POSTGRES_USER`. |
| `FATAL: database "kalendee" does not exist` | DB not created. | Create it or set `POSTGRES_DB`. In compose the postgres service creates it from the env. |
| Flyway `Validate failed` / checksum mismatch | A released migration file was edited, or the DB was migrated by a different version. | Never edit applied migrations; restore from backup and re-run the correct version. |
| Flyway `relation already exists` | Schema created outside Flyway or a partially applied migration. | Inspect `flyway_schema_history`; reconcile the schema instead of blindly rerunning. |
| `too many connections` | Pool (10 per instance) times replicas exceeds `max_connections`. | Raise `max_connections` or reduce replicas. |

Verify connectivity independently of the app:

```bash
psql "postgresql://kalendee:password@127.0.0.1:5432/kalendee" -c 'select 1'
```

Health check once the server is up:

```bash
curl -fsS http://127.0.0.1:8080/api/v1/health   # {"status":"ok"}
```

The health route calls `store.ping()`, so it fails if the database is
unreachable.

## Related pages

- [Configuration](/docs/self-hosting/configuration) — full key reference.
- [Environment variables](/docs/self-hosting/environment) — `KALENDEE_DATABASE_*`.
- [Docker Compose](/docs/self-hosting/docker-compose) — the bundled PostgreSQL
  service.
- [Backups and upgrades](/docs/self-hosting/backups-and-upgrades) — dump and
  restore, migration safety.
