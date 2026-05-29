mod common;

use assert_fs::prelude::*;
use common::{
    assert_reasonable_svg_aspect, assert_svg_texts_include, child_element, child_group_with_attr,
    ok_data, plugin_binary, semantic_group, svg_doc, workspace_file, write_render_artifact,
};
use serde_json::Value;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

const ROUTE_CLOSE_PARALLEL_DISTANCE: f64 = 20.0;
const ROUTE_CLOSE_PARALLEL_MIN_OVERLAP: f64 = 40.0;

#[test]
fn rust_elk_renders_basic_projected_graph() {
    let temp = assert_fs::TempDir::new().unwrap();

    let request_output = project_layout_request("fixtures/source/valid-basic.json");
    let request = write_temp_bytes(&temp, "basic-layout-request.json", &request_output);

    let layout_output = rust_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "basic-layout-result.json", &layout_output);
    assert_layout_quality_ok(&validate_layout(&layout));

    let layout_data = ok_data(&layout_output);
    assert_eq!(
        layout_data["nodes"]
            .as_array()
            .expect("laid out nodes should be an array")
            .len(),
        2
    );
    assert_eq!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .len(),
        1
    );

    let svg = render_svg(&layout, "fixtures/render-policy/default-svg.json", None);
    let doc = svg_doc(&svg);
    assert_svg_texts_include(&doc, &["Client", "API", "calls"]);
    assert_reasonable_svg_aspect(&svg, 4.5);
    write_render_artifact("rust-elk", "rust_elk_renders_basic_projected_graph", &svg);
}

#[test]
fn close_parallel_route_details_mark_edge_pairs() {
    let layout_data = serde_json::json!({
        "edges": [
            {
                "id": "left-edge",
                "source": "left-source",
                "target": "left-target",
                "points": [
                    { "x": 0.0, "y": 10.0 },
                    { "x": 100.0, "y": 10.0 }
                ]
            },
            {
                "id": "right-edge",
                "source": "right-source",
                "target": "right-target",
                "points": [
                    { "x": 40.0, "y": 20.0 },
                    { "x": 140.0, "y": 20.0 }
                ]
            }
        ]
    });

    let details = close_parallel_route_details(&layout_data);

    assert!(details.contains("left-edge <-> right-edge"));
    assert!(details.contains("horizontal"));
    assert!(details.contains("distance=10.0"));
    assert!(details.contains("overlap=60.0"));
}

#[test]
fn close_parallel_route_overlay_marks_graph_segments() {
    let svg = r#"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 80"><g><rect x="0" y="0" width="20" height="20"/></g></svg>"#;
    let layout_data = serde_json::json!({
        "edges": [
            {
                "id": "left-edge",
                "source": "left-source",
                "target": "left-target",
                "points": [
                    { "x": 0.0, "y": 10.0 },
                    { "x": 100.0, "y": 10.0 }
                ]
            },
            {
                "id": "right-edge",
                "source": "right-source",
                "target": "right-target",
                "points": [
                    { "x": 40.0, "y": 20.0 },
                    { "x": 140.0, "y": 20.0 }
                ]
            }
        ]
    });

    let annotated = annotate_close_parallel_routes(svg, &layout_data);

    assert!(annotated.contains("data-dediren-route-quality-overlay"));
    assert!(annotated.contains("data-dediren-route-quality-pair=\"left-edge--right-edge\""));
    assert!(annotated.contains("left-edge &lt;-&gt; right-edge"));
    assert!(annotated.contains("x1=\"0.0\" y1=\"10.0\" x2=\"100.0\" y2=\"10.0\""));
    assert!(annotated.contains("x1=\"40.0\" y1=\"20.0\" x2=\"140.0\" y2=\"20.0\""));
}

#[test]
fn close_parallel_route_details_ignore_same_edge_segments() {
    let layout_data = serde_json::json!({
        "edges": [
            {
                "id": "looping-edge",
                "source": "source",
                "target": "target",
                "points": [
                    { "x": 0.0, "y": 10.0 },
                    { "x": 100.0, "y": 10.0 },
                    { "x": 100.0, "y": 20.0 },
                    { "x": 0.0, "y": 20.0 }
                ]
            }
        ]
    });

    assert_eq!(close_parallel_route_details(&layout_data), "");
    assert_eq!(
        annotate_close_parallel_routes("<svg></svg>", &layout_data),
        "<svg></svg>"
    );
}

#[test]
fn rust_elk_renders_grouped_rich_graph_with_bounded_route_channel_limitations() {
    let temp = assert_fs::TempDir::new().unwrap();

    let request_output = project_layout_request("fixtures/source/valid-pipeline-rich.json");
    let request_data = ok_data(&request_output);
    assert_eq!(
        request_data["nodes"]
            .as_array()
            .expect("projected nodes should be an array")
            .len(),
        6
    );
    assert_eq!(
        request_data["groups"]
            .as_array()
            .expect("projected groups should be an array")
            .len(),
        2
    );
    let request = write_temp_bytes(&temp, "grouped-rich-layout-request.json", &request_output);

    let layout_output = rust_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "grouped-rich-layout-result.json", &layout_output);

    let layout_data = ok_data(&layout_output);
    assert_layout_quality_matches_known_elkrs_route_channel_limit(
        &validate_layout(&layout),
        &layout_data,
        12,
    );
    assert_eq!(
        layout_data["nodes"]
            .as_array()
            .expect("laid out nodes should be an array")
            .len(),
        6
    );
    assert_eq!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .len(),
        6
    );
    assert_eq!(
        layout_data["groups"]
            .as_array()
            .expect("laid out groups should be an array")
            .len(),
        2
    );

    let svg = render_svg(&layout, "fixtures/render-policy/rich-svg.json", None);
    let doc = svg_doc(&svg);
    assert_svg_texts_include(
        &doc,
        &[
            "Client",
            "Web App",
            "Orders API",
            "PostgreSQL",
            "Application Services",
            "submits order",
            "requests payment authorization",
        ],
    );
    assert!(svg.contains("data-dediren-group-id=\"application-services\""));
    assert!(svg.contains("data-dediren-group-id=\"external-dependencies\""));
    assert!(svg.contains("data-dediren-node-id=\"worker\""));
    assert!(svg.contains("data-dediren-node-id=\"payments\""));
    assert!(svg.contains(">Fulfillment</tspan>"));
    assert!(svg.contains(">Worker</tspan>"));
    assert!(svg.contains(">Payment</tspan>"));
    assert!(svg.contains(">Authorization</tspan>"));
    assert!(svg.contains(">Service</tspan>"));
    assert_reasonable_svg_aspect(&svg, 4.2);
    write_render_artifact("rust-elk", "rust_elk_renders_grouped_rich_graph", &svg);
}

#[test]
fn rust_elk_renders_archimate_metadata_notation_with_bounded_route_channel_limitations() {
    let temp = assert_fs::TempDir::new().unwrap();
    let source = "fixtures/source/valid-pipeline-archimate.json";

    let request_output = project_layout_request(source);
    let request = write_temp_bytes(&temp, "archimate-layout-request.json", &request_output);
    let metadata_output = project_render_metadata(source);
    let metadata = write_temp_bytes(&temp, "archimate-render-metadata.json", &metadata_output);

    let metadata_data = ok_data(&metadata_output);
    assert_eq!(metadata_data["semantic_profile"], "archimate");
    assert_eq!(
        metadata_data["nodes"]["orders-api"]["type"],
        "ApplicationService"
    );
    assert_eq!(metadata_data["nodes"]["client"]["type"], "BusinessActor");
    assert_eq!(
        metadata_data["nodes"]["payments"]["type"],
        "ApplicationService"
    );
    assert_eq!(metadata_data["nodes"]["database"]["type"], "DataObject");
    assert_eq!(
        metadata_data["edges"]["web-app-calls-api"]["type"],
        "Realization"
    );

    let layout_output = rust_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "archimate-layout-result.json", &layout_output);
    let layout_data = ok_data(&layout_output);
    assert_layout_quality_with_bounds(
        &validate_layout(&layout),
        &layout_data,
        LayoutQualityBounds {
            max_route_detours: 2,
            max_route_close_parallel: 12,
            max_group_boundary_issues: 1,
            ..LayoutQualityBounds::strict()
        },
    );

    let svg = render_svg(
        &layout,
        "fixtures/render-policy/archimate-svg.json",
        Some(&metadata),
    );
    let doc = svg_doc(&svg);

    for node_id in ["web-app", "worker"] {
        let component = semantic_group(&doc, "data-dediren-node-id", node_id);
        assert!(child_group_with_attr(
            component,
            "data-dediren-node-decorator",
            "archimate_application_component",
        )
        .is_some());
    }

    let business = semantic_group(&doc, "data-dediren-node-id", "client");
    assert!(child_group_with_attr(
        business,
        "data-dediren-node-decorator",
        "archimate_business_actor",
    )
    .is_some());

    for node_id in ["orders-api", "payments"] {
        let service = semantic_group(&doc, "data-dediren-node-id", node_id);
        assert!(child_group_with_attr(
            service,
            "data-dediren-node-decorator",
            "archimate_application_service",
        )
        .is_some());
    }

    let data_object = semantic_group(&doc, "data-dediren-node-id", "database");
    assert!(child_group_with_attr(
        data_object,
        "data-dediren-node-decorator",
        "archimate_data_object",
    )
    .is_some());

    let realization = semantic_group(&doc, "data-dediren-edge-id", "web-app-calls-api");
    let path = child_element(realization, "path");
    assert_eq!(path.attribute("stroke-dasharray"), Some("8 5"));
    assert_eq!(
        path.attribute("marker-end"),
        Some("url(#marker-end-web-app-calls-api)")
    );
    assert_svg_texts_include(&doc, &["Customer", "Orders API", "publishes fulfillment"]);
    assert!(svg.contains(">Fulfillment</tspan>"));
    assert!(svg.contains(">Worker</tspan>"));
    assert!(svg.contains(">Payment</tspan>"));
    assert!(svg.contains(">Authorization</tspan>"));
    assert!(svg.contains(">Service</tspan>"));
    assert_reasonable_svg_aspect(&svg, 5.0);
    write_render_artifact(
        "rust-elk",
        "rust_elk_renders_archimate_metadata_notation",
        &svg,
    );
}

