use std::io::{ErrorKind, Read, Write};
use std::process::{Command, Output, Stdio};

use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, LayoutRequest, LayoutResult,
};

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("capabilities") {
        println!(
            "{}",
            serde_json::json!({
                "plugin_protocol_version": "plugin.protocol.v1",
                "id": "elk-layout",
                "capabilities": ["layout"],
                "runtime": {
                    "kind": "external-elk",
                    "available": std::env::var("DEDIREN_ELK_COMMAND").is_ok()
                        || std::env::var("DEDIREN_ELK_RESULT_FIXTURE").is_ok()
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

    if let Ok(fixture) = std::env::var("DEDIREN_ELK_RESULT_FIXTURE") {
        let text = std::fs::read_to_string(fixture)?;
        let result: LayoutResult = serde_json::from_str(&text)?;
        println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
        return Ok(());
    }

    if let Ok(command_line) = std::env::var("DEDIREN_ELK_COMMAND") {
        let output = run_external_elk(&command_line, &input).unwrap_or_else(|error| {
            exit_with_diagnostic(
                "DEDIREN_ELK_RUNTIME_FAILED",
                &format!("failed to run ELK runtime command: {error}"),
            );
        });
        return emit_external_output(output);
    }

    exit_with_diagnostic(
        "DEDIREN_ELK_RUNTIME_UNAVAILABLE",
        "ELK runtime is not configured; set DEDIREN_ELK_COMMAND or use a test fixture",
    );
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
    let value: serde_json::Value = match serde_json::from_str(&stdout) {
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
