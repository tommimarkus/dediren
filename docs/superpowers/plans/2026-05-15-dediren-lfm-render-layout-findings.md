# LFM Render Layout Findings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development or superpowers:executing-plans to
> execute this plan task-by-task. Use superpowers:test-driven-development for
> code changes, superpowers:writing-skills for the skill-maintenance task, and
> souroldgeezer-architecture:architecture-design for visual/render review.

**Goal:** Turn the two LFM render review passes into durable fixes: remove
renderer-created edge artifacts, improve grouped real-ELK route quality where
the engine is responsible, split or tune the LFM views where the model is too
broad for one readable diagram, and harden the architecture-review skill so a
future first pass does not miss edge defects.

**Architecture:** Keep source graphs semantic and presentation-free. Do not add
authored coordinates to `docs/architecture/lfm.dediren/model.json`. Express
layout intent through groups, view membership, route hints, ports, hierarchy,
and ELK options. Keep renderer-only behavior in `svg-render`; keep route
geometry and layout-quality behavior in `elk-layout`; keep architecture-review
process guidance in the architecture skill.

**Layout and Routing Authority:** ELK Layered remains the authority for node,
group, port, and hierarchy placement. Libavoid remains the authority for routing
over generated geometry. Route metrics in this plan are regression evidence and
diagnostics; they must not become an independent router or authored geometry
source. Implementation may tune ELK/libavoid graph structure, options, ports,
hierarchy, endpoint merge eligibility, and constraints, then validate the
generated result.

**Evidence Reviewed:**

- Region pass PNGs:
  `/home/souroldgeezer/repos/lfm/.cache/layout-issue-pngs/*.annotated.png`
- Edge pass PNGs:
  `/home/souroldgeezer/repos/lfm/.cache/layout-issue-pngs/*.edge-pass2.png`
- Original renders:
  `/home/souroldgeezer/repos/lfm/docs/architecture/renders/*.svg`
- LFM package settings:
  `/home/souroldgeezer/repos/lfm/docs/architecture/lfm.dediren/`

Generated review artifacts are intentionally ignored under `.cache/`; do not
stage them unless a later task explicitly asks for tracked visual examples.

---

## Evaluated Findings

The findings fall into four ownership buckets.

| Priority | Bucket | Evidence | Owner |
| --- | --- | --- | --- |
| P0 | Repeated line-jump and backtracking artifacts | `AC-E1`, `AC-E2`, `RS-E1`, `RS-E2`; repeated `Q` arcs and duplicated path fragments near endpoints | `dediren-plugin-svg-render` |
| P1 | Long shared lanes and route congestion | `AC-E3`, `AC-E4`, `AC-E5`, `RS-E3`, `RS-E4`, `RS-E5`, `TU-E1` through `TU-E5`, `PR-E1` through `PR-E4` | `dediren-plugin-elk-layout`, with some LFM view split work |
| P2 | View scope too broad for render-ready output | application cooperation, technology usage, production release migration, run signup service realization | LFM package view membership/groups |
| P2 | Review process missed edge defects in first pass | first annotated PNG pass had broad regions but not exact edge-path callouts | `architecture-design` skill |

Do not treat all findings as a single layout bug. Some edges are visually ugly
because `svg-render` duplicated line jumps after layout. Some are valid ELK
routes that become unreadable because the view mixes too many concerns or
forces many relationships through the same source/target channel.

## Current Judgment

1. The P0 renderer artifacts are blocking. A valid layout should never be
   rendered as repeated jump arcs such as `render-cdn-serves-app` looping at
   `Lfm.App` or `run-service-accesses-runs` repeating the same vertical jump
   before climbing to the data group.
2. The P1 route issues should become measurable route-quality regressions.
   Existing tests check valid routing, excessive detours, and some fanout
   behavior, but the LFM evidence shows missing coverage for duplicate jump
   insertion, long shared spines, same-lane bus length, and edge-crossing
   density after rendering.
3. The LFM application, technology, and production views are too broad for a
   single render-ready diagram. Even after engine fixes, these views should be
   split or filtered so each diagram has one audience-level concern.
4. The architecture review workflow needs a mandatory edge pass. The first pass
   detected density and framing, but it did not inspect exact SVG paths deeply
   enough to catch several edge-specific defects.

## Execution Notes

