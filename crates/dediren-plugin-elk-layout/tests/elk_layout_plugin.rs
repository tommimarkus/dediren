use assert_cmd::Command;
use predicates::prelude::*;
use std::path::PathBuf;

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
