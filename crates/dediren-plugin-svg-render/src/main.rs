use std::io::Read;

use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, LaidOutEdge, LaidOutGroup, LaidOutNode,
    LayoutResult, Point, RenderPolicy, RenderResult, SvgEdgeStyle, SvgGroupStyle, SvgNodeStyle,
    RENDER_RESULT_SCHEMA_VERSION,
};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
struct RenderInput {
    layout_result: LayoutResult,
    policy: RenderPolicy,
}

#[derive(Debug, Clone)]
struct ResolvedStyle {
    background_fill: String,
    font_family: String,
    font_size: f64,
    node: ResolvedNodeStyle,
    edge: ResolvedEdgeStyle,
    group: ResolvedGroupStyle,
}

#[derive(Debug, Clone)]
struct ResolvedNodeStyle {
    fill: String,
    stroke: String,
    stroke_width: f64,
    rx: f64,
    label_fill: String,
}

#[derive(Debug, Clone)]
struct ResolvedEdgeStyle {
    stroke: String,
    stroke_width: f64,
    label_fill: String,
}

#[derive(Debug, Clone)]
struct ResolvedGroupStyle {
    fill: String,
    stroke: String,
    stroke_width: f64,
    rx: f64,
    label_fill: String,
    label_size: f64,
}

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("capabilities") {
        println!(
            "{}",
            serde_json::json!({
                "plugin_protocol_version": "plugin.protocol.v1",
                "id": "svg-render",
                "capabilities": ["render"],
                "runtime": { "artifact_kind": "svg" }
            })
        );
        return Ok(());
    }

    if args.get(1).map(String::as_str) != Some("render") {
        anyhow::bail!("expected command: render");
    }

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let render_input: RenderInput = serde_json::from_str(&input)?;
    if let Err(error) = validate_render_policy(&render_input.policy) {
        exit_with_diagnostic(
            "DEDIREN_SVG_POLICY_INVALID",
            &error.message,
            Some(error.path),
        );
    }
    let result = RenderResult {
        render_result_schema_version: RENDER_RESULT_SCHEMA_VERSION.to_string(),
        artifact_kind: "svg".to_string(),
        content: render_svg(&render_input.layout_result, &render_input.policy),
    };
    println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
    Ok(())
}

#[derive(Debug)]
struct PolicyValidationError {
    path: String,
    message: String,
}

fn validate_render_policy(policy: &RenderPolicy) -> Result<(), PolicyValidationError> {
    let Some(style) = policy.style.as_ref() else {
        return Ok(());
    };

    if let Some(background) = style.background.as_ref() {
        validate_color(&background.fill, "style.background.fill")?;
    }
    if let Some(font) = style.font.as_ref() {
        validate_string_len(&font.family, "style.font.family", 1, 120)?;
        validate_number(
            &font.size,
            "style.font.size",
            Bound::ExclusiveMin(0.0),
            96.0,
        )?;
    }
    validate_node_style(style.node.as_ref(), "style.node")?;
    validate_edge_style(style.edge.as_ref(), "style.edge")?;
    validate_group_style(style.group.as_ref(), "style.group")?;

    for (id, node_style) in &style.node_overrides {
        validate_node_style(Some(node_style), &format!("style.node_overrides.{id}"))?;
    }
    for (id, edge_style) in &style.edge_overrides {
        validate_edge_style(Some(edge_style), &format!("style.edge_overrides.{id}"))?;
    }
    for (id, group_style) in &style.group_overrides {
        validate_group_style(Some(group_style), &format!("style.group_overrides.{id}"))?;
    }

    Ok(())
}

