#!/bin/sh
# Kalendee container entrypoint.
#
# Starts the fat jar, letting Ktor (`EngineMain`) load HOCON configuration from
# the best available source. Resolution order, highest priority first:
#
#   1. A `-config=<path>` already present in the arguments forwarded after the
#      image name (e.g. `docker run image -config=/my/other.conf`). It wins and
#      is passed through untouched.
#   2. $KALENDEE_CONFIG, when set and non-empty.
#   3. /config/application.conf, when an operator mounted one there.
#   4. /app/application.conf, the readable default baked into the image.
#
# Docker-compose.yml mounts ./application.conf at /config/application.conf, so
# (3) is the normal production path; with nothing mounted the image still boots
# on its baked config (4). Environment variables are NOT read here: they are
# `${?VAR}` substitutions resolved inside whichever HOCON file Ktor loads. See
# application.conf.example for the full precedence explanation.
set -eu

log() {
    # Keep this on stderr so it never pollutes stdout.
    echo "kalendee-entrypoint: $*" >&2
}

# `-config=...` already forwarded on the command line? Accept both the joined
# and the split (`-config <path>`) forms so we never duplicate or override an
# explicit operator choice.
has_explicit_config() {
    for arg in "$@"; do
        case "$arg" in
            -config=* | -config) return 0 ;;
        esac
    done
    return 1
}

if has_explicit_config "$@"; then
    config_arg=""
    log "using -config from the command line"
elif [ -n "${KALENDEE_CONFIG:-}" ]; then
    config_arg="-config=${KALENDEE_CONFIG}"
    log "using KALENDEE_CONFIG=${KALENDEE_CONFIG}"
elif [ -f /config/application.conf ]; then
    config_arg="-config=/config/application.conf"
    log "using mounted config /config/application.conf"
elif [ -f /app/application.conf ]; then
    config_arg="-config=/app/application.conf"
    log "using baked config /app/application.conf"
else
    config_arg=""
    log "no config file found; falling back to jar defaults"
fi

# JAVA_OPTS is a deliberately unquoted space-separated list of JVM flags, and
# config_arg is either empty or a single argument; both are meant to be word
# split here so they reach java as separate arguments. $@ is forwarded
# unmodified (quoted) so extra args keep their boundaries.
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /app/server-all.jar ${config_arg} "$@"