#[test]
fn rust_elk_renders_uml_class_profile() {
    let (svg, metadata_data, layout_data) = render_rust_elk_uml_view_from_source_with_detour_budget(
        "fixtures/source/valid-uml-basic.json",
        "class-view",
        1,
    );
    let doc = svg_doc(&svg);

    assert_eq!(layout_data["view_id"], "class-view");
    assert_eq!(metadata_data["semantic_profile"], "uml");
    assert_eq!(metadata_data["nodes"]["class-order"]["type"], "Class");
    assert_eq!(
        metadata_data["nodes"]["enum-order-status"]["type"],
        "Enumeration"
    );
    assert_eq!(
        metadata_data["edges"]["order-has-lines"]["type"],
        "Composition"
    );
    assert_svg_texts_include(
        &doc,
        &[
            "Orders",
            "Order",
            "OrderLine",
            "OrderStatus",
            "+ id : OrderId",
            "+ submit() : void",
            "lines",
            "uses",
        ],
    );
    assert_uml_node_decorator(&doc, "pkg-orders", "uml_package");
    assert_uml_node_decorator(&doc, "class-order", "uml_class");
    assert_uml_node_decorator(&doc, "enum-order-status", "uml_enumeration");
    assert_edge_marker_start(&doc, "order-has-lines", "filled_diamond");
    assert_edge_marker_end(&doc, "order-status-dependency", "open_arrow");
    assert_reasonable_svg_aspect(&svg, 5.0);
    write_render_artifact("rust-elk", "rust_elk_renders_uml_class_profile", &svg);
}

#[test]
fn rust_elk_renders_uml_data_profile() {
    let (svg, metadata_data, layout_data) = render_rust_elk_uml_view("data-view");
    let doc = svg_doc(&svg);

    assert_eq!(layout_data["view_id"], "data-view");
    assert_eq!(metadata_data["semantic_profile"], "uml");
    assert_eq!(metadata_data["nodes"]["class-order"]["type"], "Class");
    assert_eq!(
        metadata_data["nodes"]["enum-order-status"]["type"],
        "Enumeration"
    );
    assert_svg_texts_include(
        &doc,
        &[
            "Order",
            "OrderLine",
            "OrderStatus",
            "- status : OrderStatus",
            "Draft",
            "Submitted",
            "lines",
            "uses",
        ],
    );
    assert_uml_node_decorator(&doc, "class-order", "uml_class");
    assert_uml_node_decorator(&doc, "class-order-line", "uml_class");
    assert_uml_node_decorator(&doc, "enum-order-status", "uml_enumeration");
    assert_edge_marker_start(&doc, "order-has-lines", "filled_diamond");
    assert_edge_marker_end(&doc, "order-status-dependency", "open_arrow");
    assert_reasonable_svg_aspect(&svg, 5.0);
    write_render_artifact("rust-elk", "rust_elk_renders_uml_data_profile", &svg);
}

#[test]
fn rust_elk_renders_uml_activity_profile() {
    let (svg, metadata_data, layout_data) = render_rust_elk_uml_view("activity-view");
    let doc = svg_doc(&svg);

    assert_eq!(layout_data["view_id"], "activity-view");
    assert_eq!(metadata_data["semantic_profile"], "uml");
    assert_eq!(
        metadata_data["nodes"]["initial-submit"]["type"],
        "InitialNode"
    );
    assert_eq!(
        metadata_data["nodes"]["decision-valid"]["type"],
        "DecisionNode"
    );
    assert_eq!(
        metadata_data["edges"]["flow-valid-submit"]["type"],
        "ControlFlow"
    );
    assert_svg_texts_include(&doc, &["Enter order", "Valid?", "Submit", "yes"]);
    assert_uml_node_decorator(&doc, "initial-submit", "uml_initial_node");
    assert_uml_node_decorator(&doc, "action-enter-order", "uml_action");
    assert_uml_node_decorator(&doc, "decision-valid", "uml_decision_node");
    assert_uml_node_decorator(&doc, "final-submit", "uml_activity_final_node");
    assert_edge_marker_end(&doc, "flow-start-enter", "open_arrow");
    assert_edge_marker_end(&doc, "flow-valid-submit", "open_arrow");
    assert_reasonable_svg_aspect(&svg, 8.0);
    write_render_artifact("rust-elk", "rust_elk_renders_uml_activity_profile", &svg);
}

#[test]
fn rust_elk_renders_uml_decision_fanout_branches_with_single_source_anchor_limit() {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = serde_json::json!({
        "layout_request_schema_version": "layout-request.schema.v1",
        "view_id": "decision-fanout",
        "nodes": [
            { "id": "start", "label": "", "source_id": "start", "width_hint": 28.0, "height_hint": 28.0 },
            { "id": "check-cache", "label": "Cached?", "source_id": "check-cache", "width_hint": 32.0, "height_hint": 32.0 },
            { "id": "use-cached", "label": "Use cached", "source_id": "use-cached", "width_hint": 140.0, "height_hint": 64.0 },
            { "id": "refresh", "label": "Refresh", "source_id": "refresh", "width_hint": 140.0, "height_hint": 64.0 }
        ],
        "edges": [
            { "id": "flow-start-check", "source": "start", "target": "check-cache", "label": "", "source_id": "flow-start-check", "relationship_type": "ControlFlow" },
            { "id": "flow-cached", "source": "check-cache", "target": "use-cached", "label": "cached", "source_id": "flow-cached", "relationship_type": "ControlFlow" },
            { "id": "flow-stale", "source": "check-cache", "target": "refresh", "label": "stale", "source_id": "flow-stale", "relationship_type": "ControlFlow" }
        ],
        "groups": [],
        "labels": [],
        "constraints": [],
        "layout_preferences": {
            "direction": "right",
            "density": "spacious",
            "routing": {
                "style": "orthogonal",
                "profile": "spacious",
                "endpoint_merging": "off"
            }
        }
    });
    let metadata = serde_json::json!({
        "render_metadata_schema_version": "render-metadata.schema.v1",
        "semantic_profile": "uml",
        "nodes": {
            "start": { "type": "InitialNode", "source_id": "start" },
            "check-cache": { "type": "DecisionNode", "source_id": "check-cache" },
            "use-cached": { "type": "Action", "source_id": "use-cached" },
            "refresh": { "type": "Action", "source_id": "refresh" }
        },
        "edges": {
            "flow-start-check": { "type": "ControlFlow", "source_id": "flow-start-check" },
            "flow-cached": { "type": "ControlFlow", "source_id": "flow-cached" },
            "flow-stale": { "type": "ControlFlow", "source_id": "flow-stale" }
        }
    });
    let request = write_temp_json(&temp, "decision-fanout-layout-request.json", &request);
    let metadata = write_temp_json(&temp, "decision-fanout-render-metadata.json", &metadata);

    let layout_output = rust_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "decision-fanout-layout-result.json", &layout_output);
    let layout_data = ok_data(&layout_output);
    assert_elkrs_edge_spacing_supported(&layout_data);
    assert_layout_quality_with_bounds(
        &validate_layout(&layout),
        &layout_data,
        LayoutQualityBounds::elkrs_spacing_supported(),
    );

    assert_eq!(layout_data["view_id"], "decision-fanout");
    assert_edges_have_at_most_corner_count(&layout_data, &["flow-cached", "flow-stale"], 2);

    let svg = render_svg(
        &layout,
        "fixtures/render-policy/uml-svg.json",
        Some(&metadata),
    );
    let doc = svg_doc(&svg);
    assert_svg_texts_include(
        &doc,
        &["Cached?", "Use cached", "Refresh", "cached", "stale"],
    );
    assert_uml_node_decorator(&doc, "check-cache", "uml_decision_node");
    assert_edge_marker_end(&doc, "flow-cached", "open_arrow");
    assert_edge_marker_end(&doc, "flow-stale", "open_arrow");
    assert_reasonable_svg_aspect(&svg, 5.0);
    write_render_artifact(
        "rust-elk",
        "rust_elk_renders_uml_decision_fanout_branches",
        &svg,
    );
}

