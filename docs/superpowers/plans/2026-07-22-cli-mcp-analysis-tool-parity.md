# CLI/MCP Analysis-Tool Parity

## Status

**Implemented 2026-07-22** on branch `plan/cli-mcp-parity` (worktree
`.worktrees/cli-mcp-parity`), not yet integrated. All four read-only analysis
tools shipped; per-stage tools deferred as planned. Verification green:
`-pl core,cli,mcp-server -am -Pquality verify` (spotless + SpotBugs + tests),
`-pl mcp-server -am -Pcoverage verify` (branch floor held), and
`-pl dist-tool -am verify -Pdist-smoke` (packaged 7-tool MCP stdio smoke + doc
consistency). **Audit gates run 2026-07-22** — both returned no blockers; all
warn/info findings fixed or accepted (see `## Audit Outcomes`).

## Audit Outcomes

`test-quality-audit` (Deep) and `devsecops-audit` (Quick) both returned **no
block findings**. Remediation applied in-branch:

- **Fixed — MCP path-leak (devsecops P1):** `verify`/`status`'s
  `UncheckedIOException` catches passed `failure.getMessage()` (which can carry a
  resolved absolute path) to the model. Sanitized via a new `ioFailure` helper
  (stderr-only detail, path-free envelope), matching the file's `pathEscape`/
  `readFailure` discipline. `build`'s catch is deliberately left verbatim — its
  message is pinned lane-for-lane to the CLI by `CliMcpParityTest`, and the CLI
  lane intentionally surfaces the path for human debugging.
- **Fixed — confinement wiring unproven (test-quality P0):** added fragment-escape
  tests for `diff`/`query`/`verify` in `DedirenToolsTest`, proving each threads
  `--root` as `confinementRoot` (the threat-model's "Pinned by" claim is now true
  per-tool).
- **Fixed — `AnalysisCommands` had no direct tests (root cause):** added
  `core/.../commands/AnalysisCommandsTest.java` covering every early-exit gate and
  result branch (all three `query` kinds + `dependents`-valid-id + unknown-node +
  null/bad kind; `verify` unstamped/stale/not-a-dir; `status` happy/not-a-dir;
  `diff` ok/source-error). Closes the `verify`-stale, `query`-kinds, and
  directory-gate coverage gaps at the source.
- **Fixed — `query` enum drift (P1):** exposed `AnalysisCommands.QUERY_KINDS` and
  added a `ToolSchemasTest` guard mirroring the `emit`/`EMIT_KINDS` pattern.
- **Addressed — `.strip()` parity idiom (P1):** documented in `CliMcpParityTest`
  that the trailing-newline difference it absorbs (CLI `println` vs MCP
  newline-free serialization) is immaterial and agent-invisible; the four analysis
  commands are newline-free on both lanes, so their equality is genuinely
  byte-for-byte.
- **Accepted (info) — CLI stdout trailing newline:** the four commands moved from
  `println` to `writePluginOutcome` (no trailing newline), an intentional
  alignment with the existing `build`/`layout`/`render`/`export` convention;
  agent consumers parse JSON, so it is immaterial.
- **Noted (pre-existing, out of scope) — `build`'s `UncheckedIOException` message**
  can carry an absolute path over MCP; it is parity-locked to the CLI lane and
  predates this change. A separate decision if the project wants MCP-lane
  sanitization there (it would require the CLI lane to sanitize too, or the parity
  gate to special-case it).

## Purpose

Bring the MCP tool surface toward CLI parity by exposing the four read-only
**analysis / provenance** commands as MCP tools: `diff`, `query`, `verify`,
`status`. Keep the five per-stage **pipeline** commands (`project`, `layout`,
`validate-layout`, `render`, `export`) deferred, now with a documented rationale.

## Scope Decision (re-evaluation outcome)

This plan re-opens two prior decisions on the MCP surface, per an explicit
request to re-evaluate:

1. **MCP spec decision #3** (`2026-07-14-dediren-mcp-server-design.md`):
   "curated tool surface" of three tools; the per-stage CLI commands "remain
   CLI-only … the decomposed debug form." Its context-cost rationale is still
   real, but it was a blanket rule made **before** the analysis commands
   existed.
