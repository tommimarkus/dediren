# Dediren UML Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add UML as a first-party Dediren semantic profile that mirrors the current ArchiMate profile with validation, projection, SVG notation, and UML/XMI export.

**Architecture:** Keep `dediren-core` contract-first and profile-neutral. Put UML vocabulary and legality checks in a new `dediren-uml` crate, wire `generic-graph` to `semantic_profile = "uml"`, extend `svg-render` with UML notation policy/rendering, and add a process-boundary `uml-xmi` export plugin. Keep Dediren JSON as the authored source and treat XMI as compatibility output.

**Tech Stack:** Rust workspace, Serde, JSON Schema, `assert_cmd`, `roxmltree`, `quick-xml`, existing Dediren plugin runtime.

---

## Scope

This plan implements the first UML vertical slice from the approved spec:

- `uml` semantic profile on `generic-graph`;
- `uml-class`, `uml-data`, and `uml-activity` view kinds;
- UML source fixtures, render metadata fixtures, SVG render policy, and layout fixture;
- UML validation for the initial class/data/activity subset;
- UML render metadata projection and UML SVG notation;
- UML/XMI export plugin for model interchange;
- docs, version bump, bundle inclusion, tests, and audit gates.

UMLDI geometry export is not in this plan. The `uml-xmi` exporter emits UML/XMI model content and may omit diagram interchange geometry while still consuming layout data through the standard export request contract.

## File Structure

Create:

- `crates/dediren-uml/Cargo.toml` - first-party UML vocabulary and validation crate.
- `crates/dediren-uml/src/lib.rs` - supported UML type constants, property parsing, profile validation, export helpers.
- `crates/dediren-uml/tests/uml_validation.rs` - unit tests for UML vocabulary, relationship legality, multiplicity, and view-kind validation helpers.
- `crates/dediren-plugin-uml-xmi-export/Cargo.toml` - UML/XMI export plugin crate.
- `crates/dediren-plugin-uml-xmi-export/src/main.rs` - process-boundary exporter that reads `export-request.schema.v1` and emits `uml-xmi+xml`.
- `crates/dediren-plugin-uml-xmi-export/tests/common/mod.rs` - plugin test helpers.
- `crates/dediren-plugin-uml-xmi-export/tests/uml_xmi_export_plugin.rs` - direct plugin tests.
- `fixtures/source/valid-uml-basic.json` - canonical UML fixture covering class/data/activity views.
- `fixtures/render-metadata/uml-basic.json` - projected UML render metadata fixture.
- `fixtures/layout-result/uml-basic.json` - deterministic UML layout fixture for render/export tests.
- `fixtures/render-policy/uml-svg.json` - UML SVG notation policy.
- `fixtures/export-policy/default-uml-xmi.json` - UML/XMI export policy.
- `fixtures/export/uml-basic.xmi` - expected UML/XMI output for the fixture.
- `fixtures/plugins/uml-xmi.manifest.json` - static first-party plugin manifest.
- `schemas/uml-xmi-export-policy.schema.json` - public export policy schema.
- `crates/dediren-plugin-svg-render/tests/svg_render_plugin/uml_nodes.rs` - UML node notation tests.
- `crates/dediren-plugin-svg-render/tests/svg_render_plugin/uml_relationships.rs` - UML relationship notation tests.
- `crates/dediren-plugin-svg-render/tests/svg_render_plugin/uml_activity.rs` - UML activity notation tests.

Modify:

- `Cargo.toml` - add `dediren-uml` and `dediren-plugin-uml-xmi-export` workspace members/dependencies; bump workspace version to `0.12.0`.
- `Cargo.lock` - refresh workspace package versions.
- `schemas/model.schema.json` - allow `semantic_profile = "uml"` and add optional `kind` on generic-graph views.
- `schemas/svg-render-policy.schema.json` - add UML decorators and keep style schema profile-neutral.
- `schemas/export-request.schema.json` - accept export policy objects for OEF and UML/XMI.
- `schemas/export-result.schema.json` - accept `archimate-oef+xml` and `uml-xmi+xml`.
- `schemas/bundle.schema.json` - inspect for required plugin metadata assumptions and update only if the current schema constrains first-party plugin ids or counts.
- `crates/dediren-contracts/src/lib.rs` - add `GenericGraphSemanticProfile::Uml`, optional `GenericGraphView.kind`, UML policy structs, generic export request support, and UML SVG decorators.
- `crates/dediren-contracts/tests/schema_contracts.rs` - schema coverage for UML fixtures/policies/manifests.
- `crates/dediren-contracts/tests/contract_roundtrip.rs` - Rust contract round-trips for UML profile, view kind, policy, and decorators.
- `crates/dediren-plugin-generic-graph/Cargo.toml` - depend on `dediren-uml`.
- `crates/dediren-plugin-generic-graph/src/main.rs` - validate/project UML profile.
- `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs` - direct plugin tests for UML validation and projection.
- `crates/dediren-plugin-svg-render/Cargo.toml` - depend on `dediren-uml`.
- `crates/dediren-plugin-svg-render/src/main.rs` - validate UML policy/metadata and render UML notation.
- `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs` - include UML test modules.
- `crates/dediren-cli/tests/common/mod.rs` - build the UML/XMI plugin binary.
- `crates/dediren-cli/tests/cli_validate.rs` - CLI semantic validation tests for UML.
- `crates/dediren-cli/tests/cli_project.rs` - CLI UML layout-request and render-metadata projection tests.
- `crates/dediren-cli/tests/cli_render.rs` - CLI UML SVG render test.
- `crates/dediren-cli/tests/cli_export.rs` - CLI UML/XMI export test.
- `crates/dediren-cli/tests/cli_pipeline.rs` - end-to-end fixture-mode UML pipeline.
- `crates/dediren-cli/tests/plugin_compat.rs` - first-party manifest/runtime compatibility for `uml-xmi`.
- `xtask/src/main.rs` - include the UML/XMI binary and plugin id in the distribution bundle.
- `xtask/tests/dist.rs` - assert the bundle includes the UML/XMI manifest/binary and metadata entry.
- `README.md` - document UML profile commands, fixtures, and export.
- `docs/agent-usage.md` - add a token-efficient UML authoring loop and runtime probes.
- `fixtures/plugins/*.manifest.json` - bump first-party plugin versions to `0.12.0`.

## Versioning

Use `0.12.0`. This is a minor bump under the repo policy because the change adds a compatible first-party profile, schemas, plugin, fixtures, bundle content, and public commands/outputs without removing existing surfaces.

---

### Task 1: Publish UML Contract Fixtures And Schema Shape

