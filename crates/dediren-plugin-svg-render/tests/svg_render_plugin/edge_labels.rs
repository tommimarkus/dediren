use super::common::*;

#[test]
fn svg_renderer_places_edge_label_near_route_midpoint_for_vertical_route() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "routed-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "routed-edge",
                "projection_id": "routed-edge",
                "points": [
                    { "x": 100, "y": 0 },
                    { "x": 100, "y": 100 },
                    { "x": 100, "y": 200 }
                ],
                "label": "routed label"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "routed-edge");
    let label = child_element(edge, "text");
    let label_box = text_box_from_svg(label, 14.0);
    let center_y = box_center_y(label_box);
    let route_gap = horizontal_gap_to_x(label_box, 100.0);
    assert!(
        route_gap <= 3.0,
        "vertical route label should stay close to the route, got gap={route_gap}"
    );
    assert!(
        (center_y - 100.0).abs() <= 6.0,
        "vertical route label should be visually centered near the route midpoint, got center_y={center_y}"
    );
    assert_eq!(label.attribute("text-anchor"), Some("end"));
}

#[test]
fn svg_renderer_aligns_vertical_edge_labels_by_text_edge() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "short-vertical",
                "source": "web-app",
                "target": "orders-api",
                "source_id": "short-vertical",
                "projection_id": "short-vertical",
                "points": [
                    { "x": 200, "y": 40 },
                    { "x": 200, "y": 220 }
                ],
                "label": "calls API"
            },
            {
                "id": "long-vertical",
                "source": "orders-api",
                "target": "worker",
                "source_id": "long-vertical",
                "projection_id": "long-vertical",
                "points": [
                    { "x": 200, "y": 260 },
                    { "x": 200, "y": 440 }
                ],
                "label": "publishes fulfillment"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let short = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "short-vertical"),
        "text",
    );
    let long = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "long-vertical"),
        "text",
    );

    assert_eq!(short.attribute("text-anchor"), Some("end"));
    assert_eq!(long.attribute("text-anchor"), Some("end"));
    assert_eq!(short.attribute("x"), long.attribute("x"));
}

#[test]
fn svg_renderer_prefers_horizontal_segment_for_edge_label() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "mostly-vertical-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "mostly-vertical-edge",
                "projection_id": "mostly-vertical-edge",
                "points": [
                    { "x": 0, "y": 0 },
                    { "x": 0, "y": 300 },
                    { "x": 100, "y": 300 },
                    { "x": 100, "y": 500 }
                ],
                "label": "horizontal label"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "mostly-vertical-edge");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();
    assert!(
        (y - 300.0).abs() <= 18.0,
        "edge label should stay near the preferred horizontal segment, got y={y}"
    );
}

#[test]
fn svg_renderer_defaults_horizontal_edge_label_near_start() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "horizontal-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "horizontal-edge",
                "projection_id": "horizontal-edge",
                "points": [
                    { "x": 0, "y": 120 },
                    { "x": 100, "y": 120 }
                ],
                "label": "start"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "horizontal-edge");
    let label = child_element(edge, "text");
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();

    assert!(
        (x - 18.0).abs() <= 1.0,
        "default horizontal edge label should be near the segment start, got x={x}"
    );
}

#[test]
fn svg_renderer_defaults_horizontal_edge_label_below_downward_bend() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "downward-bend",
                "source": "source-node",
                "target": "target-node",
                "source_id": "downward-bend",
                "projection_id": "downward-bend",
                "points": [
                    { "x": 0, "y": 120 },
                    { "x": 120, "y": 120 },
                    { "x": 120, "y": 220 }
                ],
                "label": "down"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "downward-bend");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        y > 120.0,
        "default horizontal edge label should move below a downward bend, got y={y}"
    );
}

#[test]
fn svg_renderer_defaults_horizontal_edge_label_near_first_horizontal_segment() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "two-segment-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "two-segment-edge",
                "projection_id": "two-segment-edge",
                "points": [
                    { "x": 100, "y": 120 },
                    { "x": 220, "y": 120 },
                    { "x": 220, "y": 180 },
                    { "x": 340, "y": 180 }
                ],
                "label": "first segment"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "two-segment-edge");
    let label = child_element(edge, "text");
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        x < 220.0,
        "default horizontal edge label should prefer the first horizontal segment, got x={x}"
    );
    assert!(
        y > 120.0,
        "default horizontal edge label should use the first segment bend side, got y={y}"
    );
}

#[test]
fn svg_renderer_defaults_horizontal_edge_label_above_upward_bend() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "upward-bend",
                "source": "source-node",
                "target": "target-node",
                "source_id": "upward-bend",
                "projection_id": "upward-bend",
                "points": [
                    { "x": 0, "y": 220 },
                    { "x": 120, "y": 220 },
                    { "x": 120, "y": 120 }
                ],
                "label": "up"
            }
        ]),
        serde_json::json!({}),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "upward-bend");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        y < 220.0,
        "default horizontal edge label should stay above an upward bend, got y={y}"
    );
}