fn validate_node_style(
    style: Option<&SvgNodeStyle>,
    path: &str,
) -> Result<(), PolicyValidationError> {
    let Some(style) = style else {
        return Ok(());
    };
    validate_color(&style.fill, &format!("{path}.fill"))?;
    validate_color(&style.stroke, &format!("{path}.stroke"))?;
    validate_number(
        &style.stroke_width,
        &format!("{path}.stroke_width"),
        Bound::Min(0.0),
        24.0,
    )?;
    validate_number(&style.rx, &format!("{path}.rx"), Bound::Min(0.0), 80.0)?;
    validate_color(&style.label_fill, &format!("{path}.label_fill"))
}

fn validate_edge_style(
    style: Option<&SvgEdgeStyle>,
    path: &str,
) -> Result<(), PolicyValidationError> {
    let Some(style) = style else {
        return Ok(());
    };
    validate_color(&style.stroke, &format!("{path}.stroke"))?;
    validate_number(
        &style.stroke_width,
        &format!("{path}.stroke_width"),
        Bound::Min(0.0),
        24.0,
    )?;
    validate_color(&style.label_fill, &format!("{path}.label_fill"))
}

fn validate_group_style(
    style: Option<&SvgGroupStyle>,
    path: &str,
) -> Result<(), PolicyValidationError> {
    let Some(style) = style else {
        return Ok(());
    };
    validate_color(&style.fill, &format!("{path}.fill"))?;
    validate_color(&style.stroke, &format!("{path}.stroke"))?;
    validate_number(
        &style.stroke_width,
        &format!("{path}.stroke_width"),
        Bound::Min(0.0),
        24.0,
    )?;
    validate_number(&style.rx, &format!("{path}.rx"), Bound::Min(0.0), 80.0)?;
    validate_color(&style.label_fill, &format!("{path}.label_fill"))?;
    validate_number(
        &style.label_size,
        &format!("{path}.label_size"),
        Bound::ExclusiveMin(0.0),
        96.0,
    )
}

enum Bound {
    Min(f64),
    ExclusiveMin(f64),
}

fn validate_number(
    value: &Option<f64>,
    path: &str,
    lower_bound: Bound,
    max: f64,
) -> Result<(), PolicyValidationError> {
    let Some(value) = value else {
        return Ok(());
    };
    let lower_bound_valid = match lower_bound {
        Bound::Min(min) => *value >= min,
        Bound::ExclusiveMin(min) => *value > min,
    };
    if !value.is_finite() || !lower_bound_valid || *value > max {
        return Err(PolicyValidationError {
            path: path.to_string(),
            message: format!("SVG render policy {path} is outside the allowed range"),
        });
    }
    Ok(())
}

fn validate_color(value: &Option<String>, path: &str) -> Result<(), PolicyValidationError> {
    let Some(value) = value else {
        return Ok(());
    };
    let is_hex_color = value.len() == 7
        && value.starts_with('#')
        && value[1..]
            .chars()
            .all(|character| character.is_ascii_hexdigit());
    if !is_hex_color {
        return Err(PolicyValidationError {
            path: path.to_string(),
            message: format!("SVG render policy {path} must be a #RRGGBB hex color"),
        });
    }
    Ok(())
}

fn validate_string_len(
    value: &Option<String>,
    path: &str,
    min: usize,
    max: usize,
) -> Result<(), PolicyValidationError> {
    let Some(value) = value else {
        return Ok(());
    };
    let character_count = value.chars().count();
    if character_count < min || character_count > max {
        return Err(PolicyValidationError {
            path: path.to_string(),
            message: format!("SVG render policy {path} length is outside the allowed range"),
        });
    }
    Ok(())
}

#[derive(Debug, Clone)]
struct SvgBounds {
    min_x: f64,
    min_y: f64,
    max_x: f64,
    max_y: f64,
}

impl SvgBounds {
    fn new_empty() -> Self {
        Self {
            min_x: f64::INFINITY,
            min_y: f64::INFINITY,
            max_x: f64::NEG_INFINITY,
            max_y: f64::NEG_INFINITY,
        }
    }

    fn is_empty(&self) -> bool {
        !self.min_x.is_finite()
            || !self.min_y.is_finite()
            || !self.max_x.is_finite()
            || !self.max_y.is_finite()
    }

