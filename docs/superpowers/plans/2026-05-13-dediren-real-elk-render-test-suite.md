# Real ELK Render Test Suite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the layout/render test set visibly real-ELK-heavy while keeping fixture-backed tests lean, clearly named, and limited to deterministic contract seams.

**Architecture:** Add a dedicated ignored CLI integration suite for `project -> real Java ELK helper -> validate-layout -> render` and write its artifacts under `.test-output/renders/real-elk`. Rename or group fixture-backed tests so a reader can distinguish `real_elk_*`, `fixture_elk_*`, and `fake_elk_*` lanes from test names, file names, artifact paths, and the lane matrix. Prefer real ELK for layout/render confidence; keep fixtures for deterministic contract/error seams and exhaustive semantic coverage such as ArchiMate node decorators, relationship markers, and relationship-rule legality.

**Tech Stack:** Rust workspace, Cargo integration tests, `assert_cmd`, `assert_fs`, `serde_json`, `roxmltree`, first-party CLI plugins, SDKMAN/Gradle Java ELK helper invoked through `DEDIREN_ELK_COMMAND`.

---

## Current Problem

The previous cleanup improved parsed assertions and test organization, but it did not sufficiently change the evidence balance:

- Real ELK render confidence is still concentrated in one ignored pipeline test.
- Fixture-backed CLI pipeline tests still look like broad render confidence unless the reader opens the body.
- Render artifacts do not make the real-vs-fixture distinction obvious enough.
- Some ELK adapter tests use names that distinguish runtime mechanics, but the suite has no consistent lane vocabulary.
- Fixture tests are still valuable for enumerative semantic coverage, especially ArchiMate node/relationship renderer cases and relationship-rule legality. They should not be treated as proof that ELK generated good geometry.

This plan makes the distinction explicit in four places:

| Lane | Meaning | Test Name Prefix | Artifact Group |
| --- | --- | --- | --- |
| Real ELK | Invokes `crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh` through `DEDIREN_ELK_COMMAND` | `real_elk_` | `.test-output/renders/real-elk/` |
| Fixture ELK | Uses `DEDIREN_ELK_RESULT_FIXTURE` or a static `fixtures/layout-result/*.json` as the layout result | `fixture_elk_` or `fixture_pipeline_` | `.test-output/renders/fixture-pipeline/` where rendered |
| Fake ELK | Uses a shell helper to simulate runtime output, invalid JSON, or external failure | `fake_elk_` | none |
| Semantic/Renderer Fixture | Tests SVG policy details, ArchiMate node decorators, ArchiMate relationship markers, and relationship-rule legality from deterministic fixtures; not evidence of ELK geometry | existing SVG renderer and ArchiMate rule module names | `.test-output/renders/svg-render-plugin/` where rendered |

Do not treat source fixtures like `fixtures/source/valid-pipeline-rich.json` as a problem. The target reduction is static layout-result fixtures standing in for real ELK output in render-confidence tests.
Do not reduce ArchiMate node/relationship fixture coverage just to lower fixture counts; that coverage is intentionally matrix-like and deterministic.

## File Structure

- Create `crates/dediren-cli/tests/real_elk_render.rs`
  - Owns ignored real-helper render tests.
  - Contains a per-binary mutex so real helper calls inside this test binary are serialized even when a runner forgets `--test-threads=1`.
  - Writes SVG artifacts to `.test-output/renders/real-elk/`.
- Modify `crates/dediren-cli/tests/cli_pipeline.rs`
  - Keep one lean fixture-backed CLI smoke test.
  - Rename the fixture artifact group from `cli-pipeline` to `fixture-pipeline`.
  - Remove the fixture-backed ArchiMate notation render test after the real ELK ArchiMate test exists.
