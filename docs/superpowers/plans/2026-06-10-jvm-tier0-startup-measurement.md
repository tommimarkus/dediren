# Tier 0 — JVM Startup Measurement Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `dist-tool bench` subcommand that measures wall-clock cold-start time for the bundled CLI and each plugin, producing a JSON report that becomes the baseline every other tier proves wins against.

**Architecture:** Reuse `DistTool`'s existing archive-extraction + `runBundleCommand` machinery. Add a pure, unit-tested timing/summary layer (`Bench.java`), then a thin `bench` subcommand that extracts a built bundle, runs representative commands N times each, and prints per-command min/median/max milliseconds. A new `bench` Maven profile builds the bundle then runs `bench`, mirroring `dist-smoke`.

**Tech Stack:** Java 21, Maven Wrapper, JUnit 5, AssertJ, Jackson (via `dev.dediren.contracts.json.JsonSupport`), `exec-maven-plugin`.

**Note on TDD here:** The pure summary/report logic is unit-tested (Tasks 1–2). The subcommand wiring (Task 3) and baseline capture (Task 4) are integration glue verified by running the harness against a real bundle — there is no meaningful unit test for "spawn a JVM and time it," so those tasks verify by execution, not assertion.

---

### Task 1: Pure timing-summary type

**Files:**
- Create: `dist-tool/src/main/java/dev/dediren/tools/dist/Bench.java`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/BenchTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BenchTest {
    @Test
    void summarizeComputesMinMedianMaxOverRuns() {
        Bench.Stat stat = Bench.summarize("layout", List.of(50L, 10L, 30L, 40L, 20L));
        assertThat(stat.command()).isEqualTo("layout");
        assertThat(stat.runs()).isEqualTo(5);
        assertThat(stat.minMs()).isEqualTo(10L);
        assertThat(stat.medianMs()).isEqualTo(30L);
        assertThat(stat.maxMs()).isEqualTo(50L);
    }

    @Test
    void summarizeMedianOfEvenCountTakesLowerMiddle() {
        Bench.Stat stat = Bench.summarize("capabilities", List.of(10L, 20L, 30L, 40L));
        assertThat(stat.medianMs()).isEqualTo(20L);
    }

    @Test
    void summarizeRejectsEmptySamples() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> Bench.summarize("x", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl dist-tool test -Dtest=BenchTest`
Expected: FAIL — `Bench` does not exist / cannot find symbol.

- [ ] **Step 3: Write minimal implementation**

```java
package dev.dediren.tools.dist;

import java.util.ArrayList;
import java.util.List;

final class Bench {
    private Bench() {
    }

    record Stat(String command, int runs, long minMs, long medianMs, long maxMs) {
    }

    static Stat summarize(String command, List<Long> millis) {
        if (millis.isEmpty()) {
            throw new IllegalArgumentException("no samples for " + command);
        }
        List<Long> sorted = new ArrayList<>(millis);
        sorted.sort(Long::compareTo);
        long min = sorted.get(0);
        long max = sorted.get(sorted.size() - 1);
        long median = sorted.get((sorted.size() - 1) / 2);
        return new Stat(command, millis.size(), min, median, max);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl dist-tool test -Dtest=BenchTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add dist-tool/src/main/java/dev/dediren/tools/dist/Bench.java \
        dist-tool/src/test/java/dev/dediren/tools/dist/BenchTest.java
git commit -m "test: add cold-start timing summary for dist bench harness"
```

---

### Task 2: JSON report rendering

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/Bench.java`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/BenchTest.java`

- [ ] **Step 1: Add the failing test**

Append to `BenchTest`:

```java
    @Test
    void renderReportEmitsStableJson() throws Exception {
        String json = Bench.renderReport(java.util.List.of(
            new Bench.Stat("cli --version", 3, 250L, 270L, 320L),
            new Bench.Stat("elk-layout layout", 3, 1400L, 1500L, 1700L)));
        com.fasterxml.jackson.databind.JsonNode node =
            dev.dediren.contracts.json.JsonSupport.objectMapper().readTree(json);
        assertThat(node.path("schema").asText()).isEqualTo("dediren-bench.v1");
        assertThat(node.path("results")).hasSize(2);
        assertThat(node.path("results").get(1).path("command").asText())
            .isEqualTo("elk-layout layout");
        assertThat(node.path("results").get(1).path("median_ms").asLong()).isEqualTo(1500L);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl dist-tool test -Dtest=BenchTest#renderReportEmitsStableJson`
Expected: FAIL — `renderReport` not defined.

- [ ] **Step 3: Implement `renderReport`**

Add imports and method to `Bench.java`:

```java
import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import java.util.LinkedHashMap;
import java.util.Map;
```

```java
    static String renderReport(List<Stat> stats) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Stat stat : stats) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("command", stat.command());
            row.put("runs", stat.runs());
            row.put("min_ms", stat.minMs());
            row.put("median_ms", stat.medianMs());
            row.put("max_ms", stat.maxMs());
            results.add(row);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "dediren-bench.v1");
        report.put("results", results);
        return JsonSupport.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl dist-tool test -Dtest=BenchTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add dist-tool/src/main/java/dev/dediren/tools/dist/Bench.java \
        dist-tool/src/test/java/dev/dediren/tools/dist/BenchTest.java
git commit -m "feat: render cold-start bench results as dediren-bench.v1 JSON"
```

---

### Task 3: Wire the `bench` subcommand into DistTool

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
- Modify: `dist-tool/pom.xml` (add `bench` profile)

- [ ] **Step 1: Add the `bench` case to `DistTool.run`**

In `DistTool.run`, add a new `case` alongside `build`/`smoke`/`notices` (the `switch (args[0])` around line 62):

```java
            case "bench" -> {
                String version = required(options, "version");
                int runs = options.containsKey("runs") ? Integer.parseInt(options.get("runs")) : 5;
                Path archive = options.containsKey("archive")
                    ? Path.of(options.get("archive"))
                    : root.resolve("dist").resolve(bundleName(version) + ".tar.gz");
                bench(root, archive.toAbsolutePath().normalize(), runs);
                yield 0;
            }
```

- [ ] **Step 2: Add the `bench` method**

Add this method to `DistTool` (place it directly after the `smoke` method, ~line 222). It reuses the same extraction shape as `smoke` and times representative commands. `runBundleCommand` already throws on non-zero exit, so a failed command aborts the bench:

```java
    private static void bench(Path root, Path archive, int runs) throws Exception {
        if (!Files.isRegularFile(archive)) {
            throw new IllegalStateException("archive not found: " + archive);
        }
        ensureJavaRuntime();
        Path temp = Files.createTempDirectory("dediren-dist-bench-");
        try {
            runCommand(root, List.of("tar", "-xzf", archive.toString(), "-C", temp.toString()), null);
            Path bundle = findBundleDir(temp);
            Path dediren = bundle.resolve("bin/dediren");

            // Prepare a layout request once so the layout bench has real input.
            String projectOutput = runBundleCommand(dediren, bundle, List.of(
                "project", "--target", "layout-request", "--plugin", "generic-graph", "--view", "main",
                "--input", bundle.resolve("fixtures/source/valid-pipeline-rich.json").toString()), null);
            Path request = temp.resolve("request.json");
            Files.writeString(request, projectOutput, StandardCharsets.UTF_8);

            List<Bench.Stat> stats = new ArrayList<>();
            stats.add(timeCommand("cli --version", runs,
                () -> runBundleCommand(dediren, bundle, List.of("--version"), null)));
            stats.add(timeCommand("elk-layout capabilities", runs,
                () -> runBundleCommand(bundle.resolve("bin/dediren-plugin-elk-layout"), bundle,
                    List.of("capabilities"), null)));
            stats.add(timeCommand("elk-layout layout (probe+work)", runs,
                () -> runBundleCommand(dediren, bundle,
                    List.of("layout", "--plugin", "elk-layout", "--input", request.toString()), null)));
            stats.add(timeCommand("generic-graph capabilities", runs,
                () -> runBundleCommand(bundle.resolve("bin/dediren-plugin-generic-graph"), bundle,
                    List.of("capabilities"), null)));

            System.out.println(Bench.renderReport(stats));
        } finally {
            deleteIfExists(temp);
        }
    }

    private static Bench.Stat timeCommand(String label, int runs, BenchInvocation invocation) throws Exception {
        List<Long> millis = new ArrayList<>();
        for (int index = 0; index < runs; index++) {
            long start = System.nanoTime();
            invocation.run();
            millis.add((System.nanoTime() - start) / 1_000_000L);
        }
        return Bench.summarize(label, millis);
    }

    @FunctionalInterface
    private interface BenchInvocation {
        void run() throws Exception;
    }
```

`runBundleCommand` returns the stdout `String`; the lambdas above ignore it (we only need timing), so wrap with a statement lambda as shown — adjust to `() -> { runBundleCommand(...); }` form if your IDE flags the return value. Concretely, change each lambda to a block:

```java
            stats.add(timeCommand("cli --version", runs, () -> {
                runBundleCommand(dediren, bundle, List.of("--version"), null);
            }));
```

Apply the block form to all four `timeCommand` calls.

- [ ] **Step 3: Add the `bench` Maven profile**

In `dist-tool/pom.xml`, add a new `<profile>` inside `<profiles>` (after the `dist-smoke` profile, before `</profiles>`). It mirrors `dist-smoke` but replaces the `smoke` execution with `bench`:

```xml
    <profile>
      <id>dist-bench</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>exec-maven-plugin</artifactId>
            <executions>
              <execution>
                <id>third-party-notices</id>
                <phase>verify</phase>
                <goals><goal>java</goal></goals>
                <configuration>
                  <arguments>
                    <argument>notices</argument>
                    <argument>--root</argument>
                    <argument>${maven.multiModuleProjectDirectory}</argument>
                    <argument>--output</argument>
                    <argument>${project.build.directory}/reports/third-party/THIRD-PARTY-NOTICES.md</argument>
                  </arguments>
                </configuration>
              </execution>
              <execution>
                <id>dist-build</id>
                <phase>verify</phase>
                <goals><goal>java</goal></goals>
                <configuration>
                  <arguments>
                    <argument>build</argument>
                    <argument>--root</argument>
                    <argument>${maven.multiModuleProjectDirectory}</argument>
                    <argument>--version</argument>
                    <argument>${project.version}</argument>
                    <argument>--notices</argument>
                    <argument>${project.build.directory}/reports/third-party/THIRD-PARTY-NOTICES.md</argument>
                  </arguments>
                </configuration>
              </execution>
              <execution>
                <id>dist-bench</id>
                <phase>verify</phase>
                <goals><goal>java</goal></goals>
                <configuration>
                  <arguments>
                    <argument>bench</argument>
                    <argument>--root</argument>
                    <argument>${maven.multiModuleProjectDirectory}</argument>
                    <argument>--version</argument>
                    <argument>${project.version}</argument>
                    <argument>--runs</argument>
                    <argument>5</argument>
                  </arguments>
                </configuration>
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </profile>
```

- [ ] **Step 4: Build and run the harness against a real bundle**

Run (sandbox disabled — Maven + `@TempDir`/process spawn need a writable `/tmp`; see project memory):

```bash
./mvnw -pl dist-tool -am verify -Pdist-bench
```

Expected: build succeeds, dist bundle is produced, and a `dediren-bench.v1` JSON report prints to stdout with four `results` entries and numeric `median_ms` values.

- [ ] **Step 5: Commit**

```bash
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java dist-tool/pom.xml
git commit -m "feat: add dist-tool bench subcommand and dist-bench profile"
```

---

### Task 4: Capture and record the baseline

**Files:**
- Create: `docs/superpowers/plans/2026-06-10-jvm-tier0-baseline-results.md`

- [ ] **Step 1: Run the harness with more runs for a stable baseline**

```bash
./mvnw -pl dist-tool -am verify -Pdist-bench 2>/dev/null | tail -40
```

Re-run if the machine was under load. Record the printed JSON.

- [ ] **Step 2: Write the baseline document**

Create `docs/superpowers/plans/2026-06-10-jvm-tier0-baseline-results.md` with:
- The machine/JDK identity: paste `java -version` output and `uname -a`.
- The full `dediren-bench.v1` JSON from Step 1.
- A one-paragraph reading: which command is slowest, and the delta between
  `elk-layout capabilities` (one cold start) and `elk-layout layout (probe+work)`
  (two cold starts) — that delta is the probe overhead Tier 3 targets.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-06-10-jvm-tier0-baseline-results.md
git commit -m "docs: record Tier 0 JVM cold-start baseline measurements"
```

---

## Self-review checklist

- **Spec coverage:** Measurement harness (Tasks 1–3) + recorded baseline (Task 4). Covered.
- **No placeholders:** All code blocks are concrete; the only deferred content is the machine-specific numbers in Task 4, which are measured outputs, not fabricatable.
- **Type consistency:** `Bench.Stat(command, runs, minMs, medianMs, maxMs)` and `Bench.summarize`/`Bench.renderReport` signatures match across Tasks 1–3. Report schema id `dediren-bench.v1` is used consistently.
- **Verification:** `./mvnw -pl dist-tool test` (unit) and `./mvnw -pl dist-tool -am verify -Pdist-bench` (integration). Run with sandbox disabled per project memory (`maven-tests-need-sandbox-disabled`).