#[test]
fn rust_elk_renders_complex_uml_class_profile() {
    let (svg, metadata_data, layout_data) =
        render_rust_elk_uml_view_from_source_with_quality_bounds(
            "fixtures/source/valid-uml-complex.json",
            "complex-class-view",
            LayoutQualityBounds {
                max_route_detours: 6,
                max_route_close_parallel: 19,
                max_connector_through_nodes: 9,
                ..LayoutQualityBounds::elkrs_spacing_supported()
            },
        );
    let doc = svg_doc(&svg);

    assert_eq!(layout_data["view_id"], "complex-class-view");
    assert_eq!(metadata_data["semantic_profile"], "uml");
    assert_eq!(metadata_data["nodes"]["class-order"]["type"], "Class");
    assert_eq!(
        metadata_data["nodes"]["interface-payment-gateway"]["type"],
        "Interface"
    );
    assert_eq!(metadata_data["nodes"]["datatype-money"]["type"], "DataType");
    assert_eq!(
        metadata_data["edges"]["order-has-lines"]["type"],
        "Composition"
    );
    assert_eq!(
        metadata_data["edges"]["card-payment-realizes-gateway"]["type"],
        "Realization"
    );
    assert_laid_out_node_min_size(&layout_data, "class-order", 300.0, 190.0);
    assert_laid_out_node_min_size(&layout_data, "interface-payment-gateway", 380.0, 120.0);
    assert_svg_texts_include(
        &doc,
        &[
            "Commerce",
            "Fulfillment",
            "Order",
            "OrderLine",
            "Customer",
            "PaymentGateway",
            "CardPayment",
            "Shipment",
            "Money",
            "OrderStatus",
            "implements",
            "ships to",
        ],
    );
    assert_uml_node_decorator(&doc, "class-order", "uml_class");
    assert_uml_node_decorator(&doc, "interface-payment-gateway", "uml_interface");
    assert_uml_node_decorator(&doc, "datatype-money", "uml_data_type");
    assert_uml_node_decorator(&doc, "enum-order-status", "uml_enumeration");
    assert_edge_marker_start(&doc, "order-has-lines", "filled_diamond");
    assert_edge_marker_start(&doc, "order-has-payment", "hollow_diamond");
    assert_edge_marker_end(&doc, "card-payment-realizes-gateway", "hollow_triangle");
    assert_edge_marker_end(&doc, "order-status-dependency", "open_arrow");
    assert_reasonable_svg_aspect(&svg, 6.0);
    write_render_artifact(
        "rust-elk",
        "rust_elk_renders_complex_uml_class_profile",
        &svg,
    );
}

#[test]
fn rust_elk_renders_complex_uml_data_profile() {
    let (svg, metadata_data, layout_data) =
        render_rust_elk_uml_view_from_source_with_quality_bounds(
            "fixtures/source/valid-uml-complex.json",
            "complex-data-view",
            LayoutQualityBounds {
                max_route_detours: 1,
                max_connector_through_nodes: 1,
                ..LayoutQualityBounds::elkrs_spacing_supported()
            },
        );
    let doc = svg_doc(&svg);

    assert_eq!(layout_data["view_id"], "complex-data-view");
    assert_eq!(metadata_data["semantic_profile"], "uml");
    assert_eq!(metadata_data["nodes"]["class-order"]["type"], "Class");
    assert_eq!(metadata_data["nodes"]["class-shipment"]["type"], "Class");
    assert_eq!(
        metadata_data["nodes"]["datatype-address"]["type"],
        "DataType"
    );
    assert_eq!(
        metadata_data["nodes"]["enum-shipment-state"]["type"],
        "Enumeration"
    );
    assert_svg_texts_include(
        &doc,
        &[
            "Customer",
            "Order",
            "OrderLine",
            "Shipment",
            "OrderId",
            "Money",
            "Address",
            "OrderStatus",
            "PaymentState",
            "ShipmentState",
            "places",
            "lines",
            "ships to",
        ],
    );
    assert_uml_node_decorator(&doc, "class-order", "uml_class");
    assert_uml_node_decorator(&doc, "datatype-address", "uml_data_type");
    assert_uml_node_decorator(&doc, "enum-shipment-state", "uml_enumeration");
    assert_edge_marker_start(&doc, "order-has-lines", "filled_diamond");
    assert_edge_marker_end(&doc, "order-status-dependency", "open_arrow");
    assert_edge_marker_end(&doc, "shipment-state", "open_arrow");
    assert_reasonable_svg_aspect(&svg, 6.0);
    write_render_artifact(
        "rust-elk",
        "rust_elk_renders_complex_uml_data_profile",
        &svg,
    );
}

#[test]
fn rust_elk_renders_complex_uml_activity_profile() {
    let (svg, metadata_data, layout_data) =
        render_rust_elk_uml_view_from_source_with_quality_bounds(
            "fixtures/source/valid-uml-complex.json",
            "complex-activity-view",
            LayoutQualityBounds {
                max_route_close_parallel: 8,
                ..LayoutQualityBounds::elkrs_spacing_supported()
            },
        );
    let doc = svg_doc(&svg);

    assert_eq!(layout_data["view_id"], "complex-activity-view");
    assert_eq!(metadata_data["semantic_profile"], "uml");
    assert_eq!(
        metadata_data["nodes"]["activity-fulfill-order"]["type"],
        "Activity"
    );
    assert_eq!(
        metadata_data["nodes"]["fork-fulfillment"]["type"],
        "ForkNode"
    );
    assert_eq!(
        metadata_data["nodes"]["object-shipment"]["type"],
        "ObjectNode"
    );
    assert_eq!(
        metadata_data["edges"]["flow-label-shipment"]["type"],
        "ObjectFlow"
    );
    assert_svg_texts_include(
        &doc,
        &[
            "Fulfill Order",
            "Load order",
            "Paid?",
            "Reserve stock",
            "Pack order",
            "Create label",
            "Shipment",
            "paid",
            "label",
        ],
    );
    assert_uml_node_decorator(&doc, "activity-fulfill-order", "uml_activity");
    assert_uml_node_decorator(&doc, "initial-fulfill", "uml_initial_node");
    assert_uml_node_decorator(&doc, "fork-fulfillment", "uml_fork_node");
    assert_uml_node_decorator(&doc, "join-fulfillment", "uml_join_node");
    assert_uml_node_decorator(&doc, "object-shipment", "uml_object_node");
    assert_uml_node_decorator(&doc, "final-fulfill", "uml_activity_final_node");
    assert_edge_marker_end(&doc, "flow-start-load", "open_arrow");
    assert_edge_marker_end(&doc, "flow-label-shipment", "open_arrow");
    assert_edge_marker_end(&doc, "flow-join-final", "open_arrow");
    assert_reasonable_svg_aspect(&svg, 7.0);
    write_render_artifact(
        "rust-elk",
        "rust_elk_renders_complex_uml_activity_profile",
        &svg,
    );
}

#[test]
fn rust_elk_renders_cross_group_route_with_bounded_compound_route_limitations() {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = serde_json::json!({
        "layout_request_schema_version": "layout-request.schema.v1",
        "view_id": "main",
        "nodes": [
            { "id": "a", "label": "A", "source_id": "a", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "b", "label": "B", "source_id": "b", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "c", "label": "C", "source_id": "c", "width_hint": 160.0, "height_hint": 80.0 }
        ],
        "edges": [
            { "id": "a-to-b", "source": "a", "target": "b", "label": "internal", "source_id": "a-to-b" },
            { "id": "a-to-c", "source": "a", "target": "c", "label": "connects", "source_id": "a-to-c" }
        ],
        "groups": [
            {
                "id": "group",
                "label": "Group",
                "members": ["a", "b"],
                "provenance": { "semantic_backed": { "source_id": "group" } }
            }
        ],
        "labels": [],
        "constraints": []
    });
    let request = write_temp_json(&temp, "cross-group-layout-request.json", &request);

    let layout_output = rust_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "cross-group-layout-result.json", &layout_output);
    assert_layout_quality_with_bounds(
        &validate_layout(&layout),
        &Value::Null,
        LayoutQualityBounds {
            max_route_detours: 2,
            max_connector_through_nodes: 1,
            ..LayoutQualityBounds::strict()
        },
    );

    let layout_data = ok_data(&layout_output);
    assert!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .iter()
            .any(|edge| edge["id"] == "a-to-c"),
        "Rust backend output should preserve the cross-group edge"
    );

    let svg = render_svg(&layout, "fixtures/render-policy/default-svg.json", None);
    let doc = svg_doc(&svg);
    assert_svg_texts_include(&doc, &["A", "B", "C", "connects"]);
    assert_reasonable_svg_aspect(&svg, 3.6);
    write_render_artifact(
        "rust-elk",
        "rust_elk_renders_cross_group_route_with_bounded_compound_route_limitations",
        &svg,
    );
}

