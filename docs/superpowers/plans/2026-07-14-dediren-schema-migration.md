# Dediren Schema Migration Implementation Plan

Status: complete — implemented on main as 999ecd6..570b790; UNRELEASED as of 2026-07-15: fold the breaking stale-policy hard-fail into the next release's notes.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a Dediren file written against an older schema tell its holder that it is stale, and where the upgrade steps are — instead of failing with a generic schema error (source model) or being silently accepted (policy files).

**Architecture:** `contracts` gains `KnownSchemaVersions`, a pure-data registry of every hand-authored schema family's version history and the JSON field(s) that carry it. `core` gains `SchemaVersionGate`, which classifies a parsed document's version as current, superseded, or unrecognized, and is wired in at two chokepoints: `SourceValidator.parseSourceDocument` for the model, and a new package-private `CoreCommands.parsePolicy` that all four policy parse sites funnel through. The upgrade prose lives in `docs/agent-usage.md`, served to agents as a new `dediren_guide` topic, and a bidirectional test pins registry against prose so neither can drift.

**Tech Stack:** Java 21, Maven (`./mvnw`), JUnit 5, AssertJ, Jackson 3 (`tools.jackson`), google-java-format via Spotless.

**Spec:** `docs/superpowers/specs/2026-07-14-dediren-schema-migration-design.md`

## Global Constraints

- Java 21+, built with the checked-in Maven Wrapper (`./mvnw`). Never `mvn`.
- Run `./mvnw -Pquality spotless:apply` before every commit that touches Java. The gate (`./mvnw -Pquality verify`) fails on unformatted code.
- **Agent environment note:** `./mvnw test` fails under the Claude Code sandbox (JUnit `@TempDir` needs a writable `/tmp`). Run Maven with the sandbox disabled.
- Module-scoped tests need `-am` and, when filtering, `-Dsurefire.failIfNoSpecifiedTests=false` — siblings are not installed.
- `contracts` owns data and constants only. No orchestration, no plugin logic (architecture guidelines §2).
- The `code()` string of an existing `DiagnosticCode` is a wire contract and must never change. New codes are additive.
- Anything an agent must act on belongs in the envelope's `diagnostics[]`, never in a log. `Logger.info`/`warn`/`error` are banned in first-party code and `ArchitectureRulesTest` fails the build on them.
- Every `DEDIREN_*` token named in `docs/agent-usage.md` must exist in a `.java` source file (`AgentUsageDocConsistencyTest`, dist-tool).
- No CalVer version string may appear in `docs/agent-usage.md` unless it matches the current product version (same test). **Key all migration entries by schema id, never by release.**
- Every `##` heading in `docs/agent-usage.md` must be reachable from a `GuideCatalog` topic (`GuideCatalogTest`). Adding the `## Migration` section without its topic fails the build.
- Git: direct commits to `main` are allowed. Stage by explicit path — never `git add -A`. The working tree has pre-existing untracked user dotfiles; leave them alone.

---

### Task 1: The version registry

**Files:**
- Create: `contracts/src/main/java/dev/dediren/contracts/KnownSchemaVersions.java`
- Test: `contracts/src/test/java/dev/dediren/contracts/KnownSchemaVersionsTest.java`

**Interfaces:**
- Consumes: `ContractVersions.MODEL_SCHEMA_VERSION`, `.RENDER_POLICY_SCHEMA_VERSION`, `.OEF_EXPORT_POLICY_SCHEMA_VERSION`, `.UML_XMI_EXPORT_POLICY_SCHEMA_VERSION` (all existing).
- Produces: `KnownSchemaVersions.Family` (record: `String name`, `List<String> versionFields`, `List<String> versions`) with methods `currentVersion()`, `priorVersions()`, `versionField()`; constants `MODEL`, `RENDER_POLICY`, `OEF_EXPORT_POLICY`, `UML_XMI_EXPORT_POLICY`, and `ALL`. Tasks 2, 4, and 5 all depend on these exact names.

- [ ] **Step 1: Write the failing test**

Create `contracts/src/test/java/dev/dediren/contracts/KnownSchemaVersionsTest.java`:

```java
package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnownSchemaVersionsTest {

  @Test
  void everyFamilysCurrentVersionIsItsContractVersionsConstant() {
    assertThat(KnownSchemaVersions.MODEL.currentVersion())
        .isEqualTo(ContractVersions.MODEL_SCHEMA_VERSION);
    assertThat(KnownSchemaVersions.RENDER_POLICY.currentVersion())
        .isEqualTo(ContractVersions.RENDER_POLICY_SCHEMA_VERSION);
    assertThat(KnownSchemaVersions.OEF_EXPORT_POLICY.currentVersion())
        .isEqualTo(ContractVersions.OEF_EXPORT_POLICY_SCHEMA_VERSION);
    assertThat(KnownSchemaVersions.UML_XMI_EXPORT_POLICY.currentVersion())
        .isEqualTo(ContractVersions.UML_XMI_EXPORT_POLICY_SCHEMA_VERSION);
  }

  @Test
  void renderPolicyCarriesItsShippedHistoryOldestFirst() {
    assertThat(KnownSchemaVersions.RENDER_POLICY.priorVersions())
        .containsExactly(
            "svg-render-policy.schema.v1", "render-policy.schema.v1", "render-policy.schema.v2");
  }

  @Test
  void renderPolicyKnowsTheFieldNameItUsedBeforeTheFamilyRename() {
    assertThat(KnownSchemaVersions.RENDER_POLICY.versionField())
        .isEqualTo("render_policy_schema_version");
    assertThat(KnownSchemaVersions.RENDER_POLICY.versionFields())
        .containsExactly("render_policy_schema_version", "svg_render_policy_schema_version");
  }

  @Test
  void familiesThatHaveNeverBeenBumpedHaveNoPriorVersions() {
    assertThat(KnownSchemaVersions.MODEL.priorVersions()).isEmpty();
    assertThat(KnownSchemaVersions.OEF_EXPORT_POLICY.priorVersions()).isEmpty();
    assertThat(KnownSchemaVersions.UML_XMI_EXPORT_POLICY.priorVersions()).isEmpty();
  }

  @Test
  void allListsEveryFamily() {
    assertThat(KnownSchemaVersions.ALL)
        .containsExactly(
            KnownSchemaVersions.MODEL,
            KnownSchemaVersions.RENDER_POLICY,
            KnownSchemaVersions.OEF_EXPORT_POLICY,
            KnownSchemaVersions.UML_XMI_EXPORT_POLICY);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw -pl contracts -am test -Dtest=KnownSchemaVersionsTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — compilation error, `KnownSchemaVersions` does not exist.

- [ ] **Step 3: Write the implementation**

Create `contracts/src/main/java/dev/dediren/contracts/KnownSchemaVersions.java`:

```java
package dev.dediren.contracts;

import java.util.List;

/**
 * The version history of every hand-authored Dediren schema family: which versions have shipped, in
 * order, and which JSON field carries the version.
 *
 * <p>This registry is what lets the version gate tell "a version I recognize as superseded" apart
 * from "a string I have never heard of" — the difference between an actionable upgrade instruction
 * and a shrug. Every entry in {@link Family#priorVersions()} must have a matching subsection in the
 * {@code ## Migration} section of {@code docs/agent-usage.md}; {@code MigrationRegistryTest} pins
 * the two together in both directions, so a bump cannot ship without its upgrade steps.
 *
 * <p>Data only: {@code contracts} owns no logic.
 */
public final class KnownSchemaVersions {

  /**
   * One hand-authored schema family.
   *
   * @param name the family's short name, used in diagnostic messages
   * @param versionFields the JSON field names that have carried this family's version, current
   *     first. More than one entry means the field itself was renamed, and a file written before
   *     that rename does not carry the current field name at all.
   * @param versions every version that has shipped, oldest first, current last. Never empty.
   */
  public record Family(String name, List<String> versionFields, List<String> versions) {
    public Family {
      versionFields = List.copyOf(versionFields);
      versions = List.copyOf(versions);
      if (versionFields.isEmpty()) {
        throw new IllegalArgumentException("family '" + name + "' must name its version field");
      }
      if (versions.isEmpty()) {
        throw new IllegalArgumentException("family '" + name + "' must have a current version");
      }
    }

    /** The version this build accepts. */
    public String currentVersion() {
      return versions.get(versions.size() - 1);
    }

    /** Every superseded version, oldest first. Empty when the family has never been bumped. */
    public List<String> priorVersions() {
      return versions.subList(0, versions.size() - 1);
    }

    /** The version field a file should carry today. */
    public String versionField() {
      return versionFields.get(0);
    }
  }

  public static final Family MODEL =
      new Family(
          "model", List.of("model_schema_version"), List.of(ContractVersions.MODEL_SCHEMA_VERSION));

  // The oldest entry is a different family id on purpose: the schema was renamed from
  // svg-render-policy to render-policy (238da5a), and the version field was renamed with it. A file
  // from before that rename carries svg_render_policy_schema_version and no
  // render_policy_schema_version at all, so the gate must know the old field name to recognize it.
  public static final Family RENDER_POLICY =
      new Family(
          "render-policy",
          List.of("render_policy_schema_version", "svg_render_policy_schema_version"),
          List.of(
              "svg-render-policy.schema.v1",
              "render-policy.schema.v1",
              "render-policy.schema.v2",
              ContractVersions.RENDER_POLICY_SCHEMA_VERSION));

  public static final Family OEF_EXPORT_POLICY =
      new Family(
          "oef-export-policy",
          List.of("oef_export_policy_schema_version"),
          List.of(ContractVersions.OEF_EXPORT_POLICY_SCHEMA_VERSION));

  public static final Family UML_XMI_EXPORT_POLICY =
      new Family(
          "uml-xmi-export-policy",
          List.of("uml_xmi_export_policy_schema_version"),
          List.of(ContractVersions.UML_XMI_EXPORT_POLICY_SCHEMA_VERSION));

  public static final List<Family> ALL =
      List.of(MODEL, RENDER_POLICY, OEF_EXPORT_POLICY, UML_XMI_EXPORT_POLICY);

