# Plan B P5 — Typed Sequence Intent + Delete the elk Re-derivation — Implementation Plan

Status: complete — Plan B P1–P5 all shipped by 2026.07.15.

> Erratum 2026-07-15: jqwik was removed 2026-07-14 (7b520b0). Read "jqwik
> property test" as the seeded JUnit `@ParameterizedTest` sequence property
> suite that replaced it.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the stringly `uml.sequence.*` layout constraints with a typed two-level pipeline — sealed notation-owned `SequenceConstraint` (`semantics-uml`) lowered to a neutral `LayoutIntent` (`ir`) that `elk-layout` consumes — delete elk's 418-line `SequenceLayoutConstraints` UML re-derivation, and wire the `SequenceInvariants` into `validate-layout`. Geometry stays byte-identical.

**Architecture:** `semantics-uml` builds a sealed `SequenceConstraint` family and lowers it to neutral `LayoutIntent` records (`OrderedBand`, `AlignmentAxis`) placed on `SceneGraph.constraints` (retyped `List<LayoutConstraint>` → `List<LayoutIntent>`). A notation-free codec in `ir` serializes `LayoutIntent ↔ stringly LayoutConstraint` so the public `layout-request` wire stays self-sufficient (the standalone `layout` command reconstructs full geometry with no notation running — `project --target layout-request | layout ≡ build`). `elk-layout` decodes the wire constraints to typed `LayoutIntent` via that codec and drives a new neutral `LayoutIntentNormalizer` (the old geometry mechanics, retyped to read intents instead of magic strings). No `NormalizationPass`, no schema-id bump, no `LayoutEngine.layout` signature change.

**Tech Stack:** Java 21, Maven Wrapper, JUnit 5, AssertJ, jqwik (property tests), Eclipse ELK, google-java-format (Spotless), SpotBugs, ArchUnit (dist-tool).

## Global Constraints

- **Design of record:** `docs/superpowers/specs/2026-07-09-typed-ir-provenance-design.md` §Phasing/P5 + §"P5 design resolution (2026-07-10)". Live code + tests are truth when they disagree with a plan.
- **Geometry is byte-identical.** The neutral normalizer must reproduce the exact constants and algorithms of `SequenceLayoutConstraints`: column pitch `maxWidth + 96.0`, message step `24.0`, head gap `24.0`, fragment leading gap `46.0`, operand leading gap `68.0`, stem = head-box center (`x + width/2`), head band = `min(y)`, interaction frame = bbox of lifeline boxes + every message route point, message straightening to a 2-point horizontal segment, "trust ELK columns if distinct AND non-overlapping else rebuild evenly spaced". The `uml-sequence-*` `layout-result` fixtures MUST stay byte-identical; the jqwik property test (300 seeded models) MUST stay green.
- **ELK-first (CLAUDE.md):** no new custom placement/route geometry — only retype the inputs the existing mechanics read.
- **Dependency direction:** `ir` depends only on `contracts`; `engine-api` on `contracts` + `ir`; `elk-layout` and `semantics-uml` may depend on `ir` (ArchUnit already permits both; only the dep-table + `because`-strings lag). `elk-layout` must NOT import any `semantics` module (`elkLayoutDoesNotImportSemantics`).
- **The `46.0` / `68.0` fragment/operand gap values move OUT of elk and are owned by `semantics-uml`** (they are render-fragment-chrome-coupled; duplicated with a comment and guarded by the existing `engines/render` `SequenceFragmentAlignmentTest`, exactly as elk duplicated them from render today). elk becomes value-agnostic (reads `member.leadingGap`).
- **Wire `constraint` shape is fixed** (`schemas/layout-request.schema.json`): `{id:string, kind:string, subjects:string[]}`, `kind` free-form. The neutral codec encodes within this shape (no schema-id bump).
- **Format + gate before every commit that touches Java:** `./mvnw -Pquality spotless:apply`. The known sandbox gotcha: `./mvnw test` fails on read-only `/tmp` under the Claude Code sandbox (`@TempDir`) and `JsonSupportFuzzTest` fails only under the sandbox — run Maven with the sandbox disabled.
- **Module topology / dep changes require `-Pdist-smoke`** (P3 learning: `FIRST_PARTY_ARTIFACTS` + appassembler classpath issues are caught ONLY by dist-smoke, not `-Pquality verify`). No new modules are created in P5, but `core` and `semantics-uml` gain an `ir` compile dep, so run dist-smoke in Task 9.

---

### Task 1: `ir` — neutral `LayoutIntent` vocab + notation-free codec

**Files:**
- Create: `ir/src/main/java/dev/dediren/ir/Axis.java`
- Create: `ir/src/main/java/dev/dediren/ir/BandMember.java`
- Create: `ir/src/main/java/dev/dediren/ir/LayoutIntent.java`
- Create: `ir/src/main/java/dev/dediren/ir/LayoutIntentCodec.java`
- Test: `ir/src/test/java/dev/dediren/ir/LayoutIntentCodecTest.java`

**Interfaces:**
- Produces (consumed by Tasks 2, 3, 5):
  - `enum Axis { X, Y }`
  - `record BandMember(String id, double leadingGap)`
  - `sealed interface LayoutIntent permits OrderedBand, AlignmentAxis` with nested `record OrderedBand(Axis axis, List<BandMember> members) implements LayoutIntent` and `record AlignmentAxis(Axis axis, List<String> nodeIds) implements LayoutIntent`.
  - `LayoutIntentCodec.encode(String viewId, List<LayoutIntent>) -> List<LayoutConstraint>` and `LayoutIntentCodec.decode(List<LayoutConstraint>) -> List<LayoutIntent>`, satisfying `decode(encode(viewId, xs)).equals(xs)` for all `xs`.
