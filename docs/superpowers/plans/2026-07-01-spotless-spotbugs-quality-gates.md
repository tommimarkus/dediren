# Spotless + SpotBugs Quality Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Spotless (google-java-format) and SpotBugs to the Maven build as a local gate that fails the build, running report-only in CI.

**Architecture:** One opt-in root-pom `quality` profile — mirroring the existing `coverage` profile — binds `spotless:check` and `spotbugs:check` to `verify`. A single property `quality.fail` (default `true`) governs SpotBugs' fail-on-bug behavior; Spotless always gates locally, and its CI report-only posture is achieved at the workflow-step level. A one-time `spotless:apply` reformat lands as an isolated commit recorded in `.git-blame-ignore-revs`.

**Tech Stack:** Maven multi-module (15 modules, Java 21), `com.diffplug.spotless:spotless-maven-plugin`, `com.github.spotbugs:spotbugs-maven-plugin`, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-07-01-spotless-spotbugs-quality-gates-design.md`

## Global Constraints

- **Java 21** floor; build emits Java 21 bytecode (`maven.compiler.release=21`).
- **Formatter:** google-java-format, **GOOGLE** style (2-space indent, 100-col) + `removeUnusedImports`. Java only.
- **SpotBugs:** effort `Max`, threshold `Medium`, **core detectors only — no FindSecBugs** (CodeQL owns security).
- **Posture:** local `./mvnw -Pquality verify` is a hard gate; CI runs the same checks report-only and stays green.
- **No version bump.** Internal build tooling; per `## Versioning` the CalVer version (`2026.06.10`) is untouched.
- **Suppressions are tracked debt.** Every SpotBugs `<Match>` in `spotbugs-exclude.xml` gets a reason comment and a row in `docs/architecture-guidelines.md §12`.
- **Env:** Maven test runs need the command sandbox **disabled** (JUnit `@TempDir` on read-only `/tmp`). `git commit` is SSH-signed and also needs the sandbox **disabled**.
- **Git hygiene:** stage explicit paths only (never `git add -A`; the tree has untracked user dotfiles). Each task is its own commit(s). Conventional-commit messages (`build(...)`, `style(...)`, `docs(...)`, `ci(...)`).

---

### Task 1: Wire Spotless (config only — no reformat yet)

Adds the plugin, the `quality` profile, and the JDK access config. No source is reformatted; the gate is intentionally **red** after this task and goes green in Task 2. `quality` is opt-in and not in CI yet, so nothing else is affected.

**Files:**
- Modify: `pom.xml` — add version property (`<properties>`, after line 57) and the `quality` profile (`<profiles>`, after the `coverage` profile ends at line 441).
- Create: `.mvn/jvm.config` — JDK module exports google-java-format needs on JDK 16+.

**Interfaces:**
- Produces: root-pom profile id `quality`; property `quality.fail` (default `true`); property `spotless-maven-plugin.version`. Task 3 extends the same `quality` profile; Task 5's CI job invokes it.

- [ ] **Step 1: Add the Spotless version property**

In `pom.xml`, inside `<properties>`, immediately after the `jacoco-maven-plugin.version` line (line 57), add:

```xml
    <spotless-maven-plugin.version>2.44.5</spotless-maven-plugin.version>
```

Note: `2.44.5` is a known-good floor; if a newer 2.4x is current, use it. Confirm at implementation time.

- [ ] **Step 2: Add the `quality` profile with Spotless**

In `pom.xml`, inside `<profiles>`, immediately after the closing `</profile>` of the `coverage` profile (line 441, just before `</profiles>` on 442), add:

```xml
    <profile>
      <!-- Opt-in code-quality gate: Spotless (formatting) + SpotBugs (added in
           a later change). Local `./mvnw -Pquality verify` is a hard gate;
           CI runs the same checks report-only. `quality.fail` toggles SpotBugs'
           fail-on-bug behavior; Spotless always gates when this profile runs.
           See docs/superpowers/specs/2026-07-01-spotless-spotbugs-quality-gates-design.md -->
      <id>quality</id>
      <properties>
        <quality.fail>true</quality.fail>
      </properties>
      <build>
        <pluginManagement>
          <plugins>
            <plugin>
              <groupId>com.diffplug.spotless</groupId>
              <artifactId>spotless-maven-plugin</artifactId>
              <version>${spotless-maven-plugin.version}</version>
              <configuration>
                <java>
                  <googleJavaFormat>
                    <style>GOOGLE</style>
                  </googleJavaFormat>
                  <removeUnusedImports/>
                </java>
              </configuration>
              <executions>
                <execution>
                  <id>spotless-check</id>
                  <phase>verify</phase>
                  <goals>
                    <goal>check</goal>
                  </goals>
                </execution>
              </executions>
            </plugin>
          </plugins>
        </pluginManagement>
        <plugins>
          <plugin>
            <groupId>com.diffplug.spotless</groupId>
            <artifactId>spotless-maven-plugin</artifactId>
          </plugin>
        </plugins>
      </build>
    </profile>
```

