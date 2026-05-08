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
    let dir = std::env::temp_dir().join(format!(
        "dediren-cli-elk-test-{}-{}",
        std::process::id(),
        unique_suffix()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let script = dir.join("elk-helper.sh");
    std::fs::write(&script, script_body).unwrap();
    format!("sh {}", shell_quote(&script.display().to_string()))
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
