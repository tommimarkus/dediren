# Group Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make dediren distinguish layout-only groups from semantic-backed groups, and make ArchiMate `Grouping` elements render and export as semantic groups instead of being confused with generic layout containers.

**Architecture:** Keep `dediren-core` type-agnostic and move domain meaning through explicit contracts. `generic-graph` owns source-group projection, `elk-layout` preserves group provenance through generated layout results, `svg-render` uses render metadata for semantic group notation, and `archimate-oef` exports only ArchiMate semantic groups backed by real source `Grouping` nodes.

**Tech Stack:** Rust workspace, Java ELK helper, JSON Schema draft 2020-12, first-party process plugins, `quick-xml`, `assert_cmd`, `roxmltree`, Cargo tests, Gradle helper tests.

---

## Scope

Implement one coherent slice:

- Source view groups gain an explicit role:
  - `semantic-boundary`: a group with architectural meaning. This remains the default to preserve current authored fixture behavior.
  - `layout-only`: a visual/layout aid with no domain semantics.
- Semantic groups may optionally point at a real source node through `semantic_source_id`.
- ArchiMate semantic groups are source groups with `role: "semantic-boundary"` and `semantic_source_id` pointing at a source node whose type is `Grouping`.
- Layout requests and layout results preserve group provenance as an explicit object:
  - `{ "semantic_backed": { "source_id": "..." } }`
  - `{ "visual_only": true }`
- Render metadata gains group selectors so SVG rendering can apply ArchiMate group notation.
- OEF export emits ArchiMate `Grouping` view nodes only for layout groups backed by real source `Grouping` elements.

Do not infer ArchiMate grouping relationships between a grouping element and its members. If a model needs Aggregation, Composition, Association, or another relationship involving `Grouping`, the source model must author that relationship explicitly and pass normal ArchiMate validation.

Do not change ELK layout semantics based on ArchiMate type. ELK still sees group containers and members only; the semantic distinction is provenance and downstream rendering/export behavior.

## Compatibility And Versioning

Current version is `0.5.0`. This slice adds public source fields, layout-result provenance, render metadata shape, SVG notation behavior, and OEF export behavior. Bump the product and first-party plugin version to `0.6.0`.

Keep omitted source group `role` compatible with existing fixtures by treating it as `semantic-boundary`. Require new examples to spell out `role` explicitly so users can see the difference.

Keep old layout results readable by treating a missing group `provenance` as legacy semantic-backed provenance using the group `source_id`. New layout results must always emit explicit group provenance.

## File Map

- Modify: `Cargo.toml`
- Modify: `Cargo.lock`
- Modify: `fixtures/plugins/archimate-oef.manifest.json`
- Modify: `fixtures/plugins/elk-layout.manifest.json`
- Modify: `fixtures/plugins/generic-graph.manifest.json`
- Modify: `fixtures/plugins/svg-render.manifest.json`
- Modify: `README.md`
- Modify: `schemas/layout-request.schema.json`
- Modify: `schemas/layout-result.schema.json`
- Modify: `schemas/render-metadata.schema.json`
- Modify: `schemas/svg-render-policy.schema.json`
- Modify: `fixtures/source/valid-pipeline-rich.json`
- Modify: `fixtures/source/valid-pipeline-archimate.json`
- Modify: `fixtures/layout-result/pipeline-rich.json`
- Modify: `fixtures/render-metadata/archimate-basic.json`
- Modify: `fixtures/render-policy/archimate-svg.json`
- Modify: `crates/dediren-contracts/src/lib.rs`
- Modify: `crates/dediren-contracts/tests/contract_roundtrip.rs`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`
- Modify: `crates/dediren-plugin-generic-graph/src/main.rs`
- Modify: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/JsonContracts.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/archimate_groups.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- Modify: `crates/dediren-plugin-archimate-oef-export/src/main.rs`
- Modify: `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`
- Modify: `crates/dediren-cli/tests/cli_pipeline.rs`
- Modify: `crates/dediren-cli/tests/cli_export.rs`

---

### Task 1: Lock The Contract With Failing Rust Tests

**Files:**
- Modify: `crates/dediren-contracts/tests/contract_roundtrip.rs`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`

- [ ] **Step 1: Add source group role round-trip tests**

Add these tests to `crates/dediren-contracts/tests/contract_roundtrip.rs`:

```rust
// Add these names to the existing dediren_contracts import list:
// GenericGraphPluginData, GenericGraphViewGroupRole.

#[test]
fn generic_graph_group_role_defaults_to_semantic_boundary() {
    let data: GenericGraphPluginData = serde_json::from_str(
        r#"{
          "views": [
            {
              "id": "main",
              "label": "Main",
              "nodes": ["api"],
              "relationships": [],
              "groups": [
                {
                  "id": "application-services",
                  "label": "Application Services",
                  "members": ["api"]
                }
              ]
            }
          ]
        }"#,
    )
    .unwrap();

    assert_eq!(
        GenericGraphViewGroupRole::SemanticBoundary,
        data.views[0].groups[0].role
    );
    assert_eq!(None, data.views[0].groups[0].semantic_source_id);
}

#[test]
fn generic_graph_group_role_round_trips_layout_only() {
    let data: GenericGraphPluginData = serde_json::from_str(
        r#"{
          "views": [
            {
              "id": "main",
              "label": "Main",
              "nodes": ["api"],
              "relationships": [],
              "groups": [
                {
                  "id": "visual-column",
                  "label": "Visual Column",
                  "members": ["api"],
                  "role": "layout-only"
                }
              ]
            }
          ]
        }"#,
    )
    .unwrap();

    assert_eq!(
        GenericGraphViewGroupRole::LayoutOnly,
        data.views[0].groups[0].role
    );
}

#[test]
fn layout_group_provenance_round_trips_visual_only_object_shape() {
    let request: LayoutRequest = serde_json::from_str(
        r#"{
          "layout_request_schema_version": "layout-request.schema.v1",
          "view_id": "main",
          "nodes": [],
          "edges": [],
          "groups": [
            {
              "id": "visual-column",
              "label": "Visual Column",
              "members": [],
              "provenance": { "visual_only": true }
            }
          ],
          "labels": [],
          "constraints": []
        }"#,
    )
    .unwrap();

    assert_eq!(GroupProvenance::visual_only(), request.groups[0].provenance);
}
```

- [ ] **Step 2: Add schema tests for group provenance**

Add these tests to `crates/dediren-contracts/tests/schema_contracts.rs`:

```rust
#[test]
fn layout_request_schema_accepts_visual_only_group_provenance() {
    assert_json_valid(
        "schemas/layout-request.schema.json",
        json!({
            "layout_request_schema_version": "layout-request.schema.v1",
            "view_id": "main",
            "nodes": [],
            "edges": [],
            "groups": [
                {
                    "id": "visual-column",
                    "label": "Visual Column",
                    "members": [],
                    "provenance": { "visual_only": true }
                }
            ],
            "labels": [],
            "constraints": []
        }),
    );
}

#[test]
fn layout_request_schema_rejects_ambiguous_group_provenance() {
    assert_json_invalid(
        "schemas/layout-request.schema.json",
        json!({
            "layout_request_schema_version": "layout-request.schema.v1",
            "view_id": "main",
            "nodes": [],
            "edges": [],
            "groups": [
                {
                    "id": "ambiguous",
                    "label": "Ambiguous",
                    "members": [],
                    "provenance": {
                        "visual_only": true,
                        "semantic_backed": { "source_id": "ambiguous" }
                    }
                }
            ],
            "labels": [],
            "constraints": []
        }),
        "layout request with ambiguous group provenance",
    );
}

#[test]
fn render_metadata_schema_accepts_group_selectors() {
    assert_json_valid(
        "schemas/render-metadata.schema.json",
        json!({
            "render_metadata_schema_version": "render-metadata.schema.v1",
            "semantic_profile": "archimate",
            "nodes": {},
            "edges": {},
            "groups": {
                "customer-domain": {
                    "type": "Grouping",
                    "source_id": "customer-domain"
                }
            }
        }),
    );
}
```

- [ ] **Step 3: Run contract tests and confirm failures**

Run:

```bash
cargo test -p dediren-contracts --test contract_roundtrip generic_graph_group_role_defaults_to_semantic_boundary -- --exact
cargo test -p dediren-contracts --test contract_roundtrip generic_graph_group_role_round_trips_layout_only -- --exact
cargo test -p dediren-contracts --test contract_roundtrip layout_group_provenance_round_trips_visual_only_object_shape -- --exact
cargo test -p dediren-contracts --test schema_contracts layout_request_schema_accepts_visual_only_group_provenance -- --exact
cargo test -p dediren-contracts --test schema_contracts render_metadata_schema_accepts_group_selectors -- --exact
```

Expected: tests fail because the source group role, visual-only provenance object, and render metadata groups are not implemented.

### Task 2: Implement Group Contract Types And Schemas

