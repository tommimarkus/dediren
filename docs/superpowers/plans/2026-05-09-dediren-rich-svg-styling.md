# Dediren Rich SVG Styling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a richer, schema-validated SVG render policy so agents can control diagram colors, typography, strokes, rounded corners, group styling, edge labels, and deterministic per-id style overrides without putting presentation data into source graphs or layout results.

**Architecture:** Keep styling owned by `svg-render` policy/config only. Extend `svg-render-policy.schema.v1` with optional style fields so existing policies stay valid, mirror those fields in `dediren-contracts`, then refactor `dediren-plugin-svg-render` into small renderer helpers that resolve defaults plus per-id overrides before emitting SVG. The renderer remains an executable plugin and source/layout contracts remain presentation-free.

**Tech Stack:** Rust 1.93, Cargo workspace, serde/serde_json, JSON Schema draft 2020-12, assert_cmd/predicates, existing `dediren-contracts` shared protocol crate.

---

## Scope

This plan implements rich styling for the existing SVG render plugin only.

In scope:
- Backward-compatible optional fields in `svg-render-policy.schema.v1`.
- Rust contract structs for SVG style policy.
- Generic default styles for background, font, nodes, edges, groups, and labels.
- Per-id override maps for nodes, edges, and groups keyed by layout-result ids.
- SVG rendering of groups behind nodes, nodes, edge paths, node labels, group labels, and edge labels.
- XML escaping for text and attribute values used by style fields.
- Deterministic fixture and tests for styled SVG output.
- README update documenting the style policy boundary.

Out of scope:
- PNG rendering.
- Render plugins beyond SVG.
- Schema-to-Rust code generation.
- Style data in source graph or layout request/result contracts.
- CSS files, external fonts, script tags, gradients, filters, icons, images, or embedded assets.
- Semantic type selectors. The current `LayoutResult` does not carry semantic type strings, so this slice supports only category defaults and per-layout-id overrides.

## Software Design Notes

This plan uses `souroldgeezer-design:software-design` Build mode with the Rust
extension. The design force is change isolation: visual styling is likely to
change more often than source graph semantics or layout geometry, so it stays in
the render policy and SVG plugin boundary.

Responsibilities:
- `schemas/svg-render-policy.schema.json` owns the public JSON policy contract.
- `dediren-contracts` owns shared protocol structs only; it does not resolve
  rendering defaults or emit SVG.
- `dediren-plugin-svg-render` owns style defaulting, per-id override resolution,
  XML escaping, and SVG emission.
- CLI code remains orchestration-only and should not learn individual style
  fields.

Dependency direction:
- The SVG plugin may depend on `dediren-contracts`.
- `dediren-contracts` must not depend on the SVG plugin or core.
- First-party plugins must not depend on `dediren-core`.
- Style resolution should be private plugin code unless another renderer
  actually needs it.

Rejected abstractions:
- No theme registry, selector engine, CSS generator, or trait-based renderer
  abstraction in this slice. They would add extension ceremony without current
  implementers.
- No semantic type selectors because `LayoutResult` currently has ids and
  provenance, not semantic type strings. Adding type selectors would require a
  separate contract decision.

Validation/delegation:
- Use `test-quality-audit` Quick review on the changed contract/plugin/CLI
  tests before completion.
- Use `devsecops-audit` Quick review on the changed schema, renderer, README,
  and dependency posture before completion.
- The audit skills own their findings; this plan only makes their validation
  mandatory.

## Policy Shape

Add optional `style` to `svg-render-policy.schema.v1`:

```json
{
  "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
  "page": { "width": 1200, "height": 800 },
  "margin": { "top": 32, "right": 32, "bottom": 32, "left": 32 },
  "style": {
    "background": { "fill": "#f8fafc" },
    "font": { "family": "Inter, Arial, sans-serif", "size": 14 },
    "node": {
      "fill": "#ffffff",
      "stroke": "#1f2937",
      "stroke_width": 1.5,
      "rx": 8,
      "label_fill": "#111827"
    },
    "edge": {
      "stroke": "#475569",
      "stroke_width": 1.5,
      "label_fill": "#334155"
    },
    "group": {
      "fill": "#e0f2fe",
      "stroke": "#0284c7",
      "stroke_width": 1.0,
      "rx": 10,
      "label_fill": "#0c4a6e",
      "label_size": 12
    },
    "node_overrides": {
      "api": {
        "fill": "#ecfeff",
        "stroke": "#0891b2",
        "label_fill": "#164e63"
      }
    },
    "edge_overrides": {
      "client-calls-api": {
        "stroke": "#7c3aed",
        "label_fill": "#5b21b6",
        "stroke_width": 2.0
      }
    },
    "group_overrides": {}
  }
}
```

