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

#[test]
fn layout_command_allows_path_for_layout_runtime_environment() {
    let plugin = common::workspace_binary("dediren-plugin-elk-layout", "dediren-plugin-elk-layout")
        .display()
        .to_string();
    let fixture = common::workspace_file("fixtures/layout-result/basic.json");
    let request =
        std::fs::read_to_string(common::workspace_file("fixtures/layout-request/basic.json"))
            .expect("fixture must be readable");
    let plugin_dir = TempDir::new().unwrap();
    write_manifest(plugin_dir.path(), "elk-layout", &plugin, &["layout"]);
    let allowed_path = "/usr/bin:/bin:/tmp/dediren-layout-allowlist";
    let helper = format!(
        "if [ \"$PATH\" = '{}' ]; then /bin/cat {}; else printf '%s\\n' '{{\"envelope_schema_version\":\"envelope.schema.v1\",\"status\":\"error\",\"diagnostics\":[{{\"code\":\"PATH_NOT_ALLOWED\",\"severity\":\"error\",\"message\":\"PATH was not passed to the layout runtime\"}}]}}'; exit 5; fi",
        allowed_path,
        shell_quote(&fixture.display().to_string())
    );

    let output = layout_command(LayoutCommandInput {
        plugin: "elk-layout",
        input_text: &request,
        registry: PluginRegistry::from_dirs(vec![plugin_dir.path().to_path_buf()]),
        env: vec![
            ("DEDIREN_ELK_COMMAND".to_string(), helper),
            ("PATH".to_string(), allowed_path.to_string()),
            ("DEDIREN_UNRELATED".to_string(), "not-allowed".to_string()),
        ],
    })
    .expect("layout command orchestration should pass the narrow runtime environment");

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

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', r#"'\''"#))
}
