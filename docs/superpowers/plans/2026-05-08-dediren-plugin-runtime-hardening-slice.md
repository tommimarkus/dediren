# Dediren Plugin Runtime Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make plugin runtime failures machine-readable, capability-aware, and covered by executable tests so agents can distinguish missing plugins, bad plugin output, unsupported capabilities, timeouts, and structured plugin errors.

**Architecture:** Keep plugin execution in `dediren-core` and keep CLI commands as thin orchestrators. Replace the current generic `anyhow` plugin failure surface with a small typed runtime outcome and typed execution errors that convert into public `Diagnostic` values. Add a private first-party test fixture plugin so runtime edge cases are tested through the same process boundary as real plugins.

**Tech Stack:** Rust 1.93, Cargo workspace, serde/serde_json, jsonschema, thiserror, tempfile, assert_cmd, predicates, first-party executable plugin fixture.

---

## Slice Scope

This slice implements the contract gaps left by the first vertical slice:

- Typed plugin execution errors in `dediren-core`.
- Capability checks against both static manifests and runtime capability output.
- JSON Schema validation for runtime capability output.
- JSON Schema validation for plugin command envelopes.
- Pass-through of valid structured plugin error envelopes while preserving non-zero CLI exit status.
- Structured CLI diagnostics for missing executable, timeout, invalid capability JSON, invalid command output, unsupported capability, and id mismatch.
- Tests that exercise plugin edge cases through a real executable process.

Out of scope:

- Plugin signing, provenance, or installation trust.
- OS sandboxing beyond the existing process boundary and environment allowlist.
- New third-party plugin discovery locations.
- ArchiMate, SVG, ELK, or layout-quality feature changes.
- Public raw artifact stdout mode.

## File Structure

- Modify `Cargo.toml`: add the runtime test fixture crate as a workspace member.
- Modify `crates/dediren-core/Cargo.toml`: add `tempfile` as a dev dependency for isolated manifest directories in runtime tests.
- Modify `crates/dediren-core/src/plugins.rs`: add typed plugin outcomes, typed plugin errors, capability checks, schema validation, manifest-relative executable resolution, and structured error conversion.
- Modify `crates/dediren-cli/src/main.rs`: pass required capability names explicitly and print plugin outcomes with the correct exit status.
- Modify `crates/dediren-cli/tests/plugin_compat.rs`: assert that the CLI preserves structured plugin error envelopes from the ELK plugin.
- Create `crates/dediren-core/tests/plugin_runtime.rs`: direct runtime tests for timeout, bad capability output, unsupported capability, missing executable, bad command output, and structured error pass-through.
- Create `crates/dediren-plugin-runtime-testbed/Cargo.toml`: private process-boundary fixture plugin crate.
- Create `crates/dediren-plugin-runtime-testbed/src/main.rs`: fixture plugin that can emit valid output, invalid JSON, invalid envelopes, slow responses, and structured error envelopes.
- Modify `README.md`: document that plugin runtime errors are command envelopes and that plugin stderr is never required for agent repair.

---

### Task 1: Add Runtime Test Fixture Plugin

**Files:**
- Modify: `Cargo.toml`
- Create: `crates/dediren-plugin-runtime-testbed/Cargo.toml`
- Create: `crates/dediren-plugin-runtime-testbed/src/main.rs`

- [ ] **Step 1: Add the failing runtime test crate target**

Modify the root `Cargo.toml` workspace members:

```toml
"crates/dediren-plugin-runtime-testbed",
```

Run:

```bash
cargo build -p dediren-plugin-runtime-testbed
```

Expected: FAIL because the crate does not exist yet.

- [ ] **Step 2: Create the private fixture crate manifest**

Create `crates/dediren-plugin-runtime-testbed/Cargo.toml`:

```toml
[package]
name = "dediren-plugin-runtime-testbed"
version.workspace = true
edition.workspace = true
license.workspace = true
repository.workspace = true
homepage.workspace = true
rust-version.workspace = true
publish = false

[[bin]]
name = "dediren-plugin-runtime-testbed"
path = "src/main.rs"

[dependencies]
dediren-contracts = { path = "../dediren-contracts" }
serde_json.workspace = true
```

- [ ] **Step 3: Create the fixture plugin executable**

Create `crates/dediren-plugin-runtime-testbed/src/main.rs`:

```rust
use std::io::Read;
use std::time::Duration;

use dediren_contracts::{CommandEnvelope, Diagnostic, DiagnosticSeverity, PLUGIN_PROTOCOL_VERSION};

fn main() -> anyhow_free_result::Result {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("capabilities") => capabilities(),
        Some(_) => run_command(),
        None => {
            eprintln!("expected command");
            std::process::exit(2);
        }
    }
}

fn capabilities() -> anyhow_free_result::Result {
    match mode().as_str() {
        "capabilities-invalid-json" => {
            println!("{{");
            Ok(())
        }
        "capabilities-nonzero" => {
            eprintln!("capability probe failed by request");
            std::process::exit(7);
        }
        _ => {
            let id = std::env::var("DEDIREN_TEST_PLUGIN_ID")
                .unwrap_or_else(|_| "runtime-testbed".to_string());
            let capabilities = std::env::var("DEDIREN_TEST_PLUGIN_CAPABILITIES")
                .unwrap_or_else(|_| "render".to_string())
                .split(',')
                .filter(|item| !item.is_empty())
                .map(str::to_string)
                .collect::<Vec<_>>();
            println!(
                "{}",
                serde_json::json!({
                    "plugin_protocol_version": PLUGIN_PROTOCOL_VERSION,
                    "id": id,
                    "capabilities": capabilities
                })
            );
            Ok(())
        }
    }
}

fn run_command() -> anyhow_free_result::Result {
    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input).unwrap();
    match mode().as_str() {
        "sleep" => std::thread::sleep(Duration::from_secs(2)),
        "invalid-json" => {
            println!("not-json");
            return Ok(());
        }
        "invalid-envelope" => {
            println!(r#"{{"status":"ok"}}"#);
            return Ok(());
        }
        "error-envelope" => {
            let diagnostic = Diagnostic {
                code: "DEDIREN_TESTBED_ERROR".to_string(),
                severity: DiagnosticSeverity::Error,
                message: "fixture plugin returned a structured error".to_string(),
                path: Some("$.testbed".to_string()),
            };
            println!(
                "{}",
                serde_json::to_string(&CommandEnvelope::<serde_json::Value>::error(vec![
                    diagnostic
                ]))
                .unwrap()
            );
            std::process::exit(3);
        }
        _ => {}
    }

    println!(
        "{}",
        serde_json::to_string(&CommandEnvelope::ok(serde_json::json!({
            "accepted": true,
            "input_length": input.len()
        })))
        .unwrap()
    );
    Ok(())
}

fn mode() -> String {
    std::env::var("DEDIREN_TEST_PLUGIN_MODE").unwrap_or_else(|_| "ok".to_string())
}

mod anyhow_free_result {
    pub type Result = std::result::Result<(), Box<dyn std::error::Error>>;
}
```

- [ ] **Step 4: Build the fixture plugin**

Run:

```bash
cargo build -p dediren-plugin-runtime-testbed
```

Expected: PASS.

- [ ] **Step 5: Commit the fixture plugin**

```bash
git add Cargo.toml crates/dediren-plugin-runtime-testbed
git commit -m "Add plugin runtime test fixture"
```

---

### Task 2: Add Typed Plugin Runtime Errors

**Files:**
- Modify: `crates/dediren-core/Cargo.toml`
- Modify: `crates/dediren-core/src/plugins.rs`

- [ ] **Step 1: Add test-only temp directory support**