- Preserve `crates/dediren-plugin-svg-render/tests/svg_render_plugin/archimate_nodes.rs`, `crates/dediren-plugin-svg-render/tests/svg_render_plugin/archimate_relationships.rs`, and `crates/dediren-archimate/tests/relationship_rules.rs` as fixture/table-driven semantic coverage.
- Modify `crates/dediren-cli/tests/cli_layout.rs`
  - Rename tests so real, fixture, and fake ELK lanes are obvious from test names.
- Modify `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`
  - Rename wrapper tests with the same `real_elk_`, `fixture_elk_`, and `fake_elk_` vocabulary.
- Modify `docs/superpowers/plans/2026-05-12-dediren-test-quality-gap-matrix.md`
  - Update the matrix so it points to the new real ELK suite and lane vocabulary.
- Modify `AGENTS.md`
  - Update ignored real-helper verification command names if test functions are renamed.

No README or version bump is required because this changes test organization and plan documentation only. If implementation changes public CLI behavior, plugin manifests, schemas, runtime output, or distribution contents, stop and add the required version/README work.

---

### Task 1: Create A Dedicated Real ELK Render Suite

**Files:**
- Create: `crates/dediren-cli/tests/real_elk_render.rs`

- [ ] **Step 1: Add the suite scaffold and first real-helper render test**

Create `crates/dediren-cli/tests/real_elk_render.rs` with this content:

```rust
mod common;

use assert_fs::prelude::*;
use common::{
    assert_reasonable_svg_aspect, assert_svg_texts_include, child_element, child_group_with_attr,
    ok_data, plugin_binary, semantic_group, svg_doc, workspace_file, write_render_artifact,
};
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};

static REAL_ELK_LOCK: Mutex<()> = Mutex::new(());

#[test]
#[ignore = "requires SDKMAN Java helper build; real ELK helper runs are serialized"]
fn real_elk_renders_basic_projected_graph() {
    let _guard = real_elk_guard();
    let temp = assert_fs::TempDir::new().unwrap();

    let request_output = project_layout_request("fixtures/source/valid-basic.json");
    let request = write_temp_bytes(&temp, "basic-layout-request.json", &request_output);

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "basic-layout-result.json", &layout_output);
    assert_layout_quality_ok(&validate_layout(&layout));

    let layout_data = ok_data(&layout_output);
    assert_eq!(
        layout_data["nodes"]
            .as_array()
            .expect("laid out nodes should be an array")
            .len(),
        2
    );
    assert_eq!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .len(),
        1
    );

    let svg = render_svg(&layout, "fixtures/render-policy/default-svg.json", None);
    let doc = svg_doc(&svg);
    assert_svg_texts_include(&doc, &["Client", "API", "calls"]);
    assert_reasonable_svg_aspect(&svg, 2.8);
    write_render_artifact("real-elk", "real_elk_renders_basic_projected_graph", &svg);
}

fn real_elk_guard() -> MutexGuard<'static, ()> {
    REAL_ELK_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn project_layout_request(source_fixture: &str) -> Vec<u8> {
    common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "project",
            "--target",
            "layout-request",
            "--plugin",
            "generic-graph",
            "--view",
            "main",
            "--input",
        ])
        .arg(workspace_file(source_fixture))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone()
}

fn project_render_metadata(source_fixture: &str) -> Vec<u8> {
    common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "project",
            "--target",
            "render-metadata",
            "--plugin",
            "generic-graph",
            "--view",
            "main",
            "--input",
        ])
        .arg(workspace_file(source_fixture))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone()
}

fn real_elk_layout(input: &Path) -> Vec<u8> {
    common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env(
            "DEDIREN_ELK_COMMAND",
            workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh"),
        )
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone()
}

fn validate_layout(input: &Path) -> Value {
    let output = common::dediren_command()
        .args(["validate-layout", "--input"])
        .arg(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    ok_data(&output)
}

fn assert_layout_quality_ok(quality: &Value) {
    assert_eq!(quality["status"], "ok");
    assert_eq!(quality["overlap_count"], 0);
    assert_eq!(quality["connector_through_node_count"], 0);
}

fn assert_archimate_fixture_quality_has_known_connector_warning(quality: &Value) {
    // The rich ArchiMate fixture is intentionally kept in this real-ELK lane
    // because it covers semantic metadata notation that the clean smaller
    // fixtures do not. Keep the known route-quality debt explicit so this test
    // cannot silently accept unrelated layout warnings.
    assert_eq!(quality["status"], "warning");
    assert_eq!(quality["overlap_count"], 0);
    assert_eq!(quality["connector_through_node_count"], 1);
    assert_eq!(quality["invalid_route_count"], 0);
    assert_eq!(quality["group_boundary_issue_count"], 0);
    assert_eq!(quality["warning_count"], 0);
}

fn render_svg(layout_result: &Path, policy_fixture: &str, metadata: Option<&Path>) -> String {
    let mut command = common::dediren_command();
    command
        .env(
            "DEDIREN_PLUGIN_SVG_RENDER",
            plugin_binary("dediren-plugin-svg-render"),
        )
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file(policy_fixture));
    if let Some(metadata) = metadata {
        command.arg("--metadata").arg(metadata);
    }
    let output = command
        .arg("--input")
        .arg(layout_result)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let data = ok_data(&output);
    data["content"]
        .as_str()
        .expect("render output should contain SVG content")
        .to_string()
}

fn write_temp_bytes(temp: &assert_fs::TempDir, name: &str, content: &[u8]) -> PathBuf {
    let child = temp.child(name);
    child.write_binary(content).unwrap();
    child.path().to_path_buf()
}

fn write_temp_json(temp: &assert_fs::TempDir, name: &str, content: &Value) -> PathBuf {
    let child = temp.child(name);
    child.write_str(&content.to_string()).unwrap();
    child.path().to_path_buf()
}
```

