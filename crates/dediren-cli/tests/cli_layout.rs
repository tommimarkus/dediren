use assert_cmd::Command;
use predicates::prelude::*;
use std::path::PathBuf;

#[test]
fn layout_invokes_elk_plugin_with_fixture_runtime() {
    let plugin = workspace_binary("dediren-plugin-elk-layout", "dediren-plugin-elk-layout");
    let fixture = workspace_file("fixtures/layout-result/basic.json");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .env("DEDIREN_ELK_RESULT_FIXTURE", fixture)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(workspace_file("fixtures/layout-request/basic.json"));
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"layout_result_schema_version\""));
}

#[test]
fn layout_invokes_elk_plugin_with_external_command() {
    let plugin = workspace_binary("dediren-plugin-elk-layout", "dediren-plugin-elk-layout");
    let helper = helper_command(&format!(
        "cat {}",
        shell_quote(
            &workspace_file("fixtures/layout-result/basic.json")
                .display()
                .to_string()
        )
    ));
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .env("DEDIREN_ELK_COMMAND", helper)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(workspace_file("fixtures/layout-request/basic.json"));
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"status\":\"ok\""))
        .stdout(predicate::str::contains("\"layout_result_schema_version\""));
}

#[test]
#[ignore = "requires SDKMAN Java helper build"]
fn layout_invokes_real_java_elk_helper() {
    let plugin = workspace_binary("dediren-plugin-elk-layout", "dediren-plugin-elk-layout");
    let helper = workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .env("DEDIREN_ELK_COMMAND", helper)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(workspace_file("fixtures/layout-request/basic.json"));
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"status\":\"ok\""))
        .stdout(predicate::str::contains("\"layout_result_schema_version\""))
        .stdout(predicate::str::contains("\"client-calls-api\""));
}

#[test]
#[ignore = "requires SDKMAN Java helper build"]
fn validate_layout_accepts_real_grouped_cross_group_route() {
    let plugin = workspace_binary("dediren-plugin-elk-layout", "dediren-plugin-elk-layout");
    let helper = workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh");
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

    let mut layout = Command::cargo_bin("dediren").unwrap();
    let layout_output = layout
        .current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .env("DEDIREN_ELK_COMMAND", helper)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request_file)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let layout_file = write_temp_file(
        "grouped-cross-edge-layout-result.json",
        std::str::from_utf8(&layout_output).unwrap(),
    );

    let mut validate = Command::cargo_bin("dediren").unwrap();
    validate
        .arg("validate-layout")
        .arg("--input")
        .arg(layout_file)
        .assert()
        .success()
        .stdout(predicate::str::contains(
            "\"connector_through_node_count\":0",
        ))
        .stdout(predicate::str::contains("\"status\":\"ok\""));
}

#[test]
fn validate_layout_reports_quality() {
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.arg("validate-layout")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/basic.json"));
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"overlap_count\":0"));
}

fn workspace_binary(package: &str, binary: &str) -> PathBuf {
    let status = std::process::Command::new("cargo")
        .current_dir(workspace_root())
        .args(["build", "-p", package, "--bin", binary])
        .status()
        .unwrap();
    assert!(status.success());
    let executable = if cfg!(windows) {
        format!("{binary}.exe")
    } else {
        binary.to_string()
    };
    workspace_root().join("target/debug").join(executable)
}

fn workspace_file(path: &str) -> PathBuf {
    workspace_root().join(path)
}

fn workspace_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
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
