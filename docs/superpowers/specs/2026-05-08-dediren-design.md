# Dediren Design

Date: 2026-05-08

## Purpose

`dediren` is a structured-data-first diagram rendering CLI for agentic tools.
It treats machine-readable contracts as the product surface: JSON schemas, CLI
commands, command envelopes, diagnostics, plugin manifests, plugin capability
probes, and fixture-driven compatibility tests.

The tool must let agents author semantic diagram data without absolute
positions, validate that data, project it into a backend-neutral layout request,
run a layout backend, validate layout quality, and render a deterministic visual
artifact. The initial visual artifact is SVG.

## Primary Decisions

- Implementation language: Rust.
- Repository license: MIT.
- Repository shape: Cargo workspace.
- Native data format: JSON only.
- Native source model: plugin-typed semantic graph.
- Authored source geometry: forbidden. Source JSON must not contain absolute
  position or size data.
- Generated geometry: allowed in derived layout result JSON with provenance.
- CLI posture: pipeline commands, JSON output by default, stdin for pipeline
  commands.
- Plugin posture: external executable plugins over JSON stdin/stdout.
- First organizing approach: contract-first vertical slice.

## Scope

The first implementation slice covers:

1. Validate source graph JSON.
2. Project a selected plugin-owned view into a public layout request JSON.
3. Run the bundled ELK layout plugin to produce layout result JSON.
4. Validate the layout result with backend-neutral quality metrics and policy.
5. Run the bundled SVG render plugin to produce SVG.

The initial pipeline is:

```text
validate -> project --target layout-request -> layout -> validate-layout -> render
```

The first slice deliberately does not implement ArchiMate OEF export. It only
defines the export capability shape in the plugin protocol so OEF can be added
later without redesigning the plugin system.

## Core Responsibilities

The core owns stable orchestration and contract enforcement:

- JSON schema validation.
- Command envelope handling.
- Deterministic diagnostics and exit codes.
- CLI parsing for stable pipeline commands.
- Explicit plugin discovery from configured directories and bundled plugins.
- Static plugin manifest loading.
- Runtime plugin capability probing.
- Plugin process execution controls: timeout, working directory handling, and
  environment allowlist.
- Backend-neutral layout result validation.
- Layout quality policy loading and evaluation.

The core does not own:

- Semantic element or relationship vocabulary.
- Containment semantics.
- View semantics.
- ELK-specific layout interpretation.
- SVG rendering logic.
- ArchiMate OEF export semantics.
- Concrete visual styling.

## Source Model Contract

The source document is a plugin-typed semantic graph. Nodes and relationships
have stable human-readable ids and type strings. The core validates only:

- document shape and contract version;
- id uniqueness;
- endpoint/reference integrity;
- namespaced property object shape;
- plugin declaration shape;
- command and schema compatibility metadata.

The core does not validate whether a type string is meaningful. Semantic
validation belongs to selected or document-declared plugins.

Plugin-specific data lives in namespaced areas:

- node and relationship metadata use a namespaced `properties` object;
- plugin-owned document sections live under `plugins`;
- the first generic graph plugin stores named views under its own plugin
  section.

Source documents may declare required or compatible plugins. The CLI can also
select or override plugins for exploratory runs.

## Validation Modes

Validation supports two modes:

- draft mode for agent repair loops;
- strict mode for CI, release, and exported artifacts.

Draft mode may accept forward-looking or plugin-owned data that strict mode
rejects, but it still returns structured diagnostics. Strict mode rejects unknown
or unsupported fields unless they live under an explicit plugin-owned extension
area and pass the selected plugin validation hooks.

## Views And Projection

Views are not core structure. They are plugin-defined projections over the graph.

The first bundled semantic/view plugin is `generic-graph`. It supports named
views with explicit node and relationship selections stored under its own
top-level plugin section. This proves the plugin-owned view model without
making views a core concept.