**Files:**
- Create: `fixtures/source/valid-uml-basic.json`
- Create: `fixtures/render-metadata/uml-basic.json`
- Create: `fixtures/layout-result/uml-basic.json`
- Create: `fixtures/render-policy/uml-svg.json`
- Create: `fixtures/export-policy/default-uml-xmi.json`
- Create: `fixtures/plugins/uml-xmi.manifest.json`
- Create: `schemas/uml-xmi-export-policy.schema.json`
- Modify: `schemas/model.schema.json`
- Modify: `schemas/svg-render-policy.schema.json`
- Modify: `schemas/export-request.schema.json`
- Modify: `schemas/export-result.schema.json`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`

- [ ] **Step 1: Add the canonical UML source fixture**

Create `fixtures/source/valid-uml-basic.json`:

```json
{
  "model_schema_version": "model.schema.v1",
  "required_plugins": [
    { "id": "generic-graph", "version": "0.12.0" },
    { "id": "uml-xmi", "version": "0.12.0" }
  ],
  "nodes": [
    {
      "id": "pkg-orders",
      "type": "Package",
      "label": "Orders",
      "properties": {
        "uml": { "kind": "package" }
      }
    },
    {
      "id": "class-order",
      "type": "Class",
      "label": "Order",
      "properties": {
        "uml": {
          "package": "pkg-orders",
          "attributes": [
            { "name": "id", "type": "OrderId", "visibility": "public", "multiplicity": "1" },
            { "name": "status", "type": "OrderStatus", "visibility": "private", "multiplicity": "1" }
          ],
          "operations": [
            { "name": "submit", "visibility": "public", "parameters": [], "return_type": "void" }
          ]
        }
      }
    },
    {
      "id": "enum-order-status",
      "type": "Enumeration",
      "label": "OrderStatus",
      "properties": {
        "uml": {
          "literals": ["Draft", "Submitted", "Cancelled"]
        }
      }
    },
    {
      "id": "class-order-line",
      "type": "Class",
      "label": "OrderLine",
      "properties": {
        "uml": {
          "attributes": [
            { "name": "sku", "type": "String", "visibility": "public", "multiplicity": "1" },
            { "name": "quantity", "type": "Integer", "visibility": "public", "multiplicity": "1" }
          ],
          "operations": []
        }
      }
    },
    {
      "id": "activity-submit-order",
      "type": "Activity",
      "label": "Submit Order",
      "properties": {
        "uml": { "partitions": ["Customer", "Order Service"] }
      }
    },
    {
      "id": "initial-submit",
      "type": "InitialNode",
      "label": "",
      "properties": { "uml": { "activity": "activity-submit-order", "partition": "Customer" } }
    },
    {
      "id": "action-enter-order",
      "type": "Action",
      "label": "Enter order",
      "properties": { "uml": { "activity": "activity-submit-order", "partition": "Customer" } }
    },
    {
      "id": "decision-valid",
      "type": "DecisionNode",
      "label": "Valid?",
      "properties": { "uml": { "activity": "activity-submit-order", "partition": "Order Service" } }
    },
    {
      "id": "action-submit",
      "type": "Action",
      "label": "Submit",
      "properties": { "uml": { "activity": "activity-submit-order", "partition": "Order Service" } }
    },
    {
      "id": "final-submit",
      "type": "ActivityFinalNode",
      "label": "",
      "properties": { "uml": { "activity": "activity-submit-order", "partition": "Order Service" } }
    }
  ],
  "relationships": [
    {
      "id": "order-has-lines",
      "type": "Composition",
      "source": "class-order",
      "target": "class-order-line",
      "label": "lines",
      "properties": {
        "uml": {
          "source_multiplicity": "1",
          "target_multiplicity": "1..*",
          "source_role": "order",
          "target_role": "lines"
        }
      }
    },
    {
      "id": "order-status-dependency",
      "type": "Dependency",
      "source": "class-order",
      "target": "enum-order-status",
      "label": "uses",
      "properties": {}
    },
    {
      "id": "flow-start-enter",
      "type": "ControlFlow",
      "source": "initial-submit",
      "target": "action-enter-order",
      "label": "",
      "properties": {}
    },
    {
      "id": "flow-enter-valid",
      "type": "ControlFlow",
      "source": "action-enter-order",
      "target": "decision-valid",
      "label": "",
      "properties": {}
    },
    {
      "id": "flow-valid-submit",
      "type": "ControlFlow",
      "source": "decision-valid",
      "target": "action-submit",
      "label": "yes",
      "properties": { "uml": { "guard": "valid" } }
    },
    {
      "id": "flow-submit-final",
      "type": "ControlFlow",
      "source": "action-submit",
      "target": "final-submit",
      "label": "",
      "properties": {}
    }
  ],
  "plugins": {
    "generic-graph": {
      "semantic_profile": "uml",
      "views": [
        {
          "id": "class-view",
          "label": "Orders Class Model",
          "kind": "uml-class",
          "nodes": ["pkg-orders", "class-order", "class-order-line", "enum-order-status"],
          "relationships": ["order-has-lines", "order-status-dependency"],
          "groups": [
            {
              "id": "orders-package-boundary",
              "label": "Orders",
              "members": ["class-order", "class-order-line", "enum-order-status"],
              "role": "semantic-boundary",
              "semantic_source_id": "pkg-orders"
            }
          ],
          "layout_preferences": {
            "direction": "right",
            "density": "readable",
            "routing": { "style": "orthogonal", "profile": "readable", "endpoint_merging": "off" }
          }
        },
        {
          "id": "data-view",
          "label": "Orders Data Model",
          "kind": "uml-data",
          "nodes": ["class-order", "class-order-line", "enum-order-status"],
          "relationships": ["order-has-lines", "order-status-dependency"],
          "layout_preferences": {
            "direction": "right",
            "density": "readable",
            "routing": { "style": "orthogonal", "profile": "readable", "endpoint_merging": "off" }
          }
        },
        {
          "id": "activity-view",
          "label": "Submit Order Activity",
          "kind": "uml-activity",
          "nodes": [
            "initial-submit",
            "action-enter-order",
            "decision-valid",
            "action-submit",
            "final-submit"
          ],
          "relationships": [
            "flow-start-enter",
            "flow-enter-valid",
            "flow-valid-submit",
            "flow-submit-final"
          ],
          "layout_preferences": {
            "direction": "right",
            "density": "spacious",
            "routing": { "style": "orthogonal", "profile": "spacious", "endpoint_merging": "off" }
          }
        }
      ]
    }
  }
}
```

- [ ] **Step 2: Add deterministic UML render metadata fixture**

Create `fixtures/render-metadata/uml-basic.json`:

```json
{
  "render_metadata_schema_version": "render-metadata.schema.v1",
  "semantic_profile": "uml",
  "nodes": {
    "class-order": { "type": "Class", "source_id": "class-order" },
    "class-order-line": { "type": "Class", "source_id": "class-order-line" },
    "enum-order-status": { "type": "Enumeration", "source_id": "enum-order-status" },
    "pkg-orders": { "type": "Package", "source_id": "pkg-orders" }
  },
  "edges": {
    "order-has-lines": { "type": "Composition", "source_id": "order-has-lines" },
    "order-status-dependency": { "type": "Dependency", "source_id": "order-status-dependency" }
  },
  "groups": {
    "orders-package-boundary": { "type": "Package", "source_id": "pkg-orders" }
  }
}
```

- [ ] **Step 3: Add deterministic UML layout result fixture**

Create `fixtures/layout-result/uml-basic.json`:

```json
{
  "layout_result_schema_version": "layout-result.schema.v1",
  "view_id": "class-view",
  "nodes": [
    { "id": "class-order", "source_id": "class-order", "projection_id": "class-order", "x": 80.0, "y": 80.0, "width": 220.0, "height": 120.0, "label": "Order" },
    { "id": "class-order-line", "source_id": "class-order-line", "projection_id": "class-order-line", "x": 420.0, "y": 80.0, "width": 220.0, "height": 120.0, "label": "OrderLine" },
    { "id": "enum-order-status", "source_id": "enum-order-status", "projection_id": "enum-order-status", "x": 80.0, "y": 280.0, "width": 220.0, "height": 100.0, "label": "OrderStatus" }
  ],
  "edges": [
    { "id": "order-has-lines", "source": "class-order", "target": "class-order-line", "source_id": "order-has-lines", "projection_id": "order-has-lines", "points": [{ "x": 300.0, "y": 140.0 }, { "x": 420.0, "y": 140.0 }], "label": "lines" },
    { "id": "order-status-dependency", "source": "class-order", "target": "enum-order-status", "source_id": "order-status-dependency", "projection_id": "order-status-dependency", "points": [{ "x": 190.0, "y": 200.0 }, { "x": 190.0, "y": 280.0 }], "label": "uses" }
  ],
  "groups": [
    { "id": "orders-package-boundary", "source_id": "pkg-orders", "projection_id": "orders-package-boundary", "provenance": { "semantic_backed": { "source_id": "pkg-orders" } }, "x": 40.0, "y": 40.0, "width": 640.0, "height": 380.0, "members": ["class-order", "class-order-line", "enum-order-status"], "label": "Orders" }
  ],
  "warnings": []
}
```

- [ ] **Step 4: Add UML SVG render policy fixture**

Create `fixtures/render-policy/uml-svg.json`:

```json
{
  "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
  "semantic_profile": "uml",
  "page": { "width": 1200, "height": 800 },
  "margin": { "top": 32, "right": 32, "bottom": 32, "left": 32 },
  "style": {
    "background": { "fill": "#ffffff" },
    "font": { "family": "Inter, Arial, sans-serif", "size": 14 },
    "node": { "fill": "#ffffff", "stroke": "#1f2937", "stroke_width": 1.4, "label_fill": "#111827" },
    "edge": { "stroke": "#374151", "stroke_width": 1.3, "label_fill": "#111827" },
    "group": { "fill": "#f8fafc", "stroke": "#64748b", "stroke_width": 1.2, "label_fill": "#334155", "label_size": 13, "decorator": "uml_package" },
    "node_type_overrides": {
      "Package": { "decorator": "uml_package", "fill": "#f8fafc", "stroke": "#64748b" },
      "Class": { "decorator": "uml_class", "fill": "#ffffff", "stroke": "#1f2937" },
      "Interface": { "decorator": "uml_interface", "fill": "#ffffff", "stroke": "#1f2937" },
      "DataType": { "decorator": "uml_data_type", "fill": "#ffffff", "stroke": "#1f2937" },
      "Enumeration": { "decorator": "uml_enumeration", "fill": "#fff7ed", "stroke": "#c2410c" },
      "Activity": { "decorator": "uml_activity", "fill": "#eef2ff", "stroke": "#4338ca" },
      "Action": { "decorator": "uml_action", "fill": "#eef2ff", "stroke": "#4338ca", "rx": 18 },
      "InitialNode": { "decorator": "uml_initial_node", "fill": "#111827", "stroke": "#111827" },
      "ActivityFinalNode": { "decorator": "uml_activity_final_node", "fill": "#ffffff", "stroke": "#111827" },
      "DecisionNode": { "decorator": "uml_decision_node", "fill": "#ffffff", "stroke": "#4338ca" },
      "MergeNode": { "decorator": "uml_merge_node", "fill": "#ffffff", "stroke": "#4338ca" },
      "ForkNode": { "decorator": "uml_fork_node", "fill": "#111827", "stroke": "#111827" },
      "JoinNode": { "decorator": "uml_join_node", "fill": "#111827", "stroke": "#111827" },
      "ObjectNode": { "decorator": "uml_object_node", "fill": "#f0fdf4", "stroke": "#15803d" }
    },
    "edge_type_overrides": {
      "Association": { "marker_end": "none" },
      "Composition": { "marker_start": "filled_diamond" },
      "Aggregation": { "marker_start": "hollow_diamond" },
      "Generalization": { "marker_end": "hollow_triangle" },
      "Realization": { "marker_end": "hollow_triangle", "line_style": "dashed" },
      "Dependency": { "marker_end": "open_arrow", "line_style": "dashed" },
      "ControlFlow": { "marker_end": "filled_arrow" },
      "ObjectFlow": { "marker_end": "filled_arrow", "line_style": "dashed" }
    }
  }
}
```

- [ ] **Step 5: Add UML/XMI export policy schema and fixture**

Create `schemas/uml-xmi-export-policy.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://dediren.dev/schemas/uml-xmi-export-policy.schema.json",
  "type": "object",
  "additionalProperties": false,
  "required": ["uml_xmi_export_policy_schema_version", "model_identifier", "model_name"],
  "properties": {
    "uml_xmi_export_policy_schema_version": { "const": "uml-xmi-export-policy.schema.v1" },
    "model_identifier": { "type": "string", "minLength": 1 },
    "model_name": { "type": "string", "minLength": 1 },
    "xmi_version": { "enum": ["2.5.1"] },
    "uml_version": { "enum": ["2.5.1"] }
  }
}
```

Create `fixtures/export-policy/default-uml-xmi.json`:

```json
{
  "uml_xmi_export_policy_schema_version": "uml-xmi-export-policy.schema.v1",
  "model_identifier": "id-dediren-uml-basic-model",
  "model_name": "Dediren UML Basic",
  "xmi_version": "2.5.1",
  "uml_version": "2.5.1"
}
```

- [ ] **Step 6: Add the UML/XMI plugin manifest**

Create `fixtures/plugins/uml-xmi.manifest.json`:

```json
{
  "plugin_manifest_schema_version": "plugin-manifest.schema.v1",
  "id": "uml-xmi",
  "version": "0.12.0",
  "executable": "dediren-plugin-uml-xmi-export",
  "capabilities": ["export"]
}
```

- [ ] **Step 7: Extend schema enums**

In `schemas/model.schema.json`, change the `genericGraphPluginData.semantic_profile` property to:

```json
"semantic_profile": { "enum": ["generic-graph", "archimate", "uml"] }
```

In `schemas/model.schema.json`, add this property under `genericGraphView.properties`:

```json
"kind": {
  "enum": ["generic", "archimate", "uml-class", "uml-data", "uml-activity"]
}
```

Do not add `kind` to `genericGraphView.required`; existing files stay valid.

In `schemas/export-result.schema.json`, replace the current artifact kind `const` with:

```json
"artifact_kind": { "enum": ["archimate-oef+xml", "uml-xmi+xml"] }
```

In `schemas/export-request.schema.json`, replace the policy property with:

```json
"policy": {
  "oneOf": [
    { "$ref": "oef-export-policy.schema.json" },
    { "$ref": "uml-xmi-export-policy.schema.json" }
  ]
}
```

If the JSON Schema validator cannot resolve external refs from this location, inline the two policy definitions under `$defs` and reference them with `#/$defs/oefExportPolicy` and `#/$defs/umlXmiExportPolicy`.

- [ ] **Step 8: Extend UML decorator schema enum**

In `schemas/svg-render-policy.schema.json`, append these strings to the `decorator` enum:

```json
"uml_package",
"uml_class",
"uml_interface",
"uml_data_type",
"uml_enumeration",
"uml_activity",
"uml_action",
"uml_initial_node",
"uml_activity_final_node",
"uml_decision_node",
"uml_merge_node",
"uml_fork_node",
"uml_join_node",
"uml_object_node"
```

- [ ] **Step 9: Add failing schema tests**

In `crates/dediren-contracts/tests/schema_contracts.rs`, add `schemas/uml-xmi-export-policy.schema.json` to `PUBLIC_SCHEMA_PATHS`, add `fixtures/plugins/uml-xmi.manifest.json` to `FIRST_PARTY_PLUGIN_MANIFEST_PATHS`, and add `fixtures/source/valid-uml-basic.json` to `SOURCE_FIXTURE_PATHS`.

Add these tests:

```rust
#[test]
fn uml_source_matches_model_schema() {
    assert_valid(
        "schemas/model.schema.json",
        "fixtures/source/valid-uml-basic.json",
    );
}

#[test]
fn uml_svg_policy_matches_schema() {
    assert_valid(
        "schemas/svg-render-policy.schema.json",
        "fixtures/render-policy/uml-svg.json",
    );
}

#[test]
fn uml_render_metadata_matches_schema() {
    assert_valid(
        "schemas/render-metadata.schema.json",
        "fixtures/render-metadata/uml-basic.json",
    );
}

#[test]
fn uml_xmi_export_policy_matches_schema() {
    assert_valid(
        "schemas/uml-xmi-export-policy.schema.json",
        "fixtures/export-policy/default-uml-xmi.json",
    );
}

#[test]
fn export_result_schema_accepts_uml_xmi_artifact_kind() {
    assert_json_valid(
        "schemas/export-result.schema.json",
        json!({
            "export_result_schema_version": "export-result.schema.v1",
            "artifact_kind": "uml-xmi+xml",
            "content": "<xmi:XMI/>"
        }),
    );
}
```

