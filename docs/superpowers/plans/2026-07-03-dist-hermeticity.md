# Dist Packaging Hermeticity Implementation Plan

Status: complete — executed on main; see reviews/2026-07-03-multi-viewpoint-product-review.md ("Remediation status").

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `-Pdist-build` produce a correct archive regardless of what has
accumulated in `target/`, and fail loudly if the packaged `lib/` ever diverges
from the declared dependency set.

**Source findings:** SEED-1 (block, confirmed): the packaging tars
`cli/target/appassembler/lib` wholesale; the pre-review local archive shipped
11 stale `2026.06.9` jars (plus stale `.jsa` files), a non-clean rebuild
reproduced them, and the verifier proved the mechanism by planting a fake jar
that a non-clean `-Pdist-build` shipped. Recurred during the review itself
after the `2026.07.0` release build. Evidence and repro in
`docs/superpowers/reviews/2026-07-03-multi-viewpoint-product-review.md`.

**Architecture:** Two independent guards. (1) Hermetic input: the dist build
cleans/regenerates its appassembler input directories before packaging, so
stale files cannot ride along. (2) Verified output: `DistTool` (which already
asserts launcher flags) additionally verifies the assembled `lib/` against
the resolved runtime dependency set and fails the build on any foreign,
duplicate-artifact-different-version, or missing jar, and excludes `cds/`
and other runtime-generated files from the archive.

**Tech Stack:** Maven (appassembler/antrun/clean bindings), Java 21
(`dist-tool`), JUnit.

## Global Constraints

- `dist/`, `target/` stay git-ignored; never commit generated outputs.
- Distribution changes verify with `./mvnw test` and
  `./mvnw -pl dist-tool -am verify -Pdist-smoke`; release workflow
  (`.github/workflows/release.yml`) must keep passing untouched unless the
  version source moves.
- Do not weaken `DistModuleTest` / `AgentUsageDocConsistencyTest`.

---

### Task 1: Failing regression test — planted foreign jar must fail the build

**Files:**
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`
  (or a new `DistHermeticityTest`)

- [ ] Step 1: Reproduce SEED-1 manually (review repro): plant
      `cli/target/appassembler/lib/fake-stale-0.0.1.jar`, run
      `./mvnw -pl dist-tool -am verify -Pdist-build` (no clean), observe the
      fake jar inside the tarball. Expected today: build SUCCESS with the
      foreign jar shipped — the defect.
- [ ] Step 2: Write the failing test: assemble the bundle with a planted
      foreign jar in the staged lib input and assert the dist build (or
      DistTool verification) fails with a diagnostic naming the jar.
- [ ] Step 3: Run it — expected: FAIL (build currently succeeds). Commit the
      test disabled or in the same commit as Task 2's fix, per repo test
      style.

### Task 2: Implement the guards

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
  (lib verification against the resolved dependency list; exclude `cds/`,
  `*.jsa`, and any non-declared file kinds from the archive)
- Modify: dist packaging build wiring (parent `pluginManagement` /
  `dist-build` profile) so appassembler input dirs are cleaned or freshly
  regenerated before packaging

- [ ] Step 1: Implement input cleaning: bind a delete of each module's
      `target/appassembler` (or regenerate to a build-scoped directory) ahead
      of assembly in the `dist-build` profile.
- [ ] Step 2: Implement output verification in DistTool: exact-match the
      packaged `lib/` jar set against the resolved runtime classpath
      (name+version), fail on extras/missing; exclude runtime-generated
      `cds/` content from the tar.
- [ ] Step 3: Task 1's test now passes (build fails on planted jar; clean
      build passes with 0 foreign jars).
- [ ] Step 4: Full lane: `./mvnw test` and
      `./mvnw -pl dist-tool -am verify -Pdist-build -Pdist-smoke`; verify the
      fresh tarball: `tar -tzf dist/*.tar.gz | grep -cE '\.jsa$|2026\.06\.'`
      → 0. Commit.

### Task 3: Release-lane note

**Files:**
- Modify: `README.md` release/verification section (one paragraph)

- [ ] Step 1: Document that dist builds are now hermetic and verified, and
      that locally built archives are safe to distribute; note the guard in
      the release runbook so future release automation keeps it.
- [ ] Step 2: `git diff --check`; commit.
