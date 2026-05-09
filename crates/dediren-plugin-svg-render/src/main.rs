use std::io::Read;

use dediren_contracts::{
    CommandEnvelope, LaidOutEdge, LaidOutGroup, LaidOutNode, LayoutResult, Point, RenderPolicy,
    RenderResult, SvgEdgeStyle, SvgGroupStyle, SvgNodeStyle, RENDER_RESULT_SCHEMA_VERSION,
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
    let result = RenderResult {
        render_result_schema_version: RENDER_RESULT_SCHEMA_VERSION.to_string(),
        artifact_kind: "svg".to_string(),
        content: render_svg(&render_input.layout_result, &render_input.policy),
    };
    println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
    Ok(())
}

fn render_svg(result: &LayoutResult, policy: &RenderPolicy) -> String {
    let style = base_style(policy);
    let mut svg = String::new();
    svg.push_str(&format!(
        r#"<svg xmlns="http://www.w3.org/2000/svg" width="{:.0}" height="{:.0}" viewBox="0 0 {:.0} {:.0}">"#,
        policy.page.width, policy.page.height, policy.page.width, policy.page.height
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

    for edge in &result.edges {
        let edge_style = edge_style(policy, &edge.id, &style);
        svg.push_str(&format!(
            r#"<g data-dediren-edge-id="{}">"#,
            escape_attr(&edge.id)
        ));
        svg.push_str(&edge_path(&edge.points, &edge_style));
        svg.push_str(&edge_label(edge, &edge_style));
        svg.push_str("</g>");
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

fn edge_path(points: &[Point], style: &ResolvedEdgeStyle) -> String {
    if points.len() < 2 {
        return String::new();
    }
    let mut data = format!("M {:.1} {:.1}", points[0].x, points[0].y);
    for point in points.iter().skip(1) {
        data.push_str(&format!(" L {:.1} {:.1}", point.x, point.y));
    }
    format!(
        r##"<path d="{}" fill="none" stroke="{}" stroke-width="{}"/>"##,
        escape_attr(&data),
        escape_attr(&style.stroke),
        svg_style_number(style.stroke_width)
    )
}

fn edge_label(edge: &LaidOutEdge, style: &ResolvedEdgeStyle) -> String {
    if let Some(point) = edge.points.first() {
        format!(
            r##"<text x="{:.1}" y="{:.1}" fill="{}">{}</text>"##,
            point.x,
            point.y - 8.0,
            escape_attr(&style.label_fill),
            escape_text(&edge.label)
        )
    } else {
        String::new()
    }
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
