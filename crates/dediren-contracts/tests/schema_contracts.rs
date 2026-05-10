use jsonschema::Validator;
use serde_json::json;
use std::path::PathBuf;

#[test]
fn valid_source_matches_model_schema() {
    assert_valid(
        "schemas/model.schema.json",
        "fixtures/source/valid-basic.json",
    );
    assert_valid(
        "schemas/model.schema.json",
        "fixtures/source/valid-pipeline-rich.json",
    );
    assert_valid(
        "schemas/model.schema.json",
        "fixtures/source/valid-pipeline-archimate.json",
    );
}

#[test]
fn source_with_absolute_geometry_fails_model_schema() {
    assert_invalid(
        "schemas/model.schema.json",
        "fixtures/source/invalid-absolute-geometry.json",
    );
}

#[test]
fn default_svg_policy_matches_schema() {
    assert_valid(
        "schemas/svg-render-policy.schema.json",
        "fixtures/render-policy/default-svg.json",
    );
}

#[test]
fn rich_svg_policy_matches_schema() {
    assert_valid(
        "schemas/svg-render-policy.schema.json",
        "fixtures/render-policy/rich-svg.json",
    );
}

#[test]
fn archimate_svg_policy_matches_schema() {
    assert_valid(
        "schemas/svg-render-policy.schema.json",
        "fixtures/render-policy/archimate-svg.json",
    );
}

#[test]
fn archimate_svg_policy_covers_square_nodes_and_relationships() {
    let policy: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(workspace_file("fixtures/render-policy/archimate-svg.json"))
            .unwrap(),
    )
    .unwrap();
    let node_types = policy["style"]["node_type_overrides"]
        .as_object()
        .expect("node type overrides should be an object");
    for node_type in ARCHIMATE_SQUARE_NODE_TYPES {
        let node_style = node_types
            .get(*node_type)
            .unwrap_or_else(|| panic!("missing ArchiMate node style for {node_type}"));
        assert!(
            node_style.get("fill").is_some(),
            "expected fill color for {node_type}"
        );
        assert!(
            node_style.get("stroke").is_some(),
            "expected stroke color for {node_type}"
        );
        assert!(
            node_style.get("decorator").is_some(),
            "expected icon decorator for {node_type}"
        );
    }

    let edge_types = policy["style"]["edge_type_overrides"]
        .as_object()
        .expect("edge type overrides should be an object");
    for relationship_type in ARCHIMATE_RELATIONSHIP_TYPES {
        edge_types.get(*relationship_type).unwrap_or_else(|| {
            panic!("missing ArchiMate relationship style for {relationship_type}")
        });
    }
}

#[test]
fn archimate_render_metadata_matches_schema() {
    assert_valid(
        "schemas/render-metadata.schema.json",
        "fixtures/render-metadata/archimate-basic.json",
    );
}

#[test]
fn svg_policy_schema_rejects_invalid_style_cells() {
    for (name, style) in [
        (
            "invalid color",
            json!({
                "node": {
                    "fill": "url(https://attacker.example/x.svg#p)"
                }
            }),
        ),
        (
            "out-of-range numeric style value",
            json!({
                "node": {
                    "stroke_width": 24.01
                }
            }),
        ),
        (
            "unknown style field",
            json!({
                "node": {
                    "fill": "#ffffff",
                    "blend_mode": "screen"
                }
            }),
        ),
        (
            "invalid override object shape",
            json!({
                "node_overrides": {
                    "api": "#ffffff"
                }
            }),
        ),
    ] {
        assert_json_invalid(
            "schemas/svg-render-policy.schema.json",
            json!({
                "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
                "page": { "width": 640, "height": 360 },
                "margin": { "top": 16, "right": 16, "bottom": 16, "left": 16 },
                "style": style
            }),
            name,
        );
    }
}

#[test]
fn render_metadata_schema_accepts_valid_fixture() {
    assert_json_valid(
        "schemas/render-metadata.schema.json",
        json!({
            "render_metadata_schema_version": "render-metadata.schema.v1",
            "semantic_profile": "archimate",
            "nodes": {
                "orders-component": {
                    "type": "ApplicationComponent",
                    "source_id": "orders-component"
                }
            },
            "edges": {
                "orders-realizes-service": {
                    "type": "Realization",
                    "source_id": "orders-realizes-service"
                }
            }
        }),
    );
}

#[test]
fn render_metadata_schema_rejects_style_fields() {
    assert_json_invalid(
        "schemas/render-metadata.schema.json",
        json!({
            "render_metadata_schema_version": "render-metadata.schema.v1",
            "semantic_profile": "archimate",
            "nodes": {
                "orders-component": {
                    "type": "ApplicationComponent",
                    "source_id": "orders-component",
                    "fill": "#e0f2fe"
                }
            },
            "edges": {}
        }),
        "style fields in render metadata",
    );
}

