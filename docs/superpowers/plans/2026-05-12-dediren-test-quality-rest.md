# Dediren Test Quality Remaining Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the test-quality cleanup deferred from `2026-05-12-dediren-test-quality-cleanup.md` by reducing brittle string assertions, splitting the oversized SVG renderer integration suite, strengthening ArchiMate relationship-rule evidence, and moving layout confidence toward real ELK-generated renders.

**Architecture:** Keep product behavior unchanged. Treat tests as contract evidence: command envelopes are parsed as JSON, SVG output is parsed as XML, plugin capabilities are asserted structurally, and ArchiMate relationship rules are checked against stable edge cases plus rule-table integrity. Use fixture-backed ELK tests only as a lean deterministic adapter lane; use the real Java ELK helper plus rendered SVG artifacts as the stronger geometry/render confidence lane. Keep helper code test-local unless it already belongs in `dediren-cli/tests/common`.

**Tech Stack:** Rust 1.93, Cargo workspace, Java/Gradle ELK helper tests, `assert_cmd`, `serde_json`, `roxmltree`, first-party process-boundary plugins, `souroldgeezer-audit:test-quality-audit`.

---

## Scope

This plan covers the remaining cleanup after the first slice on `main`:

- Move the broad `cli_pipeline` test off duplicated binary-build and SVG helper code.
- Convert direct first-party plugin tests from substring assertions to parsed JSON contract assertions.
- Split `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs` into focused modules and keep the renderer helper layer explicit.
- Expand the ArchiMate relationship-rule tests from example coverage to a small curated oracle plus rule-table invariants.
- Convert Java ELK helper tests from JSON substrings to parsed envelope assertions.
- Strengthen the ignored real-ELK render lane so it validates actual helper geometry before rendering and is part of closeout verification when the helper is available.
- Record a final test-quality gap matrix for the suite areas touched by this cleanup.

Do not change schemas, plugin manifests, CLI output shape, README examples, distribution artifacts, or product versions. If implementation discovers a required public behavior change, stop this plan and write a separate product-change plan with the normal version-bump surfaces.

README changes are not required for this plan as written because it changes only internal test structure and developer evidence. If a later implementation adds or changes a public command, install path, artifact path, or runtime workflow, update `README.md` in that same implementation slice.

## ELK Test Posture

Use this balance while executing the plan:

- Fixture-backed ELK tests prove wrapper behavior, envelope normalization, missing-runtime behavior, and fast no-Java CLI paths.
- Fixture-backed ELK tests must not be expanded into the main evidence for layout quality, grouped routing quality, or rendered diagram quality.
- Real ELK tests are opt-in because they require the SDKMAN/Gradle helper and must be run serially until the known concurrent invocation failure is fixed.
- A full closeout for this cleanup should include the real helper layout tests and the rich-source real render pipeline. If the local environment cannot run the helper, report that as a blocked verification lane instead of substituting fixture results.

## Audit Gates

Run narrow checks after each task, then run the full closeout lane before marking the plan complete:

```bash
cargo fmt --all -- --check
cargo test -p dediren --test cli_pipeline --locked
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin --locked
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin --locked
cargo test -p dediren-plugin-svg-render --test svg_render_plugin --locked
cargo test -p dediren-archimate --test relationship_rules --locked
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test --locked -p dediren-plugin-elk-layout --test elk_layout_plugin elk_plugin_invokes_real_java_helper -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_layout layout_invokes_real_java_elk_helper -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_layout validate_layout_accepts_real_grouped_cross_group_route -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact --test-threads=1
cargo test --workspace --locked
git diff --check
```

Perform a `souroldgeezer-audit:test-quality-audit` deep review over the changed test files, the real ELK lane files, and the final gap matrix. Fix block findings. Fix warn/info findings or explicitly accept them in the handoff with the reason.

A `devsecops-audit` quick review is not required unless implementation changes shell scripts, plugin process execution code, environment handling, dependency posture, release artifacts, or runtime discovery behavior.

## File Structure

- Modify: `crates/dediren-cli/tests/common/mod.rs`
  - Add the remaining SVG geometry and text helpers currently duplicated in `cli_pipeline.rs`.
- Modify: `crates/dediren-cli/tests/cli_pipeline.rs`
  - Reuse shared command, plugin-binary, envelope, SVG, and artifact helpers.
  - Strengthen `real_elk_pipeline_renders_rich_source` so it validates real helper layout quality before rendering.
- Create: `crates/dediren-plugin-generic-graph/tests/common/mod.rs`
  - Add direct-plugin command and parsed JSON assertion helpers.
