use std::collections::{BTreeMap, BTreeSet};
use std::io::Read;

use anyhow::{bail, Context};
use dediren_archimate::{
    ArchimateJunctionValidationError, ArchimateTypeValidationError, JunctionValidationNode,
    JunctionValidationRelationship,
};
use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, GenericGraphPluginData,
    GenericGraphSemanticProfile, GenericGraphViewGroupRole, GroupProvenance, LayoutEdge,
    LayoutGroup, LayoutLabel, LayoutNode, LayoutRequest, RenderMetadata, RenderMetadataSelector,
    SemanticValidationResult, SourceDocument, LAYOUT_REQUEST_SCHEMA_VERSION,
    RENDER_METADATA_SCHEMA_VERSION, SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION,
};
use serde_json::Value;

const UML_STRUCTURAL_MIN_WIDTH: f64 = 220.0;
const UML_STRUCTURAL_MIN_HEIGHT: f64 = 120.0;
const UML_TEXT_CHAR_WIDTH: f64 = 8.0;
const UML_TEXT_HORIZONTAL_PADDING: f64 = 32.0;
const UML_TITLE_ROW_HEIGHT: f64 = 15.0;
const UML_TITLE_PADDING: f64 = 8.0;
const UML_MEMBER_ROW_HEIGHT: f64 = 14.0;
const UML_COMPARTMENT_PADDING: f64 = 8.0;
const UML_OPERATION_COMPARTMENT_EXTRA: f64 = 14.0;

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("capabilities") {
        println!(
            "{}",
            serde_json::json!({
                "plugin_protocol_version": "plugin.protocol.v1",
                "id": "generic-graph",
                "capabilities": ["semantic-validation", "projection"]
            })
        );
        return Ok(());
    }

    match args.get(1).map(String::as_str) {
        Some("validate") => return validate_from_stdin(&args),
        Some("project") => {}
        _ => bail!("expected command: validate or project"),
    }

    let target = value_after(&args, "--target").context("missing --target")?;
    let view = value_after(&args, "--view").context("missing --view")?;
    if target != "layout-request" && target != "render-metadata" {
        bail!("unsupported target: {target}");
    }

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let source: SourceDocument = serde_json::from_str(&input)?;
    let plugin_data = generic_graph_plugin_data(&source)?;
    if let Err(error) = validate_generic_graph_plugin_data(&plugin_data) {
        exit_with_diagnostic(error.code, &error.message, Some(error.path));
    }
    let selected_view = plugin_data
        .views
        .iter()
        .find(|candidate| candidate.id == view)
        .with_context(|| format!("missing generic-graph view {view}"))?;
    let semantic_profile = source_semantic_profile(&source, &plugin_data);

    if semantic_profile == "archimate" {
        if let Err(error) = validate_archimate_source_types(&source) {
            exit_with_archimate_type_error(error);
        }
        if let Err(error) = validate_archimate_junction_semantics(&source) {
            exit_with_diagnostic(error.code, &error.message, Some(error.path));
        }
    } else if semantic_profile == GenericGraphSemanticProfile::Uml.as_str() {
        if let Err(error) = dediren_uml::validate_source(&source, &plugin_data) {
            exit_with_uml_validation_error(error);
        }
    }

    if target == "render-metadata" {
        let metadata = project_render_metadata(&source, selected_view, semantic_profile)?;
        println!("{}", serde_json::to_string(&CommandEnvelope::ok(metadata))?);
        return Ok(());
    }

    let nodes = selected_view
        .nodes
        .iter()
        .map(|id| {
            let source_node = source
                .nodes
                .iter()
                .find(|node| node.id == *id)
                .with_context(|| format!("view references missing node {id}"))?;
            Ok(LayoutNode {
                id: source_node.id.clone(),
                label: source_node.label.clone(),
                source_id: source_node.id.clone(),
                width_hint: Some(layout_width_hint(semantic_profile, source_node)),
                height_hint: Some(layout_height_hint(semantic_profile, source_node)),
            })
        })
        .collect::<anyhow::Result<Vec<_>>>()?;

    let edges = selected_view
        .relationships
        .iter()
        .map(|id| {
            let relationship = source
                .relationships
                .iter()
                .find(|relationship| relationship.id == *id)
                .with_context(|| format!("view references missing relationship {id}"))?;
            Ok(LayoutEdge {
                id: relationship.id.clone(),
                source: relationship.source.clone(),
                target: relationship.target.clone(),
                label: relationship.label.clone(),
                source_id: relationship.id.clone(),
                relationship_type: Some(relationship.relationship_type.clone()),
            })
        })
        .collect::<anyhow::Result<Vec<_>>>()?;

    let source_node_ids: BTreeSet<_> = source.nodes.iter().map(|node| node.id.as_str()).collect();

    let groups = selected_view
        .groups
        .iter()
        .map(|group| {
            for member in &group.members {
                if !selected_view.nodes.iter().any(|node_id| node_id == member) {
                    bail!("group {} references node outside view: {member}", group.id);
                }
            }

            let provenance = match group.role {
                GenericGraphViewGroupRole::LayoutOnly => GroupProvenance::visual_only(),
                GenericGraphViewGroupRole::SemanticBoundary => {
                    let source_id = group
                        .semantic_source_id
                        .clone()
                        .unwrap_or_else(|| group.id.clone());
                    if group.semantic_source_id.is_some()
                        && !source_node_ids.contains(source_id.as_str())
                    {
                        bail!(
                            "group {} semantic_source_id references missing node: {}",
                            group.id,
                            source_id
                        );
                    }
                    GroupProvenance::semantic_backed(source_id)
                }
            };

            Ok(LayoutGroup {
                id: group.id.clone(),
                label: group.label.clone(),
                members: group.members.clone(),
                provenance,
            })
        })
        .collect::<anyhow::Result<Vec<_>>>()?;

    let labels = nodes
        .iter()
        .map(|node| LayoutLabel {
            owner_id: node.id.clone(),
            text: node.label.clone(),
        })
        .collect();

    let request = LayoutRequest {
        layout_request_schema_version: LAYOUT_REQUEST_SCHEMA_VERSION.to_string(),
        view_id: selected_view.id.clone(),
        nodes,
        edges,
        groups,
        labels,
        constraints: Vec::new(),
        layout_preferences: selected_view.layout_preferences.clone(),
    };

    println!("{}", serde_json::to_string(&CommandEnvelope::ok(request))?);
    Ok(())
}

