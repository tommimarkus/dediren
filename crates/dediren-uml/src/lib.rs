use std::collections::HashMap;

use dediren_contracts::{GenericGraphPluginData, GenericGraphViewKind, SourceDocument};
use serde_json::Value;
use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UmlTypeKind {
    Element,
    Relationship,
    RelationshipEndpoint,
    Multiplicity,
    ViewKind,
}

#[derive(Debug, Clone, PartialEq, Eq, Error)]
#[error("{kind:?} validation failed for {value} at {path}")]
pub struct UmlValidationError {
    pub kind: UmlTypeKind,
    pub value: String,
    pub path: String,
}

impl UmlValidationError {
    pub fn code(&self) -> &'static str {
        match self.kind {
            UmlTypeKind::Element => "DEDIREN_UML_ELEMENT_TYPE_UNSUPPORTED",
            UmlTypeKind::Relationship => "DEDIREN_UML_RELATIONSHIP_TYPE_UNSUPPORTED",
            UmlTypeKind::RelationshipEndpoint => "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
            UmlTypeKind::Multiplicity => "DEDIREN_UML_MULTIPLICITY_INVALID",
            UmlTypeKind::ViewKind => "DEDIREN_UML_VIEW_KIND_UNSUPPORTED_ELEMENT",
        }
    }

    pub fn message(&self) -> String {
        match self.kind {
            UmlTypeKind::Element => format!("unsupported UML element type: {}", self.value),
            UmlTypeKind::Relationship => {
                format!("unsupported UML relationship type: {}", self.value)
            }
            UmlTypeKind::RelationshipEndpoint => {
                format!("unsupported UML relationship endpoint: {}", self.value)
            }
            UmlTypeKind::Multiplicity => format!("invalid UML multiplicity: {}", self.value),
            UmlTypeKind::ViewKind => {
                format!("view contains unsupported UML element: {}", self.value)
            }
        }
    }
}

pub fn validate_source(
    source: &SourceDocument,
    plugin_data: &GenericGraphPluginData,
) -> Result<(), UmlValidationError> {
    for (node_index, node) in source.nodes.iter().enumerate() {
        validate_element_type(&node.node_type, format!("$.nodes[{node_index}].type"))?;
        validate_node_multiplicities(node_index, node.properties.get("uml"))?;
    }

    let node_types = source
        .nodes
        .iter()
        .map(|node| (node.id.as_str(), node.node_type.as_str()))
        .collect::<HashMap<_, _>>();

    for (relationship_index, relationship) in source.relationships.iter().enumerate() {
        validate_relationship_type(
            &relationship.relationship_type,
            format!("$.relationships[{relationship_index}].type"),
        )?;
        validate_relationship_multiplicities(
            relationship_index,
            relationship.properties.get("uml"),
        )?;

        let Some(source_type) = node_types.get(relationship.source.as_str()) else {
            continue;
        };
        let Some(target_type) = node_types.get(relationship.target.as_str()) else {
            continue;
        };

        validate_relationship_endpoint_types(
            &relationship.relationship_type,
            source_type,
            target_type,
            format!("$.relationships[{relationship_index}]"),
        )?;
    }

    for (view_index, view) in plugin_data.views.iter().enumerate() {
        let Some(view_kind) = view.kind else {
            continue;
        };

        for (node_index, node_id) in view.nodes.iter().enumerate() {
            let Some(node_type) = node_types.get(node_id.as_str()) else {
                continue;
            };

            validate_view_node_type(
                view_kind,
                node_type,
                format!("$.plugins.generic-graph.views[{view_index}].nodes[{node_index}]"),
            )?;
        }
    }

    Ok(())
}

pub fn validate_element_type(
    value: &str,
    path: impl Into<String>,
) -> Result<(), UmlValidationError> {
    if is_structural_type(value) || is_activity_type(value) {
        Ok(())
    } else {
        Err(UmlValidationError {
            kind: UmlTypeKind::Element,
            value: value.to_string(),
            path: path.into(),
        })
    }
}

pub fn validate_relationship_type(
    value: &str,
    path: impl Into<String>,
) -> Result<(), UmlValidationError> {
    if RELATIONSHIP_TYPES.contains(&value) {
        Ok(())
    } else {
        Err(UmlValidationError {
            kind: UmlTypeKind::Relationship,
            value: value.to_string(),
            path: path.into(),
        })
    }
}

pub fn validate_relationship_endpoint_types(
    relationship_type: &str,
    source_type: &str,
    target_type: &str,
    path: impl Into<String>,
) -> Result<(), UmlValidationError> {
    let endpoints_supported = if STRUCTURAL_RELATIONSHIP_TYPES.contains(&relationship_type) {
        is_structural_type(source_type) && is_structural_type(target_type)
    } else if ACTIVITY_FLOW_TYPES.contains(&relationship_type) {
        is_activity_type(source_type) && is_activity_type(target_type)
    } else {
        false
    };

    if endpoints_supported {
        Ok(())
    } else {
        Err(UmlValidationError {
            kind: UmlTypeKind::RelationshipEndpoint,
            value: format!("{relationship_type}: {source_type} -> {target_type}"),
            path: path.into(),
        })
    }
}

pub fn validate_multiplicity(
    value: &str,
    path: impl Into<String>,
) -> Result<(), UmlValidationError> {
    if is_valid_multiplicity(value) {
        Ok(())
    } else {
        Err(UmlValidationError {
            kind: UmlTypeKind::Multiplicity,
            value: value.to_string(),
            path: path.into(),
        })
    }
}

