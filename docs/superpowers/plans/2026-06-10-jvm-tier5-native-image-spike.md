# Tier 5 — GraalVM Native Image Research Spike Plan

> **For agentic workers:** This is a **research spike**, not a bite-sized TDD feature plan. The unknowns (GraalVM reachability metadata for Jackson, and especially EMF/Xtext in elk-layout) cannot be expressed as red-green steps in advance — they are what the spike discovers. Steps below are concrete *investigation* actions with explicit **acceptance criteria** and **go/no-go decision gates**. Do not generalize beyond the gates. Use superpowers:systematic-debugging when native-image reachability errors appear.

**Goal:** Determine empirically whether dediren's plugins can become GraalVM native executables (target: 50–200 ms startup vs today's 2–4 s), starting with the easiest pure-Java plugin and treating elk-layout (EMF/Xtext reflection) as a separate, gated feasibility question.

**Why native fits dediren's contract perfectly:** A native plugin is still "an executable speaking JSON over stdio" — the process-boundary contract (CLAUDE.md) is preserved *exactly*, with no JVM cold start at all. The risk is entirely build-side: reflection/resource reachability config.

**Why pure-Java plugins first:** `generic-graph` depends only on `archimate`, `contracts`, `uml` + Jackson — no ELK/EMF/Xtext. It is the lowest-risk proof of the toolchain. `elk-layout` is the highest-value but highest-risk target and is gated behind the generic-graph result.

**Tech Stack:** GraalVM (Oracle GraalVM or GraalVM CE for JDK 21 or 25), `native-image`, `org.graalvm.buildtools:native-maven-plugin`, the native-image tracing agent, JUnit 5. Orthogonal to the Java-25 baseline — can run on GraalVM for JDK 21.

**Depends on:** Tier 0 (startup baseline to compare against). Independent of all other tiers.

---

### Task 0: Spike scope, toolchain, and success criteria

- [ ] **Step 1: Install and verify the GraalVM toolchain**

Install a GraalVM distribution with `native-image`. Verify:

```bash
java -version            # should report GraalVM
native-image --version
```

