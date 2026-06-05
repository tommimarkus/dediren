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
./mvnw -pl dist-tool -am verify -Pthird-party-notices
./mvnw -pl dist-tool -am verify -Pdist-build
./mvnw -pl dist-tool -am verify -Pdist-smoke
```

Maven artifacts and wrapper state are repo-local under `.cache/maven` for
sandbox-friendly builds. Set `NVD_API_KEY` before running the `security-sca`
profile so OWASP Dependency-Check uses the authenticated NVD API path. CI and
release workflows read the same value from the GitHub Actions `NVD_API_KEY`
secret. Vulnerability data is stored under `.cache/dependency-check`; CI and
release workflows cache that path separately from Maven artifacts.

The `dist-build` profile creates an agent-ready archive under `dist/`:

```text
dist/dediren-agent-bundle-0.23.0/
dist/dediren-agent-bundle-0.23.0.tar.gz
```

The Java archive contains launch scripts and jars, not a bundled JRE. Java 21
or newer must be available on `PATH` at runtime. The archive is
platform-neutral and is not tied to CPU architecture.

## Bundle Layout

```text
dediren-agent-bundle-0.23.0/
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
VERSION=0.23.0
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}

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

## UML Sequence Workflow

UML source uses `plugins.generic-graph.semantic_profile: "uml"`. Supported
view kinds are `generic`, `archimate`, `uml-class`, `uml-data`,
`uml-activity`, `uml-sequence`, and `uml-state-machine`. Dediren supports the
`uml-sequence` MVP plus combined fragments with `alt`, `opt`, `loop`, and
`par` interaction operators. The sequence MVP source fixture is
`fixtures/source/valid-uml-sequence-basic.json`: it declares an `Interaction`
named `Place Order`, `Lifeline` nodes, and ordered `Message` relationships in
`properties.uml.sequence` with `message_sort` values.

Combined fragment authoring uses `CombinedFragment` and `InteractionOperand`
nodes with fragment membership and guards under `properties.uml`. Operand
`fragments` entries must follow the referenced messages' `sequence` order,
message sequence values must be unique within an interaction, and combined
fragments must not leave standalone messages inside their owned sequence span.
These rules keep rendered SVG and exported XMI in the same interaction order. Use
`fixtures/source/valid-uml-sequence-fragments.json` with
`--view sequence-fragments-view` in the `project` commands to run the same
pipeline against `alt`, `opt`, `loop`, and `par` examples.

Validate UML semantics, project layout and render metadata, lay out with ELK,
render SVG, and export UML/XMI:

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view sequence-view \
  --input "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json" \
  > sequence-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view sequence-view \
  --input "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json" \
  > sequence-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input sequence-layout-request.json \
  > sequence-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin svg-render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata sequence-render-metadata.json \
  --input sequence-layout-result.json \
  > sequence-render-result.json

jq -r '.data.content' sequence-render-result.json > sequence.svg

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json" \
  --layout sequence-layout-result.json \
  > sequence-xmi-result.json

jq -r '.data.content' sequence-xmi-result.json > sequence.xmi
```

Use generated render metadata for UML sequence SVG so the renderer receives
lifeline, interaction, message order, message sort, and combined-fragment
semantics. The UML sequence vocabulary is `Interaction`, `Lifeline`, `Message`,
`ExecutionSpecification`, `Gate`, `DestructionOccurrenceSpecification`,
`CombinedFragment`, and `InteractionOperand`. Supported message sorts are
`synchCall`, `asynchCall`, `asynchSignal`, `reply`, `createMessage`, and
`deleteMessage`. `InteractionUse`, `GeneralOrdering`, `ignore`, `consider`,
UMLDI, use cases, and deployment diagrams are not yet supported.

## UML State Machine Workflow

Use `fixtures/source/valid-uml-state-machine-basic.json` for the state-machine
MVP shape: one `StateMachine`, one `Region`, state vertices, pseudostates, and
`Transition` relationships. In `plugins.generic-graph.views[].groups`,
`StateMachine` and `Region` are represented in the view as semantic-backed
groups via `semantic_source_id`; states and transitions remain semantic
nodes and relationships.

Validate UML semantics, project layout and render metadata, lay out with ELK,
render SVG, and export UML/XMI:

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view state-machine-view \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  > state-machine-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view state-machine-view \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  > state-machine-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input state-machine-layout-request.json \
  > state-machine-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin svg-render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata state-machine-render-metadata.json \
  --input state-machine-layout-result.json \
  > state-machine-render-result.json

jq -r '.data.content' state-machine-render-result.json > state-machine.svg

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  --layout state-machine-layout-result.json \
  > state-machine-xmi-result.json

jq -r '.data.content' state-machine-xmi-result.json > state-machine.xmi
```

