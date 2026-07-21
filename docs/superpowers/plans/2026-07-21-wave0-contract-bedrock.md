# Wave 0 — Contract Bedrock Implementation Plan

Status: **complete (2026-07-21).** Parent:
`2026-07-21-future-feature-roadmap-survey.md` (Theme 1, Wave 0). Each item
landed as its own commit with tests first: task 1 = envelope gap (5259c11),
task 2 = identity tripwire (3dc120f), task 3 = policy validate (dde044f),
task 4 = truthfulness sweep (9e70146), task 5 = migration steps (f5dab0e),
task 6 = decided-not-built (design note below). The cross-cutting repeated
`--target` quick win was re-examined and declined — see Task 6.

**Goal:** repair the contract's load-bearing promises — every failure decidable
from stdout JSON, no silently wrong deliverables, no schema values the tool can
never emit, migration steps as data — without any breaking change to working
documents.

**Out of wave:** MCP resources (survey 1.4, Wave 1), per-stage MCP tools,
`--artifact-out`. The `required_plugins[]` model-schema removal stays parked
(big-bang trap) — this wave only documents it as unenforced.

---

## Task 1 — Close the stdout envelope gap (survey 1.1)

Structural failures must produce error envelopes on stdout, exit 2 preserved.
Today three classes bypass the envelope (stderr + empty stdout + exit 2):
missing `plugins.generic-graph`, unknown view, unsupported `project` target.

Design (verified against live code):

- The enveloped idiom already exists one level up: `EngineException` thrown
  inside `EngineDispatch.dispatch` becomes the published error envelope with
  the exception's exit code. `EngineException.semanticFailure` pins exit 3
  (engine-boundary policy); structural failures are input-shaped and keep
  their published exit 2 — so add a parallel factory
  **`EngineException.structuralFailure(code, message, path)` → exit 2** in
  `engine-api`, with the same "policy lives at the boundary" javadoc rationale.
- New `DiagnosticCode` entries (`contracts`):
  `GENERIC_GRAPH_PLUGIN_REQUIRED`, `GENERIC_GRAPH_VIEW_UNKNOWN`,
  `COMMAND_TARGET_UNSUPPORTED` (all under already-documented code families).
- Throw-site conversions:
  - `SemanticsRouterEngine.pluginData` (missing `plugins.generic-graph`) →
    `structuralFailure(GENERIC_GRAPH_PLUGIN_REQUIRED, …, "/plugins")`.
  - `SemanticsRouterEngine.prepareProjection` (unknown view) →
    `structuralFailure(GENERIC_GRAPH_VIEW_UNKNOWN, …,
    "/plugins/generic-graph/views")`.
  - `CoreCommands.projectCommand` (unsupported target) → return
    `errorOutcome` with `COMMAND_TARGET_UNSUPPORTED` (verify `errorOutcome`
    exit is `INPUT_ERROR`; else construct the outcome explicitly).
  - `SemanticsRouterEngine` catch-and-wrap sites (arbitrary `IOException`
    from `SceneProjection`) and `SceneProjection.pluginData`: these are
    post-validation internal invariants, not user input errors — let them
    reach dispatch as runtime failures and be enveloped as
    `DEDIREN_ENGINE_FAILED` (honest: "not an input problem; report").
    `SceneProjection.pluginData` switches to a checked `IOException` (its
    duplicate missing-plugins check is unreachable in practice — the router
    resolves plugin data first).
- Deletions once no site throws it: the `UncheckedIOException` special case in
  `EngineDispatch.dispatch`/`dispatchInMemory` (javadoc outcome list updated),
  `Main.printStructuralFailure` + its two catch sites, and the
  `UncheckedIOException` catches in `mcp-server` `DedirenTools` (verify their
  current emission first; MCP behavior change rides the same envelopes).

Steps:

- [x] RED: CLI tests asserting stdout error envelope + exit 2 + code for all
      three classes (`validate --plugin/--profile` missing-plugins;
      `project` unknown view; `project --target bogus`); extend
      `CliMcpParityTest` for the MCP-reachable class (validate-with-profile,
      missing plugins block) asserting CLI/MCP byte-identical envelopes
      (`CliStructuralEnvelopeTest` + parity fixture; RED confirmed empty
      stdout on CLI and a diverging `COMMAND_INPUT_INVALID` wrap on MCP).
