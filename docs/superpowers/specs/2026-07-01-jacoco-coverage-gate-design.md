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
- `report-aggregate` (verify) — **`coverage-report` module only** → merged
  reactor report at `coverage-report/target/site/jacoco-aggregate/`.

## 2. Aggregate host: dedicated build-only `coverage-report` module

> Corrected during implementation. The original plan hosted `report-aggregate`
> in `dist-tool` on the premise that its transitive dependency closure reached
> every product module. That premise is false: JaCoCo `report-aggregate`
> collects coverage only from a host module's **direct** declared dependencies,
> not the transitive closure. `dist-tool`'s direct deps are `contracts`
> (compile) plus `cli` and the plugins (runtime); `core`, `archimate`, `uml`,
> and `schema-cache` are transitive-only, so a `dist-tool`-hosted aggregate
> would silently drop four product modules — and adding them as direct deps
> would put non-charter edges on the assembly module.

The aggregate lives in a dedicated build-only `coverage-report` module that
declares all 11 product modules as direct `runtime` dependencies and hosts
`report-aggregate` → `coverage-report/target/site/jacoco-aggregate/`. The module
ships nothing and nothing depends on it; both its product-module dependencies
and the `report-aggregate` execution are confined to the `coverage` profile, so
the default build treats it as an inert empty module and the dependency spine is
untouched. It is documented in `docs/architecture-guidelines.md` §2 (allowed-edge
table) and §3 (module charter).

The aggregate's coverage set is the hand-maintained dependency list in
`coverage-report/pom.xml`; a new product module must be added there to appear in
the aggregate (it is gated automatically unless skipped). `dist-tool`,
`test-support`, and `testbeds/plugin-runtime` are intentionally excluded.

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
