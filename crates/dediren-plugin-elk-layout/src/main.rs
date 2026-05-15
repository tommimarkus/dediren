use std::io::{ErrorKind, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};

use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, LayoutRequest, LayoutResult,
};

const MINIMUM_BUNDLED_ELK_JAVA_MAJOR: u32 = 21;

#[derive(Debug, Clone, PartialEq, Eq)]
enum RuntimeCommand {
    Fixture(String),
    Explicit(String),
    Bundled(PathBuf),
    Missing,
}

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("capabilities") {
        let env = std::env::vars().collect::<Vec<_>>();
        let runtime_available = std::env::current_exe()
            .ok()
            .map(|executable| {
                runtime_available_with_java_diagnostic(
                    &runtime_command_from_env(&env, &executable),
                    bundled_java_runtime_diagnostic,
                )
            })
            .unwrap_or(false);
        println!(
            "{}",
            serde_json::json!({
                "plugin_protocol_version": "plugin.protocol.v1",
                "id": "elk-layout",
                "capabilities": ["layout"],
                "runtime": {
                    "kind": "external-elk",
                    "available": runtime_available
                }
            })
        );
        return Ok(());
    }

    if args.get(1).map(String::as_str) != Some("layout") {
        anyhow::bail!("expected command: layout");
    }

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let _request: LayoutRequest = serde_json::from_str(&input)?;

    let env = std::env::vars().collect::<Vec<_>>();
    let executable = std::env::current_exe()?;
    match runtime_command_from_env(&env, &executable) {
        RuntimeCommand::Fixture(fixture) => {
            let text = std::fs::read_to_string(fixture)?;
            let result: LayoutResult = serde_json::from_str(&text)?;
            println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
            Ok(())
        }
        RuntimeCommand::Explicit(command_line) => {
            let output = run_external_elk(&command_line, &input).unwrap_or_else(|error| {
                exit_with_diagnostic(
                    "DEDIREN_ELK_RUNTIME_FAILED",
                    &format!("failed to run ELK runtime command: {error}"),
                );
            });
            emit_external_output(output)
        }
        RuntimeCommand::Bundled(helper) => {
            if !helper.exists() {
                exit_with_diagnostic(
                    "DEDIREN_ELK_RUNTIME_UNAVAILABLE",
                    &format!("bundled ELK helper is missing: {}", helper.display()),
                );
            }
            if let Some((code, message)) = bundled_java_runtime_diagnostic() {
                exit_with_diagnostic(
                    code,
                    &message,
                );
            }
            let output = run_external_elk(&command_line_for_path(&helper), &input).unwrap_or_else(
                |error| {
                    exit_with_diagnostic(
                        "DEDIREN_ELK_RUNTIME_FAILED",
                        &format!("failed to run ELK runtime command: {error}"),
                    );
                },
            );
            emit_external_output(output)
        }
        RuntimeCommand::Missing => exit_with_diagnostic(
            "DEDIREN_ELK_RUNTIME_UNAVAILABLE",
            "ELK runtime is not configured; set DEDIREN_ELK_COMMAND or install bundle runtimes/elk-layout-java",
        ),
    }
}

fn runtime_command_from_env(env: &[(String, String)], executable: &Path) -> RuntimeCommand {
    if let Some(fixture) = env_value(env, "DEDIREN_ELK_RESULT_FIXTURE") {
        return RuntimeCommand::Fixture(fixture);
    }
    if let Some(command) = env_value(env, "DEDIREN_ELK_COMMAND") {
        return RuntimeCommand::Explicit(command);
    }
    bundled_elk_command_for_executable(executable)
        .map(RuntimeCommand::Bundled)
        .unwrap_or(RuntimeCommand::Missing)
}

fn env_value(env: &[(String, String)], name: &str) -> Option<String> {
    env.iter()
        .find(|(env_name, _)| env_name == name)
        .map(|(_, value)| value.clone())
}

fn bundled_elk_command_for_executable(executable: &Path) -> Option<PathBuf> {
    let bin_dir = executable.parent()?;
    if bin_dir.file_name()? != "bin" {
        return None;
    }
    let root = bin_dir.parent()?;
    if !root.join("bundle.json").is_file() || !root.join("plugins").is_dir() {
        return None;
    }
    Some(root.join("runtimes/elk-layout-java/bin/dediren-elk-layout-java"))
}

