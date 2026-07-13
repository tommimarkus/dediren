# Architecture Remediation (Fable review) — Implementation Record

> **Status: COMPLETE.** Merged to `main` as `341d72f` (26 commits) and released as
> **2026.07.15** (tag `v2026.07.15`). This document is implementation history, not a task
> list. Where it disagrees with the code, the code is right.

**Goal:** Act on a whole-repo Java architecture review — waste, redundancy, unused code,
unnecessary constructs, boilerplate, abstraction quality, gaps, inconsistencies.

**How the review was produced:** 10 independent reviewers (Fable, max effort) over
architectural slices — contracts/ir/engine-api; core/cli; render; elk-layout; both export
engines; notation + semantics; build/tooling; schemas/fixtures — plus two repo-wide lenses
(dead code; abstraction/boilerplate). Every high/medium finding was then handed to an
adversarial skeptic instructed to refute it by re-reading the cited code. **31 findings
survived, 4 were killed.**

---

## The forces (root causes, not a flat list)

The review did not find 40 unrelated problems. It found a handful of structural forces, each
throwing off many symptoms:

1. **The engine wall forces copy-paste.** Engines may not depend on each other (correct rule),
   and the chartered shared homes were never extended to hold what two engines both need. Both
   HIGH findings came from this, and both had receipts: *the same defect had already been fixed
   twice.*
2. **Monolith-cutover residue.** The subprocess plugin protocol was deleted; its plumbing was
   left compiled into the product.
3. **The typed IR stopped at the seam.** P4's "adapt at boundary" was deliberate and correct;
   the payoff was collected, but the tax became permanent with no trigger to end it.
4. **Stringly vocabularies where the type system was available.** Uniformly silent failure: a
   typo compiles, validates, and just stops matching.
5. **The published contract promised more than the code enforced.**
6. **Docs and build config drifted behind the code.**

---

## What shipped

**Duplication removed at its source**
- One shared `xmllint` runner for both export engines (`schema-cache`), which also closed a pipe-drain
  **deadlock** and an unbounded wait. The same drain bug had a *third* copy in the schema fetcher.
- One marker emitter for both renderers — the arrowhead defect had been fixed once per copy
  (`bc8936f`, then `bec4fc8`).
- Sequence paint resolved through `StyleResolver` (byte-identical, proven by rendering the same
  fixture before/after and diffing the SVG bytes).
- `f1` (8 copies) and `opacity` (4) consolidated into `Svg`; the semantics `failure()` factory lifted
  to `engine-api`.

**Correctness and contract**
- `page`/`margin` are schema-**required** and were never validated: a missing `margin` was an
  unstructured NPE crash, a missing `page` was *silently accepted*. Both now diagnose.
- Edge and sequence labels are measured with the per-glyph metric, not the flat `0.56` constant the
  code's own comment condemns. That bug had already been fixed and released for *node* labels.
- `routing.profile` retired — schema'd, typed, validated, and read by no code at all.
- Node `role` constrained to an enum; layout-request id charsets aligned with `model.schema` (which
  also removes the `ordered-band` `id@gap` ambiguity at the root); the wire grammar published.
- 42 published `DEDIREN_*` wire codes migrated from raw literals to `DiagnosticCode`, **guarded**.

**Tests that can now fail**
- `RenderGoldenTest` — 16 byte-compared golden SVGs, the repo's first **geometry oracle**. The suite
  asserted structure and never geometry, which is how the label bug shipped *and* why its fix was
  invisible. Verified by reintroducing the bug: 3 goldens fail.
- `FixtureConformanceSweepTest` — all 57 fixtures against their family schema. It caught a
  schema-invalid fixture on `main` during integration.
- Guards for the XMI tolerance branch, schema congruence, and diagnostic-code ownership.

**Dead code and drift**
- 268 lines of cutover residue (incl. 7 `getenv` overloads that bypassed the no-getenv guard); elk's
  unreachable 61-line group synthesis; the inert ArchiMate "curated" endpoint table.
- Dependencies declare what they use (from `dependency:analyze`, not inspection).
- Guidelines corrected: Jackson 3 (not 2), coverage aggregate restored, `cli` ArchUnit gap closed.

---

## Findings rejected on evidence

These are recorded because a review's value is only as good as its filtering, and because each
of these looks like an obvious cleanup that a future reader will want to "fix".

1. **elk's port-count node sizing is NOT a reinvention of ELK's `NODE_SIZE_CONSTRAINTS`.** §7 says
   trial the official option first, so it was trialled: `minimumSizeWithPorts()` enforces port fit at
   *every* count and inflated a typical **80px node to 161px** to seat three ports. The custom math
   deliberately leaves nodes at their hinted size until a side exceeds three. That is size *intent*
   ELK cannot express, and it is already pinned by
   `ElkLayoutEngineTest.threeShortSidePortsKeepTheDefaultNodeSize`. Recorded in §12 with the
   measurement.
2. **`cli`'s `ir` dependency cannot move to test scope**, even though `cli/src/main` imports none of
   it. A direct declaration overrides the transitive one, so test scope silently dropped `ir` from the
   launcher's resolved runtime set and the packaged bundle failed with
   `NoClassDefFoundError: dev/dediren/ir/SceneNode`. It is `runtime` scope, and the pom says why.
   **Only `-Pdist-smoke` caught this**; the unit suite could not.
3. **`rectpacking` and `xbase.lib` are not unused**, though `dependency:analyze` says they are. ELK
   resolves the packing algorithm reflectively from an id string; no bytecode reference exists. Both
   now carry comments so the next analyzer run does not get them deleted.
4. **Sequence diagrams are not invisible.** An earlier draft of this work claimed they had no rendered
   output; that was wrong. `CliLayoutRenderCommandTest` renders one from the real engine, and
   `RenderGalleryTest` excludes sequence *deliberately* (its geometry depends on the live ELK
   normalizer). The real gap was the geometry oracle, which now exists.

---

## The schema-id rule (decided here, recorded in §4)

Retiring `routing.profile` and constraining `role` are **breaking narrowings**, and the ids were
deliberately **not** bumped. The version string is a schema `const`, so bumping an id does not
*signal* incompatibility — it *creates* it, for every document of that family, including the ones
that never used what changed. A narrowing that can only invalidate documents whose content had no
semantics forces no working document to change.

> The question is not "did the schema change?" but **"does a document that worked yesterday have to
> change today?"** If yes, bump. If no, release-note it.

---

## Remaining work

None blocking. `docs/architecture-guidelines.md` §12 carries the registered debt with triggers: the
typed-IR double representation, the orphaned `plugin-manifest`/`runtime-capability` pair, the
`dev.dediren.plugins.*` package names, and the two runtime-only ELK deps.

## Verification

Every commit gated with `./mvnw -Pquality verify`; the dependency and release work additionally with
`./mvnw -pl dist-tool -am verify -Pdist-smoke`, which is the only lane that exercises the packaged
artifact — and the only one that caught the `ir` scope error.
