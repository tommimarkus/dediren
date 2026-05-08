use assert_cmd::Command;
use predicates::prelude::*;
use std::path::PathBuf;

#[test]
fn first_party_plugins_report_capabilities() {
    for binary in [
        (
            "dediren-plugin-generic-graph",
            "dediren-plugin-generic-graph",
        ),
        ("dediren-plugin-elk-layout", "dediren-plugin-elk-layout"),
        ("dediren-plugin-svg-render", "dediren-plugin-svg-render"),
        (
            "dediren-plugin-archimate-oef-export",
            "dediren-plugin-archimate-oef-export",
        ),
    ] {
        let mut cmd = Command::new(workspace_binary(binary.0, binary.1));
        cmd.arg("capabilities");
        cmd.assert()
            .success()
            .stdout(predicate::str::contains("plugin.protocol.v1"))
            .stdout(predicate::str::contains("capabilities"));
    }
}

#[test]
fn unknown_plugin_failure_is_structured_by_cli() {
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.current_dir(workspace_root())
        .arg("layout")
        .arg("--plugin")
        .arg("missing-plugin")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-request/basic.json"));
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_PLUGIN_UNKNOWN"))
        .stdout(predicate::str::contains("unknown plugin id"));
}

#[test]
fn plugin_error_envelope_is_preserved_by_cli() {
    let plugin = workspace_binary("dediren-plugin-elk-layout", "dediren-plugin-elk-layout");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .arg("layout")
        .arg("--plugin")
        .arg("elk-layout")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-request/basic.json"));
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_ELK_RUNTIME_UNAVAILABLE"))
        .stdout(predicate::str::contains("\"status\":\"error\""))
        .stdout(predicate::str::contains("DEDIREN_PLUGIN_ERROR").not());
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
