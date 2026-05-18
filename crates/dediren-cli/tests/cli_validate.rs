mod common;

use assert_fs::prelude::*;
use common::{assert_error_code, ok_data, workspace_file};

#[test]
fn validate_accepts_valid_source_from_file() {
    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(workspace_file("fixtures/source/valid-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["model_schema_version"], "model.schema.v1");
}

#[test]
fn validate_rejects_authored_geometry() {
    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(workspace_file(
            "fixtures/source/invalid-absolute-geometry.json",
        ))
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_SCHEMA_INVALID");
}

#[test]
fn validate_rejects_duplicate_ids() {
    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(workspace_file("fixtures/source/invalid-duplicate-id.json"))
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_DUPLICATE_ID");
}

#[test]
fn validate_rejects_dangling_relationship_endpoint() {
    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(workspace_file(
            "fixtures/source/invalid-dangling-relationship.json",
        ))
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_DANGLING_ENDPOINT");
}

#[test]
fn validate_assembles_source_fragments_from_file_input() {
    let temp = assert_fs::TempDir::new().unwrap();
    let fragments = temp.child("fragments");
    fragments.create_dir_all().unwrap();
    temp.child("model.json")
        .write_str(
            &serde_json::to_string_pretty(&serde_json::json!({
                "model_schema_version": "model.schema.v1",
                "fragments": ["fragments/application.json", "fragments/view.json"],
                "nodes": [],
                "relationships": [],
                "plugins": {
                    "generic-graph": {
                        "views": []
                    }
                }
            }))
            .unwrap(),
        )
        .unwrap();
    fragments
        .child("application.json")
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
                        "type": "Association",
                        "source": "client",
                        "target": "api",
                        "label": "calls",
                        "properties": {}
                    }
                ],
                "plugins": {
                    "generic-graph": {
                        "views": []
                    }
                }
            }))
            .unwrap(),
        )
        .unwrap();
    fragments
        .child("view.json")
        .write_str(
            &serde_json::to_string_pretty(&serde_json::json!({
                "model_schema_version": "model.schema.v1",
                "nodes": [],
                "relationships": [],
                "plugins": {
                    "generic-graph": {
                        "views": [
                            {
                                "id": "main",
                                "label": "Main",
                                "nodes": ["client", "api"],
                                "relationships": ["client-calls-api"]
                            }
                        ]
                    }
                }
            }))
            .unwrap(),
        )
        .unwrap();

    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(temp.child("model.json").path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["node_count"], 2);
    assert_eq!(data["relationship_count"], 1);
}

#[test]
fn validate_rejects_duplicate_ids_after_fragment_assembly() {
    let temp = assert_fs::TempDir::new().unwrap();
    let fragments = temp.child("fragments");
    fragments.create_dir_all().unwrap();
    temp.child("model.json")
        .write_str(
            &serde_json::to_string_pretty(&serde_json::json!({
                "model_schema_version": "model.schema.v1",
                "fragments": ["fragments/one.json", "fragments/two.json"],
                "nodes": [],
                "relationships": [],
                "plugins": { "generic-graph": { "views": [] } }
            }))
            .unwrap(),
        )
        .unwrap();
    for name in ["one.json", "two.json"] {
        fragments
            .child(name)
            .write_str(
                &serde_json::to_string_pretty(&serde_json::json!({
                    "model_schema_version": "model.schema.v1",
                    "nodes": [
                        { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                    ],
                    "relationships": [],
                    "plugins": { "generic-graph": { "views": [] } }
                }))
                .unwrap(),
            )
            .unwrap();
    }

    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(temp.child("model.json").path())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_DUPLICATE_ID");
}

#[test]
fn validate_rejects_fragments_from_stdin_without_base_directory() {
    let output = common::dediren_command()
        .arg("validate")
        .write_stdin(
            serde_json::to_string(&serde_json::json!({
                "model_schema_version": "model.schema.v1",
                "fragments": ["fragments/application.json"],
                "nodes": [],
                "relationships": [],
                "plugins": { "generic-graph": { "views": [] } }
            }))
            .unwrap(),
        )
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_FRAGMENT_BASE_DIR_REQUIRED");
}

#[test]
fn validate_with_archimate_profile_accepts_semantically_valid_source() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            common::plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "validate",
            "--plugin",
            "generic-graph",
            "--profile",
            "archimate",
            "--input",
        ])
        .arg(workspace_file("fixtures/source/valid-archimate-oef.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(
        data["semantic_validation_result_schema_version"],
        "semantic-validation-result.schema.v1"
    );
    assert_eq!(data["semantic_profile"], "archimate");
    assert_eq!(data["node_count"], 2);
    assert_eq!(data["relationship_count"], 1);
}

