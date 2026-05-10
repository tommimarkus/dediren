# Render Output Quality Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every generated SVG under `.test-output/renders` readable without large empty canvases, label collisions, or overly stretched real-ELK layouts.

**Architecture:** Keep source data semantic and presentation-free. Keep generated geometry in `elk-layout`, SVG viewport and label rendering in `svg-render`, and render-quality assertions in tests that inspect the JSON envelope and SVG XML semantically. Do not add authored coordinates to source fixtures.

**Tech Stack:** Rust workspace, first-party process plugins, `dediren-contracts`, Java ELK helper, `roxmltree`, `assert_cmd`, SVG artifact writes under `.test-output/renders`, SDKMAN/Gradle ELK helper build.

---

## Current Render Evidence

Reviewed these 15 SVG artifacts from `.test-output/renders`:

- `.test-output/renders/cli-pipeline/full_pipeline_produces_svg_and_oef.svg`
- `.test-output/renders/cli-pipeline/real_elk_pipeline_renders_rich_source.svg`
- `.test-output/renders/cli-render/render_invokes_svg_plugin.svg`
- `.test-output/renders/cli-render/render_invokes_svg_plugin_with_rich_policy.svg`
- `.test-output/renders/svg-render-plugin/svg_renderer_adds_line_jump_for_later_crossing_edge.svg`
- `.test-output/renders/svg-render-plugin/svg_renderer_allows_schema_valid_non_ascii_font_family.svg`
- `.test-output/renders/svg-render-plugin/svg_renderer_applies_base_and_override_group_styles_to_group_elements.svg`
- `.test-output/renders/svg-render-plugin/svg_renderer_applies_rich_policy_styles.svg`
- `.test-output/renders/svg-render-plugin/svg_renderer_expands_viewbox_to_include_edge_labels.svg`
- `.test-output/renders/svg-render-plugin/svg_renderer_moves_edge_label_away_from_node_boxes.svg`
- `.test-output/renders/svg-render-plugin/svg_renderer_outputs_svg.svg`
- `.test-output/renders/svg-render-plugin/svg_renderer_paints_edge_label_with_background_halo.svg`
- `.test-output/renders/svg-render-plugin/svg_renderer_places_edge_label_near_route_midpoint_for_vertical_route.svg`
- `.test-output/renders/svg-render-plugin/svg_renderer_prefers_horizontal_segment_for_edge_label.svg`
- `.test-output/renders/svg-render-plugin/svg_renderer_preserves_style_number_precision.svg`

## Findings To Fix

1. Small renders are placed on large policy-sized canvases. `svg_bounds` seeds bounds from `policy.page.width` and `policy.page.height`, so two-node tests and simple CLI renders produce large pale empty backgrounds.
2. The fixture-backed pipeline render is readable, but edge labels still sit on top of edge segments and group borders. Examples: `publishes fulfillment`, `writes orders`, and `loads order`.
3. The real ELK rich pipeline render is too wide and too short. It lays `Client`, `Application Services`, and `External Dependencies` as a long horizontal strip with a `viewBox` of `-32.0 -32.0 1932.0 864.0`, leaving most of the canvas empty.
4. The real ELK grouped layout uses macro group boxes and then manual `routeBetween` paths for cross-group edges. Those routes ignore the actual member-node side where the relationship starts or ends, so labels are centered on long group-to-group spans instead of near meaningful route segments.
5. There is no regression test that treats render artifact quality as a first-class contract. Existing tests prove XML/schema facts, but not content occupancy, label-obstacle avoidance, or acceptable aspect ratio.

## Files

- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`
- Modify: `crates/dediren-cli/tests/cli_pipeline.rs`
- Optional docs if behavior is user-visible: `README.md`

---

### Task 1: Crop SVG Viewport To Rendered Content

**Files:**
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- Test: `cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_crops_small_diagram_to_content_bounds -- --exact`
- Test: `cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_expands_viewbox_to_include_edge_labels -- --exact`

- [ ] **Step 1: Add the failing crop test**

Add this test near `svg_renderer_expands_viewbox_to_include_edge_labels` in `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`:

```rust
#[test]
fn svg_renderer_crops_small_diagram_to_content_bounds() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "only-node",
                "source_id": "only-node",
                "projection_id": "only-node",
                "x": 32,
                "y": 40,
                "width": 160,
                "height": 80,
                "label": "Only Node"
            }
        ]),
        serde_json::json!([]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let root = doc.root_element();
    let width = root.attribute("width").unwrap().parse::<f64>().unwrap();
    let height = root.attribute("height").unwrap().parse::<f64>().unwrap();
    let view_box: Vec<f64> = root
        .attribute("viewBox")
        .unwrap()
        .split_whitespace()
        .map(|value| value.parse::<f64>().unwrap())
        .collect();

    assert_eq!(view_box.len(), 4);
    assert!(width < 260.0, "single-node render should not keep the 640px policy width, got {width}");
    assert!(height < 180.0, "single-node render should not keep the 360px policy height, got {height}");
    assert!(view_box[0] <= 16.0, "left margin should be included, got min-x {}", view_box[0]);
    assert!(view_box[1] <= 24.0, "top margin should be included, got min-y {}", view_box[1]);
}
```

- [ ] **Step 2: Run the failing crop test**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_crops_small_diagram_to_content_bounds -- --exact
```

Expected: FAIL because current output keeps at least the policy page size.

- [ ] **Step 3: Make `SvgBounds` content-first**

In `crates/dediren-plugin-svg-render/src/main.rs`, replace `SvgBounds::new(policy: &RenderPolicy)` with:

```rust
fn new_empty() -> Self {
    Self {
        min_x: f64::INFINITY,
        min_y: f64::INFINITY,
        max_x: f64::NEG_INFINITY,
        max_y: f64::NEG_INFINITY,
    }
}

fn is_empty(&self) -> bool {
    !self.min_x.is_finite()
        || !self.min_y.is_finite()
        || !self.max_x.is_finite()
        || !self.max_y.is_finite()
}

fn fallback(policy: &RenderPolicy) -> Self {
    Self {
        min_x: 0.0,
        min_y: 0.0,
        max_x: policy.page.width,
        max_y: policy.page.height,
    }
}
```

Then change `svg_bounds` to start from `SvgBounds::new_empty()` and finish with:

```rust
if bounds.is_empty() {
    SvgBounds::fallback(policy).padded(policy)
} else {
    bounds.padded(policy)
}
```

- [ ] **Step 4: Stop forcing root dimensions to policy page size**

In `render_svg`, replace:

```rust
let rendered_width = policy.page.width.max(bounds.width());
let rendered_height = policy.page.height.max(bounds.height());
```

with:

```rust
let rendered_width = bounds.width();
let rendered_height = bounds.height();
```

Keep the empty-diagram fallback in `svg_bounds`, so an empty render still has a usable policy-sized SVG.

- [ ] **Step 5: Verify crop and label-expansion tests**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_crops_small_diagram_to_content_bounds -- --exact
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_expands_viewbox_to_include_edge_labels -- --exact
```

Expected: both pass.

- [ ] **Step 6: Commit Task 1**

Run:

```bash
git status --short --branch
git diff -- crates/dediren-plugin-svg-render/src/main.rs crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
git add crates/dediren-plugin-svg-render/src/main.rs crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
git commit -m "Crop SVG viewport to rendered content"
```

---

### Task 2: Make Edge Labels Avoid Rendered Routes

**Files:**
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- Test: `cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_moves_edge_label_away_from_route_segments -- --exact`

- [ ] **Step 1: Add route-label collision helpers to tests**

Add these helpers near the existing XML helpers in `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`:

```rust
fn text_box_from_svg(label: roxmltree::Node<'_, '_>, font_size: f64) -> (f64, f64, f64, f64) {
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();
    let text = label.text().unwrap_or("");
    let half_width = text.chars().count() as f64 * font_size * 0.62 / 2.0;
    (x - half_width, y - font_size, x + half_width, y + font_size * 0.4)
}

