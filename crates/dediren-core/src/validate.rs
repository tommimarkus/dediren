use std::collections::HashSet;

use dediren_contracts::{CommandEnvelope, Diagnostic, DiagnosticSeverity, SourceDocument};

pub fn validate_source_json(text: &str) -> (i32, CommandEnvelope<serde_json::Value>) {
    let value: serde_json::Value = match serde_json::from_str(text) {
        Ok(value) => value,
        Err(error) => {
            return (
                2,
                CommandEnvelope::error(vec![schema_error(error.to_string())]),
            );
        }
    };

    let schema: serde_json::Value =
        serde_json::from_str(include_str!("../../../schemas/model.schema.json"))
            .expect("model schema must be valid JSON");
    let validator = jsonschema::validator_for(&schema).expect("model schema must compile");
    if let Err(error) = validator.validate(&value) {
        return (
            2,
            CommandEnvelope::error(vec![schema_error(error.to_string())]),
        );
    }

    let doc: SourceDocument = match serde_json::from_value(value) {
        Ok(doc) => doc,
        Err(error) => {
            return (
                2,
                CommandEnvelope::error(vec![schema_error(error.to_string())]),
            );
        }
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

fn schema_error(message: String) -> Diagnostic {
    Diagnostic {
        code: "DEDIREN_SCHEMA_INVALID".to_string(),
        severity: DiagnosticSeverity::Error,
        message,
        path: None,
    }
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