  private KnownSchemaVersions() {}
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw -pl contracts -am test -Dtest=KnownSchemaVersionsTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add contracts/src/main/java/dev/dediren/contracts/KnownSchemaVersions.java \
        contracts/src/test/java/dev/dediren/contracts/KnownSchemaVersionsTest.java
git commit -m "feat(contracts): register the version history of every hand-authored schema"
```

---

### Task 2: The version gate

**Files:**
- Modify: `contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java` (add two constants after `SCHEMA_INVALID` in the "Source validation" block)
- Create: `core/src/main/java/dev/dediren/core/schema/SchemaVersionGate.java`
- Test: `core/src/test/java/dev/dediren/core/schema/SchemaVersionGateTest.java`

**Interfaces:**
- Consumes: `KnownSchemaVersions.Family` and its constants (Task 1); the existing `Diagnostic` record (`String code`, `DiagnosticSeverity severity`, `String message`, `String path`).
- Produces: `SchemaVersionGate.check(KnownSchemaVersions.Family, JsonNode) -> Optional<Diagnostic>` — empty when the document carries the family's current version. Tasks 3 and 4 both call exactly this. Also produces `DiagnosticCode.SCHEMA_VERSION_OUTDATED` and `DiagnosticCode.SCHEMA_VERSION_UNKNOWN`.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/dev/dediren/core/schema/SchemaVersionGateTest.java`:

```java
package dev.dediren.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.KnownSchemaVersions;
import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;

class SchemaVersionGateTest {

  @Test
  void theCurrentVersionPasses() {
    var policy =
        JsonSupport.readTree(
            "{\"render_policy_schema_version\":\""
                + ContractVersions.RENDER_POLICY_SCHEMA_VERSION
                + "\"}");

    assertThat(SchemaVersionGate.check(KnownSchemaVersions.RENDER_POLICY, policy)).isEmpty();
  }

  @Test
  void aSupersededVersionIsOutdatedAndNamesBothVersionsAndTheGuide() {
    var policy =
        JsonSupport.readTree("{\"render_policy_schema_version\":\"render-policy.schema.v2\"}");

    Diagnostic diagnostic =
        SchemaVersionGate.check(KnownSchemaVersions.RENDER_POLICY, policy).orElseThrow();

    assertThat(diagnostic.code()).isEqualTo("DEDIREN_SCHEMA_VERSION_OUTDATED");
    assertThat(diagnostic.message())
        .contains("render-policy.schema.v2")
        .contains(ContractVersions.RENDER_POLICY_SCHEMA_VERSION)
        .contains("dediren_guide");
    assertThat(diagnostic.path()).isEqualTo("$.render_policy_schema_version");
  }

  @Test
  void aFileFromBeforeTheVersionFieldRenameIsOutdatedNotUnknown() {
    // The oldest render policies carry svg_render_policy_schema_version and no
    // render_policy_schema_version at all. A gate that only read the current field name would call
    // this "unknown" and strand precisely the file that most needs the upgrade steps.
    var policy =
        JsonSupport.readTree(
            "{\"svg_render_policy_schema_version\":\"svg-render-policy.schema.v1\"}");

    Diagnostic diagnostic =
        SchemaVersionGate.check(KnownSchemaVersions.RENDER_POLICY, policy).orElseThrow();

    assertThat(diagnostic.code()).isEqualTo("DEDIREN_SCHEMA_VERSION_OUTDATED");
    assertThat(diagnostic.message()).contains("svg-render-policy.schema.v1");
  }

  @Test
  void anUnrecognizedVersionIsUnknown() {
    var policy =
        JsonSupport.readTree("{\"render_policy_schema_version\":\"render-policy.schema.v99\"}");

    Diagnostic diagnostic =
        SchemaVersionGate.check(KnownSchemaVersions.RENDER_POLICY, policy).orElseThrow();

    assertThat(diagnostic.code()).isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
    assertThat(diagnostic.message()).contains("render-policy.schema.v99");
  }

  @Test
  void anAbsentVersionFieldIsUnknownAndNamesTheFieldItWanted() {
    var policy = JsonSupport.readTree("{\"page\":{}}");

    Diagnostic diagnostic =
        SchemaVersionGate.check(KnownSchemaVersions.RENDER_POLICY, policy).orElseThrow();

    assertThat(diagnostic.code()).isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
    assertThat(diagnostic.message()).contains("render_policy_schema_version");
  }

  @Test
  void theSourceModelIsGatedTheSameWay() {
    var model = JsonSupport.readTree("{\"model_schema_version\":\"model.schema.v0\"}");

    Diagnostic diagnostic =
        SchemaVersionGate.check(KnownSchemaVersions.MODEL, model).orElseThrow();

    // model.schema has never been bumped, so there is no history to recognize v0 against: unknown
    // is the honest answer, and it still names the version this build wants.
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
    assertThat(diagnostic.message()).contains(ContractVersions.MODEL_SCHEMA_VERSION);
    assertThat(diagnostic.path()).isEqualTo("$.model_schema_version");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw -pl core -am test -Dtest=SchemaVersionGateTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — compilation error, `SchemaVersionGate` does not exist.

- [ ] **Step 3: Add the two diagnostic codes**

In `contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java`, find the "Source validation" block that begins with `SCHEMA_INVALID("DEDIREN_SCHEMA_INVALID"),` and insert immediately after that line:

```java
  // Schema version gating (core: SchemaVersionGate) over every hand-authored surface: the source
  // model and the three policy files. OUTDATED means the registry recognizes the version as
  // superseded and docs/agent-usage.md carries the upgrade steps, so the message points there.
  // UNKNOWN means the version is absent, misspelled, or from a newer bundle than this one — there
  // is nothing useful to say beyond naming the version this build wants.
  SCHEMA_VERSION_OUTDATED("DEDIREN_SCHEMA_VERSION_OUTDATED"),
  SCHEMA_VERSION_UNKNOWN("DEDIREN_SCHEMA_VERSION_UNKNOWN"),
```

- [ ] **Step 4: Write the gate**

Create `core/src/main/java/dev/dediren/core/schema/SchemaVersionGate.java`:

```java
package dev.dediren.core.schema;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.KnownSchemaVersions;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Rejects a hand-authored document whose schema version is not the one this build accepts, and —
 * when the version is one the registry recognizes — says so in terms the reader can act on.
 *
 * <p>The whole point is the difference between the two diagnostics it emits. {@code
 * SCHEMA_VERSION_OUTDATED} names the version found, the version wanted, and where the upgrade steps
 * live, so an agent holding the file can apply them without further help. {@code
 * SCHEMA_VERSION_UNKNOWN} is the honest shrug for a version this build has never heard of.
 */
public final class SchemaVersionGate {

