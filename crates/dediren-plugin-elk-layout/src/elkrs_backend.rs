use std::collections::{BTreeMap, BTreeSet};

use dediren_contracts::{
    Diagnostic, DiagnosticSeverity, LaidOutEdge, LaidOutGroup, LaidOutNode, LayoutDensity,
    LayoutDirection, LayoutEndpointMerging, LayoutGroup, LayoutPreferences, LayoutRequest,
    LayoutResult, LayoutRoutingProfile, LayoutWrapping, Point, LAYOUT_RESULT_SCHEMA_VERSION,
};
use elkrs_core::diagnostic::Severity as ElkrsSeverity;
use elkrs_core::geometry::Size;
use elkrs_core::graph::{ElementId, ElementRef, ElkEdge, ElkGraph, ElkLabel, ElkNode};
use elkrs_core::layout::LayoutError;
use elkrs_core::options::{
    Algorithm, Direction, EdgeRouting, HierarchyHandling, DEFAULT_EDGE_EDGE_SPACING,
    DEFAULT_EDGE_NODE_SPACING, DEFAULT_LAYER_NODE_NODE_SPACING, DEFAULT_NODE_NODE_SPACING,
};
use elkrs_layered::{LayeredLayout, LayoutAlgorithm};

const DEFAULT_NODE_WIDTH: f64 = 160.0;
const DEFAULT_NODE_HEIGHT: f64 = 80.0;
const DEFAULT_GROUP_WIDTH: f64 = 320.0;
const DEFAULT_GROUP_HEIGHT: f64 = 200.0;

pub fn layout(request: &LayoutRequest) -> Result<LayoutResult, Vec<Diagnostic>> {
    validate_unique_ids(request)?;

    let mut warnings = preference_warnings(request.layout_preferences.as_ref());
    let group_mapping = group_mapping(request, &mut warnings);
    let mut graph = build_graph(request, &group_mapping);

    apply_preferences(&mut graph, request.layout_preferences.as_ref());

    let report = LayeredLayout
        .layout(&mut graph)
        .map_err(|error| vec![layout_error_diagnostic(error)])?;
    warnings.extend(report.diagnostics.into_iter().map(elkrs_diagnostic));

    Ok(layout_result(request, &graph, warnings))
}

fn validate_unique_ids(request: &LayoutRequest) -> Result<(), Vec<Diagnostic>> {
    let mut seen = BTreeSet::new();
    let mut diagnostics = Vec::new();

    for id in request
        .nodes
        .iter()
        .map(|node| node.id.as_str())
        .chain(request.groups.iter().map(|group| group.id.as_str()))
        .chain(request.edges.iter().map(|edge| edge.id.as_str()))
    {
        if !seen.insert(id.to_string()) {
            diagnostics.push(Diagnostic {
                code: "DEDIREN_ELK_DUPLICATE_ID".to_string(),
                severity: DiagnosticSeverity::Error,
                message: format!("layout request contains duplicate id `{id}`"),
                path: None,
            });
        }
    }

    if diagnostics.is_empty() {
        Ok(())
    } else {
        Err(diagnostics)
    }
}

fn preference_warnings(preferences: Option<&LayoutPreferences>) -> Vec<Diagnostic> {
    let Some(preferences) = preferences else {
        return Vec::new();
    };
    let mut warnings = Vec::new();

    if matches!(
        preferences.wrapping,
        Some(LayoutWrapping::Auto | LayoutWrapping::MultiEdge)
    ) {
        warnings.push(option_unsupported(
            "wrapping",
            "wrapping auto and multi-edge modes are not supported by the Rust ELK backend yet",
        ));
    }

    if let Some(routing) = &preferences.routing {
        if matches!(
            routing.endpoint_merging,
            Some(LayoutEndpointMerging::Local | LayoutEndpointMerging::Auto)
        ) {
            warnings.push(option_unsupported(
                "endpoint_merging",
                "endpoint_merging local and auto modes are not supported by the Rust ELK backend yet",
            ));
        }
    }

    warnings
}

