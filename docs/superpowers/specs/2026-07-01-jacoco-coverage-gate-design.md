# JaCoCo Coverage Gate (Local Opt-In) — Design

Date: 2026-07-01
Status: approved design, pending implementation plan

## Problem

The reactor has substantial tests (47 test files across the modules) and a PIT
mutation profile, but no line/branch **coverage measurement** at all: no JaCoCo
or other coverage plugin in any of the 14 module poms, and no coverage step in
CI. There is no way to read a coverage number, see which code is exercised, or
catch coverage regressions.

We want JaCoCo added as a **hard gate** that fails the build below a threshold,
producing **per-module and one reactor-wide aggregate** report, packaged as a
**local opt-in profile** (no CI change in this iteration).

## Non-goals

- No CI wiring in this iteration. CI's default `./mvnw test` stays lean and is
  not changed; coverage is run on demand. (A future iteration may add a CI
  step that uploads or enforces; out of scope here.)
- No replacement of the existing PIT `mutation` profile. Coverage complements
  mutation testing; it does not supersede it.
- No aspirational coverage targets. Thresholds are baseline floors (see §3),
  not goals to climb toward in this change.
- No new reactor module. The aggregate is hosted in an existing module (§2).

## Approach

A new root-pom **`coverage` profile** that mirrors the existing optional
profiles (`mutation`, `sbom`, `security-sca`): opt-in, plugin version managed
via a root `<properties>` entry, and absent from the default build. The default
`./mvnw test` is untouched; coverage runs only via:

```bash
./mvnw -Pcoverage verify
```

`verify` is the single entry point because per-module `check`/`report` and the
aggregate `report-aggregate` all bind there.

## 1. Profile bindings

Add `jacoco-maven-plugin.version` to root `<properties>` (latest stable,
Java-21-ready; confirm exact value at implementation time, ~`0.8.13`).

In the `coverage` profile, configure `jacoco-maven-plugin` in `pluginManagement`
so modules inherit, with these executions:

- `prepare-agent` (initialize) — attaches the coverage agent. Every module that
  is not skipped (§4).
- `report` (verify) — per-module HTML + XML at `target/site/jacoco/`.
- `check` (verify) — the hard gate (§3). Gated modules only.
- `report-aggregate` (verify) — **`dist-tool` only** → merged reactor report at
  `dist-tool/target/site/jacoco-aggregate/`.

## 2. Aggregate host: reuse `dist-tool`, no new module

`report-aggregate` covers exactly the reactor modules reachable in the host
module's dependency closure. `dist-tool` is the top-of-graph assembly module
("nothing depends on it") and already depends on `cli` + every plugin. Its
closure reaches every product module:

- `core` ← `cli`
- `contracts` ← direct + transitive
- `archimate` ← `archimate-oef-export`, `generic-graph`, `render`
- `uml` ← `uml-xmi-export`, `generic-graph`, `render`
- `schema-cache` ← `archimate-oef-export`, `uml-xmi-export`
- all 5 plugins + `cli` ← direct dependencies

So hosting `report-aggregate` in `dist-tool` covers the whole product reactor
with **zero new modules and zero new dependencies**. `testbeds/plugin-runtime`
is not in the closure (correctly excluded); `test-support` is skipped at the
source (§4) so its classes do not pollute the aggregate.

Considered and rejected: a dedicated `coverage-report` aggregator module —
guaranteed-complete but adds a reactor module depending on everything. Rejected
under YAGNI because `dist-tool` already provides full reach.

## 3. The gate: LINE + BRANCH, ratcheted to baseline

A hard gate on an existing codebase must not break the build on day one, and an
aspirational number would. The gate is a **floor that locks in current coverage
and fails on regression**:

1. Measure current per-module LINE and BRANCH ratios first
   (`./mvnw -Pcoverage verify` with `check` temporarily relaxed, reading each
   `target/site/jacoco/jacoco.xml`).
2. Set each module's threshold at/just-below today's measured ratio (rounded
   down) for both counters.
3. Express as a global default minimum property (`jacoco.line.min`,
   `jacoco.branch.min`) applied via `pluginManagement`, with **per-module
   overrides only where a module sits below the global floor**.

Counters: **LINE and BRANCH** ratio at module (bundle) level.

Gated modules (11): `contracts`, `core`, `cli`, `archimate`, `uml`,
`schema-cache`, `archimate-oef-export`, `elk-layout`, `generic-graph`,
`render`, `uml-xmi-export`.

## 4. Skips and exclusions

- `jacoco.skip=true` (no agent, no report, no gate): `test-support` (test-helper
  library) and `testbeds/plugin-runtime` (testbed). Neither has a product
  coverage target.
- `dist-tool`: **ungated** but keeps the agent so it can host the aggregate. Its
  own code is build/assembly tooling, not a product gate.
- Within-module class `<excludes>` are added only if a specifically untestable
  class blocks a reasonable baseline; none assumed up front.

## 5. argLine integration (the one real risk)

The root pom defines `argLine` as a property
(`-Dfile.encoding=UTF-8 -Djava.awt.headless=true`), which surefire consumes.
JaCoCo's agent injects through the same property and can clobber it, silently
detaching the agent (the classic "coverage reads 0%" failure).

Handle it deterministically and in isolation: set `prepare-agent`'s
`propertyName=jacocoArgLine`, and override surefire **inside the `coverage`
profile only** so its `argLine` is `${jacocoArgLine} ${argLine}` (using
late `@{...}` evaluation as needed). Both compose; the base build is unaffected
because the override lives only in the profile.

## 6. Verification

- `./mvnw -Pcoverage verify` produces per-module reports **and** the
  `dist-tool` aggregate, with **non-zero** coverage — proving the agent
  attached (guards the §5 pitfall).
- Temporarily raising a threshold above baseline **fails** the build; the chosen
  floor **passes** — proving the gate enforces.
- Plain `./mvnw test` (profile inactive) is unchanged: no JaCoCo execution, no
  new build output.
- Maven Enforcer `reactorModuleConvergence` / `dependencyConvergence` stay
  green (no new module, no new product dependency).
- Note: Maven test runs need the command sandbox disabled in this environment
  (JUnit `@TempDir` on read-only `/tmp`).

## 7. Docs and hygiene

- Add the `./mvnw -Pcoverage verify` command to **CLAUDE.md → Verification**.
- Add a one-line note to **README** only if it has a contributor/build section;
  do not touch `docs/agent-usage.md` (product-runtime guide, not dev build).
- All outputs land under `target/` (already git-ignored) — nothing new to
  stage; report artifact paths instead.
- No version bump: this is non-released build tooling, so per `## Versioning`
  the product version is left untouched.