fn box_contains_point(bounds: (f64, f64, f64, f64), x: f64, y: f64) -> bool {
    x >= bounds.0 && x <= bounds.2 && y >= bounds.1 && y <= bounds.3
}
```

- [ ] **Step 2: Add the failing route-avoidance test**

Add this test in `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`:

```rust
#[test]
fn svg_renderer_moves_edge_label_away_from_route_segments() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "vertical-edge",
                "source": "orders-api",
                "target": "worker",
                "source_id": "vertical-edge",
                "projection_id": "vertical-edge",
                "points": [
                    { "x": 220, "y": 80 },
                    { "x": 220, "y": 260 }
                ],
                "label": "publishes fulfillment"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let edge = semantic_group(&doc, "data-dediren-edge-id", "vertical-edge");
    let label = child_element(edge, "text");
    let label_box = text_box_from_svg(label, 14.0);

    assert!(
        !box_contains_point(label_box, 220.0, label.attribute("y").unwrap().parse::<f64>().unwrap()),
        "label box should not sit on top of its vertical route segment"
    );
}
```

- [ ] **Step 3: Run the failing route-avoidance test**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_moves_edge_label_away_from_route_segments -- --exact
```

Expected: FAIL because current label placement only avoids node boxes and previously placed labels.

- [ ] **Step 4: Add route-segment obstacle boxes**

In `crates/dediren-plugin-svg-render/src/main.rs`, add:

```rust
fn route_obstacle_boxes(result: &LayoutResult, font_size: f64) -> Vec<LabelBox> {
    let padding = font_size * 0.5;
    result
        .edges
        .iter()
        .flat_map(|edge| edge.points.windows(2))
        .map(|segment| {
            let start = &segment[0];
            let end = &segment[1];
            LabelBox {
                min_x: start.x.min(end.x) - padding,
                min_y: start.y.min(end.y) - padding,
                max_x: start.x.max(end.x) + padding,
                max_y: start.y.max(end.y) + padding,
            }
        })
        .collect()
}
```

Then initialize label obstacles in `render_svg` with:

```rust
let mut occupied_label_boxes = node_obstacle_boxes(result);
occupied_label_boxes.extend(route_obstacle_boxes(result, style.font_size));
```

- [ ] **Step 5: Let labels move beside vertical routes**

Change `label_offset` and `edge_label_position` so vertical-route labels can move sideways when vertical movement cannot avoid route obstacles. Add this helper:

```rust
fn label_candidate(attempt: usize, x: f64, base_y: f64, step: f64) -> (f64, f64) {
    match attempt {
        0 => (x, base_y - step),
        1 => (x, base_y + step),
        2 => (x - step * 2.0, base_y),
        3 => (x + step * 2.0, base_y),
        _ => {
            let distance = (attempt - 1) as f64;
            (x, base_y + distance * step)
        }
    }
}
```

Then in `edge_label_position`, replace the `label_y` calculation with:

```rust
let (label_x, label_y) = label_candidate(attempt, x, base_y, step);
let label_box = text_box(label_x, label_y, text, font_size);
```

Return `(label_x, label_y, label_box)` from `edge_label_position`, and update `edge_label` to use the returned `label_x` in the `<text x="...">`.

- [ ] **Step 6: Verify route-label behavior**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_moves_edge_label_away_from_route_segments -- --exact
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
```

Expected: all SVG render plugin tests pass and `.test-output/renders/svg-render-plugin/svg_renderer_moves_edge_label_away_from_route_segments.svg` shows the label beside the route instead of centered over it.

- [ ] **Step 7: Commit Task 2**

Run:

```bash
git status --short --branch
git diff -- crates/dediren-plugin-svg-render/src/main.rs crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
git add crates/dediren-plugin-svg-render/src/main.rs crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
git commit -m "Avoid route collisions for SVG edge labels"
```

---

### Task 3: Reduce Real ELK Grouped Layout Stretch

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`
- Test: `crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh`

- [ ] **Step 1: Add a real grouped-layout aspect test**

Add this test to `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`:

