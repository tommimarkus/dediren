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
    let input = serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "uml-relationship-marker-test",
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
                },
                {
                    "id": "activity-source",
                    "source_id": "activity-source",
                    "projection_id": "activity-source",
                    "x": 40,
                    "y": 176,
                    "width": 120,
                    "height": 72,
                    "label": "Action"
                },
                {
                    "id": "activity-target",
                    "source_id": "activity-target",
                    "projection_id": "activity-target",
                    "x": 360,
                    "y": 176,
                    "width": 120,
                    "height": 72,
                    "label": "Next"
                }
            ],
            "edges": [
                {
                    "id": "aggregation",
                    "source": "source",
                    "target": "target",
                    "source_id": "aggregation",
                    "projection_id": "aggregation",
                    "points": [{ "x": 160, "y": 76 }, { "x": 360, "y": 76 }],
                    "label": ""
                },
                {
                    "id": "generalization",
                    "source": "source",
                    "target": "target",
                    "source_id": "generalization",
                    "projection_id": "generalization",
                    "points": [{ "x": 160, "y": 108 }, { "x": 360, "y": 108 }],
                    "label": ""
                },
                {
                    "id": "realization",
                    "source": "source",
                    "target": "target",
                    "source_id": "realization",
                    "projection_id": "realization",
                    "points": [{ "x": 160, "y": 140 }, { "x": 360, "y": 140 }],
                    "label": ""
                },
                {
                    "id": "dependency",
                    "source": "source",
                    "target": "target",
                    "source_id": "dependency",
                    "projection_id": "dependency",
                    "points": [{ "x": 160, "y": 172 }, { "x": 360, "y": 172 }],
                    "label": ""
                },
                {
                    "id": "control-flow",
                    "source": "activity-source",
                    "target": "activity-target",
                    "source_id": "control-flow",
                    "projection_id": "control-flow",
                    "points": [{ "x": 160, "y": 204 }, { "x": 360, "y": 204 }],
                    "label": ""
                },
                {
                    "id": "object-flow",
                    "source": "activity-source",
                    "target": "activity-target",
                    "source_id": "object-flow",
                    "projection_id": "object-flow",
                    "points": [{ "x": 160, "y": 236 }, { "x": 360, "y": 236 }],
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
                "target": { "type": "Class", "source_id": "target" },
                "activity-source": { "type": "Action", "source_id": "activity-source" },
                "activity-target": { "type": "Action", "source_id": "activity-target" }
            },
            "edges": {
                "aggregation": { "type": "Aggregation", "source_id": "aggregation" },
                "generalization": { "type": "Generalization", "source_id": "generalization" },
                "realization": { "type": "Realization", "source_id": "realization" },
                "dependency": { "type": "Dependency", "source_id": "dependency" },
                "control-flow": { "type": "ControlFlow", "source_id": "control-flow" },
                "object-flow": { "type": "ObjectFlow", "source_id": "object-flow" }
            }
        },
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap()
        ).unwrap()
    });

    let content = render_content(input);
    let artifact = write_render_artifact(&current_test_name(), &content);
    assert!(artifact.exists());
    let doc = svg_doc(&content);

    let aggregation = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "aggregation"),
        "path",
    );
    assert_eq!(
        aggregation.attribute("marker-start"),
        Some("url(#marker-start-aggregation)")
    );
    assert!(content.contains(r#"data-dediren-edge-marker-start="hollow_diamond""#));

    let generalization = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "generalization"),
        "path",
    );
    assert_eq!(
        generalization.attribute("marker-end"),
        Some("url(#marker-end-generalization)")
    );
    assert!(content.contains(r#"data-dediren-edge-marker-end="hollow_triangle""#));

    let realization = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "realization"),
        "path",
    );
    assert_eq!(
        realization.attribute("marker-end"),
        Some("url(#marker-end-realization)")
    );
    assert_eq!(realization.attribute("stroke-dasharray"), Some("8 5"));

    let dependency = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "dependency"),
        "path",
    );
    assert_eq!(
        dependency.attribute("marker-end"),
        Some("url(#marker-end-dependency)")
    );
    assert!(content.contains(r#"data-dediren-edge-marker-end="open_arrow""#));
    assert_eq!(dependency.attribute("stroke-dasharray"), Some("8 5"));

    let control_flow = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "control-flow"),
        "path",
    );
    assert_eq!(
        control_flow.attribute("marker-end"),
        Some("url(#marker-end-control-flow)")
    );
    assert!(content.contains(r#"data-dediren-edge-marker-end="filled_arrow""#));

    let object_flow = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "object-flow"),
        "path",
    );
    assert_eq!(
        object_flow.attribute("marker-end"),
        Some("url(#marker-end-object-flow)")
    );
    assert_eq!(object_flow.attribute("stroke-dasharray"), Some("8 5"));
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
