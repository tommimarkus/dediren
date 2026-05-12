use dediren_archimate::{
    relationship_endpoint_triples, validate_relationship_endpoint_types, ELEMENT_TYPES,
    RELATIONSHIP_TYPES,
};

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
