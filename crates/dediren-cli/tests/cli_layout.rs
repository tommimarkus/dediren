mod common;

use assert_fs::prelude::*;
use common::{error_codes, ok_data, plugin_binary, workspace_file};
use std::path::PathBuf;

#[test]
fn fixture_elk_layout_uses_result_fixture() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env(
            "DEDIREN_ELK_RESULT_FIXTURE",
            workspace_file("fixtures/layout-result/basic.json"),
        )
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(workspace_file("fixtures/layout-request/basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_basic_layout_result(&data);
}

#[test]
fn layout_accepts_layout_preferences_with_fixture_runtime() {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = temp.child("request.json");
    request
        .write_str(
            &serde_json::to_string_pretty(&serde_json::json!({
                "layout_request_schema_version": "layout-request.schema.v1",
                "view_id": "main",
                "nodes": [],
                "edges": [],
                "groups": [],
                "labels": [],
                "constraints": [],
                "layout_preferences": {
                    "direction": "down",
                    "density": "readable",
                    "wrapping": "off",
                    "routing": {
                        "style": "orthogonal",
                        "profile": "readable",
                        "endpoint_merging": "off"
                    }
                }
            }))
            .unwrap(),
        )
        .unwrap();

    let mut cmd = common::dediren_command();
    let output = cmd
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env(
            "DEDIREN_ELK_RESULT_FIXTURE",
            workspace_file("fixtures/layout-result/basic.json"),
        )
        .arg("layout")
        .arg("--plugin")
        .arg("elk-layout")
        .arg("--input")
        .arg(request.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_basic_layout_result(&data);
}

#[test]
fn rust_elk_layout_invokes_in_process_backend() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env_remove("DEDIREN_ELK_RESULT_FIXTURE")
        .args(["layout", "--plugin", "elk-layout", "--input"])
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
    assert_edge_points_non_empty(&data, "client-calls-api");
}

#[test]
fn rust_elk_layout_validates_grouped_cross_group_route_with_bounded_quality() {
    let request = serde_json::json!({
        "layout_request_schema_version": "layout-request.schema.v1",
        "view_id": "main",
        "nodes": [
            { "id": "a", "label": "A", "source_id": "a", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "b", "label": "B", "source_id": "b", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "c", "label": "C", "source_id": "c", "width_hint": 160.0, "height_hint": 80.0 }
        ],
        "edges": [
            { "id": "a-to-b", "source": "a", "target": "b", "label": "internal", "source_id": "a-to-b" },
            { "id": "a-to-c", "source": "a", "target": "c", "label": "connects", "source_id": "a-to-c" }
        ],
        "groups": [
            {
                "id": "group",
                "label": "Group",
                "members": ["a", "b"],
                "provenance": { "semantic_backed": { "source_id": "group" } }
            }
        ],
        "labels": [],
        "constraints": []
    });
    let request_file = write_temp_file(
        "grouped-cross-edge-layout-request.json",
        &request.to_string(),
    );

    let layout_output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env_remove("DEDIREN_ELK_RESULT_FIXTURE")
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request_file)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let layout_data = ok_data(&layout_output);
    assert_eq!(
        layout_data["layout_result_schema_version"],
        "layout-result.schema.v1"
    );
    assert_edge_points_non_empty(&layout_data, "a-to-b");
    assert_edge_points_non_empty(&layout_data, "a-to-c");
    let layout_file = write_temp_file(
        "grouped-cross-edge-layout-result.json",
        std::str::from_utf8(&layout_output).unwrap(),
    );

    let validate_output = common::dediren_command()
        .arg("validate-layout")
        .arg("--input")
        .arg(layout_file)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let quality = ok_data(&validate_output);
    assert_eq!(quality["overlap_count"], 0);
    assert_eq!(quality["connector_through_node_count"], 1);
    assert_eq!(quality["route_detour_count"], 2);
    assert_eq!(quality["route_close_parallel_count"], 0);
    assert_eq!(quality["status"], "warning");
}

