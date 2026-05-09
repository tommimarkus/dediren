use assert_cmd::Command;
use predicates::prelude::*;
use std::path::PathBuf;

#[test]
fn render_invokes_svg_plugin() {
    let plugin = workspace_binary("dediren-plugin-svg-render", "dediren-plugin-svg-render");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_SVG_RENDER", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/default-svg.json"))
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/basic.json"));
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"render_result_schema_version\""))
        .stdout(predicate::str::contains("<svg"))
        .stdout(predicate::str::contains("Client"));
}

#[test]
fn render_invokes_svg_plugin_with_rich_policy() {
    let plugin = workspace_binary("dediren-plugin-svg-render", "dediren-plugin-svg-render");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.env("DEDIREN_PLUGIN_SVG_RENDER", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/rich-svg.json"))
        .args(["--input"])
        .arg(workspace_file("fixtures/layout-result/basic.json"));
    let output = cmd.assert().success().get_output().stdout.clone();
    let envelope: serde_json::Value = serde_json::from_slice(&output).unwrap();
    let content = envelope["data"]["content"].as_str().unwrap();
    assert!(envelope["data"]
        .get("render_result_schema_version")
        .is_some());
    assert!(content.contains(r##"fill="#ecfeff""##));
    assert!(content.contains(r##"stroke="#7c3aed""##));
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
