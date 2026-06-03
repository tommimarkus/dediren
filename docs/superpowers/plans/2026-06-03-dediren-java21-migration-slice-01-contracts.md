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

- [ ] **Task 1: Enumerate contract surface**
  - Model: `gpt-5-mini`
  - Run: `rg -n "pub struct|pub enum|pub const|SCHEMA_VERSION|schema_version" crates/dediren-contracts/src crates/dediren-contracts/tests`
  - Create a checklist in the slice handoff with each Rust public type mapped to a Java record/class.
  - Expected coverage includes command envelopes, diagnostics, plugin manifests, runtime capability, source document, layout request/result, render metadata/policy/result, export request/result, and semantic validation result.

- [ ] **Task 2: Port schema constants**
  - Model: `gpt-5-codex`
  - Add `ContractVersions` with string constants matching the current Rust constants exactly.
  - Add a JUnit test that asserts every constant equals the expected schema id string.
  - Run: `./gradlew :modules:contracts:test --tests '*ContractVersionsTest'`
  - Expected: pass.

- [ ] **Task 3: Port command envelope and diagnostics**
  - Model: `gpt-5-codex`
  - Add `CommandEnvelope<T>` and `Diagnostic` records.
  - Preserve success/error envelope JSON field names and diagnostic severity strings.
  - Add round-trip tests using fixture JSON copied from existing Rust tests or generated from checked-in fixtures.
  - Expected: serialized JSON matches current envelope shape and rejects missing required fields where Rust rejects them.

- [ ] **Task 4: Port plugin manifest and runtime capability contracts**
  - Model: `gpt-5-codex`
  - Add Java records for plugin manifest, capability list, runtime capability, runtime requirements, and allowed env.
  - Add tests that validate every `fixtures/plugins/*.manifest.json` file against the Java records and checked-in schema.
  - Run: `./gradlew :modules:contracts:test --tests '*Plugin*'`
  - Expected: all fixture manifests parse and validate.

- [ ] **Task 5: Port source, layout, render, and export contracts**
  - Model: `gpt-5-codex`
  - Add Java records for all source, layout, render, and export contracts.
  - Use explicit records for stable concepts; use `JsonNode` only for plugin-owned extension values and policy fragments where the Rust contract allows arbitrary JSON.
  - Add round-trip tests for representative fixtures under `fixtures/source`, `fixtures/layout-request`, `fixtures/layout-result`, `fixtures/render-policy`, and export policies.
  - Expected: Java reads and writes fixture-compatible JSON without adding authored geometry or styling to source documents.

- [ ] **Task 6: Port schema validation helper**
  - Model: `gpt-5-codex`
  - Add a schema validator that loads checked-in schema files from the repository root or classpath test resources.
  - Add tests equivalent to `all_public_schemas_compile`, `valid_source_matches_model_schema`, and representative negative schema tests.
  - Run: `./gradlew :modules:contracts:test`
  - Expected: pass.

- [ ] **Task 7: Run audit checks**
  - Model: `gpt-5`
  - Use `souroldgeezer-design:software-design` Review for contract ownership and package boundaries.
  - Use `souroldgeezer-audit:test-quality-audit` Deep on `modules/contracts/src/test`.
  - Fix block findings before commit.

- [ ] **Task 8: Verify and commit**
  - Model: `gpt-5-mini`
  - Run: `./gradlew :modules:contracts:test`
  - Run: `cargo test -p dediren-contracts --test schema_contracts --locked`
  - Run: `cargo test -p dediren-contracts --test contract_roundtrip --locked`
  - Run: `git diff --check`
  - Commit message: `feat: port contracts to java`
