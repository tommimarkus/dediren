# Dediren Package Model Design

Date: 2026-07-24

## Status

Proposed design anchor for making the **package** — several views, across
several models, each with its own render policy, its own export identity, and
its own declared output path — a first-class Dediren build primitive.

This spec **amends** the founding design (`2026-05-08-dediren-design.md`),
which placed the source model at the top of the tree and pushed views down into
a plugin-owned section. The contract-first identity, the per-view engine seam,
and the pipeline scope all stand. What changes is that a container noun is
introduced *above* the source model.

This spec also **supersedes the decline recorded on issue #62**
(`build-manifest.schema.v1` + `build-result.schema.v2`, closed `not_planned`
2026-07-23). That decline rested on proportionality — a new keep-forever
schema family plus a big-bang breaking result migration, at N=1 consumer
provenance. The reversal rationale is *not* the recorded re-open trigger ("a
second independent consumer"). It is stronger and different in kind:

> Accumulated real use forced an unvalidated, drifting shadow compiler into
> existence outside the tool. That is evidence the abstraction was in the wrong
> place, not evidence that one more consumer wants a convenience.

The design below also dissolves the decline's heaviest specific objection: the
breaking result migration is **not** required (see `## Decisions`, item 3).

The implementation plan is authored separately after this spec is approved.

## Purpose

Let a caller declare a whole package once and have Dediren build it end to end
in a single invocation, returning one normalized result that names every
artifact at its final declared path.

Today the natural unit of real work is a package, but Dediren has no primitive
for one. Every consumer that builds a package must reimplement the same
orchestration on top of `build` — and that orchestration is not consumer
domain. It is Dediren-internal build-graph knowledge leaking out.

## Evidence: the shadow compiler

The `souroldgeezer-architecture` plugin drives Dediren to produce multi-view
ArchiMate/UML packages. To do so it has rebuilt, outside the tool:

- a **model registry** and a view→(model, render-policy) **binding graph**;
- a **planner** that groups render calls by `(model, render-policy)` and fans
  out **one single-view build per export**, because export identity applies to
  a whole invocation;
- a **materializer** that copies Dediren's fixed view-major output
  (`<out>/<view-id>/{diagram.svg,oef.xml,xmi.xml}`) into caller-declared
  kind-major paths, and unwraps `--emit` envelopes to their `.data` payload;
- a **failure-shape normalizer**, because `build` stdout is one of two
  different top-level documents;
- a **presentation layer** (view titles, framing questions, diagram kinds) that
  Dediren has no notion of, consumed by a gallery builder and by a
  post-render accessible-name patcher.

Two facts make this decisive rather than merely inconvenient:

1. **The asymmetry.** Every artifact Dediren touches carries a resolvable,
   versioned schema (`render-policy.schema.v3`, `layout-result.schema.v2`,
   `envelope.schema.v1`, …). The format that *orchestrates* all of them has no
   schema at all — it is validated by nothing but a `KeyError` in a ~420-line
   script. Dediren rigorously versions the leaves and lets the trunk be an
   unvalidated convention.
2. **It is already drifting.** That unvalidated format has fractured into two
   incompatible subsets — one the build path accepts, one the gallery path
   accepts — with no single validator owning either.

## Decisions (resolved in brainstorming)

1. **The package is a new top-level container that *references* models.** It
   does not replace, inline, or absorb the source model. `model.json`,
   `render-policy.json`, and the export policies continue to be authored
   exactly as they are today; the package binds them together. Multi-model
   (mixed ArchiMate + UML) packages are the motivating case, not an extension.

2. **Presentation is carried by the package and fed to the render lane.**
   Per-view `title` and `question` flow into the render lane, which already
   owns accessible-name emission. This retires the consumer's post-render
   accessible-name patcher.

   This is **not** a product-boundary move. `schemas/render-policy.schema.json`
   already defines an `accessibility { title, description }` block, and
   `engines/render/.../SvgAccessibleName.java` already emits `<title>`/`<desc>`
   from it, falling back to the layout `view_id`. The only real gap is that
   accessibility text lives on the **policy**, which is per-invocation, so N
   views sharing a policy all get one accessible name. The package supplies it
   **per view** instead. The founding "core does not own view semantics"
   posture is unchanged: presentation is carried and echoed, never interpreted
   as semantics.

3. **The result is additive, not a breaking migration.** The package build
   returns a new `package-build-result` document wrapped in a standard
   `CommandEnvelope`. Legacy `dediren build` keeps its existing bare
   `BuildResult` stdout, unchanged, on the wire.

   This is possible because `build-result.schema.v1` is a **generated
   engine-seam** version, not a registered hand-authorable family — so no
   migration-registry entry and no consumer migration is implied. It also
   fixes, on the new surface only, the standing asymmetry that `build` is the
   one command whose primary stdout is not a `CommandEnvelope`.

4. **The package declares its own output topology.** Each view and export
   names the path its artifacts land at. Dediren writes them there. The fixed
   view-major layout and hardcoded filenames apply only to the legacy lane.
   This retires the consumer's copy/materialize layer.

5. **One document, not two.** The package holds structure, presentation, and
   output paths together. Splitting a durable "definition" from a build "plan"
   was considered and rejected as premature (see `## Rejected Approaches`).

6. **The container noun is `package`.** `project` was viable — the clash with
   the `dediren project` subcommand (the projection *verb*) is conceptual, not
   technical, since schema ids and subcommand names are separate namespaces
   (`model.schema.v1` coexists with no `dediren model` command). `package` was
   chosen so no single word names both a verb and a container; it is already
   the vocabulary in the consumer's docs and scripts, and leaves `bundle`
   (distribution manifest) and `workspace` (`status --root`) untouched.

## Resolved decisions (2026-07-24 review)

Two items were surfaced during spec review and resolved by the maintainer.

### R-1. Container noun → `package`

Chosen over `project`. Both were technically viable; `package` avoids one word
naming both the projection verb and the container. Folded into decision 6.

### R-2. Export cardinality → view **or** model per entry

An export targets exactly one of `view` / `model`, mutually exclusive. Chosen
over view-only (as first drafted) and model-only. Rationale: the v2026.07.25
slice delivered a **whole-model** `model.oef.xml` lane in which *all* views of a
model land in one OEF file, each with correct per-view identity — arguably OEF's
natural unit (one Archi-importable file). View-only left that shipped lane
unreachable from a package, which would leave the single-view export fan-out
only partly retired; the fan-out existed *because* per-invocation identity
forced it, so with the aggregate lane reachable the retirement claim in
`## Honest retirement boundary` holds fully. Model-only would have lost focused
single-view export. Both-with-mutual-exclusion reaches everything shipped at the
cost of one validation rule. Folded into the contract and execution semantics
above.

## Contract footprint

The paradigm shift lands as exactly **one** new hand-authorable schema family.

| Schema | Kind | Change |
|---|---|---|
| `package.schema.v1` | hand-authorable family (the 6th) | **new**, registered in `KnownSchemaVersions` |
| `package-build-result.schema.v1` | generated engine seam, no family | **new** |
| `model.schema.v1` | hand-authorable family | unchanged |
| `render-policy.schema.v3` | hand-authorable family | unchanged |
| `oef-export-policy.schema.v1` | hand-authorable family | unchanged |
| `uml-xmi-export-policy.schema.v1` | hand-authorable family | unchanged |
| `layout-request.schema.v2` | hand-authorable family | unchanged |
| `build-result.schema.v1` | generated engine seam | unchanged |

`render-policy` stays untouched because core constructs the **effective**
per-view render policy internally, injecting the package's per-view
accessibility text before dispatching to the render engine.

A brand-new `v1` family has no superseded predecessor, so it adds no
`## Migration` obligation in `docs/agent-usage.md` on introduction.

## The package contract

```json
{
  "package_schema_version": "package.schema.v1",

  "models": [
    { "id": "arch", "source": "model.json" },
    { "id": "uml",  "source": "model-uml.json" }
  ],

  "views": [
    {
      "id": "app-cooperation",
      "model": "arch",
      "render_policy": "render-policy.json",
      "presentation": {
        "title": "Application Cooperation",
        "question": "How do the components cooperate?",
        "diagram_kind": "Application Cooperation"
      },
      "outputs": {
        "diagram": "generated/svg/app-cooperation.svg",
        "render-metadata": "generated/render-metadata/app-cooperation.json",
        "layout": "generated/layout/app-cooperation.json"
      }
    }
  ],

  "exports": [
    {
      "id": "arch-view-oef",
      "view": "app-cooperation",
      "lane": "archimate-oef",
      "policy": "export-policy.json",
      "output": "generated/export/app-cooperation.oef.xml"
    },
    {
      "id": "arch-model-oef",
      "model": "arch",
      "lane": "archimate-oef",
      "policy": "export-policy.json",
      "output": "generated/export/arch.oef.xml"
    }
  ]
}
```

Field intent:

- **`models[]`** — the registry: an id and a source path, nothing more.
  `views[].model` binds a view to one; the binding is optional when exactly one
  model is declared. The package deliberately does **not** restate the model's
  semantic profile: `semantic_profile` is already declared inside the model
  (`schemas/model.schema.json`, on the `generic-graph` plugin section) and
  Dediren parses it. The reference consumer carries a `profile` field only
  because its orchestrator could not parse the model; that reason does not
  transfer.
- **`views[].render_policy`** — per-view. This is the field that retires the
  per-invocation `--render-policy` constraint.
- **`views[].presentation`** — carried, echoed, and fed to the render lane as
  per-view accessibility text. Never interpreted as model semantics.
- **`views[].outputs`** — declared path per artifact kind. Kinds beyond
  `diagram` are produced only when declared, preserving today's opt-in `--emit`
  behavior.
- **`exports[].view` / `exports[].model`** — an export targets exactly one of
  the two (mutually exclusive per entry). A `view` target emits one focused
  file; a `model` target emits the whole-model aggregate — every one of that
  model's views in a single file (the `model.oef.xml` lane shipped in
  v2026.07.25). For OEF both carry per-view identity through the mechanism
  already shipped (`resolveViewIdentity` + the policy `views` map). For UML/XMI
  the target only selects what to export (see `## Execution semantics core
  absorbs`, item 2).
