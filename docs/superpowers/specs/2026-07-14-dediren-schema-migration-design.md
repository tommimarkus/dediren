# Dediren Schema Migration Design

## Status

Proposed. Brainstormed 2026-07-14.

Amended 2026-07-21: Decision 2's delivery mechanism is revised from prose to
structured data — see `## Amendment (2026-07-21): Machine-Readable Migration
Steps` at the end of this document. Decisions 1, 3, 4, and 5 stand unchanged.
The historical body below is preserved as brainstormed.

## Purpose

Give an agent (or a human) that holds a Dediren file written against an older
schema a way to find out that it is stale, and a way to fix it.

Today neither half exists. A stale source model fails with a generic
`DEDIREN_SCHEMA_INVALID` const-mismatch message that never says "your version is
old". A stale *policy* file is not checked at all.

## The Defect This Fixes

`SchemaValidator` is constructed in exactly one place —
`SourceValidator.java:225` — so the JSON Schema `const` that pins a version is
enforced for the **source model only**. The three policy files
(`render-policy`, `oef-export-policy`, `uml-xmi-export-policy`) are handed to
`core` as raw JSON text on `BuildRequest`, parsed with
`CoreCommands.parseJson`, and deserialized into records. Their
`*_schema_version` field is carried on the record and **never compared to the
matching `ContractVersions` constant** outside of tests.

The field is decorative. Two consequences:

1. **Silent acceptance.** A policy stamped `render-policy.schema.v1` whose
   fields still happen to fit the current record runs today as though it were
   v3. This is the serious one: the file declares a contract Dediren is not
   honouring, and nothing says so.
2. **Unstructured failure.** When the stale file *does* contain a retired field,
   `FAIL_ON_UNKNOWN_PROPERTIES` (`JsonSupport`) throws deep in Jackson
   deserialization. The user gets a property-name error, not a diagnostic code,
   and never learns the real cause.

## Decisions (resolved in brainstorming)

1. **Scope is every hand-authored surface**: the source model plus all three
   policy files. Emitted artifacts (`layout-request`, `layout-result`,
   `render-metadata`) are out of scope — they are machine-written and
   re-emitted, so the payoff is thin.
2. **Documentation is the migration tool.** No `dediren migrate` auto-rewriter.
   The consumer is an agent that already has the file open; a precise prose
   delta is something it can apply directly. A rewriter is a large amount of
   code to save an agent an edit it is already good at.
3. **The registry backfills the shipped history.** It ships with real content
   for the three `render-policy` breaks, not as an empty promise.
4. **Split ownership, pinned by a test.** `contracts` owns the version list;
   `docs/agent-usage.md` owns the prose; a test pins them together. This
   follows the existing `GuideCatalog` / `AgentUsageDocConsistencyTest` pattern
   rather than inventing a new one.
5. **No lenient or auto-upgrade-on-read mode.** Silently accepting a stale file
   is the defect being removed. Re-adding it behind a flag reintroduces it.

## Components

| Module | Adds | Notes |
|---|---|---|
| `contracts` | `KnownSchemaVersions` | Pure data: schema family → ordered version history, current last. Plus the legacy version-*field* names per family. No logic — `contracts` stays dumb. |
| `contracts` | 2 × `DiagnosticCode` | `SCHEMA_VERSION_OUTDATED`, `SCHEMA_VERSION_UNKNOWN`. |
| `core` | `core/schema/SchemaVersionGate` | Classifies a parsed document's version. Backend-neutral validation, which `core` owns. |
| `mcp-server` | `migration` guide topic | One line in `GuideCatalog.topicMap()`; one pointer in the `dediren_build` tool description. |
| `docs/agent-usage.md` | `## Migration` section | One `###` subsection per version step. |
| tests | `MigrationRegistryTest` | Pins registry ↔ prose in both directions. |

The MCP server needs almost nothing: the diagnostic travels in the envelope, so
both the CLI and the MCP tools get the actionable error for free.

## The Registry, On Day One

`model`, `oef-export-policy`, and `uml-xmi-export-policy` have never been
bumped — one version each, ever. All history lives in `render-policy`:

| Family | Version history (current last) |
|---|---|
| `model.schema` | `v1` |
| `oef-export-policy.schema` | `v1` |
| `uml-xmi-export-policy.schema` | `v1` |
| `render-policy.schema` | `svg-render-policy.schema.v1`, `render-policy.schema.v1`, `render-policy.schema.v2`, `render-policy.schema.v3` |

