#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd -P)
REPO_ROOT=$(cd -- "$PROJECT_DIR/../../.." && pwd -P)

if [[ ! -s "$HOME/.sdkman/bin/sdkman-init.sh" ]]; then
  echo "SDKMAN is required: install SDKMAN, then run sdk env install from $PROJECT_DIR" >&2
  exit 2
fi

set +u
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd "$PROJECT_DIR"
sdk env
set -u

export GRADLE_USER_HOME="$REPO_ROOT/.cache/gradle/user-home"
mkdir -p "$GRADLE_USER_HOME" "$REPO_ROOT/.cache/gradle/project-cache"

gradle \
  --project-cache-dir "$REPO_ROOT/.cache/gradle/project-cache/elk-layout-java" \
  -p "$PROJECT_DIR" \
  clean test installDist
