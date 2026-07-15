# Coherence Remediation (2026-07-15 review) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

Status: planned — not started.

**Goal:** Close every finding of the 2026-07-15 project coherence review: make the shipped docs stop contradicting shipped code, bring `layout-request` inside the schema-version-gating design, teach the governance docs about the MCP world, and pin the recurring drift classes with tests so they cannot reopen.

**Architecture:** Three waves. Wave 1 (Tasks 1–6) is code + new guard tests — the layout-request family/gate, migration-heading hardening, coverage-aggregate and logging-provider pins, bundle-id constant. Wave 2 (Tasks 7–9) is the shipped agent guide: new/changed sections plus a new reverse-direction consistency test that makes undocumented diagnostic codes a build failure. Wave 3 (Tasks 10–12) is governance and status hygiene: CLAUDE.md, architecture-guidelines, spec/plan status stamps, deletions. Task 13 is the final gate + audits.

**Tech Stack:** Java 21, Maven (`./mvnw`), JUnit 6 + AssertJ, ArchUnit (existing), google-java-format via `-Pquality`.

## Findings → task traceability

| Review finding | Task |
| --- | --- |
| agent-usage "read nothing else" vs `DEDIREN_{OEF,XMI}_SCHEMA_VALIDATOR`; threat-model never names the knob | 7 |
| CLI footer promises "fragments" guide coverage that doesn't exist; 5 `DEDIREN_FRAGMENT_*` codes undocumented | 8 |
| `layout-request` hand-authorable but ungated (bare `IllegalArgumentException`, no family, no migration entry) | 1, 3 |
| MigrationRegistryTest ignores the `→ <to>` side of headings | 2 |
| 58 emitted codes undocumented; doc-token enforcement is one-way | 7, 8, 9 |
| agent-usage MCP tool list omits `profile`/`views`/`emit` | 7 |
| Guide preamble (redistribution obligation) unreachable via `dediren_guide`; Maven Wrapper sentence breaks bundle self-containedness | 8 |
| CLAUDE.md: zero MCP (no rule/lane/move-together row; stdout claim reads false under `dediren mcp`); R2 clause unfollowable for generated families; DistModuleTest wrongly a version surface; sweep list omits docs/features | 10 |
| guidelines: `mcp` pre-rename name; `schema-cache → contracts` edge gone; cli `ir` runtime scope unannotated; coverage-report "every product module" claim; engines-never-depend is production-only; SLF4J sentence inexact; ArchUnit comment omits cli→mcp; §4 lacks the MCP stdout carve-out | 5, 11 |
| Boundary routing never reaches the MCP design spec | 10, 11 |
| coverage-report omits mcp-server (recurring under-count class) | 4 |
| `dediren-bundle.schema.v2` triple literal | 6 |
| Two release-notes fragments past their delete-by; 3 false status lines; PNG spec unsuperseded; ~12 done-but-unmarked plans; 7 jqwik references; bend-jitter/padding deferrals recorded nowhere; inherited-followups Item 1 unannotated | 12 |

## Global Constraints

- Base the work on current `main` (`a75154c` at branch time; main carries unpushed user commits — do NOT rebase or push them). Branch `fix/coherence-remediation` in worktree `.worktrees/coherence-remediation`, land with a local `--no-ff` merge.
- A sibling worktree (`.worktrees/mcp-cli-parity`) is implementing the MCP↔CLI parity spec concurrently. Do not touch its surfaces (`BuildCommand`/`BuildRequest` emit/views validation, `Main.BuildCommand`, `CliMcpParityTest`); expect a small `docs/agent-usage.md` merge reconciliation at integration time, and integrate whichever branch lands second with care.
- No version bump and no tag in this plan (release-policy: bump rides a later release; that release's notes must mention the unreleased breaking stale-policy hard-fail — noted in Task 12's stamp for the schema-migration plan).
- Run `./mvnw -Pquality spotless:apply` before every commit that touches Java.
- `docs/agent-usage.md` edits must not introduce any CalVer string, and every `DEDIREN_*` token added must exist in `.java` source (AgentUsageDocConsistencyTest enforces both).
- Every new `## ` heading in `docs/agent-usage.md` needs a topic entry in `GuideCatalog.topicMap()` (GuideCatalogTest enforces bidirectionally).
- Do not touch `docs/superpowers/` history semantics: status stamps are *additive* lines; never rewrite plan bodies (repo policy keeps them authored-against their era).
- Untracked junk outside scope: leave the user's root dotfiles alone; the stale `mcp/` directory (pre-rename `target/` leftovers) may be deleted locally but is not part of any commit.

## Audit gates (CLAUDE.md `## Audit Gates`)

Before calling this plan complete: `souroldgeezer-audit:test-quality-audit` **Deep** over the tests added/changed in Tasks 1–5 and 9; `souroldgeezer-audit:devsecops-audit` **Quick** over Task 7 (env-var/trust-boundary docs + threat-model edit) and Task 5 (new guard). Fix blocks; fix or explicitly accept warns in the handoff.

---

### Task 1: LAYOUT_REQUEST family + migration entry

**Files:**
- Modify: `contracts/src/main/java/dev/dediren/contracts/KnownSchemaVersions.java`
- Modify: `contracts/src/test/java/dev/dediren/contracts/KnownSchemaVersionsTest.java`
- Modify: `docs/agent-usage.md` (append one `###` subsection at the end of `## Migration`, currently ends line 857)

**Interfaces:**
- Produces: `KnownSchemaVersions.LAYOUT_REQUEST` (`Family`, name `"layout-request"`, field `layout_request_schema_version`, versions `["layout-request.schema.v1", ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION]`), member of `KnownSchemaVersions.ALL`. Task 3 gates on it; MigrationRegistryTest picks it up automatically.

- [ ] **Step 1: Write the failing tests** — in `KnownSchemaVersionsTest`, extend `allListsEveryFamily` and add:

```java
@Test
void layoutRequestCarriesItsShippedHistoryOldestFirst() {
  assertThat(KnownSchemaVersions.LAYOUT_REQUEST.priorVersions())
      .containsExactly("layout-request.schema.v1");
  assertThat(KnownSchemaVersions.LAYOUT_REQUEST.currentVersion())
      .isEqualTo(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);
  assertThat(KnownSchemaVersions.LAYOUT_REQUEST.versionField())
      .isEqualTo("layout_request_schema_version");
}
```

and in `allListsEveryFamily` append `KnownSchemaVersions.LAYOUT_REQUEST` to the `containsExactly(...)` list (order: after `UML_XMI_EXPORT_POLICY`).

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl contracts test -Dtest=KnownSchemaVersionsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `LAYOUT_REQUEST` does not exist.

