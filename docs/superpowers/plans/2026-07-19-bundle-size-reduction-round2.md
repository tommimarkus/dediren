# Bundle Size Reduction — Round 2 Research (2026-07-19)

> **Status 2026-07-19 (same day):** partially adopted. Landed on `main`: V1b
> attribute strip (line numbers kept), the dead-resource injar filters, the
> `about_*.html` relocation widening in `MergedJarPostProcessor`,
> deterministic in-process gzip −9 archiving, and the smoke ceiling re-tuned
> to 5,300,000 bytes. Declined for now: V2 (bundled stack traces keep line
> numbers). **Also adopted the same day, on explicit maintainer go-ahead: the
> `.tar.xz` format switch** (3,164,352 bytes as built; extraction is `tar -xf` — every
> supported Windows 11, Linux and macOS auto-detects it; the residual gap is
> Windows Server 2022-era `tar.exe`, which needs an xz on PATH). The smoke
> ceiling now sits at 3,400,000 bytes, under the ~3.49 MB
> attribute-strip-inert plateau. Everything below this box is the research
> record as written.
>
> Research method note: every "pass" row below was
> validated with the real `DistTool smoke` (validate, layout, validate-layout,
> render build, OEF + XMI export, MCP stdio, logging/stderr gates) built from
> this working tree. Baseline is the shipped v2026.07.21 asset
> (5,459,520 bytes `.tar.gz`; local reproduction of the same config:
> 5,459,579 bytes — the delta is tar metadata timestamps).
>
> Round 1 (2026-07-15/16, shipped in v2026.07.19) took the bundle from
> 15,150,460 to ~5.46 MB. This round re-examined what that work left behind,
> plus what changed since (AppCDS removal, `.tar.gz` retention).

## Result Summary

Staying inside `.tar.gz`, a PoC-validated combination reaches
**4,259,759 bytes (−22.0% vs shipped)** with smoke green. Compounded with the
round-1 recorded-but-unadopted `tar.xz` switch, the same content is
**2,671,300 bytes (−51.1% vs shipped; −82.4% vs the original 15.15 MB)**.

| Variant | Archive bytes | Merged-jar bytes | Smoke |
| --- | --- | --- | --- |
| Shipped v2026.07.21 (`tar -czf` = gzip −6) | 5,459,520 | 18,674,294 | released |
| V0: unchanged config rebuilt | 5,459,579 | 18,674,294 | pass |
| V1: explicit `-keepattributes` list, `-dontobfuscate` kept | 5,459,573 | 18,674,294 (byte-identical) | pass |
| V1b: attribute strip via obfuscation phase, all names + line numbers kept | 5,071,855 | 17,542,513 | **pass** |
| V2 = V1b also dropping `LineNumberTable`/`SourceFile` | 4,345,305 | 16,300,924 | **pass** |
| V3 = V2 + picocli shrunk (keep-whole removed) | 4,301,342 | 16,144,612 | **FAIL** — CLI dead on arrival |
| V6 = V2 + dead-resource injar filters | **4,259,759** | 15,830,230 | **pass** |
| V5 = V6 + `-optimize` with all names kept | (see Dead Ends) | (see Dead Ends) | (see Dead Ends) |
| **Adopted = V1b + filters + gzip −9** | **4,938,533** | 17,073,543 | **pass** (full suite + smoke + bench) |

The adopted combination keeps stack-trace line numbers (V1b, not V2) per the
maintainer's call: −9.5% vs shipped, with `xz -9e` on the same content at
3,164,580 bytes (−42.0% vs shipped) — the format switch was subsequently taken
(see the status box; the released asset is the `.tar.xz`).

Outer-compression matrix (identical V6 content, byte-for-byte):

