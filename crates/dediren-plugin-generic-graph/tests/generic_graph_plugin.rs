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

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}