- [ ] **Step 10: Run contract schema tests and confirm the expected failures**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts uml_source_matches_model_schema -- --exact
cargo test -p dediren-contracts --test schema_contracts uml_svg_policy_matches_schema -- --exact
cargo test -p dediren-contracts --test schema_contracts export_result_schema_accepts_uml_xmi_artifact_kind -- --exact
```

Expected: tests fail before schema changes, then pass after Steps 7 and 8.

- [ ] **Step 11: Run the full schema contract lane**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts
```

Expected: all schema contract tests pass.

- [ ] **Step 12: Commit**

```bash
git add schemas fixtures crates/dediren-contracts/tests/schema_contracts.rs
git commit -m "test: add UML profile contract fixtures"
```

---

### Task 2: Add UML Contract Types And Round-Trips

**Files:**
- Modify: `crates/dediren-contracts/src/lib.rs`
- Modify: `crates/dediren-contracts/tests/contract_roundtrip.rs`

- [ ] **Step 1: Extend `GenericGraphSemanticProfile` and `GenericGraphView`**

In `crates/dediren-contracts/src/lib.rs`, change `GenericGraphSemanticProfile` to include `Uml`:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum GenericGraphSemanticProfile {
    GenericGraph,
    Archimate,
    Uml,
}

impl GenericGraphSemanticProfile {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::GenericGraph => "generic-graph",
            Self::Archimate => "archimate",
            Self::Uml => "uml",
        }
    }
}
```

Add `kind` to `GenericGraphView`:

```rust
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GenericGraphView {
    pub id: String,
    pub label: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<GenericGraphViewKind>,
    pub nodes: Vec<String>,
    pub relationships: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub layout_preferences: Option<LayoutPreferences>,
    #[serde(default)]
    pub groups: Vec<GenericGraphViewGroup>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum GenericGraphViewKind {
    Generic,
    Archimate,
    UmlClass,
    UmlData,
    UmlActivity,
}
```

- [ ] **Step 2: Add UML export policy and generic export request structs**

In `crates/dediren-contracts/src/lib.rs`, add:

```rust
pub const UML_XMI_EXPORT_POLICY_SCHEMA_VERSION: &str = "uml-xmi-export-policy.schema.v1";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UmlXmiExportPolicy {
    pub uml_xmi_export_policy_schema_version: String,
    pub model_identifier: String,
    pub model_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub xmi_version: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub uml_version: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ExportRequest {
    pub export_request_schema_version: String,
    pub source: SourceDocument,
    pub layout_result: LayoutResult,
    pub policy: Value,
}
```

Keep `OefExportPolicy` and `OefExportInput` available for compatibility inside the ArchiMate exporter tests until that exporter is migrated in Task 6.

- [ ] **Step 3: Add UML SVG decorators**

Append these variants to `SvgNodeDecorator`:

```rust
UmlPackage,
UmlClass,
UmlInterface,
UmlDataType,
UmlEnumeration,
UmlActivity,
UmlAction,
UmlInitialNode,
UmlActivityFinalNode,
UmlDecisionNode,
UmlMergeNode,
UmlForkNode,
UmlJoinNode,
UmlObjectNode,
```

- [ ] **Step 4: Add failing round-trip tests**

In `crates/dediren-contracts/tests/contract_roundtrip.rs`, import `GenericGraphViewKind` and `UmlXmiExportPolicy`, then add:

```rust
#[test]
fn generic_graph_uml_profile_and_view_kind_round_trip() {
    let data: GenericGraphPluginData = serde_json::from_str(
        r#"{
          "semantic_profile": "uml",
          "views": [
            {
              "id": "class-view",
              "label": "Class View",
              "kind": "uml-class",
              "nodes": ["class-order"],
              "relationships": []
            }
          ]
        }"#,
    )
    .unwrap();

    assert_eq!(Some(GenericGraphSemanticProfile::Uml), data.semantic_profile);
    assert_eq!(Some(GenericGraphViewKind::UmlClass), data.views[0].kind);
    let encoded = serde_json::to_value(&data).unwrap();
    assert_eq!(encoded["semantic_profile"], "uml");
    assert_eq!(encoded["views"][0]["kind"], "uml-class");
}

#[test]
fn uml_xmi_export_policy_round_trips() {
    let policy: UmlXmiExportPolicy = serde_json::from_str(
        r#"{
          "uml_xmi_export_policy_schema_version": "uml-xmi-export-policy.schema.v1",
          "model_identifier": "id-dediren-uml-basic-model",
          "model_name": "Dediren UML Basic",
          "xmi_version": "2.5.1",
          "uml_version": "2.5.1"
        }"#,
    )
    .unwrap();

    assert_eq!(policy.model_name, "Dediren UML Basic");
    let encoded = serde_json::to_value(&policy).unwrap();
    assert_eq!(encoded["uml_version"], "2.5.1");
}

#[test]
fn uml_svg_decorator_fields_round_trip() {
    let policy = RenderPolicy {
        svg_render_policy_schema_version: SVG_RENDER_POLICY_SCHEMA_VERSION.to_string(),
        semantic_profile: Some("uml".to_string()),
        page: Page { width: 640.0, height: 360.0 },
        margin: Margin { top: 24.0, right: 24.0, bottom: 24.0, left: 24.0 },
        style: Some(SvgStylePolicy {
            node_type_overrides: BTreeMap::from([(
                "Class".to_string(),
                SvgNodeStyle {
                    decorator: Some(SvgNodeDecorator::UmlClass),
                    ..SvgNodeStyle::default()
                },
            )]),
            edge_type_overrides: BTreeMap::from([(
                "Composition".to_string(),
                SvgEdgeStyle {
                    marker_start: Some(SvgEdgeMarkerEnd::FilledDiamond),
                    ..SvgEdgeStyle::default()
                },
            )]),
            ..SvgStylePolicy::default()
        }),
    };

    let json = serde_json::to_value(&policy).expect("serialize policy");
    assert_eq!(
        json["style"]["node_type_overrides"]["Class"]["decorator"],
        "uml_class"
    );
    let round_tripped: RenderPolicy = serde_json::from_value(json).expect("deserialize policy");
    assert_eq!(round_tripped, policy);
}
```

- [ ] **Step 5: Run round-trip tests and confirm the expected failures**

Run:

```bash
cargo test -p dediren-contracts --test contract_roundtrip generic_graph_uml_profile_and_view_kind_round_trip -- --exact
cargo test -p dediren-contracts --test contract_roundtrip uml_xmi_export_policy_round_trips -- --exact
cargo test -p dediren-contracts --test contract_roundtrip uml_svg_decorator_fields_round_trip -- --exact
```

Expected: tests fail before the contract types are added and pass after Steps 1-3.

- [ ] **Step 6: Run the full contract round-trip lane**

Run:

```bash
cargo test -p dediren-contracts --test contract_roundtrip
```

Expected: all contract round-trip tests pass.

- [ ] **Step 7: Commit**

```bash
git add crates/dediren-contracts/src/lib.rs crates/dediren-contracts/tests/contract_roundtrip.rs
git commit -m "feat: add UML profile contract types"
```

---

### Task 3: Add The `dediren-uml` Validation Crate

**Files:**
- Create: `crates/dediren-uml/Cargo.toml`
- Create: `crates/dediren-uml/src/lib.rs`
- Create: `crates/dediren-uml/tests/uml_validation.rs`
- Modify: `Cargo.toml`

- [ ] **Step 1: Add the crate to the workspace**

In root `Cargo.toml`, add `"crates/dediren-uml"` to `workspace.members` and add this workspace dependency:

```toml
dediren-uml = { path = "crates/dediren-uml" }
```

Create `crates/dediren-uml/Cargo.toml`:

```toml
[package]
name = "dediren-uml"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
rust-version.workspace = true

[dependencies]
dediren-contracts.workspace = true
serde_json.workspace = true
thiserror.workspace = true
```

- [ ] **Step 2: Write failing validation tests**

Create `crates/dediren-uml/tests/uml_validation.rs`:

```rust
use dediren_contracts::{GenericGraphPluginData, SourceDocument};

fn source_fixture() -> SourceDocument {
    serde_json::from_str(
        &std::fs::read_to_string("../../fixtures/source/valid-uml-basic.json").unwrap(),
    )
    .unwrap()
}

fn plugin_data(source: &SourceDocument) -> GenericGraphPluginData {
    serde_json::from_value(source.plugins["generic-graph"].clone()).unwrap()
}

#[test]
fn validates_uml_fixture() {
    let source = source_fixture();
    let data = plugin_data(&source);
    dediren_uml::validate_source(&source, &data).unwrap();
}

#[test]
fn rejects_unknown_uml_node_type() {
    let mut source = source_fixture();
    source.nodes[0].node_type = "Service".to_string();
    let data = plugin_data(&source);
    let error = dediren_uml::validate_source(&source, &data).unwrap_err();
    assert_eq!(error.code(), "DEDIREN_UML_ELEMENT_TYPE_UNSUPPORTED");
    assert_eq!(error.path, "$.nodes[0].type");
}