- [ ] **Step 3: Add the family** — in `KnownSchemaVersions.java` after the `UML_XMI_EXPORT_POLICY` field:

```java
  // layout-request is machine-emitted by `project` but documented as hand-authorable
  // (agent-usage "Layout constraints in a hand-written layout-request"), and it genuinely
  // shipped a v1 before the typed-IR v2 (291921d). Registering it gives a kept v1 file the
  // same OUTDATED-with-upgrade-steps treatment as the other hand-authorable inputs.
  public static final Family LAYOUT_REQUEST =
      new Family(
          "layout-request",
          List.of("layout_request_schema_version"),
          List.of("layout-request.schema.v1", ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION));
```

Add it to `ALL` (after `UML_XMI_EXPORT_POLICY`). Update the class javadoc's first sentence from "every hand-authored Dediren schema family" to "every hand-authorable Dediren input-schema family".

- [ ] **Step 4: Run contracts tests, then observe MigrationRegistryTest fail**

Run: `./mvnw -pl contracts test` → Expected: PASS.
Run: `./mvnw -pl mcp-server -am test -Dtest=MigrationRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `everySupersededVersionHasUpgradeSteps` reports `layout-request.schema.v1` (this is the registry↔doc pin working).

- [ ] **Step 5: Add the migration subsection** — append to `docs/agent-usage.md` after the `render-policy.schema.v2 → render-policy.schema.v3` subsection (end of `## Migration`):

```markdown
### layout-request.schema.v1 → layout-request.schema.v2

Usually not a hand edit: `dediren project` always emits the current version,
so regenerate the request unless you deliberately keep a hand-written one. To
upgrade a kept v1 file: set `layout_request_schema_version` to
`layout-request.schema.v2`. v2 adds an optional `source_pointer` (a JSON
Pointer into the source model, starting with `/`) on nodes and edges — add it
only if you track provenance — and constrains node `id`/`role` charsets, so
rename any id the v2 schema rejects consistently across nodes, edges, and
constraints.
```

- [ ] **Step 6: Verify green**

Run: `./mvnw -pl contracts,mcp-server -am test`
Expected: PASS (MigrationRegistryTest all three tests green).

- [ ] **Step 7: Commit**

```bash
./mvnw -Pquality spotless:apply
git add contracts/src/main/java/dev/dediren/contracts/KnownSchemaVersions.java contracts/src/test/java/dev/dediren/contracts/KnownSchemaVersionsTest.java docs/agent-usage.md
git commit -m "feat(contracts): register layout-request in the schema-version family registry"
```

### Task 2: MigrationRegistryTest validates the `→ <to>` side

**Files:**
- Modify: `mcp-server/src/test/java/dev/dediren/mcp/MigrationRegistryTest.java`

**Interfaces:**
- Consumes: `KnownSchemaVersions.Family.versions()` (record accessor, oldest→current).

- [ ] **Step 1: Add the failing-by-construction test** (it passes on the current doc; prove it guards by mutation in Step 2):

```java
@Test
void everyUpgradeStepPointsAtTheVersionThatSupersededIt() {
  Map<String, String> successor = new HashMap<>();
  for (KnownSchemaVersions.Family family : KnownSchemaVersions.ALL) {
    List<String> versions = family.versions();
    for (int i = 0; i < versions.size() - 1; i++) {
      successor.put(versions.get(i), versions.get(i + 1));
    }
  }

  Matcher matcher = STEP_HEADING.matcher(GuideCatalog.section("migration"));
  List<String> wrong = new ArrayList<>();
  while (matcher.find()) {
    String expected = successor.get(matcher.group(1));
    if (expected != null && !expected.equals(matcher.group(2))) {
      wrong.add(matcher.group(1) + " → " + matcher.group(2) + " (expected → " + expected + ")");
    }
  }

  assertThat(wrong)
      .as(
          "each '### <from> → <to>' heading must name the version that directly"
              + " superseded <from> — a typo'd <to> sends the reader to a version"
              + " that never followed it")
      .isEmpty();
}
```

Add imports `java.util.HashMap` and `java.util.Map`.

- [ ] **Step 2: Red-green by mutation** — temporarily edit one heading in `docs/agent-usage.md` (`render-policy.schema.v2 → render-policy.schema.v3` → `... → render-policy.schema.v9`), run the test, expect FAIL naming the heading; revert the doc, run again, expect PASS.

Run: `./mvnw -pl mcp-server -am test -Dtest=MigrationRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Commit**

```bash
./mvnw -Pquality spotless:apply
git add mcp-server/src/test/java/dev/dediren/mcp/MigrationRegistryTest.java
git commit -m "test(mcp): pin migration headings to the version that actually superseded <from>"
```

### Task 3: Gate the standalone layout lane

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/commands/CoreCommands.java:58-95` (`layoutCommand` + `layoutRequestBytes`)
- Modify: `core/src/test/java/dev/dediren/core/commands/CoreCommandsTest.java` (mirror the stale-policy tests at :338-425)

**Interfaces:**
- Consumes: `KnownSchemaVersions.LAYOUT_REQUEST` (Task 1), `SchemaVersionGate.check(Family, JsonNode)`, `errorOutcome(List<Diagnostic>)` (CoreCommands:337), test harness `emptyEngines()` / `EngineRunOutcome` from CoreCommandsTest.
- Non-goals: the `build` lane (constructs its request in memory — never stale); `validate-layout`'s layout-*result* side (machine output; regenerate, don't migrate); the ELK-internal version check at `ElkLayoutEngine.java:1416` stays as a programmatic-misuse guard.

- [ ] **Step 1: Write the failing tests** — add to `CoreCommandsTest`, next to the stale-policy tests:

```java
@Test
void layoutCommandRejectsAStaleRequestBeforeEngineLookup() throws Exception {
  // Same contract as the policy gates: a superseded layout-request is a user-fixable
  // INPUT_ERROR (exit 2) with the gate's own "$.<field>" path, rejected before
  // requireEngine — an empty registry proves no engine lookup preceded it.
  String staleRequest = "{\"layout_request_schema_version\":\"layout-request.schema.v1\"}";

  EngineRunOutcome outcome =
      CoreCommands.layoutCommand("nonexistent-layout-engine", staleRequest, Map.of(), emptyEngines());

  assertThat(outcome.exitCode()).isEqualTo(2);
  JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());
  assertThat(envelope.at("/status").asText()).isEqualTo("error");
  assertThat(envelope.at("/diagnostics/0/code").asText())
      .isEqualTo("DEDIREN_SCHEMA_VERSION_OUTDATED");
  assertThat(envelope.at("/diagnostics/0/path").asText())
      .isEqualTo("$.layout_request_schema_version");
}

@Test
void layoutCommandRejectsAnUnknownRequestVersion() throws Exception {
  String unknownRequest = "{\"layout_request_schema_version\":\"layout-request.schema.v99\"}";

  EngineRunOutcome outcome =
      CoreCommands.layoutCommand("nonexistent-layout-engine", unknownRequest, Map.of(), emptyEngines());

  assertThat(outcome.exitCode()).isEqualTo(2);
  JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());
  assertThat(envelope.at("/diagnostics/0/code").asText())
      .isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
}

@Test
void layoutCommandGatesTheUnwrappedDataOfAPipedEnvelope() throws Exception {
  // The chained-workflow convenience (piping a stage envelope) must not bypass the gate:
  // the gate runs on the unwrapped data node.
  String pipedStale =
      "{\"envelope_schema_version\":\"envelope.schema.v1\",\"status\":\"ok\","
          + "\"data\":{\"layout_request_schema_version\":\"layout-request.schema.v1\"}}";

  EngineRunOutcome outcome =
      CoreCommands.layoutCommand("nonexistent-layout-engine", pipedStale, Map.of(), emptyEngines());

  assertThat(outcome.exitCode()).isEqualTo(2);
  JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());
  assertThat(envelope.at("/diagnostics/0/code").asText())
      .isEqualTo("DEDIREN_SCHEMA_VERSION_OUTDATED");
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl core -am test -Dtest=CoreCommandsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — current-version requests aren't gated, so the stale request reaches `requireEngine` and yields `DEDIREN_PLUGIN_UNKNOWN` (or engine parse errors), not the gate codes.

- [ ] **Step 3: Implement** — restructure `layoutCommand`/`layoutRequestBytes` so the gate sees the unwrapped `JsonNode` before serialization and before engine lookup:

```java
public static EngineRunOutcome layoutCommand(
    String engineId, String inputText, Map<String, String> env, Engines engines)
    throws EngineExecutionException {
  // Unwrap a piped stage envelope to its data (the chained-workflow convenience), gate the
  // hand-authorable request's schema version (same INPUT_ERROR shape as the policy gates,
  // before any engine is resolved), then route the bytes through the engine's parse entry
  // point so a well-formed-but-invalid request reproduces the published
  // DEDIREN_ELK_INPUT_INVALID_JSON envelope rather than core's generic input diagnostic.
  JsonNode request = layoutRequestData(inputText);
  Optional<Diagnostic> stale = SchemaVersionGate.check(KnownSchemaVersions.LAYOUT_REQUEST, request);
  if (stale.isPresent()) {
    return errorOutcome(List.of(stale.get()));
  }
  byte[] bytes = layoutRequestBytes(request);
  LayoutEngine layout =
      EngineDispatch.requireEngine(engines, engineId, "layout", engines.layoutEngine(engineId));
  return EngineDispatch.dispatch(
      engineId,
      () -> {
        EngineResult<LaidOutScene> laid = layout.layout(layout.parseRequest(bytes));
        return new EngineResult<>(LaidOutSceneMapper.toResult(laid.value()), laid.diagnostics());
      });
}

private static JsonNode layoutRequestData(String inputText) throws EngineExecutionException {
  JsonNode value;
  try {
    value = JsonSupport.objectMapper().readTree(inputText);
  } catch (RuntimeException error) {
    throw commandInputInvalid("layout", error);
  }
  JsonNode data = value.has("envelope_schema_version") ? value.get("data") : value;
  if (data == null) {
    throw commandInputInvalid(
        "layout", new IllegalArgumentException("command envelope does not contain data"));
  }
  return data;
}

private static byte[] layoutRequestBytes(JsonNode data) throws EngineExecutionException {
  try {
    return JsonSupport.objectMapper().writeValueAsBytes(data);
  } catch (RuntimeException error) {
    throw commandInputInvalid("layout", error);
  }
}
```

(`Optional`, `Diagnostic`, `KnownSchemaVersions`, `SchemaVersionGate` are already imported in this file.)

- [ ] **Step 4: Run the tests**

Run: `./mvnw -pl core -am test`
Expected: PASS, including the pre-existing layout tests (current-version fixtures are unaffected: the gate passes `layout-request.schema.v2`).

- [ ] **Step 5: Check CLI blast radius** — `grep -rn "layoutCommand" cli/ core/` and run `./mvnw -pl core,cli -am test`. Expected: PASS (no signature change).

- [ ] **Step 6: Commit**

```bash
./mvnw -Pquality spotless:apply
git add core/src/main/java/dev/dediren/core/commands/CoreCommands.java core/src/test/java/dev/dediren/core/commands/CoreCommandsTest.java
git commit -m "feat(core): gate hand-authored layout requests like every other versioned input"
```

### Task 4: coverage-report aggregates mcp-server, pinned

**Files:**
- Modify: `coverage-report/pom.xml` (coverage-profile `<dependencies>`; comment says "the 15 gated product modules")
- Create: `dist-tool/src/test/java/dev/dediren/tools/dist/CoverageAggregateTest.java`

**Interfaces:**
- Consumes: `DistTool.FIRST_PARTY_ARTIFACTS` (package-private list, DistTool.java:66-83) — the single source of truth for shipped first-party artifacts; `repoRoot()` pattern from `AgentUsageDocConsistencyTest` (copy the private method verbatim).

- [ ] **Step 1: Write the failing guard test**

```java
package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the JaCoCo aggregate to the shipped artifact list. The aggregate silently
 * under-reported twice (ir/engine-api until 2026-07-13, mcp-server until 2026-07-15);
 * with this pin, adding a shipped module without adding it to the coverage profile
 * fails the build instead of shrinking the denominator.
 */
class CoverageAggregateTest {

  @Test
  void coverageAggregateCoversEveryFirstPartyArtifact() throws IOException {
    String pom =
        Files.readString(
            repoRoot().resolve("coverage-report/pom.xml"), StandardCharsets.UTF_8);

    List<String> missing =
        DistTool.FIRST_PARTY_ARTIFACTS.stream()
            .filter(artifact -> !pom.contains("<artifactId>" + artifact + "</artifactId>"))
            .toList();

    assertThat(missing)
        .as("coverage-report's coverage profile must aggregate every shipped first-party module")
        .isEmpty();
  }

