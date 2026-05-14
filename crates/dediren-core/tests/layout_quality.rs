use dediren_contracts::{LaidOutEdge, LaidOutGroup, LaidOutNode, LayoutResult, Point};
use std::path::PathBuf;

#[test]
fn non_overlapping_layout_has_zero_overlaps() {
    let text =
        std::fs::read_to_string(workspace_file("fixtures/layout-result/basic.json")).unwrap();
    let result: LayoutResult = serde_json::from_str(&text).unwrap();
    let report = dediren_core::quality::validate_layout(&result);
    assert_eq!(report.overlap_count, 0);
    assert_eq!(report.connector_through_node_count, 0);
    assert_eq!(report.invalid_route_count, 0);
    assert_eq!(report.route_detour_count, 0);
    assert_eq!(report.route_close_parallel_count, 0);
    assert_eq!(report.group_boundary_issue_count, 0);
    assert_eq!(report.policy_name, "draft");
    assert_eq!(report.status, "ok");
}

#[test]
fn overlapping_nodes_are_counted() {
    let mut result = empty_result();
    result.nodes.push(node("a", 0.0, 0.0));
    result.nodes.push(node("b", 50.0, 20.0));
    let report = dediren_core::quality::validate_layout(&result);
    assert_eq!(report.overlap_count, 1);
    assert_eq!(report.status, "warning");
}

#[test]
fn invalid_routes_are_counted() {
    let mut result = empty_result();
    result.edges.push(LaidOutEdge {
        id: "edge".to_string(),
        source: "a".to_string(),
        target: "b".to_string(),
        source_id: "edge".to_string(),
        projection_id: "edge".to_string(),
        routing_hints: vec![],
        points: vec![Point { x: 10.0, y: 10.0 }],
        label: "broken".to_string(),
    });
    let report = dediren_core::quality::validate_layout(&result);
    assert_eq!(report.invalid_route_count, 1);
    assert_eq!(report.status, "warning");
}

#[test]
fn connector_through_unrelated_node_is_counted() {
    let mut result = empty_result();
    result.nodes.push(node("a", 0.0, 0.0));
    result.nodes.push(node("b", 300.0, 0.0));
    result.nodes.push(node("middle", 140.0, 20.0));
    result.edges.push(LaidOutEdge {
        id: "edge".to_string(),
        source: "a".to_string(),
        target: "b".to_string(),
        source_id: "edge".to_string(),
        projection_id: "edge".to_string(),
        routing_hints: vec![],
        points: vec![Point { x: 100.0, y: 40.0 }, Point { x: 300.0, y: 40.0 }],
        label: "crosses".to_string(),
    });
    let report = dediren_core::quality::validate_layout(&result);
    assert_eq!(report.connector_through_node_count, 1);
}

#[test]
fn excessive_route_detours_are_counted() {
    let mut result = empty_result();
    result.nodes.push(node("a", 0.0, 0.0));
    result.nodes.push(node("b", 300.0, 0.0));
    result.edges.push(LaidOutEdge {
        id: "edge".to_string(),
        source: "a".to_string(),
        target: "b".to_string(),
        source_id: "edge".to_string(),
        projection_id: "edge".to_string(),
        routing_hints: vec![],
        points: vec![
            Point { x: 100.0, y: 40.0 },
            Point { x: 100.0, y: 640.0 },
            Point { x: 300.0, y: 640.0 },
            Point { x: 300.0, y: 40.0 },
        ],
        label: "detours".to_string(),
    });
    let report = dediren_core::quality::validate_layout(&result);
    assert_eq!(report.route_detour_count, 1);
    assert_eq!(report.status, "warning");
}

#[test]
fn close_parallel_route_segments_are_counted() {
    let mut result = empty_result();
    result.edges.push(edge(
        "primary",
        vec![Point { x: 0.0, y: 0.0 }, Point { x: 200.0, y: 0.0 }],
    ));
    result.edges.push(edge(
        "too-close",
        vec![Point { x: 0.0, y: 16.0 }, Point { x: 200.0, y: 16.0 }],
    ));
    result.edges.push(edge(
        "readable",
        vec![Point { x: 0.0, y: 80.0 }, Point { x: 200.0, y: 80.0 }],
    ));

    let report = dediren_core::quality::validate_layout(&result);

    assert_eq!(report.route_close_parallel_count, 1);
    assert_eq!(report.status, "warning");
}

#[test]
fn moderately_spaced_parallel_route_segments_are_readable() {
    let mut result = empty_result();
    result.edges.push(edge(
        "primary",
        vec![Point { x: 0.0, y: 0.0 }, Point { x: 200.0, y: 0.0 }],
    ));
    result.edges.push(edge(
        "readable",
        vec![Point { x: 0.0, y: 28.0 }, Point { x: 200.0, y: 28.0 }],
    ));

    let report = dediren_core::quality::validate_layout(&result);

    assert_eq!(report.route_close_parallel_count, 0);
    assert_eq!(report.status, "ok");
}

