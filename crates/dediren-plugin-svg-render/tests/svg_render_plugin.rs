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

    let content = render_content(input);
    assert!(write_render_artifact(&current_test_name(), &content).exists());
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
fn svg_renderer_applies_archimate_type_styles() {
    let input = archimate_style_input();
    let content = render_content(input);
    let doc = svg_doc(&content);

    let component = semantic_group(&doc, "data-dediren-node-id", "orders-component");
    let component_rect = child_element(component, "rect");
    assert_eq!(component_rect.attribute("fill"), Some("#fff2cc"));
    assert_eq!(component_rect.attribute("stroke"), Some("#7a5c00"));

    let service = semantic_group(&doc, "data-dediren-node-id", "orders-service");
    let service_rect = child_element(service, "rect");
    assert_eq!(service_rect.attribute("fill"), Some("#e0f2fe"));
    assert_eq!(service_rect.attribute("stroke"), Some("#0369a1"));

    let edge = semantic_group(&doc, "data-dediren-edge-id", "orders-realizes-service");
    let path = child_element(edge, "path");
    assert_eq!(path.attribute("stroke"), Some("#374151"));
    assert_eq!(path.attribute("stroke-width"), Some("1.5"));
}

#[test]
fn svg_renderer_applies_archimate_node_decorators_from_type_overrides() {
    let mut input = archimate_style_input();
    input["policy"]["style"]["node_type_overrides"]["ApplicationComponent"]["decorator"] =
        serde_json::json!("archimate_application_component");
    input["policy"]["style"]["node_type_overrides"]["ApplicationService"]["decorator"] =
        serde_json::json!("archimate_application_service");

    let content = render_content(input);
    let doc = svg_doc(&content);

    let component = semantic_group(&doc, "data-dediren-node-id", "orders-component");
    let service = semantic_group(&doc, "data-dediren-node-id", "orders-service");

    let component_decorator = child_group_with_attr(
        component,
        "data-dediren-node-decorator",
        "archimate_application_component",
    );
    let service_decorator = child_group_with_attr(
        service,
        "data-dediren-node-decorator",
        "archimate_application_service",
    );

    assert!(child_elements(component_decorator, "rect").count() >= 2);
    assert!(child_elements(service_decorator, "path").count() >= 1);
}

#[test]
fn svg_renderer_id_override_wins_over_type_override() {
    let mut input = archimate_style_input();
    input["policy"]["style"]["node_overrides"] = serde_json::json!({
        "orders-component": {
            "fill": "#fce7f3",
            "stroke": "#be185d"
        }
    });

    let content = render_content(input);
    let doc = svg_doc(&content);
    let component = semantic_group(&doc, "data-dediren-node-id", "orders-component");
    let rect = child_element(component, "rect");
    assert_eq!(rect.attribute("fill"), Some("#fce7f3"));
    assert_eq!(rect.attribute("stroke"), Some("#be185d"));
}

#[test]
fn svg_renderer_rejects_profile_mismatch() {
    let mut input = archimate_style_input();
    input["render_metadata"]["semantic_profile"] = serde_json::json!("bpmn2");

    let mut cmd = Command::cargo_bin("dediren-plugin-svg-render").unwrap();
    cmd.arg("render")
        .write_stdin(serde_json::to_string(&input).unwrap());
    cmd.assert().failure().stdout(predicate::str::contains(
        "DEDIREN_RENDER_METADATA_PROFILE_MISMATCH",
    ));
}

