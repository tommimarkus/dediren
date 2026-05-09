# Real ELK Render Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the generated `generic-graph -> elk-layout -> svg-render` path produce readable SVGs from the rich pipeline source, without relying on hand-authored layout-result coordinates.

**Architecture:** Keep source projection, layout, and rendering responsibilities separate. `generic-graph` should preserve semantic view grouping as `LayoutGroup` intent, the Java ELK helper should generate geometry and routes from the `LayoutRequest`, and `svg-render` should render the generated `LayoutResult` with a viewBox that includes nodes, groups, routes, and labels. Do not add authored absolute geometry to source fixtures.

**Tech Stack:** Rust workspace, `dediren-contracts`, first-party process plugins, Java ELK helper, JSON schemas, `assert_cmd`, `roxmltree`, Gradle/Sdkman helper build.

---

## Findings To Fix

1. `crates/dediren-plugin-generic-graph/src/main.rs` currently emits `groups: Vec::new()`, so semantic grouping from the rich source never reaches real ELK.
2. `fixtures/source/valid-pipeline-rich.json` has no plugin-owned group intent, so the real projection cannot reproduce the grouped fixture view.
3. `crates/dediren-plugin-svg-render/src/main.rs` uses a fixed `viewBox="0 0 page.width page.height"`, so real ELK output near `x=12` / `y=12` clips edge labels that extend above or left of geometry.
4. Real ELK output is currently flat. The Java helper can wrap groups after layout, but it does not use group membership to influence ELK placement. This should be tested as current behavior first, then improved only inside the ELK helper if needed.

## Files

- Modify: `fixtures/source/valid-pipeline-rich.json`
- Modify: `crates/dediren-contracts/src/lib.rs`
- No schema change expected for generic-graph plugin data today: `schemas/model.schema.json` allows plugin-owned data under `plugins` with `additionalProperties: true`. Keep schema tests in verification so this remains explicit.
- Modify: `crates/dediren-plugin-generic-graph/src/main.rs`
- Modify: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java` only if Task 3 proves group-aware ELK placement needs implementation
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- Modify: `crates/dediren-cli/tests/cli_pipeline.rs`
- Optional docs: `README.md`

---

### Task 1: Preserve Generic Graph View Groups In Layout Requests

**Files:**
- Modify: `crates/dediren-contracts/src/lib.rs`
- Modify: `fixtures/source/valid-pipeline-rich.json`
- Modify: `crates/dediren-plugin-generic-graph/src/main.rs`
- Modify: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`
- Test: `cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin`
- Test: `cargo test -p dediren-contracts --test schema_contracts`
- Test: `cargo test -p dediren-contracts --test contract_roundtrip`

- [ ] **Step 1: Confirm generic graph view shape is plugin-owned**

Run:

```bash
rg -n "GenericGraphView|views|relationships|model.schema|additionalProperties" crates/dediren-contracts schemas/model.schema.json crates/dediren-plugin-generic-graph
```

Expected: find `GenericGraphView` in `crates/dediren-contracts/src/lib.rs` and confirm `schemas/model.schema.json` leaves `plugins` as plugin-owned data. Do not add authored geometry or renderer styling to the source schema.

- [ ] **Step 2: Add the failing projection test**

Add this test to `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`:

```rust
#[test]
fn generic_graph_projects_rich_view_groups() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/source/valid-pipeline-rich.json"))
            .unwrap();
    let mut cmd = Command::cargo_bin("dediren-plugin-generic-graph").unwrap();
    let output = cmd
        .args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let envelope: serde_json::Value = serde_json::from_slice(&output).unwrap();
    let groups = envelope["data"]["groups"].as_array().unwrap();

    assert_eq!(groups.len(), 2);
    assert_eq!(groups[0]["id"], "application-services");
    assert_eq!(groups[0]["label"], "Application Services");
    assert_eq!(
        groups[0]["members"],
        serde_json::json!(["web-app", "orders-api", "worker"])
    );
    assert_eq!(
        groups[0]["provenance"],
        serde_json::json!({ "semantic_backed": { "source_id": "application-services" } })
    );

    assert_eq!(groups[1]["id"], "external-dependencies");
    assert_eq!(groups[1]["label"], "External Dependencies");
    assert_eq!(
        groups[1]["members"],
        serde_json::json!(["payments", "database"])
    );
}
```

