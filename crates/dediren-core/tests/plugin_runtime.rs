#[cfg(unix)]
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, Instant};

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
fn installed_bundle_registry_finds_manifest_and_binary_next_to_cli() {
    let temp = TempDir::new().unwrap();
    let bundle = temp
        .path()
        .join("dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu");
    let bin_dir = bundle.join("bin");
    let plugin_dir = bundle.join("plugins");
    std::fs::create_dir_all(&bin_dir).unwrap();
    std::fs::create_dir_all(&plugin_dir).unwrap();

    let plugin_binary = bin_dir.join(if cfg!(windows) {
        "dediren-plugin-runtime-testbed.exe"
    } else {
        "dediren-plugin-runtime-testbed"
    });
    std::fs::copy(testbed_binary(), &plugin_binary).unwrap();
    #[cfg(unix)]
    {
        let mut permissions = std::fs::metadata(&plugin_binary).unwrap().permissions();
        permissions.set_mode(0o755);
        std::fs::set_permissions(&plugin_binary, permissions).unwrap();
    }

    write_manifest(
        &plugin_dir,
        "runtime-testbed",
        "dediren-plugin-runtime-testbed",
        &["layout"],
    );

    let fake_cli = bin_dir.join("dediren");
    let registry = PluginRegistry::for_executable(&fake_cli);
    let mut options = PluginRunOptions::default();
    options.allowed_env.push((
        "DEDIREN_TEST_PLUGIN_CAPABILITIES".to_string(),
        "layout".to_string(),
    ));
    let outcome = run_plugin_for_capability_with_registry(
        &registry,
        "runtime-testbed",
        "layout",
        &["layout"],
        "{}",
        options,
    )
    .expect("installed bundle registry should find plugin manifest and binary");

    assert_eq!(outcome.exit_code, 0);
    assert!(outcome.stdout.contains("\"layout_result_schema_version\""));
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
fn plugin_timeout_covers_pipe_drain_after_parent_exits() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let mut options = PluginRunOptions::default();
    options.timeout = Duration::from_millis(50);
    options.allowed_env.push((
        "DEDIREN_TEST_PLUGIN_MODE".to_string(),
        "leak-stdout-child".to_string(),
    ));

    let registry = PluginRegistry::from_dirs(vec![temp.path().to_path_buf()]);
    let started = Instant::now();
    let error = run_plugin_for_capability_with_registry(
        &registry,
        "runtime-testbed",
        "render",
        &["render"],
        "{}",
        options,
    )
    .expect_err("plugin with inherited pipe descendant should time out");
    let elapsed = started.elapsed();

    assert_eq!(error.diagnostic().code, "DEDIREN_PLUGIN_TIMEOUT");
    assert!(
        elapsed < Duration::from_millis(750),
        "timeout cleanup waited for inherited pipe handles for {elapsed:?}"
    );
}

#[test]
fn plugin_that_never_reads_large_stdin_times_out() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let mut options = PluginRunOptions::default();
    options.timeout = Duration::from_millis(50);
    options.allowed_env.push((
        "DEDIREN_TEST_PLUGIN_MODE".to_string(),
        "no-read-stdin".to_string(),
    ));

    let registry = PluginRegistry::from_dirs(vec![temp.path().to_path_buf()]);
    let input = "x".repeat(1024 * 1024);
    let error = run_plugin_for_capability_with_registry(
        &registry,
        "runtime-testbed",
        "render",
        &["render"],
        &input,
        options,
    )
    .expect_err("plugin that does not read stdin should time out");

    assert_eq!(error.diagnostic().code, "DEDIREN_PLUGIN_TIMEOUT");
}

#[test]
fn plugin_large_stderr_is_drained_while_running() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let mut options = PluginRunOptions::default();
    options.timeout = Duration::from_secs(1);
    options.allowed_env.push((
        "DEDIREN_TEST_PLUGIN_MODE".to_string(),
        "large-output".to_string(),
    ));

    let registry = PluginRegistry::from_dirs(vec![temp.path().to_path_buf()]);
    let outcome = run_plugin_for_capability_with_registry(
        &registry,
        "runtime-testbed",
        "render",
        &["render"],
        "{}",
        options,
    )
    .expect("plugin output pipes should be drained while the plugin runs");

    assert_eq!(outcome.exit_code, 0);
    assert!(outcome.stdout.contains("\"render_result_schema_version\""));
}

#[test]
fn plugin_large_stdout_is_drained_while_running() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let mut options = PluginRunOptions::default();
    options.timeout = Duration::from_secs(1);
    options.allowed_env.push((
        "DEDIREN_TEST_PLUGIN_MODE".to_string(),
        "large-stdout".to_string(),
    ));

    let registry = PluginRegistry::from_dirs(vec![temp.path().to_path_buf()]);
    let outcome = run_plugin_for_capability_with_registry(
        &registry,
        "runtime-testbed",
        "render",
        &["render"],
        "{}",
        options,
    )
    .expect("plugin stdout should be drained while the plugin runs");

    assert_eq!(outcome.exit_code, 0);
    assert!(outcome.stdout.contains("\"render_result_schema_version\""));
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
fn successful_plugin_data_must_match_capability_schema() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let error = run_with_mode(temp.path(), "invalid-data", "render", &["render"])
        .expect_err("render plugins must return render-result data in successful envelopes");

    assert_eq!(
        error.diagnostic().code,
        "DEDIREN_PLUGIN_OUTPUT_INVALID_DATA"
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

#[test]
fn error_envelope_with_zero_exit_is_reported_nonzero() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let outcome = run_with_mode(temp.path(), "error-envelope-zero", "render", &["render"])
        .expect("valid error envelope should be returned to the CLI");

    assert_eq!(outcome.exit_code, 3);
    assert!(outcome.stdout.contains("DEDIREN_TESTBED_ERROR"));
}

#[test]
fn legacy_capabilities_command_bypasses_command_capability_requirement() {
    let temp = TempDir::new().unwrap();
    write_manifest(
        temp.path(),
        "runtime-testbed",
        testbed_binary().to_str().unwrap(),
        &["render"],
    );

    let registry = PluginRegistry::from_dirs(vec![temp.path().to_path_buf()]);
    let result = run_plugin_for_capability_with_registry(
        &registry,
        "runtime-testbed",
        "capability",
        &["capabilities"],
        "",
        PluginRunOptions::default(),
    );

    let outcome = result.expect("legacy capabilities command should remain supported");
    assert_eq!(outcome.exit_code, 0);
    assert!(outcome.stdout.contains("\"capabilities\""));
}

#[test]
fn manifest_schema_validation_rejects_wrong_schema_version() {
    let temp = TempDir::new().unwrap();
    let manifest = serde_json::json!({
        "plugin_manifest_schema_version": "plugin-manifest.schema.v2",
        "id": "runtime-testbed",
        "version": "0.1.0",
        "executable": testbed_binary(),
        "capabilities": ["render"]
    });
    std::fs::write(
        temp.path().join("runtime-testbed.manifest.json"),
        serde_json::to_string_pretty(&manifest).unwrap(),
    )
    .unwrap();

    let error = run_with_mode(temp.path(), "ok", "render", &["render"])
        .expect_err("manifest schema violations should fail before execution");

    assert_eq!(error.diagnostic().code, "DEDIREN_PLUGIN_MANIFEST_INVALID");
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
