# Evolution Readiness Documentation Implementation Plan

Status: complete — executed on main; see reviews/2026-07-03-multi-viewpoint-product-review.md ("Remediation status").

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the knowledge a future maintainer needs out of retired plans
and git archaeology into durable docs, and record the two standing evolution
strategies (schema v2, Jackson 2→3) before they are needed in anger.

**Source findings:** MT-1 (schema v2 is an undesigned ~30-file big-bang),
MT-2 (Jackson dual-stack maintenance tax, 60-file eventual migration),
MT-5 (plan-only knowledge: Rust origin, unexplained Gradle→Maven pivot,
launcher-flag rollback conditions, CDS tier supersession + Java 25 gate) —
all confirmed; evidence in
`docs/superpowers/reviews/2026-07-03-multi-viewpoint-product-review.md`.
Context: 486 of 530 commits are one author (bus factor 1).

**Architecture:** All deliverables land in `docs/architecture-guidelines.md`
(the durable reference the repo already treats as canonical) as new or
extended sections; no code changes. Owner input is required where rationale
exists only in the owner's head (build-system pivot).

**Tech Stack:** Markdown.

## Global Constraints

- `docs/architecture-guidelines.md` is the canonical home for rationale,
  stability tiers, and known debt — extend it, do not fork a new doc.
- Docs-only verification: `git diff --check`.
- Keep entries evidence-linked (commit shas, pom lines) so the next
  maintainer can re-verify claims the way this review did.

---

### Task 1: Schema evolution playbook (MT-1)

**Files:**
- Modify: `docs/architecture-guidelines.md` (extend the §4/§10 schema-id
  guidance into a playbook section)

- [ ] Step 1: Failing check — `grep -in 'migration\|coexist\|deprecat'
      docs/architecture-guidelines.md` has no schema-migration content.
- [ ] Step 2: Write the playbook from the measured facts: the ~30-file change
      surface (list the file classes: schema const, ContractVersions, 16
      fixtures, test hotspots, 5 docs), the three precedent bumps
      (`1087f95`, `db09a7b`, `238da5a`) as the recipe, and an explicit,
      owner-confirmed position on dual-version support: either "big-bang by
      design — consumers pin bundle versions; here is the upgrade note
      template" or a sketch of a dual-read window. State which.
- [ ] Step 3: `git diff --check`; commit.

### Task 2: Jackson posture and 2→3 decision record (MT-2)

**Files:**
- Modify: `docs/architecture-guidelines.md` (dependency posture section)

- [ ] Step 1: Failing check — the dual-stack situation (Jackson 2 product
      code + Jackson 3 transitively via the CVE-pinned networknt validator,
      convergence pins) is explained only in pom comments.
- [ ] Step 2: Record: current pins and why (GHSA ids), the convergence
      procedure for routine bumps, the trigger conditions for the 2→3
      migration (e.g. Jackson 2 EOL announcement or networknt forcing it),
      and the measured blast radius (60 main-source files, contracts=30).
- [ ] Step 3: `git diff --check`; commit.

### Task 3: Promote plan-only operational knowledge (MT-5)

**Files:**
- Modify: `docs/architecture-guidelines.md` (history/rationale + runtime
  sections)

- [ ] Step 1: Failing check — `grep -n 'Gradle' docs/architecture-guidelines.md`
      is empty; launcher-flag rollback conditions and the CDS tier
      supersession exist only under `docs/superpowers/plans/`.
- [ ] Step 2: Add, each a short evidence-linked paragraph:
      (a) build lineage — Rust/Cargo → Java+Gradle (`4933d79`) → Maven
      (`657c4fa`) with the owner-supplied *why* for the Maven pivot
      (OWNER INPUT REQUIRED — the reason is recorded nowhere);
      (b) enforced launcher flags `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC`:
      measured justification (elk-layout −102 ms / −12.9%), the rollback
      lever (`dediren.layout.jvmArgs`), and the deliberate `-Xmx` exclusion;
      (c) JVM startup tiers: Tier 1 flags, Tier 2 AppCDS, Tier 3
      manifest-trust, Tier 4 Leyden as successor, and the pending Java 25
      baseline decision gate.
- [ ] Step 3: `git diff --check`; commit.

### Task 4: Transport-initiative closure record (from the review's decision point)

**Files:**
- Modify: `docs/architecture-guidelines.md` §5 (process boundary)

- [ ] Step 1: OWNER DECISION (see the review's synthesis): either record the
      in-process transport initiative as closed — citing MT-7's analysis and
      the measured baseline (~330 ms irreducible per-stage process overhead
      post-mitigations, ~2.5 s per 6-stage model-100 pipeline, trust flag
      worth ~50 ms) — or re-materialize the lost spec as a committed draft
      first and record it as open-pending-measured-requirement.
- [ ] Step 2: Write the chosen record with the baseline numbers and a pointer
      to the review report's appendix; `git diff --check`; commit.