fn validate_from_stdin(args: &[String]) -> anyhow::Result<()> {
    let Some(profile) = value_after(args, "--profile") else {
        exit_with_diagnostic(
            "DEDIREN_SEMANTIC_PROFILE_REQUIRED",
            "semantic validation requires --profile",
            None,
        );
    };

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let source: SourceDocument = serde_json::from_str(&input)?;
    let plugin_data = generic_graph_plugin_data(&source)?;
    if let Err(error) = validate_generic_graph_plugin_data(&plugin_data) {
        exit_with_diagnostic(error.code, &error.message, Some(error.path));
    }

    match profile.as_str() {
        "archimate" => {
            if let Err(error) = validate_archimate_source_types(&source) {
                exit_with_archimate_type_error(error);
            }
            if let Err(error) = validate_archimate_junction_semantics(&source) {
                exit_with_diagnostic(error.code, &error.message, Some(error.path));
            }
        }
        "uml" => {
            if let Err(error) = dediren_uml::validate_source(&source, &plugin_data) {
                exit_with_uml_validation_error(error);
            }
        }
        _ => {
            exit_with_diagnostic(
                "DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED",
                &format!("unsupported semantic profile: {profile}"),
                Some("profile".to_string()),
            );
        }
    }

    let result = SemanticValidationResult {
        semantic_validation_result_schema_version: SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION
            .to_string(),
        semantic_profile: profile,
        node_count: source.nodes.len(),
        relationship_count: source.relationships.len(),
    };
    println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
    Ok(())
}

fn generic_graph_plugin_data(source: &SourceDocument) -> anyhow::Result<GenericGraphPluginData> {
    let plugin_value = source
        .plugins
        .get("generic-graph")
        .context("missing plugins.generic-graph")?
        .clone();
    Ok(serde_json::from_value(plugin_value)?)
}

struct GenericGraphValidationError {
    code: &'static str,
    message: String,
    path: String,
}

