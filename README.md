# dediren

`dediren` is a contract-first diagram pipeline for agentic tools. It turns
semantic JSON into generated layout JSON, rendered SVG, or ArchiMate 3.2 OEF
XML through explicit CLI commands and process-boundary plugins.

The product surface is intentionally machine-readable:

- public JSON schemas in `schemas/`;
- example inputs, policies, and expected artifacts in `fixtures/`;
- JSON command envelopes on stdout for success and failure;
- plugin manifests and runtime capability probes;
- deterministic diagnostics that agents can inspect without scraping stderr.

Authored source graph JSON is semantic and plugin-typed. It must not contain
absolute position or size data. Generated layout result JSON may contain
geometry, routes, metrics, warnings, and provenance.

## Install

### Linux Distribution Archive

For an agent-ready local installation on Linux x86_64, build the repo-local
distribution archive:

```bash
scripts/build-dist.sh
```

Build prerequisites:

- Linux x86_64 host.
- `flock` from util-linux, used by build scripts to serialize shared generated
  outputs.
- Rust and Cargo matching the workspace toolchain.
- SDKMAN with the Java and Gradle versions declared by
  `crates/dediren-plugin-elk-layout/java/.sdkmanrc`.

Runtime prerequisite:

- Java 21 or newer available as `java` on `PATH`.

For the current `0.2.0` version, the script creates:

```text
dist/dediren-agent-bundle-0.2.0-x86_64-unknown-linux-gnu/
dist/dediren-agent-bundle-0.2.0-x86_64-unknown-linux-gnu.tar.gz
```

Run the smoke test from a shell where `java -version` resolves to Java 21 or
newer:

```bash
scripts/smoke-dist.sh dist/dediren-agent-bundle-0.2.0-x86_64-unknown-linux-gnu.tar.gz
```

Concurrent `scripts/build-dist.sh` invocations serialize on a repo-local lock
under `.cache/locks/` because release binaries, the ELK helper build, and
`dist/` artifacts are shared generated outputs.

Unpack and run it anywhere:

```bash
mkdir -p /tmp/dediren-dist
tar -xzf dist/dediren-agent-bundle-0.2.0-x86_64-unknown-linux-gnu.tar.gz -C /tmp/dediren-dist
/tmp/dediren-dist/dediren-agent-bundle-0.2.0-x86_64-unknown-linux-gnu/bin/dediren --help
```

The archive includes first-party plugin manifests under `plugins/`, first-party
plugin binaries under `bin/`, schemas, fixtures, and the built ELK Java helper
under `runtimes/elk-layout-java/`. It does not bundle a JRE.

### Development Install

For development from a source checkout:

```bash
cargo install --path crates/dediren-cli
cargo install --path crates/dediren-plugin-generic-graph
cargo install --path crates/dediren-plugin-elk-layout
cargo install --path crates/dediren-plugin-svg-render
cargo install --path crates/dediren-plugin-archimate-oef-export
```

This installs Rust binaries only. It does not create the distribution archive
or bundle the ELK Java helper distribution.

## First Run

From a source checkout, this deterministic fixture-mode pipeline validates a
source graph, projects it to a layout request, uses a checked-in layout result,
and renders SVG:

```bash
dediren validate \
  --input fixtures/source/valid-basic.json

dediren project \
  --target layout-request \
  --plugin generic-graph \
  --view main \
  --input fixtures/source/valid-basic.json \
  > layout-request.json

DEDIREN_ELK_RESULT_FIXTURE=fixtures/layout-result/basic.json \
  dediren layout \
    --plugin elk-layout \
    --input layout-request.json \
    > layout-result.json

dediren validate-layout \
  --input layout-result.json

dediren render \
  --plugin svg-render \
  --policy fixtures/render-policy/default-svg.json \
  --input layout-result.json \
  > render-result.json
```

`render-result.json` is a command envelope. The SVG text is in
`.data.content`; there is no raw-output mode yet.

## Pipeline

The primary pipeline is JSON-first:

```text
validate -> project --target layout-request -> layout -> validate-layout -> render
validate -> project --target layout-request -> layout -> validate-layout -> export
```

Commands:

- `validate` checks source graph shape, id uniqueness, endpoint integrity, and
  authored-geometry rules.
- `project` asks a semantic/view plugin to produce a target artifact. The
  bundled `generic-graph` plugin supports `layout-request` and
  `render-metadata`. When projecting ArchiMate render metadata, it validates
  source node and relationship type strings and ArchiMate 3.2 relationship
  endpoint legality against the supported ArchiMate vocabulary.
- `layout` asks a layout plugin to generate a layout result. The bundled
  `elk-layout` plugin is a Rust adapter over the Java ELK helper.
