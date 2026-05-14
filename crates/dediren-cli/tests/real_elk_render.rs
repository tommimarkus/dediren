mod common;

use assert_fs::prelude::*;
use common::{
    assert_reasonable_svg_aspect, assert_svg_texts_include, child_element, child_group_with_attr,
    ok_data, plugin_binary, semantic_group, svg_doc, workspace_file, write_render_artifact,
};
use serde_json::Value;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};

static REAL_ELK_LOCK: Mutex<()> = Mutex::new(());
const ROUTE_CLOSE_PARALLEL_DISTANCE: f64 = 20.0;
const ROUTE_CLOSE_PARALLEL_MIN_OVERLAP: f64 = 40.0;

#[test]
#[ignore = "run with --ignored after building the ELK Java helper; serialize real ELK runs"]
fn real_elk_renders_basic_projected_graph() {
    let _guard = real_elk_guard();
    let temp = assert_fs::TempDir::new().unwrap();

    let request_output = project_layout_request("fixtures/source/valid-basic.json");
    let request = write_temp_bytes(&temp, "basic-layout-request.json", &request_output);

    let layout_output = real_elk_layout(&request);
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
    write_render_artifact("real-elk", "real_elk_renders_basic_projected_graph", &svg);
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
#[ignore = "run with --ignored after building the ELK Java helper; serialize real ELK runs"]
fn real_elk_renders_grouped_rich_graph() {
    let _guard = real_elk_guard();
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

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "grouped-rich-layout-result.json", &layout_output);
    assert_layout_quality_ok(&validate_layout(&layout));

    let layout_data = ok_data(&layout_output);
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
            "authorizes payment",
        ],
    );
    assert!(svg.contains("data-dediren-group-id=\"application-services\""));
    assert!(svg.contains("data-dediren-group-id=\"external-dependencies\""));
    assert!(svg.contains("data-dediren-node-id=\"worker\""));
    assert!(svg.contains("data-dediren-node-id=\"payments\""));
    assert!(svg.contains(">Fulfillment</tspan>"));
    assert!(svg.contains(">Worker</tspan>"));
    assert!(svg.contains(">Payments</tspan>"));
    assert!(svg.contains(">Provider</tspan>"));
    assert_reasonable_svg_aspect(&svg, 4.2);
    write_render_artifact("real-elk", "real_elk_renders_grouped_rich_graph", &svg);
}

#[test]
#[ignore = "run with --ignored after building the ELK Java helper; serialize real ELK runs"]
fn real_elk_renders_archimate_metadata_notation() {
    let _guard = real_elk_guard();
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

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "archimate-layout-result.json", &layout_output);
    assert_layout_quality_ok(&validate_layout(&layout));

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
    assert_svg_texts_include(
        &doc,
        &[
            "Customer",
            "Orders API",
            "Payments Service",
            "publishes fulfillment",
        ],
    );
    assert!(svg.contains(">Fulfillment</tspan>"));
    assert!(svg.contains(">Worker</tspan>"));
    assert_reasonable_svg_aspect(&svg, 5.0);
    write_render_artifact(
        "real-elk",
        "real_elk_renders_archimate_metadata_notation",
        &svg,
    );
}

#[test]
#[ignore = "run with --ignored after building the ELK Java helper; serialize real ELK runs"]
fn real_elk_renders_cross_group_route_without_quality_warnings() {
    let _guard = real_elk_guard();
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

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(&temp, "cross-group-layout-result.json", &layout_output);
    assert_layout_quality_ok(&validate_layout(&layout));

    let layout_data = ok_data(&layout_output);
    assert!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .iter()
            .any(|edge| edge["id"] == "a-to-c"),
        "real helper output should preserve the cross-group edge"
    );

    let svg = render_svg(&layout, "fixtures/render-policy/default-svg.json", None);
    let doc = svg_doc(&svg);
    assert_svg_texts_include(&doc, &["A", "B", "C", "connects"]);
    assert_reasonable_svg_aspect(&svg, 3.6);
    write_render_artifact(
        "real-elk",
        "real_elk_renders_cross_group_route_without_quality_warnings",
        &svg,
    );
}