#[test]
fn rejects_invalid_uml_relationship_endpoint() {
    let mut source = source_fixture();
    let relationship = source
        .relationships
        .iter_mut()
        .find(|relationship| relationship.id == "order-has-lines")
        .unwrap();
    relationship.source = "initial-submit".to_string();
    let data = plugin_data(&source);
    let error = dediren_uml::validate_source(&source, &data).unwrap_err();
    assert_eq!(error.code(), "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
}

#[test]
fn rejects_invalid_multiplicity() {
    let mut source = source_fixture();
    source.relationships[0].properties["uml"]["target_multiplicity"] =
        serde_json::json!("many");
    let data = plugin_data(&source);
    let error = dediren_uml::validate_source(&source, &data).unwrap_err();
    assert_eq!(error.code(), "DEDIREN_UML_MULTIPLICITY_INVALID");
}

#[test]
fn rejects_class_view_with_activity_node() {
    let mut source = source_fixture();
    let data_value = source.plugins.get_mut("generic-graph").unwrap();
    data_value["views"][0]["nodes"]
        .as_array_mut()
        .unwrap()
        .push(serde_json::json!("action-submit"));
    let data = plugin_data(&source);
    let error = dediren_uml::validate_source(&source, &data).unwrap_err();
    assert_eq!(error.code(), "DEDIREN_UML_VIEW_KIND_UNSUPPORTED_ELEMENT");
}
```

- [ ] **Step 3: Run the failing crate tests**

Run:

```bash
cargo test -p dediren-uml --test uml_validation
```

Expected: Cargo fails before the crate exists, then tests fail until Step 4 implements validation.

- [ ] **Step 4: Implement UML validation helpers**

Create `crates/dediren-uml/src/lib.rs`:

```rust
use dediren_contracts::{GenericGraphPluginData, GenericGraphViewKind, SourceDocument};
use serde_json::Value;
use std::collections::BTreeMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UmlTypeKind {
    Element,
    Relationship,
    RelationshipEndpoint,
    Multiplicity,
    ViewKind,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UmlValidationError {
    pub kind: UmlTypeKind,
    pub value: String,
    pub path: String,
}

impl UmlValidationError {
    pub fn code(&self) -> &'static str {
        match self.kind {
            UmlTypeKind::Element => "DEDIREN_UML_ELEMENT_TYPE_UNSUPPORTED",
            UmlTypeKind::Relationship => "DEDIREN_UML_RELATIONSHIP_TYPE_UNSUPPORTED",
            UmlTypeKind::RelationshipEndpoint => "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
            UmlTypeKind::Multiplicity => "DEDIREN_UML_MULTIPLICITY_INVALID",
            UmlTypeKind::ViewKind => "DEDIREN_UML_VIEW_KIND_UNSUPPORTED_ELEMENT",
        }
    }

    pub fn message(&self) -> String {
        match self.kind {
            UmlTypeKind::Element => format!("unsupported UML element type: {}", self.value),
            UmlTypeKind::Relationship => format!("unsupported UML relationship type: {}", self.value),
            UmlTypeKind::RelationshipEndpoint => {
                format!("unsupported UML relationship endpoint: {}", self.value)
            }
            UmlTypeKind::Multiplicity => format!("invalid UML multiplicity: {}", self.value),
            UmlTypeKind::ViewKind => format!("view contains unsupported UML element: {}", self.value),
        }
    }
}

pub const STRUCTURAL_TYPES: &[&str] = &["Package", "Class", "Interface", "DataType", "Enumeration"];
pub const ACTIVITY_TYPES: &[&str] = &[
    "Activity",
    "Action",
    "InitialNode",
    "ActivityFinalNode",
    "DecisionNode",
    "MergeNode",
    "ForkNode",
    "JoinNode",
    "ObjectNode",
];
pub const RELATIONSHIP_TYPES: &[&str] = &[
    "Association",
    "Composition",
    "Aggregation",
    "Generalization",
    "Realization",
    "Dependency",
    "ControlFlow",
    "ObjectFlow",
];

pub fn validate_source(
    source: &SourceDocument,
    plugin_data: &GenericGraphPluginData,
) -> Result<(), UmlValidationError> {
    let mut node_types = BTreeMap::new();
    for (index, node) in source.nodes.iter().enumerate() {
        validate_element_type(&node.node_type, format!("$.nodes[{index}].type"))?;
        node_types.insert(node.id.as_str(), node.node_type.as_str());
        validate_node_multiplicities(index, &node.properties)?;
    }

    for (index, relationship) in source.relationships.iter().enumerate() {
        validate_relationship_type(
            &relationship.relationship_type,
            format!("$.relationships[{index}].type"),
        )?;
        validate_relationship_multiplicities(index, &relationship.properties)?;
        let Some(source_type) = node_types.get(relationship.source.as_str()) else {
            continue;
        };
        let Some(target_type) = node_types.get(relationship.target.as_str()) else {
            continue;
        };
        validate_relationship_endpoint_types(
            &relationship.relationship_type,
            source_type,
            target_type,
            format!("$.relationships[{index}]"),
        )?;
    }

    for (view_index, view) in plugin_data.views.iter().enumerate() {
        let Some(kind) = view.kind else {
            continue;
        };
        for node_id in &view.nodes {
            let Some(node_type) = node_types.get(node_id.as_str()) else {
                continue;
            };
            if !view_kind_allows(kind, node_type) {
                return Err(UmlValidationError {
                    kind: UmlTypeKind::ViewKind,
                    value: format!("{node_id}:{node_type}"),
                    path: format!("$.plugins.generic-graph.views[{view_index}].nodes"),
                });
            }
        }
    }

    Ok(())
}

pub fn validate_element_type(
    value: &str,
    path: impl Into<String>,
) -> Result<(), UmlValidationError> {
    if STRUCTURAL_TYPES.contains(&value) || ACTIVITY_TYPES.contains(&value) {
        Ok(())
    } else {
        Err(UmlValidationError {
            kind: UmlTypeKind::Element,
            value: value.to_string(),
            path: path.into(),
        })
    }
}

pub fn validate_relationship_type(
    value: &str,
    path: impl Into<String>,
) -> Result<(), UmlValidationError> {
    if RELATIONSHIP_TYPES.contains(&value) {
        Ok(())
    } else {
        Err(UmlValidationError {
            kind: UmlTypeKind::Relationship,
            value: value.to_string(),
            path: path.into(),
        })
    }
}

pub fn validate_relationship_endpoint_types(
    relationship_type: &str,
    source_type: &str,
    target_type: &str,
    path: impl Into<String>,
) -> Result<(), UmlValidationError> {
    let structural = STRUCTURAL_TYPES.contains(&source_type) && STRUCTURAL_TYPES.contains(&target_type);
    let activity = ACTIVITY_TYPES.contains(&source_type) && ACTIVITY_TYPES.contains(&target_type);
    let allowed = match relationship_type {
        "Association" | "Composition" | "Aggregation" | "Generalization" | "Realization" | "Dependency" => structural,
        "ControlFlow" | "ObjectFlow" => activity,
        _ => false,
    };
    if allowed {
        Ok(())
    } else {
        Err(UmlValidationError {
            kind: UmlTypeKind::RelationshipEndpoint,
            value: format!("{relationship_type}:{source_type}->{target_type}"),
            path: path.into(),
        })
    }
}

fn validate_node_multiplicities(
    node_index: usize,
    properties: &serde_json::Map<String, Value>,
) -> Result<(), UmlValidationError> {
    if let Some(attributes) = properties
        .get("uml")
        .and_then(|uml| uml.get("attributes"))
        .and_then(Value::as_array)
    {
        for (attribute_index, attribute) in attributes.iter().enumerate() {
            if let Some(value) = attribute.get("multiplicity").and_then(Value::as_str) {
                validate_multiplicity(
                    value,
                    format!("$.nodes[{node_index}].properties.uml.attributes[{attribute_index}].multiplicity"),
                )?;
            }
        }
    }
    Ok(())
}

fn validate_relationship_multiplicities(
    relationship_index: usize,
    properties: &serde_json::Map<String, Value>,
) -> Result<(), UmlValidationError> {
    for field in ["source_multiplicity", "target_multiplicity"] {
        if let Some(value) = properties
            .get("uml")
            .and_then(|uml| uml.get(field))
            .and_then(Value::as_str)
        {
            validate_multiplicity(
                value,
                format!("$.relationships[{relationship_index}].properties.uml.{field}"),
            )?;
        }
    }
    Ok(())
}

pub fn validate_multiplicity(
    value: &str,
    path: impl Into<String>,
) -> Result<(), UmlValidationError> {
    if value == "*" || is_non_negative_integer(value) {
        return Ok(());
    }
    if let Some((lower, upper)) = value.split_once("..") {
        let lower_ok = is_non_negative_integer(lower);
        let upper_ok = upper == "*" || is_non_negative_integer(upper);
        if lower_ok && upper_ok {
            return Ok(());
        }
    }
    Err(UmlValidationError {
        kind: UmlTypeKind::Multiplicity,
        value: value.to_string(),
        path: path.into(),
    })
}

fn is_non_negative_integer(value: &str) -> bool {
    !value.is_empty() && value.chars().all(|ch| ch.is_ascii_digit())
}

fn view_kind_allows(kind: GenericGraphViewKind, node_type: &str) -> bool {
    match kind {
        GenericGraphViewKind::Generic | GenericGraphViewKind::Archimate => true,
        GenericGraphViewKind::UmlClass | GenericGraphViewKind::UmlData => STRUCTURAL_TYPES.contains(&node_type),
        GenericGraphViewKind::UmlActivity => ACTIVITY_TYPES.contains(&node_type),
    }
}

pub fn is_compact_activity_node_type(value: &str) -> bool {
    matches!(value, "InitialNode" | "ActivityFinalNode" | "DecisionNode" | "MergeNode" | "ForkNode" | "JoinNode")
}
```

- [ ] **Step 5: Run crate tests**

Run:

```bash
cargo test -p dediren-uml --test uml_validation
```

Expected: all UML validation tests pass.

- [ ] **Step 6: Commit**

```bash
git add Cargo.toml crates/dediren-uml
git commit -m "feat: add UML validation crate"
```

---

### Task 4: Wire UML Validation And Projection Into `generic-graph`

**Files:**
- Modify: `crates/dediren-plugin-generic-graph/Cargo.toml`
- Modify: `crates/dediren-plugin-generic-graph/src/main.rs`
- Modify: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`
- Modify: `crates/dediren-cli/tests/cli_validate.rs`
- Modify: `crates/dediren-cli/tests/cli_project.rs`

- [ ] **Step 1: Add dependency**

In `crates/dediren-plugin-generic-graph/Cargo.toml`, add:

```toml
dediren-uml.workspace = true
```

- [ ] **Step 2: Add failing plugin tests**

In `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`, add:

```rust
#[test]
fn generic_graph_validates_uml_profile() {
    let mut cmd = plugin_command();
    let output = cmd
        .args(["validate", "--profile", "uml"])
        .write_stdin(std::fs::read_to_string(workspace_file("fixtures/source/valid-uml-basic.json")).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["semantic_profile"], "uml");
    assert_eq!(data["node_count"], 10);
    assert_eq!(data["relationship_count"], 6);
}

#[test]
fn generic_graph_rejects_invalid_uml_relationship_endpoint() {
    let mut source: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(workspace_file("fixtures/source/valid-uml-basic.json")).unwrap(),
    )
    .unwrap();
    source["relationships"][0]["source"] = serde_json::json!("initial-submit");

    let mut cmd = plugin_command();
    let output = cmd
        .args(["validate", "--profile", "uml"])
        .write_stdin(source.to_string())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
}

#[test]
fn generic_graph_projects_uml_render_metadata() {
    let mut cmd = plugin_command();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "class-view"])
        .write_stdin(std::fs::read_to_string(workspace_file("fixtures/source/valid-uml-basic.json")).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["semantic_profile"], "uml");
    assert_eq!(data["nodes"]["class-order"]["type"], "Class");
    assert_eq!(data["edges"]["order-has-lines"]["type"], "Composition");
    assert_eq!(data["groups"]["orders-package-boundary"]["type"], "Package");
}

#[test]
fn generic_graph_projects_compact_uml_activity_node_size_hints() {
    let mut cmd = plugin_command();
    let output = cmd
        .args(["project", "--target", "layout-request", "--view", "activity-view"])
        .write_stdin(std::fs::read_to_string(workspace_file("fixtures/source/valid-uml-basic.json")).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    let initial = data["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .find(|node| node["id"] == "initial-submit")
        .unwrap();
    assert_eq!(initial["width_hint"], 32.0);
    assert_eq!(initial["height_hint"], 32.0);
}
```

- [ ] **Step 3: Run failing plugin tests**

Run:

```bash
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin generic_graph_validates_uml_profile -- --exact
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin generic_graph_projects_uml_render_metadata -- --exact
```

Expected: tests fail until UML is wired.

- [ ] **Step 4: Wire profile dispatch in `main.rs`**

In `crates/dediren-plugin-generic-graph/src/main.rs`, add `dediren_uml::UmlValidationError` import handling and implement these changes:

```rust
fn validate_from_stdin(args: &[String]) -> anyhow::Result<()> {
    let Some(profile) = value_after(args, "--profile") else {
        exit_with_diagnostic(
            "DEDIREN_SEMANTIC_PROFILE_REQUIRED",
            "semantic validation requires --profile",
            None,
        );
    };

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let source: SourceDocument = serde_json::from_str(&input)?;
    let plugin_data = generic_graph_plugin_data(&source)?;

    match profile.as_str() {
        "archimate" => {
            if let Err(error) = validate_archimate_source_types(&source) {
                exit_with_archimate_type_error(error);
            }
            if let Err(error) = validate_archimate_junction_semantics(&source) {
                exit_with_diagnostic(&error.code, &error.message, Some(error.path));
            }
        }
        "uml" => {
            if let Err(error) = dediren_uml::validate_source(&source, &plugin_data) {
                exit_with_uml_validation_error(error);
            }
        }
        _ => {
            exit_with_diagnostic(
                "DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED",
                &format!("unsupported semantic profile: {profile}"),
                Some("profile".to_string()),
            );
        }
    }

    let result = SemanticValidationResult {
        semantic_validation_result_schema_version: SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION
            .to_string(),
        semantic_profile: profile,
        node_count: source.nodes.len(),
        relationship_count: source.relationships.len(),
    };
    println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
    Ok(())
}
```

Add a helper:

```rust
fn generic_graph_plugin_data(source: &SourceDocument) -> anyhow::Result<GenericGraphPluginData> {
    let plugin_value = source
        .plugins
        .get("generic-graph")
        .context("missing plugins.generic-graph")?
        .clone();
    Ok(serde_json::from_value(plugin_value)?)
}
```

Use the helper in the main projection path instead of duplicating parse logic.

After computing `semantic_profile`, add UML validation beside the ArchiMate validation:

```rust
if semantic_profile == "uml" {
    if let Err(error) = dediren_uml::validate_source(&source, &plugin_data) {
        exit_with_uml_validation_error(error);
    }
}
```

Update `layout_width_hint` and `layout_height_hint`:

```rust
fn layout_width_hint(semantic_profile: &str, source_node: &dediren_contracts::SourceNode) -> f64 {
    if semantic_profile == GenericGraphSemanticProfile::Archimate.as_str()
        && dediren_archimate::is_relationship_connector_type(&source_node.node_type)
    {
        28.0
    } else if semantic_profile == GenericGraphSemanticProfile::Uml.as_str()
        && dediren_uml::is_compact_activity_node_type(&source_node.node_type)
    {
        32.0
    } else if semantic_profile == GenericGraphSemanticProfile::Uml.as_str()
        && matches!(source_node.node_type.as_str(), "Class" | "Interface" | "DataType" | "Enumeration")
    {
        220.0
    } else {
        160.0
    }
}

fn layout_height_hint(semantic_profile: &str, source_node: &dediren_contracts::SourceNode) -> f64 {
    if semantic_profile == GenericGraphSemanticProfile::Archimate.as_str()
        && dediren_archimate::is_relationship_connector_type(&source_node.node_type)
    {
        28.0
    } else if semantic_profile == GenericGraphSemanticProfile::Uml.as_str()
        && dediren_uml::is_compact_activity_node_type(&source_node.node_type)
    {
        32.0
    } else if semantic_profile == GenericGraphSemanticProfile::Uml.as_str()
        && matches!(source_node.node_type.as_str(), "Class" | "Interface" | "DataType" | "Enumeration")
    {
        120.0
    } else {
        80.0
    }
}
```

Add:

```rust
fn exit_with_uml_validation_error(error: dediren_uml::UmlValidationError) -> ! {
    exit_with_diagnostic(error.code(), &error.message(), Some(error.path))
}
```

- [ ] **Step 5: Add CLI tests**

In `crates/dediren-cli/tests/cli_validate.rs`, add:

```rust
#[test]
fn validate_invokes_generic_graph_uml_profile() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            common::plugin_binary("dediren-plugin-generic-graph"),
        )
        .args(["validate", "--plugin", "generic-graph", "--profile", "uml", "--input"])
        .arg(workspace_file("fixtures/source/valid-uml-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["semantic_profile"], "uml");
}
```

In `crates/dediren-cli/tests/cli_project.rs`, add:

```rust
#[test]
fn project_uml_render_metadata() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            plugin_binary("dediren-plugin-generic-graph"),
        )
        .args(["project", "--target", "render-metadata", "--plugin", "generic-graph", "--view", "class-view", "--input"])
        .arg(workspace_file("fixtures/source/valid-uml-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["semantic_profile"], "uml");
    assert_eq!(data["nodes"]["class-order"]["type"], "Class");
}
```

- [ ] **Step 6: Run generic-graph and CLI tests**

Run:

```bash
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin
cargo test -p dediren --test cli_validate validate_invokes_generic_graph_uml_profile -- --exact
cargo test -p dediren --test cli_project project_uml_render_metadata -- --exact
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit**

```bash
git add crates/dediren-plugin-generic-graph crates/dediren-cli/tests/cli_validate.rs crates/dediren-cli/tests/cli_project.rs
git commit -m "feat: validate and project UML profile"
```

---

### Task 5: Render UML SVG Notation

**Files:**
- Modify: `crates/dediren-plugin-svg-render/Cargo.toml`
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/uml_nodes.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/uml_relationships.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/uml_activity.rs`
- Modify: `crates/dediren-cli/tests/cli_render.rs`

- [ ] **Step 1: Add dependency and test modules**

In `crates/dediren-plugin-svg-render/Cargo.toml`, add:

```toml
dediren-uml.workspace = true
```

In `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`, add:

```rust
#[path = "svg_render_plugin/uml_activity.rs"]
mod uml_activity;
#[path = "svg_render_plugin/uml_nodes.rs"]
mod uml_nodes;
#[path = "svg_render_plugin/uml_relationships.rs"]
mod uml_relationships;
```

- [ ] **Step 2: Add failing UML class notation tests**

Create `crates/dediren-plugin-svg-render/tests/svg_render_plugin/uml_nodes.rs`:

```rust
use super::common::{
    child_element, child_group_with_attr, render_content, semantic_group, svg_doc, workspace_file,
};

#[test]
fn svg_renderer_renders_uml_class_compartments() {
    let input = serde_json::json!({
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/uml-basic.json")).unwrap()
        ).unwrap(),
        "render_metadata": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-metadata/uml-basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap()
        ).unwrap()
    });

    let content = render_content(input);
    let doc = svg_doc(&content);
    let order = semantic_group(&doc, "data-dediren-node-id", "class-order");
    let shape = child_element(order, "rect");
    assert_eq!(shape.attribute("data-dediren-node-shape"), Some("uml_class"));
    assert!(
        child_group_with_attr(order, "data-dediren-node-decorator", "uml_class").is_some(),
        "expected UML class decorator group"
    );
    assert!(
        content.contains("id : OrderId"),
        "expected class attribute compartment text"
    );
    assert!(
        content.contains("+ submit() : void"),
        "expected class operation compartment text"
    );
}

#[test]
fn svg_renderer_renders_uml_enumeration_literals() {
    let input = serde_json::json!({
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/uml-basic.json")).unwrap()
        ).unwrap(),
        "render_metadata": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-metadata/uml-basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap()
        ).unwrap()
    });

    let content = render_content(input);
    assert!(content.contains("«enumeration»"));
    assert!(content.contains("Submitted"));
}
```

- [ ] **Step 3: Add failing UML relationship tests**

Create `crates/dediren-plugin-svg-render/tests/svg_render_plugin/uml_relationships.rs`:

```rust
use super::common::{
    child_element, render_content, semantic_group, svg_doc, workspace_file,
};

#[test]
fn svg_renderer_applies_uml_relationship_markers() {
    let input = serde_json::json!({
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/uml-basic.json")).unwrap()
        ).unwrap(),
        "render_metadata": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-metadata/uml-basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap()
        ).unwrap()
    });

    let content = render_content(input);
    let doc = svg_doc(&content);
    let composition = semantic_group(&doc, "data-dediren-edge-id", "order-has-lines");
    let path = child_element(composition, "path");
    assert_eq!(
        path.attribute("marker-start"),
        Some("url(#marker-start-order-has-lines)")
    );
    assert!(
        content.contains(r#"data-dediren-edge-marker-start="filled_diamond""#),
        "expected UML composition filled diamond"
    );
}
```

- [ ] **Step 4: Add failing UML activity notation tests**

Create `crates/dediren-plugin-svg-render/tests/svg_render_plugin/uml_activity.rs`:

```rust
use super::common::{render_content, workspace_file};

#[test]
fn svg_renderer_renders_uml_activity_node_shapes() {
    let input = serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "activity-view",
            "nodes": [
                { "id": "initial-submit", "source_id": "initial-submit", "projection_id": "initial-submit", "x": 40.0, "y": 80.0, "width": 32.0, "height": 32.0, "label": "" },
                { "id": "action-submit", "source_id": "action-submit", "projection_id": "action-submit", "x": 140.0, "y": 60.0, "width": 160.0, "height": 80.0, "label": "Submit" },
                { "id": "decision-valid", "source_id": "decision-valid", "projection_id": "decision-valid", "x": 360.0, "y": 70.0, "width": 80.0, "height": 80.0, "label": "Valid?" },
                { "id": "final-submit", "source_id": "final-submit", "projection_id": "final-submit", "x": 520.0, "y": 80.0, "width": 32.0, "height": 32.0, "label": "" }
            ],
            "edges": [],
            "groups": [],
            "warnings": []
        },
        "render_metadata": {
            "render_metadata_schema_version": "render-metadata.schema.v1",
            "semantic_profile": "uml",
            "nodes": {
                "initial-submit": { "type": "InitialNode", "source_id": "initial-submit" },
                "action-submit": { "type": "Action", "source_id": "action-submit" },
                "decision-valid": { "type": "DecisionNode", "source_id": "decision-valid" },
                "final-submit": { "type": "ActivityFinalNode", "source_id": "final-submit" }
            },
            "edges": {}
        },
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap()
        ).unwrap()
    });

    let content = render_content(input);
    assert!(content.contains(r#"data-dediren-node-shape="uml_initial_node""#));
    assert!(content.contains(r#"data-dediren-node-shape="uml_action""#));
    assert!(content.contains(r#"data-dediren-node-shape="uml_decision_node""#));
    assert!(content.contains(r#"data-dediren-node-shape="uml_activity_final_node""#));
}
```

- [ ] **Step 5: Run failing renderer tests**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_renders_uml_class_compartments -- --exact
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_applies_uml_relationship_markers -- --exact
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_renders_uml_activity_node_shapes -- --exact
```

Expected: tests fail until UML rendering is implemented.

- [ ] **Step 6: Validate UML profile metadata in renderer**

In `crates/dediren-plugin-svg-render/src/main.rs`, add UML metadata/policy validation beside the existing ArchiMate validation:

```rust
fn validate_uml_policy_types(policy: &RenderPolicy) -> Result<(), dediren_uml::UmlValidationError> {
    if policy.semantic_profile.as_deref() != Some("uml") {
        return Ok(());
    }
    if let Some(style) = &policy.style {
        for node_type in style.node_type_overrides.keys() {
            dediren_uml::validate_element_type(node_type, format!("style.node_type_overrides.{node_type}"))?;
        }
        for relationship_type in style.edge_type_overrides.keys() {
            dediren_uml::validate_relationship_type(
                relationship_type,
                format!("style.edge_type_overrides.{relationship_type}"),
            )?;
        }
        for group_type in style.group_type_overrides.keys() {
            dediren_uml::validate_element_type(group_type, format!("style.group_type_overrides.{group_type}"))?;
        }
    }
    Ok(())
}

fn validate_uml_render_metadata(metadata: &RenderMetadata) -> Result<(), dediren_uml::UmlValidationError> {
    if metadata.semantic_profile != "uml" {
        return Ok(());
    }
    for (id, selector) in &metadata.nodes {
        dediren_uml::validate_element_type(&selector.selector_type, format!("render_metadata.nodes.{id}.type"))?;
    }
    for (id, selector) in &metadata.edges {
        dediren_uml::validate_relationship_type(&selector.selector_type, format!("render_metadata.edges.{id}.type"))?;
    }
    for (id, selector) in &metadata.groups {
        dediren_uml::validate_element_type(&selector.selector_type, format!("render_metadata.groups.{id}.type"))?;
    }
    Ok(())
}

fn exit_with_uml_type_error(error: dediren_uml::UmlValidationError) -> ! {
    exit_with_error(
        error.code(),
        &error.message(),
        Some(error.path),
    )
}
```

Call these after `validate_archimate_policy_types` and `validate_archimate_render_metadata`.

- [ ] **Step 7: Render UML node shapes**

Extend node shape rendering so these decorators produce stable SVG attributes:

```rust
fn uml_node_shape(
    node: &LaidOutNode,
    style: &ResolvedNodeStyle,
    decorator: SvgNodeDecorator,
) -> Option<String> {
    match decorator {
        SvgNodeDecorator::UmlClass
        | SvgNodeDecorator::UmlInterface
        | SvgNodeDecorator::UmlDataType
        | SvgNodeDecorator::UmlEnumeration => Some(uml_compartment_node_shape(node, style, decorator)),
        SvgNodeDecorator::UmlPackage => Some(uml_package_node_shape(node, style)),
        SvgNodeDecorator::UmlAction => Some(uml_action_node_shape(node, style)),
        SvgNodeDecorator::UmlInitialNode => Some(uml_initial_node_shape(node, style)),
        SvgNodeDecorator::UmlActivityFinalNode => Some(uml_activity_final_node_shape(node, style)),
        SvgNodeDecorator::UmlDecisionNode | SvgNodeDecorator::UmlMergeNode => {
            Some(uml_diamond_node_shape(node, style, decorator))
        }
        SvgNodeDecorator::UmlForkNode | SvgNodeDecorator::UmlJoinNode => Some(uml_bar_node_shape(node, style, decorator)),
        SvgNodeDecorator::UmlObjectNode | SvgNodeDecorator::UmlActivity => Some(uml_default_rect_node_shape(node, style, decorator)),
        _ => None,
    }
}
```

Wire this before ArchiMate-specific fallbacks in the existing node shape/decorator path. Use `data-dediren-node-shape` values matching the decorator names in the tests.

- [ ] **Step 8: Render UML class compartments from source metadata**

The current render input only carries layout result plus render metadata. To render attributes/operations without putting style or geometry in source, add optional selector payload support:

1. Extend `RenderMetadataSelector` with:

```rust
#[serde(default, skip_serializing_if = "Option::is_none")]
pub properties: Option<Value>,
```

2. Update `project_render_metadata` in `generic-graph` to copy `properties.uml` into `RenderMetadataSelector.properties` for UML selectors only:

```rust
properties: if semantic_profile == "uml" {
    source_node.properties.get("uml").cloned()
} else {
    None
},
```

3. In `svg-render`, format UML attributes and operations from `selector.properties`.

Use this text format:

```text
+ id : OrderId
- status : OrderStatus
+ submit() : void
```

Visibility mapping:

```text
public -> +
private -> -
protected -> #
package -> ~
missing -> +
```

- [ ] **Step 9: Add CLI render test**

In `crates/dediren-cli/tests/cli_render.rs`, add:

```rust
#[test]
fn render_invokes_svg_plugin_with_uml_policy_and_metadata() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_SVG_RENDER",
            plugin_binary("dediren-plugin-svg-render"),
        )
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/uml-svg.json"))
        .arg("--metadata")
        .arg(workspace_file("fixtures/render-metadata/uml-basic.json"))
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/uml-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["render_result_schema_version"], "render-result.schema.v1");
    let content = data["content"].as_str().unwrap();
    let doc = svg_doc(content);
    let order = semantic_group(&doc, "data-dediren-node-id", "class-order");
    assert!(
        child_group_with_attr(order, "data-dediren-node-decorator", "uml_class").is_some(),
        "expected UML class notation"
    );
}
```

- [ ] **Step 10: Run renderer and CLI render lanes**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
cargo test -p dediren --test cli_render render_invokes_svg_plugin_with_uml_policy_and_metadata -- --exact
```

