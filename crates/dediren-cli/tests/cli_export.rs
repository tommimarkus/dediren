mod common;

use assert_fs::prelude::*;
use common::{assert_error_code, ok_data, plugin_binary, workspace_file};

#[test]
fn export_invokes_archimate_oef_plugin() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ARCHIMATE_OEF",
            plugin_binary("dediren-plugin-archimate-oef-export"),
        )
        .arg("export")
        .arg("--plugin")
        .arg("archimate-oef")
        .arg("--policy")
        .arg(workspace_file("fixtures/export-policy/default-oef.json"))
        .arg("--source")
        .arg(workspace_file("fixtures/source/valid-archimate-oef.json"))
        .arg("--layout")
        .arg(workspace_file(
            "fixtures/layout-result/archimate-oef-basic.json",
        ))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(
        data["export_result_schema_version"],
        "export-result.schema.v1"
    );
    assert_eq!(data["artifact_kind"], "archimate-oef+xml");
    assert!(
        data["content"]
            .as_str()
            .expect("export result content should be a string")
            .contains("<model"),
        "export content should contain OEF model XML"
    );
}

#[test]
fn export_rejects_invalid_archimate_relationship_endpoint() {
    let mut source: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(workspace_file("fixtures/source/valid-archimate-oef.json"))
            .unwrap(),
    )
    .unwrap();
    source["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    source["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    source["relationships"][0]["type"] = serde_json::json!("Realization");

    let temp = assert_fs::TempDir::new().unwrap();
    let source_file = temp.child("invalid-endpoint-source.json");
    source_file
        .write_str(&serde_json::to_string_pretty(&source).unwrap())
        .unwrap();

    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ARCHIMATE_OEF",
            plugin_binary("dediren-plugin-archimate-oef-export"),
        )
        .arg("export")
        .arg("--plugin")
        .arg("archimate-oef")
        .arg("--policy")
        .arg(workspace_file("fixtures/export-policy/default-oef.json"))
        .arg("--source")
        .arg(source_file.path())
        .arg("--layout")
        .arg(workspace_file(
            "fixtures/layout-result/archimate-oef-basic.json",
        ))
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(
        &output,
        "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
    );
}
