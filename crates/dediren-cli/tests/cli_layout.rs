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