#[test]
fn svg_renderer_rejects_type_policy_without_metadata() {
    let mut input = archimate_style_input();
    input.as_object_mut().unwrap().remove("render_metadata");

    let mut cmd = Command::cargo_bin("dediren-plugin-svg-render").unwrap();
    cmd.arg("render")
        .write_stdin(serde_json::to_string(&input).unwrap());
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_RENDER_METADATA_REQUIRED"));
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
fn svg_renderer_places_edge_label_near_route_midpoint_for_vertical_route() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "routed-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "routed-edge",
                "projection_id": "routed-edge",
                "points": [
                    { "x": 100, "y": 0 },
                    { "x": 100, "y": 100 },
                    { "x": 100, "y": 200 }
                ],
                "label": "routed label"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "routed-edge");
    let label = child_element(edge, "text");
    let label_box = text_box_from_svg(label, 14.0);
    let center_y = box_center_y(label_box);
    let route_gap = horizontal_gap_to_x(label_box, 100.0);
    assert!(
        route_gap <= 3.0,
        "vertical route label should stay close to the route, got gap={route_gap}"
    );
    assert!(
        (center_y - 100.0).abs() <= 6.0,
        "vertical route label should be visually centered near the route midpoint, got center_y={center_y}"
    );
    assert_eq!(label.attribute("text-anchor"), Some("end"));
}

#[test]
fn svg_renderer_aligns_vertical_edge_labels_by_text_edge() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "short-vertical",
                "source": "web-app",
                "target": "orders-api",
                "source_id": "short-vertical",
                "projection_id": "short-vertical",
                "points": [
                    { "x": 200, "y": 40 },
                    { "x": 200, "y": 220 }
                ],
                "label": "calls API"
            },
            {
                "id": "long-vertical",
                "source": "orders-api",
                "target": "worker",
                "source_id": "long-vertical",
                "projection_id": "long-vertical",
                "points": [
                    { "x": 200, "y": 260 },
                    { "x": 200, "y": 440 }
                ],
                "label": "publishes fulfillment"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let short = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "short-vertical"),
        "text",
    );
    let long = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "long-vertical"),
        "text",
    );

    assert_eq!(short.attribute("text-anchor"), Some("end"));
    assert_eq!(long.attribute("text-anchor"), Some("end"));
    assert_eq!(short.attribute("x"), long.attribute("x"));
}

#[test]
fn svg_renderer_prefers_horizontal_segment_for_edge_label() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "mostly-vertical-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "mostly-vertical-edge",
                "projection_id": "mostly-vertical-edge",
                "points": [
                    { "x": 0, "y": 0 },
                    { "x": 0, "y": 300 },
                    { "x": 100, "y": 300 },
                    { "x": 100, "y": 500 }
                ],
                "label": "horizontal label"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "mostly-vertical-edge");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();
    assert!(
        (y - 300.0).abs() <= 18.0,
        "edge label should stay near the preferred horizontal segment, got y={y}"
    );
}

#[test]
fn svg_renderer_defaults_horizontal_edge_label_near_start() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "horizontal-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "horizontal-edge",
                "projection_id": "horizontal-edge",
                "points": [
                    { "x": 0, "y": 120 },
                    { "x": 100, "y": 120 }
                ],
                "label": "start"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "horizontal-edge");
    let label = child_element(edge, "text");
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();

    assert!(
        (x - 18.0).abs() <= 1.0,
        "default horizontal edge label should be near the segment start, got x={x}"
    );
}

#[test]
fn svg_renderer_defaults_horizontal_edge_label_below_downward_bend() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "downward-bend",
                "source": "source-node",
                "target": "target-node",
                "source_id": "downward-bend",
                "projection_id": "downward-bend",
                "points": [
                    { "x": 0, "y": 120 },
                    { "x": 120, "y": 120 },
                    { "x": 120, "y": 220 }
                ],
                "label": "down"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "downward-bend");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        y > 120.0,
        "default horizontal edge label should move below a downward bend, got y={y}"
    );
}

#[test]
fn svg_renderer_defaults_horizontal_edge_label_near_first_horizontal_segment() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "two-segment-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "two-segment-edge",
                "projection_id": "two-segment-edge",
                "points": [
                    { "x": 100, "y": 120 },
                    { "x": 220, "y": 120 },
                    { "x": 220, "y": 180 },
                    { "x": 340, "y": 180 }
                ],
                "label": "first segment"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "two-segment-edge");
    let label = child_element(edge, "text");
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        x < 220.0,
        "default horizontal edge label should prefer the first horizontal segment, got x={x}"
    );
    assert!(
        y > 120.0,
        "default horizontal edge label should use the first segment bend side, got y={y}"
    );
}