pub fn is_compact_activity_node_type(value: &str) -> bool {
    COMPACT_ACTIVITY_NODE_TYPES.contains(&value)
}

fn validate_node_multiplicities(
    node_index: usize,
    uml_properties: Option<&Value>,
) -> Result<(), UmlValidationError> {
    let Some(attributes) = uml_properties
        .and_then(Value::as_object)
        .and_then(|uml| uml.get("attributes"))
        .and_then(Value::as_array)
    else {
        return Ok(());
    };

    for (attribute_index, attribute) in attributes.iter().enumerate() {
        let Some(multiplicity) = attribute
            .as_object()
            .and_then(|attribute| attribute.get("multiplicity"))
        else {
            continue;
        };
        validate_multiplicity_value(
            multiplicity,
            format!(
                "$.nodes[{node_index}].properties.uml.attributes[{attribute_index}].multiplicity"
            ),
        )?;
    }

    Ok(())
}

fn validate_relationship_multiplicities(
    relationship_index: usize,
    uml_properties: Option<&Value>,
) -> Result<(), UmlValidationError> {
    let Some(uml) = uml_properties.and_then(Value::as_object) else {
        return Ok(());
    };

    for field in ["source_multiplicity", "target_multiplicity"] {
        let Some(multiplicity) = uml.get(field) else {
            continue;
        };
        validate_multiplicity_value(
            multiplicity,
            format!("$.relationships[{relationship_index}].properties.uml.{field}"),
        )?;
    }

    Ok(())
}

fn validate_multiplicity_value(
    value: &Value,
    path: impl Into<String>,
) -> Result<(), UmlValidationError> {
    match value.as_str() {
        Some(value) => validate_multiplicity(value, path),
        None => Err(UmlValidationError {
            kind: UmlTypeKind::Multiplicity,
            value: value.to_string(),
            path: path.into(),
        }),
    }
}

fn validate_view_node_type(
    view_kind: GenericGraphViewKind,
    node_type: &str,
    path: impl Into<String>,
) -> Result<(), UmlValidationError> {
    let supported = match view_kind {
        GenericGraphViewKind::Generic | GenericGraphViewKind::Archimate => true,
        GenericGraphViewKind::UmlClass | GenericGraphViewKind::UmlData => {
            is_structural_type(node_type)
        }
        GenericGraphViewKind::UmlActivity => is_activity_type(node_type),
    };

    if supported {
        Ok(())
    } else {
        Err(UmlValidationError {
            kind: UmlTypeKind::ViewKind,
            value: format!("{} in {}", node_type, view_kind.as_str()),
            path: path.into(),
        })
    }
}

fn is_valid_multiplicity(value: &str) -> bool {
    if value == "*" || is_non_negative_integer(value) {
        return true;
    }

    let Some((lower, upper)) = value.split_once("..") else {
        return false;
    };

    if !is_non_negative_integer(lower) {
        return false;
    }

    if upper == "*" {
        return true;
    }

    is_non_negative_integer(upper) && numeric_string_lte(lower, upper)
}

fn is_non_negative_integer(value: &str) -> bool {
    !value.is_empty() && value.bytes().all(|byte| byte.is_ascii_digit())
}

fn numeric_string_lte(lower: &str, upper: &str) -> bool {
    let lower = lower.trim_start_matches('0');
    let upper = upper.trim_start_matches('0');
    let lower = if lower.is_empty() { "0" } else { lower };
    let upper = if upper.is_empty() { "0" } else { upper };

    lower.len() < upper.len() || (lower.len() == upper.len() && lower <= upper)
}

fn is_structural_type(value: &str) -> bool {
    STRUCTURAL_TYPES.contains(&value)
}

fn is_activity_type(value: &str) -> bool {
    ACTIVITY_TYPES.contains(&value)
}

trait GenericGraphViewKindName {
    fn as_str(&self) -> &'static str;
}

impl GenericGraphViewKindName for GenericGraphViewKind {
    fn as_str(&self) -> &'static str {
        match *self {
            Self::Generic => "generic",
            Self::Archimate => "archimate",
            Self::UmlClass => "uml-class",
            Self::UmlData => "uml-data",
            Self::UmlActivity => "uml-activity",
        }
    }
}

pub const STRUCTURAL_TYPES: &[&str] = &["Package", "Class", "Interface", "DataType", "Enumeration"];

pub const ACTIVITY_TYPES: &[&str] = &[
    "Activity",
    "Action",
    "InitialNode",
    "ActivityFinalNode",
    "DecisionNode",
    "MergeNode",
    "ForkNode",
    "JoinNode",
    "ObjectNode",
];

const COMPACT_ACTIVITY_NODE_TYPES: &[&str] = &[
    "InitialNode",
    "ActivityFinalNode",
    "DecisionNode",
    "MergeNode",
    "ForkNode",
    "JoinNode",
];

pub const RELATIONSHIP_TYPES: &[&str] = &[
    "Association",
    "Composition",
    "Aggregation",
    "Generalization",
    "Realization",
    "Dependency",
    "ControlFlow",
    "ObjectFlow",
];

const STRUCTURAL_RELATIONSHIP_TYPES: &[&str] = &[
    "Association",
    "Composition",
    "Aggregation",
    "Generalization",
    "Realization",
    "Dependency",
];

const ACTIVITY_FLOW_TYPES: &[&str] = &["ControlFlow", "ObjectFlow"];
