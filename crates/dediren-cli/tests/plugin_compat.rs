mod common;

use assert_fs::prelude::*;
use common::{assert_error_code, ok_data, plugin_binary, stdout_json, workspace_file};
use std::process::Command;

#[test]
fn first_party_plugins_report_capabilities() {
    for (binary, plugin_id, expected_capabilities) in [
        (
            "dediren-plugin-generic-graph",
            "generic-graph",
            &["semantic-validation", "projection"][..],
        ),
        ("dediren-plugin-elk-layout", "elk-layout", &["layout"][..]),
        ("dediren-plugin-svg-render", "svg-render", &["render"][..]),
        (
            "dediren-plugin-archimate-oef-export",
            "archimate-oef",
            &["export"][..],
        ),
        ("dediren-plugin-uml-xmi-export", "uml-xmi", &["export"][..]),
    ] {
        let output = Command::new(plugin_binary(binary))
            .arg("capabilities")
            .output()
            .expect("plugin capabilities command should run");
        assert!(
            output.status.success(),
            "{binary} capabilities should succeed, stderr: {}",
            String::from_utf8_lossy(&output.stderr)
        );

        let data = stdout_json(&output.stdout);
        assert_eq!(data["plugin_protocol_version"], "plugin.protocol.v1");
        assert_eq!(data["id"], plugin_id);
        let capabilities = data["capabilities"]
            .as_array()
            .expect("capabilities should be an array")
            .iter()
            .map(|capability| capability.as_str().expect("capability should be a string"))
            .collect::<Vec<_>>();
        assert_eq!(
            capabilities.as_slice(),
            expected_capabilities,
            "{binary} should report expected capabilities"
        );
    }
}

#[test]
fn unknown_plugin_failure_is_structured_by_cli() {
    let output = common::dediren_command()
        .arg("layout")
        .arg("--plugin")
        .arg("missing-plugin")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-request/basic.json"))
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_PLUGIN_UNKNOWN");
}

#[test]
fn elk_layout_ignores_external_command_and_runs_in_process() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env(
            "DEDIREN_ELK_COMMAND",
            "/definitely/not/a/dediren/elk/helper",
        )
        .env_remove("DEDIREN_ELK_RESULT_FIXTURE")
        .arg("layout")
        .arg("--plugin")
        .arg("elk-layout")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-request/basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(
        data["layout_result_schema_version"],
        "layout-result.schema.v1"
    );
    assert!(
        data["edges"]
            .as_array()
            .expect("layout result edges should be an array")
            .iter()
            .any(|edge| edge["id"] == "client-calls-api"),
        "in-process Rust backend output should contain client-calls-api edge"
    );
}

#[test]
fn elk_layout_result_fixture_takes_precedence_over_external_command() {
    let temp = assert_fs::TempDir::new().unwrap();
    let fixture = temp.child("sentinel-layout-result.json");
    fixture
        .write_str(
            &serde_json::to_string_pretty(&serde_json::json!({
                "layout_result_schema_version": "layout-result.schema.v1",
                "view_id": "sentinel-fixture-view",
                "nodes": [
                    {
                        "id": "sentinel-node",
                        "source_id": "sentinel-source",
                        "projection_id": "sentinel-projection",
                        "x": 9876.5,
                        "y": 5432.25,
                        "width": 321.0,
                        "height": 123.0,
                        "label": "Sentinel fixture node"
                    }
                ],
                "edges": [],
                "groups": [],
                "warnings": []
            }))
            .unwrap(),
        )
        .unwrap();

    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env(
            "DEDIREN_ELK_COMMAND",
            "/definitely/not/a/dediren/elk/helper",
        )
        .env("DEDIREN_ELK_RESULT_FIXTURE", fixture.path())
        .arg("layout")
        .arg("--plugin")
        .arg("elk-layout")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-request/basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(
        data["layout_result_schema_version"],
        "layout-result.schema.v1"
    );
    assert_eq!(data["view_id"], "sentinel-fixture-view");

    let sentinel = data["nodes"]
        .as_array()
        .expect("sentinel fixture nodes should be an array")
        .iter()
        .find(|node| node["id"] == "sentinel-node")
        .expect("fixture result should contain sentinel node");
    assert_eq!(sentinel["label"], "Sentinel fixture node");
    assert_eq!(sentinel["projection_id"], "sentinel-projection");
    assert_eq!(sentinel["x"].as_f64(), Some(9876.5));
    assert_eq!(sentinel["y"].as_f64(), Some(5432.25));
}

#[test]
fn elk_layout_fixture_error_envelope_is_preserved_by_cli() {
    let temp = assert_fs::TempDir::new().unwrap();
    let missing_fixture = temp.path().join("missing-layout-result.json");

    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env("DEDIREN_ELK_RESULT_FIXTURE", missing_fixture)
        .arg("layout")
        .arg("--plugin")
        .arg("elk-layout")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-request/basic.json"))
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_ELK_FIXTURE_UNAVAILABLE");
    let stdout = String::from_utf8_lossy(&output);
    assert!(
        stdout.contains("DEDIREN_ELK_FIXTURE_UNAVAILABLE"),
        "CLI stdout should preserve plugin fixture error code: {stdout}"
    );
    assert!(
        !stdout.contains("DEDIREN_PLUGIN_ERROR"),
        "CLI stdout should not wrap plugin fixture errors: {stdout}"
    );
}