fn group_mapping(
    request: &LayoutRequest,
    warnings: &mut Vec<Diagnostic>,
) -> BTreeMap<String, String> {
    let node_ids = request
        .nodes
        .iter()
        .map(|node| node.id.as_str())
        .collect::<BTreeSet<_>>();
    let mut memberships = BTreeMap::new();

    for group in &request.groups {
        let mut group_seen = BTreeSet::new();
        for member in &group.members {
            if !node_ids.contains(member.as_str()) {
                warnings.push(group_membership_warning(
                    &group.id,
                    member,
                    "group references a missing member",
                ));
                continue;
            }
            if !group_seen.insert(member.as_str()) {
                warnings.push(group_membership_warning(
                    &group.id,
                    member,
                    "group lists a member more than once",
                ));
                continue;
            }
            if let Some(existing_group) = memberships.insert(member.clone(), group.id.clone()) {
                warnings.push(group_membership_warning(
                    &group.id,
                    member,
                    &format!("member already belongs to group `{existing_group}`"),
                ));
                memberships.insert(member.clone(), existing_group);
            }
        }
    }

    memberships
}

fn build_graph(request: &LayoutRequest, group_mapping: &BTreeMap<String, String>) -> ElkGraph {
    let mut graph = ElkGraph::new(request.view_id.as_str());
    let node_by_id = request
        .nodes
        .iter()
        .map(|node| (node.id.as_str(), node))
        .collect::<BTreeMap<_, _>>();

    for group in &request.groups {
        let member_sizes = group
            .members
            .iter()
            .filter(|member| {
                group_mapping
                    .get(*member)
                    .is_some_and(|group_id| group_id == &group.id)
            })
            .filter_map(|member| node_by_id.get(member.as_str()))
            .map(|node| {
                (
                    node.width_hint.unwrap_or(DEFAULT_NODE_WIDTH),
                    node.height_hint.unwrap_or(DEFAULT_NODE_HEIGHT),
                )
            })
            .collect::<Vec<_>>();
        let (group_width, group_height) = group_size_for_members(&member_sizes);
        let mut group_node = elk_node(&group.id, &group.label, group_width, group_height);
        for member in &group.members {
            if group_mapping
                .get(member)
                .is_some_and(|group_id| group_id == &group.id)
            {
                if let Some(node) = node_by_id.get(member.as_str()) {
                    group_node.add_child(elk_node(
                        &node.id,
                        &node.label,
                        node.width_hint.unwrap_or(DEFAULT_NODE_WIDTH),
                        node.height_hint.unwrap_or(DEFAULT_NODE_HEIGHT),
                    ));
                }
            }
        }
        graph.add_node(group_node);
    }

    for node in &request.nodes {
        if group_mapping.contains_key(&node.id) {
            continue;
        }
        graph.add_node(elk_node(
            &node.id,
            &node.label,
            node.width_hint.unwrap_or(DEFAULT_NODE_WIDTH),
            node.height_hint.unwrap_or(DEFAULT_NODE_HEIGHT),
        ));
    }

    for edge in &request.edges {
        let mut elk_edge = ElkEdge::new(
            edge.id.as_str(),
            ElementRef::Node(ElementId::new(edge.source.clone())),
            ElementRef::Node(ElementId::new(edge.target.clone())),
        );
        elk_edge.labels.push(ElkLabel::new(edge.label.clone()));
        graph.add_edge(elk_edge);
    }

    graph
}

fn group_size_for_members(member_sizes: &[(f64, f64)]) -> (f64, f64) {
    if member_sizes.is_empty() {
        return (DEFAULT_GROUP_WIDTH, DEFAULT_GROUP_HEIGHT);
    }

    let max_member_width = member_sizes
        .iter()
        .map(|(width, _)| *width)
        .fold(0.0, f64::max);
    let member_height_total = member_sizes.iter().map(|(_, height)| *height).sum::<f64>();
    let member_gaps = DEFAULT_NODE_NODE_SPACING * member_sizes.len().saturating_sub(1) as f64;
    let padding = DEFAULT_NODE_NODE_SPACING / 2.0;

    (
        DEFAULT_GROUP_WIDTH.max(max_member_width + padding * 2.0),
        DEFAULT_GROUP_HEIGHT.max(member_height_total + member_gaps + padding * 2.0),
    )
}

