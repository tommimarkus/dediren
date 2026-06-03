# dediren

`dediren` is a contract-first diagram pipeline for agentic tools. It turns
semantic JSON into generated layout JSON, rendered SVG, ArchiMate 3.2 OEF XML,
or UML 2.5.1 XMI XML through explicit CLI commands and process-boundary
plugins.

The stable product surface is machine-readable:

- public JSON schemas in `schemas/`;
- source, policy, layout, render, and export fixtures in `fixtures/`;
- JSON command envelopes on stdout for success and failure;
- first-party plugin manifests and runtime capability probes;
- deterministic diagnostics that agents can inspect without scraping stderr.

For a token-efficient authoring guide, see `docs/agent-usage.md`.

## Requirements

- Java 21 or newer available as `java` on `PATH`.
- Build with the checked-in Maven Wrapper: `./mvnw`.
- `xmllint` on `PATH` for standards validation in ArchiMate OEF and UML/XMI
  export paths.
- `curl` on `PATH` only when export validation needs to populate a standards
  schema cache. Offline runs can provide schema files with
  `DEDIREN_OEF_SCHEMA_DIR` and `DEDIREN_XMI_SCHEMA_PATH`.

## Build And Test

```bash
./mvnw test
./mvnw -Psecurity-sca -DskipTests package org.owasp:dependency-check-maven:aggregate
./mvnw -Psbom org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom
./mvnw -pl tools/dist -am verify -Pthird-party-notices
./mvnw -pl tools/dist -am verify -Pdist-build
./mvnw -pl tools/dist -am verify -Pdist-smoke
```

Set `NVD_API_KEY` before running the `security-sca` profile so OWASP
Dependency-Check uses the authenticated NVD API path. CI and release workflows
read the same value from the GitHub Actions `NVD_API_KEY` secret.

The `dist-build` profile creates an agent-ready archive under `dist/`:

```text
dist/dediren-agent-bundle-0.18.1-x86_64-unknown-linux-gnu/
dist/dediren-agent-bundle-0.18.1-x86_64-unknown-linux-gnu.tar.gz
```

Set a supported target with `DEDIREN_DIST_TARGET` when needed:

```bash
DEDIREN_DIST_TARGET=x86_64-unknown-linux-gnu ./mvnw -pl tools/dist -am verify -Pdist-build
DEDIREN_DIST_TARGET=x86_64-unknown-linux-gnu ./mvnw -pl tools/dist -am verify -Pdist-smoke
```

Supported targets are:

- `x86_64-unknown-linux-gnu`
- `aarch64-unknown-linux-gnu`
- `aarch64-apple-darwin`

The Java archive contains launch scripts and jars, not a bundled JRE. The host
target must match the build host.

## Bundle Layout

```text
dediren-agent-bundle-0.18.1-x86_64-unknown-linux-gnu/
  bin/
    dediren
    dediren-plugin-generic-graph
    dediren-plugin-elk-layout
    dediren-plugin-svg-render
    dediren-plugin-archimate-oef-export
    dediren-plugin-uml-xmi-export
  lib/
  plugins/
  schemas/
  fixtures/
  docs/agent-usage.md
  LICENSE
  THIRD-PARTY-NOTICES.md
  bundle.json
```

First-party plugin manifests live under `plugins/`. Manifest executable names
resolve to bundled launchers under `bin/`. Project plugin directories and
`DEDIREN_PLUGIN_DIRS` remain explicit later lookup sources; plugins are not
discovered implicitly from `PATH`.

Bundle launchers set `DEDIREN_BUNDLE_ROOT` from their installation root so
commands can locate bundled `schemas/`, `plugins/`, and `bin/` regardless of
the caller's current working directory.

## First Run

From an unpacked bundle:

```bash
VERSION=0.18.1
TARGET=x86_64-unknown-linux-gnu
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}-${TARGET}

"$BUNDLE/bin/dediren" --version
"$BUNDLE/bin/dediren-plugin-generic-graph" capabilities
"$BUNDLE/bin/dediren-plugin-elk-layout" capabilities
"$BUNDLE/bin/dediren-plugin-svg-render" capabilities
"$BUNDLE/bin/dediren-plugin-archimate-oef-export" capabilities
"$BUNDLE/bin/dediren-plugin-uml-xmi-export" capabilities
```

