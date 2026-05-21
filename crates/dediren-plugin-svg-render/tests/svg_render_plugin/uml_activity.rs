use super::common::*;

#[test]
fn svg_renderer_renders_uml_activity_node_shapes() {
    let policy = serde_json::from_str::<serde_json::Value>(
        &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap(),
    )
    .unwrap();
    let input = serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "uml-activity-test",
            "nodes": [
                {
                    "id": "activity-submit",
                    "source_id": "activity-submit",
                    "projection_id": "activity-submit",
                    "x": 16,
                    "y": 24,
                    "width": 180,
                    "height": 84,
                    "label": "Submit Order"
                },
                {
                    "id": "initial-submit",
                    "source_id": "initial-submit",
                    "projection_id": "initial-submit",
                    "x": 40,
                    "y": 160,
                    "width": 28,
                    "height": 28,
                    "label": ""
                },
                {
                    "id": "action-submit",
                    "source_id": "action-submit",
                    "projection_id": "action-submit",
                    "x": 120,
                    "y": 144,
                    "width": 128,
                    "height": 60,
                    "label": "Submit"
                },
                {
                    "id": "decision-valid",
                    "source_id": "decision-valid",
                    "projection_id": "decision-valid",
                    "x": 300,
                    "y": 144,
                    "width": 72,
                    "height": 72,
                    "label": "Valid?"
                },
                {
                    "id": "merge-valid",
                    "source_id": "merge-valid",
                    "projection_id": "merge-valid",
                    "x": 420,
                    "y": 144,
                    "width": 72,
                    "height": 72,
                    "label": ""
                },
                {
                    "id": "fork-submit",
                    "source_id": "fork-submit",
                    "projection_id": "fork-submit",
                    "x": 528,
                    "y": 176,
                    "width": 120,
                    "height": 14,
                    "label": ""
                },
                {
                    "id": "join-submit",
                    "source_id": "join-submit",
                    "projection_id": "join-submit",
                    "x": 692,
                    "y": 132,
                    "width": 14,
                    "height": 96,
                    "label": ""
                },
                {
                    "id": "object-order",
                    "source_id": "object-order",
                    "projection_id": "object-order",
                    "x": 744,
                    "y": 144,
                    "width": 128,
                    "height": 60,
                    "label": "Order"
                },
                {
                    "id": "final-submit",
                    "source_id": "final-submit",
                    "projection_id": "final-submit",
                    "x": 920,
                    "y": 160,
                    "width": 32,
                    "height": 32,
                    "label": ""
                }
            ],
            "edges": [],
            "groups": [],
            "warnings": []
        },
        "render_metadata": {
            "render_metadata_schema_version": "render-metadata.schema.v1",
            "semantic_profile": "uml",
            "nodes": {
                "activity-submit": { "type": "Activity", "source_id": "activity-submit" },
                "initial-submit": { "type": "InitialNode", "source_id": "initial-submit" },
                "action-submit": { "type": "Action", "source_id": "action-submit" },
                "decision-valid": { "type": "DecisionNode", "source_id": "decision-valid" },
                "merge-valid": { "type": "MergeNode", "source_id": "merge-valid" },
                "fork-submit": { "type": "ForkNode", "source_id": "fork-submit" },
                "join-submit": { "type": "JoinNode", "source_id": "join-submit" },
                "object-order": { "type": "ObjectNode", "source_id": "object-order" },
                "final-submit": { "type": "ActivityFinalNode", "source_id": "final-submit" }
            },
            "edges": {}
        },
        "policy": policy
    });

    let content = render_content(input);
    let artifact = write_render_artifact(&current_test_name(), &content);
    assert!(artifact.exists());
    let doc = svg_doc(&content);
    for (node_id, decorator) in [
        ("activity-submit", "uml_activity"),
        ("initial-submit", "uml_initial_node"),
        ("action-submit", "uml_action"),
        ("decision-valid", "uml_decision_node"),
        ("merge-valid", "uml_merge_node"),
        ("fork-submit", "uml_fork_node"),
        ("join-submit", "uml_join_node"),
        ("object-order", "uml_object_node"),
        ("final-submit", "uml_activity_final_node"),
    ] {
        let node = semantic_group(&doc, "data-dediren-node-id", node_id);
        let shape = child_node_shape(node);
        assert_eq!(
            shape.attribute("data-dediren-node-shape"),
            Some(decorator),
            "expected {node_id} to render {decorator} shape"
        );
        child_group_with_attr(node, "data-dediren-node-decorator", decorator);
    }
}

