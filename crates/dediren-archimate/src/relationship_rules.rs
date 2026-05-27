use crate::{
    is_relationship_connector_type, validate_element_type, validate_relationship_type,
    ArchimateTypeKind, ArchimateTypeValidationError,
};
use std::collections::{BTreeMap, BTreeSet};

// This module intentionally keeps endpoint validation to Dediren-owned product
// policy: curated supported examples plus explicit guard cases. It must not
// redistribute a complete third-party standard matrix.

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct RelationshipEndpointTriple {
    pub source_type: &'static str,
    pub relationship_type: &'static str,
    pub target_type: &'static str,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JunctionValidationNode {
    pub id: String,
    pub node_type: String,
    pub path: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JunctionValidationRelationship {
    pub relationship_type: String,
    pub source: String,
    pub target: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArchimateJunctionValidationError {
    pub code: &'static str,
    pub path: String,
    pub message: String,
}

pub fn validate_relationship_endpoint_types(
    relationship_type: &str,
    source_type: &str,
    target_type: &str,
    path: impl Into<String>,
) -> Result<(), ArchimateTypeValidationError> {
    let path = path.into();
    validate_relationship_type(relationship_type, path.clone())?;
    validate_element_type(source_type, path.clone())?;
    validate_element_type(target_type, path.clone())?;

    if is_relationship_connector_type(source_type) || is_relationship_connector_type(target_type) {
        return Ok(());
    }

    if is_rejected_relationship_endpoint(relationship_type, source_type, target_type) {
        Err(ArchimateTypeValidationError {
            kind: ArchimateTypeKind::RelationshipEndpoint,
            value: format!("{source_type} -[{relationship_type}]-> {target_type}"),
            path,
        })
    } else {
        Ok(())
    }
}

pub fn validate_junction_relationship_semantics(
    nodes: &[JunctionValidationNode],
    relationships: &[JunctionValidationRelationship],
) -> Result<(), ArchimateJunctionValidationError> {
    let node_types: BTreeMap<&str, &str> = nodes
        .iter()
        .map(|node| (node.id.as_str(), node.node_type.as_str()))
        .collect();
    let node_paths: BTreeMap<&str, &str> = nodes
        .iter()
        .map(|node| (node.id.as_str(), node.path.as_str()))
        .collect();

    for node in nodes {
        if !is_relationship_connector_type(&node.node_type) {
            continue;
        }

        let incident_relationships: Vec<_> = relationships
            .iter()
            .filter(|relationship| relationship.source == node.id || relationship.target == node.id)
            .filter(|relationship| !is_junction_containment_relationship(relationship, &node_types))
            .collect();

        let relationship_types: BTreeSet<_> = incident_relationships
            .iter()
            .map(|relationship| relationship.relationship_type.as_str())
            .collect();
        if relationship_types.len() > 1 {
            return Err(ArchimateJunctionValidationError {
                code: "DEDIREN_ARCHIMATE_JUNCTION_RELATIONSHIP_MIXED",
                path: node.path.clone(),
                message: format!(
                    "ArchiMate junction {} connects multiple relationship types: {}",
                    node.id,
                    relationship_types
                        .into_iter()
                        .collect::<Vec<_>>()
                        .join(", ")
                ),
            });
        }

        let has_incoming = incident_relationships
            .iter()
            .any(|relationship| relationship.target == node.id);
        let has_outgoing = incident_relationships
            .iter()
            .any(|relationship| relationship.source == node.id);
        if !has_incoming || !has_outgoing {
            return Err(ArchimateJunctionValidationError {
                code: "DEDIREN_ARCHIMATE_JUNCTION_DIRECTION_INCOMPLETE",
                path: node.path.clone(),
                message: format!(
                    "ArchiMate junction {} must connect at least one incoming and at least one outgoing relationship",
                    node.id
                ),
            });
        }
    }

    for relationship in relationships
        .iter()
        .filter(|relationship| !is_junction_containment_relationship(relationship, &node_types))
    {
        let Some(source_type) = node_types.get(relationship.source.as_str()) else {
            continue;
        };
        let Some(target_type) = node_types.get(relationship.target.as_str()) else {
            continue;
        };
        if is_relationship_connector_type(source_type)
            || !is_relationship_connector_type(target_type)
        {
            continue;
        }

        let path = node_paths
            .get(relationship.target.as_str())
            .copied()
            .unwrap_or("$");
        let mut visited = BTreeSet::new();
        validate_junction_reachable_targets(
            relationship.relationship_type.as_str(),
            source_type,
            relationship.target.as_str(),
            path,
            relationships,
            &node_types,
            &mut visited,
        )?;
    }

    Ok(())
}

fn validate_junction_reachable_targets(
    relationship_type: &str,
    source_type: &str,
    junction_id: &str,
    path: &str,
    relationships: &[JunctionValidationRelationship],
    node_types: &BTreeMap<&str, &str>,
    visited: &mut BTreeSet<String>,
) -> Result<(), ArchimateJunctionValidationError> {
    if !visited.insert(junction_id.to_string()) {
        return Ok(());
    }

    for relationship in relationships.iter().filter(|relationship| {
        relationship.source == junction_id
            && relationship.relationship_type == relationship_type
            && !is_junction_containment_relationship(relationship, node_types)
    }) {
        let Some(target_type) = node_types.get(relationship.target.as_str()) else {
            continue;
        };

        if is_relationship_connector_type(target_type) {
            validate_junction_reachable_targets(
                relationship_type,
                source_type,
                relationship.target.as_str(),
                path,
                relationships,
                node_types,
                visited,
            )?;
            continue;
        }

        validate_relationship_endpoint_types(relationship_type, source_type, target_type, path)
            .map_err(junction_endpoint_error)?;
    }

    Ok(())
}

fn junction_endpoint_error(
    error: ArchimateTypeValidationError,
) -> ArchimateJunctionValidationError {
    ArchimateJunctionValidationError {
        code: error.code(),
        path: error.path.clone(),
        message: error.message(),
    }
}

fn is_junction_containment_relationship(
    relationship: &JunctionValidationRelationship,
    node_types: &BTreeMap<&str, &str>,
) -> bool {
    if !matches!(
        relationship.relationship_type.as_str(),
        "Aggregation" | "Composition"
    ) {
        return false;
    }

    let Some(source_type) = node_types.get(relationship.source.as_str()) else {
        return false;
    };
    let Some(target_type) = node_types.get(relationship.target.as_str()) else {
        return false;
    };

    (is_relationship_connector_type(source_type) && is_junction_container_type(target_type))
        || (is_relationship_connector_type(target_type) && is_junction_container_type(source_type))
}

fn is_junction_container_type(node_type: &str) -> bool {
    matches!(node_type, "Plateau" | "Grouping" | "Location")
}

pub fn relationship_endpoint_triples() -> Vec<RelationshipEndpointTriple> {
    CURATED_RELATIONSHIP_ENDPOINT_TRIPLES.to_vec()
}

fn is_rejected_relationship_endpoint(
    relationship_type: &str,
    source_type: &str,
    target_type: &str,
) -> bool {
    REJECTED_RELATIONSHIP_ENDPOINT_TRIPLES.iter().any(|triple| {
        triple.relationship_type == relationship_type
            && triple.source_type == source_type
            && triple.target_type == target_type
    })
}

const CURATED_RELATIONSHIP_ENDPOINT_TRIPLES: &[RelationshipEndpointTriple] = &[
    RelationshipEndpointTriple {
        source_type: "Grouping",
        relationship_type: "Composition",
        target_type: "ApplicationComponent",
    },
    RelationshipEndpointTriple {
        source_type: "Grouping",
        relationship_type: "Aggregation",
        target_type: "ApplicationService",
    },
    RelationshipEndpointTriple {
        source_type: "BusinessRole",
        relationship_type: "Assignment",
        target_type: "BusinessProcess",
    },
    RelationshipEndpointTriple {
        source_type: "ApplicationComponent",
        relationship_type: "Realization",
        target_type: "ApplicationService",
    },
    RelationshipEndpointTriple {
        source_type: "ApplicationComponent",
        relationship_type: "Specialization",
        target_type: "ApplicationComponent",
    },
    RelationshipEndpointTriple {
        source_type: "ApplicationService",
        relationship_type: "Serving",
        target_type: "ApplicationComponent",
    },
    RelationshipEndpointTriple {
        source_type: "ApplicationComponent",
        relationship_type: "Serving",
        target_type: "BusinessActor",
    },
    RelationshipEndpointTriple {
        source_type: "ApplicationFunction",
        relationship_type: "Access",
        target_type: "DataObject",
    },
    RelationshipEndpointTriple {
        source_type: "ApplicationService",
        relationship_type: "Access",
        target_type: "DataObject",
    },
    RelationshipEndpointTriple {
        source_type: "ApplicationComponent",
        relationship_type: "Access",
        target_type: "DataObject",
    },
    RelationshipEndpointTriple {
        source_type: "Goal",
        relationship_type: "Influence",
        target_type: "Requirement",
    },
    RelationshipEndpointTriple {
        source_type: "ApplicationComponent",
        relationship_type: "Flow",
        target_type: "ApplicationService",
    },
    RelationshipEndpointTriple {
        source_type: "ApplicationService",
        relationship_type: "Flow",
        target_type: "ApplicationService",
    },
    RelationshipEndpointTriple {
        source_type: "BusinessProcess",
        relationship_type: "Triggering",
        target_type: "BusinessProcess",
    },
    RelationshipEndpointTriple {
        source_type: "ApplicationService",
        relationship_type: "Triggering",
        target_type: "ApplicationComponent",
    },
    RelationshipEndpointTriple {
        source_type: "BusinessActor",
        relationship_type: "Association",
        target_type: "DataObject",
    },
];

const REJECTED_RELATIONSHIP_ENDPOINT_TRIPLES: &[RelationshipEndpointTriple] = &[
    RelationshipEndpointTriple {
        source_type: "ApplicationService",
        relationship_type: "Realization",
        target_type: "ApplicationComponent",
    },
    RelationshipEndpointTriple {
        source_type: "BusinessActor",
        relationship_type: "Triggering",
        target_type: "DataObject",
    },
    RelationshipEndpointTriple {
        source_type: "DataObject",
        relationship_type: "Serving",
        target_type: "ApplicationService",
    },
    RelationshipEndpointTriple {
        source_type: "ApplicationComponent",
        relationship_type: "Access",
        target_type: "ApplicationFunction",
    },
    RelationshipEndpointTriple {
        source_type: "BusinessObject",
        relationship_type: "Flow",
        target_type: "ApplicationComponent",
    },
];