#[test]
fn rust_elk_layout_applies_layout_preferences() {
    let request = serde_json::json!({
        "layout_request_schema_version": "layout-request.schema.v1",
        "view_id": "main",
        "nodes": [
            { "id": "source", "label": "Source", "source_id": "source", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "target-a", "label": "Target A", "source_id": "target-a", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "target-b", "label": "Target B", "source_id": "target-b", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "target-c", "label": "Target C", "source_id": "target-c", "width_hint": 160.0, "height_hint": 80.0 }
        ],
        "edges": [
            { "id": "edge-a", "source": "source", "target": "target-a", "label": "realizes", "source_id": "edge-a", "relationship_type": "Realization" },
            { "id": "edge-b", "source": "source", "target": "target-b", "label": "realizes", "source_id": "edge-b", "relationship_type": "Realization" },
            { "id": "edge-c", "source": "source", "target": "target-c", "label": "realizes", "source_id": "edge-c", "relationship_type": "Realization" }
        ],
        "groups": [],
        "labels": [],
        "constraints": [],
        "layout_preferences": {
            "direction": "down",
            "routing": {
                "style": "orthogonal",
                "endpoint_merging": "off"
            }
        }
    });
    let request_file = write_temp_file(
        "layout-preferences-rust-elk-request.json",
        &request.to_string(),
    );

    let layout_output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env_remove("DEDIREN_ELK_RESULT_FIXTURE")
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request_file)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let layout_data = ok_data(&layout_output);
    assert_eq!(
        layout_data["layout_result_schema_version"],
        "layout-result.schema.v1"
    );
    let source = layout_node_by_id(&layout_data, "source");
    let source_center_y = node_center_y(source);
    for target_id in ["target-a", "target-b", "target-c"] {
        let target = layout_node_by_id(&layout_data, target_id);
        assert!(
            node_center_y(target) > source_center_y,
            "direction=down should place {target_id} below source, source={source:?}, target={target:?}"
        );
    }
    for edge_id in ["edge-a", "edge-b", "edge-c"] {
        assert_edge_points_non_empty(&layout_data, edge_id);
        let edge = layout_edge_by_id(&layout_data, edge_id);
        let routing_hints = &edge["routing_hints"];
        assert!(
            routing_hints.is_null()
                || routing_hints
                    .as_array()
                    .is_some_and(|hints| hints.is_empty()),
            "endpoint_merging=off should suppress shared endpoint hints for {edge_id}"
        );
    }
}

#[test]
fn fixture_layout_result_reports_quality() {
    let output = common::dediren_command()
        .arg("validate-layout")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["overlap_count"], 0);
    assert_eq!(data["connector_through_node_count"], 0);
    assert_eq!(data["route_close_parallel_count"], 0);
    assert_eq!(data["status"], "ok");
}

#[test]
fn validate_layout_rejects_empty_routes_and_endpoint_misses() {
    let temp = assert_fs::TempDir::new().unwrap();
    let layout = temp.child("invalid-route-layout.json");
    layout
        .write_str(
            &serde_json::to_string_pretty(&serde_json::json!({
                "layout_result_schema_version": "layout-result.schema.v1",
                "view_id": "main",
                "nodes": [
                    { "id": "source", "source_id": "source", "projection_id": "source", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "Source" },
                    { "id": "target", "source_id": "target", "projection_id": "target", "x": 300.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "Target" }
                ],
                "edges": [
                    { "id": "empty", "source": "source", "target": "target", "source_id": "empty", "projection_id": "empty", "routing_hints": [], "points": [], "label": "empty" },
                    { "id": "misses-target", "source": "source", "target": "target", "source_id": "misses-target", "projection_id": "misses-target", "routing_hints": [], "points": [{"x": 100.0, "y": 40.0}, {"x": 250.0, "y": 40.0}], "label": "misses target" }
                ],
                "groups": [],
                "warnings": []
            }))
            .unwrap(),
        )
        .unwrap();

    let output = common::dediren_command()
        .arg("validate-layout")
        .arg("--input")
        .arg(layout.path())
        .assert()
        .failure()
        .code(2)
        .get_output()
        .stdout
        .clone();

    assert_eq!(
        error_codes(&output),
        vec![
            "DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY",
            "DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER"
        ]
    );
}

fn layout_node_by_id<'a>(data: &'a serde_json::Value, id: &str) -> &'a serde_json::Value {
    data["nodes"]
        .as_array()
        .expect("layout result nodes should be an array")
        .iter()
        .find(|node| node["id"] == id)
        .unwrap_or_else(|| panic!("layout result should contain node {id}"))
}

fn layout_edge_by_id<'a>(data: &'a serde_json::Value, id: &str) -> &'a serde_json::Value {
    data["edges"]
        .as_array()
        .expect("layout result edges should be an array")
        .iter()
        .find(|edge| edge["id"] == id)
        .unwrap_or_else(|| panic!("layout result should contain edge {id}"))
}

fn node_center_y(node: &serde_json::Value) -> f64 {
    let y = node["y"].as_f64().expect("node y should be numeric");
    let height = node["height"]
        .as_f64()
        .expect("node height should be numeric");
    y + (height / 2.0)
}

fn assert_basic_layout_result(data: &serde_json::Value) {
    assert_eq!(
        data["layout_result_schema_version"],
        "layout-result.schema.v1"
    );
    assert_eq!(data["view_id"], "main");
    assert_eq!(
        data["nodes"]
            .as_array()
            .expect("layout result nodes should be an array")
            .len(),
        2
    );
    assert!(
        data["edges"]
            .as_array()
            .expect("layout result edges should be an array")
            .iter()
            .any(|edge| edge["id"] == "client-calls-api"),
        "layout result should contain client-calls-api edge"
    );
}

fn assert_edge_points_non_empty(data: &serde_json::Value, id: &str) {
    let edge = layout_edge_by_id(data, id);
    assert!(
        edge["points"]
            .as_array()
            .is_some_and(|points| points.len() >= 2),
        "layout result edge {id} should contain at least two route points"
    );
}

fn write_temp_file(name: &str, content: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!(
        "dediren-cli-elk-test-{}-{}",
        std::process::id(),
        unique_suffix()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(name);
    std::fs::write(&path, content).unwrap();
    path
}

fn unique_suffix() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos()
}
