# Bundle Size Reduction Research (2026-07-15)

> **Status 2026-07-16:** adopted through "shrink + merge + strip + no-YAML + STORED repack,
> `.tar.gz` retained" — implemented by `2026-07-16-bundle-shrink-implementation.md`
> (shipped archive: 5,464,991 bytes vs the 15,150,460-byte baseline). The tar.xz format
> switch (measured 3.50 MB) remains the unadopted follow-up.

Goal: shrink the release asset `dediren-agent-bundle-<version>.tar.gz` from
15.1 MB to under 5 MB. Research with PoCs; nothing here is shipped yet. All
PoC variants below were validated with the real `DistTool smoke` (layout,
render, OEF + XMI export, MCP stdio, CDS creation, quiet-stderr/logging
gates) and timed with `DistTool bench`.

## Result Summary

A PoC-validated combination reaches **3.50 MB** (77% below baseline, 30%
under target) and is *faster* at runtime, not slower:

1. ProGuard **shrink-only** pass (no optimization, no obfuscation) over all
   46 jars, merged into one jar, with keep rules for every
   reflective/ServiceLoader surface.
2. Strip non-runtime resources (Eclipse UI icons, `.ecore`/`.xsd` model
   sources, xtext `._trace`, `.melk`, OSGi metadata, `META-INF/maven`).
3. Drop the YAML stack (`jackson-dataformat-yaml`, `snakeyaml-engine`) —
   transitives of json-schema-validator; no production code path feeds YAML.
4. Repack the merged jar with **STORED** entries so the outer compressor
   sees raw class bytes (per-entry deflate blocks solid compression).
5. Compress the tar with **xz -9e** instead of gzip.

Measured sizes (full bundle including schemas/fixtures/docs/launcher):

