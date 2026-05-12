mod common;

use common::{assert_error_code, plugin_binary, stdout_json, workspace_file};
use predicates::prelude::*;
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
fn plugin_error_envelope_is_preserved_by_cli() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env_remove("DEDIREN_ELK_COMMAND")
        .env_remove("DEDIREN_ELK_RESULT_FIXTURE")
        .arg("layout")
        .arg("--plugin")
        .arg("elk-layout")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-request/basic.json"))
        .assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_PLUGIN_ERROR").not())
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_ELK_RUNTIME_UNAVAILABLE");
}
