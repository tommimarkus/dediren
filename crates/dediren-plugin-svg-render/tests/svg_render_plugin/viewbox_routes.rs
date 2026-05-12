use super::common::*;

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
