# UML Sequence: ExecutionSpecification, Destruction, Delete-Messages — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `ExecutionSpecification` and `DestructionOccurrenceSpecification` actually work — placed on their lifeline's stem instead of dumped at the canvas origin — and stop a delete-message from hard-failing the build (exit 2).

**Architecture:** The source model gains free-form `uml.covered` / `uml.start` / `uml.finish` conventions (following the existing `CombinedFragment.covered` precedent; `properties` is `additionalProperties: true`, so **no schema change**). `semantics-uml` gives these types layout roles and lowers them to ONE new neutral `LayoutIntent` variant — `StemSpan(nodeId, bandMemberId, fromMemberId, toMemberId)` — which talks about bands and members, not UML. `elk-layout` consumes it: centre the node on the covered lifeline's stem and derive y from the referenced message rows, and resolve a non-lifeline message endpoint (a destruction) through its anchor to that lifeline's stem.

**Tech Stack:** Java 21, Maven Wrapper, JUnit 5 + AssertJ, jqwik, Eclipse ELK 0.11.0 (layered), google-java-format (Spotless), SpotBugs.

## Global Constraints

- **Spec of record:** `docs/superpowers/specs/2026-07-13-uml-sequence-execution-destruction-design.md`. Live code + tests are truth when they disagree with a plan.
- **NO schema change, NO schema-id bump.** `sourceNode.properties` is `additionalProperties: true`; the new `uml.*` keys ride that. The `layout-request` wire keeps its `{id, kind, subjects: string[]}` constraint shape — `StemSpan` encodes into it.
- **Model conventions (exact):**
  - `ExecutionSpecification`: `uml.covered` = the lifeline id it sits on; `uml.start` / `uml.finish` = message ids bounding the bar.
  - `DestructionOccurrenceSpecification`: `uml.covered` = the lifeline id it terminates. Its row = the row of the message that TARGETS it. Orphan (no message targets it) → anchored one `MESSAGE_Y_STEP` below the last message row.
