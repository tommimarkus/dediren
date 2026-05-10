use std::collections::BTreeMap;
use std::io::Read;

use anyhow::{bail, Context};
use dediren_contracts::{
    CommandEnvelope, GenericGraphPluginData, GroupProvenance, LayoutEdge, LayoutGroup, LayoutLabel,
    LayoutNode, LayoutRequest, RenderMetadata, RenderMetadataSelector, SourceDocument,
    LAYOUT_REQUEST_SCHEMA_VERSION, RENDER_METADATA_SCHEMA_VERSION,
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

    if args.get(1).map(String::as_str) != Some("project") {
        bail!("expected command: project");
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
                width_hint: Some(160.0),
                height_hint: Some(80.0),
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
            })
        })
        .collect::<anyhow::Result<Vec<_>>>()?;

    let groups = selected_view
        .groups
        .iter()
        .map(|group| {
            for member in &group.members {
                if !selected_view.nodes.iter().any(|node_id| node_id == member) {
                    bail!("group {} references node outside view: {member}", group.id);
                }
            }
            Ok(LayoutGroup {
                id: group.id.clone(),
                label: group.label.clone(),
                members: group.members.clone(),
                provenance: GroupProvenance::SemanticBacked {
                    source_id: group.id.clone(),
                },
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

fn project_render_metadata(
    source: &SourceDocument,
    selected_view: &dediren_contracts::GenericGraphView,
) -> anyhow::Result<RenderMetadata> {
    let semantic_profile = if source
        .required_plugins
        .iter()
        .any(|plugin| plugin.id == "archimate-oef")
        || source.plugins.contains_key("archimate-oef")
    {
        "archimate"
    } else {
        "generic-graph"
    }
    .to_string();

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

    Ok(RenderMetadata {
        render_metadata_schema_version: RENDER_METADATA_SCHEMA_VERSION.to_string(),
        semantic_profile,
        nodes,
        edges,
    })
}

fn value_after(args: &[String], flag: &str) -> Option<String> {
    args.windows(2)
        .find(|window| window[0] == flag)
        .map(|window| window[1].clone())
}