- **`exports[].lane`** — `archimate-oef` | `uml-xmi`.

## Execution semantics core absorbs

Each item below is something the consumer's orchestrator does today and that
moves inside the boundary:

1. **Planning is internal.** The package declares views; how core batches them
   (for example by `(model, render-policy)`) to minimize passes is an
   implementation detail the caller never sees or encodes.
2. **Export fan-out is internal.** Exports target a view or a whole model; core
   routes each one instead of forcing the caller to fan out one single-view
   invocation per export. The two lanes differ and the plan must not conflate
   them:
   - **OEF** — a view target emits one focused file, a model target emits the
     whole-model aggregate; both carry per-view identity through the mechanism
     already shipped (`resolveViewIdentity` + the policy `views` map).
   - **UML/XMI** — the target selects *what to export* only. Per-view XMI
     identity remains meaningless while XMI emits no broad Diagram Interchange,
     so no per-view identity is to be built for this lane.
3. **Materialization is internal.** Every artifact lands at its declared path.
   No view-major staging directory is exposed to the caller.
4. **One result shape.** Declared `render-metadata` / `layout` outputs are the
   unwrapped payloads, never stage envelopes. Input errors and semantic
   failures share one top-level document, so a caller branches once.

## Engine seam: unchanged

This is an orchestration and contract change, **not** an engine change. The
per-view engine seams stay exactly as they are: `SceneGraph` and
`LaidOutScene` each carry one `viewId`; `ModelExportRequest` carries N views of
one model; `engine-api` capabilities are untouched; `EngineWiring` constructs
the same engines.