    fn fallback(policy: &RenderPolicy) -> Self {
        Self {
            min_x: 0.0,
            min_y: 0.0,
            max_x: policy.page.width,
            max_y: policy.page.height,
        }
    }

    fn include_rect(&mut self, x: f64, y: f64, width: f64, height: f64) {
        self.min_x = self.min_x.min(x);
        self.min_y = self.min_y.min(y);
        self.max_x = self.max_x.max(x + width);
        self.max_y = self.max_y.max(y + height);
    }

    fn include_point(&mut self, x: f64, y: f64) {
        self.min_x = self.min_x.min(x);
        self.min_y = self.min_y.min(y);
        self.max_x = self.max_x.max(x);
        self.max_y = self.max_y.max(y);
    }

    fn include_label(&mut self, x: f64, y: f64, text: &str, font_size: f64) {
        let half_width = estimate_text_width(text, font_size) / 2.0;
        self.include_rect(
            x - half_width,
            y - font_size,
            half_width * 2.0,
            font_size * 1.4,
        );
    }

    fn padded(&self, policy: &RenderPolicy) -> Self {
        Self {
            min_x: self.min_x - policy.margin.left,
            min_y: self.min_y - policy.margin.top,
            max_x: self.max_x + policy.margin.right,
            max_y: self.max_y + policy.margin.bottom,
        }
    }

    fn width(&self) -> f64 {
        self.max_x - self.min_x
    }

    fn height(&self) -> f64 {
        self.max_y - self.min_y
    }
}

fn estimate_text_width(text: &str, font_size: f64) -> f64 {
    text.chars().count() as f64 * font_size * 0.62
}

fn svg_bounds(result: &LayoutResult, policy: &RenderPolicy, style: &ResolvedStyle) -> SvgBounds {
    let mut bounds = SvgBounds::new_empty();

    for group in &result.groups {
        let group_style = group_style(policy, &group.id, style);
        bounds.include_rect(group.x, group.y, group.width, group.height);
        bounds.include_rect(
            group.x + 8.0,
            group.y + 4.0,
            estimate_text_width(&group.label, group_style.label_size),
            group_style.label_size * 1.4,
        );
    }

    for edge in &result.edges {
        for point in &edge.points {
            bounds.include_point(point.x, point.y);
        }
    }

    let mut occupied_label_boxes = node_obstacle_boxes(result);
    occupied_label_boxes.extend(route_obstacle_boxes(result, style.font_size));
    for edge in &result.edges {
        if let Some((label_x, label_y, label_box)) =
            edge_label_position_for_edge(edge, style.font_size, &occupied_label_boxes)
        {
            bounds.include_label(label_x, label_y, &edge.label, style.font_size);
            occupied_label_boxes.push(label_box);
        }
    }

    for node in &result.nodes {
        bounds.include_rect(node.x, node.y, node.width, node.height);
        bounds.include_label(
            node.x + node.width / 2.0,
            node.y + node.height / 2.0,
            &node.label,
            style.font_size,
        );
    }

    if bounds.is_empty() {
        SvgBounds::fallback(policy).padded(policy)
    } else {
        bounds.padded(policy)
    }
}

