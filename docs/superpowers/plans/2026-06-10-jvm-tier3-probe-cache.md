# Tier 3 — Manifest-Trust Fast Path (Skip the Probe JVM) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Halve the cold starts on the hot path by letting `core` skip the mandatory `capabilities` probe JVM for work commands when the caller opts in to trusting the (already-loaded, already-capability-checked) static plugin manifest.

**Architecture:** Today every work call runs two JVMs — a `capabilities` probe (`PluginRunner.java:47`) used as a runtime id-integrity check, then the work command (`:65`). Add an **opt-in** env switch `DEDIREN_TRUST_MANIFEST_CAPABILITIES`. When set, `runForCapabilityWithRegistry` short-circuits straight to the work command after the existing static manifest capability check (`:31`), skipping the probe. Default (unset) is byte-for-byte today's behavior. Work-output envelope/schema validation is unchanged in both modes — only the *pre-flight probe* is skipped.

**Tech Stack:** Java 21, JUnit 5, AssertJ. Pure `core` change; no JSON contract change, no schema-id change, no version bump.

**The tradeoff (deliberate, opt-in):** The probe is also the source of the `DEDIREN_PLUGIN_ID_MISMATCH` runtime-drift check (`:49–53`). Trust mode forgoes that pre-flight integrity check in exchange for one fewer JVM per call. It is appropriate for trusted, integrity-checked first-party bundles, which is why it is **off by default** and activated only by explicit env opt-in. CLAUDE.md's "decide success or failure from stdout JSON alone" guarantee is preserved because work-output validation still runs.

**Depends on:** Tier 0 (to measure the saved probe cost). Independent of Tiers 1/2.

---

### Task 1: Failing tests for the fast path

**Files:**
- Modify: `core/src/test/java/dev/dediren/core/plugins/PluginRuntimeTest.java`

These reuse the existing testbed harness (`writeManifest`, `testbedExecutable`, the `DEDIREN_TEST_PLUGIN_*` env knobs).

- [ ] **Step 1: Add the two characterizing tests**

Append to `PluginRuntimeTest` (the `runtimeIdMismatchIsStructured` test already proves the *default* path still catches mismatches, so these only add the trust-mode behavior):

```java
    @Test
    void manifestTrustSkipsProbeAndBypassesRuntimeIdCheck() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("layout"));
        var options = PluginRunOptions.defaults().withCandidateEnv(Map.of(
                "DEDIREN_TEST_PLUGIN_MODE", "ok",
                "DEDIREN_TEST_PLUGIN_CAPABILITIES", "layout",
                "DEDIREN_TEST_PLUGIN_ID", "different-plugin",
                "DEDIREN_TRUST_MANIFEST_CAPABILITIES", "1"));

        PluginRunOutcome outcome = PluginRunner.runForCapabilityWithRegistry(
                PluginRegistry.fromDirs(List.of(temp)),
                "runtime-testbed",
                "layout",
                List.of("layout"),
                "{}",
                options);

        // Probe is skipped, so the mismatched runtime id is never inspected and the work command runs.
        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.stdout()).contains("\"layout_result_schema_version\"");
    }

    @Test
    void manifestTrustStillValidatesWorkOutput() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("layout"));
        var options = PluginRunOptions.defaults().withCandidateEnv(Map.of(
                "DEDIREN_TEST_PLUGIN_MODE", "invalid-data",
                "DEDIREN_TEST_PLUGIN_CAPABILITIES", "layout",
                "DEDIREN_TRUST_MANIFEST_CAPABILITIES", "true"));

        assertThatThrownBy(() -> PluginRunner.runForCapabilityWithRegistry(
                PluginRegistry.fromDirs(List.of(temp)),
                "runtime-testbed",
                "layout",
                List.of("layout"),
                "{}",
                options))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo("DEDIREN_PLUGIN_OUTPUT_INVALID_DATA");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run (sandbox disabled per project memory — these spawn JVMs / use `@TempDir`):

```bash
./mvnw -pl core test -Dtest=PluginRuntimeTest#manifestTrustSkipsProbeAndBypassesRuntimeIdCheck+manifestTrustStillValidatesWorkOutput
```

Expected: FAIL — without trust handling, `manifestTrustSkipsProbeAndBypassesRuntimeIdCheck` throws `DEDIREN_PLUGIN_ID_MISMATCH` (the probe still runs and inspects the mismatched id) instead of returning exit 0.

- [ ] **Step 3: Commit the failing tests**

```bash
git add core/src/test/java/dev/dediren/core/plugins/PluginRuntimeTest.java
git commit -m "test: characterize manifest-trust fast path that skips the probe"
```

---

### Task 2: Implement the opt-in fast path

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/plugins/PluginRunner.java`

- [ ] **Step 1: Add the env-resolution helper**

Add to `PluginRunner` (next to `executablePath`, ~line 83):

```java
    private static boolean manifestTrustEnabled(PluginRunOptions options) {
        String value = options.candidateEnv().get("DEDIREN_TRUST_MANIFEST_CAPABILITIES");
        if (value == null) {
            value = System.getenv("DEDIREN_TRUST_MANIFEST_CAPABILITIES");
        }
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
```

