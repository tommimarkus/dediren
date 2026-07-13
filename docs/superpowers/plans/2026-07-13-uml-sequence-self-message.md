# UML Sequence Self-Message Geometry — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make UML sequence diagrams containing a self-message (self-call) build successfully, by giving self-messages real stem-anchored geometry instead of leaving them on ELK's meaningless raw route.

**Architecture:** `LayoutIntentNormalizer` already computes every other piece of sequence geometry post-ELK (columns, head band, y-lattice, stem anchoring, frame enclosure). Self-messages are the only message kind still falling through to ELK's route — which is the bug. Replace that fall-through with a four-point hook anchored on the lifeline stem, and reserve the hook's height in the y-lattice so the next message clears it. Neutral geometry owned by `elk-layout`; no contract, wire, or schema change.

**Tech Stack:** Java 21, Maven Wrapper, JUnit 5 + AssertJ, jqwik (property tests), Eclipse ELK 0.11.0 (layered), google-java-format (Spotless), SpotBugs.

## Global Constraints

- **Spec of record:** `docs/superpowers/specs/2026-07-13-uml-sequence-self-message-design.md`. Live code + tests are truth when they disagree with a plan.
- **Exact geometry.** A self-message (source lifeline == target lifeline) becomes exactly four points:
  `(stemX, y)`, `(stemX + 40.0, y)`, `(stemX + 40.0, y + 24.0)`, `(stemX, y + 24.0)`
  where `stemX = node.x() + node.width()/2.0` (the existing `stemX` helper).
  New constants in `LayoutIntentNormalizer`: `SELF_MESSAGE_LOOP_WIDTH = 40.0`, `SELF_MESSAGE_LOOP_HEIGHT = 24.0`.
