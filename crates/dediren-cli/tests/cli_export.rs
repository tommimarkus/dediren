use assert_cmd::Command;
use predicates::prelude::*;

fn workspace_file(path: &str) -> String {
    format!("{}/{}", env!("CARGO_MANIFEST_DIR"), path).replace("/crates/dediren-cli/", "/")
}

fn workspace_binary(package: &str, binary: &str) -> String {
    let status = std::process::Command::new("cargo")
        .args(["build", "-p", package, "--bin", binary])
        .status()
        .unwrap();
    assert!(status.success());
    workspace_file(&format!("target/debug/{binary}"))
}

#[test]
fn export_invokes_archimate_oef_plugin() {
    let plugin = workspace_binary(
        "dediren-plugin-archimate-oef-export",
        "dediren-plugin-archimate-oef-export",
    );
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.env("DEDIREN_PLUGIN_ARCHIMATE_OEF", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args([
            "export",
            "--plugin",
            "archimate-oef",
            "--policy",
            &workspace_file("fixtures/export-policy/default-oef.json"),
            "--source",
            &workspace_file("fixtures/source/valid-archimate-oef.json"),
            "--layout",
            &workspace_file("fixtures/layout-result/archimate-oef-basic.json"),
        ])
        .assert()
        .success()
        .stdout(predicate::str::contains("\"export_result_schema_version\""))
        .stdout(predicate::str::contains("archimate-oef+xml"))
        .stdout(predicate::str::contains("<model"));
}
