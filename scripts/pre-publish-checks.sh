#!/usr/bin/env bash
# Run every pre-publish gate in order. Used locally and by the Docker workflow
# before the runtime image is pushed.
#
# Usage:
#   scripts/pre-publish-checks.sh [--no-build]
#
#   --no-build   forwarded to scripts/smoke-image.sh (reuse an existing image)
#   SMOKE_ARGS   extra arguments forwarded to scripts/smoke-image.sh
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

smoke_args=()
for arg in "$@"; do
  case "$arg" in
    --no-build) smoke_args+=("$arg") ;;
    *)
      echo "pre-publish-checks: unknown argument: $arg (expected --no-build)" >&2
      exit 2
      ;;
  esac
done
if [ -n "${SMOKE_ARGS:-}" ]; then
  # Deliberately word-split: SMOKE_ARGS is a space-separated list of flags.
  read -r -a extra_args <<<"$SMOKE_ARGS"
  smoke_args+=("${extra_args[@]}")
fi

echo "==> pack page-context guard"
node scripts/check-page-context.mjs

echo "==> flake check"
scripts/check-flake.sh

echo "==> runtime image smoke test"
scripts/smoke-image.sh ${smoke_args[@]+"${smoke_args[@]}"}

echo "pre-publish-checks: all gates passed"