- [ ] **Step 2: Build the helper and run the first real suite test**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test --locked -p dediren --test real_elk_render real_elk_renders_basic_projected_graph -- --ignored --exact --test-threads=1
```

Expected:

- Java helper build reports `BUILD SUCCESSFUL`.
- Cargo reports `test real_elk_renders_basic_projected_graph ... ok`.
- `.test-output/renders/real-elk/real_elk_renders_basic_projected_graph.svg` exists.

- [ ] **Step 3: Commit the first real lane slice**

```bash
git add crates/dediren-cli/tests/real_elk_render.rs
git commit -m "test: add real elk render suite"
```

---

### Task 2: Add Rich And ArchiMate Real ELK Render Coverage

**Files:**
- Modify: `crates/dediren-cli/tests/real_elk_render.rs`

- [ ] **Step 1: Add a grouped rich graph test**

Insert this test after `real_elk_renders_basic_projected_graph`:

```rust
#[test]
#[ignore = "requires SDKMAN Java helper build; real ELK helper runs are serialized"]
fn real_elk_renders_grouped_rich_graph() {
    let _guard = real_elk_guard();
    let temp = assert_fs::TempDir::new().unwrap();

    let request_output = project_layout_request("fixtures/source/valid-pipeline-rich.json");
    let request_data = ok_data(&request_output);
    assert_eq!(
        request_data["nodes"]
            .as_array()
            .expect("projected nodes should be an array")
            .len(),
        6
    );
    assert_eq!(
        request_data["groups"]
            .as_array()
            .expect("projected groups should be an array")
            .len(),
        2
    );
    let request = write_temp_bytes(&temp, "grouped-rich-layout-request.json", &request_output);

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "grouped-rich-layout-result.json", &layout_output);
    assert_archimate_fixture_quality_has_known_connector_warning(&validate_layout(&layout));

    let layout_data = ok_data(&layout_output);
    assert_eq!(
        layout_data["nodes"]
            .as_array()
            .expect("laid out nodes should be an array")
            .len(),
        6
    );
    assert_eq!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .len(),
        6
    );
    assert_eq!(
        layout_data["groups"]
            .as_array()
            .expect("laid out groups should be an array")
            .len(),
        2
    );

    let svg = render_svg(&layout, "fixtures/render-policy/rich-svg.json", None);
    let doc = svg_doc(&svg);
    assert_svg_texts_include(
        &doc,
        &[
            "Client",
            "Web App",
            "Orders API",
            "PostgreSQL",
            "Payments Provider",
            "Application Services",
            "submits order",
            "authorizes payment",
        ],
    );
    assert!(svg.contains("data-dediren-group-id=\"application-services\""));
    assert!(svg.contains("data-dediren-group-id=\"external-dependencies\""));
    assert_reasonable_svg_aspect(&svg, 3.2);
    write_render_artifact("real-elk", "real_elk_renders_grouped_rich_graph", &svg);
}
```

- [ ] **Step 2: Add a real ELK ArchiMate metadata render test**

Insert this test after `real_elk_renders_grouped_rich_graph`:

```rust
#[test]
#[ignore = "requires SDKMAN Java helper build; real ELK helper runs are serialized"]
fn real_elk_renders_archimate_metadata_notation() {
    let _guard = real_elk_guard();
    let temp = assert_fs::TempDir::new().unwrap();
    let source = "fixtures/source/valid-pipeline-archimate.json";

    let request_output = project_layout_request(source);
    let request = write_temp_bytes(&temp, "archimate-layout-request.json", &request_output);
    let metadata_output = project_render_metadata(source);
    let metadata = write_temp_bytes(&temp, "archimate-render-metadata.json", &metadata_output);

    let metadata_data = ok_data(&metadata_output);
    assert_eq!(metadata_data["semantic_profile"], "archimate");
    assert_eq!(metadata_data["nodes"]["orders-api"]["type"], "ApplicationService");
    assert_eq!(metadata_data["nodes"]["client"]["type"], "BusinessActor");
    assert_eq!(metadata_data["nodes"]["payments"]["type"], "ApplicationService");
    assert_eq!(metadata_data["nodes"]["database"]["type"], "DataObject");
    assert_eq!(metadata_data["edges"]["web-app-calls-api"]["type"], "Realization");

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "archimate-layout-result.json", &layout_output);
    assert_layout_quality_ok(&validate_layout(&layout));

    let svg = render_svg(
        &layout,
        "fixtures/render-policy/archimate-svg.json",
        Some(&metadata),
    );
    let doc = svg_doc(&svg);

    for node_id in ["web-app", "worker"] {
        let component = semantic_group(&doc, "data-dediren-node-id", node_id);
        assert!(child_group_with_attr(
            component,
            "data-dediren-node-decorator",
            "archimate_application_component"
        )
        .is_some());
    }

    let business = semantic_group(&doc, "data-dediren-node-id", "client");
    assert!(child_group_with_attr(
        business,
        "data-dediren-node-decorator",
        "archimate_business_actor"
    )
    .is_some());

    for node_id in ["orders-api", "payments"] {
        let service = semantic_group(&doc, "data-dediren-node-id", node_id);
        assert!(child_group_with_attr(
            service,
            "data-dediren-node-decorator",
            "archimate_application_service"
        )
        .is_some());
    }

    let data_object = semantic_group(&doc, "data-dediren-node-id", "database");
    assert!(child_group_with_attr(
        data_object,
        "data-dediren-node-decorator",
        "archimate_data_object"
    )
    .is_some());

    let realization = semantic_group(&doc, "data-dediren-edge-id", "web-app-calls-api");
    let path = child_element(realization, "path");
    assert_eq!(path.attribute("stroke-dasharray"), Some("8 5"));
    assert_eq!(
        path.attribute("marker-end"),
        Some("url(#marker-end-web-app-calls-api)")
    );
    assert_svg_texts_include(
        &doc,
        &[
            "Customer",
            "Orders API",
            "Payments Service",
            "publishes fulfillment",
        ],
    );
    assert!(svg.contains(">Fulfillment</tspan>"));
    assert!(svg.contains(">Worker</tspan>"));
    assert_reasonable_svg_aspect(&svg, 5.0);
    write_render_artifact("real-elk", "real_elk_renders_archimate_metadata_notation", &svg);
}
```

- [ ] **Step 3: Run both new real tests**

Run:

```bash
cargo test --locked -p dediren --test real_elk_render real_elk_renders_grouped_rich_graph -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test real_elk_render real_elk_renders_archimate_metadata_notation -- --ignored --exact --test-threads=1
```

Expected:

- Both tests pass.
- `.test-output/renders/real-elk/real_elk_renders_grouped_rich_graph.svg` exists.
- `.test-output/renders/real-elk/real_elk_renders_archimate_metadata_notation.svg` exists.

- [ ] **Step 4: Commit the richer real render coverage**

```bash
git add crates/dediren-cli/tests/real_elk_render.rs
git commit -m "test: cover rich real elk renders"
```

---

### Task 3: Add A Real ELK Cross-Group Routing Render

**Files:**
- Modify: `crates/dediren-cli/tests/real_elk_render.rs`

- [ ] **Step 1: Add an inline layout-request case that does not use a layout-result fixture**

Insert this test after `real_elk_renders_archimate_metadata_notation`:

```rust
#[test]
#[ignore = "requires SDKMAN Java helper build; real ELK helper runs are serialized"]
fn real_elk_renders_cross_group_route_without_quality_warnings() {
    let _guard = real_elk_guard();
    let temp = assert_fs::TempDir::new().unwrap();
    let request = serde_json::json!({
        "layout_request_schema_version": "layout-request.schema.v1",
        "view_id": "main",
        "nodes": [
            { "id": "a", "label": "A", "source_id": "a", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "b", "label": "B", "source_id": "b", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "c", "label": "C", "source_id": "c", "width_hint": 160.0, "height_hint": 80.0 }
        ],
        "edges": [
            { "id": "a-to-b", "source": "a", "target": "b", "label": "internal", "source_id": "a-to-b" },
            { "id": "a-to-c", "source": "a", "target": "c", "label": "connects", "source_id": "a-to-c" }
        ],
        "groups": [
            {
                "id": "group",
                "label": "Group",
                "members": ["a", "b"],
                "provenance": { "semantic_backed": { "source_id": "group" } }
            }
        ],
        "labels": [],
        "constraints": []
    });
    let request = write_temp_json(&temp, "cross-group-layout-request.json", &request);

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "cross-group-layout-result.json", &layout_output);
    assert_layout_quality_ok(&validate_layout(&layout));

    let layout_data = ok_data(&layout_output);
    assert!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .iter()
            .any(|edge| edge["id"] == "a-to-c"),
        "real helper output should preserve the cross-group edge"
    );

    let svg = render_svg(&layout, "fixtures/render-policy/default-svg.json", None);
    let doc = svg_doc(&svg);
    assert_svg_texts_include(&doc, &["A", "B", "C", "connects"]);
    assert_reasonable_svg_aspect(&svg, 3.2);
    write_render_artifact(
        "real-elk",
        "real_elk_renders_cross_group_route_without_quality_warnings",
        &svg,
    );
}
```

- [ ] **Step 2: Run the cross-group route test**

Run:

```bash
cargo test --locked -p dediren --test real_elk_render real_elk_renders_cross_group_route_without_quality_warnings -- --ignored --exact --test-threads=1
```

Expected:

- Test passes.
- `.test-output/renders/real-elk/real_elk_renders_cross_group_route_without_quality_warnings.svg` exists.

- [ ] **Step 3: Commit the routing render case**

```bash
git add crates/dediren-cli/tests/real_elk_render.rs
git commit -m "test: render real elk cross-group route"
```

---

### Task 4: Make Fixture Pipeline Tests Visibly Fixture-Based And Lean

**Files:**
- Modify: `crates/dediren-cli/tests/cli_pipeline.rs`

- [ ] **Step 1: Rename the remaining broad fixture pipeline test**

Rename:

```rust
fn full_pipeline_produces_svg_and_oef()
```

to:

```rust
fn fixture_pipeline_produces_svg_and_oef()
```

Also change the render artifact write in that test from:

```rust
write_render_artifact(
    "cli-pipeline",
    "full_pipeline_produces_svg_and_oef",
    svg_content,
);
```

to:

```rust
write_render_artifact(
    "fixture-pipeline",
    "fixture_pipeline_produces_svg_and_oef",
    svg_content,
);
```

- [ ] **Step 2: Remove fixture-backed ArchiMate notation from the CLI pipeline suite**

Delete the whole function:

```rust
fn archimate_pipeline_renders_policy_notation_from_projected_metadata()
```

Delete from its `#[test]` attribute through its final closing brace. The real ELK replacement is `real_elk_renders_archimate_metadata_notation`; exhaustive ArchiMate node and relationship semantics remain fixture-backed in `crates/dediren-plugin-svg-render/tests/svg_render_plugin/archimate_nodes.rs`, `crates/dediren-plugin-svg-render/tests/svg_render_plugin/archimate_relationships.rs`, and `crates/dediren-archimate/tests/relationship_rules.rs`.

