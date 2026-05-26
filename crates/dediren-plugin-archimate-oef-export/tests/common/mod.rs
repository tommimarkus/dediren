#![allow(dead_code)]

use assert_cmd::Command;
use serde_json::Value;
use std::path::PathBuf;
use std::sync::OnceLock;
use tempfile::TempDir;

static TEST_SCHEMA_DIR: OnceLock<TempDir> = OnceLock::new();

const TEST_OEF_SCHEMA: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
           targetNamespace="http://www.opengroup.org/xsd/archimate/3.0/"
           xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
           elementFormDefault="qualified">
  <xs:element name="model">
    <xs:complexType>
      <xs:sequence>
        <xs:any minOccurs="0" maxOccurs="unbounded" processContents="skip"/>
      </xs:sequence>
      <xs:attribute name="identifier" type="xs:ID" use="required"/>
      <xs:anyAttribute processContents="skip"/>
    </xs:complexType>
  </xs:element>
</xs:schema>
"#;

pub fn plugin_command() -> Command {
    let mut command = Command::cargo_bin("dediren-plugin-archimate-oef-export")
        .expect("archimate-oef plugin binary should be built by Cargo");
    command.env("DEDIREN_OEF_SCHEMA_DIR", test_schema_dir());
    command
}

fn test_schema_dir() -> PathBuf {
    TEST_SCHEMA_DIR
        .get_or_init(|| {
            let temp = tempfile::tempdir().expect("test schema tempdir should be created");
            for file_name in [
                "archimate3_Model.xsd",
                "archimate3_View.xsd",
                "archimate3_Diagram.xsd",
            ] {
                std::fs::write(temp.path().join(file_name), TEST_OEF_SCHEMA)
                    .expect("test OEF schema should be written");
            }
            temp
        })
        .path()
        .to_path_buf()
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