Expected: all selected tests pass.

- [ ] **Step 11: Commit**

```bash
git add crates/dediren-contracts crates/dediren-plugin-generic-graph crates/dediren-plugin-svg-render crates/dediren-cli/tests/cli_render.rs fixtures/render-metadata/uml-basic.json
git commit -m "feat: render UML SVG notation"
```

---

### Task 6: Add UML/XMI Export Plugin

**Files:**
- Create: `crates/dediren-plugin-uml-xmi-export/Cargo.toml`
- Create: `crates/dediren-plugin-uml-xmi-export/src/main.rs`
- Create: `crates/dediren-plugin-uml-xmi-export/tests/common/mod.rs`
- Create: `crates/dediren-plugin-uml-xmi-export/tests/uml_xmi_export_plugin.rs`
- Create: `fixtures/export/uml-basic.xmi`
- Modify: `Cargo.toml`
- Modify: `crates/dediren-core/src/commands.rs`
- Modify: `crates/dediren-plugin-archimate-oef-export/src/main.rs`
- Modify: `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`
- Modify: `crates/dediren-cli/tests/common/mod.rs`
- Modify: `crates/dediren-cli/tests/cli_export.rs`

- [ ] **Step 1: Add workspace crate and dependency**

In root `Cargo.toml`, add `"crates/dediren-plugin-uml-xmi-export"` to `workspace.members`.

