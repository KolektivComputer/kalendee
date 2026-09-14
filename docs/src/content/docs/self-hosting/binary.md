---
title: Standalone binary
description: >-
  Run the Kalendee fat jar or distZip release directly under Java 21, with a
  service user and a systemd unit.
---

## Artifacts

There are two equivalent ways to run the server without Docker. Both need Java
21 and a PostgreSQL 17 database you manage yourself.

| Artifact | Built by | Path | Run |
| --- | --- | --- | --- |
| Fat jar | `./gradlew :server:buildFatJar` | `server/build/libs/server-all.jar` | `java -jar server-all.jar` |
| Distribution zip | `./gradlew :server:distZip` | `server/build/distributions/server-<version>.zip` | `bin/server` inside the unzipped directory |
| Release jar | GitHub Release | `kalendee-server-<version>.jar` | Same as the fat jar. |
| Release zip | GitHub Release | `kalendee-server-<version>.zip` | Same as the distribution. |
| Web pack | GitHub Release | `kalendee-<version>.feb` | Only for standalone pack deployments; the jar already bundles the pack. |

The fat jar (`server-all.jar`) is the recommended artifact: it contains the
server, all dependencies, and the built Keel web pack. The `distZip` is the
Gradle `application` plugin's layout (a `bin/` launcher plus `lib/` jars) if you
prefer a directory-style install.

## Build from source

Java 21 is required; the Gradle wrapper fetches the toolchain if needed. The
build also builds the Keel pack, which requires Node 22+ and pnpm 11.25.0.

```bash
./gradlew :server:buildFatJar
ls -lh server/build/libs/server-all.jar

# or the distribution layout:
./gradlew :server:distZip
ls -lh server/build/distributions/server-*.zip
```

To build a release artifact with an explicit name, the release workflow uses
`./gradlew :server:buildFatJar :server:distZip -Pversion=<version>`, which is
what produces `server-<version>.zip`.

## Run the fat jar

```bash
java -jar /opt/kalendee/server-all.jar \
    -config=/etc/kalendee/application.conf
```

`-config=<path>` is how the jar is told which HOCON file to load. This is the
same precedence the container entrypoint implements, but the jar itself only
understands `-config`; it does **not** read `KALENDEE_CONFIG`. If you want
environment-driven selection, wrap the command:

```bash
exec java -jar /opt/kalendee/server-all.jar -config="${KALENDEE_CONFIG:-/etc/kalendee/application.conf}"
```

The full resolution order for a bare jar is:

| Priority | Source |
| --- | --- |
| 1 | `-config=<path>` (or `-config <path>`) on the command line. |
| 2 | `application.conf` packaged in the jar inside the classpath. There is no mounted or baked step here. |
| 3 | Values embedded in that same jar config. Environment variables are still substituted into it via `${?VAR}`. |

Environment variables (the ones used by `${?VAR}` fallbacks such as
`KALENDEE_DATABASE_PASSWORD`) must be present in the process environment. The
`.env.local` file is loaded by the Gradle `:server:run` task only; the running
jar does not read it.

If no config is passed, the jar starts on its baked defaults
(`database.url = jdbc:postgresql://127.0.0.1:5432/kalendee`) but still fails
fast if `KALENDEE_DATABASE_PASSWORD` is unset.

## Run the distribution

```bash
unzip server-1.0.0.zip -d /opt/kalendee
/opt/kalendee/server-1.0.0/bin/server -config=/etc/kalendee/application.conf
```

The launcher respects the usual `JAVA_HOME` and `JAVA_OPTS` environment
variables and forwards trailing arguments to the application.

## Configuration and secrets

Start from the committed example and keep secrets out of the file:

```bash
sudo install -d -m 0750 -o root -g kalendee /etc/kalendee
sudo cp application.conf.example /etc/kalendee/application.conf
sudo chown root:kalendee /etc/kalendee/application.conf
sudo chmod 0640 /etc/kalendee/application.conf
```

The `${?VAR}` lines in the file are filled from the process environment, so put
secrets in a root-owned environment file (mode `0600`) and reference it from
systemd. Never put the database password, `KALENDEE_SECRET_KEY`, SMTP password,
or OAuth client secrets directly in `application.conf`. See
[Configuration](/docs/self-hosting/configuration) and
[Security](/docs/self-hosting/security).

## Create the service user

The server writes only to the avatar directory (unless you use S3) and needs
read access to its jar and config. Run it as a dedicated, non-login user:

```bash
sudo useradd --system --home /opt/kalendee --shell /usr/sbin/nologin kalendee
sudo install -d -o kalendee -g kalendee /var/lib/kalendee/avatars
sudo install -m 0755 server-all.jar /opt/kalendee/server-all.jar
```

## systemd unit

`/etc/systemd/system/kalendee.service`:

```ini
[Unit]
Description=Kalendee CalDAV server
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
Type=simple
User=kalendee
Group=kalendee
WorkingDirectory=/opt/kalendee
EnvironmentFile=/etc/kalendee/kalendee.env
ExecStart=/usr/bin/java \
    -Xms128m -Xmx512m \
    -jar /opt/kalendee/server-all.jar \
    -config=/etc/kalendee/application.conf
Restart=on-failure
RestartSec=5

# Hardening (the server needs no privileges).
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/kalendee
CapabilityBoundingSet=
AmbientCapabilities=

[Install]
WantedBy=multi-user.target
```

`/etc/kalendee/kalendee.env` (root-owned, mode `0600`) carries secrets and the
avatar location the HOCON expects:

```bash
KALENDEE_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/kalendee
KALENDEE_DATABASE_USER=kalendee
KALENDEE_DATABASE_PASSWORD=change-me
KALENDEE_ADMIN_PASSWORD=change-me
KALENDEE_PUBLIC_URL=https://calendar.example.com
# KALENDEE_AVATAR_DIR must match a path inside ReadWritePaths.
KALENDEE_AVATAR_DIR=/var/lib/kalendee/avatars
```

Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now kalendee
systemctl status kalendee
```

## Logs

Logback writes to stdout by default, so systemd captures it in the journal:

```bash
journalctl -u kalendee -f
journalctl -u kalendee --since "10 min ago"
```

The baked `logback.xml` sets the root logger to `trace`, which is noisy in
production. Override it with a config you control:

```bash
java -Dlogback.configurationFile=/etc/kalendee/logback.xml \
    -jar /opt/kalendee/server-all.jar -config=/etc/kalendee/application.conf
```

Add the same `-D` to `ExecStart` in the unit. A minimal override sets the root
logger to `INFO`.

## Health check and operations

```bash
curl -fsS http://localhost:8080/api/v1/health   # {"status":"ok"}
```

The endpoint verifies the database connection, so it fails until the database is
reachable and migrations have run. On upgrade, apply the same order as the
container path: back up, replace the jar, restart, and let Flyway migrate. See
[Backups and upgrades](/docs/self-hosting/backups-and-upgrades).
