# Tier 2 — AppCDS (Auto-Created Class Data Sharing) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut the dominant cold-start cost — class loading + verification of the heavy ELK/EMF/Xtext/Guava classpath — by giving each bundled launcher an auto-created AppCDS archive, so repeated invocations memory-map pre-parsed/linked classes instead of re-loading them.

**Architecture:** Use **`-XX:+AutoCreateSharedArchive`** (JDK 19+), not a build-time prebuilt `.jsa`. The bundle stays platform/JDK-neutral (the existing `target: "java"` contract): each launcher, on its **first** run, builds a CDS archive matched to *the user's own JDK* into a writable cache dir, and every subsequent run reuses it. This is injected by a new launcher-script rewrite in `DistTool` (mirroring the existing `withBundleRootExport`), so no Java plugin code and no plugin behavior changes. `-Xshare:auto` semantics mean a missing/incompatible/unwritable archive degrades silently to today's behavior.

**Tech Stack:** Java 21 (`-XX:+AutoCreateSharedArchive`), Maven Wrapper, JUnit 5, AssertJ, POSIX sh launcher scripts.

**Why auto-create, not prebuilt:** A prebuilt `.jsa` is tied to the builder's exact JDK build + platform; shipped in a "platform-neutral java" bundle it would only help users on a matching JDK and silently no-op elsewhere. Auto-create always matches the user's JDK and preserves bundle neutrality. The only cost is a one-time archive build on first invocation, which amortizes immediately for an agent driving the pipeline repeatedly.

**Depends on:** Tier 0 (baseline). Independent of Tier 1 (stacks additively). **Superseded by Tier 4** — if you adopt the Java 25 Leyden AOT cache, it replaces this archive mechanism; do not run both.

---

### Task 1: Pure launcher-script CDS rewrite

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
- Test: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

- [ ] **Step 1: Write the failing test**

Add to `DistModuleTest` (this verifies the rewrite against the shape `withBundleRootExport` produces — a script already containing the bundle-root export):

```java
    @Test
    void withCdsArchiveInjectsAutoCreateAfterBundleRootExport() {
        String base = "#!/bin/sh\n"
            + "BASEDIR=$(dirname \"$0\")/..\n"
            + "DEDIREN_BUNDLE_ROOT=\"${DEDIREN_BUNDLE_ROOT:-$BASEDIR}\"\n"
            + "export DEDIREN_BUNDLE_ROOT\n"
            + "exec \"$JAVACMD\" $JAVA_OPTS -classpath \"$CLASSPATH\" dev.dediren.Main \"$@\"\n";
        String rewritten = DistTool.withCdsArchive(base, "elk-layout");
        org.assertj.core.api.Assertions.assertThat(rewritten)
            .contains("DEDIREN_CDS_DIR=\"${DEDIREN_CDS_DIR:-$DEDIREN_BUNDLE_ROOT/cds}\"")
            .contains("-XX:+AutoCreateSharedArchive")
            .contains("-XX:SharedArchiveFile=$DEDIREN_CDS_DIR/elk-layout.jsa")
            .contains("${XDG_CACHE_HOME:-$HOME/.cache}/dediren/cds");
        // exec line still present and after the injected block
        org.assertj.core.api.Assertions.assertThat(rewritten.indexOf("DEDIREN_CDS_DIR="))
            .isLessThan(rewritten.indexOf("exec "));
    }

    @Test
    void withCdsArchiveIsIdempotent() {
        String base = "#!/bin/sh\nexport DEDIREN_BUNDLE_ROOT\nexec x\n";
        String once = DistTool.withCdsArchive(base, "cli");
        String twice = DistTool.withCdsArchive(once, "cli");
        org.assertj.core.api.Assertions.assertThat(twice).isEqualTo(once);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl dist-tool test -Dtest=DistModuleTest`
Expected: FAIL — `withCdsArchive` not found.

- [ ] **Step 3: Implement `withCdsArchive`**

Add to `DistTool` (next to `withBundleRootExport`, ~line 240):

