use dediren_archimate::{
    relationship_endpoint_triples, validate_relationship_endpoint_types, ELEMENT_TYPES,
    RELATIONSHIP_TYPES,
};

const REQUIRED_ALLOWED_RELATIONSHIPS: &[(&str, &str, &str)] = &[
    ("Realization", "ApplicationComponent", "ApplicationService"),
    ("Triggering", "BusinessProcess", "BusinessProcess"),
    ("Serving", "ApplicationService", "ApplicationComponent"),
    ("Access", "ApplicationFunction", "DataObject"),
    ("Association", "BusinessActor", "DataObject"),
];

const REQUIRED_REJECTED_RELATIONSHIPS: &[(&str, &str, &str)] = &[
    ("Realization", "ApplicationService", "ApplicationComponent"),
    ("Triggering", "BusinessActor", "DataObject"),
    ("Serving", "DataObject", "ApplicationService"),
    ("Access", "ApplicationComponent", "ApplicationFunction"),
    ("Flow", "BusinessObject", "ApplicationComponent"),
];

#[test]
fn relationship_connector_types_are_supported_element_types() {
    assert!(ELEMENT_TYPES.contains(&"AndJunction"));
    assert!(ELEMENT_TYPES.contains(&"OrJunction"));
}

#[test]
fn accepts_current_archimate_oef_fixture_relationship() {
    validate_relationship_endpoint_types(
        "Realization",
        "ApplicationComponent",
        "ApplicationService",
        "$.relationships[0]",
    )
    .expect("ApplicationComponent should realize ApplicationService in ArchiMate 3.2");
}

#[test]
fn relationship_connector_endpoints_are_allowed_for_supported_relationship_types() {
    validate_relationship_endpoint_types(
        "Flow",
        "ApplicationComponent",
        "AndJunction",
        "$.relationships[0]",
    )
    .expect("relationships may target an ArchiMate relationship connector");

    validate_relationship_endpoint_types(
        "Flow",
        "AndJunction",
        "ApplicationService",
        "$.relationships[1]",
    )
    .expect("relationships may source from an ArchiMate relationship connector");
}

#[test]
fn rejects_type_valid_but_endpoint_invalid_relationship() {
    let error = validate_relationship_endpoint_types(
        "Realization",
        "ApplicationService",
        "ApplicationComponent",
        "$.relationships[0]",
    )
    .expect_err("reverse realization should be rejected");

    assert_eq!(
        error.code(),
        "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED"
    );
    assert_eq!(error.path, "$.relationships[0]");
    assert!(error.message().contains("ApplicationService"));
    assert!(error.message().contains("Realization"));
    assert!(error.message().contains("ApplicationComponent"));
}

#[test]
fn rejects_dynamic_relationship_between_non_behavior_concepts() {
    let error = validate_relationship_endpoint_types(
        "Triggering",
        "BusinessActor",
        "DataObject",
        "$.relationships[1]",
    )
    .expect_err("Triggering should not connect active structure to passive structure");

    assert_eq!(
        error.code(),
        "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED"
    );
    assert_eq!(error.path, "$.relationships[1]");
}

#[test]
fn accepts_same_behavior_triggering_relationship() {
    validate_relationship_endpoint_types(
        "Triggering",
        "BusinessProcess",
        "BusinessProcess",
        "$.relationships[2]",
    )
    .expect("BusinessProcess should trigger BusinessProcess in ArchiMate 3.2");
}

#[test]
fn accepts_curated_archimate_32_relationship_oracle() {
    for (relationship_type, source_type, target_type) in REQUIRED_ALLOWED_RELATIONSHIPS {
        validate_relationship_endpoint_types(
            relationship_type,
            source_type,
            target_type,
            "$.relationships[*]",
        )
        .unwrap_or_else(|error| {
            panic!(
                "expected {source_type} -{relationship_type}-> {target_type} to be allowed, got {error:?}"
            )
        });
    }
}

#[test]
fn rejects_curated_archimate_32_relationship_oracle() {
    for (relationship_type, source_type, target_type) in REQUIRED_REJECTED_RELATIONSHIPS {
        let error = validate_relationship_endpoint_types(
            relationship_type,
            source_type,
            target_type,
            "$.relationships[*]",
        )
        .unwrap_err();
        assert_eq!(
            error.code(),
            "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED"
        );
    }
}

#[test]
fn derived_relationship_triples_only_reference_supported_names() {
    let triples = relationship_endpoint_triples();
    assert!(
        triples.len() > ELEMENT_TYPES.len(),
        "derived rule table should contain many endpoint triples"
    );

    for triple in triples {
        assert!(
            ELEMENT_TYPES.contains(&triple.source_type),
            "unknown source type in derived triple: {}",
            triple.source_type
        );
        assert!(
            RELATIONSHIP_TYPES.contains(&triple.relationship_type),
            "unknown relationship type in derived triple: {}",
            triple.relationship_type
        );
        assert!(
            ELEMENT_TYPES.contains(&triple.target_type),
            "unknown target type in derived triple: {}",
            triple.target_type
        );
    }
}

#[test]
fn derived_relationship_triples_are_accepted_by_validator() {
    for triple in relationship_endpoint_triples() {
        validate_relationship_endpoint_types(
            triple.relationship_type,
            triple.source_type,
            triple.target_type,
            "$.relationships[*]",
        )
        .unwrap_or_else(|error| panic!("derived triple should validate: {triple:?}: {error:?}"));
    }
}

#[test]
fn derived_relationship_triples_cover_every_relationship_type() {
    let covered: std::collections::BTreeSet<&str> = relationship_endpoint_triples()
        .iter()
        .map(|triple| triple.relationship_type)
        .collect();
    for relationship_type in RELATIONSHIP_TYPES {
        assert!(
            covered.contains(relationship_type),
            "relationship type {relationship_type} should have at least one derived endpoint triple"
        );
    }
}

#[test]
fn derived_relationship_triples_are_unique() {
    let mut seen = std::collections::BTreeSet::new();
    for triple in relationship_endpoint_triples() {
        assert!(
            seen.insert((
                triple.source_type,
                triple.relationship_type,
                triple.target_type,
            )),
            "duplicate ArchiMate relationship endpoint triple: {:?}",
            triple
        );
    }
}