fn elk_node(id: &str, label: &str, width: f64, height: f64) -> ElkNode {
    let mut node = ElkNode::new(id);
    node.size = Size::new(width, height);
    node.labels.push(ElkLabel::new(label.to_string()));
    node
}

fn apply_preferences(graph: &mut ElkGraph, preferences: Option<&LayoutPreferences>) {
    graph.properties.set_algorithm(Algorithm::Layered);
    graph.properties.set_edge_routing(EdgeRouting::Orthogonal);
    graph
        .properties
        .set_hierarchy_handling(HierarchyHandling::IncludeChildren);

    let Some(preferences) = preferences else {
        return;
    };

    if let Some(direction) = preferences.direction {
        graph.properties.set_direction(elkrs_direction(direction));
    }

    let (node_node, layer_node_node) = density_spacing(preferences.density);
    graph.properties.set_spacing_node_node(node_node);
    graph
        .properties
        .set_spacing_layer_node_node(layer_node_node);

    if let Some(routing) = &preferences.routing {
        let (edge_node, edge_edge) = routing_spacing(routing.profile);
        graph.properties.set_spacing_edge_node(edge_node);
        graph.properties.set_spacing_edge_edge(edge_edge);
    }
}

fn elkrs_direction(direction: LayoutDirection) -> Direction {
    match direction {
        LayoutDirection::Right => Direction::Right,
        LayoutDirection::Left => Direction::Left,
        LayoutDirection::Down => Direction::Down,
        LayoutDirection::Up => Direction::Up,
    }
}

fn density_spacing(density: Option<LayoutDensity>) -> (f64, f64) {
    match density {
        Some(LayoutDensity::Compact) => (48.0, 80.0),
        Some(LayoutDensity::Spacious) => (120.0, 180.0),
        Some(LayoutDensity::Readable) | None => {
            (DEFAULT_NODE_NODE_SPACING, DEFAULT_LAYER_NODE_NODE_SPACING)
        }
    }
}

fn routing_spacing(profile: Option<LayoutRoutingProfile>) -> (f64, f64) {
    match profile {
        Some(LayoutRoutingProfile::Compact) => (12.0, 6.0),
        Some(LayoutRoutingProfile::Spacious) => (36.0, 18.0),
        Some(LayoutRoutingProfile::Readable) | None => {
            (DEFAULT_EDGE_NODE_SPACING, DEFAULT_EDGE_EDGE_SPACING)
        }
    }
}

fn layout_result(
    request: &LayoutRequest,
    graph: &ElkGraph,
    warnings: Vec<Diagnostic>,
) -> LayoutResult {
    let node_lookup = collect_nodes(graph);

    LayoutResult {
        layout_result_schema_version: LAYOUT_RESULT_SCHEMA_VERSION.to_string(),
        view_id: request.view_id.clone(),
        nodes: request
            .nodes
            .iter()
            .filter_map(|node| {
                let elk_node = node_lookup.get(node.id.as_str())?;
                Some(LaidOutNode {
                    id: node.id.clone(),
                    source_id: node.source_id.clone(),
                    projection_id: node.id.clone(),
                    x: elk_node.position.x,
                    y: elk_node.position.y,
                    width: elk_node.size.width,
                    height: elk_node.size.height,
                    label: node.label.clone(),
                })
            })
            .collect(),
        edges: request
            .edges
            .iter()
            .map(|edge| {
                let points = graph
                    .edges
                    .get(&ElementId::new(edge.id.clone()))
                    .and_then(|edge| edge.sections.first())
                    .map(|section| {
                        section
                            .points
                            .iter()
                            .map(|point| Point {
                                x: point.x,
                                y: point.y,
                            })
                            .collect()
                    })
                    .unwrap_or_default();
                LaidOutEdge {
                    id: edge.id.clone(),
                    source: edge.source.clone(),
                    target: edge.target.clone(),
                    source_id: edge.source_id.clone(),
                    projection_id: edge.id.clone(),
                    routing_hints: Vec::new(),
                    points,
                    label: edge.label.clone(),
                }
            })
            .collect(),
        groups: request
            .groups
            .iter()
            .filter_map(|group| {
                let elk_node = node_lookup.get(group.id.as_str())?;
                Some(laid_out_group(group, elk_node))
            })
            .collect(),
        warnings,
    }
}