#[test]
fn validate_with_archimate_profile_rejects_invalid_relationship_endpoint() {
    let temp = assert_fs::TempDir::new().unwrap();
    let source_file = temp.child("invalid-archimate-endpoint.json");
    source_file
        .write_str(&serde_json::to_string_pretty(&invalid_archimate_endpoint_source()).unwrap())
        .unwrap();

    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            common::plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "validate",
            "--plugin",
            "generic-graph",
            "--profile",
            "archimate",
            "--input",
        ])
        .arg(source_file.path())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(
        &output,
        "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
    );
}

#[test]
fn validate_with_archimate_profile_rejects_dangling_relationship_endpoint() {
    let temp = assert_fs::TempDir::new().unwrap();
    let source_file = temp.child("invalid-dangling-endpoint.json");
    source_file
        .write_str(&serde_json::to_string_pretty(&dangling_endpoint_source()).unwrap())
        .unwrap();

    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            common::plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "validate",
            "--plugin",
            "generic-graph",
            "--profile",
            "archimate",
            "--input",
        ])
        .arg(source_file.path())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_DANGLING_ENDPOINT");
}

#[test]
fn validate_with_archimate_profile_rejects_relationship_as_endpoint() {
    let temp = assert_fs::TempDir::new().unwrap();
    let source_file = temp.child("relationship-as-endpoint.json");
    source_file
        .write_str(&serde_json::to_string_pretty(&relationship_as_endpoint_source()).unwrap())
        .unwrap();

    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            common::plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "validate",
            "--plugin",
            "generic-graph",
            "--profile",
            "archimate",
            "--input",
        ])
        .arg(source_file.path())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_DANGLING_ENDPOINT");
}

fn invalid_archimate_endpoint_source() -> serde_json::Value {
    let mut source = source_fixture_json("fixtures/source/valid-archimate-oef.json");
    source["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    source["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    source["relationships"][0]["type"] = serde_json::json!("Realization");
    source
}

fn dangling_endpoint_source() -> serde_json::Value {
    let mut source = source_fixture_json("fixtures/source/valid-archimate-oef.json");
    source["relationships"][0]["source"] = serde_json::json!("missing-source");
    source
}

fn relationship_as_endpoint_source() -> serde_json::Value {
    let mut source = source_fixture_json("fixtures/source/valid-archimate-oef.json");
    source["relationships"]
        .as_array_mut()
        .unwrap()
        .push(serde_json::json!({
            "id": "relationship-association",
            "type": "Association",
            "source": "orders-realizes-service",
            "target": "orders-service",
            "label": "annotates",
            "properties": {}
        }));
    source
}

fn source_fixture_json(path: &str) -> serde_json::Value {
    serde_json::from_str(&std::fs::read_to_string(workspace_file(path)).unwrap()).unwrap()
}

#[test]
fn validate_missing_input_file_returns_json_envelope() {
    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(workspace_file("fixtures/source/missing-source.json"))
        .assert()
        .failure()
        .get_output()
        .clone();

    assert!(
        output.stderr.is_empty(),
        "preflight failures should be JSON on stdout, stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert_error_code(&output.stdout, "DEDIREN_COMMAND_INPUT_INVALID");
}
