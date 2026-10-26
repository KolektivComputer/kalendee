#!/usr/bin/env bash
# Smoke test the published runtime image: build (or reuse) the Dockerfile's
# final `runtime` stage, boot it next to a disposable PostgreSQL, and exercise
# the paths that only exist once the pack is baked into the image — the Keel
# page shell, the real login page module, and an end-to-end login.
#
# Prerequisites: docker (daemon running) and curl.
#
# Usage:
#   scripts/smoke-image.sh [--no-build] [--keep]
#
# Env:
#   IMAGE_TAG  image to build and run   (default: kalendee-smoke:local)
#   HOST_PORT  localhost port to publish (default: 18080)
#   PG_IMAGE   postgres image            (default: postgres:17-alpine)
#
# Flags:
#   --no-build  skip `docker build` and use IMAGE_TAG as-is
#   --keep      leave the containers/network up on failure for debugging
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

IMAGE_TAG="${IMAGE_TAG:-kalendee-smoke:local}"
HOST_PORT="${HOST_PORT:-18080}"
PG_IMAGE="${PG_IMAGE:-postgres:17-alpine}"

build=1
keep=0
for arg in "$@"; do
  case "$arg" in
    --no-build) build=0 ;;
    --keep) keep=1 ;;
    *)
      echo "smoke-image: unknown argument: $arg (expected --no-build or --keep)" >&2
      exit 2
      ;;
  esac
done

for tool in docker curl; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "smoke-image: $tool is required" >&2
    exit 1
  fi
done
if ! docker info >/dev/null 2>&1; then
  echo "smoke-image: docker daemon is not reachable" >&2
  exit 1
fi

suffix="$$-$RANDOM"
network="kalendee-smoke-net-$suffix"
pg="kalendee-smoke-pg-$suffix"
app="kalendee-smoke-app-$suffix"
cookie_jar="$(mktemp)"
body="$(mktemp)"
base_url="http://127.0.0.1:${HOST_PORT}"