- [ ] **Step 3: Run the failing test**

Run:

```bash
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin generic_graph_projects_rich_view_groups -- --exact
```

Expected: FAIL because `groups` is currently empty.

- [ ] **Step 4: Extend the generic graph view contract**

In `crates/dediren-contracts/src/lib.rs`, add this type near `GenericGraphView`:

```rust
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GenericGraphViewGroup {
    pub id: String,
    pub label: String,
    pub members: Vec<String>,
}
```

Then add a defaulted field to `GenericGraphView`:

```rust
#[serde(default)]
pub groups: Vec<GenericGraphViewGroup>,
```
No JSON schema update is expected in this step because `plugins.generic-graph` is plugin-owned source data. The stricter contract lives in `GenericGraphPluginData` deserialization.

- [ ] **Step 5: Add group intent to the rich source fixture**

In `fixtures/source/valid-pipeline-rich.json`, add `groups` to the `plugins.generic-graph.views[0]` object:

```json
"groups": [
  {
    "id": "application-services",
    "label": "Application Services",
    "members": ["web-app", "orders-api", "worker"]
  },
  {
    "id": "external-dependencies",
    "label": "External Dependencies",
    "members": ["payments", "database"]
  }
]
```

- [ ] **Step 6: Project groups in the generic graph plugin**

Update imports in `crates/dediren-plugin-generic-graph/src/main.rs` to include:

```rust
GroupProvenance, LayoutGroup,
```

After `edges` are collected, add:

```rust
let groups = selected_view
    .groups
    .iter()
    .map(|group| {
        for member in &group.members {
            if !selected_view.nodes.iter().any(|node_id| node_id == member) {
                bail!("group {} references node outside view: {member}", group.id);
            }
        }
        Ok(LayoutGroup {
            id: group.id.clone(),
            label: group.label.clone(),
            members: group.members.clone(),
            provenance: GroupProvenance::SemanticBacked {
                source_id: group.id.clone(),
            },
        })
    })
    .collect::<anyhow::Result<Vec<_>>>()?;
```

Then replace:

```rust
groups: Vec::new(),
```

with:

```rust
groups,
```

- [ ] **Step 7: Verify projection and contract tests**

Run:

```bash
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin
cargo test -p dediren-contracts --test schema_contracts
cargo test -p dediren-contracts --test contract_roundtrip
```

Expected: all pass.

- [ ] **Step 8: Commit Task 1**

Run:

```bash
git status --short --branch
git diff -- fixtures/source/valid-pipeline-rich.json crates/dediren-contracts/src/lib.rs crates/dediren-plugin-generic-graph/src/main.rs crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs
git add fixtures/source/valid-pipeline-rich.json crates/dediren-contracts/src/lib.rs crates/dediren-plugin-generic-graph/src/main.rs crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs
git commit -m "Preserve generic graph view groups"
```

---

### Task 2: Make SVG Render Bounds Include Labels And Generated Geometry

**Files:**
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- Test: `cargo test -p dediren-plugin-svg-render --test svg_render_plugin`

- [ ] **Step 1: Add a failing render-bounds test**

Add this test to `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`:

```rust
#[test]
fn svg_renderer_expands_viewbox_to_include_edge_labels() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "left-node",
                "source_id": "left-node",
                "projection_id": "left-node",
                "x": 12,
                "y": 32,
                "width": 160,
                "height": 80,
                "label": "Left"
            },
            {
                "id": "right-node",
                "source_id": "right-node",
                "projection_id": "right-node",
                "x": 212,
                "y": 32,
                "width": 160,
                "height": 80,
                "label": "Right"
            }
        ]),
        serde_json::json!([
            {
                "id": "left-to-right",
                "source": "left-node",
                "target": "right-node",
                "source_id": "left-to-right",
                "projection_id": "left-to-right",
                "points": [
                    { "x": 172, "y": 72 },
                    { "x": 212, "y": 72 }
                ],
                "label": "very long clipped edge label"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let root = doc.root_element();

    assert_eq!(root.attribute("width"), Some("640"));
    assert_eq!(root.attribute("height"), Some("360"));
    let view_box = root.attribute("viewBox").unwrap();
    assert!(
        view_box.starts_with("-"),
        "expected negative min-x in viewBox, got {view_box}"
    );
    assert!(
        view_box.contains(" -"),
        "expected negative min-y in viewBox, got {view_box}"
    );
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_expands_viewbox_to_include_edge_labels -- --exact
```

Expected: FAIL because the current viewBox is `0 0 640 360`.

- [ ] **Step 3: Add render bounds helpers**

In `crates/dediren-plugin-svg-render/src/main.rs`, add this struct and helper functions before `render_svg`:

```rust
#[derive(Debug, Clone)]
struct SvgBounds {
    min_x: f64,
    min_y: f64,
    max_x: f64,
    max_y: f64,
}

impl SvgBounds {
    fn new(policy: &RenderPolicy) -> Self {
        Self {
            min_x: 0.0,
            min_y: 0.0,
            max_x: policy.page.width,
            max_y: policy.page.height,
        }
    }

    fn include_rect(&mut self, x: f64, y: f64, width: f64, height: f64) {
        self.min_x = self.min_x.min(x);
        self.min_y = self.min_y.min(y);
        self.max_x = self.max_x.max(x + width);
        self.max_y = self.max_y.max(y + height);
    }

    fn include_point(&mut self, x: f64, y: f64) {
        self.min_x = self.min_x.min(x);
        self.min_y = self.min_y.min(y);
        self.max_x = self.max_x.max(x);
        self.max_y = self.max_y.max(y);
    }

    fn include_label(&mut self, x: f64, y: f64, text: &str, font_size: f64) {
        let half_width = estimate_text_width(text, font_size) / 2.0;
        self.include_rect(x - half_width, y - font_size, half_width * 2.0, font_size * 1.4);
    }

    fn padded(&self, policy: &RenderPolicy) -> Self {
        Self {
            min_x: self.min_x - policy.margin.left,
            min_y: self.min_y - policy.margin.top,
            max_x: self.max_x + policy.margin.right,
            max_y: self.max_y + policy.margin.bottom,
        }
    }

    fn width(&self) -> f64 {
        self.max_x - self.min_x
    }

    fn height(&self) -> f64 {
        self.max_y - self.min_y
    }
}

fn estimate_text_width(text: &str, font_size: f64) -> f64 {
    text.chars().count() as f64 * font_size * 0.62
}

fn svg_bounds(result: &LayoutResult, policy: &RenderPolicy, style: &ResolvedStyle) -> SvgBounds {
    let mut bounds = SvgBounds::new(policy);

    for group in &result.groups {
        let group_style = group_style(policy, &group.id, style);
        bounds.include_rect(group.x, group.y, group.width, group.height);
        bounds.include_rect(
            group.x + 8.0,
            group.y + 4.0,
            estimate_text_width(&group.label, group_style.label_size),
            group_style.label_size * 1.4,
        );
    }

    for edge in &result.edges {
        for point in &edge.points {
            bounds.include_point(point.x, point.y);
        }
        if let Some(point) = edge_label_point(&edge.points) {
            bounds.include_label(point.x, point.y - 8.0, &edge.label, style.font_size);
        }
    }

    for node in &result.nodes {
        bounds.include_rect(node.x, node.y, node.width, node.height);
        bounds.include_label(
            node.x + node.width / 2.0,
            node.y + node.height / 2.0,
            &node.label,
            style.font_size,
        );
    }

    bounds.padded(policy)
}
```

