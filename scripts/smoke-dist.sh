#!/usr/bin/env bash
set -euo pipefail

ARCHIVE=${1:-}
if [[ -z "$ARCHIVE" ]]; then
  echo "usage: scripts/smoke-dist.sh dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu.tar.gz" >&2
  exit 2
fi
if [[ ! -f "$ARCHIVE" ]]; then
  echo "archive not found: $ARCHIVE" >&2
  exit 2
fi
if ! command -v java >/dev/null 2>&1; then
  echo "java is required on PATH for the distribution smoke test" >&2
  exit 2
fi

TMP=$(mktemp -d)
cleanup() {
  rm -rf "$TMP"
}
trap cleanup EXIT

tar -xzf "$ARCHIVE" -C "$TMP"
BUNDLE_DIR=$(find "$TMP" -maxdepth 1 -type d -name 'dediren-agent-bundle-*' | sort | tail -n 1)
if [[ -z "$BUNDLE_DIR" ]]; then
  echo "archive did not contain a dediren-agent-bundle directory" >&2
  exit 2
fi

BIN="$BUNDLE_DIR/bin/dediren"
REQUEST="$TMP/request.json"
LAYOUT="$TMP/layout.json"
RENDER="$TMP/render.json"

"$BIN" --help >/dev/null
"$BIN" project \
  --target layout-request \
  --plugin generic-graph \
  --view main \
  --input "$BUNDLE_DIR/fixtures/source/valid-pipeline-rich.json" \
  > "$REQUEST"
"$BIN" layout \
  --plugin elk-layout \
  --input "$REQUEST" \
  > "$LAYOUT"
"$BIN" render \
  --plugin svg-render \
  --policy "$BUNDLE_DIR/fixtures/render-policy/rich-svg.json" \
  --input "$LAYOUT" \
  > "$RENDER"

grep -q '"status":"ok"' "$RENDER"
grep -q '"artifact_kind":"svg"' "$RENDER"
grep -q '<svg' "$RENDER"

echo "distribution smoke test passed: $ARCHIVE"
