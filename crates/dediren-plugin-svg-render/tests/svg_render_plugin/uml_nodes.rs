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
fn svg_renderer_covers_each_uml_node_type() {
    let policy = serde_json::from_str::<serde_json::Value>(
        &std::fs::read_to_string(workspace_file("fixtures/render-policy/uml-svg.json")).unwrap(),
    )
    .unwrap();
    let node_styles = policy["style"]["node_type_overrides"]
        .as_object()
        .expect("node type overrides should be an object");
    let node_styles = node_styles.clone();
    let mut nodes = Vec::new();
    let mut metadata_nodes = serde_json::Map::new();
    for (index, node_type) in UML_NODE_TYPES.iter().enumerate() {
        let row = index / 4;
        let column = index % 4;
        let id = format!("uml-node-{index}");
        let (width, height) = uml_node_coverage_size(node_type);
        nodes.push(serde_json::json!({
            "id": id,
            "source_id": id,
            "projection_id": id,
            "x": 40 + column as i64 * 220,
            "y": 40 + row as i64 * 140,
            "width": width,
            "height": height,
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

    let input = serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "uml-node-type-coverage-test",
            "nodes": nodes,
            "edges": [],
            "groups": [],
            "warnings": []
        },
        "render_metadata": {
            "render_metadata_schema_version": "render-metadata.schema.v1",
            "semantic_profile": "uml",
            "nodes": serde_json::Value::Object(metadata_nodes),
            "edges": {}
        },
        "policy": policy
    });

    let content = render_content(input);
    let artifact = write_render_artifact(&current_test_name(), &content);
    assert!(artifact.exists());
    let doc = svg_doc(&content);

    assert_eq!(
        node_styles.len(),
        UML_NODE_TYPES.len(),
        "UML policy should style every covered node type"
    );
    for (index, node_type) in UML_NODE_TYPES.iter().enumerate() {
        let id = format!("uml-node-{index}");
        let expected_style = node_styles
            .get(*node_type)
            .unwrap_or_else(|| panic!("missing policy style for {node_type}"));
        let expected_decorator = expected_style["decorator"]
            .as_str()
            .unwrap_or_else(|| panic!("missing policy decorator for {node_type}"));
        let node = semantic_group(&doc, "data-dediren-node-id", &id);
        let shape = child_node_shape(node);
        assert_eq!(
            shape.attribute("data-dediren-node-shape"),
            Some(expected_decorator),
            "{node_type} should use its UML node shape"
        );
        assert_uml_node_uses_policy_style(node_type, shape, expected_style);
        child_group_with_attr(node, "data-dediren-node-decorator", expected_decorator);
        if uml_compact_control_node_type(node_type) {
            let row = index / 4;
            let column = index % 4;
            let node_x = 40.0 + column as f64 * 220.0;
            let node_y = 40.0 + row as f64 * 140.0;
            let label = child_element(node, "text");
            let label_bounds = text_label_bounds(label);
            let node_center_x = node_x + 32.0;
            let node_center_y = node_y + 32.0;
            assert!(
                label_bounds.2 < node_center_x && label_bounds.3 < node_center_y,
                "{node_type} label should stay in the up-left quadrant outside the compact control symbol center"
            );
            let label_center_x = (label_bounds.0 + label_bounds.2) / 2.0;
            let label_center_y = (label_bounds.1 + label_bounds.3) / 2.0;
            let left_delta = node_center_x - label_center_x;
            let up_delta = node_center_y - label_center_y;
            assert!(
                left_delta > 0.0 && up_delta > 0.0,
                "{node_type} label center should sit up-left of the compact control symbol center"
            );
            assert!(
                (left_delta - up_delta).abs() <= 1.0,
                "{node_type} label center should sit on the 45-degree up-left diagonal from the symbol center, got left_delta={left_delta} and up_delta={up_delta}"
            );
            assert!(
                left_delta <= 40.5,
                "{node_type} label center should stay close to the compact control symbol, got diagonal delta={left_delta}"
            );
        }
    }
    assert!(
        content.contains("«interface»"),
        "expected UML interface stereotype in node coverage SVG"
    );
    assert!(
        content.contains("«dataType»"),
        "expected UML data type stereotype in node coverage SVG"
    );
    assert!(
        content.contains("«enumeration»"),
        "expected UML enumeration stereotype in node coverage SVG"
    );
}

fn uml_node_coverage_size(node_type: &str) -> (i64, i64) {
    match node_type {
        "InitialNode" | "ActivityFinalNode" | "DecisionNode" | "MergeNode" => (64, 64),
        "ForkNode" | "JoinNode" => (128, 44),
        "Package" => (180, 96),
        "Class" | "Interface" | "DataType" | "Enumeration" => (180, 108),
        _ => (180, 72),
    }
}

fn uml_compact_control_node_type(node_type: &str) -> bool {
    matches!(
        node_type,
        "InitialNode" | "ActivityFinalNode" | "DecisionNode" | "MergeNode"
    )
}

fn text_label_bounds(label: roxmltree::Node<'_, '_>) -> (f64, f64, f64, f64) {
    let font_size = label
        .attribute("font-size")
        .unwrap()
        .parse::<f64>()
        .unwrap();
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();
    let mut baseline_y = label.attribute("y").unwrap().parse::<f64>().unwrap();
    let mut min_x = f64::INFINITY;
    let mut max_x = f64::NEG_INFINITY;
    let mut min_y = f64::INFINITY;
    let mut max_y = f64::NEG_INFINITY;
    let anchor = label.attribute("text-anchor").unwrap_or("middle");
    let tspans = child_elements(label, "tspan").collect::<Vec<_>>();
    if tspans.is_empty() {
        return text_line_bounds(x, baseline_y, label.text().unwrap_or(""), font_size, anchor);
    }
    for tspan in tspans {
        let dy = tspan.attribute("dy").unwrap_or("0").parse::<f64>().unwrap();
        baseline_y += dy;
        let line_bounds =
            text_line_bounds(x, baseline_y, tspan.text().unwrap_or(""), font_size, anchor);
        min_x = min_x.min(line_bounds.0);
        min_y = min_y.min(line_bounds.1);
        max_x = max_x.max(line_bounds.2);
        max_y = max_y.max(line_bounds.3);
    }
    (min_x, min_y, max_x, max_y)
}

fn text_line_bounds(
    x: f64,
    baseline_y: f64,
    text: &str,
    font_size: f64,
    anchor: &str,
) -> (f64, f64, f64, f64) {
    let width = estimated_svg_text_width(text, font_size);
    let (min_x, max_x) = match anchor {
        "start" => (x, x + width),
        "end" => (x - width, x),
        _ => (x - width / 2.0, x + width / 2.0),
    };
    (
        min_x,
        baseline_y - font_size,
        max_x,
        baseline_y + font_size * 0.4,
    )
}

fn assert_uml_node_uses_policy_style(
    node_type: &str,
    shape: roxmltree::Node<'_, '_>,
    expected_style: &serde_json::Value,
) {
    let expected_fill = expected_style["fill"].as_str();
    let expected_stroke = expected_style["stroke"].as_str();
    if node_type == "ActivityFinalNode" {
        let circles = child_elements(shape, "circle").collect::<Vec<_>>();
        assert_eq!(circles.len(), 2, "{node_type} should render as a bullseye");
        assert_eq!(
            circles[0].attribute("stroke"),
            expected_stroke,
            "{node_type} outer ring should use its policy stroke"
        );
        assert_eq!(
            circles[1].attribute("fill"),
            expected_stroke,
            "{node_type} inner dot should use its policy stroke"
        );
    } else {
        assert_eq!(
            shape.attribute("fill"),
            expected_fill,
            "{node_type} should use its policy fill"
        );
        assert_eq!(
            shape.attribute("stroke"),
            expected_stroke,
            "{node_type} should use its policy stroke"
        );
    }
}
