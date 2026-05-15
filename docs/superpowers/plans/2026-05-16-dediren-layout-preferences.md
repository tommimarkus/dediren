# Dediren Layout Preferences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a stable JSON `layout_preferences` surface that configures Dediren-owned layout intent and maps it to existing ELK Layered and Libavoid options without exposing raw ELK option names.

**Architecture:** `model.schema.v1` may carry per-view layout preferences under `plugins.generic-graph.views[]`; the `generic-graph` projection copies them into `layout-request.schema.v1`. The Java ELK helper treats layout preferences as optional Dediren intent, applies defaults when absent, and translates symbolic values to ELK Layered and Libavoid configuration internally.

**Tech Stack:** Rust workspace with Serde and JSON Schema contracts; Java 25 toolchain emitting Java 21 bytecode; Eclipse ELK Layered and ELK Libavoid; JUnit tests for the helper; ignored real-ELK CLI render tests for geometry evidence.

---

## Scope

This plan implements the first coherent slice from
`docs/superpowers/specs/2026-05-15-dediren-elk-libavoid-layout-routing-strategy.md`:

- add `layout_preferences` to public JSON contracts;
- project source-view preferences into layout requests;
- map stable Dediren vocabulary to hardcoded ELK/Libavoid options;
- document the user-facing JSON;
- bump the product/plugin version from `0.9.1` to `0.10.0`.

This plan does not add numeric override fields, route-quality diagnostics, a new
layout-quality-policy schema, polyline routing, or a second router. Endpoint
merging gets `off`, `local`, and `auto`; `local` and `auto` both use the current
relationship-type threshold in this slice.

## File Structure

- Modify `schemas/layout-request.schema.json`: add optional
  `layout_preferences` using Dediren-owned names.
- Modify `schemas/model.schema.json`: add optional source view
  `layout_preferences` with the same shape.
- Modify `crates/dediren-contracts/src/lib.rs`: add Rust contract structs/enums
  and optional fields on `LayoutRequest` and `GenericGraphView`.
- Modify `crates/dediren-contracts/tests/contract_roundtrip.rs`: add round-trip
  tests for layout preferences.
- Modify `crates/dediren-contracts/tests/schema_contracts.rs`: add schema valid
  and invalid tests for layout preferences.
- Modify `crates/dediren-plugin-generic-graph/src/main.rs`: project selected
  view preferences into layout requests.
- Modify `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`:
  add projection coverage.
- Modify `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/JsonContracts.java`:
  add Java request records for layout preferences.
- Modify `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`:
  validate and apply preferences to Layered and Libavoid configuration.
- Modify `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java`:
  add JSON parsing coverage.
- Modify `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java`:
  add invalid preference envelope coverage.
- Modify `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`:
  add helper option mapping and endpoint merge policy coverage.
- Modify `README.md`: document `layout_preferences` under ELK Runtime and update
  bundle version examples.
- Modify `Cargo.toml`, `Cargo.lock`, and `fixtures/plugins/*.manifest.json`:
  bump version surfaces to `0.10.0`.

## JSON Contract

The first public shape is:

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

Accepted values:

- `direction`: `right`, `left`, `down`, `up`
- `density`: `compact`, `readable`, `spacious`
- `wrapping`: `auto`, `off`, `multi-edge`
- `routing.style`: `orthogonal`
- `routing.profile`: `compact`, `readable`, `spacious`
- `routing.endpoint_merging`: `off`, `local`, `auto`

All fields are optional. Missing `layout_preferences` means current behavior.

### Preference Mapping

| JSON field | Default | ELK/Libavoid mapping |
| --- | --- | --- |
| `direction` | `right` | `CoreOptions.DIRECTION` |
| `density=compact` | current spacing | node `60`, edge-node `32`, edge-edge `40`, port `32`, group padding `24` |
| `density=readable` | opt-in | node `72`, edge-node `48`, edge-edge `48`, port `40`, group padding `32` |
| `density=spacious` | opt-in | node `96`, edge-node `64`, edge-edge `64`, port `48`, group padding `40` |
| `wrapping=auto` | current grouped behavior | grouped root uses `WrappingStrategy.MULTI_EDGE`; flat root has no wrapping override |
| `wrapping=off` | opt-in | do not set `LayeredOptions.WRAPPING_STRATEGY` |
| `wrapping=multi-edge` | opt-in | set `LayeredOptions.WRAPPING_STRATEGY` to `MULTI_EDGE` |
| `routing.profile=compact` | current Libavoid values | segment `50`, shape buffer `16`, ideal nudge `16` |
| `routing.profile=readable` | opt-in | segment `60`, shape buffer `24`, ideal nudge `24` |
| `routing.profile=spacious` | opt-in | segment `80`, shape buffer `32`, ideal nudge `32` |
| `routing.endpoint_merging=off` | opt-in | no generated shared endpoint hints |
| `routing.endpoint_merging=local` | current threshold | relationship-type keyed merge when count is at least `3` |
| `routing.endpoint_merging=auto` | default | same as `local` in this slice |

## Task 1: Add Contract Types And Schema Surface