#[test]
fn svg_policy_schema_accepts_semantic_type_overrides() {
    assert_json_valid(
        "schemas/svg-render-policy.schema.json",
        json!({
            "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
            "semantic_profile": "archimate",
            "page": { "width": 640, "height": 360 },
            "margin": { "top": 24, "right": 24, "bottom": 24, "left": 24 },
            "style": {
                "node_type_overrides": {
                    "ApplicationComponent": {
                        "fill": "#e0f2fe",
                        "stroke": "#0369a1"
                    }
                },
                "edge_type_overrides": {
                    "Realization": {
                        "stroke": "#374151",
                        "stroke_width": 1.5
                    }
                }
            }
        }),
    );
}

#[test]
fn svg_policy_schema_accepts_archimate_decorators_and_edge_notation() {
    assert_json_valid(
        "schemas/svg-render-policy.schema.json",
        json!({
            "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
            "semantic_profile": "archimate",
            "page": { "width": 640, "height": 360 },
            "margin": { "top": 24, "right": 24, "bottom": 24, "left": 24 },
            "style": {
                "node_type_overrides": {
                    "BusinessActor": {
                        "fill": "#fff2cc",
                        "stroke": "#d6b656",
                        "decorator": "archimate_business_actor"
                    },
                    "ApplicationComponent": {
                        "fill": "#e0f2fe",
                        "stroke": "#0369a1",
                        "decorator": "archimate_application_component"
                    },
                    "ApplicationService": {
                        "fill": "#e0f2fe",
                        "stroke": "#0369a1",
                        "decorator": "archimate_application_service"
                    },
                    "DataObject": {
                        "fill": "#e0f2fe",
                        "stroke": "#0369a1",
                        "decorator": "archimate_data_object"
                    },
                    "TechnologyNode": {
                        "fill": "#d5e8d4",
                        "stroke": "#4d7c0f",
                        "decorator": "archimate_technology_node"
                    }
                },
                "edge_type_overrides": {
                    "Composition": {
                        "marker_start": "filled_diamond",
                        "marker_end": "none"
                    },
                    "Realization": {
                        "stroke": "#374151",
                        "line_style": "dashed",
                        "marker_end": "hollow_triangle"
                    }
                }
            }
        }),
    );
}

#[test]
fn svg_policy_schema_rejects_unknown_node_decorator() {
    assert_json_invalid(
        "schemas/svg-render-policy.schema.json",
        json!({
            "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
            "page": { "width": 640, "height": 360 },
            "margin": { "top": 24, "right": 24, "bottom": 24, "left": 24 },
            "style": {
                "node": {
                    "decorator": "unknown_archimate_shape"
                }
            }
        }),
        "unknown node decorator",
    );
}

#[test]
fn all_public_schemas_compile() {
    for path in [
        "schemas/model.schema.json",
        "schemas/envelope.schema.json",
        "schemas/layout-request.schema.json",
        "schemas/layout-result.schema.json",
        "schemas/svg-render-policy.schema.json",
        "schemas/render-metadata.schema.json",
        "schemas/render-result.schema.json",
        "schemas/export-request.schema.json",
        "schemas/export-result.schema.json",
        "schemas/oef-export-policy.schema.json",
        "schemas/plugin-manifest.schema.json",
        "schemas/runtime-capability.schema.json",
    ] {
        let _ = validator(path);
    }
}

#[test]
fn render_result_matches_schema() {
    assert_json_valid(
        "schemas/render-result.schema.json",
        json!({
            "render_result_schema_version": "render-result.schema.v1",
            "artifact_kind": "svg",
            "content": "<svg></svg>"
        }),
    );
}

#[test]
fn plugin_manifest_matches_schema() {
    assert_json_valid(
        "schemas/plugin-manifest.schema.json",
        json!({
            "plugin_manifest_schema_version": "plugin-manifest.schema.v1",
            "id": "svg-render",
            "version": "0.1.0",
            "executable": "dediren-plugin-svg-render",
            "capabilities": ["render"]
        }),
    );
}

#[test]
fn runtime_capabilities_match_schema() {
    assert_json_valid(
        "schemas/runtime-capability.schema.json",
        json!({
            "plugin_protocol_version": "plugin.protocol.v1",
            "id": "svg-render",
            "capabilities": ["render"],
            "runtime": { "artifact_kind": "svg" }
        }),
    );
}

#[test]
fn layout_contracts_match_schemas() {
    assert_json_valid(
        "schemas/layout-request.schema.json",
        json!({
            "layout_request_schema_version": "layout-request.schema.v1",
            "view_id": "main",
            "nodes": [],
            "edges": [],
            "groups": [],
            "labels": [],
            "constraints": []
        }),
    );
    assert_json_valid(
        "schemas/layout-result.schema.json",
        json!({
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "main",
            "nodes": [],
            "edges": [],
            "groups": [],
            "warnings": []
        }),
    );
}

#[test]
fn layout_fixtures_match_schemas() {
    assert_valid(
        "schemas/layout-request.schema.json",
        "fixtures/layout-request/basic.json",
    );
    assert_valid(
        "schemas/layout-result.schema.json",
        "fixtures/layout-result/basic.json",
    );
    assert_valid(
        "schemas/layout-result.schema.json",
        "fixtures/layout-result/pipeline-rich.json",
    );
}

