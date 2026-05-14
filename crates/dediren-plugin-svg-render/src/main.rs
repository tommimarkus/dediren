use std::io::Read;

use dediren_archimate::ArchimateTypeValidationError;
use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, LaidOutEdge, LaidOutGroup, LaidOutNode,
    LayoutResult, Point, RenderMetadata, RenderPolicy, RenderResult,
    SvgEdgeLabelHorizontalPosition, SvgEdgeLabelHorizontalSide, SvgEdgeLabelVerticalPosition,
    SvgEdgeLabelVerticalSide, SvgEdgeLineStyle, SvgEdgeMarkerEnd, SvgEdgeStyle, SvgGroupStyle,
    SvgNodeDecorator, SvgNodeStyle, RENDER_RESULT_SCHEMA_VERSION,
};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
struct RenderInput {
    layout_result: LayoutResult,
    #[serde(default)]
    render_metadata: Option<RenderMetadata>,
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
    decorator: Option<SvgNodeDecorator>,
}

#[derive(Debug, Clone)]
struct ResolvedEdgeStyle {
    stroke: String,
    stroke_width: f64,
    label_fill: String,
    line_style: SvgEdgeLineStyle,
    marker_start: SvgEdgeMarkerEnd,
    marker_end: SvgEdgeMarkerEnd,
    label_horizontal_position: SvgEdgeLabelHorizontalPosition,
    label_horizontal_side: SvgEdgeLabelHorizontalSide,
    label_vertical_position: SvgEdgeLabelVerticalPosition,
    label_vertical_side: SvgEdgeLabelVerticalSide,
}

#[derive(Debug, Clone)]
struct ResolvedGroupStyle {
    fill: String,
    stroke: String,
    stroke_width: f64,
    rx: f64,
    label_fill: String,
    label_size: f64,
    decorator: Option<SvgNodeDecorator>,
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
    if let Err(error) =
        validate_render_metadata_usage(&render_input.policy, render_input.render_metadata.as_ref())
    {
        exit_with_diagnostic(&error.code, &error.message, Some(error.path));
    }
    if let Err(error) = validate_archimate_policy_types(&render_input.policy) {
        exit_with_archimate_type_error(error);
    }
    if let Err(error) = validate_archimate_render_metadata(
        &render_input.layout_result,
        render_input.render_metadata.as_ref(),
    ) {
        exit_with_archimate_type_error(error);
    }
    let result = RenderResult {
        render_result_schema_version: RENDER_RESULT_SCHEMA_VERSION.to_string(),
        artifact_kind: "svg".to_string(),
        content: render_svg(
            &render_input.layout_result,
            render_input.render_metadata.as_ref(),
            &render_input.policy,
        ),
    };
    println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
    Ok(())
}

#[derive(Debug)]
struct PolicyValidationError {
    path: String,
    message: String,
}

#[derive(Debug)]
struct RenderMetadataUsageError {
    code: &'static str,
    path: String,
    message: String,
}

fn validate_render_metadata_usage(
    policy: &RenderPolicy,
    metadata: Option<&RenderMetadata>,
) -> Result<(), RenderMetadataUsageError> {
    let uses_type_overrides = policy
        .style
        .as_ref()
        .map(|style| {
            !style.node_type_overrides.is_empty()
                || !style.edge_type_overrides.is_empty()
                || !style.group_type_overrides.is_empty()
        })
        .unwrap_or(false);

    if !uses_type_overrides {
        return Ok(());
    }

    let Some(policy_profile) = policy.semantic_profile.as_ref() else {
        return Err(RenderMetadataUsageError {
            code: "DEDIREN_RENDER_METADATA_PROFILE_REQUIRED",
            path: "semantic_profile".to_string(),
            message: "type-aware SVG render policies must declare semantic_profile".to_string(),
        });
    };
    let Some(metadata) = metadata else {
        return Err(RenderMetadataUsageError {
            code: "DEDIREN_RENDER_METADATA_REQUIRED",
            path: "render_metadata".to_string(),
            message: "type-aware SVG render policy requires render metadata".to_string(),
        });
    };
    if metadata.semantic_profile != *policy_profile {
        return Err(RenderMetadataUsageError {
            code: "DEDIREN_RENDER_METADATA_PROFILE_MISMATCH",
            path: "render_metadata.semantic_profile".to_string(),
            message: format!(
                "render metadata profile {} does not match policy profile {}",
                metadata.semantic_profile, policy_profile
            ),
        });
    }
    Ok(())
}

fn validate_archimate_policy_types(
    policy: &RenderPolicy,
) -> Result<(), ArchimateTypeValidationError> {
    if policy.semantic_profile.as_deref() != Some("archimate") {
        return Ok(());
    }

    let Some(style) = policy.style.as_ref() else {
        return Ok(());
    };

    for selector_type in style.node_type_overrides.keys() {
        dediren_archimate::validate_element_type(
            selector_type,
            format!("policy.style.node_type_overrides.{selector_type}"),
        )?;
    }
    for selector_type in style.edge_type_overrides.keys() {
        dediren_archimate::validate_relationship_type(
            selector_type,
            format!("policy.style.edge_type_overrides.{selector_type}"),
        )?;
    }
    for selector_type in style.group_type_overrides.keys() {
        dediren_archimate::validate_element_type(
            selector_type,
            format!("policy.style.group_type_overrides.{selector_type}"),
        )?;
    }
    Ok(())
}