#[test]
fn rust_elk_renders_complex_multi_layer_system_with_bounded_route_quality_limitations() {
    let temp = assert_fs::TempDir::new().unwrap();
    let mut request = serde_json::json!({
        "layout_request_schema_version": "layout-request.schema.v1",
        "view_id": "main",
        "nodes": [
            { "id": "customer-mobile", "label": "Mobile App", "source_id": "customer-mobile", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "customer-web", "label": "Web Customer", "source_id": "customer-web", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "support-agent", "label": "Support Agent", "source_id": "support-agent", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "cdn", "label": "CDN", "source_id": "cdn", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "web-frontend", "label": "Web Frontend", "source_id": "web-frontend", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "admin-portal", "label": "Admin Portal", "source_id": "admin-portal", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "api-gateway", "label": "API Gateway", "source_id": "api-gateway", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "identity-service", "label": "Identity Service", "source_id": "identity-service", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "catalog-service", "label": "Catalog Service", "source_id": "catalog-service", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "pricing-service", "label": "Pricing Service", "source_id": "pricing-service", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "order-service", "label": "Order Service", "source_id": "order-service", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "gateway-and-junction", "label": "", "source_id": "gateway-and-junction", "width_hint": 28.0, "height_hint": 28.0 },
            { "id": "payment-service", "label": "Payment Service", "source_id": "payment-service", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "fulfillment-service", "label": "Fulfillment Service", "source_id": "fulfillment-service", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "notification-service", "label": "Notification Service", "source_id": "notification-service", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "event-bus", "label": "Event Bus", "source_id": "event-bus", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "event-dispatch-or-junction", "label": "", "source_id": "event-dispatch-or-junction", "width_hint": 28.0, "height_hint": 28.0 },
            { "id": "order-worker", "label": "Order Worker", "source_id": "order-worker", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "warehouse-adapter", "label": "Warehouse Adapter", "source_id": "warehouse-adapter", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "email-worker", "label": "Email Worker", "source_id": "email-worker", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "reporting-ingestor", "label": "Reporting Ingestor", "source_id": "reporting-ingestor", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "session-cache", "label": "Session Cache", "source_id": "session-cache", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "product-db", "label": "Product DB", "source_id": "product-db", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "order-db", "label": "Order DB", "source_id": "order-db", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "payment-ledger", "label": "Payment Ledger", "source_id": "payment-ledger", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "warehouse-db", "label": "Warehouse DB", "source_id": "warehouse-db", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "analytics-warehouse", "label": "Analytics Warehouse", "source_id": "analytics-warehouse", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "identity-provider", "label": "Identity Provider", "source_id": "identity-provider", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "payment-provider", "label": "Payment Provider", "source_id": "payment-provider", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "carrier-api", "label": "Carrier API", "source_id": "carrier-api", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "email-provider", "label": "Email Provider", "source_id": "email-provider", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "erp", "label": "ERP", "source_id": "erp", "width_hint": 160.0, "height_hint": 80.0 }
        ],
        // The edge array is also ELK model-order input. Keep related edges near
        // each other so the pinned elkrs subset produces stable render
        // evidence for this hand-authored real-world fixture.
        "edges": [
            { "id": "mobile-enters-cdn", "source": "customer-mobile", "target": "cdn", "label": "uses", "source_id": "mobile-enters-cdn" },
            { "id": "web-enters-cdn", "source": "customer-web", "target": "cdn", "label": "uses", "source_id": "web-enters-cdn" },
            { "id": "support-opens-admin", "source": "support-agent", "target": "admin-portal", "label": "manages", "source_id": "support-opens-admin" },
            { "id": "cdn-serves-web", "source": "cdn", "target": "web-frontend", "label": "serves", "source_id": "cdn-serves-web" },
            { "id": "admin-calls-gateway", "source": "admin-portal", "target": "api-gateway", "label": "calls", "source_id": "admin-calls-gateway" },
            { "id": "web-calls-gateway", "source": "web-frontend", "target": "api-gateway", "label": "calls", "source_id": "web-calls-gateway" },
            { "id": "gateway-to-and-junction", "source": "api-gateway", "target": "gateway-and-junction", "label": "routes", "source_id": "gateway-to-and-junction" },
            { "id": "and-junction-authenticates", "source": "gateway-and-junction", "target": "identity-service", "label": "authenticates", "source_id": "and-junction-authenticates", "relationship_type": "Association" },
            { "id": "and-junction-queries-catalog", "source": "gateway-and-junction", "target": "catalog-service", "label": "queries catalog", "source_id": "and-junction-queries-catalog", "relationship_type": "Association" },
            { "id": "and-junction-prices-cart", "source": "gateway-and-junction", "target": "pricing-service", "label": "prices cart", "source_id": "and-junction-prices-cart", "relationship_type": "Association" },
            { "id": "and-junction-places-order", "source": "gateway-and-junction", "target": "order-service", "label": "places order", "source_id": "and-junction-places-order", "relationship_type": "Association" },
            { "id": "identity-federates", "source": "identity-service", "target": "identity-provider", "label": "federates", "source_id": "identity-federates" },
            { "id": "catalog-reads-products", "source": "catalog-service", "target": "product-db", "label": "reads products", "source_id": "catalog-reads-products" },
            { "id": "pricing-reads-products", "source": "pricing-service", "target": "product-db", "label": "reads products", "source_id": "pricing-reads-products" },
            { "id": "pricing-caches-quotes", "source": "pricing-service", "target": "session-cache", "label": "caches quotes", "source_id": "pricing-caches-quotes" },
            { "id": "identity-caches-session", "source": "identity-service", "target": "session-cache", "label": "caches session", "source_id": "identity-caches-session" },
            { "id": "order-requests-payment", "source": "order-service", "target": "payment-service", "label": "requests payment", "source_id": "order-requests-payment" },
            { "id": "order-reserves-stock", "source": "order-service", "target": "fulfillment-service", "label": "reserves stock", "source_id": "order-reserves-stock" },
            { "id": "order-checks-catalog", "source": "order-service", "target": "catalog-service", "label": "checks catalog", "source_id": "order-checks-catalog" },
            { "id": "order-writes-orders", "source": "order-service", "target": "order-db", "label": "writes orders", "source_id": "order-writes-orders" },
            { "id": "order-publishes-events", "source": "order-service", "target": "event-bus", "label": "publishes events", "source_id": "order-publishes-events" },
            { "id": "payment-authorizes", "source": "payment-service", "target": "payment-provider", "label": "authorizes", "source_id": "payment-authorizes" },
            { "id": "payment-records-ledger", "source": "payment-service", "target": "payment-ledger", "label": "records ledger", "source_id": "payment-records-ledger" },
            { "id": "fulfillment-ships", "source": "fulfillment-service", "target": "carrier-api", "label": "ships", "source_id": "fulfillment-ships" },
            { "id": "fulfillment-syncs-warehouse", "source": "fulfillment-service", "target": "warehouse-adapter", "label": "syncs", "source_id": "fulfillment-syncs-warehouse" },
            { "id": "fulfillment-writes-warehouse", "source": "fulfillment-service", "target": "warehouse-db", "label": "updates stock", "source_id": "fulfillment-writes-warehouse" },
            { "id": "fulfillment-publishes-events", "source": "fulfillment-service", "target": "event-bus", "label": "publishes events", "source_id": "fulfillment-publishes-events" },
            { "id": "event-bus-to-or-junction", "source": "event-bus", "target": "event-dispatch-or-junction", "label": "dispatches", "source_id": "event-bus-to-or-junction" },
            { "id": "event-bus-drives-order-worker", "source": "event-bus", "target": "order-worker", "label": "order event", "source_id": "event-bus-drives-order-worker" },
            { "id": "email-worker-notifies", "source": "email-worker", "target": "notification-service", "label": "notifies", "source_id": "email-worker-notifies" },
            { "id": "or-junction-drives-email-worker", "source": "event-dispatch-or-junction", "target": "email-worker", "label": "email event", "source_id": "or-junction-drives-email-worker" },
            { "id": "or-junction-drives-reporting", "source": "event-dispatch-or-junction", "target": "reporting-ingestor", "label": "reporting event", "source_id": "or-junction-drives-reporting" },
            { "id": "order-worker-reads-orders", "source": "order-worker", "target": "order-db", "label": "reads orders", "source_id": "order-worker-reads-orders" },
            { "id": "warehouse-adapter-syncs-erp", "source": "warehouse-adapter", "target": "erp", "label": "syncs stock", "source_id": "warehouse-adapter-syncs-erp" },
            { "id": "order-worker-syncs-erp", "source": "order-worker", "target": "erp", "label": "syncs orders", "source_id": "order-worker-syncs-erp" },
            { "id": "warehouse-adapter-writes-db", "source": "warehouse-adapter", "target": "warehouse-db", "label": "writes state", "source_id": "warehouse-adapter-writes-db" },
            { "id": "notification-sends-email", "source": "notification-service", "target": "email-provider", "label": "sends email", "source_id": "notification-sends-email" },
            { "id": "reporting-reads-orders", "source": "reporting-ingestor", "target": "order-db", "label": "reads changes", "source_id": "reporting-reads-orders" },
            { "id": "reporting-writes-analytics", "source": "reporting-ingestor", "target": "analytics-warehouse", "label": "loads facts", "source_id": "reporting-writes-analytics" }
        ],
        "groups": [
            {
                "id": "users",
                "label": "Users",
                "members": ["customer-mobile", "customer-web", "support-agent"],
                "provenance": { "semantic_backed": { "source_id": "users" } }
            },
            {
                "id": "edge-platform",
                "label": "Edge Platform",
                "members": ["cdn", "web-frontend", "admin-portal", "api-gateway"],
                "provenance": { "semantic_backed": { "source_id": "edge-platform" } }
            },
            {
                "id": "core-services",
                "label": "Core Services",
                "members": ["identity-service", "catalog-service", "pricing-service", "order-service", "payment-service", "fulfillment-service", "notification-service"],
                "provenance": { "semantic_backed": { "source_id": "core-services" } }
            },
            {
                "id": "async-processing",
                "label": "Async Processing",
                "members": ["event-bus", "event-dispatch-or-junction", "order-worker", "warehouse-adapter", "email-worker", "reporting-ingestor"],
                "provenance": { "semantic_backed": { "source_id": "async-processing" } }
            },
            {
                "id": "data-platform",
                "label": "Data Platform",
                "members": ["session-cache", "product-db", "order-db", "payment-ledger", "warehouse-db", "analytics-warehouse"],
                "provenance": { "semantic_backed": { "source_id": "data-platform" } }
            },
            {
                "id": "external-systems",
                "label": "External Systems",
                "members": ["identity-provider", "payment-provider", "carrier-api", "email-provider", "erp"],
                "provenance": { "semantic_backed": { "source_id": "external-systems" } }
            }
        ],
        "labels": [],
        "constraints": []
    });
    let metadata = complex_archimate_render_metadata();
    apply_layout_request_relationship_types_from_render_metadata(&mut request, &metadata);
    assert_layout_request_relationship_types_match_render_metadata(&request, &metadata);
    let request = write_temp_json(&temp, "complex-multi-layer-layout-request.json", &request);

    let layout_output = rust_elk_layout(&request);
    let layout = write_temp_bytes(
        &temp,
        "complex-multi-layer-layout-result.json",
        &layout_output,
    );
    let layout_data = ok_data(&layout_output);
    let default_svg = render_svg(&layout, "fixtures/render-policy/default-svg.json", None);
    write_render_artifact(
        "rust-elk",
        "rust_elk_renders_complex_multi_layer_system_default",
        &default_svg,
    );
    let route_quality_artifact = write_close_parallel_route_artifact(
        &default_svg,
        &layout_data,
        "rust_elk_renders_complex_multi_layer_system_route_quality",
    );
    assert_complex_layout_quality_bounded(
        &validate_layout(&layout),
        &layout_data,
        route_quality_artifact.as_deref(),
    );
    assert_eq!(
        layout_data["nodes"]
            .as_array()
            .expect("laid out nodes should be an array")
            .len(),
        32
    );
    assert_eq!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .len(),
        39
    );
    assert_eq!(
        layout_data["groups"]
            .as_array()
            .expect("laid out groups should be an array")
            .len(),
        6
    );
    assert_edges_have_at_most_corner_count(
        &layout_data,
        &[
            "and-junction-authenticates",
            "and-junction-queries-catalog",
            "and-junction-prices-cart",
            "and-junction-places-order",
        ],
        6,
    );
    assert_edges_have_at_most_corner_count(
        &layout_data,
        &["fulfillment-writes-warehouse", "payment-records-ledger"],
        8,
    );
    assert_edges_have_at_most_corner_count(&layout_data, &["or-junction-drives-email-worker"], 4);
    let default_doc = svg_doc(&default_svg);
    assert_complex_profile_svg(&default_doc, &default_svg);

    let rich_svg = render_svg(&layout, "fixtures/render-policy/rich-svg.json", None);
    let rich_doc = svg_doc(&rich_svg);
    assert_complex_profile_svg(&rich_doc, &rich_svg);
    write_render_artifact(
        "rust-elk",
        "rust_elk_renders_complex_multi_layer_system_rich",
        &rich_svg,
    );

    let archimate_edge_types = metadata["edges"]
        .as_object()
        .expect("complex ArchiMate metadata should contain edges")
        .values()
        .filter_map(|edge| edge["type"].as_str())
        .collect::<BTreeSet<_>>();
    assert!(
        archimate_edge_types.len() >= 5,
        "complex ArchiMate metadata should exercise mixed relationship notation: {archimate_edge_types:?}"
    );
    assert_eq!(
        metadata["edges"]["email-worker-notifies"]["type"],
        "Realization"
    );
    assert_eq!(metadata["edges"]["order-writes-orders"]["type"], "Access");
    assert_eq!(
        metadata["edges"]["order-publishes-events"]["type"],
        "Triggering"
    );
    assert_eq!(
        metadata["nodes"]["gateway-and-junction"]["type"],
        "AndJunction"
    );
    assert_eq!(
        metadata["nodes"]["event-dispatch-or-junction"]["type"],
        "OrJunction"
    );
    assert_eq!(
        metadata["edges"]["gateway-to-and-junction"]["type"],
        "Association"
    );
    assert_eq!(
        metadata["edges"]["event-bus-to-or-junction"]["type"],
        "Triggering"
    );
    assert_eq!(
        metadata["edges"]["fulfillment-syncs-warehouse"]["type"],
        "Serving"
    );
    let metadata = write_temp_json(
        &temp,
        "complex-multi-layer-archimate-render-metadata.json",
        &metadata,
    );
    let archimate_svg = render_svg(
        &layout,
        "fixtures/render-policy/archimate-svg.json",
        Some(&metadata),
    );
    let archimate_doc = svg_doc(&archimate_svg);
    assert_complex_profile_svg(&archimate_doc, &archimate_svg);

    let business_actor = semantic_group(&archimate_doc, "data-dediren-node-id", "customer-mobile");
    assert!(child_group_with_attr(
        business_actor,
        "data-dediren-node-decorator",
        "archimate_business_actor",
    )
    .is_some());
    let application_service =
        semantic_group(&archimate_doc, "data-dediren-node-id", "order-service");
    assert!(child_group_with_attr(
        application_service,
        "data-dediren-node-decorator",
        "archimate_application_service",
    )
    .is_some());
    let data_object = semantic_group(&archimate_doc, "data-dediren-node-id", "order-db");
    assert!(child_group_with_attr(
        data_object,
        "data-dediren-node-decorator",
        "archimate_data_object",
    )
    .is_some());
    let technology_service = semantic_group(&archimate_doc, "data-dediren-node-id", "carrier-api");
    assert!(child_group_with_attr(
        technology_service,
        "data-dediren-node-decorator",
        "archimate_technology_service",
    )
    .is_some());
    assert_archimate_node_shape(
        &archimate_doc,
        "gateway-and-junction",
        "archimate_and_junction",
    );
    assert_archimate_node_shape(
        &archimate_doc,
        "event-dispatch-or-junction",
        "archimate_or_junction",
    );
    let association = semantic_group(&archimate_doc, "data-dediren-edge-id", "mobile-enters-cdn");
    let path = child_element(association, "path");
    assert_eq!(path.attribute("marker-end"), None);
    assert_archimate_edge_notation(
        &archimate_doc,
        "email-worker-notifies",
        Some("8 5"),
        Some("hollow_triangle"),
    );
    assert_archimate_edge_notation(
        &archimate_doc,
        "order-writes-orders",
        Some("8 5"),
        Some("open_arrow"),
    );
    assert_archimate_edge_notation(
        &archimate_doc,
        "order-publishes-events",
        None,
        Some("open_arrow"),
    );
    write_render_artifact(
        "rust-elk",
        "rust_elk_renders_complex_multi_layer_system_archimate",
        &archimate_svg,
    );
}