Add this dev dependency to `crates/dediren-core/Cargo.toml`:

```toml
[dev-dependencies]
tempfile.workspace = true
```

- [ ] **Step 2: Write the failing core runtime tests**

Create `crates/dediren-core/tests/plugin_runtime.rs`:

```rust
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Duration;

use dediren_core::plugins::{
    run_plugin_for_capability_with_registry, PluginExecutionError, PluginRegistry,
    PluginRunOptions,
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

    assert_eq!(error.diagnostic().code, "DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY");
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

    let error = run_with_mode(temp.path(), "capabilities-invalid-json", "render", &["render"])
        .expect_err("bad capability JSON should fail");

    assert_eq!(error.diagnostic().code, "DEDIREN_PLUGIN_CAPABILITY_INVALID_JSON");
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
    options.allowed_env.push((
        "DEDIREN_TEST_PLUGIN_MODE".to_string(),
        "sleep".to_string(),
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

    assert_eq!(error.diagnostic().code, "DEDIREN_PLUGIN_OUTPUT_INVALID_JSON");
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

    assert_eq!(error.diagnostic().code, "DEDIREN_PLUGIN_OUTPUT_INVALID_ENVELOPE");
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
    options.allowed_env.push((
        "DEDIREN_TEST_PLUGIN_MODE".to_string(),
        mode.to_string(),
    ));
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
```

Run:

```bash
cargo test -p dediren-core --test plugin_runtime
```

Expected: FAIL because `PluginExecutionError`, `PluginRunOutcome`, `PluginRegistry::from_dirs`, and `run_plugin_for_capability_with_registry` do not exist.

- [ ] **Step 3: Add typed outcome and error types**

In `crates/dediren-core/src/plugins.rs`, add these public types near the existing plugin option types:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PluginRunOutcome {
    pub stdout: String,
    pub exit_code: i32,
}

#[derive(Debug, thiserror::Error)]
pub enum PluginExecutionError {
    #[error("unknown plugin id: {plugin_id}")]
    UnknownPlugin { plugin_id: String },
    #[error("plugin manifest for {plugin_id} is invalid: {message}")]
    ManifestInvalid { plugin_id: String, message: String },
    #[error("plugin {plugin_id} executable does not exist: {path}")]
    MissingExecutable { plugin_id: String, path: String },
    #[error("plugin {plugin_id} does not support capability {capability}")]
    UnsupportedCapability {
        plugin_id: String,
        capability: String,
    },
    #[error("plugin {plugin_id} capability probe failed: {message}")]
    CapabilityProbeFailed { plugin_id: String, message: String },
    #[error("plugin {plugin_id} capability output is not valid JSON: {message}")]
    CapabilityInvalidJson { plugin_id: String, message: String },
    #[error("plugin {plugin_id} capability output does not match the runtime schema: {message}")]
    CapabilitySchemaInvalid { plugin_id: String, message: String },
    #[error("plugin {plugin_id} runtime id {runtime_id} does not match manifest id")]
    IdMismatch {
        plugin_id: String,
        runtime_id: String,
    },
    #[error("plugin {plugin_id} timed out after {timeout_ms} ms")]
    Timeout {
        plugin_id: String,
        timeout_ms: u128,
    },
    #[error("plugin {plugin_id} exited with status {status}: {stderr}")]
    ProcessFailed {
        plugin_id: String,
        status: i32,
        stderr: String,
    },
    #[error("plugin {plugin_id} stdout is not valid JSON: {message}")]
    OutputInvalidJson { plugin_id: String, message: String },
    #[error("plugin {plugin_id} stdout does not match the command envelope schema: {message}")]
    OutputInvalidEnvelope { plugin_id: String, message: String },
    #[error("plugin {plugin_id} I/O error: {message}")]
    Io { plugin_id: String, message: String },
}
```

Add this diagnostic conversion:

```rust
impl PluginExecutionError {
    pub fn diagnostic(&self) -> dediren_contracts::Diagnostic {
        dediren_contracts::Diagnostic {
            code: self.code().to_string(),
            severity: dediren_contracts::DiagnosticSeverity::Error,
            message: self.to_string(),
            path: Some(self.path()),
        }
    }

