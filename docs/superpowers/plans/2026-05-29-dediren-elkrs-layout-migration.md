# Dediren Rust ELK Layout Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Java-backed `elk-layout` helper with the Rust `elkrs` layered layout library from `https://github.com/tommimarkus/elkrs`, remove active Java/Gradle runtime paths, and keep the public Dediren layout request/result contract stable.

**Status:** Implemented on branch `elkrs-layout-replacement`. The checklist below records the planned execution shape; live code, tests, and release surfaces are the current truth.

**Architecture:** Keep `dediren-plugin-elk-layout` as the first-party executable plugin. The plugin reads `LayoutRequest`, honors `DEDIREN_ELK_RESULT_FIXTURE` first, otherwise maps the request to `elkrs_core::graph::ElkGraph`, runs `elkrs_layered::LayeredLayout` in the plugin process, maps generated geometry back to `LayoutResult`, and emits the existing command envelope. `dediren-core` remains backend neutral and only controls plugin discovery/execution.

**Tech Stack:** Rust 1.93, Cargo workspace git dependencies pinned to `elkrs` revision `aeba4e35a0648b57caa88b8588099a3bb48021ae` (`v1.0.0`), `elkrs-core`, `elkrs-layered`, `serde_json`, existing Dediren command envelopes, existing plugin/CLI/schema/dist test lanes.

---

## Commit Boundary

This migration changes shipped behavior, plugin manifests, runtime capability output, docs, and distribution contents. Do not commit partial behavior changes without the matching release surfaces. Build the implementation in working tree checkpoints, then commit the complete migration with the `0.17.0` version bump and create annotated tag `v0.17.0` on that commit.

The already committed design spec is `52b2b0a docs: design rust elk layout migration`.

## Files To Change

- `Cargo.toml`, `Cargo.lock`
- `crates/dediren-plugin-elk-layout/Cargo.toml`
- `crates/dediren-plugin-elk-layout/src/main.rs`
- `crates/dediren-plugin-elk-layout/src/elkrs_backend.rs`
- `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`
- `crates/dediren-cli/tests/cli_layout.rs`
- `crates/dediren-cli/tests/real_elk_render.rs`
- `crates/dediren-core/tests/commands.rs`
- `crates/dediren-contracts/tests/schema_contracts.rs`
- `xtask/src/main.rs`
- `xtask/tests/dist.rs`
- `.github/workflows/release.yml`
- `.gitignore`
- `README.md`
- `docs/agent-usage.md`
- `fixtures/plugins/*.manifest.json`
- `fixtures/source/*.json`
- remove tracked `crates/dediren-plugin-elk-layout/java/**`

Do not touch unrelated untracked Java build leftovers in the original checkout. In this worktree, remove only tracked Java helper source/build-control files.

## Task 1: Add Pinned elkrs Dependencies And Red Tests

- [ ] Add pinned workspace dependencies:

```toml
elkrs-core = { git = "https://github.com/tommimarkus/elkrs", rev = "aeba4e35a0648b57caa88b8588099a3bb48021ae" }
elkrs-layered = { git = "https://github.com/tommimarkus/elkrs", rev = "aeba4e35a0648b57caa88b8588099a3bb48021ae" }
```

- [ ] Add plugin crate dependencies:

```toml
elkrs-core.workspace = true
elkrs-layered.workspace = true
```

- [ ] Replace Java/fake-command plugin tests with rust-backend contract tests. Keep fixture mode, remove external-command envelope preservation tests, and add a direct assertion that `DEDIREN_ELK_COMMAND` is ignored:

```rust
#[test]
fn rust_elk_plugin_layouts_basic_request_without_external_runtime() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    let output = cmd
        .env_remove("DEDIREN_ELK_RESULT_FIXTURE")
        .env_remove("DEDIREN_ELK_COMMAND")
        .arg("layout")
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let envelope: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(envelope["status"], "ok");
    let data = &envelope["data"];
    assert_basic_layout_result(data);
    let edge = data["edges"]
        .as_array()
        .unwrap()
        .iter()
        .find(|edge| edge["id"] == "client-calls-api")
        .unwrap();
    assert!(
        !edge["points"].as_array().unwrap().is_empty(),
        "elkrs backend should generate route points"
    );
}

#[test]
fn capabilities_report_rust_elkrs_runtime_available() {
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    let output = cmd
        .arg("capabilities")
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let capabilities: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(capabilities["id"], "elk-layout");
    assert_eq!(capabilities["runtime"]["kind"], "rust-elkrs");
    assert_eq!(capabilities["runtime"]["available"], true);
}

#[test]
fn dediren_elk_command_is_ignored_by_rust_backend() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_COMMAND", "sh -c 'exit 42'")
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"status\":\"ok\""));
}
```