- Consumes: `dev.dediren.contracts.layout.LayoutConstraint` (`{id,kind,subjects}`).

**Encoding (pin exactly — the round-trip must be lossless):**
- `OrderedBand(axis, members)` → `LayoutConstraint(viewId + ".ordered-band." + axisTag, "ordered-band:" + axisTag, members.map(m -> m.leadingGap()==0.0 ? m.id() : m.id()+"@"+encodeGap(m.leadingGap())))` where `axisTag` is `"x"`/`"y"` and `encodeGap` emits a plain decimal (e.g. `"46.0"`).
- `AlignmentAxis(axis, nodeIds)` → `LayoutConstraint(viewId + ".alignment-axis." + axisTag, "alignment-axis:" + axisTag, nodeIds)`.
- `decode` dispatches on the `kind` prefix (`ordered-band:` / `alignment-axis:`), parses the axis tag, and splits each subject on the last `"@"` (no `"@"` → `leadingGap = 0.0`). Unknown kinds are passed through untouched only if the list is otherwise empty — for P5 every constraint on the wire is a `LayoutIntent`, so `decode` may throw `IllegalArgumentException` on an unrecognized `kind` (defensive; a corrupt wire is an input error). The codec knows ONLY these neutral kinds and the `@` gap encoding — no `uml.sequence.*` strings, no `46`/`68` values.

- [ ] **Step 1: Write the failing round-trip test**

```java
package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.ir.LayoutIntent.AlignmentAxis;
import dev.dediren.ir.LayoutIntent.OrderedBand;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutIntentCodecTest {

  @Test
  void roundTripsOrderedBandWithPerMemberGapsAndAlignment() {
    List<LayoutIntent> intents =
        List.of(
            new OrderedBand(
                Axis.X, List.of(new BandMember("customer", 0.0), new BandMember("service", 0.0))),
            new AlignmentAxis(Axis.Y, List.of("customer", "service")),
            new OrderedBand(
                Axis.Y,
                List.of(
                    new BandMember("m1", 0.0),
                    new BandMember("m2", 46.0),
                    new BandMember("m3", 68.0))));

    List<LayoutConstraint> wire = LayoutIntentCodec.encode("sequence-view", intents);

    assertThat(wire)
        .extracting(LayoutConstraint::kind)
        .containsExactly("ordered-band:x", "alignment-axis:y", "ordered-band:y");
    assertThat(wire.get(2).subjects()).containsExactly("m1", "m2@46.0", "m3@68.0");
    assertThat(LayoutIntentCodec.decode(wire)).isEqualTo(intents);
  }

  @Test
  void decodeRejectsUnknownKind() {
    assertThatThrownBy(
            () -> LayoutIntentCodec.decode(List.of(new LayoutConstraint("x", "mystery", List.of()))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile** (`Axis`/`BandMember`/`LayoutIntent`/`LayoutIntentCodec` do not exist).

Run (sandbox disabled): `./mvnw -pl ir -am test -Dtest=LayoutIntentCodecTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure / symbol not found.

- [ ] **Step 3: Create `Axis`, `BandMember`, `LayoutIntent`, `LayoutIntentCodec`.**

`Axis.java`:
```java
package dev.dediren.ir;

/** Which axis an ordering or alignment intent applies to. */
public enum Axis {
  X,
  Y
}
```

`BandMember.java`:
```java
package dev.dediren.ir;

/** One member of an ordered band, with the leading gap reserved before it (0 when none). */
public record BandMember(String id, double leadingGap) {}
```

`LayoutIntent.java`:
```java
package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

/**
 * Neutral, notation-free layout intent carried on {@link SceneGraph}. A notation (e.g. {@code
 * semantics-uml}) lowers its sequence rules to these; {@code elk-layout} consumes them. Pruned to
 * the variants actually emitted: port-side is re-derived by elk from the lifeline {@code
 * OrderedBand(X)}, and interaction-frame enclosure is driven by the neutral scene {@code
 * role=="interaction"} — so no {@code PortSideHint}/{@code Encloses} variant is introduced.
 */
public sealed interface LayoutIntent permits LayoutIntent.OrderedBand, LayoutIntent.AlignmentAxis {

  /** Place {@code members} in order along {@code axis}; {@code leadingGap} reserves space before a member. */
  record OrderedBand(Axis axis, List<BandMember> members) implements LayoutIntent {
    public OrderedBand {
      members = listOrEmpty(members);
    }
  }

  /** Align {@code nodeIds} on a shared {@code axis} coordinate (lifeline heads share one top band). */
  record AlignmentAxis(Axis axis, List<String> nodeIds) implements LayoutIntent {
    public AlignmentAxis {
      nodeIds = listOrEmpty(nodeIds);
    }
  }
}
```

`LayoutIntentCodec.java` — implement `encode`/`decode` exactly per the Encoding block above (dispatch on `kind` prefix; split subjects on the last `@`; `Double.parseDouble` the gap; `Double.toString` on encode). Keep it self-contained in `ir`; import only `dev.dediren.contracts.layout.LayoutConstraint`.

- [ ] **Step 4: Run the test to green.**

