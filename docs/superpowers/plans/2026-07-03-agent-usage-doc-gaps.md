# Agent-Usage Doc Gaps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the documentation gaps that burned the cold-start reviewer on
its first contact with the bundle, so a fresh agent needs zero
guess-and-validate loops for mainstream ArchiMate work.

**Source findings:** CS-1 (ArchiMate vocabulary undocumented), CS-2 (export
extraction example missing — doc side; the contract side is in the
third-party-plugin-contract plan), CS-3 (default OEF policy stamps fixture
identity), CS-4 (offline schema dir contents unstated), CS-5 (no
`--profile archimate` example), CS-6 (SVG title falls back to view id),
PF-3 (documented warmup recipe seeds a slow CDS archive) — all confirmed;
evidence in `docs/superpowers/reviews/2026-07-03-multi-viewpoint-product-review.md`.

**Architecture:** All changes are bundle-local documentation plus one fixture
decision; the only code-adjacent risk is `AgentUsageDocConsistencyTest`,
which verifies every `DEDIREN_*` token and CalVer string in
`docs/agent-usage.md` against source.

**Tech Stack:** Markdown, JSON fixtures, dist-tool consistency tests.

## Global Constraints

- `docs/agent-usage.md` is the shipped agent contract: keep it bundle-local,
  command-oriented, and token-efficient — add reference lists, not essays.
- README and agent-usage move together for user-facing workflow changes.
- Verification lane: `./mvnw -pl dist-tool -am test` (consistency tests),
  `git diff --check`; run `./mvnw test` if any fixture changes.

---

### Task 1: ArchiMate authoring reference (CS-1, CS-5)

**Files:**
- Modify: `docs/agent-usage.md` (new "ArchiMate Handoff" section parallel to
  the UML handoffs)

- [ ] Step 1: Failing check — `grep -n 'profile archimate' docs/agent-usage.md`
      returns nothing; no type vocabulary list exists.
- [ ] Step 2: Add the section: accepted element/relationship type names for
      the archimate profile (source of truth: the generic-graph archimate
      profile implementation — enumerate from code, not memory), one
      `validate --plugin generic-graph --profile archimate` example, and the
      full validate→project→layout→render→export command sequence.
- [ ] Step 3: Both CS-1/CS-5 repro greps now hit documentation;
      `./mvnw -pl dist-tool -am test` green; commit.

### Task 2: Export envelope extraction example (CS-2 doc side)

**Files:**
- Modify: `docs/agent-usage.md` "Export" section

- [ ] Step 1: Failing check — no `.data.content` extraction example exists
      (only the render `.data.artifacts[]` jq line).
- [ ] Step 2: Add the export extraction line, e.g.
      `jq -r '.data.content' oef-result.json > model-oef.xml`, with a one-line
      note that export envelopes carry a single artifact at
      `.data.artifact_kind`/`.data.content`, unlike render.
- [ ] Step 3: Verify by running the documented commands against
      `fixtures/` inputs; commit.

### Task 3: Default export policy identity warning (CS-3)

**Files:**
- Modify: `docs/agent-usage.md` Artifact Map / Export sections
- Optional (owner choice): `fixtures/export-policy/default-oef.json` —
  neutralize `model_identifier`/`model_name` (touches OEF export tests,
  README examples, and round-trip fixtures together)

- [ ] Step 1: Failing check — docs say "usually reuse" with no mention that
      the default policy hard-codes fixture identity into the export.
- [ ] Step 2: Document that `model_identifier`/`model_name` should be
      overridden per model, with a copy-paste minimal custom policy example.
- [ ] Step 3: If the owner opts to neutralize the fixture, update the moving
      set together and run `./mvnw -pl plugins/archimate-oef-export,cli -am test`.
- [ ] Step 4: `git diff --check`; commit.

### Task 4: Offline schema directory contents (CS-4)

**Files:**
- Modify: `docs/agent-usage.md` Export section (env-var paragraphs)

- [ ] Step 1: Failing check — docs name `DEDIREN_OEF_SCHEMA_DIR` /
      `DEDIREN_XMI_SCHEMA_PATH` but not what the dir/file must contain.
- [ ] Step 2: State the required contents (which XSDs, flat layout vs the
      cache's `opengroup/archimate/3.1` nesting — verify empirically against
      the export plugins before writing) and that one online run with
      `DEDIREN_SCHEMA_CACHE_DIR` populates a reusable offline cache.
- [ ] Step 3: Verify the documented layouts actually work via the review's
      CS-4 repro variants; `./mvnw -pl dist-tool -am test`; commit.

### Task 5: CDS warmup recipe fix (PF-3) and SVG title note (CS-6)

**Files:**
- Modify: `docs/agent-usage.md` "Runtime Probes" + "Plugin Environment" (CDS
  paragraph); render policy note for the accessible-name fallback

- [ ] Step 1: Failing state — the Runtime Probes sequence seeds each
      launcher's CDS archive with a trivial command; PF-3 measured ~29%
      per-call penalty locked in versus workload seeding
      (385 ms vs 274 ms validate on model-10).
- [ ] Step 2: Change the warmup guidance: after probes, run one representative
      workload command per launcher (or delete the stale archive via
      `DEDIREN_CDS_DIR` before first real use); document that archives are
      seeded once by the first command and not regenerated.
- [ ] Step 3: Add one line to the render policy docs: the SVG accessible
      `<title>` falls back to the view id when no label-bearing policy/source
      is provided (CS-6), so author view labels accordingly.
- [ ] Step 4: Re-measure the PF-3 repro after following the new recipe —
      expected: the fast (~274 ms) profile; `git diff --check`; commit.