  private static Path repoRoot() {
    // Same convention as AgentUsageDocConsistencyTest: copy its private repoRoot() body here.
    ...
  }
}
```

Replace the `...` with the exact body of `AgentUsageDocConsistencyTest.repoRoot()` (below line 120 of that file).

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -pl dist-tool -am test -Dtest=CoverageAggregateTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — missing contains `mcp-server`.

- [ ] **Step 3: Add the dependency** — in `coverage-report/pom.xml`'s coverage-profile dependency list (alphabetical/topological position near `cli`):

```xml
        <dependency>
          <groupId>dev.dediren</groupId>
          <artifactId>mcp-server</artifactId>
          <version>${project.version}</version>
          <scope>runtime</scope>
        </dependency>
```

Update the comment "the 15 gated product modules" → "the 16 gated product modules (pinned by dist-tool's CoverageAggregateTest)" and append to its history note: "mcp-server was missing until 2026-07-15."

- [ ] **Step 4: Verify green + the lane itself**

Run: `./mvnw -pl dist-tool -am test -Dtest=CoverageAggregateTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS.
Run: `./mvnw -Pcoverage verify` → Expected: BUILD SUCCESS, aggregate now includes mcp-server.

- [ ] **Step 5: Commit**

```bash
./mvnw -Pquality spotless:apply
git add coverage-report/pom.xml dist-tool/src/test/java/dev/dediren/tools/dist/CoverageAggregateTest.java
git commit -m "fix(coverage): aggregate mcp-server and pin the module list to FIRST_PARTY_ARTIFACTS"
```

### Task 5: SLF4J provider locality — pin it, and tell the truth about it

**Files:**
- Create: `dist-tool/src/test/java/dev/dediren/tools/dist/LoggingProviderLocalityTest.java`
- Modify: `CLAUDE.md:189-190`; `docs/architecture-guidelines.md:316-319` (the equivalent sentence)

- [ ] **Step 1: Write the guard test** (walks every tracked `pom.xml`; providers allowed only where documented):

```java
package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the documented SLF4J posture: only cli binds a provider at runtime, test-support and
 * schema-cache bind slf4j-simple for tests, and no module anywhere pulls a different backend.
 * The rule was prose-only until 2026-07-15; this makes drift a build failure.
 */
class LoggingProviderLocalityTest {

  /** module directory name → the only scope slf4j-simple may have there. */
  private static final Map<String, String> SIMPLE_ALLOWED =
      Map.of("cli", "compile", "test-support", "compile", "schema-cache", "test");

  private static final List<String> BANNED_BACKENDS =
      List.of("logback-classic", "slf4j-jdk14", "slf4j-log4j12", "log4j-slf4j2-impl", "slf4j-nop");

  @Test
  void slf4jProvidersLiveOnlyWhereDocumented() throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> poms = Files.walk(repoRoot())) {
      for (Path pom :
          poms.filter(p -> p.getFileName().toString().equals("pom.xml"))
              .filter(p -> !p.toString().contains("target"))
              .toList()) {
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        String module = repoRoot().relativize(pom).toString().replace("/pom.xml", "");
        for (String backend : BANNED_BACKENDS) {
          if (xml.contains("<artifactId>" + backend + "</artifactId>")) {
            violations.add(module + " declares banned backend " + backend);
          }
        }
        int at = xml.indexOf("<artifactId>slf4j-simple</artifactId>");
        if (at >= 0) {
          String allowedScope = SIMPLE_ALLOWED.get(module);
          String window = xml.substring(at, Math.min(xml.length(), at + 400));
          boolean testScoped = window.contains("<scope>test</scope>");
          if (allowedScope == null) {
            violations.add(module + " binds slf4j-simple but is not an allowed binding site");
          } else if (allowedScope.equals("test") != testScoped) {
            violations.add(module + " binds slf4j-simple at the wrong scope");
          }
        }
      }
    }
    assertThat(violations)
        .as("SLF4J provider bindings must match the documented posture (CLAUDE.md Engine Runtime Rules)")
        .isEmpty();
  }

  private static Path repoRoot() {
    // Copy AgentUsageDocConsistencyTest's private repoRoot() body verbatim.
    ...
  }
}
```

Replace `...` as in Task 4. Note the root `pom.xml` maps to module `""` — that is fine (root declares no slf4j-simple; if the walk trips on it, skip `module.isEmpty()` entries explicitly).

- [ ] **Step 2: Run — expect PASS** (current tree conforms; the point is the pin):

Run: `./mvnw -pl dist-tool -am test -Dtest=LoggingProviderLocalityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. Prove it guards: temporarily add `<scope>compile</scope>`→`test` flip in `cli/pom.xml`'s slf4j-simple? — do NOT edit cli; instead temporarily add `"uml", "test"`-style violation by removing `"schema-cache"` from `SIMPLE_ALLOWED`, run, expect FAIL naming schema-cache, restore.

- [ ] **Step 3: Fix the two prose sentences** — CLAUDE.md:189-190, replace:

> Only `cli` binds an SLF4J provider (and `test-support` for tests); library modules take `slf4j-api` alone.

with:

> Only `cli` binds an SLF4J provider at runtime; `test-support` binds one for
> tests, and `schema-cache` binds `slf4j-simple` at test scope only (it is the
> one logging module that does not depend on test-support). Library modules
> take `slf4j-api` alone; `LoggingProviderLocalityTest` (dist-tool) pins this.

Apply the same correction to the equivalent sentence at `docs/architecture-guidelines.md:316-319`.

- [ ] **Step 4: Commit**

```bash
./mvnw -Pquality spotless:apply
git add dist-tool/src/test/java/dev/dediren/tools/dist/LoggingProviderLocalityTest.java CLAUDE.md docs/architecture-guidelines.md
git commit -m "test(dist): pin SLF4J provider locality; state the schema-cache test-scope exception"
```

### Task 6: Single source for the bundle schema id

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java:983-987`
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java:89`

- [ ] **Step 1: Add the constant and use it** — near `FIRST_PARTY_ARTIFACTS` in DistTool:

```java
  /** Bundle descriptor schema id; schemas/bundle.schema.json declares the same const. */
  static final String BUNDLE_SCHEMA_VERSION = "dediren-bundle.schema.v2";
