use jsonschema::Validator;
use serde_json::json;
use std::collections::BTreeMap;
use std::path::PathBuf;

const PUBLIC_SCHEMA_PATHS: &[&str] = &[
    "schemas/model.schema.json",
    "schemas/envelope.schema.json",
    "schemas/layout-request.schema.json",
    "schemas/layout-result.schema.json",
    "schemas/semantic-validation-result.schema.json",
    "schemas/svg-render-policy.schema.json",
    "schemas/render-metadata.schema.json",
    "schemas/render-result.schema.json",
    "schemas/export-request.schema.json",
    "schemas/export-result.schema.json",
    "schemas/oef-export-policy.schema.json",
    "schemas/plugin-manifest.schema.json",
    "schemas/runtime-capability.schema.json",
    "schemas/bundle.schema.json",
];

const FIRST_PARTY_PLUGIN_MANIFEST_PATHS: &[&str] = &[
    "fixtures/plugins/archimate-oef.manifest.json",
    "fixtures/plugins/elk-layout.manifest.json",
    "fixtures/plugins/generic-graph.manifest.json",
    "fixtures/plugins/svg-render.manifest.json",
];

const SOURCE_FIXTURE_PATHS: &[&str] = &[
    "fixtures/source/valid-basic.json",
    "fixtures/source/valid-pipeline-rich.json",
    "fixtures/source/valid-pipeline-archimate.json",
    "fixtures/source/valid-archimate-oef.json",
];

