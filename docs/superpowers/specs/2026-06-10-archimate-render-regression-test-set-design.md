# ArchiMate Render Regression Test Set — Design

Date: 2026-06-10
Status: approved design, pending implementation plan

## Problem

Since the Rust-to-Java/ELK migration, graph rendering quality has regressed in
ways the current test suite does not catch, especially for ArchiMate output.
Existing coverage is strong on envelope/structure (~60 ELK geometry tests, ~60
SVG DOM tests, 9 `LayoutQuality` unit tests) but has no label-clipping or
label-collision assertions, no golden renders, thin ArchiMate fixtures (a
3-node basic model and one 6-node pipeline), and no fixtures that put
junctions or deep Grouping nesting through layout. Issue #13 also showed the
quality gate itself can regress into false positives.

In-scope failure modes (all observed or at risk since the migration):

1. Node/label overlaps and clipping (node-node overlap, label clipped by node
   bounds, label colliding with edges or other labels).
2. Bad edge routes (edges through unrelated nodes, doglegs/detours, endpoints
   detached from the node perimeter, crossing clutter).
3. Container/group problems (Grouping/nesting sizing, members escaping group
   bounds, group labels colliding with members).
4. ArchiMate notation errors (wrong decorators/icons, junction rendering,
   relationship line styles/markers off-spec).

## Non-goals

- Visual/screenshot diffing or human-review galleries.
- Changing ELK routing behavior. The ELK-first rule stands: when a check
  fails because of ELK output, the remedy is graph structure, ports,
  hierarchy, or options — or an explicit accepted-characteristic decision —
  never post-ELK geometry patching.
- Promoting render-level checks into a product command in this iteration
  (recorded as a future promotion path only).

## Approach

Pipeline-layered test set: each layer checks its own artifact, plus a thin
end-to-end CLI smoke slice per new curated fixture. Oracles, in priority
order: property-based geometry checks (primary), ArchiMate notation
conformance against the spec, and a small normalized golden-SVG backstop.

## 1. Fixtures

Three new fixture families:

### Curated ArchiMate viewpoints

Hand-authored source models (plus projected layout-request, layout-result,
and render-metadata fixtures, following existing `fixtures/` conventions):

- `valid-archimate-layered.json` — ~30 elements across business, application,
  and technology layers; realistic relationship mix (serving, realization,
  assignment, access, flow); one Grouping per layer.
- `valid-archimate-cooperation-junctions.json` — application cooperation
  viewpoint with AndJunction/OrJunction on flow paths. Junctions currently
  appear only in render-side tests and never pass through layout.
- `valid-archimate-capability-nesting.json` — capability map with 3-level
  Grouping nesting.

These double as documentation examples.

### Generated stress models

A deterministic generator in `plugins/elk-layout` test scope, parameterized
by node count, fan-out, nesting depth, and junction density. Seeds are fixed
so failures reproduce. Output is generated at test runtime, not checked into
`fixtures/`.

### Regression repros

Intake convention `fixtures/source/regressions/<id>-<slug>.json`, each with a
description field referencing the originating bug. Initial entries come from
the user-provided repro models (reduced first); every failure the new checks
discover in curated/stress runs is also pinned here.

## 2. Layout-level checks (product surface)

New backend-neutral checks in `core` `LayoutQuality`, surfaced through
`validate-layout` as new diagnostic codes:

- **Label-space check** — where the layout request declares node label
  text/size, the computed node box must meet the declared minimum. Catches
  "label cannot fit" before fonts exist.
- **Junction geometry** — junction nodes must lie on the routes of their
  incident edges (within tolerance) and must not overlap regular nodes.
- **Deep containment** — group containment verified transitively to arbitrary
  nesting depth, including group-label band reservation: members must not
  occupy the group's title band.
- **Edge-crossing count** — surfaced as an info-level metric, not an error
  (crossings are sometimes unavoidable); tests and downstream agents assert
  per-fixture thresholds.

Contract discipline ("files that move together"): diagnostics/schema updates,
fixtures, `LayoutQualityTest`, CLI tests, and README/agent-usage notes move
in the same change.

## 3. Render-level SVG geometry harness (test scope)

A test-side library in `plugins/svg-render` tests: `SvgGeometry` plus
`RenderQualityAssertions`. It parses rendered SVG via the existing
`data-dediren-*` attributes and computes shape/text geometry.

