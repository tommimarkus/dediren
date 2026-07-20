# MCP Server Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the six actionable findings from the 2026-07-20 `mcp-server` design review, so that malformed tool arguments fail loudly instead of becoming different valid requests, the path guard establishes containment itself, and the advertised emit vocabulary cannot drift from the one core enforces.

**Architecture:** All changes are local to `mcp-server`, plus one visibility change in `core` (`BuildCommand.EMIT_KINDS`) so the MCP tool schema can be pinned to the vocabulary core owns. No contract, schema, or envelope shape changes: every new failure reuses an existing `DiagnosticCode`. No new module edges — `mcp-server` already depends on `core`.

**Tech Stack:** Java 21, Maven Wrapper, JUnit 5 + AssertJ, Jackson 3 (`tools.jackson`), MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`).

## Global Constraints

- **No version bump.** Per `CLAUDE.md` §Versioning, the bump is a separate follow-on commit made only when the change is actually being released. Leave root `pom.xml` alone.
- **Format before every commit:** `./mvnw -Pquality spotless:apply` (google-java-format, GOOGLE style).
- **No new `DEDIREN_*` diagnostic codes.** Every new error path reuses `DiagnosticCode.COMMAND_INPUT_INVALID` (`DEDIREN_COMMAND_INPUT_INVALID`). This is what keeps `AgentUsageDocConsistencyTest` green with no doc edits.
- **No docs change is required by this plan** — checked, not assumed. Two directions were verified. (a) `CLAUDE.md` §Files That Move Together's "MCP surface changes" row triggers on tools, `ToolSchemas` *content*, and `GuideCatalog` *topics*; this plan adds no tool, no schema property, and no guide topic, so `AgentUsageDocConsistencyTest` is unaffected. (b) Tasks 1 and 3 change *observable* tool behavior, so the prose was checked too: the `## MCP Server` section of `docs/agent-usage.md` and the MCP mentions in `README.md:145` describe the three tools and their arguments but say nothing about `dediren_guide`'s success/error semantics and nothing about how malformed `views`/`emit` elements are handled. No sentence in either document becomes false. Do not edit `docs/agent-usage.md`, `README.md`, or `docs/threat-model.md`.
- **stderr only for human debugging.** `Logger.info`/`warn`/`error` are banned in first-party code and `ArchitectureRulesTest` fails the build on them. Anything an agent must act on goes in the envelope's `diagnostics[]`.
- **Never leak resolved absolute paths to the model.** Model-facing messages carry only the model's own candidate string; resolved targets and raw `IOException` text go to stderr. Several tests assert this explicitly — keep them passing.
- **Per-task verification:** `./mvnw -pl mcp-server,cli -am test`. The `-am` is required (sibling modules are not installed).
- **Sandbox notes (this environment):** the two Jazzer tests fail only under the sandbox — exclude them with `-Dtest='!*FuzzTest'` or rerun sandbox-disabled. `-Pdist-smoke` needs network/cold-cache access; run it sandbox-disabled.
- **Staging:** explicit paths only. Never `git add -A` — the worktree has unrelated untracked user dotfiles.

## Findings Map

Every finding was independently re-validated by an adversarial verifier instructed to refute it. Verdicts and the corrections they forced:

| # | Finding | Verdict | Task |
|---|---|---|---|
| 1 | `stringListArg` silently drops malformed elements | **CONFIRMED, trigger narrowed** — the SDK validates inputs by default, so type mismatches never reach the handler; blank/whitespace strings do. Demonstrated on the shipped bundle. | Task 1 |
| 4 | Policy read failure does not name which policy | **CONFIRMED, scope narrowed** — the branch is reachable but fires only on exotic triggers; the common typo case routes elsewhere and already echoes the candidate. | Task 2 |
| 5 | `guide` never sets `isError` | **CONFIRMED** — MCP spec classes invalid input data as a tool-execution error (`isError: true`). Nothing asserts the current behavior. | Task 3 |
| 2 | Emit vocabulary published in two places, guarded in neither | **CONFIRMED, severity raised** — the SDK enforces the advertised enum, so each literal is the sole gate for one lane. Behavior split, not duplication. | Task 4 |
| 3 | `resolveForWrite` returns a path it has not established as contained | **REFUTED as a vulnerability** — no argument-only escape exists. Retained as zero-cost hardening; the claimed "tradeoff" was wrong, and a sibling site was missed. | Task 5 |
| 7 | Shutdown hook accumulates per `serveOn` call | **REFUTED as a defect** — accumulation is test-only; the production double-close is real but `close()` is verifiably idempotent. | ~~Task 6~~ **DROPPED** |

**Scope in force (decided 2026-07-20): Tasks 1, 2, 3, 4, 5, then 7. Task 6 is dropped — skip it.** Tasks 1–4 fix real, reachable behavior. Task 5 is zero-cost hardening across two modules.

**Deliberately excluded** (do not implement):

- **Finding 6 (reword `PathOutsideRootException`)** — dropped as cosmetic. Agents branch on `code`, and the code stays `DEDIREN_MCP_PATH_OUTSIDE_ROOT` for the typo-inside-root case regardless of wording, so rewording fixes no machine-actionable signal. Distinguishing not-found-inside from outside would reintroduce the filesystem-fingerprinting oracle the class doc deliberately closed. Rewording also churns ~8 `hasMessageContaining("outside the workspace root")` assertions for no behavioral gain.
- **Finding 8 (`MAX_WAIT = 60s`)** — needs runtime evidence (how long a large `dediren_build` actually takes under a pipelining client) before changing or making configurable. Changing an unmeasured timeout to a different unmeasured timeout is not an improvement. Leave as recorded debt.

## File Structure

**Modified:**
- `mcp-server/src/main/java/dev/dediren/mcp/DedirenTools.java` — Tasks 1, 2, 3
- `mcp-server/src/main/java/dev/dediren/mcp/GuideCatalog.java` — Task 3
- `mcp-server/src/main/java/dev/dediren/mcp/WorkspacePaths.java` — Task 5
- `mcp-server/src/main/java/dev/dediren/mcp/DedirenMcpServer.java` — Task 6
- `core/src/main/java/dev/dediren/core/commands/BuildCommand.java` — Task 4
- `mcp-server/src/test/java/dev/dediren/mcp/DedirenToolsTest.java` — Tasks 1, 2, 3
- `mcp-server/src/test/java/dev/dediren/mcp/GuideCatalogTest.java` — Task 3
- `mcp-server/src/test/java/dev/dediren/mcp/WorkspacePathsTest.java` — Task 5
- `cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java` — Task 1