(The bare `<plugin>` in `<build><plugins>` activates the managed executions in every module, exactly as the `coverage` profile does for JaCoCo.)

- [ ] **Step 3: Create `.mvn/jvm.config`**

google-java-format on JDK 16+ needs `jdk.compiler` internals opened to the Maven JVM. Create `.mvn/jvm.config` (one arg per line):

```
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
--add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-opens jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
```

This affects only the Maven process JVM (not forked test JVMs, the appassembler launcher, or ELK), so runtime behavior is unchanged.

- [ ] **Step 4: Verify Spotless is wired (expect FAIL — the gate is live)**

Run (sandbox disabled):

```bash
./mvnw -B -ntp -Pquality spotless:check
```

Expected: **BUILD FAILURE** with `The following files had format violations` listing many `.java` files across modules. This proves the plugin resolves, the JDK exports work (no `IllegalAccessError` / "module jdk.compiler does not export"), and the check is active. If instead you see an access error, re-check `.mvn/jvm.config` (Step 3).

- [ ] **Step 5: Confirm the profile parses cleanly across the reactor**

Run (sandbox disabled):

```bash
./mvnw -B -ntp -Pquality validate
```

Expected: **BUILD SUCCESS** — every module inherits the profile and the `validate` phase runs without error (Spotless binds to `verify`, so it does not fire here). Plain `./mvnw test` without `-Pquality` is unaffected; the plugin only runs under the profile.

- [ ] **Step 6: Commit**

```bash
git add pom.xml .mvn/jvm.config
git commit -m "build(quality): wire spotless (google-java-format GOOGLE) behind quality profile"
```

---

### Task 2: One-time reformat + `.git-blame-ignore-revs`

Applies google-java-format to the whole tree in one mechanical, semantics-preserving commit, then records that commit's SHA so `git blame` skips it. After this task the Spotless gate is **green**.

**Files:**
- Modify: every `**/src/{main,test}/java/**/*.java` (mechanical reformat via `spotless:apply`).
- Create: `.git-blame-ignore-revs` — the reformat SHA + rationale.

**Interfaces:**
- Consumes: the `quality` profile / Spotless config from Task 1.
- Produces: `.git-blame-ignore-revs` at repo root (GitHub auto-detects it; devs opt in locally via `git config blame.ignoreRevsFile .git-blame-ignore-revs`).

- [ ] **Step 1: Apply the formatter**

Run (sandbox disabled):

```bash
./mvnw -B -ntp -Pquality spotless:apply
```

Expected: **BUILD SUCCESS**; a large number of `.java` files are now modified in the working tree.

- [ ] **Step 2: Confirm the reformat is green and compiles**

```bash
./mvnw -B -ntp -Pquality spotless:check
```

Expected: **BUILD SUCCESS** (`All formatting is correct` / no violations).

- [ ] **Step 3: Confirm tests still pass (reformat must be behavior-preserving)**

Run (sandbox disabled):

```bash
./mvnw -B -ntp test
```

Expected: **BUILD SUCCESS**, all modules green. google-java-format changes only whitespace/wrapping/import order, so string literals and version-assertion tests are unaffected. If any test fails, STOP and investigate — a real reformat should not break tests.

- [ ] **Step 4: Commit the reformat in isolation**

Stage only tracked Java sources (`-u` avoids picking up any stray untracked file; the tree has untracked user dotfiles — never `git add -A`):

```bash
git add -u -- '*.java'
git commit -m "style: reformat all Java with google-java-format (GOOGLE) via spotless

Mechanical, semantics-preserving reformat. No behavior change.
Recorded in .git-blame-ignore-revs."
```

- [ ] **Step 5: Record the SHA in `.git-blame-ignore-revs`**

Capture the reformat commit's full SHA and write `.git-blame-ignore-revs` at repo root:

```bash
REFORMAT_SHA=$(git rev-parse HEAD)
printf '%s\n%s\n%s\n' \
  '# Bulk reformat: adopt google-java-format (GOOGLE style) via Spotless.' \
  '# Semantics-preserving; ignore in git blame. See docs/superpowers/specs/2026-07-01-spotless-spotbugs-quality-gates-design.md' \
  "$REFORMAT_SHA" > .git-blame-ignore-revs
```

- [ ] **Step 6: Commit the ignore-revs file**

```bash
git add .git-blame-ignore-revs
git commit -m "build(quality): record spotless reformat SHA in .git-blame-ignore-revs"
```

---

### Task 3: Wire SpotBugs + triage findings to green

Adds SpotBugs to the same `quality` profile, seeds an empty suppression filter, then runs SpotBugs and drives the finding count to zero — fixing trivial correctness bugs and suppressing (with tracked-debt entries) anything deferred. The finding list is **discovered at execution**; this task specifies the exact mechanism, not a pre-known fix list.

**Files:**
- Modify: `pom.xml` — add `spotbugs-maven-plugin.version` property (after the Spotless property from Task 1) and the SpotBugs plugin blocks inside the `quality` profile.
- Create: `spotbugs-exclude.xml` — repo-root suppression filter.
- Modify (as findings dictate): specific `*.java` files (fixes) and `docs/architecture-guidelines.md` (§12 debt rows for suppressions).

**Interfaces:**
- Consumes: the `quality` profile and `quality.fail` property from Task 1.
- Produces: property `spotbugs-maven-plugin.version`; repo-root `spotbugs-exclude.xml` referenced via `${maven.multiModuleProjectDirectory}`.

- [ ] **Step 1: Add the SpotBugs version property**

In `pom.xml` `<properties>`, immediately after the `spotless-maven-plugin.version` line (added in Task 1), add:

```xml
    <spotbugs-maven-plugin.version>4.9.6.0</spotbugs-maven-plugin.version>
```

Note: `4.9.6.0` is a known-good floor; use the current 4.9.x if newer. Confirm at implementation time.

- [ ] **Step 2: Create the (empty) suppression filter**

Create `spotbugs-exclude.xml` at repo root:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- SpotBugs suppression filter. Every <Match> below is tracked known debt:
     add a matching row to docs/architecture-guidelines.md §12 stating the
     reason. Do not suppress silently.
     See docs/superpowers/specs/2026-07-01-spotless-spotbugs-quality-gates-design.md §3 -->
<FindBugsFilter>
</FindBugsFilter>
```

- [ ] **Step 3: Add SpotBugs to the `quality` profile**

In `pom.xml`, inside the `quality` profile's `<build><pluginManagement><plugins>` (alongside the Spotless plugin from Task 1), add:

```xml
            <plugin>
              <groupId>com.github.spotbugs</groupId>
              <artifactId>spotbugs-maven-plugin</artifactId>
              <version>${spotbugs-maven-plugin.version}</version>
              <configuration>
                <effort>Max</effort>
                <threshold>Medium</threshold>
                <!-- ${quality.fail}=true → local gate; CI passes false for report-only. -->
                <failOnError>${quality.fail}</failOnError>
                <includeTests>false</includeTests>
                <excludeFilterFile>${maven.multiModuleProjectDirectory}/spotbugs-exclude.xml</excludeFilterFile>
              </configuration>
              <executions>
                <execution>
                  <id>spotbugs-check</id>
                  <phase>verify</phase>
                  <goals>
                    <goal>check</goal>
                  </goals>
                </execution>
              </executions>
            </plugin>
```

And in the profile's `<build><plugins>` (alongside the bare Spotless plugin), add:

```xml
          <plugin>
            <groupId>com.github.spotbugs</groupId>
            <artifactId>spotbugs-maven-plugin</artifactId>
          </plugin>
```

Note: the `check` goal's fail-on-bugs parameter is `<failOnError>` in current spotbugs-maven-plugin; if your version names it differently, bind `${quality.fail}` to the correct element (verify against the `spotbugs:check` goal docs).

- [ ] **Step 4: Surface the findings**

Run (sandbox disabled), report-only so it enumerates instead of aborting on the first module:

```bash
./mvnw -B -ntp -Pquality -Dquality.fail=false -Dspotless.check.skip=true -DskipTests verify
```

Expected: **BUILD SUCCESS**; each analyzed module writes `target/spotbugsXml.xml`. List the findings:

```bash
find . -name spotbugsXml.xml -not -path '*/.cache/*' \
  -exec sh -c 'echo "== $1 =="; grep -o "type=\"[^\"]*\"" "$1" | sort | uniq -c' _ {} \;
