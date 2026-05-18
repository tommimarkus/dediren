use std::path::Path;

use dediren_contracts::{
    Diagnostic, DiagnosticSeverity, PluginRequirement, SourceDocument, MODEL_SCHEMA_VERSION,
};
use serde_json::{Map, Value};

pub fn load_source_document(
    text: &str,
    base_dir: Option<&Path>,
) -> Result<SourceDocument, Vec<Diagnostic>> {
    let mut root = parse_source_document(text)?;
    assemble_fragments(&mut root, base_dir)?;
    root.fragments.clear();
    Ok(root)
}

pub fn validate_source_shape(value: &Value) -> Result<(), Diagnostic> {
    let schema: Value = serde_json::from_str(include_str!("../../../schemas/model.schema.json"))
        .expect("model schema must be valid JSON");
    let validator = jsonschema::validator_for(&schema).expect("model schema must compile");
    validator
        .validate(value)
        .map_err(|error| schema_error(error.to_string()))
}

pub fn schema_error(message: String) -> Diagnostic {
    Diagnostic {
        code: "DEDIREN_SCHEMA_INVALID".to_string(),
        severity: DiagnosticSeverity::Error,
        message,
        path: None,
    }
}

fn parse_source_document(text: &str) -> Result<SourceDocument, Vec<Diagnostic>> {
    let value: Value =
        serde_json::from_str(text).map_err(|error| vec![schema_error(error.to_string())])?;
    validate_source_shape(&value).map_err(|diagnostic| vec![diagnostic])?;
    serde_json::from_value(value).map_err(|error| vec![schema_error(error.to_string())])
}

fn assemble_fragments(
    root: &mut SourceDocument,
    base_dir: Option<&Path>,
) -> Result<(), Vec<Diagnostic>> {
    if root.fragments.is_empty() {
        return Ok(());
    }

    let Some(base_dir) = base_dir else {
        return Err(vec![error(
            "DEDIREN_FRAGMENT_BASE_DIR_REQUIRED",
            "source fragments require file input so relative fragment paths can be resolved"
                .to_string(),
            Some("$.fragments".to_string()),
        )]);
    };

    let fragments = root.fragments.clone();
    for (index, fragment) in fragments.iter().enumerate() {
        let fragment_path = Path::new(fragment);
        if fragment_path.is_absolute() {
            return Err(vec![error(
                "DEDIREN_FRAGMENT_PATH_UNSUPPORTED",
                format!(
                    "fragment '{}' must be relative to the source model",
                    fragment
                ),
                Some(format!("$.fragments[{index}]")),
            )]);
        }

        let full_path = base_dir.join(fragment_path);
        let text = std::fs::read_to_string(&full_path).map_err(|read_error| {
            vec![error(
                "DEDIREN_FRAGMENT_READ_FAILED",
                format!("failed to read fragment '{}': {}", fragment, read_error),
                Some(format!("$.fragments[{index}]")),
            )]
        })?;
        let mut fragment_doc = parse_source_document(&text)?;
        if !fragment_doc.fragments.is_empty() {
            return Err(vec![error(
                "DEDIREN_FRAGMENT_NESTED_UNSUPPORTED",
                format!("fragment '{}' declares nested fragments", fragment),
                Some(format!("$.fragments[{index}]")),
            )]);
        }
        merge_fragment(root, &mut fragment_doc, index)?;
    }

    Ok(())
}

fn merge_fragment(
    root: &mut SourceDocument,
    fragment: &mut SourceDocument,
    fragment_index: usize,
) -> Result<(), Vec<Diagnostic>> {
    if fragment.model_schema_version != MODEL_SCHEMA_VERSION {
        return Err(vec![error(
            "DEDIREN_FRAGMENT_SCHEMA_VERSION_UNSUPPORTED",
            format!(
                "fragment schema version '{}' is not supported",
                fragment.model_schema_version
            ),
            Some(format!("$.fragments[{fragment_index}]")),
        )]);
    }

    merge_required_plugins(&mut root.required_plugins, &fragment.required_plugins)?;
    root.nodes.append(&mut fragment.nodes);
    root.relationships.append(&mut fragment.relationships);
    merge_plugins(&mut root.plugins, std::mem::take(&mut fragment.plugins))?;
    Ok(())
}

fn merge_required_plugins(
    root: &mut Vec<PluginRequirement>,
    fragment: &[PluginRequirement],
) -> Result<(), Vec<Diagnostic>> {
    for requirement in fragment {
        match root
            .iter()
            .find(|existing| existing.id == requirement.id)
            .map(|existing| existing.version.as_str())
        {
            Some(version) if version != requirement.version => {
                return Err(vec![error(
                    "DEDIREN_FRAGMENT_CONFLICT",
                    format!(
                        "required plugin '{}' has conflicting versions '{}' and '{}'",
                        requirement.id, version, requirement.version
                    ),
                    Some("$.required_plugins".to_string()),
                )]);
            }
            Some(_) => {}
            None => root.push(requirement.clone()),
        }
    }
    Ok(())
}

fn merge_plugins(
    root: &mut Map<String, Value>,
    fragment: Map<String, Value>,
) -> Result<(), Vec<Diagnostic>> {
    for (key, value) in fragment {
        match root.get_mut(&key) {
            Some(existing) => merge_value(existing, value, &format!("$.plugins.{key}"))?,
            None => {
                root.insert(key, value);
            }
        }
    }
    Ok(())
}

fn merge_value(target: &mut Value, source: Value, path: &str) -> Result<(), Vec<Diagnostic>> {
    match (target, source) {
        (Value::Object(target), Value::Object(source)) => {
            for (key, value) in source {
                match target.get_mut(&key) {
                    Some(existing) => merge_value(existing, value, &format!("{path}.{key}"))?,
                    None => {
                        target.insert(key, value);
                    }
                }
            }
        }
        (Value::Array(target), Value::Array(mut source)) => {
            target.append(&mut source);
        }
        (target, source) if *target == source => {}
        (_, _) => {
            return Err(vec![error(
                "DEDIREN_FRAGMENT_CONFLICT",
                format!("fragment value conflicts at {path}"),
                Some(path.to_string()),
            )]);
        }
    }
    Ok(())
}

fn error(code: &str, message: String, path: Option<String>) -> Diagnostic {
    Diagnostic {
        code: code.to_string(),
        severity: DiagnosticSeverity::Error,
        message,
        path,
    }
}