#[test]
fn svg_renderer_defaults_horizontal_edge_label_above_upward_bend() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "upward-bend",
                "source": "source-node",
                "target": "target-node",
                "source_id": "upward-bend",
                "projection_id": "upward-bend",
                "points": [
                    { "x": 0, "y": 220 },
                    { "x": 120, "y": 220 },
                    { "x": 120, "y": 120 }
                ],
                "label": "up"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "upward-bend");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        y < 220.0,
        "default horizontal edge label should stay above an upward bend, got y={y}"
    );
}

#[test]
fn svg_renderer_allows_horizontal_edge_label_side_override_by_policy() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "downward-bend",
                "source": "source-node",
                "target": "target-node",
                "source_id": "downward-bend",
                "projection_id": "downward-bend",
                "points": [
                    { "x": 0, "y": 120 },
                    { "x": 120, "y": 120 },
                    { "x": 120, "y": 220 }
                ],
                "label": "forced above"
            }
        ]),
        serde_json::json!({
            "edge": {
                "label_horizontal_side": "above"
            }
        }),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "downward-bend");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        y < 120.0,
        "configured horizontal edge label side should override auto routing, got y={y}"
    );
}

#[test]
fn svg_renderer_allows_centered_horizontal_edge_labels_by_policy() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "horizontal-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "horizontal-edge",
                "projection_id": "horizontal-edge",
                "points": [
                    { "x": 0, "y": 120 },
                    { "x": 100, "y": 120 }
                ],
                "label": "center label"
            }
        ]),
        serde_json::json!({
            "edge": {
                "label_horizontal_position": "center"
            }
        }),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "horizontal-edge");
    let label = child_element(edge, "text");
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();

    assert!(
        (x - 50.0).abs() <= 1.0,
        "configured centered horizontal label should use segment midpoint, got x={x}"
    );
}

#[test]
fn svg_renderer_paints_edge_label_with_background_halo() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "labeled-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "labeled-edge",
                "projection_id": "labeled-edge",
                "points": [
                    { "x": 0, "y": 100 },
                    { "x": 200, "y": 100 }
                ],
                "label": "clear label"
            }
        ]),
        serde_json::json!({
            "background": { "fill": "#f8fafc" }
        }),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "labeled-edge");
    let path = child_element(edge, "path");
    let label = child_element(edge, "text");
    assert_eq!(
        path.attribute("marker-end"),
        Some("url(#arrow-labeled-edge)")
    );
    assert_eq!(label.attribute("paint-order"), Some("stroke"));
    assert_eq!(label.attribute("stroke"), Some("#f8fafc"));
    assert_eq!(label.attribute("stroke-width"), Some("4"));
}

#[test]
fn svg_renderer_adds_line_jump_for_later_crossing_edge() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "first-edge",
                "source": "left",
                "target": "right",
                "source_id": "first-edge",
                "projection_id": "first-edge",
                "points": [
                    { "x": 0, "y": 100 },
                    { "x": 200, "y": 100 }
                ],
                "label": "first"
            },
            {
                "id": "front-edge",
                "source": "top",
                "target": "bottom",
                "source_id": "front-edge",
                "projection_id": "front-edge",
                "points": [
                    { "x": 100, "y": 0 },
                    { "x": 100, "y": 200 }
                ],
                "label": "front"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let first_edge = semantic_group(&doc, "data-dediren-edge-id", "first-edge");
    let first_path = child_element(first_edge, "path");
    assert!(!first_path.attribute("d").unwrap().contains(" Q "));

    let front_edge = semantic_group(&doc, "data-dediren-edge-id", "front-edge");
    let front_path = child_element(front_edge, "path");
    let data = front_path.attribute("d").unwrap();
    assert!(data.contains("L 100.0 94.0"));
    assert!(data.contains("Q 106.0 100.0 100.0 106.0"));
}

