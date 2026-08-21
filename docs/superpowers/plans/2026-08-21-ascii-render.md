# ASCII render support (engines/ascii-render)

## Context

Agents authoring plans and reviews frequently sketch small graphs as Mermaid or
Graphviz DOT snippets. Dediren already imports both (`mermaid-import`,
`dot-import`) and lays them out with ELK, but the only render lane is SVG — an
agent cannot present a "before → after" diagram inline in a terminal plan or
markdown document. This change adds a **text render engine** so that
`mermaid/dot snippet → dediren → ASCII/Unicode box diagram` works end to end:
from the CLI (`dediren render --plugin ascii`) and in one MCP call
(`dediren_import` with `output: "text"`, fully in memory — the ideal
agent-writes-plan path).

## Settled decisions (user-approved 2026-08-21)

- **New module `engines/ascii-render`** (package `dev.dediren.plugins.asciirender`),
  engine id `ascii`, implementing the existing `RenderEngine` seam
  (`engine-api/.../RenderEngine.java`: `render(LaidOutScene, JsonNode policy,
  RenderMetadata) → EngineResult<RenderResult>`). Deps: `contracts`,
  `engine-api`, `ir` only; zero new third-party dependencies.
- **Charset policy-selectable**: `render-policy.schema.json` v3→v4 adds optional
  `text: {charset: "unicode"|"ascii"}`, default unicode box-drawing. Registered
  family → full ceremony (migration path, agent-usage `## Migration` subsection,
  fixture bumps + new `fixtures/render-policy/ascii-text.json`).
- **`render-result.schema.json` v5→v6**: `artifact_kind` enum → `["svg","text"]`.
  Engine-seam schema, no family: constant + pins + `BuildCommand.renderExtension`
  (`"text"→"txt"`, the seam its comment reserved).
- **Surfaces**: standalone `dediren render --plugin ascii` (already
  engine-agnostic — `CoreCommands.renderCommand` takes `--plugin`) and MCP
  `dediren_import` `output: "text"` (envelope first, diagram as second
  TextContent). **Out of scope**: `dediren build` lanes and the package model —
  documented resting state (drawio exporter precedent,
  `docs/features/exports.md` §Known limits).
- ELK routes orthogonally by default (`ElkLayeredOptions.java:287`);
  polyline/spline routes degrade via L-routing + diagnostic.

## Design essentials

**Algorithm — anchor-preserving quantization** (no overlap-repair pass):
collect distinct x/y anchors (node/group borders, edge points), epsilon-merge
0.5px, sort; consecutive gap = `max(1, round(delta/SCALE))` (SCALE_X=8,
SCALE_Y=16 px per cell) — strict monotonicity preserves ordering, containment,
and non-overlap by construction; then content-driven widening (per-node inner
width from wrapped label, deficit added to the interval's last gap via prefix
sums; caps 32 cols / 6 lines, truncate with diagnostic).

**Classes**: `AsciiRenderEngine` (orchestration, mirrors `SvgRenderEngine`),
`CoordinateGrid`, `CharCanvas` (cell = line bitmask N/E/S/W or literal;
junctions/crossings become table lookups), `GlyphSet` (UNICODE `─│┌┐└┘├┤┬┴┼▶`
/ ASCII `-|+ >v^<`), `NodeBox`, `GroupBox` (label in top border, drawn first),
`EdgeTracer` (L-route degrade for non-orthogonal segments), `EdgeLabelPlacer`.
Paint order: groups → edges → nodes (interior clear wins) → node labels → edge
labels.

**Diagnostics** (contiguous `DiagnosticCode` block, all five documented
individually in agent-usage `## Repair Rules`): `DEDIREN_ASCII_EDGE_APPROXIMATED`,
`_EDGE_LABEL_DROPPED`, `_LABEL_TRUNCATED`, `_POLICY_INVALID` (error, exit 3),
`_SEQUENCE_VIEW_GENERIC` (sequence views render generically + warn, never fail;
detection reimplemented over contracts `RenderMetadata` — ArchUnit forbids
importing `plugins.render`).

**Key hazards already grounded in code**:
- `JsonSupport` sets `FAIL_ON_UNKNOWN_PROPERTIES=true` → `RenderPolicy` must
  gain the `text` field in the same change as the schema (SVG engine then
  tolerates the block silently).
- `KnownSchemaVersions.RENDER_POLICY` v2→v3 path uses the constant for its
  `to`/`setVersion` — replace with the `"render-policy.schema.v3"` literal when
  appending the v4 hop (`setVersion`-only step). `SchemaVersionGate.composedPath`
  prunes intermediate set_versions → `fixtures/package-build-result/stale-policy-migration.json`
  changes op-chain shape, not just strings.
- MCP: `DedirenTools.importSource` line ~148 short-circuits on
  `selection == ImageSelection.NONE` — `"text"` must branch **before** the
  ImageSelection plumbing or it silently degrades to data mode. `outputArg` is
  shared with build → per-tool allowed sets. New render path via a
  `CoreCommands.renderImportedMain` overload taking the render-engine id.
- Registration follows the drawio checklist (commit `6ea22a7`); **no**
  bundle-shrink rule, **no** THIRD_PARTY_ATTRIBUTIONS, **no**
  DistHermeticityTest change. New agent-usage `##` heading requires a
  `GuideCatalog.TOPICS` entry (bidirectional test).

## Plan contract (validated)

