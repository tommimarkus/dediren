mod common;

fn workspace_file(path: &str) -> String {
    format!("{}/{}", env!("CARGO_MANIFEST_DIR"), path)
        .replace("/crates/dediren-plugin-archimate-oef-export/", "/")
}

#[test]
fn oef_export_plugin_reports_capabilities() {
    let mut cmd = common::plugin_command();
    let stdout = cmd
        .arg("capabilities")
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let capabilities = common::stdout_json(&stdout);
    assert_eq!(capabilities["id"], "archimate-oef");
    let capability_ids: Vec<&str> = capabilities["capabilities"]
        .as_array()
        .expect("capabilities should be an array")
        .iter()
        .map(|capability| {
            capability
                .as_str()
                .expect("capability id should be a string")
        })
        .collect();
    assert!(capability_ids.contains(&"export"));
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

    let mut cmd = common::plugin_command();
    let output = cmd
        .arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    assert_eq!(data["artifact_kind"], "archimate-oef+xml");

    let xml = data["content"].as_str().unwrap();
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

    let mut cmd = common::plugin_command();
    let output = cmd
        .arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(&output, "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
    assert_diagnostic_message_contains(&envelope, "TechnologyNode");
}

#[test]
fn oef_export_plugin_rejects_unknown_archimate_relationship_type_with_error_envelope() {
    let mut input = export_input();
    input["source"]["relationships"][0]["type"] = serde_json::json!("ConnectsTo");

    let mut cmd = common::plugin_command();
    let output = cmd
        .arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope =
        common::assert_error_code(&output, "DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED");
    assert_diagnostic_message_contains(&envelope, "ConnectsTo");
}

#[test]
fn oef_export_plugin_rejects_invalid_archimate_relationship_endpoint_with_error_envelope() {
    let mut input = export_input();
    input["source"]["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    input["source"]["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    input["source"]["relationships"][0]["type"] = serde_json::json!("Realization");

    let mut cmd = common::plugin_command();
    let output = cmd
        .arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(
        &output,
        "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
    );
    assert_diagnostic_message_contains(&envelope, "ApplicationService");
    assert_diagnostic_message_contains(&envelope, "Realization");
    assert_diagnostic_message_contains(&envelope, "ApplicationComponent");
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

fn assert_diagnostic_message_contains(envelope: &serde_json::Value, expected: &str) {
    let messages: Vec<&str> = envelope["diagnostics"]
        .as_array()
        .expect("diagnostics should be an array")
        .iter()
        .map(|diagnostic| {
            diagnostic["message"]
                .as_str()
                .expect("diagnostic message should be a string")
        })
        .collect();

    assert!(
        messages.iter().any(|message| message.contains(expected)),
        "expected diagnostic message to contain {expected:?}, got {messages:?}"
    );
}
