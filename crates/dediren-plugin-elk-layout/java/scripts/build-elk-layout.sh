#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd -P)
REPO_ROOT=$(cd -- "$PROJECT_DIR/../../.." && pwd -P)

LOCK_PARENT="$REPO_ROOT/.cache/locks"
LOCK_FILE="$LOCK_PARENT/elk-layout-java-build.lock"
LOCK_CANDIDATE="$LOCK_PARENT/elk-layout-java-build.$$.$RANDOM.lock"
STALE_CLEANUP_DIR="$LOCK_PARENT/elk-layout-java-build.stale-cleanup.lock.d"

mkdir -p "$LOCK_PARENT"
printf '%s\n' "$$" >"$LOCK_CANDIDATE"

remove_stale_lock() {
  local expected_pid="$1"
  local reason="$2"
  local current_pid

  if ! mkdir "$STALE_CLEANUP_DIR" 2>/dev/null; then
    return 1
  fi

  current_pid=$(sed -n '1p' "$LOCK_FILE" 2>/dev/null || true)
  case "$reason" in
    invalid)
      if [[ "$current_pid" =~ ^[0-9]+$ ]]; then
        rmdir "$STALE_CLEANUP_DIR" 2>/dev/null || true
        return 1
      fi
      echo "removing invalid ELK Java helper build lock" >&2
      ;;
    dead)
      if [[ "$current_pid" != "$expected_pid" ]] || kill -0 "$current_pid" 2>/dev/null; then
        rmdir "$STALE_CLEANUP_DIR" 2>/dev/null || true
        return 1
      fi
      echo "removing stale ELK Java helper build lock held by exited process $current_pid" >&2
      ;;
  esac

  rm -f "$LOCK_FILE"
  rmdir "$STALE_CLEANUP_DIR" 2>/dev/null || true
}

while ! ln "$LOCK_CANDIDATE" "$LOCK_FILE" 2>/dev/null; do
  LOCK_PID=$(sed -n '1p' "$LOCK_FILE" 2>/dev/null || true)
  if [[ ! "$LOCK_PID" =~ ^[0-9]+$ ]]; then
    remove_stale_lock "$LOCK_PID" invalid || true
    continue
  fi
  if ! kill -0 "$LOCK_PID" 2>/dev/null; then
    remove_stale_lock "$LOCK_PID" dead || true
    continue
  fi
  echo "another ELK Java helper build is running; waiting for $LOCK_FILE" >&2
  sleep 1
done
cleanup_lock() {
  if [[ -e "$LOCK_FILE" && "$LOCK_FILE" -ef "$LOCK_CANDIDATE" ]]; then
    rm -f "$LOCK_FILE" 2>/dev/null || true
  fi
  rm -f "$LOCK_CANDIDATE" 2>/dev/null || true
}
trap cleanup_lock EXIT

cd "$PROJECT_DIR"

SDKMAN_INIT="$HOME/.sdkman/bin/sdkman-init.sh"
USE_SDKMAN="${DEDIREN_ELK_BUILD_USE_SDKMAN:-auto}"

activate_sdkmanrc_candidate() {
  local candidate="$1"
  local version="$2"
  local candidate_dir="$HOME/.sdkman/candidates/$candidate/$version"
  if [[ ! -d "$candidate_dir" ]]; then
    echo "SDKMAN candidate $candidate=$version is not installed at $candidate_dir" >&2
    return 1
  fi
  case "$candidate" in
    java)
      export JAVA_HOME="$candidate_dir"
      ;;
    gradle)
      export GRADLE_HOME="$candidate_dir"
      ;;
  esac
  PATH="$candidate_dir/bin:$PATH"
}

