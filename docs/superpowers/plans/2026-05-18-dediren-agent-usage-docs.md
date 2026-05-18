# Dediren Agent JSON Authoring Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give agents a shipped, token-efficient contract for authoring Dediren source, policy, and pipeline JSON correctly without first reading every schema, fixture, or `--help` page.

**Architecture:** Keep `README.md` as the source-repo entrypoint and add a focused `docs/agent-usage.md` as the source for the shipped JSON authoring guide. The guide must start with the authored-vs-generated JSON boundary, minimal copyable skeletons, fixture/schema crosswalk, validation loop, and repair diagnostics; runtime probes are supporting material. The distribution archive must include that guide under `docs/agent-usage.md`, and downstream skill packages that bundle Dediren must either preserve that file path or embed the same authoring contract in their skill guidance. Fix the CLI preflight gap so the documented stdout-envelope contract is true for missing file inputs as well as plugin/runtime failures.

**Tech Stack:** Rust workspace, Clap CLI, `dediren-contracts` command envelopes, `assert_cmd`, JSON Schema fixtures, Markdown docs.

---

## File Structure

- Modify `crates/dediren-cli/src/main.rs`
  - Owns CLI argument handling and preflight file reads.
  - Add helper functions that turn input/policy/source/layout/metadata read failures into JSON command envelopes on stdout.
- Modify `crates/dediren-cli/tests/cli_validate.rs`
  - Add coverage for missing `--input` files returning JSON envelopes.
- Modify `crates/dediren-cli/tests/cli_render.rs`
  - Add coverage for missing render policy and metadata files returning JSON envelopes.
- Modify `crates/dediren-cli/tests/cli_export.rs`
  - Add coverage for missing export source/layout/policy files returning JSON envelopes.
- Create `docs/agent-usage.md`
  - Source copy of the shipped agent JSON authoring manual: authored-vs-generated artifact map, minimal source and policy skeletons, ArchiMate profile example, layout preference example, fixture/schema crosswalk, validation/repair loop, command handoff rules, runtime probes, and docs map.
- Modify `README.md`
  - Link the agent guide near the top.
  - Keep the install section current.
  - Add or link a full unpacked-bundle smoke workflow.
  - Clarify that downstream commands accept either raw artifact JSON or previous command envelopes.
- Modify `xtask/src/main.rs`
  - Copy `docs/agent-usage.md` into the distribution archive under `docs/`.
  - Add `docs_dir` to `bundle.json` so agents can discover shipped docs.
- Modify `xtask/tests/dist.rs`
  - Assert the fake distribution build includes `docs/agent-usage.md` and `bundle.json.docs_dir`.
- Modify `schemas/bundle.schema.json`
  - Add required `docs_dir` metadata.
- Modify version surfaces because CLI behavior and public diagnostics are shipping behavior:
  - `Cargo.toml`
  - `Cargo.lock`
  - `schemas/bundle.schema.json`
  - `fixtures/plugins/*.manifest.json`
  - `fixtures/source/*.json` `required_plugins[].version`
  - `crates/dediren-contracts/tests/schema_contracts.rs`
  - `README.md` bundle examples

Use patch version `0.11.1`: this is a compatible bug fix to an advertised agent contract, not a schema-family change.

---

### Task 1: Add Failing CLI Preflight Envelope Tests

**Files:**
- Modify: `crates/dediren-cli/tests/cli_validate.rs`
- Modify: `crates/dediren-cli/tests/cli_render.rs`
- Modify: `crates/dediren-cli/tests/cli_export.rs`

- [ ] **Step 1: Add missing validate input coverage**

Append this test to `crates/dediren-cli/tests/cli_validate.rs`:

```rust
#[test]
fn validate_missing_input_file_returns_json_envelope() {
    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(workspace_file("fixtures/source/missing-source.json"))
        .assert()
        .failure()
        .get_output()
        .clone();

    assert!(
        output.stderr.is_empty(),
        "preflight failures should be JSON on stdout, stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_error_code(&output.stdout, "DEDIREN_COMMAND_INPUT_INVALID");
}
```

- [ ] **Step 2: Add missing render policy and metadata coverage**

Change the import at the top of `crates/dediren-cli/tests/cli_render.rs` from:

```rust
use common::{
    child_element, child_group_with_attr, ok_data, plugin_binary, semantic_group, svg_doc,
    workspace_file, write_render_artifact,
};
```

to:

```rust
use common::{
    assert_error_code, child_element, child_group_with_attr, ok_data, plugin_binary,
    semantic_group, svg_doc, workspace_file, write_render_artifact,
};
```

Append these tests to `crates/dediren-cli/tests/cli_render.rs`:

```rust
#[test]
fn render_missing_policy_file_returns_json_envelope() {
    let output = common::dediren_command()
        .arg("render")
        .arg("--plugin")
        .arg("svg-render")
        .arg("--policy")
        .arg(workspace_file("fixtures/render-policy/missing-policy.json"))
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/basic.json"))
        .assert()
        .failure()
        .get_output()
        .clone();

    assert!(
        output.stderr.is_empty(),
        "preflight failures should be JSON on stdout, stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_error_code(&output.stdout, "DEDIREN_COMMAND_INPUT_INVALID");
}

#[test]
fn render_missing_metadata_file_returns_json_envelope() {
    let output = common::dediren_command()
        .arg("render")
        .arg("--plugin")
        .arg("svg-render")
        .arg("--policy")
        .arg(workspace_file("fixtures/render-policy/default-svg.json"))
        .arg("--metadata")
        .arg(workspace_file("fixtures/render-metadata/missing-metadata.json"))
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/basic.json"))
        .assert()
        .failure()
        .get_output()
        .clone();

    assert!(
        output.stderr.is_empty(),
        "preflight failures should be JSON on stdout, stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_error_code(&output.stdout, "DEDIREN_COMMAND_INPUT_INVALID");
}
```

- [ ] **Step 3: Add missing export file coverage**

Append these tests to `crates/dediren-cli/tests/cli_export.rs`:

```rust
#[test]
fn export_missing_source_file_returns_json_envelope() {
    let output = common::dediren_command()
        .arg("export")
        .arg("--plugin")
        .arg("archimate-oef")
        .arg("--policy")
        .arg(workspace_file("fixtures/export-policy/default-oef.json"))
        .arg("--source")
        .arg(workspace_file("fixtures/source/missing-archimate.json"))
        .arg("--layout")
        .arg(workspace_file(
            "fixtures/layout-result/archimate-oef-basic.json",
        ))
        .assert()
        .failure()
        .get_output()
        .clone();

    assert!(
        output.stderr.is_empty(),
        "preflight failures should be JSON on stdout, stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_error_code(&output.stdout, "DEDIREN_COMMAND_INPUT_INVALID");
}

#[test]
fn export_missing_policy_file_returns_json_envelope() {
    let output = common::dediren_command()
        .arg("export")
        .arg("--plugin")
        .arg("archimate-oef")
        .arg("--policy")
        .arg(workspace_file("fixtures/export-policy/missing-oef-policy.json"))
        .arg("--source")
        .arg(workspace_file("fixtures/source/valid-archimate-oef.json"))
        .arg("--layout")
        .arg(workspace_file(
            "fixtures/layout-result/archimate-oef-basic.json",
        ))
        .assert()
        .failure()
        .get_output()
        .clone();

    assert!(
        output.stderr.is_empty(),
        "preflight failures should be JSON on stdout, stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_error_code(&output.stdout, "DEDIREN_COMMAND_INPUT_INVALID");
}
```

- [ ] **Step 4: Run failing tests**

Run:

```bash
cargo test -p dediren --test cli_validate validate_missing_input_file_returns_json_envelope -- --exact
cargo test -p dediren --test cli_render render_missing_policy_file_returns_json_envelope -- --exact
cargo test -p dediren --test cli_render render_missing_metadata_file_returns_json_envelope -- --exact
cargo test -p dediren --test cli_export export_missing_source_file_returns_json_envelope -- --exact
cargo test -p dediren --test cli_export export_missing_policy_file_returns_json_envelope -- --exact
```

Expected: each test fails because stdout is empty/non-JSON and stderr contains an `anyhow` error.

- [ ] **Step 5: Commit failing tests**

```bash
git add crates/dediren-cli/tests/cli_validate.rs crates/dediren-cli/tests/cli_render.rs crates/dediren-cli/tests/cli_export.rs
git commit -m "test: capture CLI preflight envelope contract"
```

---

### Task 2: Normalize CLI Preflight Read Failures

**Files:**
- Modify: `crates/dediren-cli/src/main.rs`

- [ ] **Step 1: Add helper functions**

In `crates/dediren-cli/src/main.rs`, add these helpers near `input_base_dir`:

```rust
fn read_json_input_or_exit(path: Option<&str>, label: &str) -> anyhow::Result<String> {
    match dediren_core::io::read_json_input(path) {
        Ok(text) => Ok(text),
        Err(error) => print_command_input_error(label, path, &error),
    }
}

fn read_file_or_exit(path: &str, label: &str) -> anyhow::Result<String> {
    match std::fs::read_to_string(path) {
        Ok(text) => Ok(text),
        Err(error) => print_command_input_error(label, Some(path), &error),
    }
}

fn print_command_input_error(
    label: &str,
    path: Option<&str>,
    error: &dyn std::fmt::Display,
) -> anyhow::Result<String> {
    let diagnostic_path = path
        .map(|path| format!("{label}:{path}"))
        .unwrap_or_else(|| label.to_string());
    let envelope = dediren_contracts::CommandEnvelope::<serde_json::Value>::error(vec![
        dediren_contracts::Diagnostic {
            code: "DEDIREN_COMMAND_INPUT_INVALID".to_string(),
            severity: dediren_contracts::DiagnosticSeverity::Error,
            message: format!("failed to read {label}: {error}"),
            path: Some(diagnostic_path),
        },
    ]);
    println!("{}", serde_json::to_string(&envelope)?);
    std::process::exit(2);
}
```

- [ ] **Step 2: Replace raw `?` reads in command handlers**

In `main`, replace each preflight read:

```rust
let text = dediren_core::io::read_json_input(input.as_deref())?;
```

with:

```rust
let text = read_json_input_or_exit(input.as_deref(), "input")?;
```

Replace render reads:

```rust
let layout_text = dediren_core::io::read_json_input(input.as_deref())?;
let policy_text = std::fs::read_to_string(policy)?;
let metadata_text = metadata
    .as_deref()
    .map(std::fs::read_to_string)
    .transpose()?;
```

with:

```rust
let layout_text = read_json_input_or_exit(input.as_deref(), "input")?;
let policy_text = read_file_or_exit(&policy, "policy")?;
let metadata_text = metadata
    .as_deref()
    .map(|path| read_file_or_exit(path, "metadata"))
    .transpose()?;
```

Replace export reads:

```rust
let source_text = std::fs::read_to_string(&source)?;
let source_base_dir = input_base_dir(Some(&source));
let policy_text = std::fs::read_to_string(policy)?;
let layout_text = std::fs::read_to_string(layout)?;
```

with:

```rust
let source_text = read_file_or_exit(&source, "source")?;
let source_base_dir = input_base_dir(Some(&source));
let policy_text = read_file_or_exit(&policy, "policy")?;
let layout_text = read_file_or_exit(&layout, "layout")?;
```

- [ ] **Step 3: Run focused preflight tests**

Run:

```bash
cargo test -p dediren --test cli_validate validate_missing_input_file_returns_json_envelope -- --exact
cargo test -p dediren --test cli_render render_missing_policy_file_returns_json_envelope -- --exact
cargo test -p dediren --test cli_render render_missing_metadata_file_returns_json_envelope -- --exact
cargo test -p dediren --test cli_export export_missing_source_file_returns_json_envelope -- --exact
cargo test -p dediren --test cli_export export_missing_policy_file_returns_json_envelope -- --exact
```

Expected: all pass.

- [ ] **Step 4: Run adjacent CLI test lanes**

Run:

```bash
cargo test -p dediren --test cli_validate
cargo test -p dediren --test cli_render
cargo test -p dediren --test cli_export
cargo test -p dediren --test cli_pipeline
```

Expected: all pass.

- [ ] **Step 5: Commit implementation**

```bash
git add crates/dediren-cli/src/main.rs
git commit -m "fix: normalize CLI preflight input failures"
```

---

### Task 3: Add The Agent Usage Guide

**Files:**
- Create: `docs/agent-usage.md`

- [ ] **Step 1: Create the guide**

Create `docs/agent-usage.md` with this content:

````markdown
# Dediren Agent Usage

This guide is optimized for agents that must author Dediren JSON quickly and
correctly. Read it before loading full schemas. Use schemas for exact validation
and fixtures for examples, but use this file to decide which JSON to write,
which JSON is generated, and how to repair failures.

## Fast Path

1. Author `model.json` using the `Minimal Source JSON` shape below.
2. Add `plugins.generic-graph.views[]` with the nodes and relationships that
   belong in each view.
3. Reuse `fixtures/render-policy/default-svg.json` unless custom SVG style is
   required.
4. Run `validate`, then `project --target layout-request`, then `layout`, then
   `validate-layout`, then `render` or `export`.
5. Read `.status` and `.diagnostics[]` from stdout JSON envelopes; do not parse
   `stderr`.

## Artifact Authoring Map

| Artifact | Agent authors it? | Schema | Best example | Notes |
| --- | --- | --- | --- | --- |
| Source model | Yes | `schemas/model.schema.json` | `fixtures/source/valid-basic.json` | Semantic graph and plugin-owned views. No geometry or styling. |
| Fragment source model | Yes, for large models | `schemas/model.schema.json` | create fragments like the Source Fragments README section | Fragments use the same source shape and need file input, not stdin. |
| SVG render policy | Usually reuse, sometimes author | `schemas/svg-render-policy.schema.json` | `fixtures/render-policy/default-svg.json`, `fixtures/render-policy/rich-svg.json` | Owns colors, typography, strokes, labels, and per-layout-id style. |
| OEF export policy | Usually reuse, sometimes author | `schemas/oef-export-policy.schema.json` | `fixtures/export-policy/default-oef.json` | Owns export metadata such as model identifier. |
| Layout preferences | Sometimes author inside a view | `schemas/model.schema.json`, projected into `schemas/layout-request.schema.json` | see `layout_preferences` below | Expresses intent, not raw ELK options. |
| Layout request | Usually generated | `schemas/layout-request.schema.json` | `fixtures/layout-request/basic.json` | Generate with `project --target layout-request`; hand-author only for plugin tests. |
| Render metadata | Usually generated | `schemas/render-metadata.schema.json` | `fixtures/render-metadata/archimate-basic.json` | Generate with `project --target render-metadata` for ArchiMate SVG notation. |
| Layout result | No | `schemas/layout-result.schema.json` | `fixtures/layout-result/basic.json` | Generated by `layout`; contains geometry and routes. |
| Render/export result | No | `schemas/render-result.schema.json`, `schemas/export-result.schema.json` | command stdout | Extract SVG/OEF XML from `.data.content`. |

## Minimal Source JSON

Use this as the smallest useful source model. Replace ids and labels; keep ids
stable because layout, render metadata, diagnostics, and repair loops refer to
them.