**Files:**
- Modify: `crates/dediren-contracts/src/lib.rs`
- Modify: `schemas/layout-request.schema.json`
- Modify: `schemas/model.schema.json`
- Modify: `crates/dediren-contracts/tests/contract_roundtrip.rs`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`

- [ ] **Step 1: Write failing Rust round-trip tests**

In `crates/dediren-contracts/tests/contract_roundtrip.rs`, extend the top import
from `dediren_contracts` with these names:

```rust
    LayoutDensity, LayoutDirection, LayoutEndpointMerging, LayoutPreferences,
    LayoutRoutingPreferences, LayoutRoutingProfile, LayoutRoutingStyle, LayoutWrapping,
```

Append these tests after `layout_request_roundtrips()`:

```rust
#[test]
fn layout_request_preferences_roundtrip() {
    let request: LayoutRequest = serde_json::from_str(
        r#"{
          "layout_request_schema_version": "layout-request.schema.v1",
          "view_id": "main",
          "nodes": [],
          "edges": [],
          "groups": [],
          "labels": [],
          "constraints": [],
          "layout_preferences": {
            "direction": "down",
            "density": "readable",
            "wrapping": "off",
            "routing": {
              "style": "orthogonal",
              "profile": "spacious",
              "endpoint_merging": "off"
            }
          }
        }"#,
    )
    .unwrap();

    let preferences = request.layout_preferences.unwrap();
    assert_eq!(Some(LayoutDirection::Down), preferences.direction);
    assert_eq!(Some(LayoutDensity::Readable), preferences.density);
    assert_eq!(Some(LayoutWrapping::Off), preferences.wrapping);
    let routing = preferences.routing.unwrap();
    assert_eq!(Some(LayoutRoutingStyle::Orthogonal), routing.style);
    assert_eq!(Some(LayoutRoutingProfile::Spacious), routing.profile);
    assert_eq!(Some(LayoutEndpointMerging::Off), routing.endpoint_merging);
}

