# Complete-Review Remediation Implementation Plan

Status: complete — released 2026.07.16.

> Erratum 2026-07-15: jqwik was removed 2026-07-14 (7b520b0). Read "jqwik
> property test" as the seeded JUnit `@ParameterizedTest` sequence property
> suite that replaced it.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the 27 adversarially-verified findings of the 2026-07-14 complete
review (1 block, 12 warn, 14 info): fix everything cheap and real, and record
the two accepted items (stub-XSD assurance, SBOM attestation) as explicit debt.

**Architecture:** No structural change. Every task is an edge-hardening fix
inside an existing module boundary: cli/core error-envelope paths, a shared
XML-1.0 character policy in `engine-api` consumed by the three XML emitters,
test-net completions in `contracts`/`ir`/`cli`, one ArchUnit pin, a docs sweep,
and two workflow/threat-model touches.

**Tech Stack:** Java 21, Maven Wrapper, JUnit 5 + AssertJ, ArchUnit, picocli,
GitHub Actions.

## Global Constraints

- Java 21+; build only with the checked-in `./mvnw`.
- Run `./mvnw -Pquality spotless:apply` before every commit that touches Java.
- Maven test runs need the sandbox disabled (JUnit `@TempDir` fails on
  read-only `/tmp` under the sandbox).
- Parallel subagents must NOT run Maven (shared `target/` dirs race); execute
  tasks sequentially, one Maven invocation at a time.
- Work on a feature branch `complete-review-remediation` cut from current
  `main` (fetch + rebase main onto `origin/main` first, per repo git policy).
- No version bump in this plan (release-policy: bump only when a release is
  requested, as a separate follow-on commit).
- New `DEDIREN_*` diagnostic codes are additive wire vocabulary: add them to
  `DiagnosticCode`, keep `DiagnosticCodeTest`/`DiagnosticProvenanceTest`
  guards green, and document them where agent-usage.md discusses the lane.
- `docs/agent-usage.md` edits must keep `AgentUsageDocConsistencyTest`
  (dist-tool) green: every `DEDIREN_*` token and version string in the doc
  must exist in source / match `2026.07.15`.
- Findings review provenance: session scratchpad `review-result.json`
  (memory: `complete-review-2026-07-findings`).

## Verification Lanes (used by the tasks below)

```bash
./mvnw -pl contracts -am test                        # contracts/schema
./mvnw -pl core,cli -am test                         # dispatch / cli
./mvnw -pl engines/render,cli -am test               # SVG render
./mvnw -pl engines/archimate-oef-export,cli -am test # OEF export
./mvnw -pl engines/uml-xmi-export,cli -am test       # XMI export
./mvnw test                                          # full reactor
./mvnw -Pquality verify                              # format + SpotBugs gate
./mvnw -pl dist-tool -am verify -Pdist-smoke         # distribution smoke
```

---

### Task 1: Build artifact-write failure emits a structured error envelope (BLOCK)

`BuildCommand.writeFile` throws `UncheckedIOException` on any artifact-write
failure; cli `Main$BuildCommand.call` catches only `EngineExecutionException`,
so a routine `--out` collision produces exit 1, empty stdout, and a raw stack
trace — violating the "decide success/failure from stdout JSON alone" rule.
Note: `printStructuralFailure` (Main.java:543) deliberately reproduces the
retired process-runtime observable for validate/project and is documented as
such — do NOT change it; build gets its own envelope-emitting handler.

**Files:**
- Modify: `contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java`
- Modify: `cli/src/main/java/dev/dediren/cli/Main.java` (build catch ~478-483, new helper near line 543)
- Modify: `docs/agent-usage.md` (Build section: document the failure observable)
- Test: `cli/src/test/java/dev/dediren/cli/MainTest.java`

**Interfaces:**
- Produces: `DiagnosticCode.COMMAND_IO_FAILED` (`"DEDIREN_COMMAND_IO_FAILED"`), used only by cli.

- [ ] **Step 1: Write the failing test** in `MainTest` (reuse the existing
  `CliResult` runner helpers that `runValidate`/the build tests use — the
  generic runner that constructs `Main` with an env map and captures
  stdout/stderr/exit):

```java
@Test
void buildArtifactWriteCollisionEmitsErrorEnvelope(@TempDir Path tempDir) throws Exception {
  // --out points at an existing FILE, so Files.createDirectories(out/main) must fail.
  Path outCollision = Files.writeString(tempDir.resolve("out"), "occupied");
  CliResult result =
      run( // adapt to the existing MainTest runner helper name
          sequenceWorkflowEnv(),
          "build",
          "--input",
          workspaceRoot().resolve("fixtures/source/valid-basic.json").toString(),
          "--out",
          outCollision.toString(),
          "--render-policy",
          workspaceRoot().resolve("fixtures/render-policy/default-svg.json").toString());

  assertThat(result.exitCode()).isEqualTo(2);
  JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
  assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_COMMAND_IO_FAILED");
  assertThat(envelope.at("/diagnostics/0/message").asText()).contains("failed to write build artifact");
}
```

- [ ] **Step 2: Run it, verify it fails** (empty stdout / exit 1 today):
  `./mvnw -pl cli -am test -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement.** Add the enum constant next to
  `COMMAND_INPUT_INVALID` in `DiagnosticCode`:

```java
  COMMAND_INPUT_INVALID("DEDIREN_COMMAND_INPUT_INVALID"),
  COMMAND_IO_FAILED("DEDIREN_COMMAND_IO_FAILED"),
```

  In `Main`, add a handler next to `printStructuralFailure`:

```java
  /**
   * A command-owned I/O failure (for example a build artifact write hitting an unwritable or
   * colliding {@code --out}): unlike printStructuralFailure's deliberately raw legacy observable,
   * this is a first-class envelope so agents can decide the outcome from stdout alone.
   */
  private static Integer printCommandIoFailure(CommandSpec spec, UncheckedIOException error)
      throws IOException {
    spec.commandLine().getErr().println(error.getMessage());
    return writeEnvelope(
        spec,
        usageError(DiagnosticCode.COMMAND_IO_FAILED.code(), error.getMessage()),
        CommandExitCode.INPUT_ERROR);
  }