- Modify: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`
  - Convert substring assertions to parsed raw capability and command-envelope assertions.
- Create: `crates/dediren-plugin-archimate-oef-export/tests/common/mod.rs`
  - Add direct-plugin command and parsed JSON assertion helpers.
- Modify: `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`
  - Convert substring assertions to parsed raw capability and command-envelope assertions.
- Replace: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
  - Keep as a module entrypoint only.
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/common.rs`
  - Move renderer input builders, XML helpers, icon helpers, path helpers, bounds helpers, marker helpers, constants, and artifact helpers here.
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/render_contracts.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/archimate_nodes.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/archimate_relationships.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/edge_labels.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/viewbox_routes.rs`
- Modify: `crates/dediren-archimate/tests/relationship_rules.rs`
  - Add curated relationship oracle cases and rule-table invariants.
- Create: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/EnvelopeAssertions.java`
  - Add parsed JSON envelope assertions for Java helper tests.
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java`
- Create: `docs/superpowers/plans/2026-05-12-dediren-test-quality-gap-matrix.md`
  - Record the final suite inventory, confidence level, and accepted residual gaps.

---

### Task 1: Finish CLI Pipeline Helper Migration

**Files:**
- Modify: `crates/dediren-cli/tests/common/mod.rs`
- Modify: `crates/dediren-cli/tests/cli_pipeline.rs`

- [ ] **Step 1: Add missing SVG helpers to `common/mod.rs`**

Add these helper functions below the existing SVG helpers:

```rust
pub fn parse_svg_view_box(content: &str) -> [f64; 4] {
    let doc = svg_doc(content);
    let svg = doc.root_element();
    let view_box = svg.attribute("viewBox").expect("SVG should have viewBox");
    let values: Vec<f64> = view_box
        .split_whitespace()
        .map(|value| value.parse::<f64>().expect("viewBox should contain numbers"))
        .collect();
    assert_eq!(values.len(), 4, "viewBox should contain four numbers");
    [values[0], values[1], values[2], values[3]]
}

pub fn assert_reasonable_svg_aspect(content: &str, max_aspect: f64) {
    let [_x, _y, width, height] = parse_svg_view_box(content);
    assert!(width > 0.0, "viewBox width should be positive");
    assert!(height > 0.0, "viewBox height should be positive");
    let aspect = width.max(height) / width.min(height);
    assert!(
        aspect <= max_aspect,
        "expected SVG aspect ratio <= {max_aspect}, got {aspect} from {width}x{height}"
    );
}

pub fn svg_texts(doc: &Document<'_>) -> Vec<String> {
    doc.descendants()
        .filter(|node| node.has_tag_name("text"))
        .filter_map(|node| node.text())
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(ToOwned::to_owned)
        .collect()
}

pub fn assert_svg_texts_include(doc: &Document<'_>, expected: &[&str]) {
    let actual = svg_texts(doc);
    for expected_text in expected {
        assert!(
            actual.iter().any(|text| text == expected_text),
            "expected SVG text {expected_text:?}, got {actual:?}"
        );
    }
}
```

- [ ] **Step 2: Replace duplicated helpers in `cli_pipeline.rs`**

At the top of `cli_pipeline.rs`, use the shared module:

```rust
mod common;

use assert_fs::prelude::*;
use common::{
    assert_reasonable_svg_aspect, assert_svg_texts_include, child_element,
    child_group_with_attr, ok_data, plugin_binary, semantic_group, svg_doc, workspace_file,
    write_render_artifact,
};
```

Replace local calls as follows:

- Replace each local `workspace_binary(package, binary)` call with `plugin_binary(binary)`.
- Replace local `workspace_file(path)` calls with `workspace_file(path)`.
- Replace local `svg_doc`, `semantic_group`, `child_element`, and `child_group_with_attr` calls with imported helpers.
- Replace local `write_render_artifact(test_name, content)` calls with `write_render_artifact("cli-pipeline", test_name, content)`.
- Replace broad label substring checks with `assert_svg_texts_include(&doc, &[...])` where the test is proving visible labels.
- In `real_elk_pipeline_renders_rich_source`, keep `DEDIREN_ELK_COMMAND` pointed at `crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh` and do not set `DEDIREN_ELK_RESULT_FIXTURE`.
- In `real_elk_pipeline_renders_rich_source`, insert this validation block immediately after `result.write_binary(&layout_output).unwrap();`:

```rust
let validate_output = common::dediren_command()
    .arg("validate-layout")
    .arg("--input")
    .arg(result.path())
    .assert()
    .success()
    .get_output()
    .stdout
    .clone();
let quality = ok_data(&validate_output);
assert_eq!(quality["status"], "ok");
assert_eq!(quality["overlap_count"], 0);
assert_eq!(quality["connector_through_node_count"], 0);
```

- Delete the local helper functions now covered by `common`.

Do not change fixture paths, command arguments, ignored-test status, or artifact output paths.

- [ ] **Step 3: Verify**

Run:

```bash
cargo fmt --all -- --check
cargo test -p dediren --test cli_pipeline --locked
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test --locked -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact --test-threads=1
git diff --check
```

Expected: the non-ignored pipeline tests pass. The real-ELK pipeline test also passes when the Java helper lane is available; if the helper is unavailable, report the exact environment blocker.

---

### Task 2: Convert Direct Generic-Graph Plugin Tests To Parsed Contracts

**Files:**
- Create: `crates/dediren-plugin-generic-graph/tests/common/mod.rs`
- Modify: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`

- [ ] **Step 1: Add direct-plugin helpers**

Create `crates/dediren-plugin-generic-graph/tests/common/mod.rs`:

```rust
#![allow(dead_code)]

use assert_cmd::Command;
use serde_json::Value;
use std::path::PathBuf;

pub fn workspace_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

pub fn workspace_file(path: &str) -> PathBuf {
    workspace_root().join(path)
}

pub fn plugin_command() -> Command {
    Command::cargo_bin("dediren-plugin-generic-graph")
        .expect("generic graph plugin binary should be built by Cargo")
}

pub fn stdout_json(output: &[u8]) -> Value {
    serde_json::from_slice(output).expect("stdout should be valid JSON")
}

pub fn ok_data(output: &[u8]) -> Value {
    let envelope = stdout_json(output);
    assert_eq!(envelope["status"], "ok", "plugin should return ok envelope");
    assert!(
        envelope["diagnostics"]
            .as_array()
            .expect("diagnostics should be an array")
            .is_empty(),
        "ok envelope should not include diagnostics"
    );
    envelope["data"].clone()
}

pub fn error_envelope(output: &[u8]) -> Value {
    let envelope = stdout_json(output);
    assert_eq!(envelope["status"], "error", "plugin should return error envelope");
    assert!(
        !envelope["diagnostics"]
            .as_array()
            .expect("diagnostics should be an array")
            .is_empty(),
        "error envelope should include diagnostics"
    );
    envelope
}

pub fn assert_error_code(output: &[u8], expected_code: &str) -> Value {
    let envelope = error_envelope(output);
    let codes: Vec<&str> = envelope["diagnostics"]
        .as_array()
        .expect("diagnostics should be an array")
        .iter()
        .map(|diagnostic| {
            diagnostic["code"]
                .as_str()
                .expect("diagnostic code should be a string")
        })
        .collect();
    assert!(
        codes.iter().any(|code| *code == expected_code),
        "expected diagnostic code {expected_code}, got {codes:?}"
    );
    envelope
}
```

- [ ] **Step 2: Rewrite assertions**

In `generic_graph_plugin.rs`:

- Add `mod common;`.
- Replace `Command::cargo_bin("dediren-plugin-generic-graph")` with `common::plugin_command()`.
- For successful `project` tests, call `.assert().success().get_output().stdout.clone()`, parse with `common::ok_data`, and assert fields on the returned `serde_json::Value`.
- For invalid ArchiMate node, relationship, and endpoint tests, parse with `common::assert_error_code` and assert the diagnostic message contains the rejected type or relationship name.

Expected structural assertions:

```rust
assert_eq!(data["layout_request_schema_version"], "layout-request.schema.v1");
assert_eq!(data["view_id"], "main");
assert_eq!(data["nodes"].as_array().expect("nodes should be an array").len(), 2);
assert_eq!(data["edges"].as_array().expect("edges should be an array").len(), 1);
```

For the ArchiMate endpoint rejection, assert:

```rust
let envelope = common::assert_error_code(
    &stdout,
    "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
);
let message = envelope["diagnostics"][0]["message"]
    .as_str()
    .expect("diagnostic message should be a string");
assert!(message.contains("ApplicationService"));
assert!(message.contains("Realization"));
assert!(message.contains("ApplicationComponent"));
```

- [ ] **Step 3: Verify**

Run:

```bash
cargo fmt --all -- --check
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin --locked
git diff --check
```

---

### Task 3: Convert Direct OEF Export Plugin Tests To Parsed Contracts

**Files:**
- Create: `crates/dediren-plugin-archimate-oef-export/tests/common/mod.rs`
- Modify: `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`

- [ ] **Step 1: Add direct-plugin helpers**

Create `crates/dediren-plugin-archimate-oef-export/tests/common/mod.rs`:

```rust
#![allow(dead_code)]

use assert_cmd::Command;
use serde_json::Value;

pub fn plugin_command() -> Command {
    Command::cargo_bin("dediren-plugin-archimate-oef-export")
        .expect("archimate-oef plugin binary should be built by Cargo")
}

pub fn stdout_json(output: &[u8]) -> Value {
    serde_json::from_slice(output).expect("stdout should be valid JSON")
}

pub fn ok_data(output: &[u8]) -> Value {
    let envelope = stdout_json(output);
    assert_eq!(envelope["status"], "ok", "plugin should return ok envelope");
    assert!(
        envelope["diagnostics"]
            .as_array()
            .expect("diagnostics should be an array")
            .is_empty(),
        "ok envelope should not include diagnostics"
    );
    envelope["data"].clone()
}

pub fn error_envelope(output: &[u8]) -> Value {
    let envelope = stdout_json(output);
    assert_eq!(envelope["status"], "error", "plugin should return error envelope");
    assert!(
        !envelope["diagnostics"]
            .as_array()
            .expect("diagnostics should be an array")
            .is_empty(),
        "error envelope should include diagnostics"
    );
    envelope
}

pub fn assert_error_code(output: &[u8], expected_code: &str) -> Value {
    let envelope = error_envelope(output);
    let codes: Vec<&str> = envelope["diagnostics"]
        .as_array()
        .expect("diagnostics should be an array")
        .iter()
        .map(|diagnostic| {
            diagnostic["code"]
                .as_str()
                .expect("diagnostic code should be a string")
        })
        .collect();
    assert!(
        codes.iter().any(|code| *code == expected_code),
        "expected diagnostic code {expected_code}, got {codes:?}"
    );
    envelope
}
```

