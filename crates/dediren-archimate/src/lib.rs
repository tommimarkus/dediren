#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArchimateTypeKind {
    Element,
    Relationship,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArchimateTypeValidationError {
    pub kind: ArchimateTypeKind,
    pub value: String,
    pub path: String,
}

impl ArchimateTypeValidationError {
    pub fn code(&self) -> &'static str {
        match self.kind {
            ArchimateTypeKind::Element => "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED",
            ArchimateTypeKind::Relationship => "DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED",
        }
    }

    pub fn message(&self) -> String {
        match self.kind {
            ArchimateTypeKind::Element => {
                format!("unsupported ArchiMate element type: {}", self.value)
            }
            ArchimateTypeKind::Relationship => {
                format!("unsupported ArchiMate relationship type: {}", self.value)
            }
        }
    }
}

pub fn validate_element_type(
    value: &str,
    path: impl Into<String>,
) -> Result<(), ArchimateTypeValidationError> {
    if ELEMENT_TYPES.contains(&value) {
        Ok(())
    } else {
        Err(ArchimateTypeValidationError {
            kind: ArchimateTypeKind::Element,
            value: value.to_string(),
            path: path.into(),
        })
    }
}

pub fn validate_relationship_type(
    value: &str,
    path: impl Into<String>,
) -> Result<(), ArchimateTypeValidationError> {
    if RELATIONSHIP_TYPES.contains(&value) {
        Ok(())
    } else {
        Err(ArchimateTypeValidationError {
            kind: ArchimateTypeKind::Relationship,
            value: value.to_string(),
            path: path.into(),
        })
    }
}

pub const ELEMENT_TYPES: &[&str] = &[
    "Plateau",
    "WorkPackage",
    "Deliverable",
    "ImplementationEvent",
    "Gap",
    "Grouping",
    "Location",
    "Stakeholder",
    "Driver",
    "Assessment",
    "Goal",
    "Outcome",
    "Value",
    "Meaning",
    "Constraint",
    "Requirement",
    "Principle",
    "CourseOfAction",
    "Resource",
    "ValueStream",
    "Capability",
    "BusinessInterface",
    "BusinessCollaboration",
    "BusinessActor",
    "BusinessRole",
    "BusinessProcess",
    "BusinessService",
    "BusinessInteraction",
    "BusinessFunction",
    "BusinessEvent",
    "Product",
    "BusinessObject",
    "Contract",
    "Representation",
    "ApplicationInterface",
    "ApplicationCollaboration",
    "ApplicationComponent",
    "ApplicationService",
    "ApplicationInteraction",
    "ApplicationFunction",
    "ApplicationProcess",
    "ApplicationEvent",
    "DataObject",
    "TechnologyInterface",
    "TechnologyCollaboration",
    "Node",
    "SystemSoftware",
    "Device",
    "Facility",
    "Equipment",
    "Path",
    "TechnologyService",
    "TechnologyInteraction",
    "TechnologyFunction",
    "TechnologyProcess",
    "TechnologyEvent",
    "Artifact",
    "Material",
    "CommunicationNetwork",
    "DistributionNetwork",
];

pub const RELATIONSHIP_TYPES: &[&str] = &[
    "Composition",
    "Aggregation",
    "Assignment",
    "Realization",
    "Specialization",
    "Serving",
    "Access",
    "Influence",
    "Flow",
    "Triggering",
    "Association",
];
