mod common;

use assert_fs::prelude::*;
use common::{
    assert_reasonable_svg_aspect, assert_svg_texts_include, child_element, child_group_with_attr,
    ok_data, plugin_binary, semantic_group, svg_doc, workspace_file, write_render_artifact,
};
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};

static REAL_ELK_LOCK: Mutex<()> = Mutex::new(());

#[test]
#[ignore = "run with --ignored after building the ELK Java helper; serialize real ELK runs"]
fn real_elk_renders_basic_projected_graph() {
    let _guard = real_elk_guard();
    let temp = assert_fs::TempDir::new().unwrap();

    let request_output = project_layout_request("fixtures/source/valid-basic.json");
    let request = write_temp_bytes(&temp, "basic-layout-request.json", &request_output);

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "basic-layout-result.json", &layout_output);
    assert_layout_quality_ok(&validate_layout(&layout));

    let layout_data = ok_data(&layout_output);
    assert_eq!(
        layout_data["nodes"]
            .as_array()
            .expect("laid out nodes should be an array")
            .len(),
        2
    );
    assert_eq!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .len(),
        1
    );

    let svg = render_svg(&layout, "fixtures/render-policy/default-svg.json", None);
    let doc = svg_doc(&svg);
    assert_svg_texts_include(&doc, &["Client", "API", "calls"]);
    assert_reasonable_svg_aspect(&svg, 4.5);
    write_render_artifact("real-elk", "real_elk_renders_basic_projected_graph", &svg);
}

#[test]
#[ignore = "run with --ignored after building the ELK Java helper; serialize real ELK runs"]
fn real_elk_renders_grouped_rich_graph() {
    let _guard = real_elk_guard();
    let temp = assert_fs::TempDir::new().unwrap();

    let request_output = project_layout_request("fixtures/source/valid-pipeline-rich.json");
    let request_data = ok_data(&request_output);
    assert_eq!(
        request_data["nodes"]
            .as_array()
            .expect("projected nodes should be an array")
            .len(),
        6
    );
    assert_eq!(
        request_data["groups"]
            .as_array()
            .expect("projected groups should be an array")
            .len(),
        2
    );
    let request = write_temp_bytes(&temp, "grouped-rich-layout-request.json", &request_output);

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "grouped-rich-layout-result.json", &layout_output);
    assert_layout_quality_ok(&validate_layout(&layout));

    let layout_data = ok_data(&layout_output);
    assert_eq!(
        layout_data["nodes"]
            .as_array()
            .expect("laid out nodes should be an array")
            .len(),
        6
    );
    assert_eq!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .len(),
        6
    );
    assert_eq!(
        layout_data["groups"]
            .as_array()
            .expect("laid out groups should be an array")
            .len(),
        2
    );

    let svg = render_svg(&layout, "fixtures/render-policy/rich-svg.json", None);
    let doc = svg_doc(&svg);
    assert_svg_texts_include(
        &doc,
        &[
            "Client",
            "Web App",
            "Orders API",
            "PostgreSQL",
            "Application Services",
            "submits order",
            "authorizes payment",
        ],
    );
    assert!(svg.contains("data-dediren-group-id=\"application-services\""));
    assert!(svg.contains("data-dediren-group-id=\"external-dependencies\""));
    assert!(svg.contains("data-dediren-node-id=\"worker\""));
    assert!(svg.contains("data-dediren-node-id=\"payments\""));
    assert!(svg.contains(">Fulfillment</tspan>"));
    assert!(svg.contains(">Worker</tspan>"));
    assert!(svg.contains(">Payments</tspan>"));
    assert!(svg.contains(">Provider</tspan>"));
    assert_reasonable_svg_aspect(&svg, 3.2);
    write_render_artifact("real-elk", "real_elk_renders_grouped_rich_graph", &svg);
}

#[test]
#[ignore = "run with --ignored after building the ELK Java helper; serialize real ELK runs"]
fn real_elk_renders_archimate_metadata_notation() {
    let _guard = real_elk_guard();
    let temp = assert_fs::TempDir::new().unwrap();
    let source = "fixtures/source/valid-pipeline-archimate.json";

    let request_output = project_layout_request(source);
    let request = write_temp_bytes(&temp, "archimate-layout-request.json", &request_output);
    let metadata_output = project_render_metadata(source);
    let metadata = write_temp_bytes(&temp, "archimate-render-metadata.json", &metadata_output);

    let metadata_data = ok_data(&metadata_output);
    assert_eq!(metadata_data["semantic_profile"], "archimate");
    assert_eq!(
        metadata_data["nodes"]["orders-api"]["type"],
        "ApplicationService"
    );
    assert_eq!(metadata_data["nodes"]["client"]["type"], "BusinessActor");
    assert_eq!(
        metadata_data["nodes"]["payments"]["type"],
        "ApplicationService"
    );
    assert_eq!(metadata_data["nodes"]["database"]["type"], "DataObject");
    assert_eq!(
        metadata_data["edges"]["web-app-calls-api"]["type"],
        "Realization"
    );

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "archimate-layout-result.json", &layout_output);
    assert_archimate_fixture_quality_has_known_connector_warning(&validate_layout(&layout));

    let svg = render_svg(
        &layout,
        "fixtures/render-policy/archimate-svg.json",
        Some(&metadata),
    );
    let doc = svg_doc(&svg);

    for node_id in ["web-app", "worker"] {
        let component = semantic_group(&doc, "data-dediren-node-id", node_id);
        assert!(child_group_with_attr(
            component,
            "data-dediren-node-decorator",
            "archimate_application_component",
        )
        .is_some());
    }

    let business = semantic_group(&doc, "data-dediren-node-id", "client");
    assert!(child_group_with_attr(
        business,
        "data-dediren-node-decorator",
        "archimate_business_actor",
    )
    .is_some());

    for node_id in ["orders-api", "payments"] {
        let service = semantic_group(&doc, "data-dediren-node-id", node_id);
        assert!(child_group_with_attr(
            service,
            "data-dediren-node-decorator",
            "archimate_application_service",
        )
        .is_some());
    }

    let data_object = semantic_group(&doc, "data-dediren-node-id", "database");
    assert!(child_group_with_attr(
        data_object,
        "data-dediren-node-decorator",
        "archimate_data_object",
    )
    .is_some());

    let realization = semantic_group(&doc, "data-dediren-edge-id", "web-app-calls-api");
    let path = child_element(realization, "path");
    assert_eq!(path.attribute("stroke-dasharray"), Some("8 5"));
    assert_eq!(
        path.attribute("marker-end"),
        Some("url(#marker-end-web-app-calls-api)")
    );
    assert_svg_texts_include(
        &doc,
        &[
            "Customer",
            "Orders API",
            "Payments Service",
            "publishes fulfillment",
        ],
    );
    assert!(svg.contains(">Fulfillment</tspan>"));
    assert!(svg.contains(">Worker</tspan>"));
    assert_reasonable_svg_aspect(&svg, 5.0);
    write_render_artifact(
        "real-elk",
        "real_elk_renders_archimate_metadata_notation",
        &svg,
    );
}