What moves is a planner/materializer into `core` — which is precisely what the
consumer's script already is — plus the two `build` assumptions that are
coupled to one-model-per-invocation (fixed view-major paths, bare result).

## Command and MCP surface

- `dediren build --package <path>` — build every view and export declared.
- `dediren build <dir>` — convenience: read the package document at that root.
- Filters mirroring today's `--views`: a view subset, and an export-suppressing
  flag. (Note: no `--no-export` flag exists today; lane selection is currently
  positive-only. The suppressing flag is new surface for the package lane.)
- MCP `dediren_build` gains a `package` argument, **mutually exclusive** with
  `source` / `render_policy` / `oef_policy` / `xmi_policy`. Present-day
  single-model calls keep working unchanged.

Both front-ends (`cli` `Main.BuildCommand` and `mcp-server` `DedirenTools`)
assemble the request and call the same core entry point, as they do today.

## Trust boundary

Declared output paths are a **new write surface** and the main new risk in this
design. Requirements:

- Every declared output path resolves inside the package root (CLI lane) or
  inside the server's confinement root (MCP lane); traversal outside is a
  structured error, never a write.
- Declared *input* references (`models[].source`, policy paths) are confined on
  the same rule.
- Confinement is enforced in core, once, before any engine runs — matching the
  existing policy-gating order.