```java
@Test
void groupedPipelineLayoutKeepsReadableAspectRatio() {
    JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
        "layout-request.schema.v1",
        "main",
        List.of(
            new JsonContracts.LayoutNode("client", "Client", "client", 160.0, 80.0),
            new JsonContracts.LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
            new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
            new JsonContracts.LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
            new JsonContracts.LayoutNode("payments", "Payments Provider", "payments", 160.0, 80.0),
            new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
        List.of(
            new JsonContracts.LayoutEdge("client-submits-order", "client", "web-app", "submits order", "client-submits-order"),
            new JsonContracts.LayoutEdge("web-app-calls-api", "web-app", "orders-api", "calls API", "web-app-calls-api"),
            new JsonContracts.LayoutEdge("api-authorizes-payment", "orders-api", "payments", "authorizes payment", "api-authorizes-payment"),
            new JsonContracts.LayoutEdge("api-writes-database", "orders-api", "database", "writes orders", "api-writes-database"),
            new JsonContracts.LayoutEdge("api-publishes-job", "orders-api", "worker", "publishes fulfillment", "api-publishes-job"),
            new JsonContracts.LayoutEdge("worker-reads-database", "worker", "database", "loads order", "worker-reads-database")),
        List.of(
            new JsonContracts.LayoutGroup(
                "application-services",
                "Application Services",
                List.of("web-app", "orders-api", "worker"),
                new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("application-services"))),
            new JsonContracts.LayoutGroup(
                "external-dependencies",
                "External Dependencies",
                List.of("payments", "database"),
                new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("external-dependencies")))),
        List.of(),
        List.of());

    JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
    double minX = result.nodes().stream().mapToDouble(JsonContracts.LaidOutNode::x).min().orElse(0.0);
    double maxX = result.nodes().stream().mapToDouble(node -> node.x() + node.width()).max().orElse(0.0);
    double minY = result.nodes().stream().mapToDouble(JsonContracts.LaidOutNode::y).min().orElse(0.0);
    double maxY = result.nodes().stream().mapToDouble(node -> node.y() + node.height()).max().orElse(0.0);
    double aspect = (maxX - minX) / (maxY - minY);

    assertTrue(aspect < 3.2, "grouped rich pipeline should not render as a long horizontal strip, aspect=" + aspect);
}
```

- [ ] **Step 2: Run the failing Java test**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: FAIL on `groupedPipelineLayoutKeepsReadableAspectRatio` with the current long-strip layout.

- [ ] **Step 3: Allow graph layout direction to vary**

In `ElkLayoutEngine.java`, change:

```java
private static GraphLayout graphLayout(
    List<JsonContracts.LayoutNode> requestNodes,
    List<JsonContracts.LayoutEdge> requestEdges) {
```

to:

```java
private static GraphLayout graphLayout(
    List<JsonContracts.LayoutNode> requestNodes,
    List<JsonContracts.LayoutEdge> requestEdges,
    Direction direction) {
```

Change `configureRoot(root);` inside `graphLayout` to:

```java
configureRoot(root, direction);
```

Change `configureRoot` to:

```java
private static void configureRoot(ElkNode root, Direction direction) {
    root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
    root.setProperty(CoreOptions.DIRECTION, direction);
    root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
    root.setProperty(CoreOptions.SPACING_NODE_NODE, NODE_SPACING);
    root.setProperty(CoreOptions.SPACING_EDGE_NODE, EDGE_NODE_SPACING);
    root.setProperty(CoreOptions.SPACING_EDGE_EDGE, EDGE_EDGE_SPACING);
    root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, NODE_SPACING);
    root.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, EDGE_NODE_SPACING);
    root.setProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, EDGE_EDGE_SPACING);
}
```

Update flat layout to call `configureRoot(root, Direction.RIGHT);`.

- [ ] **Step 4: Use downward internal group layout for branching groups**

Add this helper:

```java
private static Direction internalDirection(
    List<JsonContracts.LayoutNode> nodes,
    List<JsonContracts.LayoutEdge> edges) {
    if (nodes.size() < 3) {
        return Direction.RIGHT;
    }
    Map<String, Integer> outgoing = new HashMap<>();
    for (JsonContracts.LayoutEdge edge : edges) {
        outgoing.merge(edge.source(), 1, Integer::sum);
    }
    boolean hasBranch = outgoing.values().stream().anyMatch(count -> count > 1);
    return hasBranch ? Direction.DOWN : Direction.RIGHT;
}
```

