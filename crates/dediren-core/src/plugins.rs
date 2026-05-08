use dediren_contracts::{Diagnostic, DiagnosticSeverity, PluginManifest, RuntimeCapabilities};
use std::io::{ErrorKind, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError};
use std::time::{Duration, Instant};

#[derive(Debug, Clone)]
pub struct PluginRegistry {
    manifest_dirs: Vec<PathBuf>,
}

#[derive(Debug, Clone)]
pub struct PluginRunOptions {
    pub timeout: Duration,
    pub allowed_env: Vec<(String, String)>,
}

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
    Timeout { plugin_id: String, timeout_ms: u128 },
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
    #[error("plugin {plugin_id} successful {capability} output data does not match the capability schema: {message}")]
    OutputInvalidData {
        plugin_id: String,
        capability: String,
        message: String,
    },
    #[error("command {command} input is invalid: {message}")]
    CommandInputInvalid { command: String, message: String },
    #[error("plugin {plugin_id} I/O error: {message}")]
    Io { plugin_id: String, message: String },
}

#[derive(Debug, Clone)]
struct LoadedPluginManifest {
    manifest: PluginManifest,
    path: PathBuf,
}

struct ValidatedEnvelope {
    stdout: String,
    status: String,
    value: serde_json::Value,
}

impl PluginExecutionError {
    pub fn diagnostic(&self) -> Diagnostic {
        Diagnostic {
            code: self.code().to_string(),
            severity: DiagnosticSeverity::Error,
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
            Self::OutputInvalidData { .. } => "DEDIREN_PLUGIN_OUTPUT_INVALID_DATA",
            Self::CommandInputInvalid { .. } => "DEDIREN_COMMAND_INPUT_INVALID",
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
            | Self::OutputInvalidData { plugin_id, .. }
            | Self::Io { plugin_id, .. } => format!("plugin:{plugin_id}"),
            Self::CommandInputInvalid { command, .. } => format!("command:{command}"),
        }
    }
}

impl Default for PluginRunOptions {
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(10),
            allowed_env: Vec::new(),
        }
    }
}

impl PluginRegistry {
    pub fn bundled() -> Self {
        Self {
            manifest_dirs: Self::bundled_dirs(),
        }
    }

    pub fn bundled_dirs() -> Vec<PathBuf> {
        let mut manifest_dirs = vec![
            PathBuf::from("fixtures/plugins"),
            PathBuf::from(".dediren/plugins"),
        ];
        if let Ok(configured) = std::env::var("DEDIREN_PLUGIN_DIRS") {
            manifest_dirs.extend(std::env::split_paths(&configured));
        }
        manifest_dirs
    }

    pub fn from_dirs(manifest_dirs: Vec<PathBuf>) -> Self {
        Self { manifest_dirs }
    }

    pub fn load_manifest(&self, plugin_id: &str) -> anyhow::Result<PluginManifest> {
        Ok(self.load_manifest_with_path(plugin_id)?.manifest)
    }

    fn load_manifest_with_path(
        &self,
        plugin_id: &str,
    ) -> Result<LoadedPluginManifest, PluginExecutionError> {
        for dir in &self.manifest_dirs {
            let path = dir.join(format!("{plugin_id}.manifest.json"));
            if path.exists() {
                let text =
                    std::fs::read_to_string(&path).map_err(|error| PluginExecutionError::Io {
                        plugin_id: plugin_id.to_string(),
                        message: error.to_string(),
                    })?;
                let value: serde_json::Value = serde_json::from_str(&text).map_err(|error| {
                    PluginExecutionError::ManifestInvalid {
                        plugin_id: plugin_id.to_string(),
                        message: error.to_string(),
                    }
                })?;
                validate_value_against_schema(
                    include_str!("../../../schemas/plugin-manifest.schema.json"),
                    &value,
                )
                .map_err(|message| PluginExecutionError::ManifestInvalid {
                    plugin_id: plugin_id.to_string(),
                    message,
                })?;
                let manifest: PluginManifest = serde_json::from_value(value).map_err(|error| {
                    PluginExecutionError::ManifestInvalid {
                        plugin_id: plugin_id.to_string(),
                        message: error.to_string(),
                    }
                })?;
                if manifest.id == plugin_id {
                    return Ok(LoadedPluginManifest { manifest, path });
                }
                return Err(PluginExecutionError::ManifestInvalid {
                    plugin_id: plugin_id.to_string(),
                    message: format!("manifest id '{}' did not match requested id", manifest.id),
                });
            }
        }
        Err(PluginExecutionError::UnknownPlugin {
            plugin_id: plugin_id.to_string(),
        })
    }
}

pub fn run_plugin(plugin_id: &str, args: &[&str], input: &str) -> anyhow::Result<String> {
    run_plugin_with_options(plugin_id, args, input, PluginRunOptions::default())
}

