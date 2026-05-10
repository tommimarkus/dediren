# ArchiMate Render Decorators Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing ArchiMate SVG render style from colors to notation decorators and relationship marker styling without adding a second styling system.

**Architecture:** Keep render metadata selector-only and style-free. Add optional decorator and relationship notation fields to `svg-render-policy.schema.v1`, resolve them through the existing base -> type override -> id override style chain, and render them in `dediren-plugin-svg-render` using deterministic SVG primitives. Keep layout and OEF export unchanged.

**Tech Stack:** Rust workspace, `serde`, JSON Schema draft 2020-12, first-party SVG render plugin, `roxmltree`, `assert_cmd`, command envelopes.

---

## File Map

- Modify: `schemas/svg-render-policy.schema.json`
- Modify: `fixtures/render-policy/archimate-svg.json`
- Modify: `crates/dediren-contracts/src/lib.rs`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`
- Modify: `crates/dediren-contracts/tests/contract_roundtrip.rs`
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- Modify: `crates/dediren-cli/tests/cli_render.rs`
- Modify: `README.md`

Do not modify `schemas/render-metadata.schema.json`, `fixtures/render-metadata/*.json`, `dediren-core`, `dediren-cli` argument parsing, `generic-graph`, `elk-layout`, or OEF export code for this slice.

---

## Contract Rules

- Decorators and relationship notation are SVG render policy fields.
- Render metadata remains limited to `semantic_profile`, node selectors, edge selectors, and `source_id`.
- Layout result geometry is not changed by decorators. Node decorators must stay inside the node rectangle.
- Existing policies without new fields render exactly as before.
- Id overrides continue to win over type overrides for every style field, including decorators and relationship notation.

Use these enum values for the first slice:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgNodeDecorator {
    ArchimateApplicationComponent,
    ArchimateApplicationService,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgEdgeLineStyle {
    Solid,
    Dashed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgEdgeMarkerEnd {
    FilledArrow,
    HollowTriangle,
    None,
}
```

The `None` enum value serializes as JSON string `"none"`.

---

### Task 1: Add Policy Contract Fields

**Files:**
- Modify: `schemas/svg-render-policy.schema.json`
- Modify: `crates/dediren-contracts/src/lib.rs`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`
- Modify: `crates/dediren-contracts/tests/contract_roundtrip.rs`

- [ ] **Step 1: Add failing schema tests**

Add these tests to `crates/dediren-contracts/tests/schema_contracts.rs`:

```rust
#[test]
fn svg_policy_schema_accepts_archimate_decorators_and_edge_notation() {
    assert_json_schema_valid(
        "schemas/svg-render-policy.schema.json",
        &serde_json::json!({
            "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
            "semantic_profile": "archimate",
            "page": { "width": 640, "height": 360 },
            "margin": { "top": 24, "right": 24, "bottom": 24, "left": 24 },
            "style": {
                "node_type_overrides": {
                    "ApplicationComponent": {
                        "fill": "#fff2cc",
                        "stroke": "#7a5c00",
                        "decorator": "archimate_application_component"
                    },
                    "ApplicationService": {
                        "fill": "#e0f2fe",
                        "stroke": "#0369a1",
                        "decorator": "archimate_application_service"
                    }
                },
                "edge_type_overrides": {
                    "Realization": {
                        "stroke": "#374151",
                        "line_style": "dashed",
                        "marker_end": "hollow_triangle"
                    }
                }
            }
        }),
    );
}

#[test]
fn svg_policy_schema_rejects_unknown_node_decorator() {
    assert_json_schema_invalid(
        "schemas/svg-render-policy.schema.json",
        &serde_json::json!({
            "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
            "page": { "width": 640, "height": 360 },
            "margin": { "top": 24, "right": 24, "bottom": 24, "left": 24 },
            "style": {
                "node": {
                    "decorator": "unknown_archimate_shape"
                }
            }
        }),
    );
}
```

- [ ] **Step 2: Run the failing schema test**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts svg_policy_schema_accepts_archimate_decorators_and_edge_notation -- --exact
```

Expected: FAIL because `decorator`, `line_style`, and `marker_end` are not allowed yet.

- [ ] **Step 3: Extend the JSON schema**

In `schemas/svg-render-policy.schema.json`, add these properties to the node style definition:

```json
"decorator": {
  "type": "string",
  "enum": [
    "archimate_application_component",
    "archimate_application_service"
  ]
}
```

Add these properties to the edge style definition:

```json
"line_style": {
  "type": "string",
  "enum": ["solid", "dashed"]
},
"marker_end": {
  "type": "string",
  "enum": ["filled_arrow", "hollow_triangle", "none"]
}
```

- [ ] **Step 4: Extend Rust contracts**

In `crates/dediren-contracts/src/lib.rs`, add enum fields to the style structs:

```rust
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
    #[serde(default)]
    pub decorator: Option<SvgNodeDecorator>,
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
    #[serde(default)]
    pub line_style: Option<SvgEdgeLineStyle>,
    #[serde(default)]
    pub marker_end: Option<SvgEdgeMarkerEnd>,
    #[serde(default)]
    pub label_horizontal_position: Option<SvgEdgeLabelHorizontalPosition>,
    #[serde(default)]
    pub label_horizontal_side: Option<SvgEdgeLabelHorizontalSide>,
    #[serde(default)]
    pub label_vertical_position: Option<SvgEdgeLabelVerticalPosition>,
    #[serde(default)]
    pub label_vertical_side: Option<SvgEdgeLabelVerticalSide>,
}
```

Add the enum definitions near the related style enums:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgNodeDecorator {
    ArchimateApplicationComponent,
    ArchimateApplicationService,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgEdgeLineStyle {
    Solid,
    Dashed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgEdgeMarkerEnd {
    FilledArrow,
    HollowTriangle,
    None,
}
```

- [ ] **Step 5: Add a round-trip assertion**

Add this test to `crates/dediren-contracts/tests/contract_roundtrip.rs`:

```rust
#[test]
fn svg_policy_decorator_fields_round_trip() {
    let policy = RenderPolicy {
        svg_render_policy_schema_version: SVG_RENDER_POLICY_SCHEMA_VERSION.to_string(),
        semantic_profile: Some("archimate".to_string()),
        page: Page {
            width: 640.0,
            height: 360.0,
        },
        margin: Margin {
            top: 24.0,
            right: 24.0,
            bottom: 24.0,
            left: 24.0,
        },
        style: Some(SvgStylePolicy {
            node_type_overrides: BTreeMap::from([(
                "ApplicationComponent".to_string(),
                SvgNodeStyle {
                    decorator: Some(SvgNodeDecorator::ArchimateApplicationComponent),
                    ..SvgNodeStyle::default()
                },
            )]),
            edge_type_overrides: BTreeMap::from([(
                "Realization".to_string(),
                SvgEdgeStyle {
                    line_style: Some(SvgEdgeLineStyle::Dashed),
                    marker_end: Some(SvgEdgeMarkerEnd::HollowTriangle),
                    ..SvgEdgeStyle::default()
                },
            )]),
            ..SvgStylePolicy::default()
        }),
    };

    let json = serde_json::to_value(&policy).expect("serialize policy");
    assert_eq!(
        json["style"]["node_type_overrides"]["ApplicationComponent"]["decorator"],
        "archimate_application_component"
    );
    assert_eq!(
        json["style"]["edge_type_overrides"]["Realization"]["line_style"],
        "dashed"
    );
    assert_eq!(
        json["style"]["edge_type_overrides"]["Realization"]["marker_end"],
        "hollow_triangle"
    );

    let round_tripped: RenderPolicy = serde_json::from_value(json).expect("deserialize policy");
    assert_eq!(round_tripped, policy);
}
```

Import the new enum names in the existing `use dediren_contracts::{ ... }` block.

- [ ] **Step 6: Run contract tests**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts svg_policy_schema_accepts_archimate_decorators_and_edge_notation -- --exact
cargo test -p dediren-contracts --test schema_contracts svg_policy_schema_rejects_unknown_node_decorator -- --exact
cargo test -p dediren-contracts --test contract_roundtrip svg_policy_decorator_fields_round_trip -- --exact
```

Expected: PASS.

- [ ] **Step 7: Commit contract changes**

Run:

```bash
git add schemas/svg-render-policy.schema.json crates/dediren-contracts/src/lib.rs crates/dediren-contracts/tests/schema_contracts.rs crates/dediren-contracts/tests/contract_roundtrip.rs
git commit -m "Add SVG render decorator policy fields"
```

---

### Task 2: Render Node Decorators

**Files:**
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`

- [ ] **Step 1: Add failing renderer tests**

Add this test to `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`:

```rust
#[test]
fn svg_renderer_applies_archimate_node_decorators_from_type_overrides() {
    let input = archimate_style_input();
    let content = render_content(input);
    let doc = svg_doc(&content);

    let component = semantic_group(&doc, "data-dediren-node-id", "orders-component");
    let service = semantic_group(&doc, "data-dediren-node-id", "orders-service");

    let component_decorator = child_group_with_attr(
        component,
        "data-dediren-node-decorator",
        "archimate_application_component",
    );
    let service_decorator = child_group_with_attr(
        service,
        "data-dediren-node-decorator",
        "archimate_application_service",
    );

    assert!(child_elements(component_decorator, "rect").count() >= 2);
    assert!(child_elements(service_decorator, "path").count() >= 1);
}
```

Add these local helpers if they do not already exist:

```rust
fn child_group_with_attr<'a>(
    parent: roxmltree::Node<'a, 'a>,
    attr_name: &str,
    attr_value: &str,
) -> roxmltree::Node<'a, 'a> {
    parent
        .children()
        .find(|node| {
            node.is_element()
                && node.tag_name().name() == "g"
                && node.attribute(attr_name) == Some(attr_value)
        })
        .unwrap_or_else(|| panic!("missing child group with {attr_name}={attr_value}"))
}

fn child_elements<'a>(
    parent: roxmltree::Node<'a, 'a>,
    name: &'static str,
) -> impl Iterator<Item = roxmltree::Node<'a, 'a>> {
    parent
        .children()
        .filter(move |node| node.is_element() && node.tag_name().name() == name)
}
```

- [ ] **Step 2: Run the failing renderer test**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_applies_archimate_node_decorators_from_type_overrides -- --exact
```

Expected: FAIL because no decorator group is emitted.

- [ ] **Step 3: Resolve node decorator style**

In `crates/dediren-plugin-svg-render/src/main.rs`, import the enum:

```rust
use dediren_contracts::{
    LayoutResult, LaidOutEdge, LaidOutGroup, LaidOutNode, Point, RenderMetadata, RenderPolicy,
    SvgEdgeLabelHorizontalPosition, SvgEdgeLabelHorizontalSide, SvgEdgeLabelVerticalPosition,
    SvgEdgeLabelVerticalSide, SvgEdgeLineStyle, SvgEdgeMarkerEnd, SvgEdgeStyle, SvgGroupStyle,
    SvgNodeDecorator, SvgNodeStyle,
};
```

Add the field to `ResolvedNodeStyle`:

```rust
struct ResolvedNodeStyle {
    fill: String,
    stroke: String,
    stroke_width: f64,
    rx: f64,
    label_fill: String,
    decorator: Option<SvgNodeDecorator>,
}
```

Set the default in the existing constructor:

```rust
decorator: None,
```

Merge it in the existing `ResolvedNodeStyle::merge`:

```rust
self.decorator = style.decorator.or(self.decorator);
```

- [ ] **Step 4: Emit decorators inside node groups**

In the node rendering loop, emit the decorator after `node_rect` and before `node_label`:

```rust
svg.push_str(&node_rect(node, &node_style));
svg.push_str(&node_decorator(node, &node_style));
svg.push_str(&node_label(node, &node_style));
```

Add this helper:

```rust
fn node_decorator(node: &LaidOutNode, style: &ResolvedNodeStyle) -> String {
    match style.decorator {
        Some(SvgNodeDecorator::ArchimateApplicationComponent) => {
            archimate_application_component_decorator(node, style)
        }
        Some(SvgNodeDecorator::ArchimateApplicationService) => {
            archimate_application_service_decorator(node, style)
        }
        None => String::new(),
    }
}
```

Add deterministic SVG primitives that stay inside the node rectangle:

```rust
fn archimate_application_component_decorator(
    node: &LaidOutNode,
    style: &ResolvedNodeStyle,
) -> String {
    let size = node.width.min(node.height).min(22.0).max(14.0);
    let x = node.x + node.width - size - 8.0;
    let y = node.y + 8.0;
    let tab_width = size * 0.42;
    let tab_height = size * 0.26;
    format!(
        r##"<g data-dediren-node-decorator="archimate_application_component"><rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="1.5" fill="{}" stroke="{}" stroke-width="{}"/><rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="1.2" fill="{}" stroke="{}" stroke-width="{}"/><rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="1.2" fill="{}" stroke="{}" stroke-width="{}"/></g>"##,
        x,
        y,
        size,
        size * 0.72,
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width),
        x - tab_width * 0.28,
        y + size * 0.12,
        tab_width,
        tab_height,
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width),
        x - tab_width * 0.28,
        y + size * 0.44,
        tab_width,
        tab_height,
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width)
    )
}

fn archimate_application_service_decorator(
    node: &LaidOutNode,
    style: &ResolvedNodeStyle,
) -> String {
    let size = node.width.min(node.height).min(22.0).max(14.0);
    let cx = node.x + node.width - size * 0.75 - 8.0;
    let cy = node.y + size * 0.75 + 8.0;
    let r = size * 0.36;
    format!(
        r##"<g data-dediren-node-decorator="archimate_application_service"><circle cx="{:.1}" cy="{:.1}" r="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><path d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round" stroke-linejoin="round"/></g>"##,
        cx,
        cy,
        r,
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width),
        cx - r * 0.45,
        cy,
        cx - r * 0.05,
        cy + r * 0.38,
        cx + r * 0.55,
        cy - r * 0.45,
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width)
    )
}
```

- [ ] **Step 5: Run renderer tests**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_applies_archimate_node_decorators_from_type_overrides -- --exact
```

Expected: PASS.

- [ ] **Step 6: Commit node decorator renderer**

Run:

```bash
git add crates/dediren-plugin-svg-render/src/main.rs crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
git commit -m "Render ArchiMate node decorators"
```

---

### Task 3: Render Relationship Notation

**Files:**
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`

- [ ] **Step 1: Add failing renderer tests for edge notation**

Add this test to `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`:

```rust
#[test]
fn svg_renderer_applies_archimate_realization_edge_notation() {
    let input = archimate_style_input();
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "orders-realizes-service");
    let path = child_element(edge, "path");
    assert_eq!(path.attribute("stroke-dasharray"), Some("8 5"));
    assert_eq!(
        path.attribute("marker-end"),
        Some("url(#marker-end-orders-realizes-service)")
    );

    let marker = doc
        .descendants()
        .find(|node| {
            node.is_element()
                && node.tag_name().name() == "marker"
                && node.attribute("id") == Some("marker-end-orders-realizes-service")
                && node.attribute("data-dediren-edge-marker-end") == Some("hollow_triangle")
        })
        .expect("hollow triangle marker");
    let marker_path = child_element(marker, "path");
    assert_eq!(marker_path.attribute("fill"), Some("#ffffff"));
    assert_eq!(marker_path.attribute("stroke"), Some("#374151"));
}
```

Add this test for disabling markers through id override:

```rust
#[test]
fn svg_renderer_edge_id_override_can_disable_marker() {
    let mut input = archimate_style_input();
    input["render_policy"]["style"]["edge_overrides"] = serde_json::json!({
        "orders-realizes-service": {
            "marker_end": "none",
            "line_style": "solid"
        }
    });

    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "orders-realizes-service");
    let path = child_element(edge, "path");
    assert_eq!(path.attribute("marker-end"), None);
    assert_eq!(path.attribute("stroke-dasharray"), None);
}
```

- [ ] **Step 2: Run the failing renderer test**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_applies_archimate_realization_edge_notation -- --exact
```

Expected: FAIL because all edges still use the hardcoded filled arrow marker.

- [ ] **Step 3: Resolve edge notation style**

In `crates/dediren-plugin-svg-render/src/main.rs`, add fields to `ResolvedEdgeStyle`:

```rust
struct ResolvedEdgeStyle {
    stroke: String,
    stroke_width: f64,
    label_fill: String,
    line_style: SvgEdgeLineStyle,
    marker_end: SvgEdgeMarkerEnd,
    label_horizontal_position: SvgEdgeLabelHorizontalPosition,
    label_horizontal_side: SvgEdgeLabelHorizontalSide,
    label_vertical_position: SvgEdgeLabelVerticalPosition,
    label_vertical_side: SvgEdgeLabelVerticalSide,
}
```

Set defaults in the constructor:

```rust
line_style: SvgEdgeLineStyle::Solid,
marker_end: SvgEdgeMarkerEnd::FilledArrow,
```

Merge optional policy fields in `ResolvedEdgeStyle::merge`:

```rust
self.line_style = style.line_style.unwrap_or(self.line_style);
self.marker_end = style.marker_end.unwrap_or(self.marker_end);
```

- [ ] **Step 4: Replace hardcoded edge marker rendering**

Update the edge loop to emit a marker only when one is needed:

```rust
svg.push_str(&edge_marker(edge, &edge_style));
svg.push_str(&edge_path(edge, &edge_style, &edge_refs[..index]));
```

Implement `edge_marker` as:

```rust
fn edge_marker(edge: &LaidOutEdge, style: &ResolvedEdgeStyle) -> String {
    match style.marker_end {
        SvgEdgeMarkerEnd::FilledArrow => format!(
            r##"<defs><marker id="{}" data-dediren-edge-marker-end="filled_arrow" markerWidth="8" markerHeight="8" refX="8" refY="4" orient="auto" markerUnits="strokeWidth"><path d="M 0 0 L 8 4 L 0 8 z" fill="{}"/></marker></defs>"##,
            escape_attr(&edge_marker_id(&edge.id)),
            escape_attr(&style.stroke)
        ),
        SvgEdgeMarkerEnd::HollowTriangle => format!(
            r##"<defs><marker id="{}" data-dediren-edge-marker-end="hollow_triangle" markerWidth="10" markerHeight="10" refX="9" refY="5" orient="auto" markerUnits="strokeWidth"><path d="M 1 1 L 9 5 L 1 9 z" fill="#ffffff" stroke="{}" stroke-width="1.2"/></marker></defs>"##,
            escape_attr(&edge_marker_id(&edge.id)),
            escape_attr(&style.stroke)
        ),
        SvgEdgeMarkerEnd::None => String::new(),
    }
}
```

Change `edge_marker_id` to use the new prefix:

```rust
fn edge_marker_id(edge_id: &str) -> String {
    format!("marker-end-{}", sanitize_svg_id(edge_id))
}
```

Update `edge_path` to emit optional marker and dash attributes:

```rust
fn edge_path(
    edge: &LaidOutEdge,
    style: &ResolvedEdgeStyle,
    earlier_edges: &[&LaidOutEdge],
) -> String {
    let points = &edge.points;
    if points.len() < 2 {
        return String::new();
    }
    let data = edge_path_data(points, earlier_edges);
    let dash_attr = match style.line_style {
        SvgEdgeLineStyle::Solid => String::new(),
        SvgEdgeLineStyle::Dashed => r#" stroke-dasharray="8 5""#.to_string(),
    };
    let marker_attr = match style.marker_end {
        SvgEdgeMarkerEnd::None => String::new(),
        SvgEdgeMarkerEnd::FilledArrow | SvgEdgeMarkerEnd::HollowTriangle => {
            format!(r#" marker-end="url(#{})""#, escape_attr(&edge_marker_id(&edge.id)))
        }
    };
    format!(
        r##"<path d="{}" fill="none" stroke="{}" stroke-width="{}"{}{} />"##,
        escape_attr(&data),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width),
        dash_attr,
        marker_attr
    )
}
```

If existing snapshot-style assertions expect `url(#arrow-...)`, update them to `url(#marker-end-...)`.

- [ ] **Step 5: Run renderer tests**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_applies_archimate_realization_edge_notation -- --exact
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_edge_id_override_can_disable_marker -- --exact
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
```

Expected: PASS.

- [ ] **Step 6: Commit relationship notation renderer**

Run:

```bash
git add crates/dediren-plugin-svg-render/src/main.rs crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
git commit -m "Render ArchiMate relationship notation"
```

---

### Task 4: Update ArchiMate Policy Fixture, CLI Coverage, And README

**Files:**
- Modify: `fixtures/render-policy/archimate-svg.json`
- Modify: `crates/dediren-cli/tests/cli_render.rs`
- Modify: `README.md`

- [ ] **Step 1: Add decorator fields to the ArchiMate fixture**

Update `fixtures/render-policy/archimate-svg.json` type overrides:

```json
"node_type_overrides": {
  "ApplicationComponent": {
    "fill": "#fff2cc",
    "stroke": "#7a5c00",
    "label_fill": "#3f3000",
    "decorator": "archimate_application_component"
  },
  "ApplicationService": {
    "fill": "#e0f2fe",
    "stroke": "#0369a1",
    "label_fill": "#0c4a6e",
    "decorator": "archimate_application_service"
  }
},
"edge_type_overrides": {
  "Realization": {
    "stroke": "#374151",
    "stroke_width": 1.5,
    "label_fill": "#374151",
    "line_style": "dashed",
    "marker_end": "hollow_triangle"
  }
}
```

- [ ] **Step 2: Extend CLI test assertions**

In `crates/dediren-cli/tests/cli_render.rs`, find the ArchiMate metadata/policy render test and add assertions against the generated SVG text:

```rust
assert!(
    svg.contains(r#"data-dediren-node-decorator="archimate_application_component""#),
    "expected ApplicationComponent decorator in ArchiMate SVG"
);
assert!(
    svg.contains(r#"data-dediren-node-decorator="archimate_application_service""#),
    "expected ApplicationService decorator in ArchiMate SVG"
);
assert!(
    svg.contains(r#"data-dediren-edge-marker-end="hollow_triangle""#),
    "expected Realization hollow triangle marker in ArchiMate SVG"
);
assert!(
    svg.contains(r#"stroke-dasharray="8 5""#),
    "expected Realization dashed line in ArchiMate SVG"
);
```

- [ ] **Step 3: Update README render policy documentation**

In `README.md`, update the SVG render policy section to state:

````markdown
ArchiMate-oriented SVG notation is still configured through the SVG render
policy. Render metadata only selects semantic types. The policy may attach
decorators and relationship notation to those exact types:

```json
{
  "semantic_profile": "archimate",
  "style": {
    "node_type_overrides": {
      "ApplicationComponent": {
        "decorator": "archimate_application_component"
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
````

- [ ] **Step 4: Run CLI render test**

Run:

```bash
cargo test -p dediren --test cli_render
```

Expected: PASS.

- [ ] **Step 5: Commit fixture and docs**

Run:

```bash
git add fixtures/render-policy/archimate-svg.json crates/dediren-cli/tests/cli_render.rs README.md
git commit -m "Document ArchiMate SVG notation policy"
```

---

### Task 5: Full Verification And Audits

**Files:**
- No source edits expected unless verification finds a defect.

- [ ] **Step 1: Run format and diff checks**

Run:

```bash
cargo fmt --all -- --check
git diff --check
```

Expected: PASS.

- [ ] **Step 2: Run contract and render lanes**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts
cargo test -p dediren-contracts --test contract_roundtrip
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
cargo test -p dediren --test cli_render
```

Expected: PASS.

- [ ] **Step 3: Run broader workspace regression**

Run:

```bash
cargo test --workspace --locked
```

Expected: PASS.

- [ ] **Step 4: Run audit gates named by the plan policy**

Use `souroldgeezer-audit:test-quality-audit` in quick mode over the changed contract, SVG render, and CLI tests. Confirm:

```text
PASS: decorator enum schema coverage exists.
PASS: renderer tests assert semantic SVG output for node decorators.
PASS: renderer tests assert relationship marker and dash behavior.
PASS: CLI test proves the public render flow emits ArchiMate notation.
```

Use `souroldgeezer-audit:devsecops-audit` in quick mode over the implementation diff. Confirm:

```text
PASS: no original source graph is passed into render.
PASS: render metadata remains style-free.
PASS: no external assets, network fetches, or new runtime dependencies are added.
PASS: SVG attributes are emitted through existing escaping helpers.
```

- [ ] **Step 5: Final status check**

Run:

```bash
git status --short --branch
```

Expected: clean working tree on the feature branch or `main`, with only intentional commits ahead of the upstream branch.

---

## Self-Review

- Spec coverage: The plan extends the already implemented ArchiMate render style through the same SVG render policy and render metadata selector flow. It explicitly keeps layout, projection metadata shape, OEF export, and CLI argument parsing out of scope.
- Placeholder scan: No task uses undefined future placeholders. Every code-changing step names exact fields, enum values, helpers, tests, and commands.
- Type consistency: JSON enum strings map directly to Rust enum variants through `serde(rename_all = "snake_case")`. Renderer resolution uses the same base -> type override -> id override merge path as colors.

## Execution Options

Plan complete and saved to `docs/superpowers/plans/2026-05-10-dediren-archimate-render-decorators.md`. Two execution options:

1. Subagent-Driven (recommended) - Dispatch a fresh subagent per task, review between tasks, fast iteration.
2. Inline Execution - Execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints.
