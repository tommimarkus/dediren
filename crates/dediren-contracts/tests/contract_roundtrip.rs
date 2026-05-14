use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, GenericGraphPluginData,
    GenericGraphViewGroupRole, GroupProvenance, LayoutRequest, Margin, Page, RenderMetadata,
    RenderMetadataSelector, RenderPolicy, RenderResult, SemanticValidationResult, SourceDocument,
    SvgEdgeLineStyle, SvgEdgeMarkerEnd, SvgEdgeStyle, SvgNodeDecorator, SvgNodeStyle,
    SvgStylePolicy, SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION, SVG_RENDER_POLICY_SCHEMA_VERSION,
};
use std::collections::BTreeMap;
use std::path::PathBuf;

#[test]
fn source_document_roundtrips() {
    let text = std::fs::read_to_string(workspace_file("fixtures/source/valid-basic.json")).unwrap();
    let doc: SourceDocument = serde_json::from_str(&text).unwrap();
    assert_eq!(doc.model_schema_version, "model.schema.v1");
    assert_eq!(doc.nodes[0].id, "client");
    assert_eq!(doc.relationships[0].source, "client");
}

#[test]
fn command_envelope_roundtrips() {
    let envelope = CommandEnvelope::<serde_json::Value> {
        envelope_schema_version: "envelope.schema.v1".to_string(),
        status: "ok".to_string(),
        data: Some(serde_json::json!({"kind": "sample"})),
        diagnostics: vec![Diagnostic {
            code: "DEDIREN_TEST".to_string(),
            severity: DiagnosticSeverity::Info,
            message: "sample".to_string(),
            path: Some("$.nodes[0]".to_string()),
        }],
    };
    let encoded = serde_json::to_string(&envelope).unwrap();
    let decoded: CommandEnvelope<serde_json::Value> = serde_json::from_str(&encoded).unwrap();
    assert_eq!(decoded.status, "ok");
    assert_eq!(decoded.diagnostics[0].severity, DiagnosticSeverity::Info);
}

#[test]
fn layout_request_roundtrips() {
    let request = LayoutRequest {
        layout_request_schema_version: "layout-request.schema.v1".to_string(),
        view_id: "main".to_string(),
        nodes: vec![],
        edges: vec![],
        groups: vec![],
        labels: vec![],
        constraints: vec![],
    };
    let encoded = serde_json::to_string(&request).unwrap();
    let decoded: LayoutRequest = serde_json::from_str(&encoded).unwrap();
    assert_eq!(decoded.view_id, "main");
}

#[test]
fn layout_group_provenance_uses_schema_owned_object_shape() {
    let request: LayoutRequest = serde_json::from_str(
        r#"{
          "layout_request_schema_version": "layout-request.schema.v1",
          "view_id": "main",
          "nodes": [],
          "edges": [],
          "groups": [
            {
              "id": "group-1",
              "label": "Group",
              "members": [],
              "provenance": { "semantic_backed": { "source_id": "system-group" } }
            }
          ],
          "labels": [],
          "constraints": []
        }"#,
    )
    .unwrap();

    assert_eq!(
        GroupProvenance::semantic_backed("system-group"),
        request.groups[0].provenance
    );
}

#[test]
fn generic_graph_group_role_defaults_to_semantic_boundary() {
    let data: GenericGraphPluginData = serde_json::from_str(
        r#"{
          "views": [
            {
              "id": "main",
              "label": "Main",
              "nodes": ["api"],
              "relationships": [],
              "groups": [
                {
                  "id": "application-services",
                  "label": "Application Services",
                  "members": ["api"]
                }
              ]
            }
          ]
        }"#,
    )
    .unwrap();

    assert_eq!(
        GenericGraphViewGroupRole::SemanticBoundary,
        data.views[0].groups[0].role
    );
    assert_eq!(None, data.views[0].groups[0].semantic_source_id);
}

#[test]
fn generic_graph_group_role_round_trips_layout_only() {
    let data: GenericGraphPluginData = serde_json::from_str(
        r#"{
          "views": [
            {
              "id": "main",
              "label": "Main",
              "nodes": ["api"],
              "relationships": [],
              "groups": [
                {
                  "id": "visual-column",
                  "label": "Visual Column",
                  "members": ["api"],
                  "role": "layout-only"
                }
              ]
            }
          ]
        }"#,
    )
    .unwrap();

    assert_eq!(
        GenericGraphViewGroupRole::LayoutOnly,
        data.views[0].groups[0].role
    );
}

#[test]
fn layout_group_provenance_round_trips_visual_only_object_shape() {
    let request: LayoutRequest = serde_json::from_str(
        r#"{
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
        }"#,
    )
    .unwrap();

    assert_eq!(GroupProvenance::visual_only(), request.groups[0].provenance);
}

