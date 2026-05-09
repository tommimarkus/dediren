use assert_cmd::Command;
use predicates::prelude::*;
use std::path::PathBuf;

#[test]
fn svg_renderer_outputs_svg() {
    let input = serde_json::json!({
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/default-svg.json")).unwrap()
        ).unwrap()
    });
    let mut cmd = Command::cargo_bin("dediren-plugin-svg-render").unwrap();
    cmd.arg("render")
        .write_stdin(serde_json::to_string(&input).unwrap());
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"render_result_schema_version\""))
        .stdout(predicate::str::contains("<svg"))
        .stdout(predicate::str::contains("Client"))
        .stdout(predicate::str::contains("API"));
}

#[test]
fn svg_renderer_applies_rich_policy_styles() {
    let input = serde_json::json!({
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/rich-svg.json")).unwrap()
        ).unwrap()
    });
    let content = render_content(input);

    assert!(content.contains(r##"fill="#f8fafc""##));

    let api_node = element_group(&content, "data-dediren-node-id", "api");
    assert!(api_node.contains(r##"fill="#ecfeff""##));
    assert!(api_node.contains(r##"stroke="#0891b2""##));

    let client_node = element_group(&content, "data-dediren-node-id", "client");
    assert!(client_node.contains(r##"fill="#ffffff""##));
    assert!(client_node.contains(r##"stroke="#1f2937""##));

    let calls_edge = element_group(&content, "data-dediren-edge-id", "client-calls-api");
    assert!(calls_edge.contains(r##"stroke="#7c3aed""##));
    assert!(calls_edge.contains(r##"fill="#5b21b6""##));
}

#[test]
fn svg_renderer_preserves_style_number_precision() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "node-a",
                "source_id": "node-a",
                "projection_id": "node-a",
                "x": 32,
                "y": 40,
                "width": 160,
                "height": 80,
                "label": "Node A"
            }
        ]),
        serde_json::json!([]),
        serde_json::json!({
            "font": { "family": "Inter", "size": 13.5 },
            "node": {
                "stroke_width": 1.25,
                "rx": 0.5
            },
            "edge": {
                "stroke_width": 1.25
            },
            "group": {
                "stroke_width": 1.25,
                "rx": 0.5,
                "label_size": 13.5
            }
        }),
    );
    let content = render_content(input);

    assert!(content.contains(r#"font-family="Inter" font-size="13.5""#));

    let node = element_group(&content, "data-dediren-node-id", "node-a");
    assert!(node.contains(r#"rx="0.5""#));
    assert!(node.contains(r#"stroke-width="1.25""#));
}

#[test]
fn svg_renderer_applies_base_and_override_group_styles_to_group_elements() {
    let input = styled_inline_input(
        serde_json::json!([
            {
                "id": "base-group",
                "source_id": "base-group",
                "projection_id": "base-group",
                "x": 16,
                "y": 24,
                "width": 220,
                "height": 140,
                "members": ["node-a"],
                "label": "Base Group"
            },
            {
                "id": "override-group",
                "source_id": "override-group",
                "projection_id": "override-group",
                "x": 260,
                "y": 24,
                "width": 220,
                "height": 140,
                "members": ["node-b"],
                "label": "Override Group"
            }
        ]),
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!({
            "group": {
                "fill": "#e0f2fe",
                "stroke": "#0284c7",
                "stroke_width": 1.25,
                "rx": 6.5,
                "label_fill": "#0c4a6e",
                "label_size": 13.5
            },
            "group_overrides": {
                "override-group": {
                    "fill": "#fef3c7",
                    "stroke": "#d97706",
                    "stroke_width": 2.5,
                    "rx": 3.25,
                    "label_fill": "#78350f",
                    "label_size": 15.75
                }
            }
        }),
    );
    let content = render_content(input);

    let base_group = element_group(&content, "data-dediren-group-id", "base-group");
    assert!(base_group.contains(r##"fill="#e0f2fe""##));
    assert!(base_group.contains(r##"stroke="#0284c7""##));
    assert!(base_group.contains(r#"stroke-width="1.25""#));
    assert!(base_group.contains(r#"rx="6.5""#));
    assert!(base_group.contains(r##"fill="#0c4a6e" font-size="13.5""##));
    assert!(base_group.contains(">Base Group</text>"));

    let override_group = element_group(&content, "data-dediren-group-id", "override-group");
    assert!(override_group.contains(r##"fill="#fef3c7""##));
    assert!(override_group.contains(r##"stroke="#d97706""##));
    assert!(override_group.contains(r#"stroke-width="2.5""#));
    assert!(override_group.contains(r#"rx="3.25""#));
    assert!(override_group.contains(r##"fill="#78350f" font-size="15.75""##));
    assert!(override_group.contains(">Override Group</text>"));
}

fn render_content(input: serde_json::Value) -> String {
    let mut cmd = Command::cargo_bin("dediren-plugin-svg-render").unwrap();
    cmd.arg("render")
        .write_stdin(serde_json::to_string(&input).unwrap());
    let output = cmd.assert().success().get_output().stdout.clone();
    let envelope: serde_json::Value = serde_json::from_slice(&output).unwrap();
    envelope["data"]["content"].as_str().unwrap().to_string()
}

fn styled_inline_input(
    groups: serde_json::Value,
    nodes: serde_json::Value,
    edges: serde_json::Value,
    style: serde_json::Value,
) -> serde_json::Value {
    serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "inline-test",
            "nodes": nodes,
            "edges": edges,
            "groups": groups,
            "warnings": []
        },
        "policy": {
            "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
            "page": { "width": 640, "height": 360 },
            "margin": { "top": 16, "right": 16, "bottom": 16, "left": 16 },
            "style": style
        }
    })
}

fn element_group<'a>(content: &'a str, data_attr: &str, id: &str) -> &'a str {
    let marker = format!(r#"<g {data_attr}="{id}">"#);
    let start = content.find(&marker).unwrap_or_else(|| {
        panic!("expected SVG to contain element group marker {marker:?}:\n{content}")
    });
    let remaining = &content[start..];
    let end = remaining
        .find("</g>")
        .unwrap_or_else(|| panic!("expected SVG element group {marker:?} to close:\n{content}"));
    &remaining[..end + "</g>".len()]
}

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}