fn bundled_java_runtime_diagnostic() -> Option<(&'static str, String)> {
    let output = match Command::new("java")
        .arg("-version")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .output()
    {
        Ok(output) => output,
        Err(error) => {
            return Some((
                "DEDIREN_ELK_JAVA_UNAVAILABLE",
                format!("Java {MINIMUM_BUNDLED_ELK_JAVA_MAJOR} or newer is required on PATH for the bundled ELK helper: {error}"),
            ));
        }
    };

    if !output.status.success() {
        let exit_code = output.status.code().unwrap_or(3);
        return Some((
            "DEDIREN_ELK_JAVA_UNAVAILABLE",
            format!("Java {MINIMUM_BUNDLED_ELK_JAVA_MAJOR} or newer is required on PATH for the bundled ELK helper; `java -version` exited with status {exit_code}"),
        ));
    }

    let version_output = format!(
        "{}\n{}",
        String::from_utf8_lossy(&output.stderr),
        String::from_utf8_lossy(&output.stdout)
    );
    match java_major_version_from_output(&version_output) {
        Some(version) if version >= MINIMUM_BUNDLED_ELK_JAVA_MAJOR => None,
        Some(version) => Some((
            "DEDIREN_ELK_JAVA_UNSUPPORTED",
            format!("Java {MINIMUM_BUNDLED_ELK_JAVA_MAJOR} or newer is required on PATH for the bundled ELK helper; found Java {version}"),
        )),
        None => Some((
            "DEDIREN_ELK_JAVA_UNSUPPORTED",
            format!("Java {MINIMUM_BUNDLED_ELK_JAVA_MAJOR} or newer is required on PATH for the bundled ELK helper; could not parse `java -version` output"),
        )),
    }
}

fn java_major_version_from_output(output: &str) -> Option<u32> {
    output.lines().find_map(|line| {
        let start = line.find('"')?;
        let version = &line[start + 1..];
        let end = version.find('"')?;
        java_major_version_from_token(&version[..end])
    })
}

fn java_major_version_from_token(version: &str) -> Option<u32> {
    let mut parts = version.split('.');
    let first = leading_u32(parts.next()?)?;
    if first == 1 {
        leading_u32(parts.next()?)
    } else {
        Some(first)
    }
}

fn leading_u32(value: &str) -> Option<u32> {
    let digits = value
        .chars()
        .take_while(char::is_ascii_digit)
        .collect::<String>();
    if digits.is_empty() {
        None
    } else {
        digits.parse::<u32>().ok()
    }
}

fn runtime_available_with_java_diagnostic(
    command: &RuntimeCommand,
    java_diagnostic: impl FnOnce() -> Option<(&'static str, String)>,
) -> bool {
    match command {
        RuntimeCommand::Fixture(_) | RuntimeCommand::Explicit(_) => true,
        RuntimeCommand::Bundled(helper) => helper.exists() && java_diagnostic().is_none(),
        RuntimeCommand::Missing => false,
    }
}

fn command_line_for_path(path: &Path) -> String {
    #[cfg(windows)]
    {
        path.display().to_string()
    }

    #[cfg(not(windows))]
    {
        shell_quote(&path.display().to_string())
    }
}

#[cfg(not(windows))]
fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', r#"'\''"#))
}

fn run_external_elk(command_line: &str, input: &str) -> anyhow::Result<Output> {
    let mut child = shell_command(command_line)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;

    if let Some(mut stdin) = child.stdin.take() {
        match stdin.write_all(input.as_bytes()) {
            Ok(()) => {}
            Err(error) if error.kind() == ErrorKind::BrokenPipe => {}
            Err(error) => return Err(error.into()),
        }
    }

    Ok(child.wait_with_output()?)
}

fn shell_command(command_line: &str) -> Command {
    #[cfg(windows)]
    {
        let mut command = Command::new("cmd");
        command.args(["/C", command_line]);
        command
    }

    #[cfg(not(windows))]
    {
        let mut command = Command::new("sh");
        command.arg("-c").arg(command_line);
        command
    }
}

