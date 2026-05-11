#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd -P)
REPO_ROOT=$(cd -- "$PROJECT_DIR/../../.." && pwd -P)
LOCK_DIR="$REPO_ROOT/.cache/locks"

if ! command -v flock >/dev/null 2>&1; then
  echo "flock is required to serialize the ELK Java helper build" >&2
  exit 2
fi

mkdir -p "$LOCK_DIR"
exec 8>"$LOCK_DIR/elk-layout-java-build.lock"
if ! flock -n 8; then
  echo "another ELK Java helper build is running; waiting for $LOCK_DIR/elk-layout-java-build.lock" >&2
  flock 8
fi

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