    fn code(&self) -> &'static str {
        match self {
            Self::UnknownPlugin { .. } => "DEDIREN_PLUGIN_UNKNOWN",
            Self::ManifestInvalid { .. } => "DEDIREN_PLUGIN_MANIFEST_INVALID",
            Self::MissingExecutable { .. } => "DEDIREN_PLUGIN_MISSING_EXECUTABLE",
            Self::UnsupportedCapability { .. } => "DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY",
            Self::CapabilityProbeFailed { .. } => "DEDIREN_PLUGIN_CAPABILITY_PROBE_FAILED",
            Self::CapabilityInvalidJson { .. } => "DEDIREN_PLUGIN_CAPABILITY_INVALID_JSON",
            Self::CapabilitySchemaInvalid { .. } => "DEDIREN_PLUGIN_CAPABILITY_SCHEMA_INVALID",
            Self::IdMismatch { .. } => "DEDIREN_PLUGIN_ID_MISMATCH",
            Self::Timeout { .. } => "DEDIREN_PLUGIN_TIMEOUT",
            Self::ProcessFailed { .. } => "DEDIREN_PLUGIN_PROCESS_FAILED",
            Self::OutputInvalidJson { .. } => "DEDIREN_PLUGIN_OUTPUT_INVALID_JSON",
            Self::OutputInvalidEnvelope { .. } => "DEDIREN_PLUGIN_OUTPUT_INVALID_ENVELOPE",
            Self::Io { .. } => "DEDIREN_PLUGIN_IO_ERROR",
        }
    }

    fn path(&self) -> String {
        match self {
            Self::UnknownPlugin { plugin_id }
            | Self::ManifestInvalid { plugin_id, .. }
            | Self::MissingExecutable { plugin_id, .. }
            | Self::UnsupportedCapability { plugin_id, .. }
            | Self::CapabilityProbeFailed { plugin_id, .. }
            | Self::CapabilityInvalidJson { plugin_id, .. }
            | Self::CapabilitySchemaInvalid { plugin_id, .. }
            | Self::IdMismatch { plugin_id, .. }
            | Self::Timeout { plugin_id, .. }
            | Self::ProcessFailed { plugin_id, .. }
            | Self::OutputInvalidJson { plugin_id, .. }
            | Self::OutputInvalidEnvelope { plugin_id, .. }
            | Self::Io { plugin_id, .. } => format!("plugin:{plugin_id}"),
        }
    }
}
```

- [ ] **Step 4: Add registry construction for tests**

Add this constructor:

```rust
impl PluginRegistry {
    pub fn from_dirs(manifest_dirs: Vec<PathBuf>) -> Self {
        Self { manifest_dirs }
    }
}
```

Change manifest loading internally so the registry keeps the manifest file path beside the parsed manifest:

```rust
#[derive(Debug, Clone)]
struct LoadedPluginManifest {
    manifest: PluginManifest,
    path: PathBuf,
}
```

Change `load_manifest` to return `Result<LoadedPluginManifest, PluginExecutionError>` and preserve the existing lookup order. Map missing ids to `PluginExecutionError::UnknownPlugin`.

- [ ] **Step 5: Add capability-aware runner functions**

Replace the public runner surface with these functions while keeping a compatibility wrapper for internal call sites:

```rust
pub fn run_plugin_for_capability(
    plugin_id: &str,
    required_capability: &str,
    args: &[&str],
    input: &str,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    run_plugin_for_capability_with_registry(
        &PluginRegistry::bundled(),
        plugin_id,
        required_capability,
        args,
        input,
        PluginRunOptions::default(),
    )
}