```json
{
  "model_schema_version": "model.schema.v1",
  "required_plugins": [
    { "id": "generic-graph", "version": "0.11.1" }
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
          "title": "Main",
          "nodes": ["client", "api"],
          "relationships": ["client-calls-api"],
          "groups": []
        }
      ]
    }
  }
}
```

Do not put `x`, `y`, `width`, `height`, colors, fonts, or SVG shape choices in
source JSON. Source JSON is semantic. Layout results contain generated geometry.
Render policy contains presentation.

## ArchiMate Source JSON

For ArchiMate SVG notation or OEF export, set the generic graph semantic profile
and use ArchiMate type names:

```json
{
  "model_schema_version": "model.schema.v1",
  "required_plugins": [
    { "id": "generic-graph", "version": "0.11.1" },
    { "id": "archimate-oef", "version": "0.11.1" }
  ],
  "nodes": [
    {
      "id": "orders-component",
      "type": "ApplicationComponent",
      "label": "Orders Component",
      "properties": {}
    },
    {
      "id": "orders-service",
      "type": "ApplicationService",
      "label": "Orders Service",
      "properties": {}
    }
  ],
  "relationships": [
    {
      "id": "orders-realizes-service",
      "type": "Realization",
      "source": "orders-component",
      "target": "orders-service",
      "label": "realizes",
      "properties": {}
    }
  ],
  "plugins": {
    "generic-graph": {
      "semantic_profile": "archimate",
      "views": [
        {
          "id": "main",
          "title": "Main",
          "nodes": ["orders-component", "orders-service"],
          "relationships": ["orders-realizes-service"],
          "groups": []
        }
      ]
    }
  }
}
```

Use the ArchiMate/OEF element name `Node` for technology nodes. Do not use
aliases such as `TechnologyNode`.

## Layout Preferences

Put optional layout intent on `plugins.generic-graph.views[]`:

```json
{
  "layout_preferences": {
    "direction": "right",
    "density": "readable",
    "wrapping": "auto",
    "routing": {
      "style": "orthogonal",
      "profile": "readable",
      "endpoint_merging": "local"
    }
  }
}
```

Supported directions are `right`, `left`, `down`, and `up`. Supported density
and routing profiles are `compact`, `readable`, and `spacious`. Supported
wrapping values are `auto`, `off`, and `multi-edge`. Supported endpoint merging
values are `auto`, `local`, and `off`.

## JSON Authoring Loop

```bash
dediren validate --input model.json

dediren project --target layout-request --plugin generic-graph --view main \
  --input model.json > layout-request.json

dediren layout --plugin elk-layout --input layout-request.json > layout-result.json

dediren validate-layout --input layout-result.json

dediren render --plugin svg-render --policy fixtures/render-policy/default-svg.json \
  --input layout-result.json > render-result.json

jq -r '.data.content' render-result.json > diagram.svg
```

For ArchiMate SVG notation, generate metadata before render:

```bash
dediren project --target render-metadata --plugin generic-graph --view main \
  --input model.json > render-metadata.json

dediren render --plugin svg-render --policy fixtures/render-policy/archimate-svg.json \
  --metadata render-metadata.json \
  --input layout-result.json > render-result.json
```

For OEF export:

```bash
dediren export --plugin archimate-oef \
  --policy fixtures/export-policy/default-oef.json \
  --source model.json \
  --layout layout-result.json > oef-export-result.json
```

## Contract Map

| Need | Source |
| --- | --- |
| Fast authoring contract | this file |
| Exact JSON shape | `schemas/*.json` |
| Small copyable examples | `fixtures/` |
| Broader workflow overview | `README.md` |
| Plugin ids, executables, static capabilities | `fixtures/plugins/*.manifest.json` or bundle `plugins/*.manifest.json` |
| Runtime plugin support | `<plugin-binary> capabilities` |
| Success/failure decisions | command stdout JSON envelope |

`docs/superpowers/` is implementation planning and history. Treat live code,
schemas, fixtures, README, and this guide as the current user-facing contract
when they disagree with old plans.

## Command Handoff Rules

| Command | Primary input | Output envelope `.data` | Downstream handoff |
| --- | --- | --- | --- |
| `validate` | source model | assembled source model | inspect diagnostics and stop on error |
| `project --target layout-request` | source model | layout request | pass whole envelope or raw `.data` to `layout` |
| `project --target render-metadata` | source model | render metadata | pass whole envelope or raw `.data` to `render --metadata` |
| `layout` | layout request | layout result | pass whole envelope or raw `.data` to `validate-layout`, `render`, or `export --layout` |
| `validate-layout` | layout result | quality report | inspect counts and warnings |
| `render` | layout result plus policy | render result | extract SVG from `.data.content` |
| `export` | source, layout result, export policy | export result | extract OEF XML from `.data.content` |

Commands that consume generated artifacts accept either the raw artifact JSON or
the previous command envelope. This makes redirect-based pipelines safe:

```bash
dediren project --target layout-request --plugin generic-graph --view main \
  --input fixtures/source/valid-basic.json > layout-request.json

DEDIREN_ELK_RESULT_FIXTURE=fixtures/layout-result/basic.json \
  dediren layout --plugin elk-layout --input layout-request.json > layout-result.json

dediren render --plugin svg-render --policy fixtures/render-policy/default-svg.json \
  --input layout-result.json > render-result.json

jq -r '.data.content' render-result.json > diagram.svg
```