#[test]
fn svg_renderer_allows_horizontal_edge_label_side_override_by_policy() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "downward-bend",
                "source": "source-node",
                "target": "target-node",
                "source_id": "downward-bend",
                "projection_id": "downward-bend",
                "points": [
                    { "x": 0, "y": 120 },
                    { "x": 120, "y": 120 },
                    { "x": 120, "y": 220 }
                ],
                "label": "forced above"
            }
        ]),
        serde_json::json!({
            "edge": {
                "label_horizontal_side": "above"
            }
        }),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "downward-bend");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        y < 120.0,
        "configured horizontal edge label side should override auto routing, got y={y}"
    );
}

#[test]
fn svg_renderer_allows_centered_horizontal_edge_labels_by_policy() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "horizontal-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "horizontal-edge",
                "projection_id": "horizontal-edge",
                "points": [
                    { "x": 0, "y": 120 },
                    { "x": 100, "y": 120 }
                ],
                "label": "center label"
            }
        ]),
        serde_json::json!({
            "edge": {
                "label_horizontal_position": "center"
            }
        }),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "horizontal-edge");
    let label = child_element(edge, "text");
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();

    assert!(
        (x - 50.0).abs() <= 1.0,
        "configured centered horizontal label should use segment midpoint, got x={x}"
    );
}

#[test]
fn svg_renderer_paints_edge_label_with_background_halo() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "labeled-edge",
                "source": "source-node",
                "target": "target-node",
                "source_id": "labeled-edge",
                "projection_id": "labeled-edge",
                "points": [
                    { "x": 0, "y": 100 },
                    { "x": 200, "y": 100 }
                ],
                "label": "clear label"
            }
        ]),
        serde_json::json!({
            "background": { "fill": "#f8fafc" }
        }),
    );
    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "labeled-edge");
    let path = child_element(edge, "path");
    let label = child_element(edge, "text");
    assert_eq!(
        path.attribute("marker-end"),
        Some("url(#marker-end-labeled-edge)")
    );
    assert_eq!(label.attribute("paint-order"), Some("stroke"));
    assert_eq!(label.attribute("stroke"), Some("#f8fafc"));
    assert_eq!(label.attribute("stroke-width"), Some("4"));
}

#[test]
fn svg_renderer_moves_edge_label_away_from_node_boxes() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "left-node",
                "source_id": "left-node",
                "projection_id": "left-node",
                "x": 0,
                "y": 32,
                "width": 160,
                "height": 80,
                "label": "Left"
            },
            {
                "id": "right-node",
                "source_id": "right-node",
                "projection_id": "right-node",
                "x": 200,
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
                    { "x": 160, "y": 72 },
                    { "x": 200, "y": 72 }
                ],
                "label": "label wider than gap"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);

    let edge = semantic_group(&doc, "data-dediren-edge-id", "left-to-right");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();
    assert!(
        !(32.0..=112.0).contains(&y),
        "edge label should move outside node boxes, got y={y}"
    );
}

#[test]
fn svg_renderer_centers_horizontal_edge_label_when_near_start_overlaps_adjacent_nodes() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "client",
                "source_id": "client",
                "projection_id": "client",
                "x": 40,
                "y": 160,
                "width": 160,
                "height": 80,
                "label": "Client"
            },
            {
                "id": "web-app",
                "source_id": "web-app",
                "projection_id": "web-app",
                "x": 310,
                "y": 160,
                "width": 170,
                "height": 80,
                "label": "Web App"
            }
        ]),
        serde_json::json!([
            {
                "id": "client-submits-order",
                "source": "client",
                "target": "web-app",
                "source_id": "client-submits-order",
                "projection_id": "client-submits-order",
                "points": [
                    { "x": 200, "y": 200 },
                    { "x": 310, "y": 200 }
                ],
                "label": "submits order"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let edge = semantic_group(&doc, "data-dediren-edge-id", "client-submits-order");
    let label = child_element(edge, "text");
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        (x - 255.0).abs() <= 2.0,
        "label should use the available gap center when near-start overlaps nodes, got x={x}"
    );
    assert!(
        (y - 200.0).abs() <= 18.0,
        "label should stay close to the short horizontal edge, got y={y}"
    );
}

#[test]
fn svg_renderer_moves_edge_label_away_from_route_segments() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "vertical-edge",
                "source": "orders-api",
                "target": "worker",
                "source_id": "vertical-edge",
                "projection_id": "vertical-edge",
                "points": [
                    { "x": 220, "y": 80 },
                    { "x": 220, "y": 260 }
                ],
                "label": "publishes fulfillment"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let edge = semantic_group(&doc, "data-dediren-edge-id", "vertical-edge");
    let label = child_element(edge, "text");
    let label_box = text_box_from_svg(label, 14.0);

    assert!(
        !box_contains_point(
            label_box,
            220.0,
            label.attribute("y").unwrap().parse::<f64>().unwrap()
        ),
        "label box should not sit on top of its vertical route segment"
    );
}

