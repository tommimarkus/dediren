# Tier 1 — Launcher JVM Flags Results

Captured 2026-06-10 with the `dist-tool bench` harness (`./mvnw -pl dist-tool -am verify -Pdist-bench -DskipTests`), 5 runs per command, against the freshly built agent bundle with `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` baked into every launcher via `dediren.launcher.jvmArgs`.

## Environment

Same machine and JDK as Tier 0:

```
openjdk version "21.0.10" 2026-01-20 LTS
OpenJDK Runtime Environment Temurin-21.0.10+7 (build 21.0.10+7-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.10+7 (build 21.0.10+7-LTS, mixed mode, sharing)

Linux 7.0.11-200.fc44.x86_64 x86_64 GNU/Linux
```

## Tier 0 Baseline (`dediren-bench.v1`)

```json
{
  "schema" : "dediren-bench.v1",
  "results" : [ {
    "command" : "cli --version",
    "runs" : 5,
    "min_ms" : 79,
    "median_ms" : 80,
    "max_ms" : 86
  }, {
    "command" : "elk-layout capabilities",
    "runs" : 5,
    "min_ms" : 112,
    "median_ms" : 114,
    "max_ms" : 127
  }, {
    "command" : "elk-layout layout (probe+work)",
    "runs" : 5,
    "min_ms" : 785,
    "median_ms" : 793,
    "max_ms" : 814
  }, {
    "command" : "generic-graph capabilities",
    "runs" : 5,
    "min_ms" : 107,
    "median_ms" : 110,
    "max_ms" : 115
  } ]
}
```

## Tier 1 Results (`dediren-bench.v1`)

```json
{
  "schema" : "dediren-bench.v1",
  "results" : [ {
    "command" : "cli --version",
    "runs" : 5,
    "min_ms" : 69,
    "median_ms" : 70,
    "max_ms" : 74
  }, {
    "command" : "elk-layout capabilities",
    "runs" : 5,
    "min_ms" : 99,
    "median_ms" : 100,
    "max_ms" : 101
  }, {
    "command" : "elk-layout layout (probe+work)",
    "runs" : 5,
    "min_ms" : 682,
    "median_ms" : 691,
    "max_ms" : 714
  }, {
    "command" : "generic-graph capabilities",
    "runs" : 5,
    "min_ms" : 93,
    "median_ms" : 94,
    "max_ms" : 98
  } ]
}
```

## Before / After Comparison

| Command | Tier 0 median | Tier 1 median | Delta | % |
| --- | --- | --- | --- | --- |
| `cli --version` | 80 ms | 70 ms | **−10 ms** | −12.5% |
| `elk-layout capabilities` | 114 ms | 100 ms | **−14 ms** | −12.3% |
| `elk-layout layout (probe+work)` | 793 ms | 691 ms | **−102 ms** | −12.9% |
| `generic-graph capabilities` | 110 ms | 94 ms | **−16 ms** | −14.5% |

## elk-layout Regression Watch

The plan warned that C1-only (`-XX:TieredStopAtLevel=1`) could hurt the CPU-heavy ELK layout JVM because C2 never compiles hot loops. On this machine, `elk-layout layout` median improved by **102 ms** (−12.9%). No regression. The single-property `dediren.launcher.jvmArgs` covers all six launchers including `elk-layout`; no separate `dediren.layout.jvmArgs` fallback is needed.

## Verdict

**Tier 1 delivers a consistent ~13% cold-start reduction across all launchers** with no regressions. The flags are injected from a single root-pom property (`dediren.launcher.jvmArgs`) and the dist-smoke regression guard confirms the flags are baked into every bundled launcher script.

The dominant remaining cost is class loading (especially for the ELK work JVM at ~691 ms), which is the target of Tier 2 (AppCDS).
