# syntax=docker/dockerfile:1

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

FROM eclipse-temurin:21-jre-noble AS runtime

ARG VERSION=dev

LABEL org.opencontainers.image.title="Kalendee" \
    org.opencontainers.image.description="Self-hosted CalDAV server with multiplatform clients" \
    org.opencontainers.image.licenses="AGPL-3.0-only" \
    org.opencontainers.image.version="${VERSION}" \
    org.opencontainers.image.source="https://git.yuri.capital/kolektiv/kalendee" \
    org.opencontainers.image.url="https://git.yuri.capital/kolektiv/kalendee"

# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 kalendee \
    && useradd --uid 10001 --gid kalendee --home-dir /app --shell /usr/sbin/nologin kalendee \
    && mkdir -p /app /data/avatars /config \
    && chown -R kalendee:kalendee /app /data /config

WORKDIR /app

COPY --from=build /src/server/build/libs/server-all.jar /app/server-all.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh \
    && chown kalendee:kalendee /app/server-all.jar /app/entrypoint.sh

ENV KALENDEE_AVATAR_DIR=/data/avatars

USER kalendee
EXPOSE 8080
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]

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