- 2026-05-15: Implemented the P0 `svg-render` line-jump de-duplication slice.
  The fix keeps ELK/libavoid geometry untouched and collapses duplicate visual
  jump candidates at SVG render precision before path emission.
- 2026-05-15: Bumped the product/plugin version from `0.9.0` to `0.9.1`
  because the slice changes shipped `svg-render` behavior.
- 2026-05-15: Existing real-ELK render tests pass with the built Java helper.
  No ELK/libavoid tuning was made in this slice; Task 3 remains the next owner
  if fresh LFM renders still show long-bus or fanout route-quality defects after
  the renderer artifact is removed.
- 2026-05-15: Generated read-only LFM smoke PNGs from this branch under
  `/tmp/dediren-lfm-render-smoke/png/`. The sampled P0 edges no longer contain
  repeated `Q` motifs, while the broader long-bus/readability findings remain
  visible for later ELK/view-scope work.
- 2026-05-15: Task 4 and Task 5 remain out-of-repo work for the LFM and skills
  repositories.

---

### Task 1: Freeze LFM Render Evidence As Reproducible Metrics

**Files:**

- Create or modify: `crates/dediren-cli/tests/real_elk_render.rs`
- Modify: `crates/dediren-cli/tests/common/mod.rs` only if reusable helpers are
  needed
- Optional ignored output: `.test-output/renders/lfm-findings/`

- [ ] **Step 1: Add a route-quality helper for SVG/LayoutResult paths**

Create test helpers that can calculate these metrics from a layout result or
rendered SVG path data:

- route length and direct Manhattan length
- corner count
- repeated point/segment count
- duplicate line-jump arc count in SVG `d`
- close-parallel segment count
- perpendicular crossing count
- max single horizontal/vertical segment length

Keep this helper test-local unless the implementation needs product diagnostics.
If product diagnostics are added to command envelopes or layout results, stop
and apply the version/README rules.

- [ ] **Step 2: Add fixture cases from the LFM findings**

Add minimal synthetic fixtures/tests that reproduce the P0 cases without
depending on the full LFM repository:

- `render-cdn-serves-app` style edge with many prior crossings near a target
  endpoint
- `run-service-accesses-runs` style edge with repeated crossings at nearly the
  same coordinate before a long vertical segment
- paired Blizzard API edges with duplicate crossing points on a shared segment
- `run-service-serves-signup` style source fanout that currently produces a
  duplicate line jump before the target branch

Expected RED: the tests fail because duplicate `Q` arcs or repeated route
fragments are present.

- [ ] **Step 3: Add a real-ELK representative route-quality case**

Add one grouped real-ELK case that mimics the LFM service realization shape:

- one API/service group
- one UI/process group
- one data group above or offset from the API group
- four same-source realization/serving edges
- two same-source data access edges

Assert route-quality facts rather than exact coordinates:

- no route has repeated interior jump points
- no route has duplicate consecutive bend motifs
- fanout routes do not share a source lane for more than a bounded distance
- data-access routes do not climb through an unrelated long central lane when a
  better ELK/libavoid-generated route is available

- [ ] **Step 4: Verify the evidence lane**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test -p dediren --test real_elk_render -- --ignored --test-threads=1
```

Expected: newly added RED tests fail before implementation, with failure
messages that name the route metric and edge id.

---

### Task 2: Fix SVG Line-Jump Duplication And Backtracking

**Files:**

- Modify: `crates/dediren-plugin-svg-render/src/main.rs`
- Modify: `crates/dediren-plugin-svg-render/tests/svg_render_plugin/viewbox_routes.rs`

- [x] **Step 1: Deduplicate crossing points before path emission**

In `line_jump_points`, normalize jump candidates by quantized coordinate and
segment distance before sorting. Multiple earlier segments can currently
produce the same crossing point, and `edge_path_data` appends every candidate.
The renderer should emit one jump for one visible crossing point.

- [x] **Step 2: Reject jumps that would make the path reverse over itself**

Before `append_line_jump`, check that the jump entry and exit remain monotonic
between the current segment start and end. Skip or merge a jump when the
inserted `Q` arc would be followed by a line back to a point already reached.
Implemented for the observed P0 class by removing duplicate visual crossing
points before any `Q` arc is emitted; no replacement route coordinates are
authored.

- [x] **Step 3: Compact rendered path data after jump insertion**

After line-jump and detour insertion, compact equivalent adjacent line/curve
motifs. This is a renderer-level guard against repeated `Q` arcs such as the
ones visible in `AC-E1`, `RS-E1`, and `RS-E2`.
Implemented as pre-emission candidate compaction instead of a post-render SVG
rewrite.

- [x] **Step 4: Preserve legitimate line jumps**

Keep existing positive coverage:

- one later crossing edge still gets one visible line jump
- shared endpoint junction hints still suppress line jumps between merged
  source/target junction edges
- unhinted endpoint overlap still gets a detour

- [x] **Step 5: Verify the renderer lane**

Run:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
cargo test -p dediren --test cli_render
```