pub fn run_plugin_for_capability_with_options(
    plugin_id: &str,
    required_capability: &str,
    args: &[&str],
    input: &str,
    options: PluginRunOptions,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    run_plugin_for_capability_with_registry(
        &PluginRegistry::bundled(),
        plugin_id,
        required_capability,
        args,
        input,
        options,
    )
}

pub fn run_plugin_for_capability_with_registry(
    registry: &PluginRegistry,
    plugin_id: &str,
    required_capability: &str,
    args: &[&str],
    input: &str,
    options: PluginRunOptions,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    let loaded = registry.load_manifest(plugin_id)?;
    if !loaded
        .manifest
        .capabilities
        .iter()
        .any(|capability| capability == required_capability)
    {
        return Err(PluginExecutionError::UnsupportedCapability {
            plugin_id: plugin_id.to_string(),
            capability: required_capability.to_string(),
        });
    }

    let executable = executable_path(&loaded)?;
    if !executable.exists() {
        return Err(PluginExecutionError::MissingExecutable {
            plugin_id: plugin_id.to_string(),
            path: executable.display().to_string(),
        });
    }

    let capabilities = probe_capabilities(plugin_id, &executable, &options)?;
    if capabilities.id != loaded.manifest.id {
        return Err(PluginExecutionError::IdMismatch {
            plugin_id: plugin_id.to_string(),
            runtime_id: capabilities.id,
        });
    }
    if !capabilities
        .capabilities
        .iter()
        .any(|capability| capability == required_capability)
    {
        return Err(PluginExecutionError::UnsupportedCapability {
            plugin_id: plugin_id.to_string(),
            capability: required_capability.to_string(),
        });
    }

    let output = run_executable_with_timeout(plugin_id, &executable, args, input, &options)?;
    normalize_plugin_output(plugin_id, output)
}
```

Keep this compatibility wrapper only if a caller remains during the task:

```rust
pub fn run_plugin(
    plugin_id: &str,
    args: &[&str],
    input: &str,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    let required_capability = args.first().copied().unwrap_or("capability");
    run_plugin_for_capability(plugin_id, required_capability, args, input)
}
```

- [ ] **Step 6: Resolve executables safely**

Change `executable_path` to use env overrides first, absolute manifest paths second, manifest-relative paths third, and bundled sibling binaries last:

```rust
fn executable_path(loaded: &LoadedPluginManifest) -> Result<PathBuf, PluginExecutionError> {
    let env_name = format!(
        "DEDIREN_PLUGIN_{}",
        loaded.manifest.id.to_ascii_uppercase().replace('-', "_")
    );
    if let Ok(path) = std::env::var(env_name) {
        return Ok(PathBuf::from(path));
    }

    let executable = PathBuf::from(&loaded.manifest.executable);
    if executable.is_absolute() {
        return Ok(executable);
    }
    if executable.components().count() > 1 {
        return Ok(loaded
            .path
            .parent()
            .unwrap_or_else(|| Path::new("."))
            .join(executable));
    }
    Ok(std::env::current_exe()
        .map_err(|error| PluginExecutionError::Io {
            plugin_id: loaded.manifest.id.clone(),
            message: error.to_string(),
        })?
        .with_file_name(&loaded.manifest.executable))
}
```

- [ ] **Step 7: Validate capability and envelope JSON**

Add helpers that compile the checked-in schemas with `include_str!`:

```rust
fn validate_runtime_capabilities_json(
    plugin_id: &str,
    stdout: &[u8],
) -> Result<RuntimeCapabilities, PluginExecutionError> {
    let value: serde_json::Value = serde_json::from_slice(stdout).map_err(|error| {
        PluginExecutionError::CapabilityInvalidJson {
            plugin_id: plugin_id.to_string(),
            message: error.to_string(),
        }
    })?;
    validate_value_against_schema(
        include_str!("../../../schemas/runtime-capability.schema.json"),
        &value,
    )
    .map_err(|message| PluginExecutionError::CapabilitySchemaInvalid {
        plugin_id: plugin_id.to_string(),
        message,
    })?;
    serde_json::from_value(value).map_err(|error| PluginExecutionError::CapabilitySchemaInvalid {
        plugin_id: plugin_id.to_string(),
        message: error.to_string(),
    })
}

