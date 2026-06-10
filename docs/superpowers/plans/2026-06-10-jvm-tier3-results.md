# Tier 3 — Manifest-Trust Fast Path: Results

**Date:** 2026-06-10  
**Build:** 2026.06.1 (dist-build profile)  
**Machine:** Linux 7.0.11-200.fc44.x86_64  

## Measurement

The plan's Task 3 Step 3 command was used verbatim. The layout request was
generated with `valid-pipeline-rich.json` via `project --target layout-request
--plugin generic-graph --view main`.

### `dediren layout --plugin elk-layout` timing (3 runs each)

| Mode | Run 1 real | Run 2 real | Run 3 real |
|------|-----------|-----------|-----------|
| Default (probe + work) | 0.648 s | 0.553 s | 0.476 s |
| Trust (work only, `DEDIREN_TRUST_MANIFEST_CAPABILITIES=1`) | 0.448 s | 0.440 s | 0.427 s |

Approximate savings: **~120–200 ms per layout call** (one elk-layout probe JVM
cold start eliminated). Default mean ~0.56 s, trust mean ~0.44 s, delta ~0.12 s.

### Notes

- The delta is consistent and matches the expected cost of one child JVM launch
  (the capability probe). CDS archives were available for the main JVM (Tiers 1/2).
- The probe JVM also benefits from CDS if warmed; these numbers are from a
  pre-warmed state (CDS archives already existed), so the raw first-call savings
  would be larger.
- In trust mode the elk-layout probe JVM is not launched at all; only the work
  JVM runs.

## Self-review

- Two new trust tests pass: `manifestTrustSkipsProbeAndBypassesRuntimeIdCheck`
  and `manifestTrustStillValidatesWorkOutput`.
- Full `PluginRuntimeTest` (15 tests) passes — default behavior unchanged.
- `./mvnw -pl core,cli -am test` passes (all modules).
- Fast path is opt-in only: `DEDIREN_TRUST_MANIFEST_CAPABILITIES` unset →
  byte-for-byte prior behavior.
- Work-output validation (`normalizePluginOutput`) still runs in trust mode.
- README and docs/agent-usage.md both updated together (Files That Move
  Together).
