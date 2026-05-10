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
    assert_eq!(component_rect.attribute("fill"), Some("#e0f2fe"));
    assert_eq!(component_rect.attribute("stroke"), Some("#0369a1"));

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
    assert!(child_elements(service_decorator, "rect").count() >= 1);
    assert_eq!(child_elements(service_decorator, "path").count(), 0);
}

#[test]
fn svg_renderer_covers_each_archimate_square_node_type() {
    let policy = serde_json::from_str::<serde_json::Value>(
        &std::fs::read_to_string(workspace_file("fixtures/render-policy/archimate-svg.json"))
            .unwrap(),
    )
    .unwrap();
    let node_styles = policy["style"]["node_type_overrides"]
        .as_object()
        .expect("node type overrides should be an object");
    let node_styles = node_styles.clone();
    let mut nodes = Vec::new();
    let mut metadata_nodes = serde_json::Map::new();
    for (index, node_type) in ARCHIMATE_SQUARE_NODE_TYPES.iter().enumerate() {
        let row = index / 6;
        let column = index % 6;
        let id = format!("archimate-node-{index}");
        nodes.push(serde_json::json!({
            "id": id,
            "source_id": id,
            "projection_id": id,
            "x": 32 + column as i64 * 150,
            "y": 40 + row as i64 * 95,
            "width": 128,
            "height": 68,
            "label": node_type
        }));
        metadata_nodes.insert(
            id.clone(),
            serde_json::json!({
                "type": node_type,
                "source_id": id
            }),
        );
    }
    let input = archimate_render_input(
        policy,
        serde_json::Value::Array(nodes),
        serde_json::json!([]),
        serde_json::Value::Object(metadata_nodes),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    for (index, node_type) in ARCHIMATE_SQUARE_NODE_TYPES.iter().enumerate() {
        let id = format!("archimate-node-{index}");
        let expected_style = node_styles
            .get(*node_type)
            .unwrap_or_else(|| panic!("missing policy style for {node_type}"));
        let node = semantic_group(&doc, "data-dediren-node-id", &id);
        let node_shape = child_node_shape(node);
        assert_eq!(
            node_shape.attribute("fill"),
            expected_style["fill"].as_str(),
            "{node_type} should use its policy fill"
        );
        assert_eq!(
            node_shape.attribute("stroke"),
            expected_style["stroke"].as_str(),
            "{node_type} should use its policy stroke"
        );
        if is_archimate_motivation_node_type(node_type) {
            assert!(
                node_shape.has_tag_name("path"),
                "{node_type} should render as a cut-corner motivation element"
            );
            assert_eq!(
                node_shape.attribute("data-dediren-node-shape"),
                Some("archimate_cut_corner_rectangle"),
                "{node_type} should use the ArchiMate cut-corner motivation node shape"
            );
        } else {
            assert_eq!(
                node_shape.attribute("data-dediren-node-shape"),
                Some("archimate_rectangle"),
                "{node_type} should use the standard ArchiMate rectangular node shape"
            );
        }
        let expected_decorator = expected_style["decorator"]
            .as_str()
            .unwrap_or_else(|| panic!("missing policy decorator for {node_type}"));
        let decorator =
            child_group_with_attr(node, "data-dediren-node-decorator", expected_decorator);
        assert_eq!(
            decorator.attribute("data-dediren-icon-size"),
            Some("22"),
            "{node_type} should use the standard ArchiMate icon box size"
        );
        assert_eq!(
            decorator.attribute("data-dediren-icon-kind"),
            Some(expected_archimate_icon_kind(node_type)),
            "{node_type} should use the ArchiMate spec icon family"
        );
        assert_archimate_icon_primitives_fit_standard_box(node_type, decorator);
        if node_type.ends_with("Event") {
            let event_path = child_element(decorator, "path");
            assert!(
                event_path.attribute("data-dediren-icon-part") == Some("event-pill"),
                "{node_type} event decorator should use the ArchiMate event pill shape"
            );
        }
        assert_archimate_icon_morphology(node_type, decorator);
        let label = child_element(node, "text");
        let label_font_size = label
            .attribute("font-size")
            .unwrap_or("14")
            .parse::<f64>()
            .unwrap();
        for line in text_lines_from_svg(label) {
            assert!(
                estimated_svg_text_width(&line, label_font_size) <= 108.0,
                "{node_type} label line should fit inside the node, got {line}"
            );
        }
    }
}

#[test]
fn svg_renderer_applies_archimate_business_actor_decorator() {
    let mut input = archimate_style_input();
    input["layout_result"]["nodes"]
        .as_array_mut()
        .unwrap()
        .push(serde_json::json!({
            "id": "customer",
            "source_id": "customer",
            "projection_id": "customer",
            "x": 560,
            "y": 40,
            "width": 180,
            "height": 80,
            "label": "Customer"
        }));
    input["render_metadata"]["nodes"]["customer"] = serde_json::json!({
        "type": "BusinessActor",
        "source_id": "customer"
    });

    let content = render_content(input);
    let doc = svg_doc(&content);

    let actor = semantic_group(&doc, "data-dediren-node-id", "customer");
    let actor_rect = child_element(actor, "rect");
    assert_eq!(actor_rect.attribute("fill"), Some("#fff2cc"));
    assert_eq!(actor_rect.attribute("stroke"), Some("#d6b656"));

    let actor_decorator = child_group_with_attr(
        actor,
        "data-dediren-node-decorator",
        "archimate_business_actor",
    );
    assert!(child_elements(actor_decorator, "ellipse").count() >= 1);
    assert!(child_elements(actor_decorator, "path").count() >= 1);
}

#[test]
fn svg_renderer_applies_archimate_data_object_decorator() {
    let mut input = archimate_style_input();
    input["layout_result"]["nodes"]
        .as_array_mut()
        .unwrap()
        .push(serde_json::json!({
            "id": "order-data",
            "source_id": "order-data",
            "projection_id": "order-data",
            "x": 560,
            "y": 40,
            "width": 180,
            "height": 80,
            "label": "Order Data"
        }));
    input["render_metadata"]["nodes"]["order-data"] = serde_json::json!({
        "type": "DataObject",
        "source_id": "order-data"
    });
    input["policy"]["style"]["node_type_overrides"]["DataObject"] = serde_json::json!({
        "fill": "#e0f2fe",
        "stroke": "#0369a1",
        "decorator": "archimate_data_object"
    });

    let content = render_content(input);
    let doc = svg_doc(&content);

    let data_object = semantic_group(&doc, "data-dediren-node-id", "order-data");
    let data_object_rect = child_element(data_object, "rect");
    assert_eq!(data_object_rect.attribute("fill"), Some("#e0f2fe"));
    assert_eq!(data_object_rect.attribute("stroke"), Some("#0369a1"));

    let data_object_decorator = child_group_with_attr(
        data_object,
        "data-dediren-node-decorator",
        "archimate_data_object",
    );
    assert!(child_elements(data_object_decorator, "path")
        .any(|path| path.attribute("data-dediren-icon-part") == Some("document-body")));
    assert!(child_elements(data_object_decorator, "path")
        .any(|path| path.attribute("data-dediren-icon-part") == Some("document-header")));
}

#[test]
fn svg_renderer_applies_archimate_technology_node_decorator() {
    let mut input = archimate_style_input();
    input["layout_result"]["nodes"]
        .as_array_mut()
        .unwrap()
        .push(serde_json::json!({
            "id": "postgres",
            "source_id": "postgres",
            "projection_id": "postgres",
            "x": 560,
            "y": 40,
            "width": 180,
            "height": 80,
            "label": "PostgreSQL"
        }));
    input["render_metadata"]["nodes"]["postgres"] = serde_json::json!({
        "type": "TechnologyNode",
        "source_id": "postgres"
    });

    let content = render_content(input);
    let doc = svg_doc(&content);

    let technology_node = semantic_group(&doc, "data-dediren-node-id", "postgres");
    let technology_rect = child_element(technology_node, "rect");
    assert_eq!(technology_rect.attribute("fill"), Some("#d5e8d4"));
    assert_eq!(technology_rect.attribute("stroke"), Some("#4d7c0f"));

    let technology_decorator = child_group_with_attr(
        technology_node,
        "data-dediren-node-decorator",
        "archimate_technology_node",
    );
    assert!(child_elements(technology_decorator, "rect").count() >= 1);
    let node_edges = child_element(technology_decorator, "path");
    assert_eq!(
        node_edges.attribute("data-dediren-icon-part"),
        Some("node-3d-edges")
    );
    assert!(
        node_edges
            .attribute("d")
            .unwrap_or("")
            .contains("M 732.0 48.0 L 732.0 60.8"),
        "technology node icon should include the right-side rear vertical edge"
    );
}

#[test]
fn svg_renderer_applies_archimate_realization_edge_notation() {
    let mut input = archimate_style_input();
    input["policy"]["style"]["edge_type_overrides"]["Realization"]["line_style"] =
        serde_json::json!("dashed");
    input["policy"]["style"]["edge_type_overrides"]["Realization"]["marker_end"] =
        serde_json::json!("hollow_triangle");

    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "orders-realizes-service");
    let path = child_element(edge, "path");
    assert_eq!(path.attribute("stroke-dasharray"), Some("8 5"));
    assert_eq!(
        path.attribute("marker-end"),
        Some("url(#marker-end-orders-realizes-service)")
    );

    let marker = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("marker")
                && node.attribute("id") == Some("marker-end-orders-realizes-service")
                && node.attribute("data-dediren-edge-marker-end") == Some("hollow_triangle")
        })
        .expect("hollow triangle marker");
    let marker_path = child_element(marker, "path");
    assert_eq!(marker_path.attribute("fill"), Some("#ffffff"));
    assert_eq!(marker_path.attribute("stroke"), Some("#374151"));
}