#[test]
fn svg_renderer_expands_viewbox_to_include_edge_labels() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "left-node",
                "source_id": "left-node",
                "projection_id": "left-node",
                "x": 12,
                "y": 32,
                "width": 160,
                "height": 80,
                "label": "Left"
            },
            {
                "id": "right-node",
                "source_id": "right-node",
                "projection_id": "right-node",
                "x": 212,
                "y": 32,
                "width": 160,
                "height": 80,
                "label": "Right"
            }
        ]),
        serde_json::json!([
            {
                "id": "left-to-right",
                "source": "left-node",
                "target": "right-node",
                "source_id": "left-to-right",
                "projection_id": "left-to-right",
                "points": [
                    { "x": 172, "y": 72 },
                    { "x": 212, "y": 72 }
                ],
                "label": "very long clipped edge label"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let root = doc.root_element();

    let width = root.attribute("width").unwrap().parse::<f64>().unwrap();
    let height = root.attribute("height").unwrap().parse::<f64>().unwrap();
    let view_box = root.attribute("viewBox").unwrap();
    let view_box_values: Vec<f64> = view_box
        .split_whitespace()
        .map(|value| value.parse::<f64>().unwrap())
        .collect();
    assert_eq!(view_box_values.len(), 4);
    assert!(
        width >= 380.0,
        "expanded bounds should include node geometry and long edge label, got {width}"
    );
    assert!(
        height >= 100.0,
        "expanded bounds should include label height and node geometry, got {height}"
    );
    assert!(
        view_box.starts_with('-'),
        "expected negative min-x in viewBox, got {view_box}"
    );
    assert!(
        view_box_values[1] <= 16.0,
        "expected top margin in viewBox, got {view_box}"
    );
    let edge = semantic_group(&doc, "data-dediren-edge-id", "left-to-right");
    let label = child_element(edge, "text");
    let label_y = label.attribute("y").unwrap().parse::<f64>().unwrap();
    assert!(
        view_box_values[1] + view_box_values[3] >= label_y + 8.0,
        "viewBox should include adjusted edge label baseline, got {view_box} and y={label_y}"
    );
}

#[test]
fn svg_renderer_crops_small_diagram_to_content_bounds() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "only-node",
                "source_id": "only-node",
                "projection_id": "only-node",
                "x": 32,
                "y": 40,
                "width": 160,
                "height": 80,
                "label": "Only Node"
            }
        ]),
        serde_json::json!([]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let root = doc.root_element();
    let width = root.attribute("width").unwrap().parse::<f64>().unwrap();
    let height = root.attribute("height").unwrap().parse::<f64>().unwrap();
    let view_box: Vec<f64> = root
        .attribute("viewBox")
        .unwrap()
        .split_whitespace()
        .map(|value| value.parse::<f64>().unwrap())
        .collect();

    assert_eq!(view_box.len(), 4);
    assert!(
        width < 260.0,
        "single-node render should not keep the 640px policy width, got {width}"
    );
    assert!(
        height < 180.0,
        "single-node render should not keep the 360px policy height, got {height}"
    );
    assert!(
        view_box[0] <= 16.0,
        "left margin should be included, got min-x {}",
        view_box[0]
    );
    assert!(
        view_box[1] <= 24.0,
        "top margin should be included, got min-y {}",
        view_box[1]
    );
}

