#!/usr/bin/env bash
# Disposable Testcontainers only: never starts/stops ambient Compose services.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
if ! command -v docker >/dev/null 2>&1; then
  printf '%s\n' 'Live integrations unavailable: Docker CLI/engine required; no tests were run.' >&2
  exit 1
fi
if ! command -v timeout >/dev/null 2>&1; then
  printf '%s\n' 'Live integrations require timeout (coreutils) for bounded Docker preflight.' >&2
  exit 1
fi
if ! timeout 20 docker info --format '{{.ServerVersion}}' >/dev/null 2>&1; then
  printf '%s\n' 'Live integrations unavailable: Docker engine inaccessible (20s preflight); no tests were run.' >&2
  exit 1
fi
exec ./mvnw -Plive-integrations verify
