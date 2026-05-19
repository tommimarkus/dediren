use super::common::*;

#[test]
fn svg_renderer_applies_uml_relationship_markers() {
    let input = serde_json::json!({
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/uml-basic.json")).unwrap()
        ).unwrap(),
        "render_metadata": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-metadata/uml-basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap()
        ).unwrap()
    });

    let content = render_content(input);
    let artifact = write_render_artifact(&current_test_name(), &content);
    assert!(artifact.exists());
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "order-has-lines");
    let path = child_element(edge, "path");
    assert_eq!(
        path.attribute("marker-start"),
        Some("url(#marker-start-order-has-lines)")
    );
    assert!(
        content.contains(r#"data-dediren-edge-marker-start="filled_diamond""#),
        "expected UML composition filled diamond marker in SVG"
    );
}

#[test]
fn svg_renderer_covers_each_uml_relationship_marker() {
    let policy = serde_json::from_str::<serde_json::Value>(
        &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap(),
    )
    .unwrap();
    let edge_styles = policy["style"]["edge_type_overrides"]
        .as_object()
        .expect("edge type overrides should be an object");
    let edge_styles = edge_styles.clone();
    let mut nodes = Vec::new();
    let mut edges = Vec::new();
    let mut metadata_nodes = serde_json::Map::new();
    let mut metadata_edges = serde_json::Map::new();

    for (index, relationship_type) in UML_RELATIONSHIP_TYPES.iter().enumerate() {
        let y = 60 + index as i64 * 54;
        let source = format!("relationship-source-{index}");
        let target = format!("relationship-target-{index}");
        let edge_id = format!("relationship-edge-{index}");
        let node_type = if is_uml_activity_flow_type(relationship_type) {
            "Action"
        } else {
            "Class"
        };
        nodes.push(serde_json::json!({
            "id": source,
            "source_id": source,
            "projection_id": source,
            "x": 40,
            "y": y,
            "width": 120,
            "height": 36,
            "label": "Source"
        }));
        nodes.push(serde_json::json!({
            "id": target,
            "source_id": target,
            "projection_id": target,
            "x": 360,
            "y": y,
            "width": 120,
            "height": 36,
            "label": "Target"
        }));
        edges.push(serde_json::json!({
            "id": edge_id,
            "source": source,
            "target": target,
            "source_id": edge_id,
            "projection_id": edge_id,
            "points": [
                { "x": 160, "y": y + 18 },
                { "x": 360, "y": y + 18 }
            ],
            "label": relationship_type
        }));
        metadata_nodes.insert(
            source.clone(),
            serde_json::json!({
                "type": node_type,
                "source_id": source
            }),
        );
        metadata_nodes.insert(
            target.clone(),
            serde_json::json!({
                "type": node_type,
                "source_id": target
            }),
        );
        metadata_edges.insert(
            edge_id.clone(),
            serde_json::json!({
                "type": relationship_type,
                "source_id": edge_id
            }),
        );
    }

    let input = serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "uml-relationship-marker-test",
            "nodes": nodes,
            "edges": edges,
            "groups": [],
            "warnings": []
        },
        "render_metadata": {
            "render_metadata_schema_version": "render-metadata.schema.v1",
            "semantic_profile": "uml",
            "nodes": serde_json::Value::Object(metadata_nodes),
            "edges": serde_json::Value::Object(metadata_edges)
        },
        "policy": policy
    });

    let content = render_content(input);
    let artifact = write_render_artifact(&current_test_name(), &content);
    assert!(artifact.exists());
    let doc = svg_doc(&content);

    assert_eq!(
        edge_styles.len(),
        UML_RELATIONSHIP_TYPES.len(),
        "UML policy should style every covered relationship type"
    );
    for (index, relationship_type) in UML_RELATIONSHIP_TYPES.iter().enumerate() {
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
                .and_then(|value| value.as_str()),
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

fn is_uml_activity_flow_type(relationship_type: &str) -> bool {
    matches!(relationship_type, "ControlFlow" | "ObjectFlow")
}

#[test]
fn svg_renderer_rejects_invalid_uml_relationship_endpoint() {
    let input = serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "uml-invalid-endpoint-test",
            "nodes": [
                {
                    "id": "source",
                    "source_id": "source",
                    "projection_id": "source",
                    "x": 40,
                    "y": 40,
                    "width": 120,
                    "height": 72,
                    "label": "Source"
                },
                {
                    "id": "target",
                    "source_id": "target",
                    "projection_id": "target",
                    "x": 360,
                    "y": 40,
                    "width": 120,
                    "height": 72,
                    "label": "Target"
                }
            ],
            "edges": [
                {
                    "id": "invalid-control-flow",
                    "source": "source",
                    "target": "target",
                    "source_id": "invalid-control-flow",
                    "projection_id": "invalid-control-flow",
                    "points": [{ "x": 160, "y": 76 }, { "x": 360, "y": 76 }],
                    "label": ""
                }
            ],
            "groups": [],
            "warnings": []
        },
        "render_metadata": {
            "render_metadata_schema_version": "render-metadata.schema.v1",
            "semantic_profile": "uml",
            "nodes": {
                "source": { "type": "Class", "source_id": "source" },
                "target": { "type": "Class", "source_id": "target" }
            },
            "edges": {
                "invalid-control-flow": { "type": "ControlFlow", "source_id": "invalid-control-flow" }
            }
        },
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap()
        ).unwrap()
    });

    render_error(input, "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
}