Create `crates/dediren-plugin-uml-xmi-export/Cargo.toml`:

```toml
[package]
name = "dediren-plugin-uml-xmi-export"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
rust-version.workspace = true

[[bin]]
name = "dediren-plugin-uml-xmi-export"
path = "src/main.rs"

[dependencies]
anyhow.workspace = true
dediren-contracts.workspace = true
dediren-uml.workspace = true
quick-xml.workspace = true
serde_json.workspace = true
```

- [ ] **Step 2: Generalize core export request construction**

In `crates/dediren-core/src/commands.rs`, replace construction of `OefExportInput` with `ExportRequest`:

```rust
let policy: serde_json::Value =
    serde_json::from_str(policy_text).map_err(command_input_error("export"))?;
let export_input = ExportRequest {
    export_request_schema_version: dediren_contracts::EXPORT_REQUEST_SCHEMA_VERSION.to_string(),
    source,
    layout_result,
    policy,
};
```

Keep command arguments and CLI behavior unchanged.

- [ ] **Step 3: Migrate ArchiMate exporter to generic request**

In `crates/dediren-plugin-archimate-oef-export/src/main.rs`, parse `ExportRequest`, then parse the OEF policy:

```rust
let request: ExportRequest = serde_json::from_str(&input)?;
let policy: OefExportPolicy = serde_json::from_value(request.policy.clone())?;
let request = OefExportInput {
    export_request_schema_version: request.export_request_schema_version,
    source: request.source,
    layout_result: request.layout_result,
    policy,
};
```

Run existing ArchiMate export tests after this migration.

- [ ] **Step 4: Add failing UML/XMI plugin tests**

Create `crates/dediren-plugin-uml-xmi-export/tests/common/mod.rs`:

```rust
use assert_cmd::Command;

pub fn plugin_command() -> Command {
    Command::cargo_bin("dediren-plugin-uml-xmi-export")
        .expect("UML/XMI export plugin binary should be built by Cargo")
}

pub fn stdout_json(output: &[u8]) -> serde_json::Value {
    serde_json::from_slice(output).expect("stdout should be JSON")
}

pub fn ok_data(output: &[u8]) -> serde_json::Value {
    let envelope = stdout_json(output);
    assert_eq!(envelope["envelope_schema_version"], "envelope.schema.v1");
    assert_eq!(envelope["status"], "ok");
    assert!(
        envelope["diagnostics"]
            .as_array()
            .expect("diagnostics should be an array")
            .is_empty(),
        "ok envelope should not include diagnostics"
    );
    envelope["data"].clone()
}

pub fn assert_error_code(output: &[u8], expected_code: &str) {
    let envelope = stdout_json(output);
    assert_eq!(envelope["status"], "error");
    let codes: Vec<&str> = envelope["diagnostics"]
        .as_array()
        .expect("diagnostics should be an array")
        .iter()
        .map(|diagnostic| diagnostic["code"].as_str().unwrap())
        .collect();
    assert!(
        codes.iter().any(|code| *code == expected_code),
        "expected diagnostic code {expected_code}, got {codes:?}"
    );
}
```

Create `crates/dediren-plugin-uml-xmi-export/tests/uml_xmi_export_plugin.rs`:

```rust
mod common;

fn workspace_file(path: &str) -> String {
    format!("{}/{}", env!("CARGO_MANIFEST_DIR"), path)
        .replace("/crates/dediren-plugin-uml-xmi-export/", "/")
}

#[test]
fn uml_xmi_export_plugin_reports_capabilities() {
    let mut cmd = common::plugin_command();
    let stdout = cmd
        .arg("capabilities")
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let capabilities = common::stdout_json(&stdout);
    assert_eq!(capabilities["id"], "uml-xmi");
    assert_eq!(capabilities["runtime"]["artifact_kind"], "uml-xmi+xml");
}

#[test]
fn uml_xmi_export_plugin_outputs_xmi() {
    let input = serde_json::json!({
        "export_request_schema_version": "export-request.schema.v1",
        "source": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/source/valid-uml-basic.json")).unwrap()
        ).unwrap(),
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/uml-basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/export-policy/default-uml-xmi.json")).unwrap()
        ).unwrap()
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .arg("export")
        .write_stdin(input.to_string())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    assert_eq!(data["artifact_kind"], "uml-xmi+xml");
    let xml = data["content"].as_str().unwrap();
    let expected =
        std::fs::read_to_string(workspace_file("fixtures/export/uml-basic.xmi")).unwrap();
    assert_eq!(xml, expected);

    let doc = roxmltree::Document::parse(xml).unwrap();
    let root = doc.root_element();
    assert_eq!(root.tag_name().name(), "XMI");
    assert!(xml.contains("uml:Class"));
    assert!(xml.contains("uml:Activity"));
}

#[test]
fn uml_xmi_export_rejects_invalid_uml_relationship_endpoint() {
    let mut source: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(workspace_file("fixtures/source/valid-uml-basic.json")).unwrap(),
    )
    .unwrap();
    source["relationships"][0]["source"] = serde_json::json!("initial-submit");

    let input = serde_json::json!({
        "export_request_schema_version": "export-request.schema.v1",
        "source": source,
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/uml-basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/export-policy/default-uml-xmi.json")).unwrap()
        ).unwrap()
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .arg("export")
        .write_stdin(input.to_string())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    common::assert_error_code(&output, "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
}
```

- [ ] **Step 5: Add the expected XMI fixture**

Create `fixtures/export/uml-basic.xmi` with deterministic output from the plugin implementation:

```xml
<xmi:XMI xmlns:xmi="http://www.omg.org/spec/XMI/20131001" xmlns:uml="http://www.omg.org/spec/UML/20161101" xmi:version="2.5.1"><uml:Model xmi:id="id-dediren-uml-basic-model" name="Dediren UML Basic"><packagedElement xmi:type="uml:Package" xmi:id="id-pkg-orders" name="Orders"/><packagedElement xmi:type="uml:Class" xmi:id="id-class-order" name="Order"><ownedAttribute xmi:id="id-class-order-id" name="id" type="OrderId" visibility="public" lowerValue="1" upperValue="1"/><ownedAttribute xmi:id="id-class-order-status" name="status" type="OrderStatus" visibility="private" lowerValue="1" upperValue="1"/><ownedOperation xmi:id="id-class-order-submit" name="submit" visibility="public"/></packagedElement><packagedElement xmi:type="uml:Enumeration" xmi:id="id-enum-order-status" name="OrderStatus"><ownedLiteral xmi:id="id-enum-order-status-draft" name="Draft"/><ownedLiteral xmi:id="id-enum-order-status-submitted" name="Submitted"/><ownedLiteral xmi:id="id-enum-order-status-cancelled" name="Cancelled"/></packagedElement><packagedElement xmi:type="uml:Class" xmi:id="id-class-order-line" name="OrderLine"><ownedAttribute xmi:id="id-class-order-line-sku" name="sku" type="String" visibility="public" lowerValue="1" upperValue="1"/><ownedAttribute xmi:id="id-class-order-line-quantity" name="quantity" type="Integer" visibility="public" lowerValue="1" upperValue="1"/></packagedElement><packagedElement xmi:type="uml:Activity" xmi:id="id-activity-submit-order" name="Submit Order"/></uml:Model></xmi:XMI>
```