- [ ] Keep a fixture precedence test:

```rust
#[test]
fn fixture_elk_plugin_accepts_fixture_runtime_output() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let fake = workspace_file("fixtures/layout-result/basic.json");
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_RESULT_FIXTURE", fake)
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"projection_id\":\"client\""));
}
```

- [ ] Run the focused plugin test lane and expect failures until Tasks 2 and 3 are implemented:

```bash
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin
```

If Cargo cannot fetch the git dependencies in the sandbox, rerun the same command with escalation. Do not vendor `elkrs` source into this repository.

## Task 2: Replace External Runtime Dispatch With In-Process Rust Layout

- [ ] Rewrite `crates/dediren-plugin-elk-layout/src/main.rs` so the plugin has only three runtime paths: `capabilities`, `layout` with fixture output, and `layout` through `elkrs_backend::layout`.

```rust
mod elkrs_backend;

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
                    "kind": "rust-elkrs",
                    "available": true
                }
            })
        );
        return Ok(());
    }

    if args.get(1).map(String::as_str) != Some("layout") {
        exit_with_diagnostic("DEDIREN_ELK_COMMAND_UNSUPPORTED", "expected command: layout");
    }

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let request: LayoutRequest = serde_json::from_str(&input).unwrap_or_else(|error| {
        exit_with_diagnostic(
            "DEDIREN_ELK_INPUT_INVALID_JSON",
            &format!("layout request is not valid JSON for layout-request.schema.v1: {error}"),
        );
    });

    if let Some(fixture) = std::env::var_os("DEDIREN_ELK_RESULT_FIXTURE") {
        let text = std::fs::read_to_string(&fixture).unwrap_or_else(|error| {
            exit_with_diagnostic(
                "DEDIREN_ELK_FIXTURE_UNAVAILABLE",
                &format!("failed to read DEDIREN_ELK_RESULT_FIXTURE: {error}"),
            );
        });
        let result: LayoutResult = serde_json::from_str(&text).unwrap_or_else(|error| {
            exit_with_diagnostic(
                "DEDIREN_ELK_FIXTURE_INVALID_JSON",
                &format!("fixture is not a valid layout-result.schema.v1 document: {error}"),
            );
        });
        println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
        return Ok(());
    }

    let result = elkrs_backend::layout(&request).unwrap_or_else(|diagnostic| {
        exit_with_diagnostic(&diagnostic.code, &diagnostic.message);
    });
    println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
    Ok(())
}

fn exit_with_diagnostic(code: &str, message: &str) -> ! {
    let diagnostic = Diagnostic {
        code: code.to_string(),
        severity: DiagnosticSeverity::Error,
        message: message.to_string(),
        path: None,
    };
    println!(
        "{}",
        serde_json::to_string(&CommandEnvelope::<serde_json::Value>::error(vec![
            diagnostic
        ]))
        .unwrap()
    );
    std::process::exit(3);
}
```

- [ ] Delete all Java/runtime-command helpers from `main.rs`: `RuntimeCommand`, `DEDIREN_ELK_COMMAND`, bundled helper path resolution, shell command execution, HotSpot warning stripping, Java version parsing, and external envelope pass-through.

- [ ] Add unit tests for the new error envelope helpers only if they are extracted into pure functions. Avoid private tests that assert deleted Java behavior.

## Task 3: Implement `elkrs_backend`

- [ ] Create `crates/dediren-plugin-elk-layout/src/elkrs_backend.rs`.

- [ ] Implement request-to-graph mapping with explicit validation and stable warning codes:

```rust
use std::collections::{BTreeMap, BTreeSet};

use dediren_contracts::{
    Diagnostic, DiagnosticSeverity, LaidOutEdge, LaidOutGroup, LaidOutNode,
    LayoutDensity, LayoutDirection, LayoutEndpointMerging, LayoutGroup, LayoutNode,
    LayoutPreferences, LayoutRequest, LayoutResult, LayoutRoutingProfile, LayoutWrapping, Point,
};
use elkrs_core::diagnostic::{Diagnostic as ElkrsDiagnostic, Severity as ElkrsSeverity};
use elkrs_core::geometry::Size;
use elkrs_core::graph::{ElementId, ElementRef, ElkEdge, ElkGraph, ElkLabel, ElkNode};
use elkrs_core::options::{
    Algorithm, Direction, EdgeRouting, HierarchyHandling,
};
use elkrs_layered::{LayeredLayout, LayoutAlgorithm};

const DEFAULT_NODE_WIDTH: f64 = 160.0;
const DEFAULT_NODE_HEIGHT: f64 = 80.0;

pub fn layout(request: &LayoutRequest) -> Result<LayoutResult, Diagnostic> {
    let mut warnings = Vec::new();
    validate_unique_ids(request)?;
    let mut graph = request_to_graph(request, &mut warnings)?;
    let report = LayeredLayout.layout(&mut graph).map_err(|error| Diagnostic {
        code: "DEDIREN_ELK_LAYOUT_FAILED".to_string(),
        severity: DiagnosticSeverity::Error,
        message: format!("elkrs layered layout failed: {error}"),
        path: None,
    })?;
    warnings.extend(report.diagnostics.into_iter().map(from_elkrs_diagnostic));
    Ok(graph_to_result(request, &graph, warnings))
}

fn request_to_graph(
    request: &LayoutRequest,
    warnings: &mut Vec<Diagnostic>,
) -> Result<ElkGraph, Diagnostic> {
    let mut graph = ElkGraph::new(request.view_id.as_str());
    graph.properties.set_algorithm(Algorithm::Layered);
    graph.properties.set_edge_routing(EdgeRouting::Orthogonal);
    graph
        .properties
        .set_hierarchy_handling(HierarchyHandling::IncludeChildren);
    apply_preferences(&mut graph, request.layout_preferences.as_ref(), warnings);

    let member_to_group = member_to_group(request, warnings);
    let mut group_nodes = BTreeMap::new();
    for group in &request.groups {
        let mut node = ElkNode::new(group.id.as_str());
        node.labels.push(ElkLabel {
            text: group.label.clone(),
        });
        group_nodes.insert(group.id.as_str(), node);
    }

    for layout_node in &request.nodes {
        let mut node = ElkNode::new(layout_node.id.as_str());
        node.size = Size::new(
            layout_node.width_hint.unwrap_or(DEFAULT_NODE_WIDTH),
            layout_node.height_hint.unwrap_or(DEFAULT_NODE_HEIGHT),
        );
        node.labels.push(ElkLabel {
            text: layout_node.label.clone(),
        });
        if let Some(group_id) = member_to_group.get(layout_node.id.as_str()) {
            group_nodes
                .get_mut(group_id.as_str())
                .expect("member_to_group only contains known groups")
                .add_child(node);
        } else {
            graph.add_node(node);
        }
    }

    for (_, group_node) in group_nodes {
        graph.add_node(group_node);
    }

    for layout_edge in &request.edges {
        graph.add_edge(ElkEdge::new(
            layout_edge.id.as_str(),
            ElementRef::Node(ElementId::new(layout_edge.source.clone())),
            ElementRef::Node(ElementId::new(layout_edge.target.clone())),
        ));
    }

    Ok(graph)
}
```

- [ ] Complete the helper functions with these rules:

```rust
fn validate_unique_ids(request: &LayoutRequest) -> Result<(), Diagnostic> {
    let mut ids = BTreeSet::new();
    for id in request
        .nodes
        .iter()
        .map(|node| node.id.as_str())
        .chain(request.groups.iter().map(|group| group.id.as_str()))
        .chain(request.edges.iter().map(|edge| edge.id.as_str()))
    {
        if !ids.insert(id) {
            return Err(Diagnostic {
                code: "DEDIREN_ELK_DUPLICATE_ID".to_string(),
                severity: DiagnosticSeverity::Error,
                message: format!("layout request contains duplicate id `{id}`"),
                path: None,
            });
        }
    }
    Ok(())
}

fn apply_preferences(
    graph: &mut ElkGraph,
    preferences: Option<&LayoutPreferences>,
    warnings: &mut Vec<Diagnostic>,
) {
    let Some(preferences) = preferences else {
        return;
    };
    match preferences.direction.unwrap_or(LayoutDirection::Right) {
        LayoutDirection::Right => graph.properties.set_direction(Direction::Right),
        LayoutDirection::Left => graph.properties.set_direction(Direction::Left),
        LayoutDirection::Down => graph.properties.set_direction(Direction::Down),
        LayoutDirection::Up => graph.properties.set_direction(Direction::Up),
    };
    match preferences.density.unwrap_or(LayoutDensity::Readable) {
        LayoutDensity::Compact => set_spacing(graph, 50.0, 80.0, 16.0, 8.0),
        LayoutDensity::Readable => set_spacing(graph, 80.0, 120.0, 20.0, 10.0),
        LayoutDensity::Spacious => set_spacing(graph, 110.0, 170.0, 32.0, 18.0),
    }
    if matches!(preferences.wrapping, Some(LayoutWrapping::Auto | LayoutWrapping::MultiEdge)) {
        warnings.push(warning(
            "DEDIREN_ELK_OPTION_UNSUPPORTED",
            "elkrs v1.0.0 does not implement Dediren layout wrapping preferences",
        ));
    }
    if let Some(routing) = &preferences.routing {
        if matches!(
            routing.endpoint_merging,
            Some(LayoutEndpointMerging::Local | LayoutEndpointMerging::Auto)
        ) {
            warnings.push(warning(
                "DEDIREN_ELK_OPTION_UNSUPPORTED",
                "elkrs v1.0.0 does not implement endpoint merging hints",
            ));
        }
        match routing.profile.unwrap_or(LayoutRoutingProfile::Readable) {
            LayoutRoutingProfile::Compact => set_spacing(graph, 50.0, 80.0, 16.0, 8.0),
            LayoutRoutingProfile::Readable => {}
            LayoutRoutingProfile::Spacious => graph.properties.set_spacing_edge_edge(18.0),
        };
    }
}

fn set_spacing(
    graph: &mut ElkGraph,
    node_node: f64,
    layer_node_node: f64,
    edge_node: f64,
    edge_edge: f64,
) {
    graph.properties.set_spacing_node_node(node_node);
    graph.properties.set_spacing_layer_node_node(layer_node_node);
    graph.properties.set_spacing_edge_node(edge_node);
    graph.properties.set_spacing_edge_edge(edge_edge);
}

fn member_to_group(
    request: &LayoutRequest,
    warnings: &mut Vec<Diagnostic>,
) -> BTreeMap<String, String> {
    let node_ids = request
        .nodes
        .iter()
        .map(|node| node.id.as_str())
        .collect::<BTreeSet<_>>();
    let mut result = BTreeMap::new();
    for group in &request.groups {
        for member in &group.members {
            if !node_ids.contains(member.as_str()) {
                warnings.push(warning(
                    "DEDIREN_ELK_GROUP_MEMBER_UNSUPPORTED",
                    &format!("group `{}` references non-node member `{member}`", group.id),
                ));
                continue;
            }
            if result.insert(member.clone(), group.id.clone()).is_some() {
                warnings.push(warning(
                    "DEDIREN_ELK_GROUP_MEMBER_DUPLICATE",
                    &format!("node `{member}` appears in more than one group"),
                ));
            }
        }
    }
    result
}
```

- [ ] Map results without custom route rewriting:

```rust
fn graph_to_result(
    request: &LayoutRequest,
    graph: &ElkGraph,
    warnings: Vec<Diagnostic>,
) -> LayoutResult {
    let node_by_id = request
        .nodes
        .iter()
        .map(|node| (node.id.as_str(), node))
        .collect::<BTreeMap<_, _>>();
    let group_by_id = request
        .groups
        .iter()
        .map(|group| (group.id.as_str(), group))
        .collect::<BTreeMap<_, _>>();
    let edge_by_id = request
        .edges
        .iter()
        .map(|edge| (edge.id.as_str(), edge))
        .collect::<BTreeMap<_, _>>();

    let mut nodes = Vec::new();
    let mut groups = Vec::new();
    for node in graph.nodes.values() {
        collect_node_or_group(node, &node_by_id, &group_by_id, &mut nodes, &mut groups);
    }

    let edges = graph
        .edges
        .values()
        .filter_map(|edge| {
            let request_edge = edge_by_id.get(edge.id.as_str())?;
            Some(LaidOutEdge {
                id: request_edge.id.clone(),
                source: request_edge.source.clone(),
                target: request_edge.target.clone(),
                source_id: request_edge.source_id.clone(),
                projection_id: request_edge.id.clone(),
                routing_hints: Vec::new(),
                points: edge
                    .sections
                    .first()
                    .map(|section| {
                        section
                            .points
                            .iter()
                            .map(|point| Point {
                                x: point.x,
                                y: point.y,
                            })
                            .collect()
                    })
                    .unwrap_or_default(),
                label: request_edge.label.clone(),
            })
        })
        .collect();

    LayoutResult {
        layout_result_schema_version: "layout-result.schema.v1".to_string(),
        view_id: request.view_id.clone(),
        nodes,
        edges,
        groups,
        warnings,
    }
}

fn collect_node_or_group(
    node: &ElkNode,
    node_by_id: &BTreeMap<&str, &LayoutNode>,
    group_by_id: &BTreeMap<&str, &LayoutGroup>,
    nodes: &mut Vec<LaidOutNode>,
    groups: &mut Vec<LaidOutGroup>,
) {
    let id = node.id.as_str();
    if let Some(group) = group_by_id.get(id) {
        groups.push(LaidOutGroup {
            id: group.id.clone(),
            source_id: group
                .provenance
                .semantic_source_id()
                .unwrap_or(group.id.as_str())
                .to_string(),
            projection_id: group.id.clone(),
            provenance: Some(group.provenance.clone()),
            x: node.position.x,
            y: node.position.y,
            width: node.size.width,
            height: node.size.height,
            members: group.members.clone(),
            label: group.label.clone(),
        });
        for child in node.children.values() {
            collect_node_or_group(child, node_by_id, group_by_id, nodes, groups);
        }
    } else if let Some(layout_node) = node_by_id.get(id) {
        nodes.push(LaidOutNode {
            id: layout_node.id.clone(),
            source_id: layout_node.source_id.clone(),
            projection_id: layout_node.id.clone(),
            x: node.position.x,
            y: node.position.y,
            width: node.size.width,
            height: node.size.height,
            label: layout_node.label.clone(),
        });
    }
}
```

- [ ] `collect_node_or_group` must recurse into compound group children, use `LaidOutNode` for request nodes, use `LaidOutGroup` for request groups, preserve `GroupProvenance`, and set group `source_id` to semantic provenance source id when present or the group id when visual-only.

- [ ] Convert `elkrs` diagnostics:

```rust
fn from_elkrs_diagnostic(diagnostic: ElkrsDiagnostic) -> Diagnostic {
    Diagnostic {
        code: format!("ELKRS_{}", diagnostic.code),
        severity: match diagnostic.severity {
            ElkrsSeverity::Warning => DiagnosticSeverity::Warning,
            ElkrsSeverity::Error => DiagnosticSeverity::Error,
        },
        message: diagnostic.message,
        path: diagnostic.element_id,
    }
}

fn warning(code: &str, message: &str) -> Diagnostic {
    Diagnostic {
        code: code.to_string(),
        severity: DiagnosticSeverity::Warning,
        message: message.to_string(),
        path: None,
    }
}
```

- [ ] Add backend unit tests inside `elkrs_backend.rs` for:
  - direction `down` places target centers below source centers.
  - duplicate id returns `DEDIREN_ELK_DUPLICATE_ID`.
  - endpoint merging `auto` produces warning `DEDIREN_ELK_OPTION_UNSUPPORTED`.
  - grouped source maps to `LaidOutGroup` with members and provenance.

## Task 4: Convert CLI And Render Tests To Rust Backend Evidence

- [ ] In `crates/dediren-cli/tests/cli_layout.rs`, delete `fake_elk_layout_wraps_external_command` and remove `helper_command` plus `shell_quote`.

- [ ] Convert ignored Java tests to normal tests:
  - `real_elk_layout_invokes_java_helper` becomes `rust_elk_layout_invokes_in_process_backend`.
  - `real_elk_layout_validates_grouped_cross_group_route` becomes `rust_elk_layout_validates_grouped_cross_group_route`.
  - `real_elk_layout_applies_layout_preferences` becomes `rust_elk_layout_applies_layout_preferences`.

- [ ] The converted tests must set only `DEDIREN_PLUGIN_ELK_LAYOUT`, not `DEDIREN_ELK_COMMAND`.

- [ ] Keep parsed JSON assertions. Assert schema version, expected ids, non-empty route points, direction behavior, empty endpoint-merging hints when requested off, and `validate-layout` success for grouped route output.