- [ ] **Step 3: Remove the old real ELK test from `cli_pipeline.rs`**

Delete the whole function:

```rust
fn real_elk_pipeline_renders_rich_source()
```

Delete from its `#[test]` attribute through its final closing brace. The replacement is the dedicated `real_elk_render.rs` suite.

- [ ] **Step 4: Reduce the imports to the fixture pipeline needs**

After the deletions, the top import block should be:

```rust
mod common;

use assert_fs::prelude::*;
use common::{
    assert_reasonable_svg_aspect, assert_svg_texts_include, ok_data, plugin_binary, svg_doc,
    workspace_file, write_render_artifact,
};
```

- [ ] **Step 5: Run the lean fixture pipeline test**

Run:

```bash
cargo test -p dediren --test cli_pipeline --locked
```

Expected:

- Cargo reports `test fixture_pipeline_produces_svg_and_oef ... ok`.
- No ignored tests remain in `cli_pipeline.rs`.
- `.test-output/renders/fixture-pipeline/fixture_pipeline_produces_svg_and_oef.svg` exists after the test.

- [ ] **Step 6: Commit the fixture-pipeline trim**

```bash
git add crates/dediren-cli/tests/cli_pipeline.rs
git commit -m "test: make fixture pipeline lane explicit"
```

---

