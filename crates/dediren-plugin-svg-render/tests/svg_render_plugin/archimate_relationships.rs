use super::common::*;

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
        serde_json::json!("Assignment");
    input["policy"]["style"]["edge_type_overrides"]["Assignment"] = serde_json::json!({
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
fn svg_renderer_rejects_unknown_archimate_relationship_type() {
    let mut input = archimate_style_input();
    input["render_metadata"]["edges"]["orders-realizes-service"]["type"] =
        serde_json::json!("ConnectsTo");

    let envelope = render_error(input, "DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("ConnectsTo"));
}

#[test]
fn svg_renderer_rejects_invalid_archimate_relationship_endpoint() {
    let mut input = archimate_style_input();
    input["render_metadata"]["nodes"]["orders-component"]["type"] =
        serde_json::json!("ApplicationService");
    input["render_metadata"]["nodes"]["orders-service"]["type"] =
        serde_json::json!("ApplicationComponent");
    input["render_metadata"]["edges"]["orders-realizes-service"]["type"] =
        serde_json::json!("Realization");

    let envelope = render_error(input, "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("ApplicationService"));
    assert!(message.contains("Realization"));
    assert!(message.contains("ApplicationComponent"));
}

#[test]
fn svg_renderer_rejects_unknown_archimate_policy_relationship_type_override() {
    let mut input = archimate_style_input();
    input["policy"]["style"]["edge_type_overrides"]["ConnectsTo"] =
        serde_json::json!({ "stroke": "#374151" });

    let envelope = render_error(input, "DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("ConnectsTo"));
}
