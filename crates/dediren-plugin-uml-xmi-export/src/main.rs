use std::collections::HashSet;
use std::io::{Cursor, Read};

use anyhow::{bail, Context};
use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, ExportRequest, ExportResult,
    GenericGraphPluginData, SourceNode, EXPORT_RESULT_SCHEMA_VERSION, PLUGIN_PROTOCOL_VERSION,
};
use quick_xml::events::{BytesEnd, BytesStart, Event};
use quick_xml::Writer;
use serde_json::Value;

const XMI_NS: &str = "http://www.omg.org/spec/XMI/20131001";
const UML_NS: &str = "http://www.omg.org/spec/UML/20161101";
const XMI_VERSION: &str = "2.5.1";
const UML_VERSION: &str = "2.5.1";

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("capabilities") => {
            println!(
                "{}",
                serde_json::json!({
                    "plugin_protocol_version": PLUGIN_PROTOCOL_VERSION,
                    "id": "uml-xmi",
                    "capabilities": ["export"],
                    "runtime": {
                        "artifact_kind": "uml-xmi+xml",
                        "uml_version": UML_VERSION,
                        "xmi_version": XMI_VERSION
                    }
                })
            );
            Ok(())
        }
        Some("export") => export_from_stdin(),
        _ => bail!("expected command: capabilities or export"),
    }
}

fn export_from_stdin() -> anyhow::Result<()> {
    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input)?;
    let request: ExportRequest = serde_json::from_str(&input)?;
    if let Err(message) = validate_policy_schema(&request.policy) {
        exit_with_diagnostic("DEDIREN_UML_XMI_POLICY_INVALID", message, Some("policy"));
    }
    let policy: dediren_contracts::UmlXmiExportPolicy =
        serde_json::from_value(request.policy.clone())?;
    let plugin_data = generic_graph_plugin_data(&request)?;

    if let Err(error) = dediren_uml::validate_source(&request.source, &plugin_data) {
        exit_with_uml_validation_error(error);
    }

    let content = build_xmi(&request, &policy)?;
    let result = ExportResult {
        export_result_schema_version: EXPORT_RESULT_SCHEMA_VERSION.to_string(),
        artifact_kind: "uml-xmi+xml".to_string(),
        content,
    };
    println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
    Ok(())
}

fn generic_graph_plugin_data(request: &ExportRequest) -> anyhow::Result<GenericGraphPluginData> {
    let value = request
        .source
        .plugins
        .get("generic-graph")
        .context("source is missing plugins.generic-graph")?
        .clone();
    serde_json::from_value(value)
        .context("plugins.generic-graph should match GenericGraphPluginData")
}

fn build_xmi(
    request: &ExportRequest,
    policy: &dediren_contracts::UmlXmiExportPolicy,
) -> anyhow::Result<String> {
    let xmi_version = policy.xmi_version.as_deref().unwrap_or(XMI_VERSION);
    let mut ids = IdentifierMap::with_reserved(&policy.model_identifier);
    let mut writer = Writer::new(Cursor::new(Vec::new()));

    let mut xmi = BytesStart::new("xmi:XMI");
    xmi.push_attribute(("xmlns:xmi", XMI_NS));
    xmi.push_attribute(("xmlns:uml", UML_NS));
    xmi.push_attribute(("xmi:version", xmi_version));
    writer.write_event(Event::Start(xmi))?;

    let mut model = BytesStart::new("uml:Model");
    model.push_attribute(("xmi:id", policy.model_identifier.as_str()));
    model.push_attribute(("name", policy.model_name.as_str()));
    writer.write_event(Event::Start(model))?;

    for node in &request.source.nodes {
        match node.node_type.as_str() {
            "Package" => write_empty_packaged_element(&mut writer, &mut ids, "uml:Package", node)?,
            "Class" => write_class(&mut writer, &mut ids, node)?,
            "Enumeration" => write_enumeration(&mut writer, &mut ids, node)?,
            "Activity" => {
                write_empty_packaged_element(&mut writer, &mut ids, "uml:Activity", node)?;
            }
            _ => {}
        }
    }

    writer.write_event(Event::End(BytesEnd::new("uml:Model")))?;
    writer.write_event(Event::End(BytesEnd::new("xmi:XMI")))?;

    let mut content = String::from_utf8(writer.into_inner().into_inner())?;
    content.push('\n');
    Ok(content)
}

fn write_empty_packaged_element(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    ids: &mut IdentifierMap,
    uml_type: &str,
    node: &SourceNode,
) -> anyhow::Result<()> {
    let element_id = ids.xmi_id(&node.id);
    let element = packaged_element(uml_type, node, &element_id);
    writer.write_event(Event::Empty(element))?;
    Ok(())
}

fn write_class(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    ids: &mut IdentifierMap,
    node: &SourceNode,
) -> anyhow::Result<()> {
    let element_id = ids.xmi_id(&node.id);
    let element = packaged_element("uml:Class", node, &element_id);
    writer.write_event(Event::Start(element))?;

    for attribute in uml_array(node, "attributes") {
        write_owned_attribute(writer, ids, node, attribute)?;
    }
    for operation in uml_array(node, "operations") {
        write_owned_operation(writer, ids, node, operation)?;
    }

    writer.write_event(Event::End(BytesEnd::new("packagedElement")))?;
    Ok(())
}