```java
    static String withCdsArchive(String script, String cdsName) {
        if (script.contains("DEDIREN_CDS_DIR=")) {
            return script;
        }
        String marker = "export DEDIREN_BUNDLE_ROOT";
        int markerIndex = script.indexOf(marker);
        if (markerIndex < 0) {
            throw new IllegalArgumentException(
                "launcher script must contain the DEDIREN_BUNDLE_ROOT export before CDS injection");
        }
        int lineEnd = script.indexOf('\n', markerIndex);
        int insertionPoint = lineEnd < 0 ? script.length() : lineEnd + 1;
        String nl = script.contains("\r\n") ? "\r\n" : "\n";
        String block = ""
            + "DEDIREN_CDS_DIR=\"${DEDIREN_CDS_DIR:-$DEDIREN_BUNDLE_ROOT/cds}\"" + nl
            + "if ! mkdir -p \"$DEDIREN_CDS_DIR\" 2>/dev/null || [ ! -w \"$DEDIREN_CDS_DIR\" ]; then" + nl
            + "  DEDIREN_CDS_DIR=\"${XDG_CACHE_HOME:-$HOME/.cache}/dediren/cds\"" + nl
            + "  mkdir -p \"$DEDIREN_CDS_DIR\" 2>/dev/null || true" + nl
            + "fi" + nl
            + "JAVA_OPTS=\"$JAVA_OPTS -XX:+AutoCreateSharedArchive"
            + " -XX:SharedArchiveFile=$DEDIREN_CDS_DIR/" + cdsName + ".jsa\"" + nl
            + "export JAVA_OPTS" + nl;
        return script.substring(0, insertionPoint) + block + script.substring(insertionPoint);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl dist-tool test -Dtest=DistModuleTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java \
        dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
git commit -m "feat: launcher-script rewrite for auto-created CDS archives"
```

---

### Task 2: Apply the rewrite to every bundled launcher + end-to-end guard