fn emit_external_output(output: Output) -> anyhow::Result<()> {
    let exit_code = output.status.code().unwrap_or(3);
    let stdout = String::from_utf8_lossy(&output.stdout);
    let value: serde_json::Value = match external_json_from_stdout(&stdout) {
        Ok(value) => value,
        Err(error) => {
            if output.status.success() {
                exit_with_diagnostic(
                    "DEDIREN_ELK_OUTPUT_INVALID_JSON",
                    &format!("ELK runtime output is not valid JSON: {error}"),
                );
            }
            exit_with_diagnostic(
                "DEDIREN_ELK_RUNTIME_FAILED",
                &format!(
                    "ELK runtime exited with status {exit_code} and did not return JSON: {}; stderr: {}",
                    error,
                    String::from_utf8_lossy(&output.stderr)
                ),
            );
        }
    };

    if value
        .get("envelope_schema_version")
        .and_then(serde_json::Value::as_str)
        .is_some()
    {
        return emit_external_envelope(value, exit_code, &output.stderr);
    }

    if !output.status.success() {
        exit_with_diagnostic(
            "DEDIREN_ELK_RUNTIME_FAILED",
            &format!(
                "ELK runtime exited with status {exit_code}: {}",
                String::from_utf8_lossy(&output.stderr)
            ),
        );
    }

    let result: LayoutResult = serde_json::from_value(value).unwrap_or_else(|error| {
        exit_with_diagnostic(
            "DEDIREN_ELK_OUTPUT_INVALID_LAYOUT_RESULT",
            &format!("ELK runtime output is not a valid layout result: {error}"),
        );
    });
    println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
    Ok(())
}

fn external_json_from_stdout(stdout: &str) -> Result<serde_json::Value, serde_json::Error> {
    match serde_json::from_str(stdout) {
        Ok(value) => Ok(value),
        Err(error) => {
            let trimmed = strip_hotspot_perfdata_warnings(stdout);
            if trimmed.len() != stdout.len() {
                if let Ok(value) = serde_json::from_str(trimmed) {
                    return Ok(value);
                }
            }
            Err(error)
        }
    }
}

fn strip_hotspot_perfdata_warnings(mut stdout: &str) -> &str {
    loop {
        let Some(line_end) = stdout.find('\n') else {
            return stdout;
        };
        let line = stdout[..line_end].trim_end_matches('\r');
        if !is_hotspot_perfdata_warning(line) {
            return stdout;
        }
        stdout = &stdout[line_end + 1..];
    }
}

fn is_hotspot_perfdata_warning(line: &str) -> bool {
    line.starts_with('[') && line.contains("][warning][perf") && line.contains("hsperfdata")
}

fn emit_external_envelope(
    value: serde_json::Value,
    exit_code: i32,
    stderr: &[u8],
) -> anyhow::Result<()> {
    let status = value
        .get("status")
        .and_then(serde_json::Value::as_str)
        .unwrap_or("error");

    if status == "error" {
        println!("{}", serde_json::to_string(&value)?);
        std::process::exit(if exit_code == 0 { 3 } else { exit_code });
    }

    if exit_code != 0 {
        exit_with_diagnostic(
            "DEDIREN_ELK_RUNTIME_FAILED",
            &format!(
                "ELK runtime exited with status {exit_code}: {}",
                String::from_utf8_lossy(stderr)
            ),
        );
    }

    let envelope: CommandEnvelope<LayoutResult> =
        serde_json::from_value(value).unwrap_or_else(|error| {
            exit_with_diagnostic(
                "DEDIREN_ELK_OUTPUT_INVALID_ENVELOPE",
                &format!("ELK runtime envelope does not contain a valid layout result: {error}"),
            );
        });
    println!("{}", serde_json::to_string(&envelope)?);
    Ok(())
}