### Task 5: Rename ELK Adapter Tests By Lane

**Files:**
- Modify: `crates/dediren-cli/tests/cli_layout.rs`
- Modify: `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`
- Modify: `AGENTS.md`

- [ ] **Step 1: Rename CLI layout tests**

Apply these exact renames in `crates/dediren-cli/tests/cli_layout.rs`:

| Old name | New name |
| --- | --- |
| `layout_invokes_elk_plugin_with_fixture_runtime` | `fixture_elk_layout_uses_result_fixture` |
| `layout_invokes_elk_plugin_with_external_command` | `fake_elk_layout_wraps_external_command` |
| `layout_invokes_real_java_elk_helper` | `real_elk_layout_invokes_java_helper` |
| `validate_layout_accepts_real_grouped_cross_group_route` | `real_elk_layout_validates_grouped_cross_group_route` |
| `validate_layout_reports_quality` | `fixture_layout_result_reports_quality` |

Do not change the test bodies in this step.

- [ ] **Step 2: Rename ELK plugin adapter tests**

Apply these exact renames in `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`:

| Old name | New name |
| --- | --- |
| `elk_plugin_invokes_external_command_and_wraps_raw_layout_result` | `fake_elk_plugin_wraps_raw_layout_result` |
| `elk_plugin_accepts_external_command_envelope` | `fake_elk_plugin_accepts_external_command_envelope` |
| `elk_plugin_preserves_external_error_envelope` | `fake_elk_plugin_preserves_external_error_envelope` |
| `elk_plugin_reports_external_runtime_failure` | `fake_elk_plugin_reports_external_runtime_failure` |
| `elk_plugin_reports_external_invalid_json_output` | `fake_elk_plugin_reports_external_invalid_json_output` |
| `elk_plugin_reports_missing_runtime` | `fixture_elk_plugin_reports_missing_runtime` |
| `elk_plugin_accepts_fixture_runtime_output` | `fixture_elk_plugin_accepts_fixture_runtime_output` |
| `elk_plugin_invokes_real_java_helper` | `real_elk_plugin_invokes_java_helper` |