2. **Roadmap survey gates** (`2026-07-21-future-feature-roadmap-survey.md`,
   settled 2026-07-21): MCP surface widening (per-stage tools *and* MCP twins of
   `diff`/`query`/`verify`/`status`, filed together under the "per-stage door")
   deferred behind **(a) transcript evidence** of decomposed-mode need and
   **(b) paying the `mcp-server` branch-coverage debt** first.

Re-evaluation:

- Gate (b) is **already paid** — `recorded-deferrals.md:15` marks the
  mcp-server coverage debt RESOLVED 2026-07-22; `./mvnw -pl mcp-server -am
  -Pcoverage verify` passes. (Re-verified as step 0 below; the surface grows, so
  the floor must stay green.)
- Gate (a) is **effectively unmeetable**: this is a redistributed CLI/MCP tool
  with no downstream telemetry, so "wait for transcript evidence" is a
  defer-forever mechanism. The request for parity is the intent that gate was
  waiting for.
- The four analysis commands are **not** build decompositions — `build` cannot
  diff two models, answer a dependency query, or check artifact provenance. The
  "subsumed by build" argument that justifies keeping per-stage tools out does
  **not** apply to them. They are read-only, safe under `--read-only`, and were
  the survey's own "flagship" value.

**Decision:** expose `diff`, `query`, `verify`, `status` as MCP tools now; keep
the five per-stage tools deferred (see `## Documented Deferral`).

**Non-finding, recorded so it is not re-raised:** the existing three tools are
already at full parity. The apparent `validate` divergence (MCP hardcodes engine
`generic-graph`, CLI takes `--plugin`) is not one — `generic-graph` is the sole
semantics engine id; the router selects graph/archimate/uml by `profile`, which
MCP already exposes. MCP hardcodes the only value.

## What Ships

Four new read-only MCP tools, registered in **both** default and `--read-only`
mode (unlike `dediren_build`, which stays gated to default mode):

| Tool | Arguments | Confinement |
|---|---|---|
| `dediren_diff` | `old` (source path), `new` (source path) | both `resolveExisting`; fragments confined to root |
| `dediren_query` | `source` (path), `kind` (`dependents`\|`orphans`\|`view-coverage`), optional `id` | `resolveExisting`; fragments confined |
| `dediren_verify` | `source` (model path), `artifacts` (directory path) | both `resolveExisting` (dir supported) |
| `dediren_status` | optional `dir` (directory path, default = server root) | `resolveExisting`; no source load |

Surface grows **3 → 7 tools**. Honest cost note: every registered tool taxes
each request (name + description + input schema), so this is a real context-cost
increase — accepted because each of the four does something `build` cannot and
is read-only model intelligence, not a debug decomposition.

## Findings That Shape the Work

1. **Parity is gated.** `cli/.../CliMcpParityTest.java` asserts byte-identical
   envelopes through both lanes ("handler parity"); `EngineEnvelopeContractTest`
   is the stage-command envelope regression. Each new tool needs a parity
   fixture: a happy path and an error path.
2. **Orchestration inversion (the core of the work).**
   `validate`/`build`/`project`/`layout`/`render`/`export` already call shared
   `core` entry points; **`diff`/`query`/`verify`/`status` build their envelopes
   inline in `cli/Main.java`.** Parity's invariant — "both lanes call the same
   `core` entry point" — therefore requires **lifting each analysis command's
   orchestration and input-validation out of `Main.java` into `core` first.**
   The higher-value tools cost more to expose than the per-stage tools would
   (which are already core-backed) — but they are worth it and the per-stage
   ones are not.
3. **Confinement infrastructure already exists** — no new plumbing:
   - `SourceValidator.loadAndValidateSourceDocument(text, baseDir,
     confinementRoot)` — the confinement-aware overload. CLI passes
     `confinementRoot = null`; MCP passes the server root. (`diff`/`query`/
     `verify` load a source; `status` does not.)
   - `WorkspacePaths.resolveExisting(root, candidate)` resolves and confines
     directories as well as files (`toRealPath` + `startsWith`), so
     `verify --artifacts` and `status --dir` need no special case.