```

  and extend the build command's catch:

```java
      try {
        return writePluginOutcome(
            spec, dev.dediren.core.commands.BuildCommand.run(request, engines));
      } catch (EngineExecutionException error) {
        return writePluginError(spec, error);
      } catch (UncheckedIOException error) {
        return printCommandIoFailure(spec, error);
      }
```

- [ ] **Step 4: Run the test again, verify PASS**, then run the guards:
  `./mvnw -pl contracts -am test` (DiagnosticCode uniqueness/provenance guards —
  if `DiagnosticProvenanceTest` enumerates code emitters, register
  `COMMAND_IO_FAILED` as cli-owned exactly like `COMMAND_INPUT_INVALID`), then
  `./mvnw -pl core,cli -am test`.

- [ ] **Step 5: Document.** In `docs/agent-usage.md`'s Build section (after the
  flag table), add one sentence: an artifact write failure (unwritable or
  colliding `--out`) yields a `DEDIREN_COMMAND_IO_FAILED` error envelope on
  stdout with exit 2. Run
  `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false`.

- [ ] **Step 6: Commit** `fix(cli): envelope build artifact-write failures instead of a raw stack trace`.

---

### Task 2: Product-root resolution failure emits a structured envelope

`DedirenPaths` throws raw `IllegalStateException` when the root walk-up fails
or a configured `DEDIREN_BUNDLE_ROOT` is invalid; it escapes as a stack trace
with empty stdout from every schema-touching command.

**Files:**
- Create: `core/src/main/java/dev/dediren/core/ProductRootException.java`
- Modify: `core/src/main/java/dev/dediren/core/DedirenPaths.java:42-48,59-68`
- Modify: `core/src/main/java/dev/dediren/core/engine/EngineDispatch.java:105-121`
- Modify: `contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java`
- Modify: `cli/src/main/java/dev/dediren/cli/Main.java` (validate/project/export/build catch sites, new helper)
- Test: `cli/src/test/java/dev/dediren/cli/MainTest.java`, `core/src/test/java/dev/dediren/core/DedirenPathsTest.java` (if present; else the existing core test covering DedirenPaths)

**Interfaces:**
- Produces: `dev.dediren.core.ProductRootException extends IllegalStateException` (public, message-only constructor); `DiagnosticCode.PRODUCT_ROOT_UNRESOLVED` (`"DEDIREN_PRODUCT_ROOT_UNRESOLVED"`).

- [ ] **Step 1: Write the failing cli test** (the property override is read
  from JVM globals, so set it with try/finally — the env-map injection does
  not reach `DedirenPaths`):

```java
@Test
void misconfiguredBundleRootEmitsErrorEnvelope(@TempDir Path tempDir) throws Exception {
  String previous = System.getProperty(DedirenPaths.BUNDLE_ROOT_PROPERTY);
  System.setProperty(DedirenPaths.BUNDLE_ROOT_PROPERTY, tempDir.resolve("missing").toString());
  try {
    CliResult result =
        runValidate(sequenceWorkflowEnv(), workspaceRoot().resolve("fixtures/source/valid-basic.json"));
    assertThat(result.exitCode()).isEqualTo(2);
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_PRODUCT_ROOT_UNRESOLVED");
  } finally {
    if (previous == null) {
      System.clearProperty(DedirenPaths.BUNDLE_ROOT_PROPERTY);
    } else {
      System.setProperty(DedirenPaths.BUNDLE_ROOT_PROPERTY, previous);
    }
  }
}
```

- [ ] **Step 2: Run it, verify it fails** (stack trace, exit 1 today).

- [ ] **Step 3: Implement.** New exception type:

```java
package dev.dediren.core;

/**
 * The Dediren product root (schemas/, fixtures/) could not be resolved from the configured
 * override or the working-directory walk-up. Subclasses IllegalStateException so pre-existing
 * broad catches keep working; cli converts it to a DEDIREN_PRODUCT_ROOT_UNRESOLVED envelope.
 */