`docs/threat-model.md` must be updated in the same change.

## Honest retirement boundary

What a package contract **retires** in the reference consumer:

- the orchestrator script in full — planner, materializer, envelope unwrap,
  failure-shape normalizer;
- the unvalidated `project.json` convention and its two divergent subsets;
- the single-view export fan-out;
- the post-render accessible-name patcher.

What it **does not** retire, stated up front so there is no later surprise:

- authoring `model.json`, `render-policy.json`, and export policies — that is
  simply *using* Dediren;
- the **gallery**, which stays consumer-owned presentation. Absorbing per-view
  presentation metadata must not expand into Dediren owning a gallery.

## Costs and reversals

- **A 6th keep-forever hand-authorable family.** Accepted deliberately. This is
  the irreducible cost of the paradigm shift and the one the #62 decline named
  correctly. Families are keep-forever by policy; this is not cheaply
  reversible.
- **A larger `core`.** Core gains planning and materialization responsibility
  it did not have. This is a deliberate transfer of work that was already being
  done, from outside the boundary to inside it.
- **Two build lanes coexist.** Legacy single-model `build` and the package lane
  both live in the surface for the foreseeable future. Reversal is cheap only
  for the package lane; the legacy lane is unaffected by design.

## Rejected approaches

- **Bolting a manifest onto the existing `build`** (the #62 shape). Rejected:
  it formalizes the workaround as an input file without moving the abstraction,
  leaving the model as the root and the package as an overlay.
- **A breaking `build-result` v2** unifying legacy and package results.
  Rejected: unnecessary once `build-result` is recognized as a generated seam,
  and it would break every existing caller for no capability gain.
- **Two documents — a durable package *definition* plus a separate build
  *plan*.** Rejected as premature. The split pays off only if the definition
  must be published independently of build mechanics; nothing demands that yet,
  and one document directly replaces the consumer's single file. Revisit if a
  definition ever needs to outlive a build layout.
- **Per-view accessibility via a `views` map on `render-policy`** (mirroring
  the OEF policy pattern). Rejected under the one-document decision: it would
  force the author to write each view's title in the package *and* restate it
  in the render policy. Single source of truth wins.
- **Dediren owning the gallery.** Out of scope, explicitly and permanently.

## Deferred decisions

- Package-level caching and `watch` — still deferred with the rest of Plan C.
- Retiring or migrating the legacy single-model `build` lane.
- Per-view XMI identity, which remains meaningless until XMI emits Diagram
  Interchange broadly; the UMLDI class-family lane is tracked separately.
- Any package-level presentation surface beyond carry-and-echo.

## Validation layers for this design

- **Static:** both halves mapped against live code — the current model-centric
  internals (contracts, core dispatch, engine seam, output writing) and the
  consumer's orchestration layer.
- **Empirical:** the render-policy `accessibility` block and
  `SvgAccessibleName` emission were verified in source, correcting an earlier
  framing of decision 2 as a boundary move.
- **Human:** decisions come from the 2026-07-24 brainstorming session and the
  maintainer's resolution of four design forks.

## Limits

This design is not an implementation plan. It defines the container contract,
the responsibility transfer, the trust boundary, and the retirement boundary.
The next step is a separate implementation plan after review approval.
