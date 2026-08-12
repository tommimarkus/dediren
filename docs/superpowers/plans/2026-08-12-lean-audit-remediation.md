# 2026-08-12 Lean-audit remediation — CLAUDE.md weight and cross-doc drift

Remediation plan for the 2026-08-12 lean audit of `CLAUDE.md` / `AGENTS.md`, measured at `faa1b20`.
The audit returned **zero engine findings**, which is why this plan exists: the lean-audit engine's
guard globs resolve to only three files here (`CLAUDE.md`, `AGENTS.md`, `README.md`), so a clean run
covers 3 of 16 governance documents. Hand-measurement past that boundary found two real problems.

## Findings addressed

| # | Finding | Evidence | Wave |
| --- | --- | --- | --- |
| 1 | `LA-BLOAT-2` — render-paint policy inlined in always-loaded context | 64 lines / 11.7 % of `CLAUDE.md`, needed only for render-paint work; file was 622 lines against a 250-line budget | 1 |
| 2 | `LA-DUP-1` (advisory) — undeclared duplication with `docs/features/svg-render.md` | containment 0.559; **18 of 20** pinned constants stated twice; sync obligation was prose-only | 1 |
| 3 | Policy blocks restate the skills that own them | vs full skill closures: Planning **0.813**, Scope **0.783**, Git Hygiene 0.535, Test-First 0.563, Versioning 0.451 | 2 |
| 4 | Declared carve-outs are a record of intent, not a control | pairs measure 0.801 / 0.641 (block band) and the engine never reads the counterpart files | 3 |

## What landed

**Wave 1 — hoist.** Render-paint policy moved to `docs/features/svg-render.md`, which already owned
most of it; `CLAUDE.md` keeps the run commands and a pointer. The obligation ledger found **6 of 27**
obligations the feature page did *not* already carry (direct-Maven invocation, calibration-probe
mechanism, sync-on-environment-change, the no-longer-required Noble image, the commit prohibition,
the historical-records rule) — a delete-and-point would have dropped them, so the diff adds them.
Shared constants **18/20 → 2/20**.

**Wave 2 — safe dedupe.** Cut only prose already carried by the backticked declaration line, which
is designed to hold the invariant alone. 16 inline-code spans left the file; **all 16 remain in the
policy skill closure and none vanished**; all 18 checked repo-specific facts survive. Kept every
repo-specific fact: out-of-level destinations, the no-Maven-in-parallel-agents rule, plan-mode
phrasing, `-Pdist-smoke`, worktree closeout mechanics, the protected-surface list, the release
authority gate.

**Wave 3 — enforcement.** `CarveOutDriftTest` (dist-tool) turns each declared carve-out into a build
failure on drift, and requires every `[[carve_out]]` to have a guard — so the registry cannot grow
unenforced entries. Verified by positive control: seeded drift turns all three tests red.

**Result: always-loaded cost 7,488 → 6,240 tokens (−16.7 %); `CLAUDE.md` 622 → 530 lines.**

## Deliberate non-actions

- **Wholesale hoist of `Versioning` / `Files That Move Together` / `Git Hygiene`** (~32 % of the
  file). Deferred: none has an obvious canonical home, and none was gated. Revisit only with a
  destination doc that already owns the content, as `svg-render.md` did for Wave 1.
- **Deduping to the user's global `CLAUDE.md`**, which carries the same worktree-closeout mechanics.
  Rejected: that file is personal and uncommitted, so it fails the harmless-if-absent test for
  committed repo guidance. `AGENTS.md` routes other agent tools here that will not have it.
- **Chasing the `## Verification` ↔ `svg-render.md` containment below the 0.35 advisory band.** It
  sits at 0.374 after the hoist. Getting under it would mean deleting the three run commands, which
  are the point of the section and the target of `README.md:98`. A pointer necessarily shares
  vocabulary with its target; the substantive metric (shared constants) is what closed.
- **A repo-side record of the guard-glob boundary.** Declined by the maintainer; it lives in the
  local Obsidian vault and Claude memory instead.

## Measurement note for future passes

Containment is the wrong success metric for a dedupe. It is `|A ∩ B| / |A|`, so cutting overlapping
text shrinks numerator and denominator together — Planning moved only 0.813 → 0.777 while its bullets
went from 24 lines to 8. Use containment to *find* duplication and absolute token/line delta to
*measure* the reduction.

## Verification

```bash
git diff --check                                    # docs lane
./mvnw -pl dist-tool -am test -Dtest=CarveOutDriftTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw test
```

Audit gate per `CLAUDE.md ## Audit Gates`: `test-quality-audit` quick, scoped to `CarveOutDriftTest`.
No `devsecops-audit` — no trust boundary, dependency, or release surface changed.

Worktree builds need `MAVEN_USER_HOME=<primary>/.cache/maven/user-home` and an absolute
`-Dmaven.repo.local`; `.mvn/maven.config` sets a *relative* repo path that resolves to the empty
worktree copy otherwise.