| Compressor | Bytes | vs shipped |
| --- | --- | --- |
| gzip −6 (today's `tar -czf`) | 4,259,759 | −22.0% |
| gzip −9 | 4,232,590 | −22.5% |
| zstd −19 `--long=27` | 3,019,894 | −44.7% |
| **xz −9e** | **2,671,300** | **−51.1%** |

On the *shipped* content the same matrix gives gzip −9 5,427,555 / zstd
4,011,518 / xz 3,494,160 — i.e. attribute stripping and the format switch
compound rather than overlap.

Runtime: same-machine `DistTool bench` medians (5 runs), shipped config vs
adopted config — `--version` 69 → 68 ms, `layout` 275 → 284 ms, `build`
(render) 326 → 328 ms: within noise. Class content is unchanged except
attributes, so no mechanism for a regression exists beyond class-file
parsing, which only gets cheaper.

## Finding 1 — `-keepattributes *` has been inert all along

ProGuard strips attributes **only during the obfuscation phase**;
`-dontobfuscate` skips that phase entirely. The shipped config therefore ships
every debug attribute of every surviving class, and its `-keepattributes *`
line does nothing (proved by V1: replacing `*` with a minimal list changed not
one jar byte while `-dontobfuscate` was present).

The phase can be enabled with **zero renaming** by pinning every name:

```
-keep,allowshrinking class ** { *; }   # i.e. -keepnames class ** { *; }
-keepattributes Exceptions,Signature,InnerClasses,EnclosingMethod,Deprecated,\
Synthetic,SourceFile,LineNumberTable,MethodParameters,*Annotation*,Record,\
PermittedSubclasses,NestHost,NestMembers
```

Attribute census of the shipped merged jar (17,267,154 class bytes, 4,062
classes; measured with a class-file parser, see Reproduction):

| Attribute | Bytes | Share | Fate |
| --- | --- | --- | --- |
| `LocalVariableTable` | 1,169,916 | 6.7% | drop (V1b) — debugger-only |
| `LineNumberTable` | 995,880 | 5.7% | drop in V2 only — stack-trace lines |
| `StackMapTable` | 424,401 | 2.4% | structural, not `-keepattributes`-governed, always kept |
| `LocalVariableTypeTable` | 229,174 | 1.3% | drop (V1b) |
| `MethodParameters` | 113,955 | 0.6% | **keep** — Jackson 3 creator/parameter-name binding |
| `InnerClasses` / `Signature` / annotations / `Record` / `Nest*` / `PermittedSubclasses` | ~250 K | 1.4% | **keep** — reflection, generics, nestmate access, sealed checks |
| `SourceFile` | 32,400 | 0.1% | drop in V2 only |

- **V1b (conservative): −387,724 bytes compressed (−7.1%).** Drops only the
  debugger local-variable tables. Stack traces, reflection, annotations,
  generics all intact. The cost is "no local-variable names when attaching a
  debugger to the *bundle*" — module jars used in dev are untouched.
- **V2 (aggressive): −1,114,274 bytes cumulative (−20.4%).** Additionally
  drops line numbers and source-file names: every bundled stack frame becomes
  `Class.method(Unknown Source)`. Line-number data compresses badly, which is
  why 1.03 MB uncompressed removes 727 KB compressed. Attribute stripping is
  global — first-party frames lose lines too (splitting first/third party
  would need a second ProGuard pass; ~30 KB of the win is first-party lines).
  The only production path that prints a raw trace is the unexpected-error
  handler in `cli` `Main` (`printStackTrace` on the cause); envelopes carry
  diagnostics, not traces. Class and method names are never obfuscated, so
  crash reports stay attributable to a file, just not to a line.
- Cross-checks: V0→V1b jar delta (1,131,781) is *below* raw LVT+LVTT
  (1,399,090) because `-keepparameternames` retains parameter LVT slices on
  kept methods; V1b→V2 delta (1,241,589) *exceeds* raw LNT+SourceFile
  (1,028,280) because dropping them also frees `"*.java"` and attribute-name
  constant-pool strings. The ledger is internally consistent.
- Risk class: same as round 1's shrink itself — smoke breadth. No reflective
  surface changes (names untouched); the novel bit is enabling the obfuscation
  phase at all. `Record`, `NestHost`/`NestMembers`, `PermittedSubclasses`,
  `Signature`, `MethodParameters` and annotation attributes must stay listed —
  dropping any of those breaks records/private-nestmate access/sealed
  hierarchies/Jackson generics at runtime, not at build time.

## Finding 2 — the outer gzip runs at level 6

`DistTool build` archives with `tar -czf`, which is gzip −6. Recompressing
identical content at −9 saves 32 KB (0.6%) for free; zopfli (not installed on
this machine, typically another ~2–3% over −9 at high CPU cost) could be a
release-time nicety. Both are noise next to the format switch: on V6 content
`xz -9e` is another −1.56 MB below gzip −9.

## Finding 3 — ~460 KB of provably-dead resources ride along

Measured marginal effect (V2→V6): **−85,546 bytes compressed**. Families, with
the evidence that they are dead:

- **networknt locale messages** — `jsv-messages_*.properties`, 231,152 bytes
  / 30 files (ru/uk/th/fa/ar/he/ja/ko are 9–19 KB each). Validation messages
  fall back to the base `jsv-messages.properties` (kept) via `ResourceBundle`
  when locale variants are absent; envelope diagnostics are English.
- **`ucd/**` Unicode data** — 181,277 bytes, feeds the `idn-hostname`/
  `idn-email` format validators. Round 1 kept it citing "user-authored schemas
  could exercise format keywords". Verified this round: **no `"format"`
  keyword exists anywhere in `schemas/` or in the MCP `ToolSchemas`**, and
  `schema-cache`'s only consumers are the two export engines' XSD/xmllint
  validation — no path feeds a non-dediren JSON Schema into networknt. The idn
  validators read `ucd/` lazily on first idn-format validation, which is
  unreachable.
- **Stale meta-schemas** — `draft-04/06/07` + `draft/2019-09` (~19 KB); every
  dediren schema family is 2020-12.
- **Eclipse/OSGi/GraalVM leftovers** — `**/package.html`, `OSGI-INF/**`,
  `META-INF/native-image/**`, `META-INF/proguard/**` (guava's Android rules),
  `about_*.html`, ELK `docs/*.md` (~25 KB combined).

Adoption note: `about_*.html` (xtext) is Eclipse branding/notice content like
`about.html`, which `MergedJarPostProcessor` *relocates* rather than drops.
The clean adoption is widening its licence predicate from `about.html` to
`about*.html` so the file lands under `META-INF/third-party/` too, instead of
adding it to the drop filter. The rest is data, not licence text — no notices
impact.

(Also observed, accepted: `docs/agent-usage.md` ships twice — the bundle file
plus the jar-embedded copy `GuideCatalog` serves over MCP. ~13 KB compressed.)

## Decompression Latency (the 5000 ms install budget)

Question raised at adoption time: the whole agent install (download +
extract) has a ~5000 ms budget for the MCP flow — does xz's slower
decompression eat what the smaller download saves? Measured on the identical
V6-content artifacts (median of 5, warm cache):

| Variant | Bytes | `tar -xf` wall time |
| --- | --- | --- |
| gzip −9 | 4,232,590 | 34 ms |
| xz −9e | 2,671,300 | 68 ms |
| zstd −19 | 3,019,894 | 14 ms |

xz trades **+34 ms CPU for −1,561,290 bytes of download**: breakeven is
~46 MB/s sustained download throughput. Below that, xz is net *faster*
end-to-end; above it, it loses by ≤ 35 ms. Worst plausible case at 5 MB/s is
~0.9 s total with gzip vs ~0.6 s with xz — the budget holds by an order of
magnitude either way, so latency does not decide the format question; the
Windows-10 `tar.exe` gap and doc churn do. zstd is the only variant that is
both smaller than gzip *and* faster to extract (2.4×), at the cost of the
narrowest extractor availability of the three.

## Where the Remaining Bytes Live (what non-ProGuard avenues could buy)

Per-component compressed cost of the V6 artifact (each component's entries
concatenated and compressed alone — a slight overestimate vs the solid
archive, but the right ranking):

| Component | gzip −9 est | xz est | Raw bytes | Movability |
| --- | --- | --- | --- | --- |
| jackson 3 core+databind | 1,196,464 | 774,504 | 4,543,425 | pinned: contract-first product + MCP SDK requires databind |
| ELK (5 jars) | 742,256 | 489,192 | 2,632,054 | pinned: the product's engine (rectpacking incl. — real feature) |
| EMF common+ecore | 549,217 | 364,100 | 2,038,617 | pinned by ELK: `ElkGraph` is EMF-modeled |
| first-party (all modules) | 364,919 | 240,892 | 1,329,151 | the product |
| reactor-core | 302,077 | 201,284 | 1,200,602 | blocked upstream until MCP SDK 4.x; or MCP add-on split |
| picocli | 239,949 | 166,144 | 820,338 | replaceable only by hand-rolling CLI parsing (see below) |
| networknt validator | 182,442 | 119,552 | 680,598 | pinned even if dediren validation were code-generated: the MCP SDK loads it via `JsonSchemaValidatorSupplier` for tool-input validation |
| MCP SDK | 180,764 | 109,388 | 857,633 | product surface (`dediren mcp`); only movable via add-on split |
| guava | 123,941 | 88,008 | 498,709 | pinned by ELK |
| slf4j, jackson-annotations, itu, misc | ~110 K | ~88 K | ~443 K | noise |

Reading of the table:

- **Every dependency-level avenue is smaller than the format switch.** The
  MCP add-on split (mcp + reactor ≈ 480 KB gz est) and a picocli replacement
  (≈ 240 KB gz est, at the cost of hand-rolling help/subcommand/converter
  behavior in the deliberately-thin `cli`) together move less than `xz -9e`
  does on its own (−1.56 MB below gzip −9).
- **The top three components (≈ 2.5 MB gz est combined) are architectural**,
  not packaging: dropping any of them means changing what the product is
  (JSON contracts, ELK layout, ELK's EMF graph model). Round 1's "deeper
  databind shrink" deferral is the only chip at them, and it remains
  high-risk/low-yield.
- **Replacing networknt with build-time generated validators would not remove
  networknt** — the MCP SDK resolves a `JsonSchemaValidatorSupplier` through
  `ServiceLoader` for tool-input validation, so the library stays on the
  runtime path regardless of how dediren validates its own families.

## Dead Ends Verified This Round

- **picocli shrink** (reopened because round 1's stated reason to keep it
  whole — AppCDS dump-time warnings — was removed with AppCDS in 6ac35b3):
  still not shrinkable, and the failure is now *functional*, not cosmetic. The
  first `CommandLine` construction dies with
  `InitializationException: picocli.CommandLine$AutoHelpMixin ... is not a
  command` — shrinking strips `AutoHelpMixin`'s reflectively-read `@Option`
  fields. Potential was only −43,963 bytes compressed. The keep-whole rule and
  its rewritten rationale comment are correct as shipped.
- **ProGuard optimization with names kept** (V5:
  `-keep,allowshrinking,allowoptimization class ** { *; }`, `-dontoptimize`
  removed): abandoned on build cost alone — the optimization pass was still
  running after ~25 minutes wall-clock against ~25 seconds for the whole
  shrink-only pass, so it was stopped before producing a measurable artifact.
  Even a favorable size result could not justify a ~60× dist-build slowdown
  in CI, and the correctness questions (inlining under `allowoptimization`
  vs the ServiceLoader unions) were never reached.
- **ELK `alg.rectpacking`** (155,534 jar bytes): real product surface —
  `ElkPackedOptions` dispatches `"org.eclipse.elk.rectpacking"` by string id;
  no bytecode reference exists (the pom already warns `dependency:analyze`
  about exactly this). Not removable.
- **Fonts**: none ship — Liberation Sans is `engines/render` *test* resources
  only.
- **Reactor / MCP SDK**: reactor-core 3.7.0 (~1.35 MB of the jar) is a
  non-optional compile dependency of `mcp-core` for the stdio server through
  SDK 2.0.x, the finished-but-unreleased 2.0.1, 2.1.0 planning, and the
  spec-driven 3.x line. The Reactor-free "unified Virtual-Threads-friendly
  API" is parked in the SDK's **4.x Planning** milestone
  ([modelcontextprotocol/java-sdk#778](https://github.com/modelcontextprotocol/java-sdk/issues/778),
  filed by the Spring team, no prototype or PR as of 2026-07-19; see also
  discussion #321 — Reactor being mandatory "was never intentional").
  Until SDK 4.x exists, only round 1's deferred **MCP add-on split** (a
  product-surface change, est. ~0.6 MB compressed) could move this weight.
- **jlink / native-image**: unchanged from round 1 — no JRE ships, nothing to
  cut; native-image would grow the asset.

## Still Deferred (unchanged from round 1)

- Deeper `tools.jackson.databind` shrink (4.30 MB / 815 classes in the jar —
  `deser` 1.32 MB, `ser` 0.81 MB, kept-whole `ext` 0.41 MB): highest
  reflection risk, unbounded keep-rule whack-a-mole. Revisit only if a hard
  size target appears.
- MCP add-on split (see Reactor above).
- Dropping fixtures/schemas/docs (~60 KB compressed): agent-facing product
  surface.

## Suggested Adoption Order (if pursued)

1. **V1b** — one `bundle-shrink.pro` edit, −7.1%, no observable behavior
   change. Reword the file's header comment ("nothing is altered or renamed" →
   "nothing is renamed; debugger local-variable tables are stripped").
2. **Resource filters** — with the `MergedJarPostProcessor` `about*`
   relocation tweak; notices output is unchanged.
3. **gzip −9** in `DistTool build` (pipe `tar -c` through `gzip -9`).
4. **V2 (line numbers)** — a product-posture decision, not a technical one:
   another −13% for line-less bundled stack traces. `docs/threat-model.md`
   release-row wording is unaffected either way (still shrink-only semantics,
   no renaming).
5. **tar.xz** — the round-1 follow-up, now worth 2.67 MB. Same coordinated
   change set as recorded there (DistTool build/smoke/bench, release.yml,
   README, agent-usage).
6. Keep the 7,000,000-byte smoke ceiling until adoption lands, then lower it
   (e.g. to ~5.5 MB for V1b+filters, ~4.5 MB if V2 is taken) so the
   shrink-regression tripwire stays tight. Re-run `-Pdist-bench` after.

## Reproduction

- Builds: `MAVEN_OPTS="-Djava.io.tmpdir=$TMPDIR" ./mvnw -q -pl dist-tool -am
  -DskipTests -Pdist-smoke verify` (the `MAVEN_OPTS` part matters only under a
  sandboxed `/tmp`; `exec:java` runs DistTool inside the Maven JVM, which the
  surefire-only `sandbox-tmpdir` profile does not cover).
- V1b/V2 `.pro` delta: replace `-dontobfuscate` with
  `-keepnames class ** { *; }` and replace `-keepattributes *` with the
  explicit list above (V2: without `SourceFile,LineNumberTable`).
- V6 `INJAR_FILTER` additions:
  `!jsv-messages_*.properties,!ucd/**,!draft-04/**,!draft-06/**,!draft-07/**,`
  `!draft/2019-09/**,!**/package.html,!OSGI-INF/**,!META-INF/native-image/**,`
  `!META-INF/proguard/**,!about_*.html,!docs/**`
- Attribute census: 60-line Python class-file walker summing
  `attribute_length + 6` per attribute name, recursing into `Code`
  (constant pool starts at offset 8; `Code` sub-attributes start after
  `off + 14 + code_length` plus the exception table).
- Compression matrix: `gunzip` the built archive, recompress the identical
  tar with `gzip -9` / `xz -9e` / `zstd -19 --long=27`.
