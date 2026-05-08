#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd -P)
APP="$PROJECT_DIR/build/install/dediren-elk-layout-java/bin/dediren-elk-layout-java"
HOME_DIR="${HOME:-}"

if [[ -z "$HOME_DIR" ]]; then
  HOME_DIR=$(getent passwd "$(id -u)" | cut -d: -f6 || true)
fi

if [[ ! -x "$APP" ]]; then
  echo "ELK helper is not built; run crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh" >&2
  exit 2
fi

if [[ -z "$HOME_DIR" || ! -s "$HOME_DIR/.sdkman/bin/sdkman-init.sh" ]]; then
  echo "SDKMAN is required to run the ELK helper" >&2
  exit 2
fi

export HOME="$HOME_DIR"
set +u
source "$HOME_DIR/.sdkman/bin/sdkman-init.sh"
cd "$PROJECT_DIR"
sdk env >/dev/null
set -u

exec "$APP"