- `validate-layout` reports backend-neutral layout quality metrics, including
  overlaps, connectors through unrelated nodes, invalid routes, route detours,
  close parallel route channels, group boundary issues, and backend warnings.
- `render` asks a render plugin to create a visual artifact. The bundled
  `svg-render` plugin returns SVG in a JSON command envelope.
- `export` asks an export plugin to create a non-visual artifact. The bundled
  `archimate-oef` plugin emits ArchiMate 3.2 OEF XML.

Most pipeline commands accept `--input <file>`. If `--input` is omitted, they
read JSON from stdin.

## Styling SVG

SVG styling is owned by render policy JSON. Source graph JSON and layout result
JSON stay presentation-free; they do not carry colors, fonts, shapes, or style
hints.

Useful policies:

- `fixtures/render-policy/default-svg.json` uses renderer defaults.
- `fixtures/render-policy/rich-svg.json` shows background, font, node, edge,
  group, edge-label, and per-layout-id overrides.
- `fixtures/render-policy/archimate-svg.json` applies ArchiMate-oriented
  notation from semantic render metadata.

For the full public policy surface, use
`schemas/svg-render-policy.schema.json`.

Rendered SVG includes stable semantic attributes such as
`data-dediren-node-decorator`, `data-dediren-icon-kind`,
`data-dediren-edge-marker-start`, and `data-dediren-edge-marker-end` so tests
can assert notation without depending on raw geometry.

## ArchiMate SVG And OEF

ArchiMate SVG notation uses two artifacts:

1. layout result JSON for generated geometry;
2. render metadata JSON for semantic selectors such as node and relationship
   types.

The render metadata artifact does not carry colors, fonts, shapes, or layout
data. Visual notation still comes from SVG render policy.

ArchiMate render and export paths reject unsupported ArchiMate element or
relationship type strings and reject ArchiMate 3.2 relationships whose source
and target element types are not allowed by the ArchiMate 3.2 relationship
rules. Use the ArchiMate/OEF element name `Node` for technology nodes; aliases
such as `TechnologyNode` are not accepted in ArchiMate metadata or export
source.

Create render metadata, then render with the ArchiMate policy:

```bash
dediren project \
  --target render-metadata \
  --plugin generic-graph \
  --view main \
  --input fixtures/source/valid-archimate-oef.json \
  > render-metadata.json

dediren render \
  --plugin svg-render \
  --policy fixtures/render-policy/archimate-svg.json \
  --metadata render-metadata.json \
  --input fixtures/layout-result/archimate-oef-basic.json \
  > archimate-render-result.json
```

Export ArchiMate 3.2 OEF XML from source semantics plus generated layout
geometry:

```bash
dediren export \
  --plugin archimate-oef \
  --policy fixtures/export-policy/default-oef.json \
  --source fixtures/source/valid-archimate-oef.json \
  --layout fixtures/layout-result/archimate-oef-basic.json \
  > oef-export-result.json
```

`oef-export-result.json` is a command envelope. The OEF XML text is in
`.data.content`. The exporter does not run external OEF XSD validation; import
the XML into Archi or run an explicit schema validator when tool-conformance
evidence is required.

## Plugins

Plugins are external executables that communicate through JSON stdin/stdout and
command envelopes. The bundled first-party plugins are:

| Plugin | Capability | Purpose |
| --- | --- | --- |
| `generic-graph` | `projection` | Projects source graph views into layout requests or render metadata. |
| `elk-layout` | `layout` | Runs the Java ELK helper and returns generated layout results. |
| `svg-render` | `render` | Renders SVG from layout result JSON and render policy JSON. |
| `archimate-oef` | `export` | Exports ArchiMate 3.2 OEF XML from source and layout data. |

The CLI discovers plugins explicitly:

1. bundled manifests under the installation root `plugins/` directory when
   running from a distribution archive;
2. repo fixture manifests in `fixtures/plugins` when running from the source
   checkout;
3. project plugin directories such as `.dediren/plugins`;
4. user-configured plugin directories from `DEDIREN_PLUGIN_DIRS`.

The CLI does not discover plugins implicitly from `PATH`. A manifest executable
can be an absolute path, a path relative to the manifest directory, or a binary
name resolved next to the `dediren` executable. Override a specific executable
with `DEDIREN_PLUGIN_<PLUGIN_ID>`, uppercased with dashes converted to
underscores, for example `DEDIREN_PLUGIN_SVG_RENDER`.

## ELK Runtime

The first-party `elk-layout` plugin is a Rust external-process adapter. Runtime
selection order is:

1. `DEDIREN_ELK_RESULT_FIXTURE`, for deterministic tests and repair loops;
2. `DEDIREN_ELK_COMMAND`, for an explicit external helper command;
3. bundled helper at
   `runtimes/elk-layout-java/bin/dediren-elk-layout-java` when running from a
   distribution archive.