v3 contract at
`/tmp/claude-1000/-home-souroldgeezer-repos-dediren/1ac5e410-c23e-49df-9993-c686339697e3/scratchpad/ascii-render-plan.json`
— validator: `valid: true`, `dispatch_ready: true`, `standard_ready_ratio: 1.0`,
0 errors/warnings. Copy it into the repo (e.g.
`docs/superpowers/plans/2026-08-21-ascii-render.json` + prose plan) at execution
start, then `planning_ledger.py init-v3`.

### Leaves (TDD: each writes its failing test first; edit-only — parent runs all Maven)

| # | Leaf | Tier/size | Gist | Acceptance |
|---|------|-----------|------|------------|
| 1 | `render-policy-v4` | standard/L | Schema v4 + `TextRenderPolicy`/`TextRenderCharset` + family append + migration subsection + 6 fixture bumps + `ascii-text.json` + full enumerated v3-pin sweep (contracts/core/cli/render tests, stale-policy-migration fixture, `docs/architecture/dediren.dediren/render-policy*.json`, `docs/assets/pipeline.render-policy.json`; superpowers history untouched) | `./mvnw test` |
| 2 | `render-result-v6` | mechanical/S | Enum widen + constant + 4 pins + `renderExtension` `text→txt` | `./mvnw test` |
| 3 | `module-scaffold` | mechanical/M | Module + skeleton engine + registration: root pom (modules + PIT), cli/coverage-report poms, `EngineWiring`, `DistTool.FIRST_PARTY_ARTIFACTS`, `ArchitectureRulesTest` ×3 spots, architecture-guidelines edge table | `./mvnw -pl dist-tool -am test` |
| 4 | `ascii-grid` | standard/M | `CoordinateGrid` + `CharCanvas` + `GlyphSet` + unit tests | `./mvnw -pl engines/ascii-render -am test` |
| 5 | `ascii-renderers` | standard/L | `NodeBox`/`GroupBox`/`EdgeTracer`/`EdgeLabelPlacer` + full engine + `DiagnosticCode` block + golden-string tests (both charsets, degrades, sequence warning) | `./mvnw -pl engines/ascii-render -am test` |
| 6 | `cli-envelope` | mechanical/S | `EngineEnvelopeContractTest` ok + error rows (no CLI production code) | `./mvnw -pl engines/ascii-render,cli -am test` |
| 7 | `mcp-text-mode` | standard/M | `ToolSchemas.IMPORT` enum, `DedirenTools` text branch, `renderImportedMain` overload, unit + engine-backed tests | `./mvnw -pl mcp-server,cli -am test` |
| 8 | `docs-sweep` | standard/M | agent-usage (`## ASCII Render` + Repair Rules ×5 + MCP bullet + drawio-list fix), `GuideCatalog` topic, README, docs/features (incl. documented resting state), threat-model MCP bullet, CLAUDE.md verification lane | `./mvnw -pl dist-tool -am test` |
| 9 | `dist-smoke` | mechanical/S | Packaged-bundle CLI ascii render step + `assertTextRenderOutput`; MCP stdio request 11 (`output:"text"`) + assertion | `./mvnw -pl dist-tool -am verify -Pdist-smoke` |

Dependencies: 1→2→3→4→5→{6,7}; 8 after {5,7}; 9 after 8. Leaves 6/7 are
parallel; leaf 8 owns all agent-usage prose to avoid conflicts (leaf 5's new
codes make dist-tool's `AgentUsageDocConsistencyTest` red until 8 lands — its
own lane stays green).

## Execution mechanics

- Worktree `.worktrees/ascii-render` (copy wrapper jar, absolute
  `-Dmaven.repo.local`); delegated subagent leaves receive absolute paths and
  never run Maven; parent runs every acceptance command centrally
  (sandbox-off or `MAVEN_OPTS="-Djava.io.tmpdir=$TMPDIR"` per known gotcha).
- Integration: `--no-ff` merge or rebase onto `main` per git-workflow-policy;
  closeout removes worktree + branch after `git branch --merged` check.
- No version bump unless a release is requested afterward (release-policy).

## Verification

1. Per-leaf acceptance lanes above (RED observed before implementation, per
   tdd-policy).
2. Final gate: `./mvnw -Pquality spotless:apply` → `./mvnw test` →
   `./mvnw -pl dist-tool -am verify -Pdist-smoke` → `git diff --check`.
3. End-to-end proof (the user's use case): pipe a small mermaid flowchart
   through `dediren import --plugin mermaid` → `project` → `layout` →
   `render --plugin ascii` and eyeball the diagram; same via MCP
   `dediren_import {output: "text"}` in the dist smoke.
4. Audit gates (plan-based work): quick `test-quality-audit` over the new
   golden/unit tests + quick `devsecops-audit` (new module boundary, no new
   deps) before closeout.

## Recorded findings (out of scope, user-confirmed 2026-08-21)

- **Drawio build-lane integration**: the drawio exporter is reachable only via
  standalone `export` — no build/package lane selects it, output is unstamped
  and invisible to `verify`/`status` (documented in `docs/features/exports.md`
  §Known limits). At execution closeout, file a GitHub issue (via GitHub MCP —
  no `gh` on PATH) proposing the slice: `package.schema.json` `lane` enum +
  build driver selection + stamping + verify/status suffix coverage + docs.
  Not part of this change.

## Open items tracked in the contract

- Whether dist-tool `SourceFixtureVersionConsistencyTest` pins render-policy
  versions (check during leaf 1).
- Exact pruned op chain in `stale-policy-migration.json` (derive from
  `composedPath` semantics; the failing test confirms).