**Files:**
- Modify: `crates/dediren-contracts/src/lib.rs`
- Modify: `schemas/layout-request.schema.json`
- Modify: `schemas/layout-result.schema.json`
- Modify: `schemas/render-metadata.schema.json`
- Modify: `schemas/svg-render-policy.schema.json`

- [ ] **Step 1: Replace enum-only provenance with object-shaped provenance**

In `crates/dediren-contracts/src/lib.rs`, replace `GroupProvenance` with object-shaped structs:

```rust
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GroupProvenance {
    #[serde(default, skip_serializing_if = "is_false")]
    pub visual_only: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub semantic_backed: Option<SemanticBackedGroupProvenance>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SemanticBackedGroupProvenance {
    pub source_id: String,
}

impl GroupProvenance {
    pub fn visual_only() -> Self {
        Self {
            visual_only: true,
            semantic_backed: None,
        }
    }

    pub fn semantic_backed(source_id: impl Into<String>) -> Self {
        Self {
            visual_only: false,
            semantic_backed: Some(SemanticBackedGroupProvenance {
                source_id: source_id.into(),
            }),
        }
    }
}

fn is_false(value: &bool) -> bool {
    !*value
}
```

Update every current constructor from:

```rust
GroupProvenance::SemanticBacked {
    source_id: group.id.clone(),
}
```

to:

```rust
GroupProvenance::semantic_backed(group.id.clone())
```

- [ ] **Step 2: Add source group role contracts**

Extend `GenericGraphViewGroup` in `crates/dediren-contracts/src/lib.rs`:

```rust
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GenericGraphViewGroup {
    pub id: String,
    pub label: String,
    pub members: Vec<String>,
    #[serde(default)]
    pub role: GenericGraphViewGroupRole,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub semantic_source_id: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum GenericGraphViewGroupRole {
    SemanticBoundary,
    LayoutOnly,
}

impl Default for GenericGraphViewGroupRole {
    fn default() -> Self {
        Self::SemanticBoundary
    }
}
```

- [ ] **Step 3: Add provenance to layout result groups**

Extend `LaidOutGroup` in `crates/dediren-contracts/src/lib.rs`:

```rust
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LaidOutGroup {
    pub id: String,
    pub source_id: String,
    pub projection_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub provenance: Option<GroupProvenance>,
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    pub members: Vec<String>,
    pub label: String,
}
```

New generated layout results must set `provenance: Some(...)` explicitly. Consumers that read old layout-result fixtures must treat `None` as legacy semantic-backed provenance using the group's `source_id`.

- [ ] **Step 4: Add group selectors to render metadata**

Extend `RenderMetadata` in `crates/dediren-contracts/src/lib.rs`:

```rust
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RenderMetadata {
    pub render_metadata_schema_version: String,
    pub semantic_profile: String,
    #[serde(default)]
    pub nodes: BTreeMap<String, RenderMetadataSelector>,
    #[serde(default)]
    pub edges: BTreeMap<String, RenderMetadataSelector>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub groups: BTreeMap<String, RenderMetadataSelector>,
}
```

- [ ] **Step 5: Tighten layout-request schema provenance**

Update `schemas/layout-request.schema.json` group `provenance` to require exactly one known shape:

```json
"provenance": {
  "oneOf": [
    {
      "type": "object",
      "additionalProperties": false,
      "required": ["visual_only"],
      "properties": {
        "visual_only": { "const": true }
      }
    },
    {
      "type": "object",
      "additionalProperties": false,
      "required": ["semantic_backed"],
      "properties": {
        "semantic_backed": {
          "type": "object",
          "additionalProperties": false,
          "required": ["source_id"],
          "properties": {
            "source_id": { "type": "string", "minLength": 1 }
          }
        }
      }
    }
  ]
}
```

- [ ] **Step 6: Extend layout-result and render-metadata schemas**

Add `provenance` to `schemas/layout-result.schema.json` group objects with the same `oneOf` shape as layout request. Make it optional for backward compatibility but include it in all fixtures and generated outputs.

Add optional `groups` to `schemas/render-metadata.schema.json`:

```json
"groups": {
  "type": "object",
  "additionalProperties": { "$ref": "#/$defs/selector" }
}
```

- [ ] **Step 7: Add SVG policy group type overrides**

In `schemas/svg-render-policy.schema.json`, add `group_type_overrides` beside `group_overrides`:

```json
"group_type_overrides": { "$ref": "#/$defs/groupOverrideMap" }
```

Add an optional `decorator` field to `groupStyle` using the same ArchiMate decorator enum already used by node style. This lets `Grouping` use `archimate_grouping` without making layout know ArchiMate.

