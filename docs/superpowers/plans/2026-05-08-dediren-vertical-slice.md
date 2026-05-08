# Dediren Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first contract-first `dediren` vertical slice: validate JSON source graphs, project them to layout requests, run an external-process ELK layout plugin, validate layout quality, and render SVG through a render plugin.

**Architecture:** Use a Rust Cargo workspace with a small contracts crate shared by the CLI, core, and first-party executable plugins. The core owns schema/envelope/plugin orchestration and backend-neutral validation; semantic projection, ELK layout, and SVG rendering stay in executable plugins. Public JSON schemas and fixtures lead the implementation.

**Tech Stack:** Rust 2021, Cargo workspace, `serde`, `serde_json`, `clap`, `thiserror`, `anyhow`, `schemars`, `jsonschema`, `assert_cmd`, `assert_fs`, `predicates`, `tempfile`.

---

## File Structure

- `Cargo.toml`: workspace members, shared dependency versions, workspace package metadata.
- Root binary aliases in `Cargo.toml`: expose workspace binaries to root integration tests.
- `LICENSE`: MIT license.
- `README.md`: user-facing contract summary, commands, local install, plugin lookup paths.
- `schemas/*.schema.json`: public JSON Schema contracts exported from Rust types and checked into the repo.
- `fixtures/source/valid-basic.json`: valid source graph with generic plugin view.
- `fixtures/source/invalid-absolute-geometry.json`: invalid source graph that tries to author geometry.
- `fixtures/layout-request/basic.json`: expected projection output fixture.
- `fixtures/layout-result/basic.json`: deterministic layout output fixture.
- `fixtures/render-policy/default-svg.json`: minimal SVG render policy fixture.
- `fixtures/plugins/*.json`: static plugin manifests for bundled first-party plugins.
- `src/lib.rs`: empty root package so workspace-level integration tests under `tests/` run.
- `crates/dediren-contracts/src/lib.rs`: public contract structs, schema export helpers, diagnostic/envelope helpers.
- `crates/dediren-core/src/lib.rs`: core orchestration modules.
- `crates/dediren-core/src/io.rs`: stdin/input/output helpers.
- `crates/dediren-core/src/schema.rs`: schema validation facade.
- `crates/dediren-core/src/plugins.rs`: plugin discovery, manifest loading, runtime execution.
- `crates/dediren-core/src/quality.rs`: backend-neutral layout quality metrics and policies.
- `crates/dediren-cli/src/main.rs`: CLI entrypoint and pipeline commands.
- `crates/dediren-plugin-generic-graph/src/main.rs`: semantic validation and `layout-request` projection plugin.
- `crates/dediren-plugin-elk-layout/src/main.rs`: external ELK process adapter plugin.
- `crates/dediren-plugin-svg-render/src/main.rs`: SVG renderer plugin.
- `tests/schema_contracts.rs`: validates fixtures against schemas.
- `tests/cli_pipeline.rs`: end-to-end CLI pipeline tests with stdin/stdout JSON.
- `tests/plugin_compat.rs`: executable plugin manifest/capability/envelope tests.
- `tests/layout_quality.rs`: backend-neutral metric tests.
- `tests/support/fake_elk.rs`: test helper binary source for deterministic ELK-style layout output.

## Task 1: Bootstrap Workspace, License, And Metadata

**Files:**
- Create: `Cargo.toml`
- Create: `LICENSE`
- Create: `README.md`
- Create: `src/lib.rs`
- Create: `crates/dediren-contracts/Cargo.toml`
- Create: `crates/dediren-contracts/src/lib.rs`
- Create: `crates/dediren-core/Cargo.toml`
- Create: `crates/dediren-core/src/lib.rs`
- Create: `crates/dediren-cli/Cargo.toml`
- Create: `crates/dediren-cli/src/main.rs`
- Create: `crates/dediren-plugin-generic-graph/Cargo.toml`
- Create: `crates/dediren-plugin-generic-graph/src/main.rs`
- Create: `crates/dediren-plugin-elk-layout/Cargo.toml`
- Create: `crates/dediren-plugin-elk-layout/src/main.rs`
- Create: `crates/dediren-plugin-svg-render/Cargo.toml`
- Create: `crates/dediren-plugin-svg-render/src/main.rs`

- [ ] **Step 1: Create the root workspace manifest**

Write `Cargo.toml`:

```toml
[package]
name = "dediren-workspace"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
readme.workspace = true
rust-version.workspace = true
publish = false

[[bin]]
name = "dediren"
path = "crates/dediren-cli/src/main.rs"

[[bin]]
name = "dediren-plugin-generic-graph"
path = "crates/dediren-plugin-generic-graph/src/main.rs"

[[bin]]
name = "dediren-plugin-elk-layout"
path = "crates/dediren-plugin-elk-layout/src/main.rs"

[[bin]]
name = "dediren-plugin-svg-render"
path = "crates/dediren-plugin-svg-render/src/main.rs"

[workspace]
members = [
  "crates/dediren-contracts",
  "crates/dediren-core",
  "crates/dediren-cli",
  "crates/dediren-plugin-generic-graph",
  "crates/dediren-plugin-elk-layout",
  "crates/dediren-plugin-svg-render",
]
resolver = "2"

[workspace.package]
version = "0.1.0"
edition = "2021"
license = "MIT"
repository = "https://github.com/souroldgeezer/dediren"
homepage = "https://github.com/souroldgeezer/dediren"
readme = "README.md"
rust-version = "1.93"

[workspace.dependencies]
anyhow = "1"
assert_cmd = "2"
assert_fs = "1"
clap = { version = "4", features = ["derive"] }
jsonschema = "0.33"
predicates = "3"
schemars = { version = "1", features = ["derive"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tempfile = "3"
thiserror = "2"

[dependencies]
anyhow.workspace = true
clap.workspace = true
dediren-contracts = { path = "crates/dediren-contracts" }
dediren-core = { path = "crates/dediren-core" }
serde.workspace = true
serde_json.workspace = true

[dev-dependencies]
assert_cmd.workspace = true
assert_fs.workspace = true
dediren-contracts = { path = "crates/dediren-contracts" }
dediren-core = { path = "crates/dediren-core" }
jsonschema.workspace = true
predicates.workspace = true
serde_json.workspace = true
```

- [ ] **Step 2: Add MIT license text**

Write `LICENSE`:

```text
MIT License

Copyright (c) 2026 Dediren contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

- [ ] **Step 3: Add README contract summary**

Write `README.md`:

```markdown
# dediren

`dediren` is a structured-data-first diagram rendering CLI for agentic tools.

The v1 pipeline is JSON-first:

```text
validate -> project --target layout-request -> layout -> validate-layout -> render
```

Authored source graph JSON is semantic and plugin-typed. It must not contain
absolute position or size data. Generated layout result JSON may contain
geometry with provenance.

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
```

- [ ] **Step 4: Create minimal crate manifests and entrypoints**

Write `crates/dediren-contracts/Cargo.toml`:

```toml
[package]
name = "dediren-contracts"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
rust-version.workspace = true

[dependencies]
schemars.workspace = true
serde.workspace = true
serde_json.workspace = true
```

Write `crates/dediren-contracts/src/lib.rs`:

```rust
pub const MODEL_SCHEMA_VERSION: &str = "model.schema.v1";
pub const ENVELOPE_SCHEMA_VERSION: &str = "envelope.schema.v1";
pub const PLUGIN_PROTOCOL_VERSION: &str = "plugin.protocol.v1";
```

Write `crates/dediren-core/Cargo.toml`:

```toml
[package]
name = "dediren-core"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
rust-version.workspace = true