const WORKSPACE_PACKAGE_NAMES: &[&str] = &[
    "dediren",
    "dediren-archimate",
    "dediren-contracts",
    "dediren-core",
    "dediren-plugin-archimate-oef-export",
    "dediren-plugin-elk-layout",
    "dediren-plugin-generic-graph",
    "dediren-plugin-runtime-testbed",
    "dediren-plugin-svg-render",
    "xtask",
];

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
    for node_type in node_types.keys() {
        assert!(
            ARCHIMATE_NODE_TYPES.contains(&node_type.as_str()),
            "unexpected ArchiMate node style for {node_type}"
        );
    }
    for node_type in ARCHIMATE_NODE_TYPES {
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
    for relationship_type in edge_types.keys() {
        assert!(
            ARCHIMATE_RELATIONSHIP_TYPES.contains(&relationship_type.as_str()),
            "unexpected ArchiMate relationship style for {relationship_type}"
        );
    }
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
fn layout_request_schema_accepts_visual_only_group_provenance() {
    assert_json_valid(
        "schemas/layout-request.schema.json",
        json!({
            "layout_request_schema_version": "layout-request.schema.v1",
            "view_id": "main",
            "nodes": [],
            "edges": [],
            "groups": [
                {
                    "id": "visual-column",
                    "label": "Visual Column",
                    "members": [],
                    "provenance": { "visual_only": true }
                }
            ],
            "labels": [],
            "constraints": []
        }),
    );
}

#[test]
fn layout_request_schema_rejects_ambiguous_group_provenance() {
    assert_json_invalid(
        "schemas/layout-request.schema.json",
        json!({
            "layout_request_schema_version": "layout-request.schema.v1",
            "view_id": "main",
            "nodes": [],
            "edges": [],
            "groups": [
                {
                    "id": "ambiguous",
                    "label": "Ambiguous",
                    "members": [],
                    "provenance": {
                        "visual_only": true,
                        "semantic_backed": { "source_id": "ambiguous" }
                    }
                }
            ],
            "labels": [],
            "constraints": []
        }),
        "layout request with ambiguous group provenance",
    );
}

#[test]
fn render_metadata_schema_accepts_group_selectors() {
    assert_json_valid(
        "schemas/render-metadata.schema.json",
        json!({
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
                    "Node": {
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
    for path in PUBLIC_SCHEMA_PATHS {
        let _ = validator(path);
    }
}

#[test]
fn readme_lists_all_public_schemas() {
    let readme = std::fs::read_to_string(workspace_file("README.md")).unwrap();
    for path in PUBLIC_SCHEMA_PATHS {
        assert!(readme.contains(path), "README.md should list {path}");
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
fn semantic_validation_result_matches_schema() {
    assert_json_valid(
        "schemas/semantic-validation-result.schema.json",
        json!({
            "semantic_validation_result_schema_version": "semantic-validation-result.schema.v1",
            "semantic_profile": "archimate",
            "node_count": 2,
            "relationship_count": 1
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
fn readme_documents_archimate_connector_junction_support_and_generated_hints() {
    let readme = std::fs::read_to_string(workspace_file("README.md")).unwrap();
    assert!(
        readme.contains("Relationship connector junctions are supported")
            && readme.contains("AndJunction")
            && readme.contains("OrJunction")
            && readme.contains("at least one incoming and one outgoing relationship")
            && readme.contains("contiguous junction chains")
            && readme.contains("treated as")
            && readme.contains("containment")
            && readme.contains("shared_source_junction"),
        "README.md should document source junction support and generated route hints"
    );
}

#[test]
fn first_party_plugin_manifest_versions_match_workspace_version() {
    for path in FIRST_PARTY_PLUGIN_MANIFEST_PATHS {
        let text = std::fs::read_to_string(workspace_file(path)).unwrap();
        let manifest: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert_eq!(
            manifest["version"].as_str(),
            Some(env!("CARGO_PKG_VERSION")),
            "{path} version should match workspace package version"
        );
    }
}

#[test]
fn source_fixture_required_plugin_versions_match_first_party_manifests() {
    let manifest_versions = first_party_plugin_versions();
    for path in SOURCE_FIXTURE_PATHS {
        let source = json_file(path);
        let required_plugins = source["required_plugins"]
            .as_array()
            .unwrap_or_else(|| panic!("{path} should declare required_plugins"));
        for plugin in required_plugins {
            let id = string_property(plugin, "id");
            let version = string_property(plugin, "version");
            let expected = manifest_versions
                .get(id)
                .unwrap_or_else(|| panic!("{path} requires unknown first-party plugin {id}"));
            assert_eq!(
                version, expected,
                "{path} should require {id} at the bundled first-party manifest version"
            );
        }
    }
}

#[test]
fn live_release_surfaces_match_workspace_version() {
    let version = env!("CARGO_PKG_VERSION");
    let target = "x86_64-unknown-linux-gnu";
    let bundle_name = format!("dediren-agent-bundle-{version}-{target}");
    let archive_name = format!("{bundle_name}.tar.gz");

    let readme = std::fs::read_to_string(workspace_file("README.md")).unwrap();
    assert!(
        readme.contains(&bundle_name),
        "README.md should mention {bundle_name}"
    );
    assert!(
        readme.contains(&archive_name),
        "README.md should mention {archive_name}"
    );
    assert!(
        readme.contains("cargo xtask dist build"),
        "README.md should document the xtask distribution build command"
    );
    assert!(
        readme.contains("cargo xtask dist smoke"),
        "README.md should document the xtask distribution smoke command"
    );
    assert!(
        !readme.contains("scripts/build-dist.sh"),
        "README.md should not document legacy distribution build wrappers"
    );
    assert!(
        !readme.contains("scripts/smoke-dist.sh"),
        "README.md should not document legacy distribution smoke wrappers"
    );

    for path in ["scripts/build-dist.sh", "scripts/smoke-dist.sh"] {
        assert!(
            !workspace_file(path).exists(),
            "{path} should be removed; cargo xtask is the canonical distribution tooling"
        );
    }

    let lock = std::fs::read_to_string(workspace_file("Cargo.lock")).unwrap();
    for package_name in WORKSPACE_PACKAGE_NAMES {
        let lock_version = cargo_lock_package_version(&lock, package_name)
            .unwrap_or_else(|| panic!("Cargo.lock should contain package {package_name}"));
        assert_eq!(
            lock_version, version,
            "Cargo.lock package {package_name} should match workspace version"
        );
    }
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
fn bundle_metadata_matches_schema() {
    assert_json_valid(
        "schemas/bundle.schema.json",
        json!({
            "bundle_schema_version": "dediren-bundle.schema.v1",
            "product": "dediren",
            "version": env!("CARGO_PKG_VERSION"),
            "target": "x86_64-unknown-linux-gnu",
            "built_at_utc": "2026-05-13T00:00:00Z",
            "plugins": [
                { "id": "generic-graph", "version": env!("CARGO_PKG_VERSION") },
                { "id": "elk-layout", "version": env!("CARGO_PKG_VERSION") },
                { "id": "svg-render", "version": env!("CARGO_PKG_VERSION") },
                { "id": "archimate-oef", "version": env!("CARGO_PKG_VERSION") }
            ],
            "schemas_dir": "schemas",
            "fixtures_dir": "fixtures",
            "elk_helper": "runtimes/elk-layout-java/bin/dediren-elk-layout-java"
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
    assert_json_valid(
        "schemas/layout-result.schema.json",
        json!({
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "main",
            "nodes": [],
            "edges": [
                {
                    "id": "source-fans-out",
                    "source": "source",
                    "target": "target",
                    "source_id": "source-fans-out",
                    "projection_id": "source-fans-out",
                    "routing_hints": ["shared_source_junction"],
                    "points": [
                        { "x": 0, "y": 0 },
                        { "x": 100, "y": 0 }
                    ],
                    "label": "fans out"
                }
            ],
            "groups": [],
            "warnings": []
        }),
    );
    assert_json_invalid(
        "schemas/layout-result.schema.json",
        json!({
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "main",
            "nodes": [],
            "edges": [
                {
                    "id": "bad-hint",
                    "source": "source",
                    "target": "target",
                    "source_id": "bad-hint",
                    "projection_id": "bad-hint",
                    "routing_hints": ["shared_middle_junction"],
                    "points": [
                        { "x": 0, "y": 0 },
                        { "x": 100, "y": 0 }
                    ],
                    "label": "bad hint"
                }
            ],
            "groups": [],
            "warnings": []
        }),
        "unknown routing hint",
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
    for path in FIRST_PARTY_PLUGIN_MANIFEST_PATHS {
        assert_valid("schemas/plugin-manifest.schema.json", path);
    }
}

fn first_party_plugin_versions() -> BTreeMap<String, String> {
    let mut versions = BTreeMap::new();
    for path in FIRST_PARTY_PLUGIN_MANIFEST_PATHS {
        let manifest = json_file(path);
        let id = string_property(&manifest, "id").to_string();
        let version = string_property(&manifest, "version").to_string();
        versions.insert(id, version);
    }
    versions
}

fn string_property<'a>(value: &'a serde_json::Value, property: &str) -> &'a str {
    value[property]
        .as_str()
        .unwrap_or_else(|| panic!("{property} should be a string"))
}

fn cargo_lock_package_version<'a>(lock: &'a str, package_name: &str) -> Option<&'a str> {
    let mut in_package = false;
    let mut matched_package = false;

    for line in lock.lines() {
        if line == "[[package]]" {
            in_package = true;
            matched_package = false;
            continue;
        }
        if !in_package {
            continue;
        }
        if let Some(name) = line
            .strip_prefix("name = \"")
            .and_then(|value| value.strip_suffix('"'))
        {
            matched_package = name == package_name;
            continue;
        }
        if matched_package {
            if let Some(version) = line
                .strip_prefix("version = \"")
                .and_then(|value| value.strip_suffix('"'))
            {
                return Some(version);
            }
        }
    }
    None
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

const ARCHIMATE_NODE_TYPES: &[&str] = &[
    "Plateau",
    "WorkPackage",
    "Deliverable",
    "ImplementationEvent",
    "Gap",
    "Grouping",
    "AndJunction",
    "OrJunction",
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
