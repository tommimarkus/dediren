mod common;

use std::collections::HashSet;

#[test]
fn uml_xmi_export_plugin_reports_capabilities() {
    let mut cmd = common::plugin_command();
    let stdout = cmd
        .arg("capabilities")
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let capabilities = common::stdout_json(&stdout);
    assert_eq!(capabilities["id"], "uml-xmi");
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
    assert_eq!(capabilities["runtime"]["artifact_kind"], "uml-xmi+xml");
}

#[test]
fn uml_xmi_export_plugin_outputs_xmi() {
    let input = common::export_input();

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
    assert_eq!(data["artifact_kind"], "uml-xmi+xml");

    let xml = data["content"].as_str().unwrap();
    let expected =
        std::fs::read_to_string(common::workspace_file("fixtures/export/uml-basic.xmi")).unwrap();
    assert_eq!(xml, expected);

    let doc = roxmltree::Document::parse(xml).unwrap();
    let root = doc.root_element();
    assert_eq!(root.tag_name().name(), "XMI");
    assert!(
        xml.contains("uml:Class"),
        "export content should include UML class elements"
    );
    assert!(
        xml.contains("uml:Activity"),
        "export content should include UML activity elements"
    );
}

#[test]
fn uml_xmi_export_rejects_invalid_uml_relationship_endpoint() {
    let mut input = common::export_input();
    input["source"]["relationships"][0]["source"] = serde_json::json!("initial-submit");

    let mut cmd = common::plugin_command();
    let output = cmd
        .arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    common::assert_error_code(&output, "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
}

#[test]
fn uml_xmi_export_rejects_invalid_policy_schema() {
    let mut input = common::export_input();
    input["policy"]["xmi_version"] = serde_json::json!("3.0");

    let mut cmd = common::plugin_command();
    let output = cmd
        .arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    common::assert_error_code(&output, "DEDIREN_UML_XMI_POLICY_INVALID");
}

#[test]
fn uml_xmi_export_deduplicates_colliding_generated_ids() {
    let mut input = common::export_input();
    input["policy"]["model_identifier"] = serde_json::json!("id-class-order");
    input["source"]["nodes"]
        .as_array_mut()
        .unwrap()
        .push(serde_json::json!({
            "id": "class_order",
            "type": "Class",
            "label": "Order Copy",
            "properties": {
                "uml": {
                    "attributes": [
                        {
                            "name": "id",
                            "type": "OrderId",
                            "visibility": "public",
                            "multiplicity": "1"
                        }
                    ],
                    "operations": []
                }
            }
        }));

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
    let xml = data["content"].as_str().unwrap();
    assert!(
        xml.contains(r#"xmi:id="id-class-order-2""#),
        "expected node id colliding with the model id to receive a suffix: {xml}"
    );
    assert!(
        xml.contains(r#"xmi:id="id-class-order-3""#),
        "expected subsequent colliding class id to receive another suffix: {xml}"
    );
    assert!(
        xml.contains(r#"xmi:id="id-class-order-id-2""#),
        "expected colliding attribute id to receive a suffix: {xml}"
    );

    let doc = roxmltree::Document::parse(xml).unwrap();
    let mut seen = HashSet::new();
    for node in doc.descendants().filter(|node| node.is_element()) {
        if let Some(id) = node.attribute(("http://www.omg.org/spec/XMI/20131001", "id")) {
            assert!(
                seen.insert(id.to_string()),
                "duplicate xmi:id emitted: {id}"
            );
        }
    }
}