- [ ] **Step 2: Rewrite assertions**

In `oef_export_plugin.rs`:

- Add `mod common;`.
- Replace `Command::cargo_bin("dediren-plugin-archimate-oef-export")` with `common::plugin_command()`.
- Parse `capabilities` stdout as raw JSON and assert:

```rust
let capabilities = common::stdout_json(&stdout);
assert_eq!(capabilities["id"], "archimate-oef");
let capability_ids: Vec<&str> = capabilities["capabilities"]
    .as_array()
    .expect("capabilities should be an array")
    .iter()
    .map(|capability| {
        capability
            .as_str()
            .expect("capability id should be a string")
    })
    .collect();
assert!(capability_ids.contains(&"export"));
```

- For successful export, keep XML parsing and add parsed envelope checks through `common::ok_data`.
- For invalid ArchiMate node, relationship, and endpoint tests, use `common::assert_error_code` and assert diagnostic message content from the parsed envelope.

- [ ] **Step 3: Verify**

Run:

```bash
cargo fmt --all -- --check
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin --locked
git diff --check
```

---

### Task 4: Split The SVG Renderer Integration Suite

**Files:**
- Replace: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/common.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/render_contracts.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/archimate_nodes.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/archimate_relationships.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/edge_labels.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/viewbox_routes.rs`

- [ ] **Step 1: Convert the parent test file to module declarations**

Replace `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs` with:

```rust
#[path = "svg_render_plugin/archimate_nodes.rs"]
mod archimate_nodes;
#[path = "svg_render_plugin/archimate_relationships.rs"]
mod archimate_relationships;
#[path = "svg_render_plugin/common.rs"]
mod common;
#[path = "svg_render_plugin/edge_labels.rs"]
mod edge_labels;
#[path = "svg_render_plugin/render_contracts.rs"]
mod render_contracts;
#[path = "svg_render_plugin/viewbox_routes.rs"]
mod viewbox_routes;
```

- [ ] **Step 2: Move helpers into `common.rs`**

Create `crates/dediren-plugin-svg-render/tests/svg_render_plugin/common.rs` and move these exact items from the old file without changing their bodies on the first pass:

- `render_content`
- `archimate_style_input`
- `archimate_render_input`
- `styled_inline_input`
- `svg_doc`
- `semantic_group`
- `child_element`
- `child_group_with_attr`
- `child_node_shape`
- `child_elements`
- `expected_archimate_icon_kind`
- `expected_archimate_rectangular_node_shape`
- `assert_archimate_rectangular_node_shape`
- `assert_archimate_icon_morphology`
- `assert_side_cylinder_has_left_arc`
- `assert_value_stream_icon_has_left_notch`
- `assert_outcome_arrow_points_out_from_target_center`
- `assert_course_of_action_handle_extends_from_target_to_bottom_left`
- `assert_capability_icon_uses_square_stair_grid`
- `assert_resource_icon_uses_horizontal_capsule`
- `assert_interaction_icon_uses_separate_open_half_circles`
- `assert_application_component_tabs_extend_from_body`
- `assert_collaboration_icon_uses_side_by_side_overlap`
- `assert_function_icon_uses_bottom_notched_bookmark`
- `assert_distribution_network_icon_uses_horizontal_bidirectional_arrow`
- `assert_material_icon_uses_inner_side_lines`
- `assert_facility_icon_uses_factory_silhouette`
- `assert_equipment_icon_uses_offset_gears`
- `assert_artifact_icon_uses_folded_document`
- `assert_constraint_icon_uses_inner_slanted_line`
- `assert_contract_icon_uses_document_with_two_lines`
- `assert_folded_document_icon`
- `assert_archimate_icon_primitives_fit_standard_box`
- `assert_archimate_icon_primitives_stay_in_standard_box`
- `assert_archimate_icon_primitives_are_centered_in_standard_box`
- `svg_node_shape_bounds`
- `combined_bounds`
- `svg_primitive_bounds`
- `assert_marker`
- `text_box_from_svg`
- `text_lines_from_svg`
- `estimated_svg_text_width`
- `box_contains_point`
- `box_center_y`
- `horizontal_gap_to_x`
- `boxes_overlap`
- `box_intersects_horizontal_segment`
- `write_render_artifact`
- `current_test_name`
- `assert_gap_marker_is_centered_on_guide_lines`
- `parse_svg_path_numbers`
- `path_data_contains_point`
- `point_bounds`
- `segment_slope`
- `svg_path_points`
- `svg_path_number`
- `ARCHIMATE_NODE_TYPES`
- `ARCHIMATE_RELATIONSHIP_TYPES`
- `workspace_file`

Add `pub` to helpers and constants used by module files. Keep private only for helpers used solely inside `common.rs`.

- [ ] **Step 3: Move tests by responsibility**

Move these tests into `render_contracts.rs`:

- `svg_renderer_outputs_svg`
- `svg_renderer_applies_rich_policy_styles`
- `svg_renderer_preserves_style_number_precision`
- `svg_renderer_allows_schema_valid_non_ascii_font_family`
- `svg_renderer_applies_base_and_override_group_styles_to_group_elements`
- `svg_renderer_rejects_profile_mismatch`
- `svg_renderer_rejects_type_policy_without_metadata`
- `svg_renderer_rejects_unsafe_policy_color_before_rendering`

Move these tests into `archimate_nodes.rs`:

- `svg_renderer_applies_archimate_type_styles`
- `svg_renderer_applies_archimate_node_decorators_from_type_overrides`
- `svg_renderer_covers_each_archimate_square_node_type`
- `svg_renderer_applies_archimate_business_actor_decorator`
- `svg_renderer_applies_archimate_data_object_decorator`
- `svg_renderer_applies_archimate_technology_node_decorator`
- `svg_renderer_id_override_wins_over_type_override`
- `svg_renderer_rejects_unknown_archimate_node_type`
- `svg_renderer_rejects_unknown_archimate_policy_node_type_override`

Move these tests into `archimate_relationships.rs`:

- `svg_renderer_applies_archimate_realization_edge_notation`
- `svg_renderer_applies_archimate_relationship_start_markers`
- `svg_renderer_covers_each_archimate_relationship_type`
- `svg_renderer_edge_id_override_can_disable_marker`
- `svg_renderer_rejects_unknown_archimate_relationship_type`
- `svg_renderer_rejects_invalid_archimate_relationship_endpoint`
- `svg_renderer_rejects_unknown_archimate_policy_relationship_type_override`

Move these tests into `edge_labels.rs`:

- `svg_renderer_places_edge_label_near_route_midpoint_for_vertical_route`
- `svg_renderer_aligns_vertical_edge_labels_by_text_edge`
- `svg_renderer_prefers_horizontal_segment_for_edge_label`
- `svg_renderer_defaults_horizontal_edge_label_near_start`
- `svg_renderer_defaults_horizontal_edge_label_below_downward_bend`
- `svg_renderer_defaults_horizontal_edge_label_near_first_horizontal_segment`
- `svg_renderer_defaults_horizontal_edge_label_above_upward_bend`
- `svg_renderer_allows_horizontal_edge_label_side_override_by_policy`
- `svg_renderer_allows_centered_horizontal_edge_labels_by_policy`
- `svg_renderer_paints_edge_label_with_background_halo`
- `svg_renderer_moves_edge_label_away_from_node_boxes`
- `svg_renderer_centers_horizontal_edge_label_when_near_start_overlaps_adjacent_nodes`
- `svg_renderer_moves_edge_label_away_from_route_segments`
- `svg_renderer_keeps_horizontal_edge_label_close_to_route`
- `svg_renderer_separates_labels_for_parallel_horizontal_edges`
- `svg_renderer_separates_labels_for_adjacent_multisegment_routes`

Move these tests into `viewbox_routes.rs`:

- `svg_renderer_adds_line_jump_for_later_crossing_edge`
- `svg_renderer_expands_viewbox_to_include_edge_labels`
- `svg_renderer_crops_small_diagram_to_content_bounds`
- `svg_renderer_background_covers_positive_origin_viewbox`

Each new module starts with:

```rust
use super::common::*;
```

- [ ] **Step 4: Add parsed render envelope helpers**

In `common.rs`, add:

```rust
pub fn render_success_envelope(input: serde_json::Value) -> serde_json::Value {
    let mut cmd = assert_cmd::Command::cargo_bin("dediren-plugin-svg-render")
        .expect("svg render plugin binary should be built by Cargo");
    let output = cmd
        .arg("render")
        .write_stdin(input.to_string())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    serde_json::from_slice(&output).expect("render stdout should be JSON")
}

