use std::collections::{BTreeMap, BTreeSet};
use std::io::Read;

use anyhow::{bail, Context};
use dediren_archimate::{
    ArchimateJunctionValidationError, ArchimateTypeValidationError, JunctionValidationNode,
    JunctionValidationRelationship,
};
use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, GenericGraphPluginData,
    GenericGraphViewGroupRole, GroupProvenance, LayoutEdge, LayoutGroup, LayoutLabel, LayoutNode,
    LayoutRequest, RenderMetadata, RenderMetadataSelector, SemanticValidationResult,
    SourceDocument, LAYOUT_REQUEST_SCHEMA_VERSION, RENDER_METADATA_SCHEMA_VERSION,
    SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION,
};

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
    let plugin_value = source
        .plugins
        .get("generic-graph")
        .context("missing plugins.generic-graph")?
        .clone();
    let plugin_data: GenericGraphPluginData = serde_json::from_value(plugin_value)?;
    let selected_view = plugin_data
        .views
        .iter()
        .find(|candidate| candidate.id == view)
        .with_context(|| format!("missing generic-graph view {view}"))?;

    if source_semantic_profile(&source) == "archimate" {
        if let Err(error) = validate_archimate_source_types(&source) {
            exit_with_archimate_type_error(error);
        }
        if let Err(error) = validate_archimate_junction_semantics(&source) {
            exit_with_diagnostic(&error.code, &error.message, Some(error.path));
        }
    }

    if target == "render-metadata" {
        let metadata = project_render_metadata(&source, selected_view)?;
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
                width_hint: Some(layout_width_hint(&source, source_node)),
                height_hint: Some(layout_height_hint(&source, source_node)),
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
    if profile != "archimate" {
        exit_with_diagnostic(
            "DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED",
            &format!("unsupported semantic profile: {profile}"),
            Some("profile".to_string()),
        );
    }

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let source: SourceDocument = serde_json::from_str(&input)?;
    if let Err(error) = validate_archimate_source_types(&source) {
        exit_with_archimate_type_error(error);
    }
    if let Err(error) = validate_archimate_junction_semantics(&source) {
        exit_with_diagnostic(&error.code, &error.message, Some(error.path));
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

fn project_render_metadata(
    source: &SourceDocument,
    selected_view: &dediren_contracts::GenericGraphView,
) -> anyhow::Result<RenderMetadata> {
    let semantic_profile = source_semantic_profile(source).to_string();

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
            },
        );
    }

    Ok(RenderMetadata {
        render_metadata_schema_version: RENDER_METADATA_SCHEMA_VERSION.to_string(),
        semantic_profile,
        nodes,
        edges,
        groups,
    })
}

fn source_semantic_profile(source: &SourceDocument) -> &'static str {
    if source
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

fn layout_width_hint(source: &SourceDocument, source_node: &dediren_contracts::SourceNode) -> f64 {
    if source_semantic_profile(source) == "archimate"
        && dediren_archimate::is_relationship_connector_type(&source_node.node_type)
    {
        28.0
    } else {
        160.0
    }
}

fn layout_height_hint(source: &SourceDocument, source_node: &dediren_contracts::SourceNode) -> f64 {
    if source_semantic_profile(source) == "archimate"
        && dediren_archimate::is_relationship_connector_type(&source_node.node_type)
    {
        28.0
    } else {
        80.0
    }
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
