# JVM Runtime Optimization — Plan Set Overview

> **For agentic workers:** This is an index, not an executable plan. Each tier
> below is its own plan file. Execute them in dependency order. Tiers 0–3 are
> independent, executable today on Java 21. Tier 4 supersedes Tier 2 and is
> gated on a Java-baseline decision. Tier 5 is a research spike.

**Problem (from research, 2026-06-10):** dediren is a startup-bound, run-once
Java workload. Every plugin operation spawns **two** JVM cold starts (a
mandatory `capabilities` probe at `PluginRunner.java:47`, then the work call at
`:65`), and a full pipeline triggers ~10 cold starts loading a heavy
ELK/EMF/Xtext/Guava classpath. There is **zero** JVM startup tuning anywhere.
The process-boundary plugin model is a product contract, so a long-lived
daemon/pool is out of scope — every plan below either makes each cold start
cheaper or reduces the number of cold starts, without changing the architecture.

## The plans

| Tier | File | What | Java baseline | Effort |
| --- | --- | --- | --- | --- |
| 0 | `2026-06-10-jvm-tier0-startup-measurement.md` | `dist-tool bench` cold-start measurement harness | 21 | ~0.5 day |
| 1 | `2026-06-10-jvm-tier1-launch-flags.md` | C1-only + SerialGC launcher flags via shared pom property | 21 | ~0.5 day |
| 2 | `2026-06-10-jvm-tier2-appcds.md` | Per-launcher AppCDS archives baked into the bundle | 21 | ~1–2 days |
| 3 | `2026-06-10-jvm-tier3-probe-cache.md` | Opt-in manifest-trust fast path that skips the probe JVM | 21 | ~1 day |
| 4 | `2026-06-10-jvm-tier4-leyden-aot-cache.md` | JDK 25 Leyden AOT cache (supersedes Tier 2) | **25** | ~2–3 days + decision |
| 5 | `2026-06-10-jvm-tier5-native-image-spike.md` | GraalVM native-image research spike, exporters first | 21/25 | weeks (spike) |

## Dependency order

1. **Tier 0 first.** It produces the baseline numbers every other tier proves
   wins against. Do not skip it.
2. **Tier 1, Tier 2, Tier 3 are independent** and stack additively. Recommended
   order after Tier 0: Tier 1 (cheapest), then Tier 2 (biggest structural win on
   Java 21), then Tier 3 (halves the count the others speed up).
3. **Tier 4 supersedes Tier 2.** Do one or the other, not both — Tier 4 is the
   Leyden successor to AppCDS. Choose Tier 4 only after deciding to raise the
   product baseline from Java 21 to Java 25 (a compatibility decision; see that
   plan's decision gate). If you ship Tier 2 first, Tier 4 replaces its archive
   generation.
4. **Tier 5 is orthogonal** and long-horizon. It can start any time as a spike;
   it does not block the others.

## Cross-cutting conventions used by these plans

- **Launcher JVM flags** are injected once via a root-pom property
  `dediren.launcher.jvmArgs` referenced by every module's appassembler
  `extraJvmArguments` (Tier 1 establishes it; Tiers 2/4 extend it or the bundle
  launcher rewrite in `DistTool`).
- **Bundle-relative paths** use the existing `DEDIREN_BUNDLE_ROOT` export that
  `DistTool.withBundleRootExport` already injects into each launcher script.
- **Graceful fallback** is mandatory: CDS uses `-Xshare:auto` (never `:on`) and
  the probe-cache fast path is opt-in, so a cold/incompatible cache degrades to
  today's behavior rather than failing.
- **Verification** for any tier touching launchers or the bundle always ends
  with `./mvnw -pl dist-tool -am verify -Pdist-smoke` (proves ELK layout, SVG
  render, and OEF/XMI export still pass end-to-end under the change).
