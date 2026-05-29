use std::io::Read;

use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, LayoutRequest, LayoutResult,
    PLUGIN_PROTOCOL_VERSION,
};
use serde_json::Value;

mod elkrs_backend;

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("capabilities") => {
            println!(
                "{}",
                serde_json::json!({
                    "plugin_protocol_version": PLUGIN_PROTOCOL_VERSION,
                    "id": "elk-layout",
                    "capabilities": ["layout"],
                    "runtime": {
                        "kind": "rust-elkrs",
                        "available": true
                    }
                })
            );
            Ok(())
        }
        Some("layout") => run_layout(),
        _ => anyhow::bail!("expected command: capabilities or layout"),
    }
}

fn run_layout() -> anyhow::Result<()> {
    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let request_value: Value = serde_json::from_str(&input).unwrap_or_else(|error| {
        exit_with_diagnostics(vec![Diagnostic {
            code: "DEDIREN_ELK_INPUT_INVALID_JSON".to_string(),
            severity: DiagnosticSeverity::Error,
            message: format!("layout request is not valid JSON: {error}"),
            path: None,
        }]);
    });
    if let Err(message) = validate_layout_request_schema(&request_value) {
        exit_with_diagnostics(vec![Diagnostic {
            code: "DEDIREN_ELK_INPUT_SCHEMA_INVALID".to_string(),
            severity: DiagnosticSeverity::Error,
            message,
            path: None,
        }]);
    }
    let request: LayoutRequest = serde_json::from_value(request_value).unwrap_or_else(|error| {
        exit_with_diagnostics(vec![Diagnostic {
            code: "DEDIREN_ELK_INPUT_CONTRACT_INVALID".to_string(),
            severity: DiagnosticSeverity::Error,
            message: format!(
                "layout request could not be decoded after schema validation: {error}"
            ),
            path: None,
        }]);
    });

    if let Ok(fixture) = std::env::var("DEDIREN_ELK_RESULT_FIXTURE") {
        let text = std::fs::read_to_string(&fixture).unwrap_or_else(|error| {
            exit_with_diagnostics(vec![Diagnostic {
                code: "DEDIREN_ELK_FIXTURE_UNAVAILABLE".to_string(),
                severity: DiagnosticSeverity::Error,
                message: format!(
                    "ELK fixture layout result is unavailable at `{fixture}`: {error}"
                ),
                path: None,
            }]);
        });
        let result: LayoutResult = serde_json::from_str(&text).unwrap_or_else(|error| {
            exit_with_diagnostics(vec![Diagnostic {
                code: "DEDIREN_ELK_FIXTURE_INVALID_JSON".to_string(),
                severity: DiagnosticSeverity::Error,
                message: format!("ELK fixture layout result is not valid JSON: {error}"),
                path: None,
            }]);
        });
        println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
        return Ok(());
    }

    match elkrs_backend::layout(&request) {
        Ok(result) => {
            println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
            Ok(())
        }
        Err(diagnostics) => exit_with_diagnostics(diagnostics),
    }
}

fn validate_layout_request_schema(value: &Value) -> Result<(), String> {
    let schema: Value =
        serde_json::from_str(include_str!("../../../schemas/layout-request.schema.json"))
            .expect("layout request schema should be valid JSON");
    let validator =
        jsonschema::validator_for(&schema).expect("layout request schema should compile");
    validator
        .validate(value)
        .map_err(|error| format!("layout request does not match layout-request.schema.v1: {error}"))
}

fn exit_with_diagnostics(diagnostics: Vec<Diagnostic>) -> ! {
    println!(
        "{}",
        serde_json::to_string(&CommandEnvelope::<serde_json::Value>::error(diagnostics)).unwrap()
    );
    std::process::exit(3);
}
