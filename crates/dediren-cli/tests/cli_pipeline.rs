mod common;

use assert_fs::prelude::*;
use common::{
    assert_reasonable_svg_aspect, assert_svg_texts_include, child_element, child_group_with_attr,
    ok_data, plugin_binary, semantic_group, svg_doc, workspace_file, write_render_artifact,
};
use serde_json::Value;

#[test]
fn fixture_pipeline_produces_svg_and_oef() {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = temp.child("request.json");
    let result = temp.child("result.json");
    let svg = temp.child("diagram.svg");
    let generic_plugin = plugin_binary("dediren-plugin-generic-graph");
    let elk_plugin = plugin_binary("dediren-plugin-elk-layout");
    let svg_plugin = plugin_binary("dediren-plugin-svg-render");
    let oef_plugin = plugin_binary("dediren-plugin-archimate-oef-export");
    let elk_fixture = workspace_file("fixtures/layout-result/pipeline-rich.json");

    let project_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
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
        .arg(workspace_file("fixtures/source/valid-pipeline-rich.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    request.write_binary(&project_output).unwrap();
    let project_data = ok_data(&project_output);
    assert_eq!(
        project_data["nodes"]
            .as_array()
            .expect("projected nodes should be an array")
            .len(),
        6
    );
    assert_eq!(
        project_data["edges"]
            .as_array()
            .expect("projected edges should be an array")
            .len(),
        6
    );

    let layout_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", &elk_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .env("DEDIREN_ELK_RESULT_FIXTURE", elk_fixture)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    result.write_binary(&layout_output).unwrap();

    common::dediren_command()
        .args(["validate-layout", "--input"])
        .arg(result.path())
        .assert()
        .success();

    let render_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_SVG_RENDER", &svg_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/default-svg.json"))
        .arg("--input")
        .arg(result.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let render_data = ok_data(&render_output);
    let svg_content = render_data["content"].as_str().unwrap();
    svg.write_str(svg_content).unwrap();
    write_render_artifact(
        "fixture-pipeline",
        "fixture_pipeline_produces_svg_and_oef",
        svg_content,
    );

    let svg_text = std::fs::read_to_string(svg.path()).unwrap();
    let doc = svg_doc(&svg_text);
    assert!(svg_text.contains("<svg"));
    assert_svg_texts_include(
        &doc,
        &[
            "Client",
            "Web App",
            "Orders API",
            "PostgreSQL",
            "Payments Provider",
            "Application Services",
            "submits order",
            "authorizes payment",
        ],
    );
    assert!(svg_text.contains("data-dediren-group-id=\"application-services\""));
    assert_reasonable_svg_aspect(&svg_text, 2.8);

    let export_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_ARCHIMATE_OEF", &oef_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["export", "--plugin", "archimate-oef", "--policy"])
        .arg(workspace_file("fixtures/export-policy/default-oef.json"))
        .arg("--source")
        .arg(workspace_file("fixtures/source/valid-archimate-oef.json"))
        .arg("--layout")
        .arg(workspace_file(
            "fixtures/layout-result/archimate-oef-basic.json",
        ))
        .output()
        .unwrap();
    assert!(export_output.status.success());

    let export_envelope: serde_json::Value = serde_json::from_slice(&export_output.stdout).unwrap();
    assert_eq!(export_envelope["status"], "ok");
    assert_eq!(
        export_envelope["data"]["artifact_kind"],
        "archimate-oef+xml"
    );
    assert!(export_envelope["data"]["content"]
        .as_str()
        .unwrap()
        .contains("xsi:type=\"Diagram\""));
}

#[test]
fn fixture_mode_uml_pipeline_renders_and_exports() {
    let generic_plugin = plugin_binary("dediren-plugin-generic-graph");
    let svg_plugin = plugin_binary("dediren-plugin-svg-render");
    let xmi_plugin = plugin_binary("dediren-plugin-uml-xmi-export");
    let source = workspace_file("fixtures/source/valid-uml-basic.json");

    let validate = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .args([
            "validate",
            "--plugin",
            "generic-graph",
            "--profile",
            "uml",
            "--input",
        ])
        .arg(&source)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    assert_eq!(ok_data(&validate)["semantic_profile"], "uml");

    let layout_request = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .args([
            "project",
            "--target",
            "layout-request",
            "--plugin",
            "generic-graph",
            "--view",
            "class-view",
            "--input",
        ])
        .arg(&source)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    assert_eq!(ok_data(&layout_request)["view_id"], "class-view");

    let metadata = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .args([
            "project",
            "--target",
            "render-metadata",
            "--plugin",
            "generic-graph",
            "--view",
            "class-view",
            "--input",
        ])
        .arg(&source)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let metadata_data = ok_data(&metadata);
    assert_eq!(metadata_data["semantic_profile"], "uml");
    assert_eq!(metadata_data["nodes"]["class-order"]["type"], "Class");
    assert_eq!(
        metadata_data["nodes"]["class-order"]["properties"]["attributes"][0]["name"],
        "id"
    );

    let render = common::dediren_command()
        .env("DEDIREN_PLUGIN_SVG_RENDER", &svg_plugin)
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/uml-svg.json"))
        .arg("--metadata")
        .arg(workspace_file("fixtures/render-metadata/uml-basic.json"))
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/uml-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let render_data = ok_data(&render);
    assert_eq!(render_data["artifact_kind"], "svg");
    let svg = render_data["content"]
        .as_str()
        .expect("render content should be a string");
    assert!(svg.contains("data-dediren-node-decorator=\"uml_class\""));
    let artifact = write_render_artifact(
        "fixture-pipeline",
        "fixture_mode_uml_pipeline_renders_and_exports",
        svg,
    );
    assert!(artifact.exists());

    let export = common::dediren_command()
        .env("DEDIREN_PLUGIN_UML_XMI", &xmi_plugin)
        .args(["export", "--plugin", "uml-xmi", "--policy"])
        .arg(workspace_file(
            "fixtures/export-policy/default-uml-xmi.json",
        ))
        .arg("--source")
        .arg(&source)
        .arg("--layout")
        .arg(workspace_file("fixtures/layout-result/uml-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let export_data = ok_data(&export);
    assert_eq!(export_data["artifact_kind"], "uml-xmi+xml");
    assert!(export_data["content"]
        .as_str()
        .expect("export content should be a string")
        .contains("<uml:Model"));
}

#[test]
fn fixture_mode_uml_data_view_renders() {
    let (svg, metadata_data) = render_uml_fixture_view(
        "data-view",
        "fixtures/layout-result/uml-data.json",
        "fixtures/render-metadata/uml-data.json",
    );
    let doc = svg_doc(&svg);

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
            "Order",
            "OrderLine",
            "OrderStatus",
            "+ id : OrderId",
            "Draft",
            "lines",
            "uses",
        ],
    );
    assert_uml_node_decorator(&doc, "class-order", "uml_class");
    assert_uml_node_decorator(&doc, "enum-order-status", "uml_enumeration");
    assert_edge_marker_start(&doc, "order-has-lines", "filled_diamond");
    assert_edge_marker_end(&doc, "order-status-dependency", "open_arrow");
    assert_reasonable_svg_aspect(&svg, 4.5);
    write_render_artifact(
        "fixture-pipeline",
        "fixture_mode_uml_data_view_renders",
        &svg,
    );
}

#[test]
fn fixture_mode_uml_activity_view_renders() {
    let (svg, metadata_data) = render_uml_fixture_view(
        "activity-view",
        "fixtures/layout-result/uml-activity.json",
        "fixtures/render-metadata/uml-activity.json",
    );
    let doc = svg_doc(&svg);

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
    assert_edge_marker_end(&doc, "flow-start-enter", "filled_arrow");
    assert_edge_marker_end(&doc, "flow-valid-submit", "filled_arrow");
    assert_reasonable_svg_aspect(&svg, 6.0);
    write_render_artifact(
        "fixture-pipeline",
        "fixture_mode_uml_activity_view_renders",
        &svg,
    );
}

#[test]
fn fixture_mode_uml_complex_class_view_renders() {
    let (svg, metadata_data) = render_uml_fixture_view_from_source(
        "complex-class-view",
        "fixtures/source/valid-uml-complex.json",
        "fixtures/layout-result/uml-complex-class.json",
        "fixtures/render-metadata/uml-complex-class.json",
    );
    let doc = svg_doc(&svg);

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
            "+ orders : Order",
            "+ lines : OrderLine",
            "+ payment : CardPayment",
            "+ order : Order",
            "+ total : Money",
            "- state : ShipmentState",
            "+ authorize(amount : Money) : PaymentState",
            "implements",
            "unit price",
            "ships to",
        ],
    );
    assert_uml_node_decorator(&doc, "class-order", "uml_class");
    assert_uml_node_decorator(&doc, "interface-payment-gateway", "uml_interface");
    assert_svg_node_min_size(&doc, "interface-payment-gateway", 380.0, 120.0);
    assert_uml_node_decorator(&doc, "datatype-money", "uml_data_type");
    assert_uml_node_decorator(&doc, "enum-order-status", "uml_enumeration");
    assert_edge_marker_start(&doc, "order-has-lines", "filled_diamond");
    assert_edge_marker_start(&doc, "order-has-payment", "hollow_diamond");
    assert_edge_marker_end(&doc, "card-payment-realizes-gateway", "hollow_triangle");
    assert_edge_marker_end(&doc, "order-status-dependency", "open_arrow");
    assert_reasonable_svg_aspect(&svg, 4.8);
    write_render_artifact(
        "fixture-pipeline",
        "fixture_mode_uml_complex_class_view_renders",
        &svg,
    );
}