#[test]
fn svg_renderer_background_covers_positive_origin_viewbox() {
    let input = styled_inline_input(
        serde_json::json!([
            {
                "id": "group",
                "source_id": "group",
                "projection_id": "group",
                "x": 250,
                "y": 70,
                "width": 590,
                "height": 450,
                "members": ["node"],
                "label": "Application Services"
            }
        ]),
        serde_json::json!([
            {
                "id": "node",
                "source_id": "node",
                "projection_id": "node",
                "x": 310,
                "y": 160,
                "width": 170,
                "height": 80,
                "label": "Web App"
            }
        ]),
        serde_json::json!([]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let root = doc.root_element();
    let view_box: Vec<f64> = root
        .attribute("viewBox")
        .unwrap()
        .split_whitespace()
        .map(|value| value.parse::<f64>().unwrap())
        .collect();
    let background = child_element(root, "rect");

    assert_eq!(view_box.len(), 4);
    assert!(
        view_box[0] > 0.0 && view_box[1] > 0.0,
        "test needs a positive-origin viewBox, got {:?}",
        view_box
    );
    assert_eq!(
        background.attribute("x").unwrap().parse::<f64>().unwrap(),
        view_box[0]
    );
    assert_eq!(
        background.attribute("y").unwrap().parse::<f64>().unwrap(),
        view_box[1]
    );
    assert_eq!(
        background
            .attribute("width")
            .unwrap()
            .parse::<f64>()
            .unwrap(),
        view_box[2]
    );
    assert_eq!(
        background
            .attribute("height")
            .unwrap()
            .parse::<f64>()
            .unwrap(),
        view_box[3]
    );
}

#[test]
fn svg_renderer_moves_edge_label_away_from_node_boxes() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "left-node",
                "source_id": "left-node",
                "projection_id": "left-node",
                "x": 0,
                "y": 32,
                "width": 160,
                "height": 80,
                "label": "Left"
            },
            {
                "id": "right-node",
                "source_id": "right-node",
                "projection_id": "right-node",
                "x": 200,
                "y": 32,
                "width": 160,
                "height": 80,
                "label": "Right"
            }
        ]),
        serde_json::json!([
            {
                "id": "left-to-right",
                "source": "left-node",
                "target": "right-node",
                "source_id": "left-to-right",
                "projection_id": "left-to-right",
                "points": [
                    { "x": 160, "y": 72 },
                    { "x": 200, "y": 72 }
                ],
                "label": "label wider than gap"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "left-to-right");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();
    assert!(
        !(32.0..=112.0).contains(&y),
        "edge label should move outside node boxes, got y={y}"
    );
}

#[test]
fn svg_renderer_centers_horizontal_edge_label_when_near_start_overlaps_adjacent_nodes() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "client",
                "source_id": "client",
                "projection_id": "client",
                "x": 40,
                "y": 160,
                "width": 160,
                "height": 80,
                "label": "Client"
            },
            {
                "id": "web-app",
                "source_id": "web-app",
                "projection_id": "web-app",
                "x": 310,
                "y": 160,
                "width": 170,
                "height": 80,
                "label": "Web App"
            }
        ]),
        serde_json::json!([
            {
                "id": "client-submits-order",
                "source": "client",
                "target": "web-app",
                "source_id": "client-submits-order",
                "projection_id": "client-submits-order",
                "points": [
                    { "x": 200, "y": 200 },
                    { "x": 310, "y": 200 }
                ],
                "label": "submits order"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let edge = semantic_group(&doc, "data-dediren-edge-id", "client-submits-order");
    let label = child_element(edge, "text");
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        (x - 255.0).abs() <= 2.0,
        "label should use the available gap center when near-start overlaps nodes, got x={x}"
    );
    assert!(
        (y - 200.0).abs() <= 18.0,
        "label should stay close to the short horizontal edge, got y={y}"
    );
}

#[test]
fn svg_renderer_moves_edge_label_away_from_route_segments() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "vertical-edge",
                "source": "orders-api",
                "target": "worker",
                "source_id": "vertical-edge",
                "projection_id": "vertical-edge",
                "points": [
                    { "x": 220, "y": 80 },
                    { "x": 220, "y": 260 }
                ],
                "label": "publishes fulfillment"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let edge = semantic_group(&doc, "data-dediren-edge-id", "vertical-edge");
    let label = child_element(edge, "text");
    let label_box = text_box_from_svg(label, 14.0);

    assert!(
        !box_contains_point(
            label_box,
            220.0,
            label.attribute("y").unwrap().parse::<f64>().unwrap()
        ),
        "label box should not sit on top of its vertical route segment"
    );
}

