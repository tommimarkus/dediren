# Tier 4 — Java 25 Leyden AOT Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan begins with a human decision gate (Task 0) — do not proceed past it without explicit approval.**

**Goal:** Replace/upgrade Tier 2's CDS with the Java 25 **Project Leyden AOT cache** (JEP 483 class loading+linking + JEP 515 ahead-of-time method profiling), cutting both startup *and* warmup so the ELK layout JIT starts compiling hot methods immediately instead of cold.

**Architecture:** Raise the product baseline Java 21 → **Java 25**, then give each launcher a runtime-auto-created AOT cache using JEP 514's one-step `-XX:AOTCacheOutput` ergonomics: on first run the launcher records + assembles `<name>.aot` (and runs normally); subsequent runs load it with `-XX:AOTCache`. Same writable-cache-dir + graceful-fallback design as Tier 2, so the bundle stays JDK-matched and platform-neutral. The launcher rewrite `withAotCache` **replaces** Tier 2's `withCdsArchive` (AOT cache is the superset; do not stack both).

**Tech Stack:** **Java 25** (Temurin), Maven Wrapper, appassembler, JUnit 5, AssertJ, POSIX sh.

**Relationship to Tier 2:** Supersedes it. If Tier 2 already shipped, this plan swaps the CDS rewrite for the AOT rewrite. If Tier 2 did not ship, this plan adds the AOT rewrite directly. Pick one — never both.

**Depends on:** Tier 0 (baseline). Java-baseline decision (Task 0).

---

### Task 0: Decision gate — adopt Java 25 baseline

**This is a human go/no-go, not a code task.** Do not edit anything until approved.

- [ ] **Step 1: Confirm the compatibility decision**

Raising the baseline from Java 21 (LTS) to Java 25 (LTS) means:
- Anyone building or running dediren from source needs a Java 25 JDK.
- The shipped bundle requires a Java 25+ runtime on the agent host.
- Per CLAUDE.md Versioning, this backwards-incompatible runtime requirement is communicated **in release notes**, not via the CalVer number, and there is **no schema-id change** (the JSON contract is unaffected).

Confirm with the maintainer: *is dediren ready to require Java 25?* If **no**, stop here and ship Tier 2 (AppCDS on Java 21) instead. If **yes**, continue.

- [ ] **Step 2: Record the decision**

Add a dated line to the release notes / changelog source: "Runtime baseline raised to Java 25 to enable the Leyden AOT cache." Commit only this note now if you want a paper trail, or fold it into Task 4's docs commit.

---

### Task 1: Raise the Java baseline to 25

**Files:**
- Modify: `pom.xml:31` (`maven.compiler.release`)
- Modify: `.github/workflows/ci.yml:32` and `:64` (`java-version`)
- Modify: `.github/workflows/release.yml:30` and `:77` (`java-version`)
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java:509-511` (`ensureJavaRuntime`)
- Modify: `README.md`, `docs/agent-usage.md`, `CLAUDE.md` (ELK Runtime note)

- [ ] **Step 1: Bump the compiler release**

In `pom.xml`, change line 31:

```xml
    <maven.compiler.release>21</maven.compiler.release>
```

to:

```xml
    <maven.compiler.release>25</maven.compiler.release>
```

- [ ] **Step 2: Bump CI and release JDKs**

In `.github/workflows/ci.yml`, change both `java-version: "21"` occurrences (lines 32 and 64) to `java-version: "25"`. In `.github/workflows/release.yml`, change both `java-version: "21"` occurrences (lines 30 and 77) to `java-version: "25"`. Leave the pinned `actions/setup-java` SHA untouched.

- [ ] **Step 3: Raise the runtime floor check**

In `DistTool.ensureJavaRuntime` (~line 509), change:

```java
        if (major < 21) {
            throw new IllegalStateException("Java 21 or newer is required for distribution smoke tests");
        }
```

to:

```java
        if (major < 25) {
            throw new IllegalStateException("Java 25 or newer is required for distribution smoke tests");
        }
```

- [ ] **Step 4: Update the runtime-requirement docs**

- `CLAUDE.md` "ELK Runtime" section: change "Java 21 or newer is required." to "Java 25 or newer is required."
- `README.md` and `docs/agent-usage.md`: update every "Java 21" runtime-requirement mention to "Java 25". Search first:

```bash
grep -rn "Java 21\|java-version\|major < 21\|release>21" README.md docs/agent-usage.md CLAUDE.md
```

- [ ] **Step 5: Build the whole project on a Java 25 JDK**

Confirm the active JDK is 25 (`java -version`), then (sandbox disabled):

```bash
./mvnw test
```

Expected: PASS on Java 25. (If any module uses a removed/deprecated API, fix per `systematic-debugging`; the codebase targets standard Java 21 APIs, so this is expected to be clean.)

- [ ] **Step 6: Commit**

```bash
git add pom.xml .github/workflows/ci.yml .github/workflows/release.yml \
        dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java \
        README.md docs/agent-usage.md CLAUDE.md