**Files:**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`

- [ ] **Step 1: Wire `withCdsArchive` into `installLauncher`**

In `DistTool.installLauncher` (~line 232), replace:

```java
        Files.writeString(
            targetBin,
            withBundleRootExport(Files.readString(targetBin, StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8);
```

with:

```java
        String script = withBundleRootExport(Files.readString(targetBin, StandardCharsets.UTF_8));
        script = withCdsArchive(script, launcher.sourceScript());
        Files.writeString(targetBin, script, StandardCharsets.UTF_8);
```

(`launcher.sourceScript()` is unique per launcher — `cli`, `elk-layout`, `svg-render`, `generic-graph`, `archimate-oef-export`, `uml-xmi-export` — so archives never collide.)

- [ ] **Step 2: Add static-config + runtime-creation assertions to `smoke`**

Add two methods near the other `assert*` helpers:

```java
    private static void assertCdsConfigured(Path bundle) throws IOException {
        for (Launcher launcher : LAUNCHERS) {
            String text = Files.readString(
                bundle.resolve("bin").resolve(launcher.bundleScript()), StandardCharsets.UTF_8);
            if (!text.contains("-XX:+AutoCreateSharedArchive")
                || !text.contains(launcher.sourceScript() + ".jsa")) {
                throw new IllegalStateException(
                    "launcher " + launcher.bundleScript() + " is missing its CDS configuration");
            }
        }
    }

    private static void assertCdsArchiveCreated(Path bundle, String cdsName) {
        Path archive = bundle.resolve("cds").resolve(cdsName + ".jsa");
        if (!Files.isRegularFile(archive)) {
            throw new IllegalStateException("expected CDS archive was not auto-created: " + archive);
        }
    }
```

In `smoke`, call `assertCdsConfigured(bundle);` immediately after `Path bundle = findBundleDir(temp);` (~line 155). Then, after the `layout` step writes `layout.json` (just after line 192), add:

```java
            assertCdsArchiveCreated(bundle, "elk-layout");
```

(By that point the elk-layout launcher has run at least once — in the capabilities loop and again for layout — so `-XX:+AutoCreateSharedArchive` has dumped `cds/elk-layout.jsa` at the first run's exit. The bundle is extracted to a writable temp dir, so `DEDIREN_CDS_DIR` resolves to the writable `<bundle>/cds`.)

- [ ] **Step 3: Run dist-smoke to verify it passes**

Run (sandbox disabled per project memory):

```bash
./mvnw -pl dist-tool -am verify -Pdist-smoke
```

Expected: PASS. Proves (a) every launcher is CDS-configured, (b) the archive is actually auto-created at runtime, and (c) the full ELK→render→export pipeline still works with CDS active.

- [ ] **Step 4: Manually confirm reuse (no regeneration on second run)**

```bash
B=$(ls -d dist/dediren-agent-bundle-*/ | head -1)
rm -rf "$B/cds"
"$B/bin/dediren-plugin-elk-layout" capabilities >/dev/null   # builds archive
ls -la "$B/cds/"                                              # elk-layout.jsa exists
"$B/bin/dediren-plugin-elk-layout" capabilities 2>&1 | grep -i "shared" || echo "reused silently (expected)"
```

Expected: `cds/elk-layout.jsa` exists after the first call; the second call reuses it with no error.

- [ ] **Step 5: Commit**

```bash
git add dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java
git commit -m "perf: auto-create per-launcher CDS archives in the dist bundle"
```

---

### Task 3: Document the CDS behavior + prove the win

**Files:**
- Modify: `README.md`
- Modify: `docs/agent-usage.md`
- Create: `docs/superpowers/plans/2026-06-10-jvm-tier2-results.md`

CLAUDE.md "Files That Move Together" requires launcher/runtime changes to update `README.md` and `docs/agent-usage.md` together.

- [ ] **Step 1: Document the runtime cache + override in `docs/agent-usage.md`**

Add a short note in the runtime/troubleshooting section: each `bin/dediren*` launcher auto-creates a Class-Data-Sharing archive on first use to speed startup; archives live under `<bundle>/cds/` (or `$XDG_CACHE_HOME/dediren/cds` if the bundle dir is read-only); set `DEDIREN_CDS_DIR` to relocate them, and the feature self-disables harmlessly if the directory is unwritable.

- [ ] **Step 2: Mirror the note in `README.md`**

Add the same behavior (1–2 sentences) wherever the README describes the bundle layout / runtime requirements, including that `cds/` is a generated runtime cache (not a tracked artifact).

- [ ] **Step 3: Re-run bench and record before/after**

```bash
./mvnw -pl dist-tool -am verify -Pdist-bench 2>/dev/null | tail -40
```

Run it **twice** (first run builds archives, second shows the warm-CDS numbers). Create `docs/superpowers/plans/2026-06-10-jvm-tier2-results.md` with the Tier 0 baseline, the cold-CDS run, and the warm-CDS run, plus `median_ms` deltas. Expect the largest improvement on `elk-layout` commands (heaviest classpath).

- [ ] **Step 4: Verify docs have no whitespace errors and commit**

```bash
git diff --check
git add README.md docs/agent-usage.md docs/superpowers/plans/2026-06-10-jvm-tier2-results.md
git commit -m "docs: document auto-created CDS archives and record Tier 2 results"
```

---

## Self-review checklist

- **Spec coverage:** Per-launcher CDS via auto-create (Tasks 1–2), neutrality preserved (auto-create design), docs + measured proof (Task 3). Covered.
- **No placeholders:** `withCdsArchive` is shown in full; smoke assertions are concrete; the only deferred content is measured numbers in Task 3.
- **Type consistency:** `withCdsArchive(String, String)` signature and the `DEDIREN_CDS_DIR` / `<sourceScript>.jsa` names are identical across Task 1 (impl), Task 2 (wiring + assertions), and the docs. `launcher.sourceScript()` is the archive key everywhere.
- **Graceful degradation:** `-Xshare:auto` is implied by `AutoCreateSharedArchive`; unwritable dir → warning + normal startup, never a hard failure. Stated and tested via the read-only fallback path.
- **Verification:** `./mvnw -pl dist-tool test` (unit) + `./mvnw -pl dist-tool -am verify -Pdist-smoke` (end-to-end, sandbox disabled). Re-confirm with `git diff --check` for docs.