fn project_layout_request(source_fixture: &str) -> Vec<u8> {
    project_layout_request_for_view(source_fixture, "main")
}

fn project_layout_request_for_view(source_fixture: &str, view_id: &str) -> Vec<u8> {
    common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "project",
            "--target",
            "layout-request",
            "--plugin",
            "generic-graph",
            "--view",
            view_id,
            "--input",
        ])
        .arg(workspace_file(source_fixture))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone()
}

fn project_render_metadata(source_fixture: &str) -> Vec<u8> {
    project_render_metadata_for_view(source_fixture, "main")
}

fn project_render_metadata_for_view(source_fixture: &str, view_id: &str) -> Vec<u8> {
    common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "project",
            "--target",
            "render-metadata",
            "--plugin",
            "generic-graph",
            "--view",
            view_id,
            "--input",
        ])
        .arg(workspace_file(source_fixture))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone()
}

fn render_rust_elk_uml_view(view_id: &str) -> (String, Value, Value) {
    render_rust_elk_uml_view_from_source("fixtures/source/valid-uml-basic.json", view_id)
}

fn render_rust_elk_uml_view_from_source(source: &str, view_id: &str) -> (String, Value, Value) {
    render_rust_elk_uml_view_from_source_with_quality_bounds(
        source,
        view_id,
        LayoutQualityBounds::elkrs_spacing_supported(),
    )
}

fn render_rust_elk_uml_view_from_source_with_detour_budget(
    source: &str,
    view_id: &str,
    max_route_detours: u64,
) -> (String, Value, Value) {
    render_rust_elk_uml_view_from_source_with_quality_bounds(
        source,
        view_id,
        LayoutQualityBounds {
            max_route_detours,
            ..LayoutQualityBounds::elkrs_spacing_supported()
        },
    )
}

fn render_rust_elk_uml_view_from_source_with_quality_bounds(
    source: &str,
    view_id: &str,
    quality_bounds: LayoutQualityBounds,
) -> (String, Value, Value) {
    let temp = assert_fs::TempDir::new().unwrap();

    let request_output = project_layout_request_for_view(source, view_id);
    let request_data = ok_data(&request_output);
    assert_eq!(request_data["view_id"], view_id);
    let request = write_temp_bytes(
        &temp,
        &format!("{view_id}-layout-request.json"),
        &request_output,
    );

    let metadata_output = project_render_metadata_for_view(source, view_id);
    let metadata_data = ok_data(&metadata_output);
    assert_eq!(metadata_data["semantic_profile"], "uml");
    let metadata = write_temp_bytes(
        &temp,
        &format!("{view_id}-render-metadata.json"),
        &metadata_output,
    );

    let layout_output = rust_elk_layout(&request);
    let layout_data = ok_data(&layout_output);
    assert_eq!(layout_data["view_id"], view_id);
    let layout = write_temp_bytes(
        &temp,
        &format!("{view_id}-layout-result.json"),
        &layout_output,
    );
    assert_elkrs_edge_spacing_supported(&layout_data);
    assert_layout_quality_with_bounds(&validate_layout(&layout), &layout_data, quality_bounds);

    let svg = render_svg(
        &layout,
        "fixtures/render-policy/uml-svg.json",
        Some(&metadata),
    );
    (svg, metadata_data, layout_data)
}