The UML state-machine vocabulary is `StateMachine`, `Region`, `State`,
`FinalState`, `Pseudostate`, and `Transition`. Supported pseudostate kinds are
`initial`, `deepHistory`, `shallowHistory`, `join`, `fork`, `junction`,
`choice`, `entryPoint`, `exitPoint`, and `terminate`. Supported transition
kinds are `internal`, `local`, and `external`.

This slice intentionally defers `ConnectionPointReference`,
`ProtocolStateMachine`, `ProtocolTransition`, submachine states, orthogonal
multi-region internals, trigger event metaclasses, effects as behavior nodes,
UMLDI, use cases, and deployment diagrams.

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
  edge routes. `layout_preferences.mode` may be `flow` for ELK Layered
  flow diagrams or `packed` for edge-less node/group maps using ELK Rectangle
  Packing; omit it or use `auto` for the default flow behavior.
- `validate-layout` reports backend-neutral route and layout quality metrics.
- `render` asks `svg-render` to generate SVG in `.data.content`.
- `export` asks `archimate-oef` or `uml-xmi` to generate XML in
  `.data.content`.

## Source JSON Rules

Authored source graph JSON is semantic and plugin-typed. Do not put absolute
positions, sizes, colors, fonts, or SVG shape choices in source JSON. Layout
requests express layout intent. Layout results contain generated geometry.
Render policies own SVG styling. Edge labels default to outlined text; set
`style.edge.label_presentation` to `background` when a filled label backing is
preferred.

Use `layout_preferences.mode: "packed"` only for edge-less views such as
grouped ArchiMate maps or inventories. Relationship-heavy diagrams should keep
the default `auto` mode or set `mode: "flow"` so ELK Layered owns both
placement and routing.

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
./mvnw -pl contracts -am test
./mvnw -pl core -am test
./mvnw -pl cli -am test
./mvnw -pl plugins/generic-graph -am test
./mvnw -pl plugins/elk-layout -am test
./mvnw -pl plugins/svg-render -am test
./mvnw -pl plugins/archimate-oef-export -am test
./mvnw -pl plugins/uml-xmi-export -am test
./mvnw test
./mvnw -pl dist-tool -am verify -Pdist-smoke
git diff --check
```

## Release

Release tags use `v<version>`. The product version source is
root `pom.xml`. First-party plugin manifests, source fixture
`required_plugins[].version` entries, bundle examples, and release workflow
checks must move with the product version.

Use SemVer intent while the project is pre-1.0:

- Patch: compatible fixes, documentation, tests, and internal refactors.
- Minor: additive compatible public surface changes or runtime migration
  cutovers.
- Major: backwards-incompatible public product or plugin contract changes.

Maven can calculate the next version for the POMs, which avoids manually
typing the next numeric version. For a patch bump:

```bash
./mvnw build-helper:parse-version versions:set \
  -DnewVersion='${parsedVersion.majorVersion}.${parsedVersion.minorVersion}.${parsedVersion.nextIncrementalVersion}' \
  -DprocessAllModules=true \
  -DgenerateBackupPoms=false
```

For a minor bump, use
`-DnewVersion='${parsedVersion.majorVersion}.${parsedVersion.nextMinorVersion}.0'`.
For a major bump, use
`-DnewVersion='${parsedVersion.nextMajorVersion}.0.0'`.

After Maven updates the POMs, update the remaining checked-in product version
surfaces:

| Surface | Files |
| --- | --- |
| Product version source | `pom.xml` |
| Module parent versions | `cli/pom.xml`, `*/pom.xml`, `plugins/**/pom.xml`, `test-support/pom.xml`, `testbeds/**/pom.xml`, `dist-tool/pom.xml` |
| First-party plugin manifests | `fixtures/plugins/*.manifest.json` |
| Source fixture plugin requirements | `fixtures/source/*.json` `required_plugins[].version` entries |
| Bundle and required-plugin examples | `README.md`, `docs/agent-usage.md` |
| CLI version assertion | `cli/src/test/java/dev/dediren/cli/MainTest.java` |
| Manifest round-trip assertion | `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java` |
| Export/plugin fixture assertions | `plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`, `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java` |
| Distribution launcher assertion | `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java` |
| Release tag check | `.github/workflows/release.yml` validates `v<version>` against root `pom.xml`; update only if the version source changes |

Before committing a version bump, run a stale-version search over the active
version surfaces:

```bash
rg "<old-version>" pom.xml README.md docs/agent-usage.md fixtures/plugins fixtures/source
```

Then commit the content change and version bump together, and create the
matching annotated tag on that commit:

```bash
git tag -a v<version> -m "Release <version>"
```

GitHub Releases publish one Java archive, `SHA256SUMS`, and CycloneDX SBOMs.
The release workflow generates GitHub artifact attestations for archives and
verifies those attestations before publishing. Verify a downloaded archive
with:

```bash
gh attestation verify dediren-agent-bundle-<version>.tar.gz \
  --repo tommimarkus/dediren
```