#[test]
fn svg_renderer_keeps_horizontal_edge_label_close_to_route() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "horizontal-edge",
                "source": "orders-api",
                "target": "payments",
                "source_id": "horizontal-edge",
                "projection_id": "horizontal-edge",
                "points": [
                    { "x": 100, "y": 160 },
                    { "x": 320, "y": 160 }
                ],
                "label": "authorizes payment"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let edge = semantic_group(&doc, "data-dediren-edge-id", "horizontal-edge");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        (y - 160.0).abs() <= 18.0,
        "horizontal edge label should stay close to its route, got y={y}"
    );
}

#[test]
fn svg_renderer_separates_labels_for_parallel_horizontal_edges() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "upper-edge",
                "source": "orders-api",
                "target": "payments",
                "source_id": "upper-edge",
                "projection_id": "upper-edge",
                "points": [
                    { "x": 100, "y": 160 },
                    { "x": 320, "y": 160 }
                ],
                "label": "writes orders"
            },
            {
                "id": "lower-edge",
                "source": "orders-api",
                "target": "database",
                "source_id": "lower-edge",
                "projection_id": "lower-edge",
                "points": [
                    { "x": 100, "y": 172 },
                    { "x": 320, "y": 172 }
                ],
                "label": "authorizes payment"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let upper = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "upper-edge"),
        "text",
    );
    let lower = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "lower-edge"),
        "text",
    );

    assert!(
        !boxes_overlap(
            text_box_from_svg(upper, 14.0),
            text_box_from_svg(lower, 14.0)
        ),
        "parallel edge labels should not overlap"
    );
}

#[test]
fn svg_renderer_separates_labels_for_adjacent_multisegment_routes() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "writes-orders",
                "source": "orders-api",
                "target": "database",
                "source_id": "writes-orders",
                "projection_id": "writes-orders",
                "points": [
                    { "x": 596, "y": 384 },
                    { "x": 740, "y": 384 },
                    { "x": 740, "y": 438 },
                    { "x": 884, "y": 438 }
                ],
                "label": "writes orders"
            },
            {
                "id": "authorizes-payment",
                "source": "orders-api",
                "target": "payments",
                "source_id": "authorizes-payment",
                "projection_id": "authorizes-payment",
                "points": [
                    { "x": 596, "y": 396 },
                    { "x": 740, "y": 396 },
                    { "x": 740, "y": 346 },
                    { "x": 884, "y": 346 }
                ],
                "label": "authorizes payment"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let writes = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "writes-orders"),
        "text",
    );
    let authorizes = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "authorizes-payment"),
        "text",
    );
    let authorizes_y = authorizes.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        !boxes_overlap(
            text_box_from_svg(writes, 14.0),
            text_box_from_svg(authorizes, 14.0)
        ),
        "labels for adjacent multi-segment routes should not overlap"
    );
    assert!(
        (authorizes_y - 396.0)
            .abs()
            .min((authorizes_y - 346.0).abs())
            <= 32.0,
        "fallback label should stay close to the crowded edge, got y={authorizes_y}"
    );
    assert!(
        !box_intersects_horizontal_segment(
            text_box_from_svg(authorizes, 14.0),
            740.0,
            884.0,
            422.0
        ),
        "fallback label should not sit on another edge route"
    );
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

    let mut cmd = Command::cargo_bin("dediren-plugin-svg-render").unwrap();
    cmd.arg("render")
        .write_stdin(serde_json::to_string(&input).unwrap());
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("\"status\":\"error\""))
        .stdout(predicate::str::contains("DEDIREN_SVG_POLICY_INVALID"));
}

fn render_content(input: serde_json::Value) -> String {
    let mut cmd = Command::cargo_bin("dediren-plugin-svg-render").unwrap();
    cmd.arg("render")
        .write_stdin(serde_json::to_string(&input).unwrap());
    let output = cmd.assert().success().get_output().stdout.clone();
    let envelope: serde_json::Value = serde_json::from_slice(&output).unwrap();
    let content = envelope["data"]["content"].as_str().unwrap().to_string();
    write_render_artifact(&current_test_name(), &content);
    content
}

