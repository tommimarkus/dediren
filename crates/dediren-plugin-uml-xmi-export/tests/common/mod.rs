#![allow(dead_code)]

use assert_cmd::Command;
use serde_json::Value;

pub fn plugin_command() -> Command {
    Command::cargo_bin("dediren-plugin-uml-xmi-export")
        .expect("uml-xmi plugin binary should be built by Cargo")
}

pub fn workspace_file(path: &str) -> String {
    format!("{}/{}", env!("CARGO_MANIFEST_DIR"), path)
        .replace("/crates/dediren-plugin-uml-xmi-export/", "/")
}

pub fn fixture_json(path: &str) -> Value {
    serde_json::from_str(&std::fs::read_to_string(workspace_file(path)).unwrap()).unwrap()
}

pub fn stdout_json(output: &[u8]) -> Value {
    serde_json::from_slice(output).expect("stdout should be valid JSON")
}

pub fn ok_data(output: &[u8]) -> Value {
    let envelope = stdout_json(output);
    assert_eq!(envelope["envelope_schema_version"], "envelope.schema.v1");
    assert_eq!(envelope["status"], "ok", "plugin should return ok envelope");
    assert!(
        envelope["diagnostics"]
            .as_array()
            .expect("diagnostics should be an array")
            .is_empty(),
        "ok envelope should not include diagnostics"
    );
    envelope["data"].clone()
}

pub fn error_envelope(output: &[u8]) -> Value {
    let envelope = stdout_json(output);
    assert_eq!(envelope["envelope_schema_version"], "envelope.schema.v1");
    assert_eq!(
        envelope["status"], "error",
        "plugin should return error envelope"
    );
    assert!(
        !envelope["diagnostics"]
            .as_array()
            .expect("diagnostics should be an array")
            .is_empty(),
        "error envelope should include diagnostics"
    );
    envelope
}

pub fn assert_error_code(output: &[u8], expected_code: &str) -> Value {
    let envelope = error_envelope(output);
    let codes: Vec<&str> = envelope["diagnostics"]
        .as_array()
        .expect("diagnostics should be an array")
        .iter()
        .map(|diagnostic| {
            diagnostic["code"]
                .as_str()
                .expect("diagnostic code should be a string")
        })
        .collect();
    assert!(
        codes.iter().any(|code| *code == expected_code),
        "expected diagnostic code {expected_code}, got {codes:?}"
    );
    envelope
}

pub fn export_input() -> Value {
    serde_json::json!({
        "export_request_schema_version": "export-request.schema.v1",
        "source": fixture_json("fixtures/source/valid-uml-basic.json"),
        "layout_result": fixture_json("fixtures/layout-result/uml-basic.json"),
        "policy": fixture_json("fixtures/export-policy/default-uml-xmi.json")
    })
}
