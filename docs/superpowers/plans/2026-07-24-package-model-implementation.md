# Package Model — Implementation Plan

Status: **proposed (2026-07-24).** Awaiting approval before implementation.

Parent spec: `docs/superpowers/specs/2026-07-24-dediren-package-model-design.md`
(approved). Issue: **#63** (supersedes #62).

**Goal:** make `package` a first-class build primitive — a top-level container
that references models and binds per-view render policy, per-view/whole-model
export, declared output paths, and carried presentation into a single build that
returns one normalized `package-build-result` envelope. Legacy single-model
`dediren build` is untouched on the wire.

**Not in scope (this repo):** retiring the reference consumer's shadow compiler
(`dediren-build.py`, `project.json`, the a11y patcher, the gallery's coupling)
— that lands in the `souroldgeezer-architecture` **skills repo** after this
capability ships, and is the acceptance proof of #63, not a Dediren code change.
See Slice F. Also out: package-level caching/`watch`, retiring the legacy
`build` lane, per-view XMI identity (all Deferred in the spec), and — pending
decision D-2 below — **package-aware `verify`/`status`**: the consumer uses
`dediren_verify` (stale gate) + `dediren_status` for package-level drift; unless
the package makes them package-aware, the consumer keeps hand-rolling drift
detection. Almost certainly a deferral, but it must be *stated* or the
retirement boundary is quietly overclaimed.

**Sequencing / release:** contract-first. Each slice lands as its own
commit(s), tests first. No version bump rides any content commit; a release bump
follows integration per `release-policy` if this is released. Behavior changes
are flagged for release notes in commit bodies.

**Engine seam is unchanged** — the per-view engines (`SemanticsEngine`,
`LayoutEngine`, `RenderEngine`, `ExportEngine`) and `EngineWiring` are not
touched. All new work is orchestration (`core`) + contract (`contracts`,
`schemas`) + the two front-ends (`cli`, `mcp-server`). Confirm this holds while
implementing; if any engine signature must change, stop and revise the spec.

---

## Slice A — Contract bedrock (schemas + records, no behavior)

The 6th hand-authorable family plus the additive result seam. This is the
foundation; B–D depend on it. Nothing runs yet.

Files that move together (per CLAUDE.md): `schemas/`, `contracts`, fixtures,
`ContractVersions`, `KnownSchemaVersions`, round-trip/version tests.

- [ ] **RED:** schema-conformance + round-trip tests for both new schemas
      against valid and invalid fixtures (`fixtures/package/…`): a minimal
      single-model package, a mixed multi-model package, a view-targeted export,
      a model-targeted export; invalid fixtures for unknown model/view refs and
      an export naming both `view` and `model`.
- [ ] **GREEN — schemas:**
      - `schemas/package.schema.json` — root `package_schema_version`;
        `models[]` (`id`, `source`); `views[]` (`id`, `model?`, `render_policy`,
        `presentation{title?,question?,diagram_kind?}`, `outputs{diagram?,
        render-metadata?,layout?}`); `exports[]` (`id`, exactly one of
        `view`/`model`, `lane` ∈ {archimate-oef, uml-xmi}, `policy`, `output`).
        The view/model exclusivity is a schema `oneOf`; deeper ref-integrity
        (a `view`/`model` id actually exists) is a core validation (Slice B),
        not expressible in JSON Schema.
      - `schemas/package-build-result.schema.json` — `build_result_schema_
        version`-analogue field `package_build_result_schema_version`; top-level
        `status`; `views[]` (id, status, `artifacts{kind:path}`, echoed
        `presentation`, diagnostics); `exports[]` (id, target, status,
        `artifact`, diagnostics); top-level `diagnostics[]`.
- [ ] **GREEN — records (`contracts`):** `source`-adjacent `PackageDocument`
      (+ `PackageModel`, `PackageView`, `PackagePresentation`, `PackageExport`);
      result records `PackageBuildResult` (+ `PackageViewOutcome`,
      `PackageExportOutcome`). Follow the existing `BuildResult` /
      `BuildViewOutcome` record shape as the template.
- [ ] **GREEN — versioning:**
      - `ContractVersions`: add `PACKAGE = "package.schema.v1"` and
        `PACKAGE_BUILD_RESULT = "package-build-result.schema.v1"`.
      - `KnownSchemaVersions`: register the `PACKAGE` family (version field
        `package_schema_version`) in `ALL`. `package-build-result` is a
        generated seam — **no** family entry (mirrors `build-result.schema.v1`).
      - A brand-new `v1` family has no superseded predecessor → no `## Migration`
        entry owed on introduction (spec `## Contract footprint`). Confirm
        `MigrationRegistryTest` stays green.
- [ ] Update the version-assertion surfaces that enumerate schema ids
      (`ContractRoundTripTest`, `contracts` `SchemaVersions`-style tests) — the
      new ids are current, not stale.
- [ ] Verify: `./mvnw -pl contracts -am test`.

## Slice B — Core: planner, orchestration, materialization (the heart)

Absorbs the shadow compiler's plan/map/normalize into `core`. A new
`PackageBuildCommand` (sibling of `core` `BuildCommand`) that consumes a
`PackageDocument` and returns a `PackageBuildResult` **wrapped in a
`CommandEnvelope`** (unlike legacy `build`'s bare result — this is where the
dual-top-level-shape wart is fixed, on the new surface only).

Design decisions to settle **before** coding (do not improvise mid-build):

1. **Effective per-view render policy.** The render lane already emits
   `<title>`/`<desc>` from `RenderPolicy.accessibility()` (`engines/render`
   `SvgAccessibleName`). Mechanism: core loads the view's `render_policy`, then
   constructs an *effective* `RenderPolicy` with `accessibility` set from the
   view's `presentation` (`title` → title, `question` → description) **when the
   policy does not already set them** (policy author intent wins; verify the
   precedence you want). No `render-policy` schema change. Confirm `RenderPolicy`
   is a plain record that can be rebuilt with a replaced `accessibility`.
2. **Export routing (view vs model).** Reuse what shipped:
   - `view` target → the per-view export lane (`OefExportEngine.buildOef` /
     the XMI single-view lane), honoring the view's identity.
   - `model` target → the whole-model aggregate lane (`buildModelOef` /
     `exportModel`), which already applies `resolveViewIdentity` per view.
   - OEF carries per-view identity on both; XMI target **selects what to export
     only** — build no per-view XMI identity (spec Execution item 2). The XMI
     whole-model aggregate lane exists (`model.uml.xml`, `exportModel` /
     `AggregateSpec`).
   - **XMI + a `view` target is degenerate** (D-3): XMI is model-only and emits
     no DI, so "export view X to XMI" yields that view's whole model regardless.
     Decide and document one of: (a) accept a `view` target on `uml-xmi` and
     define it as *"the view's model, whole"*, or (b) restrict `uml-xmi` exports
     to `model` targets and reject a `view` target with
     `DEDIREN_PACKAGE_EXPORT_TARGET_INVALID`. Pin the choice with a test; do not
     leave it to implementation.
3. **Planner grouping.** Group render work by `(model, effective-render-policy)`
   to minimize passes, as the consumer's planner does — but this is an internal
   optimization, invisible in the contract. Correctness must not depend on it;
   a naive per-view pass must produce identical artifacts. Start correct, add
   grouping only if a test proves it's needed.
4. **Failure shape.** One top-level document: input/ref-integrity errors and
   per-view/-export engine failures all fold into the `PackageBuildResult`
   (per-item status + diagnostics; top-level status rolled up). Only
   confinement/IO faults that escape as exceptions become error envelopes — the
   same boundary legacy `build` uses, but here the success/soft-failure path is
   itself enveloped.

- [ ] **RED:** core `PackageBuildCommandTest` against fixtures —
      - happy path (single + mixed): every declared `outputs`/`output` path is
        written; `render-metadata`/`layout` outputs are the **unwrapped**
        payloads (not stage envelopes); result names every artifact at its
        declared path; `presentation` echoed back per view.
      - accessibility injection: rendered SVG `<title>`/`<desc>` reflect the
        view's `presentation`, differing per view under one shared policy
        (the exact gap the consumer's patcher exists to fill).
      - export view-target vs model-target both produce the right file(s) with
        correct identity; OEF per-view identity present; XMI selects-only.
      - failure folding: an unknown `views[].model` ref, an export naming a
        missing view, and a per-view engine failure each land as per-item
        status + diagnostic in one enveloped result (not two shapes).
      - path confinement: a declared `outputs`/`output` or `models[].source`
        path escaping the package root is a structured error, never a write.
      - **collision:** two views (or a view + an export) declaring the same
        `output`/`outputs` path is rejected up front
        (`DEDIREN_PACKAGE_OUTPUT_COLLISION`), not silently last-write-wins. The
        consumer's `map()` staged per-view then verified presence/non-empty;
        core validates the declared-path set before building.
- [ ] **GREEN — new diagnostic codes (`contracts` `DiagnosticCode`, under
      documented families):** `DEDIREN_PACKAGE_MODEL_UNKNOWN`,
      `DEDIREN_PACKAGE_VIEW_UNKNOWN`, `DEDIREN_PACKAGE_EXPORT_TARGET_INVALID`
      (both/neither of view/model), `DEDIREN_PACKAGE_OUTPUT_ESCAPES_ROOT` (or
      reuse the existing confinement code — check what `build`/MCP uses today
      and prefer reuse). Each needs a Slice E repair-rule entry (the reverse
      token test enforces this).
- [ ] **GREEN — implementation:** `PackageBuildCommand` + a `PackagePlanner`
      (plan) and `PackageMaterializer` (write-to-declared-path) split, mirroring
      the consumer's plan/map separation but inside the boundary. Reuse
      `EngineDispatch.dispatchInMemory` and the existing per-view stages; do not
      duplicate stage logic — factor the shared per-view pipeline out of
      `BuildCommand` if that's cleaner than copying (software-design: watch the
      `BuildCommand` god-class register in `architecture-guidelines §8`).
- [ ] **GREEN — confinement:** enforce declared input + output path confinement
      in core, once, before any engine runs (matches the existing policy-gating
      order). Package root = the package file's directory (CLI) or the server
      confinement root (MCP).
- [ ] Verify: `./mvnw -pl core,cli -am test` (cli for wiring compile), plus
      `engines/render,archimate-oef-export,uml-xmi-export` lanes if fixtures
      exercise them.

## Slice C — CLI surface

Files that move together: `cli` `Main`, CLI tests, README.

**Decide before coding (D-1): `--out` and path resolution base.** Today `--out`
is a *required* picocli option on `BuildCommand`, and it seeds the fixed
view-major layout. In package mode all paths are declared and resolved against
the **package-file directory** (CLI) / confinement root (MCP), so a mandatory
`--out` is meaningless. Pin: `--out` is **forbidden (or ignored) when `--package`
is present** (a structured usage error if combined, matching the other
mutual-exclusions), and *every* package-relative path — `models[].source`,
`views[].render_policy`, `exports[].policy`, `outputs`, `output` — resolves
against the package-file directory. Confirm the exact base and the `--out`
disposition here, not mid-slice.

- [ ] **RED:** `CliPackageBuildTest` — `dediren build --package <path>` builds a
      fixture package and writes declared paths; `dediren build <dir>` reads the
      package doc at the root; `--package` is **mutually exclusive** with
      `--input`/`--render-policy`/`--oef-policy`/`--xmi-policy` (a structured
      usage error if combined); the view-subset filter and the new
      export-suppress flag behave.
- [ ] **GREEN:** extend `Main.BuildCommand` (`cli`) to accept `--package` and a
      bare directory positional; assemble a package request and dispatch to core
      `PackageBuildCommand`. Legacy single-model options path is unchanged.
      Note: no `--no-export` exists today (lane selection is positive-only); the
      export-suppress flag is **new surface on the package lane only** — name it
      to not imply it works on the legacy lane.
- [ ] Verify: `./mvnw -pl core,cli -am test`.

## Slice D — MCP surface

Files that move together (per CLAUDE.md MCP row): `mcp-server` (`DedirenTools`,
`ToolSchemas`, `GuideCatalog`), agent-usage `## MCP Server`, threat-model MCP
rows, dist-tool packaged-MCP stdio smoke.

- [ ] **RED:** parity/`DedirenTools` test — `dediren_build` with a `package`
      argument (path or inline object) builds the same result the CLI produces;
      `package` is **mutually exclusive** with
      `source`/`render_policy`/`oef_policy`/`xmi_policy`; present-day
      single-model calls are byte-identical to today.
- [ ] **GREEN:** add the `package` arg to `dediren_build` in `ToolSchemas` +
      `DedirenTools` (confined-root lane); assemble the package request and call
      the same core entry as the CLI. Add a `GuideCatalog` topic for package
      authoring if the guide gains a section (Slice E).
- [ ] Verify: `./mvnw -pl mcp-server,cli -am test` and
      `./mvnw -pl dist-tool -am verify -Pdist-smoke` (packaged MCP stdio).

## Slice E — Docs, trust boundary, files-that-move-together

- [ ] `docs/agent-usage.md`: a package-authoring section (the `package.schema`
      shape, the one-call build, reading results by declared path); a
      `## MCP Server` bullet for the `package` arg; `## Repair Rules` entries for
      every new `DEDIREN_PACKAGE_*` code (the dist-tool reverse-token test
      requires each production token to be documented).
- [ ] `README.md`: a package example alongside the existing single-model build
      example; keep it the compact human front-door (defers depth to
      agent-usage).
- [ ] `docs/threat-model.md`: the declared-output-path **write surface** — the
      new risk in this design — its confinement rule, and the MCP `package`-arg
      row.
- [ ] `CLAUDE.md` **Files That Move Together**: add a "Package model changes"
      row coupling `schemas/package*`, `contracts` package records, fixtures,
      core planner/materializer, CLI, MCP, and docs.
- [ ] `docs/features/*` if they enumerate commands/schemas (sweep for the
      version-string and command surface).
- [ ] dist-tool: if any new reflective/ServiceLoader surface appears (unlikely —
      no new dependency is planned), add the `bundle-shrink.pro` keep rule +
      attribution. Confirm none is needed; `-Pdist-smoke` is the gate.
- [ ] Verify: `git diff --check`; the touched module lanes; full `./mvnw test`
      and `-Pquality verify` before the integrating commit; dist-smoke (Slices B
      write behavior, D MCP surface).

## Slice F — Consumer migration (separate `skills` repo, follow-up)

Not a Dediren commit. After this ships, in `souroldgeezer-architecture`: replace
`dediren-build.py` + `project.json` with a `package.json` authored to
`package.schema.v1`; delete the planner/materializer, the export fan-out, the
envelope-unwrap, the failure-shape normalizer, and the post-render a11y patcher;
keep native-input authoring and the gallery (still consumer-owned — it now reads
the enveloped `package-build-result` instead of reconstructing dediren's layout).
This migration is the acceptance proof for #63. Track it as a separate skills-repo
issue; do not couple it to the Dediren release.

---

## Audit gates (per CLAUDE.md `## Audit Gates`)

This is a "vertical slice / broad pipeline": run **`test-quality-audit` Deep**
(Java tests/fixtures across contracts, core, cli, mcp, export/render lanes) and
**`devsecops-audit` Quick** (the declared-output write surface, path
confinement, MCP arg, dependency posture — no new deps expected). Fix block
findings; fix or explicitly accept warn/info in the handoff, then rerun affected
checks.

## Decisions to confirm at plan approval (contract/scope — maintainer's call)

These change the public surface or the retirement claim, so — like the design
forks — they're the maintainer's to settle before Slice B/C begin, not mine to
pin silently.

- **D-1 — `--out` disposition + path base** (Slice C). `--out` forbidden/ignored
  under `--package`; all package paths resolve against the package-file dir.
  Confirm.
- **D-2 — `verify`/`status` package-awareness.** Deliver package-aware freshness
  now (all declared outputs vs their source models), or defer and let the
  consumer keep its drift detection? Lean: **defer**, and state it in the
  retirement boundary. Your call.
- **D-3 — `uml-xmi` + `view` target** (Slice B). Accept a `view` target and
  define it as "the view's whole model", or restrict `uml-xmi` to `model`
  targets. Lean: **accept + document** (matches the consumer's existing v2
  `project.json`, which binds XMI to a view meaning its model).

## Open implementation questions (settle in-slice, record the decision)

1. **Accessibility precedence** — policy-set `accessibility` vs the view's
   `presentation` when both exist. Lean: explicit policy wins; presentation
   fills the gap. Confirm and pin with a test.
2. **Confinement code reuse** — reuse the existing `build`/MCP confinement
   diagnostic vs a new `DEDIREN_PACKAGE_OUTPUT_ESCAPES_ROOT`. Prefer reuse if the
   existing code's message fits; decide when the throw-site is written.
3. **Shared per-view pipeline factoring** — extract the per-view stage pipeline
   from `BuildCommand` for reuse by `PackageBuildCommand`, vs a thin internal
   call into the existing command. Decide by which keeps `BuildCommand` off the
   god-class watchlist; do not copy stage logic.
4. **`presentation.diagram_kind`** — carried/echoed only (spec), or does any
   Dediren surface consume it? Default: opaque echo, consumed by nobody in
   Dediren (the gallery, consumer-side, uses it). Keep it echo-only unless a
   test demands otherwise.