- [ ] **Step 8: Run contract tests**

Run:

```bash
cargo test -p dediren-contracts --test contract_roundtrip
cargo test -p dediren-contracts --test schema_contracts
```

Expected: contract tests pass after fixtures are updated in later tasks; schema fixture failures at this point identify every fixture that needs explicit group roles/provenance.

### Task 3: Project Source Group Roles In `generic-graph`

**Files:**
- Modify: `crates/dediren-plugin-generic-graph/src/main.rs`
- Modify: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`
- Modify: `fixtures/source/valid-pipeline-rich.json`
- Modify: `fixtures/source/valid-pipeline-archimate.json`

- [ ] **Step 1: Add projection tests for semantic and layout-only groups**

Add this test to `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`:

```rust
#[test]
fn generic_graph_projects_group_roles_into_provenance() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "nodes": [
            { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
            { "id": "domain-group", "type": "Grouping", "label": "Domain Group", "properties": {} }
        ],
        "relationships": [],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["client", "api"],
                        "relationships": [],
                        "groups": [
                            {
                                "id": "domain-boundary",
                                "label": "Domain Boundary",
                                "members": ["client", "api"],
                                "role": "semantic-boundary",
                                "semantic_source_id": "domain-group"
                            },
                            {
                                "id": "visual-column",
                                "label": "Visual Column",
                                "members": ["api"],
                                "role": "layout-only"
                            }
                        ]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    let groups = data["groups"].as_array().unwrap();
    assert_eq!(
        groups[0]["provenance"],
        serde_json::json!({ "semantic_backed": { "source_id": "domain-group" } })
    );
    assert_eq!(
        groups[1]["provenance"],
        serde_json::json!({ "visual_only": true })
    );
}
```

Add this test for invalid semantic references:

```rust
#[test]
fn generic_graph_rejects_group_semantic_source_id_that_is_not_a_source_node() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "nodes": [
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
        ],
        "relationships": [],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["api"],
                        "relationships": [],
                        "groups": [
                            {
                                "id": "bad-group",
                                "label": "Bad Group",
                                "members": ["api"],
                                "role": "semantic-boundary",
                                "semantic_source_id": "missing-grouping-node"
                            }
                        ]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    cmd.args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .stderr(predicates::str::contains(
            "group bad-group semantic_source_id references missing node",
        ));
}
```

- [ ] **Step 2: Implement projection role mapping**

In `crates/dediren-plugin-generic-graph/src/main.rs`, change the group projection body to:

```rust
let source_node_ids: std::collections::BTreeSet<_> =
    source.nodes.iter().map(|node| node.id.as_str()).collect();

let groups = selected_view
    .groups
    .iter()
    .map(|group| {
        for member in &group.members {
            if !selected_view.nodes.iter().any(|node_id| node_id == member) {
                bail!("group {} references node outside view: {member}", group.id);
            }
        }

        let provenance = match group.role {
            GenericGraphViewGroupRole::LayoutOnly => GroupProvenance::visual_only(),
            GenericGraphViewGroupRole::SemanticBoundary => {
                let source_id = group
                    .semantic_source_id
                    .clone()
                    .unwrap_or_else(|| group.id.clone());
                if group.semantic_source_id.is_some()
                    && !source_node_ids.contains(source_id.as_str())
                {
                    bail!(
                        "group {} semantic_source_id references missing node: {}",
                        group.id,
                        source_id
                    );
                }
                GroupProvenance::semantic_backed(source_id)
            }
        };

        Ok(LayoutGroup {
            id: group.id.clone(),
            label: group.label.clone(),
            members: group.members.clone(),
            provenance,
        })
    })
    .collect::<anyhow::Result<Vec<_>>>()?;