pub fn render_failure_envelope(input: serde_json::Value) -> serde_json::Value {
    let mut cmd = assert_cmd::Command::cargo_bin("dediren-plugin-svg-render")
        .expect("svg render plugin binary should be built by Cargo");
    let output = cmd
        .arg("render")
        .write_stdin(input.to_string())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();
    serde_json::from_slice(&output).expect("render stdout should be JSON")
}

pub fn render_ok_data(input: serde_json::Value) -> serde_json::Value {
    let envelope = render_success_envelope(input);
    assert_eq!(envelope["status"], "ok", "render should return ok envelope");
    assert!(
        envelope["diagnostics"]
            .as_array()
            .expect("diagnostics should be an array")
            .is_empty(),
        "ok envelope should not include diagnostics"
    );
    envelope["data"].clone()
}

pub fn render_error(input: serde_json::Value, expected_code: &str) -> serde_json::Value {
    let envelope = render_failure_envelope(input);
    assert_eq!(envelope["status"], "error", "render should return error envelope");
    let codes: Vec<&str> = envelope["diagnostics"]
        .as_array()
        .expect("diagnostics should be an array")
        .iter()
        .map(|diagnostic| {
            diagnostic["code"]
                .as_str()
                .expect("diagnostic code should be a string")
        })
        .collect();
    assert!(
        codes.iter().any(|code| *code == expected_code),
        "expected diagnostic code {expected_code}, got {codes:?}"
    );
    envelope
}
```

Then implement `render_content` in terms of `render_ok_data`:

```rust
pub fn render_content(input: serde_json::Value) -> String {
    render_ok_data(input)["content"]
        .as_str()
        .expect("render data should contain SVG content")
        .to_string()
}
```

- [ ] **Step 5: Convert renderer error tests**

Update the renderer rejection tests to use `render_error` and assert parsed diagnostic messages. Use these expected diagnostic codes:

- `svg_renderer_rejects_profile_mismatch`: `DEDIREN_RENDER_METADATA_PROFILE_MISMATCH`
- `svg_renderer_rejects_type_policy_without_metadata`: `DEDIREN_RENDER_METADATA_REQUIRED`
- `svg_renderer_rejects_unknown_archimate_node_type`: `DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED`
- `svg_renderer_rejects_unknown_archimate_relationship_type`: `DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED`
- `svg_renderer_rejects_invalid_archimate_relationship_endpoint`: `DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED`
- `svg_renderer_rejects_unknown_archimate_policy_node_type_override`: `DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED`
- `svg_renderer_rejects_unknown_archimate_policy_relationship_type_override`: `DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED`
- `svg_renderer_rejects_unsafe_policy_color_before_rendering`: `DEDIREN_SVG_POLICY_INVALID`

If the current implementation emits a different exact code, inspect the production diagnostic constant and use the production constant's current value. Do not relax the test to a substring over stdout.

- [ ] **Step 6: Verify**

Run:

```bash
cargo fmt --all -- --check
cargo test -p dediren-plugin-svg-render --test svg_render_plugin --locked
git diff --check
```

Expected: all existing SVG renderer tests still pass under the same single integration-test target name.

---

### Task 5: Strengthen ArchiMate Relationship Rule Tests

**Files:**
- Modify: `crates/dediren-archimate/tests/relationship_rules.rs`

- [ ] **Step 1: Add curated oracle cases**

Add these case tables near the top of the test file:

```rust
const REQUIRED_ALLOWED_RELATIONSHIPS: &[(&str, &str, &str)] = &[
    ("Realization", "ApplicationComponent", "ApplicationService"),
    ("Triggering", "BusinessProcess", "BusinessProcess"),
    ("Serving", "ApplicationService", "ApplicationComponent"),
    ("Access", "ApplicationFunction", "DataObject"),
    ("Association", "BusinessActor", "DataObject"),
];

