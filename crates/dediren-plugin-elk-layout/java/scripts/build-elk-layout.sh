#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../../../.." && pwd -P)

cd "$REPO_ROOT"

if [[ -z "${GRADLE_USER_HOME:-}" ]]; then
  export GRADLE_USER_HOME="$REPO_ROOT/.cache/gradle/user-home"
fi

exec "$REPO_ROOT/gradlew" :modules:plugins:elk-layout:installDist "$@"