#[test]
fn export_contracts_match_schemas() {
    assert_valid(
        "schemas/oef-export-policy.schema.json",
        "fixtures/export-policy/default-oef.json",
    );

    assert_json_valid(
        "schemas/export-request.schema.json",
        json!({
            "export_request_schema_version": "export-request.schema.v1",
            "source": serde_json::from_str::<serde_json::Value>(
                &std::fs::read_to_string(workspace_file("fixtures/source/valid-archimate-oef.json")).unwrap()
            ).unwrap(),
            "layout_result": serde_json::from_str::<serde_json::Value>(
                &std::fs::read_to_string(workspace_file("fixtures/layout-result/archimate-oef-basic.json")).unwrap()
            ).unwrap(),
            "policy": serde_json::from_str::<serde_json::Value>(
                &std::fs::read_to_string(workspace_file("fixtures/export-policy/default-oef.json")).unwrap()
            ).unwrap()
        }),
    );

    assert_json_valid(
        "schemas/export-result.schema.json",
        json!({
            "export_result_schema_version": "export-result.schema.v1",
            "artifact_kind": "archimate-oef+xml",
            "content": "<model/>"
        }),
    );
}

#[test]
fn archimate_oef_fixtures_match_existing_contracts() {
    assert_valid(
        "schemas/model.schema.json",
        "fixtures/source/valid-archimate-oef.json",
    );
    assert_valid(
        "schemas/layout-result.schema.json",
        "fixtures/layout-result/archimate-oef-basic.json",
    );
}

#[test]
fn bundled_plugin_manifests_match_schema() {
    for path in [
        "fixtures/plugins/generic-graph.manifest.json",
        "fixtures/plugins/elk-layout.manifest.json",
        "fixtures/plugins/svg-render.manifest.json",
        "fixtures/plugins/archimate-oef.manifest.json",
    ] {
        assert_valid("schemas/plugin-manifest.schema.json", path);
    }
}

fn assert_valid(schema_path: &str, instance_path: &str) {
    let validator = validator(schema_path);
    let instance = json_file(instance_path);
    let result = validator.validate(&instance);
    assert!(result.is_ok(), "{instance_path} should validate");
}

fn assert_invalid(schema_path: &str, instance_path: &str) {
    let validator = validator(schema_path);
    let instance = json_file(instance_path);
    let result = validator.validate(&instance);
    assert!(result.is_err(), "{instance_path} should fail validation");
}

fn assert_json_valid(schema_path: &str, instance: serde_json::Value) {
    let validator = validator(schema_path);
    let result = validator.validate(&instance);
    assert!(
        result.is_ok(),
        "{schema_path} should validate supplied JSON"
    );
}

fn assert_json_invalid(schema_path: &str, instance: serde_json::Value, name: &str) {
    let validator = validator(schema_path);
    let result = validator.validate(&instance);
    assert!(result.is_err(), "{schema_path} should reject {name}");
}

fn validator(path: &str) -> Validator {
    let schema = json_file(path);
    jsonschema::validator_for(&schema).unwrap()
}

fn json_file(path: &str) -> serde_json::Value {
    let text = std::fs::read_to_string(workspace_file(path)).unwrap();
    serde_json::from_str(&text).unwrap()
}

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}

const ARCHIMATE_SQUARE_NODE_TYPES: &[&str] = &[
    "Plateau",
    "WorkPackage",
    "Deliverable",
    "ImplementationEvent",
    "Gap",
    "Grouping",
    "Location",
    "Stakeholder",
    "Driver",
    "Assessment",
    "Goal",
    "Outcome",
    "Value",
    "Meaning",
    "Constraint",
    "Requirement",
    "Principle",
    "CourseOfAction",
    "Resource",
    "ValueStream",
    "Capability",
    "BusinessInterface",
    "BusinessCollaboration",
    "BusinessActor",
    "BusinessRole",
    "BusinessService",
    "BusinessInteraction",
    "BusinessFunction",
    "BusinessProcess",
    "BusinessEvent",
    "Product",
    "BusinessObject",
    "Contract",
    "Representation",
    "ApplicationInterface",
    "ApplicationCollaboration",
    "ApplicationComponent",
    "ApplicationService",
    "ApplicationInteraction",
    "ApplicationFunction",
    "ApplicationProcess",
    "ApplicationEvent",
    "DataObject",
    "TechnologyInterface",
    "TechnologyCollaboration",
    "Node",
    "SystemSoftware",
    "Device",
    "Facility",
    "Equipment",
    "Path",
    "TechnologyService",
    "TechnologyInteraction",
    "TechnologyFunction",
    "TechnologyProcess",
    "TechnologyEvent",
    "Artifact",
    "Material",
    "CommunicationNetwork",
    "DistributionNetwork",
];

const ARCHIMATE_RELATIONSHIP_TYPES: &[&str] = &[
    "Composition",
    "Aggregation",
    "Assignment",
    "Realization",
    "Specialization",
    "Serving",
    "Access",
    "Influence",
    "Association",
    "Triggering",
    "Flow",
];