So there are exactly three migration entries to write, all `render-policy`:

- **`svg-render-policy.schema.v1` → `render-policy.schema.v1`** (`238da5a`) —
  a family rename. Rename the field `svg_render_policy_schema_version` to
  `render_policy_schema_version` and set its value. Nothing else changes.
- **`render-policy.schema.v1` → `v2`** (`6058fb4`) — remove the top-level
  `raster` block (`scale`, `background`). PNG output was dropped; there is no
  replacement.
- **`render-policy.schema.v2` → `v3`** (`e3fc016`) — remove the top-level
  `interactive` key (`none|svg|html|both`) and the `interaction` block under
  style (`highlight_stroke`, `highlight_stroke_width`). Interactive SVG was
  retired; there is no replacement.

### The renamed-field wrinkle

The oldest step renamed the version field itself. On such a file the *current*
field is absent, so a gate that only reads `render_policy_schema_version` would
classify it as unknown and help nobody — the worst outcome for the oldest and
most-stranded file.

`KnownSchemaVersions` therefore records, per family, the **legacy version-field
names** alongside the version history. When the current field is absent, the
gate checks the legacy names before concluding "unknown".

## Diagnostics

Two new codes, both input errors (`INPUT_ERROR` exit code, error envelope,
non-zero exit). The agent still decides success or failure from stdout JSON
alone.

- **`DEDIREN_SCHEMA_VERSION_OUTDATED`** — the version is one the registry
  recognizes as prior. The message names the version found, the version
  expected, and where to get the upgrade steps (`dediren_guide` topic
  `migration`, or the `## Migration` section of the bundled guide).
- **`DEDIREN_SCHEMA_VERSION_UNKNOWN`** — the version string is absent, typo'd,
  or from a *newer* bundle than this one. There is nothing useful to say beyond
  "this is not a version I know", so the message says exactly that and names
  the expected version.

## Data Flow

Source model — `SourceValidator`, on both the `validate` and `build` paths:

    source text → parse → SchemaVersionGate → JSON Schema validation → semantic checks

The gate runs **before** JSON Schema validation. Today a stale
`model_schema_version` trips the schema `const` and surfaces as
`SCHEMA_INVALID`; running the gate first means it surfaces as
`SCHEMA_VERSION_OUTDATED` instead.

Policy files — every policy enters `core` as raw text and is parsed by the
package-private `CoreCommands.parseJson`. There are **four** such sites, and the
gate must cover all of them, not just `build`:

| Site | Lane |
|---|---|
| `CoreCommands.renderCommand:277` | standalone `dediren render` |
| `CoreCommands.exportCommand:303` | standalone `dediren export` (OEF or XMI, by engine id) |
| `BuildCommand:268` | `build` render lane |
| `BuildCommand:334` (`runExportStage`) | `build` export lanes (OEF or XMI, by engine id) |

Rather than gate four times, `CoreCommands` grows one package-private
chokepoint that all four call:

    policy text → parsePolicy(command, text, family) → engine dispatch
                    └── parseJson + SchemaVersionGate

The gate runs before engine dispatch, so a stale policy fails before any engine
runs and before any artifact is written. Engines stay unaware of versioning.

For the two export sites the family is chosen from the engine id
(`archimate-oef` → OEF policy, `uml-xmi` → XMI policy). An engine id matching
neither skips the gate and falls through to the existing
`DEDIREN_PLUGIN_UNKNOWN` from `requireEngine`, preserving today's error
precedence (a malformed policy is reported before an unknown engine).

### Known asymmetry

`dediren_validate` takes a source and an optional profile — it never sees a
policy. So a stale *policy* is caught at `dediren_build`, not at validate time.
This is acceptable (a policy is only meaningful to a build) but it means the
agent's fast feedback loop does not cover policy staleness. Widening `validate`
to accept policies is a plausible follow-up, deliberately not taken here.

## Behavior Changes

**The policy gate is breaking, on purpose.** A stale-but-structurally-compatible
policy that silently works today will hard-fail after this change. That is the
defect being fixed, but it is a real break for existing files and belongs in the
release notes.

It does **not** warrant a schema-id bump. The schemas themselves do not change —
only whether they are enforced. Bumping ids here would strand the very files this
change exists to rescue.