fn collect_nodes(graph: &ElkGraph) -> BTreeMap<&str, &ElkNode> {
    let mut nodes = BTreeMap::new();
    for node in graph.nodes.values() {
        collect_node(node, &mut nodes);
    }
    nodes
}

fn collect_node<'a>(node: &'a ElkNode, nodes: &mut BTreeMap<&'a str, &'a ElkNode>) {
    nodes.insert(node.id.as_str(), node);
    for child in node.children.values() {
        collect_node(child, nodes);
    }
}

fn laid_out_group(group: &LayoutGroup, node: &ElkNode) -> LaidOutGroup {
    let (x, y, width, height) = group_bounds(node);
    LaidOutGroup {
        id: group.id.clone(),
        source_id: group
            .provenance
            .semantic_source_id()
            .unwrap_or(group.id.as_str())
            .to_string(),
        projection_id: group.id.clone(),
        provenance: Some(group.provenance.clone()),
        x,
        y,
        width,
        height,
        members: node
            .children
            .keys()
            .map(|member_id| member_id.as_str().to_string())
            .collect(),
        label: group.label.clone(),
    }
}

fn group_bounds(node: &ElkNode) -> (f64, f64, f64, f64) {
    let mut min_x = node.position.x;
    let mut min_y = node.position.y;
    let mut max_x = node.position.x + node.size.width;
    let mut max_y = node.position.y + node.size.height;

    for child in node.children.values() {
        min_x = min_x.min(child.position.x);
        min_y = min_y.min(child.position.y);
        max_x = max_x.max(child.position.x + child.size.width);
        max_y = max_y.max(child.position.y + child.size.height);
    }

    (min_x, min_y, max_x - min_x, max_y - min_y)
}

fn option_unsupported(option: &str, message: &str) -> Diagnostic {
    Diagnostic {
        code: "DEDIREN_ELK_OPTION_UNSUPPORTED".to_string(),
        severity: DiagnosticSeverity::Warning,
        message: format!("{option}: {message}"),
        path: None,
    }
}

fn group_membership_warning(group_id: &str, member: &str, message: &str) -> Diagnostic {
    Diagnostic {
        code: "DEDIREN_ELK_GROUP_MEMBERSHIP_WARNING".to_string(),
        severity: DiagnosticSeverity::Warning,
        message: format!("{message}: group `{group_id}`, member `{member}`"),
        path: None,
    }
}

fn elkrs_diagnostic(diagnostic: elkrs_core::diagnostic::Diagnostic) -> Diagnostic {
    let code = if diagnostic.code.starts_with("ELKRS_") {
        diagnostic.code.to_string()
    } else {
        format!("ELKRS_{}", diagnostic.code)
    };
    Diagnostic {
        code,
        severity: match diagnostic.severity {
            ElkrsSeverity::Warning => DiagnosticSeverity::Warning,
            ElkrsSeverity::Error => DiagnosticSeverity::Error,
        },
        message: diagnostic.message,
        path: diagnostic.element_id,
    }
}