#[test]
fn generic_graph_view_layout_preferences_roundtrip() {
    let data: GenericGraphPluginData = serde_json::from_str(
        r#"{
          "views": [
            {
              "id": "main",
              "label": "Main",
              "nodes": ["api"],
              "relationships": [],
              "layout_preferences": {
                "direction": "right",
                "density": "compact",
                "wrapping": "auto",
                "routing": {
                  "style": "orthogonal",
                  "profile": "readable",
                  "endpoint_merging": "local"
                }
              }
            }
          ]
        }"#,
    )
    .unwrap();

    let preferences = data.views[0].layout_preferences.as_ref().unwrap();
    assert_eq!(Some(LayoutDirection::Right), preferences.direction);
    assert_eq!(Some(LayoutDensity::Compact), preferences.density);
    assert_eq!(Some(LayoutWrapping::Auto), preferences.wrapping);
    let routing = preferences.routing.as_ref().unwrap();
    assert_eq!(Some(LayoutRoutingStyle::Orthogonal), routing.style);
    assert_eq!(Some(LayoutRoutingProfile::Readable), routing.profile);
    assert_eq!(Some(LayoutEndpointMerging::Local), routing.endpoint_merging);
}
```

- [ ] **Step 2: Write failing schema tests**

Append this test after `layout_contracts_match_schemas()` in
`crates/dediren-contracts/tests/schema_contracts.rs`:

```rust
#[test]
fn layout_preferences_match_schemas() {
    assert_json_valid(
        "schemas/layout-request.schema.json",
        json!({
            "layout_request_schema_version": "layout-request.schema.v1",
            "view_id": "main",
            "nodes": [],
            "edges": [],
            "groups": [],
            "labels": [],
            "constraints": [],
            "layout_preferences": {
                "direction": "down",
                "density": "readable",
                "wrapping": "off",
                "routing": {
                    "style": "orthogonal",
                    "profile": "spacious",
                    "endpoint_merging": "off"
                }
            }
        }),
    );

    assert_json_valid(
        "schemas/model.schema.json",
        json!({
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
                            "layout_preferences": {
                                "direction": "right",
                                "density": "compact",
                                "wrapping": "auto",
                                "routing": {
                                    "style": "orthogonal",
                                    "profile": "readable",
                                    "endpoint_merging": "local"
                                }
                            }
                        }
                    ]
                }
            }
        }),
    );

    assert_json_invalid(
        "schemas/layout-request.schema.json",
        json!({
            "layout_request_schema_version": "layout-request.schema.v1",
            "view_id": "main",
            "nodes": [],
            "edges": [],
            "groups": [],
            "labels": [],
            "constraints": [],
            "layout_preferences": {
                "org.eclipse.elk.layered.mergeEdges": true
            }
        }),
        "raw ELK option passthrough",
    );
}
```

- [ ] **Step 3: Run contract tests to verify they fail**

Run:

```bash
cargo test -p dediren-contracts --test contract_roundtrip layout_request_preferences_roundtrip
cargo test -p dediren-contracts --test schema_contracts layout_preferences_match_schemas
```

Expected: the first command fails because the Rust preference types and fields
do not exist; the second fails because the schemas reject `layout_preferences`.

- [ ] **Step 4: Add Rust contract types**

In `crates/dediren-contracts/src/lib.rs`, add this block after
`GenericGraphViewGroupRole`:

```rust
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LayoutPreferences {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub direction: Option<LayoutDirection>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub density: Option<LayoutDensity>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub wrapping: Option<LayoutWrapping>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub routing: Option<LayoutRoutingPreferences>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LayoutDirection {
    Right,
    Left,
    Down,
    Up,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LayoutDensity {
    Compact,
    Readable,
    Spacious,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LayoutWrapping {
    Auto,
    Off,
    MultiEdge,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LayoutRoutingPreferences {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub style: Option<LayoutRoutingStyle>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub profile: Option<LayoutRoutingProfile>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub endpoint_merging: Option<LayoutEndpointMerging>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LayoutRoutingStyle {
    Orthogonal,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LayoutRoutingProfile {
    Compact,
    Readable,
    Spacious,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LayoutEndpointMerging {
    Off,
    Local,
    Auto,
}
```

- [ ] **Step 5: Add preference fields to Rust contract structs**

In `GenericGraphView`, add the field after `relationships`:

```rust
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub layout_preferences: Option<LayoutPreferences>,
```

In `LayoutRequest`, add the field after `constraints`:

```rust
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub layout_preferences: Option<LayoutPreferences>,
```

Update existing `LayoutRequest` initializers in tests and production code by
adding:

```rust
        layout_preferences: None,
```

- [ ] **Step 6: Add schema definitions**

In `schemas/layout-request.schema.json`, add this property after
`constraints`:

```json
    "layout_preferences": { "$ref": "#/$defs/layoutPreferences" }
```

Add these `$defs` entries before the closing `}` of `$defs`:

```json
    "layoutPreferences": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "direction": { "enum": ["right", "left", "down", "up"] },
        "density": { "enum": ["compact", "readable", "spacious"] },
        "wrapping": { "enum": ["auto", "off", "multi-edge"] },
        "routing": { "$ref": "#/$defs/layoutRoutingPreferences" }
      }
    },
    "layoutRoutingPreferences": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "style": { "enum": ["orthogonal"] },
        "profile": { "enum": ["compact", "readable", "spacious"] },
        "endpoint_merging": { "enum": ["off", "local", "auto"] }
      }
    }
```

In `schemas/model.schema.json`, add this property to
`genericGraphView.properties`:

```json
        "layout_preferences": { "$ref": "#/$defs/layoutPreferences" },
```

Add the same `layoutPreferences` and `layoutRoutingPreferences` definitions to
`schemas/model.schema.json` under `$defs`.

- [ ] **Step 7: Run contract tests to verify they pass**

Run:

```bash
cargo test -p dediren-contracts --test contract_roundtrip layout_request_preferences_roundtrip
cargo test -p dediren-contracts --test contract_roundtrip generic_graph_view_layout_preferences_roundtrip
cargo test -p dediren-contracts --test schema_contracts layout_preferences_match_schemas
```

Expected: all three tests pass.

- [ ] **Step 8: Commit contract surface**

Run:

```bash
git add schemas/layout-request.schema.json schemas/model.schema.json crates/dediren-contracts/src/lib.rs crates/dediren-contracts/tests/contract_roundtrip.rs crates/dediren-contracts/tests/schema_contracts.rs
git commit -m "feat: add layout preferences contract"
```

## Task 2: Project Source View Preferences Into Layout Requests

**Files:**
- Modify: `crates/dediren-plugin-generic-graph/src/main.rs`
- Modify: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`

- [ ] **Step 1: Write failing projection test**

Append this test after `generic_graph_projects_basic_view()` in
`crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`:

```rust
#[test]
fn generic_graph_projects_layout_preferences() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "nodes": [
            { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
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
                        "label": "Main",
                        "nodes": ["client", "api"],
                        "relationships": ["client-calls-api"],
                        "layout_preferences": {
                            "direction": "down",
                            "density": "readable",
                            "wrapping": "off",
                            "routing": {
                                "style": "orthogonal",
                                "profile": "spacious",
                                "endpoint_merging": "off"
                            }
                        }
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
    assert_eq!(
        data["layout_preferences"],
        serde_json::json!({
            "direction": "down",
            "density": "readable",
            "wrapping": "off",
            "routing": {
                "style": "orthogonal",
                "profile": "spacious",
                "endpoint_merging": "off"
            }
        })
    );
}
```

- [ ] **Step 2: Run projection test to verify it fails**

Run:

```bash
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin generic_graph_projects_layout_preferences
```

Expected: FAIL because projected layout requests omit `layout_preferences`.

- [ ] **Step 3: Copy preferences during projection**

In `crates/dediren-plugin-generic-graph/src/main.rs`, update the `LayoutRequest`
initializer by adding:

```rust
        layout_preferences: selected_view.layout_preferences.clone(),
```

- [ ] **Step 4: Run projection test to verify it passes**

Run:

```bash
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin generic_graph_projects_layout_preferences
```

Expected: PASS.

- [ ] **Step 5: Commit projection**

Run:

```bash
git add crates/dediren-plugin-generic-graph/src/main.rs crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs
git commit -m "feat: project layout preferences"
```

## Task 3: Teach The Java Helper Contract About Preferences

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/JsonContracts.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java`

- [ ] **Step 1: Write failing JSON contract test**

In `JsonContractsTest.java`, add this test after
`readsLayoutRequestAndWritesLayoutResultEnvelope()`:

```java
    @Test
    void readsLayoutPreferences() throws Exception {
        String json = """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "labels": [],
              "constraints": [],
              "layout_preferences": {
                "direction": "down",
                "density": "readable",
                "wrapping": "off",
                "routing": {
                  "style": "orthogonal",
                  "profile": "spacious",
                  "endpoint_merging": "off"
                }
              }
            }
            """;

        JsonContracts.LayoutRequest request =
            mapper.readValue(json, JsonContracts.LayoutRequest.class);

        assertEquals("down", request.layout_preferences().direction());
        assertEquals("readable", request.layout_preferences().density());
        assertEquals("off", request.layout_preferences().wrapping());
        assertEquals("orthogonal", request.layout_preferences().routing().style());
        assertEquals("spacious", request.layout_preferences().routing().profile());
        assertEquals("off", request.layout_preferences().routing().endpoint_merging());
    }
```

- [ ] **Step 2: Write failing invalid-value envelope test**

In `MainTest.java`, add this test after
`requestMissingRequiredNodeLabelReturnsErrorEnvelope()`:

```java
    @Test
    void requestWithUnknownLayoutPreferenceReturnsErrorEnvelope() throws Exception {
        String request = """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "labels": [],
              "constraints": [],
              "layout_preferences": {
                "direction": "diagonal"
              }
            }
            """;
        ByteArrayInputStream stdin =
            new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = Main.run(stdin, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        String text = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(3, exitCode);
        EnvelopeAssertions.errorEnvelope(text, "DEDIREN_ELK_LAYOUT_FAILED");
        assertTrue(text.contains("$.layout_preferences.direction"));
    }
```

- [ ] **Step 3: Run Java tests to verify they fail**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: FAIL because Java records do not contain `layout_preferences`.

- [ ] **Step 4: Add Java preference records**

In `JsonContracts.java`, change `LayoutRequest` to include
`LayoutPreferences layout_preferences` after `constraints`:

```java
    record LayoutRequest(
        String layout_request_schema_version,
        String view_id,
        List<LayoutNode> nodes,
        List<LayoutEdge> edges,
        List<LayoutGroup> groups,
        List<LayoutLabel> labels,
        List<LayoutConstraint> constraints,
        LayoutPreferences layout_preferences) {
    }
```

Add these records after `LayoutConstraint`:

```java
    record LayoutPreferences(
        String direction,
        String density,
        String wrapping,
        LayoutRoutingPreferences routing) {
    }

    record LayoutRoutingPreferences(
        String style,
        String profile,
        String endpoint_merging) {
    }
```

Update every `new JsonContracts.LayoutRequest(...)` call in Java tests to pass
one additional final argument:

```java
            null);
```

- [ ] **Step 5: Add Java preference validation**

In `ElkLayoutEngine.java`, add these helper methods near `validate(...)`:

```java
    private static void validateLayoutPreferences(
        JsonContracts.LayoutPreferences preferences,
        String path) {
        if (preferences == null) {
            return;
        }
        requireOneOf(
            preferences.direction(),
            path + ".direction",
            "right",
            "left",
            "down",
            "up");
        requireOneOf(
            preferences.density(),
            path + ".density",
            "compact",
            "readable",
            "spacious");
        requireOneOf(
            preferences.wrapping(),
            path + ".wrapping",
            "auto",
            "off",
            "multi-edge");
        validateRoutingPreferences(preferences.routing(), path + ".routing");
    }

    private static void validateRoutingPreferences(
        JsonContracts.LayoutRoutingPreferences routing,
        String path) {
        if (routing == null) {
            return;
        }
        requireOneOf(routing.style(), path + ".style", "orthogonal");
        requireOneOf(
            routing.profile(),
            path + ".profile",
            "compact",
            "readable",
            "spacious");
        requireOneOf(
            routing.endpoint_merging(),
            path + ".endpoint_merging",
            "off",
            "local",
            "auto");
    }

    private static void requireOneOf(String value, String path, String... accepted) {
        if (value == null) {
            return;
        }
        for (String candidate : accepted) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException(path + " has unsupported value: " + value);
    }
```

Call it from `validate(...)` after `requireNonNull(request.constraints(), "$.constraints");`:

```java
        validateLayoutPreferences(request.layout_preferences(), "$.layout_preferences");
```

- [ ] **Step 6: Run Java contract tests to verify they pass**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: PASS.

- [ ] **Step 7: Commit Java contract parsing**

Run:

```bash
git add crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/JsonContracts.java crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/JsonContractsTest.java crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/MainTest.java
git commit -m "feat: parse layout preferences in elk helper"
```

## Task 4: Apply Preferences To ELK And Libavoid Options

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`

- [ ] **Step 1: Write failing Libavoid profile test**

In `ElkLayoutEngineTest.java`, add this test after
`libavoidRootUsesDocumentedAestheticRoutingOptions()`:

```java
    @Test
    void libavoidRootUsesReadableRoutingProfile() {
        JsonContracts.LayoutPreferences preferences = new JsonContracts.LayoutPreferences(
            null,
            null,
            null,
            new JsonContracts.LayoutRoutingPreferences("orthogonal", "readable", null));

        ElkNode root = ElkLayoutEngine.configuredLibavoidRoot(preferences);

        assertEquals(60.0, root.getProperty(LibavoidOptions.SEGMENT_PENALTY));
        assertEquals(24.0, root.getProperty(LibavoidOptions.IDEAL_NUDGING_DISTANCE));
        assertEquals(24.0, root.getProperty(LibavoidOptions.SHAPE_BUFFER_DISTANCE));
    }
```

- [ ] **Step 2: Write failing endpoint-merge-off test**

In `ElkLayoutEngineTest.java`, add this test after the existing fan-out merge
test that asserts `shared_source_junction`:

```java
    @Test
    void endpointMergingOffSuppressesSharedSourceHints() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("source", "Source", "source", 160.0, 80.0),
                new JsonContracts.LayoutNode("target-a", "Target A", "target-a", 160.0, 80.0),
                new JsonContracts.LayoutNode("target-b", "Target B", "target-b", 160.0, 80.0),
                new JsonContracts.LayoutNode("target-c", "Target C", "target-c", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("edge-a", "source", "target-a", "realizes", "edge-a", "Realization"),
                new JsonContracts.LayoutEdge("edge-b", "source", "target-b", "realizes", "edge-b", "Realization"),
                new JsonContracts.LayoutEdge("edge-c", "source", "target-c", "realizes", "edge-c", "Realization")),
            List.of(),
            List.of(),
            List.of(),
            new JsonContracts.LayoutPreferences(
                null,
                null,
                null,
                new JsonContracts.LayoutRoutingPreferences("orthogonal", null, "off")));

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        for (JsonContracts.LaidOutEdge edge : result.edges()) {
            assertEquals(
                List.of(),
                edge.routing_hints(),
                "endpoint_merging=off must suppress shared source hints");
        }
    }
```

- [ ] **Step 3: Run Java tests to verify they fail**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: FAIL because `configuredLibavoidRoot(LayoutPreferences)` does not
exist and endpoint merging ignores preferences.

- [ ] **Step 4: Add preference-derived constants and helpers**

In `ElkLayoutEngine.java`, keep existing current constants as compact defaults
and add readable/spacious constants near them:

```java
    private static final double READABLE_NODE_SPACING = 72.0;
    private static final double READABLE_EDGE_NODE_SPACING = 48.0;
    private static final double READABLE_EDGE_EDGE_SPACING = 48.0;
    private static final double READABLE_PORT_PORT_SPACING = 40.0;
    private static final double READABLE_GROUP_PADDING = 32.0;
    private static final double SPACIOUS_NODE_SPACING = 96.0;
    private static final double SPACIOUS_EDGE_NODE_SPACING = 64.0;
    private static final double SPACIOUS_EDGE_EDGE_SPACING = 64.0;
    private static final double SPACIOUS_PORT_PORT_SPACING = 48.0;
    private static final double SPACIOUS_GROUP_PADDING = 40.0;
    private static final double READABLE_LIBAVOID_SEGMENT_PENALTY = 60.0;
    private static final double READABLE_LIBAVOID_IDEAL_NUDGING_DISTANCE = 24.0;
    private static final double READABLE_LIBAVOID_SHAPE_BUFFER_DISTANCE = 24.0;
    private static final double SPACIOUS_LIBAVOID_SEGMENT_PENALTY = 80.0;
    private static final double SPACIOUS_LIBAVOID_IDEAL_NUDGING_DISTANCE = 32.0;
    private static final double SPACIOUS_LIBAVOID_SHAPE_BUFFER_DISTANCE = 32.0;
```

Add these helper methods near `configuredLibavoidRoot()`:

```java
    static ElkNode configuredLibavoidRoot(JsonContracts.LayoutPreferences preferences) {
        ElkNode root = ElkGraphUtil.createGraph();
        configureLibavoidRoot(root, preferences);
        return root;
    }

    private static String density(JsonContracts.LayoutPreferences preferences) {
        return preferences == null || preferences.density() == null
            ? "compact"
            : preferences.density();
    }

    private static String routingProfile(JsonContracts.LayoutPreferences preferences) {
        if (preferences == null
            || preferences.routing() == null
            || preferences.routing().profile() == null) {
            return "compact";
        }
        return preferences.routing().profile();
    }

    private static String endpointMerging(JsonContracts.LayoutPreferences preferences) {
        if (preferences == null
            || preferences.routing() == null
            || preferences.routing().endpoint_merging() == null) {
            return "auto";
        }
        return preferences.routing().endpoint_merging();
    }

    private static boolean endpointMergingEnabled(JsonContracts.LayoutPreferences preferences) {
        return !"off".equals(endpointMerging(preferences));
    }

    private static Direction preferredDirection(JsonContracts.LayoutPreferences preferences) {
        if (preferences == null || preferences.direction() == null) {
            return Direction.RIGHT;
        }
        return switch (preferences.direction()) {
            case "left" -> Direction.LEFT;
            case "down" -> Direction.DOWN;
            case "up" -> Direction.UP;
            default -> Direction.RIGHT;
        };
    }
```

- [ ] **Step 5: Apply density to Layered spacing**

Change `configureLayeredRoot` to accept preferences:

```java
    private static void configureLayeredRoot(
        ElkNode root,
        Direction direction,
        JsonContracts.LayoutPreferences preferences) {
```

At the top of that method, calculate spacing:

```java
        double nodeSpacing = switch (density(preferences)) {
            case "readable" -> READABLE_NODE_SPACING;
            case "spacious" -> SPACIOUS_NODE_SPACING;
            default -> NODE_SPACING;
        };
        double edgeNodeSpacing = switch (density(preferences)) {
            case "readable" -> READABLE_EDGE_NODE_SPACING;
            case "spacious" -> SPACIOUS_EDGE_NODE_SPACING;
            default -> EDGE_NODE_SPACING;
        };
        double edgeEdgeSpacing = switch (density(preferences)) {
            case "readable" -> READABLE_EDGE_EDGE_SPACING;
            case "spacious" -> SPACIOUS_EDGE_EDGE_SPACING;
            default -> EDGE_EDGE_SPACING;
        };
        double portPortSpacing = switch (density(preferences)) {
            case "readable" -> READABLE_PORT_PORT_SPACING;
            case "spacious" -> SPACIOUS_PORT_PORT_SPACING;
            default -> PORT_PORT_SPACING;
        };
```

Replace spacing property assignments in that method so they use
`nodeSpacing`, `edgeNodeSpacing`, `edgeEdgeSpacing`, and `portPortSpacing`.

Update all calls:

```java
        configureLayeredRoot(root, preferredDirection(request.layout_preferences()), request.layout_preferences());
```

For group-local directions:

```java
            configureLayeredRoot(elkGroup, groupDirection, request.layout_preferences());
```

- [ ] **Step 6: Apply wrapping preferences**

In grouped layout, replace the unconditional
`root.setProperty(LayeredOptions.WRAPPING_STRATEGY, WrappingStrategy.MULTI_EDGE);`
with:

```java
        if (request.layout_preferences() == null
            || request.layout_preferences().wrapping() == null
            || "auto".equals(request.layout_preferences().wrapping())
            || "multi-edge".equals(request.layout_preferences().wrapping())) {
            root.setProperty(LayeredOptions.WRAPPING_STRATEGY, WrappingStrategy.MULTI_EDGE);
        }
```

This keeps current behavior for missing preferences and for `auto`.

- [ ] **Step 7: Apply Libavoid routing profiles**

Change `configuredLibavoidRoot()` to delegate to the new overload:

```java
    static ElkNode configuredLibavoidRoot() {
        return configuredLibavoidRoot(null);
    }
```

Change `configureLibavoidRoot` to accept preferences:

```java
    private static void configureLibavoidRoot(
        ElkNode root,
        JsonContracts.LayoutPreferences preferences) {
```

Set Libavoid values from the profile:

```java
        root.setProperty(LibavoidOptions.SEGMENT_PENALTY, switch (routingProfile(preferences)) {
            case "readable" -> READABLE_LIBAVOID_SEGMENT_PENALTY;
            case "spacious" -> SPACIOUS_LIBAVOID_SEGMENT_PENALTY;
            default -> LIBAVOID_SEGMENT_PENALTY;
        });
        root.setProperty(LibavoidOptions.IDEAL_NUDGING_DISTANCE, switch (routingProfile(preferences)) {
            case "readable" -> READABLE_LIBAVOID_IDEAL_NUDGING_DISTANCE;
            case "spacious" -> SPACIOUS_LIBAVOID_IDEAL_NUDGING_DISTANCE;
            default -> LIBAVOID_IDEAL_NUDGING_DISTANCE;
        });
        root.setProperty(LibavoidOptions.SHAPE_BUFFER_DISTANCE, switch (routingProfile(preferences)) {
            case "readable" -> READABLE_LIBAVOID_SHAPE_BUFFER_DISTANCE;
            case "spacious" -> SPACIOUS_LIBAVOID_SHAPE_BUFFER_DISTANCE;
            default -> LIBAVOID_SHAPE_BUFFER_DISTANCE;
        });
```

Pass preferences from `routeWithLibavoid(...)` by adding a
`JsonContracts.LayoutPreferences preferences` parameter to the private overload
and calling:

```java
        ElkNode root = configuredLibavoidRoot(preferences);
```

The public `routeWithLibavoid(List<LayoutEdge>, List<LaidOutNode>)` test helper
continues to call the private overload with `null`.

- [ ] **Step 8: Apply endpoint merging preference**

Add a `JsonContracts.LayoutPreferences preferences` parameter to
`flatEdgeEndpointMerges(...)` and `groupedEdgeEndpointMerges(...)`.

At the top of each method, add:

```java
        if (!endpointMergingEnabled(preferences)) {
            return emptyEndpointMerges(edges);
        }
```

Update callers to pass `request.layout_preferences()`.

- [ ] **Step 9: Run Java tests to verify they pass**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: PASS.

- [ ] **Step 10: Commit helper mapping**

Run:

```bash
git add crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java
git commit -m "feat: map layout preferences to elk options"
```

## Task 5: Add End-To-End CLI Coverage For Preferences

**Files:**
- Modify: `crates/dediren-cli/tests/cli_project.rs`
- Modify: `crates/dediren-cli/tests/cli_layout.rs`

- [ ] **Step 1: Add CLI projection test**

In `crates/dediren-cli/tests/cli_project.rs`, add this test after the first
layout-request projection test:

```rust
#[test]
fn project_layout_request_preserves_layout_preferences() {
    let temp = assert_fs::TempDir::new().unwrap();
    let source = temp.child("source.json");
    source
        .write_str(
            &serde_json::to_string_pretty(&serde_json::json!({
                "model_schema_version": "model.schema.v1",
                "nodes": [
                    { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
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
                                "label": "Main",
                                "nodes": ["client", "api"],
                                "relationships": ["client-calls-api"],
                                "layout_preferences": {
                                    "direction": "down",
                                    "density": "readable",
                                    "wrapping": "off",
                                    "routing": {
                                        "style": "orthogonal",
                                        "profile": "spacious",
                                        "endpoint_merging": "off"
                                    }
                                }
                            }
                        ]
                    }
                }
            }))
            .unwrap(),
        )
        .unwrap();

    let assert = common::dediren_command()
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
        .arg(source.path())
        .assert()
        .success();

    let output = assert.get_output().stdout.clone();
    let data = ok_data(&output);
    assert_eq!(
        data["layout_preferences"]["routing"]["endpoint_merging"],
        "off"
    );
}
```

If `cli_project.rs` does not already import `assert_fs::prelude::*`, add:

```rust
use assert_fs::prelude::*;
```

- [ ] **Step 2: Add fixture-backed CLI layout test**

In `crates/dediren-cli/tests/cli_layout.rs`, add this test near the other
fixture-runtime layout tests:

```rust
#[test]
fn layout_accepts_layout_preferences_with_fixture_runtime() {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = temp.child("request.json");
    request
        .write_str(
            &serde_json::to_string_pretty(&serde_json::json!({
                "layout_request_schema_version": "layout-request.schema.v1",
                "view_id": "main",
                "nodes": [],
                "edges": [],
                "groups": [],
                "labels": [],
                "constraints": [],
                "layout_preferences": {
                    "direction": "down",
                    "density": "readable",
                    "wrapping": "off",
                    "routing": {
                        "style": "orthogonal",
                        "profile": "readable",
                        "endpoint_merging": "off"
                    }
                }
            }))
            .unwrap(),
        )
        .unwrap();

    let mut cmd = common::dediren_command();
    cmd.env(
        "DEDIREN_PLUGIN_ELK_LAYOUT",
        plugin_binary("dediren-plugin-elk-layout"),
    )
    .env(
        "DEDIREN_ELK_RESULT_FIXTURE",
        workspace_file("fixtures/layout-result/basic.json"),
    )
    .arg("layout")
    .arg("--plugin")
    .arg("elk-layout")
    .arg("--input")
    .arg(request.path())
    .assert()
    .success()
    .stdout(predicate::str::contains("\"layout_result_schema_version\""));
}
```

If `cli_layout.rs` does not already import these, add:

```rust
use assert_fs::prelude::*;
use predicates::prelude::*;
```

- [ ] **Step 3: Run CLI tests to verify they fail or pass as expected**

Run:

```bash
cargo test -p dediren --test cli_project project_layout_request_preserves_layout_preferences
cargo test -p dediren --test cli_layout layout_accepts_layout_preferences_with_fixture_runtime
```

Expected: both pass after Tasks 1-4. If either fails, fix only the projection or
fixture-runtime parsing path named by the failure.

- [ ] **Step 4: Commit CLI coverage**

Run:

```bash
git add crates/dediren-cli/tests/cli_project.rs crates/dediren-cli/tests/cli_layout.rs
git commit -m "test: cover layout preferences in cli"
```

## Task 6: Document Preferences And Bump Version

**Files:**
- Modify: `README.md`
- Modify: `Cargo.toml`
- Modify: `Cargo.lock`
- Modify: `fixtures/plugins/archimate-oef.manifest.json`
- Modify: `fixtures/plugins/elk-layout.manifest.json`
- Modify: `fixtures/plugins/generic-graph.manifest.json`
- Modify: `fixtures/plugins/svg-render.manifest.json`

- [ ] **Step 1: Update README examples and behavior text**

In `README.md`, replace version `0.9.1` bundle examples with `0.10.0`.

Under the ELK Runtime section, after the paragraph that explains
`relationship_type`, add:

````markdown
Layout requests may also carry optional `layout_preferences`. Source documents
can place the same object on `plugins.generic-graph.views[]`; the
`generic-graph` projection copies it into the layout request. The values are
Dediren layout intent, not raw ELK options:

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
values are `auto`, `local`, and `off`. Missing preferences keep the helper's
default behavior.
````

- [ ] **Step 2: Bump workspace and manifest versions**

Change `Cargo.toml`:

```toml
version = "0.10.0"
```

Change every `fixtures/plugins/*.manifest.json` version to:

```json
  "version": "0.10.0",
```

Run:

```bash
cargo update --workspace --offline
cargo metadata --locked --format-version=1 >/tmp/dediren-metadata.json
```

Expected: `Cargo.lock` records first-party package versions as `0.10.0`.

- [ ] **Step 3: Run release-surface tests**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts first_party_plugin_manifest_versions_match_workspace_version
cargo test -p dediren-contracts --test schema_contracts live_release_surfaces_match_workspace_version
```

Expected: both tests pass.

- [ ] **Step 4: Commit docs and version surfaces**

Run:

```bash
git add README.md Cargo.toml Cargo.lock fixtures/plugins/archimate-oef.manifest.json fixtures/plugins/elk-layout.manifest.json fixtures/plugins/generic-graph.manifest.json fixtures/plugins/svg-render.manifest.json
git commit -m "feat: document layout preferences"
```

## Task 7: Full Verification And Audit Gates

**Files:**
- No planned source edits.
- Generated artifacts under `.test-output/`, `.cache/`, `dist/`, and Java
  `build/` remain ignored.

- [ ] **Step 1: Run formatting check**

Run:

```bash
cargo fmt --all -- --check
```

Expected: PASS.

- [ ] **Step 2: Run contract/schema tests**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts
cargo test -p dediren-contracts --test contract_roundtrip
```

Expected: PASS.

- [ ] **Step 3: Run projection and CLI tests**

Run:

```bash
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin
cargo test -p dediren --test cli_project
cargo test -p dediren --test cli_layout
```

Expected: PASS.

- [ ] **Step 4: Run Java helper tests and rebuild helper**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: PASS and `build/install/dediren-elk-layout-java/bin/dediren-elk-layout-java`
is rebuilt.

- [ ] **Step 5: Run real ELK route-quality lanes**

Run:

```bash
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin real_elk_plugin_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test -p dediren --test cli_layout real_elk_layout_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test -p dediren --test cli_layout real_elk_layout_validates_grouped_cross_group_route -- --ignored --exact --test-threads=1
cargo test -p dediren --test real_elk_render -- --ignored --test-threads=1
```

Expected: PASS. Inspect `.test-output/renders/real-elk/` if a test writes a new
SVG and fails on route-quality assertions.

- [ ] **Step 6: Run workspace tests**

Run:

```bash
cargo test --workspace --locked
```

Expected: PASS.

- [ ] **Step 7: Run plan audit gates**

Because this plan changes contracts, plugin runtime behavior, and ELK runtime
behavior, invoke these audit skills before calling the work complete.

Invoke `souroldgeezer-audit:test-quality-audit` with this prompt:

```text
Deep audit the layout_preferences implementation. Scope: Rust contracts,
generic-graph projection, Java ELK helper tests, CLI fixture tests, and real ELK
render lane. Focus on brittle assertions, missing contract cases, false
confidence around fixture mode, and ignored real-helper coverage.
```

Invoke `souroldgeezer-audit:devsecops-audit` with this prompt:

```text
Quick audit the layout_preferences implementation. Scope: public schemas,
plugin process boundary, Java helper dependency posture, version surfaces, and
README. Focus on unexpected dependency changes, raw backend option passthrough,
runtime boundary regressions, and release artifact surfaces.
```

Expected: no block findings. Fix block findings, then rerun the affected checks.
Record any accepted warn/info findings in the handoff.

- [ ] **Step 8: Final status**

Run:

```bash
git status --short --branch
```

Expected: clean branch after commits, except ignored generated artifacts.

## Self-Review

Spec coverage:

- Current hardcoded options are mapped to `direction`, `density`, `wrapping`,
  `routing.profile`, and `routing.endpoint_merging` in Tasks 1, 3, and 4.
- The plan avoids raw ELK passthrough by schema validation and a schema invalid
  test in Task 1.
- Source view preferences are projected into layout requests in Task 2.
- ELK Layered and Libavoid remain the authorities; Task 4 only maps symbolic
  preferences to backend options.
- Version and README requirements are covered in Task 6.
- Real render verification and audit gates are covered in Task 7.

Placeholder scan:

- No task uses placeholder instructions.
- Numeric values and accepted enum values are listed explicitly.
- Commands include expected outcomes.

Type consistency:

- Rust and Java names use `layout_preferences`, `routing`, `direction`,
  `density`, `wrapping`, `style`, `profile`, and `endpoint_merging`.
- JSON enum values are kebab-case strings where needed, matching Serde
  `rename_all = "kebab-case"`.
- `LayoutPreferences` is shared between `GenericGraphView` and `LayoutRequest`
  in Rust, and represented in Java request records.