All fields under `style` are optional. Missing fields resolve to the current renderer defaults:

```text
background.fill = #ffffff
font.family = Inter, Arial, sans-serif
font.size = 14
node.fill = #f8fafc
node.stroke = #334155
node.stroke_width = 1.5
node.rx = 6
node.label_fill = #0f172a
edge.stroke = #64748b
edge.stroke_width = 1.5
edge.label_fill = #374151
group.fill = #eff6ff
group.stroke = #93c5fd
group.stroke_width = 1
group.rx = 8
group.label_fill = #1e3a8a
group.label_size = 12
```

## File Map

- Modify: `schemas/svg-render-policy.schema.json` - add optional style schema with strict object shapes and reusable `$defs`.
- Modify: `fixtures/render-policy/default-svg.json` - keep minimal policy valid and unchanged unless formatting is required.
- Create: `fixtures/render-policy/rich-svg.json` - policy fixture exercising category styles and per-id overrides.
- Modify: `crates/dediren-contracts/src/lib.rs` - add optional SVG style policy structs.
- Modify: `crates/dediren-contracts/tests/contract_roundtrip.rs` - add rich policy round-trip coverage.
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs` - validate the rich policy fixture.
- Modify: `crates/dediren-plugin-svg-render/src/main.rs` - refactor renderer into deterministic style resolution and escaped SVG emission.
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs` - assert rich styling appears in SVG output.
- Modify: `crates/dediren-cli/tests/cli_render.rs` - prove CLI render accepts rich policy.
- Modify: `README.md` - document rich SVG policy use and the rule that source/layout data remains style-free.

---

### Task 1: Extend SVG Render Policy Schema

**Files:**
- Modify: `schemas/svg-render-policy.schema.json`
- Create: `fixtures/render-policy/rich-svg.json`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`

- [ ] **Step 1: Write the rich SVG policy fixture**

Create `fixtures/render-policy/rich-svg.json`:

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
  },
  "style": {
    "background": {
      "fill": "#f8fafc"
    },
    "font": {
      "family": "Inter, Arial, sans-serif",
      "size": 14
    },
    "node": {
      "fill": "#ffffff",
      "stroke": "#1f2937",
      "stroke_width": 1.5,
      "rx": 8,
      "label_fill": "#111827"
    },
    "edge": {
      "stroke": "#475569",
      "stroke_width": 1.5,
      "label_fill": "#334155"
    },
    "group": {
      "fill": "#e0f2fe",
      "stroke": "#0284c7",
      "stroke_width": 1,
      "rx": 10,
      "label_fill": "#0c4a6e",
      "label_size": 12
    },
    "node_overrides": {
      "api": {
        "fill": "#ecfeff",
        "stroke": "#0891b2",
        "label_fill": "#164e63"
      }
    },
    "edge_overrides": {
      "client-calls-api": {
        "stroke": "#7c3aed",
        "stroke_width": 2,
        "label_fill": "#5b21b6"
      }
    },
    "group_overrides": {}
  }
}
```

- [ ] **Step 2: Add a failing schema test for the rich policy**

Modify `crates/dediren-contracts/tests/schema_contracts.rs`:

```rust
#[test]
fn rich_svg_policy_matches_schema() {
    assert_valid(
        "schemas/svg-render-policy.schema.json",
        "fixtures/render-policy/rich-svg.json",
    );
}
```

Place this test near `default_svg_policy_matches_schema`.

