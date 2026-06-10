# Tier 2 — AppCDS (Auto-Created Class Data Sharing) Results

Captured 2026-06-10. Implementation: `-XX:+AutoCreateSharedArchive
-XX:SharedArchiveFile=$DEDIREN_CDS_DIR/<launcher>.jsa` injected into every
bundled launcher by `DistTool.withCdsArchive`. Archives are auto-built on
first invocation; every subsequent invocation memory-maps pre-parsed classes.

## Environment

```
openjdk version "21.0.10" 2026-01-20 LTS
OpenJDK Runtime Environment Temurin-21.0.10+7 (build 21.0.10+7-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.10+7 (build 21.0.10+7-LTS, mixed mode, sharing)

Linux 7.0.11-200.fc44.x86_64 x86_64 GNU/Linux
```

## Measurement methodology

- **Tier 0 baseline** and **Tier 1 (cold)** are from their own result files —
  same machine and JDK.
- **Tier 2 cold**: `./mvnw -pl dist-tool -am verify -Pdist-bench -DskipTests`
  (5 runs per command). The `dist-bench` profile rebuilds the bundle into a
  fresh temp dir for each full bench run; `AutoCreateSharedArchive` builds
  archives during that same run (run 1 is the cold archive-build invocation, but
  the harness still measures all 5 in sequence). The JSON below is the output of
  a single Maven bench execution — the first invocation of each command built
  the archive; subsequent ones within the same run were warm.
- **Tier 2 warm**: launchers run directly from the persisted
  `dist/dediren-agent-bundle-2026.06.1/` directory after CDS archives were
  pre-built. Five runs per command timed with `/usr/bin/time -f "%e"`. Medians
  rounded to the nearest 5 ms.

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

## Tier 2 — Cold CDS run (`dediren-bench.v1`, `dist-bench` Maven profile)

```json
{
  "schema" : "dediren-bench.v1",
  "results" : [ {
    "command" : "cli --version",
    "runs" : 5,
    "min_ms" : 67,
    "median_ms" : 68,
    "max_ms" : 71
  }, {
    "command" : "elk-layout capabilities",
    "runs" : 5,
    "min_ms" : 41,
    "median_ms" : 41,
    "max_ms" : 174
  }, {
    "command" : "elk-layout layout (probe+work)",
    "runs" : 5,
    "min_ms" : 465,
    "median_ms" : 468,
    "max_ms" : 475
  }, {
    "command" : "generic-graph capabilities",
    "runs" : 5,
    "min_ms" : 41,
    "median_ms" : 42,
    "max_ms" : 43
  } ]
}
```

Note: the 174 ms max on `elk-layout capabilities` was the archive-build
invocation (first run); the four subsequent runs were 41 ms each.

## Tier 2 — Warm CDS run (direct launcher timing, pre-built archives)

Run from `dist/dediren-agent-bundle-2026.06.1/` with all four per-launcher
archives already present in `cds/`. Timed with `/usr/bin/time -f "%e"`, 5 runs:

| Command | Raw times (s) | Median (ms) |
| --- | --- | --- |
| `cli --version` | 0.08, 0.07, 0.07, 0.08, 0.07 | **70** |
| `elk-layout capabilities` | 0.05, 0.05, 0.05, 0.05, 0.05 | **50** |
| `elk-layout layout (probe+work)` | 0.59, 0.59, 0.58, 0.58, 0.57 | **580** |
| `generic-graph capabilities` | 0.05, 0.05, 0.05, 0.05, 0.05 | **50** |

## Comparison: Tier 0 → Tier 1 → Tier 2 warm

| Command | Tier 0 (ms) | Tier 1 (ms) | Tier 2 warm (ms) | Δ vs T0 | Δ vs T1 |
| --- | --- | --- | --- | --- | --- |
| `cli --version` | 80 | 70 | **70** | −10 ms (−12.5%) | 0 ms |
| `elk-layout capabilities` | 114 | 100 | **50** | −64 ms (−56%) | −50 ms (−50%) |
| `elk-layout layout (probe+work)` | 793 | 691 | **580** | −213 ms (−27%) | −111 ms (−16%) |
| `generic-graph capabilities` | 110 | 94 | **50** | −60 ms (−55%) | −44 ms (−47%) |

## Key findings

- **Plugin launchers see the largest gains** (−50% vs Tier 1): `elk-layout
  capabilities` and `generic-graph capabilities` drop from ~94–100 ms to ~50 ms
  because AppCDS eliminates most class-loading time for those classpaths.
- **ELK layout (probe+work) improves −111 ms vs Tier 1** (−16%). The CDS
  archive covers the class-loading phase in both the probe JVM and the work JVM,
  but the layout compute itself is unchanged.
- **CLI-only command** (`--version`) shows no additional gain over Tier 1 (70 ms
  both ways) — its classpath is small enough that the remaining time is dominated
  by process spawn and JVM init, not class parsing.
- **Archive size**: `elk-layout.jsa` ≈ 7 MB, `generic-graph.jsa` ≈ 6.9 MB,
  `cli.jsa` ≈ 1.5 MB. One-time build cost: ~174 ms for the elk-layout archive.
- **Graceful degradation**: if `cds/` is read-only, the launcher falls back to
  `$HOME/.cache/dediren/cds`; if that is also unwritable, `-XX:+AutoCreateSharedArchive`
  silently no-ops and startup continues at Tier-1 speed.

## Verdict

Tier 2 delivers meaningful plugin-cold-start reduction that stacks on Tier 1,
with the biggest relative win on heavy-classpath plugin launchers. The remaining
dominant cost in `elk-layout layout` is layout compute, which AppCDS cannot
eliminate — that is the target of Tier 4 (Leyden AOT).