```

- [ ] **Step 5: Triage each finding — fix or suppress**

For each finding, decide:

- **Fix** (preferred for clear correctness bugs): make the minimal source change. Keep each fix small and reviewable.
- **Suppress** (only when a fix is out of scope for this change): add a narrow `<Match>` to `spotbugs-exclude.xml`, commented with the reason, e.g.:

```xml
  <!-- <reason>: <why deferred>. Tracked in architecture-guidelines.md §12. -->
  <Match>
    <Class name="dev.dediren.plugins.render.Main"/>
    <Bug pattern="EI_EXPOSE_REP"/>
  </Match>
```

  Then add a row to the `docs/architecture-guidelines.md §12` "Known architectural debt" table (the table at line 440):

```markdown
| SpotBugs <PATTERN> suppressed in <Class> | `spotbugs-exclude.xml` | quality gate | deferred: <reason> |
```

Prefer the narrowest match (class + pattern, or method) over blanket pattern-wide suppressions.

- [ ] **Step 6: Verify the gate is green**

Run (sandbox disabled) as a true local gate (fail-on-bug on):

```bash
./mvnw -B -ntp -Pquality -Dspotless.check.skip=true -DskipTests verify
```

Expected: **BUILD SUCCESS** — zero unsuppressed findings.

- [ ] **Step 7: Verify report-only mode does not fail**

Confirm the exact flag path CI will use:

```bash
./mvnw -B -ntp -Pquality -Dquality.fail=false -Dspotless.check.skip=true -DskipTests verify
```

Expected: **BUILD SUCCESS** with `target/spotbugsXml.xml` present in analyzed modules — proving CI's report-only invocation stays green even if findings exist.

- [ ] **Step 8: Commit**

```bash
git add pom.xml spotbugs-exclude.xml docs/architecture-guidelines.md
# plus any source files you fixed:
git add <path/to/fixed/File.java> ...
git commit -m "build(quality): add spotbugs (Max/Medium, correctness) to quality gate"
```

---

### Task 4: Document the gate (CLAUDE.md + README)

Adds the verification lane and the Code Style convention note — the documentation gap that motivated this work — plus a README contributor note.

**Files:**
- Modify: `CLAUDE.md` — add a `## Code Style` section and a `quality` verification lane (near line 204–208).
- Modify: `README.md` — add a `./mvnw -Pquality verify` note in "Build And Test" (near line 39).

**Interfaces:**
- Consumes: the `quality` profile (Task 1/3).

- [ ] **Step 1: Add the verification lane to CLAUDE.md**