Expected: no duplicate `Q` arcs for the LFM-derived cases; existing line-jump
behavior remains intentional.

---

### Task 3: Improve Grouped Route Quality For Long Buses And Fanout

**Files:**

- Modify: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
- Modify: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/ElkLayoutEngineTest.java`
- Modify: `crates/dediren-cli/tests/real_elk_render.rs`

- [ ] **Step 1: Add tests for long shared lanes**

Use LFM-derived patterns to prove the route-quality gap:

- same-target serving fan-in into an application component should not form a
  long right-side spine like `AC-E5`
- same-source realization fanout should not stack into one tall dashed trunk
  like `AC-E4`
- same-source service-to-process fanout should not share one long bus before
  crossing group boundaries like `RS-E3`
- observability/data-plane edges should not route across most of the canvas
  when source and target groups can be brought closer or routed through a
  shorter channel

Expected RED: current route metrics exceed thresholds even though layout
validation passes.

- [ ] **Step 2: Add regression metrics without replacing ELK/libavoid routing**

Add route-quality metrics that fail tests when generated ELK/libavoid output has:

- long close-parallel runs with unrelated edges
- long shared source/target lanes before the route branches
- repeated crossings with the same edge family
- routes that cross multiple unrelated group bands
- large distance between endpoint centers relative to available clean lanes

Keep these metrics deterministic and geometry-based, but use them as test
assertions and diagnostics only. Do not use them to author replacement
coordinates, create a competing router, or special-case ArchiMate relationship
types beyond already generic relationship-type grouping used for endpoint merge
eligibility.

- [ ] **Step 3: Tune ELK/libavoid inputs, endpoint merge, and port-side selection**

Use generated route geometry as feedback for changing ELK/libavoid inputs:

- keep merging for compact local fanout/fanin
- avoid or shorten merged lanes when the trunk becomes a cross-canvas bus
- allow source/target side changes when they reduce crossing density
- keep relationship-type separation so different relationship semantics do not
  merge into one indistinguishable bus
- prefer changes to graph containment, port sides/order, hierarchy, spacing, and
  libavoid options over post-processing route rewrites

- [ ] **Step 4: Re-run real render tests**

Run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin real_elk_plugin_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test -p dediren --test real_elk_render -- --ignored --test-threads=1
cargo test -p dediren --test cli_layout real_elk_layout_validates_grouped_cross_group_route -- --ignored --exact --test-threads=1
```

Expected: grouped routes remain node/group-safe, but route metrics improve for
the LFM-derived cases.

---

### Task 4: Split Or Tune LFM Views That Remain Too Broad

**Files in `/home/souroldgeezer/repos/lfm`:**

- Modify: `docs/architecture/lfm.dediren/model.json`
- Optional: `docs/architecture/lfm.dediren/project.json`
- Regenerate ignored package output and final tracked renders under
  `docs/architecture/renders/` only if the LFM workflow expects those SVGs
  tracked

- [ ] **Step 1: Application cooperation**

Split the current view into at least two concerns if engine fixes do not make
it render-ready:

- LFM-owned application services and interfaces
- external Blizzard dependencies and app/API touchpoints

This directly addresses the broad first-pass density finding and the edge-pass
issues `AC-E2` through `AC-E5`.

- [ ] **Step 2: Technology usage**

Split hosting/runtime, data/secrets, and observability into separate views or
use narrower view membership:

- hosting/runtime: Static Web Apps, Functions, plan/runtime, LFM app/API
- data/secrets: Cosmos, storage, Key Vault, identity access
- observability: Application Insights, Log Analytics, alerting routes

This addresses `TU-E1` through `TU-E5` and the first-pass finding that data
plane and observability create distant cross-view routes.

- [ ] **Step 3: Production release migration**

Separate CI orchestration from deployed runtime if the diagram remains a wide
strip:

- local gates and GitHub workflow orchestration
- build/deploy stages
- production runtime realization

This addresses `PR-E1` through `PR-E4` and the first-pass wide-aspect finding.

- [ ] **Step 4: Run signup service realization**

Keep the business process candidate view mostly unchanged, but split the
service realization if necessary:

- UI pages to process steps
- API/service to data dependencies

This addresses `RS-E1` through `RS-E5`. The process candidate edge issue
`PC-E1` is lower priority and may be acceptable if the alternate branch remains
visually understandable after the service-realization split.

- [ ] **Step 5: Regenerate and inspect final LFM PNGs**

Create fresh PNGs from the regenerated SVGs and compare against both current
annotation passes. The review-ready target is not "no red boxes"; it is:

- no repeated line-jump or backtracking artifacts
- no dominant shared lane that carries unrelated relationships across the view
- each view has one clear concern
- labels remain legible without callouts

---

### Task 5: Harden The Architecture Review Skill With TDD

**Files in the skills source repo, not this dediren repo:**

- Modify: `souroldgeezer-architecture/skills/architecture-design/SKILL.md`
- Modify one or more targeted references under
  `souroldgeezer-architecture/skills/architecture-design/references/`
- Add or update eval/pressure scenarios if the skills repo has a matching eval
  harness

This task follows superpowers:writing-skills. Do not edit the skill before RED
pressure scenarios exist.

- [ ] **RED: Create pressure scenarios from the LFM misses**

Use at least these scenarios:

1. "Review these architecture SVG renders and mark layouting issues." Baseline
   failure to capture: agent reports broad region density but misses exact edge
   path defects such as repeated line-jump loops.
2. "Do a second pass for edges; you missed some." Baseline failure to capture:
   agent adds more boxes without inspecting edge path data, line jumps, or exact
   relationship ids.
3. "Can this package be called render-ready?" Baseline failure to capture:
   agent treats layout-valid, nonblank SVG output as visually ready without
   checking route congestion, fanout, line-jump duplication, and edge labels.

Record verbatim rationalizations such as "the overall structure is readable,"
"the layout validates," "edge details are too small in the contact sheet," or
"the first pass already covered the region."

- [ ] **GREEN: Add the minimal skill guidance**

Update the architecture skill so visual review requires two distinct passes
before any `render-ready` claim:

- canvas/group pass: framing, density, aspect ratio, whitespace, group balance,
  concern split
- edge pass: exact edge ids, path shape, backtracking, duplicate jumps, shared
  lanes, crossing density, long buses, fanout/fanin, label obstruction

When the user asks for marked PNGs, require exact edge-path highlights for the
edge pass, not only region rectangles. When an issue appears in SVG path data,
call out the relationship id.

- [ ] **REFACTOR: Close loopholes and retest**

Retest the same pressure scenarios. Add explicit counters for new loopholes,
but keep the skill concise. The expected pass behavior is that the agent
produces both region-level and edge-level findings on the first full review, or
clearly says that the edge pass has not been done.

---

### Task 6: Versioning, Documentation, And Final Verification

**Versioning:**

- No version bump is required for this plan-only commit.
- A future implementation commit that changes `svg-render`, `elk-layout`,
  plugin runtime behavior, layout-result warnings, schemas, manifests, or
  distribution contents must bump the product/plugin version according to
  `AGENTS.md`.
- README must be updated if public behavior, commands, artifact locations, or
  bundle contents change.

**Verification for implementation:**

Run the narrow checks for each task, then finish with:

```bash
cargo fmt --all -- --check
cargo test --workspace --locked
git diff --check
```

For real ELK changes, also run:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin real_elk_plugin_invokes_java_helper -- --ignored --exact --test-threads=1
cargo test -p dediren --test real_elk_render -- --ignored --test-threads=1
```

**Visual exit criteria:**

- Regenerated LFM PNGs show no P0 renderer artifacts.
- Application cooperation no longer has a repeated CDN loop, duplicated
  Blizzard jump segment, or dominant right-side serving spine.
- Run signup service realization no longer has repeated data-access jump loops
  or a single long service fanout bus.
- Technology usage no longer depends on cross-canvas observability/data routes
  for the primary reading path.
- Production release migration no longer requires following one workflow edge
  across the whole canvas to understand the migration.
- The architecture-review skill passes the LFM-derived edge pressure scenarios.