Keep this fixture as a strict byte-for-byte oracle for the first exporter slice.

- [ ] **Step 6: Implement UML/XMI exporter**

Create `crates/dediren-plugin-uml-xmi-export/src/main.rs`:

```rust
use std::io::{Cursor, Read};

use anyhow::{bail, Context};
use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, ExportRequest, ExportResult,
    GenericGraphPluginData, SourceNode, UmlXmiExportPolicy, EXPORT_RESULT_SCHEMA_VERSION,
    PLUGIN_PROTOCOL_VERSION,
};
use quick_xml::events::{BytesEnd, BytesStart, Event};
use quick_xml::Writer;

const XMI_NS: &str = "http://www.omg.org/spec/XMI/20131001";
const UML_NS: &str = "http://www.omg.org/spec/UML/20161101";

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("capabilities") => {
            println!(
                "{}",
                serde_json::json!({
                    "plugin_protocol_version": PLUGIN_PROTOCOL_VERSION,
                    "id": "uml-xmi",
                    "capabilities": ["export"],
                    "runtime": {
                        "artifact_kind": "uml-xmi+xml",
                        "uml_version": "2.5.1",
                        "xmi_version": "2.5.1"
                    }
                })
            );
            Ok(())
        }
        Some("export") => export_from_stdin(),
        _ => bail!("expected command: capabilities or export"),
    }
}

fn export_from_stdin() -> anyhow::Result<()> {
    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let request: ExportRequest = serde_json::from_str(&input)?;
    let policy: UmlXmiExportPolicy = serde_json::from_value(request.policy.clone())?;
    let plugin_data: GenericGraphPluginData = serde_json::from_value(
        request
            .source
            .plugins
            .get("generic-graph")
            .context("missing plugins.generic-graph")?
            .clone(),
    )?;
    if let Err(error) = dediren_uml::validate_source(&request.source, &plugin_data) {
        exit_with_uml_error(error);
    }
    let content = build_xmi(&request, &policy)?;
    let result = ExportResult {
        export_result_schema_version: EXPORT_RESULT_SCHEMA_VERSION.to_string(),
        artifact_kind: "uml-xmi+xml".to_string(),
        content,
    };
    println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
    Ok(())
}

fn build_xmi(request: &ExportRequest, policy: &UmlXmiExportPolicy) -> anyhow::Result<String> {
    let mut writer = Writer::new(Cursor::new(Vec::new()));
    let mut xmi = BytesStart::new("xmi:XMI");
    xmi.push_attribute(("xmlns:xmi", XMI_NS));
    xmi.push_attribute(("xmlns:uml", UML_NS));
    xmi.push_attribute(("xmi:version", policy.xmi_version.as_deref().unwrap_or("2.5.1")));
    writer.write_event(Event::Start(xmi))?;

    let mut model = BytesStart::new("uml:Model");
    model.push_attribute(("xmi:id", policy.model_identifier.as_str()));
    model.push_attribute(("name", policy.model_name.as_str()));
    writer.write_event(Event::Start(model))?;

    for node in &request.source.nodes {
        if matches!(node.node_type.as_str(), "Package" | "Activity" | "Action" | "InitialNode" | "ActivityFinalNode" | "DecisionNode" | "MergeNode" | "ForkNode" | "JoinNode" | "ObjectNode") {
            write_empty_packaged_element(&mut writer, &node.node_type, &node.id, &node.label)?;
        } else if node.node_type == "Enumeration" {
            write_enumeration(&mut writer, node)?;
        } else if matches!(node.node_type.as_str(), "Class" | "Interface" | "DataType") {
            write_structural_type(&mut writer, node)?;
        }
    }

    writer.write_event(Event::End(BytesEnd::new("uml:Model")))?;
    writer.write_event(Event::End(BytesEnd::new("xmi:XMI")))?;
    let content = String::from_utf8(writer.into_inner().into_inner())?;
    Ok(format!("{content}\n"))
}

fn write_empty_packaged_element(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    uml_type: &str,
    id: &str,
    name: &str,
) -> anyhow::Result<()> {
    let mut element = BytesStart::new("packagedElement");
    element.push_attribute(("xmi:type", format!("uml:{uml_type}").as_str()));
    element.push_attribute(("xmi:id", stable_id(id).as_str()));
    element.push_attribute(("name", name));
    writer.write_event(Event::Empty(element))?;
    Ok(())
}
```

Add these helper functions in the same file:

```rust
fn write_structural_type(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    node: &SourceNode,
) -> anyhow::Result<()> {
    let mut element = BytesStart::new("packagedElement");
    element.push_attribute(("xmi:type", format!("uml:{}", node.node_type).as_str()));
    element.push_attribute(("xmi:id", stable_id(&node.id).as_str()));
    element.push_attribute(("name", node.label.as_str()));
    writer.write_event(Event::Start(element))?;

    if let Some(attributes) = node
        .properties
        .get("uml")
        .and_then(|uml| uml.get("attributes"))
        .and_then(serde_json::Value::as_array)
    {
        for attribute in attributes {
            let name = attribute
                .get("name")
                .and_then(serde_json::Value::as_str)
                .unwrap_or("attribute");
            let mut owned = BytesStart::new("ownedAttribute");
            owned.push_attribute(("xmi:id", stable_id(&format!("{}-{name}", node.id)).as_str()));
            owned.push_attribute(("name", name));
            if let Some(type_name) = attribute.get("type").and_then(serde_json::Value::as_str) {
                owned.push_attribute(("type", type_name));
            }
            owned.push_attribute((
                "visibility",
                attribute
                    .get("visibility")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or("public"),
            ));
            let multiplicity = attribute
                .get("multiplicity")
                .and_then(serde_json::Value::as_str)
                .unwrap_or("1");
            let (lower, upper) = multiplicity_bounds(multiplicity);
            owned.push_attribute(("lowerValue", lower.as_str()));
            owned.push_attribute(("upperValue", upper.as_str()));
            writer.write_event(Event::Empty(owned))?;
        }
    }

    if let Some(operations) = node
        .properties
        .get("uml")
        .and_then(|uml| uml.get("operations"))
        .and_then(serde_json::Value::as_array)
    {
        for operation in operations {
            let name = operation
                .get("name")
                .and_then(serde_json::Value::as_str)
                .unwrap_or("operation");
            let mut owned = BytesStart::new("ownedOperation");
            owned.push_attribute(("xmi:id", stable_id(&format!("{}-{name}", node.id)).as_str()));
            owned.push_attribute(("name", name));
            owned.push_attribute((
                "visibility",
                operation
                    .get("visibility")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or("public"),
            ));
            writer.write_event(Event::Empty(owned))?;
        }
    }

    writer.write_event(Event::End(BytesEnd::new("packagedElement")))?;
    Ok(())
}

fn write_enumeration(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    node: &SourceNode,
) -> anyhow::Result<()> {
    let mut element = BytesStart::new("packagedElement");
    element.push_attribute(("xmi:type", "uml:Enumeration"));
    element.push_attribute(("xmi:id", stable_id(&node.id).as_str()));
    element.push_attribute(("name", node.label.as_str()));
    writer.write_event(Event::Start(element))?;
    if let Some(literals) = node
        .properties
        .get("uml")
        .and_then(|uml| uml.get("literals"))
        .and_then(serde_json::Value::as_array)
    {
        for literal in literals {
            let Some(name) = literal.as_str() else {
                continue;
            };
            let mut owned = BytesStart::new("ownedLiteral");
            owned.push_attribute(("xmi:id", stable_id(&format!("{}-{name}", node.id)).as_str()));
            owned.push_attribute(("name", name));
            writer.write_event(Event::Empty(owned))?;
        }
    }
    writer.write_event(Event::End(BytesEnd::new("packagedElement")))?;
    Ok(())
}

fn stable_id(id: &str) -> String {
    format!(
        "id-{}",
        id.chars()
            .map(|ch| if ch.is_ascii_alphanumeric() { ch.to_ascii_lowercase() } else { '-' })
            .collect::<String>()
            .split('-')
            .filter(|part| !part.is_empty())
            .collect::<Vec<_>>()
            .join("-")
    )
}

fn multiplicity_bounds(value: &str) -> (String, String) {
    if let Some((lower, upper)) = value.split_once("..") {
        return (lower.to_string(), upper.to_string());
    }
    (value.to_string(), value.to_string())
}

fn exit_with_uml_error(error: dediren_uml::UmlValidationError) -> ! {
    let envelope = CommandEnvelope::<serde_json::Value>::error(vec![Diagnostic {
        code: error.code().to_string(),
        severity: DiagnosticSeverity::Error,
        message: error.message(),
        path: Some(error.path),
    }]);
    println!("{}", serde_json::to_string(&envelope).expect("error envelope should serialize"));
    std::process::exit(2);
}
```

The output must match `fixtures/export/uml-basic.xmi` exactly for `valid-uml-basic.json`.

- [ ] **Step 7: Add CLI export test**

In `crates/dediren-cli/tests/common/mod.rs`, add `dediren-plugin-uml-xmi-export` to `ensure_plugin_binaries()`.

In `crates/dediren-cli/tests/cli_export.rs`, add:

```rust
#[test]
fn export_invokes_uml_xmi_plugin() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_UML_XMI",
            plugin_binary("dediren-plugin-uml-xmi-export"),
        )
        .arg("export")
        .arg("--plugin")
        .arg("uml-xmi")
        .arg("--policy")
        .arg(workspace_file("fixtures/export-policy/default-uml-xmi.json"))
        .arg("--source")
        .arg(workspace_file("fixtures/source/valid-uml-basic.json"))
        .arg("--layout")
        .arg(workspace_file("fixtures/layout-result/uml-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["export_result_schema_version"], "export-result.schema.v1");
    assert_eq!(data["artifact_kind"], "uml-xmi+xml");
    assert!(
        data["content"].as_str().unwrap().contains("<uml:Model"),
        "export content should contain UML model XML"
    );
}
```

- [ ] **Step 8: Run export tests**

Run:

```bash
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin
cargo test -p dediren-plugin-uml-xmi-export --test uml_xmi_export_plugin
cargo test -p dediren --test cli_export export_invokes_uml_xmi_plugin -- --exact
```

Expected: ArchiMate export remains green and UML/XMI export passes.

- [ ] **Step 9: Commit**

```bash
git add Cargo.toml crates/dediren-core/src/commands.rs crates/dediren-plugin-archimate-oef-export crates/dediren-plugin-uml-xmi-export crates/dediren-cli/tests/common/mod.rs crates/dediren-cli/tests/cli_export.rs fixtures/export/uml-basic.xmi
git commit -m "feat: export UML XMI"
```

---

### Task 7: Add End-To-End UML CLI Pipeline Coverage

**Files:**
- Modify: `crates/dediren-cli/tests/cli_pipeline.rs`
- Modify: `crates/dediren-cli/tests/plugin_compat.rs`

- [ ] **Step 1: Add fixture-mode UML pipeline test**

In `crates/dediren-cli/tests/cli_pipeline.rs`, add a test that validates, projects layout request, fixture-layout renders, and exports UML/XMI:

```rust
#[test]
fn fixture_mode_uml_pipeline_renders_and_exports() {
    let generic_plugin = plugin_binary("dediren-plugin-generic-graph");
    let svg_plugin = plugin_binary("dediren-plugin-svg-render");
    let xmi_plugin = plugin_binary("dediren-plugin-uml-xmi-export");

    let validate = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .args(["validate", "--plugin", "generic-graph", "--profile", "uml", "--input"])
        .arg(workspace_file("fixtures/source/valid-uml-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    assert_eq!(ok_data(&validate)["semantic_profile"], "uml");

    let metadata = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .args(["project", "--target", "render-metadata", "--plugin", "generic-graph", "--view", "class-view", "--input"])
        .arg(workspace_file("fixtures/source/valid-uml-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    assert_eq!(ok_data(&metadata)["semantic_profile"], "uml");

    let render = common::dediren_command()
        .env("DEDIREN_PLUGIN_SVG_RENDER", &svg_plugin)
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/uml-svg.json"))
        .arg("--metadata")
        .arg(workspace_file("fixtures/render-metadata/uml-basic.json"))
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/uml-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    assert_eq!(ok_data(&render)["artifact_kind"], "svg");

    let export = common::dediren_command()
        .env("DEDIREN_PLUGIN_UML_XMI", &xmi_plugin)
        .args(["export", "--plugin", "uml-xmi", "--policy"])
        .arg(workspace_file("fixtures/export-policy/default-uml-xmi.json"))
        .arg("--source")
        .arg(workspace_file("fixtures/source/valid-uml-basic.json"))
        .arg("--layout")
        .arg(workspace_file("fixtures/layout-result/uml-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    assert_eq!(ok_data(&export)["artifact_kind"], "uml-xmi+xml");
}
```

- [ ] **Step 2: Add plugin compatibility coverage**

In `crates/dediren-cli/tests/plugin_compat.rs`, ensure `uml-xmi` is included in the first-party plugin manifest/runtime compatibility list. Expected manifest:

```json
{
  "id": "uml-xmi",
  "capabilities": ["export"]
}
```

Expected runtime capability:

```json
{
  "id": "uml-xmi",
  "capabilities": ["export"],
  "runtime": {
    "artifact_kind": "uml-xmi+xml",
    "uml_version": "2.5.1",
    "xmi_version": "2.5.1"
  }
}
```

- [ ] **Step 3: Run pipeline and compatibility tests**

Run:

```bash
cargo test -p dediren --test cli_pipeline fixture_mode_uml_pipeline_renders_and_exports -- --exact
cargo test -p dediren --test plugin_compat
```

Expected: UML fixture-mode pipeline passes and all first-party plugin compatibility checks pass.

- [ ] **Step 4: Commit**

```bash
git add crates/dediren-cli/tests/cli_pipeline.rs crates/dediren-cli/tests/plugin_compat.rs
git commit -m "test: cover UML CLI pipeline"
```

---

### Task 8: Update Docs, Bundle, And Version Surfaces

**Files:**
- Modify: `Cargo.toml`
- Modify: `Cargo.lock`
- Modify: `fixtures/plugins/*.manifest.json`
- Modify: `README.md`
- Modify: `docs/agent-usage.md`
- Modify: `xtask/src/main.rs`
- Modify: `xtask/tests/dist.rs`

- [ ] **Step 1: Bump workspace version**

In `Cargo.toml`, set:

```toml
[workspace.package]
version = "0.12.0"
```

Run:

```bash
cargo metadata --locked --format-version 1 > /tmp/dediren-metadata.json
```

Expected before `Cargo.lock` update: command may fail because the lock file still has `0.11.3`. Then run:

```bash
cargo check --workspace
```

Expected: `Cargo.lock` updates workspace package versions to `0.12.0`.

- [ ] **Step 2: Bump first-party manifests**

Set `"version": "0.12.0"` in every `fixtures/plugins/*.manifest.json`, including the new `fixtures/plugins/uml-xmi.manifest.json`.

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts first_party_plugin_manifest_versions_match_workspace_version -- --exact
```

Expected: manifest versions match workspace version.

- [ ] **Step 3: Include UML/XMI in the dist bundle**

In `xtask/src/main.rs`, update:

```rust
const PLUGIN_BINARIES: &[&str] = &[
    "dediren",
    "dediren-plugin-generic-graph",
    "dediren-plugin-elk-layout",
    "dediren-plugin-svg-render",
    "dediren-plugin-archimate-oef-export",
    "dediren-plugin-uml-xmi-export",
];
const BUNDLE_PLUGINS: &[&str] = &[
    "generic-graph",
    "elk-layout",
    "svg-render",
    "archimate-oef",
    "uml-xmi",
];
const CLEAN_ENV: &[&str] = &[
    "DEDIREN_PLUGIN_DIRS",
    "DEDIREN_PLUGIN_GENERIC_GRAPH",
    "DEDIREN_PLUGIN_ELK_LAYOUT",
    "DEDIREN_PLUGIN_SVG_RENDER",
    "DEDIREN_PLUGIN_ARCHIMATE_OEF",
    "DEDIREN_PLUGIN_UML_XMI",
    "DEDIREN_ELK_COMMAND",
    "DEDIREN_ELK_RESULT_FIXTURE",
];
```

If `DEDIREN_PLUGIN_ARCHIMATE_OEF` is not currently present in `CLEAN_ENV`, include it in this same edit because both export plugin overrides belong there.

- [ ] **Step 4: Add dist tests for UML bundle content**

In `xtask/tests/dist.rs`, add assertions in the existing bundle content test:

```rust
assert!(
    bundle.join("bin/dediren-plugin-uml-xmi-export").exists(),
    "dist build should include the UML/XMI export binary"
);
assert!(
    bundle.join("plugins/uml-xmi.manifest.json").exists(),
    "dist build should include the UML/XMI plugin manifest"
);
assert!(
    bundle.join("fixtures/source/valid-uml-basic.json").exists(),
    "dist build should include the UML source fixture"
);
assert!(
    bundle.join("fixtures/render-policy/uml-svg.json").exists(),
    "dist build should include the UML render policy"
);
```

Also assert `bundle.json.required_plugins` includes `{ "id": "uml-xmi", "version": "0.12.0" }`.

- [ ] **Step 5: Update README**

In `README.md`, update version examples from `0.11.3` to `0.12.0`, add the development install command:

```bash
cargo install --path crates/dediren-plugin-uml-xmi-export
```

Add a `## UML SVG And XMI` section after the ArchiMate section:

## UML SVG And XMI

UML notation uses the same profile pipeline as ArchiMate. Configure the source
graph's generic-graph plugin data with:

```json
{
  "plugins": {
    "generic-graph": {
      "semantic_profile": "uml",
      "views": []
    }
  }
}
```

The bundled UML profile supports the first class/data/activity slice:
`uml-class`, `uml-data`, and `uml-activity` views. Dediren JSON remains the
authored source. UML/XMI is compatibility export output.

Validate UML source semantics:

```bash
dediren validate \
  --plugin generic-graph \
  --profile uml \
  --input fixtures/source/valid-uml-basic.json
```

Create UML render metadata and render SVG:

```bash
dediren project \
  --target render-metadata \
  --plugin generic-graph \
  --view class-view \
  --input fixtures/source/valid-uml-basic.json \
  > uml-render-metadata.json

dediren render \
  --plugin svg-render \
  --policy fixtures/render-policy/uml-svg.json \
  --metadata uml-render-metadata.json \
  --input fixtures/layout-result/uml-basic.json \
  > uml-render-result.json
```

Export UML/XMI:

```bash
dediren export \
  --plugin uml-xmi \
  --policy fixtures/export-policy/default-uml-xmi.json \
  --source fixtures/source/valid-uml-basic.json \
  --layout fixtures/layout-result/uml-basic.json \
  > uml-xmi-export-result.json
```

`uml-xmi-export-result.json` is a command envelope. The UML/XMI XML text is in
`.data.content`.

Update the plugin table with:

```markdown
| `uml-xmi` | `export` | Exports UML 2.5.1 XMI XML from UML-profile source data. |
```

- [ ] **Step 6: Update agent usage guide**

In `docs/agent-usage.md`, add a UML authoring section that mirrors the ArchiMate profile guidance:

## UML Profile Authoring

For detailed class, data, or activity models, set:

```json
{
  "plugins": {
    "generic-graph": {
      "semantic_profile": "uml",
      "views": []
    }
  }
}
```

Use `kind: "uml-class"`, `kind: "uml-data"`, or `kind: "uml-activity"` on
views. Author UML-specific attributes, operations, multiplicities, guards, and
partitions under `properties.uml`.

Validation loop:

```bash
dediren validate --plugin generic-graph --profile uml --input model.json
dediren project --target layout-request --plugin generic-graph --view class-view --input model.json
dediren project --target render-metadata --plugin generic-graph --view class-view --input model.json
```

For UML/XMI export:

```bash
dediren export --plugin uml-xmi \
  --policy fixtures/export-policy/default-uml-xmi.json \
  --source model.json \
  --layout layout-result.json > uml-xmi-export-result.json
```

Update runtime probes to include:

```bash
target/debug/dediren-plugin-uml-xmi-export capabilities
"$BUNDLE/bin/dediren-plugin-uml-xmi-export" capabilities
```

- [ ] **Step 7: Run docs and dist-focused checks**

Run:

```bash
git diff --check
cargo test -p dediren-contracts --test schema_contracts first_party_plugin_manifest_versions_match_workspace_version -- --exact
cargo test -p xtask --test dist
```

Expected: no whitespace errors; manifest version test passes; dist tests pass.

- [ ] **Step 8: Commit**

```bash
git add Cargo.toml Cargo.lock fixtures/plugins README.md docs/agent-usage.md xtask/src/main.rs xtask/tests/dist.rs
git commit -m "docs: publish UML profile workflow"
```

---

### Task 9: Final Verification And Audit Gates

**Files:**
- No planned source edits. This task verifies the full branch.

- [ ] **Step 1: Run formatting**

Run:

```bash
cargo fmt --all -- --check
```

Expected: formatting check passes.

- [ ] **Step 2: Run contract/schema lanes**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts
cargo test -p dediren-contracts --test contract_roundtrip
```

Expected: both tests pass.

- [ ] **Step 3: Run profile/plugin lanes**

Run:

```bash
cargo test -p dediren-uml --test uml_validation
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin
cargo test -p dediren-plugin-uml-xmi-export --test uml_xmi_export_plugin
```

Expected: all profile and plugin tests pass.

- [ ] **Step 4: Run CLI lanes**

Run:

```bash
cargo test -p dediren --test cli_validate
cargo test -p dediren --test cli_project
cargo test -p dediren --test cli_render
cargo test -p dediren --test cli_export
cargo test -p dediren --test cli_pipeline
cargo test -p dediren --test plugin_compat
```

Expected: all CLI tests pass.

- [ ] **Step 5: Run workspace tests**

Run:

```bash
cargo test --workspace --locked
```

Expected: workspace tests pass.

- [ ] **Step 6: Build and smoke the distribution bundle**

Run from a shell where `java -version` reports Java 21 or newer:

```bash
cargo xtask dist build
cargo xtask dist smoke dist/dediren-agent-bundle-0.12.0-x86_64-unknown-linux-gnu.tar.gz
```

Expected: bundle build and smoke pass; the archive includes the UML/XMI binary, manifest, schemas, fixtures, docs, and `LICENSE`.

- [ ] **Step 7: Run audit gates required by AGENTS.md**

Because this is a broad pipeline/profile change, run:

```text
souroldgeezer-audit:test-quality-audit deep on Rust tests/fixtures
souroldgeezer-audit:devsecops-audit quick on dependencies, plugin process boundaries, artifacts, and docs
```

Expected: no block findings. Fix block findings before completion. Fix warn/info findings or explicitly accept them in the implementation handoff.

- [ ] **Step 8: Final status check**

Run:

```bash
git status --short --branch
git log --oneline -8
```

Expected: branch contains scoped commits from Tasks 1-8 and no unrelated working-tree changes.
