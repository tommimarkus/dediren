# MCP ↔ CLI Parity Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the one real behavioral gap between the MCP `dediren_build` tool and the CLI `build` command (unknown `emit` kinds), normalize `views` in one shared place, and widen `CliMcpParityTest` so the anti-drift guarantee actually covers the surfaces that were blind (profile-validate, emit, export lanes, views).

**Architecture:** Both lanes are thin adapters over the same `core` entry points, so parity is best enforced by pushing shared input rules *down into `core`* (where empty-lane and schema-version gating already live) rather than duplicating them per adapter. The emit gap exists precisely because that one rule was duplicated in the CLI adapter; the fix relocates it to `core` and deletes the duplicate.

**Tech Stack:** Java 21, Maven (Maven Wrapper `./mvnw`), JUnit 5 + AssertJ, picocli (CLI), MCP Java SDK (server). Design spec: `docs/superpowers/specs/2026-07-15-dediren-mcp-cli-parity-design.md`.

## Global Constraints

- Java is formatted by google-java-format (GOOGLE) via Spotless; run `./mvnw -Pquality spotless:apply` before committing Java changes.
- No schema change, no new `DiagnosticCode`, no engine change. `COMMAND_INPUT_INVALID` already exists.
- Emit vocabulary must live in exactly one place (`core` `BuildCommand`). No adapter may re-declare it.
- The MCP trust boundary is out of scope and unchanged: `--root` confinement, path/message sanitization, paths-only input, `VIEW_ID_PATTERN`. Do not "fix" these toward the CLI.
- Byte-identical envelope parity is the load-bearing invariant: identical inputs through `dediren build`/`dediren_build` and `dediren validate`/`dediren_validate` must produce byte-identical envelope JSON (path-normalized where the envelope carries absolute paths).
- Tests run **under the command sandbox** now (the `@TempDir` `/tmp` blocker was fixed on `main`). Do not disable the sandbox for these lanes.

---

### Task 1: Move emit-kind validation into `core`, delete the CLI duplicate

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/commands/BuildCommand.java` (add validation near the top of `run(...)`, after the empty-lane check)
- Modify: `cli/src/main/java/dev/dediren/cli/Main.java` (remove `KNOWN_EMIT_KINDS` field and its pre-dispatch loop)
- Test: `cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java` (add the emit-unknown parity test)

**Interfaces:**
- Consumes: `BuildCommand.run(BuildRequest, Engines)`; the existing constants `EMIT_LAYOUT_REQUEST`/`EMIT_LAYOUT_RESULT`/`EMIT_RENDER_METADATA` and the private helper `buildLevelError(Diagnostic)` (returns `EngineRunOutcome` at `CommandExitCode.INPUT_ERROR`, exit 2).
- Produces: an unknown emit kind now yields a build-level `DEDIREN_COMMAND_INPUT_INVALID` envelope (exit 2) from `core`, identical on both lanes.

- [ ] **Step 1: Write the failing parity test**

Add to `CliMcpParityTest.java`:

```java
  @Test
  void buildRejectsUnknownEmitKindThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("rich-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build", "--input", source.toString(), "--out", cliOut.toString(),
              "--render-policy", renderPolicy.toString(), "--emit", "bogus"
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "render_policy", "policy.json",
                        "emit", java.util.List.of("bogus"))));

    assertThat(cli.exitCode()).isNotZero();
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
    assertThat(textOf(mcp)).contains("DEDIREN_COMMAND_INPUT_INVALID");
  }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./mvnw -q -pl cli -am -Dtest=CliMcpParityTest#buildRejectsUnknownEmitKindThroughBothLanes -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — the MCP lane does not error on `bogus` (its envelope is a success/other build result), so `isError`/envelope differ from the CLI, and/or the CLI's message text (`build --emit has unknown kind ...`) differs from what core will emit.

- [ ] **Step 3: Add the emit-kind check in `core` `BuildCommand.run`**

In `BuildCommand.run(...)`, immediately after the existing empty-lane `if (... renderPolicyText == null && oefPolicyText == null && xmiPolicyText == null) { return buildLevelError(...); }` block, add:

```java
    java.util.Set<String> knownEmitKinds =
        java.util.Set.of(EMIT_LAYOUT_REQUEST, EMIT_LAYOUT_RESULT, EMIT_RENDER_METADATA);
    for (String kind : request.emit()) {
      if (!knownEmitKinds.contains(kind)) {
        return buildLevelError(
            new Diagnostic(
                DiagnosticCode.COMMAND_INPUT_INVALID.code(),
                DiagnosticSeverity.ERROR,
                "unknown emit kind '"
                    + kind
                    + "'; expected one of "
                    + EMIT_LAYOUT_REQUEST
                    + ", "
                    + EMIT_LAYOUT_RESULT
                    + ", "
                    + EMIT_RENDER_METADATA,
                "command:build"));
      }
    }
```

(`Diagnostic`, `DiagnosticCode`, `DiagnosticSeverity` are already imported and used by the empty-lane check. Add `import java.util.Set;` if you prefer the unqualified form.)

- [ ] **Step 4: Delete the CLI duplicate**

In `cli/src/main/java/dev/dediren/cli/Main.java`, inside the `BuildCommand` inner class:
- Remove the `private static final Set<String> KNOWN_EMIT_KINDS = Set.of("layout-request", "layout-result", "render-metadata");` field (and its explanatory comment).
- Remove the pre-dispatch loop at the top of `call()`:

```java
      for (String kind : emit) {
        if (!KNOWN_EMIT_KINDS.contains(kind)) {
          return writeEnvelope(
              spec,
              usageError(
                  DiagnosticCode.COMMAND_INPUT_INVALID.code(),
                  "build --emit has unknown kind '"
                      + kind
                      + "'; expected one of layout-request, layout-result, render-metadata"),
              CommandExitCode.INPUT_ERROR);
        }
      }
```

Leave the rest of `call()` intact (input read, `BuildRequest` assembly, `BuildCommand.run`). `Set` is still used by `Set.copyOf(emit)` later, so keep the `java.util.Set` import.

- [ ] **Step 5: Run the parity test and confirm it passes**

Run: `./mvnw -q -pl cli -am -Dtest=CliMcpParityTest#buildRejectsUnknownEmitKindThroughBothLanes -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS — both lanes now emit the identical core envelope.

- [ ] **Step 6: Run the core + cli suites to confirm no regression**

Run: `./mvnw -q -pl core,cli -am test`
Expected: BUILD SUCCESS (0 failures/errors). If any existing CLI test asserted the old `build --emit has unknown kind` message, update it to the new core message.

- [ ] **Step 7: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add core/src/main/java/dev/dediren/core/commands/BuildCommand.java \
        cli/src/main/java/dev/dediren/cli/Main.java \
        cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java
git commit -m "fix(core): validate build --emit kinds in core so both lanes reject unknowns"
```

---

### Task 2: Normalize `views` in `BuildRequest` (dedup + drop-blank)

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/commands/BuildRequest.java` (compact constructor)
- Test: `cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java` (add the duplicate-views parity test)

**Interfaces:**
- Consumes: the `BuildRequest` compact constructor (already normalizes `views`/`emit`/`env`).
- Produces: `views` is de-duplicated and blank-stripped, first-seen order preserved, for every caller (CLI 9-arg and MCP 10-arg constructors both delegate here).

- [ ] **Step 1: Write the failing parity test**

Add to `CliMcpParityTest.java`:

```java
  @Test
  void buildDeduplicatesRepeatedViewsThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("rich-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build", "--input", source.toString(), "--out", cliOut.toString(),
              "--render-policy", renderPolicy.toString(), "--views", "main,main"
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "render_policy", "policy.json",
                        "views", java.util.List.of("main", "main"))));

    assertThat(cli.exitCode()).isZero();
    assertThat(mcp.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
  }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./mvnw -q -pl cli -am -Dtest=CliMcpParityTest#buildDeduplicatesRepeatedViewsThroughBothLanes -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — the CLI passes `["main","main"]` through unchanged and produces two `main` view outcomes, while the MCP handler already de-dups to one, so the envelopes differ.

- [ ] **Step 3: Normalize `views` in the compact constructor**

In `BuildRequest.java`, change the compact constructor's `views` line and add the helper:

```java
  public BuildRequest {
    views = views == null ? List.of() : normalizeViews(views);
    emit = emit == null ? Set.of() : Set.copyOf(emit);
    env = env == null ? Map.of() : Map.copyOf(env);
  }

  private static List<String> normalizeViews(List<String> views) {
    java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
    for (String view : views) {
      if (view != null && !view.isBlank()) {
        ordered.add(view);
      }
    }
    return List.copyOf(ordered);
  }
```

(`List`, `Set`, `Map` are already imported. Add `import java.util.LinkedHashSet;` if you prefer the unqualified form.)

- [ ] **Step 4: Run the parity test and confirm it passes**

Run: `./mvnw -q -pl cli -am -Dtest=CliMcpParityTest#buildDeduplicatesRepeatedViewsThroughBothLanes -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS — both lanes produce one `main` outcome.

- [ ] **Step 5: Run core + cli suites**

Run: `./mvnw -q -pl core,cli -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add core/src/main/java/dev/dediren/core/commands/BuildRequest.java \
        cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java
git commit -m "fix(core): dedup and drop-blank build views so both lanes agree on selection"
```

---

### Task 3: Widen the parity guarantee — profile-validate, export lanes, and the env-harness fix

These are characterization tests: both lanes already call the same `core` entry points, so they are expected to **pass on first run**, closing the blind spots the audit found. If any *fails*, that is a newly-discovered real gap — stop and investigate before proceeding. This task also fixes the harness bug where the two lanes were handed different environments.

**Files:**
- Test: `cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java`

**Interfaces:**
- Consumes: `Main.executeForTesting(String[], String, Map<String,String>)` (the 3-arg overload that pins the env), `DedirenTools`, `EngineWiring.defaults()`.
- Produces: parity coverage for the profile-validate lane, the OEF and XMI export lanes (the only lanes that forward `env` to an engine), and a happy-path emit case.

- [ ] **Step 1: Add an export-policy fixture helper**

Add next to the existing `policy(...)` helper in `CliMcpParityTest.java`:

```java
  private static Path exportPolicy(String name) {
    return Path.of("..", "fixtures", "export-policy", name).toAbsolutePath().normalize();
  }
```

- [ ] **Step 2: Add the profile-validate parity test**

```java
  @Test
  void validateWithProfileProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root)
      throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-archimate.json"), source);

    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "validate", "--plugin", "generic-graph", "--profile", "archimate",
              "--input", source.toString()
            },
            "",
            Map.of());

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .validate(
                new CallToolRequest(
                    "dediren_validate", Map.of("source", "model.json", "profile", "archimate")));

    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
  }
```

- [ ] **Step 3: Add the OEF and XMI export-lane parity tests**

```java
  @Test
  void buildOefLaneProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-archimate.json"), source);
    Path oefPolicy = root.resolve("oef.json");
    Files.copy(exportPolicy("default-oef.json"), oefPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build", "--input", source.toString(), "--out", cliOut.toString(),
              "--oef-policy", oefPolicy.toString()
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "oef_policy", "oef.json")));

    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
  }

  @Test
  void buildXmiLaneProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-uml-basic.json"), source);
    Path xmiPolicy = root.resolve("xmi.json");
    Files.copy(exportPolicy("default-uml-xmi.json"), xmiPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build", "--input", source.toString(), "--out", cliOut.toString(),
              "--xmi-policy", xmiPolicy.toString()
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "xmi_policy", "xmi.json")));

    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
  }
```

Note: these assert *parity*, not success. If `xmllint` is absent both lanes emit the identical `DEDIREN_*_SCHEMA_VALIDATOR_UNAVAILABLE` envelope; if present both emit the identical success envelope. Either way the assertion holds — that is the point (env forwarded to the validator boundary is exercised identically). If the chosen source/policy pair does not project any view, pick another archimate/uml source fixture that does; the assertion (parity) is unchanged.

- [ ] **Step 4: Add the happy-path emit parity test**

```java
  @Test
  void buildWithAllEmitKindsProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root)
      throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("rich-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build", "--input", source.toString(), "--out", cliOut.toString(),
              "--render-policy", renderPolicy.toString(),
              "--emit", "layout-request,layout-result,render-metadata"
            },
            "",
            Map.of());

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "render_policy", "policy.json",
                        "emit",
                            java.util.List.of(
                                "layout-request", "layout-result", "render-metadata"))));

    assertThat(cli.exitCode()).isZero();
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
    assertThat(mcpOut.resolve("main/layout-request.json")).exists();
    assertThat(mcpOut.resolve("main/layout-result.json")).exists();
    assertThat(mcpOut.resolve("main/render-metadata.json")).exists();
  }
