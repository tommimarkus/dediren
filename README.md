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
dediren render --plugin svg-render --policy fixtures/render-policy/rich-svg.json --input fixtures/layout-result/basic.json
dediren project --target render-metadata --plugin generic-graph --view main --input fixtures/source/valid-archimate-oef.json > render-metadata.json
dediren render --plugin svg-render --policy fixtures/render-policy/archimate-svg.json --metadata render-metadata.json --input fixtures/layout-result/archimate-oef-basic.json
dediren export --plugin archimate-oef --policy fixtures/export-policy/default-oef.json --source fixtures/source/valid-archimate-oef.json --layout fixtures/layout-result/archimate-oef-basic.json
```

`render` returns a JSON command envelope by default. The SVG text is in
`.data.content`; this first slice does not expose a raw-output mode.

`export` returns a JSON command envelope by default. The ArchiMate OEF XML text
is in `.data.content`; this slice does not expose a raw-output mode.

## SVG Styling

SVG styling is owned by the render policy. Source graph JSON and layout result
JSON stay presentation-free; they do not carry colors, fonts, shapes, or style
hints.

`fixtures/render-policy/default-svg.json` uses renderer defaults.
`fixtures/render-policy/rich-svg.json` shows optional styling for background,
font, nodes, edges, groups, edge-label placement, and per-layout-id overrides.
Per-id override keys match ids in the layout result, for example `api` or
`client-calls-api`.

ArchiMate styling uses the same SVG render policy system as the default and rich
styles. The separate render metadata artifact carries only semantic selectors
such as node and relationship types; it does not carry colors, fonts, shapes, or
layout data. ArchiMate-oriented SVG notation is still configured through the
SVG render policy. The policy may attach decorators and relationship notation to
those exact types:

```json
{
  "semantic_profile": "archimate",
  "style": {
    "node_type_overrides": {
      "BusinessActor": {
        "fill": "#fff2cc",
        "stroke": "#d6b656",
        "decorator": "archimate_business_actor"
      },
      "ApplicationComponent": {
        "fill": "#e0f2fe",
        "stroke": "#0369a1",
        "decorator": "archimate_application_component"
      },
      "DataObject": {
        "fill": "#e0f2fe",
        "stroke": "#0369a1",
        "decorator": "archimate_data_object"
      },
      "TechnologyNode": {
        "fill": "#d5e8d4",
        "stroke": "#4d7c0f",
        "decorator": "archimate_technology_node"
      }
    },
    "edge_type_overrides": {
      "Realization": {
        "line_style": "dashed",
        "marker_end": "hollow_triangle"
      }
    }
  }
}
```

By default, horizontal edge labels are placed near the start of the selected
horizontal segment. The renderer chooses above or below from the route shape:
segments that bend down place the label below, while straight segments and
segments that bend up place it above, with collision fallback when needed.
Vertical edge labels are centered on the route and placed to the left side.
Edge policy can override horizontal position (`near_start`, `center`,
`near_end`), horizontal side (`auto`, `above`, `below`), vertical position
(`near_start`, `center`, `near_end`), and vertical side (`left`, `right`).

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
