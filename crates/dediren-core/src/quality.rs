use dediren_contracts::LayoutResult;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LayoutQualityReport {
    pub status: String,
    pub policy_name: String,
    pub overlap_count: usize,
    pub connector_through_node_count: usize,
    pub invalid_route_count: usize,
    pub group_boundary_issue_count: usize,
    pub warning_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LayoutQualityPolicy {
    pub name: String,
    pub max_overlap_count: usize,
    pub max_connector_through_node_count: usize,
    pub max_invalid_route_count: usize,
    pub max_group_boundary_issue_count: usize,
}

impl Default for LayoutQualityPolicy {
    fn default() -> Self {
        Self {
            name: "draft".to_string(),
            max_overlap_count: 0,
            max_connector_through_node_count: 0,
            max_invalid_route_count: 0,
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
    let group_boundary_issue_count = count_group_boundary_issues(result);
    let warning_count = result.warnings.len();
    let status = if overlap_count <= policy.max_overlap_count
        && connector_through_node_count <= policy.max_connector_through_node_count
        && invalid_route_count <= policy.max_invalid_route_count
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