const REQUIRED_REJECTED_RELATIONSHIPS: &[(&str, &str, &str)] = &[
    ("Realization", "ApplicationService", "ApplicationComponent"),
    ("Triggering", "BusinessActor", "DataObject"),
    ("Serving", "DataObject", "ApplicationService"),
    ("Access", "ApplicationComponent", "ApplicationFunction"),
    ("Flow", "BusinessObject", "ApplicationComponent"),
];
```

Then add:

```rust
#[test]
fn accepts_curated_archimate_32_relationship_oracle() {
    for (relationship_type, source_type, target_type) in REQUIRED_ALLOWED_RELATIONSHIPS {
        validate_relationship_endpoint_types(
            relationship_type,
            source_type,
            target_type,
            "$.relationships[*]",
        )
        .unwrap_or_else(|error| {
            panic!(
                "expected {source_type} -{relationship_type}-> {target_type} to be allowed, got {error:?}"
            )
        });
    }
}

#[test]
fn rejects_curated_archimate_32_relationship_oracle() {
    for (relationship_type, source_type, target_type) in REQUIRED_REJECTED_RELATIONSHIPS {
        let error = validate_relationship_endpoint_types(
            relationship_type,
            source_type,
            target_type,
            "$.relationships[*]",
        )
        .unwrap_err();
        assert_eq!(
            error.code(),
            "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED"
        );
    }
}
```

If an allowed or rejected case fails, verify against the official ArchiMate 3.2 source noted in `2026-05-12-dediren-archimate-32-relationship-validation.md` before changing either the test or production rule data.

- [ ] **Step 2: Add rule-table invariants**

Add:

```rust
#[test]
fn derived_relationship_triples_are_accepted_by_validator() {
    for triple in relationship_endpoint_triples() {
        validate_relationship_endpoint_types(
            triple.relationship_type,
            triple.source_type,
            triple.target_type,
            "$.relationships[*]",
        )
        .unwrap_or_else(|error| panic!("derived triple should validate: {triple:?}: {error:?}"));
    }
}

#[test]
fn derived_relationship_triples_cover_every_relationship_type() {
    let covered: std::collections::BTreeSet<&str> = relationship_endpoint_triples()
        .iter()
        .map(|triple| triple.relationship_type)
        .collect();
    for relationship_type in RELATIONSHIP_TYPES {
        assert!(
            covered.contains(relationship_type),
            "relationship type {relationship_type} should have at least one derived endpoint triple"
        );
    }
}
```

- [ ] **Step 3: Verify**

Run:

```bash
cargo fmt --all -- --check
cargo test -p dediren-archimate --test relationship_rules --locked
git diff --check
```

---

### Task 6: Convert Java ELK Helper Tests To Parsed JSON Assertions

**Files:**
- Create: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/EnvelopeAssertions.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java`