public final class ProductRootException extends IllegalStateException {
  public ProductRootException(String message) {
    super(message);
  }
}
```

  In `DedirenPaths`, replace both `throw new IllegalStateException(` with
  `throw new ProductRootException(` (same messages). Add the enum constant:

```java
  COMMAND_IO_FAILED("DEDIREN_COMMAND_IO_FAILED"),
  PRODUCT_ROOT_UNRESOLVED("DEDIREN_PRODUCT_ROOT_UNRESOLVED"),
```

  In `EngineDispatch.dispatchInMemory`, rethrow it before the generic
  catch-all (a misconfigured bundle root is environment, not an engine
  failure), next to the existing `UncheckedIOException` rethrow:

```java
    } catch (UncheckedIOException error) {
      throw error;
    } catch (ProductRootException error) {
      // Environment misconfiguration, not an engine defect: let cli convert it to its own
      // envelope instead of burying it in DEDIREN_ENGINE_FAILED.
      throw error;
    } catch (Exception error) {
```

  In `Main`, add the helper:

```java
  private static Integer printProductRootFailure(CommandSpec spec, ProductRootException error)
      throws IOException {
    spec.commandLine().getErr().println(error.getMessage());
    return writeEnvelope(
        spec,
        usageError(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED.code(), error.getMessage()),
        CommandExitCode.INPUT_ERROR);
  }
```

  and add `catch (ProductRootException error) { return printProductRootFailure(spec, error); }`
  to the commands whose lanes reach `DedirenPaths.productRoot()` — validate
  (wrap BOTH lanes: the `SourceValidator.validateSourceJson` call at
  Main.java:154-155 is currently outside any try), project, export, and build.
  Verify the reach set with
  `grep -rn 'productRoot()' core/src/main cli/src/main` before wiring.

- [ ] **Step 4: Run** `./mvnw -pl contracts -am test` then
  `./mvnw -pl core,cli -am test`; the new test passes, nothing else moves.

- [ ] **Step 5: Commit** `fix(core,cli): envelope product-root resolution failures`.

---

### Task 3: Bound the schema-download subprocess wall clock

`SchemaCacheModule.curlFetcher` runs curl with no `--max-time` and blocks on an
unbounded `waitFor()`; the sibling `XmlSchemaValidator` deliberately bounds its
subprocess at 60 s. A post-accept stall hangs the export lane forever.

**Files:**
- Modify: `schema-cache/src/main/java/dev/dediren/schemacache/SchemaCacheModule.java:172-198`
- Modify: `docs/threat-model.md` (~line 58, the curlFetcher hardening paragraph)
- Test: `schema-cache/src/test/java/dev/dediren/schemacache/SchemaCacheModuleTest.java` (or the existing module test class)

**Interfaces:**
- Produces: package-private `static List<String> SchemaCacheModule.curlArgs(<url type as in SchemaFetcher>, Path destination)`.

- [ ] **Step 1: Write the failing test** — extract-and-assert style, since the
  ProcessBuilder arg list is otherwise uninspectable:

```java
@Test
void curlArgsBoundTransferTimeAndForbidProtocolDowngrade() {
  List<String> args = SchemaCacheModule.curlArgs(URI.create("https://example.org/x.xsd"), Path.of("/tmp/x"));
  assertThat(args).containsSequence("--proto", "=https");
  assertThat(args).containsSequence("--max-time", "60");
}
```

  (Match the URL parameter type to the `SchemaFetcher` functional interface —
  check its signature first.)

- [ ] **Step 2: Run it, verify it fails to compile** (`curlArgs` missing).

- [ ] **Step 3: Implement.** Extract the argument list, preserving the existing
  proto comment, adding the bound (60 s matches
  `XmlSchemaValidator.DEFAULT_TIMEOUT`):

```java
  static List<String> curlArgs(URI url, Path destination) {
    return List.of(
        // Forbid protocol downgrade when following redirects: only https is allowed for
        // the initial request and every redirect hop (audit finding F2).
        "--proto",
        "=https",
        "--location",
        "--fail",
        "--silent",
        "--show-error",
        // Bound the whole transfer like XmlSchemaValidator bounds its subprocess: a stalled
        // download must degrade to a structured fetch failure, not hang the export lane.
        "--max-time",
        "60",
        url.toString(),
        "--output",
        destination.toString());
  }
```

  In `curlFetcher`, build the command as the launcher binary plus `curlArgs`:

```java
      List<String> command_ = new ArrayList<>();
      command_.add(command);
      command_.addAll(curlArgs(url, destination));
      Process process = new ProcessBuilder(command_).start();
```

- [ ] **Step 4: Run** `./mvnw -pl schema-cache -am test`, then the export
  consumers: `./mvnw -pl engines/archimate-oef-export,engines/uml-xmi-export,cli -am test`.

- [ ] **Step 5: Update `docs/threat-model.md`** (schema-cache fetching is a
  move-together surface): in the curlFetcher paragraph add that the transfer
  is bounded with `--max-time 60`, mirroring the validator subprocess bound.

- [ ] **Step 6: Commit** `fix(schema-cache): bound the curl schema download at 60s`.

---

### Task 4: ENGINE_FAILED keeps its cause

The dispatch catch-all keeps only `error.getMessage()`; the exception object is
dropped — nothing on stderr, no cause chain, and a null message yields
`"engine X failed: null"`.

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/engine/EngineExecutionException.java`
- Modify: `core/src/main/java/dev/dediren/core/engine/EngineDispatch.java:111-120`
- Modify: `cli/src/main/java/dev/dediren/cli/Main.java:530-535` (`writePluginError`)
- Test: `core/src/test/java/dev/dediren/core/engine/EngineDispatchTest.java`

**Interfaces:**
- Produces: `EngineExecutionException.plugin(String code, String pluginId, String message, Throwable cause)` overload (existing 3-arg factories unchanged).

- [ ] **Step 1: Write the failing test** in `EngineDispatchTest`:

```java
@Test
void unexpectedEngineFailureKeepsTheCause() {
  RuntimeException boom = new RuntimeException("boom");
  EngineExecutionException error =
      assertThrows(
          EngineExecutionException.class,
          () -> EngineDispatch.dispatchInMemory("elk", () -> { throw boom; }));
  assertThat(error.getCause()).isSameAs(boom);
  assertThat(error.diagnostic().code()).isEqualTo("DEDIREN_ENGINE_FAILED");
}
```

- [ ] **Step 2: Run it, verify it fails** (`getCause()` is null today).

- [ ] **Step 3: Implement.** In `EngineExecutionException`, add a cause-carrying
  private constructor and factory overload:

```java
  private EngineExecutionException(
      String code, String diagnosticPath, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.diagnosticPath = diagnosticPath;
  }

  public static EngineExecutionException plugin(
      String code, String pluginId, String message, Throwable cause) {
    return new EngineExecutionException(code, "plugin:" + pluginId, message, cause);
  }
```

  (Delegate the existing 3-arg constructor to the 4-arg one with `null`.)
  In `EngineDispatch`'s catch-all, pass the cause and survive a null message:

```java
      String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
      throw EngineExecutionException.plugin(
          DiagnosticCode.ENGINE_FAILED.code(), engineId, "engine " + engineId + " failed: " + detail, error);
```

  In `Main.writePluginError`, surface the cause on stderr (the human channel);
  the stdout envelope is unchanged:

```java
  private static Integer writePluginError(CommandSpec spec, EngineExecutionException error)
      throws IOException {
    if (error.getCause() != null) {
      error.getCause().printStackTrace(spec.commandLine().getErr());
    }
    return writeEnvelope(
        spec, CommandEnvelope.error(List.of(error.diagnostic())), CommandExitCode.PLUGIN_ERROR);
  }
```

- [ ] **Step 4: Run** `./mvnw -pl core,cli -am test`; all green.

- [ ] **Step 5: Commit** `fix(core,cli): chain and surface the ENGINE_FAILED cause`.

---

### Task 5: Report every schema violation, not the alphabetically first

`SourceValidator.parseSourceDocument` throws with
`List.of(schemaError(errors.getFirst()))` although `SchemaValidator` returns
all sorted violation messages and the semantic lane below accumulates all
diagnostics.

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/source/SourceValidator.java:122-124`
- Test: the existing `core` SourceValidator test class (add a case)

- [ ] **Step 1: Write the failing test:** craft an inline source JSON with two
  independent schema violations (for example two nodes each missing a
  different required property — inspect `schemas/model.schema.json` for two
  cheap violations) and assert the thrown/returned diagnostics list has
  size ≥ 2 with all codes `DEDIREN_SCHEMA_INVALID`.

- [ ] **Step 2: Run it, verify it fails** (size is 1 today).

- [ ] **Step 3: Implement:**

```java
    if (!errors.isEmpty()) {
      throw new SourceDiagnosticsException(
          errors.stream().map(SourceValidator::schemaError).toList());
    }
```

- [ ] **Step 4: Run** `./mvnw -pl core,cli -am test`. If any existing test
  asserted exactly one diagnostic for a multi-violation input, that assertion
  encoded the truncation — update it to the full list.

- [ ] **Step 5: Commit** `fix(core): report all schema violations instead of the first`.

---

### Task 6: `mapOrEmpty` preserves insertion order

`Map.copyOf(new LinkedHashMap<>(values))` discards the wrapper's order —
`Map.copyOf` returns hash-ordered `MapN`, so `RenderMetadata`/`SourceNode`
map fields serialize in probe order. The `LinkedHashMap` wrapper is evidence
of author intent since the original Java port.

**Files:**
- Modify: `contracts/src/main/java/dev/dediren/contracts/util/ContractCollections.java:23-25`
- Test: Create `contracts/src/test/java/dev/dediren/contracts/util/ContractCollectionsTest.java`

- [ ] **Step 1: Write the failing test:**

```java
package dev.dediren.contracts.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractCollectionsTest {
  @Test
  void mapOrEmptyPreservesInsertionOrder() {
    Map<String, String> source = new LinkedHashMap<>();
    // Enough keys that hash-probe order reliably diverges from insertion order.
    for (String key : new String[] {"zeta", "alpha", "mu", "beta", "omega", "kappa", "iota"}) {
      source.put(key, key.toUpperCase(java.util.Locale.ROOT));
    }
    assertThat(ContractCollections.mapOrEmpty(source).keySet())
        .containsExactly("zeta", "alpha", "mu", "beta", "omega", "kappa", "iota");
  }

  @Test
  void mapOrEmptyStaysImmutableAndNullHostile() {
    Map<String, String> copy = ContractCollections.mapOrEmpty(Map.of("a", "b"));
    assertThrows(UnsupportedOperationException.class, () -> copy.put("c", "d"));
    Map<String, String> withNull = new LinkedHashMap<>();
    withNull.put("a", null);
    assertThrows(NullPointerException.class, () -> ContractCollections.mapOrEmpty(withNull));
    assertThat(ContractCollections.mapOrEmpty(null)).isEmpty();
  }
}
```

- [ ] **Step 2: Run it** — the order test fails (hash order), the immutability
  test may pass; verify the failure is the order assertion.

- [ ] **Step 3: Implement** (keep `Map.copyOf`'s null-hostility explicitly):

```java
  public static <T> Map<String, T> mapOrEmpty(Map<String, T> values) {
    if (values == null) {
      return Map.of();
    }
    LinkedHashMap<String, T> copy = new LinkedHashMap<>(values);
    copy.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "map key");
          Objects.requireNonNull(value, "map value");
        });
    return Collections.unmodifiableMap(copy);
  }
