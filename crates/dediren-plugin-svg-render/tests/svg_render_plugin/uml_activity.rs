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