fn render_svg(result: &LayoutResult, policy: &RenderPolicy) -> String {
    let style = base_style(policy);
    let bounds = svg_bounds(result, policy, &style);
    let rendered_width = bounds.width();
    let rendered_height = bounds.height();
    let mut svg = String::new();
    svg.push_str(&format!(
        r#"<svg xmlns="http://www.w3.org/2000/svg" width="{:.0}" height="{:.0}" viewBox="{:.1} {:.1} {:.1} {:.1}">"#,
        rendered_width,
        rendered_height,
        bounds.min_x,
        bounds.min_y,
        bounds.width(),
        bounds.height()
    ));
    svg.push_str(&format!(
        r##"<rect width="100%" height="100%" fill="{}"/>"##,
        escape_attr(&style.background_fill)
    ));
    svg.push_str(&format!(
        r##"<g font-family="{}" font-size="{}">"##,
        escape_attr(&style.font_family),
        svg_style_number(style.font_size)
    ));

    for group in &result.groups {
        let group_style = group_style(policy, &group.id, &style);
        svg.push_str(&format!(
            r#"<g data-dediren-group-id="{}">"#,
            escape_attr(&group.id)
        ));
        svg.push_str(&group_rect(group, &group_style));
        svg.push_str(&format!(
            r##"<text x="{:.1}" y="{:.1}" fill="{}" font-size="{}">{}</text>"##,
            group.x + 8.0,
            group.y + group_style.label_size + 4.0,
            escape_attr(&group_style.label_fill),
            svg_style_number(group_style.label_size),
            escape_text(&group.label)
        ));
        svg.push_str("</g>");
    }

    let mut rendered_edges: Vec<&LaidOutEdge> = Vec::new();
    let mut occupied_label_boxes = node_obstacle_boxes(result);
    occupied_label_boxes.extend(route_obstacle_boxes(result, style.font_size));
    for edge in &result.edges {
        let edge_style = edge_style(policy, &edge.id, &style);
        svg.push_str(&format!(
            r#"<g data-dediren-edge-id="{}">"#,
            escape_attr(&edge.id)
        ));
        svg.push_str(&edge_marker(edge, &edge_style));
        svg.push_str(&edge_path(edge, &edge_style, &rendered_edges));
        svg.push_str(&edge_label(
            edge,
            &edge_style,
            &style.background_fill,
            style.font_size,
            &mut occupied_label_boxes,
        ));
        svg.push_str("</g>");
        rendered_edges.push(edge);
    }

    for node in &result.nodes {
        let node_style = node_style(policy, &node.id, &style);
        svg.push_str(&format!(
            r#"<g data-dediren-node-id="{}">"#,
            escape_attr(&node.id)
        ));
        svg.push_str(&node_rect(node, &node_style));
        svg.push_str(&node_label(node, &node_style));
        svg.push_str("</g>");
    }

    svg.push_str("</g></svg>\n");
    svg
}

fn base_style(policy: &RenderPolicy) -> ResolvedStyle {
    let default_node = ResolvedNodeStyle {
        fill: "#f8fafc".to_string(),
        stroke: "#334155".to_string(),
        stroke_width: 1.5,
        rx: 6.0,
        label_fill: "#0f172a".to_string(),
    };
    let default_edge = ResolvedEdgeStyle {
        stroke: "#64748b".to_string(),
        stroke_width: 1.5,
        label_fill: "#374151".to_string(),
    };
    let default_group = ResolvedGroupStyle {
        fill: "#eff6ff".to_string(),
        stroke: "#93c5fd".to_string(),
        stroke_width: 1.0,
        rx: 8.0,
        label_fill: "#1e3a8a".to_string(),
        label_size: 12.0,
    };

    let style_policy = policy.style.as_ref();
    ResolvedStyle {
        background_fill: style_policy
            .and_then(|style| style.background.as_ref())
            .and_then(|background| background.fill.as_ref())
            .cloned()
            .unwrap_or_else(|| "#ffffff".to_string()),
        font_family: style_policy
            .and_then(|style| style.font.as_ref())
            .and_then(|font| font.family.as_ref())
            .cloned()
            .unwrap_or_else(|| "Inter, Arial, sans-serif".to_string()),
        font_size: style_policy
            .and_then(|style| style.font.as_ref())
            .and_then(|font| font.size)
            .unwrap_or(14.0),
        node: merge_node_style(
            &default_node,
            style_policy.and_then(|style| style.node.as_ref()),
        ),
        edge: merge_edge_style(
            &default_edge,
            style_policy.and_then(|style| style.edge.as_ref()),
        ),
        group: merge_group_style(
            &default_group,
            style_policy.and_then(|style| style.group.as_ref()),
        ),
    }
}