- [ ] **Step 4: Use computed bounds in the SVG root**

Replace the opening `<svg>` format in `render_svg` with:

```rust
let bounds = svg_bounds(result, policy, &style);
svg.push_str(&format!(
    r#"<svg xmlns="http://www.w3.org/2000/svg" width="{:.0}" height="{:.0}" viewBox="{:.1} {:.1} {:.1} {:.1}">"#,
    policy.page.width,
    policy.page.height,
    bounds.min_x,
    bounds.min_y,
    bounds.width(),
    bounds.height()
));
```

Keep `width` and `height` as policy page size. Only the coordinate system expands.

- [ ] **Step 5: Verify SVG tests**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
```

Expected: all pass.

- [ ] **Step 6: Commit Task 2**

Run:

```bash
git status --short --branch
git diff -- crates/dediren-plugin-svg-render/src/main.rs crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
git add crates/dediren-plugin-svg-render/src/main.rs crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
git commit -m "Expand SVG viewBox for generated labels"
```

---

### Task 3: Decide Whether ELK Must Use Groups During Layout

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java` only if the failing test proves a necessary improvement
- Test: `crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh`
- Test: `cargo test -p dediren --test cli_layout layout_invokes_real_java_elk_helper -- --ignored --exact`

- [ ] **Step 1: Add a characterization test for grouped output**

Add this test to `ElkLayoutEngineTest.java`:

```java
@Test
void groupedMembersProduceGroupBoundsAroundGeneratedNodeGeometry() {
    JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
        "layout-request.schema.v1",
        "main",
        List.of(
            new JsonContracts.LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
            new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
            new JsonContracts.LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
            new JsonContracts.LayoutNode("payments", "Payments Provider", "payments", 160.0, 80.0),
            new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
        List.of(
            new JsonContracts.LayoutEdge(
                "web-app-calls-api", "web-app", "orders-api", "calls API", "web-app-calls-api"),
            new JsonContracts.LayoutEdge(
                "api-authorizes-payment", "orders-api", "payments", "authorizes payment", "api-authorizes-payment"),
            new JsonContracts.LayoutEdge(
                "api-writes-database", "orders-api", "database", "writes orders", "api-writes-database"),
            new JsonContracts.LayoutEdge(
                "api-publishes-job", "orders-api", "worker", "publishes fulfillment", "api-publishes-job"),
            new JsonContracts.LayoutEdge(
                "worker-reads-database", "worker", "database", "loads order", "worker-reads-database")),
        List.of(
            new JsonContracts.LayoutGroup(
                "application-services",
                "Application Services",
                List.of("web-app", "orders-api", "worker"),
                new JsonContracts.GroupProvenance(
                    new JsonContracts.SemanticBacked("application-services"))),
            new JsonContracts.LayoutGroup(
                "external-dependencies",
                "External Dependencies",
                List.of("payments", "database"),
                new JsonContracts.GroupProvenance(
                    new JsonContracts.SemanticBacked("external-dependencies")))),
        List.of(),
        List.of());

    JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

    assertEquals(2, result.groups().size());
    JsonContracts.LaidOutGroup application = result.groups().stream()
        .filter(group -> group.id().equals("application-services"))
        .findFirst()
        .orElseThrow();
    JsonContracts.LaidOutGroup external = result.groups().stream()
        .filter(group -> group.id().equals("external-dependencies"))
        .findFirst()
        .orElseThrow();

    assertEquals(List.of("web-app", "orders-api", "worker"), application.members());
    assertEquals(List.of("payments", "database"), external.members());
    assertTrue(external.x() > application.x(), "external dependency group should render to the right");
}
```

- [ ] **Step 2: Run Java tests**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: PASS. If it fails because `external.x() > application.x()` is false, continue to Step 3. If it passes, do not change ELK layout internals in this task.

- [ ] **Step 3: Implement group-aware ELK only if the test fails**