```

In `writeBundleMetadata`, replace `metadata.put("bundle_schema_version", "dediren-bundle.schema.v2");` with `metadata.put("bundle_schema_version", BUNDLE_SCHEMA_VERSION);` (keep the Cutover B comment).

- [ ] **Step 2: Point the test at it** — DistModuleTest.java:89: `.isEqualTo("dediren-bundle.schema.v2")` → `.isEqualTo(DistTool.BUNDLE_SCHEMA_VERSION)`.

- [ ] **Step 3: Verify**

Run: `./mvnw -pl dist-tool -am test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
./mvnw -Pquality spotless:apply
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
git commit -m "refactor(dist): hold the bundle schema id in one constant"
```

### Task 7: agent-usage tells the truth about env vars, codes, and MCP args; threat-model names the validator knob

**Files:**
- Modify: `docs/agent-usage.md` (`## MCP Server` :46-49; UML Sequence Handoff :386; `## Repair Rules` list :811-826; `## Plugin Environment` :860-880)
- Modify: `docs/threat-model.md` (validator subprocess section, after the "Bounded wall clock" bullet ~:150)

**Interfaces:**
- Every token added exists in source: `DEDIREN_OEF_SCHEMA_VALIDATOR` (OefExportEngine.java:67), `DEDIREN_XMI_SCHEMA_VALIDATOR` (SchemaValidation.java:30), `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE`/`DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE` (DiagnosticCode), `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`, `DEDIREN_RENDER_METADATA_REQUIRED`/`_PROFILE_REQUIRED`/`_PROFILE_MISMATCH` (DiagnosticCode:88-90).

- [ ] **Step 1: Fix the false sentence** — agent-usage.md:860-863, replace "…explicitly for the schema-path variables below and read nothing else." with "…explicitly for the schema-path and validator-override variables below and read nothing else."

- [ ] **Step 2: Document the overrides** — insert after the `DEDIREN_XMI_SCHEMA_PATH` bullet:

```markdown
- `DEDIREN_OEF_SCHEMA_VALIDATOR` / `DEDIREN_XMI_SCHEMA_VALIDATOR`: override the
  `xmllint` command the export engines run for XML schema validation (a command
  name or path). The named binary is trusted like `xmllint` itself and runs
  under the same guards — bounded wall clock, concurrent output drain — and an
  absent or wedged validator degrades to a
  `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` /
  `DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE` error envelope.
```

Before committing, confirm the override is read through `SchemaCacheModule.configuredValidator` on both engine paths (OefExportEngine.java:505, SchemaValidation.java:120) so the prose matches behavior.

- [ ] **Step 3: Extend Repair Rules** — after the `DEDIREN_PLUGIN_UNKNOWN` bullet (:819-821), insert:

```markdown
- `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`: the engine id exists but not for
  this command's capability (for example asking `elk-layout` to render). Fix
  the `--plugin` value for this command.
- `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` /
  `DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE`: the export engine's XML schema
  validator (`xmllint` by default) is missing, timed out, or failed to start.
  Install libxml2's `xmllint` or point the validator override variable at one
  (see `## Plugin Environment`); not a JSON problem — do not modify the model.
```

- [ ] **Step 4: Name the render-metadata codes** — agent-usage.md:386, extend "The SVG sequence path needs generated render metadata." to:

> The SVG sequence path needs generated render metadata — missing or mismatched
> metadata fails with `DEDIREN_RENDER_METADATA_REQUIRED`,
> `DEDIREN_RENDER_METADATA_PROFILE_REQUIRED`, or
> `DEDIREN_RENDER_METADATA_PROFILE_MISMATCH`; regenerate through `project`
> rather than hand-editing.

Before committing, read the three codes' emit sites (`grep -rn RENDER_METADATA_ core/ engines/`) and adjust the sentence if the trigger conditions differ.

- [ ] **Step 5: Document the MCP tool arguments** — replace agent-usage.md:46-49 with:

```markdown
- `dediren_validate` — `source` (path); optional `profile` to also run
  semantic profile validation. Returns the validation envelope.
- `dediren_build` — `source`, `out`, and at least one policy (`render_policy`,
  `oef_policy`, `xmi_policy`); optional `views` (subset of view ids) and
  `emit` (extra artifact kinds). Returns the build-result envelope, which
  names every artifact written.
```

(Wording matches `ToolSchemas.java:16-50`. Coordination note: the open MCP↔CLI parity spec changes `emit`/`views` *validation*, not this argument list; its Change 4 step "review README/agent-usage for old emit/views wording" will pick this text up.)

- [ ] **Step 6: Threat-model names the knob** — in `docs/threat-model.md`, after the "**Bounded wall clock.**" bullet, add:

```markdown
- **Validator selection is an environment input.** `DEDIREN_OEF_SCHEMA_VALIDATOR`
  / `DEDIREN_XMI_SCHEMA_VALIDATOR` let the environment name the validator
  command. Whoever sets the process environment already controls execution, so
  the override grants no new privilege; the guards above bound a hostile or
  broken choice, and the variables are documented in the shipped guide's
  `## Plugin Environment` section.
```

- [ ] **Step 7: Verify + commit**

Run: `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS (all new tokens exist in source).
Run: `./mvnw -pl mcp-server -am test` → PASS (GuideCatalog headings unchanged in this task).
Run: `git diff --check` → clean.

```bash
git add docs/agent-usage.md docs/threat-model.md
git commit -m "docs(agent-usage): document validator overrides, capability/validator/render-metadata codes, MCP tool args"
```

### Task 8: Fragments and Redistribution become served guide sections

**Files:**
- Modify: `docs/agent-usage.md` (preamble :1-16; new `## Fragments` after the `## Minimal Source JSON` section; new `## Redistribution` after `## Debug Logging`)
- Modify: `mcp-server/src/main/java/dev/dediren/mcp/GuideCatalog.java` (`topicMap()`)

- [ ] **Step 1: Verify fragment semantics before writing prose** — read the five emit sites: `grep -rn "FRAGMENT_BASE_DIR_REQUIRED\|FRAGMENT_PATH_UNSUPPORTED\|FRAGMENT_READ_FAILED\|FRAGMENT_NESTED_UNSUPPORTED\|FRAGMENT_CONFLICT" core/src/main/java`. Confirm: relative-only paths resolved against the source file's directory; stdin sources have no base dir; fragments may not declare `fragments`; merged ids must stay unique. Adjust Step 2's text only if code disagrees.

- [ ] **Step 2: Add `## Fragments`** (immediately after the `## Minimal Source JSON` section ends):