git commit -m "build: raise runtime baseline to Java 25 for Leyden AOT cache"
```

---

### Task 2: AOT-cache launcher rewrite

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

- [ ] **Step 1: Write the failing test**

Add to `DistModuleTest`:

```java
    @Test
    void withAotCacheInjectsExistenceBranchedFlags() {
        String base = "#!/bin/sh\n"
            + "BASEDIR=$(dirname \"$0\")/..\n"
            + "DEDIREN_BUNDLE_ROOT=\"${DEDIREN_BUNDLE_ROOT:-$BASEDIR}\"\n"
            + "export DEDIREN_BUNDLE_ROOT\n"
            + "exec \"$JAVACMD\" $JAVA_OPTS -classpath \"$CLASSPATH\" dev.dediren.Main \"$@\"\n";
        String rewritten = DistTool.withAotCache(base, "elk-layout");
        org.assertj.core.api.Assertions.assertThat(rewritten)
            .contains("DEDIREN_AOT_DIR=\"${DEDIREN_AOT_DIR:-$DEDIREN_BUNDLE_ROOT/aot}\"")
            .contains("DEDIREN_AOT_FILE=\"$DEDIREN_AOT_DIR/elk-layout.aot\"")
            .contains("-XX:AOTCache=$DEDIREN_AOT_FILE")
            .contains("-XX:AOTCacheOutput=$DEDIREN_AOT_FILE")
            .contains("${XDG_CACHE_HOME:-$HOME/.cache}/dediren/aot");
    }

    @Test
    void withAotCacheIsIdempotent() {
        String base = "#!/bin/sh\nexport DEDIREN_BUNDLE_ROOT\nexec x\n";
        String once = DistTool.withAotCache(base, "cli");
        org.assertj.core.api.Assertions.assertThat(DistTool.withAotCache(once, "cli")).isEqualTo(once);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl dist-tool test -Dtest=DistModuleTest`
Expected: FAIL — `withAotCache` not found.

- [ ] **Step 3: Implement `withAotCache`**

Add to `DistTool` (next to `withBundleRootExport`):

```java
    static String withAotCache(String script, String aotName) {
        if (script.contains("DEDIREN_AOT_DIR=")) {
            return script;
        }
        String marker = "export DEDIREN_BUNDLE_ROOT";
        int markerIndex = script.indexOf(marker);
        if (markerIndex < 0) {
            throw new IllegalArgumentException(
                "launcher script must contain the DEDIREN_BUNDLE_ROOT export before AOT injection");
        }
        int lineEnd = script.indexOf('\n', markerIndex);
        int insertionPoint = lineEnd < 0 ? script.length() : lineEnd + 1;
        String nl = script.contains("\r\n") ? "\r\n" : "\n";
        String block = ""
            + "DEDIREN_AOT_DIR=\"${DEDIREN_AOT_DIR:-$DEDIREN_BUNDLE_ROOT/aot}\"" + nl
            + "if ! mkdir -p \"$DEDIREN_AOT_DIR\" 2>/dev/null || [ ! -w \"$DEDIREN_AOT_DIR\" ]; then" + nl
            + "  DEDIREN_AOT_DIR=\"${XDG_CACHE_HOME:-$HOME/.cache}/dediren/aot\"" + nl
            + "  mkdir -p \"$DEDIREN_AOT_DIR\" 2>/dev/null || true" + nl
            + "fi" + nl
            + "DEDIREN_AOT_FILE=\"$DEDIREN_AOT_DIR/" + aotName + ".aot\"" + nl
            + "if [ -f \"$DEDIREN_AOT_FILE\" ]; then" + nl
            + "  JAVA_OPTS=\"$JAVA_OPTS -XX:AOTCache=$DEDIREN_AOT_FILE\"" + nl
            + "else" + nl
            + "  JAVA_OPTS=\"$JAVA_OPTS -XX:AOTCacheOutput=$DEDIREN_AOT_FILE\"" + nl
            + "fi" + nl
            + "export JAVA_OPTS" + nl;
        return script.substring(0, insertionPoint) + block + script.substring(insertionPoint);
    }
```

(One-step JEP 514 ergonomics: `-XX:AOTCacheOutput` records *and* assembles the cache at JVM exit while running the app normally; JEP 515 folds method profiles into that cache automatically. The next run finds the file and loads it via `-XX:AOTCache`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl dist-tool test -Dtest=DistModuleTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java \
        dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
git commit -m "feat: launcher-script rewrite for Java 25 Leyden AOT cache"
```

---

### Task 3: Wire AOT into every launcher + end-to-end guard

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`

- [ ] **Step 1: Apply `withAotCache` in `installLauncher`**

In `installLauncher`, set the script through the AOT rewrite. **If Tier 2 shipped**, replace the `withCdsArchive(...)` line with `withAotCache(...)`; **otherwise** add it:

```java
        String script = withBundleRootExport(Files.readString(targetBin, StandardCharsets.UTF_8));
        script = withAotCache(script, launcher.sourceScript());
        Files.writeString(targetBin, script, StandardCharsets.UTF_8);
```

(If Tier 2's `withCdsArchive` and its smoke assertions are present, remove them in the same commit — AOT cache supersedes CDS; running both wastes startup building two caches.)

- [ ] **Step 2: Add static-config + runtime-creation assertions**

```java
    private static void assertAotConfigured(Path bundle) throws IOException {
        for (Launcher launcher : LAUNCHERS) {
            String text = Files.readString(
                bundle.resolve("bin").resolve(launcher.bundleScript()), StandardCharsets.UTF_8);
            if (!text.contains("-XX:AOTCacheOutput=") || !text.contains(launcher.sourceScript() + ".aot")) {
                throw new IllegalStateException(
                    "launcher " + launcher.bundleScript() + " is missing its AOT configuration");
            }
        }
    }

    private static void assertAotCacheCreated(Path bundle, String aotName) {
        Path cache = bundle.resolve("aot").resolve(aotName + ".aot");
        if (!Files.isRegularFile(cache)) {
            throw new IllegalStateException("expected AOT cache was not created: " + cache);
        }
    }
```

In `smoke`, call `assertAotConfigured(bundle);` right after `Path bundle = findBundleDir(temp);`, and `assertAotCacheCreated(bundle, "elk-layout");` after the `layout` step writes `layout.json` (~line 192). (elk-layout runs first in the capabilities loop; its first run writes `aot/elk-layout.aot` at exit, so it exists by the layout step.)

- [ ] **Step 3: Run dist-smoke (on Java 25) to verify it passes**

```bash
./mvnw -pl dist-tool -am verify -Pdist-smoke
```

Expected: PASS — launchers AOT-configured, cache auto-created at runtime, full pipeline still correct under the AOT cache.

- [ ] **Step 4: Commit**

```bash
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java
git commit -m "perf: auto-create per-launcher Leyden AOT caches in the dist bundle"
```

---

### Task 4: Document + prove startup AND warmup win

**Files:**
- Modify: `README.md`, `docs/agent-usage.md`
- Create: `docs/superpowers/plans/2026-06-10-jvm-tier4-results.md`

- [ ] **Step 1: Document the AOT cache + override**

In `docs/agent-usage.md` and `README.md`: each launcher auto-creates a Leyden AOT cache on first use (under `<bundle>/aot/`, or `$XDG_CACHE_HOME/dediren/aot` if read-only); set `DEDIREN_AOT_DIR` to relocate; delete the `aot/` dir after a JDK upgrade so caches regenerate; the feature self-disables harmlessly if the directory is unwritable or the cache is incompatible.

- [ ] **Step 2: Measure cold (build cache) vs warm (use cache)**

```bash
./mvnw -pl dist-tool -am verify -Pdist-bench 2>/dev/null | tail -40   # run #1 builds caches
./mvnw -pl dist-tool -am verify -Pdist-bench 2>/dev/null | tail -40   # run #2 uses caches
```

Record the Tier 0 baseline, run #1 (cold), and run #2 (warm) in `docs/superpowers/plans/2026-06-10-jvm-tier4-results.md`. Expect the warm run to beat both the baseline and Tier 2's CDS numbers (AOT cache adds method profiling on top of class loading).

- [ ] **Step 3: Verify and commit**

```bash
git diff --check
git add README.md docs/agent-usage.md docs/superpowers/plans/2026-06-10-jvm-tier4-results.md
git commit -m "docs: document Leyden AOT cache and record Tier 4 results"
```

---

## Self-review checklist

- **Spec coverage:** Decision gate (Task 0), baseline bump across all confirmed surfaces (Task 1), AOT rewrite (Task 2), wiring + guard (Task 3), docs + measurement (Task 4). Covered.
- **No placeholders:** All edits are exact (line-anchored where stable); `withAotCache` shown in full; supersede-Tier-2 instructions explicit.
- **Type consistency:** `withAotCache(String, String)`, `DEDIREN_AOT_DIR`/`DEDIREN_AOT_FILE`/`<sourceScript>.aot` names, and the `-XX:AOTCacheOutput`/`-XX:AOTCache` flags match across impl, wiring, assertions, and docs.
- **Baseline surfaces complete:** `maven.compiler.release` (single source for all modules), CI ×2, release ×2, `ensureJavaRuntime`, README/agent-usage/CLAUDE.md. The release-note communication satisfies CLAUDE.md's "backwards-incompatible via release notes, not CalVer" rule.
- **Verification:** `./mvnw test` then `./mvnw -pl dist-tool -am verify -Pdist-smoke`, both on a Java 25 JDK, sandbox disabled.
