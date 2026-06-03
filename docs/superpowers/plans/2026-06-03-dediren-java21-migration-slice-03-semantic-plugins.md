# Dediren Java 21 Migration Slice 03: Semantic Plugins

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` for this slice. Keep plugin/core dependency direction intact.

**Goal:** Port semantic validation, projection, ArchiMate rules, UML rules, and schema cache behavior to Java first-party plugins.

**Architecture:** Domain vocabularies live outside core. The `generic-graph` plugin performs semantic validation and projection through Java contracts. Schema cache remains a plugin/runtime helper, not core behavior.

**Tech Stack:** Java 21, Jackson, JUnit 5, AssertJ, NetworkNT JSON Schema Validator, XMLUnit for XML-related fixture checks when needed.

---

## Files

- Create: `modules/archimate/src/main/java/dev/dediren/archimate/*.java`
- Create: `modules/archimate/src/test/java/dev/dediren/archimate/*Test.java`
- Create: `modules/uml/src/main/java/dev/dediren/uml/*.java`
- Create: `modules/uml/src/test/java/dev/dediren/uml/*Test.java`
- Create: `modules/schema-cache/src/main/java/dev/dediren/schema/cache/*.java`
- Create: `modules/schema-cache/src/test/java/dev/dediren/schema/cache/*Test.java`
- Create: `modules/plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/Main.java`
- Create: `modules/plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/*Test.java`
- Read parity source: `crates/dediren-archimate`
- Read parity source: `crates/dediren-uml`
- Read parity source: `crates/dediren-plugin-generic-graph`
- Read parity source: `crates/dediren-plugin-schema-cache`
- Read parity tests: `crates/dediren-archimate/tests/relationship_rules.rs`
- Read parity tests: `crates/dediren-uml/tests/uml_validation.rs`
- Read parity tests: `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs`

## Tasks

- [x] **Task 1: Port ArchiMate vocabulary and relationship rules**
  - Model: `gpt-5-codex`
  - Add Java enums or value objects for supported ArchiMate element and relationship types.
  - Preserve current curated relationship oracle behavior and endpoint validation.
  - Add tests equivalent to `relationship_rules.rs`.
  - Run: `./gradlew :modules:archimate:test`
  - Expected: pass.

- [x] **Task 2: Port UML vocabulary and validation**
  - Model: `gpt-5-codex`
  - Add Java enums/value objects for the current UML class, data, and activity slice.
  - Preserve multiplicity validation, endpoint validation, and view-kind restrictions.
  - Add tests equivalent to `uml_validation.rs`.
  - Run: `./gradlew :modules:uml:test`
  - Expected: pass.

- [x] **Task 3: Port source fragment assembly**
  - Model: `gpt-5-codex`
  - Implement source fragment resolution relative to the entry model file.
  - Preserve duplicate id checks, duplicate plugin-owned view/group checks, scalar conflict checks, and stdin-without-base rejection.
  - Add tests equivalent to current CLI validate/project fragment behavior.
  - Expected: pass.

- [x] **Task 4: Port generic graph semantic validation**
  - Model: `gpt-5-codex`
  - Implement `capabilities`, `semantic-validate`, and validation error envelopes for `generic-graph`.
  - Preserve ArchiMate and UML profile validation behavior.
  - Add tests equivalent to `generic_graph_plugin.rs` semantic validation tests.
  - Run: `./gradlew :modules:plugins:generic-graph:test`
  - Expected: pass.

- [x] **Task 5: Port projection to layout request and render metadata**
  - Model: `gpt-5-codex`
  - Implement `project` targets `layout-request` and `render-metadata`.
  - Preserve layout preferences, group roles/provenance, UML compact size hints, ArchiMate junction metadata, and source ids.
  - Add tests equivalent to current projection tests.
  - Expected: Java fixture output is schema-valid and semantically equivalent to Rust output.

- [x] **Task 6: Port schema cache plugin/helper**
  - Model: `gpt-5-codex`
  - Implement schema cache env handling for OEF/XMI dependencies.
  - Preserve structured diagnostics for unavailable schemas and cache path failures.
  - Add tests for configured cache, direct schema path, generated temp schema fixtures, download/cache behavior, download-disabled failures, and structured diagnostics.
  - Expected: Java helper behavior is available to export plugins; export-plugin command-envelope diagnostics are covered when OEF/XMI are wired in Slice 04.

- [x] **Task 7: Add semantic Rust coverage note**
  - Model: `gpt-5-mini`
  - In the slice handoff, map `dediren-archimate`, `dediren-uml`, `dediren-plugin-generic-graph`, and `dediren-plugin-schema-cache` to the Java modules and test classes created in this slice.
  - Include the fixture families covered: `fixtures/source`, `fixtures/layout-request`, `fixtures/render-metadata`, and schema-cache temp schema fixtures.
  - Expected: no semantic Rust crate remains without a named Java successor.

- [x] **Task 8: Run architecture and test audits**
  - Model: `gpt-5`
  - Use `souroldgeezer-design:software-design` Review for vocabulary ownership and plugin/core direction.
  - Use `souroldgeezer-architecture:architecture-design` Lookup/Review for ArchiMate and UML semantic expectations touched by this slice.
  - Use `souroldgeezer-audit:test-quality-audit` Deep for semantic plugin tests.
  - Fix block findings before commit.

- [x] **Task 9: Verify and commit**
  - Model: `gpt-5-mini`
  - Run: `./gradlew :modules:archimate:test :modules:uml:test :modules:schema-cache:test :modules:plugins:generic-graph:test`
  - Run: `cargo test -p dediren-archimate --locked`
  - Run: `cargo test -p dediren-uml --locked`
  - Run: `cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin --locked`
  - Run: `git diff --check`
  - Commit message: `feat: port semantic plugins to java`

## 2026-06-03 Checkpoint: ArchiMate, UML, Generic Graph

Implemented in Java so far:

- `modules/archimate`
  - Supported ArchiMate element/relationship vocabulary.
  - Curated relationship endpoint rejection policy.
  - Relationship connector and junction validation rules.
- `modules/uml`
  - UML structural/activity vocabulary.
  - Relationship endpoint validation.
  - Multiplicity validation.
  - UML view-kind restrictions.
- `modules/plugins/generic-graph`
  - `capabilities`.
  - `validate --profile archimate|uml` semantic validation.
  - `project --target layout-request`.
  - `project --target render-metadata`.
  - Duplicate view/group diagnostics.
  - ArchiMate/UML layout size hints and render metadata mapping.

Rust-to-Java successor mapping for this checkpoint:

| Rust source/test | Java successor |
| --- | --- |
| `crates/dediren-archimate/src/lib.rs`, `src/relationship_rules.rs`, `tests/relationship_rules.rs` | `modules/archimate/src/main/java/dev/dediren/archimate/*`, `ArchimateRelationshipRulesTest` |
| `crates/dediren-uml/src/lib.rs`, `tests/uml_validation.rs` | `modules/uml/src/main/java/dev/dediren/uml/*`, `UmlValidationTest` |
| `crates/dediren-plugin-generic-graph/src/main.rs`, `tests/generic_graph_plugin.rs` | `modules/plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/Main.java`, `GenericGraphPluginTest` |
| `crates/dediren-plugin-schema-cache/src/lib.rs` | `modules/schema-cache/src/main/java/dev/dediren/schemacache/*`, `SchemaCacheModuleTest` |

Verification evidence:

```bash
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :modules:archimate:test :modules:uml:test :modules:plugins:generic-graph:test
cargo test -p dediren-archimate --locked
cargo test -p dediren-uml --locked
cargo test -p dediren-plugin-generic-graph --test generic_graph_plugin --locked
cargo test -p dediren-plugin-schema-cache --locked
```

Result: all passed.

Schema-cache helper verification:

```bash
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :modules:schema-cache:test
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :modules:archimate:test :modules:uml:test :modules:schema-cache:test :modules:plugins:generic-graph:test
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew test
```

Result: all passed.

Audit notes:

- Software-design boundary check: schema-cache remains a helper module for export plugins; core does not own schema download/cache behavior.
- Architecture-design scope check: no ArchiMate or UML package source/evidence was edited in this helper checkpoint.
- Test-quality check: helper behavior covers env fallback, non-empty files, cache hit, temp download/cache store, failed download diagnostics, empty downloads, output detail formatting, and fake-curl process arguments. Export-plugin command-envelope diagnostics remain in Slice 04 when OEF/XMI are wired to this helper.

## 2026-06-03 Checkpoint: Generic Graph Projection Parity

Expanded Java coverage in `GenericGraphPluginTest` to cover the Rust projection and semantic-validation matrix:

- Duplicate view and group diagnostics.
- Layout preferences.
- Rich groups and group provenance.
- Missing group semantic source projection errors.
- ArchiMate semantic validation, junction sizing, junction render metadata, junction-chain validation, containment handling, unknown type diagnostics, and invalid endpoint diagnostics.
- UML semantic validation, invalid endpoint diagnostics, render metadata, compact activity node sizing, and structural compartment size hints.
- Layout-request and render-metadata schema validation for generated payloads.

Verification evidence:

```bash
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :modules:plugins:generic-graph:test
```

Result: all passed.

Audit notes:

- Software-design boundary check: semantic vocabularies remain in `modules/archimate` and `modules/uml`; generic-graph owns projection and profile dispatch; core remains plugin-neutral.
- Architecture-design scope check: ArchiMate and UML notation behavior was exercised through dediren source/projection contracts; no package source or rendered architecture evidence was edited in this checkpoint.
- Test-quality check: Java tests now cover happy paths, negative diagnostics, schema assertions, and fixture-backed parity. Remaining mutation evidence is not available in this Gradle setup and is accepted for this migration slice.

Slice 03 closeout:

- Semantic plugin migration is complete for the Java side-by-side path.
- Export-plugin command-envelope diagnostics that consume schema-cache move with OEF/XMI wiring in Slice 04.