struct ValidatedEnvelope {
    stdout: String,
    status: String,
}

fn validate_command_envelope_json(
    plugin_id: &str,
    stdout: &[u8],
) -> Result<ValidatedEnvelope, PluginExecutionError> {
    let value: serde_json::Value = serde_json::from_slice(stdout).map_err(|error| {
        PluginExecutionError::OutputInvalidJson {
            plugin_id: plugin_id.to_string(),
            message: error.to_string(),
        }
    })?;
    validate_value_against_schema(include_str!("../../../schemas/envelope.schema.json"), &value)
        .map_err(|message| PluginExecutionError::OutputInvalidEnvelope {
            plugin_id: plugin_id.to_string(),
            message,
        })?;
    let status = value
        .get("status")
        .and_then(serde_json::Value::as_str)
        .unwrap_or("error")
        .to_string();
    let stdout = String::from_utf8(stdout.to_vec()).map_err(|error| PluginExecutionError::OutputInvalidJson {
        plugin_id: plugin_id.to_string(),
        message: error.to_string(),
    })?;
    Ok(ValidatedEnvelope { stdout, status })
}

fn validate_value_against_schema(
    schema_text: &str,
    value: &serde_json::Value,
) -> Result<(), String> {
    let schema: serde_json::Value =
        serde_json::from_str(schema_text).map_err(|error| error.to_string())?;
    let validator = jsonschema::validator_for(&schema).map_err(|error| error.to_string())?;
    validator.validate(value).map_err(|error| error.to_string())
}
```

Update `probe_capabilities` to call `validate_runtime_capabilities_json`.

- [ ] **Step 8: Normalize plugin command output**

Add:

```rust
fn normalize_plugin_output(
    plugin_id: &str,
    output: Output,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    let exit_code = output.status.code().unwrap_or(3);
    let envelope = validate_command_envelope_json(plugin_id, &output.stdout)?;
    if output.status.success() {
        return Ok(PluginRunOutcome {
            stdout: envelope.stdout,
            exit_code: 0,
        });
    }

    if envelope.status == "error" {
        return Ok(PluginRunOutcome {
            stdout: envelope.stdout,
            exit_code,
        });
    }

    Err(PluginExecutionError::ProcessFailed {
        plugin_id: plugin_id.to_string(),
        status: exit_code,
        stderr: String::from_utf8_lossy(&output.stderr).to_string(),
    })
}
```

Update `run_executable_with_timeout` to accept `plugin_id: &str` and map timeout to `PluginExecutionError::Timeout`.

- [ ] **Step 9: Run core runtime tests**

Run:

```bash
cargo test -p dediren-core --test plugin_runtime
```

Expected: PASS.

- [ ] **Step 10: Commit runtime core hardening**

```bash
git add crates/dediren-core
git commit -m "Harden plugin runtime error handling"
```

---

### Task 3: Update CLI Capability Calls And Exit Handling

**Files:**
- Modify: `crates/dediren-cli/src/main.rs`
- Modify: `crates/dediren-cli/tests/plugin_compat.rs`

- [ ] **Step 1: Add failing CLI pass-through test**

Append this test to `crates/dediren-cli/tests/plugin_compat.rs`:

```rust
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
```

Run:

```bash
cargo test -p dediren --test plugin_compat plugin_error_envelope_is_preserved_by_cli
```

Expected: FAIL because the CLI currently wraps the plugin envelope in `DEDIREN_PLUGIN_ERROR`.

- [ ] **Step 2: Pass required capability names explicitly**

In `crates/dediren-cli/src/main.rs`, update each plugin invocation:

```rust
dediren_core::plugins::run_plugin_for_capability(
    &plugin,
    "projection",
    &["project", "--target", &target, "--view", &view],
    &text,
)
```

```rust
dediren_core::plugins::run_plugin_for_capability_with_options(
    &plugin,
    "layout",
    &["layout"],
    &serde_json::to_string(&request)?,
    options,
)
```

```rust
dediren_core::plugins::run_plugin_for_capability(
    &plugin,
    "render",
    &["render"],
    &serde_json::to_string(&render_input)?,
)
```

```rust
dediren_core::plugins::run_plugin_for_capability(
    &plugin,
    "export",
    &["export"],
    &serde_json::to_string(&export_input)?,
)
```

- [ ] **Step 3: Print plugin outcomes with exit status**

Replace `print_plugin_result` with:

```rust
fn print_plugin_result(
    result: Result<dediren_core::plugins::PluginRunOutcome, dediren_core::plugins::PluginExecutionError>,
) -> anyhow::Result<()> {
    match result {
        Ok(outcome) => {
            print!("{}", outcome.stdout);
            if outcome.exit_code == 0 {
                Ok(())
            } else {
                std::process::exit(outcome.exit_code);
            }
        }
        Err(error) => {
            let envelope =
                dediren_contracts::CommandEnvelope::<serde_json::Value>::error(vec![
                    error.diagnostic(),
                ]);
            println!("{}", serde_json::to_string(&envelope)?);
            std::process::exit(3);
        }
    }
}
```

- [ ] **Step 4: Run CLI compatibility tests**

Run:

```bash
cargo test -p dediren --test plugin_compat
```

Expected: PASS. The missing ELK runtime case must expose `DEDIREN_ELK_RUNTIME_UNAVAILABLE`, not `DEDIREN_PLUGIN_ERROR`.

- [ ] **Step 5: Run focused command tests**

Run:

```bash
cargo test -p dediren --test cli_project
cargo test -p dediren --test cli_layout
cargo test -p dediren --test cli_render
cargo test -p dediren --test cli_export
cargo test -p dediren --test cli_pipeline
```

Expected: all PASS.

- [ ] **Step 6: Commit CLI runtime handling**

```bash
git add crates/dediren-cli
git commit -m "Preserve structured plugin runtime envelopes"
```

---

### Task 4: Document Runtime Failure Contract

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update README plugin runtime notes**

Add this section to `README.md` after "Plugin Lookup":

```markdown
## Plugin Runtime Errors

