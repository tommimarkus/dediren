use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Duration;

use dediren_core::plugins::{
    run_plugin_for_capability_with_registry, PluginExecutionError, PluginRegistry, PluginRunOptions,
};
use tempfile::TempDir;

#[test]
fn missing_executable_returns_typed_diagnostic() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        temp.path().join("missing-binary").to_str().unwrap(),
        &["render"],
    );

    let error = run_with_mode(temp.path(), "ok", "render", &["render"])
        .expect_err("missing executable should fail");

    assert_eq!(error.diagnostic().code, "DEDIREN_PLUGIN_MISSING_EXECUTABLE");
}

#[test]
fn unsupported_capability_is_rejected_before_command_execution() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let error = run_with_mode(temp.path(), "ok", "layout", &["layout"])
        .expect_err("unsupported capability should fail");

    assert_eq!(
        error.diagnostic().code,
        "DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY"
    );
}

#[test]
fn invalid_runtime_capability_json_is_structured() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let error = run_with_mode(
        temp.path(),
        "capabilities-invalid-json",
        "render",
        &["render"],
    )
    .expect_err("bad capability JSON should fail");

    assert_eq!(
        error.diagnostic().code,
        "DEDIREN_PLUGIN_CAPABILITY_INVALID_JSON"
    );
}

#[test]
fn runtime_id_mismatch_is_structured() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let mut options = PluginRunOptions::default();
    options.allowed_env.push((
        "DEDIREN_TEST_PLUGIN_ID".to_string(),
        "different-plugin".to_string(),
    ));

    let registry = PluginRegistry::from_dirs(vec![temp.path().to_path_buf()]);
    let error = run_plugin_for_capability_with_registry(
        &registry,
        "runtime-testbed",
        "render",
        &["render"],
        "{}",
        options,
    )
    .expect_err("runtime id mismatch should fail");

    assert_eq!(error.diagnostic().code, "DEDIREN_PLUGIN_ID_MISMATCH");
}

#[test]
fn plugin_timeout_is_structured() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let mut options = PluginRunOptions::default();
    options.timeout = Duration::from_millis(20);
    options
        .allowed_env
        .push(("DEDIREN_TEST_PLUGIN_MODE".to_string(), "sleep".to_string()));

    let registry = PluginRegistry::from_dirs(vec![temp.path().to_path_buf()]);
    let error = run_plugin_for_capability_with_registry(
        &registry,
        "runtime-testbed",
        "render",
        &["render"],
        "{}",
        options,
    )
    .expect_err("slow plugin should time out");

    assert_eq!(error.diagnostic().code, "DEDIREN_PLUGIN_TIMEOUT");
}

#[test]
fn invalid_success_output_is_structured() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let error = run_with_mode(temp.path(), "invalid-json", "render", &["render"])
        .expect_err("invalid plugin stdout should fail");

    assert_eq!(
        error.diagnostic().code,
        "DEDIREN_PLUGIN_OUTPUT_INVALID_JSON"
    );
}

#[test]
fn invalid_success_envelope_is_structured() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let error = run_with_mode(temp.path(), "invalid-envelope", "render", &["render"])
        .expect_err("invalid plugin envelope should fail");

    assert_eq!(
        error.diagnostic().code,
        "DEDIREN_PLUGIN_OUTPUT_INVALID_ENVELOPE"
    );
}

#[test]
fn structured_plugin_error_envelope_is_preserved() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let outcome = run_with_mode(temp.path(), "error-envelope", "render", &["render"])
        .expect("valid error envelope should be returned to the CLI");

    assert_eq!(outcome.exit_code, 3);
    assert!(outcome.stdout.contains("DEDIREN_TESTBED_ERROR"));
}

fn run_with_mode(
    manifest_dir: &Path,
    mode: &str,
    required_capability: &str,
    args: &[&str],
) -> Result<dediren_core::plugins::PluginRunOutcome, PluginExecutionError> {
    let mut options = PluginRunOptions::default();
    options
        .allowed_env
        .push(("DEDIREN_TEST_PLUGIN_MODE".to_string(), mode.to_string()));
    let registry = PluginRegistry::from_dirs(vec![manifest_dir.to_path_buf()]);
    run_plugin_for_capability_with_registry(
        &registry,
        "runtime-testbed",
        required_capability,
        args,
        "{}",
        options,
    )
}

fn write_manifest(dir: &Path, id: &str, executable: &str, capabilities: &[&str]) {
    let capabilities_json = serde_json::to_string(capabilities).unwrap();
    std::fs::write(
        dir.join(format!("{id}.manifest.json")),
        format!(
            r#"{{
  "plugin_manifest_schema_version": "plugin-manifest.schema.v1",
  "id": "{id}",
  "version": "0.1.0",
  "executable": "{executable}",
  "capabilities": {capabilities_json}
}}"#
        ),
    )
    .unwrap();
}

fn testbed_binary() -> PathBuf {
    let root = workspace_root();
    let status = Command::new("cargo")
        .current_dir(&root)
        .args([
            "build",
            "-p",
            "dediren-plugin-runtime-testbed",
            "--bin",
            "dediren-plugin-runtime-testbed",
        ])
        .status()
        .unwrap();
    assert!(status.success());
    let executable = if cfg!(windows) {
        "dediren-plugin-runtime-testbed.exe"
    } else {
        "dediren-plugin-runtime-testbed"
    };
    root.join("target/debug").join(executable)
}

fn workspace_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}
