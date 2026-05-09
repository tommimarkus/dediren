use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, GroupProvenance, LayoutRequest, RenderPolicy,
    RenderResult, SourceDocument,
};
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
        GroupProvenance::SemanticBacked {
            source_id: "system-group".to_string()
        },
        request.groups[0].provenance
    );
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

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}