#[test]
fn svg_renderer_places_compact_control_node_labels_outside_symbols() {
    let policy = serde_json::from_str::<serde_json::Value>(
        &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap(),
    )
    .unwrap();
    let input = serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "uml-control-label-test",
            "nodes": [
                {
                    "id": "start",
                    "source_id": "start",
                    "projection_id": "start",
                    "x": 20,
                    "y": 100,
                    "width": 28,
                    "height": 28,
                    "label": "Start"
                },
                {
                    "id": "check-cache",
                    "source_id": "check-cache",
                    "projection_id": "check-cache",
                    "x": 100,
                    "y": 100,
                    "width": 32,
                    "height": 32,
                    "label": "Cached?"
                },
                {
                    "id": "merge-cache",
                    "source_id": "merge-cache",
                    "projection_id": "merge-cache",
                    "x": 180,
                    "y": 100,
                    "width": 32,
                    "height": 32,
                    "label": "Merge"
                },
                {
                    "id": "done",
                    "source_id": "done",
                    "projection_id": "done",
                    "x": 260,
                    "y": 100,
                    "width": 32,
                    "height": 32,
                    "label": "Done"
                }
            ],
            "edges": [],
            "groups": [],
            "warnings": []
        },
        "render_metadata": {
            "render_metadata_schema_version": "render-metadata.schema.v1",
            "semantic_profile": "uml",
            "nodes": {
                "start": { "type": "InitialNode", "source_id": "start" },
                "check-cache": { "type": "DecisionNode", "source_id": "check-cache" },
                "merge-cache": { "type": "MergeNode", "source_id": "merge-cache" },
                "done": { "type": "ActivityFinalNode", "source_id": "done" }
            },
            "edges": {}
        },
        "policy": policy
    });

    let content = render_content(input);
    let artifact = write_render_artifact(&current_test_name(), &content);
    assert!(artifact.exists());
    let doc = svg_doc(&content);
    for (node_id, expected_shape, label_text, node_x, node_y, node_width, node_height) in [
        (
            "start",
            "uml_initial_node",
            "Start",
            20.0,
            100.0,
            28.0,
            28.0,
        ),
        (
            "check-cache",
            "uml_decision_node",
            "Cached?",
            100.0,
            100.0,
            32.0,
            32.0,
        ),
        (
            "merge-cache",
            "uml_merge_node",
            "Merge",
            180.0,
            100.0,
            32.0,
            32.0,
        ),
        (
            "done",
            "uml_activity_final_node",
            "Done",
            260.0,
            100.0,
            32.0,
            32.0,
        ),
    ] {
        let node = semantic_group(&doc, "data-dediren-node-id", node_id);
        let shape = child_node_shape(node);
        assert_eq!(
            shape.attribute("data-dediren-node-shape"),
            Some(expected_shape),
            "{node_id} shape"
        );
        let label = child_element(node, "text");
        assert_eq!(label.text(), Some(label_text), "{node_id} label");
        assert_eq!(
            label.attribute("text-anchor"),
            Some("middle"),
            "{node_id} anchor"
        );
        assert_ne!(
            label.attribute("dominant-baseline"),
            Some("middle"),
            "{node_id} baseline"
        );
        let label_x = label.attribute("x").unwrap().parse::<f64>().unwrap();
        let label_y = label.attribute("y").unwrap().parse::<f64>().unwrap();
        let font_size = label
            .attribute("font-size")
            .unwrap()
            .parse::<f64>()
            .unwrap();
        let label_center_y = label_y - font_size * 0.3;
        let node_center_x = node_x + node_width / 2.0;
        let node_center_y = node_y + node_height / 2.0;
        let left_delta = node_center_x - label_x;
        let up_delta = node_center_y - label_center_y;
        assert!(
            left_delta > 0.0 && up_delta > 0.0,
            "{node_id} label center should sit up-left of its compact symbol center"
        );
        assert!(
            (left_delta - up_delta).abs() <= 1.0,
            "{node_id} label center should sit on the 45-degree up-left diagonal from the symbol center, got left_delta={left_delta} and up_delta={up_delta}"
        );
        assert!(
            left_delta <= node_width.max(node_height) / 2.0 + 10.0,
            "{node_id} label center should stay close to its compact symbol, got diagonal delta={left_delta}"
        );
    }
}
