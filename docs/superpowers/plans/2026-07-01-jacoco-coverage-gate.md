# JaCoCo Coverage Gate (Local Opt-In) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JaCoCo as an opt-in `coverage` Maven profile that produces per-module + one reactor-wide aggregate report and hard-fails (LINE + BRANCH, ratcheted to baseline) on the product modules — with no change to the default build or CI.

**Architecture:** A root-pom `coverage` profile mirrors the existing `mutation`/`sbom`/`security-sca` profiles. The profile's `pluginManagement` + `<build><plugins>` are inherited by every reactor module when `-Pcoverage` is active. `dist-tool` (top of the dependency graph) hosts the `report-aggregate` goal, covering the whole reactor with no new module. Coverage runs only via `./mvnw -Pcoverage verify`.

**Tech Stack:** Maven (multi-module reactor via `./mvnw`), `org.jacoco:jacoco-maven-plugin`, JUnit 5 + Surefire, Java 21.

## Global Constraints

- Build with the checked-in Maven Wrapper `./mvnw`. Java 21+ only.
- Maven test runs need the command sandbox **disabled** in this environment (JUnit `@TempDir` writes under read-only `/tmp` otherwise). Run every `./mvnw ... test`/`verify` step with the sandbox disabled.
- Git: direct commits to `main` are allowed; **stage explicit paths only** (never `git add -A` — untracked env files like `.bashrc`, `.idea/` exist in the worktree). Commit signing needs the sandbox **disabled**.
- **No version bump.** This is non-released build tooling; per `## Versioning` the product version (`2026.06.10`) stays untouched. Do not edit any version-assertion surface.
- Do not change the default build, CI, or `docs/agent-usage.md`. Coverage is opt-in via `-Pcoverage` only.
- JaCoCo version: use `0.8.13` (confirm it is the latest stable supporting Java 21 at implementation time; bump the property if a newer patch exists).
- All JaCoCo output lands under `target/` (git-ignored) — never stage report artifacts.

---

### Task 1: Scaffold the `coverage` profile (agent + per-module report)

Adds JaCoCo to every module behind the opt-in profile, proves the agent actually attaches (non-zero coverage — the `argLine` pitfall guard), and skips the two non-product modules. No gate and no aggregate yet.

**Files:**
- Modify: `pom.xml` (root) — add version property + `coverage` profile.
- Modify: `test-support/pom.xml` — skip JaCoCo when the profile is active.
- Modify: `testbeds/plugin-runtime/pom.xml` — skip JaCoCo when the profile is active.

**Interfaces:**
- Produces: a root `coverage` profile whose `pluginManagement` declares `org.jacoco:jacoco-maven-plugin` (version `${jacoco-maven-plugin.version}`) with executions `jacoco-prepare-agent` (phase `initialize`) and `jacoco-report` (phase `verify`), and a `<build><plugins>` binding. Later tasks add the `jacoco-check` execution (Task 3) and a `report-aggregate` execution in `dist-tool` (Task 2). The `coverage` profile id is the activation key (`-Pcoverage`) every module relies on.

- [ ] **Step 1: Add the version property**

In `pom.xml`, inside `<properties>`, add after the `pitest-junit5-plugin.version` line (currently the last plugin-version property):

```xml
    <jacoco-maven-plugin.version>0.8.13</jacoco-maven-plugin.version>
```

- [ ] **Step 2: Add the `coverage` profile**

In `pom.xml`, inside `<profiles>`, add after the closing `</profile>` of the `mutation` profile (the last profile), before `</profiles>`:

```xml
    <profile>
      <id>coverage</id>
      <build>
        <pluginManagement>
          <plugins>
            <!-- Re-add the base test JVM flags here because JaCoCo's
                 prepare-agent sets the same `argLine` property the base build
                 uses; @{argLine} late-binds JaCoCo's agent and the flags below
                 restore encoding/headless. This override lives only in the
                 coverage profile, so the default build is untouched. -->
            <plugin>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-surefire-plugin</artifactId>
              <configuration>
                <argLine>@{argLine} -Dfile.encoding=UTF-8 -Djava.awt.headless=true</argLine>
              </configuration>
            </plugin>
            <plugin>
              <groupId>org.jacoco</groupId>
              <artifactId>jacoco-maven-plugin</artifactId>
              <version>${jacoco-maven-plugin.version}</version>
              <executions>
                <execution>
                  <id>jacoco-prepare-agent</id>
                  <phase>initialize</phase>
                  <goals>
                    <goal>prepare-agent</goal>
                  </goals>
                </execution>
                <execution>
                  <id>jacoco-report</id>
                  <phase>verify</phase>
                  <goals>
                    <goal>report</goal>
                  </goals>
                </execution>
              </executions>
            </plugin>
          </plugins>
        </pluginManagement>
        <plugins>
          <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
          </plugin>
        </plugins>
      </build>
    </profile>
```