```markdown
## Fragments

A source model may split across files: `fragments` is an array of relative
paths, resolved against the main source file's directory and merged into the
model before validation. Paths must stay relative and inside that directory
(over MCP, inside `--root`); fragment files carry model content only and must
not declare `fragments` of their own.

Repair codes:

- `DEDIREN_FRAGMENT_BASE_DIR_REQUIRED`: the source arrived on stdin, so
  relative fragment paths have no base directory — pass a source file path
  instead.
- `DEDIREN_FRAGMENT_PATH_UNSUPPORTED`: the path is absolute or escapes the
  base directory — make it relative and inside.
- `DEDIREN_FRAGMENT_READ_FAILED`: no readable file at the resolved path.
- `DEDIREN_FRAGMENT_NESTED_UNSUPPORTED`: a fragment declared `fragments`;
  flatten the list into the main source.
- `DEDIREN_FRAGMENT_CONFLICT`: two files define the same id — node,
  relationship, view, and group ids must be unique across the merged model.
```

- [ ] **Step 3: Add `## Redistribution` and slim the preamble** — move the preamble's redistribution sentence (:11-12) and the "If Dediren is embedded…" paragraph (:14-16) verbatim into a new section after `## Debug Logging`:

```markdown
## Redistribution

Preserve the bundle root `LICENSE`, `THIRD-PARTY-NOTICES.md`, and this guide
when redistributing a Dediren archive.

This file is the shipped agent-facing contract for bundle usage. If Dediren is
embedded in another agent skill, plugin, or tool package, preserve this path or
carry the same JSON authoring, command handoff, runtime probe, and repair
guidance.
```

Then delete those lines from the preamble, and delete the repo-only sentence at :8-9 ("Source builds use the checked-in Maven Wrapper. Packaged bundle usage below is unchanged.") — source-build guidance lives in README, not the bundle.

- [ ] **Step 4: Register the topics** — in `GuideCatalog.topicMap()`: after the `source-json` line add `topics.put("fragments", "Fragments");`; after the `logging` line add `topics.put("redistribution", "Redistribution");`.

- [ ] **Step 5: Verify the bidirectional pin and the CLI footer promise**

Run: `./mvnw -pl mcp-server -am test` → PASS (GuideCatalogTest `everyHeadingIsReachableFromSomeTopic` + `everyTopicResolvesToARealHeading` green).
Run: `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS.
Check: `cli/src/main/java/dev/dediren/cli/Main.java:43`'s footer ("…fragments, and repair diagnostics") is now true — no code change needed.

- [ ] **Step 6: Commit**

```bash
./mvnw -Pquality spotless:apply
git add docs/agent-usage.md mcp-server/src/main/java/dev/dediren/mcp/GuideCatalog.java
git commit -m "docs(agent-usage): serve fragments and redistribution as guide sections"
```

### Task 9: Close the loop — undocumented diagnostic codes fail the build

**Files:**
- Modify: `docs/agent-usage.md` (`## Repair Rules`, closing paragraph)
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/AgentUsageDocConsistencyTest.java`

**Interfaces:**
- Consumes: the existing `TOKEN` pattern, `sourceTokens(Path)`, `repoRoot()`, and the wildcard convention (a doc token ending `_` is a documented prefix).

- [ ] **Step 1: Document the internal families** — append to the end of `## Repair Rules`:

```markdown
Codes not listed in this guide are internal: `DEDIREN_ELK_*` (layout engine
internals), `DEDIREN_LAYOUT_*` (layout quality gates), `DEDIREN_GENERIC_GRAPH_*`,
`DEDIREN_ARCHIMATE_*`, `DEDIREN_UML_*` (profile and notation validation),
`DEDIREN_OEF_*` / `DEDIREN_XMI_*` (export validation), `DEDIREN_SEMANTIC_*`,
`DEDIREN_VALIDATE_*`, `DEDIREN_SVG_*`, `DEDIREN_COMMAND_*`, `DEDIREN_MCP_*`.
Their `message` and `path` are written to be self-repairing: follow the
instruction in the message, and report any such code that persists after you
have done so.
```

- [ ] **Step 2: Write the reverse-direction test** — add to `AgentUsageDocConsistencyTest`:

```java
@Test
void sourceTokensAreDocumentedIndividuallyOrByFamily() throws IOException {
  Path repoRoot = repoRoot();
  Set<String> documented = new TreeSet<>();
  Set<String> documentedPrefixes = new TreeSet<>();
  for (String docPath : List.of("docs/agent-usage.md", "README.md")) {
    Matcher matcher =
        TOKEN.matcher(Files.readString(repoRoot.resolve(docPath), StandardCharsets.UTF_8));
    while (matcher.find()) {
      String token = matcher.group();
      if (token.endsWith("_")) {
        documentedPrefixes.add(token);
      } else {
        documented.add(token);
      }
    }
  }

  Set<String> undocumented = new TreeSet<>();
  for (String token : sourceTokens(repoRoot)) {
    boolean covered =
        documented.contains(token)
            || documentedPrefixes.stream().anyMatch(token::startsWith);
    if (!covered) {
      undocumented.add(token);
    }
  }

  assertThat(undocumented)
      .as(
          "every DEDIREN_* token in source must be documented in docs/agent-usage.md or"
              + " README.md, either individually or via a documented family prefix"
              + " (e.g. DEDIREN_ELK_*) — add the code to '## Repair Rules' or extend the"
              + " internal-families paragraph")
      .isEmpty();
}
```

- [ ] **Step 3: Run and reconcile** — run the test; for every token it still names, decide: individually document it (agent-actionable) or extend the internal-families paragraph. Do NOT add a Java-side allowlist — the doc paragraph *is* the allowlist, visible to agents.

