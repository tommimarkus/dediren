# Dediren MCP ↔ CLI Parity Design

## Status

Approved design anchor for closing the residual behavioral gaps between the MCP
tool surface and the CLI, and for hardening the anti-drift guarantee that keeps
them from diverging again. Follows the MCP server slice
(`2026-07-14-dediren-mcp-server-design.md`, released `2026.07.17`) and the
schema-migration gating that landed on `main` immediately after it (commits
`999ecd6`…`570b790`).

The implementation plan is authored separately after this spec is approved.

## Purpose

The MCP server exposes three tools — `dediren_validate`, `dediren_build`,
`dediren_guide` — mirroring the CLI's `validate` and `build` commands plus an
on-demand authoring guide. The per-stage CLI commands (`project`, `layout`,
`validate-layout`, `render`, `export`) remain deliberately CLI-only; that
non-goal is unchanged here (see `## Non-Goals`).

This slice targets **flag/feature parity within the two shared tools**: making
the MCP tools behave exactly like their CLI counterparts wherever the difference
is accidental, while preserving — untouched — the differences that are the MCP
server's trust boundary.

## Verification Result (what scoped this design)

Both shared tools are thin adapters over the same `core` entry points, so most
feature parity is automatic — new core behavior (the schema-migration gating,
for example) reaches both lanes for free. An exhaustive three-lane audit
(argument surface, error/diagnostic lanes, core-delegation + guide + test
coverage) confirmed the shared surfaces are already at functional parity with
exactly **one accidental behavioral gap**, one minor divergence, and a set of
intentional security divergences that are out of scope by definition.

The larger finding was that the guarantee meant to *enforce* parity —
`CliMcpParityTest` — pins only four scenarios (structural validate happy/error,
render-lane build happy/IO-error) and is blind to the profile-validate lane, the
`emit` handling, and the OEF/XMI export lanes. That blindness is precisely why
the emit gap went unnoticed. The test even feeds the two lanes mismatched
environments (`System.getenv()` for the CLI lane, `Map.of()` for the MCP lane),
which is latent today only because the render lane does not forward `env` to its
engine — the export lanes do.

## Scope

### In scope

1. **Close the emit-kind gap** by relocating the validation into `core`.
2. **Normalize `views`** (dedup + drop-blank) in one shared place so both lanes
   behave identically.
3. **Harden `CliMcpParityTest`** to cover the blind spots and fix its
   mismatched-env harness bug.
4. **Doc touch-ups** consequent to the above.

### Out of scope (intentional divergences — closing them would reduce safety)

These are the MCP trust boundary and stay exactly as they are:

- Paths-only input (no stdin), and required `source`/`out`.
- `--root` confinement of `source`, `out`, policy paths, and `fragments[]`.
- Path/message sanitization on read failures and the missing→outside-root
  conflation (anti host-fs-fingerprinting).
- The `VIEW_ID_PATTERN` defense-in-depth guard on model-supplied view ids.
- Always-enveloped tool results vs the CLI's raw-stderr legacy lanes
  (`printStructuralFailure`) and picocli usage errors.

### Non-goals

- No per-stage tools (`project`, `layout`, `validate-layout`, `render`,
  `export`). CLI-only, unchanged from the MCP server design.
- **No `validate --plugin` argument on the MCP tool.** The CLI's `--plugin`
  selects a semantics engine, but `EngineWiring` registers exactly one
  (`generic-graph`, a router that dispatches on `profile`), so there is nothing
  else to select. The MCP tool hard-codes that id. This is future-proofing with
  no present observable, and YAGNI applies: revisit only if a second semantics
  engine is ever exposed via the CLI.
