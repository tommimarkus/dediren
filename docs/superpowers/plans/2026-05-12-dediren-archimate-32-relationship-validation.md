# ArchiMate 3.2 Relationship Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validate ArchiMate 3.2 relationship endpoint legality for ArchiMate renders and OEF export, not only the element and relationship type names.

**Architecture:** Keep the rule knowledge in `dediren-archimate`, because domain vocabulary and ArchiMate relationship semantics must not move into `dediren-core`. Represent the ArchiMate 3.2 relationship table as classified element profiles plus compact relationship rules and explicit exceptions, then expose one shared validation function used by `generic-graph`, `svg-render`, and `archimate-oef`. The effective validation space is 60 element types x 11 relationship types x 60 element types, but the maintained data should be category-driven and generated into triples for tests.

**Tech Stack:** Rust 1.93, Cargo workspace, `dediren-archimate`, first-party executable plugins, `assert_cmd`, `predicates`, JSON command envelopes.

---

## Scope

Implement ArchiMate 3.2 relationship endpoint validation for:

- `generic-graph project --target render-metadata` when the source is ArchiMate.
- `svg-render render` when `render_metadata.semantic_profile == "archimate"` and enough layout/metadata exists to connect each edge to source and target node types.
- `archimate-oef export` before XML generation.

Do not add this to generic `dediren validate`; core source validation remains vocabulary-agnostic.

Do not implement ArchiMate 4. This repository currently documents and reports `archimate_version: "3.2"` for OEF export. ArchiMate 4 being available from The Open Group is intentionally out of scope for this slice.

Do not copy a full licensed standard table into README, tests, or comments. Use the official ArchiMate 3.2 Specification as the source for implementation, encode a compact rule model, and cite the standard version in code comments and docs without reproducing the full table text.

## Authoritative Source Requirement

Before editing rule data, verify against authoritative ArchiMate 3.2 material:

- Primary source: The Open Group ArchiMate 3.2 Specification, relationship chapter and relationship tables/Appendix B equivalent.
- Official access route: `https://pubs.opengroup.org/architecture/archimate32-doc/`, or the licensed ArchiMate 3.2 download from The Open Group Library.
- Repository target version: ArchiMate 3.2 only.

Execution gate:

- If the official ArchiMate 3.2 relationship table cannot be accessed in the current environment, stop before writing relationship rules.
- Do not derive the matrix from Visual Paradigm, Linked.Archi, Archi, PlantUML, examples, or memory. Those may be used only as secondary sanity checks after the official source has been checked.
- Record the checked source and date in a short comment at the top of `crates/dediren-archimate/src/relationship_rules.rs`.

## File Structure

- Modify: `crates/dediren-archimate/src/lib.rs`
  - Keep public type-name validation.
  - Re-export the new relationship endpoint validation API.
  - Add a new endpoint error kind and diagnostic code.
- Create: `crates/dediren-archimate/src/relationship_rules.rs`
  - Own element classifications, relationship selectors, explicit exceptions, and derived allowed triples.
- Create: `crates/dediren-archimate/tests/relationship_rules.rs`
  - Unit-test the shared ArchiMate 3.2 rule API, including valid fixture triples, invalid endpoint triples, and rule-table integrity.
- Modify: `crates/dediren-plugin-generic-graph/src/main.rs`
  - Validate relationship endpoints during ArchiMate render metadata projection.
- Modify: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`
  - Add a regression proving type-name-valid but endpoint-invalid source data is rejected.
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
  - Validate ArchiMate render metadata edge endpoints using layout edge endpoints plus node metadata.
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`
  - Add a regression proving direct ArchiMate render metadata cannot bypass endpoint validation.
- Modify: `crates/dediren-plugin-archimate-oef-export/src/main.rs`
  - Validate relationship endpoints before OEF XML generation.
- Modify: `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`
  - Add a regression proving OEF export rejects type-name-valid but endpoint-invalid source data.
- Modify: `crates/dediren-cli/tests/cli_export.rs`
  - Add CLI-level coverage for endpoint-invalid OEF export error envelopes.
- Modify: `README.md`
  - Document that ArchiMate render and OEF export validate 3.2 endpoint legality, not just type names.
- Modify: release/version surfaces because plugin behavior changes:
  - `Cargo.toml`
  - `Cargo.lock`
  - `fixtures/plugins/*.manifest.json`
  - `README.md` bundle examples
  - `scripts/smoke-dist.sh`

---

### Task 1: Confirm Official ArchiMate 3.2 Source And Start Tests

**Files:**
- Create: `crates/dediren-archimate/tests/relationship_rules.rs`

- [x] **Step 1: Verify official source access**

Open the official ArchiMate 3.2 specification through one of these routes:

```text
https://pubs.opengroup.org/architecture/archimate32-doc/
https://publications.opengroup.org/archimate-library
```

Confirm these facts before proceeding:

- The source is ArchiMate 3.2, not ArchiMate 4.
- The checked section contains the normative relationship definitions and endpoint relationship table.
- The currently supported element vocabulary in `crates/dediren-archimate/src/lib.rs` matches the ArchiMate 3.2 element names this repository already supports.

Expected: the implementer can name the checked ArchiMate 3.2 section/table in the commit message or handoff.

- [x] **Step 2: Add failing shared-rule tests**

Create `crates/dediren-archimate/tests/relationship_rules.rs`:

```rust
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
```

Run:

```bash
cargo test -p dediren-archimate --test relationship_rules --locked
```

Expected: FAIL because `relationship_endpoint_triples` and `validate_relationship_endpoint_types` do not exist yet.

### Task 2: Add Category-Driven Relationship Rules

**Files:**
- Modify: `crates/dediren-archimate/src/lib.rs`
- Create: `crates/dediren-archimate/src/relationship_rules.rs`

- [x] **Step 1: Add the public API shell**

Modify `crates/dediren-archimate/src/lib.rs` near the top:

```rust
mod relationship_rules;

pub use relationship_rules::{
    relationship_endpoint_triples, validate_relationship_endpoint_types, RelationshipEndpointTriple,
};
```

Extend `ArchimateTypeKind`:

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArchimateTypeKind {
    Element,
    Relationship,
    RelationshipEndpoint,
}
```

Extend `code()`:

```rust
ArchimateTypeKind::RelationshipEndpoint => {
    "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED"
}
```

Extend `message()`:

```rust
ArchimateTypeKind::RelationshipEndpoint => {
    format!("unsupported ArchiMate relationship endpoint: {}", self.value)
}
```

Run:

```bash
cargo test -p dediren-archimate --test relationship_rules --locked
```

Expected: FAIL because `relationship_rules.rs` does not exist yet.

- [x] **Step 2: Add the rule module structure**

Create `crates/dediren-archimate/src/relationship_rules.rs`:

```rust
use crate::{
    validate_element_type, validate_relationship_type, ArchimateTypeKind,
    ArchimateTypeValidationError, ELEMENT_TYPES, RELATIONSHIP_TYPES,
};

