mod common;

#[test]
fn generic_graph_projects_basic_view() {
    let input = std::fs::read_to_string(common::workspace_file("fixtures/source/valid-basic.json"))
        .unwrap();
    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    assert_eq!(
        data["layout_request_schema_version"],
        "layout-request.schema.v1"
    );
    assert_eq!(data["view_id"], "main");
    assert_eq!(
        data["nodes"]
            .as_array()
            .expect("nodes should be an array")
            .len(),
        2
    );
    assert_eq!(
        data["edges"]
            .as_array()
            .expect("edges should be an array")
            .len(),
        1
    );
}

#[test]
fn generic_graph_projects_rich_view_groups() {
    let input = std::fs::read_to_string(common::workspace_file(
        "fixtures/source/valid-pipeline-rich.json",
    ))
    .unwrap();
    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    let groups = data["groups"].as_array().unwrap();

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

#[test]
fn generic_graph_projects_render_metadata() {
    let input = std::fs::read_to_string(common::workspace_file(
        "fixtures/source/valid-archimate-oef.json",
    ))
    .unwrap();
    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    assert_eq!(
        data["render_metadata_schema_version"],
        "render-metadata.schema.v1"
    );
    assert_eq!(data["semantic_profile"], "archimate");
    assert_eq!(
        data["nodes"]["orders-component"]["type"],
        "ApplicationComponent"
    );
    assert_eq!(
        data["nodes"]["orders-service"]["type"],
        "ApplicationService"
    );
    assert_eq!(
        data["edges"]["orders-realizes-service"]["type"],
        "Realization"
    );
}

#[test]
fn generic_graph_validates_archimate_source_semantics() {
    let input = std::fs::read_to_string(common::workspace_file(
        "fixtures/source/valid-archimate-oef.json",
    ))
    .unwrap();
    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["validate", "--profile", "archimate"])
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    assert_eq!(
        data["semantic_validation_result_schema_version"],
        "semantic-validation-result.schema.v1"
    );
    assert_eq!(data["semantic_profile"], "archimate");
    assert_eq!(data["node_count"], 2);
    assert_eq!(data["relationship_count"], 1);
}

#[test]
fn generic_graph_rejects_unknown_archimate_node_type_for_render_metadata() {
    let mut source = archimate_source();
    source["nodes"][0]["type"] = serde_json::json!("TechnologyNode");

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&source).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(&output, "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("TechnologyNode"));
}

#[test]
fn generic_graph_rejects_unknown_archimate_relationship_type_for_render_metadata() {
    let mut source = archimate_source();
    source["relationships"][0]["type"] = serde_json::json!("ConnectsTo");

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&source).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope =
        common::assert_error_code(&output, "DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("ConnectsTo"));
}

#[test]
fn generic_graph_rejects_invalid_archimate_relationship_endpoint_for_render_metadata() {
    let mut source = archimate_source();
    source["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    source["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    source["relationships"][0]["type"] = serde_json::json!("Realization");

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&source).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(
        &output,
        "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
    );
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("ApplicationService"));
    assert!(message.contains("Realization"));
    assert!(message.contains("ApplicationComponent"));
}

#[test]
fn generic_graph_rejects_invalid_archimate_relationship_endpoint_for_semantic_validation() {
    let mut source = archimate_source();
    source["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    source["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    source["relationships"][0]["type"] = serde_json::json!("Realization");

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["validate", "--profile", "archimate"])
        .write_stdin(serde_json::to_string(&source).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(
        &output,
        "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
    );
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("ApplicationService"));
    assert!(message.contains("Realization"));
    assert!(message.contains("ApplicationComponent"));
}

#[test]
fn generic_graph_rejects_archimate_junction_as_source_node_for_semantic_validation() {
    let mut source = archimate_source();
    source["nodes"][0]["type"] = serde_json::json!("Junction");

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["validate", "--profile", "archimate"])
        .write_stdin(serde_json::to_string(&source).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(&output, "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("Junction"));
}

fn archimate_source() -> serde_json::Value {
    serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            {
                "id": "generic-graph",
                "version": "0.5.1"
            },
            {
                "id": "archimate-oef",
                "version": "0.5.1"
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
    })
}