Do not change the test bodies in this step.

- [ ] **Step 3: Update AGENTS ignored-test command names**

In `AGENTS.md`, update the ELK helper verification commands to use the new names:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin real_elk_plugin_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test -p dediren --test cli_layout real_elk_layout_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test -p dediren --test cli_layout real_elk_layout_validates_grouped_cross_group_route -- --ignored --exact --test-threads=1
cargo test -p dediren --test real_elk_render -- --ignored --test-threads=1
```

- [ ] **Step 4: Run renamed fixture and real lanes**

Run:

```bash
cargo test -p dediren --test cli_layout --locked
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin --locked
cargo test --locked -p dediren-plugin-elk-layout --test elk_layout_plugin real_elk_plugin_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_layout real_elk_layout_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_layout real_elk_layout_validates_grouped_cross_group_route -- --ignored --exact --test-threads=1
```

Expected:

- Non-ignored fixture/fake lanes pass.
- Ignored real lanes pass serially.
- Test output names make the lane obvious without opening the source file.

- [ ] **Step 5: Commit the lane naming cleanup**

```bash
git add crates/dediren-cli/tests/cli_layout.rs crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs AGENTS.md
git commit -m "test: label elk test lanes"
```

---

### Task 6: Update The Test Quality Gap Matrix

**Files:**
- Modify: `docs/superpowers/plans/2026-05-12-dediren-test-quality-gap-matrix.md`

- [ ] **Step 1: Replace the CLI fixture pipeline row**

Replace the existing `CLI fixture pipeline` row with:

```markdown
| CLI fixture pipeline | `crates/dediren-cli/tests/cli_pipeline.rs::fixture_pipeline_produces_svg_and_oef` | One deterministic fixture-backed smoke test covers CLI command wiring across project, layout fixture input, render, and OEF export. | Fixture layout does not prove ELK geometry quality and is intentionally not the render-confidence lane. | Keep this lane lean; add broad render confidence only under `real_elk_render.rs`. |
```

- [ ] **Step 2: Replace the real ELK render lane row**

Replace the existing `Real ELK render lane` row with:

```markdown
| Real ELK render lane | `crates/dediren-cli/tests/real_elk_render.rs::real_elk_*`, renamed real helper tests in `cli_layout.rs` and `elk_layout_plugin.rs` | Dedicated ignored tests run source or inline layout requests through the Java helper, validate layout quality, render SVG, and write artifacts under `.test-output/renders/real-elk/`. | Opt-in because SDKMAN/Gradle helper setup is required; tests should be run serially and include an in-binary mutex for the new render suite. | Run for ELK changes, render quality work, release checks, and suspected layout regressions. Do not substitute fixture results for this lane. |
```

- [ ] **Step 3: Add a lane vocabulary section**

Append this section after the matrix:

```markdown
## ELK Lane Vocabulary