fn rust_elk_layout(input: &Path) -> Vec<u8> {
    common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env_remove("DEDIREN_ELK_RESULT_FIXTURE")
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone()
}

fn validate_layout(input: &Path) -> Value {
    let output = common::dediren_command()
        .args(["validate-layout", "--input"])
        .arg(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    ok_data(&output)
}

fn assert_layout_quality_ok(quality: &Value) {
    assert_eq!(quality["status"], "ok", "layout quality: {quality}");
    assert_layout_quality_with_bounds(quality, &Value::Null, LayoutQualityBounds::strict());
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct LayoutQualityBounds {
    max_route_detours: u64,
    max_route_close_parallel: u64,
    max_connector_through_nodes: u64,
    max_group_boundary_issues: u64,
    expected_warning_count: u64,
}

impl LayoutQualityBounds {
    const fn strict() -> Self {
        Self {
            max_route_detours: 0,
            max_route_close_parallel: 0,
            max_connector_through_nodes: 0,
            max_group_boundary_issues: 0,
            expected_warning_count: 0,
        }
    }

    const fn elkrs_spacing_supported() -> Self {
        Self::strict()
    }
}

fn assert_layout_quality_matches_known_elkrs_route_channel_limit(
    quality: &Value,
    layout_data: &Value,
    max_route_close_parallel: u64,
) {
    assert_layout_quality_with_bounds(
        quality,
        layout_data,
        LayoutQualityBounds {
            max_route_detours: 2,
            max_route_close_parallel,
            max_group_boundary_issues: 1,
            ..LayoutQualityBounds::strict()
        },
    );
}

fn assert_layout_quality_with_bounds(
    quality: &Value,
    layout_data: &Value,
    bounds: LayoutQualityBounds,
) {
    let has_measured_warning = [
        "connector_through_node_count",
        "route_detour_count",
        "route_close_parallel_count",
        "group_boundary_issue_count",
        "warning_count",
    ]
    .iter()
    .any(|field| quality[*field].as_u64().unwrap_or(0) > 0);
    let expected_status = if has_measured_warning {
        "warning"
    } else {
        "ok"
    };
    assert_eq!(
        quality["status"], expected_status,
        "layout quality: {quality}"
    );
    assert_eq!(quality["overlap_count"], 0, "layout quality: {quality}");
    assert!(
        quality["connector_through_node_count"]
            .as_u64()
            .unwrap_or(u64::MAX)
            <= bounds.max_connector_through_nodes,
        "layout quality should keep connector-through-node count <= {}: {quality}",
        bounds.max_connector_through_nodes
    );
    assert!(
        quality["route_detour_count"].as_u64().unwrap_or(u64::MAX) <= bounds.max_route_detours,
        "layout quality should keep route detours <= {}: {quality}",
        bounds.max_route_detours
    );
    assert!(
        quality["route_close_parallel_count"]
            .as_u64()
            .unwrap_or(u64::MAX)
            <= bounds.max_route_close_parallel,
        "layout quality should keep close parallel routes <= {}: {quality}{}",
        bounds.max_route_close_parallel,
        close_parallel_route_details(layout_data)
    );
    assert_eq!(
        quality["invalid_route_count"], 0,
        "layout quality: {quality}"
    );
    assert!(
        quality["group_boundary_issue_count"]
            .as_u64()
            .unwrap_or(u64::MAX)
            <= bounds.max_group_boundary_issues,
        "layout quality should keep group boundary issues <= {}: {quality}",
        bounds.max_group_boundary_issues
    );
    assert_eq!(
        quality["warning_count"], bounds.expected_warning_count,
        "layout quality: {quality}"
    );
}

fn assert_complex_layout_quality_bounded(
    quality: &Value,
    layout_data: &Value,
    route_quality_artifact: Option<&Path>,
) {
    assert_eq!(quality["overlap_count"], 0, "layout quality: {quality}");
    assert!(
        quality["connector_through_node_count"]
            .as_u64()
            .unwrap_or(u64::MAX)
            <= 10,
        "elkrs v1.0.0 should keep complex connector-through-node count bounded: {quality}"
    );
    assert_eq!(
        quality["invalid_route_count"], 0,
        "layout quality: {quality}"
    );
    assert!(
        quality["group_boundary_issue_count"]
            .as_u64()
            .unwrap_or(u64::MAX)
            <= 25,
        "elkrs v1.0.0 should keep complex grouped route boundary issues bounded: {quality}"
    );
    assert_eq!(quality["warning_count"], 0, "layout quality: {quality}");
    assert!(
        quality["route_detour_count"].as_u64().unwrap_or(u64::MAX) <= 16,
        "complex layout should keep route detours bounded: {quality}"
    );
    assert!(
        quality["route_close_parallel_count"]
            .as_u64()
            .unwrap_or(u64::MAX)
            <= 509,
        "elkrs v1.0.0 should keep complex close parallel routes bounded: {quality}{}{}",
        close_parallel_route_details(layout_data),
        route_quality_artifact
            .map(|path| format!("\nannotated route-quality SVG: {}", artifact_path(path)))
            .unwrap_or_default()
    );
}

fn assert_elkrs_edge_spacing_supported(layout_data: &Value) {
    let warnings = layout_data["warnings"]
        .as_array()
        .expect("layout warnings should be an array");
    assert_eq!(
        warnings.len(),
        0,
        "elkrs v1.0.0 should accept Dediren edge spacing options without warnings: {warnings:?}"
    );
}

fn artifact_path(path: &Path) -> String {
    path.canonicalize()
        .unwrap_or_else(|_| path.to_path_buf())
        .display()
        .to_string()
}

fn close_parallel_route_details(layout_data: &Value) -> String {
    let pairs = close_parallel_route_pairs(layout_data);
    let mut details = Vec::new();
    for pair in pairs {
        details.push(format!(
            "  - {} <-> {}: {}, distance={:.1}, overlap={:.1}; {} fixed={:.1} span={:.1}..{:.1}; {} fixed={:.1} span={:.1}..{:.1}",
            pair.left.edge_id,
            pair.right.edge_id,
            orientation_name(pair.left.orientation),
            pair.distance,
            pair.overlap,
            pair.left.edge_id,
            pair.left.fixed,
            pair.left.min,
            pair.left.max,
            pair.right.edge_id,
            pair.right.fixed,
            pair.right.min,
            pair.right.max,
        ));
    }
    if details.is_empty() {
        String::new()
    } else {
        format!("\nclose-parallel route pairs:\n{}", details.join("\n"))
    }
}

fn write_close_parallel_route_artifact(
    svg: &str,
    layout_data: &Value,
    test_name: &str,
) -> Option<PathBuf> {
    let annotated = annotate_close_parallel_routes(svg, layout_data);
    if annotated != svg {
        return Some(write_render_artifact("rust-elk", test_name, &annotated));
    }
    None
}

fn annotate_close_parallel_routes(svg: &str, layout_data: &Value) -> String {
    let pairs = close_parallel_route_pairs(layout_data);
    if pairs.is_empty() {
        return svg.to_string();
    }

    let mut overlay = String::from(
        r##"<g data-dediren-route-quality-overlay="close-parallel" font-family="Inter, Arial, sans-serif">"##,
    );
    for (index, pair) in pairs.iter().enumerate() {
        let color = if index % 2 == 0 { "#ef4444" } else { "#f97316" };
        overlay.push_str(&format!(
            r##"<g data-dediren-route-quality-pair="{}--{}">"##,
            escape_xml_attr(&pair.left.edge_id),
            escape_xml_attr(&pair.right.edge_id)
        ));
        overlay.push_str(&overlay_line(&pair.left, color));
        overlay.push_str(&overlay_line(&pair.right, color));
        overlay.push_str(&format!(
            r##"<text x="{:.1}" y="{:.1}" fill="{}" font-size="28" font-weight="700" stroke="#ffffff" stroke-width="6" stroke-linejoin="round" paint-order="stroke">{} &lt;-&gt; {} ({:.1}px apart, {:.1}px overlap)</text>"##,
            pair.label_x(),
            pair.label_y(),
            color,
            escape_xml_text(&pair.left.edge_id),
            escape_xml_text(&pair.right.edge_id),
            pair.distance,
            pair.overlap
        ));
        overlay.push_str("</g>");
    }
    overlay.push_str("</g>");

    if let Some(index) = svg.rfind("</svg>") {
        let mut annotated = String::with_capacity(svg.len() + overlay.len());
        annotated.push_str(&svg[..index]);
        annotated.push_str(&overlay);
        annotated.push_str(&svg[index..]);
        annotated
    } else {
        format!("{svg}{overlay}")
    }
}

fn overlay_line(segment: &RouteSegment, color: &str) -> String {
    let (x1, y1, x2, y2) = segment_endpoints(segment);
    format!(
        r##"<line data-dediren-route-quality-edge="{}" x1="{:.1}" y1="{:.1}" x2="{:.1}" y2="{:.1}" stroke="{}" stroke-width="10" stroke-opacity="0.72" stroke-linecap="round" fill="none"/>"##,
        escape_xml_attr(&segment.edge_id),
        x1,
        y1,
        x2,
        y2,
        color
    )
}

fn close_parallel_route_pairs(layout_data: &Value) -> Vec<RoutePair> {
    let segments = route_segments(layout_data);
    let mut pairs = Vec::new();
    for (index, left) in segments.iter().enumerate() {
        for right in segments.iter().skip(index + 1) {
            if close_parallel_route_segments(left, right) {
                pairs.push(RoutePair {
                    left: left.clone(),
                    right: right.clone(),
                    distance: (left.fixed - right.fixed).abs(),
                    overlap: overlap_length(left.min, left.max, right.min, right.max),
                });
            }
        }
    }
    pairs
}

fn route_segments(layout_data: &Value) -> Vec<RouteSegment> {
    let edges = layout_data["edges"]
        .as_array()
        .expect("laid out edges should be an array");
    let mut segments = Vec::new();
    for (edge_index, edge) in edges.iter().enumerate() {
        let edge_id = edge["id"]
            .as_str()
            .unwrap_or_else(|| panic!("laid out edge id should be a string: {edge}"));
        let source = edge["source"]
            .as_str()
            .unwrap_or_else(|| panic!("{edge_id} source should be a string"));
        let target = edge["target"]
            .as_str()
            .unwrap_or_else(|| panic!("{edge_id} target should be a string"));
        let points = edge["points"]
            .as_array()
            .unwrap_or_else(|| panic!("{edge_id} points should be an array"));
        for segment in points.windows(2) {
            if let Some(route_segment) = route_segment(
                edge_index,
                edge_id,
                source,
                target,
                &segment[0],
                &segment[1],
            ) {
                segments.push(route_segment);
            }
        }
    }
    segments
}

fn route_segment(
    edge_index: usize,
    edge_id: &str,
    source: &str,
    target: &str,
    start: &Value,
    end: &Value,
) -> Option<RouteSegment> {
    let orientation = route_orientation(start, end)?;
    let start_x = point_coordinate(start, "x");
    let start_y = point_coordinate(start, "y");
    let end_x = point_coordinate(end, "x");
    let end_y = point_coordinate(end, "y");
    let (fixed, min, max) = match orientation {
        RouteOrientation::Horizontal => (start_y, start_x.min(end_x), start_x.max(end_x)),
        RouteOrientation::Vertical => (start_x, start_y.min(end_y), start_y.max(end_y)),
    };
    Some(RouteSegment {
        edge_index,
        edge_id: edge_id.to_string(),
        source: source.to_string(),
        target: target.to_string(),
        orientation,
        fixed,
        min,
        max,
    })
}

#[derive(Clone)]
struct RouteSegment {
    edge_index: usize,
    edge_id: String,
    source: String,
    target: String,
    orientation: RouteOrientation,
    fixed: f64,
    min: f64,
    max: f64,
}

struct RoutePair {
    left: RouteSegment,
    right: RouteSegment,
    distance: f64,
    overlap: f64,
}

impl RoutePair {
    fn label_x(&self) -> f64 {
        let (left_x1, _, left_x2, _) = segment_endpoints(&self.left);
        let (right_x1, _, right_x2, _) = segment_endpoints(&self.right);
        (left_x1 + left_x2 + right_x1 + right_x2) / 4.0
    }

    fn label_y(&self) -> f64 {
        let (_, left_y1, _, left_y2) = segment_endpoints(&self.left);
        let (_, right_y1, _, right_y2) = segment_endpoints(&self.right);
        ((left_y1 + left_y2 + right_y1 + right_y2) / 4.0) - 16.0
    }
}

fn close_parallel_route_segments(left: &RouteSegment, right: &RouteSegment) -> bool {
    left.edge_index != right.edge_index
        && !share_endpoint(left, right)
        && left.orientation == right.orientation
        && (left.fixed - right.fixed).abs() < ROUTE_CLOSE_PARALLEL_DISTANCE
        && overlap_length(left.min, left.max, right.min, right.max)
            >= ROUTE_CLOSE_PARALLEL_MIN_OVERLAP
}

fn share_endpoint(left: &RouteSegment, right: &RouteSegment) -> bool {
    left.source == right.source
        || left.source == right.target
        || left.target == right.source
        || left.target == right.target
}

fn overlap_length(left_min: f64, left_max: f64, right_min: f64, right_max: f64) -> f64 {
    (left_max.min(right_max) - left_min.max(right_min)).max(0.0)
}

fn orientation_name(orientation: RouteOrientation) -> &'static str {
    match orientation {
        RouteOrientation::Horizontal => "horizontal",
        RouteOrientation::Vertical => "vertical",
    }
}

fn segment_endpoints(segment: &RouteSegment) -> (f64, f64, f64, f64) {
    match segment.orientation {
        RouteOrientation::Horizontal => (segment.min, segment.fixed, segment.max, segment.fixed),
        RouteOrientation::Vertical => (segment.fixed, segment.min, segment.fixed, segment.max),
    }
}

fn escape_xml_text(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn escape_xml_attr(text: &str) -> String {
    escape_xml_text(text)
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

fn assert_edges_have_at_most_corner_count(
    layout_data: &Value,
    edge_ids: &[&str],
    max_corners: usize,
) {
    let edges = layout_data["edges"]
        .as_array()
        .expect("laid out edges should be an array");
    for edge_id in edge_ids {
        let edge = edges
            .iter()
            .find(|edge| edge["id"] == *edge_id)
            .unwrap_or_else(|| panic!("expected laid out edge {edge_id}"));
        let points = edge["points"]
            .as_array()
            .unwrap_or_else(|| panic!("{edge_id} points should be an array"));
        let corners = corner_count(points);
        assert!(
            corners <= max_corners,
            "{edge_id} should have at most {max_corners} corners, got {corners}: {points:?}"
        );
    }
}

fn laid_out_node<'a>(layout_data: &'a Value, node_id: &str) -> &'a Value {
    layout_data["nodes"]
        .as_array()
        .expect("laid out nodes should be an array")
        .iter()
        .find(|node| node["id"] == node_id)
        .unwrap_or_else(|| panic!("expected laid out node {node_id}"))
}

fn assert_laid_out_node_min_size(
    layout_data: &Value,
    node_id: &str,
    min_width: f64,
    min_height: f64,
) {
    let node = laid_out_node(layout_data, node_id);
    let width = point_coordinate(node, "width");
    let height = point_coordinate(node, "height");
    assert!(
        width >= min_width,
        "{node_id} width should be >= {min_width}, got {width}"
    );
    assert!(
        height >= min_height,
        "{node_id} height should be >= {min_height}, got {height}"
    );
}

fn corner_count(points: &[Value]) -> usize {
    let mut corners = 0;
    let mut previous = None;
    for segment in points.windows(2) {
        if let Some(current) = route_orientation(&segment[0], &segment[1]) {
            if previous.is_some_and(|previous| previous != current) {
                corners += 1;
            }
            previous = Some(current);
        }
    }
    corners
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum RouteOrientation {
    Horizontal,
    Vertical,
}

fn route_orientation(start: &Value, end: &Value) -> Option<RouteOrientation> {
    let start_x = point_coordinate(start, "x");
    let start_y = point_coordinate(start, "y");
    let end_x = point_coordinate(end, "x");
    let end_y = point_coordinate(end, "y");
    if same_coordinate(start_y, end_y) && !same_coordinate(start_x, end_x) {
        return Some(RouteOrientation::Horizontal);
    }
    if same_coordinate(start_x, end_x) && !same_coordinate(start_y, end_y) {
        return Some(RouteOrientation::Vertical);
    }
    None
}

fn same_coordinate(left: f64, right: f64) -> bool {
    (left - right).abs() <= 0.001
}

fn point_coordinate(point: &Value, key: &str) -> f64 {
    point[key]
        .as_f64()
        .unwrap_or_else(|| panic!("point coordinate {key} should be a number: {point}"))
}

fn assert_complex_profile_svg(doc: &roxmltree::Document<'_>, svg: &str) {
    assert_svg_texts_include(
        doc,
        &[
            "Mobile App",
            "API Gateway",
            "Order Service",
            "Event Bus",
            "External Systems",
            "publishes events",
        ],
    );
    assert!(svg.contains("data-dediren-node-id=\"analytics-warehouse\""));
    assert!(svg.contains("data-dediren-node-id=\"reporting-ingestor\""));
    assert!(svg.contains("data-dediren-group-id=\"core-services\""));
    assert!(svg.contains("data-dediren-group-id=\"async-processing\""));
    assert!(svg.contains("data-dediren-group-id=\"data-platform\""));
    assert_reasonable_svg_aspect(svg, 7.0);
}

fn assert_archimate_node_shape(doc: &roxmltree::Document<'_>, node_id: &str, expected_shape: &str) {
    let node = semantic_group(doc, "data-dediren-node-id", node_id);
    assert!(
        node.descendants()
            .any(|child| child.attribute("data-dediren-node-shape") == Some(expected_shape)),
        "{node_id} should render node shape {expected_shape}"
    );
}

fn assert_uml_node_decorator(doc: &roxmltree::Document<'_>, node_id: &str, decorator: &str) {
    let node = semantic_group(doc, "data-dediren-node-id", node_id);
    assert!(
        child_group_with_attr(node, "data-dediren-node-decorator", decorator).is_some(),
        "expected {node_id} to render UML decorator {decorator}"
    );
}

fn assert_edge_marker_start(doc: &roxmltree::Document<'_>, edge_id: &str, marker_kind: &str) {
    let edge = semantic_group(doc, "data-dediren-edge-id", edge_id);
    let path = child_element(edge, "path");
    let marker_id = format!("marker-start-{edge_id}");
    let marker_ref = format!("url(#{marker_id})");
    assert_eq!(
        path.attribute("marker-start"),
        Some(marker_ref.as_str()),
        "{edge_id} marker reference"
    );
    let marker = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("marker") && node.attribute("id") == Some(marker_id.as_str())
        })
        .unwrap_or_else(|| panic!("expected marker {marker_id}"));
    assert_eq!(
        marker.attribute("data-dediren-edge-marker-start"),
        Some(marker_kind),
        "{edge_id} marker kind"
    );
}

