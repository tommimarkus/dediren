use super::common::{child_group_with_attr, render_ok_data, semantic_group, svg_doc};

#[test]
fn archimate_grouping_metadata_renders_group_decorator() {
    let layout_result = serde_json::json!({
        "layout_result_schema_version": "layout-result.schema.v1",
        "view_id": "main",
        "nodes": [],
        "edges": [],
        "groups": [
            {
                "id": "customer-domain",
                "source_id": "customer-domain",
                "projection_id": "customer-domain",
                "provenance": { "semantic_backed": { "source_id": "customer-domain" } },
                "x": 20.0,
                "y": 20.0,
                "width": 240.0,
                "height": 140.0,
                "members": [],
                "label": "Customer Domain"
            }
        ],
        "warnings": []
    });
    let metadata = serde_json::json!({
        "render_metadata_schema_version": "render-metadata.schema.v1",
        "semantic_profile": "archimate",
        "nodes": {},
        "edges": {},
        "groups": {
            "customer-domain": {
                "type": "Grouping",
                "source_id": "customer-domain"
            }
        }
    });
    let policy = serde_json::json!({
        "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
        "semantic_profile": "archimate",
        "page": { "width": 400, "height": 240 },
        "margin": { "top": 24, "right": 24, "bottom": 24, "left": 24 },
        "style": {
            "group_type_overrides": {
                "Grouping": {
                    "decorator": "archimate_grouping",
                    "fill": "#fef9c3",
                    "stroke": "#a16207"
                }
            }
        }
    });

    let data = render_ok_data(serde_json::json!({
        "layout_result": layout_result,
        "render_metadata": metadata,
        "policy": policy
    }));
    let svg = data["content"].as_str().unwrap();
    let doc = svg_doc(svg);
    let group = semantic_group(&doc, "data-dediren-group-id", "customer-domain");
    assert_eq!(group.attribute("data-dediren-group-type"), Some("Grouping"));
    assert_eq!(
        group.attribute("data-dediren-group-source-id"),
        Some("customer-domain")
    );
    let _decorator =
        child_group_with_attr(group, "data-dediren-group-decorator", "archimate_grouping");
}
