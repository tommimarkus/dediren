use std::io::Read;

use dediren_contracts::{
    CommandEnvelope, LayoutResult, Point, RenderPolicy, RenderResult, RENDER_RESULT_SCHEMA_VERSION,
};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
struct RenderInput {
    layout_result: LayoutResult,
    policy: RenderPolicy,
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
    let mut svg = String::new();
    svg.push_str(&format!(
        r#"<svg xmlns="http://www.w3.org/2000/svg" width="{:.0}" height="{:.0}" viewBox="0 0 {:.0} {:.0}">"#,
        policy.page.width, policy.page.height, policy.page.width, policy.page.height
    ));
    svg.push_str(r##"<rect width="100%" height="100%" fill="#ffffff"/>"##);
    svg.push_str(r##"<g font-family="Inter, Arial, sans-serif" font-size="14">"##);

    for edge in &result.edges {
        svg.push_str(&edge_path(&edge.points));
        if let Some(point) = edge.points.first() {
            svg.push_str(&format!(
                r##"<text x="{:.1}" y="{:.1}" fill="#374151">{}</text>"##,
                point.x,
                point.y - 8.0,
                escape(&edge.label)
            ));
        }
    }

    for node in &result.nodes {
        svg.push_str(&format!(
            r##"<rect x="{:.1}" y="{:.1}" width="{:.1}" height="{:.1}" rx="6" fill="#f8fafc" stroke="#334155" stroke-width="1.5"/>"##,
            node.x, node.y, node.width, node.height
        ));
        svg.push_str(&format!(
            r##"<text x="{:.1}" y="{:.1}" text-anchor="middle" dominant-baseline="middle" fill="#0f172a">{}</text>"##,
            node.x + node.width / 2.0,
            node.y + node.height / 2.0,
            escape(&node.label)
        ));
    }

    svg.push_str("</g></svg>\n");
    svg
}

fn edge_path(points: &[Point]) -> String {
    if points.len() < 2 {
        return String::new();
    }
    let mut data = format!("M {:.1} {:.1}", points[0].x, points[0].y);
    for point in points.iter().skip(1) {
        data.push_str(&format!(" L {:.1} {:.1}", point.x, point.y));
    }
    format!(r##"<path d="{data}" fill="none" stroke="#64748b" stroke-width="1.5"/>"##)
}

fn escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}