```

- [ ] **Step 5: Fix the env-harness mismatch in the existing tests**

The pre-existing tests call `Main.executeForTesting(args, "")` (2-arg → `System.getenv()`) for the CLI lane but `Map.of()` for the MCP lane. Change the four existing tests (`validateProduces...`, `validateProducesTheSameError...`, `buildProduces...`, `buildProducesTheSameError...`) to use the 3-arg overload with `Map.of()`, matching the MCP lane:

Replace each `Main.executeForTesting(new String[] {...}, "")` with `Main.executeForTesting(new String[] {...}, "", Map.of())`. No behavior change is expected (the render/structural lanes do not read env), but both lanes are now handed the same environment.

- [ ] **Step 6: Run the full parity class under the sandbox**

Run: `./mvnw -q -pl core,cli -am -Dtest=CliMcpParityTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS for all cases. If a characterization test fails, a real divergence was found — investigate before continuing (do not weaken the assertion to make it pass).

- [ ] **Step 7: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java
git commit -m "test(cli): pin MCP/CLI parity for profile-validate, export lanes, emit, and views; align env"
```

---

### Task 4: Documentation touch-ups

**Files:**
- Modify: `docs/superpowers/specs/2026-07-14-dediren-mcp-server-design.md` (stale guide-topic list)
- Check (modify only if they describe the old behavior): `README.md`, `docs/agent-usage.md`

- [ ] **Step 1: Refresh the stale guide-topic list in the MCP server design spec**

In `2026-07-14-dediren-mcp-server-design.md`, the `dediren_guide` section lists 13 topics and names `runtime`. The implementation (`GuideCatalog.TOPICS`) ships 21 topics and uses `runtime-probes`, not `runtime`. Update the spec's topic list to match the shipped set, or replace the enumerated list with a pointer to `GuideCatalog.TOPICS` and a note that `GuideCatalogTest` enforces the topic↔heading mapping bidirectionally. (Get the current list from `mcp-server/src/main/java/dev/dediren/mcp/GuideCatalog.java`.)

- [ ] **Step 2: Check README and agent-usage for stale emit/views wording**

Run: `grep -n -iE 'emit|--views' README.md docs/agent-usage.md`
If either describes the old behavior (e.g. "unknown emit kinds are ignored", or implies duplicate views build twice), update it to the new behavior (unknown emit kinds are rejected with `DEDIREN_COMMAND_INPUT_INVALID`; duplicate/blank views are de-duplicated). If neither mentions it, no change — note that in the commit body. No new `DEDIREN_*` token or CalVer string is introduced, so `AgentUsageDocConsistencyTest` needs nothing.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-07-14-dediren-mcp-server-design.md
# add README.md / docs/agent-usage.md only if changed
git commit -m "docs(mcp): refresh guide-topic list; note emit/views parity behavior"
```

---

## Final Verification

- [ ] **Full parity + module suites under the sandbox**

```bash
./mvnw -q -pl core,cli,mcp-server -am test
```
Expected: BUILD SUCCESS.

- [ ] **Quality gate** (format + SpotBugs + tests). The Jazzer fuzz tests and any uncached-dependency modules need the sandbox disabled; run the quality gate sandbox-off (it also warms the dependency cache):

```bash
./mvnw -Pquality verify
```
Expected: BUILD SUCCESS. If it fails only on `*FuzzTest` under the sandbox, re-run sandbox-off — those are the known Jazzer limitation, not a regression.

- [ ] **Audit gates** (per CLAUDE.md, this touches the build driver + MCP boundary): run `souroldgeezer-audit:test-quality-audit` (deep — new parity tests, the anti-drift guarantee) and `souroldgeezer-audit:devsecops-audit` (quick — confirm the emit relocation adds no new process/dependency surface; the MCP boundary is unchanged).

- [ ] **Integration + release:** land the branch into `main` (local `--no-ff` merge or PR, author's discretion). If released, a CalVer `2026.07.18` bump in its own follow-on commit with an annotated `v2026.07.18` tag, per `## Versioning` in CLAUDE.md. No schema-id change.
