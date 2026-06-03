# Dediren Java 21 Migration Slice 01: Contracts

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` for this slice. Do not start until Slice 00 is committed and green.

**Goal:** Port `dediren-contracts` protocol structs and schema constants into Java records while keeping checked-in JSON schemas canonical.

**Architecture:** Java contracts are data-only records plus serialization helpers. They do not own orchestration, plugin execution, semantic validation, SVG rendering, OEF serialization, UML serialization, or ELK interpretation.

**Tech Stack:** Java 21 records, Jackson, NetworkNT JSON Schema Validator, JUnit 5, AssertJ.

---

## Files

- Create: `modules/contracts/src/main/java/dev/dediren/contracts/ContractVersions.java`
- Create: `modules/contracts/src/main/java/dev/dediren/contracts/CommandEnvelope.java`
- Create: `modules/contracts/src/main/java/dev/dediren/contracts/Diagnostic.java`
- Create: `modules/contracts/src/main/java/dev/dediren/contracts/plugin/*.java`
- Create: `modules/contracts/src/main/java/dev/dediren/contracts/source/*.java`
- Create: `modules/contracts/src/main/java/dev/dediren/contracts/layout/*.java`
- Create: `modules/contracts/src/main/java/dev/dediren/contracts/render/*.java`
- Create: `modules/contracts/src/main/java/dev/dediren/contracts/export/*.java`
- Create: `modules/contracts/src/main/java/dev/dediren/contracts/json/JsonSupport.java`
- Create: `modules/contracts/src/main/java/dev/dediren/contracts/schema/SchemaValidator.java`
- Create: `modules/contracts/src/test/java/dev/dediren/contracts/*Test.java`
- Read parity source: `crates/dediren-contracts/src`
- Read parity tests: `crates/dediren-contracts/tests/contract_roundtrip.rs`
- Read parity tests: `crates/dediren-contracts/tests/schema_contracts.rs`

## Tasks

- [x] **Task 1: Enumerate contract surface**
  - Model: `gpt-5-mini`
  - Run: `rg -n "pub struct|pub enum|pub const|SCHEMA_VERSION|schema_version" crates/dediren-contracts/src crates/dediren-contracts/tests`
  - Create a checklist in the slice handoff with each Rust public type mapped to a Java record/class.
  - Expected coverage includes command envelopes, diagnostics, plugin manifests, runtime capability, source document, layout request/result, render metadata/policy/result, export request/result, and semantic validation result.

- [x] **Task 2: Port schema constants**
  - Model: `gpt-5-codex`
  - Add `ContractVersions` with string constants matching the current Rust constants exactly.
  - Add a JUnit test that asserts every constant equals the expected schema id string.
  - Run: `./gradlew :modules:contracts:test --tests '*ContractVersionsTest'`
  - Expected: pass.

- [x] **Task 3: Port command envelope and diagnostics**
  - Model: `gpt-5-codex`
  - Add `CommandEnvelope<T>` and `Diagnostic` records.
  - Preserve success/error envelope JSON field names and diagnostic severity strings.
  - Add round-trip tests using fixture JSON copied from existing Rust tests or generated from checked-in fixtures.
  - Expected: serialized JSON matches current envelope shape and rejects missing required fields where Rust rejects them.

- [x] **Task 4: Port plugin manifest and runtime capability contracts**
  - Model: `gpt-5-codex`
  - Add Java records for plugin manifest, capability list, runtime capability, runtime requirements, and allowed env.
  - Add tests that validate every `fixtures/plugins/*.manifest.json` file against the Java records and checked-in schema.
  - Run: `./gradlew :modules:contracts:test --tests '*Plugin*'`
  - Expected: all fixture manifests parse and validate.

- [x] **Task 5: Port source, layout, render, and export contracts**
  - Model: `gpt-5-codex`
  - Add Java records for all source, layout, render, and export contracts.
  - Use explicit records for stable concepts; use `JsonNode` only for plugin-owned extension values and policy fragments where the Rust contract allows arbitrary JSON.
  - Add round-trip tests for representative fixtures under `fixtures/source`, `fixtures/layout-request`, `fixtures/layout-result`, `fixtures/render-policy`, and export policies.
  - Expected: Java reads and writes fixture-compatible JSON without adding authored geometry or styling to source documents.

- [x] **Task 6: Port schema validation helper**
  - Model: `gpt-5-codex`
  - Add a schema validator that loads checked-in schema files from the repository root or classpath test resources.
  - Add tests equivalent to `all_public_schemas_compile`, `valid_source_matches_model_schema`, and representative negative schema tests.
  - Run: `./gradlew :modules:contracts:test`
  - Expected: pass.

- [x] **Task 7: Run audit checks**
  - Model: `gpt-5`
  - Use `souroldgeezer-design:software-design` Review for contract ownership and package boundaries.
  - Use `souroldgeezer-audit:test-quality-audit` Deep on `modules/contracts/src/test`.
  - Fix block findings before commit.

- [x] **Task 8: Verify and commit**
  - Model: `gpt-5-mini`
  - Run: `./gradlew :modules:contracts:test`
  - Run: `cargo test -p dediren-contracts --test schema_contracts --locked`
  - Run: `cargo test -p dediren-contracts --test contract_roundtrip --locked`
  - Run: `git diff --check`
  - Commit message: `feat: port contracts to java`

## Handoff Evidence

- Java gate: `GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :modules:contracts:test` passed.
- Rust parity gates: `cargo test -p dediren-contracts --test schema_contracts --locked` passed; `cargo test -p dediren-contracts --test contract_roundtrip --locked` passed.
- Whitespace gate: `git diff --check` passed.
- Software-design review: contract records stay under `modules/contracts`, with data-only source/layout/render/export/plugin packages plus JSON/schema helpers. No orchestration, plugin execution, SVG rendering, OEF/XMI serialization, or ELK interpretation was added to contracts.
- Test-quality audit: no block findings. Positive coverage includes version constants, envelope diagnostics, unknown-field rejection, required diagnostic fields, fixture round trips, schema compilation, valid/invalid schema validation, and first-party plugin manifests. Accepted warn: direct Java required-field checks are explicit for envelope/diagnostics in this slice; broader record requiredness remains enforced by checked-in JSON schemas.

## Rust-To-Java Contract Mapping

- Constants: `MODEL_SCHEMA_VERSION`, `ENVELOPE_SCHEMA_VERSION`, `PLUGIN_PROTOCOL_VERSION`, `LAYOUT_REQUEST_SCHEMA_VERSION`, `LAYOUT_RESULT_SCHEMA_VERSION`, `SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION`, `RENDER_RESULT_SCHEMA_VERSION`, `SVG_RENDER_POLICY_SCHEMA_VERSION`, `RENDER_METADATA_SCHEMA_VERSION`, `EXPORT_REQUEST_SCHEMA_VERSION`, `EXPORT_RESULT_SCHEMA_VERSION`, `OEF_EXPORT_POLICY_SCHEMA_VERSION`, `UML_XMI_EXPORT_POLICY_SCHEMA_VERSION` -> `dev.dediren.contracts.ContractVersions`.
- Envelope/diagnostics: `DiagnosticSeverity`, `Diagnostic`, `CommandEnvelope<T>` -> `dev.dediren.contracts.DiagnosticSeverity`, `Diagnostic`, `CommandEnvelope<T>`.
- Source: `SourceDocument`, `PluginRequirement`, `SourceNode`, `SourceRelationship`, `GenericGraphPluginData`, `GenericGraphSemanticProfile`, `GenericGraphView`, `GenericGraphViewKind`, `GenericGraphViewGroup`, `GenericGraphViewGroupRole` -> `dev.dediren.contracts.source.*`.
- Layout request/preferences: `LayoutPreferences`, `LayoutDirection`, `LayoutDensity`, `LayoutWrapping`, `LayoutRoutingPreferences`, `LayoutRoutingStyle`, `LayoutRoutingProfile`, `LayoutEndpointMerging`, `LayoutRequest`, `LayoutNode`, `LayoutEdge`, `LayoutGroup`, `GroupProvenance`, `SemanticBackedGroupProvenance`, `LayoutLabel`, `LayoutConstraint` -> `dev.dediren.contracts.layout.*`.
- Layout result/validation: `SemanticValidationResult`, `LayoutResult`, `LaidOutNode`, `LaidOutEdge`, `LaidOutGroup`, `Point` -> `dev.dediren.contracts.layout.*`.
- Render: `RenderPolicy`, `RenderMetadata`, `RenderMetadataSelector`, `Page`, `Margin`, `SvgStylePolicy`, `SvgBackgroundStyle`, `SvgFontStyle`, `SvgNodeStyle`, `SvgEdgeStyle`, `SvgNodeDecorator`, `SvgEdgeLineStyle`, `SvgEdgeMarkerEnd`, `SvgEdgeLabelHorizontalPosition`, `SvgEdgeLabelHorizontalSide`, `SvgEdgeLabelVerticalPosition`, `SvgEdgeLabelVerticalSide`, `SvgGroupStyle`, `RenderResult` -> `dev.dediren.contracts.render.*`.
- Export: `OefExportPolicy`, `OefExportInput`, `UmlXmiExportPolicy`, `ExportRequest`, `ExportResult` -> `dev.dediren.contracts.export.*`.
- Plugin runtime: `PluginManifest`, `RuntimeCapabilities` -> `dev.dediren.contracts.plugin.*`.
- JSON/schema helpers: Rust `serde`/`jsonschema` test helper behavior -> `dev.dediren.contracts.json.JsonSupport` and `dev.dediren.contracts.schema.SchemaValidator`.