```

Add imports for `GenericGraphViewGroupRole` and `GroupProvenance`.

- [ ] **Step 3: Project render metadata groups**

In `project_render_metadata`, build `groups` from source groups whose `semantic_source_id` references a source node:

```rust
let mut groups = BTreeMap::new();
for group in &selected_view.groups {
    let Some(source_id) = group.semantic_source_id.as_ref() else {
        continue;
    };
    let source_node = source
        .nodes
        .iter()
        .find(|node| node.id == *source_id)
        .with_context(|| format!("group {} references missing semantic source {source_id}", group.id))?;
    groups.insert(
        group.id.clone(),
        RenderMetadataSelector {
            selector_type: source_node.node_type.clone(),
            source_id: source_node.id.clone(),
        },
    );
}
```

Return `RenderMetadata { ..., groups }`.

- [ ] **Step 4: Update source fixtures**

In `fixtures/source/valid-pipeline-rich.json`, add explicit roles to the existing groups:

```json
"role": "semantic-boundary"
```

In `fixtures/source/valid-pipeline-archimate.json`, add a source node:

```json
{
  "id": "application-landscape-grouping",
  "type": "Grouping",
  "label": "Application Landscape",
  "properties": {}
}
```

Set one existing group to:

```json
"role": "semantic-boundary",
"semantic_source_id": "application-landscape-grouping"
```

Set one fixture group to:

```json
"role": "layout-only"
```

This fixture must demonstrate both group roles in the same ArchiMate source.

- [ ] **Step 5: Run generic graph tests**

Run:

```bash
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin
```

Expected: all generic graph projection and validation tests pass.

### Task 4: Preserve Group Provenance Through ELK Layout

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/JsonContracts.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`
- Modify: `fixtures/layout-result/pipeline-rich.json`

- [ ] **Step 1: Add Java contract records**

Change `GroupProvenance` in `JsonContracts.java` to:

```java
record GroupProvenance(Boolean visual_only, SemanticBacked semantic_backed) {
}
```

Change `LaidOutGroup` to include `GroupProvenance provenance` after `projection_id`.

- [ ] **Step 2: Validate exactly one provenance shape in Java**

Update `validateProvenance` in `ElkLayoutEngine.java`:

```java
private static void validateProvenance(JsonContracts.GroupProvenance provenance, String path) {
    boolean visualOnly = Boolean.TRUE.equals(provenance.visual_only());
    boolean semanticBacked = provenance.semantic_backed() != null;
    if (visualOnly == semanticBacked) {
        throw new IllegalArgumentException(
            "group provenance must contain exactly one of visual_only or semantic_backed at " + path);
    }
    if (semanticBacked && provenance.semantic_backed().source_id() == null) {
        throw new IllegalArgumentException(
            "required string value is missing at " + path + ".semantic_backed.source_id");
    }
}
```

- [ ] **Step 3: Emit provenance on laid-out groups**

When constructing `JsonContracts.LaidOutGroup`, pass `group.provenance()` into the result. Keep `source_id` as:

```java
semanticBackedSourceId(group.provenance(), group.id())
```

For visual-only groups, `source_id` remains the group id for legacy consumers, but `provenance.visual_only` is the authority for semantic meaning.

- [ ] **Step 4: Add Java tests**

Add a test in `ElkLayoutEngineTest.java` named `layoutPreservesVisualOnlyGroupProvenance` that creates a layout request with:

```java
new JsonContracts.GroupProvenance(true, null)
```

Assert the first laid-out group has `provenance().visual_only() == true` and `provenance().semantic_backed() == null`.

Add a test in `MainTest.java` or `JsonContractsTest.java` named `requestWithAmbiguousGroupProvenanceReturnsErrorEnvelope` that sends:

```json
"provenance": {
  "visual_only": true,
  "semantic_backed": { "source_id": "group" }
}
```

Expected: error envelope with `DEDIREN_ELK_LAYOUT_FAILED`.

- [ ] **Step 5: Update layout result fixtures**

Add `provenance` to each group in `fixtures/layout-result/pipeline-rich.json`. Semantic groups use:

```json
"provenance": { "semantic_backed": { "source_id": "application-services" } }
```

Layout-only groups use:

```json
"provenance": { "visual_only": true }
```

- [ ] **Step 6: Run ELK contract tests**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin
```

Expected: Java helper builds and non-ignored Rust adapter tests pass.

### Task 5: Render Semantic ArchiMate Groups In SVG

**Files:**
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Create: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/archimate_groups.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
- Modify: `fixtures/render-policy/archimate-svg.json`
- Modify: `fixtures/render-metadata/archimate-basic.json`

- [ ] **Step 1: Register the new test module**

Add this line to `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`:

```rust
#[path = "svg_render_plugin/archimate_groups.rs"]
mod archimate_groups;
```

- [ ] **Step 2: Add SVG tests for layout-only and semantic groups**

Create `crates/dediren-plugin-svg-render/tests/svg_render_plugin/archimate_groups.rs`:

```rust
use super::common::{child_group_with_attr, render_ok_data, semantic_group, svg_doc};