#[test]
fn uml_complex_class_relationships_are_backed_by_members() {
    let source = json_fixture("fixtures/source/valid-uml-complex.json");

    for relationship_id in uml_view_relationship_ids(&source, "complex-class-view") {
        let relationship = source_relationship(&source, &relationship_id);
        if relationship["type"].as_str() == Some("Realization") {
            continue;
        }

        let source_endpoint = source_node(&source, relationship["source"].as_str().unwrap());
        let target_endpoint = source_node(&source, relationship["target"].as_str().unwrap());
        let source_label = source_endpoint["label"].as_str().unwrap();
        let target_label = target_endpoint["label"].as_str().unwrap();

        assert!(
            has_uml_attribute_of_type(source_endpoint, target_label)
                || has_uml_attribute_of_type(target_endpoint, source_label),
            "{relationship_id} should be backed by a typed UML member on at least one endpoint"
        );
    }
}

#[test]
fn uml_complex_class_edge_ports_align_to_member_rows() {
    let layout = json_fixture("fixtures/layout-result/uml-complex-class.json");
    let metadata = json_fixture("fixtures/render-metadata/uml-complex-class.json");

    for (edge_id, member_name) in [
        ("customer-places-order", "orders"),
        ("order-has-lines", "lines"),
        ("order-has-payment", "payment"),
        ("order-status-dependency", "status"),
        ("order-total-money", "total"),
        ("order-id-type", "id"),
        ("card-payment-state", "state"),
        ("shipment-for-order", "order"),
        ("shipment-destination", "destination"),
        ("shipment-state", "state"),
        ("order-line-unit-price-money", "unitPrice"),
    ] {
        assert_edge_endpoint_aligns_to_member_row(
            &layout,
            &metadata,
            edge_id,
            "source",
            member_name,
        );
    }

    for (edge_id, member_name) in [
        ("customer-places-order", "customer"),
        ("order-has-lines", "order"),
        ("order-has-payment", "order"),
        ("shipment-for-order", "shipments"),
    ] {
        assert_edge_endpoint_aligns_to_member_row(
            &layout,
            &metadata,
            edge_id,
            "target",
            member_name,
        );
    }
}