- [ ] **Step 3: Run the schema test to verify it fails**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts rich_svg_policy_matches_schema
```

Expected: FAIL because `schemas/svg-render-policy.schema.json` rejects the new `style` field.

- [ ] **Step 4: Extend the schema**

Replace `schemas/svg-render-policy.schema.json` with:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://dediren.dev/schemas/svg-render-policy.schema.json",
  "type": "object",
  "additionalProperties": false,
  "required": ["svg_render_policy_schema_version", "page", "margin"],
  "properties": {
    "svg_render_policy_schema_version": { "const": "svg-render-policy.schema.v1" },
    "page": {
      "type": "object",
      "additionalProperties": false,
      "required": ["width", "height"],
      "properties": {
        "width": { "type": "number", "exclusiveMinimum": 0 },
        "height": { "type": "number", "exclusiveMinimum": 0 }
      }
    },
    "margin": {
      "type": "object",
      "additionalProperties": false,
      "required": ["top", "right", "bottom", "left"],
      "properties": {
        "top": { "type": "number", "minimum": 0 },
        "right": { "type": "number", "minimum": 0 },
        "bottom": { "type": "number", "minimum": 0 },
        "left": { "type": "number", "minimum": 0 }
      }
    },
    "style": { "$ref": "#/$defs/style" }
  },
  "$defs": {
    "color": {
      "type": "string",
      "pattern": "^#[0-9a-fA-F]{6}$"
    },
    "font": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "family": {
          "type": "string",
          "minLength": 1,
          "maxLength": 120
        },
        "size": {
          "type": "number",
          "exclusiveMinimum": 0,
          "maximum": 96
        }
      }
    },
    "background": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "fill": { "$ref": "#/$defs/color" }
      }
    },
    "nodeStyle": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "fill": { "$ref": "#/$defs/color" },
        "stroke": { "$ref": "#/$defs/color" },
        "stroke_width": {
          "type": "number",
          "minimum": 0,
          "maximum": 24
        },
        "rx": {
          "type": "number",
          "minimum": 0,
          "maximum": 80
        },
        "label_fill": { "$ref": "#/$defs/color" }
      }
    },
    "edgeStyle": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "stroke": { "$ref": "#/$defs/color" },
        "stroke_width": {
          "type": "number",
          "minimum": 0,
          "maximum": 24
        },
        "label_fill": { "$ref": "#/$defs/color" }
      }
    },
    "groupStyle": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "fill": { "$ref": "#/$defs/color" },
        "stroke": { "$ref": "#/$defs/color" },
        "stroke_width": {
          "type": "number",
          "minimum": 0,
          "maximum": 24
        },
        "rx": {
          "type": "number",
          "minimum": 0,
          "maximum": 80
        },
        "label_fill": { "$ref": "#/$defs/color" },
        "label_size": {
          "type": "number",
          "exclusiveMinimum": 0,
          "maximum": 96
        }
      }
    },
    "nodeOverrides": {
      "type": "object",
      "additionalProperties": { "$ref": "#/$defs/nodeStyle" }
    },
    "edgeOverrides": {
      "type": "object",
      "additionalProperties": { "$ref": "#/$defs/edgeStyle" }
    },
    "groupOverrides": {
      "type": "object",
      "additionalProperties": { "$ref": "#/$defs/groupStyle" }
    },
    "style": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "background": { "$ref": "#/$defs/background" },
        "font": { "$ref": "#/$defs/font" },
        "node": { "$ref": "#/$defs/nodeStyle" },
        "edge": { "$ref": "#/$defs/edgeStyle" },
        "group": { "$ref": "#/$defs/groupStyle" },
        "node_overrides": { "$ref": "#/$defs/nodeOverrides" },
        "edge_overrides": { "$ref": "#/$defs/edgeOverrides" },
        "group_overrides": { "$ref": "#/$defs/groupOverrides" }
      }
    }
  }
}
```