#[test]
fn archimate_grouping_metadata_renders_group_decorator() {
    let layout_result = serde_json::json!({
        "layout_result_schema_version": "layout-result.schema.v1",
        "view_id": "main",
        "nodes": [],
        "edges": [],
        "groups": [
            {
                "id": "customer-domain",
                "source_id": "customer-domain",
                "projection_id": "customer-domain",
                "provenance": { "semantic_backed": { "source_id": "customer-domain" } },
                "x": 20.0,
                "y": 20.0,
                "width": 240.0,
                "height": 140.0,
                "members": [],
                "label": "Customer Domain"
            }
        ],
        "warnings": []
    });
    let metadata = serde_json::json!({
        "render_metadata_schema_version": "render-metadata.schema.v1",
        "semantic_profile": "archimate",
        "nodes": {},
        "edges": {},
        "groups": {
            "customer-domain": {
                "type": "Grouping",
                "source_id": "customer-domain"
            }
        }
    });
    let policy = serde_json::json!({
        "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
        "semantic_profile": "archimate",
        "page": { "width": 400, "height": 240 },
        "margin": { "top": 24, "right": 24, "bottom": 24, "left": 24 },
        "style": {
            "group_type_overrides": {
                "Grouping": {
                    "decorator": "archimate_grouping",
                    "fill": "#fef9c3",
                    "stroke": "#a16207"
                }
            }
        }
    });

    let data = render_ok_data(serde_json::json!({
        "layout_result": layout_result,
        "render_metadata": metadata,
        "policy": policy
    }));
    let svg = data["content"].as_str().unwrap();
    let doc = svg_doc(svg);
    let group = semantic_group(&doc, "data-dediren-group-id", "customer-domain");
    assert_eq!(
        group.attribute("data-dediren-group-type"),
        Some("Grouping")
    );
    let _decorator = child_group_with_attr(
        group,
        "data-dediren-group-decorator",
        "archimate_grouping",
    );
}
```

- [ ] **Step 3: Implement group metadata resolution**

In `crates/dediren-plugin-svg-render/src/main.rs`:

- Parse `metadata.groups`.
- Resolve group style in this order:
  1. base group style;
  2. `group_type_overrides[metadata.groups[group.id].type]`;
  3. `group_overrides[group.id]`.
- Add SVG attributes to rendered group `<g>`:

```text
data-dediren-group-id
data-dediren-group-type
data-dediren-group-source-id
```

Only emit `data-dediren-group-type` and `data-dediren-group-source-id` when metadata exists for the group.

- [ ] **Step 4: Draw the ArchiMate grouping decorator**

Reuse the existing ArchiMate icon drawing path. Add support for group decorators by drawing `ArchimateIconKind::Grouping` inside the top-right area of the group rectangle. Use the same `data-dediren-group-decorator="archimate_grouping"` marker asserted by the test.

- [ ] **Step 5: Update render fixtures**

Add a `groups` object to `fixtures/render-metadata/archimate-basic.json` if the fixture contains an ArchiMate semantic grouping. Add `group_type_overrides.Grouping` to `fixtures/render-policy/archimate-svg.json`.

- [ ] **Step 6: Run SVG tests**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin archimate_grouping_metadata_renders_group_decorator -- --exact
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
```

Expected: targeted and full SVG plugin tests pass.

### Task 6: Export ArchiMate Semantic Groups To OEF

**Files:**
- Modify: `crates/dediren-plugin-archimate-oef-export/src/main.rs`
- Modify: `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`
- Modify: `crates/dediren-cli/tests/cli_export.rs`

- [ ] **Step 1: Add exporter tests**

Add a test to `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`:

```rust
#[test]
fn oef_export_emits_semantic_grouping_view_node_and_ignores_layout_only_group() {
    let mut source: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(workspace_file("fixtures/source/valid-archimate-oef.json")).unwrap(),
    )
    .unwrap();
    source["nodes"].as_array_mut().unwrap().push(serde_json::json!({
        "id": "customer-domain",
        "type": "Grouping",
        "label": "Customer Domain",
        "properties": {}
    }));

    let mut layout: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(workspace_file("fixtures/layout-result/archimate-oef-basic.json")).unwrap(),
    )
    .unwrap();
    layout["groups"] = serde_json::json!([
        {
            "id": "customer-domain-group",
            "source_id": "customer-domain",
            "projection_id": "customer-domain-group",
            "provenance": { "semantic_backed": { "source_id": "customer-domain" } },
            "x": 10.0,
            "y": 10.0,
            "width": 520.0,
            "height": 180.0,
            "members": ["orders-component", "orders-service"],
            "label": "Customer Domain"
        },
        {
            "id": "visual-column",
            "source_id": "visual-column",
            "projection_id": "visual-column",
            "provenance": { "visual_only": true },
            "x": 40.0,
            "y": 40.0,
            "width": 200.0,
            "height": 120.0,
            "members": ["orders-component"],
            "label": "Visual Column"
        }
    ]);

    let data = export_with_source_and_layout(source, layout);
    let xml = data["content"].as_str().unwrap();
    let doc = roxmltree::Document::parse(xml).unwrap();

    let grouping_elements = doc
        .descendants()
        .filter(|node| {
            node.has_tag_name("element")
                && node.attribute(("http://www.w3.org/2001/XMLSchema-instance", "type"))
                    == Some("Grouping")
        })
        .count();
    assert_eq!(grouping_elements, 1);

    assert!(
        xml.contains("Customer Domain"),
        "expected semantic grouping label in OEF XML: {xml}"
    );
    assert!(
        !xml.contains("Visual Column"),
        "layout-only groups must not become OEF semantic view nodes: {xml}"
    );
}

fn export_with_source_and_layout(
    source: serde_json::Value,
    layout_result: serde_json::Value,
) -> serde_json::Value {
    let input = serde_json::json!({
        "export_request_schema_version": "export-request.schema.v1",
        "source": source,
        "layout_result": layout_result,
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/export-policy/default-oef.json")).unwrap()
        ).unwrap()
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    common::ok_data(&output)
}
```

- [ ] **Step 2: Implement semantic group selection**

In `crates/dediren-plugin-archimate-oef-export/src/main.rs`, build a map of source nodes by id. For each layout group:

- If `group.provenance` is `None`, treat it as legacy semantic-backed provenance using `group.source_id`.
- Skip if `group.provenance.visual_only == true`.
- Read the semantic source id from `group.provenance.semantic_backed.source_id`, or from `group.source_id` for legacy layout results.
- Skip if the semantic source id is not a source node.
- Export as a semantic group only when the source node type is exactly `Grouping`.
- Return a plugin error envelope if a layout group claims a semantic source id whose source node exists but is not ArchiMate type `Grouping`.

- [ ] **Step 3: Emit OEF group view nodes**

Emit one OEF view `<node xsi:type="Element" elementRef="...">` for each semantic group before normal view nodes. Use the layout group geometry for `x`, `y`, `w`, and `h`. Emit the grouping view node as a sibling before members and keep relationship connections unchanged. Layout-only groups must not appear as OEF semantic elements.

- [ ] **Step 4: Add CLI export coverage**

Add a CLI test to `crates/dediren-cli/tests/cli_export.rs` that uses the same source/layout shape through `dediren export --plugin archimate-oef`. Assert:

```rust
assert!(xml.contains("Grouping"));
assert!(xml.contains("Customer Domain"));
assert!(!xml.contains("Visual Column"));
```

- [ ] **Step 5: Run export tests**

Run:

```bash
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin
cargo test -p dediren --test cli_export
```

Expected: exporter and CLI tests pass.

### Task 7: Update Pipeline Fixtures And README

**Files:**
- Modify: `fixtures/source/valid-pipeline-rich.json`
- Modify: `fixtures/source/valid-pipeline-archimate.json`
- Modify: `fixtures/layout-result/pipeline-rich.json`
- Modify: `fixtures/render-metadata/archimate-basic.json`
- Modify: `fixtures/render-policy/archimate-svg.json`
- Modify: `README.md`
- Modify: `crates/dediren-cli/tests/cli_pipeline.rs`

- [ ] **Step 1: Add pipeline test assertions**

In `crates/dediren-cli/tests/cli_pipeline.rs`, extend `fixture_archimate_pipeline_renders_node_notation` or add a focused test that asserts:

```rust
let group = semantic_group(&doc, "data-dediren-group-id", "application-services");
assert_eq!(
    group.attribute("data-dediren-group-type"),
    Some("Grouping")
);
assert!(child_group_with_attr(
    group,
    "data-dediren-group-decorator",
    "archimate_grouping"
).is_some());
```

Use a group id from `fixtures/source/valid-pipeline-archimate.json` after Task 3 updates that fixture.

- [ ] **Step 2: Update README user-facing behavior**

Add a subsection under `## ArchiMate SVG And OEF`:

```markdown
### Groups

`plugins.generic-graph.views[].groups` are layout containers. Give each group
an explicit `role`:

- `semantic-boundary` means the group carries architectural meaning. This is
  the default for older source files.
- `layout-only` means the group is only a layout aid and must not be exported as
  an ArchiMate element.

For ArchiMate `Grouping`, create a normal source node with `"type": "Grouping"`
and point the view group at it with `semantic_source_id`. The layout group then
uses generated geometry, SVG can render ArchiMate grouping notation, and OEF
export can emit an ArchiMate Grouping view node. Do not use layout-only groups
for ArchiMate semantic Grouping.
```

