use dediren_contracts::{
    Diagnostic, DiagnosticSeverity, LaidOutEdge, LaidOutGroup, LaidOutNode, LayoutResult, Point,
};
use serde::{Deserialize, Serialize};

const ROUTE_DETOUR_RATIO: f64 = 1.5;
const ROUTE_DETOUR_EXCESS: f64 = 240.0;
const ROUTE_CLOSE_PARALLEL_DISTANCE: f64 = 20.0;
const ROUTE_CLOSE_PARALLEL_MIN_OVERLAP: f64 = 40.0;
const GEOMETRY_EPSILON: f64 = 0.001;
const ROUTE_ENDPOINT_TOLERANCE: f64 = 1.5;

#[derive(Debug, Clone, Copy)]
struct Rect {
    x: f64,
    y: f64,
    width: f64,
    height: f64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LayoutQualityReport {
    pub status: String,
    pub policy_name: String,
    pub overlap_count: usize,
    pub connector_through_node_count: usize,
    pub invalid_route_count: usize,
    pub route_detour_count: usize,
    pub route_close_parallel_count: usize,
    pub group_boundary_issue_count: usize,
    pub warning_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LayoutQualityPolicy {
    pub name: String,
    pub max_overlap_count: usize,
    pub max_connector_through_node_count: usize,
    pub max_invalid_route_count: usize,
    pub max_route_detour_count: usize,
    pub max_route_close_parallel_count: usize,
    pub max_group_boundary_issue_count: usize,
}

impl Default for LayoutQualityPolicy {
    fn default() -> Self {
        Self {
            name: "draft".to_string(),
            max_overlap_count: 0,
            max_connector_through_node_count: 0,
            max_invalid_route_count: 0,
            max_route_detour_count: 0,
            max_route_close_parallel_count: 0,
            max_group_boundary_issue_count: 0,
        }
    }
}

pub fn validate_layout(result: &LayoutResult) -> LayoutQualityReport {
    validate_layout_with_policy(result, &LayoutQualityPolicy::default())
}

pub fn validate_layout_with_policy(
    result: &LayoutResult,
    policy: &LayoutQualityPolicy,
) -> LayoutQualityReport {
    let overlap_count = count_overlaps(result);
    let connector_through_node_count = count_connector_through_nodes(result);
    let invalid_route_count = result
        .edges
        .iter()
        .filter(|edge| route_has_integrity_issue(edge, result))
        .count();
    let route_detour_count = count_route_detours(result);
    let route_close_parallel_count = count_close_parallel_routes(result);
    let group_boundary_issue_count = count_group_boundary_issues(result);
    let warning_count = result.warnings.len();
    let status = if overlap_count <= policy.max_overlap_count
        && connector_through_node_count <= policy.max_connector_through_node_count
        && invalid_route_count <= policy.max_invalid_route_count
        && route_detour_count <= policy.max_route_detour_count
        && route_close_parallel_count <= policy.max_route_close_parallel_count
        && group_boundary_issue_count <= policy.max_group_boundary_issue_count
        && warning_count == 0
    {
        "ok"
    } else {
        "warning"
    };

    LayoutQualityReport {
        status: status.to_string(),
        policy_name: policy.name.clone(),
        overlap_count,
        connector_through_node_count,
        invalid_route_count,
        route_detour_count,
        route_close_parallel_count,
        group_boundary_issue_count,
        warning_count,
    }
}

pub fn validate_layout_diagnostics(result: &LayoutResult) -> Vec<Diagnostic> {
    let mut diagnostics = Vec::new();
    for (edge_index, edge) in result.edges.iter().enumerate() {
        if edge.points.is_empty() {
            diagnostics.push(route_error(
                "DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY",
                format!("edge '{}' has no route points", edge.id),
                format!("$.edges[{edge_index}].points"),
            ));
            continue;
        }
        if edge.points.len() < 2 {
            diagnostics.push(route_error(
                "DEDIREN_LAYOUT_ROUTE_POINTS_INSUFFICIENT",
                format!(
                    "edge '{}' must have at least start and end route points",
                    edge.id
                ),
                format!("$.edges[{edge_index}].points"),
            ));
            continue;
        }
        let Some(source) = result.nodes.iter().find(|node| node.id == edge.source) else {
            continue;
        };
        let Some(target) = result.nodes.iter().find(|node| node.id == edge.target) else {
            continue;
        };
        if !point_on_node_perimeter(&edge.points[0], source, ROUTE_ENDPOINT_TOLERANCE) {
            diagnostics.push(route_error(
                "DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER",
                format!(
                    "edge '{}' first route point is not on source node '{}' perimeter",
                    edge.id, edge.source
                ),
                format!("$.edges[{edge_index}].points[0]"),
            ));
        }
        if !point_on_node_perimeter(
            &edge.points[edge.points.len() - 1],
            target,
            ROUTE_ENDPOINT_TOLERANCE,
        ) {
            diagnostics.push(route_error(
                "DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER",
                format!(
                    "edge '{}' last route point is not on target node '{}' perimeter",
                    edge.id, edge.target
                ),
                format!("$.edges[{edge_index}].points[-1]"),
            ));
        }
    }
    diagnostics
}

fn route_has_integrity_issue(edge: &LaidOutEdge, result: &LayoutResult) -> bool {
    if edge.points.len() < 2 {
        return true;
    }
    let Some(source) = result.nodes.iter().find(|node| node.id == edge.source) else {
        return false;
    };
    let Some(target) = result.nodes.iter().find(|node| node.id == edge.target) else {
        return false;
    };
    !point_on_node_perimeter(&edge.points[0], source, ROUTE_ENDPOINT_TOLERANCE)
        || !point_on_node_perimeter(
            &edge.points[edge.points.len() - 1],
            target,
            ROUTE_ENDPOINT_TOLERANCE,
        )
}

fn route_error(code: &str, message: String, path: String) -> Diagnostic {
    Diagnostic {
        code: code.to_string(),
        severity: DiagnosticSeverity::Error,
        message,
        path: Some(path),
    }
}

fn point_on_node_perimeter(point: &Point, node: &LaidOutNode, tolerance: f64) -> bool {
    let left = node.x;
    let right = node.x + node.width;
    let top = node.y;
    let bottom = node.y + node.height;
    point.x >= left - tolerance
        && point.x <= right + tolerance
        && point.y >= top - tolerance
        && point.y <= bottom + tolerance
        && (same_within(point.x, left, tolerance)
            || same_within(point.x, right, tolerance)
            || same_within(point.y, top, tolerance)
            || same_within(point.y, bottom, tolerance))
}

fn same_within(left: f64, right: f64, tolerance: f64) -> bool {
    (left - right).abs() <= tolerance
}

fn count_overlaps(result: &LayoutResult) -> usize {
    let mut count = 0;
    for (index, left) in result.nodes.iter().enumerate() {
        for right in result.nodes.iter().skip(index + 1) {
            if rectangles_overlap(node_rect(left), node_rect(right)) {
                count += 1;
            }
        }
    }
    count
}

fn count_connector_through_nodes(result: &LayoutResult) -> usize {
    let mut count = 0;
    for edge in &result.edges {
        for segment in edge.points.windows(2) {
            for node in &result.nodes {
                if node.id != edge.source
                    && node.id != edge.target
                    && segment_intersects_rect(&segment[0], &segment[1], node_rect(node))
                {
                    count += 1;
                    break;
                }
            }
        }
    }
    count
}

fn count_group_boundary_issues(result: &LayoutResult) -> usize {
    let mut count = 0;
    for group in &result.groups {
        for member_id in &group.members {
            if let Some(node) = result.nodes.iter().find(|node| &node.id == member_id) {
                let inside = node.x >= group.x
                    && node.y >= group.y
                    && node.x + node.width <= group.x + group.width
                    && node.y + node.height <= group.y + group.height;
                if !inside {
                    count += 1;
                }
            }
        }
    }
    for edge in &result.edges {
        for segment in edge.points.windows(2) {
            for group in &result.groups {
                if group
                    .members
                    .iter()
                    .any(|member| member == &edge.source || member == &edge.target)
                {
                    continue;
                }
                if segment_intersects_rect(&segment[0], &segment[1], group_rect(group)) {
                    count += 1;
                    break;
                }
            }
        }
    }
    count
}

fn count_route_detours(result: &LayoutResult) -> usize {
    result
        .edges
        .iter()
        .filter(|edge| has_excessive_detour(&edge.points))
        .count()
}

fn count_close_parallel_routes(result: &LayoutResult) -> usize {
    let mut segments = Vec::new();
    for (edge_index, edge) in result.edges.iter().enumerate() {
        for points in edge.points.windows(2) {
            if let Some(segment) = route_segment(
                edge_index,
                &edge.source,
                &edge.target,
                &points[0],
                &points[1],
            ) {
                segments.push(segment);
            }
        }
    }

    let mut count = 0;
    for (index, left) in segments.iter().enumerate() {
        for right in segments.iter().skip(index + 1) {
            if close_parallel_route_segments(left, right) {
                count += 1;
            }
        }
    }
    count
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum RouteSegmentOrientation {
    Horizontal,
    Vertical,
}

#[derive(Clone)]
struct RouteSegment {
    edge_index: usize,
    source: String,
    target: String,
    orientation: RouteSegmentOrientation,
    fixed: f64,
    min: f64,
    max: f64,
}

fn route_segment(
    edge_index: usize,
    source: &str,
    target: &str,
    start: &dediren_contracts::Point,
    end: &dediren_contracts::Point,
) -> Option<RouteSegment> {
    if same_coordinate(start.y, end.y) && !same_coordinate(start.x, end.x) {
        Some(RouteSegment {
            edge_index,
            source: source.to_string(),
            target: target.to_string(),
            orientation: RouteSegmentOrientation::Horizontal,
            fixed: start.y,
            min: start.x.min(end.x),
            max: start.x.max(end.x),
        })
    } else if same_coordinate(start.x, end.x) && !same_coordinate(start.y, end.y) {
        Some(RouteSegment {
            edge_index,
            source: source.to_string(),
            target: target.to_string(),
            orientation: RouteSegmentOrientation::Vertical,
            fixed: start.x,
            min: start.y.min(end.y),
            max: start.y.max(end.y),
        })
    } else {
        None
    }
}

fn close_parallel_route_segments(left: &RouteSegment, right: &RouteSegment) -> bool {
    left.edge_index != right.edge_index
        && !share_endpoint(left, right)
        && left.orientation == right.orientation
        && (left.fixed - right.fixed).abs() < ROUTE_CLOSE_PARALLEL_DISTANCE
        && overlap_length(left.min, left.max, right.min, right.max)
            >= ROUTE_CLOSE_PARALLEL_MIN_OVERLAP
}

fn share_endpoint(left: &RouteSegment, right: &RouteSegment) -> bool {
    left.source == right.source
        || left.source == right.target
        || left.target == right.source
        || left.target == right.target
}

fn overlap_length(left_min: f64, left_max: f64, right_min: f64, right_max: f64) -> f64 {
    (left_max.min(right_max) - left_min.max(right_min)).max(0.0)
}

fn has_excessive_detour(points: &[dediren_contracts::Point]) -> bool {
    if points.len() < 2 {
        return false;
    }
    let route_length = route_length(points);
    let start = &points[0];
    let end = &points[points.len() - 1];
    let direct_length = (start.x - end.x).abs() + (start.y - end.y).abs();
    direct_length > 0.0
        && route_length > direct_length * ROUTE_DETOUR_RATIO
        && route_length - direct_length > ROUTE_DETOUR_EXCESS
}

fn route_length(points: &[dediren_contracts::Point]) -> f64 {
    points
        .windows(2)
        .map(|segment| (segment[0].x - segment[1].x).abs() + (segment[0].y - segment[1].y).abs())
        .sum()
}

fn same_coordinate(left: f64, right: f64) -> bool {
    (left - right).abs() <= GEOMETRY_EPSILON
}

fn rectangles_overlap(left: Rect, right: Rect) -> bool {
    left.x < right.x + right.width
        && left.x + left.width > right.x
        && left.y < right.y + right.height
        && left.y + left.height > right.y
}

fn segment_intersects_rect(start: &Point, end: &Point, rect: Rect) -> bool {
    let min_x = start.x.min(end.x);
    let max_x = start.x.max(end.x);
    let min_y = start.y.min(end.y);
    let max_y = start.y.max(end.y);
    rectangles_overlap(
        Rect {
            x: min_x,
            y: min_y,
            width: (max_x - min_x).max(1.0),
            height: (max_y - min_y).max(1.0),
        },
        rect,
    )
}

fn node_rect(node: &LaidOutNode) -> Rect {
    Rect {
        x: node.x,
        y: node.y,
        width: node.width,
        height: node.height,
    }
}

fn group_rect(group: &LaidOutGroup) -> Rect {
    Rect {
        x: group.x,
        y: group.y,
        width: group.width,
        height: group.height,
    }
}