- No schema change, no new `DiagnosticCode`, no new engine, no envelope-format
  change. `COMMAND_INPUT_INVALID` (the emit error's code) already exists.

## Design

### Principle: push shared input rules down into `core`

The emit gap exists for one reason: that single rule was duplicated in the CLI
adapter (`Main.BuildCommand.KNOWN_EMIT_KINDS`) instead of living in `core`,
where every other build input rule already lives (empty-lane, schema-version
gating, policy-family gating). Those shared rules reach both lanes automatically;
the duplicated one did not. The fix restores the pattern rather than adding a
third copy to the MCP handler.

### Change 1 — emit-kind validation in `core`

`core`'s `BuildCommand` already owns the emit vocabulary as the constants
`EMIT_LAYOUT_REQUEST`, `EMIT_LAYOUT_RESULT`, `EMIT_RENDER_METADATA`. Add a
build-level check near the existing empty-lane check at the top of
`BuildCommand.run(...)`: any element of `request.emit()` not in that set yields a
`buildLevelError(Diagnostic)` — a single `COMMAND_INPUT_INVALID` diagnostic at
the default `INPUT_ERROR` (exit 2), matching the CLI's current observable
exactly.

Then **delete the CLI's duplicate**: `Main.BuildCommand.KNOWN_EMIT_KINDS` and
its pre-dispatch loop. With the check in core, both lanes reach it identically,
so ordering and precedence among build-level input errors become core's single
decision and cannot drift.

- The error message becomes lane-neutral (it no longer references the CLI-only
  `--emit` spelling), so the two lanes emit byte-identical envelopes. Exact
  wording and its `path` (aligned with the empty-lane check's `command:build`)
  are plan-level details; the load-bearing contract is: single diagnostic,
  `COMMAND_INPUT_INVALID`, exit `INPUT_ERROR`, identical across lanes.
- Behavior shift for CLI users, noted and accepted: `dediren build --emit bogus`
  previously errored *before* reading stdin; it now reads input first, then
  errors. The envelope and exit code are unchanged.

### Change 2 — `views` normalization in `BuildRequest`

`BuildRequest`'s compact constructor already normalizes `views`/`emit`/`env`
(`emit` is a `Set`, so it is already de-duplicated for both lanes). Extend the
`views` normalization there to **drop blank entries and de-duplicate while
preserving first-seen order**. Both lanes — and any future caller — then behave
identically, matching the MCP handler's current (safer) behavior.

- `views:["main","main"]` → one `main` outcome in both lanes (previously the CLI
  attempted `main` twice and would collide on the second write).
- The MCP handler's own `LinkedHashSet` dedup in `stringListArg` becomes
  redundant for `views` but stays harmless; simplifying it is optional and
  low-priority.

### Change 3 — harden `CliMcpParityTest`

Extend the existing byte-for-byte parity test (in the `cli` test tree, which can
reach both `Main` and `DedirenTools` + `EngineWiring`) with cases that drive the
blind spots. Each case runs identical inputs through both lanes and asserts
envelope equality (path-normalized where the envelope carries absolute paths) and
`isError`/exit parity:

1. **Semantic-profile validate** (`profile != null`): the profile lane that the
   suite never exercised — at least ArchiMate, ideally UML too, reusing existing
   semantics fixtures.
2. **`emit` — happy and unknown-kind**: all three valid kinds together, and a
   bogus kind that must now error identically in both lanes. This case directly
   guards Change 1 and is the one that would have caught the original gap.
3. **Export lanes** (OEF and XMI): the only lanes that forward `env` to an
   engine (the schema-validator boundary), so this also exercises env-forwarding
   parity. Reuse existing export policy/source fixtures.
4. **Explicit `views`, including a duplicate/blank case**: assert both lanes now
   produce the identical normalized outcome (guards Change 2).

Fix the harness bug: pass the **same** `env` map to both lanes. Prefer a single
controlled, hermetic `env` map handed to both `Main.executeForTesting(...)` and
`new DedirenTools(...)`, rather than `System.getenv()` for one and `Map.of()`
for the other.

Fragment-bearing-source parity (the intentional confinement divergence) is
acknowledged as unpinned in either direction; a guard for it is a reasonable
addition the plan may include, but it asserts a *difference* (MCP confines, CLI
does not), not parity, so it is secondary to the four cases above.

### Change 4 — doc touch-ups

- Refresh the stale topic list in the MCP server design spec
  (`2026-07-14-dediren-mcp-server-design.md`, the `dediren_guide` section): it
  names `runtime` where the code ships `runtime-probes` and omits eight topics
  the implementation now serves. Correct it or mark it explicitly illustrative.
  (The topic map itself is complete and bidirectionally test-enforced by
  `GuideCatalogTest`; only the *spec prose* is stale.)
- Review `README.md` and `docs/agent-usage.md` for any wording that describes the
  old `emit`/`views` behavior; update in the same change if found. No new
  `DEDIREN_*` token or CalVer string is introduced, so
  `AgentUsageDocConsistencyTest` needs nothing new.

## Files That Move Together

- **`core`** — `BuildCommand` (emit check) and `BuildRequest` (views
  normalization).
- **`cli`** — `Main.BuildCommand` (delete the `KNOWN_EMIT_KINDS` duplicate) and
  `CliMcpParityTest` (the hardening).
- **`README.md` / `docs/agent-usage.md`** — together, only if either describes
  the changed `emit`/`views` behavior.
- **`docs/superpowers/specs/2026-07-14-dediren-mcp-server-design.md`** — the
  guide-topic-list refresh.
- No schema, `contracts`, `engine-api`, `ir`, `threat-model`, or
  `AgentUsageDocConsistencyTest` change: this is build-input validation in core
  and a test/doc hardening, not a contract, engine, or trust-boundary change.
  The MCP trust boundary and its controls are unchanged; server-side emit
  validation is consistent with the existing "do not trust the model-driven
  client" posture and needs no new threat-model entry.

## Invariants And Testing

The load-bearing invariant remains **CLI/MCP envelope parity**: identical source
and policies through `dediren build` and `dediren_build` (and `dediren validate`
and `dediren_validate`) must produce byte-identical envelopes. Change 3 widens
the set of inputs under which that invariant is actually pinned from four
scenarios to include profile-validate, emit (happy + unknown-kind), the export
lanes, and views.

A second invariant, now made explicit: the emit vocabulary lives in exactly one
place (`core` `BuildCommand`). No adapter re-declares it.

## Verification

```bash
./mvnw -pl core,cli -am test          # core validation + CLI + parity tests
./mvnw -pl mcp-server,cli -am test    # MCP surface + parity
./mvnw -Pquality verify               # format + SpotBugs + full suite
git diff --check
```

Per the CLAUDE.md audit gates, this is a vertical-slice-ish change touching the
build driver and the MCP boundary: `test-quality-audit` deep (new parity tests,
the anti-drift guarantee) and `devsecops-audit` quick (the MCP boundary is
unchanged, but confirm the emit relocation adds no new process/dependency
surface).

## Release

At author discretion, per `## Versioning` in CLAUDE.md. If shipped, a CalVer
`2026.07.18` bump in its own commit with an annotated `v2026.07.18` tag,
sequenced after integration. This carries no schema-id change: CalVer encodes the
date, and the `emit`/`views` behavior refinements are communicated in the release
notes, not through the version number.
