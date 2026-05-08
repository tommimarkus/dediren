use std::path::PathBuf;

use dediren_core::commands::{layout_command, LayoutCommandInput};
use tempfile::TempDir;

#[test]
fn layout_command_owns_elk_runtime_environment_allowlist() {
    let plugin = workspace_binary("dediren-plugin-elk-layout", "dediren-plugin-elk-layout");
    let fixture = workspace_file("fixtures/layout-result/basic.json");
    let request = std::fs::read_to_string(workspace_file("fixtures/layout-request/basic.json"))
        .expect("fixture must be readable");
    let plugin_dir = TempDir::new().unwrap();
    write_manifest(plugin_dir.path(), "elk-layout", &plugin, &["layout"]);

    let output = layout_command(LayoutCommandInput {
        plugin: "elk-layout",
        input_text: &request,
        plugin_dirs: vec![plugin_dir.path().to_path_buf()],
        env: vec![(
            "DEDIREN_ELK_RESULT_FIXTURE".to_string(),
            fixture.display().to_string(),
        )],
    })
    .expect("layout command orchestration should run through core");

    assert_eq!(0, output.exit_code);
    assert!(output.stdout.contains("\"layout_result_schema_version\""));
}

fn workspace_binary(package: &str, binary: &str) -> String {
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
    workspace_root()
        .join("target/debug")
        .join(executable)
        .display()
        .to_string()
}

fn write_manifest(dir: &std::path::Path, id: &str, executable: &str, capabilities: &[&str]) {
    let manifest = serde_json::json!({
        "plugin_manifest_schema_version": "plugin-manifest.schema.v1",
        "id": id,
        "version": "0.1.0",
        "executable": executable,
        "capabilities": capabilities
    });
    std::fs::write(
        dir.join(format!("{id}.manifest.json")),
        serde_json::to_string_pretty(&manifest).unwrap(),
    )
    .unwrap();
}

fn workspace_file(path: &str) -> PathBuf {
    workspace_root().join(path)
}

fn workspace_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}