#[test]
fn fixture_archimate_pipeline_renders_node_notation() {
    let (svg, metadata_data) = render_fixture_archimate_pipeline();
    let doc = svg_doc(&svg);

    assert_eq!(metadata_data["semantic_profile"], "archimate");
    assert_eq!(
        metadata_data["nodes"]
            .as_object()
            .expect("projected metadata nodes should be an object")
            .len(),
        6
    );
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
        metadata_data["groups"]
            .as_object()
            .expect("projected metadata groups should be an object")
            .len(),
        1
    );
    assert_eq!(
        metadata_data["groups"]["application-services"]["type"],
        "Grouping"
    );
    assert!(metadata_data["groups"]
        .get("external-dependencies")
        .is_none());

    for node_id in ["web-app", "worker"] {
        assert_node_notation(
            &doc,
            node_id,
            "#e0f2fe",
            "#0369a1",
            "archimate_application_component",
        );
    }
    assert_node_notation(
        &doc,
        "client",
        "#fff2cc",
        "#d6b656",
        "archimate_business_actor",
    );
    for node_id in ["orders-api", "payments"] {
        assert_node_notation(
            &doc,
            node_id,
            "#e0f2fe",
            "#0369a1",
            "archimate_application_service",
        );
    }
    assert_node_notation(
        &doc,
        "database",
        "#e0f2fe",
        "#0369a1",
        "archimate_data_object",
    );

    assert_svg_texts_include(
        &doc,
        &["Application Services", "External Dependencies", "Client"],
    );
    let group = semantic_group(&doc, "data-dediren-group-id", "application-services");
    assert_eq!(group.attribute("data-dediren-group-type"), Some("Grouping"));
    assert!(
        child_group_with_attr(group, "data-dediren-group-decorator", "archimate_grouping",)
            .is_some()
    );
    assert_reasonable_svg_aspect(&svg, 2.8);
    write_render_artifact(
        "fixture-pipeline",
        "fixture_archimate_pipeline_renders_node_notation",
        &svg,
    );
}