#[test]
fn svg_renderer_applies_archimate_relationship_start_markers() {
    let mut input = archimate_style_input();
    input["render_metadata"]["edges"]["orders-realizes-service"]["type"] =
        serde_json::json!("Composition");
    input["policy"]["style"]["edge_type_overrides"]["Composition"] = serde_json::json!({
        "marker_start": "filled_diamond",
        "marker_end": "none"
    });

    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "orders-realizes-service");
    let path = child_element(edge, "path");
    assert_eq!(
        path.attribute("marker-start"),
        Some("url(#marker-start-orders-realizes-service)")
    );
    assert_eq!(path.attribute("marker-end"), None);

    let marker = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("marker")
                && node.attribute("id") == Some("marker-start-orders-realizes-service")
                && node.attribute("data-dediren-edge-marker-start") == Some("filled_diamond")
        })
        .expect("filled diamond start marker");
    let marker_path = child_element(marker, "path");
    assert_eq!(marker_path.attribute("fill"), Some("#475569"));
}

#[test]
fn svg_renderer_covers_each_archimate_relationship_type() {
    let policy = serde_json::from_str::<serde_json::Value>(
        &std::fs::read_to_string(workspace_file("fixtures/render-policy/archimate-svg.json"))
            .unwrap(),
    )
    .unwrap();
    let edge_styles = policy["style"]["edge_type_overrides"]
        .as_object()
        .expect("edge type overrides should be an object");
    let edge_styles = edge_styles.clone();
    let mut nodes = Vec::new();
    let mut edges = Vec::new();
    let mut metadata_edges = serde_json::Map::new();
    for (index, relationship_type) in ARCHIMATE_RELATIONSHIP_TYPES.iter().enumerate() {
        let y = 60 + index as i64 * 48;
        let source = format!("relationship-source-{index}");
        let target = format!("relationship-target-{index}");
        let edge_id = format!("relationship-edge-{index}");
        nodes.push(serde_json::json!({
            "id": source,
            "source_id": source,
            "projection_id": source,
            "x": 40,
            "y": y,
            "width": 80,
            "height": 32,
            "label": "Source"
        }));
        nodes.push(serde_json::json!({
            "id": target,
            "source_id": target,
            "projection_id": target,
            "x": 260,
            "y": y,
            "width": 80,
            "height": 32,
            "label": "Target"
        }));
        edges.push(serde_json::json!({
            "id": edge_id,
            "source": source,
            "target": target,
            "source_id": edge_id,
            "projection_id": edge_id,
            "points": [
                { "x": 120, "y": y + 16 },
                { "x": 260, "y": y + 16 }
            ],
            "label": relationship_type
        }));
        metadata_edges.insert(
            edge_id.clone(),
            serde_json::json!({
                "type": relationship_type,
                "source_id": edge_id
            }),
        );
    }
    let input = archimate_render_input(
        policy,
        serde_json::Value::Array(nodes),
        serde_json::Value::Array(edges),
        serde_json::json!({}),
        serde_json::Value::Object(metadata_edges),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    for (index, relationship_type) in ARCHIMATE_RELATIONSHIP_TYPES.iter().enumerate() {
        let edge_id = format!("relationship-edge-{index}");
        let expected_style = edge_styles
            .get(*relationship_type)
            .unwrap_or_else(|| panic!("missing policy style for {relationship_type}"));
        let edge = semantic_group(&doc, "data-dediren-edge-id", &edge_id);
        let path = child_element(edge, "path");
        assert_marker(
            &doc,
            path,
            &edge_id,
            "start",
            expected_style
                .get("marker_start")
                .and_then(|value| value.as_str()),
            relationship_type,
        );
        assert_marker(
            &doc,
            path,
            &edge_id,
            "end",
            expected_style
                .get("marker_end")
                .and_then(|value| value.as_str())
                .or(Some("filled_arrow")),
            relationship_type,
        );
        match expected_style
            .get("line_style")
            .and_then(|value| value.as_str())
        {
            Some("dashed") => assert_eq!(
                path.attribute("stroke-dasharray"),
                Some("8 5"),
                "{relationship_type} should render dashed"
            ),
            _ => assert_eq!(
                path.attribute("stroke-dasharray"),
                None,
                "{relationship_type} should render solid"
            ),
        }
    }
}

#[test]
fn svg_renderer_edge_id_override_can_disable_marker() {
    let mut input = archimate_style_input();
    input["policy"]["style"]["edge_type_overrides"]["Realization"]["line_style"] =
        serde_json::json!("dashed");
    input["policy"]["style"]["edge_type_overrides"]["Realization"]["marker_end"] =
        serde_json::json!("hollow_triangle");
    input["policy"]["style"]["edge_overrides"] = serde_json::json!({
        "orders-realizes-service": {
            "marker_end": "none",
            "line_style": "solid"
        }
    });

    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "orders-realizes-service");
    let path = child_element(edge, "path");
    assert_eq!(path.attribute("marker-end"), None);
    assert_eq!(path.attribute("stroke-dasharray"), None);
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
        Some("url(#marker-end-labeled-edge)")
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
    let authorizes_path = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "authorizes-payment"),
        "path",
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
    assert!(
        authorizes_path
            .attribute("d")
            .is_some_and(|data| data.contains(" Q ")),
        "adjacent multi-segment routes should draw a line jump where they share a route segment"
    );
    assert!(
        authorizes_path.attribute("d").is_some_and(|data| data
            .contains("746.0 396.0")
            && data.contains("L 746.0 384.0")),
        "adjacent multi-segment routes should run on a parallel offset while their original route overlaps"
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

fn archimate_render_input(
    policy: serde_json::Value,
    nodes: serde_json::Value,
    edges: serde_json::Value,
    metadata_nodes: serde_json::Value,
    metadata_edges: serde_json::Value,
) -> serde_json::Value {
    serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "archimate-coverage",
            "nodes": nodes,
            "edges": edges,
            "groups": [],
            "warnings": []
        },
        "render_metadata": {
            "render_metadata_schema_version": "render-metadata.schema.v1",
            "semantic_profile": "archimate",
            "nodes": metadata_nodes,
            "edges": metadata_edges
        },
        "policy": policy
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

fn child_node_shape<'a, 'input>(node: roxmltree::Node<'a, 'input>) -> roxmltree::Node<'a, 'input> {
    node.children()
        .find(|child| child.attribute("data-dediren-node-shape").is_some())
        .unwrap_or_else(|| panic!("missing primary node shape"))
}

fn child_elements<'a, 'input>(
    parent: roxmltree::Node<'a, 'input>,
    name: &'static str,
) -> impl Iterator<Item = roxmltree::Node<'a, 'input>> {
    parent
        .children()
        .filter(move |node| node.has_tag_name(name))
}

fn is_archimate_motivation_node_type(node_type: &str) -> bool {
    matches!(
        node_type,
        "Stakeholder"
            | "Driver"
            | "Assessment"
            | "Goal"
            | "Outcome"
            | "Value"
            | "Meaning"
            | "Constraint"
            | "Requirement"
            | "Principle"
    )
}

fn expected_archimate_icon_kind(node_type: &str) -> &'static str {
    match node_type {
        "BusinessInterface" | "ApplicationInterface" | "TechnologyInterface" => "interface",
        "BusinessCollaboration" | "ApplicationCollaboration" | "TechnologyCollaboration" => {
            "collaboration"
        }
        "BusinessActor" => "actor",
        "BusinessRole" => "role",
        "BusinessService" | "ApplicationService" | "TechnologyService" => "service",
        "BusinessInteraction" | "ApplicationInteraction" | "TechnologyInteraction" => "interaction",
        "BusinessFunction" | "ApplicationFunction" | "TechnologyFunction" => "function",
        "BusinessProcess" | "ApplicationProcess" | "TechnologyProcess" => "process",
        "BusinessEvent" | "ApplicationEvent" | "TechnologyEvent" | "ImplementationEvent" => "event",
        "BusinessObject" | "DataObject" => "object",
        "ApplicationComponent" => "component",
        "Contract" => "contract",
        "Product" => "product",
        "Representation" => "representation",
        "Location" => "location",
        "Grouping" => "grouping",
        "Stakeholder" => "stakeholder",
        "Driver" => "driver",
        "Assessment" => "assessment",
        "Goal" => "goal",
        "Outcome" => "outcome",
        "Value" => "value",
        "Meaning" => "meaning",
        "Constraint" => "constraint",
        "Requirement" => "requirement",
        "Principle" => "principle",
        "CourseOfAction" => "course_of_action",
        "Resource" => "resource",
        "ValueStream" => "value_stream",
        "Capability" => "capability",
        "Plateau" => "plateau",
        "WorkPackage" => "work_package",
        "Deliverable" => "deliverable",
        "Gap" => "gap",
        "Artifact" => "artifact",
        "SystemSoftware" => "system_software",
        "Device" => "device",
        "Facility" => "facility",
        "Equipment" => "equipment",
        "Node" => "node",
        "Material" => "material",
        "CommunicationNetwork" | "DistributionNetwork" => "network",
        "Path" => "path",
        other => panic!("missing expected icon kind for {other}"),
    }
}