Project, layout, validate, and render:

```bash
"$BUNDLE/bin/dediren" validate \
  --input "$BUNDLE/fixtures/source/valid-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view main \
  --input "$BUNDLE/fixtures/source/valid-basic.json" \
  > layout-request.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input layout-request.json \
  > layout-result.json

"$BUNDLE/bin/dediren" validate-layout \
  --input layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin svg-render \
  --policy "$BUNDLE/fixtures/render-policy/default-svg.json" \
  --input layout-result.json \
  > render-result.json

jq -r '.data.content' render-result.json > diagram.svg
```

Downstream commands accept either a full Dediren command envelope or the raw
`.data` artifact JSON.

## Pipeline

```text
validate -> project --target layout-request -> layout -> validate-layout -> render
validate -> project --target layout-request -> layout -> validate-layout -> export
```

Commands:

- `validate` checks source graph shape, id uniqueness, endpoint integrity, and
  authored-geometry rules. With `--plugin generic-graph --profile archimate`
  or `--profile uml`, it also runs plugin-owned semantic validation.
- `project` asks `generic-graph` to generate a layout request or render
  metadata for a named view.
- `layout` asks the official Java ELK plugin to generate node geometry and
  edge routes.
- `validate-layout` reports backend-neutral route and layout quality metrics.
- `render` asks `svg-render` to generate SVG in `.data.content`.
- `export` asks `archimate-oef` or `uml-xmi` to generate XML in
  `.data.content`.

## Source JSON Rules

Authored source graph JSON is semantic and plugin-typed. Do not put absolute
positions, sizes, colors, fonts, or SVG shape choices in source JSON. Layout
requests express layout intent. Layout results contain generated geometry.
Render policies own SVG styling.

The smallest useful source model is `fixtures/source/valid-basic.json`. Larger
models can use relative file fragments declared in the source model; fragments
use the same schema and are resolved relative to the entry file.

## Runtime Environment

Bundle launchers use `DEDIREN_BUNDLE_ROOT` for product-root discovery. Plugin
child processes launched by the core receive only environment variables listed
in their manifests. Important explicit variables:

- `DEDIREN_BUNDLE_ROOT`: explicit bundle or repository root for schemas,
  bundled plugin manifests, and bundled launchers. Packaged launchers set this
  automatically; override it only for custom launchers or tests.
- `DEDIREN_PLUGIN_DIRS`: additional manifest directories, separated with the
  platform path separator.
- `DEDIREN_PLUGIN_<PLUGIN_ID>`: per-plugin executable override, for example
  `DEDIREN_PLUGIN_SVG_RENDER`.
- `DEDIREN_OEF_SCHEMA_DIR`: local directory containing official OEF schema
  files.
- `DEDIREN_XMI_SCHEMA_PATH`: local XMI schema file.
- `DEDIREN_SCHEMA_CACHE_DIR`: cache directory for schema downloads.

Stderr is for human debugging only. Agents should make success or failure
decisions from stdout JSON.

## Verification Lanes

Use the narrowest useful lane first:

```bash
./mvnw -pl modules/contracts -am test
./mvnw -pl modules/core -am test
./mvnw -pl apps/cli -am test
./mvnw -pl modules/plugins/generic-graph -am test
./mvnw -pl modules/plugins/elk-layout -am test
./mvnw -pl modules/plugins/svg-render -am test
./mvnw -pl modules/plugins/archimate-oef-export -am test
./mvnw -pl modules/plugins/uml-xmi-export -am test
./mvnw test
./mvnw -pl tools/dist -am verify -Pdist-smoke
git diff --check
```

## Release

Release tags use `v<version>`. The product version source is
root `pom.xml`. First-party plugin manifests, source fixture
`required_plugins[].version` entries, bundle examples, and release workflow
checks must move with the product version.

GitHub Releases publish target-specific archives, `SHA256SUMS`, and CycloneDX
SBOMs. The release workflow generates GitHub artifact attestations for archives
and verifies those attestations before publishing. Verify a downloaded archive
with:

```bash
gh attestation verify dediren-agent-bundle-<version>-<target>.tar.gz \
  --repo tommimarkus/dediren
```
