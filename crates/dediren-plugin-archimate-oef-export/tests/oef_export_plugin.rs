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
fn oef_export_emits_semantic_grouping_view_node_and_ignores_layout_only_group() {
    let mut source: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(workspace_file("fixtures/source/valid-archimate-oef.json"))
            .unwrap(),
    )
    .unwrap();
    source["nodes"]
        .as_array_mut()
        .unwrap()
        .push(serde_json::json!({
            "id": "customer-domain",
            "type": "Grouping",
            "label": "Customer Domain",
            "properties": {}
        }));

    let mut layout: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(workspace_file(
            "fixtures/layout-result/archimate-oef-basic.json",
        ))
        .unwrap(),
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

    let grouping_view_node = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("node")
                && node.attribute("elementRef") == Some("id-el-customer-domain")
        })
        .expect("expected a semantic grouping view node");
    assert_eq!(grouping_view_node.attribute("x"), Some("10"));
    assert_eq!(grouping_view_node.attribute("w"), Some("520"));

    assert!(
        xml.contains("Customer Domain"),
        "expected semantic grouping label in OEF XML: {xml}"
    );
    assert!(
        !xml.contains("Visual Column"),
        "layout-only groups must not become OEF semantic view nodes: {xml}"
    );
}

#[test]
fn oef_export_emits_archimate_relationship_connector_junctions() {
    let source = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            { "id": "generic-graph", "version": "0.8.0" },
            { "id": "archimate-oef", "version": "0.8.0" }
        ],
        "nodes": [
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
            { "id": "flow-junction", "type": "AndJunction", "label": "All Targets", "properties": {} },
            { "id": "orders", "type": "ApplicationService", "label": "Orders", "properties": {} },
            { "id": "billing", "type": "ApplicationService", "label": "Billing", "properties": {} }
        ],
        "relationships": [
            { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "flow-junction", "label": "", "properties": {} },
            { "id": "junction-to-orders", "type": "Flow", "source": "flow-junction", "target": "orders", "label": "", "properties": {} },
            { "id": "junction-to-billing", "type": "Flow", "source": "flow-junction", "target": "billing", "label": "", "properties": {} }
        ],
        "plugins": {}
    });
    let layout = serde_json::json!({
        "layout_result_schema_version": "layout-result.schema.v1",
        "view_id": "main",
        "nodes": [
            { "id": "api", "source_id": "api", "projection_id": "api", "x": 20.0, "y": 80.0, "width": 160.0, "height": 80.0, "label": "API" },
            { "id": "flow-junction", "source_id": "flow-junction", "projection_id": "flow-junction", "x": 240.0, "y": 106.0, "width": 28.0, "height": 28.0, "label": "" },
            { "id": "orders", "source_id": "orders", "projection_id": "orders", "x": 340.0, "y": 40.0, "width": 160.0, "height": 80.0, "label": "Orders" },
            { "id": "billing", "source_id": "billing", "projection_id": "billing", "x": 340.0, "y": 150.0, "width": 160.0, "height": 80.0, "label": "Billing" }
        ],
        "edges": [
            { "id": "api-to-junction", "source": "api", "target": "flow-junction", "source_id": "api-to-junction", "projection_id": "api-to-junction", "points": [{ "x": 180.0, "y": 120.0 }, { "x": 240.0, "y": 120.0 }], "label": "" },
            { "id": "junction-to-orders", "source": "flow-junction", "target": "orders", "source_id": "junction-to-orders", "projection_id": "junction-to-orders", "points": [{ "x": 268.0, "y": 120.0 }, { "x": 340.0, "y": 80.0 }], "label": "" },
            { "id": "junction-to-billing", "source": "flow-junction", "target": "billing", "source_id": "junction-to-billing", "projection_id": "junction-to-billing", "points": [{ "x": 268.0, "y": 120.0 }, { "x": 340.0, "y": 190.0 }], "label": "" }
        ],
        "groups": [],
        "warnings": []
    });

    let data = export_with_source_and_layout(source, layout);
    let xml = data["content"].as_str().unwrap();
    let doc = roxmltree::Document::parse(xml).unwrap();

    let junction_element = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("element")
                && node.attribute("identifier") == Some("id-el-flow-junction")
        })
        .expect("expected an OEF element for the junction");
    assert_eq!(
        junction_element.attribute(("http://www.w3.org/2001/XMLSchema-instance", "type")),
        Some("AndJunction")
    );

    let junction_view_node = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("node") && node.attribute("elementRef") == Some("id-el-flow-junction")
        })
        .expect("expected a view node for the junction");
    assert_eq!(junction_view_node.attribute("w"), Some("28"));

    let incoming = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("connection")
                && node.attribute("relationshipRef") == Some("id-rel-api-to-junction")
        })
        .expect("expected incoming junction relationship view connection");
    assert_eq!(
        incoming.attribute("target"),
        Some("id-vn-main-flow-junction")
    );

    let outgoing = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("connection")
                && node.attribute("relationshipRef") == Some("id-rel-junction-to-orders")
        })
        .expect("expected outgoing junction relationship view connection");
    assert_eq!(
        outgoing.attribute("source"),
        Some("id-vn-main-flow-junction")
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

#[test]
fn oef_export_plugin_rejects_junction_chain_with_invalid_effective_endpoint() {
    let mut input = export_input();
    input["source"] = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            { "id": "generic-graph", "version": "0.8.0" },
            { "id": "archimate-oef", "version": "0.8.0" }
        ],
        "nodes": [
            { "id": "service", "type": "ApplicationService", "label": "Service", "properties": {} },
            { "id": "join", "type": "AndJunction", "label": "", "properties": {} },
            { "id": "split", "type": "AndJunction", "label": "", "properties": {} },
            { "id": "component", "type": "ApplicationComponent", "label": "Component", "properties": {} }
        ],
        "relationships": [
            { "id": "service-to-join", "type": "Realization", "source": "service", "target": "join", "label": "", "properties": {} },
            { "id": "join-to-split", "type": "Realization", "source": "join", "target": "split", "label": "", "properties": {} },
            { "id": "split-to-component", "type": "Realization", "source": "split", "target": "component", "label": "", "properties": {} }
        ],
        "plugins": {}
    });
    input["layout_result"] = serde_json::json!({
        "layout_result_schema_version": "layout-result.schema.v1",
        "view_id": "main",
        "nodes": [
            { "id": "service", "source_id": "service", "projection_id": "service", "x": 20.0, "y": 60.0, "width": 160.0, "height": 80.0, "label": "Service" },
            { "id": "join", "source_id": "join", "projection_id": "join", "x": 220.0, "y": 86.0, "width": 28.0, "height": 28.0, "label": "" },
            { "id": "split", "source_id": "split", "projection_id": "split", "x": 280.0, "y": 86.0, "width": 28.0, "height": 28.0, "label": "" },
            { "id": "component", "source_id": "component", "projection_id": "component", "x": 360.0, "y": 60.0, "width": 160.0, "height": 80.0, "label": "Component" }
        ],
        "edges": [
            { "id": "service-to-join", "source": "service", "target": "join", "source_id": "service-to-join", "projection_id": "service-to-join", "points": [{ "x": 180.0, "y": 100.0 }, { "x": 220.0, "y": 100.0 }], "label": "" },
            { "id": "join-to-split", "source": "join", "target": "split", "source_id": "join-to-split", "projection_id": "join-to-split", "points": [{ "x": 248.0, "y": 100.0 }, { "x": 280.0, "y": 100.0 }], "label": "" },
            { "id": "split-to-component", "source": "split", "target": "component", "source_id": "split-to-component", "projection_id": "split-to-component", "points": [{ "x": 308.0, "y": 100.0 }, { "x": 360.0, "y": 100.0 }], "label": "" }
        ],
        "groups": [],
        "warnings": []
    });

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