fn node_style(policy: &RenderPolicy, node_id: &str, base: &ResolvedStyle) -> ResolvedNodeStyle {
    merge_node_style(
        &base.node,
        policy
            .style
            .as_ref()
            .and_then(|style| style.node_overrides.get(node_id)),
    )
}

fn edge_style(policy: &RenderPolicy, edge_id: &str, base: &ResolvedStyle) -> ResolvedEdgeStyle {
    merge_edge_style(
        &base.edge,
        policy
            .style
            .as_ref()
            .and_then(|style| style.edge_overrides.get(edge_id)),
    )
}

fn group_style(policy: &RenderPolicy, group_id: &str, base: &ResolvedStyle) -> ResolvedGroupStyle {
    merge_group_style(
        &base.group,
        policy
            .style
            .as_ref()
            .and_then(|style| style.group_overrides.get(group_id)),
    )
}

fn merge_node_style(
    base: &ResolvedNodeStyle,
    override_style: Option<&SvgNodeStyle>,
) -> ResolvedNodeStyle {
    match override_style {
        Some(style) => ResolvedNodeStyle {
            fill: style.fill.clone().unwrap_or_else(|| base.fill.clone()),
            stroke: style.stroke.clone().unwrap_or_else(|| base.stroke.clone()),
            stroke_width: style.stroke_width.unwrap_or(base.stroke_width),
            rx: style.rx.unwrap_or(base.rx),
            label_fill: style
                .label_fill
                .clone()
                .unwrap_or_else(|| base.label_fill.clone()),
        },
        None => base.clone(),
    }
}

fn merge_edge_style(
    base: &ResolvedEdgeStyle,
    override_style: Option<&SvgEdgeStyle>,
) -> ResolvedEdgeStyle {
    match override_style {
        Some(style) => ResolvedEdgeStyle {
            stroke: style.stroke.clone().unwrap_or_else(|| base.stroke.clone()),
            stroke_width: style.stroke_width.unwrap_or(base.stroke_width),
            label_fill: style
                .label_fill
                .clone()
                .unwrap_or_else(|| base.label_fill.clone()),
        },
        None => base.clone(),
    }
}

fn merge_group_style(
    base: &ResolvedGroupStyle,
    override_style: Option<&SvgGroupStyle>,
) -> ResolvedGroupStyle {
    match override_style {
        Some(style) => ResolvedGroupStyle {
            fill: style.fill.clone().unwrap_or_else(|| base.fill.clone()),
            stroke: style.stroke.clone().unwrap_or_else(|| base.stroke.clone()),
            stroke_width: style.stroke_width.unwrap_or(base.stroke_width),
            rx: style.rx.unwrap_or(base.rx),
            label_fill: style
                .label_fill
                .clone()
                .unwrap_or_else(|| base.label_fill.clone()),
            label_size: style.label_size.unwrap_or(base.label_size),
        },
        None => base.clone(),
    }
}

fn group_rect(group: &LaidOutGroup, style: &ResolvedGroupStyle) -> String {
    format!(
        r##"<rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="{}" fill="{}" stroke="{}" stroke-width="{}"/>"##,
        group.x,
        group.y,
        group.width,
        group.height,
        svg_style_number(style.rx),
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width)
    )
}

fn node_rect(node: &LaidOutNode, style: &ResolvedNodeStyle) -> String {
    format!(
        r##"<rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="{}" fill="{}" stroke="{}" stroke-width="{}"/>"##,
        node.x,
        node.y,
        node.width,
        node.height,
        svg_style_number(style.rx),
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width)
    )
}

