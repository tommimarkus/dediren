use std::io::Read;

use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, LayoutRequest, LayoutResult,
};

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("capabilities") {
        println!(
            "{}",
            serde_json::json!({
                "plugin_protocol_version": "plugin.protocol.v1",
                "id": "elk-layout",
                "capabilities": ["layout"],
                "runtime": {
                    "kind": "external-elk",
                    "available": std::env::var("DEDIREN_ELK_COMMAND").is_ok()
                        || std::env::var("DEDIREN_ELK_RESULT_FIXTURE").is_ok()
                }
            })
        );
        return Ok(());
    }

    if args.get(1).map(String::as_str) != Some("layout") {
        anyhow::bail!("expected command: layout");
    }

    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let _request: LayoutRequest = serde_json::from_str(&input)?;

    if let Ok(fixture) = std::env::var("DEDIREN_ELK_RESULT_FIXTURE") {
        let text = std::fs::read_to_string(fixture)?;
        let result: LayoutResult = serde_json::from_str(&text)?;
        println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
        return Ok(());
    }

    let diagnostic = Diagnostic {
        code: "DEDIREN_ELK_RUNTIME_UNAVAILABLE".to_string(),
        severity: DiagnosticSeverity::Error,
        message: "ELK runtime is not configured; set DEDIREN_ELK_COMMAND or use a test fixture"
            .to_string(),
        path: None,
    };
    println!(
        "{}",
        serde_json::to_string(&CommandEnvelope::<serde_json::Value>::error(vec![
            diagnostic
        ]))?
    );
    std::process::exit(3);
}