Run (sandbox disabled): `./mvnw -pl ir -am test -Dtest=LayoutIntentCodecTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Format + commit.**

```bash
./mvnw -Pquality spotless:apply
git add ir/src/main/java/dev/dediren/ir/Axis.java ir/src/main/java/dev/dediren/ir/BandMember.java ir/src/main/java/dev/dediren/ir/LayoutIntent.java ir/src/main/java/dev/dediren/ir/LayoutIntentCodec.java ir/src/test/java/dev/dediren/ir/LayoutIntentCodecTest.java
git commit -m "feat(ir): neutral LayoutIntent vocab + notation-free wire codec (Plan B P5)"
```

---

### Task 2: `semantics-uml` — sealed `SequenceConstraint` + lowering to `LayoutIntent`

**Files:**
- Create: `semantics-uml/src/main/java/dev/dediren/semantics/uml/SequenceConstraint.java`
- Modify: `semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlSequenceConstraints.java` (add a typed producer + a lowering method beside the existing `of`; do NOT delete `of` yet)
- Modify: `semantics-uml/pom.xml` (add the `ir` dependency)
- Test: `semantics-uml/src/test/java/dev/dediren/semantics/uml/UmlSequenceConstraintsTest.java` (extend)

**Interfaces:**
- Produces (consumed by Tasks 3, 5):
  - `sealed interface SequenceConstraint permits LifelineOrder, MessageOrder, FragmentOpen, OperandOpen` with `record LifelineOrder(List<String> lifelineIds)`, `record MessageOrder(List<String> messageIds)`, `record FragmentOpen(List<String> messageIds)`, `record OperandOpen(List<String> messageIds)`.
  - `UmlSequenceConstraints.sequenceConstraints(SourceDocument, GenericGraphView) -> List<SequenceConstraint>` (the typed form of today's four producers; empty for non-`UML_SEQUENCE` views).
  - `UmlSequenceConstraints.lower(List<SequenceConstraint>) -> List<LayoutIntent>`.
- Consumes: `dev.dediren.ir.{LayoutIntent, Axis, BandMember}` (Task 1).

**Lowering (pin exactly — this preserves geometry):**
- `LifelineOrder(ids)` → `OrderedBand(Axis.X, ids.map(id -> new BandMember(id, 0.0)))` **and** `AlignmentAxis(Axis.Y, ids)`.
- `MessageOrder(ids)` + `FragmentOpen(fs)` + `OperandOpen(os)` → one `OrderedBand(Axis.Y, ids.map(id -> new BandMember(id, leadingGapFor(id, fs, os))))` where `leadingGapFor` returns `FRAGMENT_OPEN_GAP` (46.0) if `fs.contains(id)`, else `OPERAND_OPEN_GAP` (68.0) if `os.contains(id)`, else `0.0`. **Precedence: fragment-open wins over operand-open** — verify against `SequenceLayoutConstraints.normalizedMessageYSlots` (today the fragment-open gap is looked up first). If the current code applies whichever matches with operand taking precedence, match THAT; add a lowering test that pins the observed precedence.
- `FRAGMENT_OPEN_GAP = 46.0` and `OPERAND_OPEN_GAP = 68.0` are declared as constants in `UmlSequenceConstraints` with a comment: `// Kept in sync with engines/render FRAGMENT_VERTICAL_PADDING; guarded by render SequenceFragmentAlignmentTest.` (moved here from elk `SequenceLayoutConstraints`).

**Note (additive — do NOT touch `of`):** `of` (returning the stringly `uml.sequence.*` `List<LayoutConstraint>`) stays EXACTLY as-is and live — it is still the wire producer that elk's `SequenceLayoutConstraints` reads until the Task 5 cutover; changing its output now would break elk. Add `sequenceConstraints(...)` and `lower(...)` as NEW methods that share `of`'s source-scan helpers (`firstMessageOfOperand`/`operandOrder`/`umlMessageSequence`, the lifeline filter, the message sort) — extract those helpers if needed, but leave `of`'s output byte-identical. The new methods are additive and unused until Task 5.

- [ ] **Step 1: Write failing tests for the typed producer + lowering.**