fn assert_archimate_icon_morphology(node_type: &str, decorator: roxmltree::Node<'_, '_>) {
    match node_type {
        "BusinessInterface" | "ApplicationInterface" | "TechnologyInterface" => {
            assert_eq!(
                child_elements(decorator, "ellipse").count(),
                1,
                "{node_type} interface icon should use a lollipop ellipse"
            );
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} interface icon should include a lollipop stem"
            );
        }
        "BusinessActor" => {
            assert_eq!(
                child_elements(decorator, "ellipse").count(),
                1,
                "{node_type} actor icon should use a head ellipse"
            );
            assert!(
                child_elements(decorator, "path").count() >= 1,
                "{node_type} actor icon should include body strokes"
            );
        }
        "BusinessInteraction" | "ApplicationInteraction" | "TechnologyInteraction" => {
            assert_eq!(
                child_elements(decorator, "path")
                    .filter(
                        |path| path.attribute("data-dediren-icon-part") == Some("interaction-half")
                    )
                    .count(),
                2,
                "{node_type} interaction icon should use two separated half-circles"
            );
        }
        "BusinessService" | "ApplicationService" | "TechnologyService" => {
            assert_eq!(
                child_elements(decorator, "rect").count(),
                1,
                "{node_type} service icon should use one rounded service capsule"
            );
        }
        "BusinessFunction" | "ApplicationFunction" | "TechnologyFunction" => {
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} function icon should use one bookmark path"
            );
            let bookmark = child_elements(decorator, "path").any(|path| {
                path.attribute("data-dediren-icon-part") == Some("function-chevron-up")
            });
            assert!(
                bookmark,
                "{node_type} function icon should expose the upward chevron shape"
            );
        }
        "BusinessProcess" | "ApplicationProcess" | "TechnologyProcess" => {
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} should use one process arrow path"
            );
            let process_arrow = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("process-arrow"));
            assert!(
                process_arrow,
                "{node_type} should use the ArchiMate process arrow shape"
            );
        }
        "BusinessEvent" | "ApplicationEvent" | "TechnologyEvent" | "ImplementationEvent" => {
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} should use one event path"
            );
            let event_pill = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("event-pill"));
            assert!(
                event_pill,
                "{node_type} should use the ArchiMate event pill shape"
            );
        }
        "ValueStream" => {
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} should use one value stream path"
            );
            let chevron = child_elements(decorator, "path").any(|path| {
                path.attribute("data-dediren-icon-part") == Some("value-stream-chevron")
            });
            assert!(
                chevron,
                "{node_type} should use the ArchiMate value stream chevron"
            );
        }
        "BusinessRole" => {
            assert_eq!(
                child_elements(decorator, "ellipse").count(),
                1,
                "{node_type} icon should use the side-cylinder end ellipse"
            );
            assert!(
                child_elements(decorator, "path")
                    .any(|path| path.attribute("data-dediren-icon-part") == Some("side-cylinder")),
                "{node_type} icon should use the right-facing side-cylinder body"
            );
            assert_eq!(
                child_elements(decorator, "rect").count(),
                0,
                "{node_type} icon should not use a generic service pill"
            );
        }
        "BusinessCollaboration" | "ApplicationCollaboration" | "TechnologyCollaboration" => {
            assert!(
                child_elements(decorator, "circle").count() >= 2,
                "{node_type} icon should use overlapping Venn circles"
            );
            assert!(
                child_elements(decorator, "circle")
                    .all(|circle| circle.attribute("data-dediren-icon-part")
                        == Some("collaboration-circles")),
                "{node_type} icon should expose collaboration circle primitives"
            );
        }
        "Product" => {
            let tab = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("product-tab"));
            assert!(tab, "{node_type} icon should include a product tab");
        }
        "Contract" => {
            let lines = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("contract-lines"));
            assert!(lines, "{node_type} icon should include contract line marks");
        }
        "Representation" => {
            let wavy = child_elements(decorator, "path").any(|path| {
                path.attribute("data-dediren-icon-part") == Some("wavy-representation")
            });
            assert!(
                wavy,
                "{node_type} icon should use a wavy representation shape"
            );
        }
        "BusinessObject" | "DataObject" | "Artifact" => {
            let body = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("document-body"));
            assert!(
                body,
                "{node_type} icon should include an unfolded document body"
            );
            let header = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("document-header"));
            assert!(
                header,
                "{node_type} icon should include a document header line"
            );
        }
        "Deliverable" => {
            let wavy_document = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("wavy-document"));
            assert!(
                wavy_document,
                "{node_type} icon should use the wavy deliverable document"
            );
        }
        "ApplicationComponent" => {
            assert_eq!(
                child_elements(decorator, "rect").count(),
                3,
                "{node_type} icon should use the component rectangle plus two side tabs"
            );
        }
        "Stakeholder" => {
            assert!(
                child_elements(decorator, "ellipse").count() >= 1,
                "{node_type} icon should use the right cylinder end ellipse"
            );
            assert!(
                child_elements(decorator, "path")
                    .any(|path| path.attribute("data-dediren-icon-part") == Some("side-cylinder")),
                "{node_type} icon should use a side-cylinder body"
            );
        }
        "Assessment" => {
            assert_eq!(
                child_elements(decorator, "ellipse").count(),
                1,
                "{node_type} icon should use a magnifier lens"
            );
            let handle = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("assessment-handle"));
            assert!(handle, "{node_type} icon should include a magnifier handle");
        }
        "Goal" => {
            assert!(
                child_elements(decorator, "ellipse").count() >= 3,
                "{node_type} icon should use three concentric target rings"
            );
        }
        "Constraint" | "Requirement" => {
            let expected_part = if node_type == "Constraint" {
                "constraint-parallelogram"
            } else {
                "requirement-parallelogram"
            };
            assert!(
                child_elements(decorator, "path")
                    .any(|path| path.attribute("data-dediren-icon-part") == Some(expected_part)),
                "{node_type} icon should use its ArchiMate parallelogram variant"
            );
            if node_type == "Constraint" {
                assert!(
                    child_elements(decorator, "path")
                        .any(|path| path.attribute("data-dediren-icon-part")
                            == Some("constraint-left-line")),
                    "{node_type} icon should include the extra left-side line"
                );
            }
            assert_eq!(
                child_elements(decorator, "rect").count(),
                0,
                "{node_type} icon should not render as a rectangle"
            );
        }
        "Outcome" | "CourseOfAction" => {
            assert!(
                child_elements(decorator, "ellipse").count() >= 3,
                "{node_type} icon should use a target symbol"
            );
            let arrow = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("target-arrow"));
            assert!(arrow, "{node_type} icon should include the target arrow");
        }
        "Driver" => {
            assert!(
                child_elements(decorator, "ellipse").count() >= 2,
                "{node_type} icon should use a circled driver symbol with a center mark"
            );
            let spokes = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("driver-spokes"));
            assert!(spokes, "{node_type} icon should include radial spokes");
        }
        "Capability" => {
            assert_eq!(
                child_elements(decorator, "rect").count(),
                3,
                "{node_type} icon should use three square stair blocks"
            );
            assert!(
                child_elements(decorator, "rect").all(|rect| rect
                    .attribute("data-dediren-icon-part")
                    == Some("capability-step")),
                "{node_type} icon should expose each capability stair block"
            );
        }
        "Resource" => {
            let bars = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("resource-bars"));
            let handle = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("resource-handle"));
            assert!(bars, "{node_type} icon should include resource bars");
            assert!(handle, "{node_type} icon should include a resource handle");
        }
        "WorkPackage" => {
            let corner = child_elements(decorator, "path").any(|path| {
                path.attribute("data-dediren-icon-part") == Some("work-package-loop-arrow")
            });
            assert!(
                corner,
                "{node_type} icon should include the circular loop arrow"
            );
        }
        "Gap" => {
            assert_eq!(
                child_elements(decorator, "ellipse").count(),
                1,
                "{node_type} icon should render a single gap marker ellipse"
            );
            let gap_lines = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("gap-lines"));
            assert!(gap_lines, "{node_type} icon should include gap guide lines");
        }
        "SystemSoftware" => {
            assert!(
                child_elements(decorator, "ellipse").count() >= 2,
                "{node_type} icon should use overlapping software ellipses"
            );
            assert!(
                child_elements(decorator, "ellipse")
                    .all(|ellipse| ellipse.attribute("data-dediren-icon-part")
                        == Some("system-software-disks")),
                "{node_type} icon should expose system software disk primitives"
            );
        }
        "Node" => {
            assert_eq!(
                child_elements(decorator, "rect").count(),
                1,
                "{node_type} icon should include one 3D node face"
            );
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} icon should include 3D node edges"
            );
            let node_edges = child_element(decorator, "path");
            assert_eq!(
                node_edges.attribute("data-dediren-icon-part"),
                Some("node-3d-edges"),
                "{node_type} icon should expose the 3D edge path"
            );
            assert!(
                node_edges
                    .attribute("d")
                    .unwrap_or("")
                    .matches(" L ")
                    .count()
                    >= 6,
                "{node_type} icon should include the right-side rear vertical edge"
            );
        }
        "Path" => {
            let dashed = child_elements(decorator, "path").any(|path| {
                path.attribute("data-dediren-icon-part") == Some("path-line")
                    && path.attribute("stroke-dasharray").is_some()
            });
            let arrowheads = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("path-arrowheads"));
            assert!(dashed, "{node_type} icon should include a dashed path line");
            assert!(
                arrowheads,
                "{node_type} icon should include separate arrowheads"
            );
        }
        "Device" => {
            let stand = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("device-stand"));
            assert!(stand, "{node_type} icon should include a device stand");
        }
        "Facility" => {
            let roof = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("factory-roof"));
            assert!(roof, "{node_type} icon should include a factory roofline");
        }
        "Equipment" => {
            assert!(
                child_elements(decorator, "circle").count() >= 3,
                "{node_type} icon should render gear-like wheels"
            );
        }
        "Material" => {
            let material = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("material-hexagon"));
            assert!(
                material,
                "{node_type} icon should expose the material hexagon"
            );
            let lines = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("material-lines"));
            assert!(
                lines,
                "{node_type} icon should include inner material lines"
            );
        }
        "CommunicationNetwork" | "DistributionNetwork" => {
            assert!(
                child_elements(decorator, "circle").count() >= 4,
                "{node_type} icon should use connected network nodes"
            );
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} icon should include network connectors"
            );
        }
        _ => {}
    }
}