- [x] GREEN: factory + codes + throw-site conversions + deletions above.
      Bonus fold: build's per-view diagnostics now carry the engine's
      structural code verbatim (`GENERIC_GRAPH_VIEW_UNKNOWN`) instead of
      rewrapping as `COMMAND_INPUT_INVALID`; `runStage`'s dead
      UncheckedIOException catch removed.
- [x] Update tests pinning the old observable (`EngineDispatchTest`
      propagate-unchanged pair → burial-is-correct; `BuildCommandTest` fake +
      code assertions; `CliBuildCommandTest` per-view code) — updated, not
      worked around. `RouterHarness` needed no change.
- [x] Docs: three explicit `## Repair Rules` entries in
      `docs/agent-usage.md`.
- [x] Behavior change noted for release notes in the commit body.
      Verified: module suites, full `-Pquality verify`, dist-smoke.

## Task 2 — Placeholder-identity tripwire (survey 1.2a)

- [x] RED: export-engine tests — OEF/XMI export with the shipped default
      policy identity emits the `DEDIREN_EXPORT_IDENTITY_PLACEHOLDER` warning;
      a real-identity policy emits nothing. Trigger keyed on
      `model_identifier` equality (the false-positive-proof signal).
- [x] GREEN: shared `DiagnosticCode`; per-engine placeholder constants with
      drift-pin tests against the shipped `default-*.json` fixtures.
- [x] `build` lane + decomposed `export` + packaged bundle all pin the
      warning: lane tests flipped to the truthful `warning` expectation where
      they deliberately run the default policy; the dist-smoke now REQUIRES
      the warning end-to-end (packaging-survival proof); the envelope-contract
      suite gained the warning-envelope row. Test-harness `Main`s (both export
      engines) were also fixed: they hardcoded `status: ok` for any
      diagnostics — now they mirror the real ok/warning/info-ride-ok policy.
- [x] Docs: `## Repair Rules` entry + `## Export` and both omission sections
      note the warning; documented-workflow tests updated accordingly.
- [x] `--strict-identity` escalation deferred until demanded.

## Task 3 — Policy validation via `validate` (survey 1.2b)

Pre-legitimized open door (schema-migration spec "Known asymmetry").

- [x] RED: `validate --input <policy.json>` dispatches on the document's
      schema-version field: current → `ok`; stale → `SCHEMA_VERSION_OUTDATED`
      (incl. the legacy `svg_render_policy_schema_version` wrinkle); unknown
      version → `SCHEMA_VERSION_UNKNOWN`; current-but-malformed →
      `SCHEMA_INVALID` against the policy's own schema.
- [x] GREEN: new `core/source/DocumentValidator` — family detection over
      `KnownSchemaVersions` fields (model wins; unrecognized input keeps
      today's model path byte for byte), then `SchemaVersionGate` + JSON
      Schema; covers all registered non-model families incl. layout-request.
- [x] MCP `dediren_validate` routes through the same entry (parity fixture
      added); `ToolSchemas` + tool description updated. Threat-model MCP rows
      reviewed: no change needed — the policy path is an ordinary confined
      tool path; fragment confinement applies only to the model path, which
      is untouched. Packaged smoke unaffected.
- [x] Docs: `## MCP Server` bullet, `## Export` early-validate pointer, and a
      dated "Known asymmetry: closed" note in the schema-migration spec's
      amendment section.

## Task 4 — Contract truthfulness sweep (survey 1.3)

- [x] `render-result.schema.v4 → v5`: dropped `"html"` from `artifact_kind`
      and `"base64"` from `encoding`; bumped `ContractVersions` + the three
      version pins (round-trip, versions test, render MainTest); removed
      `BuildCommand.renderExtension`'s dead html branch. Only production
      consumer of the dead values was that branch. Verified with a `clean`
      full build (inlined-constant staleness trap).
- [x] Deleted `plugin-manifest.schema.json` + `runtime-capability.schema.json`,
      the `contracts.plugin` records, `PLUGIN_PROTOCOL_VERSION`, and their
      test entries (incl. the orphan round-trip test — which also removes one
      product-version assertion surface from the bump sweep). No dist-tool
      edit needed: schemas bundle via directory copy. Features docs updated
      (orphan row removed; engine-runtime notes the cleanup).
- [x] `required_plugins[]` documented as informational/unenforced in
      agent-usage (prose note after the Minimal Source JSON example); its
      removal waits for the next warranted `model.schema` bump.

## Task 5 — Machine-readable migration steps (survey Shape B, spec amendment
2026-07-21)