fn validate_archimate_render_metadata(
    layout_result: &LayoutResult,
    metadata: Option<&RenderMetadata>,
) -> Result<(), ArchimateTypeValidationError> {
    let Some(metadata) = metadata else {
        return Ok(());
    };
    if metadata.semantic_profile != "archimate" {
        return Ok(());
    }

    for (node_id, selector) in &metadata.nodes {
        dediren_archimate::validate_element_type(
            &selector.selector_type,
            format!("render_metadata.nodes.{node_id}.type"),
        )?;
    }
    for (edge_id, selector) in &metadata.edges {
        dediren_archimate::validate_relationship_type(
            &selector.selector_type,
            format!("render_metadata.edges.{edge_id}.type"),
        )?;
    }
    for (group_id, selector) in &metadata.groups {
        dediren_archimate::validate_element_type(
            &selector.selector_type,
            format!("render_metadata.groups.{group_id}.type"),
        )?;
    }

    for edge in &layout_result.edges {
        let Some(edge_selector) = metadata.edges.get(&edge.id) else {
            continue;
        };
        let Some(source_selector) = metadata.nodes.get(&edge.source) else {
            continue;
        };
        let Some(target_selector) = metadata.nodes.get(&edge.target) else {
            continue;
        };

        dediren_archimate::validate_relationship_endpoint_types(
            &edge_selector.selector_type,
            &source_selector.selector_type,
            &target_selector.selector_type,
            format!("render_metadata.edges.{}", edge.id),
        )?;
    }
    Ok(())
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

    for (selector_type, node_style) in &style.node_type_overrides {
        validate_node_style(
            Some(node_style),
            &format!("style.node_type_overrides.{selector_type}"),
        )?;
    }
    for (selector_type, edge_style) in &style.edge_type_overrides {
        validate_edge_style(
            Some(edge_style),
            &format!("style.edge_type_overrides.{selector_type}"),
        )?;
    }
    for (id, node_style) in &style.node_overrides {
        validate_node_style(Some(node_style), &format!("style.node_overrides.{id}"))?;
    }
    for (id, edge_style) in &style.edge_overrides {
        validate_edge_style(Some(edge_style), &format!("style.edge_overrides.{id}"))?;
    }
    for (id, group_style) in &style.group_overrides {
        validate_group_style(Some(group_style), &format!("style.group_overrides.{id}"))?;
    }
    for (selector_type, group_style) in &style.group_type_overrides {
        validate_group_style(
            Some(group_style),
            &format!("style.group_type_overrides.{selector_type}"),
        )?;
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

fn svg_bounds(
    result: &LayoutResult,
    policy: &RenderPolicy,
    metadata: Option<&RenderMetadata>,
    style: &ResolvedStyle,
) -> SvgBounds {
    let mut bounds = SvgBounds::new_empty();

    for group in &result.groups {
        let group_style = group_style(policy, metadata, &group.id, style);
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
    for edge in &result.edges {
        let mut edge_occupied_boxes = occupied_label_boxes.clone();
        edge_occupied_boxes.extend(edge_route_obstacle_boxes(result, style.font_size, edge));
        let edge_style = edge_style(policy, metadata, &edge.id, style);
        if let Some(label) =
            edge_label_position_for_edge(edge, style.font_size, &edge_style, &edge_occupied_boxes)
        {
            bounds.include_rect(
                label.bounds.min_x,
                label.bounds.min_y,
                label.bounds.max_x - label.bounds.min_x,
                label.bounds.max_y - label.bounds.min_y,
            );
            occupied_label_boxes.push(label.bounds);
        }
    }

    for node in &result.nodes {
        bounds.include_rect(node.x, node.y, node.width, node.height);
        let (label_lines, label_font_size) = node_label_lines_and_size(node, style.font_size);
        let line_height = node_label_line_height(label_font_size);
        let first_y = node_label_first_line_y(node, label_font_size, label_lines.len());
        for (index, line) in label_lines.iter().enumerate() {
            bounds.include_label(
                node.x + node.width / 2.0,
                first_y + index as f64 * line_height,
                line,
                label_font_size,
            );
        }
    }

    if bounds.is_empty() {
        SvgBounds::fallback(policy).padded(policy)
    } else {
        bounds.padded(policy)
    }
}

fn render_svg(
    result: &LayoutResult,
    metadata: Option<&RenderMetadata>,
    policy: &RenderPolicy,
) -> String {
    let style = base_style(policy);
    let bounds = svg_bounds(result, policy, metadata, &style);
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
        r##"<rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" fill="{}"/>"##,
        bounds.min_x,
        bounds.min_y,
        bounds.width(),
        bounds.height(),
        escape_attr(&style.background_fill)
    ));
    svg.push_str(&format!(
        r##"<g font-family="{}" font-size="{}">"##,
        escape_attr(&style.font_family),
        svg_style_number(style.font_size)
    ));

    for group in &result.groups {
        let group_style = group_style(policy, metadata, &group.id, &style);
        let selector = metadata.and_then(|metadata| metadata.groups.get(&group.id));
        let group_type_attrs = selector
            .map(|selector| {
                format!(
                    r#" data-dediren-group-type="{}" data-dediren-group-source-id="{}""#,
                    escape_attr(&selector.selector_type),
                    escape_attr(&selector.source_id)
                )
            })
            .unwrap_or_default();
        svg.push_str(&format!(
            r#"<g data-dediren-group-id="{}"{}>"#,
            escape_attr(&group.id),
            group_type_attrs
        ));
        svg.push_str(&group_rect(group, &group_style));
        svg.push_str(&group_decorator(group, &group_style));
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
    for edge in &result.edges {
        let mut edge_occupied_boxes = occupied_label_boxes.clone();
        edge_occupied_boxes.extend(edge_route_obstacle_boxes(result, style.font_size, edge));
        let edge_style = edge_style(policy, metadata, &edge.id, &style);
        svg.push_str(&format!(
            r#"<g data-dediren-edge-id="{}">"#,
            escape_attr(&edge.id)
        ));
        svg.push_str(&edge_marker(edge, &edge_style));
        svg.push_str(&edge_path(edge, &edge_style, &rendered_edges));
        let edge_occupied_before_label = edge_occupied_boxes.len();
        svg.push_str(&edge_label(
            edge,
            &edge_style,
            &style.background_fill,
            style.font_size,
            &mut edge_occupied_boxes,
        ));
        if edge_occupied_boxes.len() > edge_occupied_before_label {
            if let Some(label_box) = edge_occupied_boxes.last() {
                occupied_label_boxes.push(label_box.clone());
            }
        }
        svg.push_str("</g>");
        rendered_edges.push(edge);
    }

    for node in &result.nodes {
        let node_style = node_style(policy, metadata, &node.id, &style);
        svg.push_str(&format!(
            r#"<g data-dediren-node-id="{}">"#,
            escape_attr(&node.id)
        ));
        svg.push_str(&node_shape(node, &node_style));
        svg.push_str(&node_decorator(node, &node_style));
        svg.push_str(&node_label(node, &node_style, style.font_size));
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
        decorator: None,
    };
    let default_edge = ResolvedEdgeStyle {
        stroke: "#64748b".to_string(),
        stroke_width: 1.5,
        label_fill: "#374151".to_string(),
        line_style: SvgEdgeLineStyle::Solid,
        marker_start: SvgEdgeMarkerEnd::None,
        marker_end: SvgEdgeMarkerEnd::FilledArrow,
        label_horizontal_position: SvgEdgeLabelHorizontalPosition::NearStart,
        label_horizontal_side: SvgEdgeLabelHorizontalSide::Auto,
        label_vertical_position: SvgEdgeLabelVerticalPosition::Center,
        label_vertical_side: SvgEdgeLabelVerticalSide::Left,
    };
    let default_group = ResolvedGroupStyle {
        fill: "#eff6ff".to_string(),
        stroke: "#93c5fd".to_string(),
        stroke_width: 1.0,
        rx: 8.0,
        label_fill: "#1e3a8a".to_string(),
        label_size: 12.0,
        decorator: None,
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

fn node_style(
    policy: &RenderPolicy,
    metadata: Option<&RenderMetadata>,
    node_id: &str,
    base: &ResolvedStyle,
) -> ResolvedNodeStyle {
    let type_style = metadata
        .and_then(|metadata| metadata.nodes.get(node_id))
        .and_then(|selector| {
            policy
                .style
                .as_ref()
                .and_then(|style| style.node_type_overrides.get(&selector.selector_type))
        });
    let resolved = merge_node_style(&base.node, type_style);
    merge_node_style(
        &resolved,
        policy
            .style
            .as_ref()
            .and_then(|style| style.node_overrides.get(node_id)),
    )
}

fn edge_style(
    policy: &RenderPolicy,
    metadata: Option<&RenderMetadata>,
    edge_id: &str,
    base: &ResolvedStyle,
) -> ResolvedEdgeStyle {
    let type_style = metadata
        .and_then(|metadata| metadata.edges.get(edge_id))
        .and_then(|selector| {
            policy
                .style
                .as_ref()
                .and_then(|style| style.edge_type_overrides.get(&selector.selector_type))
        });
    let resolved = merge_edge_style(&base.edge, type_style);
    merge_edge_style(
        &resolved,
        policy
            .style
            .as_ref()
            .and_then(|style| style.edge_overrides.get(edge_id)),
    )
}

fn group_style(
    policy: &RenderPolicy,
    metadata: Option<&RenderMetadata>,
    group_id: &str,
    base: &ResolvedStyle,
) -> ResolvedGroupStyle {
    let type_style = metadata
        .and_then(|metadata| metadata.groups.get(group_id))
        .and_then(|selector| {
            policy
                .style
                .as_ref()
                .and_then(|style| style.group_type_overrides.get(&selector.selector_type))
        });
    let resolved = merge_group_style(&base.group, type_style);
    merge_group_style(
        &resolved,
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
            decorator: style.decorator.or(base.decorator),
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
            line_style: style.line_style.unwrap_or(base.line_style),
            marker_start: style.marker_start.unwrap_or(base.marker_start),
            marker_end: style.marker_end.unwrap_or(base.marker_end),
            label_horizontal_position: style
                .label_horizontal_position
                .unwrap_or(base.label_horizontal_position),
            label_horizontal_side: style
                .label_horizontal_side
                .unwrap_or(base.label_horizontal_side),
            label_vertical_position: style
                .label_vertical_position
                .unwrap_or(base.label_vertical_position),
            label_vertical_side: style
                .label_vertical_side
                .unwrap_or(base.label_vertical_side),
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
            decorator: style.decorator.or(base.decorator),
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

fn group_decorator(group: &LaidOutGroup, style: &ResolvedGroupStyle) -> String {
    let Some(SvgNodeDecorator::ArchimateGrouping) = style.decorator else {
        return String::new();
    };
    let synthetic_node = LaidOutNode {
        id: group.id.clone(),
        source_id: group.source_id.clone(),
        projection_id: group.projection_id.clone(),
        x: group.x,
        y: group.y,
        width: group.width,
        height: group.height,
        label: group.label.clone(),
    };
    let node_style = ResolvedNodeStyle {
        fill: style.fill.clone(),
        stroke: style.stroke.clone(),
        stroke_width: style.stroke_width,
        rx: style.rx,
        label_fill: style.label_fill.clone(),
        decorator: style.decorator,
    };
    archimate_symbol_decorator(
        &synthetic_node,
        &node_style,
        "archimate_grouping",
        ArchimateIconKind::Grouping,
    )
    .replace(
        "data-dediren-node-decorator",
        "data-dediren-group-decorator",
    )
}

fn node_shape(node: &LaidOutNode, style: &ResolvedNodeStyle) -> String {
    if let Some(decorator) = style.decorator {
        if matches!(
            decorator,
            SvgNodeDecorator::ArchimateAndJunction | SvgNodeDecorator::ArchimateOrJunction
        ) {
            return archimate_junction_node_shape(node, style, decorator);
        }
        return archimate_rectangular_node_shape(node, style, decorator);
    }
    format!(
        r##"<rect data-dediren-node-shape="archimate_rectangle" x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="{}" fill="{}" stroke="{}" stroke-width="{}"/>"##,
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

fn archimate_junction_node_shape(
    node: &LaidOutNode,
    style: &ResolvedNodeStyle,
    decorator: SvgNodeDecorator,
) -> String {
    let center_x = node.x + node.width / 2.0;
    let center_y = node.y + node.height / 2.0;
    let radius = (node.width.min(node.height) / 2.0 - style.stroke_width).max(4.0);
    let (shape_name, fill) = match decorator {
        SvgNodeDecorator::ArchimateAndJunction => ("archimate_and_junction", style.stroke.as_str()),
        SvgNodeDecorator::ArchimateOrJunction => ("archimate_or_junction", style.fill.as_str()),
        _ => unreachable!("junction node shape only accepts junction decorators"),
    };
    format!(
        r##"<circle data-dediren-node-shape="{}" cx="{:.1}" cy="{:.1}" r="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/>"##,
        shape_name,
        center_x,
        center_y,
        radius,
        escape_attr(fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width)
    )
}

fn archimate_rectangular_node_shape(
    node: &LaidOutNode,
    style: &ResolvedNodeStyle,
    decorator: SvgNodeDecorator,
) -> String {
    if is_archimate_cut_corner_rectangle(decorator) {
        let corner = node.width.min(node.height) * 0.14;
        let corner = corner.clamp(8.0, 14.0);
        return format!(
            r##"<path data-dediren-node-shape="archimate_cut_corner_rectangle" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/>"##,
            node.x + corner,
            node.y,
            node.x + node.width - corner,
            node.y,
            node.x + node.width,
            node.y + corner,
            node.x + node.width,
            node.y + node.height - corner,
            node.x + node.width - corner,
            node.y + node.height,
            node.x + corner,
            node.y + node.height,
            node.x,
            node.y + node.height - corner,
            node.x,
            node.y + corner,
            escape_attr(&style.fill),
            escape_attr(&style.stroke),
            svg_style_number(style.stroke_width)
        );
    }

    let (shape_name, rx) = if is_archimate_rounded_rectangle(decorator) {
        ("archimate_rounded_rectangle", style.rx.max(1.0))
    } else {
        ("archimate_rectangle", 0.0)
    };
    format!(
        r##"<rect data-dediren-node-shape="{}" x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="{}" fill="{}" stroke="{}" stroke-width="{}"/>"##,
        shape_name,
        node.x,
        node.y,
        node.width,
        node.height,
        svg_style_number(rx),
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width)
    )
}

fn is_archimate_cut_corner_rectangle(decorator: SvgNodeDecorator) -> bool {
    matches!(
        decorator,
        SvgNodeDecorator::ArchimateStakeholder
            | SvgNodeDecorator::ArchimateDriver
            | SvgNodeDecorator::ArchimateAssessment
            | SvgNodeDecorator::ArchimateGoal
            | SvgNodeDecorator::ArchimateOutcome
            | SvgNodeDecorator::ArchimateValue
            | SvgNodeDecorator::ArchimateMeaning
            | SvgNodeDecorator::ArchimateConstraint
            | SvgNodeDecorator::ArchimateRequirement
            | SvgNodeDecorator::ArchimatePrinciple
    )
}

fn is_archimate_rounded_rectangle(decorator: SvgNodeDecorator) -> bool {
    matches!(
        decorator,
        SvgNodeDecorator::ArchimateWorkPackage
            | SvgNodeDecorator::ArchimateImplementationEvent
            | SvgNodeDecorator::ArchimateCourseOfAction
            | SvgNodeDecorator::ArchimateValueStream
            | SvgNodeDecorator::ArchimateCapability
            | SvgNodeDecorator::ArchimateBusinessService
            | SvgNodeDecorator::ArchimateBusinessFunction
            | SvgNodeDecorator::ArchimateBusinessProcess
            | SvgNodeDecorator::ArchimateBusinessEvent
            | SvgNodeDecorator::ArchimateApplicationService
            | SvgNodeDecorator::ArchimateApplicationFunction
            | SvgNodeDecorator::ArchimateApplicationProcess
            | SvgNodeDecorator::ArchimateApplicationEvent
            | SvgNodeDecorator::ArchimateTechnologyService
            | SvgNodeDecorator::ArchimateTechnologyFunction
            | SvgNodeDecorator::ArchimateTechnologyProcess
            | SvgNodeDecorator::ArchimateTechnologyEvent
    )
}
fn node_decorator(node: &LaidOutNode, style: &ResolvedNodeStyle) -> String {
    match style.decorator {
        Some(SvgNodeDecorator::ArchimateAndJunction | SvgNodeDecorator::ArchimateOrJunction) => {
            String::new()
        }
        Some(SvgNodeDecorator::ArchimateBusinessActor) => {
            archimate_business_actor_decorator(node, style)
        }
        Some(SvgNodeDecorator::ArchimateApplicationComponent) => {
            archimate_application_component_decorator(node, style)
        }
        Some(SvgNodeDecorator::ArchimateApplicationService) => {
            archimate_application_service_decorator(node, style)
        }
        Some(SvgNodeDecorator::ArchimateDataObject) => archimate_data_object_decorator(node, style),
        Some(SvgNodeDecorator::ArchimateTechnologyNode) => {
            archimate_technology_node_decorator(node, style)
        }
        Some(decorator) => archimate_symbol_decorator(
            node,
            style,
            &archimate_decorator_name(decorator),
            archimate_icon_kind(decorator),
        ),
        None => String::new(),
    }
}

#[derive(Debug, Clone, Copy)]
enum ArchimateIconKind {
    Actor,
    Interface,
    Collaboration,
    Role,
    Service,
    Interaction,
    Function,
    Process,
    Event,
    Object,
    Component,
    Contract,
    Product,
    Representation,
    Location,
    Grouping,
    Junction,
    Stakeholder,
    Driver,
    Goal,
    Assessment,
    Outcome,
    Value,
    Meaning,
    Constraint,
    Requirement,
    Principle,
    CourseOfAction,
    Resource,
    ValueStream,
    Capability,
    Plateau,
    WorkPackage,
    Deliverable,
    Gap,
    Artifact,
    SystemSoftware,
    Device,
    Facility,
    Equipment,
    Node,
    Material,
    Network,
    DistributionNetwork,
    Path,
}

impl ArchimateIconKind {
    fn as_str(self) -> &'static str {
        match self {
            ArchimateIconKind::Actor => "actor",
            ArchimateIconKind::Interface => "interface",
            ArchimateIconKind::Collaboration => "collaboration",
            ArchimateIconKind::Role => "role",
            ArchimateIconKind::Service => "service",
            ArchimateIconKind::Interaction => "interaction",
            ArchimateIconKind::Function => "function",
            ArchimateIconKind::Process => "process",
            ArchimateIconKind::Event => "event",
            ArchimateIconKind::Object => "object",
            ArchimateIconKind::Component => "component",
            ArchimateIconKind::Contract => "contract",
            ArchimateIconKind::Product => "product",
            ArchimateIconKind::Representation => "representation",
            ArchimateIconKind::Location => "location",
            ArchimateIconKind::Grouping => "grouping",
            ArchimateIconKind::Junction => "junction",
            ArchimateIconKind::Stakeholder => "stakeholder",
            ArchimateIconKind::Driver => "driver",
            ArchimateIconKind::Goal => "goal",
            ArchimateIconKind::Assessment => "assessment",
            ArchimateIconKind::Outcome => "outcome",
            ArchimateIconKind::Value => "value",
            ArchimateIconKind::Meaning => "meaning",
            ArchimateIconKind::Constraint => "constraint",
            ArchimateIconKind::Requirement => "requirement",
            ArchimateIconKind::Principle => "principle",
            ArchimateIconKind::CourseOfAction => "course_of_action",
            ArchimateIconKind::Resource => "resource",
            ArchimateIconKind::ValueStream => "value_stream",
            ArchimateIconKind::Capability => "capability",
            ArchimateIconKind::Plateau => "plateau",
            ArchimateIconKind::WorkPackage => "work_package",
            ArchimateIconKind::Deliverable => "deliverable",
            ArchimateIconKind::Gap => "gap",
            ArchimateIconKind::Artifact => "artifact",
            ArchimateIconKind::SystemSoftware => "system_software",
            ArchimateIconKind::Device => "device",
            ArchimateIconKind::Facility => "facility",
            ArchimateIconKind::Equipment => "equipment",
            ArchimateIconKind::Node => "node",
            ArchimateIconKind::Material => "material",
            ArchimateIconKind::Network => "network",
            ArchimateIconKind::DistributionNetwork => "distribution_network",
            ArchimateIconKind::Path => "path",
        }
    }
}

fn archimate_decorator_name(decorator: SvgNodeDecorator) -> String {
    serde_json::to_value(decorator)
        .expect("serialize SVG node decorator")
        .as_str()
        .expect("decorator serializes as string")
        .to_string()
}

fn archimate_icon_kind(decorator: SvgNodeDecorator) -> ArchimateIconKind {
    match decorator {
        SvgNodeDecorator::ArchimateBusinessInterface
        | SvgNodeDecorator::ArchimateApplicationInterface
        | SvgNodeDecorator::ArchimateTechnologyInterface => ArchimateIconKind::Interface,
        SvgNodeDecorator::ArchimateBusinessCollaboration
        | SvgNodeDecorator::ArchimateApplicationCollaboration
        | SvgNodeDecorator::ArchimateTechnologyCollaboration => ArchimateIconKind::Collaboration,
        SvgNodeDecorator::ArchimateBusinessRole => ArchimateIconKind::Role,
        SvgNodeDecorator::ArchimateBusinessService
        | SvgNodeDecorator::ArchimateTechnologyService => ArchimateIconKind::Service,
        SvgNodeDecorator::ArchimateBusinessInteraction
        | SvgNodeDecorator::ArchimateApplicationInteraction
        | SvgNodeDecorator::ArchimateTechnologyInteraction => ArchimateIconKind::Interaction,
        SvgNodeDecorator::ArchimateBusinessFunction
        | SvgNodeDecorator::ArchimateApplicationFunction
        | SvgNodeDecorator::ArchimateTechnologyFunction => ArchimateIconKind::Function,
        SvgNodeDecorator::ArchimateBusinessProcess
        | SvgNodeDecorator::ArchimateApplicationProcess
        | SvgNodeDecorator::ArchimateTechnologyProcess => ArchimateIconKind::Process,
        SvgNodeDecorator::ArchimateBusinessEvent
        | SvgNodeDecorator::ArchimateApplicationEvent
        | SvgNodeDecorator::ArchimateTechnologyEvent
        | SvgNodeDecorator::ArchimateImplementationEvent => ArchimateIconKind::Event,
        SvgNodeDecorator::ArchimateBusinessObject | SvgNodeDecorator::ArchimateDataObject => {
            ArchimateIconKind::Object
        }
        SvgNodeDecorator::ArchimateValue => ArchimateIconKind::Value,
        SvgNodeDecorator::ArchimateMeaning => ArchimateIconKind::Meaning,
        SvgNodeDecorator::ArchimateContract => ArchimateIconKind::Contract,
        SvgNodeDecorator::ArchimateProduct => ArchimateIconKind::Product,
        SvgNodeDecorator::ArchimateRepresentation => ArchimateIconKind::Representation,
        SvgNodeDecorator::ArchimateLocation => ArchimateIconKind::Location,
        SvgNodeDecorator::ArchimateGrouping => ArchimateIconKind::Grouping,
        SvgNodeDecorator::ArchimateAndJunction | SvgNodeDecorator::ArchimateOrJunction => {
            ArchimateIconKind::Junction
        }
        SvgNodeDecorator::ArchimateStakeholder => ArchimateIconKind::Stakeholder,
        SvgNodeDecorator::ArchimateDriver => ArchimateIconKind::Driver,
        SvgNodeDecorator::ArchimateGoal => ArchimateIconKind::Goal,
        SvgNodeDecorator::ArchimateOutcome => ArchimateIconKind::Outcome,
        SvgNodeDecorator::ArchimateAssessment => ArchimateIconKind::Assessment,
        SvgNodeDecorator::ArchimateConstraint => ArchimateIconKind::Constraint,
        SvgNodeDecorator::ArchimateRequirement => ArchimateIconKind::Requirement,
        SvgNodeDecorator::ArchimatePrinciple => ArchimateIconKind::Principle,
        SvgNodeDecorator::ArchimateCourseOfAction => ArchimateIconKind::CourseOfAction,
        SvgNodeDecorator::ArchimateResource => ArchimateIconKind::Resource,
        SvgNodeDecorator::ArchimateValueStream => ArchimateIconKind::ValueStream,
        SvgNodeDecorator::ArchimateCapability => ArchimateIconKind::Capability,
        SvgNodeDecorator::ArchimatePlateau => ArchimateIconKind::Plateau,
        SvgNodeDecorator::ArchimateWorkPackage => ArchimateIconKind::WorkPackage,
        SvgNodeDecorator::ArchimateDeliverable => ArchimateIconKind::Deliverable,
        SvgNodeDecorator::ArchimateGap => ArchimateIconKind::Gap,
        SvgNodeDecorator::ArchimateArtifact => ArchimateIconKind::Artifact,
        SvgNodeDecorator::ArchimateSystemSoftware => ArchimateIconKind::SystemSoftware,
        SvgNodeDecorator::ArchimateDevice => ArchimateIconKind::Device,
        SvgNodeDecorator::ArchimateFacility => ArchimateIconKind::Facility,
        SvgNodeDecorator::ArchimateEquipment => ArchimateIconKind::Equipment,
        SvgNodeDecorator::ArchimateTechnologyNode => ArchimateIconKind::Node,
        SvgNodeDecorator::ArchimateMaterial => ArchimateIconKind::Material,
        SvgNodeDecorator::ArchimateCommunicationNetwork => ArchimateIconKind::Network,
        SvgNodeDecorator::ArchimateDistributionNetwork => ArchimateIconKind::DistributionNetwork,
        SvgNodeDecorator::ArchimatePath => ArchimateIconKind::Path,
        SvgNodeDecorator::ArchimateBusinessActor => ArchimateIconKind::Actor,
        SvgNodeDecorator::ArchimateApplicationComponent => ArchimateIconKind::Component,
        SvgNodeDecorator::ArchimateApplicationService => ArchimateIconKind::Service,
    }
}

const ARCHIMATE_ICON_SIZE: f64 = 22.0;

#[derive(Debug, Clone, Copy)]
struct ArchimateIconBox {
    x: f64,
    y: f64,
    size: f64,
}

fn archimate_icon_box(node: &LaidOutNode) -> ArchimateIconBox {
    ArchimateIconBox {
        x: node.x + node.width - ARCHIMATE_ICON_SIZE - 6.0,
        y: node.y + 9.0,
        size: ARCHIMATE_ICON_SIZE,
    }
}

fn archimate_decorator_group_attrs(decorator_name: &str, kind: ArchimateIconKind) -> String {
    format!(
        r#"data-dediren-node-decorator="{}" data-dediren-icon-kind="{}" data-dediren-icon-size="{}""#,
        escape_attr(decorator_name),
        kind.as_str(),
        ARCHIMATE_ICON_SIZE as i64
    )
}

fn archimate_actor_icon_body(
    x: f64,
    y: f64,
    size: f64,
    fill: &str,
    stroke: &str,
    width: &str,
) -> String {
    let cx = x + size * 0.5;
    let head_rx = size * 0.16;
    let head_ry = size * 0.2;
    let head_cy = y + head_ry;
    let body_top = y + head_ry * 2.0 + size * 0.08;
    let body_bottom = y + size * 0.72;
    let arm_y = body_top + size * 0.12;
    format!(
        r##"<ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><path d="M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round" stroke-linejoin="round"/>"##,
        cx,
        head_cy,
        head_rx,
        head_ry,
        fill,
        stroke,
        width,
        cx,
        body_top,
        cx,
        body_bottom,
        cx - size * 0.28,
        arm_y,
        cx,
        body_top + size * 0.1,
        cx + size * 0.28,
        arm_y,
        cx,
        body_bottom,
        cx - size * 0.24,
        y + size,
        cx,
        body_bottom,
        cx + size * 0.24,
        y + size,
        stroke,
        width
    )
}

#[derive(Debug, Clone, Copy)]
enum TargetIconStyle {
    Bullseye,
    Arrow,
    Handle,
}

fn archimate_target_icon_body(
    x: f64,
    y: f64,
    size: f64,
    fill: &str,
    stroke: &str,
    width: &str,
    style: TargetIconStyle,
) -> String {
    let center_x = x + size * 0.5;
    let center_y = y + size * 0.36;
    let outer = size * 0.34;
    let inner = size * 0.16;
    match style {
        TargetIconStyle::Bullseye => format!(
            r#"<ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="none" stroke="{}" stroke-width="{}"/><ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="none" stroke="{}" stroke-width="{}"/>"#,
            center_x,
            center_y,
            outer,
            outer,
            fill,
            stroke,
            width,
            center_x,
            center_y,
            inner,
            inner,
            stroke,
            width,
            center_x,
            center_y,
            size * 0.05,
            size * 0.05,
            stroke,
            width
        ),
        TargetIconStyle::Arrow => format!(
            r#"<ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="none" stroke="{}" stroke-width="{}"/><ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="none" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="target-arrow" d="M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round"/>"#,
            center_x,
            center_y,
            outer,
            outer,
            fill,
            stroke,
            width,
            center_x,
            center_y,
            inner,
            inner,
            stroke,
            width,
            center_x,
            center_y,
            size * 0.05,
            size * 0.05,
            stroke,
            width,
            center_x,
            center_y,
            x + size * 0.82,
            y + size * 0.08,
            x + size * 0.82,
            y + size * 0.04,
            x + size * 0.82,
            y + size * 0.13,
            x + size * 0.78,
            y + size * 0.12,
            x + size * 0.86,
            y + size * 0.05,
            x + size * 0.82,
            y + size * 0.08,
            x + size * 0.88,
            y + size * 0.01,
            stroke,
            width
        ),
        TargetIconStyle::Handle => format!(
            r#"<ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="none" stroke="{}" stroke-width="{}"/><ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="none" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="course-of-action-handle" d="M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round"/>"#,
            center_x,
            center_y,
            outer,
            outer,
            fill,
            stroke,
            width,
            center_x,
            center_y,
            inner,
            inner,
            stroke,
            width,
            center_x,
            center_y,
            size * 0.05,
            size * 0.05,
            stroke,
            width,
            center_x - size * 0.22,
            center_y + size * 0.2,
            x + size * 0.06,
            y + size * 0.72,
            stroke,
            width
        ),
    }
}

fn archimate_document_icon_body(
    x: f64,
    y: f64,
    size: f64,
    fill: &str,
    stroke: &str,
    width: &str,
    folded: bool,
) -> String {
    if folded {
        return format!(
            r#"<path data-dediren-icon-part="document-fold" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1}" fill="{}" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="document-header" d="M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}"/>"#,
            x,
            y,
            x + size * 0.72,
            y,
            x + size * 0.92,
            y + size * 0.2,
            x + size * 0.92,
            y + size * 0.72,
            x,
            y + size * 0.72,
            x + size * 0.72,
            y,
            x + size * 0.72,
            y + size * 0.2,
            x + size * 0.92,
            y + size * 0.2,
            fill,
            stroke,
            width,
            x,
            y + size * 0.22,
            x + size * 0.68,
            y + size * 0.22,
            stroke,
            width
        );
    }
    format!(
        r#"<path data-dediren-icon-part="document-body" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="document-header" d="M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}"/>"#,
        x,
        y,
        x + size * 0.92,
        y,
        x + size * 0.92,
        y + size * 0.72,
        x,
        y + size * 0.72,
        fill,
        stroke,
        width,
        x,
        y + size * 0.22,
        x + size * 0.92,
        y + size * 0.22,
        stroke,
        width
    )
}

fn archimate_folded_document_icon_body(
    part: &str,
    x: f64,
    y: f64,
    size: f64,
    fill: &str,
    stroke: &str,
    width: &str,
) -> String {
    format!(
        r#"<path data-dediren-icon-part="{}" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1}" fill="{}" stroke="{}" stroke-width="{}"/>"#,
        escape_attr(part),
        x + size * 0.1,
        y,
        x + size * 0.58,
        y,
        x + size * 0.86,
        y + size * 0.28,
        x + size * 0.86,
        y + size * 0.9,
        x + size * 0.1,
        y + size * 0.9,
        x + size * 0.58,
        y,
        x + size * 0.58,
        y + size * 0.28,
        x + size * 0.86,
        y + size * 0.28,
        fill,
        stroke,
        width
    )
}

fn archimate_contract_icon_body(
    x: f64,
    y: f64,
    size: f64,
    fill: &str,
    stroke: &str,
    width: &str,
) -> String {
    format!(
        r#"<path data-dediren-icon-part="contract-document-body" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="contract-lines" d="M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}"/>"#,
        x,
        y,
        x + size * 0.92,
        y,
        x + size * 0.92,
        y + size * 0.72,
        x,
        y + size * 0.72,
        fill,
        stroke,
        width,
        x,
        y + size * 0.24,
        x + size * 0.92,
        y + size * 0.24,
        x,
        y + size * 0.48,
        x + size * 0.92,
        y + size * 0.48,
        stroke,
        width
    )
}

fn archimate_gear_path(
    part: &str,
    cx: f64,
    cy: f64,
    radius: f64,
    fill: &str,
    stroke: &str,
    width: &str,
) -> String {
    let points = [
        (cx, cy - radius),
        (cx + radius * 0.18, cy - radius * 0.72),
        (cx + radius * 0.42, cy - radius * 0.9),
        (cx + radius * 0.52, cy - radius * 0.58),
        (cx + radius * 0.84, cy - radius * 0.54),
        (cx + radius * 0.72, cy - radius * 0.18),
        (cx + radius, cy),
        (cx + radius * 0.72, cy + radius * 0.18),
        (cx + radius * 0.84, cy + radius * 0.54),
        (cx + radius * 0.52, cy + radius * 0.58),
        (cx + radius * 0.42, cy + radius * 0.9),
        (cx + radius * 0.18, cy + radius * 0.72),
        (cx, cy + radius),
        (cx - radius * 0.18, cy + radius * 0.72),
        (cx - radius * 0.42, cy + radius * 0.9),
        (cx - radius * 0.52, cy + radius * 0.58),
        (cx - radius * 0.84, cy + radius * 0.54),
        (cx - radius * 0.72, cy + radius * 0.18),
        (cx - radius, cy),
        (cx - radius * 0.72, cy - radius * 0.18),
        (cx - radius * 0.84, cy - radius * 0.54),
        (cx - radius * 0.52, cy - radius * 0.58),
        (cx - radius * 0.42, cy - radius * 0.9),
        (cx - radius * 0.18, cy - radius * 0.72),
    ];
    let d = points
        .iter()
        .enumerate()
        .map(|(index, (x, y))| {
            let command = if index == 0 { "M" } else { "L" };
            format!("{command} {x:.1} {y:.1}")
        })
        .collect::<Vec<_>>()
        .join(" ");
    format!(
        r#"<path data-dediren-icon-part="{}" d="{} Z" fill="{}" stroke="{}" stroke-width="{}"/>"#,
        escape_attr(part),
        d,
        fill,
        stroke,
        width
    )
}

fn archimate_symbol_decorator(
    node: &LaidOutNode,
    style: &ResolvedNodeStyle,
    decorator_name: &str,
    kind: ArchimateIconKind,
) -> String {
    let icon_box = archimate_icon_box(node);
    let size = icon_box.size;
    let x = icon_box.x;
    let y = icon_box.y;
    let stroke = escape_attr(&style.stroke);
    let fill = escape_attr(&style.fill);
    let width = svg_style_number(style.stroke_width);
    let body = match kind {
        ArchimateIconKind::Junction => String::new(),
        ArchimateIconKind::Actor => archimate_actor_icon_body(x, y, size, &fill, &stroke, &width),
        ArchimateIconKind::Interface => format!(
            r#"<ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><path d="M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.72,
            y + size * 0.28,
            size * 0.18,
            size * 0.18,
            fill,
            stroke,
            width,
            x + size * 0.1,
            y + size * 0.28,
            x + size * 0.54,
            y + size * 0.28,
            stroke,
            width
        ),
        ArchimateIconKind::Collaboration => format!(
            r#"<circle data-dediren-icon-part="collaboration-circles" cx="{:.1}" cy="{:.1}" r="{:.1}" fill="{}" stroke="none"/><circle data-dediren-icon-part="collaboration-circles" cx="{:.1}" cy="{:.1}" r="{:.1}" fill="{}" stroke="none"/><circle data-dediren-icon-part="collaboration-circles" cx="{:.1}" cy="{:.1}" r="{:.1}" fill="none" stroke="{}" stroke-width="{}"/><circle data-dediren-icon-part="collaboration-circles" cx="{:.1}" cy="{:.1}" r="{:.1}" fill="none" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.38,
            y + size * 0.38,
            size * 0.26,
            fill,
            x + size * 0.62,
            y + size * 0.38,
            size * 0.26,
            fill,
            x + size * 0.38,
            y + size * 0.38,
            size * 0.26,
            stroke,
            width,
            x + size * 0.62,
            y + size * 0.38,
            size * 0.26,
            stroke,
            width
        ),
        ArchimateIconKind::Role => format!(
            r#"<path data-dediren-icon-part="side-cylinder" d="M {:.1} {:.1} A {:.1} {:.1} 0 0 0 {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}"/><ellipse data-dediren-icon-part="side-cylinder-end" cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.1,
            y + size * 0.2,
            size * 0.16,
            size * 0.16,
            x + size * 0.1,
            y + size * 0.52,
            x + size * 0.1,
            y + size * 0.2,
            x + size * 0.7,
            y + size * 0.2,
            x + size * 0.1,
            y + size * 0.52,
            x + size * 0.7,
            y + size * 0.52,
            stroke,
            width,
            x + size * 0.7,
            y + size * 0.36,
            size * 0.16,
            size * 0.16,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Service => format!(
            r#"<rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x,
            y + size * 0.12,
            size,
            size * 0.5,
            size * 0.18,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Interaction => format!(
            r#"<path data-dediren-icon-part="interaction-half" d="M {:.1} {:.1} A {:.1} {:.1} 0 0 0 {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="interaction-half" d="M {:.1} {:.1} A {:.1} {:.1} 0 0 1 {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.42,
            y + size * 0.12,
            size * 0.24,
            size * 0.24,
            x + size * 0.42,
            y + size * 0.6,
            x + size * 0.42,
            y + size * 0.12,
            stroke,
            width,
            x + size * 0.58,
            y + size * 0.12,
            size * 0.24,
            size * 0.24,
            x + size * 0.58,
            y + size * 0.6,
            x + size * 0.58,
            y + size * 0.12,
            stroke,
            width
        ),
        ArchimateIconKind::Function => format!(
            r#"<path data-dediren-icon-part="function-bookmark" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.18,
            y + size * 0.2,
            x + size * 0.5,
            y + size * 0.06,
            x + size * 0.82,
            y + size * 0.2,
            x + size * 0.82,
            y + size * 0.7,
            x + size * 0.5,
            y + size * 0.56,
            x + size * 0.18,
            y + size * 0.7,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Process => format!(
            r#"<path data-dediren-icon-part="process-arrow" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x,
            y + size * 0.24,
            x + size * 0.62,
            y + size * 0.24,
            x + size * 0.62,
            y,
            x + size,
            y + size * 0.36,
            x + size * 0.62,
            y + size * 0.72,
            x + size * 0.62,
            y + size * 0.48,
            x,
            y + size * 0.48,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::CourseOfAction => {
            archimate_target_icon_body(x, y, size, &fill, &stroke, &width, TargetIconStyle::Handle)
        }
        ArchimateIconKind::Event => format!(
            r#"<path data-dediren-icon-part="event-pill" d="M {:.1} {:.1} L {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.18,
            y + size * 0.04,
            x + size * 0.74,
            y + size * 0.04,
            x + size * 0.94,
            y + size * 0.04,
            x + size,
            y + size * 0.2,
            x + size,
            y + size * 0.36,
            x + size,
            y + size * 0.52,
            x + size * 0.94,
            y + size * 0.68,
            x + size * 0.74,
            y + size * 0.68,
            x + size * 0.18,
            y + size * 0.68,
            x + size * 0.34,
            y + size * 0.36,
            x + size * 0.18,
            y + size * 0.04,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Object => {
            archimate_document_icon_body(x, y, size, &fill, &stroke, &width, false)
        }
        ArchimateIconKind::Artifact => archimate_folded_document_icon_body(
            "artifact-document",
            x,
            y - 1.0,
            size,
            &fill,
            &stroke,
            &width,
        ),
        ArchimateIconKind::Component => format!(
            r#"<path data-dediren-icon-part="document-fold" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1}" fill="{}" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="document-header" d="M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}"/>"#,
            x,
            y,
            x + size * 0.72,
            y,
            x + size * 0.92,
            y + size * 0.2,
            x + size * 0.92,
            y + size * 0.72,
            x,
            y + size * 0.72,
            x + size * 0.72,
            y,
            x + size * 0.72,
            y + size * 0.2,
            x + size * 0.92,
            y + size * 0.2,
            fill,
            stroke,
            width,
            x,
            y + size * 0.22,
            x + size * 0.68,
            y + size * 0.22,
            stroke,
            width
        ),
        ArchimateIconKind::Deliverable => format!(
            r#"<path data-dediren-icon-part="wavy-document" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}" stroke-linejoin="round"/>"#,
            x,
            y,
            x + size,
            y,
            x + size,
            y + size * 0.58,
            x + size * 0.82,
            y + size * 0.5,
            x + size * 0.66,
            y + size * 0.5,
            x + size * 0.5,
            y + size * 0.58,
            x + size * 0.34,
            y + size * 0.66,
            x + size * 0.18,
            y + size * 0.66,
            x,
            y + size * 0.58,
            x,
            y,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Contract => {
            archimate_contract_icon_body(x, y, size, &fill, &stroke, &width)
        }
        ArchimateIconKind::Product => format!(
            r#"<path data-dediren-icon-part="product-tab" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1}" fill="{}" stroke="{}" stroke-width="{}" stroke-linejoin="round"/>"#,
            x,
            y,
            x + size,
            y,
            x + size,
            y + size * 0.72,
            x,
            y + size * 0.72,
            x,
            y,
            x,
            y + size * 0.24,
            x + size * 0.62,
            y + size * 0.24,
            x + size * 0.62,
            y,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Requirement => format!(
            r#"<path data-dediren-icon-part="requirement-parallelogram" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.22,
            y,
            x + size,
            y,
            x + size * 0.78,
            y + size * 0.72,
            x,
            y + size * 0.72,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Constraint => format!(
            r#"<path data-dediren-icon-part="constraint-parallelogram" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="constraint-left-line" d="M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.22,
            y,
            x + size,
            y,
            x + size * 0.78,
            y + size * 0.72,
            x,
            y + size * 0.72,
            fill,
            stroke,
            width,
            x + size * 0.32,
            y,
            x + size * 0.1,
            y + size * 0.72,
            stroke,
            width
        ),
        ArchimateIconKind::Capability => format!(
            r#"<rect data-dediren-icon-part="capability-step" x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><rect data-dediren-icon-part="capability-step" x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><rect data-dediren-icon-part="capability-step" x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><rect data-dediren-icon-part="capability-step" x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><rect data-dediren-icon-part="capability-step" x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><rect data-dediren-icon-part="capability-step" x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.2,
            y + size * 0.52,
            size * 0.2,
            size * 0.2,
            fill,
            stroke,
            width,
            x + size * 0.4,
            y + size * 0.52,
            size * 0.2,
            size * 0.2,
            fill,
            stroke,
            width,
            x + size * 0.4,
            y + size * 0.32,
            size * 0.2,
            size * 0.2,
            fill,
            stroke,
            width,
            x + size * 0.6,
            y + size * 0.52,
            size * 0.2,
            size * 0.2,
            fill,
            stroke,
            width,
            x + size * 0.6,
            y + size * 0.32,
            size * 0.2,
            size * 0.2,
            fill,
            stroke,
            width,
            x + size * 0.6,
            y + size * 0.12,
            size * 0.2,
            size * 0.2,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Representation => format!(
            r#"<path data-dediren-icon-part="wavy-representation" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} L {:.1} {:.1} Z M {:.1} {:.1} L {:.1} {:.1}" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x,
            y,
            x + size * 0.92,
            y,
            x + size * 0.92,
            y + size * 0.58,
            x + size * 0.74,
            y + size * 0.5,
            x + size * 0.56,
            y + size * 0.5,
            x + size * 0.42,
            y + size * 0.58,
            x + size * 0.28,
            y + size * 0.66,
            x + size * 0.14,
            y + size * 0.66,
            x,
            y + size * 0.58,
            x,
            y,
            x,
            y + size * 0.22,
            x + size * 0.92,
            y + size * 0.22,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Location => {
            let y = y - 3.0;
            format!(
                r#"<path d="M {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/>"#,
                x + size * 0.5,
                y + size,
                x + size * 0.04,
                y + size * 0.5,
                x + size * 0.14,
                y,
                x + size * 0.5,
                y,
                x + size * 0.86,
                y,
                x + size * 0.96,
                y + size * 0.5,
                x + size * 0.5,
                y + size,
                fill,
                stroke,
                width
            )
        }
        ArchimateIconKind::Grouping => format!(
            r#"<rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="1.2" fill="none" stroke="{}" stroke-width="{}" stroke-dasharray="3 2"/>"#,
            x,
            y,
            size,
            size * 0.72,
            stroke,
            width
        ),
        ArchimateIconKind::Stakeholder => format!(
            r#"<path data-dediren-icon-part="side-cylinder" d="M {:.1} {:.1} A {:.1} {:.1} 0 0 0 {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}"/><ellipse data-dediren-icon-part="side-cylinder-end" cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.1,
            y + size * 0.2,
            size * 0.16,
            size * 0.16,
            x + size * 0.1,
            y + size * 0.52,
            x + size * 0.1,
            y + size * 0.2,
            x + size * 0.7,
            y + size * 0.2,
            x + size * 0.1,
            y + size * 0.52,
            x + size * 0.7,
            y + size * 0.52,
            stroke,
            width,
            x + size * 0.7,
            y + size * 0.36,
            size * 0.16,
            size * 0.16,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Driver => format!(
            r#"<ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="none" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="driver-spokes" d="M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round"/><ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.5,
            y + size * 0.36,
            size * 0.28,
            size * 0.28,
            stroke,
            width,
            x + size * 0.5,
            y,
            x + size * 0.5,
            y + size * 0.72,
            x + size * 0.14,
            y + size * 0.36,
            x + size * 0.86,
            y + size * 0.36,
            x + size * 0.26,
            y + size * 0.12,
            x + size * 0.74,
            y + size * 0.6,
            x + size * 0.26,
            y + size * 0.6,
            x + size * 0.74,
            y + size * 0.12,
            stroke,
            width,
            x + size * 0.5,
            y + size * 0.36,
            size * 0.12,
            size * 0.08,
            stroke,
            stroke,
            width
        ),
        ArchimateIconKind::Assessment => format!(
            r#"<ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="none" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="assessment-handle" d="M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round"/>"#,
            x + size * 0.5,
            y + size * 0.28,
            size * 0.22,
            size * 0.22,
            stroke,
            width,
            x + size * 0.36,
            y + size * 0.44,
            x + size * 0.16,
            y + size * 0.64,
            stroke,
            width
        ),
        ArchimateIconKind::Goal => archimate_target_icon_body(
            x,
            y,
            size,
            &fill,
            &stroke,
            &width,
            TargetIconStyle::Bullseye,
        ),
        ArchimateIconKind::Outcome => {
            archimate_target_icon_body(x, y, size, &fill, &stroke, &width, TargetIconStyle::Arrow)
        }
        ArchimateIconKind::Value => format!(
            r#"<ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.5,
            y + size * 0.36,
            size * 0.44,
            size * 0.24,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Meaning => format!(
            r#"<path d="M {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} C {:.1} {:.1}, {:.1} {:.1}, {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.18,
            y + size * 0.54,
            x + size * 0.04,
            y + size * 0.38,
            x + size * 0.18,
            y + size * 0.22,
            x + size * 0.36,
            y + size * 0.28,
            x + size * 0.46,
            y + size * 0.04,
            x + size * 0.68,
            y + size * 0.18,
            x + size * 0.66,
            y + size * 0.34,
            x + size * 0.92,
            y + size * 0.32,
            x + size * 0.94,
            y + size * 0.54,
            x + size * 0.72,
            y + size * 0.62,
            x + size * 0.42,
            y + size * 0.68,
            x + size * 0.26,
            y + size * 0.62,
            x + size * 0.18,
            y + size * 0.54,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Principle => format!(
            r#"<rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="1.2" fill="{}" stroke="{}" stroke-width="{}"/><path d="M {:.1} {:.1} L {:.1} {:.1}" stroke="{}" stroke-width="{}"/><circle cx="{:.1}" cy="{:.1}" r="1.2" fill="{}"/>"#,
            x,
            y,
            size,
            size * 0.72,
            fill,
            stroke,
            width,
            x + size * 0.5,
            y + size * 0.12,
            x + size * 0.5,
            y + size * 0.44,
            stroke,
            width,
            x + size * 0.5,
            y + size * 0.58,
            stroke
        ),
        ArchimateIconKind::Resource => format!(
            r#"<rect data-dediren-icon-part="resource-capsule" x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/><rect data-dediren-icon-part="resource-tab" x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="0.8" fill="{}" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="resource-bars" d="M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" stroke="{}" stroke-width="{}" stroke-linecap="round"/>"#,
            x + size * 0.04,
            y + size * 0.18,
            size * 0.78,
            size * 0.4,
            size * 0.12,
            fill,
            stroke,
            width,
            x + size * 0.82,
            y + size * 0.3,
            size * 0.08,
            size * 0.2,
            fill,
            stroke,
            width,
            x + size * 0.2,
            y + size * 0.28,
            x + size * 0.2,
            y + size * 0.52,
            x + size * 0.34,
            y + size * 0.28,
            x + size * 0.34,
            y + size * 0.52,
            x + size * 0.48,
            y + size * 0.28,
            x + size * 0.48,
            y + size * 0.52,
            stroke,
            width
        ),
        ArchimateIconKind::ValueStream => format!(
            r#"<path data-dediren-icon-part="value-stream-chevron" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x,
            y + size * 0.08,
            x + size * 0.68,
            y + size * 0.08,
            x + size,
            y + size * 0.36,
            x + size * 0.68,
            y + size * 0.64,
            x,
            y + size * 0.64,
            x + size * 0.24,
            y + size * 0.36,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Plateau => format!(
            r#"<path d="M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="square"/>"#,
            x + size * 0.26,
            y + size * 0.18,
            x + size * 0.88,
            y + size * 0.18,
            x + size * 0.12,
            y + size * 0.36,
            x + size * 0.74,
            y + size * 0.36,
            x + size * 0.0,
            y + size * 0.54,
            x + size * 0.62,
            y + size * 0.54,
            stroke,
            width
        ),
        ArchimateIconKind::WorkPackage => {
            let x = x - size * 0.14;
            let y = y - size * 0.3;
            format!(
                r#"<path data-dediren-icon-part="work-package-loop-arrow" d="M {:.1} {:.1} A {:.1} {:.1} 0 1 0 {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round" stroke-linejoin="round"/>"#,
                x + size * 0.58,
                y + size * 0.52,
                size * 0.26,
                size * 0.26,
                x + size * 0.54,
                y + size * 0.72,
                x + size * 0.86,
                y + size * 0.72,
                x + size * 0.86,
                y + size * 0.72,
                x + size * 0.74,
                y + size * 0.62,
                x + size * 0.86,
                y + size * 0.72,
                x + size * 0.74,
                y + size * 0.82,
                stroke,
                width
            )
        }
        ArchimateIconKind::Gap => format!(
            r#"<ellipse cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="none" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="gap-lines" d="M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round"/>"#,
            x + size * 0.52,
            y + size * 0.34,
            size * 0.22,
            size * 0.22,
            stroke,
            width,
            x + size * 0.1,
            y + size * 0.26,
            x + size * 0.94,
            y + size * 0.26,
            x + size * 0.1,
            y + size * 0.42,
            x + size * 0.94,
            y + size * 0.42,
            stroke,
            width
        ),
        ArchimateIconKind::SystemSoftware => format!(
            r#"<ellipse data-dediren-icon-part="system-software-disks" cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="none" stroke="{}" stroke-width="{}"/><ellipse data-dediren-icon-part="system-software-disks" cx="{:.1}" cy="{:.1}" rx="{:.1}" ry="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.58,
            y + size * 0.36,
            size * 0.26,
            size * 0.26,
            stroke,
            width,
            x + size * 0.38,
            y + size * 0.5,
            size * 0.26,
            size * 0.26,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Device => format!(
            r#"<rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="2.0" fill="{}" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="device-stand" d="M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round"/>"#,
            x + size * 0.08,
            y + size * 0.04,
            size * 0.84,
            size * 0.52,
            fill,
            stroke,
            width,
            x + size * 0.5,
            y + size * 0.56,
            x + size * 0.5,
            y + size * 0.72,
            x + size * 0.32,
            y + size * 0.72,
            x + size * 0.68,
            y + size * 0.72,
            stroke,
            width
        ),
        ArchimateIconKind::Facility => format!(
            r#"<path data-dediren-icon-part="factory-silhouette" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.08,
            y + size * 0.72,
            x + size * 0.08,
            y + size * 0.08,
            x + size * 0.22,
            y + size * 0.08,
            x + size * 0.22,
            y + size * 0.48,
            x + size * 0.42,
            y + size * 0.34,
            x + size * 0.42,
            y + size * 0.48,
            x + size * 0.62,
            y + size * 0.34,
            x + size * 0.62,
            y + size * 0.48,
            x + size * 0.84,
            y + size * 0.34,
            x + size * 0.84,
            y + size * 0.72,
            x + size * 0.08,
            y + size * 0.72,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Equipment => format!(
            r#"{}<circle data-dediren-icon-part="equipment-gear-hole" cx="{:.1}" cy="{:.1}" r="{:.1}" fill="none" stroke="{}" stroke-width="{}"/>{}<circle data-dediren-icon-part="equipment-gear-hole" cx="{:.1}" cy="{:.1}" r="{:.1}" fill="none" stroke="{}" stroke-width="{}"/>"#,
            archimate_gear_path(
                "equipment-gear-large",
                x + size * 0.34,
                y + size * 0.52,
                size * 0.24,
                &fill,
                &stroke,
                &width
            ),
            x + size * 0.34,
            y + size * 0.52,
            size * 0.06,
            stroke,
            width,
            archimate_gear_path(
                "equipment-gear-small",
                x + size * 0.68,
                y + size * 0.24,
                size * 0.16,
                &fill,
                &stroke,
                &width
            ),
            x + size * 0.68,
            y + size * 0.24,
            size * 0.04,
            stroke,
            width
        ),
        ArchimateIconKind::Node => archimate_technology_node_decorator(node, style).replace(
            r#"data-dediren-node-decorator="archimate_technology_node""#,
            &format!(
                r#"data-dediren-node-decorator="{}""#,
                escape_attr(decorator_name)
            ),
        ),
        ArchimateIconKind::Material => format!(
            r#"<path data-dediren-icon-part="material-hexagon" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="material-lines" d="M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round"/>"#,
            x + size * 0.25,
            y,
            x + size * 0.75,
            y,
            x + size,
            y + size * 0.36,
            x + size * 0.75,
            y + size * 0.72,
            x + size * 0.25,
            y + size * 0.72,
            x,
            y + size * 0.36,
            fill,
            stroke,
            width,
            x + size * 0.36,
            y + size * 0.16,
            x + size * 0.64,
            y + size * 0.16,
            x + size * 0.78,
            y + size * 0.28,
            x + size * 0.62,
            y + size * 0.52,
            x + size * 0.22,
            y + size * 0.28,
            x + size * 0.38,
            y + size * 0.52,
            stroke,
            width
        ),
        ArchimateIconKind::Network => format!(
            r#"<circle cx="{:.1}" cy="{:.1}" r="2.3" fill="{}"/><circle cx="{:.1}" cy="{:.1}" r="2.3" fill="{}"/><circle cx="{:.1}" cy="{:.1}" r="2.3" fill="{}"/><circle cx="{:.1}" cy="{:.1}" r="2.3" fill="{}"/><path d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="none" stroke="{}" stroke-width="{}"/>"#,
            x + size * 0.32,
            y + size * 0.22,
            stroke,
            x + size * 0.72,
            y + size * 0.22,
            stroke,
            x + size * 0.22,
            y + size * 0.58,
            stroke,
            x + size * 0.62,
            y + size * 0.58,
            stroke,
            x + size * 0.32,
            y + size * 0.22,
            x + size * 0.72,
            y + size * 0.22,
            x + size * 0.62,
            y + size * 0.58,
            x + size * 0.22,
            y + size * 0.58,
            stroke,
            width
        ),
        ArchimateIconKind::DistributionNetwork => format!(
            r#"<path data-dediren-icon-part="distribution-network-arrows" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} Z" fill="{}" stroke="{}" stroke-width="{}" stroke-linejoin="round"/>"#,
            x + size * 0.12,
            y + size * 0.36,
            x + size * 0.3,
            y + size * 0.22,
            x + size * 0.3,
            y + size * 0.3,
            x + size * 0.7,
            y + size * 0.3,
            x + size * 0.7,
            y + size * 0.22,
            x + size * 0.88,
            y + size * 0.36,
            x + size * 0.7,
            y + size * 0.5,
            x + size * 0.7,
            y + size * 0.42,
            x + size * 0.3,
            y + size * 0.42,
            x + size * 0.3,
            y + size * 0.5,
            fill,
            stroke,
            width
        ),
        ArchimateIconKind::Path => format!(
            r#"<path data-dediren-icon-part="path-line" d="M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-dasharray="3 2" stroke-linecap="round"/><path data-dediren-icon-part="path-arrowheads" d="M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linecap="round"/>"#,
            x + size * 0.08,
            y + size * 0.36,
            x + size * 0.92,
            y + size * 0.36,
            stroke,
            width,
            x + size * 0.25,
            y + size * 0.16,
            x + size * 0.08,
            y + size * 0.36,
            x + size * 0.75,
            y + size * 0.16,
            x + size * 0.92,
            y + size * 0.36,
            x + size * 0.25,
            y + size * 0.56,
            x + size * 0.08,
            y + size * 0.36,
            x + size * 0.75,
            y + size * 0.56,
            x + size * 0.92,
            y + size * 0.36,
            stroke,
            width
        ),
    };
    if matches!(kind, ArchimateIconKind::Node) {
        body
    } else {
        format!(
            r##"<g {}>{}</g>"##,
            archimate_decorator_group_attrs(decorator_name, kind),
            body
        )
    }
}

fn archimate_business_actor_decorator(node: &LaidOutNode, style: &ResolvedNodeStyle) -> String {
    let icon_box = archimate_icon_box(node);
    let body = archimate_actor_icon_body(
        icon_box.x,
        icon_box.y - 3.0,
        icon_box.size,
        &escape_attr(&style.fill),
        &escape_attr(&style.stroke),
        &svg_style_number(style.stroke_width),
    );
    format!(
        r##"<g {}>{}</g>"##,
        archimate_decorator_group_attrs("archimate_business_actor", ArchimateIconKind::Actor),
        body
    )
}

fn archimate_application_component_decorator(
    node: &LaidOutNode,
    style: &ResolvedNodeStyle,
) -> String {
    let icon_box = archimate_icon_box(node);
    let size = icon_box.size;
    let x = icon_box.x;
    let y = icon_box.y;
    let tab_x = x;
    let tab_width = size * 0.36;
    let tab_height = size * 0.26;
    let body_x = x + tab_width / 2.0;
    let body_width = size - tab_width / 2.0;
    format!(
        r##"<g {}><rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="1.5" fill="{}" stroke="{}" stroke-width="{}"/><rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="1.2" fill="{}" stroke="{}" stroke-width="{}"/><rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="1.2" fill="{}" stroke="{}" stroke-width="{}"/></g>"##,
        archimate_decorator_group_attrs(
            "archimate_application_component",
            ArchimateIconKind::Component
        ),
        body_x,
        y,
        body_width,
        size * 0.72,
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width),
        tab_x,
        y + size * 0.12,
        tab_width,
        tab_height,
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width),
        tab_x,
        y + size * 0.44,
        tab_width,
        tab_height,
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width)
    )
}

fn archimate_application_service_decorator(
    node: &LaidOutNode,
    style: &ResolvedNodeStyle,
) -> String {
    let icon_box = archimate_icon_box(node);
    let size = icon_box.size;
    let x = icon_box.x;
    let y = icon_box.y;
    format!(
        r##"<g {}><rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="{:.1}" fill="{}" stroke="{}" stroke-width="{}"/></g>"##,
        archimate_decorator_group_attrs(
            "archimate_application_service",
            ArchimateIconKind::Service
        ),
        x,
        y,
        size,
        size * 0.62,
        size * 0.18,
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width)
    )
}

fn archimate_data_object_decorator(node: &LaidOutNode, style: &ResolvedNodeStyle) -> String {
    let icon_box = archimate_icon_box(node);
    let size = icon_box.size;
    let x = icon_box.x;
    let y = icon_box.y;
    let fill = escape_attr(&style.fill);
    let stroke = escape_attr(&style.stroke);
    let width = svg_style_number(style.stroke_width);
    let body = archimate_document_icon_body(x, y, size, &fill, &stroke, &width, false);
    format!(
        r##"<g {}>{}</g>"##,
        archimate_decorator_group_attrs("archimate_data_object", ArchimateIconKind::Object),
        body
    )
}

fn archimate_technology_node_decorator(node: &LaidOutNode, style: &ResolvedNodeStyle) -> String {
    let icon_box = archimate_icon_box(node);
    let size = icon_box.size;
    let x = icon_box.x;
    let y = icon_box.y + size * 0.18;
    let depth = size * 0.18;
    format!(
        r##"<g {}><rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="1.5" fill="{}" stroke="{}" stroke-width="{}"/><path data-dediren-icon-part="node-3d-edges" d="M {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1} M {:.1} {:.1} L {:.1} {:.1}" fill="none" stroke="{}" stroke-width="{}" stroke-linejoin="round"/></g>"##,
        archimate_decorator_group_attrs("archimate_technology_node", ArchimateIconKind::Node),
        x,
        y,
        size - depth,
        size * 0.58,
        escape_attr(&style.fill),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width),
        x,
        y,
        x + depth,
        y - depth,
        x + size,
        y - depth,
        x + size - depth,
        y,
        x + size - depth,
        y,
        x + size,
        y - depth,
        x + size - depth,
        y + size * 0.58,
        x + size,
        y + size * 0.58 - depth,
        x + size,
        y - depth,
        x + size,
        y + size * 0.58 - depth,
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width)
    )
}

fn node_label(node: &LaidOutNode, style: &ResolvedNodeStyle, font_size: f64) -> String {
    let (lines, label_font_size) = node_label_lines_and_size(node, font_size);
    let x = node.x + node.width / 2.0;
    let y = node_label_first_line_y(node, label_font_size, lines.len());
    if lines.len() == 1 {
        return format!(
            r##"<text x="{:.1}" y="{:.1}" text-anchor="middle" dominant-baseline="middle" fill="{}" font-size="{}">{}</text>"##,
            x,
            y,
            escape_attr(&style.label_fill),
            svg_label_number(label_font_size),
            escape_text(&lines[0])
        );
    }
    let mut text = format!(
        r##"<text x="{:.1}" y="{:.1}" text-anchor="middle" dominant-baseline="middle" fill="{}" font-size="{}">"##,
        x,
        y,
        escape_attr(&style.label_fill),
        svg_label_number(label_font_size)
    );
    for (index, line) in lines.iter().enumerate() {
        let dy = if index == 0 {
            "0".to_string()
        } else {
            svg_label_number(node_label_line_height(label_font_size))
        };
        text.push_str(&format!(
            r##"<tspan x="{:.1}" dy="{}">{}</tspan>"##,
            x,
            dy,
            escape_text(line)
        ));
    }
    text.push_str("</text>");
    text
}

fn node_label_lines_and_size(node: &LaidOutNode, font_size: f64) -> (Vec<String>, f64) {
    let lines = wrapped_node_label_lines(node, font_size);
    let max_width = node_label_max_width(node, font_size);
    let widest_line = lines
        .iter()
        .map(|line| estimate_text_width(line, font_size))
        .fold(0.0, f64::max);
    let label_font_size = if widest_line > max_width {
        (font_size * max_width / widest_line).max(9.0)
    } else {
        font_size
    };
    (lines, label_font_size)
}

fn wrapped_node_label_lines(node: &LaidOutNode, font_size: f64) -> Vec<String> {
    let max_width = node_label_max_width(node, font_size);
    let tokens = label_wrap_tokens(&node.label);
    let mut lines = Vec::new();
    let mut current = String::new();
    for token in tokens {
        let candidate = if current.is_empty() {
            token.clone()
        } else {
            format!("{current} {token}")
        };
        if estimate_text_width(&candidate, font_size) <= max_width {
            current = candidate;
            continue;
        }
        if !current.is_empty() {
            lines.push(current);
        }
        current = token;
    }
    if !current.is_empty() {
        lines.push(current);
    }
    if lines.is_empty() {
        vec![node.label.clone()]
    } else {
        lines
    }
}

fn label_wrap_tokens(label: &str) -> Vec<String> {
    label
        .split_whitespace()
        .flat_map(split_camel_token)
        .collect()
}

fn split_camel_token(token: &str) -> Vec<String> {
    let mut parts = Vec::new();
    let mut current = String::new();
    let characters: Vec<char> = token.chars().collect();
    for (index, character) in characters.iter().enumerate() {
        let previous_is_lowercase = index > 0 && characters[index - 1].is_lowercase();
        let next_is_lowercase = characters
            .get(index + 1)
            .is_some_and(|next| next.is_lowercase());
        if previous_is_lowercase
            && character.is_uppercase()
            && next_is_lowercase
            && !current.is_empty()
        {
            parts.push(current);
            current = String::new();
        }
        current.push(*character);
    }
    if !current.is_empty() {
        parts.push(current);
    }
    parts
}

fn node_label_max_width(node: &LaidOutNode, font_size: f64) -> f64 {
    (node.width - 20.0).max(font_size * 3.0)
}

fn node_label_line_height(font_size: f64) -> f64 {
    font_size * 1.15
}

fn node_label_first_line_y(node: &LaidOutNode, font_size: f64, line_count: usize) -> f64 {
    node.y + node.height / 2.0
        - (line_count.saturating_sub(1) as f64 * node_label_line_height(font_size)) / 2.0
}

fn svg_label_number(value: f64) -> String {
    let floored = (value * 10.0).floor() / 10.0;
    format!("{floored:.1}")
}

fn edge_marker(edge: &LaidOutEdge, style: &ResolvedEdgeStyle) -> String {
    let mut markers = String::new();
    if style.marker_start != SvgEdgeMarkerEnd::None {
        markers.push_str(&edge_marker_def(
            &edge_marker_start_id(&edge.id),
            "start",
            style.marker_start,
            &style.stroke,
        ));
    }
    if style.marker_end != SvgEdgeMarkerEnd::None {
        markers.push_str(&edge_marker_def(
            &edge_marker_end_id(&edge.id),
            "end",
            style.marker_end,
            &style.stroke,
        ));
    }
    if markers.is_empty() {
        String::new()
    } else {
        format!("<defs>{markers}</defs>")
    }
}

fn edge_marker_def(id: &str, position: &str, marker: SvgEdgeMarkerEnd, stroke: &str) -> String {
    let data_attr = format!("data-dediren-edge-marker-{position}");
    match marker {
        SvgEdgeMarkerEnd::FilledArrow => format!(
            r##"<marker id="{}" {}="filled_arrow" markerWidth="8" markerHeight="8" refX="8" refY="4" orient="auto" markerUnits="strokeWidth"><path d="M 0 0 L 8 4 L 0 8 z" fill="{}"/></marker>"##,
            escape_attr(id),
            data_attr,
            escape_attr(stroke)
        ),
        SvgEdgeMarkerEnd::OpenArrow => format!(
            r##"<marker id="{}" {}="open_arrow" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto" markerUnits="strokeWidth"><path d="M 1 1 L 8 4.5 L 1 8" fill="none" stroke="{}" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></marker>"##,
            escape_attr(id),
            data_attr,
            escape_attr(stroke)
        ),
        SvgEdgeMarkerEnd::HollowTriangle => format!(
            r##"<marker id="{}" {}="hollow_triangle" markerWidth="10" markerHeight="10" refX="9" refY="5" orient="auto" markerUnits="strokeWidth"><path d="M 1 1 L 9 5 L 1 9 z" fill="#ffffff" stroke="{}" stroke-width="1.2"/></marker>"##,
            escape_attr(id),
            data_attr,
            escape_attr(stroke)
        ),
        SvgEdgeMarkerEnd::FilledDiamond => format!(
            r##"<marker id="{}" {}="filled_diamond" markerWidth="10" markerHeight="10" refX="1" refY="5" orient="auto" markerUnits="strokeWidth"><path d="M 1 5 L 5 1 L 9 5 L 5 9 z" fill="{}" stroke="{}" stroke-width="1.1"/></marker>"##,
            escape_attr(id),
            data_attr,
            escape_attr(stroke),
            escape_attr(stroke)
        ),
        SvgEdgeMarkerEnd::HollowDiamond => format!(
            r##"<marker id="{}" {}="hollow_diamond" markerWidth="10" markerHeight="10" refX="1" refY="5" orient="auto" markerUnits="strokeWidth"><path d="M 1 5 L 5 1 L 9 5 L 5 9 z" fill="#ffffff" stroke="{}" stroke-width="1.1"/></marker>"##,
            escape_attr(id),
            data_attr,
            escape_attr(stroke)
        ),
        SvgEdgeMarkerEnd::FilledCircle => format!(
            r##"<marker id="{}" {}="filled_circle" markerWidth="8" markerHeight="8" refX="1" refY="4" orient="auto" markerUnits="strokeWidth"><circle cx="4" cy="4" r="3" fill="{}" stroke="{}" stroke-width="1"/></marker>"##,
            escape_attr(id),
            data_attr,
            escape_attr(stroke),
            escape_attr(stroke)
        ),
        SvgEdgeMarkerEnd::HollowCircle => format!(
            r##"<marker id="{}" {}="hollow_circle" markerWidth="8" markerHeight="8" refX="1" refY="4" orient="auto" markerUnits="strokeWidth"><circle cx="4" cy="4" r="3" fill="#ffffff" stroke="{}" stroke-width="1"/></marker>"##,
            escape_attr(id),
            data_attr,
            escape_attr(stroke)
        ),
        SvgEdgeMarkerEnd::None => String::new(),
    }
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
    let data = edge_path_data(edge, earlier_edges);
    let dash_attr = match style.line_style {
        SvgEdgeLineStyle::Solid => String::new(),
        SvgEdgeLineStyle::Dashed => r#" stroke-dasharray="8 5""#.to_string(),
    };
    let marker_attr = match style.marker_end {
        SvgEdgeMarkerEnd::None => String::new(),
        _ => format!(
            r#" marker-end="url(#{})""#,
            escape_attr(&edge_marker_end_id(&edge.id))
        ),
    };
    let marker_start_attr = match style.marker_start {
        SvgEdgeMarkerEnd::None => String::new(),
        _ => format!(
            r#" marker-start="url(#{})""#,
            escape_attr(&edge_marker_start_id(&edge.id))
        ),
    };
    let jump_masks = line_jump_mask_group(edge, style, earlier_edges);
    format!(
        r##"{}<path d="{}" fill="none" stroke="{}" stroke-width="{}"{}{}{}/>"##,
        jump_masks,
        escape_attr(&data),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width),
        marker_start_attr,
        dash_attr,
        marker_attr
    )
}

fn line_jump_mask_group(
    edge: &LaidOutEdge,
    style: &ResolvedEdgeStyle,
    earlier_edges: &[&LaidOutEdge],
) -> String {
    let mask_paths = line_jump_mask_path_data(edge, earlier_edges);
    if mask_paths.is_empty() {
        return String::new();
    }
    let stroke_width = svg_style_number(style.stroke_width + 4.0);
    let mut content = format!(
        r##"<g data-dediren-line-jump-masks="{}">"##,
        escape_attr(&edge.id)
    );
    for mask_path in mask_paths {
        content.push_str(&format!(
            r##"<path d="{}" fill="none" stroke="#ffffff" stroke-width="{}" stroke-linecap="round" stroke-linejoin="round"/>"##,
            escape_attr(&mask_path),
            stroke_width
        ));
    }
    content.push_str("</g>");
    content
}

fn line_jump_mask_path_data(edge: &LaidOutEdge, earlier_edges: &[&LaidOutEdge]) -> Vec<String> {
    let mut paths = Vec::new();
    for segment in edge.points.windows(2) {
        let start = &segment[0];
        let end = &segment[1];
        for jump in line_jump_points(edge, start, end, earlier_edges) {
            if let Some(data) = line_jump_arc_path_data(start, end, &jump) {
                paths.push(data);
            }
        }
    }
    paths
}

fn line_jump_arc_path_data(start: &Point, end: &Point, jump: &LineJump) -> Option<String> {
    if start.y == end.y {
        let direction = (end.x - start.x).signum();
        return Some(format!(
            "M {:.1} {:.1} Q {:.1} {:.1} {:.1} {:.1}",
            jump.point.x - direction * LINE_JUMP_SIZE,
            jump.point.y,
            jump.point.x,
            jump.point.y - LINE_JUMP_SIZE,
            jump.point.x + direction * LINE_JUMP_SIZE,
            jump.point.y
        ));
    }
    if start.x == end.x {
        let direction = (end.y - start.y).signum();
        return Some(format!(
            "M {:.1} {:.1} Q {:.1} {:.1} {:.1} {:.1}",
            jump.point.x,
            jump.point.y - direction * LINE_JUMP_SIZE,
            jump.point.x + LINE_JUMP_SIZE,
            jump.point.y,
            jump.point.x,
            jump.point.y + direction * LINE_JUMP_SIZE
        ));
    }
    None
}

fn edge_path_data(edge: &LaidOutEdge, earlier_edges: &[&LaidOutEdge]) -> String {
    let points = &edge.points;
    let detours: Vec<Option<LineDetour>> = points
        .windows(2)
        .map(|segment| {
            colinear_overlap_detours(edge, &segment[0], &segment[1], earlier_edges)
                .into_iter()
                .next()
        })
        .collect();
    let mut data = format!("M {:.1} {:.1}", points[0].x, points[0].y);
    let mut current_point = points[0].clone();
    for (index, segment) in points.windows(2).enumerate() {
        let start = &segment[0];
        let end = &segment[1];
        if let Some(detour) = &detours[index] {
            let previous = index
                .checked_sub(1)
                .and_then(|previous| points.get(previous));
            let next = points.get(index + 2);
            let detour_exit = append_colinear_overlap_detour(
                &mut data,
                &current_point,
                start,
                end,
                previous,
                next,
                detour,
            );
            current_point = detour_exit.point;
            if !detour_exit.continues_after_segment {
                append_line_to(&mut data, &current_point, end);
                current_point = end.clone();
            }
            continue;
        }

        let mut segment_end = end.clone();
        if let Some(Some(next_detour)) = detours.get(index + 1) {
            if same_point(&next_detour.entry, end) {
                segment_end = shifted_toward(&next_detour.entry, &current_point, LINE_JUMP_SIZE);
            }
        }

        let jumps = line_jump_points(edge, &current_point, &segment_end, earlier_edges);
        if jumps.is_empty() {
            append_line_to(&mut data, &current_point, &segment_end);
            current_point = segment_end;
            continue;
        }
        for jump in jumps {
            append_line_jump(&mut data, &current_point, &segment_end, &jump);
        }
        append_line_to(&mut data, &current_point, &segment_end);
        current_point = segment_end;
    }
    data
}

#[derive(Debug)]
struct LineJump {
    point: Point,
    distance: f64,
}

#[derive(Debug)]
struct LineDetour {
    entry: Point,
    exit: Point,
    offset_entry: Point,
    offset_exit: Point,
    distance: f64,
}

#[derive(Debug)]
struct DetourExit {
    point: Point,
    continues_after_segment: bool,
}

fn colinear_overlap_detours(
    current_edge: &LaidOutEdge,
    start: &Point,
    end: &Point,
    earlier_edges: &[&LaidOutEdge],
) -> Vec<LineDetour> {
    let mut detours = Vec::new();
    for earlier_edge in earlier_edges {
        for earlier_segment in earlier_edge.points.windows(2) {
            if let Some(detour) =
                colinear_overlap_detour(start, end, &earlier_segment[0], &earlier_segment[1])
            {
                if shared_junction_hint_matches(current_edge, earlier_edge) {
                    continue;
                }
                detours.push(detour);
            }
        }
    }
    detours.sort_by(|left, right| left.distance.total_cmp(&right.distance));
    detours
}

fn shared_junction_hint_matches(current_edge: &LaidOutEdge, earlier_edge: &LaidOutEdge) -> bool {
    (has_routing_hint(current_edge, SHARED_SOURCE_JUNCTION_HINT)
        && (current_edge.source == earlier_edge.source
            || same_route_start(current_edge, earlier_edge)))
        || (has_routing_hint(current_edge, SHARED_TARGET_JUNCTION_HINT)
            && (current_edge.target == earlier_edge.target
                || same_route_end(current_edge, earlier_edge)))
}

fn has_routing_hint(edge: &LaidOutEdge, hint: &str) -> bool {
    edge.routing_hints.iter().any(|candidate| candidate == hint)
}

fn same_route_start(left: &LaidOutEdge, right: &LaidOutEdge) -> bool {
    left.points
        .first()
        .zip(right.points.first())
        .is_some_and(|(left, right)| same_point(left, right))
}

fn same_route_end(left: &LaidOutEdge, right: &LaidOutEdge) -> bool {
    left.points
        .last()
        .zip(right.points.last())
        .is_some_and(|(left, right)| same_point(left, right))
}

fn line_jump_points(
    current_edge: &LaidOutEdge,
    start: &Point,
    end: &Point,
    earlier_edges: &[&LaidOutEdge],
) -> Vec<LineJump> {
    let mut jumps = Vec::new();
    for earlier_edge in earlier_edges {
        if shared_junction_hint_matches(current_edge, earlier_edge) {
            continue;
        }
        for earlier_segment in earlier_edge.points.windows(2) {
            if let Some(point) =
                route_jump_point(start, end, &earlier_segment[0], &earlier_segment[1])
            {
                let distance = segment_length(start, &point);
                if distance >= LINE_JUMP_SIZE && segment_length(&point, end) >= LINE_JUMP_SIZE {
                    jumps.push(LineJump { point, distance });
                }
            }
        }
    }
    jumps.sort_by(|left, right| left.distance.total_cmp(&right.distance));
    jumps
}

const LINE_JUMP_SIZE: f64 = 6.0;
const SHARED_SOURCE_JUNCTION_HINT: &str = "shared_source_junction";
const SHARED_TARGET_JUNCTION_HINT: &str = "shared_target_junction";

fn route_jump_point(
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

fn colinear_overlap_detour(
    start: &Point,
    end: &Point,
    other_start: &Point,
    other_end: &Point,
) -> Option<LineDetour> {
    if start.y == end.y && other_start.y == other_end.y && start.y == other_start.y {
        let overlap_start = start.x.min(end.x).max(other_start.x.min(other_end.x));
        let overlap_end = start.x.max(end.x).min(other_start.x.max(other_end.x));
        if overlap_end - overlap_start >= LINE_JUMP_SIZE * 2.0 {
            let direction = (end.x - start.x).signum();
            let entry_x = if direction >= 0.0 {
                overlap_start
            } else {
                overlap_end
            };
            let exit_x = if direction >= 0.0 {
                overlap_end
            } else {
                overlap_start
            };
            let entry = Point {
                x: entry_x,
                y: start.y,
            };
            let exit = Point {
                x: exit_x,
                y: start.y,
            };
            return Some(LineDetour {
                entry: entry.clone(),
                exit: exit.clone(),
                offset_entry: Point {
                    x: entry.x,
                    y: entry.y - LINE_JUMP_SIZE,
                },
                offset_exit: Point {
                    x: exit.x,
                    y: exit.y - LINE_JUMP_SIZE,
                },
                distance: segment_length(start, &entry),
            });
        }
    }
    if start.x == end.x && other_start.x == other_end.x && start.x == other_start.x {
        let overlap_start = start.y.min(end.y).max(other_start.y.min(other_end.y));
        let overlap_end = start.y.max(end.y).min(other_start.y.max(other_end.y));
        if overlap_end - overlap_start >= LINE_JUMP_SIZE * 2.0 {
            let direction = (end.y - start.y).signum();
            let entry_y = if direction >= 0.0 {
                overlap_start
            } else {
                overlap_end
            };
            let exit_y = if direction >= 0.0 {
                overlap_end
            } else {
                overlap_start
            };
            let entry = Point {
                x: start.x,
                y: entry_y,
            };
            let exit = Point {
                x: start.x,
                y: exit_y,
            };
            return Some(LineDetour {
                entry: entry.clone(),
                exit: exit.clone(),
                offset_entry: Point {
                    x: entry.x + LINE_JUMP_SIZE,
                    y: entry.y,
                },
                offset_exit: Point {
                    x: exit.x + LINE_JUMP_SIZE,
                    y: exit.y,
                },
                distance: segment_length(start, &entry),
            });
        }
    }
    None
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

fn append_colinear_overlap_detour(
    data: &mut String,
    current_point: &Point,
    start: &Point,
    end: &Point,
    previous: Option<&Point>,
    next: Option<&Point>,
    detour: &LineDetour,
) -> DetourExit {
    let approach = detour_entry_approach(current_point, start, previous, detour);
    let detour_exit = detour_exit_departure(end, next, detour);
    append_line_to(data, current_point, &approach);
    if start.y == end.y {
        let exit_control = Point {
            x: detour_exit.point.x,
            y: detour.offset_exit.y,
        };
        data.push_str(&format!(
            " Q {:.1} {:.1} {:.1} {:.1} L {:.1} {:.1} Q {:.1} {:.1} {:.1} {:.1}",
            detour.entry.x,
            detour.entry.y - LINE_JUMP_SIZE,
            detour.offset_entry.x,
            detour.offset_entry.y,
            detour.offset_exit.x,
            detour.offset_exit.y,
            exit_control.x,
            exit_control.y,
            detour_exit.point.x,
            detour_exit.point.y
        ));
    } else if start.x == end.x {
        let entry_control = if approach.y == detour.entry.y && approach.x != detour.entry.x {
            Point {
                x: detour.entry.x,
                y: detour.entry.y - LINE_JUMP_SIZE,
            }
        } else {
            Point {
                x: detour.entry.x + LINE_JUMP_SIZE,
                y: detour.entry.y,
            }
        };
        let exit_control = Point {
            x: detour.offset_exit.x,
            y: detour_exit.point.y,
        };
        data.push_str(&format!(
            " Q {:.1} {:.1} {:.1} {:.1} L {:.1} {:.1} Q {:.1} {:.1} {:.1} {:.1}",
            entry_control.x,
            entry_control.y,
            detour.offset_entry.x,
            detour.offset_entry.y,
            detour.offset_exit.x,
            detour.offset_exit.y,
            exit_control.x,
            exit_control.y,
            detour_exit.point.x,
            detour_exit.point.y
        ));
    }
    detour_exit
}

fn append_line_to(data: &mut String, current: &Point, target: &Point) {
    if !same_point(current, target) {
        data.push_str(&format!(" L {:.1} {:.1}", target.x, target.y));
    }
}

fn same_point(left: &Point, right: &Point) -> bool {
    left.x == right.x && left.y == right.y
}

fn shifted_toward(from: &Point, to: &Point, distance: f64) -> Point {
    let length = segment_length(from, to);
    if length == 0.0 {
        return from.clone();
    }
    let ratio = distance.min(length) / length;
    Point {
        x: from.x + (to.x - from.x) * ratio,
        y: from.y + (to.y - from.y) * ratio,
    }
}

fn detour_entry_approach(
    current_point: &Point,
    start: &Point,
    previous: Option<&Point>,
    detour: &LineDetour,
) -> Point {
    if !same_point(current_point, start) {
        return current_point.clone();
    }
    if same_point(&detour.entry, start) {
        return previous
            .map(|point| shifted_toward(&detour.entry, point, LINE_JUMP_SIZE))
            .unwrap_or_else(|| detour.entry.clone());
    }
    shifted_toward(&detour.entry, start, LINE_JUMP_SIZE)
}

fn detour_exit_departure(end: &Point, next: Option<&Point>, detour: &LineDetour) -> DetourExit {
    if same_point(&detour.exit, end) {
        if let Some(next) = next {
            return DetourExit {
                point: shifted_toward(&detour.exit, next, LINE_JUMP_SIZE),
                continues_after_segment: true,
            };
        }
        return DetourExit {
            point: detour.exit.clone(),
            continues_after_segment: false,
        };
    }
    DetourExit {
        point: shifted_toward(&detour.exit, end, LINE_JUMP_SIZE),
        continues_after_segment: false,
    }
}

fn edge_marker_end_id(edge_id: &str) -> String {
    format!("marker-end-{edge_id}")
}

fn edge_marker_start_id(edge_id: &str) -> String {
    format!("marker-start-{edge_id}")
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

    fn overlap_area(&self, other: &LabelBox) -> f64 {
        let width = (self.max_x.min(other.max_x) - self.min_x.max(other.min_x)).max(0.0);
        let height = (self.max_y.min(other.max_y) - self.min_y.max(other.min_y)).max(0.0);
        width * height
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

fn route_obstacle_boxes_except(
    result: &LayoutResult,
    font_size: f64,
    ignored_edge_id: &str,
) -> Vec<LabelBox> {
    let padding = font_size * 0.15;
    result
        .edges
        .iter()
        .filter(|edge| edge.id != ignored_edge_id)
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

fn edge_route_obstacle_boxes(
    result: &LayoutResult,
    font_size: f64,
    edge: &LaidOutEdge,
) -> Vec<LabelBox> {
    if has_horizontal_segment(&edge.points) {
        route_obstacle_boxes_except(result, font_size, &edge.id)
    } else {
        route_obstacle_boxes_except(result, font_size, "")
    }
}

fn has_horizontal_segment(points: &[Point]) -> bool {
    points
        .windows(2)
        .any(|segment| segment[0].y == segment[1].y && segment[0].x != segment[1].x)
}

fn edge_label(
    edge: &LaidOutEdge,
    style: &ResolvedEdgeStyle,
    background_fill: &str,
    font_size: f64,
    occupied_boxes: &mut Vec<LabelBox>,
) -> String {
    if let Some(label) = edge_label_position_for_edge(edge, font_size, style, occupied_boxes) {
        occupied_boxes.push(label.bounds);
        format!(
            r##"<text x="{:.1}" y="{:.1}" text-anchor="{}" fill="{}" stroke="{}" stroke-width="4" stroke-linejoin="round" paint-order="stroke">{}</text>"##,
            label.x,
            label.y,
            label.text_anchor,
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
    style: &ResolvedEdgeStyle,
    occupied_boxes: &[LabelBox],
) -> Option<LabelPlacement> {
    edge_label_anchors(edge, style)
        .into_iter()
        .map(|anchor| {
            let (label_x, label_y, label_box) = edge_label_position(
                anchor.point.x,
                edge_label_base_y(anchor.point.y, font_size, &anchor),
                &edge.label,
                font_size,
                &anchor,
                occupied_boxes,
            );
            let score = label_position_score(&anchor, label_x, label_y);
            let placement = label_placement(&anchor, label_x, label_y, label_box);
            (score, placement)
        })
        .min_by(|left, right| left.0.total_cmp(&right.0))
        .map(|(_, placement)| placement)
}

fn edge_label_position(
    x: f64,
    base_y: f64,
    text: &str,
    font_size: f64,
    anchor: &LabelAnchor,
    occupied_boxes: &[LabelBox],
) -> (f64, f64, LabelBox) {
    let step = font_size + 4.0;
    if matches!(anchor.orientation, LabelAnchorOrientation::Horizontal) {
        return best_horizontal_edge_label_position(
            x,
            base_y,
            text,
            font_size,
            anchor,
            occupied_boxes,
        );
    }

    for attempt in 0..12 {
        let (label_x, label_y) = label_candidate(attempt, x, base_y, step, text, font_size, anchor);
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

fn best_horizontal_edge_label_position(
    x: f64,
    base_y: f64,
    text: &str,
    font_size: f64,
    anchor: &LabelAnchor,
    occupied_boxes: &[LabelBox],
) -> (f64, f64, LabelBox) {
    let step = font_size + 4.0;
    let mut best: Option<(f64, f64, f64, LabelBox)> = None;
    for attempt in 0..12 {
        let (label_x, label_y) = label_candidate(attempt, x, base_y, step, text, font_size, anchor);
        let label_box = text_box(label_x, label_y, text, font_size);
        let overlap_area = occupied_boxes
            .iter()
            .map(|occupied| label_box.overlap_area(occupied))
            .sum::<f64>();
        let distance_score = (label_y - base_y).abs() + (label_x - anchor.point.x).abs() * 0.25;
        let score = distance_score + overlap_area * 0.5;
        if best
            .as_ref()
            .is_none_or(|(best_score, _, _, _)| score < *best_score)
        {
            best = Some((score, label_x, label_y, label_box));
        }
    }
    best.map(|(_, label_x, label_y, label_box)| (label_x, label_y, label_box))
        .unwrap_or_else(|| {
            let label_box = text_box(x, base_y, text, font_size);
            (x, base_y, label_box)
        })
}

fn edge_label_base_y(route_y: f64, font_size: f64, anchor: &LabelAnchor) -> f64 {
    match anchor.orientation {
        LabelAnchorOrientation::Horizontal => match anchor.horizontal_side {
            Some(SvgEdgeLabelHorizontalSide::Auto) => route_y - font_size * 0.6,
            Some(SvgEdgeLabelHorizontalSide::Above) => route_y - font_size * 0.6,
            Some(SvgEdgeLabelHorizontalSide::Below) => route_y + font_size * 1.2,
            None => route_y - font_size * 0.6,
        },
        LabelAnchorOrientation::Vertical => route_y + font_size * 0.3,
    }
}

fn label_position_score(anchor: &LabelAnchor, label_x: f64, label_y: f64) -> f64 {
    anchor.route_order as f64 * 1000.0
        + (label_y - anchor.point.y).abs()
        + (label_x - anchor.point.x).abs() * 0.25
}

fn label_placement(
    anchor: &LabelAnchor,
    label_x: f64,
    label_y: f64,
    label_box: LabelBox,
) -> LabelPlacement {
    match anchor.orientation {
        LabelAnchorOrientation::Horizontal => LabelPlacement {
            x: label_x,
            y: label_y,
            text_anchor: "middle",
            bounds: label_box,
        },
        LabelAnchorOrientation::Vertical if label_box.max_x <= anchor.point.x => LabelPlacement {
            x: label_box.max_x,
            y: label_y,
            text_anchor: "end",
            bounds: label_box,
        },
        LabelAnchorOrientation::Vertical => LabelPlacement {
            x: label_box.min_x,
            y: label_y,
            text_anchor: "start",
            bounds: label_box,
        },
    }
}

fn label_candidate(
    attempt: usize,
    x: f64,
    base_y: f64,
    step: f64,
    text: &str,
    font_size: f64,
    anchor: &LabelAnchor,
) -> (f64, f64) {
    let route_gap = match anchor.orientation {
        LabelAnchorOrientation::Horizontal => step,
        LabelAnchorOrientation::Vertical => font_size * 0.15,
    };
    let side_offset = estimate_text_width(text, font_size) / 2.0 + route_gap;
    match (anchor.orientation, anchor.horizontal_side) {
        (LabelAnchorOrientation::Horizontal, Some(SvgEdgeLabelHorizontalSide::Below)) => {
            horizontal_label_candidate_order(
                attempt,
                x,
                base_y,
                step,
                side_offset,
                -1.0,
                anchor.horizontal_direction,
                anchor.horizontal_center_x,
            )
        }
        (LabelAnchorOrientation::Horizontal, _) => horizontal_label_candidate_order(
            attempt,
            x,
            base_y,
            step,
            side_offset,
            1.0,
            anchor.horizontal_direction,
            anchor.horizontal_center_x,
        ),
        (LabelAnchorOrientation::Vertical, _) => {
            let prefer_left = anchor.vertical_side != Some(SvgEdgeLabelVerticalSide::Right);
            label_candidate_order(attempt, x, base_y, step, side_offset, 1.0, prefer_left)
        }
    }
}

fn horizontal_label_candidate_order(
    attempt: usize,
    x: f64,
    base_y: f64,
    step: f64,
    side_offset: f64,
    fallback_direction: f64,
    horizontal_direction: f64,
    center_x: Option<f64>,
) -> (f64, f64) {
    match attempt {
        0 => (x, base_y),
        1 => (center_x.unwrap_or(x), base_y),
        2 => (x + horizontal_direction * side_offset, base_y),
        3 => (x, base_y - fallback_direction * step),
        4 => (x, base_y + fallback_direction * step),
        5 => (x - horizontal_direction * side_offset, base_y),
        _ => {
            let level = ((attempt - 6) / 3 + 2) as f64;
            let y = base_y + fallback_direction * level * step;
            match (attempt - 6) % 3 {
                0 => (x, y),
                1 => (x + horizontal_direction * side_offset, y),
                _ => (x - horizontal_direction * side_offset, y),
            }
        }
    }
}

fn label_candidate_order(
    attempt: usize,
    x: f64,
    base_y: f64,
    step: f64,
    side_offset: f64,
    fallback_direction: f64,
    prefer_left: bool,
) -> (f64, f64) {
    match attempt {
        0 => (x, base_y),
        1 => (x, base_y - fallback_direction * step),
        2 => (x, base_y + fallback_direction * step),
        3 if prefer_left => (x - side_offset, base_y),
        3 => (x + side_offset, base_y),
        4 if prefer_left => (x + side_offset, base_y),
        4 => (x - side_offset, base_y),
        _ => {
            let level = ((attempt - 5) / 3 + 2) as f64;
            let y = base_y + fallback_direction * level * step;
            match (attempt - 5) % 3 {
                0 => (x, y),
                1 if prefer_left => (x - side_offset, y),
                1 => (x + side_offset, y),
                _ if prefer_left => (x + side_offset, y),
                _ => (x - side_offset, y),
            }
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

#[derive(Clone)]
struct LabelAnchor {
    point: Point,
    orientation: LabelAnchorOrientation,
    horizontal_side: Option<SvgEdgeLabelHorizontalSide>,
    horizontal_direction: f64,
    horizontal_center_x: Option<f64>,
    vertical_side: Option<SvgEdgeLabelVerticalSide>,
    route_order: usize,
}

struct LabelPlacement {
    x: f64,
    y: f64,
    text_anchor: &'static str,
    bounds: LabelBox,
}

#[derive(Clone, Copy)]
enum LabelAnchorOrientation {
    Horizontal,
    Vertical,
}

fn edge_label_anchors(edge: &LaidOutEdge, style: &ResolvedEdgeStyle) -> Vec<LabelAnchor> {
    let mut anchors = horizontal_segment_anchors(edge, style);
    if anchors.is_empty() {
        if let Some(point) =
            route_point_by_vertical_position(&edge.points, style.label_vertical_position)
        {
            anchors.push(LabelAnchor {
                point,
                orientation: LabelAnchorOrientation::Vertical,
                horizontal_side: None,
                horizontal_direction: 1.0,
                horizontal_center_x: None,
                vertical_side: Some(style.label_vertical_side),
                route_order: 0,
            });
        }
    }
    anchors
}

fn horizontal_segment_anchors(edge: &LaidOutEdge, style: &ResolvedEdgeStyle) -> Vec<LabelAnchor> {
    let points = &edge.points;
    let mut segments: Vec<(usize, &Point, &Point, f64)> = Vec::new();
    for (index, segment) in points.windows(2).enumerate() {
        let start = &segment[0];
        let end = &segment[1];
        if start.y != end.y || start.x == end.x {
            continue;
        }
        let length = (end.x - start.x).abs();
        segments.push((index, start, end, length));
    }

    let segment_count = segments.len();
    let prefer_target_branch = has_routing_hint(edge, SHARED_SOURCE_JUNCTION_HINT)
        && !has_routing_hint(edge, SHARED_TARGET_JUNCTION_HINT);
    segments
        .into_iter()
        .enumerate()
        .map(|(route_order, (index, start, end, length))| {
            let route_order = if prefer_target_branch {
                segment_count - route_order - 1
            } else {
                route_order
            };
            LabelAnchor {
                point: horizontal_label_point(start, end, length, style.label_horizontal_position),
                orientation: LabelAnchorOrientation::Horizontal,
                horizontal_side: Some(horizontal_side_for_segment(
                    points,
                    index,
                    style.label_horizontal_side,
                )),
                horizontal_direction: if end.x >= start.x { 1.0 } else { -1.0 },
                horizontal_center_x: Some(start.x + (end.x - start.x) / 2.0),
                vertical_side: None,
                route_order,
            }
        })
        .collect()
}

fn horizontal_side_for_segment(
    points: &[Point],
    segment_index: usize,
    configured: SvgEdgeLabelHorizontalSide,
) -> SvgEdgeLabelHorizontalSide {
    if configured != SvgEdgeLabelHorizontalSide::Auto {
        return configured;
    }

    if let Some(side) = vertical_bend_side(points, segment_index + 1) {
        return side;
    }
    if segment_index > 0 {
        if let Some(side) = vertical_bend_side(points, segment_index - 1) {
            return side;
        }
    }
    SvgEdgeLabelHorizontalSide::Above
}

fn vertical_bend_side(
    points: &[Point],
    segment_index: usize,
) -> Option<SvgEdgeLabelHorizontalSide> {
    let start = points.get(segment_index)?;
    let end = points.get(segment_index + 1)?;
    if start.x != end.x || start.y == end.y {
        return None;
    }
    if end.y > start.y {
        Some(SvgEdgeLabelHorizontalSide::Below)
    } else {
        Some(SvgEdgeLabelHorizontalSide::Above)
    }
}

fn horizontal_label_point(
    start: &Point,
    end: &Point,
    length: f64,
    position: SvgEdgeLabelHorizontalPosition,
) -> Point {
    let direction = if end.x >= start.x { 1.0 } else { -1.0 };
    let offset = 18.0_f64.min(length / 2.0);
    let x = match position {
        SvgEdgeLabelHorizontalPosition::NearStart => start.x + direction * offset,
        SvgEdgeLabelHorizontalPosition::Center => start.x + (end.x - start.x) / 2.0,
        SvgEdgeLabelHorizontalPosition::NearEnd => end.x - direction * offset,
    };
    Point { x, y: start.y }
}

fn route_point_by_vertical_position(
    points: &[Point],
    position: SvgEdgeLabelVerticalPosition,
) -> Option<Point> {
    match position {
        SvgEdgeLabelVerticalPosition::Center => route_midpoint(points),
        SvgEdgeLabelVerticalPosition::NearStart => points.first().cloned(),
        SvgEdgeLabelVerticalPosition::NearEnd => points.last().cloned(),
    }
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

fn exit_with_archimate_type_error(error: ArchimateTypeValidationError) -> ! {
    exit_with_diagnostic(error.code(), &error.message(), Some(error.path));
}