| Variant | Bytes | Size | Smoke |
| --- | --- | --- | --- |
| Baseline: 46 deflated jars, tar.gz (today's pipeline) | 15,150,460 | 14.4 MiB | pass |
| 46 jars repacked STORED, tar.gz -9 | 11,521,262 | 11.0 MiB | not run (compression-only) |
| 46 jars repacked STORED, tar.xz -9e | 7,916,496 | 7.6 MiB | not run (compression-only) |
| Shrunk merged jar (conservative keeps), tar.gz | 10,160,794 | 9.7 MiB | **pass** |
| Shrunk merged jar (final keeps), deflated, tar.gz | 7,177,944 | 6.8 MiB | **pass** |
| Shrunk merged jar (final keeps), STORED, tar.gz | 5,430,405 | 5.2 MiB | **pass** |
| Shrunk merged jar (final keeps), STORED, tar.zst -19 --long | 4,011,925 | 3.8 MiB | same content as tar.gz row |
| **Shrunk merged jar (final keeps), STORED, tar.xz -9e** | **3,497,000** | **3.3 MiB** | same content as tar.gz row |

If the `.tar.gz` format must stay, the floor found is 5.43 MB (just above
target). zstd or xz both clear the target; xz wins and is the safer
availability bet (GNU tar `-xJf` everywhere, bsdtar/libarchive on macOS and
Windows auto-detect).

Runtime (DistTool bench medians, 5 runs, warm CDS; same machine):

| Command | Baseline | Shrunk STORED bundle |
| --- | --- | --- |
| `dediren --version` | 77 ms | 63 ms |
| `dediren layout` (per-stage) | 340 ms | 224 ms |
| `dediren build` (render) | 397 ms | 259 ms |

The offered size/perf trade was not needed: one stored jar beats 46 deflated
jars on classpath scanning and inflation, and the smaller class set shrinks
CDS training. First-run CDS auto-creation continues to work (smoke asserts
it), and the `.jsa` never ships (`--exclude=cds` already guarantees that).

## Bundle Anatomy (why it is 15 MB)

- lib/ carries 16.4 MB of jars, of which only ~0.7 MB is first-party code.
- Already-deflated jars defeat the outer gzip: recompressing the baseline
  tar with xz saves under 1%.
- Heaviest third-party members (deflated): guava 3.0 MB, elk.core 2.1 MB,
  jackson-databind 1.9 MB, reactor-core 1.8 MB (MCP SDK transitive),
  elk.alg.layered 1.2 MB, emf.ecore 1.2 MB, mcp-core 0.6 MB,
  jackson-core 0.6 MB, json-schema-validator 0.6 MB.
- Dead weight found: 2.8 MB of Eclipse UI icons (`images/**` in elk.core +
  elk.alg.layered), `.ecore`/`.xsd` model sources (EMF initializes packages
  from generated code in standalone use), xtext `._trace` files, `.melk`
  sources, OSGi `plugin.xml`, and the entire MCP *client* half of mcp-core.
- Reachability shrinking is very effective here: guava 3.0 MB → 0.6 MB,
  reactor 4 MB → 1.4 MB (uncompressed), MCP client code removed entirely.

Final merged-jar composition (uncompressed, top entries): jackson-databind
4.2 MB, ELK 3.0 MB, EMF 2.4 MB, reactor 1.4 MB, picocli 0.9 MB (kept whole,
see below), jackson-core 0.9 MB, networknt 0.7 MB, MCP spec 0.7 MB, guava
0.6 MB; total 17.4 MB (from 37.4 MB).

## Keep Rules That Matter (learned by breaking them)

- `META-INF/services` files collide across merged jars; the first-wins merge
  silently dropped ELK's `ILayoutMetaDataProvider` registrations →
  `DEDIREN_ELK_LAYOUT_FAILED: Layout algorithm 'org.eclipse.elk.layered' not
  found`. Service files must be **unioned** (shade's
  `ServicesResourceTransformer` semantic), filtered to surviving classes.
  The MCP SDK also resolves its JSON mapper and schema validator through
  ServiceLoader (`McpJsonMapperSupplier`, `JsonSchemaValidatorSupplier`) —
  losing those breaks `dediren mcp` at first tool call.
- Shrinking picocli produces `[warning][cds]` stderr noise at CDS dump time
  ("super class picocli/CommandLine$ParameterException is excluded"),
  which fails the bundle's quiet-stderr contract. Keep `picocli.**` whole
  (~0.1 MB compressed cost).
- Kept whole as reflective surfaces: `dev.dediren.**`, `picocli.**`,
  `org.slf4j.**`, jackson annotations, `tools.jackson.databind.ext.**`,
  MCP `spec`/`json` packages (databind introspects them), ELK
  `IDataObject` impls and provider impls, enum `values`/`valueOf` members.
- `ucd/**` (177 KB, json-schema-validator's Unicode data for idn-* format
  validators) was deliberately kept: user-authored schemas could exercise
  format keywords the smoke fixtures never touch.
- `META-INF/versions/**` (multi-release class variants) was stripped: JDK
  17/21-optimized jackson-core paths are lost — base implementations are
  used. Bench shows no net regression.

## Residual Risks / What Adoption Must Address

- **Reflection blind spots.** Shrink-only + smoke is strong but not proof:
  user schemas can reach networknt validators, and exotic ELK options can
  reach code paths the fixtures never load. Mitigations: broaden the packaged
  smoke to sweep every fixture family through the bundled CLI, and keep the
  keep-rule file conservative (shrink the big five: guava/ELK/EMF/reactor/
  jackson-core+databind internals; keep the reflective rest whole).
- **Licence/notices rework.** THIRD-PARTY-NOTICES.md currently states jars
  are redistributed "in unmodified object form" with embedded licence files
  authoritative. A shrunk merged jar is a modified redistribution: the
  notices generator must switch wording, preserve all embedded
  LICENSE/NOTICE/about.html texts (colliding names currently keep only one),
  and keep the EPL source-availability section. Run
  `souroldgeezer-audit:ip-hygiene` at implementation time.
- **Toolchain surface.** ProGuard 7.7 (+ transitives incl. kotlin-stdlib)
  becomes a pinned build-time dependency; `docs/threat-model.md` release
  rows must cover it. The SBOM stays pom-accurate at component level but the
  shipped binary no longer byte-matches upstream jars (Grype scanning via
  SBOM is unaffected; provenance attestation unaffected).
- **Format change ripples** (if tar.xz is adopted): DistTool `build` (tar
  invocation) and `smoke`/`bench` (`tar -xzf` hardcoded), release.yml
  archive-glob + verify steps, README install snippet, and
  `docs/agent-usage.md` — the user-facing-docs-move-together rule applies.
- **Dist pipeline shape.** appassembler still generates the 46-jar
  CLASSPATH; DistTool would run shrink+merge+repack after staging and
  rewrite the CLASSPATH line to the single jar (PoC did exactly this and all
  launcher-script assertions still pass). `verifyPackagedLib`,
  `DistHermeticityTest`, and the notices generator need matching updates.
  CI dist-smoke wall-clock grows by the ProGuard pass (~25 s on this
  machine).

## Options Considered and Not Pursued

- **jlink/custom runtime, native-image**: the bundle ships no JRE, so there
  is nothing to cut there; native-image would grow the asset.
- **Deeper jackson-databind shrink** (still 4.2 MB uncompressed): possible
  next ~0.3–0.5 MB compressed, but highest reflection risk; unnecessary at
  3.5 MB.
- **Splitting MCP into an optional add-on bundle** (reactor + mcp jars):
  saves ~0.7 MB compressed in the core bundle but changes the product
  surface; unnecessary at 3.5 MB.
- **Dropping fixtures/schemas/docs**: ~60 KB compressed combined; they are
  the agent-facing product surface. Not worth it.
- **zopfli/brotli on jars, pack200**: pack200 was removed from the JDK;
  the STORED+xz approach subsumes the rest.

## Reproduction

PoC scripts (session scratchpad, ephemeral): `repack_stored.py` (STORED
repack), `poc-shrink/run-shrink*.sh` (injar list + ProGuard invocation),
`poc-shrink/fix_services.py` (service-file union), smoke/bench via
`java -cp dist-tool/target/classes:contracts/target/classes:<jackson jars>
dev.dediren.tools.dist.DistTool smoke|bench --root . --version <v>
--archive <poc.tar.gz>`.

Injar resource filter:

```
!META-INF/*.SF,!META-INF/*.RSA,!META-INF/*.DSA,!module-info.class,
!META-INF/versions/**,!META-INF/maven/**,!**.melk,!**._trace,!images/**,
!model/**,!schema/**,!plugin.xml,!plugin.properties,!about.html,
!about_files/**,!.api_description,!.options,!profile.list,
!systembundle.properties
```

Final ProGuard configuration (shrink-only):

```
-dontoptimize
-dontobfuscate
-keepattributes *
-keepparameternames
-keepdirectories META-INF/services
-libraryjars <java.home>/jmods(!**.jar;!module-info.class)

-keep class dev.dediren.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class tools.jackson.databind.ext.** { *; }
-keep class io.modelcontextprotocol.spec.** { *; }
-keep class io.modelcontextprotocol.json.** { *; }
-keep class org.slf4j.** { *; }
-keep class picocli.** { *; }

-keep class * implements org.eclipse.elk.core.data.ILayoutMetaDataProvider { *; }
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }
-keep class * implements io.modelcontextprotocol.json.McpJsonMapperSupplier { *; }
-keep class * implements io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier { *; }
-keep class * implements tools.jackson.core.TokenStreamFactory { *; }
-keep class * extends tools.jackson.databind.ObjectMapper { *; }
-keep class * implements org.eclipse.elk.core.util.IDataObject { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-dontwarn **
-dontnote **
```

YAML jars (`jackson-dataformat-yaml`, `snakeyaml-engine`) are excluded from
the injar list; `-dontwarn` covers networknt's dangling references, which
only load on YAML schema input that no dediren code path produces.

## Suggested Implementation Order (if adopted)

1. Wire shrink+merge+services-union+STORED-repack into DistTool `build`
   after staging, rewriting the launcher CLASSPATH line; keep `.tar.gz`
   initially (5.4 MB, no consumer-facing format change; every smoke gate
   already passes in this shape).
2. Rework notices generation + embedded licence handling for modified
   redistribution (ip-hygiene audit) and update `docs/threat-model.md`.
3. Broaden dist-smoke fixture coverage (cheap insurance against reflection
   blind spots).
4. Switch the archive to `.tar.xz` (3.5 MB) with the coordinated
   README/agent-usage/release.yml/DistTool updates.
