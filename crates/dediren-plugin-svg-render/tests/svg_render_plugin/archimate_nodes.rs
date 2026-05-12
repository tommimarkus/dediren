use super::common::*;

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
    for (index, node_type) in ARCHIMATE_NODE_TYPES.iter().enumerate() {
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
    for (index, node_type) in ARCHIMATE_NODE_TYPES.iter().enumerate() {
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
        let expected_shape = expected_archimate_rectangular_node_shape(node_type);
        assert_eq!(
            node_shape.attribute("data-dediren-node-shape"),
            Some(expected_shape),
            "{node_type} should use the ArchiMate rectangular representation"
        );
        assert_archimate_rectangular_node_shape(node_type, node_shape);
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
        assert_archimate_icon_primitives_stay_in_standard_box(node_type, node, decorator);
        assert_archimate_icon_primitives_are_centered_in_standard_box(node_type, node, decorator);
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
    let data_object_rect = child_node_shape(data_object);
    assert_eq!(
        data_object_rect.attribute("data-dediren-node-shape"),
        Some("archimate_rectangle")
    );
    assert_eq!(data_object_rect.attribute("fill"), Some("#e0f2fe"));
    assert_eq!(data_object_rect.attribute("stroke"), Some("#0369a1"));
    assert_eq!(data_object_rect.attribute("rx"), Some("0"));

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
        "type": "Node",
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
            .contains("M 734.0 49.0 L 734.0 61.8"),
        "technology node icon should include the right-side rear vertical edge"
    );
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
fn svg_renderer_rejects_unknown_archimate_node_type() {
    let mut input = archimate_style_input();
    input["render_metadata"]["nodes"]["orders-component"]["type"] =
        serde_json::json!("TechnologyNode");

    let envelope = render_error(input, "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("TechnologyNode"));
}

#[test]
fn svg_renderer_rejects_unknown_archimate_policy_node_type_override() {
    let mut input = archimate_style_input();
    input["policy"]["style"]["node_type_overrides"]["TechnologyNode"] =
        serde_json::json!({ "fill": "#d5e8d4" });

    let envelope = render_error(input, "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("TechnologyNode"));
}
