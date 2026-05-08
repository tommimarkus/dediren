# dediren

`dediren` is a structured-data-first diagram rendering CLI for agentic tools.

The v1 pipeline is JSON-first:

```text
validate -> project --target layout-request -> layout -> validate-layout -> render
validate -> project --target layout-request -> layout -> validate-layout -> export
```

Authored source graph JSON is semantic and plugin-typed. It must not contain
absolute position or size data. Generated layout result JSON may contain
geometry with source and projection provenance.

## Commands

```bash
dediren validate --input fixtures/source/valid-basic.json
dediren project --target layout-request --plugin generic-graph --view main --input fixtures/source/valid-basic.json
DEDIREN_ELK_RESULT_FIXTURE=fixtures/layout-result/basic.json dediren layout --plugin elk-layout --input fixtures/layout-request/basic.json
dediren validate-layout --input fixtures/layout-result/basic.json
dediren render --plugin svg-render --policy fixtures/render-policy/default-svg.json --input fixtures/layout-result/basic.json
dediren export --plugin archimate-oef --policy fixtures/export-policy/default-oef.json --source fixtures/source/valid-archimate-oef.json --layout fixtures/layout-result/archimate-oef-basic.json
```

`render` returns a JSON command envelope by default. The SVG text is in
`.data.content`; this first slice does not expose a raw-output mode.

`export` returns a JSON command envelope by default. The ArchiMate OEF XML text
is in `.data.content`; this slice does not expose a raw-output mode.

## Local Install

```bash
cargo install --path crates/dediren-cli
```

## Plugin Lookup

The CLI discovers plugins explicitly:

1. bundled first-party plugins from the installed workspace;
2. project plugin directories such as `.dediren/plugins`;
3. user-configured plugin directories.

The CLI does not discover plugins implicitly from `PATH`.

The bundled `archimate-oef` export plugin emits ArchiMate 3.2 OEF XML from
source graph semantics plus generated layout result geometry. It does not run
external OEF XSD validation; import the XML into Archi or run an explicit schema
validator when tool-conformance evidence is required.

## Plugin Runtime Errors

Plugin failures are reported as JSON command envelopes. If a plugin returns a
valid error envelope, the CLI preserves that envelope and exits non-zero. If the
runtime boundary fails before a plugin can return a valid envelope, the CLI
normalizes the failure into a `CommandEnvelope` diagnostic such as
`DEDIREN_PLUGIN_MISSING_EXECUTABLE`, `DEDIREN_PLUGIN_TIMEOUT`,
`DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`, or
`DEDIREN_PLUGIN_OUTPUT_INVALID_JSON`.

Agents should read stdout JSON for success and failure decisions. `stderr` is
reserved for human debugging and is not required for repair loops.

## ELK Runtime

The bundled `elk-layout` plugin is a Rust external-process adapter. The real ELK
layered runtime is a Java helper under
`crates/dediren-plugin-elk-layout/java` and is built with SDKMAN-managed Java and
Gradle.

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

The runtime wrapper requires SDKMAN. When plugin execution starts with a minimal
environment that clears `HOME`, it resolves `HOME` through the current Linux
user account before loading SDKMAN.

The Java helper reads a `layout-request.schema.v1` document from stdin and
returns a JSON command envelope whose `.data` is a `layout-result.schema.v1`
document. The helper uses Eclipse ELK Layered (`org.eclipse.elk.layered`) and
the Gradle build pins Maven dependencies through dependency locking.

Tests may still use `DEDIREN_ELK_RESULT_FIXTURE` to exercise the Rust plugin
contract without Java. Fixture mode takes precedence over
`DEDIREN_ELK_COMMAND` for deterministic compatibility tests.
Real Java helper integration tests are ignored by default and require building
the helper before running ignored tests.
