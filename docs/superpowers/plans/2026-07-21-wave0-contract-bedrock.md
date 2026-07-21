# Wave 0 — Contract Bedrock Implementation Plan

Status: in progress (started 2026-07-21). Parent:
`2026-07-21-future-feature-roadmap-survey.md` (Theme 1, Wave 0). Each item is
independently shippable and lands as its own commit(s) with tests first.

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

- [ ] RED: `validate --input <policy.json>` (no plugin/profile) dispatches on
      the document's schema-version field: current → `ok` envelope; stale →
      `SCHEMA_VERSION_OUTDATED`; unknown → `SCHEMA_VERSION_UNKNOWN`; plus
      JSON-Schema validation against the matching policy schema.
- [ ] GREEN: route through the existing `SchemaVersionGate` + schema
      validation; family detected from the version field name/value
      (`KnownSchemaVersions` already records legacy field names).
- [ ] MCP `dediren_validate` gains the same acceptance (no new tool;
      `ToolSchemas` description updated). MCP rows of `docs/threat-model.md`
      reviewed (no new write primitive — expected no-op), packaged-MCP smoke
      updated if tool description text is pinned there.
- [ ] Docs: agent-usage `## MCP Server` + validate sections.

## Task 4 — Contract truthfulness sweep (survey 1.3)

- [ ] `render-result.schema.v4 → v5`: drop `"html"` from `artifact_kind` and
      `"base64"` from `encoding`. Generated engine-seam family: bump
      `ContractVersions` constant, fixtures, mapping code, round-trip tests —
      no migration family, no working-document cost. (Grep first for any
      consumer of the dead branches; expect none.)
- [ ] Delete orphaned `plugin-manifest.schema.json` +
      `runtime-capability.schema.json`, their `contracts` records, and their
      `ContractRoundTripTest` entries; drop the bundle copies (dist-tool
      manifest/test updates).
- [ ] Document `required_plugins[]` as informational/unenforced where it is
      described (`docs/features/source-model.md` already says it; ensure
      agent-usage says it too); its removal waits for the next warranted
      `model.schema` bump.

## Task 5 — Machine-readable migration steps (survey Shape B, spec amendment
2026-07-21)

- [ ] Data: per-step operation lists (`rename_field` / `remove_key` /
      `set_version`, JSON-Pointer targets) for the three render-policy steps,
      as pure data in `contracts` beside `KnownSchemaVersions` (records only,
      no logic — `contracts` stays dumb). `layout-request v1→v2` is
      regenerate-first and partially judgment-bound: its entry carries a
      `regenerate` marker instead of ops.
- [ ] Delivery: `SchemaVersionGate` attaches the matching op list to the
      `SCHEMA_VERSION_OUTDATED` diagnostic — additive-optional field on the
      diagnostic object in `envelope.schema.json` (output schema; additive →
      no id bump; verify round-trip fixtures).
- [ ] Pinning: extend `MigrationRegistryTest` three ways — every prior
      version has ops-or-marker; every ops list has a fixture pair (stale doc
      + expected outcome) and applying ops to the stale fixture yields the
      expected outcome; prose subsections still exist per step.
- [ ] Docs: `## Migration` notes the machine-readable delivery; guide
      `migration` topic unchanged otherwise. Dediren still never applies
      steps (spec amendment holds).

## Task 6 — Repeated `--target` on `project` (survey cross-cutting)

Design decision needed before code (envelope `.data` is single-target today):
composite `.data` keyed by target vs. multi-artifact list vs. leaving the CLI
double-pass and only documenting. Decide when reached, as its own mini-design
note in this file; do not improvise a contract shape mid-implementation.

- [ ] Design note appended here; then RED/GREEN accordingly.

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