4. **Coverage floor is green** for `mcp-server` (step 0 re-verifies it).
5. **No new diagnostic codes expected.** The four commands already use
   `COMMAND_INPUT_INVALID`, `ARTIFACT_STALE`, `ARTIFACT_UNSTAMPED`,
   `PRODUCT_ROOT_UNRESOLVED`; MCP adds `MCP_PATH_OUTSIDE_ROOT` on escape — all
   existing. Confirm during implementation; any new code triggers a
   `## Repair Rules` entry in `docs/agent-usage.md` and the reverse-token test.
6. **`verify`/`status` report paths *relative to the passed directory*** —
   `ProvenanceCheck` reports `relative(dir, file)` normalized to forward slashes
   (`ProvenanceCheck.java:45,57,66,125`). This is what makes byte-identical
   parity possible for the two path-*reporting* commands: the CLI passes the raw
   `--artifacts`/`--root` and MCP passes the `toRealPath()`-resolved directory,
   but each `relativize`s against its **own** base, so both emit the identical
   relative remainder. Pinned empirically today: `CliProvenanceTest:66` asserts
   `verify` output path `"main/diagram.svg"` and `:119` asserts `status` output
   path `"out/main/diagram.svg"`. **Consequence: verify/status stay pure
   refactors** — the shared core entry point must keep reporting relative paths;
   it must never echo an absolute or as-passed path, or the two lanes diverge.
   A parity fixture guards this (see X.4).

## Task Breakdown (TDD; one command at a time)

No shared prerequisite task — the confinement overload already exists. For each
command `X ∈ {diff, query, verify, status}`, the parity fixture is the
acceptance test; the existing CLI command tests are the refactor safety net that
keeps CLI output byte-identical (`CliDiffQueryTest` for `diff`/`query`,
`CliProvenanceTest` for `verify`/`status`).

Two sub-shapes, both confirmed pure refactors:

- **Content-output (`diff`, `query`)** — envelopes describe model content (node
  ids, change records), no filesystem paths. Straightforward extraction.
- **Path-output (`verify`, `status`)** — envelopes report artifact/model paths.
  Parity is safe *only* because those paths are relative to the passed directory
  (Finding 6); the extraction must preserve that. Not a behavior change, but the
  invariant it rests on is load-bearing and must be fixture-guarded.

- **X.1 — Extract the core entry point.** Add `CoreCommands.<x>Command(...)`
  (or a new `AnalysisCommands` class if `CoreCommands` grows unwieldy) returning
  `EngineRunOutcome(String stdout, int exitCode)` — the serialized envelope
  string + exit code. This is the same return shape `layout`/`render`/`export`
  use, and returning the pre-serialized bytes is what makes byte-identical
  parity trivial. Move into it:
  - Envelope + diagnostic construction currently inline in `Main.java`.
  - Input validation currently inline in `Main.java`, so **both** lanes enforce
    it identically: `query`'s `kind` whitelist, `dependents`-requires-`id`, and
    unknown-node-id check; `verify`'s "`--artifacts` must be an existing
    directory"; `status`'s "`--root` must be an existing directory".
  - Signature takes already-read text + `baseDir` + `confinementRoot` (nullable),
    plus resolved directory `Path`s for `verify`/`status`. It never reads
    argument paths itself — reading (stdin/file for CLI, confined file for MCP)
    stays in the callers.
- **X.2 — Slim the CLI command.** `Main.XCommand.call()` becomes: read input(s)
  (unchanged path/stdin handling), call the core entry point, write
  `outcome.stdout()` and return `outcome.exitCode()`. CLI-observable behavior is
  unchanged (guarded by existing CLI tests).
- **X.3 — Add the MCP handler + schema + registration.**
  `DedirenTools.<x>(request)` (confine paths via `WorkspacePaths`, read files,
  call the *same* core entry point, wrap the envelope with the `isError` flag);
  `ToolSchemas.X`; register in `DedirenMcpServer.create(...)` in **both** modes.
- **X.4 — Parity fixture.** Add happy-path and error-path cases to
  `CliMcpParityTest` asserting byte-identical envelope text and the `isError` ↔
  `exitCode != 0` mapping, following the existing `validate` cases. For
  `verify`/`status`, the happy-path fixture is also the guard on Finding 6: it
  passes the CLI a relative dir and MCP the confined (real-path) dir over the
  same on-disk tree and asserts identical bytes — a regression to absolute or
  as-passed path reporting fails it. Include a case whose input dir reaches the
  tree through a symlink or a `..` segment, since that is exactly where a naive
  path echo would diverge.

