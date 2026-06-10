# Tier 1 — Launcher JVM Flags Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut per-process cold-start cost by giving every dediren launcher startup-friendly JVM flags (`-XX:TieredStopAtLevel=1` C1-only JIT, `-XX:+UseSerialGC`) injected from a single root-pom property, without changing any plugin behavior or the process-boundary contract.

**Architecture:** Define one root-pom property `dediren.launcher.jvmArgs` and reference it from every module's appassembler `extraJvmArguments` (currently only `cli` sets that element). A regression guard in the dist smoke path asserts the flags are baked into every bundled launcher script, so the full ELK→render→export pipeline is proven to still pass under C1-only + SerialGC.

**Tech Stack:** Java 21, Maven Wrapper, appassembler-maven-plugin 2.1.0, JUnit 5.

**Why these flags:** This is a run-once workload — each plugin JVM exits before the C2 JIT's profiling pays off, so `-XX:TieredStopAtLevel=1` (C1 only) removes wasted compilation; `-XX:+UseSerialGC` avoids G1's thread/region setup for a small short-lived heap. **Deliberately excluded:** heap caps (`-Xmx`), which risk OOM on large ELK graphs without Tier 0 evidence — see the "Optional follow-up" note at the end.

**Depends on:** Tier 0 (you need the baseline to prove the win and to catch any layout regression under C1-only).

---

### Task 1: Regression guard — assert launchers carry the flags (red)

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`

This adds the assertion *before* the flags exist, so the dist-smoke run fails — our red state.

- [ ] **Step 1: Add an expected-flags constant**

In `DistTool`, near the existing `BUNDLE_METADATA_TARGET` constant (~line 24), add:

```java
    private static final List<String> EXPECTED_LAUNCHER_FLAGS =
        List.of("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC");
```

- [ ] **Step 2: Add the assertion method**

Add to `DistTool` (next to the other `assert*` methods, ~line 408):

```java
    private static void assertLauncherJvmFlags(Path bundle) throws IOException {
        for (Launcher launcher : LAUNCHERS) {
            Path script = bundle.resolve("bin").resolve(launcher.bundleScript());
            String text = Files.readString(script, StandardCharsets.UTF_8);
            for (String flag : EXPECTED_LAUNCHER_FLAGS) {
                if (!text.contains(flag)) {
                    throw new IllegalStateException(
                        "launcher " + launcher.bundleScript() + " is missing JVM flag " + flag);
                }
            }
        }
    }
```

- [ ] **Step 3: Call it from `smoke`**

In `DistTool.smoke`, immediately after `Path bundle = findBundleDir(temp);` (~line 155), add:

```java
            assertLauncherJvmFlags(bundle);
```

- [ ] **Step 4: Run dist-smoke to verify it fails**

Run (sandbox disabled per project memory):

```bash
./mvnw -pl dist-tool -am verify -Pdist-smoke
```

Expected: FAIL — `launcher dediren is missing JVM flag -XX:TieredStopAtLevel=1`.

- [ ] **Step 5: Commit the guard**

```bash
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java
git commit -m "test: assert bundled launchers carry startup JVM flags"
```

---

### Task 2: Inject the flags from a single root-pom property (green)

**Files:**
- Modify: `pom.xml` (root — add property)
- Modify: `cli/pom.xml:86`
- Modify: `plugins/generic-graph/pom.xml`
- Modify: `plugins/elk-layout/pom.xml`
- Modify: `plugins/svg-render/pom.xml`
- Modify: `plugins/archimate-oef-export/pom.xml`
- Modify: `plugins/uml-xmi-export/pom.xml`

- [ ] **Step 1: Add the property to the root pom**

In `pom.xml` `<properties>` (after `<argLine>` ~line 32), add:

```xml
    <dediren.launcher.jvmArgs>-XX:TieredStopAtLevel=1 -XX:+UseSerialGC</dediren.launcher.jvmArgs>
```

- [ ] **Step 2: Reference it in the CLI launcher (preserving the version arg)**

In `cli/pom.xml`, replace line 86:

```xml
              <extraJvmArguments>-Ddediren.version=${project.version}</extraJvmArguments>
```

with:

```xml
              <extraJvmArguments>${dediren.launcher.jvmArgs} -Ddediren.version=${project.version}</extraJvmArguments>
