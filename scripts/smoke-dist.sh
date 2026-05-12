#!/usr/bin/env bash
set -euo pipefail

ARCHIVE=${1:-}
MIN_JAVA_MAJOR=21
if [[ -z "$ARCHIVE" ]]; then
  echo "usage: scripts/smoke-dist.sh dist/dediren-agent-bundle-0.1.2-x86_64-unknown-linux-gnu.tar.gz" >&2
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
JAVA_VERSION_OUTPUT=$(java -version 2>&1)
JAVA_VERSION=$(printf '%s\n' "$JAVA_VERSION_OUTPUT" | sed -n 's/.* version "\([^"]*\)".*/\1/p' | head -n 1)
if [[ "$JAVA_VERSION" =~ ^1\.([0-9]+) ]]; then
  JAVA_MAJOR=${BASH_REMATCH[1]}
elif [[ "$JAVA_VERSION" =~ ^([0-9]+) ]]; then
  JAVA_MAJOR=${BASH_REMATCH[1]}
else
  JAVA_MAJOR=
fi
if [[ ! "$JAVA_MAJOR" =~ ^[0-9]+$ || "$JAVA_MAJOR" -lt "$MIN_JAVA_MAJOR" ]]; then
  echo "Java $MIN_JAVA_MAJOR or newer is required on PATH for the bundled ELK helper" >&2
  exit 2
fi

TMP=$(mktemp -d)
cleanup() {
  rm -rf "$TMP"
}
trap cleanup EXIT

run_bundle() {
  env \
    -u DEDIREN_PLUGIN_DIRS \
    -u DEDIREN_PLUGIN_GENERIC_GRAPH \
    -u DEDIREN_PLUGIN_ELK_LAYOUT \
    -u DEDIREN_PLUGIN_SVG_RENDER \
    -u DEDIREN_ELK_COMMAND \
    -u DEDIREN_ELK_RESULT_FIXTURE \
    "$@"
}

tar -xzf "$ARCHIVE" -C "$TMP"
BUNDLE_DIR=$(find "$TMP" -maxdepth 1 -type d -name 'dediren-agent-bundle-*' | sort | tail -n 1)
if [[ -z "$BUNDLE_DIR" ]]; then
  echo "archive did not contain a dediren-agent-bundle directory" >&2
  exit 2
fi
if [[ -e "$BUNDLE_DIR/fixtures/plugins" ]]; then
  echo "archive must not include source fixture plugin manifests under fixtures/plugins" >&2
  exit 2
fi
cd "$BUNDLE_DIR"

BIN="$BUNDLE_DIR/bin/dediren"
REQUEST="$TMP/request.json"
LAYOUT="$TMP/layout.json"
RENDER="$TMP/render.json"

run_bundle "$BIN" --help >/dev/null
run_bundle "$BIN" project \
  --target layout-request \
  --plugin generic-graph \
  --view main \
  --input "$BUNDLE_DIR/fixtures/source/valid-pipeline-rich.json" \
  > "$REQUEST"
run_bundle "$BIN" layout \
  --plugin elk-layout \
  --input "$REQUEST" \
  > "$LAYOUT"
run_bundle "$BIN" render \
  --plugin svg-render \
  --policy "$BUNDLE_DIR/fixtures/render-policy/rich-svg.json" \
  --input "$LAYOUT" \
  > "$RENDER"

grep -q '"status":"ok"' "$RENDER"
grep -q '"artifact_kind":"svg"' "$RENDER"
grep -q '<svg' "$RENDER"

echo "distribution smoke test passed: $ARCHIVE"