**Created:**
- `mcp-server/src/test/java/dev/dediren/mcp/ToolSchemasTest.java` — Task 4

Tasks 1–3 all touch `DedirenTools.java` and are ordered adjacently on purpose. Do them in order.

---

### Task 1: Reject malformed list arguments instead of silently emptying them

**Files:**
- Modify: `mcp-server/src/main/java/dev/dediren/mcp/DedirenTools.java:106-170` (`build`), `:186-198` (`stringListArg`)
- Test: `mcp-server/src/test/java/dev/dediren/mcp/DedirenToolsTest.java`
- Test: `cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `DedirenTools.InvalidListArgumentException` (private nested, `argument()` accessor). No public surface change.

**Why:** a malformed element collapses the list to empty, and `BuildCommand.selectViews` (`core/src/main/java/dev/dediren/core/commands/BuildCommand.java:219-222`) treats an empty list as *build every view*. The malformed argument therefore produces **more** artifacts than asked for, under `status: "ok"` / `isError: false`.

**Read this before writing the tests — the live trigger is narrower than it looks.** The MCP Java SDK 2.0.0 validates tool arguments against the advertised `inputSchema` *before* the handler runs, and it does so **by default**: `McpServer$SyncSpecification` initialises `validateToolInputs` to `true`, and the only knob is an opt-*out* that `DedirenMcpServer.java:48-88` never calls. So over a real connection:

- **Type mismatches are already caught by the SDK.** `views: [123]` is rejected with `"input validation failed: [/views/0: integer found, string expected]"` and never reaches `stringListArg`. Verified against the shipped bundle.
- **Blank and whitespace strings are NOT caught.** `{"type": "string"}` carries no `minLength`, so `""` and `"   "` pass SDK validation, reach `stringListArg`, and are dropped by `!text.isBlank()`. Verified end-to-end on the shipped bundle: a 3-view model with `views: [""]` produced **all three views** under `"status":"ok"` / `"isError":false`, where `views: ["class-view"]` produces one.

That makes the blank-string case the live defect and the type cases defence-in-depth. Both are worth guarding — the handler must not depend on an SDK default that a future version, or an explicit opt-out, could flip — but the *tests must lead with the blank case*, because it is the one that reaches production code today.

Deliberately **not** adding `"minLength": 1` to `ToolSchemas`: the handler guard is correct regardless of SDK behavior, and editing `ToolSchemas` content pulls in the `CLAUDE.md` "MCP surface changes" doc-sync row for no additional safety.

**Critical distinction — do not regress "build all":** three inputs reach `stringListArg` today and only one may now fail. `views` absent → `List.of()` → build all (**keep**). `views: []` → `List.of()` → build all (**keep**). `views: [123]` → currently `List.of()` → build all (**must now error**). The discriminator is "a list was supplied and an element failed to convert", never "the result is empty".

- [ ] **Step 1: Write the failing tests**

Add to `mcp-server/src/test/java/dev/dediren/mcp/DedirenToolsTest.java`:

```java
  // THE LIVE CASE. A blank string clears the SDK's input-schema validation ({"type":"string"} has
  // no minLength), reaches this handler, and used to be dropped -- collapsing views to empty, which
  // BuildCommand.selectViews reads as "build every view". Verified on the shipped bundle: a 3-view
  // model with views:[""] built all three under status:"ok".
  @Test
  void buildRejectsABlankViewsElement(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "views", List.of(""))));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'views'[0]");
    // The whole point: a malformed list must not silently become "build every view".
    assertThat(Files.exists(root.resolve("out"))).isFalse();
  }

  // Defence in depth. The SDK's input validation (on by default, opt-out only) rejects this over a
  // real connection before the handler sees it -- but the handler must not depend on that default.
  @Test
  void buildRejectsANonStringViewsElement(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "views", List.of(1))));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'views'[0]");
    assertThat(diagnostic.path("path").asText()).isEqualTo("views");
    // The whole point: a malformed list must not silently become "build every view".
    assertThat(Files.exists(root.resolve("out"))).isFalse();
  }

  @Test
  void buildRejectsABlankEmitElement(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "emit", List.of("layout-request", "  "))));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'emit'[1]");
  }

  @Test
  void buildRejectsAViewsArgumentThatIsNotAnArray(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "views", "main")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'views' must be an array of strings");
  }