- [ ] **Step 1: Add parsed JSON helper**

Create `EnvelopeAssertions.java`:

```java
package dev.dediren.elk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

final class EnvelopeAssertions {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EnvelopeAssertions() {}

    static JsonNode parseJson(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    static JsonNode okData(String text) throws Exception {
        JsonNode envelope = parseJson(text);
        assertEquals("envelope.schema.v1", envelope.path("envelope_schema_version").asText());
        assertEquals("ok", envelope.path("status").asText());
        assertTrue(envelope.path("diagnostics").isArray());
        assertEquals(0, envelope.path("diagnostics").size());
        return envelope.path("data");
    }

    static JsonNode errorEnvelope(String text, String expectedCode) throws Exception {
        JsonNode envelope = parseJson(text);
        assertEquals("envelope.schema.v1", envelope.path("envelope_schema_version").asText());
        assertEquals("error", envelope.path("status").asText());
        assertTrue(envelope.path("diagnostics").isArray());
        assertFalse(envelope.path("diagnostics").isEmpty());
        List<String> codes = diagnosticCodes(envelope);
        assertTrue(
            codes.contains(expectedCode),
            "expected diagnostic code " + expectedCode + ", got " + codes
        );
        return envelope;
    }

    static List<String> diagnosticCodes(JsonNode envelope) {
        List<String> codes = new ArrayList<>();
        for (JsonNode diagnostic : envelope.path("diagnostics")) {
            codes.add(diagnostic.path("code").asText());
        }
        return codes;
    }
}
```

- [ ] **Step 2: Rewrite Java tests**

In `JsonContractsTest.java`, replace string `contains` assertions with:

```java
JsonNode data = EnvelopeAssertions.okData(envelope);
assertEquals("layout-result.schema.v1", data.path("layout_result_schema_version").asText());
```

In `MainTest.java`:

- Use `EnvelopeAssertions.errorEnvelope(text, "DEDIREN_ELK_INPUT_INVALID_JSON")` for invalid JSON and input-contract errors.
- Use `EnvelopeAssertions.errorEnvelope(text, "DEDIREN_ELK_LAYOUT_FAILED")` for layout failures that deterministically emit that code.
- For the width-overflow test that currently accepts either layout failure or input-invalid diagnostics, parse the envelope and assert the code set structurally:

```java
JsonNode envelope = EnvelopeAssertions.parseJson(text);
assertEquals("error", envelope.path("status").asText());
List<String> codes = EnvelopeAssertions.diagnosticCodes(envelope);
assertTrue(
    codes.contains("DEDIREN_ELK_LAYOUT_FAILED")
        || codes.contains("DEDIREN_ELK_INPUT_INVALID_JSON"),
    "expected ELK layout or input diagnostic, got " + codes
);
```

For successful layout tests:

```java
JsonNode data = EnvelopeAssertions.okData(text);
assertEquals("layout-result.schema.v1", data.path("layout_result_schema_version").asText());
assertEquals("client-calls-api", data.path("edges").get(0).path("id").asText());
```

- [ ] **Step 3: Verify**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
git diff --check
```

Expected: Java helper tests pass through the existing build script.

---

### Task 7: Record The Final Test-Quality Gap Matrix

**Files:**
- Create: `docs/superpowers/plans/2026-05-12-dediren-test-quality-gap-matrix.md`

- [ ] **Step 1: Add the matrix**

Create the file with this structure:

```markdown
# Dediren Test Quality Gap Matrix

Date: 2026-05-12

## Scope

This matrix records the post-cleanup test confidence for the test-quality cleanup series. It is not a product contract and does not change user-facing behavior.

## Matrix

| Area | Primary Tests | Confidence After Cleanup | Remaining Gap | Action |
| --- | --- | --- | --- | --- |
| CLI command envelopes | `crates/dediren-cli/tests/cli_*.rs` | Parsed JSON envelope assertions cover status, data, diagnostics, and plugin boundary failures. | Pipeline tests remain smoke-level by design. | Keep narrow CLI tests authoritative. |
| CLI fixture pipeline | `crates/dediren-cli/tests/cli_pipeline.rs` non-ignored tests | Shared binary setup and SVG XML assertions cover fixture-backed happy paths. | Fixture-backed layout does not prove ELK geometry quality. | Keep this lane lean and deterministic. |
| ELK fixture adapter lane | `crates/dediren-cli/tests/cli_layout.rs`, `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs` | Fixture and fake-command tests prove wrapper behavior, envelope normalization, and no-Java failure handling. | This lane cannot prove grouped routing quality or final render quality. | Do not expand fixture coverage as a substitute for real helper evidence. |
| Real ELK render lane | `crates/dediren-cli/tests/cli_pipeline.rs::real_elk_pipeline_renders_rich_source`, ignored real-helper layout tests | Real helper output is validated by `validate-layout` and rendered to SVG for artifact inspection. | Opt-in because SDKMAN/Gradle helper setup is required and real helper runs must stay serial. | Run serially for this cleanup closeout, ELK changes, release checks, and suspected layout regressions. |
| Generic graph plugin | `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs` | Parsed plugin envelopes cover projection output and ArchiMate validation failures. | Tests still use fixture-scale graphs. | Add larger graph fixture only when projection logic changes. |
| OEF export plugin | `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs` | Parsed envelopes plus XML parsing cover success and ArchiMate failures. | XML assertions are structural, not visual. | Keep OEF semantics under architecture review when export behavior changes. |
| SVG renderer plugin | `crates/dediren-plugin-svg-render/tests/svg_render_plugin/*.rs` | Split modules cover render contracts, ArchiMate nodes, relationships, labels, viewBox, and routes. | The suite is still integration-heavy. | Add smaller unit tests only when renderer internals become stable enough to expose. |
| ArchiMate relationship rules | `crates/dediren-archimate/tests/relationship_rules.rs` | Curated oracle cases plus rule-table invariants cover drift and validator consistency. | The test does not reproduce the full licensed standard table. | Verify against official ArchiMate 3.2 source before rule-data changes. |
| Java ELK helper | `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk` | Parsed JSON assertions cover helper envelopes and deterministic diagnostics. | Real helper behavior still depends on Java/Gradle availability. | Use the existing helper build script for ELK changes and release checks. |