**The source-model diagnostic changes code.** A stale `model_schema_version`
moves from `DEDIREN_SCHEMA_INVALID` to `DEDIREN_SCHEMA_VERSION_OUTDATED`. Any
existing test asserting the old code on that path gets updated, not worked
around.

## Documentation Constraints

Migration entries are keyed by **schema id, never by release version**. Two
reasons, one of them enforced: `AgentUsageDocConsistencyTest` (dist-tool) fails
the build on any CalVer string in `docs/agent-usage.md` that does not match the
current product version, so "in 2026.07.15 the policy changed" would not
compile. And CLAUDE.md is explicit that CalVer encodes the release date, not
compatibility — schema ids are the durable signal.

The same test requires every `DEDIREN_*` token named in the guide to exist in
source, so the two new codes must land in `DiagnosticCode` before the guide
names them.

## Testing

- **contracts** — each family's last registry entry equals the matching
  `ContractVersions` constant, so the registry cannot drift from the product.
- **core** — gate unit tests per surface: current passes; a known prior version
  yields `SCHEMA_VERSION_OUTDATED` naming both versions; an unrecognized,
  absent, or newer version yields `SCHEMA_VERSION_UNKNOWN`; a file using a
  legacy version-*field* name is still recognized as outdated.
- **core** — build integration: a stale render policy produces an error
  envelope, a non-zero exit, and **no artifacts written**.
- **mcp** — `GuideCatalogTest` (already bidirectional) covers the new
  `migration` topic.
- **new** — `MigrationRegistryTest`: every non-current version in
  `KnownSchemaVersions` has a prose subsection, and every prose subsection has a
  registry entry.
- **dist-tool** — `AgentUsageDocConsistencyTest` stays green.

## Keeping It Fed

Add to CLAUDE.md's **Files That Move Together**: a breaking schema-version bump
must ship a `KnownSchemaVersions` entry and a `## Migration` subsection in the
same change.

Without that rule the registry rots after one release, and the next bump strands
files exactly the way `render-policy` v1 and v2 are stranded today.

## Out of Scope

- `dediren migrate` auto-rewriter (decision 2).
- Version gating of emitted artifacts (decision 1).
- A dedicated MCP migration *tool* — the `dediren_guide` topic carries it, and
  every extra tool costs the agent context window in `tools/list`.
- Lenient / auto-upgrade-on-read mode (decision 5).
- Widening `dediren_validate` to accept policy files (see Known asymmetry).

## Amendment (2026-07-21): Machine-Readable Migration Steps

Owner re-examination, recorded in
`docs/superpowers/plans/2026-07-21-future-feature-roadmap-survey.md`
(§ "Owner-directed re-examination").

**What changes.** Decision 2's *delivery mechanism* is revised: prose is no
longer the sole migration tool. Each registry step is additionally expressed
as **structured operations** (RFC-6902-style: `rename_field` / `remove_key` /
`set_version`, JSON-Pointer targets), carried to the consumer on the
`DEDIREN_SCHEMA_VERSION_OUTDATED` diagnostic (and available to any future
read-only surface, e.g. an MCP resource). The `## Migration` prose in
`docs/agent-usage.md` remains the human-readable record, kept from drifting by
deriving or test-pinning it against the same operation data. Every shipped
operation list is fixture-verified (stale document + expected outcome) in CI.

**What does not change.** Dediren still never applies the steps: no command
rewrites or emits a migrated document, the stale file still fails validation
until the consumer migrates it, and the consumer's diff remains the
consumer's diff. Decision 5 (no lenient / auto-upgrade-on-read) is
**reaffirmed** — the rejected alternative of emitting a migrated copy as a
derived artifact was examined and declined, because the consumer would copy it
over the source, making the tool a de facto rewriter of record and dissolving
the review the big-bang philosophy exists to force.

**Escalation triggers.** Revisit a full `dediren migrate` only if (i) a future
bump's steps cannot be expressed as mechanical operations, or (ii) the first
`model.schema` bump is contemplated — decide then, with the evidence in hand.

**Known asymmetry: closed (2026-07, wave-0).** The "plausible follow-up,
deliberately not taken here" was taken: `validate` and `dediren_validate` now
dispatch on the document's version field (current or legacy), so a policy or
kept layout-request meets its version gate and JSON Schema at validate time,
not only at build time. Source-model behaviour is unchanged.