fn archimate_style_input() -> serde_json::Value {
    serde_json::json!({
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/archimate-oef-basic.json")).unwrap()
        ).unwrap(),
        "render_metadata": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-metadata/archimate-basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/archimate-svg.json")).unwrap()
        ).unwrap()
    })
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

fn svg_doc(content: &str) -> roxmltree::Document<'_> {
    roxmltree::Document::parse(content).unwrap()
}

fn semantic_group<'a, 'input>(
    doc: &'a roxmltree::Document<'input>,
    data_attr: &str,
    id: &str,
) -> roxmltree::Node<'a, 'input> {
    doc.descendants()
        .find(|node| node.has_tag_name("g") && node.attribute(data_attr) == Some(id))
        .unwrap_or_else(|| panic!("expected SVG to contain <g {data_attr}=\"{id}\">"))
}

fn child_element<'a, 'input>(
    node: roxmltree::Node<'a, 'input>,
    tag_name: &str,
) -> roxmltree::Node<'a, 'input> {
    node.children()
        .find(|child| child.has_tag_name(tag_name))
        .unwrap_or_else(|| {
            panic!(
                "expected <{}> to contain <{}>",
                node.tag_name().name(),
                tag_name
            )
        })
}

fn child_group_with_attr<'a, 'input>(
    parent: roxmltree::Node<'a, 'input>,
    attr_name: &str,
    attr_value: &str,
) -> roxmltree::Node<'a, 'input> {
    parent
        .children()
        .find(|node| node.has_tag_name("g") && node.attribute(attr_name) == Some(attr_value))
        .unwrap_or_else(|| panic!("missing child group with {attr_name}={attr_value}"))
}

fn child_elements<'a, 'input>(
    parent: roxmltree::Node<'a, 'input>,
    name: &'static str,
) -> impl Iterator<Item = roxmltree::Node<'a, 'input>> {
    parent
        .children()
        .filter(move |node| node.has_tag_name(name))
}

fn text_box_from_svg(label: roxmltree::Node<'_, '_>, font_size: f64) -> (f64, f64, f64, f64) {
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();
    let text = label.text().unwrap_or("");
    let half_width = text.chars().count() as f64 * font_size * 0.62 / 2.0;
    let width = half_width * 2.0;
    match label.attribute("text-anchor") {
        Some("end") => (x - width, y - font_size, x, y + font_size * 0.4),
        Some("start") => (x, y - font_size, x + width, y + font_size * 0.4),
        _ => (
            x - half_width,
            y - font_size,
            x + half_width,
            y + font_size * 0.4,
        ),
    }
}

fn box_contains_point(bounds: (f64, f64, f64, f64), x: f64, y: f64) -> bool {
    x >= bounds.0 && x <= bounds.2 && y >= bounds.1 && y <= bounds.3
}

fn box_center_y(bounds: (f64, f64, f64, f64)) -> f64 {
    (bounds.1 + bounds.3) / 2.0
}

fn horizontal_gap_to_x(bounds: (f64, f64, f64, f64), x: f64) -> f64 {
    if bounds.2 < x {
        x - bounds.2
    } else if bounds.0 > x {
        bounds.0 - x
    } else {
        0.0
    }
}

fn boxes_overlap(left: (f64, f64, f64, f64), right: (f64, f64, f64, f64)) -> bool {
    left.0 < right.2 && left.2 > right.0 && left.1 < right.3 && left.3 > right.1
}

fn box_intersects_horizontal_segment(
    bounds: (f64, f64, f64, f64),
    start_x: f64,
    end_x: f64,
    y: f64,
) -> bool {
    bounds.0 < end_x && bounds.2 > start_x && bounds.1 < y && bounds.3 > y
}

fn write_render_artifact(test_name: &str, content: &str) -> PathBuf {
    let path = workspace_file(&format!(
        ".test-output/renders/svg-render-plugin/{test_name}.svg"
    ));
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(&path, content).unwrap();
    path
}

fn current_test_name() -> String {
    std::thread::current()
        .name()
        .unwrap_or("unknown-test")
        .rsplit("::")
        .next()
        .unwrap_or("unknown-test")
        .to_string()
}

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}