fn validate_generic_graph_plugin_data(
    plugin_data: &GenericGraphPluginData,
) -> Result<(), GenericGraphValidationError> {
    let mut view_ids = BTreeSet::new();
    for (view_index, view) in plugin_data.views.iter().enumerate() {
        if !view_ids.insert(view.id.as_str()) {
            return Err(GenericGraphValidationError {
                code: "DEDIREN_GENERIC_GRAPH_DUPLICATE_VIEW_ID",
                message: format!("duplicate generic-graph view id '{}'", view.id),
                path: format!("$.plugins.generic-graph.views[{view_index}].id"),
            });
        }

        let mut group_ids = BTreeSet::new();
        for (group_index, group) in view.groups.iter().enumerate() {
            if !group_ids.insert(group.id.as_str()) {
                return Err(GenericGraphValidationError {
                    code: "DEDIREN_GENERIC_GRAPH_DUPLICATE_GROUP_ID",
                    message: format!(
                        "duplicate generic-graph group id '{}' in view '{}'",
                        group.id, view.id
                    ),
                    path: format!(
                        "$.plugins.generic-graph.views[{view_index}].groups[{group_index}].id"
                    ),
                });
            }
        }
    }
    Ok(())
}

fn project_render_metadata(
    source: &SourceDocument,
    selected_view: &dediren_contracts::GenericGraphView,
    semantic_profile: &str,
) -> anyhow::Result<RenderMetadata> {
    let mut nodes = BTreeMap::new();
    for id in &selected_view.nodes {
        let source_node = source
            .nodes
            .iter()
            .find(|node| node.id == *id)
            .with_context(|| format!("view references missing node {id}"))?;
        nodes.insert(
            source_node.id.clone(),
            RenderMetadataSelector {
                selector_type: source_node.node_type.clone(),
                source_id: source_node.id.clone(),
                properties: if semantic_profile == GenericGraphSemanticProfile::Uml.as_str() {
                    source_node.properties.get("uml").cloned()
                } else {
                    None
                },
            },
        );
    }

    let mut edges = BTreeMap::new();
    for id in &selected_view.relationships {
        let relationship = source
            .relationships
            .iter()
            .find(|relationship| relationship.id == *id)
            .with_context(|| format!("view references missing relationship {id}"))?;
        edges.insert(
            relationship.id.clone(),
            RenderMetadataSelector {
                selector_type: relationship.relationship_type.clone(),
                source_id: relationship.id.clone(),
                properties: None,
            },
        );
    }

    let mut groups = BTreeMap::new();
    for group in &selected_view.groups {
        if group.role != GenericGraphViewGroupRole::SemanticBoundary {
            continue;
        }
        let Some(source_id) = group.semantic_source_id.as_ref() else {
            continue;
        };
        let source_node = source
            .nodes
            .iter()
            .find(|node| node.id == *source_id)
            .with_context(|| {
                format!(
                    "group {} references missing semantic source {source_id}",
                    group.id
                )
            })?;
        groups.insert(
            group.id.clone(),
            RenderMetadataSelector {
                selector_type: source_node.node_type.clone(),
                source_id: source_node.id.clone(),
                properties: None,
            },
        );
    }

    Ok(RenderMetadata {
        render_metadata_schema_version: RENDER_METADATA_SCHEMA_VERSION.to_string(),
        semantic_profile: semantic_profile.to_string(),
        nodes,
        edges,
        groups,
    })
}

fn source_semantic_profile(
    source: &SourceDocument,
    plugin_data: &GenericGraphPluginData,
) -> &'static str {
    if let Some(profile) = plugin_data.semantic_profile {
        profile.as_str()
    } else if source
        .required_plugins
        .iter()
        .any(|plugin| plugin.id == "archimate-oef")
        || source.plugins.contains_key("archimate-oef")
    {
        "archimate"
    } else {
        "generic-graph"
    }
}

fn layout_width_hint(semantic_profile: &str, source_node: &dediren_contracts::SourceNode) -> f64 {
    if semantic_profile == GenericGraphSemanticProfile::Archimate.as_str()
        && dediren_archimate::is_relationship_connector_type(&source_node.node_type)
    {
        28.0
    } else if semantic_profile == GenericGraphSemanticProfile::Uml.as_str()
        && dediren_uml::is_compact_activity_node_type(&source_node.node_type)
    {
        32.0
    } else if semantic_profile == GenericGraphSemanticProfile::Uml.as_str()
        && is_large_uml_structural_node_type(&source_node.node_type)
    {
        uml_structural_width_hint(source_node)
    } else {
        160.0
    }
}