## Runtime Probes

From a source checkout:

```bash
dediren --version
target/debug/dediren-plugin-generic-graph capabilities
target/debug/dediren-plugin-elk-layout capabilities
target/debug/dediren-plugin-svg-render capabilities
target/debug/dediren-plugin-archimate-oef-export capabilities
```

From an unpacked distribution bundle:

```bash
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-0.11.1-x86_64-unknown-linux-gnu
"$BUNDLE/bin/dediren" --version
"$BUNDLE/bin/dediren-plugin-generic-graph" capabilities
"$BUNDLE/bin/dediren-plugin-elk-layout" capabilities
"$BUNDLE/bin/dediren-plugin-svg-render" capabilities
"$BUNDLE/bin/dediren-plugin-archimate-oef-export" capabilities
```

Capability output is raw JSON using `schemas/runtime-capability.schema.json`.
CLI workflow commands return command envelopes using `schemas/envelope.schema.json`.

## Bundle Smoke Workflow

```bash
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-0.11.1-x86_64-unknown-linux-gnu

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

The real `elk-layout` path needs Java 21 or newer as `java` on `PATH`. For a
deterministic no-Java repair loop in a source checkout, set
`DEDIREN_ELK_RESULT_FIXTURE=fixtures/layout-result/basic.json`.

## Repair Map

Every CLI workflow command should emit a JSON command envelope on stdout for
success and failure. Agents should decide from:

```bash
jq -r '.status' result.json
jq -r '.diagnostics[]?.code' result.json
```

Common recovery signals:

| Diagnostic | Likely JSON repair |
| --- | --- |
| `DEDIREN_COMMAND_INPUT_INVALID` | Check file path, readable file, or stdin content. |
| `DEDIREN_SCHEMA_INVALID` | Fix required fields and field types against the matching schema. |
| `DEDIREN_DUPLICATE_ID` | Make source node, relationship, group, and fragment ids unique after assembly. |
| `DEDIREN_DANGLING_ENDPOINT` | Fix `relationships[].source` or `relationships[].target` to reference existing node ids. |
| `DEDIREN_FRAGMENT_BASE_DIR_REQUIRED` | Use `--input <file>` instead of stdin for fragmented models. |
| `DEDIREN_SEMANTIC_PROFILE_REQUIRED` | Add `plugins.generic-graph.semantic_profile`, usually `archimate` for ArchiMate metadata. |
| `DEDIREN_RENDER_METADATA_REQUIRED` | Generate render metadata or use a policy that does not require semantic metadata. |
| `DEDIREN_PLUGIN_UNKNOWN` | Check plugin id and explicit plugin discovery paths. |
| `DEDIREN_PLUGIN_MISSING_EXECUTABLE` | Check the manifest executable and bundle/source binary location. |
| `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY` | Probe plugin capabilities and choose a plugin that supports the command. |
| `DEDIREN_ELK_RUNTIME_UNAVAILABLE` | Build/provide the helper or use the distribution bundle. |
| `DEDIREN_ELK_JAVA_UNAVAILABLE` | Put Java 21 or newer on `PATH`. |
| `DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED` | Replace node `type` with a supported ArchiMate element type. |
| `DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED` | Replace relationship `type` with a supported ArchiMate relationship type. |
| `DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED` | Fix ArchiMate source/target type legality. |

`stderr` is for human debugging. Do not parse it for workflow decisions.
````

- [ ] **Step 2: Check the guide for concrete probes**

Run:

```bash
rg -n "Fast Path|Artifact Authoring Map|Minimal Source JSON|JSON Authoring Loop|Repair Map|Runtime Probes|docs/superpowers" docs/agent-usage.md
```

Expected: all headings and the historical-docs warning are present.

- [ ] **Step 3: Commit the guide**

```bash
git add docs/agent-usage.md
git commit -m "docs: add agent usage guide"
```

---

### Task 4: Update README For Agent Discovery

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add an agent-guide link near the product surface**

After the product-surface bullet list, add:

```markdown
For a token-efficient agent JSON authoring guide, see `docs/agent-usage.md`.
It maps which JSON agents author, which JSON Dediren generates, minimal source
and policy skeletons, fixture/schema references, validation commands, repair
diagnostics, runtime probes, and bundle smoke workflow. Design plans under
`docs/superpowers/` are implementation history, not the current user-facing
contract.
```

- [ ] **Step 2: Clarify envelope handoff and artifact extraction**

Replace the sentence after the first render example:

```markdown
`render-result.json` is a command envelope. The SVG text is in
`.data.content`; there is no raw-output mode yet.
```

with:

```markdown
`project`, `layout`, `render`, and `export` write JSON command envelopes.
Downstream commands that consume generated artifacts accept either the full
envelope or raw `.data` artifact JSON, so redirecting one command into the next
is valid. Extract rendered SVG with:

```bash
jq -r '.data.content' render-result.json > diagram.svg
```
```

- [ ] **Step 3: Add unpacked-bundle workflow pointer**

After the unpacked `--help` command in the install section, add:

```markdown
For a full unpacked-bundle JSON authoring and project/layout/render smoke
workflow, use the `JSON Authoring Loop` and `Bundle Smoke Workflow` sections in
`docs/agent-usage.md`.
```

- [ ] **Step 4: Add runtime capability probe examples**

In the Plugins section after the bundled plugin table, add:

```markdown
After authoring JSON, agents can inspect runtime plugin support directly:

```bash
dediren-plugin-generic-graph capabilities
dediren-plugin-elk-layout capabilities
dediren-plugin-svg-render capabilities
dediren-plugin-archimate-oef-export capabilities
```

The capability JSON uses `schemas/runtime-capability.schema.json`.
```

- [ ] **Step 5: Verify README references**

Run:

```bash
rg -n "docs/agent-usage.md|JSON authoring|Minimal Source JSON|full envelope or raw \\.data|capabilities|Bundle Smoke Workflow" README.md
```

Expected: README links the authoring guide, states the handoff rule, shows capability probes, and points to the bundle smoke workflow.

- [ ] **Step 6: Commit README docs**

```bash
git add README.md
git commit -m "docs: improve agent-facing README discovery"
```

---

### Task 5: Ship The Agent Guide In The Distribution Archive

**Files:**
- Modify: `xtask/src/main.rs`
- Modify: `xtask/tests/dist.rs`
- Modify: `schemas/bundle.schema.json`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`

- [ ] **Step 1: Copy agent docs into the bundle**

In `xtask/src/main.rs`, after:

```rust
fs::create_dir_all(bundle_dir.join("runtimes")).context("create bundle runtimes directory")?;
```

add:

```rust
fs::create_dir_all(bundle_dir.join("docs")).context("create bundle docs directory")?;
```

After:

```rust
copy_fixture_dirs(root, &bundle_dir.join("fixtures"))?;
```

add:

```rust
copy_agent_docs(root, &bundle_dir.join("docs"))?;
```

Add this helper near `copy_fixture_dirs`:

```rust
fn copy_agent_docs(root: &Path, destination: &Path) -> Result<()> {
    fs::copy(
        root.join("docs/agent-usage.md"),
        destination.join("agent-usage.md"),
    )
    .context("copy agent usage guide")?;
    Ok(())
}
```

- [ ] **Step 2: Advertise docs in bundle metadata**

In `write_bundle_metadata`, change:

```rust
"schemas_dir": "schemas",
"fixtures_dir": "fixtures",
"elk_helper": "runtimes/elk-layout-java/bin/dediren-elk-layout-java"
```

to:

```rust
"schemas_dir": "schemas",
"fixtures_dir": "fixtures",
"docs_dir": "docs",
"elk_helper": "runtimes/elk-layout-java/bin/dediren-elk-layout-java"
```

- [ ] **Step 3: Update bundle schema**

In `schemas/bundle.schema.json`, add `"docs_dir"` to the `required` array between `"fixtures_dir"` and `"elk_helper"`, then add this property after `fixtures_dir`:

```json
"docs_dir": { "type": "string", "minLength": 1 },
```

- [ ] **Step 4: Update schema contract bundle fixture**

In `crates/dediren-contracts/tests/schema_contracts.rs`, update the bundle metadata fixture used by the bundle schema test so it contains:

```json
"docs_dir": "docs",
```

Keep the existing schema id `dediren-bundle.schema.v1`; adding a metadata field is a product version change, not a schema-family rename.

- [ ] **Step 5: Add dist test source doc fixture**

In `xtask/tests/dist.rs`, inside `FakeDistRepo::write_tree`, after:

```rust
fs::create_dir_all(self.root.path().join("schemas")).unwrap();
```

add:

```rust
fs::create_dir_all(self.root.path().join("docs")).unwrap();
```

After:

```rust
fs::write(self.root.path().join("schemas/schema.json"), "{}").unwrap();
```

add:

```rust
fs::write(
    self.root.path().join("docs/agent-usage.md"),
    "# Dediren Agent Usage\n\nBundle-visible agent guide.\n",
)
.unwrap();
```

- [ ] **Step 6: Add dist archive assertions**

In `xtask/tests/dist.rs`, append this test after `dist_build_prunes_stale_bundle_artifacts`:

```rust
#[cfg(unix)]
#[test]
fn dist_build_includes_agent_usage_docs() {
    let repo = FakeDistRepo::new();
    repo.release_helper_build();
    let output = repo.xtask_command(["dist", "build"]).output().unwrap();

    assert!(
        output.status.success(),
        "dist build should pass\nstatus: {:?}\nstdout:\n{}\nstderr:\n{}",
        output.status.code(),
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );

    let bundle = repo.root.path().join(format!(
        "dist/dediren-agent-bundle-{}-x86_64-unknown-linux-gnu",
        env!("CARGO_PKG_VERSION")
    ));
    let guide = bundle.join("docs/agent-usage.md");
    assert!(
        guide.exists(),
        "dist build should include docs/agent-usage.md"
    );
    let metadata = fs::read_to_string(bundle.join("bundle.json")).unwrap();
    assert!(
        metadata.contains("\"docs_dir\": \"docs\""),
        "bundle metadata should advertise docs_dir: {metadata}"
    );
}
```

- [ ] **Step 7: Run focused dist tests**

Run:

```bash
cargo test -p xtask --test dist dist_build_includes_agent_usage_docs -- --exact
cargo test -p xtask --test dist dist_build_prunes_stale_bundle_artifacts -- --exact
cargo test -p xtask --test dist dist_smoke_runs_bundle_pipeline_with_clean_environment -- --exact
cargo test -p dediren-contracts --test schema_contracts bundle_metadata_matches_schema -- --exact
```

Expected: all pass.

- [ ] **Step 8: Commit distribution docs shipping**

```bash
git add xtask/src/main.rs xtask/tests/dist.rs schemas/bundle.schema.json crates/dediren-contracts/tests/schema_contracts.rs
git commit -m "feat: ship agent usage guide in distribution"
```

---

### Task 6: Add Downstream Skill Sync Requirement

**Files:**
- Modify: `README.md`
- Modify: `docs/agent-usage.md`

- [ ] **Step 1: Add README note for skill-packaged users**

In `README.md`, near the install/archive description, add:

```markdown
Skill packages that bundle Dediren should preserve the archive's
`docs/agent-usage.md` file or embed the same JSON authoring contract in the
skill guidance. Do not rely on this source repository README being present at
runtime.
```

- [ ] **Step 2: Add guide note for downstream packagers**

In `docs/agent-usage.md`, after the opening paragraph, add:

```markdown
If this guide is consumed through a skill package, the package should either
ship this file alongside the Dediren bundle or copy the same JSON authoring
contract into the skill instructions. The source repository README is not
assumed to be available to runtime users.
```

- [ ] **Step 3: Add implementation handoff requirement**

In the final implementation handoff, explicitly state whether the downstream
skill bundle was updated. If the implementation only updates `dediren`, say:

```text
Downstream skill package sync was not performed in this repo. The new Dediren
bundle ships docs/agent-usage.md with the JSON authoring contract; update any
skill package that strips bundle docs before publishing it.
```

- [ ] **Step 4: Commit downstream packaging docs**

```bash
git add README.md docs/agent-usage.md
git commit -m "docs: document skill-packaged agent guide requirement"
```

---

### Task 7: Bump Patch Version Surfaces To 0.11.1