#[test]
#[ignore = "run with --ignored after building the ELK Java helper; serialize real ELK runs"]
fn real_elk_renders_complex_multi_layer_system() {
    let _guard = real_elk_guard();
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
        "edges": [
            { "id": "mobile-enters-cdn", "source": "customer-mobile", "target": "cdn", "label": "uses", "source_id": "mobile-enters-cdn" },
            { "id": "web-enters-cdn", "source": "customer-web", "target": "cdn", "label": "uses", "source_id": "web-enters-cdn" },
            { "id": "support-opens-admin", "source": "support-agent", "target": "admin-portal", "label": "manages", "source_id": "support-opens-admin" },
            { "id": "cdn-serves-web", "source": "cdn", "target": "web-frontend", "label": "serves", "source_id": "cdn-serves-web" },
            { "id": "web-calls-gateway", "source": "web-frontend", "target": "api-gateway", "label": "calls", "source_id": "web-calls-gateway" },
            { "id": "admin-calls-gateway", "source": "admin-portal", "target": "api-gateway", "label": "calls", "source_id": "admin-calls-gateway" },
            { "id": "gateway-to-and-junction", "source": "api-gateway", "target": "gateway-and-junction", "label": "routes", "source_id": "gateway-to-and-junction" },
            { "id": "and-junction-authenticates", "source": "gateway-and-junction", "target": "identity-service", "label": "authenticates", "source_id": "and-junction-authenticates", "relationship_type": "Association" },
            { "id": "and-junction-queries-catalog", "source": "gateway-and-junction", "target": "catalog-service", "label": "queries catalog", "source_id": "and-junction-queries-catalog", "relationship_type": "Association" },
            { "id": "and-junction-prices-cart", "source": "gateway-and-junction", "target": "pricing-service", "label": "prices cart", "source_id": "and-junction-prices-cart", "relationship_type": "Association" },
            { "id": "and-junction-places-order", "source": "gateway-and-junction", "target": "order-service", "label": "places order", "source_id": "and-junction-places-order", "relationship_type": "Association" },
            { "id": "identity-federates", "source": "identity-service", "target": "identity-provider", "label": "federates", "source_id": "identity-federates" },
            { "id": "identity-caches-session", "source": "identity-service", "target": "session-cache", "label": "caches session", "source_id": "identity-caches-session" },
            { "id": "catalog-reads-products", "source": "catalog-service", "target": "product-db", "label": "reads products", "source_id": "catalog-reads-products" },
            { "id": "pricing-reads-products", "source": "pricing-service", "target": "product-db", "label": "reads products", "source_id": "pricing-reads-products" },
            { "id": "pricing-caches-quotes", "source": "pricing-service", "target": "session-cache", "label": "caches quotes", "source_id": "pricing-caches-quotes" },
            { "id": "order-checks-catalog", "source": "order-service", "target": "catalog-service", "label": "checks catalog", "source_id": "order-checks-catalog" },
            { "id": "order-requests-payment", "source": "order-service", "target": "payment-service", "label": "requests payment", "source_id": "order-requests-payment" },
            { "id": "order-reserves-stock", "source": "order-service", "target": "fulfillment-service", "label": "reserves stock", "source_id": "order-reserves-stock" },
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
            { "id": "or-junction-drives-email-worker", "source": "event-dispatch-or-junction", "target": "email-worker", "label": "email event", "source_id": "or-junction-drives-email-worker" },
            { "id": "or-junction-drives-reporting", "source": "event-dispatch-or-junction", "target": "reporting-ingestor", "label": "reporting event", "source_id": "or-junction-drives-reporting" },
            { "id": "order-worker-reads-orders", "source": "order-worker", "target": "order-db", "label": "reads orders", "source_id": "order-worker-reads-orders" },
            { "id": "order-worker-syncs-erp", "source": "order-worker", "target": "erp", "label": "syncs orders", "source_id": "order-worker-syncs-erp" },
            { "id": "warehouse-adapter-syncs-erp", "source": "warehouse-adapter", "target": "erp", "label": "syncs stock", "source_id": "warehouse-adapter-syncs-erp" },
            { "id": "warehouse-adapter-writes-db", "source": "warehouse-adapter", "target": "warehouse-db", "label": "writes state", "source_id": "warehouse-adapter-writes-db" },
            { "id": "email-worker-notifies", "source": "email-worker", "target": "notification-service", "label": "notifies", "source_id": "email-worker-notifies" },
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

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(
        &temp,
        "complex-multi-layer-layout-result.json",
        &layout_output,
    );
    let layout_data = ok_data(&layout_output);
    let default_svg = render_svg(&layout, "fixtures/render-policy/default-svg.json", None);
    write_render_artifact(
        "real-elk",
        "real_elk_renders_complex_multi_layer_system_default",
        &default_svg,
    );
    let route_quality_artifact = write_close_parallel_route_artifact(
        &default_svg,
        &layout_data,
        "real_elk_renders_complex_multi_layer_system_route_quality",
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
        2,
    );
    assert_edges_include_routing_hint(
        &layout_data,
        &[
            "and-junction-authenticates",
            "and-junction-queries-catalog",
            "and-junction-prices-cart",
            "and-junction-places-order",
        ],
        "shared_source_junction",
    );
    assert_edges_have_at_most_corner_count(
        &layout_data,
        &["fulfillment-writes-warehouse", "payment-records-ledger"],
        4,
    );
    assert_edges_have_distinct_source_ports(
        &layout_data,
        "fulfillment-ships",
        "fulfillment-writes-warehouse",
    );
    assert_edge_does_not_intersect_unrelated_groups(&layout_data, "payment-authorizes");
    assert_no_route_crossing_near_source(
        &layout_data,
        "identity-service",
        "identity-federates",
        "identity-caches-session",
    );
    assert_no_route_crossing_near_source(
        &layout_data,
        "pricing-service",
        "pricing-reads-products",
        "pricing-caches-quotes",
    );
    assert_no_route_crossing_near_source_area(
        &layout_data,
        "event-bus",
        "event-bus-to-or-junction",
        "event-bus-drives-order-worker",
    );
    assert_no_route_crossing_near_source_area(
        &layout_data,
        "event-dispatch-or-junction",
        "or-junction-drives-email-worker",
        "or-junction-drives-reporting",
    );
    assert_source_port_right_of_node_center(
        &layout_data,
        "or-junction-drives-email-worker",
        "event-dispatch-or-junction",
    );
    assert_source_port_right_of_node_center(
        &layout_data,
        "or-junction-drives-reporting",
        "event-dispatch-or-junction",
    );
    assert_edges_have_at_most_corner_count(&layout_data, &["or-junction-drives-email-worker"], 1);
    assert_junction_between_x(
        &layout_data,
        "gateway-and-junction",
        &["api-gateway"],
        &[
            "identity-service",
            "catalog-service",
            "pricing-service",
            "order-service",
        ],
    );
    assert_junction_between_x(
        &layout_data,
        "event-dispatch-or-junction",
        &["event-bus"],
        &["email-worker", "reporting-ingestor"],
    );

    let default_doc = svg_doc(&default_svg);
    assert_complex_profile_svg(&default_doc, &default_svg);

    let rich_svg = render_svg(&layout, "fixtures/render-policy/rich-svg.json", None);
    let rich_doc = svg_doc(&rich_svg);
    assert_complex_profile_svg(&rich_doc, &rich_svg);
    write_render_artifact(
        "real-elk",
        "real_elk_renders_complex_multi_layer_system_rich",
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
        "real-elk",
        "real_elk_renders_complex_multi_layer_system_archimate",
        &archimate_svg,
    );
}

fn real_elk_guard() -> MutexGuard<'static, ()> {
    REAL_ELK_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn project_layout_request(source_fixture: &str) -> Vec<u8> {
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
            "main",
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
            "main",
            "--input",
        ])
        .arg(workspace_file(source_fixture))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone()
}

