use assert_cmd::Command;
use predicates::prelude::*;
use std::path::PathBuf;

#[test]
fn generic_graph_projects_basic_view() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/source/valid-basic.json")).unwrap();
    let mut cmd = Command::cargo_bin("dediren-plugin-generic-graph").unwrap();
    cmd.args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(input);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains(
            "\"layout_request_schema_version\"",
        ))
        .stdout(predicate::str::contains("\"view_id\":\"main\""));
}

#[test]
fn generic_graph_projects_rich_view_groups() {
    let input = std::fs::read_to_string(workspace_file("fixtures/source/valid-pipeline-rich.json"))
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

#[test]
fn generic_graph_projects_render_metadata() {
    let input = std::fs::read_to_string(workspace_file("fixtures/source/valid-archimate-oef.json"))
        .unwrap();
    let mut cmd = Command::cargo_bin("dediren-plugin-generic-graph").unwrap();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let envelope: serde_json::Value = serde_json::from_slice(&output).unwrap();
    assert_eq!(
        envelope["data"]["render_metadata_schema_version"],
        "render-metadata.schema.v1"
    );
    assert_eq!(envelope["data"]["semantic_profile"], "archimate");
    assert_eq!(
        envelope["data"]["nodes"]["orders-component"]["type"],
        "ApplicationComponent"
    );
    assert_eq!(
        envelope["data"]["nodes"]["orders-service"]["type"],
        "ApplicationService"
    );
    assert_eq!(
        envelope["data"]["edges"]["orders-realizes-service"]["type"],
        "Realization"
    );
}

#[test]
fn generic_graph_rejects_unknown_archimate_node_type_for_render_metadata() {
    let mut source = archimate_source();
    source["nodes"][0]["type"] = serde_json::json!("TechnologyNode");

    let mut cmd = Command::cargo_bin("dediren-plugin-generic-graph").unwrap();
    cmd.args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&source).unwrap());
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains(
            "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED",
        ))
        .stdout(predicate::str::contains("TechnologyNode"));
}

#[test]
fn generic_graph_rejects_unknown_archimate_relationship_type_for_render_metadata() {
    let mut source = archimate_source();
    source["relationships"][0]["type"] = serde_json::json!("ConnectsTo");

    let mut cmd = Command::cargo_bin("dediren-plugin-generic-graph").unwrap();
    cmd.args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&source).unwrap());
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains(
            "DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED",
        ))
        .stdout(predicate::str::contains("ConnectsTo"));
}

#[test]
fn generic_graph_rejects_invalid_archimate_relationship_endpoint_for_render_metadata() {
    let mut source = archimate_source();
    source["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    source["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    source["relationships"][0]["type"] = serde_json::json!("Realization");

    let mut cmd = Command::cargo_bin("dediren-plugin-generic-graph").unwrap();
    cmd.args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&source).unwrap());
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains(
            "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
        ))
        .stdout(predicate::str::contains("ApplicationService"))
        .stdout(predicate::str::contains("Realization"))
        .stdout(predicate::str::contains("ApplicationComponent"));
}

fn archimate_source() -> serde_json::Value {
    serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            {
                "id": "generic-graph",
                "version": "0.1.4"
            },
            {
                "id": "archimate-oef",
                "version": "0.1.4"
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

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}