fn assert_edge_marker_end(doc: &roxmltree::Document<'_>, edge_id: &str, marker_kind: &str) {
    let edge = semantic_group(doc, "data-dediren-edge-id", edge_id);
    let path = child_element(edge, "path");
    let marker_id = format!("marker-end-{edge_id}");
    let marker_ref = format!("url(#{marker_id})");
    assert_eq!(
        path.attribute("marker-end"),
        Some(marker_ref.as_str()),
        "{edge_id} marker reference"
    );
    let marker = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("marker") && node.attribute("id") == Some(marker_id.as_str())
        })
        .unwrap_or_else(|| panic!("expected marker {marker_id}"));
    assert_eq!(
        marker.attribute("data-dediren-edge-marker-end"),
        Some(marker_kind),
        "{edge_id} marker kind"
    );
}

fn assert_archimate_edge_notation(
    doc: &roxmltree::Document<'_>,
    edge_id: &str,
    expected_dasharray: Option<&str>,
    expected_marker_end: Option<&str>,
) {
    let edge = semantic_group(doc, "data-dediren-edge-id", edge_id);
    let path = child_element(edge, "path");
    assert_eq!(
        path.attribute("stroke-dasharray"),
        expected_dasharray,
        "{edge_id} dash pattern"
    );
    match expected_marker_end {
        Some(marker_kind) => {
            let marker_id = format!("marker-end-{edge_id}");
            let marker_ref = format!("url(#{marker_id})");
            assert_eq!(
                path.attribute("marker-end"),
                Some(marker_ref.as_str()),
                "{edge_id} marker reference"
            );
            let marker = doc
                .descendants()
                .find(|node| {
                    node.has_tag_name("marker") && node.attribute("id") == Some(marker_id.as_str())
                })
                .unwrap_or_else(|| panic!("expected marker {marker_id}"));
            assert_eq!(
                marker.attribute("data-dediren-edge-marker-end"),
                Some(marker_kind),
                "{edge_id} marker kind"
            );
        }
        None => assert_eq!(path.attribute("marker-end"), None, "{edge_id} marker"),
    }
}

