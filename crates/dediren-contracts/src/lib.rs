use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::BTreeMap;

pub const MODEL_SCHEMA_VERSION: &str = "model.schema.v1";
pub const ENVELOPE_SCHEMA_VERSION: &str = "envelope.schema.v1";
pub const PLUGIN_PROTOCOL_VERSION: &str = "plugin.protocol.v1";
pub const LAYOUT_REQUEST_SCHEMA_VERSION: &str = "layout-request.schema.v1";
pub const LAYOUT_RESULT_SCHEMA_VERSION: &str = "layout-result.schema.v1";
pub const SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION: &str = "semantic-validation-result.schema.v1";
pub const RENDER_RESULT_SCHEMA_VERSION: &str = "render-result.schema.v1";
pub const SVG_RENDER_POLICY_SCHEMA_VERSION: &str = "svg-render-policy.schema.v1";
pub const RENDER_METADATA_SCHEMA_VERSION: &str = "render-metadata.schema.v1";
pub const EXPORT_REQUEST_SCHEMA_VERSION: &str = "export-request.schema.v1";
pub const EXPORT_RESULT_SCHEMA_VERSION: &str = "export-result.schema.v1";
pub const OEF_EXPORT_POLICY_SCHEMA_VERSION: &str = "oef-export-policy.schema.v1";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DiagnosticSeverity {
    Info,
    Warning,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Diagnostic {
    pub code: String,
    pub severity: DiagnosticSeverity,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CommandEnvelope<T> {
    pub envelope_schema_version: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<T>,
    #[serde(default)]
    pub diagnostics: Vec<Diagnostic>,
}

impl<T> CommandEnvelope<T> {
    pub fn ok(data: T) -> Self {
        Self {
            envelope_schema_version: ENVELOPE_SCHEMA_VERSION.to_string(),
            status: "ok".to_string(),
            data: Some(data),
            diagnostics: Vec::new(),
        }
    }

    pub fn error(diagnostics: Vec<Diagnostic>) -> Self {
        Self {
            envelope_schema_version: ENVELOPE_SCHEMA_VERSION.to_string(),
            status: "error".to_string(),
            data: None,
            diagnostics,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SourceDocument {
    pub model_schema_version: String,
    #[serde(default)]
    pub required_plugins: Vec<PluginRequirement>,
    #[serde(default)]
    pub nodes: Vec<SourceNode>,
    #[serde(default)]
    pub relationships: Vec<SourceRelationship>,
    #[serde(default)]
    pub plugins: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PluginRequirement {
    pub id: String,
    pub version: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SourceNode {
    pub id: String,
    #[serde(rename = "type")]
    pub node_type: String,
    pub label: String,
    #[serde(default)]
    pub properties: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SourceRelationship {
    pub id: String,
    #[serde(rename = "type")]
    pub relationship_type: String,
    pub source: String,
    pub target: String,
    pub label: String,
    #[serde(default)]
    pub properties: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GenericGraphPluginData {
    pub views: Vec<GenericGraphView>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GenericGraphView {
    pub id: String,
    pub label: String,
    pub nodes: Vec<String>,
    pub relationships: Vec<String>,
    #[serde(default)]
    pub groups: Vec<GenericGraphViewGroup>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GenericGraphViewGroup {
    pub id: String,
    pub label: String,
    pub members: Vec<String>,
    #[serde(default)]
    pub role: GenericGraphViewGroupRole,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub semantic_source_id: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum GenericGraphViewGroupRole {
    SemanticBoundary,
    LayoutOnly,
}

impl Default for GenericGraphViewGroupRole {
    fn default() -> Self {
        Self::SemanticBoundary
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LayoutRequest {
    pub layout_request_schema_version: String,
    pub view_id: String,
    #[serde(default)]
    pub nodes: Vec<LayoutNode>,
    #[serde(default)]
    pub edges: Vec<LayoutEdge>,
    #[serde(default)]
    pub groups: Vec<LayoutGroup>,
    #[serde(default)]
    pub labels: Vec<LayoutLabel>,
    #[serde(default)]
    pub constraints: Vec<LayoutConstraint>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LayoutNode {
    pub id: String,
    pub label: String,
    pub source_id: String,
    #[serde(default)]
    pub width_hint: Option<f64>,
    #[serde(default)]
    pub height_hint: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LayoutEdge {
    pub id: String,
    pub source: String,
    pub target: String,
    pub label: String,
    pub source_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub relationship_type: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LayoutGroup {
    pub id: String,
    pub label: String,
    pub members: Vec<String>,
    pub provenance: GroupProvenance,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GroupProvenance {
    #[serde(default, skip_serializing_if = "is_false")]
    pub visual_only: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub semantic_backed: Option<SemanticBackedGroupProvenance>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SemanticBackedGroupProvenance {
    pub source_id: String,
}

impl GroupProvenance {
    pub fn visual_only() -> Self {
        Self {
            visual_only: true,
            semantic_backed: None,
        }
    }

    pub fn semantic_backed(source_id: impl Into<String>) -> Self {
        Self {
            visual_only: false,
            semantic_backed: Some(SemanticBackedGroupProvenance {
                source_id: source_id.into(),
            }),
        }
    }

    pub fn semantic_source_id(&self) -> Option<&str> {
        self.semantic_backed
            .as_ref()
            .map(|semantic| semantic.source_id.as_str())
    }
}

fn is_false(value: &bool) -> bool {
    !*value
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LayoutLabel {
    pub owner_id: String,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LayoutConstraint {
    pub id: String,
    pub kind: String,
    pub subjects: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SemanticValidationResult {
    pub semantic_validation_result_schema_version: String,
    pub semantic_profile: String,
    pub node_count: usize,
    pub relationship_count: usize,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LayoutResult {
    pub layout_result_schema_version: String,
    pub view_id: String,
    #[serde(default)]
    pub nodes: Vec<LaidOutNode>,
    #[serde(default)]
    pub edges: Vec<LaidOutEdge>,
    #[serde(default)]
    pub groups: Vec<LaidOutGroup>,
    #[serde(default)]
    pub warnings: Vec<Diagnostic>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LaidOutNode {
    pub id: String,
    pub source_id: String,
    pub projection_id: String,
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LaidOutEdge {
    pub id: String,
    pub source: String,
    pub target: String,
    pub source_id: String,
    pub projection_id: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub routing_hints: Vec<String>,
    pub points: Vec<Point>,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LaidOutGroup {
    pub id: String,
    pub source_id: String,
    pub projection_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub provenance: Option<GroupProvenance>,
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    pub members: Vec<String>,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Point {
    pub x: f64,
    pub y: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RenderPolicy {
    pub svg_render_policy_schema_version: String,
    #[serde(default)]
    pub semantic_profile: Option<String>,
    pub page: Page,
    pub margin: Margin,
    #[serde(default)]
    pub style: Option<SvgStylePolicy>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RenderMetadata {
    pub render_metadata_schema_version: String,
    pub semantic_profile: String,
    #[serde(default)]
    pub nodes: BTreeMap<String, RenderMetadataSelector>,
    #[serde(default)]
    pub edges: BTreeMap<String, RenderMetadataSelector>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub groups: BTreeMap<String, RenderMetadataSelector>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RenderMetadataSelector {
    #[serde(rename = "type")]
    pub selector_type: String,
    pub source_id: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Page {
    pub width: f64,
    pub height: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Margin {
    pub top: f64,
    pub right: f64,
    pub bottom: f64,
    pub left: f64,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgStylePolicy {
    #[serde(default)]
    pub background: Option<SvgBackgroundStyle>,
    #[serde(default)]
    pub font: Option<SvgFontStyle>,
    #[serde(default)]
    pub node: Option<SvgNodeStyle>,
    #[serde(default)]
    pub edge: Option<SvgEdgeStyle>,
    #[serde(default)]
    pub group: Option<SvgGroupStyle>,
    #[serde(default)]
    pub node_type_overrides: BTreeMap<String, SvgNodeStyle>,
    #[serde(default)]
    pub edge_type_overrides: BTreeMap<String, SvgEdgeStyle>,
    #[serde(default)]
    pub group_type_overrides: BTreeMap<String, SvgGroupStyle>,
    #[serde(default)]
    pub node_overrides: BTreeMap<String, SvgNodeStyle>,
    #[serde(default)]
    pub edge_overrides: BTreeMap<String, SvgEdgeStyle>,
    #[serde(default)]
    pub group_overrides: BTreeMap<String, SvgGroupStyle>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgBackgroundStyle {
    #[serde(default)]
    pub fill: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgFontStyle {
    #[serde(default)]
    pub family: Option<String>,
    #[serde(default)]
    pub size: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgNodeStyle {
    #[serde(default)]
    pub fill: Option<String>,
    #[serde(default)]
    pub stroke: Option<String>,
    #[serde(default)]
    pub stroke_width: Option<f64>,
    #[serde(default)]
    pub rx: Option<f64>,
    #[serde(default)]
    pub label_fill: Option<String>,
    #[serde(default)]
    pub decorator: Option<SvgNodeDecorator>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgEdgeStyle {
    #[serde(default)]
    pub stroke: Option<String>,
    #[serde(default)]
    pub stroke_width: Option<f64>,
    #[serde(default)]
    pub label_fill: Option<String>,
    #[serde(default)]
    pub line_style: Option<SvgEdgeLineStyle>,
    #[serde(default)]
    pub marker_start: Option<SvgEdgeMarkerEnd>,
    #[serde(default)]
    pub marker_end: Option<SvgEdgeMarkerEnd>,
    #[serde(default)]
    pub label_horizontal_position: Option<SvgEdgeLabelHorizontalPosition>,
    #[serde(default)]
    pub label_horizontal_side: Option<SvgEdgeLabelHorizontalSide>,
    #[serde(default)]
    pub label_vertical_position: Option<SvgEdgeLabelVerticalPosition>,
    #[serde(default)]
    pub label_vertical_side: Option<SvgEdgeLabelVerticalSide>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgNodeDecorator {
    ArchimatePlateau,
    ArchimateWorkPackage,
    ArchimateDeliverable,
    ArchimateImplementationEvent,
    ArchimateGap,
    ArchimateGrouping,
    ArchimateAndJunction,
    ArchimateOrJunction,
    ArchimateLocation,
    ArchimateStakeholder,
    ArchimateDriver,
    ArchimateAssessment,
    ArchimateGoal,
    ArchimateOutcome,
    ArchimateValue,
    ArchimateMeaning,
    ArchimateConstraint,
    ArchimateRequirement,
    ArchimatePrinciple,
    ArchimateCourseOfAction,
    ArchimateResource,
    ArchimateValueStream,
    ArchimateCapability,
    ArchimateBusinessInterface,
    ArchimateBusinessCollaboration,
    ArchimateBusinessActor,
    ArchimateBusinessRole,
    ArchimateBusinessService,
    ArchimateBusinessInteraction,
    ArchimateBusinessFunction,
    ArchimateBusinessProcess,
    ArchimateBusinessEvent,
    ArchimateProduct,
    ArchimateBusinessObject,
    ArchimateContract,
    ArchimateRepresentation,
    ArchimateApplicationInterface,
    ArchimateApplicationCollaboration,
    ArchimateApplicationComponent,
    ArchimateApplicationService,
    ArchimateApplicationInteraction,
    ArchimateApplicationFunction,
    ArchimateApplicationProcess,
    ArchimateApplicationEvent,
    ArchimateDataObject,
    ArchimateTechnologyInterface,
    ArchimateTechnologyCollaboration,
    ArchimateTechnologyNode,
    ArchimateSystemSoftware,
    ArchimateDevice,
    ArchimateFacility,
    ArchimateEquipment,
    ArchimatePath,
    ArchimateTechnologyService,
    ArchimateTechnologyInteraction,
    ArchimateTechnologyFunction,
    ArchimateTechnologyProcess,
    ArchimateTechnologyEvent,
    ArchimateArtifact,
    ArchimateMaterial,
    ArchimateCommunicationNetwork,
    ArchimateDistributionNetwork,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgEdgeLineStyle {
    Solid,
    Dashed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgEdgeMarkerEnd {
    FilledArrow,
    OpenArrow,
    HollowTriangle,
    FilledDiamond,
    HollowDiamond,
    FilledCircle,
    HollowCircle,
    None,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgEdgeLabelHorizontalPosition {
    NearStart,
    Center,
    NearEnd,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgEdgeLabelHorizontalSide {
    Auto,
    Above,
    Below,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgEdgeLabelVerticalPosition {
    NearStart,
    Center,
    NearEnd,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SvgEdgeLabelVerticalSide {
    Left,
    Right,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SvgGroupStyle {
    #[serde(default)]
    pub fill: Option<String>,
    #[serde(default)]
    pub stroke: Option<String>,
    #[serde(default)]
    pub stroke_width: Option<f64>,
    #[serde(default)]
    pub rx: Option<f64>,
    #[serde(default)]
    pub label_fill: Option<String>,
    #[serde(default)]
    pub label_size: Option<f64>,
    #[serde(default)]
    pub decorator: Option<SvgNodeDecorator>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RenderResult {
    pub render_result_schema_version: String,
    pub artifact_kind: String,
    pub content: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OefExportPolicy {
    pub oef_export_policy_schema_version: String,
    pub model_identifier: String,
    pub model_name: String,
    pub view_identifier: String,
    pub view_name: String,
    pub viewpoint: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OefExportInput {
    pub export_request_schema_version: String,
    pub source: SourceDocument,
    pub layout_result: LayoutResult,
    pub policy: OefExportPolicy,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ExportResult {
    pub export_result_schema_version: String,
    pub artifact_kind: String,
    pub content: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PluginManifest {
    pub plugin_manifest_schema_version: String,
    pub id: String,
    pub version: String,
    pub executable: String,
    pub capabilities: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RuntimeCapabilities {
    pub plugin_protocol_version: String,
    pub id: String,
    pub capabilities: Vec<String>,
    #[serde(default)]
    pub runtime: Option<Value>,
}
