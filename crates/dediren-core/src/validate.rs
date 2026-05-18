use std::collections::HashSet;
use std::path::Path;

use dediren_contracts::{CommandEnvelope, Diagnostic, DiagnosticSeverity, SourceDocument};

pub fn validate_source_json(text: &str) -> (i32, CommandEnvelope<serde_json::Value>) {
    validate_source_json_with_base(text, None)
}

pub fn validate_source_json_with_base(
    text: &str,
    base_dir: Option<&Path>,
) -> (i32, CommandEnvelope<serde_json::Value>) {
    let doc = match crate::source::load_source_document(text, base_dir) {
        Ok(doc) => doc,
        Err(diagnostics) => return (2, CommandEnvelope::error(diagnostics)),
    };
    match validate_source_document(&doc) {
        Ok(()) => {
            let data = serde_json::json!({
                "model_schema_version": doc.model_schema_version,
                "node_count": doc.nodes.len(),
                "relationship_count": doc.relationships.len()
            });
            (0, CommandEnvelope::ok(data))
        }
        Err(diagnostics) => (2, CommandEnvelope::error(diagnostics)),
    }
}

pub fn load_and_validate_source_document(
    text: &str,
    base_dir: Option<&Path>,
) -> Result<SourceDocument, Vec<Diagnostic>> {
    let doc = crate::source::load_source_document(text, base_dir)?;
    validate_source_document(&doc)?;
    Ok(doc)
}

fn validate_source_document(doc: &SourceDocument) -> Result<(), Vec<Diagnostic>> {
    let mut diagnostics = Vec::new();
    let mut ids = HashSet::new();
    let mut node_ids = HashSet::new();

    for node in &doc.nodes {
        if !ids.insert(node.id.clone()) {
            diagnostics.push(error(
                "DEDIREN_DUPLICATE_ID",
                format!("duplicate id '{}'", node.id),
                Some(format!("$.nodes[?(@.id=='{}')]", node.id)),
            ));
        }
        node_ids.insert(node.id.clone());
    }

    for relationship in &doc.relationships {
        if !ids.insert(relationship.id.clone()) {
            diagnostics.push(error(
                "DEDIREN_DUPLICATE_ID",
                format!("duplicate id '{}'", relationship.id),
                Some(format!("$.relationships[?(@.id=='{}')]", relationship.id)),
            ));
        }
        if !node_ids.contains(&relationship.source) {
            diagnostics.push(error(
                "DEDIREN_DANGLING_ENDPOINT",
                format!(
                    "relationship '{}' references missing source '{}'",
                    relationship.id, relationship.source
                ),
                Some(format!(
                    "$.relationships[?(@.id=='{}')].source",
                    relationship.id
                )),
            ));
        }
        if !node_ids.contains(&relationship.target) {
            diagnostics.push(error(
                "DEDIREN_DANGLING_ENDPOINT",
                format!(
                    "relationship '{}' references missing target '{}'",
                    relationship.id, relationship.target
                ),
                Some(format!(
                    "$.relationships[?(@.id=='{}')].target",
                    relationship.id
                )),
            ));
        }
    }

    if diagnostics.is_empty() {
        Ok(())
    } else {
        Err(diagnostics)
    }
}

fn error(code: &str, message: String, path: Option<String>) -> Diagnostic {
    Diagnostic {
        code: code.to_string(),
        severity: DiagnosticSeverity::Error,
        message,
        path,
    }
}