- `real_elk_*`: invokes the Java ELK helper through `DEDIREN_ELK_COMMAND`.
- `fixture_elk_*` or `fixture_pipeline_*`: consumes static layout fixtures or `DEDIREN_ELK_RESULT_FIXTURE`.
- `fake_elk_*`: uses a shell helper to simulate external runtime envelopes or failures.
- ArchiMate node/relationship fixture tests: deterministic semantic matrix coverage, intentionally retained as fixture-based tests.
- `.test-output/renders/real-elk/*.svg`: real helper render artifacts.
- `.test-output/renders/fixture-pipeline/*.svg`: fixture-backed broad pipeline artifacts.
- `.test-output/renders/svg-render-plugin/*.svg`: renderer policy artifacts from static layout inputs, not ELK geometry evidence.
```

- [ ] **Step 4: Verify docs formatting**

Run:

```bash
git diff --check
```

Expected: no whitespace errors.

- [ ] **Step 5: Commit the documentation update**

```bash
git add docs/superpowers/plans/2026-05-12-dediren-test-quality-gap-matrix.md
git commit -m "docs: map elk test lanes"
```

---

### Task 7: Final Verification And Audit Gates

**Files:**
- All files changed by Tasks 1-6.

- [ ] **Step 1: Run formatting and deterministic fixture lanes**

Run:

```bash
cargo fmt --all -- --check
cargo test -p dediren --test cli_pipeline --locked
cargo test -p dediren --test cli_layout --locked
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin --locked
cargo test -p dediren-plugin-svg-render --test svg_render_plugin --locked
cargo test --workspace --locked
git diff --check
```

Expected:

- All commands exit 0.
- `cargo test --workspace --locked` still skips ignored real-helper tests.

- [ ] **Step 2: Run the real ELK render lane serially**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test --locked -p dediren-plugin-elk-layout --test elk_layout_plugin real_elk_plugin_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_layout real_elk_layout_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_layout real_elk_layout_validates_grouped_cross_group_route -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test real_elk_render -- --ignored --test-threads=1
```