## Files That Move Together

Per the CLAUDE.md "MCP surface changes" rule, in the same change:

- **mcp-server**: `DedirenTools` (4 handlers), `ToolSchemas` (4 schemas),
  `DedirenMcpServer` (register 4 tools, both modes). `GuideCatalog` only if a
  guide topic enumerates the tools.
- **`docs/agent-usage.md` `## MCP Server`**: "Three tools" → seven; document the
  four (args, read-only availability). Confirm no new `DEDIREN_*` token is
  introduced; if one is, add a `## Repair Rules` entry (reverse-token test).
- **`docs/threat-model.md`**: MCP rows for the four — all read-only, all
  `--root`-confined; note `verify`/`status` take directory arguments and `diff`
  takes two source paths.
- **`README.md`** (~line 160): update the enumerated tool list.
- **dist-tool packaged-MCP stdio smoke**: extend to exercise one analysis tool
  over real stdio (proves registration survives the shrink-merged bundle).

## Verification

- `./mvnw -pl mcp-server,cli -am test`
- `./mvnw -pl dist-tool -am verify -Pdist-smoke`
- `./mvnw -pl mcp-server -am -Pcoverage verify` — step 0 (baseline) and again at
  the end (the surface grows; keep the 0.70 floor green).
- `./mvnw -Pquality spotless:apply` then `./mvnw -Pquality verify`.
- **Audit gates** (repo Audit Gates table, "OEF/UML-XMI export" row is not this;
  this is engine-adjacent MCP surface): `test-quality-audit` Deep on the new
  core + parity tests; `devsecops-audit` Quick on the MCP boundary widening.

## Documented Deferral (per-stage tools)

Append to `docs/superpowers/plans/2026-07-15-recorded-deferrals.md` (and/or the
roadmap survey's per-stage-door entry): the five per-stage MCP tools
(`project`, `layout`, `validate-layout`, `render`, `export`) remain deferred
after the analysis tools shipped. Rationale: `build` subsumes them end-to-end,
and each would add always-loaded context tax for a debug decomposition. Revisit
trigger: a concrete decomposed-mode need (e.g., an agent workflow that must
inspect or hand-edit a stage envelope between stages). Recording this keeps the
door's state current with the re-evaluation.

## Risks / Open Questions

- **Shared-file contention if parallelized.** `DedirenTools`, `ToolSchemas`,
  `DedirenMcpServer`, `CliMcpParityTest`, `agent-usage.md`, and
  `threat-model.md` are touched by all four commands. Prefer sequential, or let
  each subagent own its command's **core-extraction** files (separate) and
  integrate the shared MCP-wiring edits centrally — the established edit-wave →
  central-build pattern (no parallel Maven; one central `-Pquality verify`).
- **`status` semantics over MCP.** `dir` is confined within the server root and
  defaults to the server root itself — a mild overlap with the server `--root`.
  Confirm it reads naturally and document it in the tool description + guide.
- **`query` error precedence (conscious trade, not an oversight).** Moving the
  `kind` whitelist and the `dependents`-needs-`id` check into `core` means the
  CLI now reads its input *before* judging `kind`, so the obscure double-fault
  `query --kind bogus --input missing.json` reports the input-read failure where
  it once reported the bad kind. Deliberate: re-adding the arg-check in `Main`
  would re-split the validation this change centralized for parity. Every
  single-fault case is unchanged and byte-identical (pinned by `CliDiffQueryTest`
  and `CliMcpParityTest`).
- **Return-shape choice.** `EngineRunOutcome` is reused for its pre-serialized
  string (lowest-friction parity). If reviewers object to an engine-named record
  carrying non-engine analysis output, substitute a dedicated
  `CommandOutcome(String stdout, int exitCode)` record — behavior-neutral.
- **Release.** An MCP surface widening is release-worthy. Per `release-policy`,
  sequence a CalVer bump as its **own** follow-on commit + annotated `v<version>`
  tag on `main` **after** integration, only when the change is being released —
  never inside the content commits.
