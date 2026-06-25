# 2026-06-25 — Inherited-codebase follow-ups

## Context

A fresh assessment of dediren (version `2026.06.7`) found the codebase healthy:
clean layering, an enforced contract-first surface, strong typed plugin-boundary
diagnostics, and mature DevSecOps. Nothing is broken. This plan addresses the
small set of "worth knowing about" items that surfaced, ordered by
risk-reduction value. Each item is independent; they can be done in any order or
as separate commits/branches.

The goal is to clear known temporary states and one structural watch-item before
they calcify, not to change product behavior.

---

## Item 1 — Re-enable OWASP dependency-check when NVD recovers

**Why:** `dependency-check` is disabled via `if: false` in both CI and release
because the NVD API was returning 503. Grype/SBOM scanning covers the gap at the
same High/CVSS≥7 threshold, so this is a *temporary posture to revert*, not a
permanent removal. Left indefinitely, it quietly becomes a permanent loss of the
NVD-backed gate.

**Precondition (verify first — do not re-enable blindly):**
- Confirm NVD has recovered before touching the workflow. Quick check:
  `curl -s -o /dev/null -w "%{http_code}" "https://services.nvd.nist.gov/rest/json/cves/2.0?resultsPerPage=1"`
  expecting `200` (with `NVD_API_KEY` the rate limits are higher). If it still
  500/503s, stop — leave the `if: false` and the explanatory comment in place.

**Changes (only after NVD is confirmed healthy):**
- `.github/workflows/ci.yml` — remove the `if: false` (line 55) and the
  TEMPORARY comment block (lines 52–54) on the `dependency-check` job.
- `.github/workflows/release.yml` — remove the matching `if: false` /
  comment on its dependency-check steps (reported around lines 69–90; confirm by
  reading the file).
- Decide whether `vulnerability-scan` (Grype) stays as belt-and-suspenders
  (recommended — keep it; it is NVD-independent and cheap) or is reverted to
  pre-outage state. Default: **keep both.**

**Verification:**
- Push to a branch and confirm the `dependency-check` job runs green (it needs
  the `NVD_API_KEY` secret and the `.cache/dependency-check` restore/save steps,
  both already wired).
- Confirm the NVD data update completes within the 45-minute `timeout-minutes`.

**Audit gate:** `souroldgeezer-audit:devsecops-audit` (quick) on the workflow
diff.

---

## Item 2 — Decompose `uml/.../Uml.java` (1,833 lines)

**Why:** `uml/src/main/java/dev/dediren/uml/Uml.java` is ~27% of all main LOC and
the single largest class. It is not buggy — it is a cohesion/maintainability
watch-item. It currently mixes the public vocabulary/validation entry points, a
very large sequence-diagram/combined-fragment validation engine, and a block of
generic JSON property-reader helpers.

**Approach (use `souroldgeezer-design:software-design` for the boundary calls;
behavior-preserving refactor, no semantic changes):**
- Keep `Uml` as the public facade (`structuralTypes()`, `relationshipTypes()`,
  `validateSource(...)`, `validateElementType(...)`, etc.) so callers
  (`generic-graph`, `render`, `uml-xmi-export`) are unaffected.
- Extract the sequence/interaction engine — the combined-fragment, interaction
  operand, message-sequence, fragment-coverage, and fragment-nesting logic
  (roughly the `validateCombinedFragment*` / `validateInteraction*` /
  `validateMessage*` / `validateFragment*` cluster, ~lines 434–1471) — into a
  package-private `UmlSequenceValidation` (or similarly named) collaborator.
- Extract the generic JSON property-reader helpers (`requiredProperty`,
  `requiredPositiveIntegerProperty`, `requiredTextProperty`, `optionalProperty`,
  `textValueSet`, etc., ~lines 1472–1660) into a package-private
  `UmlProperties` helper.
- Move the `InteractionFragmentInterval` record and `ValidationContext` usage to
  travel with the sequence engine.

**Constraints:**
- Strictly behavior-preserving. No change to validation error codes, messages,
  paths, or `UmlValidationException` shape — these are part of the diagnostic
  surface agents depend on.