#[test]
fn oef_export_allows_junction_containment_relationship() {
    let source = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            { "id": "generic-graph", "version": "0.8.0" },
            { "id": "archimate-oef", "version": "0.8.0" }
        ],
        "nodes": [
            { "id": "group", "type": "Grouping", "label": "Group", "properties": {} },
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
            { "id": "junction", "type": "AndJunction", "label": "", "properties": {} },
            { "id": "orders", "type": "ApplicationService", "label": "Orders", "properties": {} }
        ],
        "relationships": [
            { "id": "group-contains-junction", "type": "Composition", "source": "group", "target": "junction", "label": "", "properties": {} },
            { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "junction", "label": "", "properties": {} },
            { "id": "junction-to-orders", "type": "Flow", "source": "junction", "target": "orders", "label": "", "properties": {} }
        ],
        "plugins": {}
    });
    let layout = serde_json::json!({
        "layout_result_schema_version": "layout-result.schema.v1",
        "view_id": "main",
        "nodes": [
            { "id": "group", "source_id": "group", "projection_id": "group", "x": 0.0, "y": 0.0, "width": 440.0, "height": 180.0, "label": "Group" },
            { "id": "api", "source_id": "api", "projection_id": "api", "x": 20.0, "y": 60.0, "width": 160.0, "height": 80.0, "label": "API" },
            { "id": "junction", "source_id": "junction", "projection_id": "junction", "x": 220.0, "y": 86.0, "width": 28.0, "height": 28.0, "label": "" },
            { "id": "orders", "source_id": "orders", "projection_id": "orders", "x": 300.0, "y": 60.0, "width": 120.0, "height": 80.0, "label": "Orders" }
        ],
        "edges": [
            { "id": "group-contains-junction", "source": "group", "target": "junction", "source_id": "group-contains-junction", "projection_id": "group-contains-junction", "points": [{ "x": 220.0, "y": 86.0 }, { "x": 220.0, "y": 86.0 }], "label": "" },
            { "id": "api-to-junction", "source": "api", "target": "junction", "source_id": "api-to-junction", "projection_id": "api-to-junction", "points": [{ "x": 180.0, "y": 100.0 }, { "x": 220.0, "y": 100.0 }], "label": "" },
            { "id": "junction-to-orders", "source": "junction", "target": "orders", "source_id": "junction-to-orders", "projection_id": "junction-to-orders", "points": [{ "x": 248.0, "y": 100.0 }, { "x": 300.0, "y": 100.0 }], "label": "" }
        ],
        "groups": [],
        "warnings": []
    });

    let data = export_with_source_and_layout(source, layout);
    let xml = data["content"].as_str().unwrap();
    assert!(
        xml.contains("id-rel-group-contains-junction"),
        "expected containment relationship in OEF XML: {xml}"
    );
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