fn layout_height_hint(semantic_profile: &str, source_node: &dediren_contracts::SourceNode) -> f64 {
    if semantic_profile == GenericGraphSemanticProfile::Archimate.as_str()
        && dediren_archimate::is_relationship_connector_type(&source_node.node_type)
    {
        28.0
    } else if semantic_profile == GenericGraphSemanticProfile::Uml.as_str()
        && dediren_uml::is_compact_activity_node_type(&source_node.node_type)
    {
        32.0
    } else if semantic_profile == GenericGraphSemanticProfile::Uml.as_str()
        && is_large_uml_structural_node_type(&source_node.node_type)
    {
        uml_structural_height_hint(source_node)
    } else {
        80.0
    }
}

fn is_large_uml_structural_node_type(node_type: &str) -> bool {
    matches!(
        node_type,
        "Class" | "Interface" | "DataType" | "Enumeration"
    )
}

fn uml_structural_width_hint(source_node: &dediren_contracts::SourceNode) -> f64 {
    let properties = source_node.properties.get("uml");
    let max_chars =
        uml_classifier_line_lengths(&source_node.node_type, &source_node.label, properties)
            .into_iter()
            .max()
            .unwrap_or_else(|| source_node.label.chars().count());
    round_up(
        (max_chars as f64 * UML_TEXT_CHAR_WIDTH + UML_TEXT_HORIZONTAL_PADDING)
            .max(UML_STRUCTURAL_MIN_WIDTH),
        20.0,
    )
}

fn uml_structural_height_hint(source_node: &dediren_contracts::SourceNode) -> f64 {
    let properties = source_node.properties.get("uml");
    let title_height = uml_title_height(&source_node.node_type);
    let attribute_count = if source_node.node_type == "Enumeration" {
        uml_array_len(properties, "literals")
    } else {
        uml_array_len(properties, "attributes")
    };
    let operation_count = if source_node.node_type == "Enumeration" {
        0
    } else {
        uml_array_len(properties, "operations")
    };
    let operation_extra = if operation_count > 0 {
        UML_OPERATION_COMPARTMENT_EXTRA
    } else {
        0.0
    };

    round_up(
        (title_height
            + uml_compartment_height(attribute_count)
            + uml_compartment_height(operation_count)
            + operation_extra)
            .max(UML_STRUCTURAL_MIN_HEIGHT),
        10.0,
    )
}

fn uml_classifier_line_lengths(
    node_type: &str,
    label: &str,
    properties: Option<&Value>,
) -> Vec<usize> {
    let mut lengths = Vec::new();
    if let Some(stereotype_len) = uml_stereotype_char_count(node_type) {
        lengths.push(stereotype_len);
    }
    lengths.push(label.chars().count());

    if node_type == "Enumeration" {
        lengths.extend(
            uml_string_values(properties, "literals")
                .map(str::chars)
                .map(Iterator::count),
        );
    } else {
        lengths.extend(
            uml_array_values(properties, "attributes")
                .map(|attribute| uml_attribute_line(attribute).chars().count()),
        );
        lengths.extend(
            uml_array_values(properties, "operations")
                .map(|operation| uml_operation_line(operation).chars().count()),
        );
    }

    lengths
}

fn uml_title_height(node_type: &str) -> f64 {
    let title_lines = if uml_stereotype_char_count(node_type).is_some() {
        2.0
    } else {
        1.0
    };
    (title_lines * UML_TITLE_ROW_HEIGHT + UML_TITLE_PADDING).max(28.0)
}

fn uml_stereotype_char_count(node_type: &str) -> Option<usize> {
    match node_type {
        "Enumeration" => Some(13),
        "Interface" => Some(11),
        "DataType" => Some(10),
        _ => None,
    }
}

fn uml_compartment_height(row_count: usize) -> f64 {
    if row_count == 0 {
        0.0
    } else {
        row_count as f64 * UML_MEMBER_ROW_HEIGHT + UML_COMPARTMENT_PADDING
    }
}

fn uml_array_len(properties: Option<&Value>, key: &str) -> usize {
    uml_array_values(properties, key).count()
}

fn uml_array_values<'a>(
    properties: Option<&'a Value>,
    key: &str,
) -> impl Iterator<Item = &'a Value> {
    properties
        .and_then(|properties| properties.get(key))
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
}

fn uml_string_values<'a>(
    properties: Option<&'a Value>,
    key: &str,
) -> impl Iterator<Item = &'a str> {
    uml_array_values(properties, key).filter_map(Value::as_str)
}