#[test]
fn shared_endpoint_fanout_route_segments_are_readable() {
    let mut result = empty_result();
    result.edges.push(LaidOutEdge {
        id: "to-left".to_string(),
        source: "hub".to_string(),
        target: "left".to_string(),
        source_id: "to-left".to_string(),
        projection_id: "to-left".to_string(),
        routing_hints: vec![],
        points: vec![Point { x: 0.0, y: 0.0 }, Point { x: 200.0, y: 0.0 }],
        label: "to-left".to_string(),
    });
    result.edges.push(LaidOutEdge {
        id: "to-right".to_string(),
        source: "hub".to_string(),
        target: "right".to_string(),
        source_id: "to-right".to_string(),
        projection_id: "to-right".to_string(),
        routing_hints: vec![],
        points: vec![Point { x: 0.0, y: 12.0 }, Point { x: 200.0, y: 12.0 }],
        label: "to-right".to_string(),
    });

    let report = dediren_core::quality::validate_layout(&result);

    assert_eq!(report.route_close_parallel_count, 0);
    assert_eq!(report.status, "ok");
}

#[test]
fn group_boundary_issues_are_counted() {
    let mut result = empty_result();
    result.nodes.push(node("member", 200.0, 200.0));
    result.groups.push(LaidOutGroup {
        id: "group".to_string(),
        source_id: "group".to_string(),
        projection_id: "group".to_string(),
        provenance: None,
        x: 0.0,
        y: 0.0,
        width: 100.0,
        height: 100.0,
        members: vec!["member".to_string()],
        label: "Group".to_string(),
    });
    let report = dediren_core::quality::validate_layout(&result);
    assert_eq!(report.group_boundary_issue_count, 1);
}

#[test]
fn connector_through_unrelated_group_is_counted() {
    let mut result = empty_result();
    result.nodes.push(node("source", 0.0, 0.0));
    result.nodes.push(node("target", 500.0, 0.0));
    result.groups.push(LaidOutGroup {
        id: "source-group".to_string(),
        source_id: "source-group".to_string(),
        projection_id: "source-group".to_string(),
        provenance: None,
        x: -20.0,
        y: -20.0,
        width: 160.0,
        height: 120.0,
        members: vec!["source".to_string()],
        label: "Source".to_string(),
    });
    result.groups.push(LaidOutGroup {
        id: "unrelated-group".to_string(),
        source_id: "unrelated-group".to_string(),
        projection_id: "unrelated-group".to_string(),
        provenance: None,
        x: 220.0,
        y: -20.0,
        width: 160.0,
        height: 120.0,
        members: vec!["unrelated".to_string()],
        label: "Unrelated".to_string(),
    });
    result.groups.push(LaidOutGroup {
        id: "target-group".to_string(),
        source_id: "target-group".to_string(),
        projection_id: "target-group".to_string(),
        provenance: None,
        x: 480.0,
        y: -20.0,
        width: 160.0,
        height: 120.0,
        members: vec!["target".to_string()],
        label: "Target".to_string(),
    });
    result.edges.push(LaidOutEdge {
        id: "edge".to_string(),
        source: "source".to_string(),
        target: "target".to_string(),
        source_id: "edge".to_string(),
        projection_id: "edge".to_string(),
        routing_hints: vec![],
        points: vec![Point { x: 100.0, y: 40.0 }, Point { x: 500.0, y: 40.0 }],
        label: "crosses group".to_string(),
    });

    let report = dediren_core::quality::validate_layout(&result);

    assert_eq!(report.group_boundary_issue_count, 1);
    assert_eq!(report.status, "warning");
}

fn empty_result() -> LayoutResult {
    LayoutResult {
        layout_result_schema_version: "layout-result.schema.v1".to_string(),
        view_id: "main".to_string(),
        nodes: vec![],
        edges: vec![],
        groups: vec![],
        warnings: vec![],
    }
}

fn node(id: &str, x: f64, y: f64) -> LaidOutNode {
    LaidOutNode {
        id: id.to_string(),
        source_id: id.to_string(),
        projection_id: id.to_string(),
        x,
        y,
        width: 100.0,
        height: 80.0,
        label: id.to_string(),
    }
}

fn edge(id: &str, points: Vec<Point>) -> LaidOutEdge {
    LaidOutEdge {
        id: id.to_string(),
        source: format!("{id}-source"),
        target: format!("{id}-target"),
        source_id: id.to_string(),
        projection_id: id.to_string(),
        routing_hints: vec![],
        points,
        label: id.to_string(),
    }
}

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}
