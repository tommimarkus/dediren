#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
TARGET=${DEDIREN_DIST_TARGET:-x86_64-unknown-linux-gnu}

cd "$ROOT"

if [[ "$TARGET" != "x86_64-unknown-linux-gnu" ]]; then
  echo "scripts/build-dist.sh supports only DEDIREN_DIST_TARGET=x86_64-unknown-linux-gnu" >&2
  echo "got: $TARGET" >&2
  exit 2
fi

export CARGO_TARGET_DIR="$ROOT/target"
unset CARGO_BUILD_TARGET
BIN_DIR="$CARGO_TARGET_DIR/$TARGET/release"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) ;;
  *)
    echo "scripts/build-dist.sh currently supports Linux x86_64 only" >&2
    exit 2
    ;;
esac

VERSION=$(awk -F '"' '/^version = / { print $2; exit }' "$ROOT/Cargo.toml")
if [[ -z "$VERSION" ]]; then
  echo "could not read workspace package version from Cargo.toml" >&2
  exit 2
fi

BUNDLE_NAME="dediren-agent-bundle-${VERSION}-${TARGET}"
DIST_DIR="$ROOT/dist"
BUNDLE_DIR="$DIST_DIR/$BUNDLE_NAME"
ARCHIVE="$DIST_DIR/${BUNDLE_NAME}.tar.gz"
BUILD_TIME=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

echo "checking first-party plugin manifest versions"
cargo test -p dediren-contracts --test schema_contracts first_party_plugin_manifest_versions_match_workspace_version --locked

echo "building Rust release binaries"
cargo build --release --locked --target "$TARGET" \
  -p dediren \
  -p dediren-plugin-generic-graph \
  -p dediren-plugin-elk-layout \
  -p dediren-plugin-svg-render \
  -p dediren-plugin-archimate-oef-export

echo "building ELK Java helper"
"$ROOT/crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh"

echo "assembling $BUNDLE_DIR"
rm -rf "$BUNDLE_DIR" "$ARCHIVE"
mkdir -p "$BUNDLE_DIR/bin" "$BUNDLE_DIR/plugins" "$BUNDLE_DIR/runtimes"

install -m 755 "$BIN_DIR/dediren" "$BUNDLE_DIR/bin/dediren"
install -m 755 "$BIN_DIR/dediren-plugin-generic-graph" "$BUNDLE_DIR/bin/dediren-plugin-generic-graph"
install -m 755 "$BIN_DIR/dediren-plugin-elk-layout" "$BUNDLE_DIR/bin/dediren-plugin-elk-layout"
install -m 755 "$BIN_DIR/dediren-plugin-svg-render" "$BUNDLE_DIR/bin/dediren-plugin-svg-render"
install -m 755 "$BIN_DIR/dediren-plugin-archimate-oef-export" "$BUNDLE_DIR/bin/dediren-plugin-archimate-oef-export"

cp "$ROOT"/fixtures/plugins/*.manifest.json "$BUNDLE_DIR/plugins/"
cp -R "$ROOT/schemas" "$BUNDLE_DIR/schemas"
cp -R "$ROOT/fixtures" "$BUNDLE_DIR/fixtures"
cp -R "$ROOT/crates/dediren-plugin-elk-layout/java/build/install/dediren-elk-layout-java" \
  "$BUNDLE_DIR/runtimes/elk-layout-java"

cat > "$BUNDLE_DIR/bundle.json" <<JSON
{
  "bundle_schema_version": "dediren-bundle.schema.v1",
  "product": "dediren",
  "version": "$VERSION",
  "target": "$TARGET",
  "built_at_utc": "$BUILD_TIME",
  "plugins": [
    { "id": "generic-graph", "version": "$VERSION" },
    { "id": "elk-layout", "version": "$VERSION" },
    { "id": "svg-render", "version": "$VERSION" },
    { "id": "archimate-oef", "version": "$VERSION" }
  ],
  "schemas_dir": "schemas",
  "fixtures_dir": "fixtures",
  "elk_helper": "runtimes/elk-layout-java/bin/dediren-elk-layout-java"
}
JSON

echo "creating $ARCHIVE"
tar --owner=0 --group=0 --numeric-owner -C "$DIST_DIR" -czf "$ARCHIVE" "$BUNDLE_NAME"

echo "$ARCHIVE"
