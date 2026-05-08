# ArchiMate OEF Export Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first export plugin, `archimate-oef`, so `dediren export` can combine a semantic source document and a generated layout result into a machine-readable ArchiMate OEF XML artifact.

**Architecture:** Keep the core contract-first and plugin-neutral. The CLI owns orchestration, schema-shaped request assembly, and plugin process execution; the new first-party plugin owns ArchiMate/OEF vocabulary checks, identifier mapping, and XML serialization. Source documents still contain no authored absolute geometry; generated layout result coordinates are the only source for OEF view geometry.

**Tech Stack:** Rust 1.93, Cargo workspace, serde/serde_json, jsonschema, quick-xml for XML emission, roxmltree for OEF fixture assertions, assert_cmd for executable plugin and CLI tests.

---

## Slice Scope

This slice implements a narrow OEF export path:

- Add public export request, policy, and result contracts.
- Add a first-party `dediren-plugin-archimate-oef-export` executable plugin.
- Add `dediren export --plugin archimate-oef --policy <file> --source <file> --layout <file>`.
- Export exactly one materialized OEF `<view>` from one source document and one layout result.
- Support a small ArchiMate type allowlist needed by the fixture.
- Validate OEF structure locally with parser-based tests: child order, global identifier uniqueness, view-node/view-connection `xsi:type`, and geometry sourced from layout result.

Out of scope for this slice:

- Full ArchiMate Appendix B relationship matrix validation.
- OEF XSD validation through network or Archi.
- Existing OEF merge/update.
- Multiple views per export.
- Organization trees, property definitions, metadata catalogs, and authority/readiness properties.
- Raw XML stdout mode.

## File Structure

- Modify `Cargo.toml`: add the new plugin crate and XML dependencies.
- Modify `crates/dediren-contracts/src/lib.rs`: add export constants and Rust structs.
- Modify `crates/dediren-contracts/tests/schema_contracts.rs`: add schema and fixture coverage.
- Modify `crates/dediren-cli/src/main.rs`: add `export` command orchestration.
- Modify `crates/dediren-cli/tests/plugin_compat.rs`: include the new plugin in capability coverage.
- Create `crates/dediren-cli/tests/cli_export.rs`: CLI-level export test.
- Create `crates/dediren-plugin-archimate-oef-export/Cargo.toml`.
- Create `crates/dediren-plugin-archimate-oef-export/src/main.rs`: plugin command, input parsing, OEF generation.
- Create `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`: direct plugin tests.
- Create `schemas/export-request.schema.json`.
- Create `schemas/export-result.schema.json`.
- Create `schemas/oef-export-policy.schema.json`.
- Create `fixtures/source/valid-archimate-oef.json`.
- Create `fixtures/layout-result/archimate-oef-basic.json`.
- Create `fixtures/export-policy/default-oef.json`.
- Create `fixtures/export/oef-basic.xml`.
- Create `fixtures/plugins/archimate-oef.manifest.json`.
- Modify `README.md`: document the new export command.

---

### Task 1: Export Contracts And Schemas

**Files:**
- Modify: `Cargo.toml`
- Modify: `crates/dediren-contracts/src/lib.rs`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`
- Create: `schemas/export-request.schema.json`
- Create: `schemas/export-result.schema.json`
- Create: `schemas/oef-export-policy.schema.json`
- Create: `fixtures/export-policy/default-oef.json`
- Create: `fixtures/source/valid-archimate-oef.json`
- Create: `fixtures/layout-result/archimate-oef-basic.json`

- [ ] **Step 1: Add failing schema tests**

Append these tests to `crates/dediren-contracts/tests/schema_contracts.rs`:

```rust
#[test]
fn export_contracts_match_schemas() {
    assert_valid(
        "schemas/oef-export-policy.schema.json",
        "fixtures/export-policy/default-oef.json",
    );

    assert_json_valid(
        "schemas/export-request.schema.json",
        json!({
            "export_request_schema_version": "export-request.schema.v1",
            "source": serde_json::from_str::<serde_json::Value>(
                &std::fs::read_to_string(workspace_file("fixtures/source/valid-archimate-oef.json")).unwrap()
            ).unwrap(),
            "layout_result": serde_json::from_str::<serde_json::Value>(
                &std::fs::read_to_string(workspace_file("fixtures/layout-result/archimate-oef-basic.json")).unwrap()
            ).unwrap(),
            "policy": serde_json::from_str::<serde_json::Value>(
                &std::fs::read_to_string(workspace_file("fixtures/export-policy/default-oef.json")).unwrap()
            ).unwrap()
        }),
    );

    assert_json_valid(
        "schemas/export-result.schema.json",
        json!({
            "export_result_schema_version": "export-result.schema.v1",
            "artifact_kind": "archimate-oef+xml",
            "content": "<model/>"
        }),
    );
}

#[test]
fn archimate_oef_fixtures_match_existing_contracts() {
    assert_valid("schemas/model.schema.json", "fixtures/source/valid-archimate-oef.json");
    assert_valid(
        "schemas/layout-result.schema.json",
        "fixtures/layout-result/archimate-oef-basic.json",
    );
}
```

Also extend the `all_public_schemas_compile` path list with:

```rust
"schemas/export-request.schema.json",
"schemas/export-result.schema.json",
"schemas/oef-export-policy.schema.json",
```

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts
```