pub fn run_plugin_with_options(
    plugin_id: &str,
    args: &[&str],
    input: &str,
    options: PluginRunOptions,
) -> anyhow::Result<String> {
    let required_capability = args
        .first()
        .map(|command| legacy_command_capability(command))
        .unwrap_or("capability");
    let outcome = run_plugin_for_capability_with_options(
        plugin_id,
        required_capability,
        args,
        input,
        options,
    )?;
    if outcome.exit_code == 0 {
        Ok(outcome.stdout)
    } else {
        Err(anyhow::anyhow!(
            "plugin {plugin_id} exited with status {}: stdout={}",
            outcome.exit_code,
            outcome.stdout
        ))
    }
}

fn legacy_command_capability(command: &str) -> &str {
    match command {
        "capabilities" => "capability",
        "project" => "projection",
        "layout" => "layout",
        "render" => "render",
        "export" => "export",
        unknown => unknown,
    }
}

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
    let loaded = registry.load_manifest_with_path(plugin_id)?;
    let is_capabilities_command = args.first().copied() == Some("capabilities");
    if !is_capabilities_command && !supports_capability(&loaded.manifest, required_capability) {
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
    if !is_capabilities_command
        && !capabilities
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
    if is_capabilities_command {
        return normalize_capability_output(plugin_id, output);
    }
    normalize_plugin_output(plugin_id, required_capability, output)
}

fn supports_capability(manifest: &PluginManifest, required_capability: &str) -> bool {
    manifest
        .capabilities
        .iter()
        .any(|capability| capability == required_capability)
}

fn run_executable_with_timeout(
    plugin_id: &str,
    executable: &Path,
    args: &[&str],
    input: &str,
    options: &PluginRunOptions,
) -> Result<Output, PluginExecutionError> {
    let deadline = Instant::now() + options.timeout;
    let mut child = Command::new(executable)
        .args(args)
        .env_clear()
        .envs(options.allowed_env.iter().cloned())
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| PluginExecutionError::Io {
            plugin_id: plugin_id.to_string(),
            message: error.to_string(),
        })?;

    let stdin_result = child
        .stdin
        .take()
        .map(|stdin| write_stdin_async(stdin, input.as_bytes().to_vec()));
    let stdout_result = child.stdout.take().map(read_pipe_to_end_async);
    let stderr_result = child.stderr.take().map(read_pipe_to_end_async);

    let status = loop {
        if let Some(status) = child.try_wait().map_err(|error| PluginExecutionError::Io {
            plugin_id: plugin_id.to_string(),
            message: error.to_string(),
        })? {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(PluginExecutionError::Timeout {
                plugin_id: plugin_id.to_string(),
                timeout_ms: options.timeout.as_millis(),
            });
        }
        std::thread::sleep(Duration::from_millis(10));
    };

    receive_stdin_result(plugin_id, stdin_result, deadline, options.timeout)?;
    let stdout = receive_pipe_result(plugin_id, stdout_result, deadline, options.timeout)?;
    let stderr = receive_pipe_result(plugin_id, stderr_result, deadline, options.timeout)?;

    Ok(Output {
        status,
        stdout,
        stderr,
    })
}

fn read_pipe_to_end<R: Read>(mut pipe: R) -> std::io::Result<Vec<u8>> {
    let mut output = Vec::new();
    pipe.read_to_end(&mut output)?;
    Ok(output)
}

fn write_stdin_async<W: Write + Send + 'static>(
    mut stdin: W,
    input: Vec<u8>,
) -> Receiver<std::io::Result<()>> {
    let (sender, receiver) = mpsc::channel();
    std::thread::spawn(move || {
        let result = match stdin.write_all(&input) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == ErrorKind::BrokenPipe => Ok(()),
            Err(error) => Err(error),
        };
        let _ = sender.send(result);
    });
    receiver
}

fn read_pipe_to_end_async<R: Read + Send + 'static>(pipe: R) -> Receiver<std::io::Result<Vec<u8>>> {
    let (sender, receiver) = mpsc::channel();
    std::thread::spawn(move || {
        let _ = sender.send(read_pipe_to_end(pipe));
    });
    receiver
}

fn receive_stdin_result(
    plugin_id: &str,
    receiver: Option<Receiver<std::io::Result<()>>>,
    deadline: Instant,
    timeout: Duration,
) -> Result<(), PluginExecutionError> {
    receive_thread_result(
        plugin_id,
        receiver,
        deadline,
        timeout,
        "stdin writer thread ended without result",
    )
    .map(|_| ())
}

fn receive_pipe_result(
    plugin_id: &str,
    receiver: Option<Receiver<std::io::Result<Vec<u8>>>>,
    deadline: Instant,
    timeout: Duration,
) -> Result<Vec<u8>, PluginExecutionError> {
    receive_thread_result(
        plugin_id,
        receiver,
        deadline,
        timeout,
        "pipe reader thread ended without result",
    )
    .map(|output| output.unwrap_or_default())
}

