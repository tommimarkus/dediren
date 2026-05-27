use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::io::{Cursor, Read, Write};
use std::path::PathBuf;
use std::process::{Command, Stdio};

use anyhow::{bail, Context};
use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, ExportRequest, ExportResult,
    GenericGraphPluginData, LaidOutGroup, SourceNode, SourceRelationship,
    EXPORT_RESULT_SCHEMA_VERSION, PLUGIN_PROTOCOL_VERSION,
};
use dediren_plugin_schema_cache::{
    command_output_details, ensure_cached_schema_file, is_non_empty_file, non_empty_env_path,
    schema_cache_base_dir,
};
use quick_xml::events::{BytesEnd, BytesStart, Event};
use quick_xml::Writer;
use serde_json::Value;

const XMI_NS: &str = "http://www.omg.org/spec/XMI/20131001";
const UML_NS: &str = "http://www.omg.org/spec/UML/20161101";
const XMI_VERSION: &str = "2.5.1";
const UML_VERSION: &str = "2.5.1";
const XMI_SCHEMA_VALIDATOR: &str = "xmllint";
const OMG_XMI_SCHEMA_URL: &str = "https://www.omg.org/spec/XMI/20131001/XMI.xsd";
const XMI_SCHEMA_PATH_ENV: &str = "DEDIREN_XMI_SCHEMA_PATH";
const SCHEMA_CACHE_DIR_ENV: &str = "DEDIREN_SCHEMA_CACHE_DIR";
const SCHEMA_FETCHER: &str = "curl";

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("capabilities") => {
            let schema_validation_available = Command::new(XMI_SCHEMA_VALIDATOR)
                .arg("--version")
                .output()
                .map(|output| output.status.success())
                .unwrap_or(false);
            println!(
                "{}",
                serde_json::json!({
                    "plugin_protocol_version": PLUGIN_PROTOCOL_VERSION,
                    "id": "uml-xmi",
                    "capabilities": ["export"],
                    "runtime": {
                        "artifact_kind": "uml-xmi+xml",
                        "uml_version": UML_VERSION,
                        "xmi_version": XMI_VERSION,
                        "schema_validation": {
                            "kind": "omg-xmi-xsd-partial",
                            "schema_version": XMI_VERSION,
                            "validator": XMI_SCHEMA_VALIDATOR,
                            "available": schema_validation_available,
                            "schema_source": "DEDIREN_XMI_SCHEMA_PATH or runtime cache download",
                            "schema_path_env": XMI_SCHEMA_PATH_ENV,
                            "cache_dir_env": SCHEMA_CACHE_DIR_ENV,
                            "fetcher": SCHEMA_FETCHER,
                            "limitation": "UML 2.5.1 is published as an XMI metamodel, not an importable XML Schema"
                        }
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
    if let Err(error) = validate_xmi_to_available_standards(&content) {
        match error {
            XmiValidationError::Invalid { code, message } => {
                exit_with_diagnostic(code, message, Some("content"))
            }
            XmiValidationError::ValidatorUnavailable(message) => exit_with_diagnostic(
                "DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE",
                message,
                Some("content"),
            ),
            XmiValidationError::SchemaUnavailable(message) => {
                exit_with_diagnostic("DEDIREN_XMI_SCHEMA_UNAVAILABLE", message, Some("content"))
            }
        }
    }
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
    let mut ids = IdentifierMap::with_reserved(&policy.model_identifier);
    let scope = ExportScope::from_request(request);
    let selected_nodes = request
        .source
        .nodes
        .iter()
        .filter(|node| scope.node_ids.contains(&node.id))
        .collect::<Vec<_>>();
    let selected_relationships = request
        .source
        .relationships
        .iter()
        .filter(|relationship| scope.relationship_ids.contains(&relationship.id))
        .collect::<Vec<_>>();
    let mut node_ids = BTreeMap::new();
    for node in &selected_nodes {
        node_ids.insert(node.id.clone(), ids.xmi_id(&node.id));
    }
    let mut relationship_ids = BTreeMap::new();
    for relationship in &selected_relationships {
        relationship_ids.insert(relationship.id.clone(), ids.xmi_id(&relationship.id));
    }
    let mut writer = Writer::new(Cursor::new(Vec::new()));

    let mut xmi = BytesStart::new("xmi:XMI");
    xmi.push_attribute(("xmlns:xmi", XMI_NS));
    xmi.push_attribute(("xmlns:uml", UML_NS));
    writer.write_event(Event::Start(xmi))?;

    let mut model = BytesStart::new("uml:Model");
    model.push_attribute(("xmi:id", policy.model_identifier.as_str()));
    model.push_attribute(("name", policy.model_name.as_str()));
    writer.write_event(Event::Start(model))?;

    for node in selected_nodes {
        let element_id = node_ids
            .get(&node.id)
            .with_context(|| format!("missing generated XMI id for node {}", node.id))?;
        match node.node_type.as_str() {
            "Package" => {
                write_empty_packaged_element(&mut writer, "uml:Package", node, element_id)?
            }
            "Class" => write_classifier(&mut writer, &mut ids, "uml:Class", node, element_id)?,
            "Interface" => {
                write_classifier(&mut writer, &mut ids, "uml:Interface", node, element_id)?;
            }
            "DataType" => {
                write_classifier(&mut writer, &mut ids, "uml:DataType", node, element_id)?;
            }
            "Enumeration" => write_enumeration(&mut writer, &mut ids, node, element_id)?,
            "Activity" => write_activity(
                &mut writer,
                node,
                element_id,
                &request.source.nodes,
                &selected_relationships,
                &node_ids,
                &relationship_ids,
            )?,
            "Action" | "InitialNode" | "ActivityFinalNode" | "DecisionNode" | "MergeNode"
            | "ForkNode" | "JoinNode" | "ObjectNode" => {}
            _ => {}
        }
    }

    writer.write_event(Event::End(BytesEnd::new("uml:Model")))?;
    writer.write_event(Event::End(BytesEnd::new("xmi:XMI")))?;

    let mut content = String::from_utf8(writer.into_inner().into_inner())?;
    content.push('\n');
    Ok(content)
}

enum XmiValidationError {
    Invalid { code: &'static str, message: String },
    ValidatorUnavailable(String),
    SchemaUnavailable(String),
}

fn validate_xmi_to_available_standards(content: &str) -> Result<(), XmiValidationError> {
    validate_xmi_document_and_ids(content)?;
    validate_omg_xmi_schema(content)
}

fn validate_xmi_document_and_ids(content: &str) -> Result<(), XmiValidationError> {
    let doc = roxmltree::Document::parse(content).map_err(|error| XmiValidationError::Invalid {
        code: "DEDIREN_XMI_XML_INVALID",
        message: format!("generated UML/XMI XML is not well-formed: {error}"),
    })?;
    let root = doc.root_element();
    if root.tag_name().namespace() != Some(XMI_NS) || root.tag_name().name() != "XMI" {
        return Err(XmiValidationError::Invalid {
            code: "DEDIREN_XMI_SCHEMA_INVALID",
            message: "generated UML/XMI XML root must be xmi:XMI in the OMG XMI namespace"
                .to_string(),
        });
    }
    if root.attribute((XMI_NS, "version")).is_some() {
        return Err(XmiValidationError::Invalid {
            code: "DEDIREN_XMI_SCHEMA_INVALID",
            message: "generated UML/XMI XML uses xmi:version, which OMG XMI.xsd does not allow"
                .to_string(),
        });
    }

    let mut ids = HashSet::new();
    for element in doc.descendants().filter(roxmltree::Node::is_element) {
        let Some(id) = element.attribute((XMI_NS, "id")) else {
            continue;
        };
        if !is_xml_id(id) {
            return Err(XmiValidationError::Invalid {
                code: "DEDIREN_XMI_ID_INVALID",
                message: format!("generated UML/XMI XML contains invalid xmi:id {id:?}"),
            });
        }
        if !ids.insert(id.to_string()) {
            return Err(XmiValidationError::Invalid {
                code: "DEDIREN_XMI_ID_INVALID",
                message: format!("generated UML/XMI XML contains duplicate xmi:id {id:?}"),
            });
        }
    }

    Ok(())
}

fn validate_omg_xmi_schema(content: &str) -> Result<(), XmiValidationError> {
    let schema_path = resolve_omg_xmi_schema_path()?;

    let mut child = Command::new(XMI_SCHEMA_VALIDATOR)
        .args(["--nonet", "--noout", "--schema"])
        .arg(&schema_path)
        .arg("-")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| {
            XmiValidationError::ValidatorUnavailable(format!(
                "failed to run OMG XMI schema validator {XMI_SCHEMA_VALIDATOR}: {error}"
            ))
        })?;

    let mut stdin = child.stdin.take().ok_or_else(|| {
        XmiValidationError::ValidatorUnavailable(format!(
            "failed to open stdin for OMG XMI schema validator {XMI_SCHEMA_VALIDATOR}"
        ))
    })?;
    stdin.write_all(content.as_bytes()).map_err(|error| {
        XmiValidationError::ValidatorUnavailable(format!(
            "failed to write UML/XMI XML to OMG XMI schema validator {XMI_SCHEMA_VALIDATOR}: {error}"
        ))
    })?;
    drop(stdin);

    let output = child.wait_with_output().map_err(|error| {
        XmiValidationError::ValidatorUnavailable(format!(
            "failed to read OMG XMI schema validator output: {error}"
        ))
    })?;
    if output.status.success() {
        return Ok(());
    }

    let details = command_output_details(
        &output.stdout,
        &output.stderr,
        XMI_SCHEMA_VALIDATOR,
        output.status,
    );
    if xmi_schema_errors_are_only_unavailable_uml_schema(&details) {
        return Ok(());
    }
    Err(XmiValidationError::Invalid {
        code: "DEDIREN_XMI_SCHEMA_INVALID",
        message: format!("generated UML/XMI XML does not validate against OMG XMI.xsd: {details}"),
    })
}

fn resolve_omg_xmi_schema_path() -> Result<PathBuf, XmiValidationError> {
    if let Some(configured) = non_empty_env_path(XMI_SCHEMA_PATH_ENV) {
        if is_non_empty_file(&configured) {
            return Ok(configured);
        }
        return Err(XmiValidationError::SchemaUnavailable(format!(
            "OMG XMI schema file {} is missing or empty; provide the official XMI.xsd or unset {XMI_SCHEMA_PATH_ENV} to allow cache download",
            configured.display()
        )));
    }

    let schema_path = schema_cache_base_dir(SCHEMA_CACHE_DIR_ENV, XMI_SCHEMA_PATH_ENV)
        .map_err(XmiValidationError::SchemaUnavailable)?
        .join("omg")
        .join("xmi")
        .join("2.5.1")
        .join("XMI.xsd");
    ensure_cached_schema_file(
        &schema_path,
        OMG_XMI_SCHEMA_URL,
        "OMG XMI schema",
        SCHEMA_FETCHER,
    )
    .map_err(XmiValidationError::SchemaUnavailable)?;
    Ok(schema_path)
}

fn xmi_schema_errors_are_only_unavailable_uml_schema(details: &str) -> bool {
    let mut saw_uml_schema_gap = false;
    for line in details
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
    {
        if line.ends_with("fails to validate") {
            continue;
        }
        if line.contains(UML_NS)
            && line.contains("No matching global element declaration available")
        {
            saw_uml_schema_gap = true;
            continue;
        }
        return false;
    }
    saw_uml_schema_gap
}

fn is_xml_id(value: &str) -> bool {
    let mut chars = value.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    if !(first == '_' || first.is_ascii_alphabetic()) {
        return false;
    }
    chars.all(|character| {
        character == '_'
            || character == '-'
            || character == '.'
            || character.is_ascii_alphanumeric()
    })
}

fn write_empty_packaged_element(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    uml_type: &str,
    node: &SourceNode,
    element_id: &str,
) -> anyhow::Result<()> {
    let element = packaged_element(uml_type, node, element_id);
    writer.write_event(Event::Empty(element))?;
    Ok(())
}

fn write_classifier(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    ids: &mut IdentifierMap,
    uml_type: &str,
    node: &SourceNode,
    element_id: &str,
) -> anyhow::Result<()> {
    let element = packaged_element(uml_type, node, element_id);
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
    element_id: &str,
) -> anyhow::Result<()> {
    let element = packaged_element("uml:Enumeration", node, element_id);
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

fn write_activity(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    activity: &SourceNode,
    activity_id: &str,
    source_nodes: &[SourceNode],
    selected_relationships: &[&SourceRelationship],
    node_ids: &BTreeMap<String, String>,
    relationship_ids: &BTreeMap<String, String>,
) -> anyhow::Result<()> {
    let element = packaged_element("uml:Activity", activity, activity_id);
    writer.write_event(Event::Start(element))?;

    for node in source_nodes.iter().filter(|node| {
        node_ids.contains_key(&node.id)
            && uml_string(node, "activity") == Some(activity.id.as_str())
    }) {
        let node_id = node_ids
            .get(&node.id)
            .with_context(|| format!("missing generated XMI id for activity node {}", node.id))?;
        write_activity_node(writer, node, node_id)?;
    }

    for relationship in selected_relationships {
        let Some(source_id) = node_ids.get(&relationship.source) else {
            continue;
        };
        let Some(target_id) = node_ids.get(&relationship.target) else {
            continue;
        };
        let relationship_id = relationship_ids.get(&relationship.id).with_context(|| {
            format!(
                "missing generated XMI id for activity relationship {}",
                relationship.id
            )
        })?;
        write_activity_edge(writer, relationship, relationship_id, source_id, target_id)?;
    }

    writer.write_event(Event::End(BytesEnd::new("packagedElement")))?;
    Ok(())
}

fn write_activity_node(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    node: &SourceNode,
    node_id: &str,
) -> anyhow::Result<()> {
    let mut activity_node = BytesStart::new("node");
    activity_node.push_attribute(("xmi:type", activity_node_xmi_type(&node.node_type)));
    activity_node.push_attribute(("xmi:id", node_id));
    if !node.label.is_empty() {
        activity_node.push_attribute(("name", node.label.as_str()));
    }
    if let Some(object_type) = uml_string(node, "type") {
        activity_node.push_attribute(("type", object_type));
    }
    writer.write_event(Event::Empty(activity_node))?;
    Ok(())
}

fn write_activity_edge(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    relationship: &SourceRelationship,
    relationship_id: &str,
    source_id: &str,
    target_id: &str,
) -> anyhow::Result<()> {
    let mut edge = BytesStart::new("edge");
    edge.push_attribute((
        "xmi:type",
        activity_edge_xmi_type(&relationship.relationship_type),
    ));
    edge.push_attribute(("xmi:id", relationship_id));
    if !relationship.label.is_empty() {
        edge.push_attribute(("name", relationship.label.as_str()));
    }
    edge.push_attribute(("source", source_id));
    edge.push_attribute(("target", target_id));
    writer.write_event(Event::Empty(edge))?;
    Ok(())
}

fn activity_node_xmi_type(node_type: &str) -> &'static str {
    match node_type {
        "Action" => "uml:OpaqueAction",
        "InitialNode" => "uml:InitialNode",
        "ActivityFinalNode" => "uml:ActivityFinalNode",
        "DecisionNode" => "uml:DecisionNode",
        "MergeNode" => "uml:MergeNode",
        "ForkNode" => "uml:ForkNode",
        "JoinNode" => "uml:JoinNode",
        "ObjectNode" => "uml:CentralBufferNode",
        _ => "uml:OpaqueAction",
    }
}

fn activity_edge_xmi_type(relationship_type: &str) -> &'static str {
    match relationship_type {
        "ObjectFlow" => "uml:ObjectFlow",
        _ => "uml:ControlFlow",
    }
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

fn uml_string<'a>(node: &'a SourceNode, field: &str) -> Option<&'a str> {
    node.properties
        .get("uml")
        .and_then(Value::as_object)
        .and_then(|uml| uml.get(field))
        .and_then(Value::as_str)
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

struct ExportScope {
    node_ids: BTreeSet<String>,
    relationship_ids: BTreeSet<String>,
}

impl ExportScope {
    fn from_request(request: &ExportRequest) -> Self {
        let source_nodes_by_id = request
            .source
            .nodes
            .iter()
            .map(|node| (node.id.as_str(), node))
            .collect::<BTreeMap<_, _>>();
        let mut node_ids = request
            .layout_result
            .nodes
            .iter()
            .map(|node| node.source_id.clone())
            .collect::<BTreeSet<_>>();

        for group in &request.layout_result.groups {
            if let Some(source_id) = semantic_group_source_id(group) {
                node_ids.insert(source_id.to_string());
            }
        }

        let activity_ids = node_ids
            .iter()
            .filter_map(|node_id| source_nodes_by_id.get(node_id.as_str()))
            .filter_map(|node| uml_string(node, "activity"))
            .map(ToString::to_string)
            .collect::<Vec<_>>();
        node_ids.extend(activity_ids);

        let relationship_ids = request
            .layout_result
            .edges
            .iter()
            .map(|edge| edge.source_id.clone())
            .collect();

        Self {
            node_ids,
            relationship_ids,
        }
    }
}

fn semantic_group_source_id(group: &LaidOutGroup) -> Option<&str> {
    match group.provenance.as_ref() {
        Some(provenance) if provenance.visual_only => None,
        Some(provenance) => provenance.semantic_source_id(),
        None => Some(group.source_id.as_str()),
    }
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