Expected: FAIL because the export schema and fixture files do not exist.

- [ ] **Step 2: Add workspace dependencies**

Modify the root `Cargo.toml` workspace dependency block:

```toml
quick-xml = "0.38"
roxmltree = "0.20"
```

Add the new workspace member in the `members` array:

```toml
"crates/dediren-plugin-archimate-oef-export",
```

- [ ] **Step 3: Add export contract structs**

Append these constants near the existing schema-version constants in `crates/dediren-contracts/src/lib.rs`:

```rust
pub const EXPORT_REQUEST_SCHEMA_VERSION: &str = "export-request.schema.v1";
pub const EXPORT_RESULT_SCHEMA_VERSION: &str = "export-result.schema.v1";
pub const OEF_EXPORT_POLICY_SCHEMA_VERSION: &str = "oef-export-policy.schema.v1";
```

Append these structs after `RenderResult`:

```rust
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OefExportPolicy {
    pub oef_export_policy_schema_version: String,
    pub model_identifier: String,
    pub model_name: String,
    pub view_identifier: String,
    pub view_name: String,
    pub viewpoint: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OefExportInput {
    pub export_request_schema_version: String,
    pub source: SourceDocument,
    pub layout_result: LayoutResult,
    pub policy: OefExportPolicy,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ExportResult {
    pub export_result_schema_version: String,
    pub artifact_kind: String,
    pub content: String,
}
```

- [ ] **Step 4: Add export schemas**

Create `schemas/oef-export-policy.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://dediren.dev/schemas/oef-export-policy.schema.json",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "oef_export_policy_schema_version",
    "model_identifier",
    "model_name",
    "view_identifier",
    "view_name",
    "viewpoint"
  ],
  "properties": {
    "oef_export_policy_schema_version": { "const": "oef-export-policy.schema.v1" },
    "model_identifier": { "type": "string", "minLength": 1 },
    "model_name": { "type": "string", "minLength": 1 },
    "view_identifier": { "type": "string", "minLength": 1 },
    "view_name": { "type": "string", "minLength": 1 },
    "viewpoint": { "type": "string", "minLength": 1 }
  }
}
```

Create `schemas/export-result.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://dediren.dev/schemas/export-result.schema.json",
  "type": "object",
  "additionalProperties": false,
  "required": ["export_result_schema_version", "artifact_kind", "content"],
  "properties": {
    "export_result_schema_version": { "const": "export-result.schema.v1" },
    "artifact_kind": { "const": "archimate-oef+xml" },
    "content": { "type": "string" }
  }
}
```

Create `schemas/export-request.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://dediren.dev/schemas/export-request.schema.json",
  "type": "object",
  "additionalProperties": false,
  "required": ["export_request_schema_version", "source", "layout_result", "policy"],
  "properties": {
    "export_request_schema_version": { "const": "export-request.schema.v1" },
    "source": {
      "type": "object",
      "required": ["model_schema_version"],
      "additionalProperties": true
    },
    "layout_result": {
      "type": "object",
      "required": ["layout_result_schema_version"],
      "additionalProperties": true
    },
    "policy": {
      "type": "object",
      "required": ["oef_export_policy_schema_version"],
      "additionalProperties": true
    }
  }
}
```

- [ ] **Step 5: Add export fixtures**

Create `fixtures/export-policy/default-oef.json`:

```json
{
  "oef_export_policy_schema_version": "oef-export-policy.schema.v1",
  "model_identifier": "id-dediren-oef-basic-model",
  "model_name": "Dediren OEF Basic",
  "view_identifier": "id-view-main",
  "view_name": "Main",
  "viewpoint": "Application Cooperation"
}
```

Create `fixtures/source/valid-archimate-oef.json`:

```json
{
  "model_schema_version": "model.schema.v1",
  "required_plugins": [
    {
      "id": "generic-graph",
      "version": "0.1.0"
    },
    {
      "id": "archimate-oef",
      "version": "0.1.0"
    }
  ],
  "nodes": [
    {
      "id": "orders-component",
      "type": "ApplicationComponent",
      "label": "Orders Component",
      "properties": {}
    },
    {
      "id": "orders-service",
      "type": "ApplicationService",
      "label": "Orders Service",
      "properties": {}
    }
  ],
  "relationships": [
    {
      "id": "orders-realizes-service",
      "type": "Realization",
      "source": "orders-component",
      "target": "orders-service",
      "label": "realizes",
      "properties": {}
    }
  ],
  "plugins": {
    "generic-graph": {
      "views": [
        {
          "id": "main",
          "label": "Main",
          "nodes": ["orders-component", "orders-service"],
          "relationships": ["orders-realizes-service"]
        }
      ]
    }
  }
}
```

Create `fixtures/layout-result/archimate-oef-basic.json`:

```json
{
  "layout_result_schema_version": "layout-result.schema.v1",
  "view_id": "main",
  "nodes": [
    {
      "id": "orders-component",
      "source_id": "orders-component",
      "projection_id": "orders-component",
      "x": 40,
      "y": 40,
      "width": 180,
      "height": 80,
      "label": "Orders Component"
    },
    {
      "id": "orders-service",
      "source_id": "orders-service",
      "projection_id": "orders-service",
      "x": 300,
      "y": 40,
      "width": 180,
      "height": 80,
      "label": "Orders Service"
    }
  ],
  "edges": [
    {
      "id": "orders-realizes-service",
      "source": "orders-component",
      "target": "orders-service",
      "source_id": "orders-realizes-service",
      "projection_id": "orders-realizes-service",
      "points": [
        {
          "x": 220,
          "y": 80
        },
        {
          "x": 300,
          "y": 80
        }
      ],
      "label": "realizes"
    }
  ],
  "groups": [],
  "warnings": []
}
```

- [ ] **Step 6: Run contract tests**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts
```

Expected: PASS.

- [ ] **Step 7: Commit contracts**

```bash
git add Cargo.toml crates/dediren-contracts schemas fixtures/source/valid-archimate-oef.json fixtures/layout-result/archimate-oef-basic.json fixtures/export-policy/default-oef.json
git commit -m "Add OEF export contracts"
```

---

### Task 2: First-Party OEF Export Plugin

**Files:**
- Create: `crates/dediren-plugin-archimate-oef-export/Cargo.toml`
- Create: `crates/dediren-plugin-archimate-oef-export/src/main.rs`
- Create: `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`
- Create: `fixtures/export/oef-basic.xml`
- Create: `fixtures/plugins/archimate-oef.manifest.json`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`

- [ ] **Step 1: Add failing plugin tests**

Create `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`:

```rust
use assert_cmd::Command;
use predicates::prelude::*;

fn workspace_file(path: &str) -> String {
    format!("{}/{}", env!("CARGO_MANIFEST_DIR"), path)
        .replace("/crates/dediren-plugin-archimate-oef-export/", "/")
}

#[test]
fn oef_export_plugin_reports_capabilities() {
    let mut cmd = Command::cargo_bin("dediren-plugin-archimate-oef-export").unwrap();
    cmd.arg("capabilities")
        .assert()
        .success()
        .stdout(predicate::str::contains("\"id\":\"archimate-oef\""))
        .stdout(predicate::str::contains("\"export\""));
}

#[test]
fn oef_export_plugin_outputs_model_valid_oef_xml() {
    let input = serde_json::json!({
        "export_request_schema_version": "export-request.schema.v1",
        "source": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/source/valid-archimate-oef.json")).unwrap()
        ).unwrap(),
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/archimate-oef-basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/export-policy/default-oef.json")).unwrap()
        ).unwrap()
    });

    let mut cmd = Command::cargo_bin("dediren-plugin-archimate-oef-export").unwrap();
    let output = cmd
        .arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let envelope: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(envelope["status"], "ok");
    assert_eq!(envelope["data"]["artifact_kind"], "archimate-oef+xml");

    let xml = envelope["data"]["content"].as_str().unwrap();
    let expected = std::fs::read_to_string(workspace_file("fixtures/export/oef-basic.xml")).unwrap();
    assert_eq!(xml, expected);

    let doc = roxmltree::Document::parse(xml).unwrap();
    let root = doc.root_element();
    assert_eq!(root.tag_name().name(), "model");
    assert_eq!(root.attribute("identifier"), Some("id-dediren-oef-basic-model"));

    let child_names: Vec<_> = root
        .children()
        .filter(|node| node.is_element())
        .map(|node| node.tag_name().name().to_string())
        .collect();
    assert_eq!(child_names, vec!["name", "elements", "relationships", "views"]);

    let mut identifiers = std::collections::HashSet::new();
    for node in doc.descendants().filter(|node| node.is_element()) {
        if let Some(identifier) = node.attribute("identifier") {
            assert!(
                identifiers.insert(identifier.to_string()),
                "duplicate OEF identifier: {identifier}"
            );
        }
    }

    let view_nodes: Vec<_> = doc
        .descendants()
        .filter(|node| node.has_tag_name("node"))
        .collect();
    assert_eq!(view_nodes.len(), 2);
    for node in view_nodes {
        assert_eq!(node.attribute(("http://www.w3.org/2001/XMLSchema-instance", "type")), Some("Element"));
        assert!(node.attribute("elementRef").is_some());
        assert!(node.attribute("x").is_some());
        assert!(node.attribute("y").is_some());
        assert!(node.attribute("w").is_some());
        assert!(node.attribute("h").is_some());
    }

    let connection = doc
        .descendants()
        .find(|node| node.has_tag_name("connection"))
        .unwrap();
    assert_eq!(
        connection.attribute(("http://www.w3.org/2001/XMLSchema-instance", "type")),
        Some("Relationship")
    );
    assert_eq!(connection.attribute("relationshipRef"), Some("id-rel-orders-realizes-service"));
    assert_eq!(connection.attribute("source"), Some("id-vn-main-orders-component"));
    assert_eq!(connection.attribute("target"), Some("id-vn-main-orders-service"));
}
```

Run:

```bash
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin
```

Expected: FAIL because the crate does not exist.

- [ ] **Step 2: Add plugin manifest schema coverage**

Modify `bundled_plugin_manifests_match_schema` in `crates/dediren-contracts/tests/schema_contracts.rs` to include:

```rust
"fixtures/plugins/archimate-oef.manifest.json",
```

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts bundled_plugin_manifests_match_schema
```

Expected: FAIL because the manifest does not exist.

- [ ] **Step 3: Add plugin crate manifest**

Create `crates/dediren-plugin-archimate-oef-export/Cargo.toml`:

```toml
[package]
name = "dediren-plugin-archimate-oef-export"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
rust-version.workspace = true

[[bin]]
name = "dediren-plugin-archimate-oef-export"
path = "src/main.rs"

[dependencies]
anyhow.workspace = true
dediren-contracts = { path = "../dediren-contracts" }
quick-xml.workspace = true
serde.workspace = true
serde_json.workspace = true

[dev-dependencies]
assert_cmd.workspace = true
predicates.workspace = true
roxmltree.workspace = true
```

Create `fixtures/plugins/archimate-oef.manifest.json`:

```json
{
  "plugin_manifest_schema_version": "plugin-manifest.schema.v1",
  "id": "archimate-oef",
  "version": "0.1.0",
  "executable": "dediren-plugin-archimate-oef-export",
  "capabilities": ["export"]
}
```

- [ ] **Step 4: Add expected OEF fixture**

Create `fixtures/export/oef-basic.xml`:

```xml
<model xmlns="http://www.opengroup.org/xsd/archimate/3.0/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.opengroup.org/xsd/archimate/3.0/ http://www.opengroup.org/xsd/archimate/3.1/archimate3_Model.xsd" identifier="id-dediren-oef-basic-model"><name xml:lang="en">Dediren OEF Basic</name><elements><element identifier="id-el-orders-component" xsi:type="ApplicationComponent"><name xml:lang="en">Orders Component</name></element><element identifier="id-el-orders-service" xsi:type="ApplicationService"><name xml:lang="en">Orders Service</name></element></elements><relationships><relationship identifier="id-rel-orders-realizes-service" source="id-el-orders-component" target="id-el-orders-service" xsi:type="Realization"><name xml:lang="en">realizes</name></relationship></relationships><views><diagrams><view identifier="id-view-main" xsi:type="Diagram" viewpoint="Application Cooperation"><name xml:lang="en">Main</name><node identifier="id-vn-main-orders-component" xsi:type="Element" elementRef="id-el-orders-component" x="40" y="40" w="180" h="80"/><node identifier="id-vn-main-orders-service" xsi:type="Element" elementRef="id-el-orders-service" x="300" y="40" w="180" h="80"/><connection identifier="id-vc-main-orders-realizes-service" xsi:type="Relationship" relationshipRef="id-rel-orders-realizes-service" source="id-vn-main-orders-component" target="id-vn-main-orders-service"><bendpoint x="220" y="80"/><bendpoint x="300" y="80"/></connection></view></diagrams></views></model>
```

- [ ] **Step 5: Implement OEF exporter**

Create `crates/dediren-plugin-archimate-oef-export/src/main.rs`:

```rust
use std::collections::{BTreeMap, HashSet};
use std::io::{Cursor, Read};

use anyhow::{bail, Context};
use dediren_contracts::{
    CommandEnvelope, ExportResult, OefExportInput, EXPORT_RESULT_SCHEMA_VERSION,
    PLUGIN_PROTOCOL_VERSION,
};
use quick_xml::events::{BytesEnd, BytesStart, BytesText, Event};
use quick_xml::Writer;