fn exit_with_diagnostic(code: &str, message: &str) -> ! {
    let diagnostic = Diagnostic {
        code: code.to_string(),
        severity: DiagnosticSeverity::Error,
        message: message.to_string(),
        path: None,
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

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::*;

    #[test]
    fn bundled_elk_command_is_relative_to_installed_bin() {
        let bundle = TempBundle::new();
        bundle.create();

        assert_eq!(
            Some(bundle.helper()),
            bundled_elk_command_for_executable(&bundle.executable())
        );
    }

    #[test]
    fn target_debug_plugin_executable_has_no_bundled_helper_path() {
        let executable = PathBuf::from("/tmp/repo/target/debug/dediren-plugin-elk-layout");

        assert_eq!(None, bundled_elk_command_for_executable(&executable));
    }

    #[test]
    fn plain_bin_plugin_executable_has_no_bundled_helper_path() {
        let executable = PathBuf::from("/tmp/home/.cargo/bin/dediren-plugin-elk-layout");

        assert_eq!(None, bundled_elk_command_for_executable(&executable));
    }

    #[test]
    fn runtime_command_prefers_fixture_then_explicit_command_then_bundle() {
        let bundle = TempBundle::new();
        let executable = bundle.executable();
        let helper = bundle.helper();
        bundle.create();

        let fixture_env = vec![
            (
                "DEDIREN_ELK_COMMAND".to_string(),
                "explicit-helper".to_string(),
            ),
            (
                "DEDIREN_ELK_RESULT_FIXTURE".to_string(),
                "fixture-result.json".to_string(),
            ),
        ];
        assert_eq!(
            RuntimeCommand::Fixture("fixture-result.json".to_string()),
            runtime_command_from_env(&fixture_env, &executable)
        );

        let explicit_env = vec![(
            "DEDIREN_ELK_COMMAND".to_string(),
            "explicit-helper".to_string(),
        )];
        assert_eq!(
            RuntimeCommand::Explicit("explicit-helper".to_string()),
            runtime_command_from_env(&explicit_env, &executable)
        );

        assert_eq!(
            RuntimeCommand::Bundled(helper),
            runtime_command_from_env(&[], &executable)
        );
    }

    #[test]
    fn bundled_elk_runtime_accepts_java_21_as_minimum() {
        assert_eq!(21, MINIMUM_BUNDLED_ELK_JAVA_MAJOR);
    }

    #[test]
    fn java_major_version_parses_modern_and_legacy_version_output() {
        assert_eq!(
            Some(25),
            java_major_version_from_output(r#"openjdk version "25.0.3" 2026-04-21"#)
        );
        assert_eq!(
            Some(25),
            java_major_version_from_output(r#"openjdk version "25-ea" 2026-09-15"#)
        );
        assert_eq!(
            Some(26),
            java_major_version_from_output(r#"openjdk version "26-ea" 2027-03-16"#)
        );
        assert_eq!(
            Some(8),
            java_major_version_from_output(r#"java version "1.8.0_392""#)
        );
    }

    #[test]
    fn java_major_version_rejects_unrecognized_output() {
        assert_eq!(None, java_major_version_from_output("not a java version"));
    }

    #[test]
    fn external_json_from_stdout_ignores_leading_hotspot_perfdata_warning() {
        let value = external_json_from_stdout(
            "[0.001s][warning][perf,memops] Cannot use file /tmp/hsperfdata_user/1 because it is locked by another process (errno = 11)\n{\"status\":\"ok\"}",
        )
        .unwrap();

        assert_eq!(value["status"], "ok");
    }

    #[test]
    fn external_json_from_stdout_keeps_unrecognized_leading_output_invalid() {
        assert!(external_json_from_stdout("not-json\n{\"status\":\"ok\"}").is_err());
    }

    #[test]
    fn bundled_runtime_availability_requires_helper_and_supported_java() {
        let bundle = TempBundle::new();
        let command = RuntimeCommand::Bundled(bundle.helper());
        bundle.create();

        assert!(runtime_available_with_java_diagnostic(&command, || None));
        assert!(!runtime_available_with_java_diagnostic(&command, || {
            Some((
                "DEDIREN_ELK_JAVA_UNSUPPORTED",
                "Java 21 or newer is required".to_string(),
            ))
        }));
    }

    struct TempBundle {
        root: PathBuf,
    }

    impl TempBundle {
        fn new() -> Self {
            Self {
                root: std::env::temp_dir().join(format!(
                    "dediren-elk-bundle-test-{}-{}",
                    std::process::id(),
                    unique_suffix()
                )),
            }
        }

        fn create(&self) {
            std::fs::create_dir_all(self.root.join("bin")).unwrap();
            std::fs::create_dir_all(self.root.join("plugins")).unwrap();
            std::fs::create_dir_all(self.root.join("runtimes/elk-layout-java/bin")).unwrap();
            std::fs::write(self.root.join("bundle.json"), "{}").unwrap();
            std::fs::write(self.helper(), "").unwrap();
        }

        fn executable(&self) -> PathBuf {
            self.root.join("bin/dediren-plugin-elk-layout")
        }

        fn helper(&self) -> PathBuf {
            self.root
                .join("runtimes/elk-layout-java/bin/dediren-elk-layout-java")
        }
    }

    impl Drop for TempBundle {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    fn unique_suffix() -> u128 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    }
}