// Source checked during implementation:
// The Open Group ArchiMate 3.2 Specification, relationship chapter and
// relationship tables. This module intentionally supports ArchiMate 3.2 only.

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct RelationshipEndpointTriple {
    pub source_type: &'static str,
    pub relationship_type: &'static str,
    pub target_type: &'static str,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Layer {
    ImplementationMigration,
    Motivation,
    Strategy,
    Business,
    Application,
    Technology,
    Physical,
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Aspect {
    ActiveStructure,
    Behavior,
    PassiveStructure,
    Motivation,
    Strategy,
    ImplementationMigration,
    Location,
    Grouping,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Family {
    ImplementationMigration,
    Motivation,
    Strategy,
    BusinessActive,
    BusinessBehavior,
    BusinessPassive,
    ApplicationActive,
    ApplicationBehavior,
    ApplicationPassive,
    TechnologyActive,
    TechnologyBehavior,
    TechnologyPassive,
    PhysicalActive,
    PhysicalPassive,
    Location,
    Grouping,
}

#[derive(Debug, Clone, Copy)]
struct ElementProfile {
    name: &'static str,
    layer: Layer,
    aspect: Aspect,
    family: Family,
}

#[derive(Debug, Clone, Copy)]
enum Selector {
    Any,
    Type(&'static str),
    Layer(Layer),
    Aspect(Aspect),
    Family(Family),
    LayerAspect(Layer, Aspect),
}

impl Selector {
    fn matches(self, profile: ElementProfile) -> bool {
        match self {
            Self::Any => true,
            Self::Type(name) => profile.name == name,
            Self::Layer(layer) => profile.layer == layer,
            Self::Aspect(aspect) => profile.aspect == aspect,
            Self::Family(family) => profile.family == family,
            Self::LayerAspect(layer, aspect) => {
                profile.layer == layer && profile.aspect == aspect
            }
        }
    }
}

#[derive(Debug, Clone, Copy)]
struct RelationshipRule {
    relationship_type: &'static str,
    source: Selector,
    target: Selector,
}

const ELEMENT_PROFILES: &[ElementProfile] = &[
    ElementProfile { name: "Plateau", layer: Layer::ImplementationMigration, aspect: Aspect::ImplementationMigration, family: Family::ImplementationMigration },
    ElementProfile { name: "WorkPackage", layer: Layer::ImplementationMigration, aspect: Aspect::ImplementationMigration, family: Family::ImplementationMigration },
    ElementProfile { name: "Deliverable", layer: Layer::ImplementationMigration, aspect: Aspect::ImplementationMigration, family: Family::ImplementationMigration },
    ElementProfile { name: "ImplementationEvent", layer: Layer::ImplementationMigration, aspect: Aspect::ImplementationMigration, family: Family::ImplementationMigration },
    ElementProfile { name: "Gap", layer: Layer::ImplementationMigration, aspect: Aspect::ImplementationMigration, family: Family::ImplementationMigration },
    ElementProfile { name: "Grouping", layer: Layer::Other, aspect: Aspect::Grouping, family: Family::Grouping },
    ElementProfile { name: "Location", layer: Layer::Other, aspect: Aspect::Location, family: Family::Location },
    ElementProfile { name: "Stakeholder", layer: Layer::Motivation, aspect: Aspect::Motivation, family: Family::Motivation },
    ElementProfile { name: "Driver", layer: Layer::Motivation, aspect: Aspect::Motivation, family: Family::Motivation },
    ElementProfile { name: "Assessment", layer: Layer::Motivation, aspect: Aspect::Motivation, family: Family::Motivation },
    ElementProfile { name: "Goal", layer: Layer::Motivation, aspect: Aspect::Motivation, family: Family::Motivation },
    ElementProfile { name: "Outcome", layer: Layer::Motivation, aspect: Aspect::Motivation, family: Family::Motivation },
    ElementProfile { name: "Value", layer: Layer::Motivation, aspect: Aspect::Motivation, family: Family::Motivation },
    ElementProfile { name: "Meaning", layer: Layer::Motivation, aspect: Aspect::Motivation, family: Family::Motivation },
    ElementProfile { name: "Constraint", layer: Layer::Motivation, aspect: Aspect::Motivation, family: Family::Motivation },
    ElementProfile { name: "Requirement", layer: Layer::Motivation, aspect: Aspect::Motivation, family: Family::Motivation },
    ElementProfile { name: "Principle", layer: Layer::Motivation, aspect: Aspect::Motivation, family: Family::Motivation },
    ElementProfile { name: "CourseOfAction", layer: Layer::Strategy, aspect: Aspect::Strategy, family: Family::Strategy },
    ElementProfile { name: "Resource", layer: Layer::Strategy, aspect: Aspect::Strategy, family: Family::Strategy },
    ElementProfile { name: "ValueStream", layer: Layer::Strategy, aspect: Aspect::Strategy, family: Family::Strategy },
    ElementProfile { name: "Capability", layer: Layer::Strategy, aspect: Aspect::Strategy, family: Family::Strategy },
    ElementProfile { name: "BusinessInterface", layer: Layer::Business, aspect: Aspect::ActiveStructure, family: Family::BusinessActive },
    ElementProfile { name: "BusinessCollaboration", layer: Layer::Business, aspect: Aspect::ActiveStructure, family: Family::BusinessActive },
    ElementProfile { name: "BusinessActor", layer: Layer::Business, aspect: Aspect::ActiveStructure, family: Family::BusinessActive },
    ElementProfile { name: "BusinessRole", layer: Layer::Business, aspect: Aspect::ActiveStructure, family: Family::BusinessActive },
    ElementProfile { name: "BusinessProcess", layer: Layer::Business, aspect: Aspect::Behavior, family: Family::BusinessBehavior },
    ElementProfile { name: "BusinessService", layer: Layer::Business, aspect: Aspect::Behavior, family: Family::BusinessBehavior },
    ElementProfile { name: "BusinessInteraction", layer: Layer::Business, aspect: Aspect::Behavior, family: Family::BusinessBehavior },
    ElementProfile { name: "BusinessFunction", layer: Layer::Business, aspect: Aspect::Behavior, family: Family::BusinessBehavior },
    ElementProfile { name: "BusinessEvent", layer: Layer::Business, aspect: Aspect::Behavior, family: Family::BusinessBehavior },
    ElementProfile { name: "Product", layer: Layer::Business, aspect: Aspect::PassiveStructure, family: Family::BusinessPassive },
    ElementProfile { name: "BusinessObject", layer: Layer::Business, aspect: Aspect::PassiveStructure, family: Family::BusinessPassive },
    ElementProfile { name: "Contract", layer: Layer::Business, aspect: Aspect::PassiveStructure, family: Family::BusinessPassive },
    ElementProfile { name: "Representation", layer: Layer::Business, aspect: Aspect::PassiveStructure, family: Family::BusinessPassive },
    ElementProfile { name: "ApplicationInterface", layer: Layer::Application, aspect: Aspect::ActiveStructure, family: Family::ApplicationActive },
    ElementProfile { name: "ApplicationCollaboration", layer: Layer::Application, aspect: Aspect::ActiveStructure, family: Family::ApplicationActive },
    ElementProfile { name: "ApplicationComponent", layer: Layer::Application, aspect: Aspect::ActiveStructure, family: Family::ApplicationActive },
    ElementProfile { name: "ApplicationService", layer: Layer::Application, aspect: Aspect::Behavior, family: Family::ApplicationBehavior },
    ElementProfile { name: "ApplicationInteraction", layer: Layer::Application, aspect: Aspect::Behavior, family: Family::ApplicationBehavior },
    ElementProfile { name: "ApplicationFunction", layer: Layer::Application, aspect: Aspect::Behavior, family: Family::ApplicationBehavior },
    ElementProfile { name: "ApplicationProcess", layer: Layer::Application, aspect: Aspect::Behavior, family: Family::ApplicationBehavior },
    ElementProfile { name: "ApplicationEvent", layer: Layer::Application, aspect: Aspect::Behavior, family: Family::ApplicationBehavior },
    ElementProfile { name: "DataObject", layer: Layer::Application, aspect: Aspect::PassiveStructure, family: Family::ApplicationPassive },
    ElementProfile { name: "TechnologyInterface", layer: Layer::Technology, aspect: Aspect::ActiveStructure, family: Family::TechnologyActive },
    ElementProfile { name: "TechnologyCollaboration", layer: Layer::Technology, aspect: Aspect::ActiveStructure, family: Family::TechnologyActive },
    ElementProfile { name: "Node", layer: Layer::Technology, aspect: Aspect::ActiveStructure, family: Family::TechnologyActive },
    ElementProfile { name: "SystemSoftware", layer: Layer::Technology, aspect: Aspect::ActiveStructure, family: Family::TechnologyActive },
    ElementProfile { name: "Device", layer: Layer::Technology, aspect: Aspect::ActiveStructure, family: Family::TechnologyActive },
    ElementProfile { name: "Facility", layer: Layer::Physical, aspect: Aspect::ActiveStructure, family: Family::PhysicalActive },
    ElementProfile { name: "Equipment", layer: Layer::Physical, aspect: Aspect::ActiveStructure, family: Family::PhysicalActive },
    ElementProfile { name: "Path", layer: Layer::Technology, aspect: Aspect::ActiveStructure, family: Family::TechnologyActive },
    ElementProfile { name: "TechnologyService", layer: Layer::Technology, aspect: Aspect::Behavior, family: Family::TechnologyBehavior },
    ElementProfile { name: "TechnologyInteraction", layer: Layer::Technology, aspect: Aspect::Behavior, family: Family::TechnologyBehavior },
    ElementProfile { name: "TechnologyFunction", layer: Layer::Technology, aspect: Aspect::Behavior, family: Family::TechnologyBehavior },
    ElementProfile { name: "TechnologyProcess", layer: Layer::Technology, aspect: Aspect::Behavior, family: Family::TechnologyBehavior },
    ElementProfile { name: "TechnologyEvent", layer: Layer::Technology, aspect: Aspect::Behavior, family: Family::TechnologyBehavior },
    ElementProfile { name: "Artifact", layer: Layer::Technology, aspect: Aspect::PassiveStructure, family: Family::TechnologyPassive },
    ElementProfile { name: "Material", layer: Layer::Physical, aspect: Aspect::PassiveStructure, family: Family::PhysicalPassive },
    ElementProfile { name: "CommunicationNetwork", layer: Layer::Technology, aspect: Aspect::ActiveStructure, family: Family::TechnologyActive },
    ElementProfile { name: "DistributionNetwork", layer: Layer::Physical, aspect: Aspect::ActiveStructure, family: Family::PhysicalActive },
];

const RELATIONSHIP_RULES: &[RelationshipRule] = &[
    RelationshipRule { relationship_type: "Specialization", source: Selector::Any, target: Selector::Any },
    RelationshipRule { relationship_type: "Association", source: Selector::Any, target: Selector::Any },
    RelationshipRule { relationship_type: "Composition", source: Selector::Any, target: Selector::Any },
    RelationshipRule { relationship_type: "Aggregation", source: Selector::Any, target: Selector::Any },
    RelationshipRule { relationship_type: "Triggering", source: Selector::Aspect(Aspect::Behavior), target: Selector::Aspect(Aspect::Behavior) },
    RelationshipRule { relationship_type: "Flow", source: Selector::Aspect(Aspect::Behavior), target: Selector::Aspect(Aspect::Behavior) },
    RelationshipRule { relationship_type: "Access", source: Selector::Aspect(Aspect::Behavior), target: Selector::Aspect(Aspect::PassiveStructure) },
    RelationshipRule { relationship_type: "Serving", source: Selector::Aspect(Aspect::Behavior), target: Selector::Aspect(Aspect::Behavior) },
    RelationshipRule { relationship_type: "Assignment", source: Selector::Aspect(Aspect::ActiveStructure), target: Selector::Aspect(Aspect::Behavior) },
    RelationshipRule { relationship_type: "Realization", source: Selector::Aspect(Aspect::ActiveStructure), target: Selector::Aspect(Aspect::Behavior) },
    RelationshipRule { relationship_type: "Influence", source: Selector::Layer(Layer::Motivation), target: Selector::Layer(Layer::Motivation) },
];

const ALLOW_EXCEPTIONS: &[RelationshipEndpointTriple] = &[
    RelationshipEndpointTriple { source_type: "ApplicationComponent", relationship_type: "Realization", target_type: "ApplicationService" },
];

const DENY_EXCEPTIONS: &[RelationshipEndpointTriple] = &[];

pub fn validate_relationship_endpoint_types(
    relationship_type: &str,
    source_type: &str,
    target_type: &str,
    path: impl Into<String>,
) -> Result<(), ArchimateTypeValidationError> {
    validate_relationship_type(relationship_type, path.into())?;
    validate_element_type(source_type, "$.relationship.source.type")?;
    validate_element_type(target_type, "$.relationship.target.type")?;

    if is_relationship_endpoint_allowed(relationship_type, source_type, target_type) {
        Ok(())
    } else {
        Err(ArchimateTypeValidationError {
            kind: ArchimateTypeKind::RelationshipEndpoint,
            value: format!("{source_type} -[{relationship_type}]-> {target_type}"),
            path: "$.relationship".to_string(),
        })
    }
}

pub fn relationship_endpoint_triples() -> Vec<RelationshipEndpointTriple> {
    let mut triples = Vec::new();
    for source in ELEMENT_PROFILES {
        for relationship_type in RELATIONSHIP_TYPES {
            for target in ELEMENT_PROFILES {
                if is_relationship_endpoint_allowed(
                    *relationship_type,
                    source.name,
                    target.name,
                ) {
                    triples.push(RelationshipEndpointTriple {
                        source_type: source.name,
                        relationship_type: *relationship_type,
                        target_type: target.name,
                    });
                }
            }
        }
    }
    triples.sort();
    triples.dedup();
    triples
}

fn is_relationship_endpoint_allowed(
    relationship_type: &str,
    source_type: &str,
    target_type: &str,
) -> bool {
    if contains_triple(DENY_EXCEPTIONS, relationship_type, source_type, target_type) {
        return false;
    }
    if contains_triple(ALLOW_EXCEPTIONS, relationship_type, source_type, target_type) {
        return true;
    }

    let Some(source) = profile(source_type) else {
        return false;
    };
    let Some(target) = profile(target_type) else {
        return false;
    };

    RELATIONSHIP_RULES.iter().any(|rule| {
        rule.relationship_type == relationship_type
            && rule.source.matches(source)
            && rule.target.matches(target)
    })
}

fn contains_triple(
    triples: &[RelationshipEndpointTriple],
    relationship_type: &str,
    source_type: &str,
    target_type: &str,
) -> bool {
    triples.iter().any(|triple| {
        triple.relationship_type == relationship_type
            && triple.source_type == source_type
            && triple.target_type == target_type
    })
}

fn profile(type_name: &str) -> Option<ElementProfile> {
    ELEMENT_PROFILES
        .iter()
        .copied()
        .find(|profile| profile.name == type_name)
}
```

Run:

```bash
cargo test -p dediren-archimate --test relationship_rules --locked
```

Expected: tests compile, but at least `rejects_type_valid_but_endpoint_invalid_relationship` may fail because the permissive initial rules still need official-table tightening.

- [x] **Step 3: Replace the initial rule set with official ArchiMate 3.2 rules**

Using the official ArchiMate 3.2 relationship table, adjust only the data constants
`ELEMENT_PROFILES`, `RELATIONSHIP_RULES`, `ALLOW_EXCEPTIONS`, and
`DENY_EXCEPTIONS`. Keep all 60 supported ArchiMate 3.2 element names represented
exactly once in `ELEMENT_PROFILES`. Encode broad official-table patterns in
`RELATIONSHIP_RULES`, and use the exception arrays only when an official
allowed or denied triple does not fit a category rule.

Required result:

- `ApplicationComponent -[Realization]-> ApplicationService` is accepted.
- `ApplicationService -[Realization]-> ApplicationComponent` is rejected unless the official ArchiMate 3.2 table explicitly allows it. If it is allowed, update the failing test to another official-table-invalid reverse pair before proceeding.
- `BusinessActor -[Triggering]-> DataObject` is rejected unless the official ArchiMate 3.2 table explicitly allows it. If it is allowed, update the failing test to another official-table-invalid non-behavior dynamic relationship before proceeding.
- No rule references a name outside `ELEMENT_TYPES` and `RELATIONSHIP_TYPES`.

Run:

```bash
cargo test -p dediren-archimate --test relationship_rules --locked
```

Expected: PASS.

- [x] **Step 4: Preserve caller paths in endpoint errors**

Fix the temporary `validate_relationship_endpoint_types` path handling so callers get their own relationship path:

```rust
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

    if is_relationship_endpoint_allowed(relationship_type, source_type, target_type) {
        Ok(())
    } else {
        Err(ArchimateTypeValidationError {
            kind: ArchimateTypeKind::RelationshipEndpoint,
            value: format!("{source_type} -[{relationship_type}]-> {target_type}"),
            path,
        })
    }
}
```

Run:

```bash
cargo test -p dediren-archimate --test relationship_rules --locked
```

Expected: PASS.

### Task 3: Validate ArchiMate Sources In Generic Graph Projection

**Files:**
- Modify: `crates/dediren-plugin-generic-graph/src/main.rs`
- Modify: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`

- [x] **Step 1: Add failing generic-graph regression**

Append to `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`:

```rust
#[test]
fn generic_graph_rejects_invalid_archimate_relationship_endpoint_for_render_metadata() {
    let mut source = archimate_source();
    source["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    source["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    source["relationships"][0]["type"] = serde_json::json!("Realization");

    let mut cmd = Command::cargo_bin("dediren-plugin-generic-graph").unwrap();
    cmd.args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&source).unwrap());
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains(
            "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
        ))
        .stdout(predicate::str::contains("ApplicationService"))
        .stdout(predicate::str::contains("Realization"))
        .stdout(predicate::str::contains("ApplicationComponent"));
}
```

Run:

```bash
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin generic_graph_rejects_invalid_archimate_relationship_endpoint_for_render_metadata --locked
```

Expected: FAIL because endpoint validation is not wired in.

- [x] **Step 2: Build node-type lookup and validate endpoints**

Modify `validate_archimate_source_types` in `crates/dediren-plugin-generic-graph/src/main.rs`:

```rust
fn validate_archimate_source_types(
    source: &SourceDocument,
) -> Result<(), ArchimateTypeValidationError> {
    let mut node_types = BTreeMap::new();

    for (index, node) in source.nodes.iter().enumerate() {
        dediren_archimate::validate_element_type(
            &node.node_type,
            format!("$.nodes[{index}].type"),
        )?;
        node_types.insert(node.id.as_str(), node.node_type.as_str());
    }
    for (index, relationship) in source.relationships.iter().enumerate() {
        dediren_archimate::validate_relationship_type(
            &relationship.relationship_type,
            format!("$.relationships[{index}].type"),
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
            format!("$.relationships[{index}]"),
        )?;
    }
    Ok(())
}
```

Run:

```bash
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin --locked
```

Expected: PASS.

### Task 4: Validate ArchiMate Render Metadata In SVG Render

**Files:**
- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`

- [x] **Step 1: Add failing SVG regression**

Append near the existing ArchiMate rejection tests in `crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs`:

```rust
#[test]
fn svg_renderer_rejects_invalid_archimate_relationship_endpoint() {
    let mut input = archimate_style_input();
    input["render_metadata"]["nodes"]["orders-component"]["type"] =
        serde_json::json!("ApplicationService");
    input["render_metadata"]["nodes"]["orders-service"]["type"] =
        serde_json::json!("ApplicationComponent");
    input["render_metadata"]["edges"]["orders-realizes-service"]["type"] =
        serde_json::json!("Realization");

    let mut cmd = Command::cargo_bin("dediren-plugin-svg-render").unwrap();
    cmd.arg("render")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .stdout(predicate::str::contains(
            "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
        ))
        .stdout(predicate::str::contains("ApplicationService"))
        .stdout(predicate::str::contains("Realization"))
        .stdout(predicate::str::contains("ApplicationComponent"));
}
```

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_rejects_invalid_archimate_relationship_endpoint --locked
```

Expected: FAIL because SVG render validates type names but not endpoint triples.

- [x] **Step 2: Pass layout result into metadata validation**

In `crates/dediren-plugin-svg-render/src/main.rs`, change the call:

```rust
if let Err(error) = validate_archimate_render_metadata(render_input.render_metadata.as_ref()) {
    exit_with_archimate_type_error(error);
}
```

to:

```rust
if let Err(error) = validate_archimate_render_metadata(
    &render_input.layout_result,
    render_input.render_metadata.as_ref(),
) {
    exit_with_archimate_type_error(error);
}
```

Replace the function signature and body:

```rust
fn validate_archimate_render_metadata(
    layout_result: &LayoutResult,
    metadata: Option<&RenderMetadata>,
) -> Result<(), ArchimateTypeValidationError> {
    let Some(metadata) = metadata else {
        return Ok(());
    };
    if metadata.semantic_profile != "archimate" {
        return Ok(());
    }

    for (node_id, selector) in &metadata.nodes {
        dediren_archimate::validate_element_type(
            &selector.selector_type,
            format!("render_metadata.nodes.{node_id}.type"),
        )?;
    }
    for (edge_id, selector) in &metadata.edges {
        dediren_archimate::validate_relationship_type(
            &selector.selector_type,
            format!("render_metadata.edges.{edge_id}.type"),
        )?;
    }

    for edge in &layout_result.edges {
        let Some(edge_selector) = metadata.edges.get(&edge.id) else {
            continue;
        };
        let Some(source_selector) = metadata.nodes.get(&edge.source) else {
            continue;
        };
        let Some(target_selector) = metadata.nodes.get(&edge.target) else {
            continue;
        };

        dediren_archimate::validate_relationship_endpoint_types(
            &edge_selector.selector_type,
            &source_selector.selector_type,
            &target_selector.selector_type,
            format!("render_metadata.edges.{}", edge.id),
        )?;
    }
    Ok(())
}
```

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin svg_renderer_rejects_invalid_archimate_relationship_endpoint --locked
```

Expected: PASS.

- [x] **Step 3: Run SVG render lane**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin --locked
cargo test -p dediren --test cli_render --locked
```

Expected: PASS.

### Task 5: Validate ArchiMate OEF Export Endpoints

**Files:**
- Modify: `crates/dediren-plugin-archimate-oef-export/src/main.rs`
- Modify: `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`
- Modify: `crates/dediren-cli/tests/cli_export.rs`

- [x] **Step 1: Add failing OEF plugin regression**

Append to `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs`:

```rust
#[test]
fn oef_export_plugin_rejects_invalid_archimate_relationship_endpoint_with_error_envelope() {
    let mut input = export_input();
    input["source"]["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    input["source"]["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    input["source"]["relationships"][0]["type"] = serde_json::json!("Realization");

    let mut cmd = Command::cargo_bin("dediren-plugin-archimate-oef-export").unwrap();
    cmd.arg("export")
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .stdout(predicate::str::contains("\"status\":\"error\""))
        .stdout(predicate::str::contains(
            "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
        ))
        .stdout(predicate::str::contains("ApplicationService"))
        .stdout(predicate::str::contains("Realization"))
        .stdout(predicate::str::contains("ApplicationComponent"));
}
```

Run:

```bash
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin oef_export_plugin_rejects_invalid_archimate_relationship_endpoint_with_error_envelope --locked
```

Expected: FAIL because OEF export validates type names but not endpoint triples.

- [x] **Step 2: Validate source endpoint triples before XML generation**

Modify `validate_archimate_types` in `crates/dediren-plugin-archimate-oef-export/src/main.rs`:

```rust
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
```

Run:

```bash
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin --locked
```

Expected: PASS.

- [x] **Step 3: Add CLI export coverage**

Append to `crates/dediren-cli/tests/cli_export.rs`:

```rust
#[test]
fn export_rejects_invalid_archimate_relationship_endpoint() {
    let plugin = workspace_binary(
        "dediren-plugin-archimate-oef-export",
        "dediren-plugin-archimate-oef-export",
    );

    let mut source: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(workspace_file("fixtures/source/valid-archimate-oef.json"))
            .unwrap(),
    )
    .unwrap();
    source["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    source["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    source["relationships"][0]["type"] = serde_json::json!("Realization");

    let temp = assert_fs::TempDir::new().unwrap();
    let source_file = temp.child("invalid-endpoint-source.json");
    source_file
        .write_str(&serde_json::to_string_pretty(&source).unwrap())
        .unwrap();

    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.env("DEDIREN_PLUGIN_ARCHIMATE_OEF", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args([
            "export",
            "--plugin",
            "archimate-oef",
            "--policy",
            &workspace_file("fixtures/export-policy/default-oef.json"),
            "--source",
        ])
        .arg(source_file.path())
        .args([
            "--layout",
            &workspace_file("fixtures/layout-result/archimate-oef-basic.json"),
        ])
        .assert()
        .failure()
        .stdout(predicate::str::contains(
            "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
        ));
}
```

Add imports at the top if missing:

```rust
use assert_fs::prelude::*;
```

Run:

```bash
cargo test -p dediren --test cli_export export_rejects_invalid_archimate_relationship_endpoint --locked
```

Expected: PASS after the plugin fix.

### Task 6: Documentation And Version Bump

**Files:**
- Modify: `Cargo.toml`
- Modify: `Cargo.lock`
- Modify: `fixtures/plugins/archimate-oef.manifest.json`
- Modify: `fixtures/plugins/elk-layout.manifest.json`
- Modify: `fixtures/plugins/generic-graph.manifest.json`
- Modify: `fixtures/plugins/svg-render.manifest.json`
- Modify: `README.md`
- Modify: `scripts/smoke-dist.sh`

- [x] **Step 1: Bump patch version**

At plan time the workspace is `0.1.3`, so this behavior change should ship as `0.1.4`. If the current workspace version has moved by execution time, bump to the next patch version from whatever `Cargo.toml [workspace.package] version` says.

Update:

```text
Cargo.toml
Cargo.lock
fixtures/plugins/archimate-oef.manifest.json
fixtures/plugins/elk-layout.manifest.json
fixtures/plugins/generic-graph.manifest.json
fixtures/plugins/svg-render.manifest.json
README.md
scripts/smoke-dist.sh
```

Use `rg "0\\.1\\.3|0\\.1\\.4"` to verify no stale bundle example or first-party manifest version remains.

- [x] **Step 2: Update README diagnostics**

In `README.md`, update the ArchiMate section so it says:

```markdown
ArchiMate render and export paths reject unsupported ArchiMate element or
relationship type strings and reject ArchiMate 3.2 relationships whose source
and target element types are not allowed by the ArchiMate 3.2 relationship
rules. Use the ArchiMate/OEF element name `Node` for technology nodes; aliases
such as `TechnologyNode` are not accepted in ArchiMate metadata or export
source.
```

In the diagnostics list, add:

```markdown
- `DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED`
```

Run:

```bash
git diff --check
```

Expected: PASS.

### Task 7: Verification And Audits

**Files:**
- No additional edits unless verification finds a bug.

- [x] **Step 1: Run focused lanes**

Run:

```bash
cargo fmt --all -- --check
cargo test -p dediren-archimate --test relationship_rules --locked
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin --locked
cargo test -p dediren-plugin-svg-render --test svg_render_plugin --locked
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin --locked
cargo test -p dediren --test cli_export --locked
cargo test -p dediren --test cli_render --locked
```

Expected: PASS.

- [x] **Step 2: Run broad workspace verification**

Run:

```bash
cargo test --workspace --locked
git diff --check
```

Expected: PASS.

- [x] **Step 3: Run required audit gates**

Because this plan touches ArchiMate render and OEF export behavior:

- Run `souroldgeezer-audit:test-quality-audit` deep enough to inspect the new Rust tests and fixtures for false confidence, brittle endpoint cases, and missing direct-render bypass coverage.
- Run `souroldgeezer-audit:devsecops-audit` quick scope over the diff, focusing on plugin process boundaries, docs, licensed-source handling, and generated artifact/version surfaces.

Fix block findings. Fix warn/info findings or explicitly accept them in the handoff, then rerun affected checks.

### Task 8: Commit

**Files:**
- Stage only intentional paths from this plan.

- [x] **Step 1: Review changed files**

Run:

```bash
git status --short --branch
git diff -- crates/dediren-archimate/src/lib.rs
git diff -- crates/dediren-archimate/src/relationship_rules.rs
git diff -- crates/dediren-archimate/tests/relationship_rules.rs
git diff -- crates/dediren-plugin-generic-graph/src/main.rs
git diff -- crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs
git diff -- crates/dediren-plugin-svg-render/src/main.rs
git diff -- crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
git diff -- crates/dediren-plugin-archimate-oef-export/src/main.rs
git diff -- crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs
git diff -- crates/dediren-cli/tests/cli_export.rs
git diff -- crates/dediren-cli/tests/cli_pipeline.rs
git diff -- fixtures/source/valid-pipeline-archimate.json
git diff -- README.md
git diff -- Cargo.toml Cargo.lock fixtures/plugins scripts/smoke-dist.sh
git diff -- docs/superpowers/plans/2026-05-12-dediren-archimate-32-relationship-validation.md
```

Expected: only the relationship-validation implementation, docs, and version surfaces changed.

- [x] **Step 2: Stage intentional files**

Run explicit staging, adjusting the versioned files only if they changed:

```bash
git add crates/dediren-archimate/src/lib.rs
git add crates/dediren-archimate/src/relationship_rules.rs
git add crates/dediren-archimate/tests/relationship_rules.rs
git add crates/dediren-plugin-generic-graph/src/main.rs
git add crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs
git add crates/dediren-plugin-svg-render/src/main.rs
git add crates/dediren-plugin-svg-render/tests/svg_render_plugin.rs
git add crates/dediren-plugin-archimate-oef-export/src/main.rs
git add crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs
git add crates/dediren-cli/tests/cli_export.rs
git add crates/dediren-cli/tests/cli_pipeline.rs
git add README.md
git add Cargo.toml Cargo.lock
git add fixtures/plugins/archimate-oef.manifest.json
git add fixtures/plugins/elk-layout.manifest.json
git add fixtures/plugins/generic-graph.manifest.json
git add fixtures/plugins/svg-render.manifest.json
git add fixtures/source/valid-pipeline-archimate.json
git add scripts/smoke-dist.sh
git add docs/superpowers/plans/2026-05-12-dediren-archimate-32-relationship-validation.md
```

- [x] **Step 3: Commit**

Run:

```bash
git commit -m "Validate ArchiMate 3.2 relationship endpoints"
```

Expected: commit succeeds.

---

## Self-Review

- Source coverage: the plan includes a hard gate to check the official ArchiMate 3.2 specification before encoding relationship rules.
- Boundary coverage: `dediren-archimate` owns rule knowledge; core remains generic; plugins consume the shared API.
- Render coverage: `generic-graph` validates generated ArchiMate metadata, and `svg-render` validates direct ArchiMate metadata against layout endpoints so render-only bypass is covered.
- Export coverage: `archimate-oef` validates endpoint triples before generating OEF XML.
- Version/docs coverage: plugin behavior and public diagnostics require a patch bump and README update.
- Audit coverage: test-quality and devsecops gates are explicit because this plan changes planned ArchiMate render/export behavior.