- [x] Data: `MigrationOperation` + `MigrationPath` records in `contracts`;
      `Family` gains `steps` with a compact-constructor invariant (one chained
      step per superseded version — a bump structurally cannot ship without
      its ops). Three render-policy steps as ops; `layout-request v1→v2`
      carries the `regenerate` marker (judgment-bound hop).
- [x] Delivery: `SchemaVersionGate` composes found→current (concatenated
      steps, intermediate `set_version` writes pruned; `regenerate` anywhere
      collapses the path) and attaches it via `Diagnostic.withMigration` —
      new optional 6th record component, NON_NULL so existing envelopes stay
      byte-identical. `envelope.schema.json` + `build-result.schema.json`
      diagnostic defs gained the optional `migration` object (additive, no id
      bump).
- [x] Pinning: completeness is constructor-enforced;
      `MigrationPathApplicationTest` (core) is the application oracle — a
      test-only reference applier runs each shipped path against stale
      fixture documents and the outcome must equal the expected document AND
      pass the family's gate + JSON Schema; `SchemaVersionGateTest` pins
      composition/pruning/collapse; `CliValidateTest` pins the wire shape end
      to end; `MigrationRegistryTest` prose pinning unchanged.
- [x] Docs: `## Migration` intro documents the `migration` object and the
      four-op vocabulary; "you are the hands; dediren never rewrites the
      file". Spec amendment holds.

## Task 6 — Repeated `--target` on `project` (survey cross-cutting)

Design decision needed before code (envelope `.data` is single-target today):
composite `.data` keyed by target vs. multi-artifact list vs. leaving the CLI
double-pass and only documenting. Decide when reached, as its own mini-design
note in this file; do not improvise a contract shape mid-implementation.

- [x] **Design note (2026-07-21): not built — evidence-gated with its
      siblings.** Every shape that collapses the two `project` passes into
      one invocation changes the `project` envelope contract: a composite
      `.data` keyed by target is a new result shape every consumer must
      learn (and polymorphic against the single-target form), a
      multi-artifact list re-invents the render-result artifacts wrapper for
      a stage that today emits the artifact *as* `.data`, and two envelopes
      on one stdout breaks one-envelope-per-command. Against that permanent
      contract surface, the win is small: `build` already covers the common
      path in one call; in decomposed mode the two passes are mechanical and
      an agent issues them in a single shell block — one round-trip in
      practice, with only a second JVM start as real cost. The honest
      smallest change is no change. Revisit together with `--artifact-out`
      and per-stage MCP tools under the same evidence gate this plan already
      set for them: transcript evidence that decomposed-mode UML flows are
      frequent (and, for MCP surface widening, the mcp-server coverage debt
      paid first).

---

## Verification

Per item: module lanes (`./mvnw -pl <touched>,cli -am test`, fuzz tests
excluded in-sandbox via `-Dtest='!*FuzzTest'` where applicable), then per
CLAUDE.md: full `./mvnw test`, `-Pquality verify` before each commit, and the
MCP/dist-smoke gate (`./mvnw -pl dist-tool -am verify -Pdist-smoke`) for Tasks
1 and 3 (MCP-visible behavior) and Task 4 (bundle contents change). Docs-only
edits: `git diff --check`.

No version bump in this wave's commits — releases are cut separately per
release-policy; behavior changes are flagged for release notes in commit
bodies.
