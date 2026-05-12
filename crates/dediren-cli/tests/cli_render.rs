mod common;

use common::{
    child_element, child_group_with_attr, ok_data, plugin_binary, semantic_group, svg_doc,
    workspace_file, write_render_artifact,
};

#[test]
fn render_invokes_svg_plugin() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_SVG_RENDER",
            plugin_binary("dediren-plugin-svg-render"),
        )
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/default-svg.json"))
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(
        data["render_result_schema_version"],
        "render-result.schema.v1"
    );
    assert_eq!(data["artifact_kind"], "svg");
    let content = data["content"]
        .as_str()
        .expect("render result content should be a string");
    let doc = svg_doc(content);
    let client_node = semantic_group(&doc, "data-dediren-node-id", "client");
    let client_label = child_element(client_node, "text");
    assert_eq!(client_label.text(), Some("Client"));
    let artifact = write_render_artifact("cli-render", "render_invokes_svg_plugin", content);
    assert!(artifact.exists());
}

#[test]
fn render_invokes_svg_plugin_with_rich_policy() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_SVG_RENDER",
            plugin_binary("dediren-plugin-svg-render"),
        )
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/rich-svg.json"))
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(
        data["render_result_schema_version"],
        "render-result.schema.v1"
    );
    let content = data["content"]
        .as_str()
        .expect("render result content should be a string");
    let doc = svg_doc(content);

    let api_node = semantic_group(&doc, "data-dediren-node-id", "api");
    let api_rect = child_element(api_node, "rect");
    assert_eq!(api_rect.attribute("fill"), Some("#ecfeff"));

    let calls_edge = semantic_group(&doc, "data-dediren-edge-id", "client-calls-api");
    let calls_path = child_element(calls_edge, "path");
    assert_eq!(calls_path.attribute("stroke"), Some("#7c3aed"));

    let artifact = write_render_artifact(
        "cli-render",
        "render_invokes_svg_plugin_with_rich_policy",
        content,
    );
    assert!(artifact.exists());
}

#[test]
fn render_invokes_svg_plugin_with_archimate_policy_and_metadata() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_SVG_RENDER",
            plugin_binary("dediren-plugin-svg-render"),
        )
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/archimate-svg.json"))
        .arg("--metadata")
        .arg(workspace_file(
            "fixtures/render-metadata/archimate-basic.json",
        ))
        .arg("--input")
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
        data["render_result_schema_version"],
        "render-result.schema.v1"
    );
    let content = data["content"]
        .as_str()
        .expect("render result content should be a string");
    let doc = svg_doc(content);

    let component = semantic_group(&doc, "data-dediren-node-id", "orders-component");
    let rect = child_element(component, "rect");
    assert_eq!(rect.attribute("fill"), Some("#e0f2fe"));
    assert!(
        child_group_with_attr(
            component,
            "data-dediren-node-decorator",
            "archimate_application_component"
        )
        .is_some(),
        "expected ApplicationComponent decorator in ArchiMate SVG"
    );

    let service = semantic_group(&doc, "data-dediren-node-id", "orders-service");
    assert!(
        child_group_with_attr(
            service,
            "data-dediren-node-decorator",
            "archimate_application_service"
        )
        .is_some(),
        "expected ApplicationService decorator in ArchiMate SVG"
    );

    let realization = semantic_group(&doc, "data-dediren-edge-id", "orders-realizes-service");
    let path = child_element(realization, "path");
    assert_eq!(path.attribute("stroke-dasharray"), Some("8 5"));
    assert_eq!(
        path.attribute("marker-end"),
        Some("url(#marker-end-orders-realizes-service)")
    );
    assert!(
        content.contains(r#"data-dediren-edge-marker-end="hollow_triangle""#),
        "expected Realization hollow triangle marker in ArchiMate SVG"
    );

    let artifact = write_render_artifact(
        "cli-render",
        "render_invokes_svg_plugin_with_archimate_policy_and_metadata",
        content,
    );
    assert!(artifact.exists());
}