fn write_owned_attribute(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    ids: &mut IdentifierMap,
    node: &SourceNode,
    attribute: &Value,
) -> anyhow::Result<()> {
    let name = string_field(attribute, "name").unwrap_or("attribute");
    let id = ids.xmi_id(&format!("{}-{name}", node.id));
    let attribute_type = string_field(attribute, "type").unwrap_or("String");
    let visibility = string_field(attribute, "visibility").unwrap_or("public");
    let (lower, upper) =
        multiplicity_bounds(string_field(attribute, "multiplicity").unwrap_or("1"));

    let mut owned_attribute = BytesStart::new("ownedAttribute");
    owned_attribute.push_attribute(("xmi:id", id.as_str()));
    owned_attribute.push_attribute(("name", name));
    owned_attribute.push_attribute(("type", attribute_type));
    owned_attribute.push_attribute(("visibility", visibility));
    owned_attribute.push_attribute(("lowerValue", lower.as_str()));
    owned_attribute.push_attribute(("upperValue", upper.as_str()));
    writer.write_event(Event::Empty(owned_attribute))?;
    Ok(())
}

fn write_owned_operation(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    ids: &mut IdentifierMap,
    node: &SourceNode,
    operation: &Value,
) -> anyhow::Result<()> {
    let name = string_field(operation, "name").unwrap_or("operation");
    let id = ids.xmi_id(&format!("{}-{name}", node.id));
    let visibility = string_field(operation, "visibility").unwrap_or("public");

    let mut owned_operation = BytesStart::new("ownedOperation");
    owned_operation.push_attribute(("xmi:id", id.as_str()));
    owned_operation.push_attribute(("name", name));
    owned_operation.push_attribute(("visibility", visibility));
    writer.write_event(Event::Empty(owned_operation))?;
    Ok(())
}

fn write_enumeration(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    ids: &mut IdentifierMap,
    node: &SourceNode,
) -> anyhow::Result<()> {
    let element_id = ids.xmi_id(&node.id);
    let element = packaged_element("uml:Enumeration", node, &element_id);
    writer.write_event(Event::Start(element))?;

    for literal in uml_array(node, "literals") {
        let Some(name) = literal.as_str() else {
            continue;
        };
        let id = ids.xmi_id(&format!("{}-{name}", node.id));
        let mut owned_literal = BytesStart::new("ownedLiteral");
        owned_literal.push_attribute(("xmi:id", id.as_str()));
        owned_literal.push_attribute(("name", name));
        writer.write_event(Event::Empty(owned_literal))?;
    }

    writer.write_event(Event::End(BytesEnd::new("packagedElement")))?;
    Ok(())
}

fn packaged_element<'a>(
    uml_type: &'a str,
    node: &'a SourceNode,
    element_id: &'a str,
) -> BytesStart<'a> {
    let mut element = BytesStart::new("packagedElement");
    element.push_attribute(("xmi:type", uml_type));
    element.push_attribute(("xmi:id", element_id));
    element.push_attribute(("name", node.label.as_str()));
    element
}

fn uml_array<'a>(node: &'a SourceNode, field: &str) -> &'a [Value] {
    node.properties
        .get("uml")
        .and_then(Value::as_object)
        .and_then(|uml| uml.get(field))
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[])
}

fn string_field<'a>(value: &'a Value, field: &str) -> Option<&'a str> {
    value.as_object()?.get(field)?.as_str()
}

fn multiplicity_bounds(value: &str) -> (String, String) {
    if let Some((lower, upper)) = value.split_once("..") {
        (lower.to_string(), upper.to_string())
    } else if value == "*" {
        ("0".to_string(), "*".to_string())
    } else {
        (value.to_string(), value.to_string())
    }
}

fn validate_policy_schema(value: &Value) -> Result<(), String> {
    let schema: Value = serde_json::from_str(include_str!(
        "../../../schemas/uml-xmi-export-policy.schema.json"
    ))
    .map_err(|error| error.to_string())?;
    let validator = jsonschema::validator_for(&schema).map_err(|error| error.to_string())?;
    validator.validate(value).map_err(|error| error.to_string())
}

#[derive(Default)]
struct IdentifierMap {
    used: HashSet<String>,
}

impl IdentifierMap {
    fn with_reserved(value: &str) -> Self {
        let mut ids = Self::default();
        ids.used.insert(value.to_string());
        ids
    }

    fn xmi_id(&mut self, value: &str) -> String {
        let base = format!("id-{}", slug(value));
        if self.used.insert(base.clone()) {
            return base;
        }

        for suffix in 2.. {
            let candidate = format!("{base}-{suffix}");
            if self.used.insert(candidate.clone()) {
                return candidate;
            }
        }
        unreachable!("suffix loop must return")
    }
}

fn slug(value: &str) -> String {
    let mut result = String::new();
    let mut previous_dash = false;
    for character in value.chars() {
        if character.is_ascii_alphanumeric() {
            result.push(character.to_ascii_lowercase());
            previous_dash = false;
        } else if !previous_dash {
            result.push('-');
            previous_dash = true;
        }
    }
    let trimmed = result.trim_matches('-').to_string();
    if trimmed.is_empty() {
        "item".to_string()
    } else {
        trimmed
    }
}

fn exit_with_uml_validation_error(error: dediren_uml::UmlValidationError) -> ! {
    exit_with_diagnostic(error.code(), error.message(), Some(error.path));
}

fn exit_with_diagnostic(
    code: impl Into<String>,
    message: impl Into<String>,
    path: Option<impl Into<String>>,
) -> ! {
    let diagnostic = Diagnostic {
        code: code.into(),
        severity: DiagnosticSeverity::Error,
        message: message.into(),
        path: path.map(Into::into),
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