  private static final String GUIDE_POINTER =
      "upgrade it with the 'Migration' section of the agent guide"
          + " (MCP: call dediren_guide with topic 'migration')";

  private SchemaVersionGate() {}

  /**
   * Returns a diagnostic when {@code document} does not carry {@code family}'s current schema
   * version, or empty when it does.
   */
  public static Optional<Diagnostic> check(KnownSchemaVersions.Family family, JsonNode document) {
    String found = findVersion(family, document);
    if (family.currentVersion().equals(found)) {
      return Optional.empty();
    }
    if (found != null && family.priorVersions().contains(found)) {
      return Optional.of(
          diagnostic(
              DiagnosticCode.SCHEMA_VERSION_OUTDATED,
              family,
              "'"
                  + found
                  + "' is a superseded "
                  + family.name()
                  + " schema version; this build accepts '"
                  + family.currentVersion()
                  + "'. To fix, "
                  + GUIDE_POINTER
                  + "."));
    }
    String describedAs =
        found == null ? "no '" + family.versionField() + "' field" : "'" + found + "'";
    return Optional.of(
        diagnostic(
            DiagnosticCode.SCHEMA_VERSION_UNKNOWN,
            family,
            describedAs
                + " is not a "
                + family.name()
                + " schema version this build knows; it accepts '"
                + family.currentVersion()
                + "'."));
  }

  /**
   * The version string this document carries, or null when it carries none.
   *
   * <p>Falls back to the family's legacy field names, so a file written before a version-field
   * rename is still recognized as outdated rather than dismissed as unknown.
   */
  private static String findVersion(KnownSchemaVersions.Family family, JsonNode document) {
    for (String field : family.versionFields()) {
      JsonNode value = document.get(field);
      if (value != null && value.isTextual()) {
        return value.asText();
      }
    }
    return null;
  }

