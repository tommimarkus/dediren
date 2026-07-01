# Spotless + SpotBugs Quality Gates (Local Gate, CI Report-Only) — Design

Date: 2026-07-01
Status: approved design, pending implementation plan

## Problem

The repo documents and enforces *structural* conventions (dependency spine via
ArchUnit + Enforcer, module charter) but has **no code-formatting or static
bug-analysis convention at all**: no `.editorconfig`, no Spotless, Checkstyle,
google-java-format, PMD, or SpotBugs in any of the 15 module poms, and no
format/lint step in CI. `CLAUDE.md` says nothing about indentation, braces,
import order, line length, or naming; source style is consistency-by-imitation
only. CI is `./mvnw -B -ntp test` plus `git diff --check`.

We want two additions:

- **Spotless** — deterministic Java formatting, enforced.
- **SpotBugs** — static correctness-bug analysis, enforced.

Both with a specific posture: **a hard gate when run locally, report-only in
CI** (CI surfaces findings but stays green).

## Non-goals

- **No security detectors in SpotBugs.** FindSecBugs is deliberately excluded;
  CodeQL (already in CI, `codeql.yml`) owns security scanning. SpotBugs stays
  focused on correctness bugs so the two engines don't double up.
- **No non-Java formatting.** Spotless is scoped to Java only in this iteration.
  POM sorting / JSON / Markdown formatting are trivial future extensions, left
  out to bound churn.
- **No always-on lifecycle binding.** Like every other gate here (`coverage`,
  `mutation`, `security-sca`, `sbom`), the checks live in an opt-in profile, not
  the default `test`/`verify` build.
- **No aspirational cleanup.** SpotBugs findings that are real but out of scope
  for this change are tracked as known debt (§3), not fixed opportunistically.
- **No version bump.** Internal build tooling, not a product/plugin contract
  change; per `## Versioning` the CalVer version is left untouched.

## Approach

A single opt-in root-pom **`quality` profile** mirroring the existing `coverage`
profile: plugin versions managed via root `<properties>`, config in
`pluginManagement` so all modules inherit, and absent from the default build. It
binds `spotless:check` and `spotbugs:check` to `verify`. A single property,
**`quality.fail`** (default `true`), toggles gate-vs-report.

- **Local gate:**

  ```bash
  ./mvnw -Pquality verify
  ```

  fails on any formatting violation or SpotBugs finding.

- **CI report-only:** a new non-blocking job/step runs

  ```bash
  ./mvnw -B -ntp -Pquality -Dquality.fail=false -DskipTests verify
  ```

  which compiles and runs both checks. `quality.fail=false` cleanly makes
  **SpotBugs** report-only (`failOnError=false`). **Spotless** has only
  fail-or-fix modes, so its report-only behavior comes from the CI step's
  `continue-on-error: true`: the Maven invocation may exit non-zero on
  formatting drift, but the job stays green and the drift is captured as an
  uploaded diff artifact. `-DskipTests` avoids re-running the suite already
  covered by the existing `test` job.

The gate-when-invoked model matches the repo's existing `coverage` profile,
which `CLAUDE.md` already documents and describes as a "local, opt-in ... gate"
that is "not run in CI." `quality` adds one twist over `coverage`: CI *does*
run it, but only to report.

## 1. Spotless configuration

- **Formatter:** `googleJavaFormat` in **GOOGLE** style (2-space indent,
  100-col), plus `removeUnusedImports`. Import ordering is handled by GJF.
- **Scope:** Java sources only. Exclude `target/`, `.cache/`, and any generated
  sources.
- **Placement:** `spotless-maven-plugin` version in root `<properties>`; config
  in root `pluginManagement` so all 15 modules inherit — consistent with how
  `maven-compiler-plugin`, `maven-enforcer-plugin`, and `maven-surefire-plugin`
  are centralized.
- **Check binding:** `spotless:check` bound to `verify` inside the `quality`
  profile. `spotless:check` fails on any unformatted file (the local gate).
  Spotless has no report-only mode, so CI's report-only posture is realized at
  the workflow step (`continue-on-error`), not a plugin flag — see §Approach.
  `${quality.fail}` therefore governs **SpotBugs**' `failOnError`; Spotless
  always gates when the profile runs locally.

### GOOGLE style is the largest-churn choice, by request

The tree is 190 files / ~35k LOC, uniformly 4-space indent, tolerant of ~120
cols (only ~0.7% of lines exceed 120). GOOGLE style (2-space, 100-col) reformats
essentially every indented line and rewraps ~1,475 lines over 100 cols. This is
the accepted, deliberate trade for the most recognizable Java style. The churn
is isolated (§4).

### Implementation risk: JDK module access