```

- [ ] **Step 3: Add `extraJvmArguments` to each plugin launcher**

For **each** of the five plugin poms, locate the appassembler `<configuration>` block that contains `<programs>` and add a sibling `<extraJvmArguments>` line as the first child of `<configuration>`. Example for `plugins/elk-layout/pom.xml` — change:

```xml
            <configuration>
              <programs>
                <program>
                  <mainClass>dev.dediren.plugins.elklayout.Main</mainClass>
                  <id>elk-layout</id>
                </program>
              </programs>
            </configuration>
```

to:

```xml
            <configuration>
              <extraJvmArguments>${dediren.launcher.jvmArgs}</extraJvmArguments>
              <programs>
                <program>
                  <mainClass>dev.dediren.plugins.elklayout.Main</mainClass>
                  <id>elk-layout</id>
                </program>
              </programs>
            </configuration>
```

Apply the identical `<extraJvmArguments>${dediren.launcher.jvmArgs}</extraJvmArguments>` insertion to:
- `plugins/generic-graph/pom.xml`
- `plugins/svg-render/pom.xml`
- `plugins/archimate-oef-export/pom.xml`
- `plugins/uml-xmi-export/pom.xml`

(Each already has its own appassembler execution with a `<configuration><programs>` block — only the `<extraJvmArguments>` line is new; leave `<mainClass>`/`<id>` untouched.)

- [ ] **Step 4: Run dist-smoke to verify it passes**

Run:

```bash
./mvnw -pl dist-tool -am verify -Pdist-smoke
```

Expected: PASS — `distribution smoke test passed`. This proves (a) all six launchers now carry the flags and (b) the full ELK layout → SVG render → OEF/XMI export pipeline still works under C1-only + SerialGC.

- [ ] **Step 5: Confirm the flags landed in a generated script**

```bash
grep -o "\-XX:[^ ]*" cli/target/appassembler/bin/cli | sort -u
```

Expected: shows `-XX:+UseSerialGC` and `-XX:TieredStopAtLevel=1`.

- [ ] **Step 6: Commit**

```bash
git add pom.xml cli/pom.xml plugins/*/pom.xml
git commit -m "perf: add C1-only + SerialGC startup flags to all launchers"
```

---

### Task 3: Prove the win against the Tier 0 baseline

**Files:**
- Create: `docs/superpowers/plans/2026-06-10-jvm-tier1-results.md`

- [ ] **Step 1: Re-run the bench harness (requires Tier 0)**

```bash
./mvnw -pl dist-tool -am verify -Pdist-bench 2>/dev/null | tail -40
```

- [ ] **Step 2: Record before/after**

Create `docs/superpowers/plans/2026-06-10-jvm-tier1-results.md` containing the Tier 0 baseline JSON and the Tier 1 JSON side by side, with the per-command `median_ms` deltas and a one-line verdict.

**Regression watch:** If `elk-layout layout` median got *worse* (C1-only can hurt CPU-heavy layout of large graphs because C2 never compiles the hot loops), do NOT ship C1-only for that plugin. Instead, in `pom.xml` add a second property `<dediren.layout.jvmArgs>-XX:+UseSerialGC</dediren.layout.jvmArgs>` and reference *that* one only in `plugins/elk-layout/pom.xml`, leaving tiered compilation on for layout. Re-run Steps 1–2 to confirm.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-06-10-jvm-tier1-results.md
git commit -m "docs: record Tier 1 launcher-flag startup results"
```

---

## Optional follow-up (not in scope, gated on evidence)

If Tier 0/Tier 1 numbers show class loading still dominates (it will — flags don't touch it), proceed to **Tier 2 (AppCDS)**. Heap caps (`-Xmx256m -Xms32m`) can shave footprint and ergonomic probing, but only add them after measuring peak heap of the rich ELK fixture (`-Xlog:gc` during a layout) to confirm headroom — record that evidence before changing `dediren.launcher.jvmArgs`.

## Self-review checklist

- **Spec coverage:** Single-source flag property (Task 2), regression guard (Task 1), measured proof (Task 3). Covered.
- **No placeholders:** Concrete pom edits with exact before/after; the elk-layout example is shown in full and the other four are explicitly enumerated.
- **Type consistency:** `EXPECTED_LAUNCHER_FLAGS` strings match the `dediren.launcher.jvmArgs` property value exactly (`-XX:TieredStopAtLevel=1`, `-XX:+UseSerialGC`).
- **Verification:** `./mvnw -pl dist-tool -am verify -Pdist-smoke` is the gate (sandbox disabled). It exercises every launcher and the full pipeline.