The `project` command invokes a semantic/view plugin to produce a target
artifact. The first target is:

```text
project --target layout-request
```

The layout request is a public stable contract, not hidden glue. Agents can
inspect, validate, store, diff, and repair it independently.

## Layout Request Contract

The first layout request schema supports:

- nodes;
- edges;
- groups;
- labels;
- layout constraints.

Groups may be visual-only or semantic-backed. Semantic-backed groups carry
provenance linking back to the source graph and projection step.

Layout constraints are semantic or intent-oriented, not absolute geometry. They
can express concerns such as grouping, ordering, rank, flow, importance, and
keep-together behavior. They must not author concrete `x`, `y`, `w`, or `h`
values in source graph data.

## Layout Result Contract

The layout result is derived data. It may contain generated positions, sizes,
routes, metrics, backend warnings, and provenance.

Every generated geometry item must reference source ids and projection ids
where applicable. This traceability is required for agent repair loops,
diagnostics, later export, and visual debugging.

The ELK layout plugin emits backend warnings with the layout result. Backend
warnings are not the same as backend-neutral quality validation.

## Layout Quality Validation

`validate-layout` combines core generic validation with optional plugin-specific
checks.

Core layout validation computes backend-neutral quality metrics such as:

- node overlap;
- connector-through-node;
- disconnected or invalid routes;
- containment or group boundary issues where represented in the layout request;
- warning severity summaries;
- policy threshold results.

Strict validation is policy-based. Built-in named policies such as `draft`,
`ci`, and `release` provide defaults. External JSON policy files can override or
extend thresholds for reproducible project-specific gates.

## Rendering

Rendering is a plugin capability, not a core implementation detail.

The first bundled render plugin is an SVG renderer. It consumes layout result
JSON and a schema-validated SVG render policy. V1 SVG rendering is intentionally
minimal:

- fixed baseline theme;
- page size;
- margins.

Visual styling lives in render policy/config only. Source graph and layout
request data remain semantic and layout-oriented. They do not carry colors,
fonts, shapes, or other concrete presentation choices in v1.

## Export Capability

Render and export share plugin protocol mechanics but are distinct capability
kinds with distinct schemas.

V1 includes export capability shape in static manifests and runtime capability
responses. It does not include an OEF exporter implementation. The intended
first export plugin after the vertical slice is ArchiMate OEF.

## Plugin Runtime Contract

Plugins are external executables. Each plugin has:

- a static manifest for indexing, installation checks, documentation, and agent
  inspection;
- a runtime capability command that reports actual executable support, schema
  versions, helper availability, runtime requirements, and feature support.

Plugin discovery is explicit:

1. bundled first-party plugins;
2. configured project plugin directories, for example `.dediren/plugins`;
3. configured user plugin directories.

There is no implicit `PATH` discovery in v1.

Plugin execution uses:

- JSON stdin/stdout;
- deterministic command envelopes;
- timeout controls;
- controlled working directory handling;
- environment allowlist;
- structured diagnostics for missing executables, timeouts, invalid JSON,
  schema mismatches, unsupported capabilities, and missing runtime dependencies.

## First-Party Plugins

V1 bundles these first-party plugins:

- `generic-graph`: semantic validation and layout-request projection.
- `elk-layout`: layout request to layout result. It calls a pinned external ELK
  executable or JAR and reports runtime detection failures as structured
  diagnostics.
- `svg-render`: layout result to SVG using the minimal SVG render policy.

First-party plugins are real executable plugins even though they ship with the
workspace. Plugins may share only protocol/schema Rust types with the core.
They must not depend on core implementation logic.

## Command Output And Diagnostics

JSON is the default output. Human-readable text summaries can be added behind an
explicit `--format text` option.

Pipeline commands read JSON from stdin unless `--input` is provided. Discovery
and administration commands do not read stdin by default.

Every command returns a structured envelope containing:

- status;
- data or artifact metadata when applicable;
- diagnostics;
- contract versions;
- plugin and runtime provenance where applicable.

`stderr` is for human progress/debug text only. Agents must not need `stderr` to
understand success or failure.

Commands never mutate source documents. They emit diagnostics or derived
artifacts only.

## Contract Versioning

Contracts are versioned separately so compatibility failures can be precise.
Initial public contract families include:

- source model schema;
- plugin manifest schema;
- runtime capability schema;
- command envelope and diagnostics schema;
- projection request/result schemas;
- layout request schema;
- layout result schema;
- layout quality policy schema;
- render request/result schemas;
- SVG render policy schema;
- export capability/request schemas.

## Repository Structure

The initial repository should be a Cargo workspace with this approximate shape:

```text
Cargo.toml
LICENSE
README.md
schemas/
crates/
  dediren-core/
  dediren-cli/
  dediren-contracts/
  dediren-plugin-generic-graph/
  dediren-plugin-elk-layout/
  dediren-plugin-svg-render/
fixtures/
tests/
docs/
```

`dediren-contracts` contains shared Rust structs for public protocol and schema
contracts only. The core and first-party plugins can depend on it. First-party
plugins must not depend on `dediren-core`.

Schemas are public versioned JSON Schema files under `schemas/`. Rust types are
manual in v1 and verified with schema conformance tests. Schema-to-Rust codegen
is intentionally deferred.

## Testing Strategy

The initial test suite should prioritize contract and plugin behavior:

- JSON Schema tests for valid and invalid fixtures.
- Source graph validation fixtures.
- Layout request and layout result fixtures.
- Render policy and render result fixtures.
- Command envelope and diagnostics fixtures.
- Executable plugin compatibility tests for manifests, capabilities, envelopes,
  exit codes, and schema conformance.
- CLI pipeline tests proving stdin/stdout composition.
- Plugin failure tests for missing executable, timeout, invalid JSON, schema
  mismatch, unsupported capability, and missing ELK runtime.
- Layout quality metric tests for overlap, connector-through-node, route issues,
  group/containment boundary issues, and policy thresholds.
- SVG render fixture tests for deterministic output.

## Bootstrap Requirements

The first repository bootstrap should include:

- MIT `LICENSE`;
- workspace `Cargo.toml` and package metadata;
- README with the contract summary and v1 pipeline;
- documented local `cargo install` path;
- documented plugin lookup paths;
- note that release binaries are intended later;
- initial schema and fixture directories.

## Rejected Approaches

The design rejects a Rust-library-first approach because it would make schemas
and plugin protocol follow implementation details. The public contract must lead.

The design rejects a core-owned semantic vocabulary because it would make the
native model drift toward ArchiMate or another domain too early. Semantics belong
to plugins.

The design rejects in-process plugins for v1 because the tool is intended for
agentic composition, language-neutral plugin implementation, and inspectable
process boundaries.

The design rejects implicit `PATH` plugin discovery because surprise executable
loading is too loose for an agent-facing tool.

The design rejects source-level style hints and absolute geometry because the
source model must stay semantic, validatable, portable, and layout-backend
independent.

## Deferred Decisions

- ArchiMate OEF export implementation.
- PNG rendering.
- Rich SVG styling policy.
- Render plugins beyond SVG.
- Schema-to-Rust type generation.
- Third-party plugin publishing and signing.
- OS-level sandboxing beyond basic process controls.
- Release binary automation.
- Non-ELK layout backends.

## Validation Layers For This Design

- Static: repository inspection showed no existing source files to preserve.
- Human: decisions in this document come from the brainstorming choices made on
  2026-05-08.
- Memory-derived: prior layout work informed the split between backend result
  validity, layout quality validation, and export-readiness validation.

## Limits

This design is not an implementation plan. It defines product boundaries,
contracts, responsibilities, and the first vertical slice. The next step is a
separate implementation plan after review approval.