If the characterization fails, update `ElkLayoutEngine.layout` so grouped nodes are created under ELK child container nodes for each `LayoutGroup`, and ungrouped nodes remain under root. Preserve these constraints:

```java
root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT);
root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
```

For each request group:

```java
ElkNode groupNode = ElkGraphUtil.createNode(root);
groupNode.setIdentifier(group.id());
groupNode.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
groupNode.setProperty(CoreOptions.DIRECTION, Direction.RIGHT);
groupNode.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
```

Then create member `ElkNode`s under that group node instead of root. Keep the public `LayoutResult` node coordinates absolute; if ELK child node coordinates are relative to a parent, add parent offsets before serializing `LaidOutNode`.

- [ ] **Step 4: Verify real helper through CLI**

Run:

```bash
cargo test -p dediren --test cli_layout layout_invokes_real_java_elk_helper -- --ignored --exact
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

If only the test was added:

```bash
git status --short --branch
git diff -- crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java
git add crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java
git commit -m "Characterize grouped ELK layout output"
```

If implementation changed too:

```bash
git status --short --branch
git diff -- crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java
git add crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java
git commit -m "Use groups in ELK layout generation"
```

---

### Task 4: Add A Real Pipeline Regression Test

**Files:**
- Modify: `crates/dediren-cli/tests/cli_pipeline.rs`
- Optional modify: `README.md`
- Test: `cargo test -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact`

- [ ] **Step 1: Add an ignored real-helper pipeline test**

Add this test to `crates/dediren-cli/tests/cli_pipeline.rs`:

```rust
#[test]
#[ignore = "requires built Java ELK helper"]
fn real_elk_pipeline_renders_rich_source() {
    let temp = tempfile::tempdir().unwrap();
    let request_path = temp.path().join("rich-layout-request.json");
    let result_path = temp.path().join("rich-layout-result.json");
    let svg_path = temp.path().join("rich.svg");

    let generic_graph = workspace_binary("dediren-plugin-generic-graph");
    let elk_layout = workspace_binary("dediren-plugin-elk-layout");
    let svg_render = workspace_binary("dediren-plugin-svg-render");
    let elk_helper = workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh");

    let project_output = Command::cargo_bin("dediren")
        .unwrap()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", generic_graph)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args([
            "project",
            "--target",
            "layout-request",
            "--plugin",
            "generic-graph",
            "--view",
            "main",
            "--input",
            workspace_file("fixtures/source/valid-pipeline-rich.json")
                .to_str()
                .unwrap(),
        ])
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let project_envelope: serde_json::Value = serde_json::from_slice(&project_output).unwrap();
    assert_eq!(project_envelope["data"]["groups"].as_array().unwrap().len(), 2);
    std::fs::write(
        &request_path,
        serde_json::to_vec(&project_envelope["data"]).unwrap(),
    )
    .unwrap();

    let layout_output = Command::cargo_bin("dediren")
        .unwrap()
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", elk_layout)
        .env("DEDIREN_ELK_COMMAND", elk_helper)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args([
            "layout",
            "--plugin",
            "elk-layout",
            "--input",
            request_path.to_str().unwrap(),
        ])
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let layout_envelope: serde_json::Value = serde_json::from_slice(&layout_output).unwrap();
    assert_eq!(layout_envelope["data"]["nodes"].as_array().unwrap().len(), 6);
    assert_eq!(layout_envelope["data"]["edges"].as_array().unwrap().len(), 6);
    assert_eq!(layout_envelope["data"]["groups"].as_array().unwrap().len(), 2);
    std::fs::write(
        &result_path,
        serde_json::to_vec(&layout_envelope["data"]).unwrap(),
    )
    .unwrap();

    let render_output = Command::cargo_bin("dediren")
        .unwrap()
        .env("DEDIREN_PLUGIN_SVG_RENDER", svg_render)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args([
            "render",
            "--plugin",
            "svg-render",
            "--policy",
            workspace_file("fixtures/render-policy/rich-svg.json")
                .to_str()
                .unwrap(),
            "--input",
            result_path.to_str().unwrap(),
        ])
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let render_envelope: serde_json::Value = serde_json::from_slice(&render_output).unwrap();
    let content = render_envelope["data"]["content"].as_str().unwrap();
    assert!(content.contains("data-dediren-group-id=\"application-services\""));
    assert!(content.contains("data-dediren-group-id=\"external-dependencies\""));
    assert!(content.contains("viewBox=\"-"));
    std::fs::write(svg_path, content).unwrap();
}
```

If `workspace_binary` or `workspace_file` already exists in the file with a different signature, adapt the call sites to that local helper instead of duplicating helpers.

- [ ] **Step 2: Run the ignored real pipeline test**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact
```