```

- [ ] **Step 4: Run the full reactor** `./mvnw test` — this is a
  byte-observable serialization-order change for map-carrying contract records
  (`RenderMetadata`, `SourceNode`, `SourceRelationship`, `SvgStylePolicy`).
  If render-metadata goldens or `--emit` byte-identity tests diff, inspect:
  the new order is the document/insertion order, which is the intended one —
  update the affected fixtures/goldens in this task and say so in the commit.

- [ ] **Step 5: Commit** `fix(contracts): mapOrEmpty preserves insertion order`.

---

### Task 7: Shared XML 1.0 character policy for all three XML emitters

XMI, OEF, and SVG emitters escape only `& < > "`; a contract-valid label
containing a C0 control character (model.schema constrains ids, not labels)
yields an ill-formed artifact — the exporters mis-attribute it as
generation/schema failure, render ships a broken artifact under an `ok`
envelope. One shared scrubber; the historical duplication of exactly this
kind of helper was just consolidated by the architecture-remediation merge,
so the shared home is `engine-api` (which already hosts the lifted semantics
failure factory).

**Files:**
- Create: `engine-api/src/main/java/dev/dediren/engine/XmlText.java`
- Create: `engine-api/src/test/java/dev/dediren/engine/XmlTextTest.java`
- Modify: `engines/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/build/XmiHelpers.java:170-183`
- Modify: `engines/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/OefExportEngine.java:672-677`
- Modify: `engines/render/src/main/java/dev/dediren/plugins/render/svg/SvgWriter.java:58-75`
- Test: existing emitter test classes in each engine module

**Interfaces:**
- Produces: `public static String XmlText.scrub(String value)` — null-in/null-out; returns the SAME instance when nothing is invalid (byte-stability fast path).

- [ ] **Step 1: Write the failing engine-api test:**

```java
package dev.dediren.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XmlTextTest {
  @Test
  void replacesXmlInvalidCharactersWithReplacementChar() {
    assertThat(XmlText.scrub("a\u0000b\u0007c")).isEqualTo("a�b�c");
    assertThat(XmlText.scrub("lone-high\uD800end")).isEqualTo("lone-high�end");
    assertThat(XmlText.scrub("ffff:\uFFFF")).isEqualTo("ffff:�");
  }

  @Test
  void preservesEverythingXmlCanRepresent() {
    String clean = "tab\t lf\n cr\r text ünïcode 😀";
    assertThat(XmlText.scrub(clean)).isSameAs(clean);
    assertThat(XmlText.scrub(null)).isNull();
  }
}
```

- [ ] **Step 2: Run it, verify it fails to compile.**

- [ ] **Step 3: Implement:**

