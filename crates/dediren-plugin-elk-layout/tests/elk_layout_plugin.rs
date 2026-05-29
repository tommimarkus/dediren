use assert_cmd::Command;
use dediren_contracts::{CommandEnvelope, LayoutResult};
use predicates::prelude::*;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

static TEMP_COUNTER: AtomicU64 = AtomicU64::new(0);

#[test]
fn rust_elk_plugin_layouts_basic_request_without_external_runtime() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    let output = cmd
        .env_remove("DEDIREN_ELK_RESULT_FIXTURE")
        .env_remove("DEDIREN_ELK_COMMAND")
        .arg("layout")
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let envelope: CommandEnvelope<LayoutResult> = serde_json::from_slice(&output).unwrap();
    let result = envelope.data.expect("layout command should return data");

    assert_eq!(envelope.status, "ok");
    assert_eq!(
        result.layout_result_schema_version,
        "layout-result.schema.v1"
    );
    assert_eq!(result.view_id, "main");
    assert_eq!(result.nodes.len(), 2);
    assert_eq!(result.edges.len(), 1);
    assert!(
        !result.edges[0].points.is_empty(),
        "elkrs should return routed edge points"
    );
}

#[test]
fn capabilities_report_rust_elkrs_runtime_available() {
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.arg("capabilities");
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"kind\":\"rust-elkrs\""))
        .stdout(predicate::str::contains("\"available\":true"));
}

#[test]
fn dediren_elk_command_is_ignored_by_rust_backend() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let helper = helper_command("echo should-not-run >&2; exit 42");
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_COMMAND", helper)
        .env_remove("DEDIREN_ELK_RESULT_FIXTURE")
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"status\":\"ok\""))
        .stdout(predicate::str::contains("\"layout_result_schema_version\""))
        .stdout(predicate::str::contains("should-not-run").not());
}

#[test]
fn fixture_elk_plugin_accepts_fixture_runtime_output() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let fake = workspace_file("fixtures/layout-result/basic.json");
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_RESULT_FIXTURE", fake)
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"layout_result_schema_version\""))
        .stdout(predicate::str::contains("\"projection_id\":\"client\""));
}

#[test]
fn fixture_elk_plugin_reports_missing_fixture_as_error_envelope() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let missing = std::env::temp_dir().join(format!(
        "dediren-missing-elk-fixture-{}-{}.json",
        std::process::id(),
        unique_suffix()
    ));
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_RESULT_FIXTURE", missing)
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .code(3)
        .stdout(predicate::str::contains("\"status\":\"error\""))
        .stdout(predicate::str::contains(
            "\"code\":\"DEDIREN_ELK_FIXTURE_UNAVAILABLE\"",
        ))
        .stdout(predicate::str::contains("\"severity\":\"error\""));
}

#[test]
fn fixture_elk_plugin_reports_invalid_fixture_json_as_error_envelope() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let invalid_fixture = write_temp_file("invalid-elk-result.json", "not-json");
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_RESULT_FIXTURE", invalid_fixture)
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .code(3)
        .stdout(predicate::str::contains("\"status\":\"error\""))
        .stdout(predicate::str::contains(
            "\"code\":\"DEDIREN_ELK_FIXTURE_INVALID_JSON\"",
        ))
        .stdout(predicate::str::contains("\"severity\":\"error\""));
}

#[test]
fn layout_rejects_wrong_schema_version_at_plugin_boundary() {
    let request = serde_json::json!({
        "layout_request_schema_version": "layout-request.schema.v2",
        "view_id": "main",
        "nodes": [],
        "edges": [],
        "groups": [],
        "labels": [],
        "constraints": []
    });

    assert_layout_request_rejected(request);
}

#[test]
fn layout_rejects_missing_required_arrays_at_plugin_boundary() {
    let request = serde_json::json!({
        "layout_request_schema_version": "layout-request.schema.v1",
        "view_id": "main"
    });

    assert_layout_request_rejected(request);
}

#[test]
fn layout_rejects_non_positive_size_hints_at_plugin_boundary() {
    let request = serde_json::json!({
        "layout_request_schema_version": "layout-request.schema.v1",
        "view_id": "main",
        "nodes": [
            {
                "id": "a",
                "label": "A",
                "source_id": "a",
                "width_hint": 0.0,
                "height_hint": -1.0
            }
        ],
        "edges": [],
        "groups": [],
        "labels": [],
        "constraints": []
    });

    assert_layout_request_rejected(request);
}

fn assert_layout_request_rejected(request: serde_json::Value) {
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env_remove("DEDIREN_ELK_RESULT_FIXTURE")
        .arg("layout")
        .write_stdin(serde_json::to_string(&request).unwrap());
    cmd.assert()
        .code(3)
        .stdout(predicate::str::contains("\"status\":\"error\""))
        .stdout(predicate::str::contains(
            "\"code\":\"DEDIREN_ELK_INPUT_SCHEMA_INVALID\"",
        ));
}

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}

fn helper_command(script_body: &str) -> String {
    let script = write_temp_file("elk-helper.sh", script_body);
    format!("sh {}", shell_quote(&script.display().to_string()))
}

fn write_temp_file(name: &str, content: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!(
        "dediren-elk-plugin-test-{}-{}-{}",
        std::process::id(),
        unique_suffix(),
        TEMP_COUNTER.fetch_add(1, Ordering::Relaxed)
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