```java
// in UmlSequenceConstraintsTest.java — add:
import dev.dediren.ir.Axis;
import dev.dediren.ir.BandMember;
import dev.dediren.ir.LayoutIntent;
import dev.dediren.ir.LayoutIntent.AlignmentAxis;
import dev.dediren.ir.LayoutIntent.OrderedBand;
import dev.dediren.semantics.uml.SequenceConstraint.FragmentOpen;
import dev.dediren.semantics.uml.SequenceConstraint.LifelineOrder;
import dev.dediren.semantics.uml.SequenceConstraint.MessageOrder;
import dev.dediren.semantics.uml.SequenceConstraint.OperandOpen;

@Test
void buildsTypedSequenceConstraintsInDeclaredOrder() {
  var constraints = UmlSequenceConstraints.sequenceConstraints(source(), sequenceView());
  assertThat(constraints)
      .containsExactly(
          new LifelineOrder(List.of("customer", "service")),
          new MessageOrder(List.of("m2", "m1", "m3")),
          new FragmentOpen(List.of("m1", "m5", "m7", "m9")),
          new OperandOpen(List.of("m3", "m11")));
}

@Test
void loweringPlacesLifelineColumnsHeadBandAndMessageGaps() {
  var intents =
      UmlSequenceConstraints.lower(
          List.of(
              new LifelineOrder(List.of("customer", "service")),
              new MessageOrder(List.of("m1", "m2", "m3")),
              new FragmentOpen(List.of("m2")),
              new OperandOpen(List.of("m3"))));

  assertThat(intents)
      .containsExactly(
          new OrderedBand(
              Axis.X, List.of(new BandMember("customer", 0.0), new BandMember("service", 0.0))),
          new AlignmentAxis(Axis.Y, List.of("customer", "service")),
          new OrderedBand(
              Axis.Y,
              List.of(
                  new BandMember("m1", 0.0),
                  new BandMember("m2", 46.0),
                  new BandMember("m3", 68.0))));
}
```
(Use the existing test's `source()`/`sequenceView()` fixtures; the exact subject lists mirror the current `projectsFragmentAndOperandOpenConstraintsForSequenceFragments` / `projectsLifelineAndMessageOrderConstraints` expectations. Confirm the fragment-vs-operand precedence for a message in both sets and pin it here.)

- [ ] **Step 2: Add the `ir` dep to `semantics-uml/pom.xml`** (beside the existing `contracts`/`engine-api`/`uml` deps):
```xml
<dependency>
  <groupId>dev.dediren</groupId>
  <artifactId>ir</artifactId>
  <version>${project.version}</version>
</dependency>
```
Run (sandbox disabled): `./mvnw -pl semantics-uml -am test -Dtest=UmlSequenceConstraintsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (symbols `sequenceConstraints`/`lower`/`SequenceConstraint` not found).

- [ ] **Step 3: Create `SequenceConstraint.java` and add `sequenceConstraints` + `lower` + gap constants to `UmlSequenceConstraints`.** Refactor the four `of` producers into `sequenceConstraints`; implement `lower` per the pin above; rewrite `of` to delegate through `lower` + `LayoutIntentCodec.encode`.

- [ ] **Step 4: Run to green.**

Run (sandbox disabled): `./mvnw -pl semantics-uml -am test -Dtest=UmlSequenceConstraintsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add semantics-uml/src/main/java/dev/dediren/semantics/uml/SequenceConstraint.java semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlSequenceConstraints.java semantics-uml/pom.xml semantics-uml/src/test/java/dev/dediren/semantics/uml/UmlSequenceConstraintsTest.java
git commit -m "feat(semantics-uml): sealed SequenceConstraint + lowering to neutral LayoutIntent (Plan B P5)"
```

---

### Task 3: `elk-layout` — new `LayoutIntentNormalizer` consuming typed intents (built beside the old class)

**Files:**
- Create: `engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizer.java`
- Test: `engines/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizerTest.java`

**Interfaces:**
- Produces (consumed by Task 5): a class mirroring `SequenceLayoutConstraints`' surface but reading `List<LayoutIntent>` instead of `LayoutRequest.constraints()`:
  - `static LayoutIntentNormalizer from(List<LayoutIntent> intents, Map<String,String> nodePointers, Map<String,String> edgePointers)`
  - `boolean active()` (true iff both a lifeline `OrderedBand(X)` and a message `OrderedBand(Y)` are present)
  - `List<LayoutNode> orderedNodes(List<LayoutNode>)`, `List<LayoutEdge> orderedEdges(List<LayoutEdge>)`
  - `PortSide sourcePortSide(LayoutEdge, PortSide fallback)`, `PortSide targetPortSide(LayoutEdge, PortSide fallback)` (re-derived from the lifeline `OrderedBand(X)` column index — no `PortSideHint` intent)
  - `LayoutResult normalize(LayoutResult)`
- Consumes: `dev.dediren.ir.{LayoutIntent, Axis, BandMember}` (already a compile dep of `elk-layout`).

**Behavior:** Port `SequenceLayoutConstraints` verbatim in geometry, changing only the INPUT source:
- Lifeline order + column X + head band ← the `OrderedBand(Axis.X, members)` id sequence + the `AlignmentAxis(Axis.Y, ids)` set (both derived from `LifelineOrder`). Column rebuild uses the SAME `distinctColumnXSlots`/`columnsAreNonOverlapping` logic and the `maxWidth + 96.0` pitch (keep `LIFELINE_COLUMN_GAP = 96.0` here — it is neutral band spacing, NOT a fragment/render constant).
- Message order + per-message leading gap ← the `OrderedBand(Axis.Y, members)`; each message's `member.leadingGap()` REPLACES the old `fragmentOpenIds`/`operandOpenIds` membership + hardcoded `46`/`68` lookup. Keep `MESSAGE_Y_STEP = 24.0`, `MESSAGE_HEAD_GAP = 24.0`, `MINIMUM_MESSAGE_Y_STEP = 1.0` here (neutral).
- Stem anchoring, message straightening, interaction enclosure, provenance re-threading (`nodePointers`/`edgePointers`) — identical to `SequenceLayoutConstraints` (lines 145-401). Interaction enclosure stays driven by `role()=="interaction"`.

**Do NOT wire it into `ElkLayoutEngine` in this task.** `SequenceLayoutConstraints` stays live and wired; this class is built and unit-tested in isolation.

- [ ] **Step 1: Write failing tests — port the two direct-construction tests to the new class.** Recreate `SequenceLifelineColumnOverlapTest`'s column-rebuild assertion and `ElkLayoutEngineTest.normalizesSequenceMessagesToCleanHorizontalSegments`'s stem-center assertion, but construct `LayoutIntentNormalizer.from(intents, ...)` with typed `OrderedBand`/`AlignmentAxis` intents instead of stringly `LayoutConstraint`s.

```java
package dev.dediren.plugins.elklayout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.ir.Axis;
import dev.dediren.ir.BandMember;
import dev.dediren.ir.LayoutIntent;
import dev.dediren.ir.LayoutIntent.AlignmentAxis;
import dev.dediren.ir.LayoutIntent.OrderedBand;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LayoutIntentNormalizerTest {

  @Test
  void rebuildsColumnsWhenElkPacksLifelinesCloserThanTheirWidth() {
    // mirror SequenceLifelineColumnOverlapTest fixtures: two lifelines at x=12,x=101 width=140
    LayoutResult result = overlappingTwoLifelineResult();
    List<LayoutIntent> intents =
        List.of(
            new OrderedBand(Axis.X, List.of(new BandMember("a", 0.0), new BandMember("b", 0.0))),
            new AlignmentAxis(Axis.Y, List.of("a", "b")),
            new OrderedBand(Axis.Y, List.of()));

    LayoutResult normalized =
        LayoutIntentNormalizer.from(intents, Map.of(), Map.of()).normalize(result);

    LaidOutNode a = node(normalized, "a");
    LaidOutNode b = node(normalized, "b");
    assertThat(b.x()).isGreaterThanOrEqualTo(a.x() + a.width());
  }

  @Test
  void straightensCrossLifelineMessageToStemCenters() {
    // mirror ElkLayoutEngineTest.normalizesSequenceMessagesToCleanHorizontalSegments:
    // lifelines at x=100 and x=520, width 140 -> stem centers 170 and 590
    LayoutResult result = twoLifelineMessageWithBendPoints();
    List<LayoutIntent> intents = twoLifelineIntentsWithOneMessage("msg");

    LayoutResult normalized =
        LayoutIntentNormalizer.from(intents, Map.of(), Map.of()).normalize(result);

    List<Point> points = edge(normalized, "msg").points();
    assertThat(points).hasSize(2);
    assertThat(points.get(0).x()).isEqualTo(170.0);
    assertThat(points.get(1).x()).isEqualTo(590.0);
    assertThat(points.get(0).y()).isEqualTo(points.get(1).y());
  }
  // ... helpers copied/adapted from ElkLayoutEngineTest fixtures ...
}
```

- [ ] **Step 2: Run and confirm failure** (class does not exist).

Run (sandbox disabled): `./mvnw -pl engines/elk-layout -am test -Dtest=LayoutIntentNormalizerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (symbol not found).

- [ ] **Step 3: Create `LayoutIntentNormalizer` by porting `SequenceLayoutConstraints`' geometry**, swapping the four stringly `from` reads (lines 62-81) for reads off `List<LayoutIntent>` and the per-message gap for `member.leadingGap()`. Keep every geometry constant/algorithm identical except `FRAGMENT_OPEN_GAP`/`OPERAND_OPEN_GAP` (now carried in `leadingGap`, so those two constants do NOT appear here).

- [ ] **Step 4: Run to green.**

Run (sandbox disabled): `./mvnw -pl engines/elk-layout -am test -Dtest=LayoutIntentNormalizerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizer.java engines/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutIntentNormalizerTest.java
git commit -m "feat(elk-layout): LayoutIntentNormalizer ports sequence geometry off typed intents (Plan B P5)"
```

---

### Task 4: Add `NotationSemantics.layoutIntents` alongside `layoutConstraints` (additive)

**Files:**
- Modify: `engine-api/src/main/java/dev/dediren/engine/NotationSemantics.java` (ADD `layoutIntents`; keep `layoutConstraints`)
- Modify: `semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlNotationSemantics.java`
- Modify: `semantics-graph/src/main/java/dev/dediren/semantics/graph/GraphNotationSemantics.java`
- Modify: `semantics-archimate/src/main/java/dev/dediren/semantics/archimate/ArchimateNotationSemantics.java`
- Test: `semantics-uml/src/test/java/dev/dediren/semantics/uml/UmlNotationSemanticsTest.java`

**Interfaces:**
- Produces: `List<LayoutIntent> NotationSemantics.layoutIntents(SourceDocument source, GenericGraphView view)` — a NEW SPI method ADDED beside the existing `List<LayoutConstraint> layoutConstraints(...)` (which stays and is still the live producer until Task 5). This is Parallel Change: both coexist so every module stays green; Task 5 removes the old method + its only caller in one step.
- `UmlNotationSemantics.layoutIntents` returns `UmlSequenceConstraints.lower(UmlSequenceConstraints.sequenceConstraints(source, view))`. `GraphNotationSemantics.layoutIntents` and `ArchimateNotationSemantics.layoutIntents` return `List.of()`.

- [ ] **Step 1: Write the failing delegation test** — add `layoutIntentsDelegatesToLowering` asserting `notation.layoutIntents(source, view)` equals `UmlSequenceConstraints.lower(UmlSequenceConstraints.sequenceConstraints(source, view))` and is empty for a class view. Leave the existing `layoutConstraintsDelegatesToUmlSequenceConstraints` test untouched (the old method still exists).

- [ ] **Step 2: Add `layoutIntents` to the SPI + 3 impls** (do NOT remove `layoutConstraints`). Add javadoc noting `layoutIntents` is the P5 typed successor and `layoutConstraints` is removed at the cutover.

Run (sandbox disabled): `./mvnw -pl semantics-uml,semantics-graph,semantics-archimate -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL then, after Step 2, both old and new delegation tests PASS.

- [ ] **Step 3: Run to green.**
Run (sandbox disabled): `./mvnw -pl semantics-uml -am test -Dtest=UmlNotationSemanticsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 4: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add engine-api/src/main/java/dev/dediren/engine/NotationSemantics.java semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlNotationSemantics.java semantics-graph/src/main/java/dev/dediren/semantics/graph/GraphNotationSemantics.java semantics-archimate/src/main/java/dev/dediren/semantics/archimate/ArchimateNotationSemantics.java semantics-uml/src/test/java/dev/dediren/semantics/uml/UmlNotationSemanticsTest.java
git commit -m "feat(engine-api): add NotationSemantics.layoutIntents alongside layoutConstraints (Plan B P5)"
```

---

### Task 5: The atomic cutover — typed `SceneGraph.constraints`, wire codec, elk consumes intents, delete the old re-derivation

This is the connected-component cutover: `SceneGraph.constraints` retype, the wire codec, and elk's swap MUST land together (the moment the wire goes neutral, `SequenceLayoutConstraints` reading `uml.sequence.*` is dead). Everything it calls was built + unit-tested in Tasks 1-4, so this task is re-wiring + deletion, guarded by the integration gates.

**Files:**
- Modify: `ir/src/main/java/dev/dediren/ir/SceneGraph.java` (`constraints`: `List<LayoutConstraint>` → `List<LayoutIntent>`)
- Modify: `ir/src/main/java/dev/dediren/ir/LayoutRequestMapper.java` (`toRequest` encodes via `LayoutIntentCodec.encode(graph.viewId(), graph.constraints())`; `toSceneGraph` decodes via `LayoutIntentCodec.decode(request.constraints())`)
- Modify: `engine-api/src/main/java/dev/dediren/engine/NotationSemantics.java` (REMOVE the now-superseded `layoutConstraints`; `layoutIntents` from Task 4 stays)
- Modify: `semantics-uml`/`semantics-graph`/`semantics-archimate` `*NotationSemantics.java` (remove the `layoutConstraints` impl)
- Modify: `semantics-graph/src/main/java/dev/dediren/semantics/graph/SceneProjection.java:188` (`notation.layoutConstraints(...)` → `notation.layoutIntents(...)`)
- Modify: `engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java` (lines 78-98, 199, 615-632: swap `SequenceLayoutConstraints.from(request,…)` for `LayoutIntentNormalizer.from(LayoutIntentCodec.decode(request.constraints()), nodePointers, edgePointers)`; keep `layout(LayoutRequest)` signature)
- Delete: `engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/SequenceLayoutConstraints.java`
- Modify: `semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlSequenceConstraints.java` (delete the old `of` returning `List<LayoutConstraint>` and its now-unused `LayoutConstraint` import)
- Modify: `engines/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java` (the one direct-construction test at line 289 was ported in Task 3 → delete it here or point it at `LayoutIntentNormalizer`; the ~10 geometry-outcome tests driving `new ElkLayoutEngine().layout(request)` stay UNCHANGED and must pass — they now flow through the codec + normalizer)
- Delete: `engines/elk-layout/src/test/java/dev/dediren/plugins/elklayout/SequenceLifelineColumnOverlapTest.java` (superseded by `LayoutIntentNormalizerTest` from Task 3)
- Modify: any test/fixture asserting the stringly `uml.sequence.*` wire kinds (grep below)

**Pre-flight grep (run first; every hit is either updated or confirmed unaffected):**
```bash
grep -rn "uml\.sequence\.\(lifeline-order\|message-order\|fragment-open\|operand-open\)" --include=*.java --include=*.json .
grep -rln "SequenceLayoutConstraints" --include=*.java .
```
Known hits from scouting: `ElkLayoutEngineTest` (line 289 + the `ignoresPartialSequenceConstraintsForNonSequenceGraphs` parameterized test at 309-346 asserts the exact `uml.sequence.*` strings — rewrite it to build typed intents / neutral wire), `ElkLayoutProvenanceTest:122` (comment only), `cli/MainTest.java:57`, `SequenceLayoutPropertyTest` (builds a `SourceDocument`, not the wire — unaffected), and any `fixtures/layout-request/*sequence*` representations (neutral kinds now). The `uml-sequence-*` `layout-result` fixtures carry NO constraints → byte-identical, no change.

- [ ] **Step 1: Run the pre-flight greps** and list every file to touch. Confirm `SequenceLayoutConstraints` has exactly one production caller (`ElkLayoutEngine`).

- [ ] **Step 2: Make the connected edit** (SceneGraph, LayoutRequestMapper, SceneProjection, ElkLayoutEngine swap, deletions). `ElkLayoutEngine.layoutFlat` gains, at the top, `List<LayoutIntent> intents = LayoutIntentCodec.decode(request.constraints());` and builds `LayoutIntentNormalizer.from(intents, nodePointers, edgePointers)`; the `sequenceEdgeEndpointSides` helper takes the `LayoutIntentNormalizer`.

- [ ] **Step 3: Update the affected tests/fixtures** from the Step 1 list (rewrite `ignoresPartialSequenceConstraintsForNonSequenceGraphs` to typed intents; repoint any wire-kind fixtures to neutral kinds; delete the superseded direct-construction tests).

- [ ] **Step 4: Run the geometry-critical gates and confirm byte-stability.**

Run (sandbox disabled):
```
./mvnw -pl engines/elk-layout -am test
./mvnw -pl core,cli -am test          # includes SequenceLayoutPropertyTest (300 trials) + fixture sweeps
```
Expected: PASS, with the `uml-sequence-*` `layout-result` fixtures unchanged (byte-identical geometry). If any sequence geometry assertion or the property test fails, STOP and use systematic-debugging — a byte-stability break means the normalizer/lowering diverged from `SequenceLayoutConstraints`; do not re-baseline fixtures to hide it.

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add -- ir/ engines/elk-layout/ semantics-graph/ semantics-uml/ cli/  # explicit paths only; review git diff first
git commit -m "refactor(elk-layout): consume typed LayoutIntent, delete SequenceLayoutConstraints re-derivation (Plan B P5)"
```

---

### Task 6: Wire `SequenceInvariants` into `validate-layout` (`core` gains `ir`)

**Files:**
- Modify: `core/pom.xml` (add the `ir` dependency)
- Modify: `core/src/main/java/dev/dediren/core/commands/CoreCommands.java:181-197` (`validateLayoutResult`)
- Test: `core/src/test/java/dev/dediren/core/commands/CoreCommandsTest.java` and/or `cli/src/test/java/dev/dediren/cli/CliLayoutRenderCommandTest.java`

**Interfaces:**
- Consumes: `dev.dediren.ir.LaidOutSceneMapper.toScene(LayoutResult)` and `dev.dediren.ir.quality.SequenceInvariants` (3 checks) + `dev.dediren.ir.quality.InvariantViolation`.
- Behavior: in `validateLayoutResult`, after the existing `LayoutQuality.validateLayoutDiagnostics(result)` hard-error lane, map `result → LaidOutScene` via `LaidOutSceneMapper.toScene`, run the three `SequenceInvariants` checks, and fold any `InvariantViolation`s into `Diagnostic`s (carrying `violation.origin()` as `sourcePointer`) in the SAME hard-error lane (a violated geometric invariant is an input error, consistent with the existing `INPUT_ERROR` verdict). Non-sequence layouts produce no violations (all three return empty when there is no lifeline/interaction geometry), so this must NOT change the verdict for existing non-sequence fixtures.

**Guard:** `CliLayoutRenderCommandTest.validateLayoutAcceptsSequenceLifelineMessageEndpoints` (feeds `uml-sequence-validatable.json`, expects `status=="ok"`) MUST stay green — the invariant wiring must not reject a valid sequence layout. Add a NEW negative test feeding a layout-result that violates one invariant (e.g. a message whose endpoints are off the lifeline axis) and assert the new diagnostic code appears.

- [ ] **Step 1: Write the failing tests** — (a) a valid sequence `LayoutResult` still yields `ok` (regression guard); (b) a hand-built `LayoutResult` with an off-axis message endpoint yields an `INPUT_ERROR` verdict carrying the invariant diagnostic + its `sourcePointer`.

- [ ] **Step 2: Add `ir` to `core/pom.xml`.**
Run (sandbox disabled): `./mvnw -pl core -am test -Dtest=CoreCommandsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (new assertions unmet).

- [ ] **Step 3: Implement the wire-in** in `validateLayoutResult` (map + run `SequenceInvariants` + fold violations to `Diagnostic`s). Choose one diagnostic code for invariant violations (e.g. reuse the closest existing `DEDIREN_LAYOUT_*` code or add one constant); record it in the envelope contract if new.

- [ ] **Step 4: Run to green.**
Run (sandbox disabled): `./mvnw -pl core,cli -am test`
Expected: PASS (including the existing validate-layout acceptance tests).

- [ ] **Step 5: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add core/pom.xml core/src/main/java/dev/dediren/core/commands/CoreCommands.java core/src/test/java/dev/dediren/core/commands/CoreCommandsTest.java cli/src/test/java/dev/dediren/cli/CliLayoutRenderCommandTest.java
git commit -m "feat(core): validate-layout enforces the sequence invariants on the typed scene (Plan B P5)"
```

---

### Task 7: Extend the jqwik generator to combined fragments (close W3)

**Files:**
- Modify: `cli/src/test/java/dev/dediren/cli/SequenceLayoutPropertyTest.java`

**Interfaces:**
- Consumes: the Task 5 pipeline (project → layout) end to end.
- Extends `buildSourceJson` (lines 140-214) + `validSequenceModels` (lines 82-127) to sometimes emit a `CombinedFragment` node with 1-2 `InteractionOperand` children (each with `uml.order` + `uml.fragments` member lists referencing a subset of the generated messages), so the fragment/operand-open gap path (now carried through `OrderedBand(Y).leadingGap`) is exercised under the 300-trial property test. Keep source≠target messages, unique strictly-increasing `uml.sequence`, and the interaction frame. W1 (ExecutionSpecification/Destruction/create-delete) stays OUT of scope — note it in the generator javadoc as a tracked follow-up.

**Guard:** all three `SequenceInvariants` + `assertNoNodeRectsOverlap` must stay `.isEmpty()`/pass across 300 seeded trials WITH fragments present. The interaction-frame-encloses invariant now genuinely exercises fragment-bearing scenes.

- [ ] **Step 1: Extend the generator** to include the optional combined-fragment shape; update the generator javadoc (W3 closed, W1 deferred).

- [ ] **Step 2: Run the property test.**
Run (sandbox disabled): `./mvnw -pl cli -am test -Dtest=SequenceLayoutPropertyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (300/300). If a fragment counterexample surfaces a real geometry break, STOP and systematic-debug (that is the property test doing its job) — a genuine defect gets a fix + a pinned repro, not a generator narrowing.

- [ ] **Step 3: Format + commit.**
```bash
./mvnw -Pquality spotless:apply
git add cli/src/test/java/dev/dediren/cli/SequenceLayoutPropertyTest.java
git commit -m "test(cli): property test covers combined-fragment sequence geometry (Plan B P5, W3)"
```

---

### Task 8: Move-together docs + build surfaces

**Files:**
- Modify: `docs/architecture-guidelines.md` (§2 dep-table rows `semantics-uml` and `core` gain `ir`; §12 debt register — the new `ir` `LayoutIntent`/`OrderedBand`/`BandMember` records join the existing `ir` `EI_EXPOSE_REP` note if SpotBugs flags them)
- Modify: `spotbugs-exclude.xml` (add the new `ir` `LayoutIntent`/`OrderedBand` records to the existing `ir` `EI_EXPOSE_REP` suppression block ONLY if SpotBugs flags them under `-Pquality verify`)
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java` (the `elkLayoutDoesNotImportSemantics` `because`-string at ~306-308: change "stringly LayoutConstraints over contracts" → "typed LayoutIntent over ir"; verify the `semantics-uml`/`core` → `ir` edges are already permitted by the forbidden lists — no rule body change expected)

**Interfaces:** none (docs + test-metadata only).

- [ ] **Step 1: Update the dep-table rows and the `because`-string.** Confirm no ArchUnit *rule* body needs to change (run the ArchUnit test).
Run (sandbox disabled): `./mvnw -pl dist-tool -am test -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 2: Commit.**
```bash
git add docs/architecture-guidelines.md dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java
# add spotbugs-exclude.xml only if Task 9 shows SpotBugs flags the new records
git commit -m "docs+test(arch): record semantics-uml/core->ir edges and the typed-intent seam (Plan B P5)"
```

---

### Task 9: Full-reactor verification + audits

**Files:** none (verification only; fold any `spotbugs-exclude.xml` addition surfaced here into Task 8's commit or a small follow-up commit).

- [ ] **Step 1: Full quality gate.**
Run (sandbox disabled): `./mvnw -Pquality verify`
Expected: PASS (format, SpotBugs, ~all tests). If SpotBugs flags the new `ir` records with `EI_EXPOSE_REP`, add them to the existing `ir` suppression block in `spotbugs-exclude.xml` and record in `docs/architecture-guidelines.md §12`, then re-run.

- [ ] **Step 2: Distribution smoke** (mandatory — `core`/`semantics-uml` gained an `ir` compile dep; P3 showed dep/topology changes are caught only here).
Run (sandbox disabled): `./mvnw -pl dist-tool -am verify -Pdist-smoke`
Expected: PASS.

- [ ] **Step 3: Confirm geometry byte-stability end to end.** Verify `git status` shows NO changes to `fixtures/layout-result/uml-sequence-*.json` (geometry unchanged). Confirm the `--emit layout-request.json` for a sequence view now carries neutral `ordered-band:*` / `alignment-axis:*` kinds and that `project --target layout-request | layout` reproduces the same `layout-result` as `build` for a fragment-bearing source (the wire-self-sufficiency property this design turns on).

- [ ] **Step 4: Audits (per CLAUDE.md Audit Gates — "Engine runtime" + "Vertical slice" rows).**
  - `souroldgeezer-audit:test-quality-audit` — Deep (the geometry-critical tests, the ported normalizer/lowering/codec tests, the property-test generator extension, the invariant wire-in tests).
  - `souroldgeezer-audit:devsecops-audit` — Quick (the two new `ir` compile edges, no new external deps, no new process boundary).
  Fix block findings; fix or explicitly accept warn/info; rerun affected checks.

- [ ] **Step 5: Final whole-branch review** via `superpowers:requesting-code-review` (opus), then integrate per `finishing-a-development-branch`.

---

## Notes for the executor

- **The one atomic task is Task 5.** Tasks 1-4 make it safe by building + unit-testing the codec, lowering, typed producer, and normalizer in isolation first; Task 5 is re-wiring + deletion guarded by the property test + byte-identical fixtures. If the reactor won't compile between Tasks 4 and 5, treat them as one commit.
- **Byte-stability is the acceptance oracle.** The `uml-sequence-*` `layout-result` fixtures and the 300-trial property test are the proof the neutral pipeline reproduces `SequenceLayoutConstraints` exactly. A fixture diff is a red flag, not a re-baseline opportunity.
- **Fragment/operand gap precedence** (fragment-open vs operand-open for a message in both sets) must be read off the CURRENT `SequenceLayoutConstraints.normalizedMessageYSlots` and pinned in the Task 2 lowering test — do not assume.
- **No `NormalizationPass`, no schema-id bump, no `LayoutEngine.layout` signature change** — all three were considered and rejected in the design resolution (wire self-sufficiency + minimal geometry-code movement).
- **Release:** P5 does not change a public schema id. The still-outstanding P1 `v1→v2` breaking-schema release (pom is `2026.07.14`; no bump/tag yet) is a separate, unaddressed follow-on governed by `release-policy`.