- **The new neutral IR variant (exact):** `record StemSpan(String nodeId, String bandMemberId, String fromMemberId, String toMemberId) implements LayoutIntent`. Wire: kind `"stem-span"`, subjects `[nodeId, bandMemberId, fromMemberId, toMemberId]`. `elk-layout` and `ir` must remain notation-free — no `ExecutionSpecification`/`uml.*` string may appear in either.
- **NOTHING IS WEAKENED to get green.** The hard-error lane (`LayoutQuality.validateLayoutDiagnostics`, incl. `pointOnNodePerimeter`) is UNTOUCHED. That is precisely why a delete-message must terminate on an **edge** of the destruction, not its centre. **Corrected after the Task 3 review:** it must be the **NEAR** edge, chosen by relative column order — `endX = sourceIndex > targetIndex ? target.x() + target.width() : target.x()`. An unconditional left edge is a silent visual defect when the source lifeline is declared to the RIGHT of the destroyed one (the arrow crosses the whole ✕ and the arrowhead is drawn past the glyph pointing away — the mirror of the D′ arrowhead defect this repo already fixed once). Both edges satisfy `pointOnNodePerimeter`, so the near edge honours the "unweakened" constraint equally.
- **The one legitimate extension:** `LayoutQuality.isSequenceContainer` (`core/.../LayoutQuality.java:363`) currently returns `"interaction".equals(node.role())` and its own comment says the frame "legitimately encloses its lifelines, **executions**, and messages". Extend it to also exempt `"execution"` and `"destruction"` from the soft overlap / connector-through-node COUNTS (same for the property test's `assertNoNodeRectsOverlap`). Without this, correct UML geometry (a bar sitting on the stem a message lands on) is reported as a defect. This extends an existing documented exemption — it is not a loosening invented for this change.
- **BYTE-STABILITY GUARD:** no existing fixture contains any of these node types, so **every current `fixtures/layout-result/*.json` must stay byte-identical**. A diff there means the change leaked into ordinary sequence geometry — debug it, do NOT re-baseline.
- **Maven under the sandbox:** run every `./mvnw` with the Bash tool's sandbox DISABLED (`dangerouslyDisableSandbox: true`) — tests fail under the sandbox on read-only `/tmp` (`@TempDir`) and the fuzz test's self-attach. Environment failures, not code failures.
- **Work in the worktree** `/home/souroldgeezer/repos/dediren/.worktrees/w1-uml-sequence` (branch `w1-uml-sequence-coverage`), NOT the main checkout.
- Before every Java commit: `./mvnw -Pquality spotless:apply`. Explicit-path staging only — never `git add -A`. Commits unsigned. New source fixtures carry `required_plugins[].version` == the product version in root `pom.xml`.

### The acceptance oracle

This model (legal UML — `validate --profile uml` → `ok`) currently fails `build` with **exit 2**:
`DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER: edge 'm3' first route point is not on source node 'service' perimeter`, with the activation bar dumped at **(12,12)** and the ✕ floating at x=411. After this plan it must build `status: ok` with the bar on the `service` stem and the ✕ on the `worker` stem.

---

### Task 1: `ir` — the `StemSpan` neutral intent + codec

**Files:**
- Modify: `ir/src/main/java/dev/dediren/ir/LayoutIntent.java`
- Modify: `ir/src/main/java/dev/dediren/ir/LayoutIntentCodec.java`
- Test: `ir/src/test/java/dev/dediren/ir/LayoutIntentCodecTest.java`

**Interfaces produced (used by Tasks 2 + 3):**
`LayoutIntent.StemSpan(String nodeId, String bandMemberId, String fromMemberId, String toMemberId)`, encoding to `LayoutConstraint(viewId + ".stem-span." + nodeId, "stem-span", List.of(nodeId, bandMemberId, fromMemberId, toMemberId))` and decoding back losslessly.

**Note:** `LayoutIntent` is a sealed interface and `LayoutIntentCodec` switches over it exhaustively, so adding the variant will not compile until the codec handles it — that is expected and is why they land together. `LayoutIntentNormalizer` matches with `instanceof OrderedBand`, so it simply ignores `StemSpan` until Task 3.

- [ ] **Step 1: Write the failing round-trip test.**

```java
  @Test
  void roundTripsStemSpan() {
    List<LayoutIntent> intents =
        List.of(
            new OrderedBand(Axis.X, List.of(new BandMember("customer", 0.0), new BandMember("service", 0.0))),
            new StemSpan("exec-1", "service", "m1", "m4"),
            new StemSpan("destroy-1", "worker", "m3", "m3")); // degenerate: a destruction

    List<LayoutConstraint> wire = LayoutIntentCodec.encode("v1", intents);

    assertThat(wire).extracting(LayoutConstraint::kind)
        .containsExactly("ordered-band:x", "stem-span", "stem-span");
    assertThat(wire.get(1).subjects()).containsExactly("exec-1", "service", "m1", "m4");
    assertThat(LayoutIntentCodec.decode(wire)).isEqualTo(intents);
  }
```

- [ ] **Step 2: Run it, confirm RED** (symbol `StemSpan` not found).

Run (sandbox disabled): `./mvnw -pl ir -am test -Dtest=LayoutIntentCodecTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Add the variant + codec cases.**

In `LayoutIntent.java`, extend the `permits` clause and add:
```java
  /**
   * Place {@code nodeId} on the axis of band member {@code bandMemberId}, spanning from ordered
   * member {@code fromMemberId} to {@code toMemberId}. Neutral: it speaks of bands and members, not
   * of any notation. A point anchor is the degenerate case {@code fromMemberId == toMemberId}.
   */
  record StemSpan(String nodeId, String bandMemberId, String fromMemberId, String toMemberId)
      implements LayoutIntent {}
```
In `LayoutIntentCodec`, add the encode case (kind `"stem-span"`, the 4 subjects in that order) and the decode case (dispatch on the `"stem-span"` kind; require exactly 4 subjects, else throw `IllegalArgumentException` consistent with the existing unknown-kind rejection). Keep the codec notation-free.

- [ ] **Step 4: Run to GREEN.** `./mvnw -pl ir -am test` → PASS.

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add ir/src/main/java/dev/dediren/ir/LayoutIntent.java ir/src/main/java/dev/dediren/ir/LayoutIntentCodec.java ir/src/test/java/dev/dediren/ir/LayoutIntentCodecTest.java
git commit -m "feat(ir): StemSpan layout intent anchors a node to a band member over a row span"
```
(If SpotBugs flags the new record under `-Pquality verify` later, add it to the existing `ir` `EI_EXPOSE_REP` block in `spotbugs-exclude.xml` AND record it in `docs/architecture-guidelines.md §12` — this repo never suppresses silently.)

---

### Task 2: `semantics-uml` — roles + typed constraints + lowering

**Files:**
- Modify: `semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlNotationSemantics.java` (`layoutRole`)
- Modify: `semantics-uml/src/main/java/dev/dediren/semantics/uml/SequenceConstraint.java` (2 new variants)
- Modify: `semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlSequenceConstraints.java` (produce + lower them)
- Test: `semantics-uml/src/test/java/dev/dediren/semantics/uml/UmlSequenceConstraintsTest.java`, `UmlNotationSemanticsTest.java`

**Interfaces:**
- Consumes: `LayoutIntent.StemSpan` (Task 1).
- Produces (used by Task 3): scene nodes carrying `role="execution"` / `role="destruction"`, and `StemSpan` intents on the `SceneGraph`.

**Behaviour:**
- `layoutRole`: `"ExecutionSpecification"` → `"execution"`; `"DestructionOccurrenceSpecification"` → `"destruction"`. `Gate` stays `null` (out of scope). Keep `Lifeline` → `"lifeline"` and `Interaction` → `"interaction"` unchanged.
- New sealed `SequenceConstraint` variants:
  ```java
  record ExecutionSpan(String executionId, String coveredLifelineId, String startMessageId, String finishMessageId) implements SequenceConstraint {}
  record DestructionAnchor(String destructionId, String coveredLifelineId, String anchorMessageId) implements SequenceConstraint {}
  ```
- In `UmlSequenceConstraints.sequenceConstraints(...)`, for the selected `UML_SEQUENCE` view:
  - For each selected `ExecutionSpecification` node: read `uml.covered` (lifeline id), `uml.start`, `uml.finish` (message ids) → emit `ExecutionSpan`. Skip the node (emit nothing) if `covered`/`start`/`finish` are absent or don't resolve to selected ids — a malformed occurrence must not crash layout.
  - For each selected `DestructionOccurrenceSpecification` node: read `uml.covered`; find the selected `Message` relationship whose `target` is this node → its id is the anchor. If none, `anchorMessageId = null` (the orphan case; Task 3 places it below the last row) → emit `DestructionAnchor`.
- In `lower(...)`: `ExecutionSpan → StemSpan(executionId, coveredLifelineId, startMessageId, finishMessageId)`; `DestructionAnchor → StemSpan(destructionId, coveredLifelineId, anchorMessageId, anchorMessageId)` (degenerate span). For the orphan case (`anchorMessageId == null`), emit `StemSpan(destructionId, coveredLifelineId, "", "")` — Task 3 treats empty from/to as "below the last row". Document that convention on the record.

- [ ] **Step 1: Write the failing tests.** In `UmlNotationSemanticsTest`, assert `layoutRole("ExecutionSpecification") == "execution"` and `layoutRole("DestructionOccurrenceSpecification") == "destruction"` (and that `Gate` is still null). In `UmlSequenceConstraintsTest`, build a source with an execution spec (`covered: service`, `start: m1`, `finish: m4`) and a destruction (`covered: worker`, targeted by `m3`), and assert `sequenceConstraints(...)` contains `new ExecutionSpan("exec-1","service","m1","m4")` and `new DestructionAnchor("destroy-1","worker","m3")`, and that `lower(...)` yields the two `StemSpan`s above.

- [ ] **Step 2: Run, confirm RED.** `./mvnw -pl semantics-uml -am test -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** the role additions, the two sealed variants, the producers, and the lowering.

- [ ] **Step 4: Run to GREEN.** `./mvnw -pl semantics-uml,semantics-graph -am test` → PASS. Confirm `git status --short -- fixtures/` is EMPTY (no existing fixture has these node types, so nothing may change).

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add semantics-uml/src/main/java/dev/dediren/semantics/uml/ semantics-uml/src/test/java/dev/dediren/semantics/uml/
git commit -m "feat(semantics-uml): execution/destruction roles + StemSpan lowering"
```

---

### Task 3: `elk-layout` — place the bar and the ✕, and route the delete-message

**Files:**
- Modify: `engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizer.java`
- Test: `engines/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizerTest.java`

**Interfaces:**
- Consumes: `LayoutIntent.StemSpan` (Task 1), scene node roles `execution`/`destruction` (Task 2).

**This is the geometry. Read the whole file first.** Current `normalize(...)` order is: interaction nodes → lifeline nodes → message edges. You must place the `StemSpan` nodes AFTER lifelines (their x depends on the normalized stem) and BEFORE message edges (a delete-message's endpoint depends on the placed ✕). New order: lifelines → stem-span nodes → message edges → interaction frame.

**Behaviour (exact):**
1. **Parse `StemSpan` intents** in `from(...)` into a map `stemSpanByNodeId`.
2. **Endpoint resolution.** Build `anchorLifelineByNodeId`: for every `StemSpan`, `nodeId → bandMemberId` (the covered lifeline id). Add a helper `resolveLifelineId(String endpointId)` returning the endpoint itself if it is a lifeline, else its anchor lifeline (or null). Use it wherever the code currently looks up `lifelineIndexById.get(edge.source()/target())` — so a message targeting a destruction resolves to the covered lifeline's column. **This is what fixes the exit-2 failure.**
3. **Place `StemSpan` nodes** (after lifelines are normalized):
   - Let `stem = stemX(coveredLifelineNode)` and `rowOf(messageId)` = that message's normalized y-slot.
   - Execution (`from != to`): `x = stem - node.width()/2`, `y = rowOf(from)`, `height = max(rowOf(to) - rowOf(from), MINIMUM_EXECUTION_HEIGHT)` where `MINIMUM_EXECUTION_HEIGHT = 24.0` (new neutral constant). Width unchanged.
   - Point anchor (`from == to`, i.e. a destruction): `x = stem - node.width()/2`, `y = rowOf(from) - node.height()/2` (centred on the row). Size unchanged.
   - Orphan (`from`/`to` empty): `y = lastMessageRow + MESSAGE_Y_STEP` (centred as above). If there are no messages at all, leave the node untouched.
   - Preserve `sourcePointer` provenance on every rebuilt node exactly as `normalizedLifelineNodes` does.
4. **Delete-message termination.** In `normalizedMessagePoints`, when the TARGET endpoint is a point-anchored node (a destruction), the last point is that node's **left edge**: `new Point(destructionNode.x(), y)` — NOT the stem centre. (The pre-existing `pointOnNodePerimeter` hard-error check must pass unweakened.) The SOURCE endpoint still anchors to its lifeline stem. So the delete-message is a clean 2-point horizontal segment: `(stemX(sourceLifeline), y) → (destruction.x(), y)`.
5. **Interaction frame.** Extend the bbox in `normalizedInteractionNodes` to also enclose the placed execution/destruction nodes.

- [ ] **Step 1: Write the failing tests.** Build a `LayoutResult` + intents with: lifelines `a` (x=0,w=140 → stem 70) and `b` (x=236,w=140 → stem 306), both y=0,h=48 (headBottom 48); messages `m1` (a→b), `m2` (a→b), `m3` (a→`destroy-b`); an execution node `exec-b` (w=16,h=72) with `StemSpan("exec-b","b","m1","m2")`; a destruction node `destroy-b` (w=24,h=24) with `StemSpan("destroy-b","b","m3","m3")`. Rows: m1=72, m2=96, m3=120.

```java
  @Test
  void executionSpecificationSitsOnItsLifelineStemSpanningItsRows() {
    LayoutResult out = LayoutIntentNormalizer.from(lifecycleIntents(), Map.of(), Map.of())
        .normalize(lifecycleResult());

    LaidOutNode exec = node(out, "exec-b");
    assertThat(exec.x()).isEqualTo(306.0 - 16.0 / 2); // centred on b's stem (306)
    assertThat(exec.y()).isEqualTo(72.0);             // m1's row
    assertThat(exec.height()).isEqualTo(24.0);        // m2(96) - m1(72)
  }

  @Test
  void destructionSitsCentredOnItsLifelineStemAtTheDeleteMessageRow() {
    LayoutResult out = LayoutIntentNormalizer.from(lifecycleIntents(), Map.of(), Map.of())
        .normalize(lifecycleResult());

    LaidOutNode x = node(out, "destroy-b");
    assertThat(x.x()).isEqualTo(306.0 - 24.0 / 2);  // centred on b's stem
    assertThat(x.y()).isEqualTo(120.0 - 24.0 / 2);  // centred on m3's row
  }

  @Test
  void deleteMessageRunsFromTheSourceStemToTheDestructionsLeftEdge() {
    LayoutResult out = LayoutIntentNormalizer.from(lifecycleIntents(), Map.of(), Map.of())
        .normalize(lifecycleResult());

    List<Point> pts = edge(out, "m3").points();
    assertThat(pts).containsExactly(
        new Point(70.0, 120.0),              // a's stem
        new Point(306.0 - 24.0 / 2, 120.0)); // the destruction's LEFT EDGE (perimeter, not centre)
  }
```

- [ ] **Step 2: Run, confirm RED.** `./mvnw -pl engines/elk-layout -am test -Dtest=LayoutIntentNormalizerTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** points 1-5 above.

- [ ] **Step 4: Run to GREEN + byte-stability.**
```
./mvnw -pl engines/elk-layout -am test
git status --short -- fixtures/
```
Expected: the whole elk suite PASSES (every existing sequence test unchanged) and `git status -- fixtures/` is **EMPTY**. If a fixture changed, STOP — the change leaked into ordinary sequence geometry. Debug; do not re-baseline.

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizer.java engines/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizerTest.java
git commit -m "fix(elk-layout): place execution/destruction on the lifeline stem; route delete-messages"
```

---

### Task 4: `core` — treat execution/destruction as sequence chrome in the soft quality counts

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/quality/LayoutQuality.java` (`isSequenceContainer`, line ~363)
- Test: `core/src/test/java/dev/dediren/core/quality/LayoutQualityTest.java`

**Why:** an activation bar sits ON the stem a message terminates on — that is correct UML, but the soft `countOverlaps` / `countConnectorThroughNodes` counters would report it as a defect. `isSequenceContainer` already exempts `role="interaction"` and its own comment says the frame "legitimately encloses its lifelines, **executions**, and messages". Extend it to `"execution"` and `"destruction"`.

**The hard-error lane is UNTOUCHED** — `validateLayoutDiagnostics` (incl. `pointOnNodePerimeter`, route-endpoint, self-loop) keeps guarding these nodes. Only the soft counters change.

- [ ] **Step 1: Write the failing test.** In `LayoutQualityTest`, build a `LayoutResult` where an `execution`-role node sits on a lifeline stem that a message terminates on, and assert `validateLayout(result)` reports `overlapCount == 0` and `connectorThroughNodeCount == 0` (today it counts them). Add a companion assertion that a genuine overlap between two ORDINARY (non-chrome) nodes is STILL counted — proving the exemption is scoped, not a blanket disable.

- [ ] **Step 2: Run, confirm RED.** `./mvnw -pl core -am test -Dtest=LayoutQualityTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement.** Change `isSequenceContainer` to:
```java
  private static boolean isSequenceContainer(LaidOutNode node) {
    // A UML sequence interaction frame legitimately encloses its lifelines, executions, and
    // messages; and an execution bar / destruction marker legitimately sits ON the lifeline stem a
    // message terminates on. None of these are overlaps or route-through-node defects. The
    // hard-error lane (validateLayoutDiagnostics) still guards them.
    return "interaction".equals(node.role())
        || "execution".equals(node.role())
        || "destruction".equals(node.role());
  }
```
Rename it if `isSequenceContainer` no longer fits (e.g. `isSequenceChrome`) and update its 3 call sites (~374, 379, 405).

- [ ] **Step 4: Run to GREEN.** `./mvnw -pl core,cli -am test` → PASS (the existing quality tests must stay green).

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add core/src/main/java/dev/dediren/core/quality/LayoutQuality.java core/src/test/java/dev/dediren/core/quality/LayoutQualityTest.java
git commit -m "fix(core): exempt execution/destruction sequence chrome from the soft quality counts"
```

---

### Task 5: `uml` — validate occurrence properties (close the remaining exit-2 path)

**Added after the Task 3 review**, which found that the exit-2 bug this slice exists to kill is **still reachable**: nothing validates `uml.covered`. The UML validator has no rules for `ExecutionSpecification` / `DestructionOccurrenceSpecification` properties at all — so a model that omits `uml.covered` on a destruction (or points it at a non-selected / non-Lifeline node) passes `validate --profile uml`, produces no `StemSpan`, falls through to ELK's raw route, and **hard-fails `build` with exit 2 and an obscure geometry diagnostic**. A clear semantic diagnostic at validation time beats an obscure geometry one at layout time.

**Files:**
- Modify: `uml/src/main/java/dev/dediren/uml/Uml.java` (sequence validation)
- Test: `uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`

**Interfaces:** none new — this tightens validation only.

**Rules to add (UML_SEQUENCE views only):**
1. An `ExecutionSpecification` MUST carry `uml.covered` resolving to a `Lifeline` **selected in the same view**, and `uml.start` / `uml.finish` each resolving to a `Message` **selected in the same view**.
2. A `DestructionOccurrenceSpecification` MUST carry `uml.covered` resolving to a selected `Lifeline`.
3. **At most one** `Message` may target a given `DestructionOccurrenceSpecification` (a second delete-message would route to a row outside the ✕'s box and fail the perimeter check).

Each violation throws the module's existing UML validation exception with a clear message naming the offending node id and the offending property — follow the idiom of the neighbouring sequence rules in `Uml.java` (e.g. the CombinedFragment/operand rules) exactly; reuse their diagnostic code and exit code. **Do not invent a new `DEDIREN_*` code** (that would churn `AgentUsageDocConsistencyTest`).

**This is a deliberate tightening.** Models it now rejects were already broken — they hard-failed at layout with exit 2. Rejecting them at `validate` with a clear message is strictly better. Note it in the spec's non-goals/limitations if you touch that area.

- [ ] **Step 1: Write the failing tests.** In `UmlValidationTest`, add cases (mirroring the existing sequence-validation test idiom): an ExecutionSpecification missing `uml.covered`; one whose `covered` names a non-Lifeline; one whose `start` names an unselected message; a DestructionOccurrenceSpecification missing `uml.covered`; and two Messages targeting the same destruction. Each must throw with a message naming the node and the property. Also add a POSITIVE case: a well-formed lifecycle model still validates `ok` (guards against over-tightening).

- [ ] **Step 2: Run, confirm RED.** `./mvnw -pl uml -am test -Dtest=UmlValidationTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement** the three rules.

- [ ] **Step 4: Run to GREEN.** `./mvnw -pl uml,semantics-uml,cli -am test` → PASS. **Every existing fixture must still validate** — no current source fixture has these node types, so none may start failing.

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add uml/src/main/java/dev/dediren/uml/Uml.java uml/src/test/java/dev/dediren/uml/UmlValidationTest.java
git commit -m "fix(uml): require covered/start/finish on sequence occurrences (was: obscure exit-2 at layout)"
```

---

### Task 6: Fixture + end-to-end build test (the acceptance oracle)

**Files:**
- Create: `fixtures/source/valid-uml-sequence-lifecycle.json`
- Create (GENERATED, not hand-authored): `fixtures/layout-result/uml-sequence-lifecycle.json`
- Modify: `cli/src/test/java/dev/dediren/cli/LayoutFixtureRegenerator.java` (add the `FixtureMapping` entry — its `MAPPINGS` table is explicit; without an entry the regen script cannot emit the fixture)
- Test: `cli/src/test/java/dev/dediren/cli/CliBuildCommandTest.java`

**The source fixture** — model it on `fixtures/source/valid-uml-sequence-basic.json` (read it for the exact conventions; do not invent fields). Three lifelines `customer`, `service`, `worker`; an `ExecutionSpecification` `exec-service` with `uml.covered: "service"`, `uml.start: "m1"`, `uml.finish: "m4"`; a `DestructionOccurrenceSpecification` `worker-destroyed` with `uml.covered: "worker"`; messages `m1` customer→service (seq 1, `synchCall`), `m2` service→worker (seq 2, `createMessage`), `m3` service→`worker-destroyed` (seq 3, `deleteMessage`), `m4` service→customer (seq 4, `reply`). View `sequence-view`. `required_plugins[].version` = the product version from root `pom.xml`.

- [ ] **Step 1: Write the failing end-to-end test.** In `CliBuildCommandTest`, mirror the existing sequence build tests' harness:
```java
  @Test
  void buildsASequenceViewWithAnExecutionSpecificationAndADestruction() throws Exception {
    // Regression: an execution bar used to be dumped at the canvas origin (12,12) and a
    // delete-message hard-failed the build (exit 2) with ROUTE_ENDPOINT_OFF_NODE_PERIMETER,
    // because neither the bar nor the destruction was ever anchored to its lifeline.
    var envelope = buildView("valid-uml-sequence-lifecycle.json", "sequence-view");

    assertThat(envelope.status()).isEqualTo("ok");
    assertThat(envelope.diagnostics()).isEmpty();
  }
```

- [ ] **Step 2: Add the source fixture; run the test → PASS** (it fails with `status: error` before Tasks 1-4).
Run (sandbox disabled): `./mvnw -pl cli -am test -Dtest=CliBuildCommandTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Generate the layout-result fixture** with the real engine: add the `FixtureMapping` entry, then `./scripts/regen-layout-fixtures.sh`. Confirm `git status --short -- fixtures/layout-result/` shows **ONLY the new file** — no existing fixture rewritten. If any existing fixture changed, STOP (byte-stability break).

- [ ] **Step 4: Confirm the sweeps accept it.** `./mvnw -pl core,contracts,cli -am test` → PASS (`LayoutQualityFixtureSweepTest` sweeps every layout-result: zero hard errors, quality metrics zero — this is where Task 4's exemption earns its keep).

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add fixtures/source/valid-uml-sequence-lifecycle.json fixtures/layout-result/uml-sequence-lifecycle.json cli/src/test/java/dev/dediren/cli/LayoutFixtureRegenerator.java cli/src/test/java/dev/dediren/cli/CliBuildCommandTest.java
git commit -m "test(cli): execution/destruction sequence source builds end to end (regression fixture)"
```

---

### Task 7: Render assertion — the bar and the ✕ are drawn on the stem

**Files:**
- Test: `engines/render/src/test/java/dev/dediren/plugins/render/` (add to / mirror the existing UML-sequence render tests — e.g. `SequenceSelfMessageHookTest` is a good model)
- Modify (ONLY if verification shows a defect): `engines/render/src/main/java/dev/dediren/plugins/render/node/uml/UmlSequenceRenderer.java`

**Expectation (verify, don't assume):** the renderer already draws executions/gates/destructions from `node.x/y/width/height` (`UmlSequenceModel` routes them; `UmlSequenceRenderer.renderExecutionSpecifications`/`renderGates` paint them). Now that layout finally places them, they should simply appear in the right spot with NO renderer change.

- [ ] **Step 1: Write the assertion.** Render `fixtures/layout-result/uml-sequence-lifecycle.json` and assert the `exec-service` rect is horizontally centred on the `service` lifeline stem (compare against the real `<line data-dediren-sequence-lifeline-stem="service">` x), and the `worker-destroyed` marker is centred on the `worker` stem.

- [ ] **Step 2: Run + eyeball a real render.** `./mvnw -pl engines/render,cli -am test`, then build the CLI and render `fixtures/source/valid-uml-sequence-lifecycle.json` with `fixtures/render-policy/uml-svg.json` and inspect the SVG: the bar must sit on the service stem, the ✕ on the worker stem, and the delete arrow must reach the ✕. Report what you observed.

- [ ] **Step 3: Fix the renderer ONLY if Step 2 showed a defect** (minimal fix; do not restructure).

- [ ] **Step 4: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add engines/render/  # explicit paths for what you actually touched
git commit -m "test(render): execution bar and destruction marker render on the lifeline stem"
```

---

### Task 8: Property-test coverage (close the hole for real)

**Files:**
- Modify: `cli/src/test/java/dev/dediren/cli/SequenceLayoutPropertyTest.java`

**Why:** the generator emits none of these node types — which is exactly why this shipped broken (same hole that hid the self-message defect). Extend it so a share of trials carry an `ExecutionSpecification` (with valid `covered`/`start`/`finish`) and a `DestructionOccurrenceSpecification` (with `covered`, targeted by a `deleteMessage`).

- [ ] **Step 1: Extend the generator.** Keep `@Property(tries = 300, seed = "1")`, deterministic jqwik `Arbitraries` only (no `Math.random`), models must stay VALID UML (real `Uml.validateSource` runs in the pipeline). Preserve ALL existing coverage (cross-lifeline messages, self-messages, combined fragments) — do not displace it. Extend `assertNoNodeRectsOverlap` to exempt `execution`/`destruction` roles (mirroring Task 4's `LayoutQuality` change and the existing `interaction` exemption — the bar sits on the stem by construction). Add the new shares to `Statistics.collect(...)`.

- [ ] **Step 2: Run.** `./mvnw -pl cli -am test -Dtest=SequenceLayoutPropertyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 300/300 with a non-trivial share of trials carrying an execution spec and a destruction; all three `SequenceInvariants` green.

**If a counterexample surfaces a REAL geometry break, STOP and use systematic-debugging** — that is the property test doing its job. Fix the defect + pin a repro; do NOT narrow the generator or weaken an assertion. Report it.

- [ ] **Step 3: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add cli/src/test/java/dev/dediren/cli/SequenceLayoutPropertyTest.java
git commit -m "test(cli): property test covers execution specs, destructions, and delete-messages"
```

---

### Task 9: Full gate + audits

**Files:** none (verification only; fold any SpotBugs suppression surfaced here into its own small commit, recorded in `docs/architecture-guidelines.md §12`).

- [ ] **Step 1: Full quality gate.** `./mvnw -Pquality verify` → BUILD SUCCESS, Spotless clean, SpotBugs 0.
- [ ] **Step 2: Distribution smoke.** `./mvnw -pl dist-tool -am verify -Pdist-smoke` → BUILD SUCCESS (the bundle ships fixtures; this branch adds one).
- [ ] **Step 3: Byte-stability.** `git diff --stat <branch-base>..HEAD -- fixtures/` must show exactly TWO files ADDED (the new source + layout-result) and ZERO modified.
- [ ] **Step 4: Acceptance oracle.** Build the CLI and run `fixtures/source/valid-uml-sequence-lifecycle.json` through `build` with `fixtures/render-policy/uml-svg.json`: expect **exit 0**, `status: ok`, an emitted `diagram.svg`. Report the exec bar's and destruction's coordinates from the layout-result and confirm they sit on their lifelines' stems.
- [ ] **Step 5: Audits** (per CLAUDE.md Audit Gates — ELK runtime row): `souroldgeezer-audit:test-quality-audit` Deep; `souroldgeezer-audit:devsecops-audit` Quick. Fix block findings; fix or explicitly accept warn/info.
- [ ] **Step 6: Final whole-branch review**, then integrate per `superpowers:finishing-a-development-branch`.

---

## Notes for the executor

- **The acceptance oracle is the repro.** Before this plan, the lifecycle source fails `build` with exit 2 and the activation bar sits at (12,12). After it, `status: ok` with the bar on the service stem. That transition is the whole point.
- **`ir` and `elk-layout` must stay notation-free.** `StemSpan` speaks of bands and members; no `uml.*` string or `ExecutionSpecification` literal may appear in either module.
- **Nothing gets weakened.** The delete-message terminates on the ✕'s LEFT EDGE precisely so the pre-existing perimeter hard-error check passes untouched. The only quality change is extending an existing, documented sequence-chrome exemption to two more roles — and Task 4 must prove it is scoped by keeping a genuine two-ordinary-node overlap counted.
- **Known limitation (document, don't fake):** nested/overlapping activation bars on one lifeline will be drawn on top of each other. The model can express it; this slice does not solve it. Note it in the spec's non-goals if you touch that area.