fn assert_archimate_icon_primitives_fit_standard_box(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    for rect in child_elements(decorator, "rect") {
        let width = rect.attribute("width").unwrap().parse::<f64>().unwrap();
        let height = rect.attribute("height").unwrap().parse::<f64>().unwrap();
        assert!(
            width <= 22.0 && height <= 22.0,
            "{node_type} icon rect primitive should fit in the standard icon box, got {width}x{height}"
        );
    }
    for circle in child_elements(decorator, "circle") {
        let radius = circle.attribute("r").unwrap().parse::<f64>().unwrap();
        assert!(
            radius <= 11.0,
            "{node_type} icon circle primitive should fit in the standard icon box, got radius {radius}"
        );
    }
    for ellipse in child_elements(decorator, "ellipse") {
        let rx = ellipse.attribute("rx").unwrap().parse::<f64>().unwrap();
        let ry = ellipse.attribute("ry").unwrap().parse::<f64>().unwrap();
        assert!(
            rx <= 11.0 && ry <= 11.0,
            "{node_type} icon ellipse primitive should fit in the standard icon box, got {rx}x{ry}"
        );
    }
}

fn assert_marker(
    doc: &roxmltree::Document<'_>,
    path: roxmltree::Node<'_, '_>,
    edge_id: &str,
    position: &str,
    expected_marker: Option<&str>,
    relationship_type: &str,
) {
    let attr_name = format!("marker-{position}");
    let id = format!("marker-{position}-{edge_id}");
    match expected_marker {
        Some("none") | None => assert_eq!(
            path.attribute(attr_name.as_str()),
            None,
            "{relationship_type} should not render a {position} marker"
        ),
        Some(marker) => {
            assert_eq!(
                path.attribute(attr_name.as_str()),
                Some(format!("url(#{id})").as_str()),
                "{relationship_type} should reference its {position} marker"
            );
            doc.descendants()
                .find(|node| {
                    node.has_tag_name("marker")
                        && node.attribute("id") == Some(id.as_str())
                        && node.attribute(format!("data-dediren-edge-marker-{position}").as_str())
                            == Some(marker)
                })
                .unwrap_or_else(|| {
                    panic!("expected {relationship_type} to emit {position} marker {marker}")
                });
        }
    }
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

fn text_lines_from_svg(label: roxmltree::Node<'_, '_>) -> Vec<String> {
    let tspan_lines: Vec<String> = label
        .children()
        .filter(|node| node.has_tag_name("tspan"))
        .filter_map(|node| node.text().map(ToString::to_string))
        .collect();
    if tspan_lines.is_empty() {
        vec![label.text().unwrap_or("").to_string()]
    } else {
        tspan_lines
    }
}

fn estimated_svg_text_width(text: &str, font_size: f64) -> f64 {
    text.chars().count() as f64 * font_size * 0.62
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

const ARCHIMATE_SQUARE_NODE_TYPES: &[&str] = &[
    "Plateau",
    "WorkPackage",
    "Deliverable",
    "ImplementationEvent",
    "Gap",
    "Grouping",
    "Location",
    "Stakeholder",
    "Driver",
    "Assessment",
    "Goal",
    "Outcome",
    "Value",
    "Meaning",
    "Constraint",
    "Requirement",
    "Principle",
    "CourseOfAction",
    "Resource",
    "ValueStream",
    "Capability",
    "BusinessInterface",
    "BusinessCollaboration",
    "BusinessActor",
    "BusinessRole",
    "BusinessService",
    "BusinessInteraction",
    "BusinessFunction",
    "BusinessProcess",
    "BusinessEvent",
    "Product",
    "BusinessObject",
    "Contract",
    "Representation",
    "ApplicationInterface",
    "ApplicationCollaboration",
    "ApplicationComponent",
    "ApplicationService",
    "ApplicationInteraction",
    "ApplicationFunction",
    "ApplicationProcess",
    "ApplicationEvent",
    "DataObject",
    "TechnologyInterface",
    "TechnologyCollaboration",
    "Node",
    "SystemSoftware",
    "Device",
    "Facility",
    "Equipment",
    "Path",
    "TechnologyService",
    "TechnologyInteraction",
    "TechnologyFunction",
    "TechnologyProcess",
    "TechnologyEvent",
    "Artifact",
    "Material",
    "CommunicationNetwork",
    "DistributionNetwork",
];

const ARCHIMATE_RELATIONSHIP_TYPES: &[&str] = &[
    "Composition",
    "Aggregation",
    "Assignment",
    "Realization",
    "Specialization",
    "Serving",
    "Access",
    "Influence",
    "Association",
    "Triggering",
    "Flow",
];

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}