[dependencies]
anyhow.workspace = true
dediren-contracts = { path = "../dediren-contracts" }
jsonschema.workspace = true
serde.workspace = true
serde_json.workspace = true
thiserror.workspace = true
```

Write `crates/dediren-core/src/lib.rs`:

```rust
pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
```

Write `crates/dediren-cli/Cargo.toml`:

```toml
[package]
name = "dediren"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
rust-version.workspace = true

[[bin]]
name = "dediren"
path = "src/main.rs"

[dependencies]
anyhow.workspace = true
clap.workspace = true
dediren-core = { path = "../dediren-core" }
```

Write `crates/dediren-cli/src/main.rs`:

```rust
fn main() {
    println!("dediren {}", dediren_core::version());
}
```

Write each plugin manifest with the matching package and binary name. Example for `crates/dediren-plugin-generic-graph/Cargo.toml`:

```toml
[package]
name = "dediren-plugin-generic-graph"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
rust-version.workspace = true

[[bin]]
name = "dediren-plugin-generic-graph"
path = "src/main.rs"

[dependencies]
anyhow.workspace = true
dediren-contracts = { path = "../dediren-contracts" }
serde.workspace = true
serde_json.workspace = true
```

Use the same pattern for `dediren-plugin-elk-layout` and `dediren-plugin-svg-render`, changing only the package and binary names.

Write each plugin `src/main.rs`:

```rust
fn main() {
    println!("{} {}", env!("CARGO_PKG_NAME"), env!("CARGO_PKG_VERSION"));
}
```

Write `src/lib.rs`:

```rust
pub fn workspace_package_marker() -> &'static str {
    "dediren-workspace"
}
```

- [ ] **Step 5: Verify workspace bootstrap**

Run:

```bash
cargo fmt --all
cargo check --workspace
```

Expected: both commands exit `0`.

- [ ] **Step 6: Commit bootstrap**

```bash
git add Cargo.toml LICENSE README.md src crates
git commit -m "chore: bootstrap rust workspace"
```

## Task 2: Define Public Contracts And Fixture Shapes

**Files:**
- Modify: `crates/dediren-contracts/src/lib.rs`
- Create: `fixtures/source/valid-basic.json`
- Create: `fixtures/source/invalid-absolute-geometry.json`
- Create: `fixtures/render-policy/default-svg.json`
- Create: `tests/contract_roundtrip.rs`

- [ ] **Step 1: Write failing contract roundtrip tests**

Create `tests/contract_roundtrip.rs`:

```rust
use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, LayoutRequest, RenderPolicy, SourceDocument,
};

#[test]
fn source_document_roundtrips() {
    let text = std::fs::read_to_string("fixtures/source/valid-basic.json").unwrap();
    let doc: SourceDocument = serde_json::from_str(&text).unwrap();
    assert_eq!(doc.model_schema_version, "model.schema.v1");
    assert_eq!(doc.nodes[0].id, "client");
    assert_eq!(doc.relationships[0].source, "client");
}

#[test]
fn command_envelope_roundtrips() {
    let envelope = CommandEnvelope::<serde_json::Value> {
        envelope_schema_version: "envelope.schema.v1".to_string(),
        status: "ok".to_string(),
        data: Some(serde_json::json!({"kind": "sample"})),
        diagnostics: vec![Diagnostic {
            code: "DEDIREN_TEST".to_string(),
            severity: DiagnosticSeverity::Info,
            message: "sample".to_string(),
            path: Some("$.nodes[0]".to_string()),
        }],
    };
    let encoded = serde_json::to_string(&envelope).unwrap();
    let decoded: CommandEnvelope<serde_json::Value> = serde_json::from_str(&encoded).unwrap();
    assert_eq!(decoded.status, "ok");
    assert_eq!(decoded.diagnostics[0].severity, DiagnosticSeverity::Info);
}

#[test]
fn layout_request_roundtrips() {
    let request = LayoutRequest {
        layout_request_schema_version: "layout-request.schema.v1".to_string(),
        view_id: "main".to_string(),
        nodes: vec![],
        edges: vec![],
        groups: vec![],
        labels: vec![],
        constraints: vec![],
    };
    let encoded = serde_json::to_string(&request).unwrap();
    let decoded: LayoutRequest = serde_json::from_str(&encoded).unwrap();
    assert_eq!(decoded.view_id, "main");
}

#[test]
fn render_policy_roundtrips() {
    let text = std::fs::read_to_string("fixtures/render-policy/default-svg.json").unwrap();
    let policy: RenderPolicy = serde_json::from_str(&text).unwrap();
    assert_eq!(policy.svg_render_policy_schema_version, "svg-render-policy.schema.v1");
    assert_eq!(policy.page.width, 1200.0);
}
```

Run:

```bash
cargo test --test contract_roundtrip
```

Expected: FAIL because the contract types do not exist.

- [ ] **Step 2: Implement contract structs**

Replace `crates/dediren-contracts/src/lib.rs` with:

```rust
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

