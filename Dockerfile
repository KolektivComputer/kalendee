# syntax=docker/dockerfile:1

# Stage order matters: `docker build` with no `--target` builds the LAST stage.
# `runtime` must stay last so a bare build produces the production image. CI
# (.github/workflows/docker.yml) also pins `target: runtime` defensively, and
# the `dev` stage is intermediate, selected explicitly by
# docker-compose.dev.yml via `target: dev`.

# Build stage runs on the builder's native platform so multi-arch builds do not
# emulate Gradle/Node; the fat jar is architecture-independent Java bytecode.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-noble AS build

ARG PNPM_VERSION=11.25.0

ENV GRADLE_USER_HOME=/gradle-home

# hadolint ignore=DL3008,DL4006
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install --global "pnpm@${PNPM_VERSION}" \
    && npm cache clean --force \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY . .

# The Gradle home and the pnpm content-addressable store are cached between
# builds; neither is part of the resulting image.
RUN --mount=type=cache,target=/gradle-home \
    --mount=type=cache,target=/root/.local/share/pnpm/store \
    ./gradlew :server:buildFatJar --no-daemon

# Development stage: JDK + Node + pnpm + the bind-mounted source tree. Runs as
# an arbitrary host UID (docker-compose.dev.yml sets `user:`), so the shared
# caches are world-writable.
FROM build AS dev

ENV GRADLE_USER_HOME=/gradle-home \
    PNPM_HOME=/pnpm-home \
    npm_config_store_dir=/pnpm-store

RUN mkdir -p /gradle-home /pnpm-home /pnpm-store \
    && chmod -R 1777 /gradle-home /pnpm-home /pnpm-store

WORKDIR /src

CMD ["./gradlew", "--no-daemon", ":server:run"]

# Production stage — must stay LAST so a bare `docker build` yields it. See the
# stage-order note at the top of this file.
FROM eclipse-temurin:21-jre-noble AS runtime

ARG VERSION=dev

LABEL org.opencontainers.image.title="Kalendee" \
    org.opencontainers.image.description="Self-hosted CalDAV server with multiplatform clients" \
    org.opencontainers.image.licenses="AGPL-3.0-only" \
    org.opencontainers.image.version="${VERSION}" \
    org.opencontainers.image.source="https://github.com/kolektivdev/kalendee" \
    org.opencontainers.image.url="https://github.com/kolektivdev/kalendee"

# ca-certificates is required so the JVM (AWS SDK v2 -> S3/R2, OAuth, SMTP over
# TLS) trusts public CAs. The JRE base image ships a cacerts bundle, but without
# the OS trust store a freshly provisioned TLS endpoint can fail verification.
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 kalendee \
    && useradd --uid 10001 --gid kalendee --home-dir /app --shell /usr/sbin/nologin kalendee \
    && mkdir -p /app /data/avatars /config \
    && chown -R kalendee:kalendee /app /data /config

WORKDIR /app

COPY --from=build /src/server/build/libs/server-all.jar /app/server-all.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
# Bake a readable default HOCON config. It documents every setting and uses
# `${?VAR}` fallbacks, so it works as-is and is a copy-paste starting point for
# a mounted /config/application.conf. No secrets live here: secrets come from
# env vars (docker-compose `env_file: .env`) or an operator-mounted file, both
# of which take precedence. See application.conf.example for the precedence.
COPY application.conf.example /app/application.conf
RUN chmod +x /app/entrypoint.sh \
    && chown kalendee:kalendee /app/server-all.jar /app/entrypoint.sh /app/application.conf

ENV KALENDEE_AVATAR_DIR=/data/avatars

USER kalendee
EXPOSE 8080
# /data persists avatars; /config is a supported mount point for an operator's
# application.conf. Both are declared as volumes so `docker run` without an
# explicit mount still gets writable, persistent locations.
VOLUME ["/data", "/config"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