fn assert_layout_request_relationship_types_match_render_metadata(
    request: &Value,
    metadata: &Value,
) {
    let request_edges = request["edges"]
        .as_array()
        .expect("layout request edges should be an array");
    let metadata_edges = metadata["edges"]
        .as_object()
        .expect("render metadata edges should be an object");
    for edge in request_edges {
        let edge_id = edge["id"]
            .as_str()
            .expect("layout request edge id should be a string");
        let expected_type = metadata_edges
            .get(edge_id)
            .and_then(|edge| edge["type"].as_str())
            .unwrap_or_else(|| panic!("{edge_id} should have render metadata type"));
        assert_eq!(
            edge["relationship_type"].as_str(),
            Some(expected_type),
            "{edge_id} layout relationship_type should match ArchiMate render metadata"
        );
    }
}

fn apply_layout_request_relationship_types_from_render_metadata(
    request: &mut Value,
    metadata: &Value,
) {
    let metadata_edges = metadata["edges"]
        .as_object()
        .expect("render metadata edges should be an object");
    let request_edges = request["edges"]
        .as_array_mut()
        .expect("layout request edges should be an array");
    for edge in request_edges {
        let edge_id = edge["id"]
            .as_str()
            .expect("layout request edge id should be a string");
        let relationship_type = metadata_edges
            .get(edge_id)
            .and_then(|edge| edge["type"].as_str())
            .unwrap_or_else(|| panic!("{edge_id} should have render metadata type"));
        edge["relationship_type"] = Value::String(relationship_type.to_string());
    }
}

fn complex_archimate_render_metadata() -> Value {
    let node_types = [
        ("customer-mobile", "BusinessActor"),
        ("customer-web", "BusinessActor"),
        ("support-agent", "BusinessActor"),
        ("cdn", "TechnologyService"),
        ("web-frontend", "ApplicationComponent"),
        ("admin-portal", "ApplicationComponent"),
        ("api-gateway", "ApplicationInterface"),
        ("identity-service", "ApplicationService"),
        ("catalog-service", "ApplicationService"),
        ("pricing-service", "ApplicationService"),
        ("order-service", "ApplicationService"),
        ("gateway-and-junction", "AndJunction"),
        ("payment-service", "ApplicationService"),
        ("fulfillment-service", "ApplicationService"),
        ("notification-service", "ApplicationService"),
        ("event-bus", "ApplicationComponent"),
        ("event-dispatch-or-junction", "OrJunction"),
        ("order-worker", "ApplicationProcess"),
        ("warehouse-adapter", "ApplicationComponent"),
        ("email-worker", "ApplicationProcess"),
        ("reporting-ingestor", "ApplicationProcess"),
        ("session-cache", "DataObject"),
        ("product-db", "DataObject"),
        ("order-db", "DataObject"),
        ("payment-ledger", "DataObject"),
        ("warehouse-db", "DataObject"),
        ("analytics-warehouse", "DataObject"),
        ("identity-provider", "ApplicationService"),
        ("payment-provider", "BusinessService"),
        ("carrier-api", "TechnologyService"),
        ("email-provider", "TechnologyService"),
        ("erp", "ApplicationComponent"),
    ];
    let edge_types = [
        ("mobile-enters-cdn", "Association"),
        ("web-enters-cdn", "Association"),
        ("support-opens-admin", "Association"),
        ("cdn-serves-web", "Association"),
        ("web-calls-gateway", "Association"),
        ("admin-calls-gateway", "Association"),
        ("gateway-to-and-junction", "Association"),
        ("and-junction-authenticates", "Association"),
        ("and-junction-queries-catalog", "Association"),
        ("and-junction-prices-cart", "Association"),
        ("and-junction-places-order", "Association"),
        ("identity-federates", "Flow"),
        ("identity-caches-session", "Access"),
        ("catalog-reads-products", "Access"),
        ("pricing-reads-products", "Access"),
        ("pricing-caches-quotes", "Access"),
        ("order-checks-catalog", "Flow"),
        ("order-requests-payment", "Flow"),
        ("order-reserves-stock", "Flow"),
        ("order-writes-orders", "Access"),
        ("order-publishes-events", "Triggering"),
        ("payment-authorizes", "Association"),
        ("payment-records-ledger", "Access"),
        ("fulfillment-ships", "Association"),
        ("fulfillment-syncs-warehouse", "Serving"),
        ("fulfillment-writes-warehouse", "Access"),
        ("fulfillment-publishes-events", "Triggering"),
        ("event-bus-to-or-junction", "Triggering"),
        ("event-bus-drives-order-worker", "Triggering"),
        ("or-junction-drives-email-worker", "Triggering"),
        ("or-junction-drives-reporting", "Triggering"),
        ("order-worker-reads-orders", "Access"),
        ("order-worker-syncs-erp", "Association"),
        ("warehouse-adapter-syncs-erp", "Association"),
        ("warehouse-adapter-writes-db", "Access"),
        ("email-worker-notifies", "Realization"),
        ("notification-sends-email", "Association"),
        ("reporting-reads-orders", "Access"),
        ("reporting-writes-analytics", "Access"),
    ];
    let nodes = node_types
        .into_iter()
        .map(|(id, selector_type)| (id.to_string(), archimate_selector(selector_type, id)))
        .collect::<serde_json::Map<String, Value>>();
    let edges = edge_types
        .into_iter()
        .map(|(id, selector_type)| (id.to_string(), archimate_selector(selector_type, id)))
        .collect::<serde_json::Map<String, Value>>();

    serde_json::json!({
        "render_metadata_schema_version": "render-metadata.schema.v1",
        "semantic_profile": "archimate",
        "nodes": nodes,
        "edges": edges
    })
}

fn archimate_selector(selector_type: &str, source_id: &str) -> Value {
    serde_json::json!({
        "type": selector_type,
        "source_id": source_id
    })
}

fn render_svg(layout_result: &Path, policy_fixture: &str, metadata: Option<&Path>) -> String {
    let mut command = common::dediren_command();
    command
        .env(
            "DEDIREN_PLUGIN_SVG_RENDER",
            plugin_binary("dediren-plugin-svg-render"),
        )
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file(policy_fixture));
    if let Some(metadata) = metadata {
        command.arg("--metadata").arg(metadata);
    }
    let output = command
        .arg("--input")
        .arg(layout_result)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let data = ok_data(&output);
    data["content"]
        .as_str()
        .expect("render output should contain SVG content")
        .to_string()
}

fn write_temp_bytes(temp: &assert_fs::TempDir, name: &str, content: &[u8]) -> PathBuf {
    let child = temp.child(name);
    child.write_binary(content).unwrap();
    child.path().to_path_buf()
}

fn write_temp_json(temp: &assert_fs::TempDir, name: &str, content: &Value) -> PathBuf {
    let child = temp.child(name);
    child.write_str(&content.to_string()).unwrap();
    child.path().to_path_buf()
}