Plugin failures are reported as JSON command envelopes. If a plugin returns a
valid error envelope, the CLI preserves that envelope and exits non-zero. If the
runtime boundary fails before a plugin can return a valid envelope, the CLI
normalizes the failure into a `CommandEnvelope` diagnostic such as
`DEDIREN_PLUGIN_MISSING_EXECUTABLE`, `DEDIREN_PLUGIN_TIMEOUT`,
`DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`, or
`DEDIREN_PLUGIN_OUTPUT_INVALID_JSON`.

Agents should read stdout JSON for success and failure decisions. `stderr` is
reserved for human debugging and is not required for repair loops.
```

- [ ] **Step 2: Run docs-adjacent compatibility test**

Run:

```bash
cargo test -p dediren --test plugin_compat
```

Expected: PASS.

- [ ] **Step 3: Commit runtime docs**

```bash
git add README.md
git commit -m "Document plugin runtime failures"
```

---

### Task 5: Validation Gates

**Files:**
- No code files required unless validation finds defects.

- [ ] **Step 1: Format**

Run:

```bash
cargo fmt --all
```

Expected: PASS.

- [ ] **Step 2: Run focused tests**

Run:

```bash
cargo test -p dediren-core --test plugin_runtime
cargo test -p dediren --test plugin_compat
cargo test -p dediren --test cli_project
cargo test -p dediren --test cli_layout
cargo test -p dediren --test cli_render
cargo test -p dediren --test cli_export
cargo test -p dediren --test cli_pipeline
```

Expected: all PASS.

- [ ] **Step 3: Run full workspace verification**

Run:

```bash
cargo test --workspace
cargo check --workspace
```

Expected: both PASS.

- [ ] **Step 4: Run static hygiene scans**

Run:

```bash
git diff --check
PATTERN='TB''D|TO''DO|FIX''ME|XX''X|place''holder|implement ''later|fill ''in|similar ''to|move the ''existing|unknown ''decision|to be ''decided|raw XML stdout|PATH discovery'
rg -n --hidden --glob '!target/**' --glob '!.git/**' --glob '!Cargo.lock' --glob '!docs/superpowers/plans/**' "$PATTERN" .
```

Expected: `git diff --check` exits 0. The `rg` command exits 1 with no matches.

- [ ] **Step 5: Run test-quality audit validation**

Invoke `souroldgeezer-audit:test-quality-audit` in Deep mode:

```text
Mode: Deep
Scope: crates/dediren-core/tests/plugin_runtime.rs, crates/dediren-cli/tests/plugin_compat.rs, crates/dediren-plugin-runtime-testbed/src/main.rs, existing CLI pipeline tests
Expected gate: no block findings. Warn/info findings must be fixed or recorded as accepted residual risk.
```

The audit must specifically check that tests prove:

- missing executables become structured diagnostics;
- unsupported capabilities are rejected before command execution;
- invalid runtime capability JSON is rejected;
- runtime id mismatch is rejected;
- plugin timeouts are bounded and surfaced as structured diagnostics;
- invalid plugin stdout is rejected;
- valid plugin error envelopes are preserved;
- CLI exit status remains non-zero for preserved plugin error envelopes.

- [ ] **Step 6: Run DevSecOps audit validation**

Invoke `souroldgeezer-audit:devsecops-audit` in Quick mode:

```text
Mode: Quick
Scope: plugin execution code, manifest-relative executable resolution, environment allowlist behavior, fixture plugin crate, README runtime claims, and Cargo dependency inventory
Expected gate: no block findings. Warn/info findings must be fixed or recorded as accepted residual risk.
```

The audit must specifically check:

- no implicit `PATH` executable discovery is introduced;
- env allowlist behavior still applies to child plugin processes;
- timeout handling kills and waits on timed-out plugin processes;
- valid plugin error envelopes do not require stderr parsing;
- the private fixture plugin is not presented as a bundled user-facing plugin.

- [ ] **Step 7: Final commit if validation changes files**

If validation fixes changed files after earlier commits:

```bash
git status --short
git add Cargo.toml README.md crates/dediren-cli crates/dediren-core crates/dediren-plugin-runtime-testbed
git commit -m "Harden plugin runtime validation"
```

- [ ] **Step 8: Confirm working tree**

Run:

```bash
git status --short --branch
```

Expected: clean feature branch with all implementation commits present.

## Self-Review Notes

- Spec coverage: this plan implements structured diagnostics for plugin process failures, timeout controls, capability/schema mismatch handling, and plugin error-envelope behavior from the original design.
- Software-design check: `dediren-core` owns process orchestration and failure normalization; CLI commands remain thin; first-party feature plugins do not gain runtime policy; the fixture plugin is private test support, not a product extension point.
- Test-quality check: tests use real executable process boundaries and assert behavior-specific diagnostic codes instead of broad string-only success.
- DevSecOps check: the plan preserves explicit manifest/env plugin discovery, validates runtime JSON, avoids stderr-as-contract behavior, and keeps sandboxing/signing out of scope.
- Deferred scope: plugin signing, provenance, OS sandboxing, richer semantic validation, layout policy files, PNG rendering, and additional layout backends remain outside this slice.