**Files:**
- Modify: `Cargo.toml`
- Modify: `Cargo.lock`
- Modify: `schemas/bundle.schema.json`
- Modify: `fixtures/plugins/archimate-oef.manifest.json`
- Modify: `fixtures/plugins/elk-layout.manifest.json`
- Modify: `fixtures/plugins/generic-graph.manifest.json`
- Modify: `fixtures/plugins/svg-render.manifest.json`
- Modify: `fixtures/source/valid-basic.json`
- Modify: `fixtures/source/valid-pipeline-rich.json`
- Modify: `fixtures/source/valid-pipeline-archimate.json`
- Modify: `fixtures/source/valid-archimate-oef.json`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`
- Modify: `README.md`
- Modify: `docs/agent-usage.md`

- [ ] **Step 1: Update workspace version**

Change `Cargo.toml`:

```toml
[workspace.package]
version = "0.11.1"
```

- [ ] **Step 2: Regenerate lockfile package versions**

Run:

```bash
cargo metadata --locked --format-version=1 >/tmp/dediren-metadata.json
```

Expected: this fails because `Cargo.lock` still has `0.11.0` workspace package entries.

Run:

```bash
cargo metadata --format-version=1 >/tmp/dediren-metadata.json
```

Expected: command succeeds and updates `Cargo.lock` package entries to `0.11.1`.

- [ ] **Step 3: Update plugin manifests**

In each `fixtures/plugins/*.manifest.json`, change:

```json
"version": "0.11.0"
```

to:

```json
"version": "0.11.1"
```

- [ ] **Step 4: Update source fixture plugin requirements**

In `fixtures/source/valid-basic.json`, `fixtures/source/valid-pipeline-rich.json`, `fixtures/source/valid-pipeline-archimate.json`, and `fixtures/source/valid-archimate-oef.json`, change every:

```json
"version": "0.11.0"
```

to:

```json
"version": "0.11.1"
```

- [ ] **Step 5: Update schema contract test fixture version**

In `crates/dediren-contracts/tests/schema_contracts.rs`, change the source model fixture expectation:

```rust
{ "id": "generic-graph", "version": "0.11.1" }
```

- [ ] **Step 6: Update README and agent-guide bundle examples**

Replace product bundle examples in `README.md` and `docs/agent-usage.md`:

```text
0.11.0
```

with:

```text
0.11.1
```

Do not change ELK third-party dependency versions such as `org.eclipse.elk.* 0.11.0`; those are Maven dependency versions, not Dediren product versions.

- [ ] **Step 7: Verify version surface**

Run:

```bash
rg -n '"version": "0.11.0"|dediren-agent-bundle-0.11.0|Dediren 0.11.0|version = "0.11.0"' Cargo.toml Cargo.lock README.md docs/agent-usage.md schemas/bundle.schema.json fixtures crates/dediren-contracts/tests/schema_contracts.rs
```

Expected: no matches except unrelated third-party ELK dependency strings if the search scope is widened beyond these files.

- [ ] **Step 8: Commit version bump**

```bash
git add Cargo.toml Cargo.lock README.md docs/agent-usage.md schemas/bundle.schema.json fixtures/plugins/archimate-oef.manifest.json fixtures/plugins/elk-layout.manifest.json fixtures/plugins/generic-graph.manifest.json fixtures/plugins/svg-render.manifest.json fixtures/source/valid-basic.json fixtures/source/valid-pipeline-rich.json fixtures/source/valid-pipeline-archimate.json fixtures/source/valid-archimate-oef.json crates/dediren-contracts/tests/schema_contracts.rs
git commit -m "chore: bump agent docs fix release to 0.11.1"
```

---

### Task 8: Verification And Audit

**Files:**
- No new files.
- Verify all touched files.

- [ ] **Step 1: Run formatting**

```bash
cargo fmt --all -- --check
```

Expected: success.

- [ ] **Step 2: Run focused contract and CLI tests**

```bash
cargo test -p dediren-contracts --test schema_contracts
cargo test -p dediren --test cli_validate
cargo test -p dediren --test cli_render
cargo test -p dediren --test cli_export
cargo test -p dediren --test cli_pipeline
cargo test -p dediren --test plugin_compat
cargo test -p xtask --test dist
```

Expected: all pass.

- [ ] **Step 3: Run workspace tests**

```bash
cargo test --workspace --locked
```

Expected: all non-ignored tests pass.

- [ ] **Step 4: Run distribution build and smoke**

From a shell where `java -version` resolves to Java 21 or newer:

```bash
cargo xtask dist build
cargo xtask dist smoke dist/dediren-agent-bundle-0.11.1-x86_64-unknown-linux-gnu.tar.gz
```

Expected: both pass, `dist/` contains only the current `0.11.1` bundle directory and tarball, and `dist/dediren-agent-bundle-0.11.1-x86_64-unknown-linux-gnu/docs/agent-usage.md` exists. Do not stage `dist/`; it is ignored generated output.

- [ ] **Step 5: Run docs checks**

```bash
git diff --check
rg -n "docs/agent-usage.md|JSON authoring|Artifact Authoring Map|Minimal Source JSON|Bundle Smoke Workflow|full envelope or raw \\.data|capabilities|skill package" README.md docs/agent-usage.md
```

Expected: whitespace check passes and the authoring guide, key authoring sections, links, and runtime probes are discoverable.

- [ ] **Step 6: Run audit validations required by this plan**

Use `souroldgeezer-audit:test-quality-audit` in Quick mode over:

```text
crates/dediren-cli/tests/cli_validate.rs
crates/dediren-cli/tests/cli_render.rs
crates/dediren-cli/tests/cli_export.rs
xtask/tests/dist.rs
```

Acceptance: no block findings. Fix warn/info findings unless the handoff explicitly accepts them.

Use `souroldgeezer-audit:devsecops-audit` in Quick mode over:

```text
crates/dediren-cli/src/main.rs
xtask/src/main.rs
schemas/bundle.schema.json
README.md
docs/agent-usage.md
```

Acceptance: no block findings. Focus on process-boundary behavior, stdout/stderr contract, JSON authoring clarity, external Java/runtime guidance, and artifact handling.

- [ ] **Step 7: Review diff and status**

```bash
git diff --stat
git diff -- README.md docs/agent-usage.md crates/dediren-cli/src/main.rs xtask/src/main.rs xtask/tests/dist.rs schemas/bundle.schema.json
git status --short --branch
```

Expected: only intentional files are modified, and ignored generated outputs are not staged.

- [ ] **Step 8: Commit verification adjustments if any**

If verification required small fixes, stage only the intentional changed files shown by `git status --short`, then commit:

```bash
git commit -m "fix: address agent docs verification findings"
```

Skip this commit if no changes were needed.

---

## Self-Review

- Spec coverage:
  - JSON preflight mismatch: Task 1 and Task 2.
  - Token-efficient documentation for agents authoring source/policy JSON: Task 3 and Task 4.
  - Authored-vs-generated artifact clarity: Task 3.
  - Minimal JSON skeletons for generic and ArchiMate source models: Task 3.
  - Fixture/schema crosswalk for authored JSON: Task 3.
  - JSON repair loop and diagnostic-to-field guidance: Task 3.
  - Agent discovery beyond `--help`: Task 3, Task 4, and Task 5.
  - Skill-packaged users cannot read repo-only Markdown: Task 5 and Task 6.
  - Bundle first-run workflow: Task 3, Task 4, and Task 5.
  - Envelope/raw `.data` handoff: Task 3 and Task 4.
  - Capability probes: Task 3 and Task 4.
  - Stale plan confusion: Task 3 and Task 4.
  - Versioning rules for behavior/docs change: Task 7.
  - Verification and audit gates: Task 8.
- Placeholder scan: no undefined implementation placeholders remain.
- Type consistency:
  - Uses existing `DEDIREN_COMMAND_INPUT_INVALID`.
  - Uses existing `CommandEnvelope`, `Diagnostic`, and `DiagnosticSeverity` types.
  - Uses existing CLI test helpers `assert_error_code`, `workspace_file`, and `common::dediren_command`.

Plan complete. Execute with subagent-driven development or executing-plans; keep commits scoped to each task and do not stage ignored `dist/` or `.test-output/` artifacts.