Expected:

- Java helper build reports `BUILD SUCCESSFUL`.
- All ignored real-helper tests pass.
- Real render artifacts exist under `.test-output/renders/real-elk/`.

- [ ] **Step 3: Run the required audit gates**

Run `souroldgeezer-audit:test-quality-audit` in deep mode over:

- `crates/dediren-cli/tests/real_elk_render.rs`
- `crates/dediren-cli/tests/cli_pipeline.rs`
- `crates/dediren-cli/tests/cli_layout.rs`
- `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`
- `docs/superpowers/plans/2026-05-12-dediren-test-quality-gap-matrix.md`
- `AGENTS.md`

Run `souroldgeezer-audit:devsecops-audit` in quick mode over the implementation diff.

Expected:

- No block findings.
- Warn/info findings are fixed or explicitly accepted in the handoff.

- [ ] **Step 4: Final status check**

Run:

```bash
git status --short --branch
```

Expected:

- Only intentional tracked changes are present before final commit or merge.
- `.test-output/` artifacts remain ignored and unstaged.

---

## Self-Review

Spec coverage:

- Real ELK-heavy: Tasks 1-3 add four real-helper render cases and make them the preferred layout/render confidence lane.
- Lean fixture side: Task 4 trims the broad fixture pipeline to one explicit smoke test.
- Fixture carve-out: ArchiMate node/relationship and rule-table tests remain fixture/table-driven because they cover deterministic semantic matrices rather than layout geometry.
- Quickly tell real vs fixture: Tasks 4-6 add file, name, artifact, and matrix signals.
- Serial real helper behavior: Task 1 adds an in-binary mutex and Task 7 uses `--test-threads=1`.
- Audit gates: Task 7 includes deep test-quality and quick DevSecOps gates.

Placeholder scan:

- No placeholder markers remain.
- Every code-changing task names exact files, functions, commands, and expected outcomes.

Type consistency:

- Helper functions introduced in Task 1 are used by Tasks 2 and 3 with matching signatures.
- Renamed test commands in Task 5 match the final verification commands in Task 7.