#[test]
fn render_policy_roundtrips() {
    let text =
        std::fs::read_to_string(workspace_file("fixtures/render-policy/default-svg.json")).unwrap();
    let policy: RenderPolicy = serde_json::from_str(&text).unwrap();
    assert_eq!(
        policy.svg_render_policy_schema_version,
        "svg-render-policy.schema.v1"
    );
    assert_eq!(policy.page.width, 1200.0);
}

#[test]
fn rich_render_policy_roundtrips() {
    let text =
        std::fs::read_to_string(workspace_file("fixtures/render-policy/rich-svg.json")).unwrap();
    let policy: RenderPolicy = serde_json::from_str(&text).unwrap();
    assert_eq!(
        policy
            .style
            .as_ref()
            .unwrap()
            .node
            .as_ref()
            .unwrap()
            .fill
            .as_deref(),
        Some("#ffffff")
    );
    assert_eq!(
        policy
            .style
            .as_ref()
            .unwrap()
            .node_overrides
            .get("api")
            .unwrap()
            .stroke
            .as_deref(),
        Some("#0891b2")
    );
    let serialized = serde_json::to_string(&policy).unwrap();
    let reparsed: RenderPolicy = serde_json::from_str(&serialized).unwrap();
    assert_eq!(reparsed, policy);
}

#[test]
fn render_metadata_round_trips() {
    let metadata = RenderMetadata {
        render_metadata_schema_version: dediren_contracts::RENDER_METADATA_SCHEMA_VERSION
            .to_string(),
        semantic_profile: "archimate".to_string(),
        nodes: [(
            "orders-component".to_string(),
            RenderMetadataSelector {
                selector_type: "ApplicationComponent".to_string(),
                source_id: "orders-component".to_string(),
            },
        )]
        .into(),
        edges: [(
            "orders-realizes-service".to_string(),
            RenderMetadataSelector {
                selector_type: "Realization".to_string(),
                source_id: "orders-realizes-service".to_string(),
            },
        )]
        .into(),
        groups: BTreeMap::new(),
    };

    let json = serde_json::to_string(&metadata).unwrap();
    assert!(json.contains("\"type\":\"ApplicationComponent\""));
    let decoded: RenderMetadata = serde_json::from_str(&json).unwrap();
    assert_eq!(decoded, metadata);
}

#[test]
fn svg_policy_decorator_fields_round_trip() {
    let policy = RenderPolicy {
        svg_render_policy_schema_version: SVG_RENDER_POLICY_SCHEMA_VERSION.to_string(),
        semantic_profile: Some("archimate".to_string()),
        page: Page {
            width: 640.0,
            height: 360.0,
        },
        margin: Margin {
            top: 24.0,
            right: 24.0,
            bottom: 24.0,
            left: 24.0,
        },
        style: Some(SvgStylePolicy {
            node_type_overrides: BTreeMap::from([(
                "ApplicationComponent".to_string(),
                SvgNodeStyle {
                    decorator: Some(SvgNodeDecorator::ArchimateApplicationComponent),
                    ..SvgNodeStyle::default()
                },
            )]),
            edge_type_overrides: BTreeMap::from([(
                "Realization".to_string(),
                SvgEdgeStyle {
                    line_style: Some(SvgEdgeLineStyle::Dashed),
                    marker_end: Some(SvgEdgeMarkerEnd::HollowTriangle),
                    ..SvgEdgeStyle::default()
                },
            )]),
            ..SvgStylePolicy::default()
        }),
    };

    let json = serde_json::to_value(&policy).expect("serialize policy");
    assert_eq!(
        json["style"]["node_type_overrides"]["ApplicationComponent"]["decorator"],
        "archimate_application_component"
    );
    assert_eq!(
        json["style"]["edge_type_overrides"]["Realization"]["line_style"],
        "dashed"
    );
    assert_eq!(
        json["style"]["edge_type_overrides"]["Realization"]["marker_end"],
        "hollow_triangle"
    );

    let round_tripped: RenderPolicy = serde_json::from_value(json).expect("deserialize policy");
    assert_eq!(round_tripped, policy);
}

#[test]
fn render_result_roundtrips() {
    let result = RenderResult {
        render_result_schema_version: "render-result.schema.v1".to_string(),
        artifact_kind: "svg".to_string(),
        content: "<svg></svg>".to_string(),
    };
    let encoded = serde_json::to_string(&result).unwrap();
    let decoded: RenderResult = serde_json::from_str(&encoded).unwrap();
    assert_eq!(decoded.artifact_kind, "svg");
}

#[test]
fn semantic_validation_result_roundtrips() {
    let result = SemanticValidationResult {
        semantic_validation_result_schema_version: SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION
            .to_string(),
        semantic_profile: "archimate".to_string(),
        node_count: 2,
        relationship_count: 1,
    };

    let encoded = serde_json::to_string(&result).unwrap();
    let decoded: SemanticValidationResult = serde_json::from_str(&encoded).unwrap();
    assert_eq!(decoded.semantic_profile, "archimate");
    assert_eq!(decoded.relationship_count, 1);
}

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}