fn uml_attribute_line(attribute: &Value) -> String {
    let visibility = uml_visibility_symbol(attribute.get("visibility").and_then(Value::as_str));
    let name = attribute
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let attribute_type = attribute
        .get("type")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if attribute_type.is_empty() {
        format!("{visibility} {name}")
    } else {
        format!("{visibility} {name} : {attribute_type}")
    }
}

fn uml_operation_line(operation: &Value) -> String {
    let visibility = uml_visibility_symbol(operation.get("visibility").and_then(Value::as_str));
    let name = operation
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let parameters = operation
        .get("parameters")
        .and_then(Value::as_array)
        .map(|parameters| {
            parameters
                .iter()
                .map(uml_parameter_text)
                .collect::<Vec<_>>()
                .join(", ")
        })
        .unwrap_or_default();
    let return_type = operation
        .get("return_type")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if return_type.is_empty() {
        format!("{visibility} {name}({parameters})")
    } else {
        format!("{visibility} {name}({parameters}) : {return_type}")
    }
}

fn uml_parameter_text(parameter: &Value) -> String {
    let name = parameter
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let parameter_type = parameter
        .get("type")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if parameter_type.is_empty() {
        name.to_string()
    } else if name.is_empty() {
        parameter_type.to_string()
    } else {
        format!("{name} : {parameter_type}")
    }
}

fn uml_visibility_symbol(visibility: Option<&str>) -> &'static str {
    match visibility {
        Some("private") => "-",
        Some("protected") => "#",
        Some("package") => "~",
        _ => "+",
    }
}

fn round_up(value: f64, step: f64) -> f64 {
    (value / step).ceil() * step
}

fn validate_archimate_source_types(
    source: &SourceDocument,
) -> Result<(), ArchimateTypeValidationError> {
    let mut node_types = BTreeMap::new();

    for (index, node) in source.nodes.iter().enumerate() {
        dediren_archimate::validate_element_type(
            &node.node_type,
            format!("$.nodes[{index}].type"),
        )?;
        node_types.insert(node.id.as_str(), node.node_type.as_str());
    }
    for (index, relationship) in source.relationships.iter().enumerate() {
        dediren_archimate::validate_relationship_type(
            &relationship.relationship_type,
            format!("$.relationships[{index}].type"),
        )?;

        let Some(source_type) = node_types.get(relationship.source.as_str()) else {
            continue;
        };
        let Some(target_type) = node_types.get(relationship.target.as_str()) else {
            continue;
        };

        dediren_archimate::validate_relationship_endpoint_types(
            &relationship.relationship_type,
            source_type,
            target_type,
            format!("$.relationships[{index}]"),
        )?;
    }
    Ok(())
}

fn validate_archimate_junction_semantics(
    source: &SourceDocument,
) -> Result<(), ArchimateJunctionValidationError> {
    let nodes = source
        .nodes
        .iter()
        .enumerate()
        .map(|(index, node)| JunctionValidationNode {
            id: node.id.clone(),
            node_type: node.node_type.clone(),
            path: format!("$.nodes[{index}]"),
        })
        .collect::<Vec<_>>();
    let relationships = source
        .relationships
        .iter()
        .map(|relationship| JunctionValidationRelationship {
            relationship_type: relationship.relationship_type.clone(),
            source: relationship.source.clone(),
            target: relationship.target.clone(),
        })
        .collect::<Vec<_>>();

    dediren_archimate::validate_junction_relationship_semantics(&nodes, &relationships)
}

fn exit_with_archimate_type_error(error: ArchimateTypeValidationError) -> ! {
    exit_with_diagnostic(error.code(), &error.message(), Some(error.path));
}

fn exit_with_uml_validation_error(error: dediren_uml::UmlValidationError) -> ! {
    exit_with_diagnostic(error.code(), &error.message(), Some(error.path));
}

fn exit_with_diagnostic(code: &str, message: &str, path: Option<String>) -> ! {
    let diagnostic = Diagnostic {
        code: code.to_string(),
        severity: DiagnosticSeverity::Error,
        message: message.to_string(),
        path,
    };
    println!(
        "{}",
        serde_json::to_string(&CommandEnvelope::<serde_json::Value>::error(vec![
            diagnostic
        ]))
        .unwrap()
    );
    std::process::exit(3);
}

fn value_after(args: &[String], flag: &str) -> Option<String> {
    args.windows(2)
        .find(|window| window[0] == flag)
        .map(|window| window[1].clone())
}
