mod common;

use assert_fs::prelude::*;
use common::{
    assert_reasonable_svg_aspect, assert_svg_texts_include, child_element, child_group_with_attr,
    ok_data, plugin_binary, semantic_group, svg_doc, workspace_file, write_render_artifact,
};

#[test]
fn full_pipeline_produces_svg_and_oef() {
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
        "cli-pipeline",
        "full_pipeline_produces_svg_and_oef",
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
fn archimate_pipeline_renders_policy_notation_from_projected_metadata() {
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
    assert_eq!(metadata_data["semantic_profile"], "archimate");
    assert_eq!(
        metadata_data["nodes"]
            .as_object()
            .expect("projected metadata nodes should be an object")
            .len(),
        6
    );
    assert_eq!(
        metadata_data["edges"]
            .as_object()
            .expect("projected metadata edges should be an object")
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
    assert_eq!(
        metadata_data["edges"]["web-app-calls-api"]["type"],
        "Realization"
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
    let content = render_data["content"].as_str().unwrap();
    let doc = svg_doc(content);
    for node_id in ["web-app", "worker"] {
        let component = semantic_group(&doc, "data-dediren-node-id", node_id);
        let component_rect = child_element(component, "rect");
        assert_eq!(component_rect.attribute("fill"), Some("#e0f2fe"));
        assert_eq!(component_rect.attribute("stroke"), Some("#0369a1"));
        assert!(child_group_with_attr(
            component,
            "data-dediren-node-decorator",
            "archimate_application_component"
        )
        .is_some());
    }

    let business = semantic_group(&doc, "data-dediren-node-id", "client");
    let business_rect = child_element(business, "rect");
    assert_eq!(business_rect.attribute("fill"), Some("#fff2cc"));
    assert_eq!(business_rect.attribute("stroke"), Some("#d6b656"));
    assert!(child_group_with_attr(
        business,
        "data-dediren-node-decorator",
        "archimate_business_actor"
    )
    .is_some());

    for node_id in ["orders-api", "payments"] {
        let service = semantic_group(&doc, "data-dediren-node-id", node_id);
        assert!(child_group_with_attr(
            service,
            "data-dediren-node-decorator",
            "archimate_application_service"
        )
        .is_some());
    }

    let data_object = semantic_group(&doc, "data-dediren-node-id", "database");
    let data_object_rect = child_element(data_object, "rect");
    assert_eq!(data_object_rect.attribute("fill"), Some("#e0f2fe"));
    assert_eq!(data_object_rect.attribute("stroke"), Some("#0369a1"));
    assert!(child_group_with_attr(
        data_object,
        "data-dediren-node-decorator",
        "archimate_data_object"
    )
    .is_some());

    assert_svg_texts_include(
        &doc,
        &[
            "Application Services",
            "External Dependencies",
            "publishes fulfillment",
            "loads order",
        ],
    );
    assert_reasonable_svg_aspect(content, 2.8);

    let realization = semantic_group(&doc, "data-dediren-edge-id", "web-app-calls-api");
    let path = child_element(realization, "path");
    assert_eq!(path.attribute("stroke-dasharray"), Some("8 5"));
    assert_eq!(
        path.attribute("marker-end"),
        Some("url(#marker-end-web-app-calls-api)")
    );
    assert!(content.contains(r#"data-dediren-edge-marker-end="hollow_triangle""#));
    write_render_artifact(
        "cli-pipeline",
        "archimate_pipeline_renders_policy_notation_from_projected_metadata",
        content,
    );
}

#[test]
#[ignore = "requires built Java ELK helper"]
fn real_elk_pipeline_renders_rich_source() {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = temp.child("rich-layout-request.json");
    let result = temp.child("rich-layout-result.json");
    let svg = temp.child("rich.svg");
    let generic_plugin = plugin_binary("dediren-plugin-generic-graph");
    let elk_plugin = plugin_binary("dediren-plugin-elk-layout");
    let svg_plugin = plugin_binary("dediren-plugin-svg-render");
    let elk_helper = workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh");

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
        project_data["groups"]
            .as_array()
            .expect("projected groups should be an array")
            .len(),
        2
    );

    let layout_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", &elk_plugin)
        .env("DEDIREN_ELK_COMMAND", elk_helper)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    result.write_binary(&layout_output).unwrap();
    let validate_output = common::dediren_command()
        .arg("validate-layout")
        .arg("--input")
        .arg(result.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let quality = ok_data(&validate_output);
    assert_eq!(quality["status"], "ok");
    assert_eq!(quality["overlap_count"], 0);
    assert_eq!(quality["connector_through_node_count"], 0);

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

    let render_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_SVG_RENDER", &svg_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/rich-svg.json"))
        .arg("--input")
        .arg(result.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let render_data = ok_data(&render_output);
    let content = render_data["content"].as_str().unwrap();
    assert!(content.contains("data-dediren-group-id=\"application-services\""));
    assert!(content.contains("data-dediren-group-id=\"external-dependencies\""));
    assert!(content.contains("viewBox=\"-"));
    assert_reasonable_svg_aspect(content, 3.2);
    svg.write_str(content).unwrap();
    write_render_artifact(
        "cli-pipeline",
        "real_elk_pipeline_renders_rich_source",
        content,
    );
}
