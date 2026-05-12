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
    let expected =
        std::fs::read_to_string(workspace_file("fixtures/export/oef-basic.xml")).unwrap();
    assert_eq!(xml, expected);

    let doc = roxmltree::Document::parse(xml).unwrap();
    let root = doc.root_element();
    assert_eq!(root.tag_name().name(), "model");
    assert_eq!(
        root.attribute("identifier"),
        Some("id-dediren-oef-basic-model")
    );

    let child_names: Vec<_> = root
        .children()
        .filter(|node| node.is_element())
        .map(|node| node.tag_name().name().to_string())
        .collect();
    assert_eq!(
        child_names,
        vec!["name", "elements", "relationships", "views"]
    );

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
        assert_eq!(
            node.attribute(("http://www.w3.org/2001/XMLSchema-instance", "type")),
            Some("Element")
        );
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
    assert_eq!(
        connection.attribute("relationshipRef"),
        Some("id-rel-orders-realizes-service")
    );
    assert_eq!(
        connection.attribute("source"),
        Some("id-vn-main-orders-component")
    );
    assert_eq!(
        connection.attribute("target"),
        Some("id-vn-main-orders-service")
    );
}

#[test]
fn oef_export_plugin_rejects_unknown_archimate_node_type_with_error_envelope() {
    let mut input = export_input();
    input["source"]["nodes"][0]["type"] = serde_json::json!("TechnologyNode");

    let mut cmd = Command::cargo_bin("dediren-plugin-archimate-oef-export").unwrap();
    cmd.arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .stdout(predicate::str::contains("\"status\":\"error\""))
        .stdout(predicate::str::contains(
            "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED",
        ))
        .stdout(predicate::str::contains("TechnologyNode"));
}

#[test]
fn oef_export_plugin_rejects_unknown_archimate_relationship_type_with_error_envelope() {
    let mut input = export_input();
    input["source"]["relationships"][0]["type"] = serde_json::json!("ConnectsTo");

    let mut cmd = Command::cargo_bin("dediren-plugin-archimate-oef-export").unwrap();
    cmd.arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .stdout(predicate::str::contains("\"status\":\"error\""))
        .stdout(predicate::str::contains(
            "DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED",
        ))
        .stdout(predicate::str::contains("ConnectsTo"));
}

#[test]
fn oef_export_plugin_rejects_invalid_archimate_relationship_endpoint_with_error_envelope() {
    let mut input = export_input();
    input["source"]["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    input["source"]["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    input["source"]["relationships"][0]["type"] = serde_json::json!("Realization");

    let mut cmd = Command::cargo_bin("dediren-plugin-archimate-oef-export").unwrap();
    cmd.arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .stdout(predicate::str::contains("\"status\":\"error\""))
        .stdout(predicate::str::contains(
            "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
        ))
        .stdout(predicate::str::contains("ApplicationService"))
        .stdout(predicate::str::contains("Realization"))
        .stdout(predicate::str::contains("ApplicationComponent"));
}

fn export_input() -> serde_json::Value {
    serde_json::json!({
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
    })
}