#[test]
fn fixture_archimate_pipeline_renders_relationship_notation() {
    let (svg, metadata_data) = render_fixture_archimate_pipeline();
    let doc = svg_doc(&svg);

    assert_eq!(
        metadata_data["edges"]
            .as_object()
            .expect("projected metadata edges should be an object")
            .len(),
        6
    );
    assert_eq!(
        metadata_data["edges"]["web-app-calls-api"]["type"],
        "Realization"
    );
    assert_eq!(
        metadata_data["edges"]["client-submits-order"]["type"],
        "Serving"
    );
    assert_eq!(
        metadata_data["edges"]["api-writes-database"]["type"],
        "Access"
    );
    assert_eq!(
        metadata_data["edges"]["api-publishes-job"]["type"],
        "Triggering"
    );

    assert_edge_marker_end(&doc, "web-app-calls-api", "hollow_triangle");
    let realization = semantic_group(&doc, "data-dediren-edge-id", "web-app-calls-api");
    assert_eq!(
        child_element(realization, "path").attribute("stroke-dasharray"),
        Some("8 5")
    );
    assert_edge_marker_end(&doc, "client-submits-order", "open_arrow");
    assert_edge_marker_end(&doc, "api-writes-database", "open_arrow");
    assert_edge_marker_end(&doc, "api-authorizes-payment", "open_arrow");
    assert_edge_marker_end(&doc, "api-publishes-job", "open_arrow");
    assert_edge_marker_end(&doc, "worker-reads-database", "open_arrow");
    assert_svg_texts_include(
        &doc,
        &[
            "calls API",
            "writes orders",
            "publishes fulfillment",
            "loads order",
        ],
    );
    write_render_artifact(
        "fixture-pipeline",
        "fixture_archimate_pipeline_renders_relationship_notation",
        &svg,
    );
}

fn render_fixture_archimate_pipeline() -> (String, serde_json::Value) {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = temp.child("archimate-layout-request.json");
    let metadata = temp.child("archimate-render-metadata.json");
    let result = temp.child("archimate-layout-result.json");
    let generic_plugin = plugin_binary("dediren-plugin-generic-graph");
    let elk_plugin = plugin_binary("dediren-plugin-elk-layout");
    let svg_plugin = plugin_binary("dediren-plugin-svg-render");
    let source = workspace_file("fixtures/source/valid-pipeline-archimate.json");
    let elk_fixture = workspace_file("fixtures/layout-result/pipeline-rich.json");

    let project_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
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
        .arg(&source)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    request.write_binary(&project_output).unwrap();

    let metadata_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
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
        .arg(&source)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    metadata.write_binary(&metadata_output).unwrap();
    let metadata_data = ok_data(&metadata_output);

    let layout_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", &elk_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .env("DEDIREN_ELK_RESULT_FIXTURE", elk_fixture)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    result.write_binary(&layout_output).unwrap();

    let render_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_SVG_RENDER", &svg_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/archimate-svg.json"))
        .arg("--metadata")
        .arg(metadata.path())
        .arg("--input")
        .arg(result.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let render_data = ok_data(&render_output);
    (
        render_data["content"].as_str().unwrap().to_string(),
        metadata_data,
    )
}

fn render_uml_fixture_view(
    view_id: &str,
    layout_fixture: &str,
    metadata_fixture: &str,
) -> (String, serde_json::Value) {
    render_uml_fixture_view_from_source(
        view_id,
        "fixtures/source/valid-uml-basic.json",
        layout_fixture,
        metadata_fixture,
    )
}