- [ ] In `crates/dediren-cli/tests/real_elk_render.rs`, remove all ignored Java-helper attributes and helper-command setup. Keep the visual quality lane as a normal Rust backend test suite, write artifacts under `.test-output/renders/rust-elk/`, and keep render artifacts untracked.

- [ ] If `elkrs` output exposes a supported limitation, prefer fixing graph options and group mapping first. If a route-quality assertion still fails due an `elkrs` v1.0.0 limitation, document the exact limitation in the test name and assert the warning code emitted by the plugin instead of silently weakening geometry checks.

## Task 5: Remove Java Helper Source And Runtime Surfaces

- [ ] Remove tracked Java helper files:

```bash
git rm -r crates/dediren-plugin-elk-layout/java
```

- [ ] Remove Java/Gradle ignore entries that only existed for the helper: `.cache/gradle/`, `crates/dediren-plugin-elk-layout/java/.gradle/`, `crates/dediren-plugin-elk-layout/java/build/`, and helper lock paths.

- [ ] Update `fixtures/plugins/elk-layout.manifest.json`:

```json
{
  "plugin_manifest_schema_version": "plugin-manifest.schema.v1",
  "id": "elk-layout",
  "version": "0.17.0",
  "executable": "dediren-plugin-elk-layout",
  "capabilities": ["layout"],
  "allowed_env": ["DEDIREN_ELK_RESULT_FIXTURE"]
}
```

- [ ] Update `crates/dediren-core/tests/commands.rs` to remove `DEDIREN_ELK_COMMAND` and `PATH` from the `elk-layout` manifest fixtures and assertions. The core should still prove allowed environment variables are explicitly passed and ambient variables are stripped.

- [ ] Update `crates/dediren-contracts/tests/schema_contracts.rs` to assert the new manifest allowlist, no Java/Gradle release workflow steps, no `bundle.json.elk_helper`, and README text that describes Rust-backed layout.

## Task 6: Clean Distribution Build And Release CI

- [ ] In `xtask/src/main.rs`, remove:
  - `MIN_JAVA_MAJOR`.
  - `DEDIREN_ELK_COMMAND` from `CLEAN_ENV`.
  - Java helper build invocation.
  - `runtimes/elk-layout-java` copy.
  - Java runtime smoke check.
  - Java/Gradle third-party notice section.
  - `ensure_java_runtime` and Java version parsing helpers.
  - `elk_helper` from generated `bundle.json`.

- [ ] Keep Rust plugin binaries in the bundle and keep smoke tests executing `dediren layout` through bundled first-party plugins.

- [ ] Update `xtask/tests/dist.rs` by deleting Java helper stubs, Java version checks, Gradle notice assertions, and `DEDIREN_ELK_COMMAND` clean-env assertions. Add assertions that:
  - bundle metadata has no `elk_helper`.
  - distribution contents do not contain `runtimes/elk-layout-java`.
  - smoke runs without requiring `java` on `PATH`.
  - notices only reflect Rust dependency notice generation.

- [ ] In `.github/workflows/release.yml`, remove Java setup, Gradle setup, Gradle project-cache restore, and `DEDIREN_ELK_BUILD_USE_SDKMAN`. Keep pinned action SHAs for remaining release steps.

## Task 7: Update Docs And Versioned Release Surfaces

- [ ] Bump workspace package version in `Cargo.toml` from `0.16.0` to `0.17.0`.

- [ ] Update all first-party plugin manifests under `fixtures/plugins/*.manifest.json` to `0.17.0`.

- [ ] Update `fixtures/source/*.json` `required_plugins[].version` values to `0.17.0`.

- [ ] Update README distribution archive examples from `0.16.0` to `0.17.0`.

- [ ] Rewrite README ELK sections:
  - no Java, Gradle, SDKMAN, helper build, bundled helper runtime, or `DEDIREN_ELK_COMMAND` requirement.
  - `elk-layout` runs Rust `elkrs-layered` in the plugin process.
  - `DEDIREN_ELK_RESULT_FIXTURE` remains test-only fixture mode.
  - `elkrs` is pinned as a git dependency because it is not published to crates.io.
  - release archives no longer include `runtimes/elk-layout-java`.

- [ ] Update `docs/agent-usage.md` with the same plugin runtime and bundle examples as README.

- [ ] Refresh `Cargo.lock` after dependency and version changes:

```bash
cargo update -w
```

Then all verification commands must use `--locked`.

## Task 8: Test-Quality And DevSecOps Audit Checks

- [ ] Apply `souroldgeezer-audit:test-quality-audit` as a deep layout/plugin suite review. Explicitly check:
  - Positive path evidence comes from real plugin output, not fixture-only data.
  - Parsed command envelopes are asserted structurally.
  - Basic, grouped, preference, render, and fixture paths are covered.
  - Negative coverage exists for invalid request JSON, invalid fixture JSON, duplicate ids, and an `elkrs` layout failure path.
  - No normal test depends on Java, Gradle, SDKMAN, `PATH`, or ignored real-helper setup.
  - Route-quality assertions prove contract-level behavior without coupling to exact coordinates.

- [ ] Apply `souroldgeezer-audit:devsecops-audit` as a quick implementation diff review. Explicitly check:
  - `elkrs` dependencies are pinned by exact revision.
  - No shell execution remains in `dediren-plugin-elk-layout`.
  - The plugin manifest allowlist only includes `DEDIREN_ELK_RESULT_FIXTURE`.
  - Release CI no longer provisions Java or Gradle.
  - Dist archives no longer contain helper runtimes or Java notices.
  - No new generated artifacts, build outputs, credentials, or local cache paths are staged.

- [ ] Fix block findings. Fix warn/info findings or record a concrete acceptance in the handoff.

## Task 9: Verification Commands

- [ ] Format and focused tests:

```bash
cargo fmt --all -- --check
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin --locked
cargo test -p dediren --test cli_layout --locked
cargo test -p dediren --test real_elk_render --locked -- --test-threads=1
```

- [ ] Contract and runtime tests:

```bash
cargo test -p dediren-contracts --test schema_contracts --locked
cargo test -p dediren-contracts --test contract_roundtrip --locked
cargo test -p dediren-core --test plugin_runtime --locked
cargo test -p dediren --test plugin_compat --locked
```

- [ ] Workspace and docs checks:

```bash
cargo test --workspace --locked
git diff --check
```

- [ ] Distribution checks, if the target toolchain is installed:

```bash
cargo xtask dist build --target x86_64-unknown-linux-gnu --version 0.17.0
cargo xtask dist smoke --target x86_64-unknown-linux-gnu --version 0.17.0
```

- [ ] Product-surface Java cleanup search:

```bash
rg -n 'Java|java|Gradle|SDKMAN|DEDIREN_ELK_COMMAND|DEDIREN_ELK_JAVA|elk-layout-java|runtimes/elk-layout-java' README.md docs/agent-usage.md .github fixtures crates xtask Cargo.toml Cargo.lock
```

Expected: no active product/runtime hits. If hits remain in migration specs or historical plans under `docs/superpowers`, report them separately as implementation history rather than public runtime guidance.

- [ ] Version search:

```bash
rg -n '0\.16\.0|0\.17\.0' Cargo.toml Cargo.lock README.md docs/agent-usage.md fixtures/plugins fixtures/source
```

Expected: `0.17.0` on current version surfaces and no stale `0.16.0` matches.

## Task 10: Stage, Commit, And Tag

- [ ] Check status:

```bash
git status --short --branch
```

- [ ] Review each touched path before staging:

```bash
git diff -- Cargo.toml Cargo.lock README.md docs/agent-usage.md .github/workflows/release.yml xtask crates fixtures
```

- [ ] Stage intentional files explicitly. Do not use `git add -A`.

- [ ] Commit the migration:

```bash
git commit -m "Replace Java ELK helper with elkrs layout"
```

- [ ] Create annotated tag on the migration commit:

```bash
git tag -a v0.17.0 -m "v0.17.0"
```

- [ ] Finish with:

```bash
git status --short --branch
```

## Success Criteria

- `elk-layout` produces layout results through Rust `elkrs-layered` without Java.
- Fixture mode remains deterministic through `DEDIREN_ELK_RESULT_FIXTURE`.
- `DEDIREN_ELK_COMMAND` is not documented, allowed, or honored.
- Release CI and dist builds do not install, build, copy, smoke, or notice Java/Gradle helper artifacts.
- Public Dediren layout request/result schemas stay unchanged.
- Versioned release surfaces are at `0.17.0` and tag `v0.17.0` exists on the implementation commit.
- Test-quality and DevSecOps audit gates have no unresolved block findings.