Run: `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected after reconciliation: PASS.

- [ ] **Step 4: Commit**

```bash
./mvnw -Pquality spotless:apply
git add docs/agent-usage.md dist-tool/src/test/java/dev/dediren/tools/dist/AgentUsageDocConsistencyTest.java
git commit -m "test(dist): enforce source→docs coverage for DEDIREN_* tokens"
```

### Task 10: CLAUDE.md learns about the MCP world and stops contradicting itself

**Files:**
- Modify: `CLAUDE.md` (lines 13, 82-84, 155-159, 165-167, Architecture Rules bullets, Engine Runtime Rules :170-171, Files That Move Together, Verification lanes)

- [ ] **Step 1: Boundary routing (line 13)** — replace

> - Product boundary question: `docs/superpowers/specs/2026-05-08-dediren-design.md`

with

> - Product boundary question: `docs/superpowers/specs/2026-05-08-dediren-design.md`
>   (as amended; for the MCP surface also
>   `docs/superpowers/specs/2026-07-14-dediren-mcp-server-design.md`)

- [ ] **Step 2: Architecture Rules** — after the engines bullet ("First-party engines are library modules behind `engine-api`…"), add:

> - `mcp-server` adapts the CLI's command surface to MCP stdio (`dediren mcp`).
>   Its allowed edges are `contracts`, `core`, `engine-api` only (ArchUnit-pinned);
>   tool results carry the same envelopes the CLI prints.

- [ ] **Step 3: R2 scope (lines 82-84)** — replace the KnownSchemaVersions clause so it reads:

> - Breaking schema-version bumps: update the schema and the `ContractVersions`
>   constant. For the hand-authorable input families registered in
>   `KnownSchemaVersions` (source model, render policy, both export policies,
>   layout request): also append the new version to the family (the old one
>   becomes a prior version) and add a `### <from> → <to>` subsection under
>   `## Migration` in `docs/agent-usage.md` — `MigrationRegistryTest` fails the
>   build if a superseded registered version has no upgrade steps, or if a
>   heading's `<to>` is not the version that superseded `<from>`. Generated
>   engine-seam schemas (envelope, layout/render/export results, …) have no
>   family: bump the constant, fixtures, and mapping code only. If the version
>   *field* is renamed, add the old field name to the family's `versionFields`.

- [ ] **Step 4: Version surfaces (lines 155-159)** — remove the `DistModuleTest.java` entry (it asserts only synthetic fixture versions) and add `docs/features/README.md` / `docs/features/source-model.md` to the list. **Sweep list (lines 165-167):** add `docs/features` between `docs/agent-usage.md` and `fixtures/source`.

- [ ] **Step 5: Engine Runtime Rules stdout sentence (lines 170-171)** — append after "…decide success or failure from stdout JSON alone.":

> Under `dediren mcp`, stdout carries JSON-RPC frames instead and the same
> envelopes ride inside tool results; `StdoutIntegrity` keeps stray writes off
> the frame channel.

- [ ] **Step 6: Files That Move Together** — add a row:

> - MCP surface changes: update `mcp-server` (tools, `ToolSchemas`,
>   `GuideCatalog` topics), the `## MCP Server` section of
>   `docs/agent-usage.md`, the MCP rows of `docs/threat-model.md`, and the
>   dist-tool packaged-MCP stdio smoke together.

- [ ] **Step 7: Verification lane** — after the UML/XMI export lane block, add:

```markdown
MCP server changes:

```bash
./mvnw -pl mcp-server,cli -am test
./mvnw -pl dist-tool -am verify -Pdist-smoke
```
```

- [ ] **Step 8: Verify + commit**

Run: `git diff --check` → clean. Re-read the edited sections once for internal consistency.

```bash
git add CLAUDE.md
git commit -m "docs(claude): teach the guidance the mcp-server world; fix version-surface and R2 scope claims"
```

### Task 11: architecture-guidelines catches up with the module reality