activate_sdkmanrc_candidates() {
  local sdkmanrc="$PROJECT_DIR/.sdkmanrc"
  local missing=0
  [[ -f "$sdkmanrc" ]] || return 0
  while IFS='=' read -r candidate version; do
    [[ -n "${candidate:-}" ]] || continue
    [[ "$candidate" != \#* ]] || continue
    [[ -n "${version:-}" ]] || continue
    if ! activate_sdkmanrc_candidate "$candidate" "$version"; then
      missing=1
    fi
  done <"$sdkmanrc"
  return "$missing"
}

case "$USE_SDKMAN" in
  "" | auto)
    if [[ -s "$SDKMAN_INIT" ]]; then
      USE_SDKMAN=1
    else
      USE_SDKMAN=0
      echo "SDKMAN not found; using java and gradle from PATH" >&2
    fi
    ;;
  1)
    if [[ ! -s "$SDKMAN_INIT" ]]; then
      echo "SDKMAN is required when DEDIREN_ELK_BUILD_USE_SDKMAN=1, but $SDKMAN_INIT was not found" >&2
      exit 2
    fi
    ;;
  0)
    ;;
  *)
    echo "DEDIREN_ELK_BUILD_USE_SDKMAN must be auto, 1, or 0; found $USE_SDKMAN" >&2
    exit 2
    ;;
esac

if [[ "$USE_SDKMAN" == "1" ]]; then
  if ! activate_sdkmanrc_candidates; then
    echo "Install missing SDKMAN candidates by running sdk env install from $PROJECT_DIR" >&2
    exit 2
  fi
  export PATH
fi

if [[ -z "${GRADLE_USER_HOME:-}" ]]; then
  if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
    export GRADLE_USER_HOME="$HOME/.gradle"
  else
    export GRADLE_USER_HOME="$REPO_ROOT/.cache/gradle/user-home"
  fi
else
  export GRADLE_USER_HOME
fi
PROJECT_CACHE_DIR="${DEDIREN_ELK_GRADLE_PROJECT_CACHE_DIR:-$REPO_ROOT/.cache/gradle/project-cache/elk-layout-java}"

mkdir -p "$GRADLE_USER_HOME" "$PROJECT_CACHE_DIR"

if ! command -v java >/dev/null 2>&1; then
  echo "java is required to build the ELK Java helper, but it was not found on PATH" >&2
  exit 2
fi

if ! command -v gradle >/dev/null 2>&1; then
  echo "gradle is required to build the ELK Java helper, but it was not found on PATH" >&2
  exit 2
fi

JAVA_VERSION_OUTPUT=$(java -version 2>&1 | sed -n '1p')
JAVA_VERSION=$(printf '%s\n' "$JAVA_VERSION_OUTPUT" | sed -E 's/.*version "([^"]+)".*/\1/')
if [[ "$JAVA_VERSION" == "$JAVA_VERSION_OUTPUT" ]]; then
  JAVA_VERSION=$(printf '%s\n' "$JAVA_VERSION_OUTPUT" | sed -E 's/^[^0-9]*([0-9][^[:space:]]*).*/\1/')
fi
JAVA_MAJOR=${JAVA_VERSION%%.*}

if [[ "$JAVA_MAJOR" != "25" ]]; then
  echo "Java 25 is required to build the ELK Java helper; found Java $JAVA_VERSION" >&2
  exit 2
fi

GRADLE_VERSION_OUTPUT=$(gradle --version 2>&1)
GRADLE_VERSION=$(printf '%s\n' "$GRADLE_VERSION_OUTPUT" | sed -nE 's/^Gradle ([0-9]+(\.[0-9]+){0,2}).*/\1/p' | head -n 1)
if [[ "$GRADLE_VERSION" != "9.5.0" ]]; then
  echo "Gradle 9.5.0 is required to build the ELK Java helper; found Gradle ${GRADLE_VERSION:-unknown}" >&2
  exit 2
fi

GRADLE_LAUNCHER_JVM=$(
  printf '%s\n' "$GRADLE_VERSION_OUTPUT" \
    | sed -nE 's/^Launcher JVM:[[:space:]]+([0-9]+).*/\1/p' \
    | head -n 1
)
if [[ "$GRADLE_LAUNCHER_JVM" != "25" ]]; then
  echo "Gradle must run on Java 25; Gradle launcher JVM is Java ${GRADLE_LAUNCHER_JVM:-unknown}" >&2
  exit 2
fi

gradle \
  --project-cache-dir "$PROJECT_CACHE_DIR" \
  -p "$PROJECT_DIR" \
  clean test installDist
