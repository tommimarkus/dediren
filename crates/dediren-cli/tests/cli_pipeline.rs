mod common;

use assert_fs::prelude::*;
use common::{
    assert_reasonable_svg_aspect, assert_svg_texts_include, child_element, child_group_with_attr,
    ok_data, plugin_binary, semantic_group, svg_doc, workspace_file, write_render_artifact,
};

#[test]
fn fixture_pipeline_produces_svg_and_oef() {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = temp.child("request.json");
    let result = temp.child("result.json");
    let svg = temp.child("diagram.svg");
    let generic_plugin = plugin_binary("dediren-plugin-generic-graph");
    let elk_plugin = plugin_binary("dediren-plugin-elk-layout");
    let svg_plugin = plugin_binary("dediren-plugin-svg-render");
    let oef_plugin = plugin_binary("dediren-plugin-archimate-oef-export");
    let elk_fixture = workspace_file("fixtures/layout-result/pipeline-rich.json");

    let project_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
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
        .arg(workspace_file("fixtures/source/valid-pipeline-rich.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    request.write_binary(&project_output).unwrap();
    let project_data = ok_data(&project_output);
    assert_eq!(
        project_data["nodes"]
            .as_array()
            .expect("projected nodes should be an array")
            .len(),
        6
    );
    assert_eq!(
        project_data["edges"]
            .as_array()
            .expect("projected edges should be an array")
            .len(),
        6
    );

    let layout_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", &elk_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .env("DEDIREN_ELK_RESULT_FIXTURE", elk_fixture)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    result.write_binary(&layout_output).unwrap();

    common::dediren_command()
        .args(["validate-layout", "--input"])
        .arg(result.path())
        .assert()
        .success();

    let render_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_SVG_RENDER", &svg_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/default-svg.json"))
        .arg("--input")
        .arg(result.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let render_data = ok_data(&render_output);
    let svg_content = render_data["content"].as_str().unwrap();
    svg.write_str(svg_content).unwrap();
    write_render_artifact(
        "fixture-pipeline",
        "fixture_pipeline_produces_svg_and_oef",
        svg_content,
    );

    let svg_text = std::fs::read_to_string(svg.path()).unwrap();
    let doc = svg_doc(&svg_text);
    assert!(svg_text.contains("<svg"));
    assert_svg_texts_include(
        &doc,
        &[
            "Client",
            "Web App",
            "Orders API",
            "PostgreSQL",
            "Payments Provider",
            "Application Services",
            "submits order",
            "authorizes payment",
        ],
    );
    assert!(svg_text.contains("data-dediren-group-id=\"application-services\""));
    assert_reasonable_svg_aspect(&svg_text, 2.8);

    let export_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_ARCHIMATE_OEF", &oef_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["export", "--plugin", "archimate-oef", "--policy"])
        .arg(workspace_file("fixtures/export-policy/default-oef.json"))
        .arg("--source")
        .arg(workspace_file("fixtures/source/valid-archimate-oef.json"))
        .arg("--layout")
        .arg(workspace_file(
            "fixtures/layout-result/archimate-oef-basic.json",
        ))
        .output()
        .unwrap();
    assert!(export_output.status.success());

    let export_envelope: serde_json::Value = serde_json::from_slice(&export_output.stdout).unwrap();
    assert_eq!(export_envelope["status"], "ok");
    assert_eq!(
        export_envelope["data"]["artifact_kind"],
        "archimate-oef+xml"
    );
    assert!(export_envelope["data"]["content"]
        .as_str()
        .unwrap()
        .contains("xsi:type=\"Diagram\""));
}

#[test]
fn fixture_archimate_pipeline_renders_node_notation() {
    let (svg, metadata_data) = render_fixture_archimate_pipeline();
    let doc = svg_doc(&svg);

    assert_eq!(metadata_data["semantic_profile"], "archimate");
    assert_eq!(
        metadata_data["nodes"]
            .as_object()
            .expect("projected metadata nodes should be an object")
            .len(),
        6
    );
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

    for node_id in ["web-app", "worker"] {
        assert_node_notation(
            &doc,
            node_id,
            "#e0f2fe",
            "#0369a1",
            "archimate_application_component",
        );
    }
    assert_node_notation(
        &doc,
        "client",
        "#fff2cc",
        "#d6b656",
        "archimate_business_actor",
    );
    for node_id in ["orders-api", "payments"] {
        assert_node_notation(
            &doc,
            node_id,
            "#e0f2fe",
            "#0369a1",
            "archimate_application_service",
        );
    }
    assert_node_notation(
        &doc,
        "database",
        "#e0f2fe",
        "#0369a1",
        "archimate_data_object",
    );

    assert_svg_texts_include(
        &doc,
        &["Application Services", "External Dependencies", "Client"],
    );
    assert_reasonable_svg_aspect(&svg, 2.8);
    write_render_artifact(
        "fixture-pipeline",
        "fixture_archimate_pipeline_renders_node_notation",
        &svg,
    );
}

#[test]
fn fixture_archimate_pipeline_renders_relationship_notation() {
    let (svg, metadata_data) = render_fixture_archimate_pipeline();
    let doc = svg_doc(&svg);

    assert_eq!(
        metadata_data["edges"]
            .as_object()
            .expect("projected metadata edges should be an object")
            .len(),
        6
    );
    assert_eq!(
        metadata_data["edges"]["web-app-calls-api"]["type"],
        "Realization"
    );
    assert_eq!(
        metadata_data["edges"]["client-submits-order"]["type"],
        "Serving"
    );
    assert_eq!(
        metadata_data["edges"]["api-writes-database"]["type"],
        "Access"
    );
    assert_eq!(
        metadata_data["edges"]["api-publishes-job"]["type"],
        "Triggering"
    );

    assert_edge_marker_end(&doc, "web-app-calls-api", "hollow_triangle");
    let realization = semantic_group(&doc, "data-dediren-edge-id", "web-app-calls-api");
    assert_eq!(
        child_element(realization, "path").attribute("stroke-dasharray"),
        Some("8 5")
    );
    assert_edge_marker_end(&doc, "client-submits-order", "open_arrow");
    assert_edge_marker_end(&doc, "api-writes-database", "open_arrow");
    assert_edge_marker_end(&doc, "api-authorizes-payment", "open_arrow");
    assert_edge_marker_end(&doc, "api-publishes-job", "open_arrow");
    assert_edge_marker_end(&doc, "worker-reads-database", "open_arrow");
    assert_svg_texts_include(
        &doc,
        &[
            "calls API",
            "writes orders",
            "publishes fulfillment",
            "loads order",
        ],
    );
    write_render_artifact(
        "fixture-pipeline",
        "fixture_archimate_pipeline_renders_relationship_notation",
        &svg,
    );
}

fn render_fixture_archimate_pipeline() -> (String, serde_json::Value) {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = temp.child("archimate-layout-request.json");
    let metadata = temp.child("archimate-render-metadata.json");
    let result = temp.child("archimate-layout-result.json");
    let generic_plugin = plugin_binary("dediren-plugin-generic-graph");
    let elk_plugin = plugin_binary("dediren-plugin-elk-layout");
    let svg_plugin = plugin_binary("dediren-plugin-svg-render");
    let source = workspace_file("fixtures/source/valid-pipeline-archimate.json");
    let elk_fixture = workspace_file("fixtures/layout-result/pipeline-rich.json");

    let project_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
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
        .arg(&source)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    request.write_binary(&project_output).unwrap();

    let metadata_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
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
        .arg(&source)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    metadata.write_binary(&metadata_output).unwrap();
    let metadata_data = ok_data(&metadata_output);

    let layout_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", &elk_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .env("DEDIREN_ELK_RESULT_FIXTURE", elk_fixture)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    result.write_binary(&layout_output).unwrap();

    let render_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_SVG_RENDER", &svg_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/archimate-svg.json"))
        .arg("--metadata")
        .arg(metadata.path())
        .arg("--input")
        .arg(result.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let render_data = ok_data(&render_output);
    (
        render_data["content"].as_str().unwrap().to_string(),
        metadata_data,
    )
}

fn assert_node_notation(
    doc: &roxmltree::Document<'_>,
    node_id: &str,
    fill: &str,
    stroke: &str,
    decorator: &str,
) {
    let node = semantic_group(doc, "data-dediren-node-id", node_id);
    let rect = child_element(node, "rect");
    assert_eq!(rect.attribute("fill"), Some(fill));
    assert_eq!(rect.attribute("stroke"), Some(stroke));
    assert!(child_group_with_attr(node, "data-dediren-node-decorator", decorator).is_some());
}

fn assert_edge_marker_end(doc: &roxmltree::Document<'_>, edge_id: &str, marker_kind: &str) {
    let edge = semantic_group(doc, "data-dediren-edge-id", edge_id);
    let path = child_element(edge, "path");
    let marker_id = format!("marker-end-{edge_id}");
    let marker_ref = format!("url(#{marker_id})");
    assert_eq!(path.attribute("marker-end"), Some(marker_ref.as_str()));
    let marker = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("marker") && node.attribute("id") == Some(marker_id.as_str())
        })
        .unwrap_or_else(|| panic!("expected marker {marker_id}"));
    assert_eq!(
        marker.attribute("data-dediren-edge-marker-end"),
        Some(marker_kind)
    );
}