```java
package dev.dediren.engine;

/**
 * XML 1.0 character policy shared by every XML-emitting engine (SVG, OEF, XMI). Escaping of
 * {@code & < > "} stays each emitter's job; this class only guarantees validity: characters XML
 * 1.0 cannot represent at all (C0 controls other than tab/LF/CR, lone surrogates, U+FFFE/U+FFFF)
 * are replaced with U+FFFD, so a contract-valid label can never yield an ill-formed artifact.
 */
public final class XmlText {
  private XmlText() {}

  public static String scrub(String value) {
    if (value == null) {
      return null;
    }
    int firstInvalid = -1;
    for (int i = 0; i < value.length(); i++) {
      if (!validAt(value, i)) {
        firstInvalid = i;
        break;
      }
    }
    if (firstInvalid < 0) {
      return value;
    }
    StringBuilder out = new StringBuilder(value.length());
    out.append(value, 0, firstInvalid);
    for (int i = firstInvalid; i < value.length(); i++) {
      out.append(validAt(value, i) ? value.charAt(i) : '�');
    }
    return out.toString();
  }

  private static boolean validAt(String value, int index) {
    char c = value.charAt(index);
    if (Character.isHighSurrogate(c)) {
      return index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1));
    }
    if (Character.isLowSurrogate(c)) {
      return index > 0 && Character.isHighSurrogate(value.charAt(index - 1));
    }
    return c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c <= 0xFFFD);
  }
}
```

- [ ] **Step 4: Run** `./mvnw -pl engine-api -am test`; PASS.

- [ ] **Step 5: Wire the three emitters (test-first per emitter).**
  XMI — failing test in the uml-xmi module:
  `assertThat(XmiHelpers.text("a\u0007b")).isEqualTo("a�b");` then:

```java
  public static String attr(String value) {
    return XmlText.scrub(value == null ? "" : value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  public static String text(String value) {
    return XmlText.scrub(value == null ? "" : value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }
```

  OEF — same change to `OefExportEngine.text` (its `attr` delegates to
  `text`); test through the engine's existing harness with a label containing
  `\u0007` (BEL) and assert the emitted XML parses (reuse whatever XML parse helper
  the module's tests already use) and contains `�`.
  SVG — in `SvgWriter`:

```java
  public SvgWriter attr(String name, String value) {
    run(() -> writer.writeAttribute(name, XmlText.scrub(value)));
    return this;
  }

  public SvgWriter text(String value) {
    run(() -> writer.writeCharacters(value == null ? "" : XmlText.scrub(value)));
    return this;
  }
```

  with an `SvgWriter` unit test: `new SvgWriter().start("t").attr("l","a\u0007").text("b\u0000").end().finish()`
  contains two `�` and no raw control chars. Update the `SvgWriter` class
  javadoc's escaping description to mention the validity scrub.
  Confirm each of the three modules already declares the `engine-api`
  dependency in its pom (they implement its interfaces; just verify).

- [ ] **Step 6: Run the three lanes** — render goldens are the byte-stability
  oracle and must NOT change (goldens contain no control characters; the fast
  path returns the same instance):
  `./mvnw -pl engines/render,cli -am test && ./mvnw -pl engines/uml-xmi-export,cli -am test && ./mvnw -pl engines/archimate-oef-export,cli -am test`

- [ ] **Step 7: Commit** `fix(engines): scrub XML-invalid characters in all three XML emitters`.

---

### Task 8: Line-jump emission gets a start-of-segment bound

`roundedPathDataWithLineJumps` filters jumps only against the segment END
(`rounded.before()` progress); the pen actually resumes at the PREVIOUS
corner's `rounded.after()` (up to 8px into the segment), so a crossing inside
that entry region makes the path double back.

**Files:**
- Modify: `engines/render/src/main/java/dev/dediren/plugins/render/svg/EdgeRenderer.java:202-249`
- Test: the existing `EdgeRenderer` test class in `engines/render`

- [ ] **Step 1: Write the failing test** — a jump inside a rounded corner's
  entry region must be dropped, making the jump-path identical to the plain
  rounded path:

```java
@Test
void jumpInsideRoundedCornerEntryRegionIsDropped() {
  List<Point> points = List.of(new Point(0, 0), new Point(100, 0), new Point(100, 100));
  // Segment 1 runs (100,0)->(100,100); the corner at (100,0) resumes the pen at (100, r).
  // A crossing at y=3 sits inside that entry region and must not be emitted.
  LineJump insideEntry = new LineJump(1, 100.0, 3.0, true);

  assertThat(EdgeRenderer.roundedPathDataWithLineJumps(points, List.of(insideEntry)))
      .isEqualTo(EdgeRenderer.roundedPathData(points));
}
```

  (If `roundedCorner`'s radius shrinks below 3.0 for these lengths, scale the
  route up until the entry region covers y=3 — read `roundedCorner` first.)

- [ ] **Step 2: Run it, verify it fails** (today the jump is emitted and the
  path doubles back).

- [ ] **Step 3: Implement** — track the pen's resume point across iterations
  and add the symmetric lower bound:

```java
    StringBuilder data = new StringBuilder();
    Point first = points.getFirst();
    data.append(String.format(Locale.ROOT, "M %.1f %.1f", first.x(), first.y()));
    Point penStart = first;
    for (int index = 0; index < points.size() - 1; index++) {
      int segmentIndex = index;
      Point start = points.get(index);
      Point end = points.get(index + 1);
      RoundedCorner rounded =
          index + 2 < points.size() ? roundedCorner(start, end, points.get(index + 2)) : null;
      Point segmentEnd = rounded == null ? end : rounded.before();
      double segmentStartProgress = segmentProgress(start, end, penStart.x(), penStart.y());
      double segmentEndProgress = segmentProgress(start, end, segmentEnd.x(), segmentEnd.y());
      List<LineJump> segmentJumps =
          lineJumps.stream()
              .filter(jump -> jump.segmentIndex() == segmentIndex)
              .filter(
                  jump -> {
                    double progress = segmentProgress(start, end, jump.x(), jump.y());
                    // Both bounds, symmetrically: the pen enters at the previous corner's
                    // rounded.after() and leaves at this corner's rounded.before(); a jump
                    // outside that window would make the path double back.
                    return progress >= segmentStartProgress - 0.001
                        && progress <= segmentEndProgress + 0.001;
                  })
              .sorted(
                  (left, right) ->
                      Double.compare(
                          segmentProgress(start, end, left.x(), left.y()),
                          segmentProgress(start, end, right.x(), right.y())))
              .toList();
      for (LineJump jump : segmentJumps) {
        data.append(" ").append(jump.pathPrefix(start, end));
      }
      data.append(String.format(Locale.ROOT, " L %.1f %.1f", segmentEnd.x(), segmentEnd.y()));
      if (rounded != null) {
        data.append(
            String.format(
                Locale.ROOT,
                " Q %.1f %.1f %.1f %.1f",
                end.x(),
                end.y(),
                rounded.after().x(),
                rounded.after().y()));
      }
      penStart = rounded == null ? end : rounded.after();
    }
    return data.toString();
```

- [ ] **Step 4: Run** `./mvnw -pl engines/render,cli -am test` — the
  `RenderGoldenTest` byte oracle must stay green (jumps in the goldens sit in
  segment interiors; only degenerate entry-region jumps are dropped).

- [ ] **Step 5: Commit** `fix(render): bound line jumps at the rounded-corner entry region`.

---

### Task 9: Test-net completions (conformance sweep, version guard, mapper equality)

Three cheap guards the review found missing.

**Files:**
- Modify: `contracts/src/test/java/dev/dediren/contracts/FixtureConformanceSweepTest.java:31-71`
- Modify: `contracts/src/test/java/dev/dediren/contracts/ContractVersionsTest.java`
- Modify: `ir/src/test/java/dev/dediren/ir/LaidOutSceneMapperTest.java:69-110`

- [ ] **Step 1: Enroll `fixtures/export-policy` in the sweep.** The directory
  holds two fixtures with DIFFERENT schemas, which the one-schema-per-directory
  `FAMILIES` map cannot express; add a per-file map plus a guard so a new
  export-policy fixture cannot dodge the sweep:

```java
  /** Fixture file -> schema, for directories whose fixtures use different schemas. */
  private static final Map<String, String> PER_FILE_FAMILIES =
      Map.of(
          "fixtures/export-policy/default-oef.json", "schemas/oef-export-policy.schema.json",
          "fixtures/export-policy/default-uml-xmi.json",
              "schemas/uml-xmi-export-policy.schema.json");
```

  In `fixtures()`, after the directory loop:

```java
    PER_FILE_FAMILIES.forEach((fixture, schema) -> cases.add(new Object[] {fixture, schema}));
```

  New guard test in the same class:

```java
  @Test
  void everyExportPolicyFixtureIsEnrolled() throws IOException {
    try (Stream<Path> files = Files.list(workspaceRoot().resolve("fixtures/export-policy"))) {
      assertThat(files.map(path -> "fixtures/export-policy/" + path.getFileName()).sorted())
          .containsExactlyElementsOf(PER_FILE_FAMILIES.keySet().stream().sorted().toList());
    }
  }
```

- [ ] **Step 2: Add build-result to both version guards** in
  `ContractVersionsTest`:

```java
    versionConstBySchema.put(
        "schemas/build-result.schema.json",
        new String[] {"build_result_schema_version", ContractVersions.BUILD_RESULT_SCHEMA_VERSION});
```

  and in `schemaVersionConstantsMatchPublicSchemas`:

```java
    assertThat(ContractVersions.BUILD_RESULT_SCHEMA_VERSION).isEqualTo("build-result.schema.v1");
```

- [ ] **Step 3: Make the mapper round-trip a whole-record identity.** In
  `LaidOutSceneMapperTest.roundTripPreservesGeometryIdsRoleAndSourcePointer`,
  replace the entire hand-enumerated per-field block (nodes, edges, groups,
  warnings) with:

```java
    // Whole-record equality: a future field added to the records but missed by the mapper
    // fails here structurally, instead of passing a hand-enumerated field list silently.
    assertThat(roundTripped).isEqualTo(result);
```

  Keep the schema-version and `toSceneWrapsSourcePointerIntoOrigin` assertions
  as they are.

- [ ] **Step 4: Run** `./mvnw -pl contracts,ir -am test` — all four export-policy
  sweep rows pass, both version guards pass, the identity assert passes.

- [ ] **Step 5: Commit** `test(contracts,ir): enroll export-policy fixtures, guard build-result version, whole-record mapper identity`.

---

### Task 10: Always-on layout-fixture freshness gate

Fixture freshness currently relies entirely on the opt-in regenerator; the
checked-in layout-result fixtures can silently stop representing real engine
output — a milder rerun of the stale-golden failure this repo already paid
for. Determinism is already guaranteed (bundled Liberation Sans, pinned ELK;
the jqwik property suite runs the real engine in cli tests routinely).

**Files:**
- Modify: `cli/src/test/java/dev/dediren/cli/LayoutFixtureRegenerator.java` (extract reusable mapping + regeneration)
- Create: `cli/src/test/java/dev/dediren/cli/LayoutFixtureFreshnessTest.java`

**Interfaces:**
- Produces (package-private, in `LayoutFixtureRegenerator`): `record FixtureMapping(String fixtureName, String sourceFileName, String viewId)`, `static List<FixtureMapping> mappings()`, `static String regeneratedJson(FixtureMapping mapping) throws Exception` — returns exactly the pretty-printed JSON + trailing `\n` the regenerator writes.

- [ ] **Step 1: Extract.** In `LayoutFixtureRegenerator`, lift the engine
  assembly + per-mapping pipeline out of the `@Test` into
  `static String regeneratedJson(FixtureMapping mapping)` (build the
  `SemanticsRouterEngine`/`ElkEngine` inside it, or in a private static
  helper), expose `static List<FixtureMapping> mappings()` returning
  `MAPPINGS`, and have the opt-in test body become: for each mapping,
  `Files.writeString(target, regeneratedJson(mapping), StandardCharsets.UTF_8)`.
  Behavior of the opt-in path must be byte-identical — same serializer
  (`writerWithDefaultPrettyPrinter()` + `"\n"`).

- [ ] **Step 2: Write the freshness gate** (this is the new failing/passing test):

```java
package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.testsupport.TestSupport;
import java.nio.file.Files;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Always-on drift gate: every checked-in layout-result fixture must be byte-identical to what the
 * real project->layout pipeline produces today. The opt-in LayoutFixtureRegenerator writes these
 * bytes; this gate keeps them honest between regenerations (the stale-golden failure mode this
 * repo has paid for before). Determinism: bundled Liberation Sans + pinned ELK.
 */
class LayoutFixtureFreshnessTest {

  static Stream<LayoutFixtureRegenerator.FixtureMapping> mappings() {
    return LayoutFixtureRegenerator.mappings().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mappings")
  void checkedInFixtureMatchesRealEngineOutput(LayoutFixtureRegenerator.FixtureMapping mapping)
      throws Exception {
    String checkedIn =
        Files.readString(
            TestSupport.workspaceRoot().resolve("fixtures/layout-result").resolve(mapping.fixtureName()));
    assertThat(LayoutFixtureRegenerator.regeneratedJson(mapping))
        .describedAs("run scripts/regen-layout-fixtures.sh if this drift is an intended geometry change")
        .isEqualTo(checkedIn);
  }
}
```

- [ ] **Step 3: Run** `./mvnw -pl cli -am test -Dtest='LayoutFixture*' -Dsurefire.failIfNoSpecifiedTests=false`.
  If any row is red, the fixtures are ALREADY stale — that is the gate doing
  its job on day one: run `scripts/regen-layout-fixtures.sh`, inspect the
  fixture diff, and (a) if the geometry delta is explainable by a merged
  change, commit the regeneration as its own commit with that explanation;
  (b) if it is not explainable, STOP and investigate before proceeding
  (systematic-debugging).

- [ ] **Step 4: Run the cli lane** `./mvnw -pl core,cli -am test`.

- [ ] **Step 5: Commit** `test(cli): always-on layout-fixture freshness gate`.

---

### Task 11: Pin the exporters-away-from-ir boundary; fix the §2 edge table

§2 forbids the export engines an `ir` edge, but nothing enforces it and
transitive compilation leaves it reachable; and the §2 table row for
`semantics-archimate` omits its live `ir` dependency.

**Files:**
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java` (new rule next to `exportersDoNotImportSvgEmitter`, ~line 348)
- Modify: `docs/architecture-guidelines.md:78`

- [ ] **Step 1: Verify the live edge** before editing the table:
  `grep -n -A1 '<artifactId>ir</artifactId>' semantics-archimate/pom.xml` — if
  absent, check `./mvnw -pl semantics-archimate dependency:tree` reasoning in
  the imports instead (`grep -rn 'dev.dediren.ir' semantics-archimate/src/main`).
  The review verified the compile edge exists; confirm and note which form.

- [ ] **Step 2: Add the ArchUnit rule** (constants `IR`, `ARCHIMATE_OEF`,
  `UML_XMI`, `PRODUCTION_CLASSES` already exist in the class):

```java
  @Test
  void exportersDoNotImportIr() {
    noClasses()
        .that()
        .resideInAnyPackage(ARCHIMATE_OEF, UML_XMI)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(IR)
        .because(
            "the export engines consume the record-based ExportRequest wire contract, not the"
                + " in-memory IR; §2's edge table allows them no ir edge even though transitive"
                + " compilation leaves it reachable (P4 forced deviation, §5)")
        .check(PRODUCTION_CLASSES);
  }
```

  If the class has a non-vacuity guard enumerating its rules, register the new
  rule there the same way the neighbours are.

- [ ] **Step 3: Fix the table row** in `docs/architecture-guidelines.md:78`, to
  match the verified reality (expected shape):

```
| `semantics-archimate` (engine) | `engine-api`, `contracts`, `archimate`, `ir` | 2 — leaf engine |
```

- [ ] **Step 4: Run** `./mvnw -pl dist-tool -am test` and `git diff --check`.

- [ ] **Step 5: Commit** `chore(arch): pin exporters away from ir; record the semantics-archimate ir edge`.

---

### Task 12: Docs consistency sweep

Five verified doc drifts plus the miscounting javadoc. All edits below were
verified against live code during planning; re-verify each with the named grep
before editing.

**Files:**
- Modify: `README.md:155`
- Modify: `docs/agent-usage.md` (~236-243, ~265, ~817-819)
- Modify: `CLAUDE.md` (~line 82, "Files That Move Together")
- Modify: `contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java:4-13` (javadoc only)

- [ ] **Step 1: README profile literal.** Line 155: change
  `` - **Generic** graph views (`generic` profile). `` to
  `` - **Generic** graph views (`generic-graph` profile). ``
  (verify: `grep -n '"generic-graph"' schemas/model.schema.json`).

- [ ] **Step 2: agent-usage constraint vocabulary.** Replace the first bullet
  under "Layout constraints in a hand-written layout-request" (~236) and the
  stale "silently dropped" caveat (~240-243) with:

```markdown
- `kind` is `ordered-band:x`, `ordered-band:y`, or `stem-span`. An ordered
  band's subjects form an ordered band along that axis (this is how UML
  sequence lifelines and message rows are placed). A `stem-span` constraint
  carries exactly four subjects — `[node-id, lifeline-id, from-message-id,
  to-message-id]` — anchoring a node (an execution specification or a
  destruction marker) to the span of its lifeline stem between those two
  messages; empty from/to ids mean the stem's full extent. `project` emits
  both kinds for you. An unrecognised `kind` is rejected by the layout engine.
