# Runtime size + speed challenge (2026-07-08)

Time-boxed exploration (20 min explore + 5 min summaries) of how small and
fast the dediren agent-bundle runtime can get. Ten ideas were opened, refined
against measurements on the built `dist/dediren-agent-bundle-2026.07.8`
(Temurin 21), and cut on evidence, ROI, or architecture constraints. Ledgers
were kept per idea in near-realtime; they are reproduced verbatim below.

Nothing in the repo was changed: all experiments ran on scratchpad copies of
the bundle. Findings are proposals, not implemented work.

A same-day post-challenge follow-up re-examined I9 (single-JVM host) without
the architecture constraint and promoted it to dark horse, then ruled to
prefer I9 over I5 (keeping only I5's low-disruption wins) and radicalized I4
from an optional raster pack to dropping PNG support entirely. Those additions
are marked "follow-up" in the ledgers; the challenge-time record is otherwise
untouched.

## Top 3 — runtime small+fast challenge (2026-07-08)

Baseline (bundle 2026.07.8, Temurin 21, warm CDS): download 17M tar.gz; installed 18M lib + ~25M runtime-generated CDS; 3-stage pipeline (project→layout→render) 1.325s; each stage = 3 JVM spawns (CLI + capability probe + plugin).

### 1. I7 — Store-repacked jars + xz distribution (size −48%, speed −8%, zero product code)
Repack all 58 jars with zip -0 (stored) and ship as tar.xz: 16.74M → 8.85M because xz can finally compress across class files that per-jar deflate was hiding. Verified functionally (full pipeline ok) and warm pipeline got ~8% faster (1.213s vs 1.325s — no per-class inflate). Cost: installed lib grows 18M→41M (disk-for-download trade). Change confined to dist-tool packaging.

### 2. I5 — Kill redundant JVM spawns: `dediren pipeline` + probe cache (agent flow ~1.9s → ~1.0s)
Documented agent flow is 5 CLI invocations, each spawning CLI + probe + plugin JVMs (~13–15 JVMs/diagram). Measured probe cost ~50–80ms/stage (trust mode: 1.325s→1.152s). Proposal: (a) one-shot `pipeline` subcommand chaining validate→project→layout→validate-layout→render in one CLI JVM (saves ~4 CLI startups + envelope re-parsing); (b) probe-result cache keyed by manifest hash + launcher mtime, so the probe is paid once ever instead of per call — same integrity posture, no trust-mode opt-in needed. Core/CLI change; preserves process-boundary architecture.

*Follow-up: demoted — I9 preferred (see dark horse). Only the probe cache
survives as standalone work; the `pipeline` subcommand folds into I9 as its
facade.*

### 3. I4 — Optional raster pack: split batik out of the base bundle (base download → ~7.1M)
Batik/xmlgraphics family (21 jars, 4.2M raw, 1.74M compressed ≈ 20% of the stored-xz download) is used only by SvgRasterizer for PNG output; SVG rendering doesn't need it. Ship an SVG-only base bundle + optional raster add-on pack. Combined with #1: 17M → ~7.1M (−58%). Fewer jars also shrink CDS training time and the on-disk archive.

*Follow-up: radicalized — drop PNG support entirely instead of shipping a
raster pack; SVG→PNG is well served by external CLI tools (rsvg-convert,
resvg, ImageMagick, Inkscape). Same download win, no add-on pack to build or
maintain. See the I4 ledger for the measured removal surface.*

### Dark horse — I9 single-JVM plugin host (follow-up, unconstrained)
Cut during the challenge on architecture grounds, not on ROI. If the
process-boundary rule were relaxed for bundled first-party plugins, I9 is the
biggest speed lever on the board and would displace I5 (which is its
constrained approximation): one JVM boot instead of ~13–15 spawns per diagram.
Estimated ~0.5–0.7s one-shot for the 3-stage pipeline (vs 1.15s trust-mode
baseline); a persistent daemon variant drops subsequent diagrams to an
estimated ~0.1–0.3s (5–10×), since ELK/EMF/Jackson class loading and JIT
warmup are paid once. Also shrinks the warmed on-disk footprint (~15–20M less
CDS: one archive instead of five). Feasibility signal: the bundle already uses
one shared deduped `lib/`, proving the full dependency graph coexists
conflict-free in a single classpath. Realistic shape is a hybrid, not
boundary removal: in-process fast path for bundled first-party plugins,
JSON-over-stdio retained for third-party/external plugins (fault isolation and
the threat-model trust boundary survive for them). One-shot estimates are
unmeasured (plugins never ran in-process); the daemon claim rests on measured
per-spawn overhead.

Composability with the top 3: no hard mutual exclusions. I7 (stored jars) and
I4 (raster pack) are packaging-level and compose with everything, including
each other (measured ~7.1M combined). The only tension is I5 ↔ I9: I9
subsumes most of I5's win, so building both in full is wasted effort.
Ruling (follow-up): prefer I9 and take from I5 only the wins that don't
shuffle the deck — the probe cache (zero user-visible change, keeps
independent value under I9 for external process-boundary plugins) lands
standalone; the `dediren pipeline` subcommand is not built separately but
arrives with I9 as the facade the host/daemon sits behind.

### Honorable mention / cut log
- I2 CDS: already well-designed (AutoCreateSharedArchive, runtime-generated, not shipped). Residual: fix "Old class has been linked" archive-degradation warnings; version-gate `-XX:AOTCache` for JDK 24+ (I8).
- CUT with evidence: I3 JVM flags (C1-only/SerialGC: zero measurable change), naive recompression (xz/zstd on deflated jars: ~2%).
- CUT on constraints/ROI: I1 jlink (triples download), I6 native-image (size regression risk, EMF/Jackson reflection, unmeasurable here), I9 single-JVM host (violates process-boundary architecture; later promoted to dark horse in the follow-up above), I10 content-strip (~2%, hurts agent usability).

## Runtime small+fast challenge — master ledger

Start: 2026-07-08 00:00:16 EEST. Budget: 20 min explore + 5 min summaries.
Target: dediren agent bundle runtime (dist bundle 2026.07.8 as baseline).

### Baseline facts
- Bundle dir 42M; tar.gz 17M.
- cds/ = 23M (3 .jsa: elk-layout, generic-graph, render). lib/ = 18M (58 unique jars, already deduped, shared).
- No .jsa for: CLI (dediren), archimate-oef-export, uml-xmi-export.
- Big jars: guava 3.0M, elk.core 2.1M, jackson-databind 1.9M, elk.layered 1.3M, emf.ecore 1.2M, batik ~2.5M total, commons-io 0.6M, picocli 0.4M.

### Idea register (status: OPEN / REFINING / CUT / TOP)
- I1 jlink-runtime — OPEN
- I2 cds-strategy (coverage gap + on-install generation to cut 23M) — OPEN
- I3 jvm-flags (tiered stop, serial GC, AutoCreateSharedArchive) — OPEN
- I4 dep-slimming (jdeps/shrink guava, jackson, batik, commons-io) — OPEN
- I5 process-count (probe caching, fewer JVM spawns per pipeline) — OPEN
- I6 native-image — OPEN
- I7 compression (zstd/xz tarball, jar recompress/strip) — OPEN
- I8 aot-cache-leyden (JDK24+ opportunistic) — OPEN
- I9 single-jvm-host — OPEN (likely arch-violating)
- I10 bundle-content-strip (fixtures/docs) — OPEN

### Final decisions (00:19)
- CUT: I1 jlink, I3 flags, I6 native-image, I9 single-JVM, I10 content-strip, I2 cds (folded to notes), I8 (folded).
- TOP 3: I7 stored-jar repack (+xz), I5 process-count (pipeline cmd + probe cache), I4 raster-pack split.
- Combined projection: download 17M → ~7.1M (-58%); 3-stage warm 1.33s → ~0.85-0.95s; agent 5-stage ~1.9s → ~1.0s.

### Follow-up decisions (same day)
- I9 promoted to DARK HORSE (unconstrained re-examination on request; see dark-horse section and I9 ledger).
- Composability ruling: no hard mutual exclusions among top 3 + dark horse; I5 ↔ I9 overlap is the only tension.
- Preference ruling (user): I9 over I5. From I5 keep only the probe cache (low-disruption, no user-visible change); the `pipeline` subcommand folds into I9 as its facade rather than standalone work.
- I4 radicalized (user): drop PNG/raster support entirely — no optional raster pack; SVG→PNG delegated to external CLI tools. Doc-level proposal; implementation would be an intentional contract change (schema-id bumps, see I4 ledger).
- Effective ROI order after follow-ups: I7, I9 (+probe cache), I4-radical.

## Per-idea ledgers

### I1 jlink bundled runtime
00:08 Assess: bundle needs system Java 21+. jlink image ~35-60M would triple 17M download to remove that prereq. Goal is SMALL — this grows it.
00:08 DECISION: CUT (anti-goal on size; startup gain redundant with CDS).

### I2 CDS strategy
00:03 Found cds/=23M, only 3 .jsa (elk-layout, generic-graph, render); none for CLI/export plugins.
00:04 Launcher uses -XX:+AutoCreateSharedArchive → .jsa generated at first run, NOT shipped (tarball has 0 .jsa). Download unaffected; warmed on-disk = 42M.
00:05 CLI --version auto-created cli.jsa; warm startup 80ms. CDS warnings: "Skipping picocli/...: Old class has been linked" → archive slightly degraded.
00:07 Export plugins + probe path get .jsa on first use too (same mechanism). Coverage gap smaller than it looked.
STATUS: REFINING — remaining levers: (a) on-disk 23M cds is >jar size; single shared archive for all launchers could dedupe JDK classes across 4+ .jsa; (b) fix "old class linked" degradation; (c) JDK24+ AOTCache opportunistic (see I8).
00:19 Stored-jar bundle cds/ regenerates fine (AutoCreateSharedArchive). On-disk cds dedupe via shared base archive: high complexity (classpath-prefix rules), on-disk-only benefit.
00:19 DECISION: CUT as standalone; keep as honorable mention (fix 'Old class linked' warnings; JDK24+ AOTCache in launchers).

### I3 JVM flags
00:09 Plan: test -XX:TieredStopAtLevel=1 and -XX:+UseSerialGC on plugin stages (short-lived procs). Measure layout+render.
00:14 Measured: layout 0.522s / render 0.331s vs 0.516/0.325 trust-baseline → zero win. CDS already absorbs startup; rest is real work.
00:14 DECISION: CUT. (AOT-cache on JDK24+ remains the only flag-level future lever — see I8 note.)

### I4 Dependency slimming
00:03 lib/=18M ≈ whole 17M tarball. Targets: guava 3.0M (ELK dep), jackson-databind 1.9M, batik ~2.5M+xmlgraphics 0.67M (render), commons-io 0.6M, EMF 1.5M.
00:09 Plan: verify batik/commons-io actually used at runtime; check jackson-databind vs core-only.
00:14 batik confirmed used ONLY by SvgRasterizer (PNG rasterization). Family = 21 jars, 4.2M raw.
00:19 Compressed share: 1.74M of 8.85M stored-xz base (~20%). Proposal: optional "raster pack" add-on; base bundle SVG-only → ~7.1M.
Jackson/guava/EMF: load-bearing (contracts serialization, ELK). ProGuard shrink: CUT (reflection risk: jackson databind + EMF; contract-first product).
STATUS: TOP CANDIDATE as packaging split, scoped to rasterization.
Follow-up: measured removal surface — contracts RasterPolicy + RenderPolicy raster field; raster $defs in render-policy.schema.v1; "png" in render-result.schema.v3 artifact_kind enum; SvgRasterizer + Main + RenderInputValidator wiring in plugins/render; 1 mention in docs/agent-usage.md; 0 in README; no raster fixtures; no CLI code. Contained.
Follow-up DECISION (user): radicalize — drop PNG support altogether instead of an optional pack; external CLI tools (rsvg-convert, resvg, ImageMagick, Inkscape) cover SVG→PNG. Same −1.74M download as the pack split, minus the pack's build/maintenance surface. Implementing it is an intentional contract-family change: bump render-policy.schema v1→v2 and render-result.schema v3→v4, communicate in release notes (CalVer carries no compatibility signal).

### I5 Fewer JVM spawns per pipeline
00:07 Found: default run = 3 JVMs/stage (CLI + capability-probe + plugin). DEDIREN_TRUST_MANIFEST_CAPABILITIES=1 skips probe (documented, opt-in).
00:09 Measured 3-stage pipeline: 1.325s default vs 1.152s trust → probe costs ~175ms (13%).
00:09 Refinement: (a) probe-result cache keyed by manifest hash+mtime → probe cost only on first use, no trust tradeoff; (b) `dediren pipeline` one-shot command chaining project→layout→render in one CLI JVM: saves 2 CLI startups (~160ms) + intermediate envelope reserialization.
STATUS: REFINING — top candidate.
00:15 CLI has no one-shot pipeline command (6 subcommands; agent flow = 5 CLI invocations × (CLI+probe+plugin) JVMs ≈ 13-15 JVMs/diagram).
Refined proposal: (a) `dediren pipeline` one-shot: 5 CLI spawns → 1 (saves ~320ms+); (b) probe-result cache keyed by (manifest hash, launcher mtime): probe pays once ever, not per call (~50-80ms/stage), no trust tradeoff.
Estimated agent-flow wall: ~1.9s → ~1.0-1.1s. STATUS: TOP CANDIDATE (speed; core/cli code change).
Follow-up DECISION (user): demoted in favor of I9. Probe cache survives as standalone low-disruption work; `pipeline` subcommand becomes I9's facade instead of separate pre-work.

### I6 GraalVM native-image
00:09 Assess: startup ~10ms/binary, but 6 binaries; ELK+EMF heavy reflection = high build risk; GraalVM not in env (cannot measure); binaries ~20-40M each likely GROW bundle unless single multiplexed binary.
00:09 DECISION: CUT for this challenge (unmeasurable here, size regression risk, high effort). Note: future option as separate "fast bundle" flavor.

### I7 Distribution compression
00:09 Plan: measure tar.zst/tar.xz vs current 17M tar.gz on same content.
00:13 gzip-9 17.07M / xz-9 16.79M / zstd-19 16.72M on current jars → ~2%, CUT naive recompression.
00:14 PIVOT: repacked all 58 jars STORED (zip -0, 41M raw) → tar.xz-9 = 8.85M (-48%), tar.zst-19 = 9.78M.
00:17 Functional test: full pipeline on stored-jar bundle → status ok, version ok.
00:19 Warm pipeline 1.213s vs 1.325s baseline → stored jars ~8% FASTER (no per-class inflate).
Tradeoff: installed lib 18M→41M on disk. STATUS: TOP CANDIDATE (size+speed, zero code change — dist-tool only).

### I8 JDK24+ AOT cache (Leyden)
00:09 Assess: -XX:AOTCache supersedes CDS with profile-driven AOT; product floor is Java 21 → must be opportunistic version-gated in launchers. Cannot measure here (Temurin 21 installed).
00:09 DECISION: fold into I2/I3 as forward refinement; not standalone.

### I9 Single-JVM plugin host
00:08 Violates contract-first process-boundary architecture (plugins must stay separate processes; explicit CLAUDE.md rule).
00:08 DECISION: CUT on constraint grounds.
Follow-up: unconstrained re-assessment. Per-diagram cost today ≈ 13-15 JVM spawns; CLI boot 80ms measured, plugin boot ~150-250ms estimated (elk-layout .jsa 8.4M), validate-only floor 229ms measured. One-shot host estimate ~0.5-0.7s for 3-stage (vs 1.15s); daemon variant ~0.1-0.3s per subsequent diagram (5-10x ceiling). On-disk: one CDS archive instead of five (~15-20M less warmed footprint). Shared deduped lib/ proves classpath coexistence — no per-plugin classloader isolation strictly required for first-party set.
Follow-up: realistic design is hybrid (in-process fast path for bundled first-party plugins; stdio protocol retained for external plugins), preserving fault isolation and threat-model boundary where they matter.
Follow-up DECISION: promote to DARK HORSE — highest absolute speed ceiling, high effort, subsumes most of I5; unconstrained ROI order would be I7, I9, I4.
Follow-up DECISION (user): preferred over I5 for the actual roadmap — hybrid host (first-party plugins in-process, external plugins stay on the stdio protocol), with I5's probe cache kept as the only standalone I5 win.

### I10 Strip bundle content
00:08 fixtures 356K + docs 44K of 17M ≈ 2%; fixtures are load-bearing for agents (examples/policies).
00:08 DECISION: CUT (negligible ROI, hurts agent usability).

