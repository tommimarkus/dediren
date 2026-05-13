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
    let request = serde_json::json!({
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
            { "id": "payment-service", "label": "Payment Service", "source_id": "payment-service", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "fulfillment-service", "label": "Fulfillment Service", "source_id": "fulfillment-service", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "notification-service", "label": "Notification Service", "source_id": "notification-service", "width_hint": 160.0, "height_hint": 80.0 },
            { "id": "event-bus", "label": "Event Bus", "source_id": "event-bus", "width_hint": 160.0, "height_hint": 80.0 },
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
            { "id": "gateway-authenticates", "source": "api-gateway", "target": "identity-service", "label": "authenticates", "source_id": "gateway-authenticates" },
            { "id": "gateway-queries-catalog", "source": "api-gateway", "target": "catalog-service", "label": "queries catalog", "source_id": "gateway-queries-catalog" },
            { "id": "gateway-prices-cart", "source": "api-gateway", "target": "pricing-service", "label": "prices cart", "source_id": "gateway-prices-cart" },
            { "id": "gateway-places-order", "source": "api-gateway", "target": "order-service", "label": "places order", "source_id": "gateway-places-order" },
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
            { "id": "event-bus-drives-order-worker", "source": "event-bus", "target": "order-worker", "label": "dispatches", "source_id": "event-bus-drives-order-worker" },
            { "id": "event-bus-drives-email-worker", "source": "event-bus", "target": "email-worker", "label": "dispatches", "source_id": "event-bus-drives-email-worker" },
            { "id": "event-bus-drives-reporting", "source": "event-bus", "target": "reporting-ingestor", "label": "streams", "source_id": "event-bus-drives-reporting" },
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
                "members": ["event-bus", "order-worker", "warehouse-adapter", "email-worker", "reporting-ingestor"],
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
    let request = write_temp_json(&temp, "complex-multi-layer-layout-request.json", &request);

    let layout_output = real_elk_layout(&request);
    let layout = write_temp_bytes(
        &temp,
        "complex-multi-layer-layout-result.json",
        &layout_output,
    );
    assert_complex_layout_quality_bounded(&validate_layout(&layout));

    let layout_data = ok_data(&layout_output);
    assert_eq!(
        layout_data["nodes"]
            .as_array()
            .expect("laid out nodes should be an array")
            .len(),
        30
    );
    assert_eq!(
        layout_data["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .len(),
        37
    );
    assert_eq!(
        layout_data["groups"]
            .as_array()
            .expect("laid out groups should be an array")
            .len(),
        6
    );

    let default_svg = render_svg(&layout, "fixtures/render-policy/default-svg.json", None);
    let default_doc = svg_doc(&default_svg);
    assert_complex_profile_svg(&default_doc, &default_svg);
    write_render_artifact(
        "real-elk",
        "real_elk_renders_complex_multi_layer_system_default",
        &default_svg,
    );

    let rich_svg = render_svg(&layout, "fixtures/render-policy/rich-svg.json", None);
    let rich_doc = svg_doc(&rich_svg);
    assert_complex_profile_svg(&rich_doc, &rich_svg);
    write_render_artifact(
        "real-elk",
        "real_elk_renders_complex_multi_layer_system_rich",
        &rich_svg,
    );

    let metadata = complex_archimate_render_metadata();
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
    assert_eq!(metadata["edges"]["order-requests-payment"]["type"], "Flow");
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

fn assert_complex_layout_quality_bounded(quality: &Value) {
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
        quality["route_close_parallel_count"], 0,
        "complex layout should keep parallel route channels readable: {quality}"
    );
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
        ("payment-service", "ApplicationService"),
        ("fulfillment-service", "ApplicationService"),
        ("notification-service", "ApplicationService"),
        ("event-bus", "ApplicationComponent"),
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
        ("gateway-authenticates", "Association"),
        ("gateway-queries-catalog", "Association"),
        ("gateway-prices-cart", "Association"),
        ("gateway-places-order", "Association"),
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
        ("event-bus-drives-order-worker", "Association"),
        ("event-bus-drives-email-worker", "Association"),
        ("event-bus-drives-reporting", "Association"),
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
