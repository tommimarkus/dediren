use super::common::*;

fn uml_style_input() -> serde_json::Value {
    serde_json::json!({
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/uml-basic.json")).unwrap()
        ).unwrap(),
        "render_metadata": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-metadata/uml-basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap()
        ).unwrap()
    })
}

#[test]
fn svg_renderer_renders_uml_class_compartments() {
    let input = uml_style_input();
    let content = render_content(input);
    let artifact = write_render_artifact(&current_test_name(), &content);
    assert!(artifact.exists());
    let doc = svg_doc(&content);

    let order = semantic_group(&doc, "data-dediren-node-id", "class-order");
    let shape = child_node_shape(order);
    assert_eq!(
        shape.attribute("data-dediren-node-shape"),
        Some("uml_class")
    );
    child_group_with_attr(order, "data-dediren-node-decorator", "uml_class");
    assert!(
        content.contains("id : OrderId"),
        "expected UML class attributes in SVG"
    );
    assert!(
        content.contains("+ submit() : void"),
        "expected UML class operations in SVG"
    );
}

#[test]
fn svg_renderer_renders_uml_enumeration_literals() {
    let input = uml_style_input();
    let content = render_content(input);
    let artifact = write_render_artifact(&current_test_name(), &content);
    assert!(artifact.exists());
    let doc = svg_doc(&content);

    let enumeration = semantic_group(&doc, "data-dediren-node-id", "enum-order-status");
    let shape = child_node_shape(enumeration);
    assert_eq!(
        shape.attribute("data-dediren-node-shape"),
        Some("uml_enumeration")
    );
    child_group_with_attr(
        enumeration,
        "data-dediren-node-decorator",
        "uml_enumeration",
    );

    assert!(
        content.contains("«enumeration»"),
        "expected UML enumeration stereotype in SVG"
    );
    assert!(
        content.contains("Submitted"),
        "expected UML enumeration literals in SVG"
    );
}

#[test]
fn svg_renderer_covers_uml_structural_node_shapes() {
    let input = serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "uml-structural-node-test",
            "nodes": [
                {
                    "id": "package-orders",
                    "source_id": "package-orders",
                    "projection_id": "package-orders",
                    "x": 40,
                    "y": 40,
                    "width": 180,
                    "height": 96,
                    "label": "Orders"
                },
                {
                    "id": "interface-submittable",
                    "source_id": "interface-submittable",
                    "projection_id": "interface-submittable",
                    "x": 280,
                    "y": 40,
                    "width": 180,
                    "height": 104,
                    "label": "Submittable"
                },
                {
                    "id": "datatype-order-id",
                    "source_id": "datatype-order-id",
                    "projection_id": "datatype-order-id",
                    "x": 520,
                    "y": 40,
                    "width": 180,
                    "height": 104,
                    "label": "OrderId"
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
                "package-orders": { "type": "Package", "source_id": "package-orders" },
                "interface-submittable": { "type": "Interface", "source_id": "interface-submittable" },
                "datatype-order-id": { "type": "DataType", "source_id": "datatype-order-id" }
            },
            "edges": {}
        },
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap()
        ).unwrap()
    });

    let content = render_content(input);
    let artifact = write_render_artifact(&current_test_name(), &content);
    assert!(artifact.exists());
    let doc = svg_doc(&content);

    let package = semantic_group(&doc, "data-dediren-node-id", "package-orders");
    let package_shape = child_node_shape(package);
    assert_eq!(
        package_shape.attribute("data-dediren-node-shape"),
        Some("uml_package")
    );
    child_group_with_attr(package, "data-dediren-node-decorator", "uml_package");

    let interface = semantic_group(&doc, "data-dediren-node-id", "interface-submittable");
    let interface_shape = child_node_shape(interface);
    assert_eq!(
        interface_shape.attribute("data-dediren-node-shape"),
        Some("uml_interface")
    );
    child_group_with_attr(interface, "data-dediren-node-decorator", "uml_interface");
    assert!(
        content.contains("«interface»"),
        "expected UML interface stereotype in SVG"
    );

    let data_type = semantic_group(&doc, "data-dediren-node-id", "datatype-order-id");
    let data_type_shape = child_node_shape(data_type);
    assert_eq!(
        data_type_shape.attribute("data-dediren-node-shape"),
        Some("uml_data_type")
    );
    child_group_with_attr(data_type, "data-dediren-node-decorator", "uml_data_type");
    assert!(
        content.contains("«dataType»"),
        "expected UML data type stereotype in SVG"
    );
}