fn receive_thread_result<T>(
    plugin_id: &str,
    receiver: Option<Receiver<std::io::Result<T>>>,
    deadline: Instant,
    timeout: Duration,
    disconnected_message: &str,
) -> Result<Option<T>, PluginExecutionError> {
    let Some(receiver) = receiver else {
        return Ok(None);
    };
    let Some(remaining) = deadline.checked_duration_since(Instant::now()) else {
        return Err(timeout_error(plugin_id, timeout));
    };
    match receiver.recv_timeout(remaining) {
        Ok(Ok(value)) => Ok(Some(value)),
        Ok(Err(error)) => Err(PluginExecutionError::Io {
            plugin_id: plugin_id.to_string(),
            message: error.to_string(),
        }),
        Err(RecvTimeoutError::Timeout) => Err(timeout_error(plugin_id, timeout)),
        Err(RecvTimeoutError::Disconnected) => Err(PluginExecutionError::Io {
            plugin_id: plugin_id.to_string(),
            message: disconnected_message.to_string(),
        }),
    }
}

fn timeout_error(plugin_id: &str, timeout: Duration) -> PluginExecutionError {
    PluginExecutionError::Timeout {
        plugin_id: plugin_id.to_string(),
        timeout_ms: timeout.as_millis(),
    }
}

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

fn probe_capabilities(
    plugin_id: &str,
    executable: &Path,
    options: &PluginRunOptions,
) -> Result<RuntimeCapabilities, PluginExecutionError> {
    let output =
        run_executable_with_timeout(plugin_id, executable, &["capabilities"], "", options)?;
    if !output.status.success() {
        return Err(PluginExecutionError::CapabilityProbeFailed {
            plugin_id: plugin_id.to_string(),
            message: String::from_utf8_lossy(&output.stderr).to_string(),
        });
    }
    validate_runtime_capabilities_json(plugin_id, &output.stdout)
}

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
    validate_value_against_schema(
        include_str!("../../../schemas/envelope.schema.json"),
        &value,
    )
    .map_err(|message| PluginExecutionError::OutputInvalidEnvelope {
        plugin_id: plugin_id.to_string(),
        message,
    })?;
    let status = value
        .get("status")
        .and_then(serde_json::Value::as_str)
        .unwrap_or("error")
        .to_string();
    let stdout = String::from_utf8(stdout.to_vec()).map_err(|error| {
        PluginExecutionError::OutputInvalidJson {
            plugin_id: plugin_id.to_string(),
            message: error.to_string(),
        }
    })?;
    Ok(ValidatedEnvelope {
        stdout,
        status,
        value,
    })
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

fn normalize_plugin_output(
    plugin_id: &str,
    required_capability: &str,
    output: Output,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    let exit_code = output.status.code().unwrap_or(3);
    let envelope = validate_command_envelope_json(plugin_id, &output.stdout)?;
    if envelope.status == "error" {
        return Ok(PluginRunOutcome {
            stdout: envelope.stdout,
            exit_code: if exit_code == 0 { 3 } else { exit_code },
        });
    }

    if output.status.success() {
        validate_success_data(plugin_id, required_capability, &envelope.value)?;
        return Ok(PluginRunOutcome {
            stdout: envelope.stdout,
            exit_code: 0,
        });
    }

    Err(PluginExecutionError::ProcessFailed {
        plugin_id: plugin_id.to_string(),
        status: exit_code,
        stderr: String::from_utf8_lossy(&output.stderr).to_string(),
    })
}

fn validate_success_data(
    plugin_id: &str,
    required_capability: &str,
    envelope: &serde_json::Value,
) -> Result<(), PluginExecutionError> {
    let Some(schema_text) = capability_result_schema(required_capability) else {
        return Ok(());
    };
    let data = envelope
        .get("data")
        .ok_or_else(|| PluginExecutionError::OutputInvalidData {
            plugin_id: plugin_id.to_string(),
            capability: required_capability.to_string(),
            message: "successful envelope does not contain data".to_string(),
        })?;
    validate_value_against_schema(schema_text, data).map_err(|message| {
        PluginExecutionError::OutputInvalidData {
            plugin_id: plugin_id.to_string(),
            capability: required_capability.to_string(),
            message,
        }
    })
}

fn capability_result_schema(required_capability: &str) -> Option<&'static str> {
    match required_capability {
        "projection" => Some(include_str!("../../../schemas/layout-request.schema.json")),
        "layout" => Some(include_str!("../../../schemas/layout-result.schema.json")),
        "render" => Some(include_str!("../../../schemas/render-result.schema.json")),
        "export" => Some(include_str!("../../../schemas/export-result.schema.json")),
        _ => None,
    }
}

fn normalize_capability_output(
    plugin_id: &str,
    output: Output,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    if !output.status.success() {
        return Err(PluginExecutionError::CapabilityProbeFailed {
            plugin_id: plugin_id.to_string(),
            message: String::from_utf8_lossy(&output.stderr).to_string(),
        });
    }
    validate_runtime_capabilities_json(plugin_id, &output.stdout)?;
    let stdout = String::from_utf8(output.stdout).map_err(|error| {
        PluginExecutionError::CapabilityInvalidJson {
            plugin_id: plugin_id.to_string(),
            message: error.to_string(),
        }
    })?;
    Ok(PluginRunOutcome {
        stdout,
        exit_code: 0,
    })
}