fn node_label(node: &LaidOutNode, style: &ResolvedNodeStyle) -> String {
    format!(
        r##"<text x="{:.1}" y="{:.1}" text-anchor="middle" dominant-baseline="middle" fill="{}">{}</text>"##,
        node.x + node.width / 2.0,
        node.y + node.height / 2.0,
        escape_attr(&style.label_fill),
        escape_text(&node.label)
    )
}

fn edge_marker(edge: &LaidOutEdge, style: &ResolvedEdgeStyle) -> String {
    format!(
        r##"<defs><marker id="{}" markerWidth="8" markerHeight="8" refX="8" refY="4" orient="auto" markerUnits="strokeWidth"><path d="M 0 0 L 8 4 L 0 8 z" fill="{}"/></marker></defs>"##,
        escape_attr(&edge_marker_id(&edge.id)),
        escape_attr(&style.stroke)
    )
}

fn edge_path(
    edge: &LaidOutEdge,
    style: &ResolvedEdgeStyle,
    earlier_edges: &[&LaidOutEdge],
) -> String {
    let points = &edge.points;
    if points.len() < 2 {
        return String::new();
    }
    let data = edge_path_data(points, earlier_edges);
    format!(
        r##"<path d="{}" fill="none" stroke="{}" stroke-width="{}" marker-end="url(#{})"/>"##,
        escape_attr(&data),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width),
        escape_attr(&edge_marker_id(&edge.id))
    )
}

fn edge_path_data(points: &[Point], earlier_edges: &[&LaidOutEdge]) -> String {
    let mut data = format!("M {:.1} {:.1}", points[0].x, points[0].y);
    for segment in points.windows(2) {
        let start = &segment[0];
        let end = &segment[1];
        let jumps = line_jump_points(start, end, earlier_edges);
        if jumps.is_empty() {
            data.push_str(&format!(" L {:.1} {:.1}", end.x, end.y));
            continue;
        }
        for jump in jumps {
            append_line_jump(&mut data, start, end, &jump);
        }
        data.push_str(&format!(" L {:.1} {:.1}", end.x, end.y));
    }
    data
}

#[derive(Debug)]
struct LineJump {
    point: Point,
    distance: f64,
}

fn line_jump_points(start: &Point, end: &Point, earlier_edges: &[&LaidOutEdge]) -> Vec<LineJump> {
    let mut jumps = Vec::new();
    for earlier_edge in earlier_edges {
        for earlier_segment in earlier_edge.points.windows(2) {
            if let Some(point) =
                crossing_point(start, end, &earlier_segment[0], &earlier_segment[1])
            {
                let distance = segment_length(start, &point);
                if distance > LINE_JUMP_SIZE && segment_length(&point, end) > LINE_JUMP_SIZE {
                    jumps.push(LineJump { point, distance });
                }
            }
        }
    }
    jumps.sort_by(|left, right| left.distance.total_cmp(&right.distance));
    jumps
}

const LINE_JUMP_SIZE: f64 = 6.0;

fn crossing_point(
    start: &Point,
    end: &Point,
    other_start: &Point,
    other_end: &Point,
) -> Option<Point> {
    let current_horizontal = start.y == end.y && start.x != end.x;
    let current_vertical = start.x == end.x && start.y != end.y;
    let other_horizontal = other_start.y == other_end.y && other_start.x != other_end.x;
    let other_vertical = other_start.x == other_end.x && other_start.y != other_end.y;

    match (
        current_horizontal,
        current_vertical,
        other_horizontal,
        other_vertical,
    ) {
        (true, false, false, true) => perpendicular_crossing(start, end, other_start, other_end),
        (false, true, true, false) => perpendicular_crossing(other_start, other_end, start, end),
        _ => None,
    }
}

fn perpendicular_crossing(
    horizontal_start: &Point,
    horizontal_end: &Point,
    vertical_start: &Point,
    vertical_end: &Point,
) -> Option<Point> {
    let x = vertical_start.x;
    let y = horizontal_start.y;
    if between_exclusive(x, horizontal_start.x, horizontal_end.x)
        && between_exclusive(y, vertical_start.y, vertical_end.y)
    {
        Some(Point { x, y })
    } else {
        None
    }
}

