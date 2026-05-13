mod common;

use common::{ok_data, plugin_binary, workspace_file};
use std::path::PathBuf;

#[test]
fn fixture_elk_layout_uses_result_fixture() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env(
            "DEDIREN_ELK_RESULT_FIXTURE",
            workspace_file("fixtures/layout-result/basic.json"),
        )
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(workspace_file("fixtures/layout-request/basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_basic_layout_result(&data);
}

#[test]
fn fake_elk_layout_wraps_external_command() {
    let helper = helper_command(&format!(
        "cat {}",
        shell_quote(
            &workspace_file("fixtures/layout-result/basic.json")
                .display()
                .to_string()
        )
    ));
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env("DEDIREN_ELK_COMMAND", helper)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(workspace_file("fixtures/layout-request/basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_basic_layout_result(&data);
}

#[test]
#[ignore = "run with --ignored after building the ELK Java helper"]
fn real_elk_layout_invokes_java_helper() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env(
            "DEDIREN_ELK_COMMAND",
            workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh"),
        )
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(workspace_file("fixtures/layout-request/basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(
        data["layout_result_schema_version"],
        "layout-result.schema.v1"
    );
    assert!(
        data["edges"]
            .as_array()
            .expect("layout result edges should be an array")
            .iter()
            .any(|edge| edge["id"] == "client-calls-api"),
        "real helper output should contain client-calls-api edge"
    );
}

#[test]
#[ignore = "run with --ignored after building the ELK Java helper"]
fn real_elk_layout_validates_grouped_cross_group_route() {
    let request = serde_json::json!({
        "layout_request_schema_version": "layout-request.schema.v1",
        "view_id": "main",
        "nodes": [
            { "id": "a", "label": "A", "source_id": "a", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "b", "label": "B", "source_id": "b", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "c", "label": "C", "source_id": "c", "width_hint": 160.0, "height_hint": 80.0 }
        ],
        "edges": [
            { "id": "a-to-b", "source": "a", "target": "b", "label": "internal", "source_id": "a-to-b" },
            { "id": "a-to-c", "source": "a", "target": "c", "label": "connects", "source_id": "a-to-c" }
        ],
        "groups": [
            {
                "id": "group",
                "label": "Group",
                "members": ["a", "b"],
                "provenance": { "semantic_backed": { "source_id": "group" } }
            }
        ],
        "labels": [],
        "constraints": []
    });
    let request_file = write_temp_file(
        "grouped-cross-edge-layout-request.json",
        &request.to_string(),
    );

    let layout_output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env(
            "DEDIREN_ELK_COMMAND",
            workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh"),
        )
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request_file)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let layout_data = ok_data(&layout_output);
    assert_eq!(
        layout_data["layout_result_schema_version"],
        "layout-result.schema.v1"
    );
    assert!(
        layout_data["edges"]
            .as_array()
            .expect("layout result edges should be an array")
            .iter()
            .any(|edge| edge["id"] == "a-to-c"),
        "real helper output should contain cross-group edge"
    );
    let layout_file = write_temp_file(
        "grouped-cross-edge-layout-result.json",
        std::str::from_utf8(&layout_output).unwrap(),
    );

    let validate_output = common::dediren_command()
        .arg("validate-layout")
        .arg("--input")
        .arg(layout_file)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let quality = ok_data(&validate_output);
    assert_eq!(quality["overlap_count"], 0);
    assert_eq!(quality["connector_through_node_count"], 0);
    assert_eq!(quality["route_close_parallel_count"], 0);
    assert_eq!(quality["status"], "ok");
}

#[test]
fn fixture_layout_result_reports_quality() {
    let output = common::dediren_command()
        .arg("validate-layout")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["overlap_count"], 0);
    assert_eq!(data["connector_through_node_count"], 0);
    assert_eq!(data["route_close_parallel_count"], 0);
    assert_eq!(data["status"], "ok");
}

fn assert_basic_layout_result(data: &serde_json::Value) {
    assert_eq!(
        data["layout_result_schema_version"],
        "layout-result.schema.v1"
    );
    assert_eq!(data["view_id"], "main");
    assert_eq!(
        data["nodes"]
            .as_array()
            .expect("layout result nodes should be an array")
            .len(),
        2
    );
    assert!(
        data["edges"]
            .as_array()
            .expect("layout result edges should be an array")
            .iter()
            .any(|edge| edge["id"] == "client-calls-api"),
        "layout result should contain client-calls-api edge"
    );
}

fn helper_command(script_body: &str) -> String {
    let script = write_temp_file("elk-helper.sh", script_body);
    format!("sh {}", shell_quote(&script.display().to_string()))
}

fn write_temp_file(name: &str, content: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!(
        "dediren-cli-elk-test-{}-{}",
        std::process::id(),
        unique_suffix()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(name);
    std::fs::write(&path, content).unwrap();
    path
}

fn unique_suffix() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos()
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', r#"'\''"#))
}
