use std::collections::{BTreeMap, HashSet};
use std::io::{Cursor, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use anyhow::{bail, Context};
use dediren_archimate::{
    ArchimateJunctionValidationError, ArchimateTypeValidationError, JunctionValidationNode,
    JunctionValidationRelationship,
};
use dediren_contracts::{
    CommandEnvelope, Diagnostic, DiagnosticSeverity, ExportRequest, ExportResult, LaidOutGroup,
    OefExportInput, OefExportPolicy, EXPORT_RESULT_SCHEMA_VERSION, PLUGIN_PROTOCOL_VERSION,
};
use quick_xml::events::{BytesEnd, BytesStart, BytesText, Event};
use quick_xml::Writer;

const OEF_NS: &str = "http://www.opengroup.org/xsd/archimate/3.0/";
const XSI_NS: &str = "http://www.w3.org/2001/XMLSchema-instance";
const OEF_SCHEMA: &str = "http://www.opengroup.org/xsd/archimate/3.1/archimate3_Model.xsd";
const OEF_SCHEMA_VALIDATOR: &str = "xmllint";
const OEF_SCHEMA_BASE_URL: &str = "https://www.opengroup.org/xsd/archimate/3.1";
const OEF_SCHEMA_DIR_ENV: &str = "DEDIREN_OEF_SCHEMA_DIR";
const SCHEMA_CACHE_DIR_ENV: &str = "DEDIREN_SCHEMA_CACHE_DIR";
const SCHEMA_FETCHER: &str = "curl";
const OFFICIAL_OEF_SCHEMA_FILES: &[&str] = &[
    "archimate3_Model.xsd",
    "archimate3_View.xsd",
    "archimate3_Diagram.xsd",
];

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("capabilities") => {
            let schema_validation_available = Command::new(OEF_SCHEMA_VALIDATOR)
                .arg("--version")
                .output()
                .map(|output| output.status.success())
                .unwrap_or(false);
            println!(
                "{}",
                serde_json::json!({
                    "plugin_protocol_version": PLUGIN_PROTOCOL_VERSION,
                    "id": "archimate-oef",
                    "capabilities": ["export"],
                    "runtime": {
                        "artifact_kind": "archimate-oef+xml",
                        "archimate_version": "3.2",
                        "oef_namespace": OEF_NS,
                        "schema_validation": {
                            "kind": "official-oef-xsd",
                            "schema_version": "3.1",
                            "validator": OEF_SCHEMA_VALIDATOR,
                            "available": schema_validation_available,
                            "schema_source": "DEDIREN_OEF_SCHEMA_DIR or runtime cache download",
                            "schema_dir_env": OEF_SCHEMA_DIR_ENV,
                            "cache_dir_env": SCHEMA_CACHE_DIR_ENV,
                            "fetcher": SCHEMA_FETCHER
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
    if let Err(message) = validate_oef_policy_schema(&request.policy) {
        exit_with_diagnostic(
            "DEDIREN_OEF_POLICY_INVALID",
            &message,
            Some("policy".to_string()),
        );
    }
    let policy: OefExportPolicy = serde_json::from_value(request.policy)?;
    let request = OefExportInput {
        export_request_schema_version: request.export_request_schema_version,
        source: request.source,
        layout_result: request.layout_result,
        policy,
    };
    if let Err(error) = validate_archimate_types(&request) {
        exit_with_archimate_type_error(error);
    }
    if let Err(error) = validate_archimate_junction_semantics(&request) {
        exit_with_diagnostic(&error.code, &error.message, Some(error.path));
    }
    if let Err(error) = validate_archimate_group_semantics(&request) {
        exit_with_diagnostic(&error.code, &error.message, Some(error.path));
    }
    let content = build_oef(&request)?;
    if let Err(error) = validate_official_oef_schema(&content) {
        match error {
            OefSchemaValidationError::Invalid(message) => exit_with_diagnostic(
                "DEDIREN_OEF_SCHEMA_INVALID",
                &message,
                Some("content".into()),
            ),
            OefSchemaValidationError::ValidatorUnavailable(message) => exit_with_diagnostic(
                "DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE",
                &message,
                Some("content".into()),
            ),
            OefSchemaValidationError::SchemaUnavailable(message) => exit_with_diagnostic(
                "DEDIREN_OEF_SCHEMA_UNAVAILABLE",
                &message,
                Some("content".into()),
            ),
        }
    }
    let result = ExportResult {
        export_result_schema_version: EXPORT_RESULT_SCHEMA_VERSION.to_string(),
        artifact_kind: "archimate-oef+xml".to_string(),
        content,
    };
    println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
    Ok(())
}

fn build_oef(request: &OefExportInput) -> anyhow::Result<String> {
    let mut ids = IdentifierMap::default();
    let element_ids: BTreeMap<_, _> = request
        .source
        .nodes
        .iter()
        .map(|node| (node.id.clone(), ids.oef_id("el", &node.id)))
        .collect();
    let relationship_ids: BTreeMap<_, _> = request
        .source
        .relationships
        .iter()
        .map(|relationship| (relationship.id.clone(), ids.oef_id("rel", &relationship.id)))
        .collect();
    let source_nodes_by_id: BTreeMap<_, _> = request
        .source
        .nodes
        .iter()
        .map(|node| (node.id.as_str(), node))
        .collect();
    let semantic_groups: Vec<_> = request
        .layout_result
        .groups
        .iter()
        .filter_map(|group| {
            let source_id = semantic_group_source_id(group)?;
            let source_node = source_nodes_by_id.get(source_id)?;
            if source_node.node_type == "Grouping" {
                Some((group, source_id))
            } else {
                None
            }
        })
        .collect();
    let view_node_ids: BTreeMap<_, _> = request
        .layout_result
        .nodes
        .iter()
        .map(|node| {
            (
                node.id.clone(),
                ids.oef_id(&format!("vn-{}", request.layout_result.view_id), &node.id),
            )
        })
        .collect();
    let group_view_node_ids: BTreeMap<_, _> = semantic_groups
        .iter()
        .map(|(group, _source_id)| {
            (
                group.id.clone(),
                ids.oef_id(&format!("vg-{}", request.layout_result.view_id), &group.id),
            )
        })
        .collect();
    let view_connection_ids: BTreeMap<_, _> = request
        .layout_result
        .edges
        .iter()
        .map(|edge| {
            (
                edge.id.clone(),
                ids.oef_id(&format!("vc-{}", request.layout_result.view_id), &edge.id),
            )
        })
        .collect();

    let mut writer = Writer::new(Cursor::new(Vec::new()));

    let mut model = BytesStart::new("model");
    model.push_attribute(("xmlns", OEF_NS));
    model.push_attribute(("xmlns:xsi", XSI_NS));
    let schema_location = format!("{OEF_NS} {OEF_SCHEMA}");
    model.push_attribute(("xsi:schemaLocation", schema_location.as_str()));
    model.push_attribute(("identifier", request.policy.model_identifier.as_str()));
    writer.write_event(Event::Start(model))?;

    write_text_element(&mut writer, "name", &request.policy.model_name)?;

    writer.write_event(Event::Start(BytesStart::new("elements")))?;
    for node in &request.source.nodes {
        let mut element = BytesStart::new("element");
        element.push_attribute(("identifier", element_ids[&node.id].as_str()));
        element.push_attribute(("xsi:type", node.node_type.as_str()));
        writer.write_event(Event::Start(element))?;
        write_text_element(&mut writer, "name", &node.label)?;
        writer.write_event(Event::End(BytesEnd::new("element")))?;
    }
    writer.write_event(Event::End(BytesEnd::new("elements")))?;

    writer.write_event(Event::Start(BytesStart::new("relationships")))?;
    for relationship in &request.source.relationships {
        let mut rel = BytesStart::new("relationship");
        rel.push_attribute(("identifier", relationship_ids[&relationship.id].as_str()));
        rel.push_attribute((
            "source",
            element_ids
                .get(&relationship.source)
                .with_context(|| format!("relationship {} has missing source", relationship.id))?
                .as_str(),
        ));
        rel.push_attribute((
            "target",
            element_ids
                .get(&relationship.target)
                .with_context(|| format!("relationship {} has missing target", relationship.id))?
                .as_str(),
        ));
        rel.push_attribute(("xsi:type", relationship.relationship_type.as_str()));
        writer.write_event(Event::Start(rel))?;
        write_text_element(&mut writer, "name", &relationship.label)?;
        writer.write_event(Event::End(BytesEnd::new("relationship")))?;
    }
    writer.write_event(Event::End(BytesEnd::new("relationships")))?;

    writer.write_event(Event::Start(BytesStart::new("views")))?;
    writer.write_event(Event::Start(BytesStart::new("diagrams")))?;
    let mut view = BytesStart::new("view");
    view.push_attribute(("identifier", request.policy.view_identifier.as_str()));
    view.push_attribute(("xsi:type", "Diagram"));
    view.push_attribute(("viewpoint", request.policy.viewpoint.as_str()));
    writer.write_event(Event::Start(view))?;
    write_text_element(&mut writer, "name", &request.policy.view_name)?;

    for (group, source_id) in &semantic_groups {
        let element_ref = element_ids
            .get(*source_id)
            .with_context(|| format!("layout group {} has missing semantic source", group.id))?;
        let mut view_node = BytesStart::new("node");
        let x = format_number(group.x);
        let y = format_number(group.y);
        let width = format_number(group.width);
        let height = format_number(group.height);
        view_node.push_attribute(("identifier", group_view_node_ids[&group.id].as_str()));
        view_node.push_attribute(("xsi:type", "Element"));
        view_node.push_attribute(("elementRef", element_ref.as_str()));
        view_node.push_attribute(("x", x.as_str()));
        view_node.push_attribute(("y", y.as_str()));
        view_node.push_attribute(("w", width.as_str()));
        view_node.push_attribute(("h", height.as_str()));
        writer.write_event(Event::Empty(view_node))?;
    }

    for node in &request.layout_result.nodes {
        let element_ref = element_ids
            .get(&node.source_id)
            .with_context(|| format!("layout node {} has missing source", node.id))?;
        let mut view_node = BytesStart::new("node");
        let x = format_number(node.x);
        let y = format_number(node.y);
        let width = format_number(node.width);
        let height = format_number(node.height);
        view_node.push_attribute(("identifier", view_node_ids[&node.id].as_str()));
        view_node.push_attribute(("xsi:type", "Element"));
        view_node.push_attribute(("elementRef", element_ref.as_str()));
        view_node.push_attribute(("x", x.as_str()));
        view_node.push_attribute(("y", y.as_str()));
        view_node.push_attribute(("w", width.as_str()));
        view_node.push_attribute(("h", height.as_str()));
        writer.write_event(Event::Empty(view_node))?;
    }

    for edge in &request.layout_result.edges {
        let relationship_ref = relationship_ids
            .get(&edge.source_id)
            .with_context(|| format!("layout edge {} has missing source relationship", edge.id))?;
        let mut connection = BytesStart::new("connection");
        connection.push_attribute(("identifier", view_connection_ids[&edge.id].as_str()));
        connection.push_attribute(("xsi:type", "Relationship"));
        connection.push_attribute(("relationshipRef", relationship_ref.as_str()));
        connection.push_attribute((
            "source",
            view_node_ids
                .get(&edge.source)
                .with_context(|| format!("layout edge {} has missing source node", edge.id))?
                .as_str(),
        ));
        connection.push_attribute((
            "target",
            view_node_ids
                .get(&edge.target)
                .with_context(|| format!("layout edge {} has missing target node", edge.id))?
                .as_str(),
        ));
        writer.write_event(Event::Start(connection))?;
        for point in &edge.points {
            let mut bendpoint = BytesStart::new("bendpoint");
            let x = format_number(point.x);
            let y = format_number(point.y);
            bendpoint.push_attribute(("x", x.as_str()));
            bendpoint.push_attribute(("y", y.as_str()));
            writer.write_event(Event::Empty(bendpoint))?;
        }
        writer.write_event(Event::End(BytesEnd::new("connection")))?;
    }

    writer.write_event(Event::End(BytesEnd::new("view")))?;
    writer.write_event(Event::End(BytesEnd::new("diagrams")))?;
    writer.write_event(Event::End(BytesEnd::new("views")))?;
    writer.write_event(Event::End(BytesEnd::new("model")))?;

    let mut content = String::from_utf8(writer.into_inner().into_inner())?;
    content.push('\n');
    Ok(content)
}

enum OefSchemaValidationError {
    Invalid(String),
    ValidatorUnavailable(String),
    SchemaUnavailable(String),
}

fn validate_official_oef_schema(content: &str) -> Result<(), OefSchemaValidationError> {
    let schema_dir = resolve_official_oef_schema_dir()?;
    let schema_path = schema_dir.join("archimate3_Diagram.xsd");
    let mut child = Command::new(OEF_SCHEMA_VALIDATOR)
        .args(["--nonet", "--noout", "--schema"])
        .arg(&schema_path)
        .arg("-")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| {
            OefSchemaValidationError::ValidatorUnavailable(format!(
                "failed to run official OEF schema validator {OEF_SCHEMA_VALIDATOR}: {error}"
            ))
        })?;

    let mut stdin = child.stdin.take().ok_or_else(|| {
        OefSchemaValidationError::ValidatorUnavailable(format!(
            "failed to open stdin for official OEF schema validator {OEF_SCHEMA_VALIDATOR}"
        ))
    })?;
    stdin.write_all(content.as_bytes()).map_err(|error| {
        OefSchemaValidationError::ValidatorUnavailable(format!(
            "failed to write OEF XML to official OEF schema validator {OEF_SCHEMA_VALIDATOR}: {error}"
        ))
    })?;
    drop(stdin);

    let output = child.wait_with_output().map_err(|error| {
        OefSchemaValidationError::ValidatorUnavailable(format!(
            "failed to read official OEF schema validator output: {error}"
        ))
    })?;
    if output.status.success() {
        return Ok(());
    }

    let mut details = String::from_utf8_lossy(&output.stderr).trim().to_string();
    let stdout = String::from_utf8_lossy(&output.stdout);
    if details.is_empty() {
        details = stdout.trim().to_string();
    }
    if details.is_empty() {
        details = format!(
            "{OEF_SCHEMA_VALIDATOR} exited with status {}",
            output.status
        );
    }
    Err(OefSchemaValidationError::Invalid(format!(
        "generated OEF XML does not validate against the official OEF schema: {details}"
    )))
}

fn resolve_official_oef_schema_dir() -> Result<PathBuf, OefSchemaValidationError> {
    if let Some(configured) = non_empty_env_path(OEF_SCHEMA_DIR_ENV) {
        ensure_oef_schema_files_exist(&configured)?;
        return Ok(configured);
    }

    let schema_dir = schema_cache_base_dir()
        .map_err(OefSchemaValidationError::SchemaUnavailable)?
        .join("opengroup")
        .join("archimate")
        .join("3.1");
    std::fs::create_dir_all(&schema_dir).map_err(|error| {
        OefSchemaValidationError::SchemaUnavailable(format!(
            "failed to create OEF schema cache directory {}: {error}",
            schema_dir.display()
        ))
    })?;

    for file_name in OFFICIAL_OEF_SCHEMA_FILES {
        let url = format!("{OEF_SCHEMA_BASE_URL}/{file_name}");
        ensure_cached_schema_file(&schema_dir.join(file_name), &url, "official OEF schema")?;
    }
    Ok(schema_dir)
}

fn ensure_oef_schema_files_exist(schema_dir: &Path) -> Result<(), OefSchemaValidationError> {
    for file_name in OFFICIAL_OEF_SCHEMA_FILES {
        let schema_path = schema_dir.join(file_name);
        if !is_non_empty_file(&schema_path) {
            return Err(OefSchemaValidationError::SchemaUnavailable(format!(
                "official OEF schema file {} is missing or empty; provide all ArchiMate 3.1 OEF XSD files or unset {OEF_SCHEMA_DIR_ENV} to allow cache download",
                schema_path.display()
            )));
        }
    }
    Ok(())
}

fn ensure_cached_schema_file(
    schema_path: &Path,
    url: &str,
    description: &str,
) -> Result<(), OefSchemaValidationError> {
    if is_non_empty_file(schema_path) {
        return Ok(());
    }

    let parent = schema_path.parent().ok_or_else(|| {
        OefSchemaValidationError::SchemaUnavailable(format!(
            "schema cache path {} has no parent directory",
            schema_path.display()
        ))
    })?;
    let temp = tempfile::NamedTempFile::new_in(parent).map_err(|error| {
        OefSchemaValidationError::SchemaUnavailable(format!(
            "failed to prepare temporary {description} download in {}: {error}",
            parent.display()
        ))
    })?;

    let output = Command::new(SCHEMA_FETCHER)
        .args([
            "--location",
            "--fail",
            "--silent",
            "--show-error",
            url,
            "--output",
        ])
        .arg(temp.path())
        .output()
        .map_err(|error| {
            OefSchemaValidationError::SchemaUnavailable(format!(
                "failed to start {SCHEMA_FETCHER} to download {description} from {url}: {error}"
            ))
        })?;
    if !output.status.success() {
        return Err(OefSchemaValidationError::SchemaUnavailable(format!(
            "failed to download {description} from {url}: {}",
            validation_output_details(&output.stdout, &output.stderr, output.status)
        )));
    }
    if !is_non_empty_file(temp.path()) {
        return Err(OefSchemaValidationError::SchemaUnavailable(format!(
            "downloaded {description} from {url} was empty"
        )));
    }

    match temp.persist(schema_path) {
        Ok(_) => Ok(()),
        Err(error) if is_non_empty_file(schema_path) => Ok(()),
        Err(error) => Err(OefSchemaValidationError::SchemaUnavailable(format!(
            "failed to store {description} in {}: {}",
            schema_path.display(),
            error.error
        ))),
    }
}

fn schema_cache_base_dir() -> Result<PathBuf, String> {
    if let Some(configured) = non_empty_env_path(SCHEMA_CACHE_DIR_ENV) {
        return Ok(configured);
    }
    if let Some(configured) = non_empty_env_path("XDG_CACHE_HOME") {
        return Ok(configured.join("dediren").join("schemas"));
    }
    if let Some(configured) = non_empty_env_path("LOCALAPPDATA") {
        return Ok(configured.join("dediren").join("schemas"));
    }
    if let Some(home) = non_empty_env_path("HOME") {
        return Ok(home.join(".cache").join("dediren").join("schemas"));
    }
    Err(format!(
        "cannot determine schema cache directory; set {SCHEMA_CACHE_DIR_ENV} or {OEF_SCHEMA_DIR_ENV}"
    ))
}

fn non_empty_env_path(name: &str) -> Option<PathBuf> {
    std::env::var_os(name)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
}

fn is_non_empty_file(path: &Path) -> bool {
    path.metadata()
        .map(|metadata| metadata.is_file() && metadata.len() > 0)
        .unwrap_or(false)
}

fn validation_output_details(
    stdout: &[u8],
    stderr: &[u8],
    status: std::process::ExitStatus,
) -> String {
    let mut details = String::from_utf8_lossy(stderr).trim().to_string();
    let stdout = String::from_utf8_lossy(stdout);
    let stdout = stdout.trim();
    if !stdout.is_empty() {
        if !details.is_empty() {
            details.push('\n');
        }
        details.push_str(stdout);
    }
    if details.is_empty() {
        details = format!("{SCHEMA_FETCHER} exited with status {status}");
    }
    details
}

fn write_text_element(
    writer: &mut Writer<Cursor<Vec<u8>>>,
    name: &str,
    text: &str,
) -> anyhow::Result<()> {
    let mut start = BytesStart::new(name);
    start.push_attribute(("xml:lang", "en"));
    writer.write_event(Event::Start(start))?;
    writer.write_event(Event::Text(BytesText::new(text)))?;
    writer.write_event(Event::End(BytesEnd::new(name)))?;
    Ok(())
}

fn validate_oef_policy_schema(value: &serde_json::Value) -> Result<(), String> {
    let schema: serde_json::Value = serde_json::from_str(include_str!(
        "../../../schemas/oef-export-policy.schema.json"
    ))
    .map_err(|error| error.to_string())?;
    let validator = jsonschema::validator_for(&schema).map_err(|error| error.to_string())?;
    validator.validate(value).map_err(|error| error.to_string())
}

fn validate_archimate_types(request: &OefExportInput) -> Result<(), ArchimateTypeValidationError> {
    let mut node_types = BTreeMap::new();

    for (index, node) in request.source.nodes.iter().enumerate() {
        dediren_archimate::validate_element_type(
            &node.node_type,
            format!("$.source.nodes[{index}].type"),
        )?;
        node_types.insert(node.id.as_str(), node.node_type.as_str());
    }
    for (index, relationship) in request.source.relationships.iter().enumerate() {
        dediren_archimate::validate_relationship_type(
            &relationship.relationship_type,
            format!("$.source.relationships[{index}].type"),
        )?;

        let Some(source_type) = node_types.get(relationship.source.as_str()) else {
            continue;
        };
        let Some(target_type) = node_types.get(relationship.target.as_str()) else {
            continue;
        };

        dediren_archimate::validate_relationship_endpoint_types(
            &relationship.relationship_type,
            source_type,
            target_type,
            format!("$.source.relationships[{index}]"),
        )?;
    }
    Ok(())
}

fn validate_archimate_junction_semantics(
    request: &OefExportInput,
) -> Result<(), ArchimateJunctionValidationError> {
    let nodes = request
        .source
        .nodes
        .iter()
        .enumerate()
        .map(|(index, node)| JunctionValidationNode {
            id: node.id.clone(),
            node_type: node.node_type.clone(),
            path: format!("$.source.nodes[{index}]"),
        })
        .collect::<Vec<_>>();
    let relationships = request
        .source
        .relationships
        .iter()
        .map(|relationship| JunctionValidationRelationship {
            relationship_type: relationship.relationship_type.clone(),
            source: relationship.source.clone(),
            target: relationship.target.clone(),
        })
        .collect::<Vec<_>>();

    dediren_archimate::validate_junction_relationship_semantics(&nodes, &relationships)
}

#[derive(Debug)]
struct GroupSemanticValidationError {
    code: &'static str,
    path: String,
    message: String,
}

fn validate_archimate_group_semantics(
    request: &OefExportInput,
) -> Result<(), GroupSemanticValidationError> {
    let source_nodes_by_id: BTreeMap<_, _> = request
        .source
        .nodes
        .iter()
        .map(|node| (node.id.as_str(), node))
        .collect();
    for (index, group) in request.layout_result.groups.iter().enumerate() {
        let Some(source_id) = semantic_group_source_id(group) else {
            continue;
        };
        let Some(source_node) = source_nodes_by_id.get(source_id) else {
            continue;
        };
        if source_node.node_type != "Grouping" {
            return Err(GroupSemanticValidationError {
                code: "DEDIREN_ARCHIMATE_GROUP_SOURCE_NOT_GROUPING",
                path: format!("$.layout_result.groups[{index}].provenance"),
                message: format!(
                    "layout group {} semantic source {} has ArchiMate type {}, expected Grouping",
                    group.id, source_id, source_node.node_type
                ),
            });
        }
    }
    Ok(())
}

fn semantic_group_source_id(group: &LaidOutGroup) -> Option<&str> {
    match group.provenance.as_ref() {
        Some(provenance) if provenance.visual_only => None,
        Some(provenance) => provenance.semantic_source_id(),
        None => Some(group.source_id.as_str()),
    }
}

fn exit_with_archimate_type_error(error: ArchimateTypeValidationError) -> ! {
    exit_with_diagnostic(error.code(), &error.message(), Some(error.path));
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

fn format_number(value: f64) -> String {
    format!("{:.0}", value.round())
}

#[derive(Default)]
struct IdentifierMap {
    used: HashSet<String>,
}

impl IdentifierMap {
    fn oef_id(&mut self, prefix: &str, value: &str) -> String {
        let base = format!("id-{}-{}", slug(prefix), slug(value));
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
