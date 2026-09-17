#!/usr/bin/env bash
# Evaluate the Nix flake the way a consumer would: check it, then show it.
#
# Prerequisites: nix with flakes enabled (install Nix or skip this check).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if ! command -v nix >/dev/null 2>&1; then
  echo "check-flake: nix is not installed; install Nix or skip this check" >&2
  exit 1
fi

echo "==> nix flake check --no-build"
nix flake check --no-build

echo "==> nix flake show"
nix flake show
