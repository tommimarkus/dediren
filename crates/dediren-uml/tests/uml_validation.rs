use std::{fs, path::PathBuf};

use dediren_contracts::{GenericGraphPluginData, SourceDocument};
use serde_json::{json, Value};

fn fixture_path(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .ancestors()
        .nth(2)
        .expect("crate manifest should be below workspace root")
        .join("fixtures/source")
        .join(name)
}

fn load_uml_fixture() -> (SourceDocument, GenericGraphPluginData) {
    let fixture = fs::read_to_string(fixture_path("valid-uml-basic.json")).unwrap();
    let source: SourceDocument = serde_json::from_str(&fixture).unwrap();
    let data = serde_json::from_value(
        source
            .plugins
            .get("generic-graph")
            .expect("fixture should include generic graph plugin data")
            .clone(),
    )
    .unwrap();

    (source, data)
}

#[test]
fn validates_uml_fixture() {
    let (source, data) = load_uml_fixture();

    dediren_uml::validate_source(&source, &data).unwrap();
}

#[test]
fn exposes_public_uml_vocabulary() {
    assert_eq!(
        dediren_uml::STRUCTURAL_TYPES,
        ["Package", "Class", "Interface", "DataType", "Enumeration"]
    );
    assert_eq!(
        dediren_uml::ACTIVITY_TYPES,
        [
            "Activity",
            "Action",
            "InitialNode",
            "ActivityFinalNode",
            "DecisionNode",
            "MergeNode",
            "ForkNode",
            "JoinNode",
            "ObjectNode"
        ]
    );
    assert_eq!(
        dediren_uml::RELATIONSHIP_TYPES,
        [
            "Association",
            "Composition",
            "Aggregation",
            "Generalization",
            "Realization",
            "Dependency",
            "ControlFlow",
            "ObjectFlow"
        ]
    );
}

#[test]
fn rejects_unknown_uml_node_type() {
    let (mut source, data) = load_uml_fixture();
    source.nodes[0].node_type = "Service".to_string();

    let error = dediren_uml::validate_source(&source, &data).unwrap_err();

    assert_eq!(error.code(), "DEDIREN_UML_ELEMENT_TYPE_UNSUPPORTED");
    assert_eq!(error.path, "$.nodes[0].type");
}

#[test]
fn rejects_invalid_uml_relationship_endpoint() {
    let (mut source, data) = load_uml_fixture();
    let relationship = source
        .relationships
        .iter_mut()
        .find(|relationship| relationship.id == "order-has-lines")
        .unwrap();
    relationship.source = "initial-submit".to_string();

    let error = dediren_uml::validate_source(&source, &data).unwrap_err();

    assert_eq!(
        error.code(),
        "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED"
    );
}

#[test]
fn rejects_unknown_relationship_type_in_endpoint_helper() {
    let error = dediren_uml::validate_relationship_endpoint_types(
        "Unknown",
        "Class",
        "Class",
        "$.relationships[0]",
    )
    .unwrap_err();

    assert_eq!(
        error.code(),
        "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED"
    );
}

#[test]
fn rejects_invalid_multiplicity() {
    let (mut source, data) = load_uml_fixture();
    source.relationships[0]
        .properties
        .entry("uml".to_string())
        .or_insert_with(|| json!({}))
        .as_object_mut()
        .unwrap()
        .insert(
            "target_multiplicity".to_string(),
            Value::String("many".to_string()),
        );

    let error = dediren_uml::validate_source(&source, &data).unwrap_err();

    assert_eq!(error.code(), "DEDIREN_UML_MULTIPLICITY_INVALID");
}

#[test]
fn rejects_inverted_finite_multiplicity_range() {
    let error = dediren_uml::validate_multiplicity("2..1", "$.multiplicity").unwrap_err();

    assert_eq!(error.code(), "DEDIREN_UML_MULTIPLICITY_INVALID");
}

#[test]
fn accepts_valid_multiplicities() {
    for value in ["1..*", "0..1", "1", "*"] {
        dediren_uml::validate_multiplicity(value, "$.multiplicity").unwrap();
    }
}

#[test]
fn rejects_class_view_with_activity_node() {
    let (source, mut data) = load_uml_fixture();
    data.views[0].nodes.push("action-submit".to_string());

    let error = dediren_uml::validate_source(&source, &data).unwrap_err();

    assert_eq!(error.code(), "DEDIREN_UML_VIEW_KIND_UNSUPPORTED_ELEMENT");
}
