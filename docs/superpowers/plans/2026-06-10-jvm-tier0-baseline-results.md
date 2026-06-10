# Tier 0 — JVM Cold-Start Baseline Results

Captured 2026-06-10 with the `dist-tool bench` harness (`./mvnw -pl dist-tool -am verify -Pdist-bench`), 5 runs per command, against the freshly built agent bundle. **No JVM tuning applied** — this is the untuned baseline that Tiers 1–4 are measured against.

## Environment

```
openjdk version "21.0.10" 2026-01-20 LTS
OpenJDK Runtime Environment Temurin-21.0.10+7 (build 21.0.10+7-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.10+7 (build 21.0.10+7-LTS, mixed mode, sharing)

Linux 7.0.11-200.fc44.x86_64 x86_64 GNU/Linux
```

(`sharing` = the default JDK-class CDS archive is active; there is **no** AppCDS for dediren/ELK application classes — that is Tier 2.)

## Results (`dediren-bench.v1`)

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

## Reading

| Command | Median | What it measures |
| --- | --- | --- |
| `cli --version` | **80 ms** | One bare CLI JVM cold start, no plugin, no ELK |
| `generic-graph capabilities` | **110 ms** | One pure-Java plugin cold start (CLI not involved — launcher invoked directly) |
| `elk-layout capabilities` | **114 ms** | One elk-layout cold start running only the `capabilities` probe (does *not* load the ELK layout algorithm classes) |
| `elk-layout layout (probe+work)` | **793 ms** | Full pipeline step: CLI JVM → elk-layout **probe** JVM → elk-layout **work** JVM (loads the full Eclipse ELK/EMF/Xtext classpath + runs the layout) |

Key takeaways that direct the later tiers:

- **The ELK work JVM dominates.** `elk-layout layout` (793 ms) dwarfs a bare CLI start (80 ms) or the probe-only `capabilities` (114 ms). The gap is almost entirely **class loading + linking of the heavy ELK/EMF/Xtext/Guava classpath plus the layout compute** in the work JVM. → This is exactly what **Tier 2 (AppCDS)** and **Tier 4 (Leyden AOT cache)** attack.
- **The probe is a real but secondary tax.** The mandatory `capabilities` probe is a full ~114 ms cold start riding in front of every work call. **Tier 3 (manifest-trust fast path)** removes it — an expected ~110–115 ms / ~14% saving on a `layout` call, more in relative terms on lighter plugins.
- **Every command pays an untuned cold start.** Even the 80 ms bare CLI start has no C1-only/SerialGC tuning. → **Tier 1** shaves a slice off *all* of these uniformly.

## Notes

- The `dist-bench` profile is pinned to `--runs 5`. The `bench` subcommand accepts `--runs N` for ad-hoc deeper sampling.
- Numbers are machine- and load-dependent; re-capture on the same host before/after each tier rather than comparing across machines.