```

Add to `cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java` — this is the regression guard for "empty still means all":

```java
  /**
   * An explicitly empty {@code views} list must keep meaning "build every view", exactly like
   * omitting the argument. Only a list whose <em>elements</em> are malformed is an error — see
   * DedirenToolsTest.buildRejectsANonStringViewsElement. Pinned here because the CLI lane is the
   * oracle for what "build every view" produces.
   */
  @Test
  void buildTreatsAnEmptyViewsListAsEveryViewThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("rich-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              cliOut.toString(),
              "--render-policy",
              renderPolicy.toString()
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
                        "source",
                        "model.json",
                        "out",
                        mcpOut.toString(),
                        "render_policy",
                        "policy.json",
                        "views",
                        java.util.List.of())));

    assertThat(cli.exitCode()).isZero();
    assertThat(mcp.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl mcp-server,cli -am test -Dtest='DedirenToolsTest,CliMcpParityTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL. The three `DedirenToolsTest` cases fail because the malformed elements are silently dropped, so the build proceeds and produces a different envelope (no `DEDIREN_COMMAND_INPUT_INVALID` diagnostic at index 0). `buildTreatsAnEmptyViewsListAsEveryViewThroughBothLanes` should already PASS — it pins existing behavior that must survive Step 3.

- [ ] **Step 3: Implement**

In `DedirenTools.java`, replace `stringListArg` (currently `:186-198`) with:

```java
  /**
   * The de-duplicated string elements of a list argument, in first-seen order. An absent argument
   * is an empty list; a <em>present but malformed</em> one is an error rather than a silent empty
   * list, because empty already means something specific ("build every view", "emit nothing").
   * Quietly turning a malformed request into that different, valid request is the failure this
   * guards: {@code views: [123]} used to build every view under a success envelope.
   *
   * <p>The offending element is reported by index, not by value: the index is enough to repair, and
   * an element can be an arbitrarily large nested structure.
   */
  private static List<String> stringListArg(CallToolRequest request, String name)
      throws InvalidListArgumentException {
    Object value = request.arguments().get(name);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> raw)) {
      throw new InvalidListArgumentException(name, "'" + name + "' must be an array of strings");
    }
    Set<String> items = new LinkedHashSet<>();
    for (int index = 0; index < raw.size(); index++) {
      Object item = raw.get(index);
      if (!(item instanceof String text) || text.isBlank()) {
        throw new InvalidListArgumentException(
            name, "'" + name + "'[" + index + "] must be a non-blank string");
      }
      items.add(text);
    }
    return List.copyOf(items);
  }

  /** A list tool argument whose elements are not all non-blank strings. */
  private static final class InvalidListArgumentException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String argument;

    InvalidListArgumentException(String argument, String message) {
      super(message);
      this.argument = argument;
    }

    String argument() {
      return argument;
    }
  }
```

In `build`, replace the `views` block (currently `:115-120`) with a block that parses **both** list arguments up front, before any path resolution — so a malformed request is rejected before anything is created:

```java
    List<String> views;
    List<String> emit;
    try {
      views = stringListArg(request, "views");
      emit = stringListArg(request, "emit");
    } catch (InvalidListArgumentException invalid) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, invalid.getMessage(), invalid.argument());
    }
    for (String view : views) {
      if (!VIEW_ID_PATTERN.matcher(view).matches()) {
        return error(DiagnosticCode.COMMAND_INPUT_INVALID, "invalid view id: " + view, view);
      }
    }
```

Then in the `BuildRequest` constructor call (currently `:157`), replace `Set.copyOf(stringListArg(request, "emit"))` with the already-parsed value:

```java
            Set.copyOf(emit),
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl mcp-server,cli -am test -Dtest='DedirenToolsTest,CliMcpParityTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, all cases including the pre-existing `buildRejectsUnknownEmitKindThroughBothLanes` and `buildDeduplicatesRepeatedViewsThroughBothLanes` (dedup still happens via `LinkedHashSet`).

- [ ] **Step 5: Run the module suites**

Run: `./mvnw -pl mcp-server,cli -am test`

Expected: BUILD SUCCESS.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add mcp-server/src/main/java/dev/dediren/mcp/DedirenTools.java \
        mcp-server/src/test/java/dev/dediren/mcp/DedirenToolsTest.java \
        cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java
git commit -m "fix(mcp): reject malformed list arguments instead of silently emptying them

A blank or non-string element in 'views' or 'emit' was dropped, so
views: [\"\"] collapsed to an empty list — which BuildCommand.selectViews
reads as 'build every view'. A malformed request became a different valid
request that wrote more artifacts than were asked for, under status:'ok'.

The blank case is the live one: the MCP SDK validates arguments against
the advertised inputSchema by default, so type mismatches never reach the
handler, but {\"type\":\"string\"} has no minLength and so admits \"\" and
\"   \". The type guards stay as defence in depth — the handler must not
depend on an SDK default that an opt-out could flip.

An absent or explicitly empty list still means 'build every view'; only a
supplied element that fails to convert is now an error."
```

---

### Task 2: Name which policy argument failed to read

**Files:**
- Modify: `mcp-server/src/main/java/dev/dediren/mcp/DedirenTools.java:127-138` (`build`'s catch block), `:172-179` (`readOptionalPolicy`)
- Test: `mcp-server/src/test/java/dev/dediren/mcp/DedirenToolsTest.java`

**Interfaces:**
- Consumes: `readFailure(String label, String candidate, IOException error)` — already exists at `DedirenTools.java:219`, produces `"failed to read <label> '<candidate>'"` with `path = candidate`, and logs the raw `IOException` to stderr.
- Produces: `DedirenTools.PolicyReadException` (private nested, accessors `argument()`, `candidate()`, `ioCause()`).

**Why:** `readOptionalPolicy` is called three times and throws `IOException` into one shared catch that reports `"failed to read policy"` with `path: null`. The agent is told to repair from the envelope alone but cannot tell which of `render_policy` / `oef_policy` / `xmi_policy` to fix. This needs the label carried out of the helper — a message swap alone cannot do it. The CLI lane already does this correctly (`cli/src/main/java/dev/dediren/cli/Main.java:448,452,456` pass a per-policy label into `readFile`), so this is MCP-specific drift, not a shared gap.

**Scope honesty — this branch is narrower than it looks.** The *common* failure, a typo'd or missing policy path, never reaches this catch: `WorkspacePaths.resolveExisting`'s `toRealPath()` throws first and is converted to `PathOutsideRootException`, handled by `pathEscape` (`DedirenTools.java:204`), which already echoes the candidate. This catch fires only when a path resolves but cannot be read — a directory in place of a file, or permission-denied. The fix is correct and cheap, but do not describe it as improving the everyday repair loop.

**Do not widen the leak while fixing it.** `error.getMessage()` on a failed read routinely carries the resolved absolute path (`NoSuchFileException`'s message *is* the path). It must stay on stderr. Only the label and the model's own candidate string may enter the envelope — which is exactly what the existing `readFailure` helper already does, hence reusing it rather than writing a new message.

- [ ] **Step 1: Write the failing test**

Add to `mcp-server/src/test/java/dev/dediren/mcp/DedirenToolsTest.java`:

```java
  @Test
  void buildNamesWhichPolicyArgumentFailedToRead(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));
    // A directory resolves like a file (so it clears WorkspacePaths) but cannot be read as one.
    Files.createDirectory(root.resolve("oef.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "oef_policy", "oef.json")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    // Which of the three policy arguments failed must be in the envelope, not just on stderr.
    assertThat(diagnostic.path("message").asText()).contains("oef_policy");
    assertThat(diagnostic.path("path").asText()).isEqualTo("oef.json");
    // The resolved absolute path is stderr-only and must never reach the model.
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl mcp-server -am test -Dtest=DedirenToolsTest#buildNamesWhichPolicyArgumentFailedToRead -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL — actual message is `"failed to read policy"` (no `oef_policy`) and `path` is null, so the `contains("oef_policy")` assertion fails.

- [ ] **Step 3: Implement**

In `DedirenTools.java`, replace `readOptionalPolicy` (currently `:172-179`) with:

```java
  private String readOptionalPolicy(CallToolRequest request, String argument)
      throws PathOutsideRootException, PolicyReadException {
    String value = stringArg(request, argument);
    if (value == null) {
      return null;
    }
    Path resolved = WorkspacePaths.resolveExisting(root, value);
    try {
      return Files.readString(resolved);
    } catch (IOException error) {
      // Carry the argument name out: three policy arguments share one catch in build(), and an
      // agent repairing from the envelope has to know which one to fix.
      throw new PolicyReadException(argument, value, error);
    }
  }

  /** A policy argument that resolved inside the root but could not be read. */
  private static final class PolicyReadException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String argument;
    private final String candidate;
    private final IOException cause;

    PolicyReadException(String argument, String candidate, IOException cause) {
      super(cause);
      this.argument = argument;
      this.candidate = candidate;
      this.cause = cause;
    }

    String argument() {
      return argument;
    }

    String candidate() {
      return candidate;
    }

    IOException ioCause() {
      return cause;
    }
  }
```

In `build`, replace the `catch (IOException error)` clause (currently `:135-138`) with:

```java
    } catch (PolicyReadException failure) {
      return readFailure(failure.argument(), failure.candidate(), failure.ioCause());
    }
```

Leave the `catch (PathOutsideRootException escape)` clause above it unchanged. No other statement in that `try` block throws `IOException` — `WorkspacePaths.resolveExisting` and `resolveForWrite` throw only `PathOutsideRootException` — so removing the `IOException` catch cannot orphan a checked exception. The separate `Files.readString(sourcePath)` below keeps its own `catch (IOException error)`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -pl mcp-server -am test -Dtest=DedirenToolsTest#buildNamesWhichPolicyArgumentFailedToRead -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. The message is now `failed to read oef_policy 'oef.json'`.

- [ ] **Step 5: Run the module suites**

Run: `./mvnw -pl mcp-server,cli -am test`

Expected: BUILD SUCCESS.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add mcp-server/src/main/java/dev/dediren/mcp/DedirenTools.java \
        mcp-server/src/test/java/dev/dediren/mcp/DedirenToolsTest.java
git commit -m "fix(mcp): name which policy argument failed to read

Three policy arguments shared one catch that reported 'failed to read
policy' with a null path, so an agent repairing from the envelope could
not tell which of render_policy/oef_policy/xmi_policy to fix. Carry the
argument name out of readOptionalPolicy and reuse the existing
readFailure helper, which already names the label and echoes only the
model's own candidate string."
```

---

### Task 3: Flag an unknown guide topic as a failed call

**Files:**
- Modify: `mcp-server/src/main/java/dev/dediren/mcp/GuideCatalog.java:87-98`
- Modify: `mcp-server/src/main/java/dev/dediren/mcp/DedirenTools.java:60-64`
- Test: `mcp-server/src/test/java/dev/dediren/mcp/GuideCatalogTest.java`
- Test: `mcp-server/src/test/java/dev/dediren/mcp/DedirenToolsTest.java`

**Interfaces:**
- Produces: `GuideCatalog.hasSection(String topic)` → `boolean`, public static. True only when the topic maps to a heading **and** that heading has a body.

**Why:** `guide` hardcodes `isError(false)`, so an unknown topic is a *successful* call that happens to contain an error message. Every other invalid argument in `DedirenTools` sets `isError(true)`, and MCP clients branch on that flag for surfacing and retry. This is a deliberate semantics choice — "a help response is a failed call" is arguable — but consistency with the rest of the tool surface wins, and the response body still lists the valid topics either way.

**Do not change `GuideCatalog.section`'s return contract.** Three existing tests pin the `"unknown topic '<x>'. Valid topics: ..."` string (`GuideCatalogTest:32`, `:43`, `MigrationRegistryTest:70`). Add a query method alongside it instead.

- [ ] **Step 1: Write the failing tests**

Add to `mcp-server/src/test/java/dev/dediren/mcp/GuideCatalogTest.java`:

```java
  @Test
  void hasSectionAgreesWithWhatSectionActuallyReturns() {
    for (String topic : GuideCatalog.topics()) {
      assertThat(GuideCatalog.hasSection(topic))
          .as("topic '%s' resolves to a real section, so hasSection must say so", topic)
          .isTrue();
    }
    assertThat(GuideCatalog.hasSection("no-such-topic")).isFalse();
  }
```

Add to `mcp-server/src/test/java/dev/dediren/mcp/DedirenToolsTest.java`:

```java
  @Test
  void guideFlagsAnUnknownTopicAsAnError() {
    CallToolResult result =
        toolsIn(Path.of("."))
            .guide(new CallToolRequest("dediren_guide", Map.of("topic", "no-such-topic")));

    assertThat(result.isError()).isTrue();
    // Still helpful: the body names the valid topics so the model can retry without a second call.
    assertThat(textOf(result)).contains("unknown topic 'no-such-topic'");
    assertThat(textOf(result)).contains("render-policy");
  }

  @Test
  void guideFlagsAKnownTopicAsASuccess() {
    CallToolResult result =
        toolsIn(Path.of("."))
            .guide(new CallToolRequest("dediren_guide", Map.of("topic", "repair")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(textOf(result)).contains("Repair Rules");
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl mcp-server -am test -Dtest='GuideCatalogTest,DedirenToolsTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL — `hasSectionAgreesWithWhatSectionActuallyReturns` does not compile (`hasSection` does not exist yet). Fix that first, then `guideFlagsAnUnknownTopicAsAnError` fails on `isError()` being `false`.

- [ ] **Step 3: Implement**

In `GuideCatalog.java`, replace `section` (currently `:87-98`) with:

```java
  /**
   * Whether {@code topic} resolves to a real section, as opposed to the unknown-topic message. The
   * one definition of "known", so {@code section} and the MCP tool's {@code isError} flag cannot
   * disagree about it.
   *
   * <p>Null-tolerant on purpose: both backing maps are {@code Map.copyOf} results, which throw on a
   * null key rather than returning null. The only caller today null-checks first, but this is
   * public and the next one might not.
   */
  public static boolean hasSection(String topic) {
    if (topic == null) {
      return false;
    }
    String heading = TOPICS.get(topic);
    return heading != null && SECTIONS.containsKey(heading);
  }

  /** The markdown for one topic, or an "unknown topic" message naming the valid topics. */
  public static String section(String topic) {
    if (!hasSection(topic)) {
      return "unknown topic '" + topic + "'. Valid topics: " + String.join(", ", topics());
    }
    return SECTIONS.get(TOPICS.get(topic));
  }
```

This preserves the returned strings exactly — both previous unknown branches produced the identical message — and removes the duplicated literal.

In `DedirenTools.java`, replace `guide` (currently `:60-64`) with:

```java
  public CallToolResult guide(CallToolRequest request) {
    String topic = stringArg(request, "topic");
    if (topic == null) {
      return CallToolResult.builder().addTextContent(GuideCatalog.index()).isError(false).build();
    }
    // An unknown topic is a failed call, not a successful one that happens to describe a failure:
    // MCP clients branch on isError, and every other bad argument in this class sets it. The body
    // still lists the valid topics, so the model can retry without a second round trip.
    return CallToolResult.builder()
        .addTextContent(GuideCatalog.section(topic))
        .isError(!GuideCatalog.hasSection(topic))
        .build();
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl mcp-server -am test -Dtest='GuideCatalogTest,DedirenToolsTest,MigrationRegistryTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, including the pre-existing `guideWithoutTopicReturnsTheIndex`, `guideWithTopicReturnsThatSection`, `unknownTopicListsTheValidTopics`, and `MigrationRegistryTest`.

- [ ] **Step 5: Run the module suites**

Run: `./mvnw -pl mcp-server,cli -am test`

Expected: BUILD SUCCESS.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add mcp-server/src/main/java/dev/dediren/mcp/GuideCatalog.java \
        mcp-server/src/main/java/dev/dediren/mcp/DedirenTools.java \
        mcp-server/src/test/java/dev/dediren/mcp/GuideCatalogTest.java \
        mcp-server/src/test/java/dev/dediren/mcp/DedirenToolsTest.java
git commit -m "fix(mcp): flag an unknown guide topic as a failed call

dediren_guide hardcoded isError(false), so an unknown topic returned a
successful call whose body happened to be an error message. Every other
invalid argument in DedirenTools sets isError, and MCP clients branch on
it. Adds GuideCatalog.hasSection as the single definition of 'known' so
section() and the isError flag cannot disagree; the response body still
lists the valid topics."
```

---

### Task 4: Pin the advertised emit enum to the vocabulary core enforces

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/commands/BuildCommand.java:75-77`, `:95-99`
- Create: `mcp-server/src/test/java/dev/dediren/mcp/ToolSchemasTest.java`

**Interfaces:**
- Produces: `BuildCommand.EMIT_KINDS` → `java.util.Set<String>`, public static final, containing `"layout-request"`, `"layout-result"`, `"render-metadata"`.

**Why:** `BuildCommand:95` claims to be "the one place that owns the emit vocabulary", but its constants are private and `ToolSchemas.BUILD:48` re-declares the same three values as a JSON enum literal — and *that* is the surface agents discover the vocabulary from. Add a fourth emit kind to core and MCP agents silently cannot request it. `GuideCatalog` has a two-way pinning test against the guide; `ToolSchemas` has none, and that asymmetry is the whole finding.

`ToolSchemas` is package-private, so the test must live in package `dev.dediren.mcp` — which is where `mcp-server`'s tests already are.

**This is a behavior split, not cosmetic duplication.** The MCP SDK validates tool arguments against the advertised `inputSchema` before dispatch (on by default — see Task 1), so over a real connection `emit: ["bogus"]` is rejected by **`ToolSchemas`' enum**, and the handler is never reached. `BuildCommand.EMIT_KINDS` is what gates the **CLI**. The two literals are therefore each the sole gate for one lane: a kind added only to core is unreachable over MCP, and a kind added only to `ToolSchemas` reaches core and is rejected there with a different message. Pinning them together is what keeps the two lanes one product.

**Known consequence, do not try to fix it here:** `CliMcpParityTest#buildRejectsUnknownEmitKindThroughBothLanes` constructs `DedirenTools` directly, so it exercises a path a real MCP client never takes for that input — the SDK would reject first, with a different shape. The assertion is not wrong, but it proves handler parity, not wire parity. Step 3 adds a comment saying so; changing the test to drive a real transport is out of scope for this plan.

- [ ] **Step 1: Write the failing test**

Create `mcp-server/src/test/java/dev/dediren/mcp/ToolSchemasTest.java`:

```java
package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.commands.BuildCommand;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The build tool's advertised input schema is how an agent discovers what it may ask for;
 * {@code BuildCommand} is what actually enforces it. Nothing but this test stops the two from
 * drifting, and drift is silent in the direction that matters: a kind added to core but not
 * advertised here is simply unreachable over MCP.
 */
class ToolSchemasTest {

  @Test
  void advertisedEmitEnumMatchesTheVocabularyBuildCommandAccepts() {
    JsonNode advertisedEnum =
        JsonSupport.objectMapper()
            .readTree(ToolSchemas.BUILD)
            .path("properties")
            .path("emit")
            .path("items")
            .path("enum");

    assertThat(advertisedEnum.isArray())
        .as("ToolSchemas.BUILD must advertise an emit enum")
        .isTrue();
    List<String> advertised = new ArrayList<>();
    advertisedEnum.forEach(node -> advertised.add(node.asText()));

    assertThat(advertised)
        .as(
            "ToolSchemas.BUILD's emit enum is the only way an agent learns the emit vocabulary —"
                + " a kind added to BuildCommand.EMIT_KINDS must be advertised here too")
        .containsExactlyInAnyOrderElementsOf(BuildCommand.EMIT_KINDS);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl mcp-server -am test -Dtest=ToolSchemasTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL — compilation error, `EMIT_KINDS` does not exist on `BuildCommand`.

- [ ] **Step 3: Implement**

In `core/src/main/java/dev/dediren/core/commands/BuildCommand.java`, immediately after the three private constants at `:75-77`, add:

```java
  /**
   * The emit kinds {@code emitEnvelope} knows how to write. Public because this vocabulary is
   * <em>published</em>, not merely enforced: the MCP build tool advertises it to agents in its
   * input schema, and {@code ToolSchemasTest} pins that advertisement to this set. Adding a kind
   * here without advertising it there makes the kind unreachable over MCP.
   */
  public static final java.util.Set<String> EMIT_KINDS =
      java.util.Set.of(EMIT_LAYOUT_REQUEST, EMIT_LAYOUT_RESULT, EMIT_RENDER_METADATA);
```

Then replace the validation block at `:95-100` — the comment and the local set — with:

```java
    // Emit-kind validation lives here, in the module that owns the emit vocabulary, so both the
    // CLI and the MCP lane inherit it (an unknown kind would otherwise be silently dropped by
    // emitEnvelope, which only writes for the three keys in EMIT_KINDS). The MCP tool schema
    // re-publishes the same set to agents; ToolSchemasTest pins the two together.
    for (String kind : request.emit()) {
      if (!EMIT_KINDS.contains(kind)) {
```

Leave the body of the `if` (the `buildLevelError(...)` call) exactly as it is.

Then add this comment above `buildRejectsUnknownEmitKindThroughBothLanes` in `cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java`, so nobody reads it as proving wire behavior it does not prove:

```java
  /**
   * Handler parity, not wire parity. This calls DedirenTools directly; over a real MCP connection
   * the SDK validates arguments against ToolSchemas.BUILD's enum first (validateToolInputs defaults
   * to true), so an unknown emit kind is rejected by the transport and this handler path is never
   * reached. Both rejections are correct; they simply happen at different layers, which is why
   * ToolSchemasTest pins the advertised enum to BuildCommand.EMIT_KINDS.
   */
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -pl mcp-server -am test -Dtest=ToolSchemasTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 5: Run the affected module suites**

Run: `./mvnw -pl mcp-server,cli,core -am test`

Expected: BUILD SUCCESS, including `BuildCommandTest` and `CliMcpParityTest#buildRejectsUnknownEmitKindThroughBothLanes`.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add core/src/main/java/dev/dediren/core/commands/BuildCommand.java \
        mcp-server/src/test/java/dev/dediren/mcp/ToolSchemasTest.java \
        cli/src/test/java/dev/dediren/cli/CliMcpParityTest.java
git commit -m "test(mcp): pin the advertised emit enum to BuildCommand's vocabulary

BuildCommand claimed to be the one place that owns the emit vocabulary,
but its constants were private and ToolSchemas.BUILD re-declared the same
three values as a JSON enum literal — the surface agents actually
discover the vocabulary from. A fourth kind added to core would have been
silently unreachable over MCP. Exposes BuildCommand.EMIT_KINDS and asserts
the advertised enum equals it."
```

---

### Task 5: Anchor the ancestor walk with `NOFOLLOW_LINKS` (both sites)

> **Severity, after adversarial re-validation: this is defence-in-depth hardening, NOT a vulnerability fix.** Do not describe it as closing a hole in the commit message or anywhere else.

**Files:**
- Modify: `mcp-server/src/main/java/dev/dediren/mcp/WorkspacePaths.java:1-18` (imports and class doc), `:42`
- Modify: `core/src/main/java/dev/dediren/core/source/SourceValidator.java:154-167` (javadoc), `:189`
- Test: `mcp-server/src/test/java/dev/dediren/mcp/WorkspacePathsTest.java`

**Why:** the ancestor walk uses `existing.toFile().exists()`, which **follows** symlinks. A dangling symlink inside the root therefore reads as absent, the walk steps straight past it, its name becomes an unresolved remainder segment, and the final `confine` on that segment is purely textual — so the method returns a path whose containment it never established.

**What re-validation established (do not re-litigate):**

- **There is no argument-only escape.** `Files.createDirectories` fails closed on *every* dangling-symlink variant probed — intermediate segment, final segment, relative-pointing-back-inside, and nested chain — because `mkdir` returns `EEXIST` (lstat sees the link) and the follow-up `isDirectory` follows the link and is false. The model has no symlink-creation primitive: `view` is `VIEW_ID_PATTERN`-constrained and artifact filenames are derived.
- **The escapes that do exist need a local actor with write access inside the root**, which is the residual already accepted at `WorkspacePaths.java:15-17` and `docs/threat-model.md:105-106,228`. This change narrows one of them (a dangling-symlink TOCTOU becomes a guard rejection) and does not close the general resolve-then-open race.
- **core does NOT make this redundant.** `BuildCommand.requireWithinOutDir` (`:512-518`) anchors on `outDir` — which *is* the value `resolveForWrite` returned — not on the workspace root. It stops view-id escapes *within* `outDir` only. If `resolveForWrite` ever returned an escaping path, core would write there.
- **There is no legitimate-workflow cost.** `Files.exists(p, NOFOLLOW_LINKS)` and `toFile().exists()` differ *only* for dangling links. Probed: a legitimate in-root symlink (absolute or relative) returns the identical path before and after, and the existing pinned test for a non-dangling escaping symlink still rejects. An earlier draft of this plan claimed a "deliberate tradeoff" here — that claim was wrong and has been removed.

**Both sites move together.** `core/src/main/java/dev/dediren/core/source/SourceValidator.java:189` carries the identical walk on the fragment *read* path, and its javadoc at `:156-158` explicitly claims to mirror `WorkspacePaths.resolveForWrite`. Fixing one site alone makes that claim false, which `CLAUDE.md` §Files That Move Together forbids. Note the javadoc at `:164` already misdescribes its own code — it says `Files#exists` where the code calls `existing.toFile().exists()`; fix that too.

- [ ] **Step 1: Write the failing test**

Add to `mcp-server/src/test/java/dev/dediren/mcp/WorkspacePathsTest.java`:

```java
  // A symlink whose target does not exist. File.exists() follows links and so reports it absent,
  // which lets the ancestor walk step past it and confine the remainder textually only -- the guard
  // then returns a path it has not actually established as contained, and containment falls to
  // whatever the caller does with it. Anchoring the walk with NOFOLLOW_LINKS makes the symlink
  // itself the nearest existing ancestor, which is then real-path-resolved (and fails).
  //
  // Nothing that succeeds today stops succeeding: the two existence checks differ only for
  // dangling links. A dangling link fails either way -- this just fails at the guard, with a
  // clear diagnostic, instead of later in the writer.
  @Test
  void resolveForWriteRejectsWriteThroughADanglingSymlink(@TempDir Path root, @TempDir Path outside)
      throws Exception {
    Path link = root.resolve("link");
    try {
      Files.createSymbolicLink(link, outside.resolve("never-created"));
    } catch (UnsupportedOperationException | IOException unsupported) {
      return; // Filesystem without symlink support.
    }

    assertThatThrownBy(() -> WorkspacePaths.resolveForWrite(root, "link/out"))
        .isInstanceOf(PathOutsideRootException.class)
        .hasMessageContaining("outside the workspace root");
  }

  @Test
  void resolveForWriteRejectsADanglingSymlinkAsTheTargetItself(
      @TempDir Path root, @TempDir Path outside) throws Exception {
    Path link = root.resolve("out");
    try {
      Files.createSymbolicLink(link, outside.resolve("never-created"));
    } catch (UnsupportedOperationException | IOException unsupported) {
      return;
    }

    assertThatThrownBy(() -> WorkspacePaths.resolveForWrite(root, "out"))
        .isInstanceOf(PathOutsideRootException.class);
  }
```

The `SourceValidator` half needs its own coverage — the existing fragment tests only exercise non-dangling paths. Add to `mcp-server/src/test/java/dev/dediren/mcp/DedirenToolsTest.java`, beside the other fragment-confinement cases:

```java
  // The core-side twin of resolveForWriteRejectsWriteThroughADanglingSymlink. SourceValidator's
  // fragment walk carries the identical anchoring on the READ path, and its javadoc claims to
  // mirror WorkspacePaths -- so the two must be pinned together, not just kept in sync by hand.
  //
  // Behavior change worth knowing: before this fix the walk stepped past the dangling link, the
  // textual confine passed, and the failure surfaced later as FRAGMENT_READ_FAILED. Now the walk
  // anchors ON the link, real-path resolution fails, and it is rejected as a confinement failure.
  // Both are errors and the fragment is unreadable either way; only the code and message change.
  @Test
  void validateRejectsAFragmentReachedThroughADanglingSymlink(
      @TempDir Path root, @TempDir Path outside) throws Exception {
    Path link = root.resolve("link");
    try {
      Files.createSymbolicLink(link, outside.resolve("never-created"));
    } catch (UnsupportedOperationException | IOException unsupported) {
      return; // Filesystem without symlink support.
    }
    Files.writeString(root.resolve("model.json"), modelWithFragment("link/frag.json"));

    CallToolResult result =
        toolsIn(root)
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    // Same anti-fingerprinting rule as the sibling fragment tests.
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }
```

This needs `java.io.IOException` imported in `DedirenToolsTest` — check and add it if absent.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl mcp-server -am test -Dtest=WorkspacePathsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL — both new cases fail because `resolveForWrite` returns a path instead of throwing.

- [ ] **Step 3: Implement**

In `WorkspacePaths.java`, add the two imports (the file currently imports only `java.io.IOException` and `java.nio.file.Path`):

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
```

Replace the walk condition at `:42`:

```java
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
```

Then extend the class doc's third paragraph so the guarantee and its cost are recorded:

```java
 * <p>The ancestor walk deliberately does not follow symlinks. {@code File.exists()} does, so a
 * dangling symlink inside the root would read as absent, the walk would step past it, and its name
 * would become an unresolved remainder segment checked only as text -- leaving the guard to return
 * a path whose containment it had not established, and the caller to establish it by accident.
 * With {@code NOFOLLOW_LINKS} the symlink is itself the nearest existing ancestor and gets
 * real-path-resolved like any other. Nothing that resolves today stops resolving: the two checks
 * differ only for dangling links, which fail either way -- now at the guard rather than the writer.
```

Now the sibling site. In `core/src/main/java/dev/dediren/core/source/SourceValidator.java`, apply the same change at `:189`:

```java
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
```

Check the imports at the top of that file and add `java.nio.file.LinkOption` if absent (`java.nio.file.Files` is already used there). Then correct the javadoc at `:154-167`, which currently both claims to mirror the mcp guard *and* misdescribes its own code:

- At `:157-158`, replace "real-path-resolve that ancestor (following symlinks)" with "real-path-resolve that ancestor (the walk itself does not follow symlinks, so a dangling link anchors it rather than being stepped over)".
- At `:164`, the sentence begins "{@link Files#exists} and {@link Path#toRealPath} both resolve symlinks physically" — this described a call the code does not make. With the change it becomes true of `toRealPath`, so rewrite as "{@link Path#toRealPath} resolves symlinks physically, component-by-component — exactly like the OS call a read performs — and the walk deliberately stops at a symlink rather than following it".

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl mcp-server -am test -Dtest=WorkspacePathsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS — 14 cases (`WorkspacePathsTest` holds 12 today; this task adds 2). Confirm specifically that the pre-existing `resolveForWriteAcceptsNonExistentPathInsideRoot` (no symlink in the path, so the walk is unchanged) and `resolveForWriteRejectsWriteThroughEscapingSymlinkDirectory` (link resolves to an existing outside dir, still caught by `confine`) both still pass.

- [ ] **Step 5: Run the module suites — `core` included, because the sibling site lives there**

Run: `./mvnw -pl mcp-server,cli,core -am test`

Expected: BUILD SUCCESS. `SourceValidatorTest` and the MCP fragment-confinement cases in `DedirenToolsTest` (`validateConfinesAnEscapingSourceFragmentToTheRoot`, `validateLoadsALegitimateFragmentInsideTheRoot`) are the guard for the `SourceValidator` half.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add mcp-server/src/main/java/dev/dediren/mcp/WorkspacePaths.java \
        mcp-server/src/test/java/dev/dediren/mcp/WorkspacePathsTest.java \
        core/src/main/java/dev/dediren/core/source/SourceValidator.java
git commit -m "harden: anchor the confinement walk with NOFOLLOW_LINKS at both sites

Both walks used File.exists(), which follows symlinks, so a dangling
symlink inside the root read as absent: the walk stepped past it and the
remainder was confined textually only, leaving the guard to return a path
whose containment it had not established.

Defence in depth, not a vulnerability fix. There is no argument-only
escape: Files.createDirectories fails closed on every dangling-symlink
variant, and the model has no symlink-creation primitive. What this buys
is that the guard establishes containment itself instead of relying on
the writer, and it narrows the locally-planted-symlink residual already
accepted in docs/threat-model.md. No legitimate path changes behavior —
the two existence checks differ only for dangling links.

SourceValidator carries the identical walk on the fragment read path and
its javadoc claims to mirror WorkspacePaths, so both move together; that
javadoc also described a Files.exists call the code never made."
```

---

### Task 6: Deregister the shutdown hook after a normal close — **DROPPED, DO NOT IMPLEMENT**

> **This task is out of scope. Skip it entirely and go from Task 5 to Task 7.**
>
> Adversarial re-validation refuted it as a defect: hook accumulation is test-only, and the one real production effect — a double `close()` on the clean-EOF path — is harmless because `close()` is idempotent by construction (verified in SDK bytecode and on a live session, not inferred from green tests). Scope decision taken 2026-07-20: confirmed defects and zero-cost hardening only.
>
> The analysis below is retained verbatim in case the decision is revisited; it was validated as correct and non-regressing across every exit path. **Do not treat anything below this banner as work to perform.**

**Files:**
- Modify: `mcp-server/src/main/java/dev/dediren/mcp/DedirenMcpServer.java:142-153`

> **Severity, after adversarial re-validation: hygiene, not a defect. Zero user-visible impact.** This task is optional — drop it without consequence if scope needs trimming. It is last for that reason.

**Why:** `serveOn` registers a shutdown hook per call and never removes it. Both halves of the original finding were checked:

- **Accumulation is test-only.** Production calls `serveOn` exactly once per JVM (`Main.java:515` → `serve` → `serveOn`). `DedirenMcpServerTest` calls it ~9 times in one surefire JVM — nine `Thread` objects, negligible. (An earlier draft of this plan said "22 times"; that was a `grep -c` of lines mentioning `serveOn`, not a call count.)
- **The double-close is real in production** — the hook plus the explicit `close()` both fire on the ordinary clean-EOF path — **but harmless**, because `close()` is idempotent by construction, not by luck. Traced through the SDK: `McpSyncServer.close` → `McpAsyncServer.close` → `closeGracefully().subscribe()` → `StdioMcpSessionTransport` does `isClosing.set(true)` plus a discarded `tryEmitComplete()` (second call returns `FAIL_TERMINATED`), with the onClose Mono wrapped in `onErrorComplete()`. Probed on a live initialized session: three successive closes each returned in 0 ms with no throw and no output damage.

What remains worth doing: the `finally` also closes the server on the `InterruptedException` path, where today it does not. That is a strict superset of current behavior.

**No new test.** Hook deregistration is not directly assertable without reflecting into `java.lang.ApplicationShutdownHooks`, which is not open and is not worth an `--add-opens` for a hygiene fix. The existing `DedirenMcpServerTest` cases are the regression guard. The proposed shape was already driven over every exit path that file exercises — EOF, unreadable frame (both malformed-JSON and wrong-shape), read failure, oversized frame, and healthy-batch-then-EOF — all returning promptly with hooks back to zero and all four diagnostic stderr messages still emitted.

**Implementation note:** the hook **must** be hoisted into a captured local. The current inline `new Thread(server::close)` at `:150` cannot be passed to `removeShutdownHook`. The `catch (IllegalStateException)` is correctly scoped: `removeShutdownHook` returns `false` when already removed and throws only mid-shutdown.

- [ ] **Step 1: Implement**

In `DedirenMcpServer.java`, replace the body of `serveOn(InputStream, OutputStream, ServerFactory)` (currently `:142-153`) with:

```java
  static void serveOn(InputStream in, OutputStream out, ServerFactory factory)
      throws InterruptedException {
    CountDownLatch stdinClosed = new CountDownLatch(1);
    PendingRequests pending = new PendingRequests();
    McpSyncServer server =
        factory.create(
            new EofSignalingInputStream(in, stdinClosed, pending),
            new FrameScanningOutputStream(out, pending));
    // The hook only has to cover the window before the normal close below. Leaving it registered
    // afterwards retains a thread for the life of the JVM and closes the server a second time at
    // shutdown; the test suite drives this method many times in one JVM.
    Thread shutdownHook = new Thread(server::close);
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    try {
      stdinClosed.await();
    } finally {
      server.close();
      removeShutdownHook(shutdownHook);
    }
  }

  /**
   * Removes the hook, tolerating the one case where it cannot be removed: {@code
   * removeShutdownHook} throws {@link IllegalStateException} once shutdown is already under way,
   * which is precisely when the hook is already running and there is nothing left to deregister.
   */
  private static void removeShutdownHook(Thread hook) {
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException shutdownInProgress) {
      // Shutdown already started; the hook is doing its job.
    }
  }
```

Note the `try`/`finally` also closes the server when `stdinClosed.await()` is interrupted, which the previous straight-line version did not.

- [ ] **Step 2: Run the server suite**

Run: `./mvnw -pl mcp-server -am test -Dtest=DedirenMcpServerTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS, all cases. These exercise every exit path the new `finally` now covers.

- [ ] **Step 3: Run the module suites**

Run: `./mvnw -pl mcp-server,cli -am test`

Expected: BUILD SUCCESS.

- [ ] **Step 4: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add mcp-server/src/main/java/dev/dediren/mcp/DedirenMcpServer.java
git commit -m "fix(mcp): deregister the shutdown hook after a normal close

serveOn registered a hook per call and never removed it, retaining a
thread for the life of the JVM and closing the server twice at shutdown.
Production calls serveOn once; the test suite calls it 22 times. Moving
the close into a finally block also closes the server when the latch wait
is interrupted, which the straight-line version did not."
```

---

### Task 7: Full verification

**Files:** none — verification only.

- [ ] **Step 1: Full test suite**

Run: `./mvnw test -Dtest='!*FuzzTest'`

Expected: BUILD SUCCESS. (The `!*FuzzTest` exclusion is for the sandbox only — the two Jazzer tests fail under it for ByteBuddy self-attach reasons unrelated to this change. Run `./mvnw test` unfiltered if executing outside the sandbox.)

- [ ] **Step 2: Quality gate**

Run: `./mvnw -Pquality verify -Dtest='!*FuzzTest'`

Expected: BUILD SUCCESS — no Spotless violations, no SpotBugs findings. If SpotBugs flags the new nested exception classes, do **not** add a suppression without recording it in `docs/architecture-guidelines.md §12` per `CLAUDE.md` §Code Style.

- [ ] **Step 3: Distribution smoke**

Run (sandbox-disabled — needs network/cold-cache access): `./mvnw -pl dist-tool -am verify -Pdist-smoke`

Expected: BUILD SUCCESS, including `DistTool.assertMcpServesToolsOverStdio` (the packaged MCP stdio smoke — the only place the real process streams are observable) and `AgentUsageDocConsistencyTest` (which should be unaffected: no new `DEDIREN_*` token, no new guide topic).

- [ ] **Step 4: Confirm the worktree is clean of incidental edits**

```bash
git status --short --branch
git diff --check
```

Expected: no unstaged changes to tracked files. Pre-existing untracked dotfiles in the repo root are user work — leave them alone.

---

## Self-Review

**Spec coverage:** all six actionable findings map to a task (see Findings Map). Findings 6 and 8 are explicitly excluded with reasons, not silently dropped.

**Placeholder scan:** every code step carries complete code; every run step carries an exact command and expected outcome. Task 6 has no new test and says so, with the reason and the substitute guard named.

**Type consistency:** `InvalidListArgumentException.argument()` (Task 1) and `PolicyReadException.argument()`/`candidate()`/`ioCause()` (Task 2) are distinct nested classes in the same file with no name collisions. `readFailure(String, String, IOException)` (Task 2) matches the existing signature at `DedirenTools.java:219`. `GuideCatalog.hasSection(String)` (Task 3) is used by both `GuideCatalog.section` and `DedirenTools.guide`. `BuildCommand.EMIT_KINDS` (Task 4) is `java.util.Set<String>`, matching `containsExactlyInAnyOrderElementsOf`'s `Iterable` parameter.

**Ordering:** run all seven tasks **sequentially**. Tasks 1–3 all modify `DedirenTools.java`, so they must be. Tasks 4–6 touch disjoint files and could be reordered freely, but do **not** run them concurrently: every task's verification step invokes Maven, and parallel agents sharing this repo's `target/` directories race each other. Task 4 modifies `core`, so run its full suite before moving on.