Fixture mode takes precedence over `DEDIREN_ELK_COMMAND`.

To build and run the Java helper from a source checkout:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
cd crates/dediren-plugin-elk-layout/java
sdk env install
sdk env
cd ../../..
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
DEDIREN_ELK_COMMAND=crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh \
  dediren layout --plugin elk-layout --input fixtures/layout-request/basic.json
```

The Java helper reads a `layout-request.schema.v1` document from stdin and
returns a command envelope whose `.data` is a `layout-result.schema.v1`
document. The helper uses Eclipse ELK Layered (`org.eclipse.elk.layered`). The
Gradle build keeps the SDKMAN Java 25 toolchain for ELK layout work, emits Java
21-compatible bytecode for the distributed helper, and pins Maven dependencies
through dependency locking. Concurrent helper builds serialize on
`.cache/locks/elk-layout-java-build.lock`.

### ELK Test Lanes

Test names use explicit lane prefixes so failures and artifacts are easy to
classify:

- `fixture_*` tests use checked-in JSON fixtures. They are deterministic and
  are the right place for contract shape, ArchiMate node and relationship
  vocabulary, and relationship-rule coverage.
- `fake_*` tests exercise runtime boundary behavior with a test command instead
  of the Java helper.
- `real_elk_*` tests invoke the Java helper and are ignored by default. They are
  the preferred coverage for generated geometry, route quality, and SVG render
  evidence that depends on actual ELK output.

The default `cargo test --workspace --locked` lane runs fixture and fake tests
only. If Cargo prints `test ... ignored, run with --ignored after building the
ELK Java helper`, the test did not run; rerun it with `-- --ignored` after
building the helper.

Generated render artifacts are written under `.test-output/renders/`:

- `.test-output/renders/real-elk/` for real Java helper render tests.
- `.test-output/renders/fixture-pipeline/` for fixture-backed CLI pipeline
  tests, including deterministic ArchiMate node and relationship render
  notation.
- `.test-output/renders/svg-render-plugin/` for renderer policy and semantic
  fixture tests that do not prove ELK geometry, including the all-Archimate-node
  and all-Archimate-relationship visual sheets.

Generated SVGs are ignored by git. Inspect them locally instead of committing
them unless a tracked example fixture was deliberately requested.

## Contracts And Fixtures

Use the public schemas as the source of truth for JSON shape:

- `schemas/model.schema.json`
- `schemas/layout-request.schema.json`
- `schemas/layout-result.schema.json`
- `schemas/render-metadata.schema.json`
- `schemas/svg-render-policy.schema.json`
- `schemas/render-result.schema.json`
- `schemas/oef-export-policy.schema.json`
- `schemas/export-result.schema.json`
- `schemas/plugin-manifest.schema.json`
- `schemas/runtime-capability.schema.json`
- `schemas/envelope.schema.json`

The `fixtures/` tree provides small examples for source documents, layout
requests/results, render policies, export policies, plugin manifests, and
expected artifacts.

## Error Handling

All command results are JSON command envelopes on stdout. If a plugin returns a
valid error envelope, the CLI preserves that envelope and exits non-zero. If the
runtime boundary fails before a plugin can return a valid envelope, the CLI
normalizes the failure into diagnostics such as:

- `DEDIREN_PLUGIN_MISSING_EXECUTABLE`
- `DEDIREN_PLUGIN_TIMEOUT`
- `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`
- `DEDIREN_PLUGIN_OUTPUT_INVALID_JSON`
- `DEDIREN_ELK_RUNTIME_UNAVAILABLE`
- `DEDIREN_ELK_JAVA_UNAVAILABLE`
- `DEDIREN_ELK_JAVA_UNSUPPORTED`
- `DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED`
- `DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED`
- `DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED`

Agents should read stdout JSON for success and failure decisions. `stderr` is
reserved for human debugging.

## Development Checks

Common verification commands:

```bash
cargo fmt --all -- --check
cargo test --workspace --locked
git diff --check
```

Distribution checks from a shell where `java -version` resolves to Java 21 or
newer:

```bash
scripts/build-dist.sh
scripts/smoke-dist.sh dist/dediren-agent-bundle-0.2.0-x86_64-unknown-linux-gnu.tar.gz
```

Focused checks:

```bash
cargo test -p dediren-contracts --test schema_contracts
cargo test -p dediren-core --test plugin_runtime
cargo test -p dediren --test plugin_compat
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin
```

Real ELK checks from a source checkout, after building the helper:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test --locked -p dediren-plugin-elk-layout --test elk_layout_plugin real_elk_plugin_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_layout real_elk_layout_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_layout real_elk_layout_validates_grouped_cross_group_route -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test real_elk_render -- --ignored --test-threads=1
```

## Security

See `SECURITY.md` for supported versions and vulnerability reporting.