`google-java-format` under JDK 16+ historically requires
`--add-exports jdk.compiler/com.sun.tools.javac.*=ALL-UNNAMED` (and matching
`--add-opens`) for the Maven process. Modern Spotless usually injects these, but
if `spotless:apply`/`check` errors on `com.sun.tools.javac` access, the fix is a
`.mvn/jvm.config` (or plugin JVM args) carrying those exports. Resolved in the
plan, not guessed here.

## 2. SpotBugs configuration

- **Effort:** `Max`. **Threshold:** `Medium` (report-level `Medium`). Core
  detectors only; **no FindSecBugs**.
- **Placement:** `spotbugs-maven-plugin` version in root `<properties>`; config
  in root `pluginManagement`; `spotbugs:check` bound to `verify` in the
  `quality` profile, honoring `${quality.fail}` via the plugin's `failOnError`.
- **Exclusions:** a repo-root `spotbugs-exclude.xml` filter (referenced by
  `excludeFilterFile`) for suppressed findings and for classes SpotBugs cannot
  usefully analyze.
- SpotBugs analyzes compiled bytecode, so `verify` (post-`compile`) is the
  correct phase; the CI report step compiles via `-DskipTests verify`.

## 3. Pre-existing findings are tracked debt, not hidden

The first SpotBugs run over 35k LOC will surface some batch of findings. Because
the local gate fails on them, they must reach zero *before this lands*:

1. Run SpotBugs, triage the findings.
2. **Fix** trivial/clear correctness bugs (small, reviewable diffs).
3. **Suppress** anything genuinely deferred in `spotbugs-exclude.xml`, each entry
   commented with the reason, and record it in the **known architectural debt
   register** (`docs/architecture-guidelines.md §12`) so suppressions are
   visible debt, not silent gaps.

Goal: `./mvnw -Pquality verify` is green when the work is complete.

## 4. Rollout sequencing (each its own commit, per Git Hygiene)

1. **Wire Spotless** — `pluginManagement` config, `quality` profile, formatter
   settings, exclusions. No source reformatted yet.
2. **`spotless:apply` reformat** — the large mechanical diff, isolated in a
   single commit that changes nothing but formatting. Its commit SHA is then
   recorded in a new **`.git-blame-ignore-revs`** file so `git blame` (and
   GitHub's blame view via `blame.ignoreRevsFile`) skips it.
3. **Wire SpotBugs** — `pluginManagement` config, profile binding,
   `spotbugs-exclude.xml`; triage findings to green (§3).
4. **Docs + CI** — verification lane and Code Style note in `CLAUDE.md`, README
   contributor note, and the report-only CI step.

`.git-blame-ignore-revs` records the reformat SHA in a follow-on edit after the
reformat commit exists (the SHA isn't known until the commit is made).

## 5. Files that move together

- `pom.xml` — properties, `pluginManagement`, `quality` profile.
- `.github/workflows/ci.yml` — report-only step/job + artifact upload.
- `.git-blame-ignore-revs` — **new**, holds the reformat SHA.
- `spotbugs-exclude.xml` — **new**, suppression filter.
- `docs/architecture-guidelines.md §12` — any SpotBugs suppressions logged as
  known debt.
- `CLAUDE.md` — new `./mvnw -Pquality verify` verification lane, and a short
  **Code Style** convention note (the originally-identified documentation gap:
  google-java-format GOOGLE style is now the house standard, enforced by
  Spotless).
- `README.md` — one-line contributor/build note (only if it has such a section).
- This spec.

`docs/agent-usage.md` is **not** touched: it is the product-runtime guide for
agents using a Dediren archive, not a dev-build document.

## 6. Verification

- `./mvnw -Pquality verify` on a well-formatted, finding-free tree **passes**;
  introducing a mis-formatted file or a seeded SpotBugs finding **fails** it —
  proving both gates enforce locally.
- `./mvnw -Pquality -Dquality.fail=false verify` on the same seeded tree
  **passes** (exit 0) while still producing the reports — proving report-only
  mode.
- Plain `./mvnw test` (profile inactive) is unchanged: no Spotless, no SpotBugs,
  no new build output.
- Maven Enforcer `reactorModuleConvergence` / `dependencyConvergence` stay green
  (no new product module, no new product dependency — the plugins are build-time
  only).
- Note: Maven test runs need the command sandbox disabled in this environment
  (JUnit `@TempDir` on read-only `/tmp`).

## 7. Docs and hygiene

- Add `./mvnw -Pquality verify` to **CLAUDE.md → Verification**, and a **Code
  Style** note naming google-java-format GOOGLE style as the enforced standard.
- Add a one-line **README** contributor note if it has a build/contributing
  section.
- Reformat/report outputs land under `target/` (git-ignored) or are the tracked
  config files listed in §5; report the large reformat commit by SHA rather than
  re-listing files.
- No version bump (§Non-goals).