fn between_exclusive(value: f64, start: f64, end: f64) -> bool {
    value > start.min(end) && value < start.max(end)
}

fn append_line_jump(data: &mut String, start: &Point, end: &Point, jump: &LineJump) {
    if start.y == end.y {
        let direction = (end.x - start.x).signum();
        data.push_str(&format!(
            " L {:.1} {:.1} Q {:.1} {:.1} {:.1} {:.1}",
            jump.point.x - direction * LINE_JUMP_SIZE,
            jump.point.y,
            jump.point.x,
            jump.point.y - LINE_JUMP_SIZE,
            jump.point.x + direction * LINE_JUMP_SIZE,
            jump.point.y
        ));
    } else if start.x == end.x {
        let direction = (end.y - start.y).signum();
        data.push_str(&format!(
            " L {:.1} {:.1} Q {:.1} {:.1} {:.1} {:.1}",
            jump.point.x,
            jump.point.y - direction * LINE_JUMP_SIZE,
            jump.point.x + LINE_JUMP_SIZE,
            jump.point.y,
            jump.point.x,
            jump.point.y + direction * LINE_JUMP_SIZE
        ));
    }
}

fn edge_marker_id(edge_id: &str) -> String {
    format!("arrow-{edge_id}")
}

#[derive(Debug, Clone)]
struct LabelBox {
    min_x: f64,
    min_y: f64,
    max_x: f64,
    max_y: f64,
}

impl LabelBox {
    fn overlaps(&self, other: &LabelBox) -> bool {
        self.min_x < other.max_x
            && self.max_x > other.min_x
            && self.min_y < other.max_y
            && self.max_y > other.min_y
    }
}

fn node_obstacle_boxes(result: &LayoutResult) -> Vec<LabelBox> {
    result
        .nodes
        .iter()
        .map(|node| LabelBox {
            min_x: node.x,
            min_y: node.y,
            max_x: node.x + node.width,
            max_y: node.y + node.height,
        })
        .collect()
}

fn route_obstacle_boxes(result: &LayoutResult, font_size: f64) -> Vec<LabelBox> {
    let padding = font_size * 0.5;
    result
        .edges
        .iter()
        .flat_map(|edge| edge.points.windows(2))
        .map(|segment| {
            let start = &segment[0];
            let end = &segment[1];
            LabelBox {
                min_x: start.x.min(end.x) - padding,
                min_y: start.y.min(end.y) - padding,
                max_x: start.x.max(end.x) + padding,
                max_y: start.y.max(end.y) + padding,
            }
        })
        .collect()
}

fn edge_label(
    edge: &LaidOutEdge,
    style: &ResolvedEdgeStyle,
    background_fill: &str,
    font_size: f64,
    occupied_boxes: &mut Vec<LabelBox>,
) -> String {
    if let Some(point) = edge_label_point(&edge.points) {
        let (label_x, label_y, label_box) =
            edge_label_position(point.x, point.y - 8.0, &edge.label, font_size, occupied_boxes);
        occupied_boxes.push(label_box);
        format!(
            r##"<text x="{:.1}" y="{:.1}" text-anchor="middle" fill="{}" stroke="{}" stroke-width="4" stroke-linejoin="round" paint-order="stroke">{}</text>"##,
            label_x,
            label_y,
            escape_attr(&style.label_fill),
            escape_attr(background_fill),
            escape_text(&edge.label)
        )
    } else {
        String::new()
    }
}

fn edge_label_position_for_edge(
    edge: &LaidOutEdge,
    font_size: f64,
    occupied_boxes: &[LabelBox],
) -> Option<(f64, f64, LabelBox)> {
    edge_label_point(&edge.points).map(|point| {
        edge_label_position(
            point.x,
            point.y - 8.0,
            &edge.label,
            font_size,
            occupied_boxes,
        )
    })
}

