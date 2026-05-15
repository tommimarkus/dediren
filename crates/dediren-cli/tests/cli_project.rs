mod common;

use assert_fs::prelude::*;
use common::{ok_data, plugin_binary, workspace_file};

#[test]
fn project_invokes_generic_graph_plugin() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "project",
            "--target",
            "layout-request",
            "--plugin",
            "generic-graph",
            "--view",
            "main",
            "--input",
        ])
        .arg(workspace_file("fixtures/source/valid-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(
        data["layout_request_schema_version"],
        "layout-request.schema.v1"
    );
    assert_eq!(data["view_id"], "main");
    assert_eq!(
        data["nodes"]
            .as_array()
            .expect("layout request nodes should be an array")
            .len(),
        2
    );
}

#[test]
fn project_layout_request_preserves_layout_preferences() {
    let temp = assert_fs::TempDir::new().unwrap();
    let source = temp.child("source.json");
    let layout_preferences = serde_json::json!({
        "direction": "down",
        "density": "readable",
        "wrapping": "off",
        "routing": {
            "style": "orthogonal",
            "profile": "spacious",
            "endpoint_merging": "off"
        }
    });
    source
        .write_str(
            &serde_json::to_string_pretty(&serde_json::json!({
                "model_schema_version": "model.schema.v1",
                "nodes": [
                    { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                ],
                "relationships": [
                    {
                        "id": "client-calls-api",
                        "type": "generic.calls",
                        "source": "client",
                        "target": "api",
                        "label": "calls",
                        "properties": {}
                    }
                ],
                "plugins": {
                    "generic-graph": {
                        "views": [
                            {
                                "id": "main",
                                "label": "Main",
                                "nodes": ["client", "api"],
                                "relationships": ["client-calls-api"],
                                "layout_preferences": layout_preferences.clone()
                            }
                        ]
                    }
                }
            }))
            .unwrap(),
        )
        .unwrap();

    let assert = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "project",
            "--target",
            "layout-request",
            "--plugin",
            "generic-graph",
            "--view",
            "main",
            "--input",
        ])
        .arg(source.path())
        .assert()
        .success();

    let output = assert.get_output().stdout.clone();
    let data = ok_data(&output);
    assert_eq!(data["layout_preferences"], layout_preferences);
}
