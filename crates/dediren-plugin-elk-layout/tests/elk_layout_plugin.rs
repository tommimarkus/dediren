use assert_cmd::Command;
use predicates::prelude::*;
use std::path::PathBuf;

#[test]
fn elk_plugin_invokes_external_command_and_wraps_raw_layout_result() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let helper = helper_command(&format!(
        "cat {}",
        shell_quote(
            &workspace_file("fixtures/layout-result/basic.json")
                .display()
                .to_string()
        )
    ));
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_COMMAND", helper)
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"status\":\"ok\""))
        .stdout(predicate::str::contains("\"layout_result_schema_version\""))
        .stdout(predicate::str::contains("\"projection_id\":\"client\""));
}

#[test]
fn elk_plugin_accepts_external_command_envelope() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let layout_result: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(workspace_file("fixtures/layout-result/basic.json")).unwrap(),
    )
    .unwrap();
    let envelope = serde_json::json!({
        "envelope_schema_version": "envelope.schema.v1",
        "status": "ok",
        "data": layout_result,
        "diagnostics": []
    });
    let fixture = write_temp_file("elk-ok-envelope.json", &envelope.to_string());
    let helper = helper_command(&format!(
        "cat {}",
        shell_quote(&fixture.display().to_string())
    ));
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_COMMAND", helper)
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"status\":\"ok\""))
        .stdout(predicate::str::contains("\"layout_result_schema_version\""));
}

#[test]
fn elk_plugin_preserves_external_error_envelope() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let envelope = serde_json::json!({
        "envelope_schema_version": "envelope.schema.v1",
        "status": "error",
        "diagnostics": [{
            "code": "DEDIREN_EXTERNAL_ELK_ERROR",
            "severity": "error",
            "message": "external ELK helper rejected the graph"
        }]
    });
    let fixture = write_temp_file("elk-error-envelope.json", &envelope.to_string());
    let helper = helper_command(&format!(
        "cat {}; exit 7",
        shell_quote(&fixture.display().to_string())
    ));
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_COMMAND", helper)
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_EXTERNAL_ELK_ERROR"))
        .stdout(predicate::str::contains("DEDIREN_ELK_RUNTIME_FAILED").not());
}

#[test]
fn elk_plugin_reports_external_runtime_failure() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let helper = helper_command("echo external failure >&2; exit 42");
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_COMMAND", helper)
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_ELK_RUNTIME_FAILED"))
        .stdout(predicate::str::contains("external failure"));
}

#[test]
fn elk_plugin_reports_external_invalid_json_output() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let helper = helper_command("printf '%s\\n' not-json");
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.env("DEDIREN_ELK_COMMAND", helper)
        .arg("layout")
        .write_stdin(input);
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_ELK_OUTPUT_INVALID_JSON"))
        .stdout(predicate::str::contains("DEDIREN_ELK_RUNTIME_FAILED").not());
}

#[test]
fn elk_plugin_reports_missing_runtime() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json")).unwrap();
    let mut cmd = Command::cargo_bin("dediren-plugin-elk-layout").unwrap();
    cmd.arg("layout").write_stdin(input);
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_ELK_RUNTIME_UNAVAILABLE"));
}

#[test]
fn elk_plugin_accepts_fixture_runtime_output() {
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
        "dediren-elk-plugin-test-{}-{}",
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