fn render_uml_fixture_view_from_source(
    view_id: &str,
    source_fixture: &str,
    layout_fixture: &str,
    metadata_fixture: &str,
) -> (String, serde_json::Value) {
    let generic_plugin = plugin_binary("dediren-plugin-generic-graph");
    let svg_plugin = plugin_binary("dediren-plugin-svg-render");
    let source = workspace_file(source_fixture);

    let request_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
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
        .arg(&source)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let request_data = ok_data(&request_output);
    assert_eq!(request_data["view_id"], view_id);

    let metadata_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
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
        .arg(&source)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let metadata_data = ok_data(&metadata_output);
    let fixture_metadata: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(workspace_file(metadata_fixture)).unwrap())
            .unwrap();
    assert_eq!(metadata_data, fixture_metadata);

    let render_output = common::dediren_command()
        .env("DEDIREN_PLUGIN_SVG_RENDER", &svg_plugin)
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/uml-svg.json"))
        .arg("--metadata")
        .arg(workspace_file(metadata_fixture))
        .arg("--input")
        .arg(workspace_file(layout_fixture))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let render_data = ok_data(&render_output);
    assert_eq!(render_data["artifact_kind"], "svg");
    (
        render_data["content"].as_str().unwrap().to_string(),
        metadata_data,
    )
}

fn json_fixture(path: &str) -> Value {
    serde_json::from_str(&std::fs::read_to_string(workspace_file(path)).unwrap()).unwrap()
}

fn uml_view_relationship_ids(source: &Value, view_id: &str) -> Vec<String> {
    source["plugins"]["generic-graph"]["views"]
        .as_array()
        .unwrap()
        .iter()
        .find(|view| view["id"].as_str() == Some(view_id))
        .unwrap_or_else(|| panic!("expected UML fixture view {view_id}"))
        .get("relationships")
        .and_then(Value::as_array)
        .unwrap()
        .iter()
        .map(|relationship_id| relationship_id.as_str().unwrap().to_string())
        .collect()
}

fn source_node<'a>(source: &'a Value, node_id: &str) -> &'a Value {
    source["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .find(|node| node["id"].as_str() == Some(node_id))
        .unwrap_or_else(|| panic!("expected source node {node_id}"))
}

fn source_relationship<'a>(source: &'a Value, relationship_id: &str) -> &'a Value {
    source["relationships"]
        .as_array()
        .unwrap()
        .iter()
        .find(|relationship| relationship["id"].as_str() == Some(relationship_id))
        .unwrap_or_else(|| panic!("expected source relationship {relationship_id}"))
}

fn has_uml_attribute_of_type(node: &Value, type_name: &str) -> bool {
    node["properties"]["uml"]["attributes"]
        .as_array()
        .is_some_and(|attributes| {
            attributes
                .iter()
                .any(|attribute| attribute["type"].as_str() == Some(type_name))
        })
}

fn assert_edge_endpoint_aligns_to_member_row(
    layout: &Value,
    metadata: &Value,
    edge_id: &str,
    endpoint: &str,
    member_name: &str,
) {
    let edge = layout_edge(layout, edge_id);
    let points = edge["points"].as_array().unwrap();
    let (node_id, point) = match endpoint {
        "source" => (edge["source"].as_str().unwrap(), points.first().unwrap()),
        "target" => (edge["target"].as_str().unwrap(), points.last().unwrap()),
        _ => panic!("unsupported endpoint {endpoint}"),
    };
    let actual_y = point["y"].as_f64().unwrap();
    let expected_y = uml_member_row_center_y(layout, metadata, node_id, member_name);
    assert!(
        (actual_y - expected_y).abs() <= 2.0,
        "{edge_id} {endpoint} endpoint should align with {node_id}.{member_name} row: got y={actual_y}, expected y={expected_y}"
    );
}

fn layout_edge<'a>(layout: &'a Value, edge_id: &str) -> &'a Value {
    layout["edges"]
        .as_array()
        .unwrap()
        .iter()
        .find(|edge| edge["id"].as_str() == Some(edge_id))
        .unwrap_or_else(|| panic!("expected layout edge {edge_id}"))
}

fn layout_node<'a>(layout: &'a Value, node_id: &str) -> &'a Value {
    layout["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .find(|node| node["id"].as_str() == Some(node_id))
        .unwrap_or_else(|| panic!("expected layout node {node_id}"))
}