Update the command/workflow text to mention that render metadata can contain node, relationship, and group selectors.

- [ ] **Step 3: Run pipeline tests**

Run:

```bash
cargo test -p dediren --test cli_pipeline
```

Expected: pipeline tests pass and assert both SVG and OEF behavior.

### Task 8: Version Bump And Distribution Surfaces

**Files:**
- Modify: `Cargo.toml`
- Modify: `Cargo.lock`
- Modify: `fixtures/plugins/archimate-oef.manifest.json`
- Modify: `fixtures/plugins/elk-layout.manifest.json`
- Modify: `fixtures/plugins/generic-graph.manifest.json`
- Modify: `fixtures/plugins/svg-render.manifest.json`
- Modify: `README.md`
- Modify: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`

- [ ] **Step 1: Bump workspace version**

Change `Cargo.toml`:

```toml
[workspace.package]
version = "0.6.0"
```

Run:

```bash
cargo check --workspace --locked
```

Expected: FAIL because `Cargo.lock` still has `0.5.0` workspace package versions.

- [ ] **Step 2: Refresh lockfile package versions**

Run:

```bash
cargo check --workspace
```

Expected: PASS and `Cargo.lock` updates workspace package versions to `0.6.0`.

- [ ] **Step 3: Update plugin manifests**

Set each first-party manifest version to `0.6.0`:

```json
"version": "0.6.0"
```

- [ ] **Step 4: Update README bundle examples**

Replace `0.5.0` with `0.6.0` in bundle archive names and smoke commands.

- [ ] **Step 5: Update tests that assert version strings**

Update expected version strings in `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs` from `0.5.0` to `0.6.0`.

- [ ] **Step 6: Run version-surface tests**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts first_party_plugin_manifest_versions_match_workspace_version -- --exact
cargo test -p dediren-contracts --test schema_contracts source_fixture_required_plugin_versions_match_first_party_manifests -- --exact
cargo test -p dediren-contracts --test schema_contracts live_release_surfaces_match_workspace_version -- --exact
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin
```

Expected: version assertions pass.

### Task 9: Full Verification And Audit Gates

**Files:**
- No planned source edits in this task.

- [ ] **Step 1: Run formatting**

Run:

```bash
cargo fmt --all -- --check
```

Expected: PASS.

- [ ] **Step 2: Run contract and plugin lanes**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts
cargo test -p dediren-contracts --test contract_roundtrip
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin
cargo test -p dediren --test cli_export
cargo test -p dediren --test cli_pipeline
```

Expected: PASS.

- [ ] **Step 3: Run Java helper lane**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin
cargo test -p dediren --test cli_layout real_elk_layout_validates_grouped_cross_group_route -- --ignored --exact --test-threads=1
```

Expected: PASS. The ignored real helper test must pass because layout group provenance is now part of the real helper contract.

- [ ] **Step 4: Run workspace verification**

Run:

```bash
cargo test --workspace --locked
```

Expected: PASS.

- [ ] **Step 5: Run distribution smoke**

Run:

```bash
cargo xtask dist build
cargo xtask dist smoke dist/dediren-agent-bundle-0.6.0-x86_64-unknown-linux-gnu.tar.gz
```

Expected: archive builds and smoke pipeline passes with first-party bundled plugins.

- [ ] **Step 6: Run required audit gates**

Because this plan changes source contracts, plugin behavior, OEF export, render metadata, fixtures, README, Java helper boundary behavior, and distribution contents, run:

```text
souroldgeezer-audit:test-quality-audit
Mode: Deep
Scope: contracts, group fixtures, generic-graph projection tests, SVG group rendering tests, OEF group export tests, Java helper provenance tests, CLI pipeline/export tests
```

```text
souroldgeezer-audit:devsecops-audit
Mode: Quick
Scope: plugin process boundary, Java helper JSON boundary, distribution archive contents, README install/runtime claims
```

Fix block findings. Fix warn/info findings or explicitly accept them in the implementation handoff with the affected files and verification commands.

- [ ] **Step 7: Review git diff before staging**

Run:

```bash
git status --short --branch
git diff -- Cargo.toml Cargo.lock README.md schemas fixtures crates xtask
```

Expected: only intentional group-semantics, version, fixture, test, and documentation changes appear. Do not stage generated SVGs or build output.
