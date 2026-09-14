---
title: Backups and upgrades
description: >-
  Back up PostgreSQL, avatars, and secrets; understand Flyway migrations on
  startup; upgrade safely and know the limits of rolling back.
---

## What to back up

| Data | Where | If lost |
| --- | --- | --- |
| PostgreSQL database | `kalendee-postgres` volume, or your own PostgreSQL | Catastrophic: users, calendars, events, shares, settings, connections. |
| Avatar files (local storage) | `kalendee-avatars` volume, or `KALENDEE_AVATAR_DIR` | Avatars are gone; users must re-upload. Referenced keys remain in the DB and render as missing. |
| Avatar files (S3/R2) | Your bucket | Same as above; enable bucket versioning or lifecycle rules. |
| Secrets and config | `.env`, `application.conf`, `environmentFile` | Cannot connect to the DB or decrypt tokens. Back up out of band. |
| `KALENDEE_SECRET_KEY` | Your secret store | External calendar connections cannot be decrypted and must be re-linked. No plaintext fallback exists. |

The database is authoritative. Avatars and tokens are secondary but should be
captured together with a recent database dump.

## Back up PostgreSQL

For Docker Compose, use the database container so you match the server's client
version:

```bash
mkdir -p backups
docker compose exec -T postgres \
    pg_dump -U "${POSTGRES_USER:-kalendee}" -d "${POSTGRES_DB:-kalendee}" \
    -Fc > "backups/kalendee-$(date +%F-%H%M).dump"
```

For a standalone binary against a local PostgreSQL:

```bash
pg_dump -U kalendee -h 127.0.0.1 -Fc kalendee > "backups/kalendee-$(date +%F-%H%M).dump"
```

The custom format (`-Fc`) is compressed and restorable selectively. For a
logical, human-readable dump use `-Fp` instead:

```bash
pg_dump -U kalendee kalendee | gzip > backups/kalendee.sql.gz
```

A dump is only consistent at a point in time for a single database. Avatars
written between the dump and the avatar snapshot may be missing, and vice
versa; for tightly coupled backups, stop the server (or pause writes) first:

```bash
docker compose stop kalendee
docker compose exec -T postgres pg_dump ... > backups/kalendee.dump
# snapshot avatars now
docker compose start kalendee
```

## Back up avatars

Local storage, from the named volume:

```bash
docker run --rm \
    -v kalendee_kalendee-avatars:/data:ro \
    -v "$PWD/backups":/backup \
    alpine tar czf /backup/avatars-$(date +%F).tgz -C /data .
```

(Compose prefixes volume names with the project name, hence
`kalendee_kalendee-avatars`; check `docker volume ls` for the exact name.) For a
host directory, tar it directly:

```bash
tar czf backups/avatars-$(date +%F).tgz -C /var/lib/kalendee avatars
```

S3/R2:

```bash
rclone sync r2:kalendee backups/avatars
# or, with the AWS CLI:
aws s3 sync "s3://kalendee" backups/avatars \
    --endpoint-url "https://<account-id>.r2.cloudflarestorage.com"
```

Object keys are `avatars/<userId>/<uuid>.<ext>`, so bucket-level backup and
restore preserve them.

## Flyway migrations on startup

The server runs Flyway automatically on every startup, before it begins serving
traffic. Migrations live in `server/src/main/resources/db/migration` and are
numbered `V1` through `V19` today. Flyway records applied versions in the
`flyway_schema_history` table and applies only pending ones.

Practical consequences:

- **Forward-only.** There are no down migrations. Flyway Community has no
  `undo`, and the schema is not intended to be downgraded.
- **A failing migration aborts startup.** The process exits and the container
  restarts; nothing half-applied is served. Fix the database or restore a dump.
- **A newer server against an older database migrates up.** An older server
  against a newer database is unsupported: its code expects the older schema and
  Flyway will not undo it.
- **Checksums are enforced.** Editing an already-applied migration file changes
  its checksum and makes startup fail. Never edit shipped migrations; add a new
  one.

## Upgrade procedure

1. Back up the database and avatars as above, and confirm the dump is non-empty.
2. Note the current version (`KALENDEE_IMAGE_TAG`, jar filename, or release tag).
3. Read the release notes for any migration or configuration changes.
4. Update to the new version:
   - Compose: set `KALENDEE_IMAGE_TAG` to the release, then `docker compose pull && docker compose up -d`.
   - Binary: replace `server-all.jar` and restart the service.
   - NixOS: bump `imageTag` and `nixos-rebuild switch`.
5. Watch startup logs for Flyway output and a clean listen:
   `docker compose logs -f kalendee` (or `journalctl -u kalendee -f`).
6. Verify: `curl -fsS http://localhost:8080/api/v1/health` returns
   `{"status":"ok"}`, then log in and open a calendar.

Pin a specific version instead of `latest` so a restart cannot silently move
you across a migration boundary.

## Rollback caveats

Rolling back the **image/jar** does not roll back the **schema**. If the new
release applied a migration, the old binary may fail against the migrated
database. Options, in order of preference:

1. **Fix forward.** Deploy a newer fixed release.
2. **Restore.** Stop the server, restore the pre-upgrade dump (and matching
   avatars), then run the old version.

There is no supported in-place schema downgrade.

## Restore

Stop the server first so it cannot hold connections or run migrations:

```bash
docker compose stop kalendee
```

Recreate the database cleanly, then restore. With Compose:

```bash
docker compose exec -T postgres \
    psql -U "${POSTGRES_USER:-kalendee}" -d postgres \
    -c 'DROP DATABASE IF EXISTS kalendee WITH (FORCE);' \
    -c 'CREATE DATABASE kalendee OWNER kalendee;'

docker compose exec -T postgres \
    pg_restore -U "${POSTGRES_USER:-kalendee}" -d "${POSTGRES_DB:-kalendee}" \
    --no-owner < backups/kalendee.dump
```

Restore avatars into the volume (matching the backup command):

```bash
docker run --rm \
    -v kalendee_kalendee-avatars:/data \
    -v "$PWD/backups":/backup \
    alpine sh -c 'tar xzf /backup/avatars-2026-01-01.tgz -C /data'
```

Then start the server and verify:

```bash
docker compose start kalendee
docker compose logs -f kalendee
curl -fsS http://localhost:8080/api/v1/health
```

Practice a restore into a throwaway database periodically; an untested backup is
not a backup.
