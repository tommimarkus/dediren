use dediren_contracts::LayoutResult;
use serde::{Deserialize, Serialize};

const ROUTE_DETOUR_RATIO: f64 = 1.5;
const ROUTE_DETOUR_EXCESS: f64 = 240.0;
const ROUTE_CLOSE_PARALLEL_DISTANCE: f64 = 20.0;
const ROUTE_CLOSE_PARALLEL_MIN_OVERLAP: f64 = 40.0;
const GEOMETRY_EPSILON: f64 = 0.001;

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
        .filter(|edge| edge.points.len() < 2)
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

fn count_overlaps(result: &LayoutResult) -> usize {
    let mut count = 0;
    for (index, left) in result.nodes.iter().enumerate() {
        for right in result.nodes.iter().skip(index + 1) {
            if rectangles_overlap(
                left.x,
                left.y,
                left.width,
                left.height,
                right.x,
                right.y,
                right.width,
                right.height,
            ) {
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
                    && segment_intersects_rect(
                        segment[0].x,
                        segment[0].y,
                        segment[1].x,
                        segment[1].y,
                        node.x,
                        node.y,
                        node.width,
                        node.height,
                    )
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

fn rectangles_overlap(
    left_x: f64,
    left_y: f64,
    left_width: f64,
    left_height: f64,
    right_x: f64,
    right_y: f64,
    right_width: f64,
    right_height: f64,
) -> bool {
    left_x < right_x + right_width
        && left_x + left_width > right_x
        && left_y < right_y + right_height
        && left_y + left_height > right_y
}

fn segment_intersects_rect(
    x1: f64,
    y1: f64,
    x2: f64,
    y2: f64,
    rect_x: f64,
    rect_y: f64,
    rect_width: f64,
    rect_height: f64,
) -> bool {
    let min_x = x1.min(x2);
    let max_x = x1.max(x2);
    let min_y = y1.min(y2);
    let max_y = y1.max(y2);
    rectangles_overlap(
        min_x,
        min_y,
        (max_x - min_x).max(1.0),
        (max_y - min_y).max(1.0),
        rect_x,
        rect_y,
        rect_width,
        rect_height,
    )
}