fn layout_error_diagnostic(error: LayoutError) -> Diagnostic {
    let code = match &error {
        LayoutError::UnsupportedAlgorithm(_) => "ELKRS_UNSUPPORTED_ALGORITHM",
        LayoutError::MissingEndpoint(_) => "ELKRS_MISSING_ENDPOINT",
        LayoutError::InvalidHierarchy(_) => "ELKRS_INVALID_HIERARCHY",
        LayoutError::InvalidOption(_) => "ELKRS_INVALID_OPTION",
        LayoutError::PhaseFailed { .. } => "ELKRS_LAYOUT_PHASE_FAILED",
    };
    Diagnostic {
        code: code.to_string(),
        severity: DiagnosticSeverity::Error,
        message: error.to_string(),
        path: None,
    }
}

#[cfg(test)]
mod tests {
    use dediren_contracts::{
        DiagnosticSeverity, GroupProvenance, LayoutDirection, LayoutEdge, LayoutEndpointMerging,
        LayoutGroup, LayoutNode, LayoutPreferences, LayoutRequest, LayoutRoutingPreferences,
    };

    use super::*;

    #[test]
    fn direction_down_places_targets_below_source() {
        let request = request_with_preferences(LayoutPreferences {
            direction: Some(LayoutDirection::Down),
            density: None,
            wrapping: None,
            routing: None,
        });

        let result = layout(&request).unwrap();
        let source = result
            .nodes
            .iter()
            .find(|node| node.id == "source")
            .unwrap();
        let target = result
            .nodes
            .iter()
            .find(|node| node.id == "target")
            .unwrap();

        assert!(target.y > source.y);
    }

    #[test]
    fn duplicate_id_returns_dediren_elk_duplicate_id() {
        let mut request = basic_request();
        request.nodes.push(LayoutNode {
            id: "source".to_string(),
            label: "Duplicate".to_string(),
            source_id: "duplicate".to_string(),
            width_hint: None,
            height_hint: None,
        });

        let diagnostics = layout(&request).unwrap_err();

        assert!(diagnostics.iter().any(|diagnostic| {
            diagnostic.code == "DEDIREN_ELK_DUPLICATE_ID"
                && diagnostic.severity == DiagnosticSeverity::Error
        }));
    }

    #[test]
    fn endpoint_merging_auto_produces_dediren_elk_option_unsupported_warning() {
        let request = request_with_preferences(LayoutPreferences {
            direction: None,
            density: None,
            wrapping: None,
            routing: Some(LayoutRoutingPreferences {
                style: None,
                profile: None,
                endpoint_merging: Some(LayoutEndpointMerging::Auto),
            }),
        });

        let result = layout(&request).unwrap();

        assert!(result.warnings.iter().any(|diagnostic| {
            diagnostic.code == "DEDIREN_ELK_OPTION_UNSUPPORTED"
                && diagnostic.severity == DiagnosticSeverity::Warning
                && diagnostic.message.contains("endpoint_merging")
        }));
    }

    #[test]
    fn grouped_source_maps_to_laid_out_group_with_members_and_provenance() {
        let mut request = basic_request();
        request.groups.push(LayoutGroup {
            id: "application-services".to_string(),
            label: "Application Services".to_string(),
            members: vec!["source".to_string(), "target".to_string()],
            provenance: GroupProvenance::semantic_backed("application-services-source"),
        });

        let result = layout(&request).unwrap();

        assert_eq!(result.groups.len(), 1);
        assert_eq!(result.groups[0].id, "application-services");
        assert_eq!(result.groups[0].source_id, "application-services-source");
        assert_eq!(
            result.groups[0].members,
            vec!["source".to_string(), "target".to_string()]
        );
        assert_eq!(
            result.groups[0].provenance,
            Some(GroupProvenance::semantic_backed(
                "application-services-source"
            ))
        );
    }

    #[test]
    fn group_bounds_contain_every_emitted_child_member_rectangle() {
        let result = layout(&four_member_group_request()).unwrap();
        let group = result
            .groups
            .iter()
            .find(|group| group.id == "application-services")
            .unwrap();

        assert_eq!(group.members.len(), 4);
        for member in &group.members {
            let node = result.nodes.iter().find(|node| &node.id == member).unwrap();
            assert!(
                group_contains_node(group, node),
                "group bounds should contain member `{member}`: group=({}, {}, {}, {}), node=({}, {}, {}, {})",
                group.x,
                group.y,
                group.width,
                group.height,
                node.x,
                node.y,
                node.width,
                node.height
            );
        }
    }