  private static Diagnostic diagnostic(
      DiagnosticCode code, KnownSchemaVersions.Family family, String message) {
    return new Diagnostic(
        code.code(), DiagnosticSeverity.ERROR, message, "$." + family.versionField());
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./mvnw -pl core -am test -Dtest=SchemaVersionGateTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, 6 tests.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java \
        core/src/main/java/dev/dediren/core/schema/SchemaVersionGate.java \
        core/src/test/java/dev/dediren/core/schema/SchemaVersionGateTest.java
git commit -m "feat(core): gate hand-authored schema versions with an actionable diagnostic"
```

---

### Task 3: Gate the source model

The gate must run **before** JSON Schema validation. Today a stale `model_schema_version` trips the `const` in `schemas/model.schema.json` and surfaces as a generic `DEDIREN_SCHEMA_INVALID`; running the gate first replaces that with a diagnostic that names the version this build wants.

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/source/SourceValidator.java` (method `parseSourceDocument`, around line 217)
- Test: `core/src/test/java/dev/dediren/core/source/SourceValidatorTest.java` (add one test)

**Interfaces:**
- Consumes: `SchemaVersionGate.check` and `KnownSchemaVersions.MODEL` (Tasks 1–2).
- Produces: no new API. Changes the diagnostic code emitted for a bad `model_schema_version` from `DEDIREN_SCHEMA_INVALID` to `DEDIREN_SCHEMA_VERSION_UNKNOWN`.

- [ ] **Step 1: Find any existing test that pins the old behavior**

```bash
grep -rn "SCHEMA_INVALID" core/src/test cli/src/test mcp-server/src/test
```

Read each hit. If one asserts `DEDIREN_SCHEMA_INVALID` for a document whose *only* fault is a wrong or missing `model_schema_version`, it is now asserting the wrong code — update it to expect `DEDIREN_SCHEMA_VERSION_UNKNOWN` in Step 5 rather than working around it. Hits about other schema faults (bad node geometry, malformed JSON) are unaffected and must keep passing.

- [ ] **Step 2: Write the failing test**

Add to `core/src/test/java/dev/dediren/core/source/SourceValidatorTest.java`:

```java
  @Test
  void aModelWithAnUnknownSchemaVersionSaysSoInsteadOfFailingGenericSchemaValidation() {
    ValidationResult result =
        SourceValidator.validateSourceJson(
            """
            {
              "model_schema_version": "model.schema.v0",
              "nodes": [],
              "relationships": [],
              "plugins": { "generic-graph": { "views": [] } }
            }
            """,
            null);

    assertThat(result.exitCode()).isNotZero();
    List<Diagnostic> diagnostics = result.envelope().diagnostics();
    assertThat(diagnostics).hasSize(1);
    assertThat(diagnostics.get(0).code()).isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
    assertThat(diagnostics.get(0).message()).contains("model.schema.v1");
    assertThat(diagnostics.get(0).path()).isEqualTo("$.model_schema_version");
  }
```

Add `import java.util.List;` if the file does not already have it.

- [ ] **Step 3: Run test to verify it fails**

```bash
./mvnw -pl core -am test -Dtest=SourceValidatorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — the diagnostic code is `DEDIREN_SCHEMA_INVALID` (the schema `const` mismatch), not `DEDIREN_SCHEMA_VERSION_UNKNOWN`.

- [ ] **Step 4: Wire the gate in**

In `core/src/main/java/dev/dediren/core/source/SourceValidator.java`, method `parseSourceDocument`, insert the gate between `readTree` and the `SchemaValidator` call so it reads:

```java
  private static SourceDocument parseSourceDocument(String text) throws SourceDiagnosticsException {
    JsonNode value;
    try {
      value = JsonSupport.objectMapper().readTree(text);
    } catch (JacksonException error) {
      throw new SourceDiagnosticsException(List.of(schemaError(error.getMessage())));
    }
    // Before structural validation: a stale version makes every downstream schema error noise, and
    // the const mismatch it would otherwise produce never says "your file is old".
    Optional<Diagnostic> staleVersion = SchemaVersionGate.check(KnownSchemaVersions.MODEL, value);
    if (staleVersion.isPresent()) {
      throw new SourceDiagnosticsException(List.of(staleVersion.get()));
    }
    var errors =
        SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
            .validate("schemas/model.schema.json", value);
    if (!errors.isEmpty()) {
      throw new SourceDiagnosticsException(
          errors.stream().map(SourceValidator::schemaError).toList());
    }
    try {
      return JsonSupport.objectMapper().treeToValue(value, SourceDocument.class);
    } catch (JacksonException error) {
      throw new SourceDiagnosticsException(List.of(schemaError(error.getMessage())));
    }
  }
```

Add the imports:

```java
import dev.dediren.contracts.KnownSchemaVersions;
import dev.dediren.core.schema.SchemaVersionGate;
import java.util.Optional;
```

- [ ] **Step 5: Update any existing test found in Step 1**

Change any assertion that expected `DEDIREN_SCHEMA_INVALID` purely for a bad `model_schema_version` to expect `DEDIREN_SCHEMA_VERSION_UNKNOWN`. Do not weaken assertions about other schema faults.

- [ ] **Step 6: Run the module's tests**

```bash
./mvnw -pl core -am test
```

Expected: PASS. If `SourceValidatorTest`'s bundle-root tests fail, check they still declare `"model_schema_version": "model.schema.v1"` — they write a permissive `{}` schema to a temp bundle root, so the gate (which does not read the schema file) is now the first thing to reject a wrong version there.

- [ ] **Step 7: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add core/src/main/java/dev/dediren/core/source/SourceValidator.java \
        core/src/test/java/dev/dediren/core/source/SourceValidatorTest.java
git commit -m "feat(core): tell a stale source model it is stale, before schema validation"
```

---

### Task 4: Gate the policy files

Every policy enters `core` as raw text and is parsed by the package-private `CoreCommands.parseJson`. There are four such sites — the standalone `render` and `export` commands, and `build`'s render and export lanes. Rather than gate four times, add one chokepoint they all call.

**This task breaks existing tests on purpose.** Seven of the twelve `new BuildRequest(...)` call sites in tests pass a bare `"{}"` as policy text, and `EngineEnvelopeContractTest:216` passes `"{}"` to `exportCommand`. Under the gate, `{}` carries no version field and is rejected. Those tests were asserting build behavior with policies that declare no version at all; fixing them is part of this task, not a workaround.

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/commands/CoreCommands.java` (add `parsePolicy`, `exportPolicyFamily`, `parseExportPolicy`; use them in `renderCommand` ~line 277 and `exportCommand` ~line 303)
- Modify: `core/src/main/java/dev/dediren/core/commands/BuildCommand.java` (render lane ~line 268; `runExportStage` ~line 334)
- Modify: `core/src/test/java/dev/dediren/core/commands/BuildCommandTest.java` (stub policies)
- Modify: `cli/src/test/java/dev/dediren/cli/EngineEnvelopeContractTest.java` (stub policy at ~line 216)
- Test: `core/src/test/java/dev/dediren/core/commands/BuildCommandTest.java` (add one test)

**Interfaces:**
- Consumes: `SchemaVersionGate.check`, `KnownSchemaVersions.{RENDER_POLICY, OEF_EXPORT_POLICY, UML_XMI_EXPORT_POLICY}` (Tasks 1–2); the existing `EngineExecutionException.command(String code, String command, String message)`.
- Produces: package-private `CoreCommands.parsePolicy(String command, String text, KnownSchemaVersions.Family family)` and `CoreCommands.parseExportPolicy(String engineId, String policyText)`, both `throws EngineExecutionException`.

- [ ] **Step 1: Write the failing test**

Add to `core/src/test/java/dev/dediren/core/commands/BuildCommandTest.java`:

```java
  @Test
  void aStaleRenderPolicyFailsTheBuildBeforeAnyArtifactIsWritten(@TempDir Path out) {
    BuildRequest request =
        new BuildRequest(
            SOURCE,
            null,
            List.of(),
            "{\"render_policy_schema_version\":\"render-policy.schema.v2\"}",
            null,
            null,
            Set.of(),
            out,
            Map.of());

    EngineRunOutcome outcome = BuildCommand.run(request, engines());

    assertThat(outcome.exitCode()).isNotZero();
    assertThat(outcome.stdout()).contains("DEDIREN_SCHEMA_VERSION_OUTDATED");
    assertThat(outcome.stdout()).contains("render-policy.schema.v2");
    assertThat(out).isEmptyDirectory();
  }
```

(`EngineRunOutcome` is `record EngineRunOutcome(String stdout, int exitCode)`, so `stdout()` is the envelope JSON and `exitCode()` is the process exit code — the same accessors the other tests in this file use.)

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw -pl core -am test -Dtest=BuildCommandTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — the build succeeds and writes an SVG, because nothing checks the policy version.

- [ ] **Step 3: Add the chokepoint to CoreCommands**

In `core/src/main/java/dev/dediren/core/commands/CoreCommands.java`, add these three methods next to the existing `parseJson` (around line 337):

```java
  /**
   * Parses a policy document and rejects it when it does not carry {@code family}'s current schema
   * version. Every policy lane — the standalone render and export commands and both build lanes —
   * goes through here, so a stale policy is caught once, before any engine runs.
   */
  static JsonNode parsePolicy(String command, String text, KnownSchemaVersions.Family family)
      throws EngineExecutionException {
    JsonNode policy = parseJson(command, text);
    Optional<Diagnostic> stale = SchemaVersionGate.check(family, policy);
    if (stale.isPresent()) {
      Diagnostic diagnostic = stale.get();
      throw EngineExecutionException.command(diagnostic.code(), command, diagnostic.message());
    }
    return policy;
  }

  /**
   * The policy family an export engine id expects, or empty for an id that is neither export
   * engine. Empty skips the gate and lets {@code requireEngine} raise {@code
   * DEDIREN_PLUGIN_UNKNOWN}, which preserves today's error precedence: a malformed policy is
   * reported before an unknown engine.
   */
  static Optional<KnownSchemaVersions.Family> exportPolicyFamily(String engineId) {
    return switch (engineId) {
      case "archimate-oef" -> Optional.of(KnownSchemaVersions.OEF_EXPORT_POLICY);
      case "uml-xmi" -> Optional.of(KnownSchemaVersions.UML_XMI_EXPORT_POLICY);
      default -> Optional.empty();
    };
  }

  /** The export lanes' policy parse: which family applies depends on which engine is running. */
  static JsonNode parseExportPolicy(String engineId, String policyText)
      throws EngineExecutionException {
    Optional<KnownSchemaVersions.Family> family = exportPolicyFamily(engineId);
    return family.isPresent()
        ? parsePolicy("export", policyText, family.get())
        : parseJson("export", policyText);
  }
```

Add the imports:

```java
import dev.dediren.contracts.KnownSchemaVersions;
import dev.dediren.core.schema.SchemaVersionGate;
import java.util.Optional;
```

(`Diagnostic` and `JsonNode` are already imported in this file.)

- [ ] **Step 4: Route all four parse sites through it**

In `CoreCommands.renderCommand`, replace:

```java
    JsonNode policy = parseJson("render", policyText);
```

with:

```java
    JsonNode policy = parsePolicy("render", policyText, KnownSchemaVersions.RENDER_POLICY);
```

In `CoreCommands.exportCommand`, replace:

```java
    JsonNode policy = parseJson("export", policyText);
```

with:

```java
    JsonNode policy = parseExportPolicy(engineId, policyText);
```

In `BuildCommand`, render lane (~line 268), replace:

```java
                JsonNode policy = CoreCommands.parseJson("render", request.renderPolicyText());
```

with:

```java
                JsonNode policy =
                    CoreCommands.parsePolicy(
                        "render", request.renderPolicyText(), KnownSchemaVersions.RENDER_POLICY);
```

In `BuildCommand.runExportStage` (~line 334), replace:

```java
          JsonNode policy = CoreCommands.parseJson("export", policyText);
```

with:

```java
          JsonNode policy = CoreCommands.parseExportPolicy(engineId, policyText);
```

Add `import dev.dediren.contracts.KnownSchemaVersions;` to `BuildCommand.java`.

- [ ] **Step 5: Run the test to verify it passes, and see what else broke**

```bash
./mvnw -pl core,cli -am test
```

Expected: the new test PASSES. Expect roughly seven other failures in `BuildCommandTest` and one in `EngineEnvelopeContractTest`, all of the form `DEDIREN_SCHEMA_VERSION_UNKNOWN` where a test passed `"{}"` as a policy. That is the gate working.

- [ ] **Step 6: Give every stub policy a real version**

For each failing test, replace the bare `"{}"` policy text with a policy that declares the current version. The fake engines in these tests ignore the policy body, so the version field alone is enough:

- render policy → `"{\"render_policy_schema_version\":\"render-policy.schema.v3\"}"`
- OEF policy → `"{\"oef_export_policy_schema_version\":\"oef-export-policy.schema.v1\"}"`
- XMI policy → `"{\"uml_xmi_export_policy_schema_version\":\"uml-xmi-export-policy.schema.v1\"}"`

Prefer defining these as `private static final String` constants at the top of `BuildCommandTest` (for example `RENDER_POLICY`, `OEF_POLICY`, `XMI_POLICY`) and referencing them, rather than repeating the literals at seven call sites.

**Read `EngineEnvelopeContractTest:216` before changing it.** If that `"{}"` is deliberately testing an *invalid policy* path, the right fix is to assert the new code rather than to make the policy valid — check what the test claims to prove before editing it.

- [ ] **Step 7: Run the tests again**

```bash
./mvnw -pl core,cli -am test
```

Expected: PASS, all green.

- [ ] **Step 8: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add core/src/main/java/dev/dediren/core/commands/CoreCommands.java \
        core/src/main/java/dev/dediren/core/commands/BuildCommand.java \
        core/src/test/java/dev/dediren/core/commands/BuildCommandTest.java \
        cli/src/test/java/dev/dediren/cli/EngineEnvelopeContractTest.java
git commit -m "feat(core): stop silently accepting policy files that declare a stale schema version"
```

---

### Task 5: The migration guide

**Files:**
- Modify: `docs/agent-usage.md` (add `## Migration`, after `## Repair Rules` — around line 827)
- Modify: `mcp-server/src/main/java/dev/dediren/mcp/GuideCatalog.java` (one topic)
- Modify: `mcp-server/src/main/java/dev/dediren/mcp/DedirenMcpServer.java` (`dediren_build` tool description)
- Test: `mcp-server/src/test/java/dev/dediren/mcp/MigrationRegistryTest.java`

**Interfaces:**
- Consumes: `KnownSchemaVersions.ALL` and `Family.priorVersions()` (Task 1); the existing `GuideCatalog.section(String)`.
- Produces: guide topic `migration`; the `## Migration` section as the single home of upgrade prose.

- [ ] **Step 1: Write the failing test**

Create `mcp-server/src/test/java/dev/dediren/mcp/MigrationRegistryTest.java`:

```java
package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.KnownSchemaVersions;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the version registry against the migration prose in both directions.
 *
 * <p>A superseded version with no upgrade steps is a dead end for whoever holds that file, and
 * upgrade steps for a version the gate does not recognize are unreachable. Neither can ship.
 */
class MigrationRegistryTest {

  private static final Pattern STEP_HEADING = Pattern.compile("### (\\S+) → (\\S+)");

  @Test
  void everySupersededVersionHasUpgradeSteps() {
    String migration = GuideCatalog.section("migration");
    List<String> undocumented = new ArrayList<>();

    for (KnownSchemaVersions.Family family : KnownSchemaVersions.ALL) {
      for (String prior : family.priorVersions()) {
        if (!migration.contains("### " + prior + " → ")) {
          undocumented.add(prior);
        }
      }
    }

    assertThat(undocumented)
        .as(
            "every superseded version in KnownSchemaVersions needs a '### <from> → <to>'"
                + " subsection under '## Migration' in docs/agent-usage.md — the gate points"
                + " people there, so a missing entry is a dead end")
        .isEmpty();
  }

  @Test
  void everyUpgradeStepDescribesAVersionTheGateRecognizes() {
    List<String> known = new ArrayList<>();
    for (KnownSchemaVersions.Family family : KnownSchemaVersions.ALL) {
      known.addAll(family.priorVersions());
    }

    Matcher matcher = STEP_HEADING.matcher(GuideCatalog.section("migration"));
    List<String> orphaned = new ArrayList<>();
    while (matcher.find()) {
      if (!known.contains(matcher.group(1))) {
        orphaned.add(matcher.group(1));
      }
    }

    assertThat(orphaned)
        .as(
            "every '### <from> → <to>' subsection must describe a version listed in"
                + " KnownSchemaVersions — otherwise the gate never sends anyone to it")
        .isEmpty();
  }

  @Test
  void theMigrationTopicIsReachable() {
    assertThat(GuideCatalog.topics()).contains("migration");
    assertThat(GuideCatalog.section("migration")).doesNotContain("unknown topic");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw -pl mcp-server -am test -Dtest=MigrationRegistryTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL — `GuideCatalog.section("migration")` returns "unknown topic 'migration'".

- [ ] **Step 3: Write the migration section**

In `docs/agent-usage.md`, add this section immediately after `## Repair Rules` (before `## Plugin Environment`):

```markdown
## Migration

`DEDIREN_SCHEMA_VERSION_OUTDATED` means the file declares a schema version this
build has superseded. Find the version it declares below and apply each step in
order until it declares the current one. `DEDIREN_SCHEMA_VERSION_UNKNOWN` means
the version is absent, misspelled, or newer than this build — there is no
upgrade path; fix the version field or use a newer bundle.

Entries are keyed by schema id, not by release. A schema id changes only when
the contract changes, so it is the only durable signal of what a file needs.

### svg-render-policy.schema.v1 → render-policy.schema.v1

The family was renamed, and the version field was renamed with it. Rename the
field `svg_render_policy_schema_version` to `render_policy_schema_version` and
set its value to `render-policy.schema.v1`. Nothing else changes.

### render-policy.schema.v1 → render-policy.schema.v2

Raster output was dropped. Remove the top-level `raster` block (its `scale` and
`background` keys) and set `render_policy_schema_version` to
`render-policy.schema.v2`. There is no replacement: renders are SVG only.

### render-policy.schema.v2 → render-policy.schema.v3

Interactive SVG was retired. Remove the top-level `interactive` key (`none`,
`svg`, `html`, or `both`) and the `interaction` block under `style` (its
`highlight_stroke` and `highlight_stroke_width` keys), then set
`render_policy_schema_version` to `render-policy.schema.v3`. There is no
replacement: renders are static.
```

The `→` characters are U+2192 and the test matches on them literally. Write this with an editor, not a shell heredoc.

- [ ] **Step 4: Register the topic**

In `mcp-server/src/main/java/dev/dediren/mcp/GuideCatalog.java`, in `topicMap()`, add after the `repair` entry:

```java
    topics.put("migration", "Migration");
```

- [ ] **Step 5: Point the build tool at it**

In `mcp-server/src/main/java/dev/dediren/mcp/DedirenMcpServer.java`, extend the `dediren_build` tool description so a model that hits a stale policy knows where to go. Replace the description string with:

```java
                      "Compile a Dediren source model into artifacts (SVG render, ArchiMate OEF,"
                          + " and/or UML XMI) under an output directory. Select a lane by passing"
                          + " its policy: render_policy, oef_policy, xmi_policy. Returns the"
                          + " build-result envelope, which names every artifact written. A"
                          + " DEDIREN_SCHEMA_VERSION_OUTDATED error means a source or policy file"
                          + " declares a superseded schema version: call dediren_guide with topic"
                          + " 'migration' for the upgrade steps."
```

- [ ] **Step 6: Run the tests**

```bash
./mvnw -pl mcp-server -am test
```

Expected: PASS — `MigrationRegistryTest` (3 tests) and `GuideCatalogTest`, which now sees `## Migration` reachable from the `migration` topic.

- [ ] **Step 7: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add docs/agent-usage.md \
        mcp-server/src/main/java/dev/dediren/mcp/GuideCatalog.java \
        mcp-server/src/main/java/dev/dediren/mcp/DedirenMcpServer.java \
        mcp-server/src/test/java/dev/dediren/mcp/MigrationRegistryTest.java
git commit -m "feat(mcp): serve schema migration steps as a dediren_guide topic"
```

---

### Task 6: Keep the registry fed, and verify the whole thing

A registry nobody is required to update rots after one release. The rule below is what makes the next schema bump ship its upgrade steps instead of stranding files the way `render-policy` v1 and v2 were stranded.

**Files:**
- Modify: `CLAUDE.md` (the `## Files That Move Together` list)

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: nothing in code.

- [ ] **Step 1: Add the rule**

In `CLAUDE.md`, under `## Files That Move Together`, add this bullet after the "Public JSON shape changes" bullet:

```markdown
- Breaking schema-version bumps: update the schema, the `ContractVersions`
  constant, the `KnownSchemaVersions` family (append the new version; the old
  one becomes a prior version), and a `### <from> → <to>` subsection under
  `## Migration` in `docs/agent-usage.md` together. `MigrationRegistryTest`
  fails the build if a superseded version has no upgrade steps. If the version
  *field* is renamed, add the old field name to the family's `versionFields`.
```

- [ ] **Step 2: Run the full suite**

```bash
./mvnw test
```

Expected: PASS, everything green. This is the first run that exercises `AgentUsageDocConsistencyTest` (dist-tool) against the new guide section — it verifies both new `DEDIREN_*` tokens exist in source and that the section introduced no stray CalVer string.

- [ ] **Step 3: Run the quality gate**

```bash
./mvnw -Pquality verify
```

Expected: PASS — formatting clean, no SpotBugs findings, `ArchitectureRulesTest` green (the gate logs nothing; it reports through diagnostics).

- [ ] **Step 4: Run the distribution smoke**

```bash
./mvnw -pl dist-tool -am verify -Pdist-smoke
```

Expected: PASS. The bundled copy of `agent-usage.md` carries the new section, so the guide tool serves it from a real bundle.

- [ ] **Step 5: Confirm the working tree is clean and commit**

```bash
git diff --check
git status --short --branch
git add CLAUDE.md
git commit -m "docs(claude): require a migration entry with every breaking schema bump"
```

The pre-existing untracked dotfiles in the working tree are the user's; leave them untracked.

---

## Notes for the Implementer

**Why there is no `dediren migrate` command.** The consumer of these diagnostics is an agent that already has the file open. A precise prose delta is something it can apply directly, so a rewriter would be a large amount of code to save an edit the agent is already good at. If you find yourself wanting one, re-read the spec's decision 2 first.

**The known asymmetry.** `dediren_validate` takes a source and an optional profile — it never sees a policy. So a stale *policy* is caught at `dediren_build`, not at validate time. This is deliberate (a policy is only meaningful to a build) and is recorded in the spec's Out of Scope. Do not widen `validate` as part of this work.

**This is a breaking runtime change.** A policy file that declares a stale version but happens to still fit the current record runs today and will hard-fail after this. That is the defect being fixed, but when this reaches a release, it belongs in the release notes. It does **not** warrant a schema-id bump — the schemas do not change, only whether they are enforced, and bumping ids would strand the very files this exists to rescue.