Then in `internalLayout`, replace:

```java
GraphLayout layout = graphLayout(nodes, edges);
```

with:

```java
GraphLayout layout = graphLayout(nodes, edges, internalDirection(nodes, edges));
```

Keep macro layout as `Direction.RIGHT` so user-facing flow still moves left to right across actors and dependency groups.

- [ ] **Step 5: Update remaining `graphLayout` calls**

Replace:

```java
GraphLayout macroLayout = graphLayout(macroNodes, macroEdges);
```

with:

```java
GraphLayout macroLayout = graphLayout(macroNodes, macroEdges, Direction.RIGHT);
```

Replace any flat helper calls with `Direction.RIGHT`.

- [ ] **Step 6: Verify Java layout tests**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: Java helper builds and all Java tests pass.

- [ ] **Step 7: Commit Task 3**

Run:

```bash
git status --short --branch
git diff -- crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java
git add crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java
git commit -m "Reduce grouped ELK layout stretch"
```

---

### Task 4: Add End-To-End Render Quality Assertions

**Files:**
- Modify: `crates/dediren-cli/tests/cli_pipeline.rs`
- Test: `cargo test -p dediren --test cli_pipeline full_pipeline_produces_svg_and_oef -- --exact`
- Test: `cargo test -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact`

- [ ] **Step 1: Add SVG viewBox helper functions**

Add these helpers to `crates/dediren-cli/tests/cli_pipeline.rs` near `write_render_artifact`:

```rust
fn parse_view_box(content: &str) -> [f64; 4] {
    let doc = roxmltree::Document::parse(content).unwrap();
    let values: Vec<f64> = doc
        .root_element()
        .attribute("viewBox")
        .unwrap()
        .split_whitespace()
        .map(|value| value.parse::<f64>().unwrap())
        .collect();
    assert_eq!(values.len(), 4);
    [values[0], values[1], values[2], values[3]]
}

fn assert_reasonable_svg_aspect(content: &str, max_aspect: f64) {
    let view_box = parse_view_box(content);
    let aspect = view_box[2] / view_box[3];
    assert!(
        aspect <= max_aspect,
        "rendered SVG aspect ratio should be <= {max_aspect}, got {aspect} from viewBox {:?}",
        view_box
    );
}
```

If `roxmltree` is not already in `crates/dediren-cli/Cargo.toml` dev-dependencies, add it there with the same version already used by `dediren-plugin-svg-render`.

- [ ] **Step 2: Assert fixture-backed pipeline aspect**

In `full_pipeline_produces_svg_and_oef`, after extracting `svg_text`, add:

```rust
assert_reasonable_svg_aspect(svg_text, 2.8);
```

- [ ] **Step 3: Assert real ELK pipeline aspect**

In `real_elk_pipeline_renders_rich_source`, after extracting `content`, add:

```rust
assert_reasonable_svg_aspect(content, 3.2);
```

- [ ] **Step 4: Run fixture-backed CLI pipeline test**

Run:

```bash
cargo test -p dediren --test cli_pipeline full_pipeline_produces_svg_and_oef -- --exact
```

Expected: PASS and `.test-output/renders/cli-pipeline/full_pipeline_produces_svg_and_oef.svg` remains readable.

- [ ] **Step 5: Run ignored real ELK pipeline test**

Run:

```bash
cargo test -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact
```

Expected: PASS after the Java helper has been built.

- [ ] **Step 6: Commit Task 4**

Run:

```bash
git status --short --branch
git diff -- crates/dediren-cli/tests/cli_pipeline.rs crates/dediren-cli/Cargo.toml
git add crates/dediren-cli/tests/cli_pipeline.rs crates/dediren-cli/Cargo.toml
git commit -m "Assert generated render quality"
```

---

### Task 5: Regenerate And Review Render Artifacts

