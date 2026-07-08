# Monolithic Compiler Restructure Design

Date: 2026-07-08

## Purpose

This is a product-boundary amendment to the founding
`2026-05-08-dediren-design.md`. It records the decision to stop shipping
`dediren` as a kernel plus process-isolated executable plugins and to ship it
instead as a single-JVM diagram compiler with a service mode. The contract-first
identity and the pipeline scope are unchanged; the external-executable plugin
posture and its runtime protocol are the thing being reversed.

The decision was brainstormed and owner-approved on 2026-07-08 (the "Refined
target vision" of `docs/superpowers/plans/2026-07-08-monolithic-runtime-radical.md`).
The evidence, measurements, and rulings behind it are recorded in
`docs/superpowers/reviews/2026-07-08-runtime-size-speed-challenge.md`
(the I9 dark-horse follow-up). This document is the spec-level record so the
boundary reversal is legitimate before any code moves; the implementation lands
task-by-task via that plan.

## Decision

`dediren` is a compiler with a service mode, not a pipeline of separate tools.

The five-invocation pipeline (validate, project, layout, validate-layout, render/
export) is a sequence of compiler phases that were exposed as separate processes
only because process handoffs forced it. The five first-party plugins
(`generic-graph`, `elk-layout`, `render`, `archimate-oef-export`,
`uml-xmi-export`) become ordinary in-tree Maven library modules behind small
typed engine interfaces; `core` orchestrates them in memory using the existing
`contracts` records; a new one-shot `dediren build` command produces all views
and all requested artifact kinds in one invocation. There is **no runtime plugin
protocol**: manifests, capability probes, plugin discovery, and trust machinery
are deleted, and the bundle ships one launcher and one CDS archive.

The compiler answer dominates on speed, size, diagnostics quality, and
testability. It costs the founding plugin identity and its wire contract surface,
and this design pays that cost deliberately.

## Module Shape

The target vocabulary is compiler roles. Items marked *Phase 1* are the shape
this restructure lands; the rest are the target vocabulary carried by follow-up
plans (see Phase Split) and are recorded here only so Phase 1 does not foreclose
them.

- `model` — public schemas + typed source model + validation (contract surface
  unchanged: JSON in, envelopes/diagnostics out). *Phase 1: stays `contracts`
  plus `core` source validation, unrenamed.*
- `semantics-archimate` / `semantics-uml` / `semantics-graph` — notation front
  ends: legality rules plus projection. *Phase 1: stays one `generic-graph`
  engine consuming the `archimate`/`uml` notation cores; the per-notation split
  belongs to Plan B.*
- `ir` — typed scene graph (sealed interfaces, records, exhaustive pattern
  matching, JSON-Pointer provenance back to the source model). *Deferred to
  Plan B; Phase 1 keeps the existing `contracts` records as the inter-stage
  types.*
- `layout` — ELK as a lowering pass plus layout-quality validation (ELK-first
  rule unchanged). *Phase 1: `elk-layout` engine plus `core` quality checks.*
- `emit-svg` / `emit-oef` / `emit-xmi` — backends. *Phase 1: `render`,
  `archimate-oef-export`, `uml-xmi-export` engines.*
- `driver` — orchestration plus diagnostics bus. *Phase 1: `core` gains the
  `build` driver. Content-addressed build cache, incremental re-layout, and
  `watch` are deferred to Plan C.*
- `cli` (plus `serve`). *Phase 1: `cli` only; `serve`/MCP is Plan C.*

In Phase 1 the engines are wired behind a tiny interfaces-only `engine-api`
module: engines depend on `engine-api`, `contracts`, and the notation/utility
cores they already use; `core` depends on `engine-api` and stays blind to engine
implementations; `cli` performs explicit engine construction in a single wiring
class. The dependency graph stays an acyclic graph rooted at `contracts`, and no
engine depends on `core`.

## Why the IR Is the Prize

Recorded here (Plan B rationale) so Phase 1 does not accidentally foreclose the
typed intermediate representation:

1. Cross-phase diagnostics with source provenance: a render-stage defect can
   point the agent at the exact source element to repair — impossible today
   because each stage only sees its own input.
2. The recorded sequence-diagram defect class (empty bands, arrowhead
   anchoring, fragment chrome) becomes exhaustiveness errors or IR invariant
   checks ("every message anchors on a lifeline stem") instead of post-hoc
   SVG-audit findings; phase-level property tests replace defect-hiding golden
   fixtures.

## Execution Modes

Agent-first, one-shot by default:

- `dediren build model.json --out dist/` — one invocation, all views, all
  requested artifact kinds. *Phase 1 builds views sequentially and
  deterministically; virtual-thread parallelism is a follow-up optimization.*
- Stage artifacts are demoted to opt-in debug outputs (`--emit layout-request`,
  compiler-style). They stay public and schema'd — this is the pipe-level
  extension surface. The existing per-stage subcommands remain the decomposed
  form of the same phases and keep working throughout and after the transition.
- `dediren serve` — the daemon done right, plausibly an MCP server
  (validate/build/diagnose as tools) with warm rebuilds around 50–150 ms.
  *Plan C.*

## Extensibility Bet

Extensibility is made as an explicit bet (the esbuild bet): curated in-tree
notations, declarative styling/policy, and pipe-level composition over the
public stage artifacts. There is deliberately **no runtime plugin protocol**.
Adding a notation or a backend is an in-tree change reviewed like any other, not
a third-party executable loaded across a process boundary.

Distribution consequences (exploration notes, not Phase 1 work): one build
target re-opens native-image (single binary, roughly 10–30 ms startup, no JDK
prerequisite; EMF/Jackson reflection config becomes one hard problem instead of
six) or a single JDK-24 AOT cache (around 40 ms boot). With stored-jar packaging
(review idea I7, a separate plan) the archive is a single ~7 M bundle, and that
work composes unchanged and gets simpler with one launcher.

## What This Revises in the 2026-05-08 Design

This document revises exactly these parts of `2026-05-08-dediren-design.md` and
nothing else:

- **Purpose.** Plugin manifests and plugin capability probes are removed from
  the enumerated product surface. The product surface becomes JSON schemas, CLI
  commands, command envelopes, and structured diagnostics.
- **Primary Decisions → "Plugin posture: external executable plugins over JSON
  stdin/stdout".** Reversed: the notation/layout/render/export concerns are
  in-tree library engines behind typed interfaces, orchestrated in a single JVM.
- **"Plugin Runtime Contract".** Retired in full: external-executable plugins,
  static manifests, the runtime capability command, explicit multi-directory
  discovery, timeouts, working-directory handling, and the environment allowlist
  are all deleted. The engine seam replaces the wire protocol.
- **"Rejected Approaches: in-process plugins".** The design's original rejection
  of in-process plugins is itself reversed. In-tree, single-JVM engines are now
  the chosen structure; the reasons the 2026-05-08 design gave (agentic
  composition, language-neutral implementation, inspectable process boundaries)
  are answered instead by curated in-tree notations plus public, schema'd stage
  artifacts.
- **Deferred Decisions → "Third-party plugin publishing and signing".** Removed
  from the roadmap rather than deferred: there is no plugin protocol to publish
  against. The shipped `2026-07-03-third-party-plugin-contract.md` roadmap is
  reversed and `docs/plugin-authoring.md` retires.

The historical body of the 2026-05-08 design otherwise stands as the record of
how the product began; that spec carries a status note pointing here.

## What Is Preserved

The reversal is narrow. These founding commitments are unchanged:

- **Contract-first identity.** Machine-readable contracts remain the product,
  not the Java API. An agent still decides success or failure from stdout JSON
  alone.
- **Per-stage commands and their stage artifacts as public surface.** `validate`,
  `project`, `layout`, `validate-layout`, `render`, and `export` keep working;
  the layout request, layout result, render policy/result, and export
  request/result stay public, schema'd, inspectable, diffable, and repairable.
- **The command envelope and diagnostics contract.** Same envelope schema, same
  statuses, same diagnostics vocabulary agents already branch on, same exit
  codes; stderr stays human-only.
- **ELK-first.** The layout engine stays a translation boundary over Eclipse
  ELK. This restructure adds no custom placement or route geometry.
- **The `xmllint` tool dependency.** The export engines keep validating against
  external standards schemas via `xmllint`, and still report its absence as a
  structured error the driver preserves (`DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE`
  / `DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE`).

## Costs and Reversals

All owned by the implementing plan:

- The founding plugin-boundary identity (this amendment).
- The third-party plugin roadmap (`2026-07-03-third-party-plugin-contract.md`,
  shipped and now reversed); `docs/plugin-authoring.md` retires.
- Plugin manifests, discovery, capability probing, and trust machinery
  (deleted). The `2026-07-08-plugin-probe-cache.md` plan dies with the probe it
  caches; the `2026-07-08-hybrid-plugin-host.md` interim design is superseded.
- The threat model shrinks to schema-cache fetching, XML parser hardening, SVG
  escaping, and the release chain.
- The plugin-surface schemas (`plugin-manifest.schema.json`,
  `runtime-capability.schema.json`) and their `contracts` records become
  orphaned-but-harmless; their removal is the separate contract-cleanup
  follow-up, not this restructure.

Two adjacent review outcomes for the record: I4 (PNG/raster removal) already
landed (release 2026.07.13; `render-policy.schema.v2` / `render-result.schema.v4`),
so no raster migration belongs here; I7 (stored-jar distribution) is unaffected
and gets simpler.

## Evidence Record

The measured baseline that justifies the reversal, from
`docs/superpowers/reviews/2026-07-08-runtime-size-speed-challenge.md` (bundle
`2026.07.8`, Temurin 21, warm CDS): the documented five-invocation agent flow is
roughly 13–15 JVM spawns totalling about 1.9 s, of which the shipped startup
tiers (launcher flags, AppCDS, manifest-trust probe skip) recover only a small
fraction — an order of process overhead they cannot remove. The one-shot
projection is about 0.3–0.5 s once spawns and protocol serialization are gone
(the challenge estimated 0.5–0.7 s for a one-shot host; the monolith removes
serialization on top of spawns). These projections are expectations, not test
gates; the implementing plan records actual measurements.

## Phase Split

This amendment authorizes a phased delivery. Only Phase 1 is in the implementing
plan; the rest are follow-up plans and are named here so their boundaries stay
clear:

- **Phase 1 — this plan (`2026-07-08-monolithic-runtime-radical.md`).** Delete
  the plugin process protocol; make the five plugins in-tree library engines
  behind `engine-api`; give `core` the in-memory `build` driver; add
  `dediren build`; ship one launcher and one CDS archive; land this governance
  amendment. No typed IR, no daemon/serve, no build cache.
- **Plan B — the typed IR.** The sealed-interface scene graph with JSON-Pointer
  provenance and the per-notation `semantics-*` split. Phase 1 keeps the
  `contracts` records as the inter-stage types and must not foreclose this.
- **Plan C — `serve`/cache/watch.** The `dediren serve` daemon (plausibly MCP),
  content-addressed build cache, incremental re-layout, and warm-rebuild mode.
- **Contract cleanup (separate follow-up).** Remove the orphaned plugin-surface
  schemas and their `contracts` records, and retire the `required_plugins[]`
  field, once nothing depends on them.

## Limits

This is a product-boundary amendment, not an implementation plan. It records the
decision, what it revises, what it preserves, the extensibility bet, and the
phase split. The implementation sequence, task breakdown, and verification lanes
live in `docs/superpowers/plans/2026-07-08-monolithic-runtime-radical.md`.