- [ ] **Step 3: Skip JaCoCo in the two non-product modules**

In `test-support/pom.xml`, add a `<profiles>` block as the last child before `</project>` (if `<profiles>` already exists, add the profile inside it):

```xml
  <profiles>
    <profile>
      <id>coverage</id>
      <properties>
        <jacoco.skip>true</jacoco.skip>
      </properties>
    </profile>
  </profiles>
```

Add the identical block to `testbeds/plugin-runtime/pom.xml`.

- [ ] **Step 4: Run the coverage build (sandbox disabled)**

Run: `./mvnw -B -ntp -Pcoverage verify`
Expected: `BUILD SUCCESS`. (Tests run with the agent attached; no AWT/headless errors.)

- [ ] **Step 5: Verify the agent attached (non-zero coverage)**

Run:

```bash
python3 - <<'EOF'
import xml.etree.ElementTree as ET
t = ET.parse('core/target/site/jacoco/jacoco.xml')
line = next(c for c in t.getroot().findall('counter') if c.get('type') == 'LINE')
covered = int(line.get('covered'))
print('core LINE covered =', covered)
assert covered > 0, 'agent not attached / no coverage recorded'
print('OK: agent attached')
EOF
```

Expected: `core LINE covered = <positive number>` then `OK: agent attached`.
Also confirm `test-support/target/site/jacoco/` does **not** exist (skipped).

- [ ] **Step 6: Verify the default build is unaffected**

Run: `./mvnw -B -ntp -pl core help:active-profiles`
Expected: the active-profiles listing does **not** include `coverage` (it only activates with `-Pcoverage`).

- [ ] **Step 7: Commit**

```bash
git add pom.xml test-support/pom.xml testbeds/plugin-runtime/pom.xml
git commit -m "build(coverage): add opt-in JaCoCo agent + per-module report profile"
```

---

### Task 2: Reactor-wide aggregate report in `dist-tool`

Adds the single merged report covering the whole product reactor. `dist-tool` already depends (transitively) on every product module, so `report-aggregate` needs no new dependency.

**Files:**
- Modify: `dist-tool/pom.xml` — add a `coverage` profile with the `report-aggregate` execution.

**Interfaces:**
- Consumes: the root `coverage` profile's inherited `pluginManagement` (provides the JaCoCo plugin version and the inherited `prepare-agent`/`report` executions for `dist-tool` itself).
- Produces: aggregate report at `dist-tool/target/site/jacoco-aggregate/index.html`. Task 3 adds a `jacoco-check` phase-`none` override to this same profile to keep `dist-tool` ungated.

- [ ] **Step 1: Add the aggregate execution**

In `dist-tool/pom.xml`, inside the existing `<profiles>` element, add after the last `</profile>` (the `dist-bench` profile), before `</profiles>`:

```xml
    <profile>
      <id>coverage</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
            <executions>
              <execution>
                <id>jacoco-aggregate</id>
                <phase>verify</phase>
                <goals>
                  <goal>report-aggregate</goal>
                </goals>
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </profile>
```

- [ ] **Step 2: Run the coverage build (sandbox disabled)**

Run: `./mvnw -B -ntp -Pcoverage verify`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Verify the aggregate covers multiple modules**

Run:

```bash
python3 - <<'EOF'
import xml.etree.ElementTree as ET
t = ET.parse('dist-tool/target/site/jacoco-aggregate/jacoco.xml')
pkgs = sorted({p.get('name') for p in t.getroot().findall('package')})
print('aggregate packages:', len(pkgs))
for p in pkgs: print(' ', p)
assert any('core' in p for p in pkgs) and any('contracts' in p for p in pkgs), \
    'aggregate is missing expected product modules'
print('OK: aggregate spans the reactor')
EOF
```

Expected: a list including `dev/dediren/core`, `dev/dediren/contracts`, `dev/dediren/cli`, the plugin packages, etc., then `OK: aggregate spans the reactor`.

- [ ] **Step 4: Commit**

```bash
git add dist-tool/pom.xml
git commit -m "build(coverage): aggregate reactor JaCoCo report in dist-tool"
```

---

### Task 3: Hard gate — LINE + BRANCH, ratcheted to baseline