(Resolved the same way as the `DEDIREN_PLUGIN_*` executable overrides in `executablePath`: candidate env first, then process env. It is a `core` control, so it is intentionally read directly and never forwarded to the plugin via `allowedEnv`.)

- [ ] **Step 2: Short-circuit before the probe**

In `runForCapabilityWithRegistry`, immediately after the line `Map<String, String> allowedEnv = allowedEnv(options, loaded);` (line 46) and *before* the probe (`ProcessOutput capabilities = runExecutable(... "capabilities" ...)` at line 47), insert:

```java
        if (!capabilitiesCommand && manifestTrustEnabled(options)) {
            ProcessOutput trusted = runExecutable(pluginId, executable, args, input, options.timeout(), allowedEnv);
            return normalizePluginOutput(pluginId, requiredCapability, args, trusted);
        }
```

The static manifest capability check at line 31 has already run, so an unsupported capability is still rejected before reaching here. `normalizePluginOutput` still enforces the envelope + capability-result schema, so a misbehaving plugin is still caught — only the probe and the runtime id/capability cross-checks (`:48–60`) are skipped. The `capabilitiesCommand` path is untouched (an explicit `capabilities` request must still execute the plugin).

- [ ] **Step 3: Run the new tests to verify they pass**

```bash
./mvnw -pl core test -Dtest=PluginRuntimeTest#manifestTrustSkipsProbeAndBypassesRuntimeIdCheck+manifestTrustStillValidatesWorkOutput
```

Expected: PASS (2 tests).

- [ ] **Step 4: Run the full PluginRuntimeTest to prove default behavior is unchanged**

```bash
./mvnw -pl core test -Dtest=PluginRuntimeTest
```

Expected: PASS — all existing tests (including `runtimeIdMismatchIsStructured`, which has no trust env) still pass, confirming default behavior is identical.

- [ ] **Step 5: Run the plugin-runtime lane**

```bash
./mvnw -pl core,cli -am test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/dev/dediren/core/plugins/PluginRunner.java
git commit -m "perf: opt-in manifest-trust fast path skipping the capability probe"
```

---

### Task 3: Document the opt-in + prove the saved probe

**Files:**
- Modify: `README.md`
- Modify: `docs/agent-usage.md`
- Create: `docs/superpowers/plans/2026-06-10-jvm-tier3-results.md`

Plugin-runtime change → README + agent-usage move together (CLAUDE.md "Files That Move Together").

- [ ] **Step 1: Document the env var in `docs/agent-usage.md`**

In the runtime/plugin section, add: setting `DEDIREN_TRUST_MANIFEST_CAPABILITIES=1` (or `true`) makes dediren trust each plugin's static manifest capabilities and skip the per-call runtime capability probe, removing one JVM start per plugin operation; the tradeoff is that the runtime `id`-mismatch pre-flight check is bypassed, so use it only with trusted bundles. Default (unset) keeps the probe.

- [ ] **Step 2: Mirror in `README.md`**

Add the same opt-in and its tradeoff (2 sentences) in the plugin-runtime/environment section.

- [ ] **Step 3: Measure the saved probe**

Build the bundle, prepare a layout request, and time both modes:

```bash
./mvnw -pl dist-tool -am verify -Pdist-build
B=$(ls -d dist/dediren-agent-bundle-*/ | head -1)
"$B/bin/dediren" project --target layout-request --plugin generic-graph --view main \
  --input "$B/fixtures/source/valid-pipeline-rich.json" > /tmp/req.json
echo "== default (probe + work) =="; time "$B/bin/dediren" layout --plugin elk-layout --input /tmp/req.json > /dev/null
echo "== trust (work only) ==";     time env DEDIREN_TRUST_MANIFEST_CAPABILITIES=1 "$B/bin/dediren" layout --plugin elk-layout --input /tmp/req.json > /dev/null
```

Record both timings in `docs/superpowers/plans/2026-06-10-jvm-tier3-results.md`; the delta is one elk-layout cold start (the probe).

- [ ] **Step 4: Verify docs and commit**

```bash
git diff --check
git add README.md docs/agent-usage.md docs/superpowers/plans/2026-06-10-jvm-tier3-results.md
git commit -m "docs: document manifest-trust opt-in and record Tier 3 results"
```

---

## Self-review checklist

- **Spec coverage:** Opt-in fast path (Task 2), default-unchanged proof (Task 2 Step 4), output-validation-still-runs proof (Task 1 second test), docs + measurement (Task 3). Covered.
- **No placeholders:** `manifestTrustEnabled` and the short-circuit are shown in full; tests use only existing harness helpers and env knobs verified to exist (`DEDIREN_TEST_PLUGIN_MODE/CAPABILITIES/ID`, modes `ok` and `invalid-data`).
- **Type consistency:** Env name `DEDIREN_TRUST_MANIFEST_CAPABILITIES` and method `manifestTrustEnabled(PluginRunOptions)` match between tests and implementation. `runForCapabilityWithRegistry` signature unchanged, so no caller updates needed.
- **Contract safety:** No schema/JSON shape change; `normalizePluginOutput` validation path is preserved, satisfying CLAUDE.md plugin-runtime rules. Default behavior identical — no version bump unless released.
- **Verification:** `./mvnw -pl core,cli -am test` (sandbox disabled).