fn uml_member_row_center_y(
    layout: &Value,
    metadata: &Value,
    node_id: &str,
    member_name: &str,
) -> f64 {
    let node = layout_node(layout, node_id);
    let metadata_node = &metadata["nodes"][node_id];
    let type_name = metadata_node["type"].as_str().unwrap();
    let title_line_count = match type_name {
        "DataType" | "Enumeration" | "Interface" => 2.0,
        _ => 1.0,
    };
    let title_height = (title_line_count * 15.0_f64 + 8.0_f64).max(28.0_f64);
    let attributes = metadata_node["properties"]["attributes"]
        .as_array()
        .unwrap_or_else(|| panic!("{node_id} should have attributes for member row assertions"));
    let index = attributes
        .iter()
        .position(|attribute| attribute["name"].as_str() == Some(member_name))
        .unwrap_or_else(|| panic!("{node_id} should have UML member {member_name}"));

    node["y"].as_f64().unwrap() + title_height + 8.0 + index as f64 * 14.0
}

fn assert_node_notation(
    doc: &roxmltree::Document<'_>,
    node_id: &str,
    fill: &str,
    stroke: &str,
    decorator: &str,
) {
    let node = semantic_group(doc, "data-dediren-node-id", node_id);
    let rect = child_element(node, "rect");
    assert_eq!(rect.attribute("fill"), Some(fill));
    assert_eq!(rect.attribute("stroke"), Some(stroke));
    assert!(child_group_with_attr(node, "data-dediren-node-decorator", decorator).is_some());
}

fn assert_uml_node_decorator(doc: &roxmltree::Document<'_>, node_id: &str, decorator: &str) {
    let node = semantic_group(doc, "data-dediren-node-id", node_id);
    assert!(
        child_group_with_attr(node, "data-dediren-node-decorator", decorator).is_some(),
        "expected {node_id} to render UML decorator {decorator}"
    );
}

fn assert_svg_node_min_size(
    doc: &roxmltree::Document<'_>,
    node_id: &str,
    min_width: f64,
    min_height: f64,
) {
    let node = semantic_group(doc, "data-dediren-node-id", node_id);
    let rect = child_element(node, "rect");
    let width = rect
        .attribute("width")
        .unwrap_or_else(|| panic!("expected {node_id} rect to have width"))
        .parse::<f64>()
        .unwrap_or_else(|error| panic!("expected {node_id} width to be numeric: {error}"));
    let height = rect
        .attribute("height")
        .unwrap_or_else(|| panic!("expected {node_id} rect to have height"))
        .parse::<f64>()
        .unwrap_or_else(|error| panic!("expected {node_id} height to be numeric: {error}"));

    assert!(
        width >= min_width,
        "{node_id} width should be >= {min_width}, got {width}"
    );
    assert!(
        height >= min_height,
        "{node_id} height should be >= {min_height}, got {height}"
    );
}

fn assert_edge_marker_start(doc: &roxmltree::Document<'_>, edge_id: &str, marker_kind: &str) {
    let edge = semantic_group(doc, "data-dediren-edge-id", edge_id);
    let path = child_element(edge, "path");
    let marker_id = format!("marker-start-{edge_id}");
    let marker_ref = format!("url(#{marker_id})");
    assert_eq!(path.attribute("marker-start"), Some(marker_ref.as_str()));
    let marker = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("marker") && node.attribute("id") == Some(marker_id.as_str())
        })
        .unwrap_or_else(|| panic!("expected marker {marker_id}"));
    assert_eq!(
        marker.attribute("data-dediren-edge-marker-start"),
        Some(marker_kind)
    );
}

fn assert_edge_marker_end(doc: &roxmltree::Document<'_>, edge_id: &str, marker_kind: &str) {
    let edge = semantic_group(doc, "data-dediren-edge-id", edge_id);
    let path = child_element(edge, "path");
    let marker_id = format!("marker-end-{edge_id}");
    let marker_ref = format!("url(#{marker_id})");
    assert_eq!(path.attribute("marker-end"), Some(marker_ref.as_str()));
    let marker = doc
        .descendants()
        .find(|node| {
            node.has_tag_name("marker") && node.attribute("id") == Some(marker_id.as_str())
        })
        .unwrap_or_else(|| panic!("expected marker {marker_id}"));
    assert_eq!(
        marker.attribute("data-dediren-edge-marker-end"),
        Some(marker_kind)
    );
}