- Each ordered-band subject is a node id, optionally `@` plus a leading gap in
  layout units (`lifeline-b@48` leaves 48 units before that member).
- The `@` separator is unambiguous: the id charset
  (`[A-Za-z0-9][A-Za-z0-9._-]*`) cannot contain `@`. A subject whose `@` tail
  is not a number is rejected by the layout engine, not silently dropped.
```

  (Verify semantics against `ir/.../LayoutIntent.java` `StemSpan(nodeId,
  bandMemberId, fromMemberId, toMemberId)` and the `UmlSequenceConstraints`
  javadoc before finalizing wording.)

- [ ] **Step 3: agent-usage build table.** ~265: change
  `` writes `<view-id>/diagram.<svg\|html>` `` to
  `` writes `<view-id>/diagram.svg` `` — the bundled render engine emits only
  `svg` (the same doc says so at ~223; `html` is schema headroom only).

- [ ] **Step 4: agent-usage CDS paragraph.** ~817-819: replace the sentence
  claiming `-Xlog:cds=off` keeps warnings off stdout AND stderr with the
  launcher's real behavior (DistTool.java:871):

```markdown
The launcher also passes `-Xlog:cds=off:stdout -Xlog:cds=warning:stderr`,
keeping stdout JSON-pure while preserving first-launch CDS warnings on stderr
(the human debug channel); once the archive exists, a healthy run stays quiet.
```

- [ ] **Step 5: CLAUDE.md move-together row.** In "Engine contract or runtime
  changes", add `ir`: `update `engine-api`, `ir` (the SceneGraph/LaidOutScene
  seam types), `core` dispatch, `cli` `EngineWiring`, ...`.

- [ ] **Step 6: DiagnosticCode javadoc.** Verify the real count first:
  `grep -rn 'DEDIREN_ARCHIMATE_' archimate/src/main --include='*.java'`.
  Then correct "owns its six {@code DEDIREN_ARCHIMATE_*} codes locally,
  emitted from a single switch per exception type" to the verified reality
  (five codes; three from the `ArchimateTypeValidationException` switch, two
  as constructor-site literals).

- [ ] **Step 7: Run the gates:**
  `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false`,
  `./mvnw -pl contracts -am test`, `git diff --check`.

- [ ] **Step 8: Commit** `docs: fix profile literal, constraint vocabulary, render outputs, CDS logging, move-together row, archimate code count`.

---

### Task 13: Release-workflow attestation scope + accepted-debt recording

Two author's-call devsecops items are fixed cheap; two review findings are
explicitly ACCEPTED and recorded so future reviews stop re-finding them.

**Files:**
- Modify: `.github/workflows/release.yml` (~line 111, "Attest archive provenance")
- Modify: `docs/threat-model.md` (residual-risk table, ~line 143)
- Modify: `docs/architecture-guidelines.md` §12 (known-debt register)

- [ ] **Step 1: Gate attestation to tag builds.** Add a condition to the
  attest step in the `build` job so a `workflow_dispatch` run does not mint
  release provenance:

```yaml
      - name: Attest archive provenance
        if: startsWith(github.ref, 'refs/tags/v')
        uses: actions/attest-build-provenance@0f67c3f4856b2e3261c31976d6725780e5e4c373 # v4.1.1
        with:
          subject-path: ${{ steps.archive.outputs.path }}