Expected: PASS.

- [ ] **Step 3: Document the real pipeline command**

If `README.md` does not already show the real helper flow, add a short example using:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
DEDIREN_ELK_COMMAND=crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh dediren layout --plugin elk-layout --input /tmp/dediren-real-layout-request.json
```

Do not imply fixture mode is the real ELK path.

- [ ] **Step 4: Commit Task 4**

Run:

```bash
git status --short --branch
git diff -- crates/dediren-cli/tests/cli_pipeline.rs README.md
git add crates/dediren-cli/tests/cli_pipeline.rs README.md
git commit -m "Cover real ELK rich render pipeline"
```

---

### Task 5: Final Verification And Audit

**Files:**
- No intended code changes.

- [ ] **Step 1: Run narrow checks**

Run:

```bash
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
cargo test -p dediren --test cli_pipeline real_elk_pipeline_renders_rich_source -- --ignored --exact
cargo test -p dediren --test cli_layout layout_invokes_real_java_elk_helper -- --ignored --exact
```

Expected: all pass.

- [ ] **Step 2: Run contract checks**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts
cargo test -p dediren-contracts --test contract_roundtrip
```

Expected: all pass.

- [ ] **Step 3: Run formatting and workspace tests**

Run:

```bash
cargo fmt --all -- --check
cargo test --workspace --locked
```

Expected: all pass.

- [ ] **Step 4: Run the required audit gate for this plan**

Because this plan touches a vertical slice of projection, layout, render, fixtures, and CLI behavior, run:

```bash
$souroldgeezer-audit:test-quality-audit
```

Use Deep mode for Rust tests/fixtures and include the real-helper ignored tests in the evidence. Fix block findings. Fix warn/info findings or explicitly accept them in the handoff.

- [ ] **Step 5: Generate and inspect the real artifact**

Run the real pipeline and write:

```bash
/tmp/dediren-real-elk-rich.svg
```

Then convert for inspection:

```bash
convert /tmp/dediren-real-elk-rich.svg /tmp/dediren-real-elk-rich.png
```

Expected visual result: group rectangles present, no clipped edge labels, nodes and routes are generated by ELK rather than copied from `fixtures/layout-result/pipeline-rich.json`.

- [ ] **Step 6: Final status**

Run:

```bash
git status --short --branch
```

Expected: clean working tree after commits.

---

## Design Notes

- Do not add absolute coordinates to `fixtures/source/valid-pipeline-rich.json`.
- Do not make `svg-render` infer semantic groups. It should only draw `LayoutResult.groups`.
- Do not make `generic-graph` compute geometry. It should emit layout intent.
- Do not make `dediren-core` own generic graph vocabulary or ELK-specific interpretation.
- Treat fixture layout results as regression fixtures, not as proof that the real pipeline is healthy.

## Self-Review

- Spec coverage: the plan covers the real-pipeline findings: dropped groups, fixed SVG bounds, real ELK characterization, and end-to-end ignored regression coverage.
- Placeholder scan: no task uses deferred-work placeholders.
- Type consistency: group intent flows from `GenericGraphViewGroup` to `LayoutGroup` to `LaidOutGroup` to SVG group rendering.