#[test]
fn svg_renderer_keeps_horizontal_edge_label_close_to_route() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "horizontal-edge",
                "source": "orders-api",
                "target": "payments",
                "source_id": "horizontal-edge",
                "projection_id": "horizontal-edge",
                "points": [
                    { "x": 100, "y": 160 },
                    { "x": 320, "y": 160 }
                ],
                "label": "authorizes payment"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let edge = semantic_group(&doc, "data-dediren-edge-id", "horizontal-edge");
    let label = child_element(edge, "text");
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        (y - 160.0).abs() <= 18.0,
        "horizontal edge label should stay close to its route, got y={y}"
    );
}

#[test]
fn svg_renderer_separates_labels_for_parallel_horizontal_edges() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "upper-edge",
                "source": "orders-api",
                "target": "payments",
                "source_id": "upper-edge",
                "projection_id": "upper-edge",
                "points": [
                    { "x": 100, "y": 160 },
                    { "x": 320, "y": 160 }
                ],
                "label": "writes orders"
            },
            {
                "id": "lower-edge",
                "source": "orders-api",
                "target": "database",
                "source_id": "lower-edge",
                "projection_id": "lower-edge",
                "points": [
                    { "x": 100, "y": 172 },
                    { "x": 320, "y": 172 }
                ],
                "label": "authorizes payment"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let upper = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "upper-edge"),
        "text",
    );
    let lower = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "lower-edge"),
        "text",
    );

    assert!(
        !boxes_overlap(
            text_box_from_svg(upper, 14.0),
            text_box_from_svg(lower, 14.0)
        ),
        "parallel edge labels should not overlap"
    );
}

#[test]
fn svg_renderer_separates_labels_for_adjacent_multisegment_routes() {
    let input = styled_inline_input(
        serde_json::json!([]),
        serde_json::json!([]),
        serde_json::json!([
            {
                "id": "writes-orders",
                "source": "orders-api",
                "target": "database",
                "source_id": "writes-orders",
                "projection_id": "writes-orders",
                "points": [
                    { "x": 596, "y": 384 },
                    { "x": 740, "y": 384 },
                    { "x": 740, "y": 438 },
                    { "x": 884, "y": 438 }
                ],
                "label": "writes orders"
            },
            {
                "id": "authorizes-payment",
                "source": "orders-api",
                "target": "payments",
                "source_id": "authorizes-payment",
                "projection_id": "authorizes-payment",
                "points": [
                    { "x": 596, "y": 396 },
                    { "x": 740, "y": 396 },
                    { "x": 740, "y": 346 },
                    { "x": 884, "y": 346 }
                ],
                "label": "authorizes payment"
            }
        ]),
        serde_json::json!({}),
    );

    let content = render_content(input);
    let doc = svg_doc(&content);
    let writes = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "writes-orders"),
        "text",
    );
    let authorizes = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "authorizes-payment"),
        "text",
    );
    let authorizes_path = child_element(
        semantic_group(&doc, "data-dediren-edge-id", "authorizes-payment"),
        "path",
    );
    let authorizes_data = authorizes_path.attribute("d").unwrap();
    let authorizes_y = authorizes.attribute("y").unwrap().parse::<f64>().unwrap();

    assert!(
        !boxes_overlap(
            text_box_from_svg(writes, 14.0),
            text_box_from_svg(authorizes, 14.0)
        ),
        "labels for adjacent multi-segment routes should not overlap"
    );
    assert!(
        (authorizes_y - 396.0)
            .abs()
            .min((authorizes_y - 346.0).abs())
            <= 32.0,
        "fallback label should stay close to the crowded edge, got y={authorizes_y}"
    );
    assert!(
        !box_intersects_horizontal_segment(
            text_box_from_svg(authorizes, 14.0),
            740.0,
            884.0,
            422.0
        ),
        "fallback label should not sit on another edge route"
    );
    assert!(
        authorizes_data.contains(" Q "),
        "adjacent multi-segment routes should draw a line jump where they share a route segment"
    );
    assert!(
        path_data_contains_point(authorizes_data, 746.0, 396.0)
            && path_data_contains_point(authorizes_data, 746.0, 384.0),
        "adjacent multi-segment routes should run on a parallel offset while their original route overlaps"
    );
    assert!(
        !path_data_contains_point(authorizes_data, 740.0, 396.0),
        "adjacent multi-segment route detour should not draw through the overlap entry; got {authorizes_data}"
    );
    assert!(
        !path_data_contains_point(authorizes_data, 740.0, 384.0),
        "adjacent multi-segment route detour should not rejoin at the overlap exit; got {authorizes_data}"
    );
}
