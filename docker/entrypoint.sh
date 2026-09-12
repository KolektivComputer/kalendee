#!/bin/sh
set -eu

# KALENDEE_CONFIG optionally points Ktor EngineMain at an external HOCON file
# (for example /config/application.conf). When unset, the config embedded in
# the jar is used.
config_arg=""
if [ -n "${KALENDEE_CONFIG:-}" ]; then
    config_arg="-config=${KALENDEE_CONFIG}"
fi

# JAVA_OPTS is a deliberately unquoted space-separated list of JVM flags, and
# config_arg is either empty or a single argument; both are meant to be word
# split here so they reach java as separate arguments. Args passed after the
# image name (for example `docker run image -config=...`) are forwarded too.
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /app/server-all.jar ${config_arg} "$@"