- **Y-lattice.** The slot *after* a self-message must additionally clear `SELF_MESSAGE_LOOP_HEIGHT` (the hook's lower leg), on top of the normal `MESSAGE_Y_STEP`.
- **Neutral constants owned by `elk-layout`**, exactly like `LIFELINE_COLUMN_GAP = 96.0` and `MESSAGE_Y_STEP = 24.0`. **No `LayoutIntent` variant, no wire/codec change, no schema-id bump, no new diagnostic code.** The notation emits no new data.
- **BYTE-STABILITY GUARD:** no existing fixture contains a self-message, so **every current `fixtures/layout-result/*.json` must stay byte-identical**. A diff there means the change leaked into cross-lifeline geometry — debug it, do NOT re-baseline.
- **ELK-first (CLAUDE.md):** satisfied — ELK 0.11.0 publishes no sequence algorithm (`org.eclipse.elk.alg.sequence` does not exist; probed), and a sequence diagram's geometry is dictated by semantics, not optimized by a layout algorithm. We duplicate no ELK capability.
- **Maven under the sandbox:** `./mvnw` tests fail under the Claude Code sandbox (read-only `/tmp` `@TempDir`; the fuzz test's self-attach). Run every `./mvnw` with the Bash tool's sandbox DISABLED (`dangerouslyDisableSandbox: true`). These are environment failures, not code failures.
- **Before every Java commit:** `./mvnw -Pquality spotless:apply`. Explicit-path staging only — never `git add -A` (other untracked files exist). Commits are unsigned.
- New source fixtures must carry `required_plugins[].version` == the product version `2026.07.14`.

### Why the fix satisfies every existing check (do not re-litigate these; verify they stay green)

- `LayoutQuality.endpointAccepted` → passes via its `onLifelineAxis` branch (both endpoints on stem centre-x).
- `SequenceInvariants.messageEndpointsOnLifelineAxis` → same reason.
- `SequenceInvariants.messageYStrictlyIncreasing` → the representative y is the FIRST route point (`p0.y` = the slot); slots stay strictly increasing.
- `SequenceInvariants.interactionFrameEnclosesLifelines` → with a 140-wide head box, `stemX + 40` stays inside the lifeline's own x-extent, and the frame spans the lifeline boxes, so the hook is enclosed.
- `LayoutQuality.selfLoopEscapesNode` (`core/.../LayoutQuality.java:286`) → requires a self-loop point outside the node box by ≥ 4.0 in x **or y**. The hook sits at `headBottom + 24` (well below the lifeline head box), so it escapes in **y**. Passes.

---

### Task 1: Self-message hook geometry + y-lattice reservation

**Files:**
- Modify: `engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizer.java`
- Test: `engines/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizerTest.java`

**Interfaces:**
- Consumes: the existing private helpers `stemX(LaidOutNode)`, `pointsAtY(List<Point>, double)`, the field `lifelineIndexById`, and the constants `MESSAGE_HEAD_GAP = 24.0`, `MESSAGE_Y_STEP = 24.0`.
- Produces (used by every later task): a self-message renders as the exact four-point hook above; the message after a self-message is pushed down by an extra `SELF_MESSAGE_LOOP_HEIGHT`.

- [ ] **Step 1: Write the failing tests.**

Add to `LayoutIntentNormalizerTest`. Build a two-lifeline `LayoutResult` where lifeline `a` is at `x=0,width=140` (so `stemX(a) = 70.0`) and `b` at `x=236,width=140`, both with `y=0,height=48` (so `headBottom = 48.0`), and three ordered messages: `m1` (a→b), `m2` (b→b, the self-message), `m3` (b→a). Use the same direct `LaidOutNode`/`LaidOutEdge`/`LayoutResult` construction the existing tests in this file use, and typed intents `OrderedBand(Axis.X, [a,b])` + `OrderedBand(Axis.Y, [m1,m2,m3])` (all `leadingGap` 0.0).

```java
  @Test
  void selfMessageBecomesStemAnchoredHook() {
    LayoutResult normalized =
        LayoutIntentNormalizer.from(selfMessageIntents(), Map.of(), Map.of())
            .normalize(selfMessageResult());

    // m2 is b->b; stemX(b) = 236 + 140/2 = 306.0
    // slot y for m2 = headBottom(48) + MESSAGE_HEAD_GAP(24) + MESSAGE_Y_STEP(24) = 96.0
    List<Point> hook = edge(normalized, "m2").points();
    assertThat(hook)
        .containsExactly(
            new Point(306.0, 96.0),
            new Point(346.0, 96.0),
            new Point(346.0, 120.0),
            new Point(306.0, 120.0));
    // both endpoints sit on the stem -> satisfies the lifeline-axis invariant
    assertThat(hook.get(0).x()).isEqualTo(306.0);
    assertThat(hook.get(hook.size() - 1).x()).isEqualTo(306.0);
  }

  @Test
  void messageAfterSelfMessageClearsTheHook() {
    LayoutResult normalized =
        LayoutIntentNormalizer.from(selfMessageIntents(), Map.of(), Map.of())
            .normalize(selfMessageResult());

    double m2Top = edge(normalized, "m2").points().get(0).y(); // 96.0
    double m3Y = edge(normalized, "m3").points().get(0).y();
    // m3 must clear the hook's lower leg (m2Top + LOOP_HEIGHT = 120.0), not just MESSAGE_Y_STEP:
    // m3 = m2Top + MESSAGE_Y_STEP(24) + SELF_MESSAGE_LOOP_HEIGHT(24) = 144.0
    assertThat(m3Y).isEqualTo(144.0);
    assertThat(m3Y).isGreaterThanOrEqualTo(m2Top + 24.0);
  }
```

- [ ] **Step 2: Run them and confirm RED.**

Run (sandbox disabled): `./mvnw -pl engines/elk-layout -am test -Dtest=LayoutIntentNormalizerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `m2` currently comes back as the flattened ELK route (not the 4-point hook), and `m3` lands at `120.0` (no loop-height reservation).

- [ ] **Step 3: Implement.**

Add the constants beside the existing ones in `LayoutIntentNormalizer`:

```java
  // A self-message hooks off the lifeline stem and returns to it (the conventional UML self-call).
  // Neutral band geometry, owned here like LIFELINE_COLUMN_GAP / MESSAGE_Y_STEP.
  private static final double SELF_MESSAGE_LOOP_WIDTH = 40.0;
  private static final double SELF_MESSAGE_LOOP_HEIGHT = 24.0;
```

Add a helper:

```java
  private boolean isSelfMessage(LaidOutEdge edge) {
    Integer sourceIndex = lifelineIndexById.get(edge.source());
    Integer targetIndex = lifelineIndexById.get(edge.target());
    return sourceIndex != null && sourceIndex.equals(targetIndex);
  }
```

Replace the self-branch in `normalizedMessagePoints` (it currently lumps the self case in with the dangling case and returns `pointsAtY`). A dangling/unknown endpoint still falls back to `pointsAtY`; a *self* message now gets the hook:

```java
  private List<Point> normalizedMessagePoints(
      LaidOutEdge edge, Map<String, LaidOutNode> normalizedNodesById, double y) {
    LaidOutNode source = normalizedNodesById.get(edge.source());
    LaidOutNode target = normalizedNodesById.get(edge.target());
    Integer sourceIndex = lifelineIndexById.get(edge.source());
    Integer targetIndex = lifelineIndexById.get(edge.target());
    if (source == null || target == null || sourceIndex == null || targetIndex == null) {
      return pointsAtY(edge.points(), y);
    }
    if (sourceIndex.equals(targetIndex)) {
      double stem = stemX(source);
      return List.of(
          new Point(stem, y),
          new Point(stem + SELF_MESSAGE_LOOP_WIDTH, y),
          new Point(stem + SELF_MESSAGE_LOOP_WIDTH, y + SELF_MESSAGE_LOOP_HEIGHT),
          new Point(stem, y + SELF_MESSAGE_LOOP_HEIGHT));
    }
    return List.of(new Point(stemX(source), y), new Point(stemX(target), y));
  }
```

Reserve the loop height in `normalizedMessageYSlots` — the extra clearance applies to the slot AFTER a self-message:

```java
      double y = headBottom + MESSAGE_HEAD_GAP;
      for (int index = 0; index < orderedMessages.size(); index++) {
        if (index > 0) {
          y += MESSAGE_Y_STEP;
          if (isSelfMessage(orderedMessages.get(index - 1))) {
            y += SELF_MESSAGE_LOOP_HEIGHT; // clear the previous hook's lower leg
          }
        }
        String id = orderedMessages.get(index).id();
        y += messageLeadingGapById.getOrDefault(id, 0.0);
        ySlots.add(y);
      }
```

- [ ] **Step 4: Run to GREEN + prove byte-stability.**

Run (sandbox disabled):
```
./mvnw -pl engines/elk-layout -am test
git status --short -- fixtures/
```
Expected: elk-layout suite PASSES (the new tests plus every existing sequence geometry test unchanged), and `git status -- fixtures/` is **EMPTY** — no existing `layout-result` fixture changed (none contains a self-message). If a fixture changed, STOP: the change leaked into cross-lifeline geometry. Debug it; do not re-baseline.

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizer.java engines/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizerTest.java
git commit -m "fix(elk-layout): anchor self-messages to the lifeline stem as a hook (was: unroutable)"
```

---

### Task 2: Self-message fixture + end-to-end build test

**Files:**
- Create: `fixtures/source/valid-uml-sequence-self-message.json`
- Create (generated, do NOT hand-author): `fixtures/layout-result/uml-sequence-self-message.json`
- Test: `cli/src/test/java/dev/dediren/cli/CliBuildCommandTest.java`

**Interfaces:**
- Consumes: the Task 1 hook geometry.
- Produces: a checked-in source + real-engine layout-result fixture that later tasks (and the fixture sweeps) exercise.

**The source fixture** is a three-lifeline UML sequence view whose middle message is a self-call. Model it on `fixtures/source/valid-uml-sequence-basic.json` (same shape: `model_schema_version: model.schema.v1`, `required_plugins` with `generic-graph` at version `2026.07.14`, an `Interaction` node, `Lifeline` nodes each with `properties.uml.interaction`, `Message` relationships each with `properties.uml.{interaction,sequence,message_sort}`, and a `plugins.generic-graph.views[]` entry of `kind: uml-sequence` listing the node + relationship ids). Give it: lifelines `customer` + `service`; messages `m1` `customer→service` (`sequence: 1`, `synchCall`), **`m2` `service→service`** (`sequence: 2`, `synchCall`, label e.g. `validateOrder`), `m3` `service→customer` (`sequence: 3`, `reply`). View id `sequence-view`.

- [ ] **Step 1: Write the failing end-to-end test.**

Add to `CliBuildCommandTest`, following the existing build-test pattern in that file (it already builds `valid-uml-sequence-*` sources through the real engine and asserts on the envelope):

```java
  @Test
  void buildsASequenceViewContainingASelfMessage() throws Exception {
    // Regression: a self-call (source lifeline == target lifeline) is legal UML but used to fail
    // layout validation with ROUTE_ENDPOINT_OFF_NODE_PERIMETER + the lifeline-axis invariant,
    // because the normalizer left self-messages on ELK's raw route.
    var envelope = buildView("valid-uml-sequence-self-message.json", "sequence-view");

    assertThat(envelope.status()).isEqualTo("ok");
    assertThat(envelope.diagnostics()).isEmpty();
  }
```
(Use whatever helper/assertion shape `CliBuildCommandTest` already uses to run a build and read the envelope — mirror the neighbouring sequence build test rather than inventing a new harness.)

- [ ] **Step 2: Add the source fixture, run the test, confirm it now passes.**

Run (sandbox disabled): `./mvnw -pl cli -am test -Dtest=CliBuildCommandTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (it would have FAILED with `status: error` before Task 1 — that is the bug this fixes).

- [ ] **Step 3: Generate the layout-result fixture with the real engine.**

Use the existing opt-in regenerator (do NOT hand-author the layout-result):
```
./scripts/regen-layout-fixtures.sh
```
(or the equivalent `-Ddediren.regen-layout-fixtures=true` run it wraps). Confirm it emits `fixtures/layout-result/uml-sequence-self-message.json` and that **no other layout-result fixture changed** (`git status --short -- fixtures/layout-result/` should show only the new file). If it rewrites others, STOP — that is a byte-stability break.

- [ ] **Step 4: Confirm the new fixture passes the sweeps.**

Run (sandbox disabled): `./mvnw -pl core,contracts,cli -am test`
Expected: PASS — `LayoutQualityFixtureSweepTest` (sweeps every `fixtures/layout-result/*.json`: zero hard errors, quality metrics zero) and the contracts schema/round-trip tests accept the new fixture.

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add fixtures/source/valid-uml-sequence-self-message.json fixtures/layout-result/uml-sequence-self-message.json cli/src/test/java/dev/dediren/cli/CliBuildCommandTest.java
git commit -m "test(cli): self-message sequence source builds end to end (regression fixture)"
```

---

### Task 3: Render the hook + verify the arrowhead

**Files:**
- Test: `engines/render/src/test/java/dev/dediren/plugins/render/` (add to the existing UML-sequence render test — find it by grepping for the test that renders a `uml-sequence-*` layout-result)
- Modify (ONLY if the verification below shows a defect): `engines/render/src/main/java/dev/dediren/plugins/render/node/uml/UmlSequenceRenderer.java`

**Interfaces:**
- Consumes: the Task 2 `fixtures/layout-result/uml-sequence-self-message.json`.

**Expectation (verify, don't assume):** `UmlSequenceRenderer.edgePath` already emits `pathData(edge.points())`, so a four-point hook draws as a path with no code change. The arrowhead is an SVG `marker-end` with `refX="9"` and `orient="auto"` (`UmlSequenceRenderer.edgeMarker`, ~line 436) — `refX=9` anchors the marker's **tip at the endpoint** and `orient="auto"` rotates it along the final segment, which for the hook points **west**, back at the stem. So the arrowhead tip should land exactly on the stem. If a real render shows it floating or clipped, fix `edgeMarker`/`edgePath` minimally — do not restructure the renderer.

- [ ] **Step 1: Write the render assertion.**

Add a test that renders `fixtures/layout-result/uml-sequence-self-message.json` and asserts the self-message path contains the hook's four points (i.e. the emitted `d=` for `data-dediren-sequence-message="m2"` walks stem → stem+40 → down → back to stem), and that a `marker-end` is attached to it.

- [ ] **Step 2: Run it and eyeball a real render.**

Run (sandbox disabled): `./mvnw -pl engines/render,cli -am test`
Then produce a real SVG and LOOK at the self-message: build the CLI (`./mvnw -pl cli -am package -DskipTests`) and run
`cli/target/appassembler/bin/cli build --input fixtures/source/valid-uml-sequence-self-message.json --out <tmpdir> --views sequence-view --render-policy <a uml render policy from fixtures/render-policy/>`
Open/inspect the emitted SVG's `m2` path + marker. Confirm the arrowhead tip sits ON the lifeline stem (not floating in space, not clipped behind the stem). Report what you observed.

- [ ] **Step 3: Fix the renderer ONLY if Step 2 showed a defect.** If the arrowhead is correct, change no production code — the assertion from Step 1 is the deliverable.

- [ ] **Step 4: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add engines/render/  # explicit paths for the files you actually touched
git commit -m "test(render): self-message hook renders with the arrowhead on the lifeline stem"
```

---

### Task 4: Property-test coverage for self-messages (closes the W1 slice that hid this bug)

**Files:**
- Modify: `cli/src/test/java/dev/dediren/cli/SequenceLayoutPropertyTest.java`

**Interfaces:**
- Consumes: the Task 1 geometry, end to end through the real `SemanticsRouterEngine.projectScene` + `ElkEngine.layout`.

**Why this matters:** the generator currently guarantees `source != target` for every message (a shift-mod trick), which is *exactly why this bug survived*. Extending it to self-messages means a regression here can never again ship silently.

- [ ] **Step 1: Extend the generator** so a message may have `source == target` (a self-message) on some fraction of trials — keep the existing `@Property(tries = 300, seed = "1")` and the deterministic jqwik `Arbitraries` (no `Math.random`). Keep `uml.sequence` values unique and strictly increasing. Update the generator javadoc: self-messages are now covered; ExecutionSpecification / DestructionOccurrence / create-delete geometry (the rest of W1) remain out of scope. Add the self-message share to the existing `Statistics.collect(...)` so the coverage is observable.

- [ ] **Step 2: Run the property test.**

Run (sandbox disabled): `./mvnw -pl cli -am test -Dtest=SequenceLayoutPropertyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 300/300 PASS with a non-trivial share of trials carrying a self-message, and all three `SequenceInvariants` + `assertNoNodeRectsOverlap` still empty/green.

If a self-message counterexample surfaces a REAL geometry break, STOP and use systematic-debugging — that is the property test doing its job. A genuine defect gets a fix + a pinned repro, NOT a narrowed generator. Report it.

- [ ] **Step 3: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add cli/src/test/java/dev/dediren/cli/SequenceLayoutPropertyTest.java
git commit -m "test(cli): property test covers self-message sequence geometry (closes W1 slice)"
```

---

### Task 5: Full gate + audits

**Files:** none (verification only).

- [ ] **Step 1: Full quality gate.**
Run (sandbox disabled): `./mvnw -Pquality verify`
Expected: BUILD SUCCESS across all modules, Spotless clean, SpotBugs 0 findings.

- [ ] **Step 2: Distribution smoke.**
Run (sandbox disabled): `./mvnw -pl dist-tool -am verify -Pdist-smoke`
Expected: BUILD SUCCESS. (No module topology change here, but the fixture set changed and the bundle ships fixtures.)

- [ ] **Step 3: Byte-stability confirmation.**
`git status --short -- fixtures/` on a clean tree must show NOTHING (the new fixtures are committed; no pre-existing `layout-result` fixture was rewritten). Re-confirm with `git log --stat` that Task 2's commit added exactly one new layout-result file and modified none.

- [ ] **Step 4: Audits** (per CLAUDE.md Audit Gates — ELK runtime row).
  - `souroldgeezer-audit:test-quality-audit` — Deep (the bounded ELK/sequence test suite: hook geometry unit tests, the new fixture + sweeps, the property-generator extension, the render assertion).
  - `souroldgeezer-audit:devsecops-audit` — Quick (implementation diff; expect no dependency/boundary change at all).
  Fix block findings; fix or explicitly accept warn/info; rerun affected checks.

- [ ] **Step 5: Final whole-branch review** via `superpowers:requesting-code-review`, then integrate per `superpowers:finishing-a-development-branch`.

---

## Notes for the executor

- **The acceptance oracle is the repro.** Before Task 1, `build` on a self-message source fails with exit 2 (`ROUTE_ENDPOINT_OFF_NODE_PERIMETER` ×2 + `SEQUENCE_INVARIANT_VIOLATED` ×3). After Task 1+2 it must return `status: ok`. That transition is the whole point.
- **Do not touch the neutral `LayoutIntent` vocabulary, the codec, the wire, or any schema.** The notation emits no new data for self-messages; this is pure elk-side geometry.
- **Existing `layout-result` fixtures are the regression guard** — they must stay byte-identical throughout. No existing fixture contains a self-message, so any diff means cross-lifeline geometry regressed.
- The `y + SELF_MESSAGE_LOOP_HEIGHT` clearance applies to the message *after* a self-message. Two consecutive self-messages must therefore each get their own clearance — the loop in `normalizedMessageYSlots` handles this naturally since it checks `orderedMessages.get(index - 1)` each iteration.