```

  (Check the `build` job's trigger set first; if `build` also runs on plain
  pushes, this `if:` is exactly right — the publish job is already tag-gated.)

- [ ] **Step 2: Record the accepted residuals** in `docs/threat-model.md`'s
  residual-risk table, one new row:

```markdown
| Tampered SBOM / SHA256SUMS after build | Bundle archive carries build provenance attestation | The SBOM and SHA256SUMS themselves are unattested, and the SBOM is regenerated in the publish job rather than carried from the attested build (accepted 2026-07) |
```

- [ ] **Step 3: Record the stub-XSD assurance debt** in
  `docs/architecture-guidelines.md` §12, one new row in the register (match
  the existing table's columns):

```markdown
| Every automated xmllint validation lane (unit through dist-smoke) validates OEF/XMI output against permissive stub XSDs, never the official OMG/OpenGroup schemas | `engines/{archimate-oef-export,uml-xmi-export}` test resources, dist-smoke | §4 contract surface | real schemas are not redistributable and remote fetch is non-hermetic; "schema-valid export" is therefore proven for the plumbing only. Manual real-schema validation stays possible via `DEDIREN_OEF_SCHEMA_DIR`/`DEDIREN_XMI_SCHEMA_PATH`; an opt-in schema-cache-backed lane is a candidate follow-up (accepted 2026-07) |
```

- [ ] **Step 4: Verify:** `git diff --check`; re-read the release.yml diff
  against the workflow's trigger block (workflow changes are not locally
  testable — say so in the handoff).

- [ ] **Step 5: Commit** `chore(release,docs): tag-gate provenance attestation; record accepted SBOM and stub-XSD debt`.

---

### Task 14: Full gates, audits, handoff

- [ ] **Step 1:** `./mvnw -Pquality spotless:apply`, then the definitive gate:
  `./mvnw -Pquality verify` (full reactor; sandbox disabled).

- [ ] **Step 2:** `./mvnw -pl dist-tool -am verify -Pdist-smoke` (cli behavior
  and doc-consistency surfaces changed).

- [ ] **Step 3: Audit gates** (per CLAUDE.md): run
  `souroldgeezer-audit:test-quality-audit` DEEP over the new/changed tests
  (Tasks 1-2, 4-10), and `souroldgeezer-audit:devsecops-audit` QUICK over the
  schema-cache and release.yml diffs (Tasks 3, 13). Fix blocks; fix or
  explicitly accept warn/info in the handoff.

- [ ] **Step 4:** `git status --short --branch`; confirm only intended files
  are touched; no generated artifacts staged.

- [ ] **Step 5: Handoff.** Report: findings fixed (25) vs recorded-accepted
  (2: stub-XSD lane, SBOM attestation), any test expectations updated in
  Tasks 5/6/10 with their justification, and that release.yml was not
  executable locally. Integration (merge to main / PR) and any release bump
  are the author's call per git-workflow-policy and release-policy.

---

## Self-Review Notes

- **Coverage:** all 27 findings are handled — T1 (block), T2-T8 (code warns/infos),
  T9-T10 (test-net warns/infos), T11 (arch hygiene warns), T12 (docs warns/infos),
  T13 (devsecops infos + the two explicit accepts). The four refuted findings
  are deliberately NOT re-litigated.
- **Byte-stability watchpoints:** T6 (map serialization order — expected fixture
  fallout, owned in-task), T7 (fast path keeps goldens identical), T8 (goldens
  must stay green), T10 (may expose real pre-existing drift; stop-and-investigate
  rule included).
- **Deliberate non-changes:** `printStructuralFailure`'s raw observable for
  validate/project is documented legacy behavior — left alone (T1 note);
  `semantic-validation-result` fixture gap — refuted (cli MainTest covers the
  live lane end-to-end).