#[test]
#[ignore = "run with --ignored after building the ELK Java helper; serialize real ELK runs"]
fn real_elk_renders_cross_group_route_without_quality_warnings() {
    let _guard = real_elk_guard();
    let temp = assert_fs::TempDir::new().unwrap();
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
    let request = write_temp_json(&temp, "cross-group-layout-request.json", &request);

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "cross-group-layout-result.json", &layout_output);
    assert_layout_quality_ok(&validate_layout(&layout));

    let layout_data = ok_data(&layout_output);
    assert!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .iter()
            .any(|edge| edge["id"] == "a-to-c"),
        "real helper output should preserve the cross-group edge"
    );

    let svg = render_svg(&layout, "fixtures/render-policy/default-svg.json", None);
    let doc = svg_doc(&svg);
    assert_svg_texts_include(&doc, &["A", "B", "C", "connects"]);
    assert_reasonable_svg_aspect(&svg, 3.2);
    write_render_artifact(
        "real-elk",
        "real_elk_renders_cross_group_route_without_quality_warnings",
        &svg,
    );
}

fn real_elk_guard() -> MutexGuard<'static, ()> {
    REAL_ELK_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn project_layout_request(source_fixture: &str) -> Vec<u8> {
    common::dediren_command()
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
        .arg(workspace_file(source_fixture))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone()
}

fn project_render_metadata(source_fixture: &str) -> Vec<u8> {
    common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "project",
            "--target",
            "render-metadata",
            "--plugin",
            "generic-graph",
            "--view",
            "main",
            "--input",
        ])
        .arg(workspace_file(source_fixture))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone()
}

fn real_elk_layout(input: &Path) -> Vec<u8> {
    common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env(
            "DEDIREN_ELK_COMMAND",
            workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh"),
        )
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone()
}

fn validate_layout(input: &Path) -> Value {
    let output = common::dediren_command()
        .args(["validate-layout", "--input"])
        .arg(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    ok_data(&output)
}

fn assert_layout_quality_ok(quality: &Value) {
    assert_eq!(quality["status"], "ok");
    assert_eq!(quality["overlap_count"], 0);
    assert_eq!(quality["connector_through_node_count"], 0);
}

fn assert_archimate_fixture_quality_has_known_connector_warning(quality: &Value) {
    // The rich ArchiMate fixture is intentionally kept in this real-ELK lane
    // because it covers semantic metadata notation that the clean smaller
    // fixtures do not. Keep the known route-quality debt explicit so this test
    // cannot silently accept unrelated layout warnings.
    assert_eq!(quality["status"], "warning");
    assert_eq!(quality["overlap_count"], 0);
    assert_eq!(quality["connector_through_node_count"], 1);
    assert_eq!(quality["invalid_route_count"], 0);
    assert_eq!(quality["group_boundary_issue_count"], 0);
    assert_eq!(quality["warning_count"], 0);
}

fn render_svg(layout_result: &Path, policy_fixture: &str, metadata: Option<&Path>) -> String {
    let mut command = common::dediren_command();
    command
        .env(
            "DEDIREN_PLUGIN_SVG_RENDER",
            plugin_binary("dediren-plugin-svg-render"),
        )
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file(policy_fixture));
    if let Some(metadata) = metadata {
        command.arg("--metadata").arg(metadata);
    }
    let output = command
        .arg("--input")
        .arg(layout_result)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let data = ok_data(&output);
    data["content"]
        .as_str()
        .expect("render output should contain SVG content")
        .to_string()
}

fn write_temp_bytes(temp: &assert_fs::TempDir, name: &str, content: &[u8]) -> PathBuf {
    let child = temp.child(name);
    child.write_binary(content).unwrap();
    child.path().to_path_buf()
}

fn write_temp_json(temp: &assert_fs::TempDir, name: &str, content: &Value) -> PathBuf {
    let child = temp.child(name);
    child.write_str(&content.to_string()).unwrap();
    child.path().to_path_buf()
}