Acceptance: both commands succeed. If `native-image` is missing, install it (`gu install native-image` on CE, or it's bundled in recent Oracle GraalVM).

- [ ] **Step 2: Pin the build-tools plugin version**

Look up the current stable `org.graalvm.buildtools:native-maven-plugin` version (Maven Central / context7 — do **not** guess a version string). Record it; it will be added as a root-pom property `native-maven-plugin.version` in Task 1, matching how the repo pins all plugin versions.

- [ ] **Step 3: Write down the spike's success criteria**

The spike succeeds for a plugin if the native executable:
1. Passes `capabilities` with output byte-identical (modulo whitespace) to the JVM build.
2. Produces a valid command envelope for its real capability over an existing fixture, matching the JVM build's output.
3. Starts measurably faster (target: <300 ms wall-clock for `capabilities` vs the Tier 0 JVM number).

Record these in the findings doc created in Task 2.

---

### Task 1: Spike `generic-graph` as a native executable

**Files (spike-local — keep on a throwaway branch):**
- Modify: `pom.xml` (add `native-maven-plugin.version` property)
- Modify: `plugins/generic-graph/pom.xml` (add a `native` profile)
- Create: `plugins/generic-graph/src/main/resources/META-INF/native-image/` (generated reachability metadata)

- [ ] **Step 1: Create an isolated spike branch**

```bash
git status --short --branch
git switch -c spike/native-generic-graph
```

- [ ] **Step 2: Generate reachability metadata with the tracing agent**

Run the existing `generic-graph` tests (and/or the Main over a fixture) under the native-image agent to capture reflection/resource/proxy usage (Jackson is the main reflective consumer). One reliable approach — run the packaged plugin over a real fixture with the agent:

```bash
./mvnw -q -pl plugins/generic-graph -am package
GG=plugins/generic-graph
CP="$GG/target/appassembler/lib/*"
mkdir -p $GG/src/main/resources/META-INF/native-image
java -agentlib:native-image-agent=config-output-dir=$GG/src/main/resources/META-INF/native-image \
  -cp "$CP" dev.dediren.plugins.genericgraph.Main \
  project --target layout-request --view main \
  --input fixtures/source/valid-pipeline-rich.json
```

Run the agent a second time with `config-merge-dir` for the `capabilities` command and any other code paths (semantic-validation, render-metadata) so the metadata covers all branches:

```bash
java -agentlib:native-image-agent=config-merge-dir=$GG/src/main/resources/META-INF/native-image \
  -cp "$CP" dev.dediren.plugins.genericgraph.Main capabilities
```

Acceptance: `reflect-config.json`/`resource-config.json` etc. are written under `META-INF/native-image`.

- [ ] **Step 3: Add a `native` build profile to `plugins/generic-graph/pom.xml`**

Add the property `<native-maven-plugin.version>VERSION</native-maven-plugin.version>` to the root `pom.xml` (using the version from Task 0 Step 2), then add this profile to `plugins/generic-graph/pom.xml`:

```xml
    <profile>
      <id>native</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.graalvm.buildtools</groupId>
            <artifactId>native-maven-plugin</artifactId>
            <version>${native-maven-plugin.version}</version>
            <extensions>true</extensions>
            <executions>
              <execution>
                <id>build-native</id>
                <phase>package</phase>
                <goals><goal>compile-no-fork</goal></goals>
              </execution>
            </executions>
            <configuration>
              <imageName>dediren-plugin-generic-graph</imageName>
              <mainClass>dev.dediren.plugins.genericgraph.Main</mainClass>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </profile>
```

- [ ] **Step 4: Build the native image**

```bash
./mvnw -pl plugins/generic-graph -am package -Pnative
```

Acceptance: a native binary `plugins/generic-graph/target/dediren-plugin-generic-graph` is produced. If the build fails on a reachability error, use `systematic-debugging`: read the missing-registration message, re-run the agent over the offending path (Step 2), and rebuild. Record each gap found.

- [ ] **Step 5: Validate the native binary against the JSON contract**

Compare native vs JVM output for the same inputs:

```bash
NB=plugins/generic-graph/target/dediren-plugin-generic-graph
"$NB" capabilities | python3 -m json.tool > /tmp/native-caps.json
"$NB" project --target layout-request --view main \
  --input fixtures/source/valid-pipeline-rich.json | python3 -m json.tool > /tmp/native-proj.json
# Compare to the JVM launcher output produced the same way; they must match (modulo formatting).
```

Acceptance: native `capabilities` reports the correct `id` + capabilities, and `project` yields a valid `layout-request` envelope matching the JVM build.

- [ ] **Step 6: Measure startup**

```bash
echo "native:"; time "$NB" capabilities > /dev/null
echo "jvm:";    time plugins/generic-graph/target/appassembler/bin/generic-graph capabilities > /dev/null
```

Record both numbers.

- [ ] **Step 7: Commit the spike artifacts on the spike branch**

```bash
git add pom.xml plugins/generic-graph/pom.xml plugins/generic-graph/src/main/resources/META-INF/native-image
git commit -m "spike: native-image build for generic-graph plugin"
```

---

### Task 2: Findings + go/no-go for pure-Java plugins

**Files:**
- Create: `docs/superpowers/plans/2026-06-10-jvm-tier5-native-findings.md`

- [ ] **Step 1: Write the findings doc**

Capture, for generic-graph: startup delta (native vs JVM from Task 1 Step 6), binary size (`ls -lh`), native build wall-clock time, the list of reachability-metadata gaps found and how each was resolved, and any output discrepancies.

- [ ] **Step 2: Decision gate — generalize to the other pure-Java plugins?**

If generic-graph meets all three success criteria (Task 0 Step 3), the toolchain is proven. Decide whether to extend the same pattern to the other reflection-light plugins (`archimate-oef-export`, `uml-xmi-export`, `svg-render` — note these touch XML/XSD, which may need extra resource config). List the candidate order and the expected metadata risk for each. If generic-graph fails a criterion, record why and stop — native is not yet viable for dediren.

- [ ] **Step 3: Commit the findings**

```bash
git add docs/superpowers/plans/2026-06-10-jvm-tier5-native-findings.md
git commit -m "docs: record Tier 5 native-image spike findings for generic-graph"
```

---

### Task 3: (Gated) elk-layout feasibility spike

**Only start this if Task 2's gate said "generalize" AND there is appetite for the ELK risk.**

- [ ] **Step 1: Attempt agent-based metadata over ELK layout paths**

On a fresh branch, repeat Task 1's agent capture but drive a real `layout` over the ELK fixtures so EMF model registration, Xtext, and Guava reflective paths are exercised. Expect far more reflection than generic-graph. Also check the GraalVM reachability-metadata repository for existing Eclipse ELK / EMF / Guava metadata to reuse instead of hand-capturing.

- [ ] **Step 2: Attempt the native build and record where it breaks**

```bash
./mvnw -pl plugins/elk-layout -am package -Pnative
```

EMF's dynamic `EPackage` registration and Xtext's reflective wiring are the usual blockers. Record each failure class and whether agent metadata or manual `reflect-config` entries resolve it. **Time-box this** (e.g. 2 days) — the goal is a feasibility verdict, not a finished binary.

- [ ] **Step 3: Decision gate — elk-layout native go/no-go**

Append to the findings doc: did a working elk-layout native binary emerge? At what metadata-maintenance cost? Estimate ongoing burden (ELK upgrades may break metadata). Recommend one of: (a) ship native elk-layout, (b) native for pure-Java plugins only + keep elk-layout on the JVM (with Tier 2/4 CDS/AOT), or (c) defer native entirely.

---

### Task 4: (Gated) Integration design — how native and JVM plugins coexist

**Only after Task 2/3 gates approve some native plugins.** This is a design task; write it up, don't implement broadly here.

- [ ] **Step 1: Decide the distribution model**

Native binaries are per-OS/arch, breaking today's platform-neutral `target: "java"` bundle. Decide: separate per-platform bundles, an optional native overlay alongside the JVM bundle, or native-only for the plugins that qualify. Note the `DistTool.LAUNCHERS` and `bundleMetadataTarget()` changes each option implies, and that `ensureJavaRuntime`/the smoke pipeline would need a native-aware path.

- [ ] **Step 2: Write the integration plan**

Produce a follow-on bite-sized implementation plan (`docs/superpowers/plans/<date>-native-distribution.md`) covering the chosen model, the `dist-tool` changes, and CI native-build jobs. That plan — not this spike — is what gets executed to ship native to users.

---

## Self-review checklist

- **Honest framing:** Explicitly a spike; investigation steps have acceptance criteria and decision gates rather than fabricated red-green TDD for unknown reachability config. Stated up front.
- **Concrete where it can be:** Exact GraalVM/agent/Maven commands, the real Main class (`dev.dediren.plugins.genericgraph.Main`), real fixtures, and the real dependency profile (generic-graph = no ELK/EMF/Xtext) are all verified, not guessed.
- **No fabricated versions:** Task 0 Step 2 requires looking up the current `native-maven-plugin` version instead of inventing one.
- **Gated risk:** elk-layout (EMF/Xtext) and broad integration are behind explicit go/no-go gates, time-boxed, and never assumed to succeed.
- **Contract-aware:** Notes that native preserves the stdio/JSON process-boundary contract but breaks platform-neutral distribution — surfaced as the Task 4 design question.
