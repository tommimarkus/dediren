use std::io::{Read, Write};
use std::process::{Command, Stdio};
use std::time::Duration;

use dediren_contracts::{CommandEnvelope, Diagnostic, DiagnosticSeverity, PLUGIN_PROTOCOL_VERSION};

fn main() -> anyhow_free_result::Result {
    if std::env::var_os("DEDIREN_TEST_PLUGIN_PIPE_LEAK_CHILD").is_some() {
        std::thread::sleep(Duration::from_secs(2));
        return Ok(());
    }

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
    if mode().as_str() == "no-read-stdin" {
        std::thread::sleep(Duration::from_secs(2));
        return Ok(());
    }

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input).unwrap();
    match mode().as_str() {
        "sleep" => std::thread::sleep(Duration::from_secs(2)),
        "large-stdout" => {
            println!(
                "{}",
                serde_json::to_string(&CommandEnvelope::ok(serde_json::json!({
                    "accepted": true,
                    "input_length": input.len(),
                    "padding": "x".repeat(1024 * 1024)
                })))
                .unwrap()
            );
            return Ok(());
        }
        "large-output" => {
            let noise = vec![b'x'; 1024 * 1024];
            std::io::stderr().write_all(&noise).unwrap();
        }
        "invalid-json" => {
            println!("not-json");
            return Ok(());
        }
        "invalid-envelope" => {
            println!(r#"{{"status":"ok"}}"#);
            return Ok(());
        }
        "leak-stdout-child" => {
            spawn_pipe_leak_child()?;
        }
        "error-envelope" => {
            print_error_envelope();
            std::process::exit(3);
        }
        "error-envelope-zero" => {
            print_error_envelope();
            return Ok(());
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

fn print_error_envelope() {
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
}

fn spawn_pipe_leak_child() -> anyhow_free_result::Result {
    Command::new(std::env::current_exe()?)
        .env("DEDIREN_TEST_PLUGIN_PIPE_LEAK_CHILD", "1")
        .stdin(Stdio::null())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        .spawn()?;
    Ok(())
}

fn mode() -> String {
    std::env::var("DEDIREN_TEST_PLUGIN_MODE").unwrap_or_else(|_| "ok".to_string())
}

mod anyhow_free_result {
    pub type Result = std::result::Result<(), Box<dyn std::error::Error>>;
}
