# Multi-Viewpoint Product Review

## Purpose

Review the whole repository as it stands on `main` (baseline commit `71676a6`,
product version 2026.06.10) from four stakeholder viewpoints that exercise the
shipped product rather than reading the source through a checklist:

1. **Cold-start AI agent** — the product's declared primary user.
2. **Community plugin author** — the product's extension surface.
3. **Maintainer in 2027** — the product's change cost and evolution story.
4. **Performance-conscious developer** — the product's runtime and
   developer-loop cost.

The June 30 audits predate the quality-gate wave (Spotless/SpotBugs/JaCoCo)
and the god-file splits, and they were code-artifact lenses. This review is
product-centric and empirical: each reviewer role-plays its stakeholder
against the real distribution bundle built from `main`.

## Primary Decisions

1. **Staged pipeline, not straight fan-out.** Shared groundwork is built once;
   four reviewers run in parallel against the same artifacts; qualitative
   findings pass independent verification before entering the report; a final
   synthesis ranks findings across viewpoints. Numbers from all reviewers are
   comparable because the artifacts are identical.
2. **Role-isolated subagents.** Each viewpoint runs as a dedicated subagent
   with a deliberately shaped context. Two roles have a load-bearing
   "no prior knowledge" premise (cold-start agent, plugin author) that inline
   role-play by a full-context session would contaminate.
3. **Verified findings only.** Qualitative findings are reproduced by an
   independent verifier blind to the reviewer's reasoning. Raw measurements
   carry their own evidence (exact re-runnable commands) and skip
   verification.
4. **Deliverables are a consolidated report plus drafted follow-up plans.**
   Remediation itself is out of scope; each finding cluster worth fixing gets
   a ready-to-execute plan for later sessions.
5. **Severity vocabulary matches the existing audit gates**: `block` /
   `warn` / `info`.

## Scope

In scope:

- The distribution bundle built from current `main`, exercised end to end.
- The public contract surface: schemas, manifests, envelopes, diagnostics,
  CLI behavior, `README.md`, `docs/agent-usage.md`.
- Evolution and change-cost assessment of the repository itself.
- Measured performance baselines, including the numbers the in-flight
  in-process transport spec asserts without measurement.

Out of scope:

- Remediating findings (deferred to the drafted follow-up plans).
- Hostile-input / security probing (explicitly declined).
- Competitive positioning against other diagram tooling.
- Direct review of the uncommitted in-process transport design spec.

## Groundwork

Done once, inline, before any reviewer launches:

- **Build the real product.** Produce the distribution bundle from `main` via
  the dist-tool lane and unpack it into the session scratchpad. Every
  reviewer exercises this artifact, not the source tree.
- **Scaled fixture models.** Generate three valid source models at roughly
  10 / 100 / 1000 elements (ArchiMate application/technology layers with
  realistic relation density) into the scratchpad. Never committed.
- **Standard scenario script.** One realistic end-to-end task shared by the
  cold-start and performance reviewers: author a model from a short textual
  brief → validate → layout → render SVG → export OEF, deciding success from
  stdout envelopes alone.
- **Sanity gate.** `./mvnw test` green on `main` before anything launches, so
  reviewers report product findings, not a broken checkout.

## Reviewer Briefs

Each reviewer returns structured findings with severity, evidence, and exact
reproduction steps.

### Cold-start AI agent

- Receives only the unpacked bundle path and `docs/agent-usage.md`.
- Forbidden from reading repo source, plans, or specs.
- Executes the standard scenario as a first-contact agent.
- Reports: stalls, guesses, envelope misreads, missing documentation, wasted
  tokens, and whether each step's outcome was genuinely decidable from stdout
  JSON alone.

### Community plugin author

- Receives the public contract surface only: `schemas/`, plugin manifest
  fixtures, and the plugin-related sections of `README.md` and
  `docs/agent-usage.md`. Forbidden from reading `core` internals.
- Builds a minimal third-party exporter plugin (element/relation stats to
  JSON) against the stdio contract, installs it into a project
  `.dediren/plugins` directory, and gets it discovered and executed by the
  real CLI.
- Reports a friction log: what had to be guessed, which error messages helped
  or misled, whether the contract stands without reading core source. The toy
  plugin stays in the scratchpad.

### Maintainer in 2027

- Full repo access; the isolation is perspective, not information.
- Evolution stress-tests backed by evidence:
  - Walk the concrete change surface of a hypothetical `model.schema.v2`.
  - Assess upgrade exposure for ELK, Jackson, and the next Java LTS from the
    actual dependency tree.
  - Test whether the "files that move together" lists are designed cohesion
    or spreading debt, using git-history fan-out of past schema changes.
  - Identify knowledge that lives only in plans/specs (bus factor).
  - Assess the maintenance load the in-flight in-process transport would add.

### Performance-conscious developer

- Receives the bundle, the scaled models, and the repo for instrumentation.
- Produces a numbers table, each number with the exact command that produced
  it:
  - **Pipeline overhead**: per-stage wall time for the standard pipeline on
    the medium (~100-element) model, JVM-spawn overhead broken out from real
    work.
  - **Scale ceiling**: the same pipeline at 10 / 100 / 1000 elements with
    latency, memory, and output size per stage; which stage degrades first.
  - **Cold-start & footprint**: archive size, unpacked disk footprint, and
    time-to-first-result from a fresh unpack.
  - **Developer loop**: wall times for `./mvnw test` and the quality gate
    (`./mvnw -Pquality verify`).
- These measurements double as the baseline for the in-process transport
  spec.

## Verification

- Raw measurements pass through unverified; their evidence is the re-runnable
  command.
- Every qualitative finding goes to an independent verifier agent that
  attempts reproduction from the finding's stated steps, blind to the
  reviewer's reasoning.
- Verdicts: **confirmed** (reproduced; enters the report), **plausible**
  (not fully reproduced, not refuted; enters the report labeled plausible),
  **refuted** (dropped).
- Rationale: a role-played "confused newcomer" can manufacture confusion; the
  verifier cannot.

## Synthesis & Deliverables

- **Consolidated report** at
  `docs/superpowers/reviews/2026-07-03-multi-viewpoint-product-review.md`
  (new `reviews/` sibling to `specs/` and `plans/`):
  - Executive summary.
  - One section per viewpoint with confirmed/plausible findings and evidence.
  - Cross-viewpoint synthesis ranking findings by product impact.
  - Measured-baselines appendix.
- **Follow-up plans**: one drafted
  `docs/superpowers/plans/2026-07-03-<cluster>.md` per finding cluster worth
  fixing, in the existing plan format. Clusters emerge from synthesis
  (several viewpoints hitting the same gap becomes one plan).
- **Hygiene**: report and plans committed as a docs-only change
  (`git diff --check`). The bundle, scaled models, and toy plugin remain in
  the scratchpad with their paths listed in the report. Nothing generated is
  staged.

## Success Criteria

- All four reviewers complete their briefs against the same groundwork
  artifacts.
- Every qualitative finding in the report is confirmed or explicitly labeled
  plausible; no refuted or unverified qualitative claims appear.
- Every measurement in the report is re-runnable from its recorded command.
- The report ranks findings across viewpoints with `block`/`warn`/`info`
  severities, and each cluster worth fixing has a drafted follow-up plan.
