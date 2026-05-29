mod common;

use dediren_core::commands::{layout_command, semantic_validate_command, LayoutCommandInput};
use dediren_core::plugins::PluginRegistry;
use tempfile::TempDir;

#[test]
fn layout_command_owns_elk_runtime_environment_allowlist() {
    let plugin = common::workspace_binary("dediren-plugin-elk-layout", "dediren-plugin-elk-layout")
        .display()
        .to_string();
    let fixture = common::workspace_file("fixtures/layout-result/basic.json");
    let request =
        std::fs::read_to_string(common::workspace_file("fixtures/layout-request/basic.json"))
            .expect("fixture must be readable");
    let plugin_dir = TempDir::new().unwrap();
    write_manifest(plugin_dir.path(), "elk-layout", &plugin, &["layout"]);

    let output = layout_command(LayoutCommandInput {
        plugin: "elk-layout",
        input_text: &request,
        registry: PluginRegistry::from_dirs(vec![plugin_dir.path().to_path_buf()]),
        env: vec![(
            "DEDIREN_ELK_RESULT_FIXTURE".to_string(),
            fixture.display().to_string(),
        )],
    })
    .expect("layout command orchestration should run through core");

    assert_eq!(0, output.exit_code);
    assert!(output.stdout.contains("\"layout_result_schema_version\""));
}

#[cfg(unix)]
#[test]
fn layout_command_forwards_only_manifest_allowed_environment() {
    use std::os::unix::fs::PermissionsExt;

    let plugin_dir = TempDir::new().unwrap();
    let plugin = plugin_dir.path().join("env-check-plugin.sh");
    std::fs::write(
        &plugin,
        r#"#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "capabilities" ]]; then
  printf '%s\n' '{"plugin_protocol_version":"plugin.protocol.v1","id":"elk-layout","capabilities":["layout"],"runtime":{"kind":"test","available":true}}'
  exit 0
fi
if [[ -n "${DEDIREN_UNRELATED:-}" ]]; then
  printf '%s\n' '{"envelope_schema_version":"envelope.schema.v1","status":"error","diagnostics":[{"code":"UNRELATED_ENV_LEAKED","severity":"error","message":"unrelated env was passed to plugin"}]}'
  exit 5
fi
if [[ -z "${DEDIREN_ELK_RESULT_FIXTURE:-}" ]]; then
  printf '%s\n' '{"envelope_schema_version":"envelope.schema.v1","status":"error","diagnostics":[{"code":"FIXTURE_ENV_MISSING","severity":"error","message":"fixture env was not passed to plugin"}]}'
  exit 6
fi
printf '%s' '{"envelope_schema_version":"envelope.schema.v1","status":"ok","diagnostics":[],"data":'
cat "$DEDIREN_ELK_RESULT_FIXTURE"
printf '%s\n' '}'
"#,
    )
    .unwrap();
    let mut permissions = std::fs::metadata(&plugin).unwrap().permissions();
    permissions.set_mode(0o755);
    std::fs::set_permissions(&plugin, permissions).unwrap();

    let fixture = common::workspace_file("fixtures/layout-result/basic.json");
    let request =
        std::fs::read_to_string(common::workspace_file("fixtures/layout-request/basic.json"))
            .expect("fixture must be readable");
    write_manifest(
        plugin_dir.path(),
        "elk-layout",
        &plugin.display().to_string(),
        &["layout"],
    );

    let output = layout_command(LayoutCommandInput {
        plugin: "elk-layout",
        input_text: &request,
        registry: PluginRegistry::from_dirs(vec![plugin_dir.path().to_path_buf()]),
        env: vec![
            (
                "DEDIREN_ELK_RESULT_FIXTURE".to_string(),
                fixture.display().to_string(),
            ),
            ("DEDIREN_UNRELATED".to_string(), "not-allowed".to_string()),
        ],
    })
    .expect("layout command orchestration should pass only the manifest allowlist");

    assert_eq!(0, output.exit_code);
    assert!(output.stdout.contains("\"layout_result_schema_version\""));
}

#[test]
fn semantic_validate_command_rejects_invalid_source_before_plugin_lookup() {
    let mut source: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(common::workspace_file(
            "fixtures/source/valid-archimate-oef.json",
        ))
        .unwrap(),
    )
    .unwrap();
    source["relationships"][0]["source"] = serde_json::json!("missing-source");
    let input = serde_json::to_string(&source).unwrap();

    let output = semantic_validate_command("missing-plugin", "archimate", &input)
        .expect("invalid source should be returned as a validation envelope");

    assert_eq!(2, output.exit_code);
    assert!(output.stdout.contains("DEDIREN_DANGLING_ENDPOINT"));
}

fn write_manifest(dir: &std::path::Path, id: &str, executable: &str, capabilities: &[&str]) {
    let mut manifest = serde_json::json!({
        "plugin_manifest_schema_version": "plugin-manifest.schema.v1",
        "id": id,
        "version": "0.1.0",
        "executable": executable,
        "capabilities": capabilities
    });
    if id == "elk-layout" {
        manifest["allowed_env"] = serde_json::json!(["DEDIREN_ELK_RESULT_FIXTURE"]);
    }
    std::fs::write(
        dir.join(format!("{id}.manifest.json")),
        serde_json::to_string_pretty(&manifest).unwrap(),
    )
    .unwrap();
}