In `CLAUDE.md`, immediately after the coverage lane block (the closing ``` on line 208), insert:

````markdown

Code style + static analysis (local, opt-in gate — fails on violations; CI runs
the same checks report-only):

```bash
./mvnw -Pquality verify          # full gate (format + SpotBugs + tests)
./mvnw -Pquality spotless:check  # formatting only
./mvnw -Pquality spotless:apply  # auto-fix formatting
```
````

- [ ] **Step 2: Add a `## Code Style` section to CLAUDE.md**

In `CLAUDE.md`, immediately before the `## Verification` heading (line 187), insert:

```markdown
## Code Style

- Java is formatted by **google-java-format (GOOGLE style)** enforced via
  Spotless; SpotBugs (Max effort, Medium threshold, correctness only) runs
  alongside it. Both live in the opt-in `quality` profile.
- Run `./mvnw -Pquality spotless:apply` before committing Java changes; the gate
  (`./mvnw -Pquality verify`) fails on unformatted code or SpotBugs findings.
- SpotBugs suppressions live in `spotbugs-exclude.xml` and must be recorded as
  known debt in `docs/architecture-guidelines.md §12` — never suppress silently.
- Security scanning is CodeQL's job (CI), not SpotBugs; do not add FindSecBugs.

```

- [ ] **Step 3: Add the README contributor note**

In `README.md`, replace the coverage note (lines 39–41) so it also covers the quality gate. Change:

```markdown
Run `./mvnw -Pcoverage verify` for the opt-in JaCoCo coverage report and gate
(LINE + BRANCH; per-module reports plus an aggregate under
`coverage-report/target/site/jacoco-aggregate/`).
```

to:

```markdown
Run `./mvnw -Pcoverage verify` for the opt-in JaCoCo coverage report and gate
(LINE + BRANCH; per-module reports plus an aggregate under
`coverage-report/target/site/jacoco-aggregate/`).

Run `./mvnw -Pquality verify` for the opt-in code-quality gate: google-java-format
(GOOGLE style) via Spotless plus SpotBugs. Auto-fix formatting with
`./mvnw -Pquality spotless:apply`. The gate fails the build locally; CI runs the
same checks report-only. To skip the bulk-reformat commit in blame, run
`git config blame.ignoreRevsFile .git-blame-ignore-revs`.
```

- [ ] **Step 4: Verify docs render / no whitespace issues**

```bash
git diff --check
```

Expected: no output (no trailing-whitespace/conflict markers).

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs(quality): document code-style gate and quality verification lane"
```

---

### Task 5: CI report-only job

Adds a non-blocking CI job that runs both checks and uploads their reports without failing the build.

**Files:**
- Modify: `.github/workflows/ci.yml` — add a `quality-report` job after the `test` job.

**Interfaces:**
- Consumes: the `quality` profile and `quality.fail` property.

- [ ] **Step 1: Add the `quality-report` job**

In `.github/workflows/ci.yml`, after the `test` job (which ends at line 49) and before `vulnerability-scan:` (line 51), add:

```yaml
  quality-report:
    # Non-blocking: Spotless + SpotBugs run here report-only. The local
    # `./mvnw -Pquality verify` gate is where these fail; CI only surfaces
    # findings as artifacts. See
    # docs/superpowers/specs/2026-07-01-spotless-spotbugs-quality-gates-design.md
    runs-on: ubuntu-24.04
    timeout-minutes: 20
    steps:
      - name: Checkout
        uses: actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0 # v7.0.0
        with:
          persist-credentials: false

      - name: Set up Java
        uses: actions/setup-java@1bcf9fb12cf4aa7d266a90ae39939e61372fe520 # v5.4.0
        with:
          distribution: temurin
          java-version: "21"

      - name: Cache Maven artifacts
        uses: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0
        with:
          path: .cache/maven
          key: maven-${{ runner.os }}-${{ hashFiles('**/pom.xml', '.mvn/wrapper/maven-wrapper.properties') }}
          restore-keys: |
            maven-${{ runner.os }}-

      - name: SpotBugs (report-only)
        continue-on-error: true
        run: ./mvnw -B -ntp -Pquality -Dquality.fail=false -Dspotless.check.skip=true -DskipTests verify

      - name: Spotless drift (report-only)
        continue-on-error: true
        run: |
          ./mvnw -B -ntp -Pquality spotless:apply
          git --no-pager diff > spotless-drift.patch || true

      - name: Upload quality reports
        if: always()
        uses: actions/upload-artifact@<pin-current-v4-sha> # v4
        with:
          name: quality-reports
          path: |
            **/target/spotbugsXml.xml
            spotless-drift.patch
          if-no-files-found: ignore
```

Note: pin `actions/upload-artifact` to its current v4 commit SHA, matching the SHA-pinning convention used by every other action in this file (look up the SHA at implementation time; do not leave the `<pin-current-v4-sha>` placeholder).

- [ ] **Step 2: Validate the workflow YAML**

```bash
git diff --check
```

Expected: no output. If `actionlint` is available, run it; otherwise confirm indentation matches the sibling `vulnerability-scan` job by inspection.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci(quality): add non-blocking spotless + spotbugs report job"
```

---

## Post-plan verification (whole change)

Run (sandbox disabled) from a clean tree:

- [ ] `./mvnw -B -ntp -Pquality verify` → **BUILD SUCCESS** (full local gate green: format + SpotBugs + tests).
- [ ] `./mvnw -B -ntp test` → **BUILD SUCCESS** (default build unchanged; no Spotless/SpotBugs).
- [ ] Introduce a deliberate mis-format in one file, run `./mvnw -Pquality spotless:check` → **BUILD FAILURE**; revert. (Proves the gate bites.)
- [ ] `git blame` on a reformatted file with `git config blame.ignoreRevsFile .git-blame-ignore-revs` set attributes lines to their pre-reformat commits.
- [ ] `git status --short --branch` → only the intended tracked files changed; untracked user dotfiles untouched.

## Audit gate

This plan touches CI/CD and dependency posture (new build plugins, new CI job). Per `CLAUDE.md → Audit Gates`, run a **quick `devsecops-audit`** over the diff (new plugin coordinates, CI job permissions, artifact handling) before calling the work complete. No product runtime or test-behavior change, so a deep `test-quality-audit` is not required.
