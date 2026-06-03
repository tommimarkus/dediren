#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../../../.." && pwd -P)
APP="$REPO_ROOT/modules/plugins/elk-layout/build/install/elk-layout/bin/elk-layout"

if [[ ! -x "$APP" ]]; then
  echo "ELK layout runtime is not built; run crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh" >&2
  exit 2
fi

exec "$APP" "$@"