fn real_elk_layout(input: &Path) -> Vec<u8> {
    common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_ELK_LAYOUT",
            plugin_binary("dediren-plugin-elk-layout"),
        )
        .env(
            "DEDIREN_ELK_COMMAND",
            workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh"),
        )
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
    assert_eq!(quality["overlap_count"], 0, "layout quality: {quality}");
    assert_eq!(
        quality["connector_through_node_count"], 0,
        "layout quality: {quality}"
    );
    assert_eq!(
        quality["route_detour_count"], 0,
        "layout quality: {quality}"
    );
    assert_eq!(
        quality["route_close_parallel_count"], 0,
        "layout quality: {quality}"
    );
    assert_eq!(
        quality["invalid_route_count"], 0,
        "layout quality: {quality}"
    );
    assert_eq!(
        quality["group_boundary_issue_count"], 0,
        "layout quality: {quality}"
    );
    assert_eq!(quality["warning_count"], 0, "layout quality: {quality}");
}

fn assert_complex_layout_quality_bounded(
    quality: &Value,
    layout_data: &Value,
    route_quality_artifact: Option<&Path>,
) {
    assert_eq!(quality["overlap_count"], 0, "layout quality: {quality}");
    assert_eq!(
        quality["connector_through_node_count"], 0,
        "layout quality: {quality}"
    );
    assert_eq!(
        quality["invalid_route_count"], 0,
        "layout quality: {quality}"
    );
    assert_eq!(
        quality["group_boundary_issue_count"], 0,
        "layout quality: {quality}"
    );
    assert_eq!(quality["warning_count"], 0, "layout quality: {quality}");
    assert!(
        quality["route_detour_count"].as_u64().unwrap_or(u64::MAX) <= 4,
        "complex layout should keep route detours bounded: {quality}"
    );
    assert_eq!(
        quality["route_close_parallel_count"],
        0,
        "complex layout should keep parallel route channels readable: {quality}{}{}",
        close_parallel_route_details(layout_data),
        route_quality_artifact
            .map(|path| format!("\nannotated route-quality SVG: {}", artifact_path(path)))
            .unwrap_or_default()
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
        return Some(write_render_artifact("real-elk", test_name, &annotated));
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

fn assert_no_route_crossing_near_source(
    layout_data: &Value,
    source_id: &str,
    left_edge_id: &str,
    right_edge_id: &str,
) {
    let source = laid_out_node(layout_data, source_id);
    let source_right = point_coordinate(source, "x") + point_coordinate(source, "width");
    let near_source_right = source_right + 220.0;
    let segments = route_segments(layout_data);
    let mut crossings = Vec::new();
    for left in segments
        .iter()
        .filter(|segment| segment.edge_id == left_edge_id)
    {
        for right in segments
            .iter()
            .filter(|segment| segment.edge_id == right_edge_id)
        {
            if let Some((crossing_x, crossing_y)) = segment_crossing(left, right) {
                if crossing_x >= source_right && crossing_x <= near_source_right {
                    crossings.push(format!(
                        "{} {} {:?} crosses {} {} {:?} at ({crossing_x:.1}, {crossing_y:.1})",
                        left.edge_id,
                        orientation_name(left.orientation),
                        segment_endpoints(left),
                        right.edge_id,
                        orientation_name(right.orientation),
                        segment_endpoints(right)
                    ));
                }
            }
        }
    }
    assert!(
        crossings.is_empty(),
        "routes from {source_id} should not cross near the source:\n{}",
        crossings.join("\n")
    );
}

fn assert_no_route_crossing_near_source_area(
    layout_data: &Value,
    source_id: &str,
    left_edge_id: &str,
    right_edge_id: &str,
) {
    let source = laid_out_node(layout_data, source_id);
    let source_left = point_coordinate(source, "x");
    let source_right = source_left + point_coordinate(source, "width");
    let source_top = point_coordinate(source, "y");
    let source_bottom = source_top + point_coordinate(source, "height");
    let near_left = source_left - 80.0;
    let near_right = source_right + 220.0;
    let near_top = source_top - 120.0;
    let near_bottom = source_bottom + 220.0;
    let segments = route_segments(layout_data);
    let mut crossings = Vec::new();
    for left in segments
        .iter()
        .filter(|segment| segment.edge_id == left_edge_id)
    {
        for right in segments
            .iter()
            .filter(|segment| segment.edge_id == right_edge_id)
        {
            if let Some((crossing_x, crossing_y)) = segment_crossing(left, right) {
                if crossing_x >= near_left
                    && crossing_x <= near_right
                    && crossing_y >= near_top
                    && crossing_y <= near_bottom
                {
                    crossings.push(format!(
                        "{} {} {:?} crosses {} {} {:?} at ({crossing_x:.1}, {crossing_y:.1})",
                        left.edge_id,
                        orientation_name(left.orientation),
                        segment_endpoints(left),
                        right.edge_id,
                        orientation_name(right.orientation),
                        segment_endpoints(right)
                    ));
                }
            }
        }
    }
    assert!(
        crossings.is_empty(),
        "routes from {source_id} should not cross near the source area:\n{}",
        crossings.join("\n")
    );
}

fn segment_crossing(left: &RouteSegment, right: &RouteSegment) -> Option<(f64, f64)> {
    if left.orientation == right.orientation {
        return None;
    }
    let (horizontal, vertical) = if left.orientation == RouteOrientation::Horizontal {
        (left, right)
    } else {
        (right, left)
    };
    if vertical.fixed > horizontal.min
        && vertical.fixed < horizontal.max
        && horizontal.fixed > vertical.min
        && horizontal.fixed < vertical.max
    {
        return Some((vertical.fixed, horizontal.fixed));
    }
    None
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

fn assert_edges_include_routing_hint(layout_data: &Value, edge_ids: &[&str], hint: &str) {
    let edges = layout_data["edges"]
        .as_array()
        .expect("laid out edges should be an array");
    for edge_id in edge_ids {
        let edge = edges
            .iter()
            .find(|edge| edge["id"] == *edge_id)
            .unwrap_or_else(|| panic!("expected laid out edge {edge_id}"));
        let hints = edge["routing_hints"]
            .as_array()
            .unwrap_or_else(|| panic!("{edge_id} routing_hints should be an array"));
        assert!(
            hints.iter().any(|candidate| candidate == hint),
            "{edge_id} should include routing hint {hint}, got {hints:?}"
        );
    }
}

fn assert_edges_have_distinct_source_ports(
    layout_data: &Value,
    left_edge_id: &str,
    right_edge_id: &str,
) {
    let left = laid_out_edge(layout_data, left_edge_id);
    let right = laid_out_edge(layout_data, right_edge_id);
    let left_points = left["points"]
        .as_array()
        .unwrap_or_else(|| panic!("{left_edge_id} points should be an array"));
    let right_points = right["points"]
        .as_array()
        .unwrap_or_else(|| panic!("{right_edge_id} points should be an array"));
    let left_source = &left_points[0];
    let right_source = &right_points[0];
    let same_source_port = same_coordinate(
        point_coordinate(left_source, "x"),
        point_coordinate(right_source, "x"),
    ) && same_coordinate(
        point_coordinate(left_source, "y"),
        point_coordinate(right_source, "y"),
    );
    assert!(
        !same_source_port,
        "{left_edge_id} and {right_edge_id} should not share a source port: left={left_source:?}, right={right_source:?}"
    );
}

fn assert_edge_does_not_intersect_unrelated_groups(layout_data: &Value, edge_id: &str) {
    let edge = laid_out_edge(layout_data, edge_id);
    let points = edge["points"]
        .as_array()
        .unwrap_or_else(|| panic!("{edge_id} points should be an array"));
    for segment in points.windows(2) {
        for group in layout_data["groups"]
            .as_array()
            .expect("laid out groups should be an array")
        {
            if group_contains_endpoint(group, edge["source"].as_str())
                || group_contains_endpoint(group, edge["target"].as_str())
            {
                continue;
            }
            assert!(
                !segment_intersects_group(&segment[0], &segment[1], group),
                "{edge_id} should not route through unrelated group {}: segment={segment:?}, group={group:?}",
                group["id"]
            );
        }
    }
}

fn group_contains_endpoint(group: &Value, endpoint: Option<&str>) -> bool {
    endpoint.is_some_and(|endpoint| {
        group["members"]
            .as_array()
            .expect("group members should be an array")
            .iter()
            .any(|member| member == endpoint)
    })
}

fn segment_intersects_group(start: &Value, end: &Value, group: &Value) -> bool {
    rectangles_overlap(
        point_coordinate(start, "x").min(point_coordinate(end, "x")),
        point_coordinate(start, "y").min(point_coordinate(end, "y")),
        (point_coordinate(start, "x") - point_coordinate(end, "x"))
            .abs()
            .max(1.0),
        (point_coordinate(start, "y") - point_coordinate(end, "y"))
            .abs()
            .max(1.0),
        point_coordinate(group, "x"),
        point_coordinate(group, "y"),
        point_coordinate(group, "width"),
        point_coordinate(group, "height"),
    )
}

fn rectangles_overlap(
    left_x: f64,
    left_y: f64,
    left_width: f64,
    left_height: f64,
    right_x: f64,
    right_y: f64,
    right_width: f64,
    right_height: f64,
) -> bool {
    left_x < right_x + right_width
        && left_x + left_width > right_x
        && left_y < right_y + right_height
        && left_y + left_height > right_y
}

fn assert_junction_between_x(
    layout_data: &Value,
    junction_id: &str,
    source_ids: &[&str],
    target_ids: &[&str],
) {
    let junction_x = node_center_x(layout_data, junction_id);
    let source_x = source_ids
        .iter()
        .map(|node_id| node_center_x(layout_data, node_id))
        .fold(f64::NEG_INFINITY, f64::max);
    let target_x = target_ids
        .iter()
        .map(|node_id| node_center_x(layout_data, node_id))
        .fold(f64::INFINITY, f64::min);
    assert!(
        source_x < junction_x && junction_x < target_x,
        "{junction_id} should sit between source side x={source_x} and target side x={target_x}, got x={junction_x}"
    );
}

fn assert_source_port_right_of_node_center(layout_data: &Value, edge_id: &str, node_id: &str) {
    let edge = laid_out_edge(layout_data, edge_id);
    let points = edge["points"]
        .as_array()
        .unwrap_or_else(|| panic!("{edge_id} points should be an array"));
    let source_port_x = point_coordinate(&points[0], "x");
    let node_center = node_center_x(layout_data, node_id);
    assert!(
        source_port_x > node_center,
        "{edge_id} should leave {node_id} from the right side, got source x={source_port_x}, node center x={node_center}"
    );
}

fn node_center_x(layout_data: &Value, node_id: &str) -> f64 {
    let node = laid_out_node(layout_data, node_id);
    point_coordinate(node, "x") + (point_coordinate(node, "width") / 2.0)
}

fn laid_out_edge<'a>(layout_data: &'a Value, edge_id: &str) -> &'a Value {
    layout_data["edges"]
        .as_array()
        .expect("laid out edges should be an array")
        .iter()
        .find(|edge| edge["id"] == edge_id)
        .unwrap_or_else(|| panic!("expected laid out edge {edge_id}"))
}

fn laid_out_node<'a>(layout_data: &'a Value, node_id: &str) -> &'a Value {
    layout_data["nodes"]
        .as_array()
        .expect("laid out nodes should be an array")
        .iter()
        .find(|node| node["id"] == node_id)
        .unwrap_or_else(|| panic!("expected laid out node {node_id}"))
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
    assert_reasonable_svg_aspect(svg, 6.5);
}

fn assert_archimate_node_shape(doc: &roxmltree::Document<'_>, node_id: &str, expected_shape: &str) {
    let node = semantic_group(doc, "data-dediren-node-id", node_id);
    assert!(
        node.descendants()
            .any(|child| child.attribute("data-dediren-node-shape") == Some(expected_shape)),
        "{node_id} should render node shape {expected_shape}"
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