## Accepted Residual Risk

- The broad workspace suite still has expensive integration tests; keep narrow package tests as the first signal during development.
- Fixture-backed ELK tests are intentionally lean and are not accepted as evidence of real layout/render quality.
- Generated render artifacts remain untracked; report their paths when useful rather than committing them.
- The ignored real-ELK Rust lane remains opt-in because it requires the SDKMAN/Gradle helper setup documented in `AGENTS.md`; if it cannot run, record the exact blocker instead of replacing it with fixture results.
```

- [ ] **Step 2: Verify the docs-only artifact**

Run:

```bash
git diff --check
```

---

### Task 8: Final Verification And Audit

**Files:**
- All files changed by Tasks 1-7.

- [ ] **Step 1: Run narrow and broad verification**

Run:

```bash
cargo fmt --all -- --check
cargo test -p dediren --test cli_pipeline --locked
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin --locked
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin --locked
cargo test -p dediren-plugin-svg-render --test svg_render_plugin --locked
cargo test -p dediren-archimate --test relationship_rules --locked
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test --locked -p dediren-plugin-elk-layout --test elk_layout_plugin elk_plugin_invokes_real_java_helper -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_layout layout_invokes_real_java_elk_helper -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_layout validate_layout_accepts_real_grouped_cross_group_route -- --ignored --exact --test-threads=1
cargo test --locked -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact --test-threads=1
cargo test --workspace --locked
git diff --check
```

- [ ] **Step 2: Run the required audit gate**

Run `souroldgeezer-audit:test-quality-audit` deep review over changed files plus the real ELK lane files:

- `crates/dediren-cli/tests/common/mod.rs`
- `crates/dediren-cli/tests/cli_pipeline.rs`
- `crates/dediren-cli/tests/cli_layout.rs`
- `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`
- `crates/dediren-plugin-generic-graph/tests/common/mod.rs`
- `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`
- `crates/dediren-plugin-archimate-oef-export/tests/common/mod.rs`
- `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`
- `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- `crates/dediren-plugin-svg-render/tests/svg_render_plugin/*.rs`
- `crates/dediren-archimate/tests/relationship_rules.rs`
- `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/*.java`
- `docs/superpowers/plans/2026-05-12-dediren-test-quality-gap-matrix.md`

Record block/warn/info findings in the handoff. Fix block findings before completion. Fix warn/info findings or explicitly accept them.

- [ ] **Step 3: Final git hygiene**

Run:

```bash
git status --short --branch
git diff --stat
```

Review each changed file before staging. Stage only intentional files. Do not stage generated render artifacts, Gradle outputs, `target/`, or unrelated worktree changes.

Suggested commit sequence:

```bash
git add docs/superpowers/plans/2026-05-12-dediren-test-quality-rest.md crates/dediren-cli/tests/common/mod.rs crates/dediren-cli/tests/cli_pipeline.rs
git commit -m "test: share CLI pipeline contract helpers"

git add crates/dediren-plugin-generic-graph/tests/common/mod.rs crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs crates/dediren-plugin-archimate-oef-export/tests/common/mod.rs crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs
git commit -m "test: assert plugin command contracts structurally"

git add crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs crates/dediren-plugin-svg-render/tests/svg_render_plugin
git commit -m "test: split SVG renderer integration suite"

git add crates/dediren-archimate/tests/relationship_rules.rs
git commit -m "test: strengthen ArchiMate relationship rule oracle"

git add crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/EnvelopeAssertions.java crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java
git commit -m "test: parse ELK helper envelopes"

git add docs/superpowers/plans/2026-05-12-dediren-test-quality-gap-matrix.md
git commit -m "docs: record test quality gap matrix"
```

If a task stays small enough to merge with its neighbor without hiding intent, combine adjacent commits. Keep the SVG file split separate because it will be large even when behavior-neutral.