    #[test]
    fn invalid_group_members_warn_and_emit_only_laid_out_members() {
        let mut request = basic_request();
        request.groups.push(LayoutGroup {
            id: "primary".to_string(),
            label: "Primary".to_string(),
            members: vec![
                "source".to_string(),
                "missing".to_string(),
                "source".to_string(),
                "target".to_string(),
            ],
            provenance: GroupProvenance::semantic_backed("primary"),
        });
        request.groups.push(LayoutGroup {
            id: "secondary".to_string(),
            label: "Secondary".to_string(),
            members: vec!["target".to_string()],
            provenance: GroupProvenance::semantic_backed("secondary"),
        });

        let result = layout(&request).unwrap();

        assert_eq!(
            warning_count(&result, "DEDIREN_ELK_GROUP_MEMBERSHIP_WARNING"),
            3
        );
        assert_eq!(
            result
                .groups
                .iter()
                .find(|group| group.id == "primary")
                .unwrap()
                .members,
            vec!["source".to_string(), "target".to_string()]
        );
        assert_eq!(
            result
                .groups
                .iter()
                .find(|group| group.id == "secondary")
                .unwrap()
                .members,
            Vec::<String>::new()
        );
    }

    fn warning_count(result: &LayoutResult, code: &str) -> usize {
        result
            .warnings
            .iter()
            .filter(|diagnostic| diagnostic.code == code)
            .count()
    }

    fn group_contains_node(group: &LaidOutGroup, node: &LaidOutNode) -> bool {
        node.x >= group.x
            && node.y >= group.y
            && node.x + node.width <= group.x + group.width
            && node.y + node.height <= group.y + group.height
    }

    fn request_with_preferences(layout_preferences: LayoutPreferences) -> LayoutRequest {
        LayoutRequest {
            layout_preferences: Some(layout_preferences),
            ..basic_request()
        }
    }

    fn four_member_group_request() -> LayoutRequest {
        let mut request = basic_request();
        request.nodes.extend([
            LayoutNode {
                id: "worker".to_string(),
                label: "Worker".to_string(),
                source_id: "worker-semantic".to_string(),
                width_hint: Some(120.0),
                height_hint: Some(60.0),
            },
            LayoutNode {
                id: "database".to_string(),
                label: "Database".to_string(),
                source_id: "database-semantic".to_string(),
                width_hint: Some(120.0),
                height_hint: Some(60.0),
            },
        ]);
        request.groups.push(LayoutGroup {
            id: "application-services".to_string(),
            label: "Application Services".to_string(),
            members: vec![
                "source".to_string(),
                "target".to_string(),
                "worker".to_string(),
                "database".to_string(),
            ],
            provenance: GroupProvenance::semantic_backed("application-services-source"),
        });
        request
    }

    fn basic_request() -> LayoutRequest {
        LayoutRequest {
            layout_request_schema_version: "layout-request.schema.v1".to_string(),
            view_id: "test-view".to_string(),
            nodes: vec![
                LayoutNode {
                    id: "source".to_string(),
                    label: "Source".to_string(),
                    source_id: "source-semantic".to_string(),
                    width_hint: Some(120.0),
                    height_hint: Some(60.0),
                },
                LayoutNode {
                    id: "target".to_string(),
                    label: "Target".to_string(),
                    source_id: "target-semantic".to_string(),
                    width_hint: Some(120.0),
                    height_hint: Some(60.0),
                },
            ],
            edges: vec![LayoutEdge {
                id: "source-target".to_string(),
                source: "source".to_string(),
                target: "target".to_string(),
                label: "calls".to_string(),
                source_id: "relationship-source".to_string(),
                relationship_type: None,
            }],
            groups: Vec::new(),
            labels: Vec::new(),
            constraints: Vec::new(),
            layout_preferences: None,
        }
    }
}
