use super::common::*;

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
    let data = render_ok_data(input);
    assert!(data["render_result_schema_version"].is_string());
    let content = data["content"]
        .as_str()
        .expect("render data should contain SVG content");
    assert!(content.contains("<svg"));
    assert!(content.contains("Client"));
    assert!(content.contains("API"));
    assert!(write_render_artifact(&current_test_name(), content).exists());
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
    let doc = svg_doc(&content);

    let background = child_element(doc.root_element(), "rect");
    assert_eq!(background.attribute("fill"), Some("#f8fafc"));

    let api_node = semantic_group(&doc, "data-dediren-node-id", "api");
    let api_rect = child_element(api_node, "rect");
    assert_eq!(api_rect.attribute("fill"), Some("#ecfeff"));
    assert_eq!(api_rect.attribute("stroke"), Some("#0891b2"));

    let client_node = semantic_group(&doc, "data-dediren-node-id", "client");
    let client_rect = child_element(client_node, "rect");
    assert_eq!(client_rect.attribute("fill"), Some("#ffffff"));
    assert_eq!(client_rect.attribute("stroke"), Some("#1f2937"));

    let calls_edge = semantic_group(&doc, "data-dediren-edge-id", "client-calls-api");
    let calls_path = child_element(calls_edge, "path");
    let calls_label = child_element(calls_edge, "text");
    assert_eq!(calls_path.attribute("stroke"), Some("#7c3aed"));
    assert_eq!(calls_label.attribute("fill"), Some("#5b21b6"));
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
    let doc = svg_doc(&content);

    let viewport = child_element(doc.root_element(), "g");
    assert_eq!(viewport.attribute("font-family"), Some("Inter"));
    assert_eq!(viewport.attribute("font-size"), Some("13.5"));

    let node = semantic_group(&doc, "data-dediren-node-id", "node-a");
    let node_rect = child_element(node, "rect");
    assert_eq!(node_rect.attribute("rx"), Some("0.5"));
    assert_eq!(node_rect.attribute("stroke-width"), Some("1.25"));
}

#[test]
fn svg_renderer_allows_schema_valid_non_ascii_font_family() {
    let font_family = "Å".repeat(120);
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!({
            "font": { "family": font_family, "size": 14 }
        }),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let viewport = child_element(doc.root_element(), "g");
    assert_eq!(
        viewport.attribute("font-family"),
        Some(font_family.as_str())
    );
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
    let doc = svg_doc(&content);

    let base_group = semantic_group(&doc, "data-dediren-group-id", "base-group");
    let base_rect = child_element(base_group, "rect");
    let base_label = child_element(base_group, "text");
    assert_eq!(base_rect.attribute("fill"), Some("#e0f2fe"));
    assert_eq!(base_rect.attribute("stroke"), Some("#0284c7"));
    assert_eq!(base_rect.attribute("stroke-width"), Some("1.25"));
    assert_eq!(base_rect.attribute("rx"), Some("6.5"));
    assert_eq!(base_label.attribute("fill"), Some("#0c4a6e"));
    assert_eq!(base_label.attribute("font-size"), Some("13.5"));
    assert_eq!(base_label.text(), Some("Base Group"));

    let override_group = semantic_group(&doc, "data-dediren-group-id", "override-group");
    let override_rect = child_element(override_group, "rect");
    let override_label = child_element(override_group, "text");
    assert_eq!(override_rect.attribute("fill"), Some("#fef3c7"));
    assert_eq!(override_rect.attribute("stroke"), Some("#d97706"));
    assert_eq!(override_rect.attribute("stroke-width"), Some("2.5"));
    assert_eq!(override_rect.attribute("rx"), Some("3.25"));
    assert_eq!(override_label.attribute("fill"), Some("#78350f"));
    assert_eq!(override_label.attribute("font-size"), Some("15.75"));
    assert_eq!(override_label.text(), Some("Override Group"));
}

#[test]
fn svg_renderer_rejects_profile_mismatch() {
    let mut input = archimate_style_input();
    input["render_metadata"]["semantic_profile"] = serde_json::json!("bpmn2");

    let envelope = render_error(input, "DEDIREN_RENDER_METADATA_PROFILE_MISMATCH");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("bpmn2"));
    assert!(message.contains("archimate"));
}

#[test]
fn svg_renderer_rejects_type_policy_without_metadata() {
    let mut input = archimate_style_input();
    input.as_object_mut().unwrap().remove("render_metadata");

    let envelope = render_error(input, "DEDIREN_RENDER_METADATA_REQUIRED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("requires render metadata"));
}

#[test]
fn svg_renderer_rejects_unsafe_policy_color_before_rendering() {
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
            "node": {
                "fill": "url(https://attacker.example/x.svg#p)"
            }
        }),
    );

    let envelope = render_error(input, "DEDIREN_SVG_POLICY_INVALID");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("node.fill"));
    assert!(message.contains("#RRGGBB"));
}