fn edge_label_position(
    x: f64,
    base_y: f64,
    text: &str,
    font_size: f64,
    occupied_boxes: &[LabelBox],
) -> (f64, f64, LabelBox) {
    let step = font_size + 4.0;
    for attempt in 0..12 {
        let (label_x, label_y) = label_candidate(attempt, x, base_y, step, text, font_size);
        let label_box = text_box(label_x, label_y, text, font_size);
        if occupied_boxes
            .iter()
            .all(|occupied| !label_box.overlaps(occupied))
        {
            return (label_x, label_y, label_box);
        }
    }
    let label_box = text_box(x, base_y, text, font_size);
    (x, base_y, label_box)
}

fn label_candidate(
    attempt: usize,
    x: f64,
    base_y: f64,
    step: f64,
    text: &str,
    font_size: f64,
) -> (f64, f64) {
    let side_offset = estimate_text_width(text, font_size) / 2.0 + step;
    match attempt {
        0 => (x, base_y - step),
        1 => (x, base_y + step),
        2 => (x - side_offset, base_y),
        3 => (x + side_offset, base_y),
        _ => {
            let distance = (attempt - 1) as f64;
            (x, base_y + distance * step)
        }
    }
}

fn text_box(x: f64, y: f64, text: &str, font_size: f64) -> LabelBox {
    let half_width = estimate_text_width(text, font_size) / 2.0;
    LabelBox {
        min_x: x - half_width,
        min_y: y - font_size,
        max_x: x + half_width,
        max_y: y + font_size * 0.4,
    }
}

fn edge_label_point(points: &[Point]) -> Option<Point> {
    longest_horizontal_segment_midpoint(points).or_else(|| route_midpoint(points))
}

fn longest_horizontal_segment_midpoint(points: &[Point]) -> Option<Point> {
    let mut longest: Option<(&Point, &Point, f64)> = None;
    for segment in points.windows(2) {
        let start = &segment[0];
        let end = &segment[1];
        if start.y != end.y || start.x == end.x {
            continue;
        }
        let length = (end.x - start.x).abs();
        if longest
            .as_ref()
            .is_none_or(|(_, _, longest_length)| length > *longest_length)
        {
            longest = Some((start, end, length));
        }
    }

    longest.map(|(start, end, _)| Point {
        x: start.x + (end.x - start.x) / 2.0,
        y: start.y,
    })
}

fn route_midpoint(points: &[Point]) -> Option<Point> {
    if points.is_empty() {
        return None;
    }
    if points.len() == 1 {
        return Some(points[0].clone());
    }

    let total_length: f64 = points
        .windows(2)
        .map(|segment| segment_length(&segment[0], &segment[1]))
        .sum();
    if total_length == 0.0 {
        return Some(points[0].clone());
    }

    let midpoint = total_length / 2.0;
    let mut traversed = 0.0;
    for segment in points.windows(2) {
        let start = &segment[0];
        let end = &segment[1];
        let segment_length = segment_length(start, end);
        if segment_length == 0.0 {
            continue;
        }
        if traversed + segment_length >= midpoint {
            let ratio = (midpoint - traversed) / segment_length;
            return Some(Point {
                x: start.x + (end.x - start.x) * ratio,
                y: start.y + (end.y - start.y) * ratio,
            });
        }
        traversed += segment_length;
    }

    points.last().cloned()
}

fn segment_length(start: &Point, end: &Point) -> f64 {
    ((end.x - start.x).powi(2) + (end.y - start.y).powi(2)).sqrt()
}

fn escape_text(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

fn escape_attr(value: &str) -> String {
    escape_text(value).replace('"', "&quot;")
}

fn svg_style_number(value: f64) -> String {
    if value == 0.0 {
        return "0".to_string();
    }
    serde_json::to_string(&value).expect("finite SVG style number")
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