- New classes stay package-private inside `dev.dediren.uml`; the public API of
  the module does not grow.

**Verification (TDD discipline — green before and after each extraction):**
```bash
./mvnw -pl uml,plugins/uml-xmi-export,plugins/generic-graph,plugins/render -am test
```
Run after each extraction step, not just at the end. The existing
`UmlTest`/`UmlValidationTest` suites (~1,475 test LOC) are the safety net; if any
extraction has no covering test, add the characterization test first.

**Audit gate:** `souroldgeezer-audit:test-quality-audit` (deep, on the uml
tests) since the refactor leans entirely on them for safety.

---

## Item 3 — Test-quality hardening backlog

**Why:** `docs/superpowers/plans/2026-06-07-test-quality-hardening.md` tracks an
active, deliberate test-confidence campaign (~75 open items). PIT mutation
testing is already wired (`mutation` profile) but gap-closing is in progress.
This is ongoing work, not a one-shot fix.

**Approach (do NOT try to clear all 75 at once):**
- Re-read `2026-06-07-test-quality-hardening.md` and reconcile it against live
  tests — per CLAUDE.md the plan's checkboxes are not authoritative; live code
  and tests are the truth. Mark already-done items as done.
- Run mutation testing on the highest-value modules to find real gaps rather
  than working from the stale list:
  ```bash
  ./mvnw -pl core,uml,plugins/generic-graph -am test -Pmutation
  ```
- Triage surviving mutants into the backlog, then close them in small,
  module-scoped commits. Prioritize `core` (orchestration), `uml` (largest
  validation surface — pairs naturally with Item 2), and the plugin boundary.

**Verification:** mutation score improves on the targeted modules; new/changed
tests pass under `./mvnw test`.

**Audit gate:** `souroldgeezer-audit:test-quality-audit` (deep) on the modules
touched.

---

## Item 4 — JVM startup optimization roadmap (informational, no action now)

**Why:** `2026-06-10-jvm-tier4-leyden-aot-cache.md` and
`...-tier5-native-image-spike.md` are exploratory and unfinished — the genuine
forward frontier (Leyden AOT cache, GraalVM native-image spike). They are
**spikes, not committed work**, and depend on toolchain availability.

**Recommendation:** Leave as-is unless startup latency becomes a felt problem.
These are not "fix-it" items; they are a future performance investment. If
picked up, treat as a separate brainstorming → spike, not part of this cleanup.

---

## Item 5 — Minor nits (low-effort cleanup)

**5a. Stale local dist bundle.** A git-ignored `dist/dediren-agent-bundle-2026.06.6/`
lingers one version behind. It is untracked and harmless. Before the next build
review, remove stale `dist/` output (e.g. `git clean -ndx dist/` to preview,
then the user-confirmed clean). Do not stage anything — `dist/` is ignored.

**5b. `setup-java` pin comment.** In all three CI/release jobs the action is
SHA-pinned but commented `# v5` rather than an exact patch like every other
action (`# v7.0.0`, `# v7.4.0`). Cosmetic only — update the comment to the exact
released patch the SHA corresponds to for consistency. This is a protected-ish
workflow surface; change only the comment text, nothing else.

---

## Suggested sequencing

1. **Item 1** when NVD is confirmed healthy (gated on an external precondition).
2. **Item 2 + Item 3** together — the UML decomposition and UML test hardening
   reinforce each other; do the characterization tests (Item 3) as the safety net
   for the refactor (Item 2).
3. **Item 5** as a trivial standalone tidy.
4. **Item 4** only if/when startup performance is prioritized — out of scope for
   routine cleanup.

Each item is its own commit (and optionally its own branch). None require a
version bump unless the change reaches a release surface; per `release-policy`
the bump rides as a separate follow-on commit if a release is cut.

## Whole-repo verification before calling any item done

```bash
./mvnw test
git diff --check
```
For distribution-touching changes additionally:
```bash
./mvnw -pl dist-tool -am verify -Pdist-smoke
```