**Files:**
- Modify: `docs/architecture-guidelines.md` (§2 table rows at :69-86, §2/§3 prose `mcp`→`mcp-server`, §4 envelope line ~:259, engines-independence sentence :113-114)
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java:163-165` (comment only)
- Modify: `docs/superpowers/specs/2026-05-08-dediren-design.md` (amendment banner, lines 5-8)

- [ ] **Step 1: §2 table fixes** —
  - `mcp` row: rename to `mcp-server` (keep edges `contracts`, `core`, `engine-api`); rename every other `mcp` module mention in §2 bullets and the §3 charter heading (`**\`mcp\`**` at ~:231) to `mcp-server`. The Java package `dev.dediren.mcp` is unchanged — say so where the charter mentions packages.
  - `schema-cache` row: dependency cell `` `contracts` `` → `*(nothing internal)*` (its pom declares no first-party dependency).
  - `cli` row: annotate `ir` → `` `ir` (runtime scope — cli's main code does not compile against the IR) ``.
  - `coverage-report` row: "every product module" → "every shipped first-party module (pinned to `DistTool.FIRST_PARTY_ARTIFACTS` by dist-tool's `CoverageAggregateTest`)".
- [ ] **Step 2: Engines-independence sentence (:113-114)** — extend "…never on `core` and never on each other" with "(production classes; deliberate test-scope harness edges exist — e.g. elk-layout tests render output — and the ArchUnit import excludes tests)".
- [ ] **Step 3: §4 stdout carve-out (~:259)** — after "command envelopes on stdout (success and error);" add "(under `dediren mcp`, stdout is the JSON-RPC frame channel and these envelopes ride inside tool results)".
- [ ] **Step 4: ArchUnit comment** — ArchitectureRulesTest.java:163-164, extend "§2 allows cli only contracts, core, engine-api and ir" to "…and ir, plus mcp-server (the MCP adapter edge)".
- [ ] **Step 5: Design-spec banner** — in `2026-05-08-dediren-design.md`'s header banner, after the 2026-07-08 amendment sentence, add:

> **Further amended 2026-07-14 by `2026-07-14-dediren-mcp-server-design.md`:**
> an MCP stdio surface (`dediren mcp`, module `mcp-server`) joins the CLI as a
> second in-boundary entrypoint serving the same envelopes over tool results.

- [ ] **Step 6: Verify + commit**

Run: `./mvnw -pl dist-tool -am test` → PASS (comment-only Java change).
Run: `git diff --check` → clean.

```bash
./mvnw -Pquality spotless:apply
git add docs/architecture-guidelines.md dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java docs/superpowers/specs/2026-05-08-dediren-design.md
git commit -m "docs(architecture): true up the edge table, mcp-server naming, and boundary amendments"
```

### Task 12: Status hygiene sweep

**Files:** (all under `docs/superpowers/` unless noted; stamps are *added* lines directly under each file's H1 — never rewrite bodies)
- Delete: `plans/2026-07-08-monolithic-runtime-radical-release-notes.md`, `plans/2026-07-08-drop-png-raster-support-release-notes.md`
- Modify: 16 plan/spec files below
- Create: `plans/2026-07-15-recorded-deferrals.md`

- [ ] **Step 1: Delete the folded release-note fragments**

```bash
git rm docs/superpowers/plans/2026-07-08-monolithic-runtime-radical-release-notes.md docs/superpowers/plans/2026-07-08-drop-png-raster-support-release-notes.md
```

(Their own headers order this once folded; v2026.07.13/.14 shipped long ago.)

- [ ] **Step 2: Correct the three false status lines** (replace the existing Status text):
  - `plans/2026-07-09-retire-interactive-svg.md:3-5` → `Status: complete — shipped in 2026.07.15 (render-policy.schema.v3; the worktree-stacking note below is historical).`
  - `plans/2026-07-09-structured-svg-emission-proposal.md:3` → `Status: implemented — the full StAX emitter (SvgWriter) shipped in 2026.07.15.`
  - `specs/2026-06-10-interactive-svg-design.md:4` → `Status: implemented 2026-06; retired 2026-07 (render-policy.schema.v3 removed interactive output — see plans/2026-07-09-retire-interactive-svg.md).`
- [ ] **Step 3: Supersession stamp** — `specs/2026-06-18-render-plugin-png-design.md`, add under the title: `Status: superseded 2026-07-08 — PNG/raster support was dropped in 2026.07.13 (plans/2026-07-08-drop-png-raster-support.md); the plugin-rename half stands.`
- [ ] **Step 4: Done-stamps** (one line each, under the H1):
  - `plans/2026-07-14-dediren-schema-migration.md` → `Status: complete — implemented on main as 999ecd6..570b790; UNRELEASED as of 2026-07-15: fold the breaking stale-policy hard-fail into the next release's notes.`
  - `plans/2026-07-14-dediren-mcp-server.md` → `Status: complete — merged 51d7f33, released 2026.07.17.`
  - `plans/2026-07-14-complete-review-remediation.md` → `Status: complete — released 2026.07.16.`
  - `plans/2026-07-13-uml-sequence-execution-destruction.md` and `plans/2026-07-13-uml-sequence-self-message.md` → `Status: complete — contained in 2026.07.15.`
  - `plans/2026-07-09-typed-ir-p1-scene-graph-provenance.md`, `-p2-invariants-property-tests-fixtures.md`, `-p3-semantics-carve.md`, `plans/2026-07-10-typed-ir-p4-ir-seam-flip.md`, `-p5-typed-sequence-intent.md` → `Status: complete — Plan B P1–P5 all shipped by 2026.07.15.`
  - `plans/2026-07-03-agent-usage-doc-gaps.md`, `plans/2026-07-03-dist-hermeticity.md`, `plans/2026-07-03-evolution-readiness-docs.md` → `Status: complete — executed on main; see reviews/2026-07-03-multi-viewpoint-product-review.md ("Remediation status").`
  - `plans/2026-06-25-inherited-followups.md`, under Item 1 add: `> Resolved differently (2026-07): OWASP dependency-check demoted to a weekly cross-check in .github/workflows/dependency-audit.yml (17ab377) instead of re-enabling in the main build.`
- [ ] **Step 5: jqwik errata** — add directly under the Status line (or H1) of each of the 7 files that cite jqwik (`plans/2026-07-09-typed-ir-p1-…`, `-p2-…`, `2026-07-10-typed-ir-p4-…`, `-p5-…`, `2026-07-13-uml-sequence-self-message.md`, `2026-07-13-uml-sequence-execution-destruction.md`, `2026-07-14-complete-review-remediation.md`):

> Erratum 2026-07-15: jqwik was removed 2026-07-14 (7b520b0). Read "jqwik
> property test" as the seeded JUnit `@ParameterizedTest` sequence property
> suite that replaced it.

- [ ] **Step 6: Record the unrecorded deferrals** — create `plans/2026-07-15-recorded-deferrals.md`:

```markdown
# Recorded deferrals (2026-07-15)

Status: open — parked items with no other in-repo record, surfaced by the
2026-07-15 coherence review. Each is deliberate scope-cutting, not a defect
list; pick up individually.

- **Sequence-edge bend jitter** (visual-defect suite, 2026-07 era): minor
  route-bend wobble on dense sequence diagrams; deferred when the SvgAudit
  items shipped. Start from the SvgAudit render-layer tests in
  `engines/render` and real-render evidence, per the ELK-first rule.
- **Label/lane padding polish** (same era): spacing between labels and
  lane/box edges reads tight in dense diagrams; express any fix through ELK
  spacing options, not custom geometry.

ELK vocabulary deferrals (node/edge `priority`, `layer_choice`,
`position_choice`, alternate layout algorithms) are already recorded in
`2026-07-05-elk-node-placement-hints.md` and `2026-07-05-elk-algorithm-gate.md`
and are not duplicated here.
```

- [ ] **Step 7: Verify + commit**

Run: `git diff --check` → clean. `git status --short` → only the intended files.

```bash
git add -A docs/superpowers/plans docs/superpowers/specs
git commit -m "docs(plans): status hygiene — stamp shipped work, delete folded fragments, record deferrals"
```

(`git add -A` scoped to those two directories is acceptable here: every change in them this task is intentional.)

### Task 13: Final gate + audits

- [ ] **Step 1:** `./mvnw -Pquality verify` → BUILD SUCCESS, zero SpotBugs findings, formatting clean.
- [ ] **Step 2:** `./mvnw -pl dist-tool -am verify -Pdist-smoke` → BUILD SUCCESS (bundle + packaged MCP stdio smoke; the guide gained sections, so the packaged `dediren_guide` index must list `fragments` and `redistribution`).
- [ ] **Step 3:** `./mvnw -Pcoverage verify` → BUILD SUCCESS (mcp-server now aggregated).
- [ ] **Step 4:** `git diff --check` across the branch; `git log --oneline main..HEAD` reads as the 12 commits above.
- [ ] **Step 5: Audits** — run `souroldgeezer-audit:test-quality-audit` (Deep) over Tasks 1–5 and 9's tests, and `souroldgeezer-audit:devsecops-audit` (Quick) over Task 7 + Task 5. Fix blocks; fix or explicitly accept warns in the handoff.
- [ ] **Step 6: Integrate** — local `--no-ff` merge to `main` (author-discretion policy), then `git status --short --branch`. Do not push, bump, or tag: release sequencing is a separate decision, and its notes owe the stale-policy breaking-change mention (Task 12's stamp records this).

## Self-review notes

- Every review finding maps to a task (traceability table above); the two decisions the review left open are resolved as: layout-request gets **full gating** (family + core gate + migration entry — matches the shipped design's own premise), and SLF4J locality gets **a guard test + truthful prose** (a dist-tool repo-guard test over enforcer gymnastics, matching the house pattern of AgentUsageDocConsistencyTest).
- Deliberately out of scope: layout-*result* family (machine output; regenerate), removing the validator-override seam (tests exercise it; documenting is the fix), the MCP↔CLI parity implementation (its own spec/plan owns emit/views validation), contract-cleanup (separately recorded debt), and any release/bump.
- Tasks 7–9 are ordered so the reverse-direction test (9) lands after the doc sections it depends on (7, 8).