const OEF_NS: &str = "http://www.opengroup.org/xsd/archimate/3.0/";
const XSI_NS: &str = "http://www.w3.org/2001/XMLSchema-instance";
const OEF_SCHEMA: &str =
    "http://www.opengroup.org/xsd/archimate/3.1/archimate3_Model.xsd";

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("capabilities") => {
            println!(
                "{}",
                serde_json::json!({
                    "plugin_protocol_version": PLUGIN_PROTOCOL_VERSION,
                    "id": "archimate-oef",
                    "capabilities": ["export"],
                    "runtime": {
                        "artifact_kind": "archimate-oef+xml",
                        "archimate_version": "3.2",
                        "oef_namespace": OEF_NS
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
    let request: OefExportInput = serde_json::from_str(&input)?;
    let content = build_oef(&request)?;
    let result = ExportResult {
        export_result_schema_version: EXPORT_RESULT_SCHEMA_VERSION.to_string(),
        artifact_kind: "archimate-oef+xml".to_string(),
        content,
    };
    println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
    Ok(())
}

fn build_oef(request: &OefExportInput) -> anyhow::Result<String> {
    validate_archimate_types(request)?;

    let mut ids = IdentifierMap::default();
    let element_ids: BTreeMap<_, _> = request
        .source
        .nodes
        .iter()
        .map(|node| (node.id.clone(), ids.oef_id("el", &node.id)))
        .collect();
    let relationship_ids: BTreeMap<_, _> = request
        .source
        .relationships
        .iter()
        .map(|relationship| (relationship.id.clone(), ids.oef_id("rel", &relationship.id)))
        .collect();
    let view_node_ids: BTreeMap<_, _> = request
        .layout_result
        .nodes
        .iter()
        .map(|node| {
            (
                node.id.clone(),
                ids.oef_id(&format!("vn-{}", request.layout_result.view_id), &node.id),
            )
        })
        .collect();
    let view_connection_ids: BTreeMap<_, _> = request
        .layout_result
        .edges
        .iter()
        .map(|edge| {
            (
                edge.id.clone(),
                ids.oef_id(&format!("vc-{}", request.layout_result.view_id), &edge.id),
            )
        })
        .collect();

    let mut writer = Writer::new(Cursor::new(Vec::new()));

    let mut model = BytesStart::new("model");
    model.push_attribute(("xmlns", OEF_NS));
    model.push_attribute(("xmlns:xsi", XSI_NS));
    let schema_location = format!("{OEF_NS} {OEF_SCHEMA}");
    model.push_attribute(("xsi:schemaLocation", schema_location.as_str()));
    model.push_attribute(("identifier", request.policy.model_identifier.as_str()));
    writer.write_event(Event::Start(model))?;

    write_text_element(&mut writer, "name", &request.policy.model_name)?;

    writer.write_event(Event::Start(BytesStart::new("elements")))?;
    for node in &request.source.nodes {
        let mut element = BytesStart::new("element");
        element.push_attribute(("identifier", element_ids[&node.id].as_str()));
        element.push_attribute(("xsi:type", node.node_type.as_str()));
        writer.write_event(Event::Start(element))?;
        write_text_element(&mut writer, "name", &node.label)?;
        writer.write_event(Event::End(BytesEnd::new("element")))?;
    }
    writer.write_event(Event::End(BytesEnd::new("elements")))?;

    writer.write_event(Event::Start(BytesStart::new("relationships")))?;
    for relationship in &request.source.relationships {
        let mut rel = BytesStart::new("relationship");
        rel.push_attribute(("identifier", relationship_ids[&relationship.id].as_str()));
        rel.push_attribute((
            "source",
            element_ids
                .get(&relationship.source)
                .with_context(|| format!("relationship {} has missing source", relationship.id))?
                .as_str(),
        ));
        rel.push_attribute((
            "target",
            element_ids
                .get(&relationship.target)
                .with_context(|| format!("relationship {} has missing target", relationship.id))?
                .as_str(),
        ));
        rel.push_attribute(("xsi:type", relationship.relationship_type.as_str()));
        writer.write_event(Event::Start(rel))?;
        write_text_element(&mut writer, "name", &relationship.label)?;
        writer.write_event(Event::End(BytesEnd::new("relationship")))?;
    }
    writer.write_event(Event::End(BytesEnd::new("relationships")))?;

    writer.write_event(Event::Start(BytesStart::new("views")))?;
    writer.write_event(Event::Start(BytesStart::new("diagrams")))?;
    let mut view = BytesStart::new("view");
    view.push_attribute(("identifier", request.policy.view_identifier.as_str()));
    view.push_attribute(("xsi:type", "Diagram"));
    view.push_attribute(("viewpoint", request.policy.viewpoint.as_str()));
    writer.write_event(Event::Start(view))?;
    write_text_element(&mut writer, "name", &request.policy.view_name)?;

    for node in &request.layout_result.nodes {
        let element_ref = element_ids
            .get(&node.source_id)
            .with_context(|| format!("layout node {} has missing source", node.id))?;
        let mut view_node = BytesStart::new("node");
        let x = format_number(node.x);
        let y = format_number(node.y);
        let width = format_number(node.width);
        let height = format_number(node.height);
        view_node.push_attribute(("identifier", view_node_ids[&node.id].as_str()));
        view_node.push_attribute(("xsi:type", "Element"));
        view_node.push_attribute(("elementRef", element_ref.as_str()));
        view_node.push_attribute(("x", x.as_str()));
        view_node.push_attribute(("y", y.as_str()));
        view_node.push_attribute(("w", width.as_str()));
        view_node.push_attribute(("h", height.as_str()));
        writer.write_event(Event::Empty(view_node))?;
    }

    for edge in &request.layout_result.edges {
        let relationship_ref = relationship_ids
            .get(&edge.source_id)
            .with_context(|| format!("layout edge {} has missing source relationship", edge.id))?;
        let mut connection = BytesStart::new("connection");
        connection.push_attribute(("identifier", view_connection_ids[&edge.id].as_str()));
        connection.push_attribute(("xsi:type", "Relationship"));
        connection.push_attribute(("relationshipRef", relationship_ref.as_str()));
        connection.push_attribute((
            "source",
            view_node_ids
                .get(&edge.source)
                .with_context(|| format!("layout edge {} has missing source node", edge.id))?
                .as_str(),
        ));
        connection.push_attribute((
            "target",
            view_node_ids
                .get(&edge.target)
                .with_context(|| format!("layout edge {} has missing target node", edge.id))?
                .as_str(),
        ));
        writer.write_event(Event::Start(connection))?;
        for point in &edge.points {
            let mut bendpoint = BytesStart::new("bendpoint");
            let x = format_number(point.x);
            let y = format_number(point.y);
            bendpoint.push_attribute(("x", x.as_str()));
            bendpoint.push_attribute(("y", y.as_str()));
            writer.write_event(Event::Empty(bendpoint))?;
        }
        writer.write_event(Event::End(BytesEnd::new("connection")))?;
    }

    writer.write_event(Event::End(BytesEnd::new("view")))?;
    writer.write_event(Event::End(BytesEnd::new("diagrams")))?;
    writer.write_event(Event::End(BytesEnd::new("views")))?;
    writer.write_event(Event::End(BytesEnd::new("model")))?;

    Ok(String::from_utf8(writer.into_inner().into_inner())?)
}

fn write_text_element(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    name: &str,
    text: &str,
) -> anyhow::Result<()> {
    let mut start = BytesStart::new(name);
    start.push_attribute(("xml:lang", "en"));
    writer.write_event(Event::Start(start))?;
    writer.write_event(Event::Text(BytesText::new(text)))?;
    writer.write_event(Event::End(BytesEnd::new(name)))?;
    Ok(())
}

fn validate_archimate_types(request: &OefExportInput) -> anyhow::Result<()> {
    let element_types = [
        "BusinessProcess",
        "BusinessService",
        "ApplicationComponent",
        "ApplicationService",
        "ApplicationInterface",
        "DataObject",
        "Node",
        "TechnologyService",
    ];
    let relationship_types = [
        "Composition",
        "Aggregation",
        "Assignment",
        "Realization",
        "Serving",
        "Access",
        "Flow",
        "Triggering",
        "Association",
    ];
    for node in &request.source.nodes {
        if !element_types.contains(&node.node_type.as_str()) {
            bail!("unsupported ArchiMate element type: {}", node.node_type);
        }
    }
    for relationship in &request.source.relationships {
        if !relationship_types.contains(&relationship.relationship_type.as_str()) {
            bail!(
                "unsupported ArchiMate relationship type: {}",
                relationship.relationship_type
            );
        }
    }
    Ok(())
}

fn format_number(value: f64) -> String {
    if value.fract() == 0.0 {
        format!("{}", value as i64)
    } else {
        format!("{value}")
    }
}

#[derive(Default)]
struct IdentifierMap {
    used: HashSet<String>,
}

impl IdentifierMap {
    fn oef_id(&mut self, prefix: &str, value: &str) -> String {
        let base = format!("id-{}-{}", slug(prefix), slug(value));
        if self.used.insert(base.clone()) {
            return base;
        }

        for suffix in 2.. {
            let candidate = format!("{base}-{suffix}");
            if self.used.insert(candidate.clone()) {
                return candidate;
            }
        }
        unreachable!("suffix loop must return")
    }
}

fn slug(value: &str) -> String {
    let mut result = String::new();
    let mut previous_dash = false;
    for character in value.chars() {
        if character.is_ascii_alphanumeric() {
            result.push(character.to_ascii_lowercase());
            previous_dash = false;
        } else if !previous_dash {
            result.push('-');
            previous_dash = true;
        }
    }
    let trimmed = result.trim_matches('-').to_string();
    if trimmed.is_empty() {
        "item".to_string()
    } else {
        trimmed
    }
}
```

- [ ] **Step 6: Run plugin tests**

Run:

```bash
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin
```

Expected: PASS.

- [ ] **Step 7: Run manifest schema test**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts bundled_plugin_manifests_match_schema
```

Expected: PASS.

- [ ] **Step 8: Commit plugin**

```bash
git add Cargo.toml Cargo.lock crates/dediren-plugin-archimate-oef-export fixtures/export fixtures/plugins/archimate-oef.manifest.json crates/dediren-contracts/tests/schema_contracts.rs
git commit -m "Add ArchiMate OEF export plugin"
```

---

### Task 3: CLI Export Command

**Files:**
- Modify: `crates/dediren-cli/src/main.rs`
- Modify: `crates/dediren-cli/tests/plugin_compat.rs`
- Create: `crates/dediren-cli/tests/cli_export.rs`

- [ ] **Step 1: Add failing CLI export test**

Create `crates/dediren-cli/tests/cli_export.rs`:

```rust
use assert_cmd::Command;
use predicates::prelude::*;

fn workspace_file(path: &str) -> String {
    format!("{}/{}", env!("CARGO_MANIFEST_DIR"), path).replace("/crates/dediren-cli/", "/")
}

fn workspace_binary(package: &str, binary: &str) -> String {
    let status = std::process::Command::new("cargo")
        .args(["build", "-p", package, "--bin", binary])
        .status()
        .unwrap();
    assert!(status.success());
    workspace_file(&format!("target/debug/{binary}"))
}

#[test]
fn export_invokes_archimate_oef_plugin() {
    let plugin = workspace_binary(
        "dediren-plugin-archimate-oef-export",
        "dediren-plugin-archimate-oef-export",
    );
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.env("DEDIREN_PLUGIN_ARCHIMATE_OEF", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args([
            "export",
            "--plugin",
            "archimate-oef",
            "--policy",
            &workspace_file("fixtures/export-policy/default-oef.json"),
            "--source",
            &workspace_file("fixtures/source/valid-archimate-oef.json"),
            "--layout",
            &workspace_file("fixtures/layout-result/archimate-oef-basic.json"),
        ])
        .assert()
        .success()
        .stdout(predicate::str::contains("\"export_result_schema_version\""))
        .stdout(predicate::str::contains("archimate-oef+xml"))
        .stdout(predicate::str::contains("<model"));
}
```

Run:

```bash
cargo test -p dediren --test cli_export
```

Expected: FAIL because the `export` subcommand is missing.

- [ ] **Step 2: Extend plugin compatibility test**

In `crates/dediren-cli/tests/plugin_compat.rs`, extend the plugin list in `first_party_plugins_report_capabilities`:

```rust
(
    "dediren-plugin-archimate-oef-export",
    "dediren-plugin-archimate-oef-export",
),
```

Run:

```bash
cargo test -p dediren --test plugin_compat
```

Expected: PASS after Task 2 exists, or FAIL only until the plugin crate is available.

- [ ] **Step 3: Add CLI export command**

Modify the `Commands` enum in `crates/dediren-cli/src/main.rs`:

```rust
Export {
    #[arg(long)]
    plugin: String,
    #[arg(long)]
    policy: String,
    #[arg(long)]
    source: String,
    #[arg(long)]
    layout: String,
},
```

Add this match arm before `None`:

```rust
Some(Commands::Export {
    plugin,
    policy,
    source,
    layout,
}) => {
    let source_text = std::fs::read_to_string(source)?;
    let policy_text = std::fs::read_to_string(policy)?;
    let layout_text = std::fs::read_to_string(layout)?;

    let source_doc: dediren_contracts::SourceDocument = serde_json::from_str(&source_text)?;
    let layout_result: dediren_contracts::LayoutResult =
        dediren_core::io::parse_command_data(&layout_text)?;
    let policy: dediren_contracts::OefExportPolicy = serde_json::from_str(&policy_text)?;

    let export_input = dediren_contracts::OefExportInput {
        export_request_schema_version: dediren_contracts::EXPORT_REQUEST_SCHEMA_VERSION
            .to_string(),
        source: source_doc,
        layout_result,
        policy,
    };

    print_plugin_result(dediren_core::plugins::run_plugin(
        &plugin,
        &["export"],
        &serde_json::to_string(&export_input)?,
    ))
}
```

- [ ] **Step 4: Run CLI export tests**

Run:

```bash
cargo test -p dediren --test cli_export
cargo test -p dediren --test plugin_compat
```

Expected: PASS.

- [ ] **Step 5: Commit CLI command**

```bash
git add crates/dediren-cli
git commit -m "Add OEF export CLI command"
```

---

### Task 4: Pipeline Documentation And Export Fixture Coverage

**Files:**
- Modify: `README.md`
- Modify: `crates/dediren-cli/tests/cli_pipeline.rs`

- [ ] **Step 1: Add failing full-pipeline export assertion**

In `crates/dediren-cli/tests/cli_pipeline.rs`, add the OEF plugin binary beside the existing plugin binaries:

```rust
let oef_plugin = workspace_binary(
    "dediren-plugin-archimate-oef-export",
    "dediren-plugin-archimate-oef-export",
);
```

After the existing render assertion, add an export command using the checked-in OEF fixtures:

```rust
let export_output = Command::cargo_bin("dediren")
    .unwrap()
    .env("DEDIREN_PLUGIN_ARCHIMATE_OEF", &oef_plugin)
    .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
    .args([
        "export",
        "--plugin",
        "archimate-oef",
        "--policy",
        &workspace_file("fixtures/export-policy/default-oef.json"),
        "--source",
        &workspace_file("fixtures/source/valid-archimate-oef.json"),
        "--layout",
        &workspace_file("fixtures/layout-result/archimate-oef-basic.json"),
    ])
    .output()
    .unwrap();
assert!(export_output.status.success());

let export_envelope: serde_json::Value = serde_json::from_slice(&export_output.stdout).unwrap();
assert_eq!(export_envelope["status"], "ok");
assert_eq!(export_envelope["data"]["artifact_kind"], "archimate-oef+xml");
assert!(
    export_envelope["data"]["content"]
        .as_str()
        .unwrap()
        .contains("xsi:type=\"Diagram\"")
);
```

Run:

```bash
cargo test -p dediren --test cli_pipeline
```

Expected: PASS after Task 3; if it fails, fix the command arguments before updating docs.

- [ ] **Step 2: Update README command list**

Change the pipeline block in `README.md`:

```text
validate -> project --target layout-request -> layout -> validate-layout -> render
validate -> project --target layout-request -> layout -> validate-layout -> export
```

Add the command:

```bash
dediren export --plugin archimate-oef --policy fixtures/export-policy/default-oef.json --source fixtures/source/valid-archimate-oef.json --layout fixtures/layout-result/archimate-oef-basic.json
```

Add this note after the render output note:

```markdown
`export` returns a JSON command envelope by default. The ArchiMate OEF XML text
is in `.data.content`; this slice does not expose a raw-output mode.
```

Add this plugin note under "Plugin Lookup":

```markdown
The bundled `archimate-oef` export plugin emits ArchiMate 3.2 OEF XML from
source graph semantics plus generated layout result geometry. It does not run
external OEF XSD validation; import the XML into Archi or run an explicit schema
validator when tool-conformance evidence is required.
```

- [ ] **Step 3: Run docs-adjacent tests**

Run:

```bash
cargo test -p dediren --test cli_pipeline
```

Expected: PASS.

- [ ] **Step 4: Commit docs and pipeline test**

```bash
git add README.md crates/dediren-cli/tests/cli_pipeline.rs
git commit -m "Document OEF export pipeline"
```

---

### Task 5: Validation Gates

**Files:**
- No code files required unless validation finds defects.

- [ ] **Step 1: Format**

Run:

```bash
cargo fmt --all
```

Expected: PASS with no output that indicates changed syntax errors.

- [ ] **Step 2: Run focused tests**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin
cargo test -p dediren --test cli_export
cargo test -p dediren --test cli_pipeline
cargo test -p dediren --test plugin_compat
```

Expected: all PASS.

- [ ] **Step 3: Run full workspace tests**

Run:

```bash
cargo test --workspace
cargo check --workspace
```

Expected: all PASS.

- [ ] **Step 4: Run static hygiene scans**

Run:

```bash
git diff --check
PATTERN='TB''D|TO''DO|FIX''ME|XX''X|place''holder|implement ''later|fill ''in|similar ''to|move the ''existing|unknown ''decision|to be ''decided|raw XML stdout|PATH discovery'
rg -n --hidden --glob '!target/**' --glob '!.git/**' --glob '!Cargo.lock' --glob '!docs/superpowers/plans/**' "$PATTERN" .
```

Expected: `git diff --check` exits 0. The `rg` command exits 1 with no matches.

- [ ] **Step 5: Run OEF structural review**

Use the direct plugin test as the local architecture-design structural gate. Confirm it proves:

- `<model>` uses the ArchiMate namespace and canonical schema location.
- Direct `<model>` children appear in this order: `name`, `elements`, `relationships`, `views`.
- Every `identifier` attribute is globally unique.
- Every view `<node>` and `<connection>` carries `xsi:type`.
- Every view `<node>` uses generated layout geometry from `LayoutResult`.
- Every view `<connection>` references view-node identifiers, not model element identifiers.
- No model-root `<properties>` block is emitted.

If `xmllint` is installed and network access is intentionally available, optionally run:

```bash
xmllint --noout fixtures/export/oef-basic.xml
```

Expected optional result: XML well-formedness PASS. Do not claim OEF XSD validation unless `xmllint --schema http://www.opengroup.org/xsd/archimate/3.1/archimate3_Model.xsd <file>` or Archi import validation actually runs.

- [ ] **Step 6: Run audit gates**

Invoke `souroldgeezer-audit:test-quality-audit` in Deep mode against the OEF export tests and fixtures:

```text
Mode: Deep
Scope: crates/dediren-plugin-archimate-oef-export/tests, crates/dediren-cli/tests/cli_export.rs, crates/dediren-cli/tests/cli_pipeline.rs, crates/dediren-contracts/tests/schema_contracts.rs, fixtures/export/, fixtures/export-policy/, fixtures/source/valid-archimate-oef.json, fixtures/layout-result/archimate-oef-basic.json
Expected gate: no block findings. Warn/info findings must be fixed or recorded as accepted residual risk.
```

Invoke `souroldgeezer-audit:devsecops-audit` in Quick mode against the export boundary:

```text
Mode: Quick
Scope: new Cargo dependencies, OEF export plugin process boundary, XML generation code, fixtures, README statements, and absence of implicit executable discovery
Expected gate: no block findings. Warn/info findings must be fixed or recorded as accepted residual risk.
```

- [ ] **Step 7: Final commit if needed**

If validation fixes changed files after the earlier commits:

```bash
git status --short
git add Cargo.toml Cargo.lock README.md crates/dediren-cli crates/dediren-contracts crates/dediren-plugin-archimate-oef-export fixtures/export fixtures/export-policy fixtures/layout-result/archimate-oef-basic.json fixtures/plugins/archimate-oef.manifest.json fixtures/source/valid-archimate-oef.json schemas
git commit -m "Harden OEF export validation"
```

- [ ] **Step 8: Confirm working tree**

Run:

```bash
git status --short --branch
```

Expected: clean feature branch, with all implementation commits present.

## Self-Review Notes

- Spec coverage: this plan implements the deferred ArchiMate OEF export plugin as the next slice while preserving the first-slice rule that source data has no absolute geometry.
- Boundary check: OEF vocabulary, identifier mapping, and XML serialization live inside `dediren-plugin-archimate-oef-export`; the core remains an orchestration and process-boundary owner.
- Software-design check: this is a thin adapter with local export policy, not a shared ArchiMate core. Full relationship-matrix validation is named out of scope to avoid a speculative domain engine.
- Architecture-design check: the plan uses OEF XML child order, namespace, schema-location reference, `xsi:type` requirements, model-vs-view identity separation, generated view geometry, and global identifier uniqueness from the loaded ArchiMate/OEF reference.
- Test-quality check: tests cover contracts, plugin capabilities, exact output fixture, parser-level OEF structure, CLI invocation, and pipeline integration.
- DevSecOps check: plugin discovery remains manifest/env based with no implicit `PATH` discovery; XML is generated by `quick-xml`; no external schema fetch is claimed.
- Type consistency: the plan consistently uses `OefExportPolicy`, `OefExportInput`, `ExportResult`, `export_request_schema_version`, `export_result_schema_version`, and `archimate-oef+xml`.