- [ ] **Step 5: Run schema tests**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts
```

Expected: PASS, including both `default_svg_policy_matches_schema` and `rich_svg_policy_matches_schema`.

- [ ] **Step 6: Commit schema and fixture**

```bash
git add schemas/svg-render-policy.schema.json fixtures/render-policy/rich-svg.json crates/dediren-contracts/tests/schema_contracts.rs
git commit -m "feat: extend svg render policy schema"
```

---

### Task 2: Add Rich SVG Policy Contract Types

**Files:**
- Modify: `crates/dediren-contracts/src/lib.rs`
- Modify: `crates/dediren-contracts/tests/contract_roundtrip.rs`

- [ ] **Step 1: Add a failing rich policy round-trip test**

Modify `crates/dediren-contracts/tests/contract_roundtrip.rs`:

```rust
#[test]
fn rich_render_policy_roundtrips() {
    let text = std::fs::read_to_string(workspace_file("fixtures/render-policy/rich-svg.json"))
        .unwrap();
    let policy: RenderPolicy = serde_json::from_str(&text).unwrap();
    assert_eq!(
        policy.style.as_ref().unwrap().node.as_ref().unwrap().fill.as_deref(),
        Some("#ffffff")
    );
    assert_eq!(
        policy
            .style
            .as_ref()
            .unwrap()
            .node_overrides
            .get("api")
            .unwrap()
            .stroke
            .as_deref(),
        Some("#0891b2")
    );
    let serialized = serde_json::to_string(&policy).unwrap();
    let reparsed: RenderPolicy = serde_json::from_str(&serialized).unwrap();
    assert_eq!(reparsed, policy);
}
```

- [ ] **Step 2: Run the round-trip test to verify it fails**

Run:

```bash
cargo test -p dediren-contracts --test contract_roundtrip rich_render_policy_roundtrips
```

Expected: FAIL because `RenderPolicy` rejects the unknown `style` field.

- [ ] **Step 3: Add contract structs**

Modify `crates/dediren-contracts/src/lib.rs`. Add the import near the top:

```rust
use std::collections::BTreeMap;
```

Replace `RenderPolicy` with:

```rust
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RenderPolicy {
    pub svg_render_policy_schema_version: String,
    pub page: Page,
    pub margin: Margin,
    #[serde(default)]
    pub style: Option<SvgStylePolicy>,
}
```

Add these structs after `Margin`:

```rust
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgStylePolicy {
    #[serde(default)]
    pub background: Option<SvgBackgroundStyle>,
    #[serde(default)]
    pub font: Option<SvgFontStyle>,
    #[serde(default)]
    pub node: Option<SvgNodeStyle>,
    #[serde(default)]
    pub edge: Option<SvgEdgeStyle>,
    #[serde(default)]
    pub group: Option<SvgGroupStyle>,
    #[serde(default)]
    pub node_overrides: BTreeMap<String, SvgNodeStyle>,
    #[serde(default)]
    pub edge_overrides: BTreeMap<String, SvgEdgeStyle>,
    #[serde(default)]
    pub group_overrides: BTreeMap<String, SvgGroupStyle>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgBackgroundStyle {
    #[serde(default)]
    pub fill: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgFontStyle {
    #[serde(default)]
    pub family: Option<String>,
    #[serde(default)]
    pub size: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgNodeStyle {
    #[serde(default)]
    pub fill: Option<String>,
    #[serde(default)]
    pub stroke: Option<String>,
    #[serde(default)]
    pub stroke_width: Option<f64>,
    #[serde(default)]
    pub rx: Option<f64>,
    #[serde(default)]
    pub label_fill: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgEdgeStyle {
    #[serde(default)]
    pub stroke: Option<String>,
    #[serde(default)]
    pub stroke_width: Option<f64>,
    #[serde(default)]
    pub label_fill: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgGroupStyle {
    #[serde(default)]
    pub fill: Option<String>,
    #[serde(default)]
    pub stroke: Option<String>,
    #[serde(default)]
    pub stroke_width: Option<f64>,
    #[serde(default)]
    pub rx: Option<f64>,
    #[serde(default)]
    pub label_fill: Option<String>,
    #[serde(default)]
    pub label_size: Option<f64>,
}
```

- [ ] **Step 4: Run contract tests**

Run:

```bash
cargo test -p dediren-contracts --test contract_roundtrip
```

Expected: PASS.

- [ ] **Step 5: Run schema tests again**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts
```

Expected: PASS.

- [ ] **Step 6: Commit contract types**

```bash
git add crates/dediren-contracts/src/lib.rs crates/dediren-contracts/tests/contract_roundtrip.rs
git commit -m "feat: add svg style policy contracts"
```

---

### Task 3: Refactor SVG Renderer Style Resolution

**Files:**
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`

- [ ] **Step 1: Write failing plugin assertions for rich styling**

Modify `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs` by adding:

```rust
#[test]
fn svg_renderer_applies_rich_policy_styles() {
    let input = serde_json::json!({
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/rich-svg.json")).unwrap()
        ).unwrap()
    });
    let mut cmd = Command::cargo_bin("dediren-plugin-svg-render").unwrap();
    cmd.arg("render")
        .write_stdin(serde_json::to_string(&input).unwrap());
    cmd.assert()
        .success()
        .stdout(predicate::str::contains(r##"fill="#f8fafc""##))
        .stdout(predicate::str::contains(r##"fill="#ecfeff""##))
        .stdout(predicate::str::contains(r##"stroke="#0891b2""##))
        .stdout(predicate::str::contains(r##"stroke="#7c3aed""##))
        .stdout(predicate::str::contains(r##"fill="#5b21b6""##));
}
```

- [ ] **Step 2: Run plugin tests to verify the new test fails**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_applies_rich_policy_styles
```

Expected: FAIL because the renderer still emits hard-coded colors.

- [ ] **Step 3: Update imports**

Modify imports in `crates/dediren-plugin-svg-render/src/main.rs`:

```rust
use dediren_contracts::{
    CommandEnvelope, LaidOutEdge, LaidOutGroup, LaidOutNode, LayoutResult, Point, RenderPolicy,
    RenderResult, SvgEdgeStyle, SvgGroupStyle, SvgNodeStyle, RENDER_RESULT_SCHEMA_VERSION,
};
```

- [ ] **Step 4: Add resolved style structs**

Add these structs below `RenderInput`:

```rust
#[derive(Debug, Clone)]
struct ResolvedStyle {
    background_fill: String,
    font_family: String,
    font_size: f64,
    node: ResolvedNodeStyle,
    edge: ResolvedEdgeStyle,
    group: ResolvedGroupStyle,
}

#[derive(Debug, Clone)]
struct ResolvedNodeStyle {
    fill: String,
    stroke: String,
    stroke_width: f64,
    rx: f64,
    label_fill: String,
}

#[derive(Debug, Clone)]
struct ResolvedEdgeStyle {
    stroke: String,
    stroke_width: f64,
    label_fill: String,
}

#[derive(Debug, Clone)]
struct ResolvedGroupStyle {
    fill: String,
    stroke: String,
    stroke_width: f64,
    rx: f64,
    label_fill: String,
    label_size: f64,
}
```

- [ ] **Step 5: Add style resolution functions**

Add these functions below `render_svg` or above it:

```rust
fn base_style(policy: &RenderPolicy) -> ResolvedStyle {
    let style = policy.style.as_ref();
    let background = style.and_then(|style| style.background.as_ref());
    let font = style.and_then(|style| style.font.as_ref());
    let node = style.and_then(|style| style.node.as_ref());
    let edge = style.and_then(|style| style.edge.as_ref());
    let group = style.and_then(|style| style.group.as_ref());

    ResolvedStyle {
        background_fill: background
            .and_then(|style| style.fill.clone())
            .unwrap_or_else(|| "#ffffff".to_string()),
        font_family: font
            .and_then(|style| style.family.clone())
            .unwrap_or_else(|| "Inter, Arial, sans-serif".to_string()),
        font_size: font.and_then(|style| style.size).unwrap_or(14.0),
        node: ResolvedNodeStyle {
            fill: node
                .and_then(|style| style.fill.clone())
                .unwrap_or_else(|| "#f8fafc".to_string()),
            stroke: node
                .and_then(|style| style.stroke.clone())
                .unwrap_or_else(|| "#334155".to_string()),
            stroke_width: node.and_then(|style| style.stroke_width).unwrap_or(1.5),
            rx: node.and_then(|style| style.rx).unwrap_or(6.0),
            label_fill: node
                .and_then(|style| style.label_fill.clone())
                .unwrap_or_else(|| "#0f172a".to_string()),
        },
        edge: ResolvedEdgeStyle {
            stroke: edge
                .and_then(|style| style.stroke.clone())
                .unwrap_or_else(|| "#64748b".to_string()),
            stroke_width: edge.and_then(|style| style.stroke_width).unwrap_or(1.5),
            label_fill: edge
                .and_then(|style| style.label_fill.clone())
                .unwrap_or_else(|| "#374151".to_string()),
        },
        group: ResolvedGroupStyle {
            fill: group
                .and_then(|style| style.fill.clone())
                .unwrap_or_else(|| "#eff6ff".to_string()),
            stroke: group
                .and_then(|style| style.stroke.clone())
                .unwrap_or_else(|| "#93c5fd".to_string()),
            stroke_width: group.and_then(|style| style.stroke_width).unwrap_or(1.0),
            rx: group.and_then(|style| style.rx).unwrap_or(8.0),
            label_fill: group
                .and_then(|style| style.label_fill.clone())
                .unwrap_or_else(|| "#1e3a8a".to_string()),
            label_size: group.and_then(|style| style.label_size).unwrap_or(12.0),
        },
    }
}

fn node_style(policy: &RenderPolicy, node_id: &str, base: &ResolvedStyle) -> ResolvedNodeStyle {
    let override_style = policy
        .style
        .as_ref()
        .and_then(|style| style.node_overrides.get(node_id));
    merge_node_style(&base.node, override_style)
}

fn edge_style(policy: &RenderPolicy, edge_id: &str, base: &ResolvedStyle) -> ResolvedEdgeStyle {
    let override_style = policy
        .style
        .as_ref()
        .and_then(|style| style.edge_overrides.get(edge_id));
    merge_edge_style(&base.edge, override_style)
}

fn group_style(policy: &RenderPolicy, group_id: &str, base: &ResolvedStyle) -> ResolvedGroupStyle {
    let override_style = policy
        .style
        .as_ref()
        .and_then(|style| style.group_overrides.get(group_id));
    merge_group_style(&base.group, override_style)
}

fn merge_node_style(base: &ResolvedNodeStyle, override_style: Option<&SvgNodeStyle>) -> ResolvedNodeStyle {
    let Some(override_style) = override_style else {
        return base.clone();
    };
    ResolvedNodeStyle {
        fill: override_style.fill.clone().unwrap_or_else(|| base.fill.clone()),
        stroke: override_style
            .stroke
            .clone()
            .unwrap_or_else(|| base.stroke.clone()),
        stroke_width: override_style.stroke_width.unwrap_or(base.stroke_width),
        rx: override_style.rx.unwrap_or(base.rx),
        label_fill: override_style
            .label_fill
            .clone()
            .unwrap_or_else(|| base.label_fill.clone()),
    }
}

fn merge_edge_style(base: &ResolvedEdgeStyle, override_style: Option<&SvgEdgeStyle>) -> ResolvedEdgeStyle {
    let Some(override_style) = override_style else {
        return base.clone();
    };
    ResolvedEdgeStyle {
        stroke: override_style
            .stroke
            .clone()
            .unwrap_or_else(|| base.stroke.clone()),
        stroke_width: override_style.stroke_width.unwrap_or(base.stroke_width),
        label_fill: override_style
            .label_fill
            .clone()
            .unwrap_or_else(|| base.label_fill.clone()),
    }
}

fn merge_group_style(base: &ResolvedGroupStyle, override_style: Option<&SvgGroupStyle>) -> ResolvedGroupStyle {
    let Some(override_style) = override_style else {
        return base.clone();
    };
    ResolvedGroupStyle {
        fill: override_style.fill.clone().unwrap_or_else(|| base.fill.clone()),
        stroke: override_style
            .stroke
            .clone()
            .unwrap_or_else(|| base.stroke.clone()),
        stroke_width: override_style.stroke_width.unwrap_or(base.stroke_width),
        rx: override_style.rx.unwrap_or(base.rx),
        label_fill: override_style
            .label_fill
            .clone()
            .unwrap_or_else(|| base.label_fill.clone()),
        label_size: override_style.label_size.unwrap_or(base.label_size),
    }
}
```

- [ ] **Step 6: Replace `render_svg` with style-aware rendering**

Replace the existing `render_svg` function:

```rust
fn render_svg(result: &LayoutResult, policy: &RenderPolicy) -> String {
    let style = base_style(policy);
    let mut svg = String::new();
    svg.push_str(&format!(
        r#"<svg xmlns="http://www.w3.org/2000/svg" width="{:.0}" height="{:.0}" viewBox="0 0 {:.0} {:.0}">"#,
        policy.page.width, policy.page.height, policy.page.width, policy.page.height
    ));
    svg.push_str(&format!(
        r#"<rect width="100%" height="100%" fill="{}"/>"#,
        escape_attr(&style.background_fill)
    ));
    svg.push_str(&format!(
        r#"<g font-family="{}" font-size="{:.1}">"#,
        escape_attr(&style.font_family),
        style.font_size
    ));

    for group in &result.groups {
        svg.push_str(&group_rect(group, &group_style(policy, &group.id, &style)));
    }

    for edge in &result.edges {
        let edge_style = edge_style(policy, &edge.id, &style);
        svg.push_str(&edge_path(&edge.points, &edge_style));
        svg.push_str(&edge_label(edge, &edge_style));
    }

    for node in &result.nodes {
        let node_style = node_style(policy, &node.id, &style);
        svg.push_str(&node_rect(node, &node_style));
        svg.push_str(&node_label(node, &node_style));
    }

    svg.push_str("</g></svg>\n");
    svg
}
```

- [ ] **Step 7: Add SVG emission helpers**

Replace `edge_path` and add helpers:

```rust
fn group_rect(group: &LaidOutGroup, style: &ResolvedGroupStyle) -> String {
    format!(
        r##"<g data-dediren-group-id="{}"><rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="{:.1}" fill="{}" stroke="{}" stroke-width="{:.1}"/><text x="{:.1}" y="{:.1}" fill="{}" font-size="{:.1}">{}</text></g>"##,
        escape_attr(&group.id),
        group.x,
        group.y,
        group.width,
        group.height,
        style.rx,
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        style.stroke_width,
        group.x + 8.0,
        group.y + style.label_size + 4.0,
        escape_attr(&style.label_fill),
        style.label_size,
        escape_text(&group.label)
    )
}

fn node_rect(node: &LaidOutNode, style: &ResolvedNodeStyle) -> String {
    format!(
        r##"<rect data-dediren-node-id="{}" x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="{:.1}" fill="{}" stroke="{}" stroke-width="{:.1}"/>"##,
        escape_attr(&node.id),
        node.x,
        node.y,
        node.width,
        node.height,
        style.rx,
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        style.stroke_width
    )
}

fn node_label(node: &LaidOutNode, style: &ResolvedNodeStyle) -> String {
    format!(
        r##"<text x="{:.1}" y="{:.1}" text-anchor="middle" dominant-baseline="middle" fill="{}">{}</text>"##,
        node.x + node.width / 2.0,
        node.y + node.height / 2.0,
        escape_attr(&style.label_fill),
        escape_text(&node.label)
    )
}

fn edge_path(points: &[Point], style: &ResolvedEdgeStyle) -> String {
    if points.len() < 2 {
        return String::new();
    }
    let mut data = format!("M {:.1} {:.1}", points[0].x, points[0].y);
    for point in points.iter().skip(1) {
        data.push_str(&format!(" L {:.1} {:.1}", point.x, point.y));
    }
    format!(
        r##"<path d="{data}" fill="none" stroke="{}" stroke-width="{:.1}"/>"##,
        escape_attr(&style.stroke),
        style.stroke_width
    )
}

fn edge_label(edge: &LaidOutEdge, style: &ResolvedEdgeStyle) -> String {
    let Some(point) = edge.points.first() else {
        return String::new();
    };
    format!(
        r##"<text data-dediren-edge-id="{}" x="{:.1}" y="{:.1}" fill="{}">{}</text>"##,
        escape_attr(&edge.id),
        point.x,
        point.y - 8.0,
        escape_attr(&style.label_fill),
        escape_text(&edge.label)
    )
}
```

- [ ] **Step 8: Split text and attribute escaping**

Replace `escape` with:

```rust
fn escape_text(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn escape_attr(value: &str) -> String {
    escape_text(value).replace('"', "&quot;")
}
```

- [ ] **Step 9: Format and run plugin tests**

Run:

```bash
cargo fmt
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
```

Expected: PASS.

- [ ] **Step 10: Commit renderer changes**

```bash
git add crates/dediren-plugin-svg-render/src/main.rs crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
git commit -m "feat: apply svg render policy styles"
```

---

### Task 4: Add CLI Coverage For Rich Styling

**Files:**
- Modify: `crates/dediren-cli/tests/cli_render.rs`

- [ ] **Step 1: Add a failing CLI render test**

Modify `crates/dediren-cli/tests/cli_render.rs` by adding:

```rust
#[test]
fn render_invokes_svg_plugin_with_rich_policy() {
    let plugin = workspace_binary("dediren-plugin-svg-render", "dediren-plugin-svg-render");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.env("DEDIREN_PLUGIN_SVG_RENDER", plugin)
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/rich-svg.json"))
        .args(["--input"])
        .arg(workspace_file("fixtures/layout-result/basic.json"));
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"render_result_schema_version\""))
        .stdout(predicate::str::contains(r##"fill="#ecfeff""##))
        .stdout(predicate::str::contains(r##"stroke="#7c3aed""##));
}
```

- [ ] **Step 2: Run the CLI rich render test**

Run:

```bash
cargo test -p dediren --test cli_render render_invokes_svg_plugin_with_rich_policy
```

Expected after Task 3: PASS. If it fails, inspect whether `workspace_binary` built the plugin and whether the expected style fixture path is correct.

- [ ] **Step 3: Run all render CLI tests**

Run:

```bash
cargo test -p dediren --test cli_render
```

Expected: PASS.

- [ ] **Step 4: Commit CLI coverage**

```bash
git add crates/dediren-cli/tests/cli_render.rs
git commit -m "test: cover rich svg styling through cli"
```

---

### Task 5: Document Rich SVG Styling

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update README render examples**

Modify `README.md`. After the existing `render` command example, add:

```bash
dediren render --plugin svg-render --policy fixtures/render-policy/rich-svg.json --input fixtures/layout-result/basic.json
```

- [ ] **Step 2: Add a short SVG styling section**

Add this section after the current `render`/`export` output explanation:

```markdown
## SVG Styling

SVG styling is owned by the render policy. Source graph JSON and layout result
JSON stay presentation-free; they do not carry colors, fonts, shapes, or style
hints.

`fixtures/render-policy/default-svg.json` uses renderer defaults.
`fixtures/render-policy/rich-svg.json` shows optional styling for background,
font, nodes, edges, groups, and per-layout-id overrides. Per-id override keys
match ids in the layout result, for example `api` or `client-calls-api`.
```

- [ ] **Step 3: Verify docs preserve the styling boundary**

Run:

```bash
rg -n "Source graph JSON and layout result|rich-svg.json|Per-id override keys" README.md
```

Expected: three matches in the new SVG styling section.

- [ ] **Step 4: Commit docs**

```bash
git add README.md
git commit -m "docs: document rich svg styling policy"
```

---

### Task 6: Final Verification

**Files:**
- Verify only unless audit findings require fixes.

- [ ] **Step 1: Run formatting**

Run:

```bash
cargo fmt --check
```

Expected: PASS.

- [ ] **Step 2: Run the full Rust test suite**

Run:

```bash
cargo test --workspace
```

Expected: PASS. Real Java ELK ignored tests may remain ignored by default; this styling slice does not require them.

- [ ] **Step 3: Render a rich SVG envelope manually**

Run:

```bash
DEDIREN_PLUGIN_SVG_RENDER=target/debug/dediren-plugin-svg-render \
  cargo run -p dediren -- render \
  --plugin svg-render \
  --policy fixtures/render-policy/rich-svg.json \
  --input fixtures/layout-result/basic.json
```

Expected: command exits `0` and stdout is a JSON envelope containing:

```text
"status":"ok"
"render_result_schema_version":"render-result.schema.v1"
fill="#ecfeff"
stroke="#7c3aed"
```

- [ ] **Step 4: Run test-quality audit validation**

Use `souroldgeezer-audit:test-quality-audit` in Quick mode against the changed
test files and fixtures:

```text
Target files:
- crates/dediren-contracts/tests/schema_contracts.rs
- crates/dediren-contracts/tests/contract_roundtrip.rs
- crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
- crates/dediren-cli/tests/cli_render.rs
- fixtures/render-policy/rich-svg.json

Audit question:
Do these tests prove the public SVG styling policy contract, renderer behavior,
and CLI integration without brittle implementation-only assertions or false
confidence?
```

Expected: no block findings. If the audit reports block or warn findings, fix
the tests or explicitly document the residual risk before completing the branch.

- [ ] **Step 5: Run DevSecOps audit validation**

Use `souroldgeezer-audit:devsecops-audit` in Quick mode against the changed
schema, renderer, fixture, README, and dependency posture:

```text
Target files:
- schemas/svg-render-policy.schema.json
- fixtures/render-policy/rich-svg.json
- crates/dediren-contracts/src/lib.rs
- crates/dediren-plugin-svg-render/src/main.rs
- README.md

Audit question:
Does this styling slice preserve safe SVG output, avoid external asset/script
loading, avoid dependency or supply-chain expansion, and keep executable plugin
behavior within the existing process-boundary posture?
```

Expected: no block findings. If the audit reports block or warn findings, fix
the implementation or explicitly document the residual risk before completing
the branch.

- [ ] **Step 6: Confirm working tree state**

Run:

```bash
git status --short --branch
```

Expected: clean branch after commits, or only intentional uncommitted changes if the user asked not to commit.

---

## Self-Review

- Spec coverage: implements the deferred “Rich SVG styling policy” item while preserving the original rule that source graph and layout request/result data do not carry styling.
- Compatibility: existing `fixtures/render-policy/default-svg.json` remains valid because all new style fields are optional.
- Boundary: no PNG, alternate renderer, external CSS, codegen, release automation, sandboxing, or plugin signing work is included.
- Software design: styling volatility is isolated in the SVG plugin/policy boundary; shared contracts carry data shape only.
- Validation: schema tests, contract round-trip tests, plugin tests, CLI tests, formatting, full workspace tests, `test-quality-audit` Quick review, and `devsecops-audit` Quick review cover the slice.