Adds the `jacoco-check` gate to the product modules at floors set from the measured baseline (so the build passes today and fails on regression), and keeps `dist-tool` ungated.

**Files:**
- Modify: `pom.xml` (root) — add `jacoco.line.min`/`jacoco.branch.min` properties + the `jacoco-check` execution.
- Modify: `dist-tool/pom.xml` — disable the inherited `jacoco-check` (phase `none`).
- Modify (only if a gated module's baseline forces it): `<module>/pom.xml` — per-module threshold override.

**Interfaces:**
- Consumes: the per-module `jacoco.xml` reports produced by Task 1/2.
- Produces: `jacoco-check` (phase `verify`, `BUNDLE` element, `LINE` + `BRANCH` `COVEREDRATIO` limits reading `${jacoco.line.min}` / `${jacoco.branch.min}`), enforced on the 11 product modules: `contracts`, `core`, `cli`, `archimate`, `uml`, `schema-cache`, `archimate-oef-export`, `elk-layout`, `generic-graph`, `render`, `uml-xmi-export`.

- [ ] **Step 1: Measure the baseline**

Ensure reports are fresh (`./mvnw -B -ntp -Pcoverage verify` if not run this session), then:

```bash
python3 - <<'EOF'
import glob, xml.etree.ElementTree as ET
mods = ['contracts','core','cli','archimate','uml','schema-cache',
        'plugins/archimate-oef-export','plugins/elk-layout',
        'plugins/generic-graph','plugins/render','plugins/uml-xmi-export']
def ratio(counter):
    if counter is None: return None
    c, m = int(counter.get('covered')), int(counter.get('missed'))
    return c/(c+m) if (c+m) else None
worst_line = worst_branch = 1.0
for mod in mods:
    f = f'{mod}/target/site/jacoco/jacoco.xml'
    root = ET.parse(f).getroot()
    line = next((c for c in root.findall('counter') if c.get('type')=='LINE'), None)
    branch = next((c for c in root.findall('counter') if c.get('type')=='BRANCH'), None)
    rl, rb = ratio(line), ratio(branch)
    print(f'{mod:34s} LINE={rl if rl is None else round(rl,3)}  BRANCH={rb if rb is None else round(rb,3)}')
    if rl is not None: worst_line = min(worst_line, rl)
    if rb is not None: worst_branch = min(worst_branch, rb)
import math
floor = lambda r: math.floor(r*20)/20  # round down to nearest 0.05
print('---')
print('global LINE floor   =', floor(worst_line))
print('global BRANCH floor =', floor(worst_branch))
EOF
```

Record the two printed `global ... floor` values; these are `jacoco.line.min` / `jacoco.branch.min`. Note any module whose `BRANCH` printed `None` (no branches) — that module needs the override in Step 4.

- [ ] **Step 2: Add the gate properties and check execution**

In `pom.xml`, add a `<properties>` block to the `coverage` profile (immediately after `<id>coverage</id>`), using the values from Step 1 (example shows `0.50`/`0.40` — substitute the measured floors):

```xml
      <properties>
        <jacoco.line.min>0.50</jacoco.line.min>
        <jacoco.branch.min>0.40</jacoco.branch.min>
      </properties>
```

Then, in the same profile, add a third execution to the JaCoCo plugin in `pluginManagement` (after the `jacoco-report` execution, inside its `<executions>`):

```xml
                <execution>
                  <id>jacoco-check</id>
                  <phase>verify</phase>
                  <goals>
                    <goal>check</goal>
                  </goals>
                  <configuration>
                    <rules>
                      <rule>
                        <element>BUNDLE</element>
                        <limits>
                          <limit>
                            <counter>LINE</counter>
                            <value>COVEREDRATIO</value>
                            <minimum>${jacoco.line.min}</minimum>
                          </limit>
                          <limit>
                            <counter>BRANCH</counter>
                            <value>COVEREDRATIO</value>
                            <minimum>${jacoco.branch.min}</minimum>
                          </limit>
                        </limits>
                      </rule>
                    </rules>
                  </configuration>
                </execution>
```

- [ ] **Step 3: Keep `dist-tool` ungated**

In `dist-tool/pom.xml`, in the `coverage` profile's JaCoCo plugin `<executions>` (added in Task 2), add a second execution that disables the inherited check:

```xml
              <execution>
                <id>jacoco-check</id>
                <phase>none</phase>
              </execution>
```

- [ ] **Step 4: Add per-module overrides only where required**

For any gated module whose Step-1 `LINE`/`BRANCH` ratio is below the chosen global floor, or whose `BRANCH` was `None` (no branches → set its branch floor to `0.00` so the limit is trivially met), add a `coverage` profile override to that module's pom. Example for a module that needs a lower branch floor:

```xml
  <profiles>
    <profile>
      <id>coverage</id>
      <properties>
        <jacoco.branch.min>0.00</jacoco.branch.min>
      </properties>
    </profile>
  </profiles>
```

If Step 1 showed every module at/above the global floor with branches present, add nothing here.

- [ ] **Step 5: Verify the gate passes at baseline (sandbox disabled)**

Run: `./mvnw -B -ntp -Pcoverage verify`
Expected: `BUILD SUCCESS` (every gated module meets its floor; `dist-tool` and the two skipped modules run no check).

- [ ] **Step 6: Prove the gate actually enforces**

Run with an impossible line floor:

```bash
./mvnw -B -ntp -Pcoverage -Djacoco.line.min=0.99 -pl core verify
```

Expected: `BUILD FAILURE` with a message like `Rule violated for bundle core: lines covered ratio is X, but expected minimum is 0.99`.
Then re-run **without** the override to confirm it passes again:

```bash
./mvnw -B -ntp -Pcoverage -pl core verify
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add pom.xml dist-tool/pom.xml
# add any per-module override poms touched in Step 4, by explicit path
git commit -m "build(coverage): hard gate JaCoCo LINE+BRANCH at baseline floors"
```

---

### Task 4: Document the coverage command

Records the opt-in command where build guidance lives.

**Files:**
- Modify: `CLAUDE.md` — add a coverage lane to the Verification section.
- Modify (only if it has a contributor/build-from-source section): `README.md`.

**Interfaces:**
- Consumes: the `./mvnw -Pcoverage verify` command established in Tasks 1–3.

- [ ] **Step 1: Add the coverage lane to CLAUDE.md**

In `CLAUDE.md`, in the `## Verification` section, add immediately after the "General Java changes" block (the one containing `./mvnw test`):

```markdown
Coverage (local, opt-in JaCoCo gate — LINE + BRANCH, not run in CI):

```bash
./mvnw -Pcoverage verify
```
```

- [ ] **Step 2: Add a README note only if a build/contributor section exists**

Run: `grep -n -iE "contribut|build from source|develop|## Building" README.md`
- If it returns a relevant section, add one line there: ``Run `./mvnw -Pcoverage verify` for the opt-in JaCoCo coverage report and gate (LINE + BRANCH; per-module + aggregate under `target/`).``
- If it returns nothing relevant, make no README change (note this in the handoff).

- [ ] **Step 3: Verify whitespace**

Run: `git diff --check`
Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
# add README.md by explicit path only if changed in Step 2
git commit -m "docs(coverage): document opt-in JaCoCo coverage command"
```

---

## Self-Review

**Spec coverage:**
- `coverage` profile mirroring existing profiles, `-Pcoverage verify` → Task 1. ✓
- `prepare-agent`/`report`/`check`/`report-aggregate` bindings → Tasks 1 (agent+report), 2 (aggregate), 3 (check). ✓
- Aggregate hosted in `dist-tool`, no new module → Task 2. ✓
- Hard gate, LINE + BRANCH, ratcheted to baseline, 11 gated modules → Task 3. ✓
- `test-support`/`testbeds` skipped; `dist-tool` ungated but agent kept → Tasks 1 (skips) + 3 (ungate). ✓
- `argLine` clobber handled in-profile → Task 1 Step 2 + verified Task 1 Step 5. ✓
- Verification (non-zero coverage, gate enforces, default build unchanged, enforcer green) → Task 1 Steps 4–6, Task 3 Steps 5–6. ✓
- Docs (CLAUDE.md; README if applicable; agent-usage.md untouched) → Task 4. ✓
- No version bump → Global Constraints. ✓

**Placeholder scan:** Threshold numbers (`0.50`/`0.40`) are illustrative and explicitly replaced by the measured floors from Task 3 Step 1's computation — a concrete, reproducible procedure, not a TBD. No other placeholders.

**Type consistency:** Execution ids are stable across tasks — `jacoco-prepare-agent`, `jacoco-report` (Task 1), `jacoco-aggregate` (Task 2), `jacoco-check` (Task 3 root add + `dist-tool` phase-`none` override matches the same id). Property names `jacoco.line.min` / `jacoco.branch.min` and `jacoco.skip` are used consistently. Profile id `coverage` is identical everywhere.