pub const MODEL_SCHEMA_VERSION: &str = "model.schema.v1";
pub const ENVELOPE_SCHEMA_VERSION: &str = "envelope.schema.v1";
pub const PLUGIN_PROTOCOL_VERSION: &str = "plugin.protocol.v1";
pub const LAYOUT_REQUEST_SCHEMA_VERSION: &str = "layout-request.schema.v1";
pub const LAYOUT_RESULT_SCHEMA_VERSION: &str = "layout-result.schema.v1";
pub const SVG_RENDER_POLICY_SCHEMA_VERSION: &str = "svg-render-policy.schema.v1";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum DiagnosticSeverity {
    Info,
    Warning,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct Diagnostic {
    pub code: String,
    pub severity: DiagnosticSeverity,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct CommandEnvelope<T> {
    pub envelope_schema_version: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<T>,
    #[serde(default)]
    pub diagnostics: Vec<Diagnostic>,
}

impl<T> CommandEnvelope<T> {
    pub fn ok(data: T) -> Self {
        Self {
            envelope_schema_version: ENVELOPE_SCHEMA_VERSION.to_string(),
            status: "ok".to_string(),
            data: Some(data),
            diagnostics: Vec::new(),
        }
    }

    pub fn error(diagnostics: Vec<Diagnostic>) -> Self {
        Self {
            envelope_schema_version: ENVELOPE_SCHEMA_VERSION.to_string(),
            status: "error".to_string(),
            data: None,
            diagnostics,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct SourceDocument {
    pub model_schema_version: String,
    #[serde(default)]
    pub required_plugins: Vec<PluginRequirement>,
    #[serde(default)]
    pub nodes: Vec<SourceNode>,
    #[serde(default)]
    pub relationships: Vec<SourceRelationship>,
    #[serde(default)]
    pub plugins: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct PluginRequirement {
    pub id: String,
    pub version: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct SourceNode {
    pub id: String,
    #[serde(rename = "type")]
    pub node_type: String,
    pub label: String,
    #[serde(default)]
    pub properties: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct SourceRelationship {
    pub id: String,
    #[serde(rename = "type")]
    pub relationship_type: String,
    pub source: String,
    pub target: String,
    pub label: String,
    #[serde(default)]
    pub properties: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct GenericGraphPluginData {
    pub views: Vec<GenericGraphView>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct GenericGraphView {
    pub id: String,
    pub label: String,
    pub nodes: Vec<String>,
    pub relationships: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct LayoutRequest {
    pub layout_request_schema_version: String,
    pub view_id: String,
    #[serde(default)]
    pub nodes: Vec<LayoutNode>,
    #[serde(default)]
    pub edges: Vec<LayoutEdge>,
    #[serde(default)]
    pub groups: Vec<LayoutGroup>,
    #[serde(default)]
    pub labels: Vec<LayoutLabel>,
    #[serde(default)]
    pub constraints: Vec<LayoutConstraint>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct LayoutNode {
    pub id: String,
    pub label: String,
    pub source_id: String,
    #[serde(default)]
    pub width_hint: Option<f64>,
    #[serde(default)]
    pub height_hint: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct LayoutEdge {
    pub id: String,
    pub source: String,
    pub target: String,
    pub label: String,
    pub source_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct LayoutGroup {
    pub id: String,
    pub label: String,
    pub members: Vec<String>,
    pub provenance: GroupProvenance,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum GroupProvenance {
    VisualOnly,
    SemanticBacked { source_id: String },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct LayoutLabel {
    pub owner_id: String,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct LayoutConstraint {
    pub id: String,
    pub kind: String,
    pub subjects: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct LayoutResult {
    pub layout_result_schema_version: String,
    pub view_id: String,
    #[serde(default)]
    pub nodes: Vec<LaidOutNode>,
    #[serde(default)]
    pub edges: Vec<LaidOutEdge>,
    #[serde(default)]
    pub warnings: Vec<Diagnostic>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct LaidOutNode {
    pub id: String,
    pub source_id: String,
    pub projection_id: String,
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct LaidOutEdge {
    pub id: String,
    pub source_id: String,
    pub projection_id: String,
    pub points: Vec<Point>,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct RenderPolicy {
    pub svg_render_policy_schema_version: String,
    pub page: Page,
    pub margin: Margin,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct Page {
    pub width: f64,
    pub height: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct Margin {
    pub top: f64,
    pub right: f64,
    pub bottom: f64,
    pub left: f64,
}
```

- [ ] **Step 3: Add source and render fixtures**

Create `fixtures/source/valid-basic.json`:

```json
{
  "model_schema_version": "model.schema.v1",
  "required_plugins": [
    {
      "id": "generic-graph",
      "version": "0.1.0"
    }
  ],
  "nodes": [
    {
      "id": "client",
      "type": "generic.actor",
      "label": "Client",
      "properties": {}
    },
    {
      "id": "api",
      "type": "generic.component",
      "label": "API",
      "properties": {}
    }
  ],
  "relationships": [
    {
      "id": "client-calls-api",
      "type": "generic.calls",
      "source": "client",
      "target": "api",
      "label": "calls",
      "properties": {}
    }
  ],
  "plugins": {
    "generic-graph": {
      "views": [
        {
          "id": "main",
          "label": "Main",
          "nodes": ["client", "api"],
          "relationships": ["client-calls-api"]
        }
      ]
    }
  }
}
```

Create `fixtures/source/invalid-absolute-geometry.json`:

```json
{
  "model_schema_version": "model.schema.v1",
  "nodes": [
    {
      "id": "client",
      "type": "generic.actor",
      "label": "Client",
      "x": 10,
      "y": 20,
      "properties": {}
    }
  ],
  "relationships": [],
  "plugins": {}
}
```

Create `fixtures/render-policy/default-svg.json`:

```json
{
  "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
  "page": {
    "width": 1200,
    "height": 800
  },
  "margin": {
    "top": 32,
    "right": 32,
    "bottom": 32,
    "left": 32
  }
}
```

- [ ] **Step 4: Run roundtrip tests**

Run:

```bash
cargo test --test contract_roundtrip
```

Expected: PASS.

- [ ] **Step 5: Commit contracts**

```bash
git add crates/dediren-contracts fixtures tests/contract_roundtrip.rs
git commit -m "feat: define initial json contracts"
```

## Task 3: Add JSON Schemas And Schema Tests

**Files:**
- Modify: `crates/dediren-contracts/src/lib.rs`
- Create: `crates/dediren-contracts/src/bin/export_schemas.rs`
- Create: `schemas/model.schema.json`
- Create: `schemas/envelope.schema.json`
- Create: `schemas/layout-request.schema.json`
- Create: `schemas/layout-result.schema.json`
- Create: `schemas/svg-render-policy.schema.json`
- Create: `tests/schema_contracts.rs`

- [ ] **Step 1: Add schema export binary**

Create `crates/dediren-contracts/src/bin/export_schemas.rs`:

```rust
use dediren_contracts::{
    CommandEnvelope, LayoutRequest, LayoutResult, RenderPolicy, SourceDocument,
};
use schemars::schema_for;

fn main() -> anyhow::Result<()> {
    std::fs::create_dir_all("schemas")?;
    write_schema("schemas/model.schema.json", &schema_for!(SourceDocument))?;
    write_schema(
        "schemas/envelope.schema.json",
        &schema_for!(CommandEnvelope<serde_json::Value>),
    )?;
    write_schema("schemas/layout-request.schema.json", &schema_for!(LayoutRequest))?;
    write_schema("schemas/layout-result.schema.json", &schema_for!(LayoutResult))?;
    write_schema("schemas/svg-render-policy.schema.json", &schema_for!(RenderPolicy))?;
    Ok(())
}

fn write_schema(path: &str, schema: &schemars::Schema) -> anyhow::Result<()> {
    let text = serde_json::to_string_pretty(schema)?;
    std::fs::write(path, format!("{text}\n"))?;
    Ok(())
}
```

Add `anyhow.workspace = true` to `crates/dediren-contracts/Cargo.toml`.

- [ ] **Step 2: Generate schemas**

Run:

```bash
cargo run -p dediren-contracts --bin export_schemas
```

Expected: command exits `0` and writes the five schema files under `schemas/`.

- [ ] **Step 3: Write schema validation tests**

Create `tests/schema_contracts.rs`:

```rust
use jsonschema::Validator;

#[test]
fn valid_source_matches_model_schema() {
    assert_valid("schemas/model.schema.json", "fixtures/source/valid-basic.json");
}

#[test]
fn source_with_absolute_geometry_fails_model_schema() {
    assert_invalid(
        "schemas/model.schema.json",
        "fixtures/source/invalid-absolute-geometry.json",
    );
}

#[test]
fn default_svg_policy_matches_schema() {
    assert_valid(
        "schemas/svg-render-policy.schema.json",
        "fixtures/render-policy/default-svg.json",
    );
}

fn assert_valid(schema_path: &str, instance_path: &str) {
    let validator = validator(schema_path);
    let instance = json_file(instance_path);
    let result = validator.validate(&instance);
    assert!(result.is_ok(), "{instance_path} should validate");
}

fn assert_invalid(schema_path: &str, instance_path: &str) {
    let validator = validator(schema_path);
    let instance = json_file(instance_path);
    let result = validator.validate(&instance);
    assert!(result.is_err(), "{instance_path} should fail validation");
}

fn validator(path: &str) -> Validator {
    let schema = json_file(path);
    jsonschema::validator_for(&schema).unwrap()
}

fn json_file(path: &str) -> serde_json::Value {
    let text = std::fs::read_to_string(path).unwrap();
    serde_json::from_str(&text).unwrap()
}
```

- [ ] **Step 4: Tighten schema shape so authored geometry fails**

Add `#[serde(deny_unknown_fields)]` to `SourceDocument`, `SourceNode`, `SourceRelationship`, and all render policy structs in `crates/dediren-contracts/src/lib.rs`.

Run:

```bash
cargo run -p dediren-contracts --bin export_schemas
cargo test --test schema_contracts
```

Expected: PASS.

- [ ] **Step 5: Commit schemas**

```bash
git add crates/dediren-contracts schemas tests/schema_contracts.rs
git commit -m "feat: publish initial json schemas"
```

## Task 4: Implement Core I/O And Validate Command

**Files:**
- Create: `crates/dediren-core/src/io.rs`
- Create: `crates/dediren-core/src/validate.rs`
- Modify: `crates/dediren-core/src/lib.rs`
- Modify: `crates/dediren-cli/src/main.rs`
- Create: `tests/cli_validate.rs`

- [ ] **Step 1: Write failing CLI validate tests**

Create `tests/cli_validate.rs`:

```rust
use assert_cmd::Command;
use predicates::prelude::*;

#[test]
fn validate_accepts_valid_source_from_file() {
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.args(["validate", "--input", "fixtures/source/valid-basic.json"]);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"status\":\"ok\""));
}

#[test]
fn validate_rejects_authored_geometry() {
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.args([
        "validate",
        "--input",
        "fixtures/source/invalid-absolute-geometry.json",
    ]);
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_SCHEMA_INVALID"));
}
```

Run:

```bash
cargo test --test cli_validate
```

Expected: FAIL because the CLI has no `validate` command.

- [ ] **Step 2: Implement input reading and source validation**

Create `crates/dediren-core/src/io.rs`:

```rust
use serde::de::DeserializeOwned;
use std::io::Read;

pub fn read_json_input(path: Option<&str>) -> anyhow::Result<String> {
    match path {
        Some(path) => Ok(std::fs::read_to_string(path)?),
        None => {
            let mut text = String::new();
            std::io::stdin().read_to_string(&mut text)?;
            Ok(text)
        }
    }
}

pub fn parse_command_data<T: DeserializeOwned>(text: &str) -> anyhow::Result<T> {
    let value: serde_json::Value = serde_json::from_str(text)?;
    if value
        .get("envelope_schema_version")
        .and_then(serde_json::Value::as_str)
        .is_some()
    {
        let data = value
            .get("data")
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("command envelope does not contain data"))?;
        Ok(serde_json::from_value(data)?)
    } else {
        Ok(serde_json::from_value(value)?)
    }
}
```

Create `crates/dediren-core/src/validate.rs`:

```rust
use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, SourceDocument, ENVELOPE_SCHEMA_VERSION,
};

pub fn validate_source_json(text: &str) -> (i32, CommandEnvelope<serde_json::Value>) {
    match serde_json::from_str::<SourceDocument>(text) {
        Ok(doc) => {
            let data = serde_json::json!({
                "model_schema_version": doc.model_schema_version,
                "node_count": doc.nodes.len(),
                "relationship_count": doc.relationships.len()
            });
            (0, CommandEnvelope::ok(data))
        }
        Err(error) => {
            let diagnostic = Diagnostic {
                code: "DEDIREN_SCHEMA_INVALID".to_string(),
                severity: DiagnosticSeverity::Error,
                message: error.to_string(),
                path: None,
            };
            (
                2,
                CommandEnvelope {
                    envelope_schema_version: ENVELOPE_SCHEMA_VERSION.to_string(),
                    status: "error".to_string(),
                    data: None,
                    diagnostics: vec![diagnostic],
                },
            )
        }
    }
}
```

Modify `crates/dediren-core/src/lib.rs`:

```rust
pub mod io;
pub mod validate;

pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
```

- [ ] **Step 3: Implement CLI validate command**

Replace `crates/dediren-cli/src/main.rs` with:

```rust
use clap::{Parser, Subcommand};

#[derive(Debug, Parser)]
#[command(name = "dediren")]
#[command(version)]
struct Cli {
    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Debug, Subcommand)]
enum Commands {
    Validate {
        #[arg(long)]
        input: Option<String>,
    },
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    match cli.command {
        Some(Commands::Validate { input }) => {
            let text = dediren_core::io::read_json_input(input.as_deref())?;
            let (code, envelope) = dediren_core::validate::validate_source_json(&text);
            println!("{}", serde_json::to_string(&envelope)?);
            std::process::exit(code);
        }
        None => {
            println!("dediren {}", dediren_core::version());
            Ok(())
        }
    }
}
```

Add `serde_json.workspace = true` to `crates/dediren-cli/Cargo.toml`.

- [ ] **Step 4: Verify validate command**

Run:

```bash
cargo test --test cli_validate
```

Expected: PASS.

- [ ] **Step 5: Commit validate command**

```bash
git add crates/dediren-core crates/dediren-cli tests/cli_validate.rs
git commit -m "feat: add validate command"
```

## Task 5: Implement Generic Graph Projection Plugin

**Files:**
- Modify: `crates/dediren-plugin-generic-graph/src/main.rs`
- Create: `fixtures/layout-request/basic.json`
- Create: `tests/generic_graph_plugin.rs`

- [ ] **Step 1: Write failing plugin projection test**

Create `tests/generic_graph_plugin.rs`:

```rust
use assert_cmd::Command;
use predicates::prelude::*;

#[test]
fn generic_graph_projects_basic_view() {
    let input = std::fs::read_to_string("fixtures/source/valid-basic.json").unwrap();
    let mut cmd = Command::cargo_bin("dediren-plugin-generic-graph").unwrap();
    cmd.args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(input);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"layout_request_schema_version\""))
        .stdout(predicate::str::contains("\"view_id\":\"main\""));
}
```

Run:

```bash
cargo test --test generic_graph_plugin
```

Expected: FAIL because the plugin has no `project` command.

- [ ] **Step 2: Implement plugin projection behavior**

Replace `crates/dediren-plugin-generic-graph/src/main.rs` with:

```rust
use std::io::Read;

use anyhow::{bail, Context};
use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, GenericGraphPluginData, LayoutEdge,
    LayoutLabel, LayoutNode, LayoutRequest, LAYOUT_REQUEST_SCHEMA_VERSION, SourceDocument,
};

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("capabilities") {
        println!(
            "{}",
            serde_json::json!({
                "plugin_protocol_version": "plugin.protocol.v1",
                "id": "generic-graph",
                "capabilities": ["semantic-validation", "projection"]
            })
        );
        return Ok(());
    }

    if args.get(1).map(String::as_str) != Some("project") {
        bail!("expected command: project");
    }

    let target = value_after(&args, "--target").context("missing --target")?;
    let view = value_after(&args, "--view").context("missing --view")?;
    if target != "layout-request" {
        bail!("unsupported target: {target}");
    }

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let source: SourceDocument = serde_json::from_str(&input)?;
    let plugin_value = source
        .plugins
        .get("generic-graph")
        .context("missing plugins.generic-graph")?
        .clone();
    let plugin_data: GenericGraphPluginData = serde_json::from_value(plugin_value)?;
    let selected_view = plugin_data
        .views
        .iter()
        .find(|candidate| candidate.id == view)
        .with_context(|| format!("missing generic-graph view {view}"))?;

    let nodes = selected_view
        .nodes
        .iter()
        .map(|id| {
            let source_node = source
                .nodes
                .iter()
                .find(|node| node.id == *id)
                .with_context(|| format!("view references missing node {id}"))?;
            Ok(LayoutNode {
                id: source_node.id.clone(),
                label: source_node.label.clone(),
                source_id: source_node.id.clone(),
                width_hint: Some(160.0),
                height_hint: Some(80.0),
            })
        })
        .collect::<anyhow::Result<Vec<_>>>()?;

    let edges = selected_view
        .relationships
        .iter()
        .map(|id| {
            let relationship = source
                .relationships
                .iter()
                .find(|relationship| relationship.id == *id)
                .with_context(|| format!("view references missing relationship {id}"))?;
            Ok(LayoutEdge {
                id: relationship.id.clone(),
                source: relationship.source.clone(),
                target: relationship.target.clone(),
                label: relationship.label.clone(),
                source_id: relationship.id.clone(),
            })
        })
        .collect::<anyhow::Result<Vec<_>>>()?;

    let labels = nodes
        .iter()
        .map(|node| LayoutLabel {
            owner_id: node.id.clone(),
            text: node.label.clone(),
        })
        .collect();

    let request = LayoutRequest {
        layout_request_schema_version: LAYOUT_REQUEST_SCHEMA_VERSION.to_string(),
        view_id: selected_view.id.clone(),
        nodes,
        edges,
        groups: Vec::new(),
        labels,
        constraints: Vec::new(),
    };

    println!("{}", serde_json::to_string(&CommandEnvelope::ok(request))?);
    Ok(())
}

fn value_after(args: &[String], flag: &str) -> Option<String> {
    args.windows(2)
        .find(|window| window[0] == flag)
        .map(|window| window[1].clone())
}
```

- [ ] **Step 3: Create expected layout request fixture**

Create `fixtures/layout-request/basic.json`:

```json
{
  "layout_request_schema_version": "layout-request.schema.v1",
  "view_id": "main",
  "nodes": [
    {
      "id": "client",
      "label": "Client",
      "source_id": "client",
      "width_hint": 160,
      "height_hint": 80
    },
    {
      "id": "api",
      "label": "API",
      "source_id": "api",
      "width_hint": 160,
      "height_hint": 80
    }
  ],
  "edges": [
    {
      "id": "client-calls-api",
      "source": "client",
      "target": "api",
      "label": "calls",
      "source_id": "client-calls-api"
    }
  ],
  "groups": [],
  "labels": [
    {
      "owner_id": "client",
      "text": "Client"
    },
    {
      "owner_id": "api",
      "text": "API"
    }
  ],
  "constraints": []
}
```

- [ ] **Step 4: Verify projection plugin**

Run:

```bash
cargo test --test generic_graph_plugin
```

Expected: PASS.

- [ ] **Step 5: Commit generic graph plugin**

```bash
git add crates/dediren-plugin-generic-graph fixtures/layout-request tests/generic_graph_plugin.rs
git commit -m "feat: add generic graph projection plugin"
```

## Task 6: Add Plugin Discovery And Project Command

**Files:**
- Create: `fixtures/plugins/generic-graph.manifest.json`
- Create: `crates/dediren-core/src/plugins.rs`
- Modify: `crates/dediren-core/src/lib.rs`
- Modify: `crates/dediren-cli/src/main.rs`
- Create: `tests/cli_project.rs`

- [ ] **Step 1: Write failing CLI project test**

Create `tests/cli_project.rs`:

```rust
use assert_cmd::Command;
use predicates::prelude::*;

#[test]
fn project_invokes_generic_graph_plugin() {
    let plugin = assert_cmd::cargo::cargo_bin("dediren-plugin-generic-graph");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.env("DEDIREN_PLUGIN_GENERIC_GRAPH", plugin);
    cmd.args([
        "project",
        "--target",
        "layout-request",
        "--plugin",
        "generic-graph",
        "--view",
        "main",
        "--input",
        "fixtures/source/valid-basic.json",
    ]);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"layout_request_schema_version\""));
}
```

Run:

```bash
cargo test --test cli_project
```

Expected: FAIL because the CLI has no `project` command.

- [ ] **Step 2: Add bundled plugin manifest**

Create `fixtures/plugins/generic-graph.manifest.json`:

```json
{
  "plugin_manifest_schema_version": "plugin-manifest.schema.v1",
  "id": "generic-graph",
  "version": "0.1.0",
  "executable": "dediren-plugin-generic-graph",
  "capabilities": ["semantic-validation", "projection"]
}
```

- [ ] **Step 3: Implement plugin execution helper**

Create `crates/dediren-core/src/plugins.rs`:

```rust
use std::io::Write;
use std::process::{Command, Stdio};

pub fn bundled_executable(plugin_id: &str) -> anyhow::Result<std::path::PathBuf> {
    let (env_name, binary_name) = match plugin_id {
        "generic-graph" => ("DEDIREN_PLUGIN_GENERIC_GRAPH", "dediren-plugin-generic-graph"),
        "elk-layout" => ("DEDIREN_PLUGIN_ELK_LAYOUT", "dediren-plugin-elk-layout"),
        "svg-render" => ("DEDIREN_PLUGIN_SVG_RENDER", "dediren-plugin-svg-render"),
        _ => anyhow::bail!("unknown plugin id: {plugin_id}"),
    };

    if let Ok(path) = std::env::var(env_name) {
        return Ok(std::path::PathBuf::from(path));
    }

    let current = std::env::current_exe()?;
    Ok(current.with_file_name(binary_name))
}

pub fn run_plugin(plugin_id: &str, args: &[&str], input: &str) -> anyhow::Result<String> {
    let executable = bundled_executable(plugin_id)?;
    let mut child = Command::new(executable)
        .args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;

    if let Some(stdin) = child.stdin.as_mut() {
        stdin.write_all(input.as_bytes())?;
    }

    let output = child.wait_with_output()?;
    if !output.status.success() {
        return Err(anyhow::anyhow!(
            "plugin {plugin_id} exited with status {:?}: {}",
            output.status.code(),
            String::from_utf8_lossy(&output.stderr)
        ));
    }

    Ok(String::from_utf8(output.stdout)?)
}
```

Modify `crates/dediren-core/src/lib.rs`:

```rust
pub mod io;
pub mod plugins;
pub mod validate;

pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
```

- [ ] **Step 4: Add CLI project command**

Extend `crates/dediren-cli/src/main.rs` with a `Project` subcommand:

```rust
Project {
    #[arg(long)]
    target: String,
    #[arg(long)]
    plugin: String,
    #[arg(long)]
    view: String,
    #[arg(long)]
    input: Option<String>,
},
```

Add this match arm:

```rust
Some(Commands::Project {
    target,
    plugin,
    view,
    input,
}) => {
    let text = dediren_core::io::read_json_input(input.as_deref())?;
    let output = dediren_core::plugins::run_plugin(
        &plugin,
        &["project", "--target", &target, "--view", &view],
        &text,
    )?;
    print!("{output}");
    Ok(())
}
```

- [ ] **Step 5: Verify project command**

Run:

```bash
cargo test --test cli_project
```

Expected: PASS.

- [ ] **Step 6: Commit project orchestration**

```bash
git add crates/dediren-core crates/dediren-cli fixtures/plugins tests/cli_project.rs
git commit -m "feat: orchestrate projection plugins"
```

## Task 7: Implement ELK Layout Plugin With External Helper Contract

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/src/main.rs`
- Create: `tests/elk_layout_plugin.rs`
- Create: `fixtures/layout-result/basic.json`

- [ ] **Step 1: Write failing ELK plugin tests**

Create `tests/elk_layout_plugin.rs`:

```rust
use assert_cmd::Command;
use predicates::prelude::*;

#[test]
fn elk_plugin_reports_missing_runtime() {
    let input = std::fs::read_to_string("fixtures/layout-request/basic.json").unwrap();
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.arg("layout").write_stdin(input);
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_ELK_RUNTIME_UNAVAILABLE"));
}

#[test]
fn elk_plugin_accepts_fixture_runtime_output() {
    let input = std::fs::read_to_string("fixtures/layout-request/basic.json").unwrap();
    let fake = std::env::current_dir()
        .unwrap()
        .join("fixtures/layout-result/basic.json");
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_RESULT_FIXTURE", fake)
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"layout_result_schema_version\""))
        .stdout(predicate::str::contains("\"projection_id\":\"client\""));
}
```

Run:

```bash
cargo test --test elk_layout_plugin
```

Expected: FAIL because the plugin has no `layout` command.

- [ ] **Step 2: Add deterministic layout result fixture**

Create `fixtures/layout-result/basic.json`:

```json
{
  "layout_result_schema_version": "layout-result.schema.v1",
  "view_id": "main",
  "nodes": [
    {
      "id": "client",
      "source_id": "client",
      "projection_id": "client",
      "x": 32,
      "y": 32,
      "width": 160,
      "height": 80,
      "label": "Client"
    },
    {
      "id": "api",
      "source_id": "api",
      "projection_id": "api",
      "x": 272,
      "y": 32,
      "width": 160,
      "height": 80,
      "label": "API"
    }
  ],
  "edges": [
    {
      "id": "client-calls-api",
      "source_id": "client-calls-api",
      "projection_id": "client-calls-api",
      "points": [
        {
          "x": 192,
          "y": 72
        },
        {
          "x": 272,
          "y": 72
        }
      ],
      "label": "calls"
    }
  ],
  "warnings": []
}
```

- [ ] **Step 3: Implement ELK adapter behavior**

Replace `crates/dediren-plugin-elk-layout/src/main.rs` with:

```rust
use std::io::Read;

use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, LayoutRequest, LayoutResult,
};

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("capabilities") {
        println!(
            "{}",
            serde_json::json!({
                "plugin_protocol_version": "plugin.protocol.v1",
                "id": "elk-layout",
                "capabilities": ["layout"],
                "runtime": {
                    "kind": "external-elk",
                    "available": std::env::var("DEDIREN_ELK_COMMAND").is_ok()
                        || std::env::var("DEDIREN_ELK_RESULT_FIXTURE").is_ok()
                }
            })
        );
        return Ok(());
    }

    if args.get(1).map(String::as_str) != Some("layout") {
        anyhow::bail!("expected command: layout");
    }

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let _request: LayoutRequest = serde_json::from_str(&input)?;

    if let Ok(fixture) = std::env::var("DEDIREN_ELK_RESULT_FIXTURE") {
        let text = std::fs::read_to_string(fixture)?;
        let result: LayoutResult = serde_json::from_str(&text)?;
        println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
        return Ok(());
    }

    let diagnostic = Diagnostic {
        code: "DEDIREN_ELK_RUNTIME_UNAVAILABLE".to_string(),
        severity: DiagnosticSeverity::Error,
        message: "ELK runtime is not configured; set DEDIREN_ELK_COMMAND or use a test fixture".to_string(),
        path: None,
    };
    println!(
        "{}",
        serde_json::to_string(&CommandEnvelope::<serde_json::Value>::error(vec![diagnostic]))?
    );
    std::process::exit(3);
}
```

- [ ] **Step 4: Verify ELK plugin**

Run:

```bash
cargo test --test elk_layout_plugin
```

Expected: PASS.

- [ ] **Step 5: Commit ELK plugin**

```bash
git add crates/dediren-plugin-elk-layout fixtures/layout-result tests/elk_layout_plugin.rs
git commit -m "feat: add elk layout plugin contract"
```

## Task 8: Add Layout Command And Layout Quality Validation

**Files:**
- Create: `crates/dediren-core/src/quality.rs`
- Modify: `crates/dediren-core/src/lib.rs`
- Modify: `crates/dediren-cli/src/main.rs`
- Create: `tests/layout_quality.rs`
- Create: `tests/cli_layout.rs`

- [ ] **Step 1: Write failing quality tests**

Create `tests/layout_quality.rs`:

```rust
use dediren_contracts::{LaidOutNode, LayoutResult};

#[test]
fn non_overlapping_layout_has_zero_overlaps() {
    let text = std::fs::read_to_string("fixtures/layout-result/basic.json").unwrap();
    let result: LayoutResult = serde_json::from_str(&text).unwrap();
    let report = dediren_core::quality::validate_layout(&result);
    assert_eq!(report.overlap_count, 0);
    assert_eq!(report.status, "ok");
}

#[test]
fn overlapping_nodes_are_counted() {
    let mut result = LayoutResult {
        layout_result_schema_version: "layout-result.schema.v1".to_string(),
        view_id: "main".to_string(),
        nodes: vec![],
        edges: vec![],
        warnings: vec![],
    };
    result.nodes.push(node("a", 0.0, 0.0));
    result.nodes.push(node("b", 50.0, 20.0));
    let report = dediren_core::quality::validate_layout(&result);
    assert_eq!(report.overlap_count, 1);
    assert_eq!(report.status, "warning");
}

fn node(id: &str, x: f64, y: f64) -> LaidOutNode {
    LaidOutNode {
        id: id.to_string(),
        source_id: id.to_string(),
        projection_id: id.to_string(),
        x,
        y,
        width: 100.0,
        height: 80.0,
        label: id.to_string(),
    }
}
```

Run:

```bash
cargo test --test layout_quality
```

Expected: FAIL because `quality` does not exist.

- [ ] **Step 2: Implement backend-neutral quality validation**

Create `crates/dediren-core/src/quality.rs`:

```rust
use dediren_contracts::LayoutResult;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LayoutQualityReport {
    pub status: String,
    pub overlap_count: usize,
    pub warning_count: usize,
}

pub fn validate_layout(result: &LayoutResult) -> LayoutQualityReport {
    let overlap_count = count_overlaps(result);
    let warning_count = result.warnings.len();
    let status = if overlap_count == 0 && warning_count == 0 {
        "ok"
    } else {
        "warning"
    };

    LayoutQualityReport {
        status: status.to_string(),
        overlap_count,
        warning_count,
    }
}

fn count_overlaps(result: &LayoutResult) -> usize {
    let mut count = 0;
    for (index, left) in result.nodes.iter().enumerate() {
        for right in result.nodes.iter().skip(index + 1) {
            if rectangles_overlap(left.x, left.y, left.width, left.height, right.x, right.y, right.width, right.height) {
                count += 1;
            }
        }
    }
    count
}

fn rectangles_overlap(
    left_x: f64,
    left_y: f64,
    left_width: f64,
    left_height: f64,
    right_x: f64,
    right_y: f64,
    right_width: f64,
    right_height: f64,
) -> bool {
    left_x < right_x + right_width
        && left_x + left_width > right_x
        && left_y < right_y + right_height
        && left_y + left_height > right_y
}
```

Modify `crates/dediren-core/src/lib.rs`:

```rust
pub mod io;
pub mod plugins;
pub mod quality;
pub mod validate;

pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
```

- [ ] **Step 3: Add CLI layout and validate-layout tests**

Create `tests/cli_layout.rs`:

```rust
use assert_cmd::Command;
use predicates::prelude::*;

#[test]
fn layout_invokes_elk_plugin_with_fixture_runtime() {
    let plugin = assert_cmd::cargo::cargo_bin("dediren-plugin-elk-layout");
    let fixture = std::env::current_dir()
        .unwrap()
        .join("fixtures/layout-result/basic.json");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.env("DEDIREN_PLUGIN_ELK_LAYOUT", plugin)
        .env("DEDIREN_ELK_RESULT_FIXTURE", fixture)
        .args([
            "layout",
            "--plugin",
            "elk-layout",
            "--input",
            "fixtures/layout-request/basic.json",
        ]);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"layout_result_schema_version\""));
}

#[test]
fn validate_layout_reports_quality() {
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.args(["validate-layout", "--input", "fixtures/layout-result/basic.json"]);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"overlap_count\":0"));
}
```

Run:

```bash
cargo test --test cli_layout
```

Expected: FAIL because the CLI has no `layout` or `validate-layout` commands.

- [ ] **Step 4: Add CLI layout and validate-layout commands**

Add command variants in `crates/dediren-cli/src/main.rs`:

```rust
Layout {
    #[arg(long)]
    plugin: String,
    #[arg(long)]
    input: Option<String>,
},
ValidateLayout {
    #[arg(long)]
    input: Option<String>,
},
```

Add match arms:

```rust
Some(Commands::Layout { plugin, input }) => {
    let text = dediren_core::io::read_json_input(input.as_deref())?;
    let request: dediren_contracts::LayoutRequest =
        dediren_core::io::parse_command_data(&text)?;
    let output = dediren_core::plugins::run_plugin(
        &plugin,
        &["layout"],
        &serde_json::to_string(&request)?,
    )?;
    print!("{output}");
    Ok(())
}
Some(Commands::ValidateLayout { input }) => {
    let text = dediren_core::io::read_json_input(input.as_deref())?;
    let result: dediren_contracts::LayoutResult =
        dediren_core::io::parse_command_data(&text)?;
    let report = dediren_core::quality::validate_layout(&result);
    let envelope = dediren_contracts::CommandEnvelope::ok(report);
    println!("{}", serde_json::to_string(&envelope)?);
    Ok(())
}
```

Add `dediren-contracts = { path = "../dediren-contracts" }` to `crates/dediren-cli/Cargo.toml`.

- [ ] **Step 5: Verify layout commands**

Run:

```bash
cargo test --test layout_quality
cargo test --test cli_layout
```

Expected: PASS.

- [ ] **Step 6: Commit layout validation**

```bash
git add crates/dediren-core crates/dediren-cli tests/layout_quality.rs tests/cli_layout.rs
git commit -m "feat: add layout command and quality validation"
```

## Task 9: Implement SVG Render Plugin And Render Command

**Files:**
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-cli/src/main.rs`
- Create: `tests/svg_render_plugin.rs`
- Create: `tests/cli_render.rs`

- [ ] **Step 1: Write failing SVG plugin test**

Create `tests/svg_render_plugin.rs`:

```rust
use assert_cmd::Command;
use predicates::prelude::*;

#[test]
fn svg_renderer_outputs_svg() {
    let input = serde_json::json!({
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string("fixtures/layout-result/basic.json").unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string("fixtures/render-policy/default-svg.json").unwrap()
        ).unwrap()
    });
    let mut cmd = Command::cargo_bin("dediren-plugin-svg-render").unwrap();
    cmd.arg("render").write_stdin(serde_json::to_string(&input).unwrap());
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("<svg"))
        .stdout(predicate::str::contains("Client"))
        .stdout(predicate::str::contains("API"));
}
```

Run:

```bash
cargo test --test svg_render_plugin
```

Expected: FAIL because the renderer has no `render` command.

- [ ] **Step 2: Implement SVG renderer plugin**

Replace `crates/dediren-plugin-svg-render/src/main.rs` with:

```rust
use std::io::Read;

use dediren_contracts::{LayoutResult, Point, RenderPolicy};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
struct RenderInput {
    layout_result: LayoutResult,
    policy: RenderPolicy,
}

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("capabilities") {
        println!(
            "{}",
            serde_json::json!({
                "plugin_protocol_version": "plugin.protocol.v1",
                "id": "svg-render",
                "capabilities": ["render"],
                "artifact": "svg"
            })
        );
        return Ok(());
    }

    if args.get(1).map(String::as_str) != Some("render") {
        anyhow::bail!("expected command: render");
    }

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let render_input: RenderInput = serde_json::from_str(&input)?;
    print!("{}", render_svg(&render_input.layout_result, &render_input.policy));
    Ok(())
}

fn render_svg(result: &LayoutResult, policy: &RenderPolicy) -> String {
    let mut svg = String::new();
    svg.push_str(&format!(
        r#"<svg xmlns="http://www.w3.org/2000/svg" width="{:.0}" height="{:.0}" viewBox="0 0 {:.0} {:.0}">"#,
        policy.page.width, policy.page.height, policy.page.width, policy.page.height
    ));
    svg.push_str(r#"<rect width="100%" height="100%" fill="#ffffff"/>"#);
    svg.push_str(r#"<g font-family="Inter, Arial, sans-serif" font-size="14">"#);

    for edge in &result.edges {
        svg.push_str(&edge_path(&edge.points));
        if let Some(point) = edge.points.first() {
            svg.push_str(&format!(
                r#"<text x="{:.1}" y="{:.1}" fill="#374151">{}</text>"#,
                point.x,
                point.y - 8.0,
                escape(&edge.label)
            ));
        }
    }

    for node in &result.nodes {
        svg.push_str(&format!(
            r#"<rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="6" fill="#f8fafc" stroke="#334155" stroke-width="1.5"/>"#,
            node.x, node.y, node.width, node.height
        ));
        svg.push_str(&format!(
            r#"<text x="{:.1}" y="{:.1}" text-anchor="middle" dominant-baseline="middle" fill="#0f172a">{}</text>"#,
            node.x + node.width / 2.0,
            node.y + node.height / 2.0,
            escape(&node.label)
        ));
    }

    svg.push_str("</g></svg>\n");
    svg
}

fn edge_path(points: &[Point]) -> String {
    if points.len() < 2 {
        return String::new();
    }
    let mut data = format!("M {:.1} {:.1}", points[0].x, points[0].y);
    for point in points.iter().skip(1) {
        data.push_str(&format!(" L {:.1} {:.1}", point.x, point.y));
    }
    format!(
        r#"<path d="{data}" fill="none" stroke="#64748b" stroke-width="1.5" marker-end="url(#arrow)"/>"#
    )
}

fn escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}
```

- [ ] **Step 3: Write failing CLI render test**

Create `tests/cli_render.rs`:

```rust
use assert_cmd::Command;
use predicates::prelude::*;

#[test]
fn render_invokes_svg_plugin() {
    let plugin = assert_cmd::cargo::cargo_bin("dediren-plugin-svg-render");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.env("DEDIREN_PLUGIN_SVG_RENDER", plugin);
    cmd.args([
        "render",
        "--plugin",
        "svg-render",
        "--policy",
        "fixtures/render-policy/default-svg.json",
        "--input",
        "fixtures/layout-result/basic.json",
    ]);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("<svg"))
        .stdout(predicate::str::contains("Client"));
}
```

Run:

```bash
cargo test --test cli_render
```

Expected: FAIL because the CLI has no `render` command.

- [ ] **Step 4: Add CLI render command**

Add command variant:

```rust
Render {
    #[arg(long)]
    plugin: String,
    #[arg(long)]
    policy: String,
    #[arg(long)]
    input: Option<String>,
},
```

Add match arm:

```rust
Some(Commands::Render {
    plugin,
    policy,
    input,
}) => {
    let layout_text = dediren_core::io::read_json_input(input.as_deref())?;
    let policy_text = std::fs::read_to_string(policy)?;
    let layout_result: dediren_contracts::LayoutResult =
        dediren_core::io::parse_command_data(&layout_text)?;
    let render_input = serde_json::json!({
        "layout_result": layout_result,
        "policy": serde_json::from_str::<serde_json::Value>(&policy_text)?
    });
    let output = dediren_core::plugins::run_plugin(
        &plugin,
        &["render"],
        &serde_json::to_string(&render_input)?,
    )?;
    print!("{output}");
    Ok(())
}
```

- [ ] **Step 5: Verify render behavior**

Run:

```bash
cargo test --test svg_render_plugin
cargo test --test cli_render
```

Expected: PASS.

- [ ] **Step 6: Commit render plugin**

```bash
git add crates/dediren-plugin-svg-render crates/dediren-cli tests/svg_render_plugin.rs tests/cli_render.rs
git commit -m "feat: add svg render plugin"
```

## Task 10: Add Full Pipeline Test

**Files:**
- Create: `tests/cli_pipeline.rs`

- [ ] **Step 1: Write full pipeline test using intermediate files**

Create `tests/cli_pipeline.rs`:

```rust
use assert_cmd::Command;
use assert_fs::prelude::*;

#[test]
fn full_pipeline_produces_svg() {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = temp.child("request.json");
    let result = temp.child("result.json");
    let svg = temp.child("diagram.svg");
    let generic_plugin = assert_cmd::cargo::cargo_bin("dediren-plugin-generic-graph");
    let elk_plugin = assert_cmd::cargo::cargo_bin("dediren-plugin-elk-layout");
    let svg_plugin = assert_cmd::cargo::cargo_bin("dediren-plugin-svg-render");
    let elk_fixture = std::env::current_dir()
        .unwrap()
        .join("fixtures/layout-result/basic.json");

    let project_output = Command::cargo_bin("dediren")
        .unwrap()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .args([
            "project",
            "--target",
            "layout-request",
            "--plugin",
            "generic-graph",
            "--view",
            "main",
            "--input",
            "fixtures/source/valid-basic.json",
        ])
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    request.write_binary(&project_output).unwrap();

    let layout_output = Command::cargo_bin("dediren")
        .unwrap()
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", &elk_plugin)
        .env("DEDIREN_ELK_RESULT_FIXTURE", elk_fixture)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    result.write_binary(&layout_output).unwrap();

    Command::cargo_bin("dediren")
        .unwrap()
        .args(["validate-layout", "--input"])
        .arg(result.path())
        .assert()
        .success();

    let render_output = Command::cargo_bin("dediren")
        .unwrap()
        .env("DEDIREN_PLUGIN_SVG_RENDER", &svg_plugin)
        .args([
            "render",
            "--plugin",
            "svg-render",
            "--policy",
            "fixtures/render-policy/default-svg.json",
            "--input",
        ])
        .arg(result.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    svg.write_binary(&render_output).unwrap();

    let svg_text = std::fs::read_to_string(svg.path()).unwrap();
    assert!(svg_text.contains("<svg"));
    assert!(svg_text.contains("Client"));
    assert!(svg_text.contains("API"));
}
```

- [ ] **Step 2: Confirm test dependencies are available**

The root `Cargo.toml` from Task 1 already includes these dev-dependencies:

```toml
[dev-dependencies]
assert_cmd.workspace = true
assert_fs.workspace = true
dediren-contracts = { path = "crates/dediren-contracts" }
dediren-core = { path = "crates/dediren-core" }
jsonschema.workspace = true
predicates.workspace = true
serde_json.workspace = true
```

- [ ] **Step 3: Run pipeline test**

Run:

```bash
cargo test --test cli_pipeline
```

Expected: PASS.

- [ ] **Step 4: Commit pipeline test**

```bash
git add tests/cli_pipeline.rs Cargo.toml crates/dediren-cli/Cargo.toml
git commit -m "test: cover full diagram pipeline"
```

## Task 11: Add Plugin Capability And Failure Tests

**Files:**
- Create: `tests/plugin_compat.rs`

- [ ] **Step 1: Write capability tests**

Create `tests/plugin_compat.rs`:

```rust
use assert_cmd::Command;
use predicates::prelude::*;

#[test]
fn first_party_plugins_report_capabilities() {
    for binary in [
        "dediren-plugin-generic-graph",
        "dediren-plugin-elk-layout",
        "dediren-plugin-svg-render",
    ] {
        let mut cmd = Command::cargo_bin(binary).unwrap();
        cmd.arg("capabilities");
        cmd.assert()
            .success()
            .stdout(predicate::str::contains("plugin.protocol.v1"))
            .stdout(predicate::str::contains("capabilities"));
    }
}

#[test]
fn unknown_plugin_failure_is_structured_by_cli() {
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.args([
        "layout",
        "--plugin",
        "missing-plugin",
        "--input",
        "fixtures/layout-request/basic.json",
    ]);
    cmd.assert()
        .failure()
        .stderr(predicate::str::contains("unknown plugin id"));
}
```

Run:

```bash
cargo test --test plugin_compat
```

Expected: PASS. The CLI already returns `anyhow::Result<()>` from `main`, so unknown plugin errors are printed to stderr and the process exits non-zero.

- [ ] **Step 2: Run compatibility tests**

Run:

```bash
cargo test --test plugin_compat
```

Expected: PASS.

- [ ] **Step 3: Commit compatibility tests**

```bash
git add crates/dediren-cli crates/dediren-core tests/plugin_compat.rs
git commit -m "test: verify plugin compatibility surfaces"
```

## Task 12: Finalize README And Verification Gates

**Files:**
- Modify: `README.md`
- Create: `.gitignore`

- [ ] **Step 1: Add repository ignore rules**

Create `.gitignore`:

```gitignore
/target/
**/*.svg
!.github/**/*.svg
```

- [ ] **Step 2: Expand README with concrete examples**

Replace `README.md` with:

```markdown
# dediren

`dediren` is a structured-data-first diagram rendering CLI for agentic tools.

The v1 pipeline is JSON-first:

```text
validate -> project --target layout-request -> layout -> validate-layout -> render
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
```

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

## ELK Runtime

The bundled ELK layout plugin is an external-process adapter. In production it
expects an ELK executable or JAR to be configured. Tests use
`DEDIREN_ELK_RESULT_FIXTURE` to exercise the plugin contract without requiring a
Java runtime.
```

- [ ] **Step 3: Run final verification**

Run:

```bash
cargo fmt --all
cargo test --workspace
cargo check --workspace
git status --short
```

Expected:

- `cargo fmt --all` exits `0`.
- `cargo test --workspace` exits `0`.
- `cargo check --workspace` exits `0`.
- `git status --short` shows only intentional README and `.gitignore` changes before commit.

- [ ] **Step 4: Commit final docs**

```bash
git add README.md .gitignore
git commit -m "docs: document initial pipeline"
```

## Self-Review Notes

- Spec coverage: this plan covers the Rust workspace, MIT license, JSON-only contracts, no source geometry, plugin-typed source graph, pipeline commands, external executable plugins, generic projection, ELK layout adapter contract, layout quality validation, SVG render plugin, schema fixtures, executable compatibility tests, local install docs, and plugin lookup docs.
- Deferred scope: OEF export implementation, PNG rendering, rich SVG policy, non-ELK layout backends, schema-to-Rust codegen, plugin signing, and release binaries remain outside this first implementation plan.
- Type consistency: the plan consistently uses `SourceDocument`, `LayoutRequest`, `LayoutResult`, `RenderPolicy`, and `CommandEnvelope`.