- Text bounds are estimated from font-size/family/anchor attributes with a
  conservative width factor; only clear violations fail, to avoid
  font-metric flakiness across JDKs.
- Assertions: node label fits inside its node shape; edge labels do not
  overlap node shapes or other edge labels; group members render inside the
  group rect; decorator icons stay in their corner without colliding with
  label text; junction dots sit on edge paths.
- The harness itself gets unit tests against hand-built tiny SVGs, since new
  assertion code can lie in both directions.

Future promotion path (not in this iteration): once stable, these checks may
become a product render-side quality capability.

## 4. ArchiMate notation conformance suite (test scope)

Table-driven tests in `plugins/svg-render` tests, one row per ArchiMate
element/relationship/junction type, with expectations derived from the
ArchiMate 3.2 notation spec rather than from current renderer output:

- Element rows: expected shape (`data-dediren-node-shape`), decorator id,
  decorator position.
- Relationship rows: expected stroke pattern (solid/dashed/dotted) and
  marker-start/end (open arrow, closed arrow, filled/hollow diamond, circle).
- Junction rows: AndJunction filled circle, OrJunction hollow circle.

The expectation table is checked in (JSON or Java) with a spec-section
reference per row so wrong expectations are reviewable. Renderer gaps become
explicitly-skipped rows with a tracking note — visible debt, not silent
absence. Use `souroldgeezer-architecture:architecture-design` during
implementation to validate the table against ArchiMate semantics.

## 5. Golden backstop

Three to five goldens over the curated viewpoint fixtures: rendered SVG,
normalized (stable attribute order, coordinates rounded to 0.1,
non-deterministic content stripped), committed under
`fixtures/render/golden/`. One test compares normalized output to golden;
its failure message includes the diff and the regeneration command (a
`-Dgolden.update=true`-style switch). Goldens stay few by design — they are
the tripwire for changes the property checks did not model. Generated
`*.svg` stays gitignored; the golden directory is a deliberate tracked
exception.

## 6. Execution and CI

- Default `./mvnw test`: curated-fixture property tests (layout and render),
  notation conformance, golden backstop, new `LayoutQuality` unit tests, and
  one CLI end-to-end smoke case per new curated fixture (in `cli` tests,
  mirroring `CliLayoutRenderCommandTest`).
- New opt-in profile `-Prender-stress` (modeled on `dist-smoke`/pitest):
  generated stress sweep in `plugins/elk-layout` — generator output → ELK →
  strengthened `LayoutQuality` must be clean or within declared per-shape
  thresholds. Wired into scheduled/release CI, not per-push.
- CLAUDE.md verification lanes gain a one-line entry for the new profile.

## 7. Error handling and testing-the-tests

- **False-positive discipline** (issue #13 lesson): every new check ships
  with a positive case (fires on bad geometry) and a negative suite (silent
  on known-good layouts of every diagram kind: ArchiMate structural, UML
  class, sequence, state machine, use case, component, deployment). A check
  that cannot pass the negative suite gets a tolerance or a diagram-kind
  scoping rule, not a weakened assertion.
- **Severity model**: hard geometric impossibilities (member outside group,
  endpoint detached, label provably clipped) are errors; aesthetic metrics
  (crossing count, detour ratio) are warnings/info with per-fixture
  thresholds.
- **Failure ergonomics**: failed geometry assertions write the offending
  SVG/layout JSON to a report directory (reusing the opt-in
  `ElkLayoutRenderArtifacts` pattern) and name the offending element ids in
  the failure message.
- **Audit gates**: deep `test-quality-audit` on the new suites; quick
  `devsecops-audit` on contract and profile changes.

## Success criteria

- The four in-scope failure modes each have at least one automated check
  that fails on a seeded bad example and passes on all known-good fixtures.
- ArchiMate fixtures exercise junctions through layout, 3-level Grouping
  nesting, and a ~30-element layered viewpoint.
- `validate-layout` reports the new diagnostics with no false positives on
  the full known-good fixture set.
- Default-build runtime impact stays modest; stress sweep runs only under
  `-Prender-stress`.
- User-provided repro models are reduced, pinned under
  `fixtures/source/regressions/`, and covered by the checks.