**Files:**
- Generated only: `.test-output/renders/**/*.svg`
- Do not stage generated render artifacts unless explicitly requested.
- Test: `cargo test -p dediren-plugin-svg-render --test svg_render_plugin`
- Test: `cargo test -p dediren --test cli_render`
- Test: `cargo test -p dediren --test cli_pipeline full_pipeline_produces_svg_and_oef -- --exact`
- Test: `cargo test -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact`

- [ ] **Step 1: Regenerate plugin render artifacts**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
```

Expected: all plugin SVG tests pass and `.test-output/renders/svg-render-plugin/*.svg` are refreshed.

- [ ] **Step 2: Regenerate CLI render artifacts**

Run:

```bash
cargo test -p dediren --test cli_render
```

Expected: all CLI render tests pass and `.test-output/renders/cli-render/*.svg` are refreshed.

- [ ] **Step 3: Regenerate fixture-backed pipeline artifact**

Run:

```bash
cargo test -p dediren --test cli_pipeline full_pipeline_produces_svg_and_oef -- --exact
```

Expected: test passes and `.test-output/renders/cli-pipeline/full_pipeline_produces_svg_and_oef.svg` is refreshed.

- [ ] **Step 4: Regenerate real ELK pipeline artifact**

Run:

```bash
cargo test -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact
```

Expected: test passes and `.test-output/renders/cli-pipeline/real_elk_pipeline_renders_rich_source.svg` is refreshed.

- [ ] **Step 5: Inspect the refreshed SVGs visually**

Run:

```bash
magick montage .test-output/renders/cli-pipeline/*.svg .test-output/renders/cli-render/*.svg .test-output/renders/svg-render-plugin/*.svg -background white -geometry 420x260+20+32 -tile 3x /tmp/dediren-renders-contact.png
```

Expected visual result: no tiny diagrams floating in large empty pages, no unreadable labels centered over the same route they describe, and the real ELK rich pipeline no longer appears as a single long top strip.

- [ ] **Step 6: Commit generated-artifact policy**

Run:

```bash
git status --short --branch
```

Expected: `.test-output/renders/**/*.svg` may appear as ignored/untracked outputs. Do not stage them. Report their paths in the handoff.

---

### Task 6: Verification And Audit Gates

**Files:**
- No new source files expected.
- Test: `cargo fmt --all -- --check`
- Test: `cargo test -p dediren-plugin-svg-render --test svg_render_plugin`
- Test: `cargo test -p dediren --test cli_render`
- Test: `cargo test -p dediren --test cli_pipeline full_pipeline_produces_svg_and_oef -- --exact`
- Test: `crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh`
- Test: `cargo test -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact`
- Test: `cargo test --workspace --locked`
- Audit: `souroldgeezer-audit:test-quality-audit` deep for render/plugin/CLI tests and artifacts
- Audit: `souroldgeezer-audit:devsecops-audit` quick for SVG renderer, Java helper, process boundary, and artifact handling

- [ ] **Step 1: Run formatting**

Run:

```bash
cargo fmt --all -- --check
```

Expected: PASS.

- [ ] **Step 2: Run focused Rust render tests**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
cargo test -p dediren --test cli_render
cargo test -p dediren --test cli_pipeline full_pipeline_produces_svg_and_oef -- --exact
```

Expected: all pass.

- [ ] **Step 3: Run Java helper and real ELK lane**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact
```

Expected: Java helper builds and the ignored real-helper pipeline test passes.

- [ ] **Step 4: Run workspace tests**

Run:

```bash
cargo test --workspace --locked
```

Expected: PASS.

- [ ] **Step 5: Run required audits**

Run `souroldgeezer-audit:test-quality-audit` in deep mode against the changed render/plugin/CLI test lane. Fix block findings. Fix warn/info findings or record the accepted risk in the handoff.

Run `souroldgeezer-audit:devsecops-audit` in quick mode against the SVG renderer, Java helper changes, plugin process boundary, and generated artifact handling. Fix block findings. Fix warn/info findings or record the accepted risk in the handoff.

- [ ] **Step 6: Final status**

Run:

```bash
git status --short --branch
```

Expected: only intentional tracked changes are present. Generated `.test-output/renders/**/*.svg` artifacts are reported but not staged unless the user explicitly asks to track examples.