cleanup() {
  status=$?
  rm -f "$cookie_jar" "$body"
  if [ "$keep" -eq 1 ] && [ "$status" -ne 0 ]; then
    echo "smoke-image: --keep set, leaving $app / $pg / $network up for debugging" >&2
    return
  fi
  docker rm -f "$app" >/dev/null 2>&1 || true
  docker rm -f "$pg" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

app_running() {
  [ "$(docker inspect -f '{{.State.Running}}' "$app" 2>/dev/null || echo false)" = "true" ]
}

dump_app_logs() {
  echo "--- docker logs $app ---" >&2
  docker logs "$app" >&2 2>&1 || true
  echo "--- end docker logs $app ---" >&2
}

fail() {
  echo "FAIL: $1" >&2
  echo "--- failing response ---" >&2
  cat "$body" >&2 || true
  echo >&2
  dump_app_logs
  exit 1
}

pass() {
  echo "PASS: $1"
}

if [ "$build" -eq 1 ]; then
  echo "==> docker build --target runtime --build-arg VERSION=smoke -t $IMAGE_TAG ."
  docker build --target runtime --build-arg VERSION=smoke -t "$IMAGE_TAG" .
fi

echo "==> creating network $network and postgres container $pg ($PG_IMAGE)"
docker network create "$network" >/dev/null
docker run -d --name "$pg" --network "$network" \
  -e POSTGRES_DB=kalendee \
  -e POSTGRES_USER=kalendee \
  -e POSTGRES_PASSWORD=smoke \
  "$PG_IMAGE" >/dev/null

echo -n "==> waiting for postgres"
deadline=$((SECONDS + 60))
until docker exec "$pg" pg_isready -U kalendee -d kalendee >/dev/null 2>&1; do
  if [ "$(docker inspect -f '{{.State.Running}}' "$pg" 2>/dev/null || echo false)" != "true" ]; then
    echo
    docker logs "$pg" >&2 || true
    echo "smoke-image: postgres container exited before becoming ready" >&2
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo
    docker logs "$pg" >&2 || true
    echo "smoke-image: postgres did not become ready within 60s" >&2
    exit 1
  fi
  sleep 1
  echo -n .
done
echo " ready"

echo "==> starting app container $app (127.0.0.1:$HOST_PORT -> 8080)"
docker run -d --name "$app" --network "$network" \
  -p "127.0.0.1:${HOST_PORT}:8080" \
  -e "KALENDEE_DATABASE_URL=jdbc:postgresql://${pg}:5432/kalendee" \
  -e KALENDEE_DATABASE_USER=kalendee \
  -e KALENDEE_DATABASE_PASSWORD=smoke \
  -e KALENDEE_ADMIN_USERNAME=admin \
  -e KALENDEE_ADMIN_PASSWORD=smoke-admin \
  -e "KALENDEE_PUBLIC_URL=${base_url}" \
  -e KALENDEE_COOKIE_SECURE=false \
  "$IMAGE_TAG" >/dev/null

echo -n "==> waiting for ${base_url}/api/v1/health"
deadline=$((SECONDS + 120))
ready=0
until [ "$ready" -eq 1 ]; do
  if ! app_running; then
    echo
    dump_app_logs
    echo "smoke-image: app container exited before becoming ready" >&2
    exit 1
  fi
  code="$(curl -sS -o "$body" -w '%{http_code}' --max-time 5 "$base_url/api/v1/health" 2>/dev/null || true)"
  if [ "$code" = "200" ]; then
    ready=1
    break
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo
    echo "smoke-image: app did not become ready within 120s (last HTTP status: ${code:-none})" >&2
    dump_app_logs
    exit 1
  fi
  sleep 2
  echo -n .
done
echo " ready"

if grep -q '"ok"' "$body"; then
  pass "GET /api/v1/health body contains \"ok\""
else
  fail "GET /api/v1/health body does not contain \"ok\""
fi

code="$(curl -sS -o "$body" -w '%{http_code}' --max-time 15 "$base_url/login")"
if [ "$code" != "200" ]; then
  fail "GET /login returned $code"
fi
if ! grep -q '<html' "$body"; then
  fail "GET /login did not return an HTML document"
fi
pass "GET /login returns 200 HTML"

code="$(curl -sS -o "$body" -w '%{http_code}' --max-time 15 "$base_url/__keel/pack/kalendee/bootstrap.js")"
if [ "$code" != "200" ] || [ ! -s "$body" ]; then
  fail "GET /__keel/pack/kalendee/bootstrap.js returned $code (non-empty: $([ -s "$body" ] && echo yes || echo no))"
fi
pass "GET /__keel/pack/kalendee/bootstrap.js returns 200 non-empty JS"

code="$(curl -sS -o "$body" -w '%{http_code}' --max-time 15 "$base_url/__keel/pack/kalendee/pages/kalendee.login.js")"
if [ "$code" != "200" ]; then
  fail "GET /__keel/pack/kalendee/pages/kalendee.login.js returned $code"
fi
if ! grep -q 'Sign in' "$body"; then
  fail "GET /__keel/pack/kalendee/pages/kalendee.login.js does not contain \"Sign in\""
fi
pass "GET /__keel/pack/kalendee/pages/kalendee.login.js contains the login page module"

code="$(curl -sS -o "$body" -w '%{http_code}' --max-time 15 \
  -c "$cookie_jar" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"smoke-admin"}' \
  "$base_url/api/v1/auth/login")"
if [ "$code" != "200" ]; then
  fail "POST /api/v1/auth/login returned $code"
fi
if ! grep -q 'admin' "$body"; then
  fail "POST /api/v1/auth/login response does not mention admin"
fi
pass "POST /api/v1/auth/login returns 200 and sets the session cookie"

code="$(curl -sS -o "$body" -w '%{http_code}' --max-time 15 -b "$cookie_jar" "$base_url/api/v1/calendars")"
if [ "$code" != "200" ]; then
  fail "authenticated GET /api/v1/calendars returned $code"
fi
pass "authenticated GET /api/v1/calendars returns 200"

echo "smoke-image: all checks passed against $IMAGE_TAG at $base_url"
