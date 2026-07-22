# Software-Design Deep Review — 2026-07-22

Whole-repository design review at commit `4c979c7` (post-roadmap, post-remediation,
post-"validator story"). Six parallel module reviews (core+cli+ir, mcp-server,
schema-cache+exports, render, elk-layout, contracts+notation cores) plus an
independent graph-layer pom audit and a history-layer churn pass; every block
and top-warn finding was re-verified against the cited source lines by the
review lead.

- **Mode:** Review (findings only) · **Extensions:** java
- **Reference:** souroldgeezer-design software-design core + smell-catalog/smell-cards
- **Layers used:** `static`, `graph`, `history`. **Absent:** `runtime`, `human` —
  every hang/race/performance claim below is static inference and says so.
- **Recorded §12 debt excluded** (ir↔contracts mappers, `dev.dediren.plugins.*`
  package names, stub-XSD lanes, SpotBugs suppressions, port-count sizing,
  test-Main envelope boilerplate, LA-CODE-DUP-2).

## Verdict

The macro-architecture is in excellent shape: **every internal compile edge in
all 17 module poms matches the §2 dependency table exactly** — no cycles, no
undocumented edges, engines clean of `core`, mcp-server confined to its three
edges, the semantics trio independent at production scope. EngineWiring,
EngineDispatch's failure taxonomy, the notation-core export surface, and the
mcp tool lane's envelope discipline all verify as designed.

The debt is one level down, and it clusters into four systemic themes rather
than forty unrelated nits:

1. **Adapter-lane divergence (1 block, several warns).** Core's command-driver
   surface is incomplete, so each adapter re-implements slices of command
   policy: cli owns the whole `verify` verdict, cli+mcp each decide what
   `validate` means, cli owns the query vocabulary, the whole-model OEF export
   bypasses dispatch, and source legality differs between the router lane and
   the direct-XMI lane. (This theme is exactly what the existing
   `plan/cli-mcp-parity` branch appears aimed at; these findings are its
   worklist.)
2. **Comment-pinned cross-module invariants (~10 findings).** Where the module
   graph forbids a code edge, semantic twins are being kept equal by comments:
   renderer geometry constants in `LayoutQuality`, UML member-line formats in
   sizing vs drawing, sequence well-formedness vs constraint predicates, the
   confinement algorithm, engine-id and env-name constants, role maps. The repo
   already owns the two correct patterns — lift to a shared owner
   (`ir.quality.LayoutTolerances`) or pin with a dist-tool consistency test
   (`ARCHIMATE_LABEL_ICON_RESERVE`) — they just aren't applied uniformly.
3. **Re-forming god classes.** `ElkLayoutEngine` (1609 LOC, ~8
   responsibilities), `OefExportEngine` (1004 LOC, ~6, with intra-file
   duplication already visible), `Uml.java` (863 LOC, ~7). The render and
   uml-xmi splits are the in-repo precedent for the fix.
4. **Error-contract gaps at the edges.** The envelope taxonomy is excellent at
   the dispatch boundary but leaks at the rims: build-artifact write failure
   aborts the whole build with no `BuildResult`; `status`/`verify` can die with
   a raw stack trace; infra failures masquerade as user-model diagnostics;
   schema-cache collapses retryable saturation into terminal-looking advice;
   the curl child has no Java-side lifetime owner.

## Block

**[SD-B-4] cli/src/main/java/dev/dediren/cli/Main.java:376** · static ·
*Adapter owns the verify domain verdict.* `VerifyCommand.call` computes the
model hash (`CanonicalJson.sha256(...)`), authors both remediation texts, and
decides envelope status + exit (stale→ERROR/INPUT_ERROR, unstamped→WARNING/OK)
entirely inside the adapter, while every other command receives its verdict
from core. This is the unregistered part of cli's growth from the 446 LOC the
guidelines record to 854, and it makes `verify` un-exposable over MCP without
duplicating policy. **Action:** move verdict assembly into a core driver
(`CoreCommands.verifyCommand` returning the diagnostics/status); cli keeps
parse/print. *(Verified by review lead.)*

## Warn

### Adapter-lane divergence

- **[SD-S-1] uml/src/main/java/dev/dediren/uml/Uml.java:286** · graph · The
  endpoint-must-be-selected rule exists twice with different definitions: the
  router lane runs `SemanticsRouterEngine`'s strictly-stricter base check
  (semantics-graph …/SemanticsRouterEngine.java:199) before `Uml`'s four
  per-view-kind loops (whose component/deployment widening is unreachable dead
  permissiveness there), while `XmiExportEngine.java:75` calls
  `Uml.validateSource` directly with no base check — same model, two legality
  outcomes by entry lane. **Action:** declare the rule once (router owns the
  base check; a `NotationSemantics` hook if a notation must widen), delete the
  shadowed loops, add a lane-agreement test. *(Verified.)*
- **[SD-S-2] cli/src/main/java/dev/dediren/cli/Main.java:299** · static · Query
  vocabulary (`dependents`/`orphans`/`view-coverage`) declared three times (cli
  guard, cli switch, core `ModelQuery`); cli also owns the unknown-node-id
  check. **Action:** core `queryCommand` driver owns kind vocabulary + id check.
- **[SD-S-2] mcp-server/src/main/java/dev/dediren/mcp/DedirenTools.java:40** ·
  static · `SEMANTICS_ENGINE = "generic-graph"` privately re-declares core
  `BuildCommand`'s private default engine id; nothing pins them (contrast
  `EMIT_KINDS`, which core made public and `ToolSchemasTest` pins). A renamed
  default surfaces only as runtime `DEDIREN_PLUGIN_UNKNOWN` on the MCP lane.
  **Action:** publish the constant from core; adapter references it.
- **[SD-S-5] core/src/main/java/dev/dediren/core/commands/BuildCommand.java:245**
  · graph · The whole-model OEF aggregate calls `exporter.exportModel(...)`
  directly, bypassing `EngineDispatch.dispatchInMemory`; an unexpected
  `RuntimeException` skips the published `DEDIREN_ENGINE_FAILED` mapping and
  cli's catch set → raw stack trace, empty stdout. **Action:** route through
  `dispatchInMemory` like every other engine call. *(Verified.)*
- **[SD-S-2] mcp-server/src/main/java/dev/dediren/mcp/WorkspacePaths.java:47** ·
  graph · The `--root` confinement algorithm is duplicated line-for-line in
  core `SourceValidator.resolveFragmentReadPath` (its javadoc admits it: "core
  cannot depend on the mcp module") and the copies have already diverged on the
  normalize-before-walk step core's own doc calls "Critically". Both remain
  confined today. **Action:** move the algorithm into core (the mcp→core edge
  exists); `WorkspacePaths` delegates; settle normalization once. *(Verified.)*

### Error-contract gaps

- **[SD-S-5] core/src/main/java/dev/dediren/core/commands/BuildCommand.java:598**
  · graph · `writeFile` throws `UncheckedIOException`, which escapes the
  per-view loop (it catches only `ViewOutputEscapesRootException`) — one view's
  write failure aborts the entire build, contradicting the class javadoc "a
  failing view never aborts the others", and cli prints a usage envelope with
  **no `BuildResult`**, discarding the record of artifacts already written.
  **Action:** fold `UncheckedIOException` into the per-view failure branch.
  *(Verified.)*
- **[SD-S-5] core/src/main/java/dev/dediren/core/schema/SchemaValidator.java:38**
  · static · `catch (IOException | RuntimeException) → List.of(error.getMessage())`
  collapses a broken bundle (unreadable schema under the product root) into
  `DEDIREN_SCHEMA_INVALID` against the *user's* model; a null message NPEs.
  **Action:** schema-load failure becomes a product/internal diagnostic.
- **[SD-S-5] core/src/main/java/dev/dediren/core/analysis/ProvenanceCheck.java:124**
  · graph · `filesWithSuffix` throws `UncheckedIOException` on a failed walk and
  neither `status` nor `verify` catches it — raw stack trace, no envelope, on
  two published commands. **Action:** extend cli's `printCommandIoFailure`
  handling to status/verify (or catch in core).
- **[SD-S-5] schema-cache/src/main/java/dev/dediren/schemacache/InJvmXmlValidator.java:159**
  · static · Saturation, timeout, schema-set-compile, and JAXP-config failures
  all collapse into message-only `SchemaCacheException`; both export engines
  decorate with schema-placement/proxy remediation that only fits one class, so
  transient saturation reads as terminal misconfiguration. **Action:** small
  cause enum (SCHEMA_SET/CONFIG/TIMEOUT/SATURATED); remediation per class;
  SATURATED says "transient, retry".
- **[SD-C-6] schema-cache/src/main/java/dev/dediren/schemacache/SchemaCacheModule.java:207**
  · static · The curl child has no Java-side lifetime owner: unbounded
  `process.waitFor()` (the 60s budget lives only in the child's `--max-time`),
  no `destroyForcibly()` anywhere, and an interrupt during `waitFor` is wrapped
  into "failed to download" without restoring the interrupt flag while the
  child keeps running. **Action:** `waitFor(75s)` + `destroyForcibly()` on
  timeout/interrupt; re-set the interrupt flag. *(Verified.)*
- **[SD-C-6] mcp-server/src/main/java/dev/dediren/mcp/DedirenMcpServer.java:155**
  · static · `serveOn` adds a shutdown hook per call and never removes it, and
  `stdinClosed.await(); server.close();` has no try/finally — an interrupt
  leaves non-daemon transport threads running with nothing in-process to close
  them; embedding JVMs (the test class calls `serveOn` 8×) accumulate hooks
  each retaining a server. **Action:** try/finally close; `removeShutdownHook`
  after normal close.

### Hidden state & seams (schema-cache lane)

- **[SD-C-4] schema-cache/src/main/java/dev/dediren/schemacache/InJvmXmlValidator.java:92**
  · static · The validator's whole operational state is JVM-global statics
  (`COMPILED` cache, `VALIDATION_GATE`, `WORKERS`, `COMPILE_COUNT`); OEF, XMI,
  and concurrent MCP requests share one permit budget invisibly, observability
  is package-private static peeks, and no owner has the lifetime. **Action:**
  make it an instance owned at the composition edge (EngineWiring), so the
  sharing is an explicit wiring decision.
- **[SD-C-4] schema-cache/src/main/java/dev/dediren/schemacache/InJvmXmlValidator.java:257**
  · static · Freshness stamps are captured *after* compile re-reads disk
  (`cacheCompiled` stamps `FileStamp.of(key)` once `compile(schemaPath)` has
  consumed the bytes): a file rewritten in the window gets new stamps attached
  to a grammar compiled from old bytes, and `isFresh()` then serves the stale
  grammar indefinitely — the exact mode the revalidation commit exists to
  prevent. Low probability (SHA-pinned downloads), self-defeating mechanism.
  **Action:** capture stamps at read time, pass out via `CompileResult`.
  *(Verified.)*
- **[SD-B-5] engines/archimate-oef-export/…/OefExportEngine.java:784** · graph ·
  The `SchemaFetcher` port exists, but both export engines construct
  `SchemaCacheModule.curlFetcher(...)` inline deep in static policy, and
  `InJvmXmlValidator.validate` is a hardwired static — neither fetch nor
  validation substitutable from the engine boundary. Also quietly ends the
  explicit-env discipline: `new ProcessBuilder(...).start()` hands the child
  the full ambient environment by inheritance. **Action:** inject
  `SchemaFetcher` (default `curlFetcher`) via engine constructors in
  `EngineWiring`. (Env inheritance: delegate posture question to
  `devsecops-audit`.)
- **[SD-B-5] mcp-server/src/main/java/dev/dediren/mcp/EofSignalingInputStream.java:93**
  · static · `MAX_WAIT = 60s` is hardwired, so the "must never fire"
  lost-response stderr report (`reportUnanswered`) is unexercisable — every
  test caps at 30s; the class's stated last-resort obligation is unverified
  code. **Action:** package-private constructor `Duration`; one starvation
  test. (Test itself: `test-quality-audit`.)

### God classes & cohesion

- **[SD-B-1] engines/elk-layout/…/ElkLayoutEngine.java:52** · static · ~8
  separable responsibilities in one 1609-LOC class (request validation ~170
  LOC self-contained; three per-mode orchestrations; direction inference;
  endpoint-merge/junction policy; port planning; group construction; result
  extraction; sequence special-casing threaded through). Three independent
  reasons to change. **Action:** extract `LayoutRequestValidator` and a
  `PortPlan` value object first; the three `layoutX` orchestrators can stay.
- **[SD-B-1] engines/archimate-oef-export/…/OefExportEngine.java:58** · static ·
  Six responsibilities in one 1004-LOC file, with intra-file duplication
  already visible (policy-parse+four-catch block verbatim in `export` and
  `exportModel`; id-map setup repeated in `buildOef`/`buildModelOef`). The
  sibling engine proves the target shape (182-LOC orchestrator +
  policy/schema/build/write packages). **Action:** mirror the umlxmi split;
  first move: extract the schema-resolution/fetch cluster.
- **[SD-B-1] uml/src/main/java/dev/dediren/uml/Uml.java:23** · static · Seven
  distinguishable families in the 863-LOC facade; the four per-view-kind
  endpoint loops are copy-paste variants (see the SD-S-1 lane finding — the
  dedupe may simply delete them); multiplicity grammar and state-machine
  validation are extractable along the `UmlSequenceValidation` precedent.
- **[SD-C-1] engines/render/…/svg/SvgDocument.java:41 (+3–14)** · graph ·
  Package cycle `svg ↔ node.*`: `SvgDocument` (13 static imports of `node.*`
  shape/label/icon builders plus `UmlSequenceRenderer`, and per-notation
  dispatch) and `Geometry` (`node.NodeLabels`/`NodeShapeSupport`) point down
  while all seven `node/*` classes import `svg` primitives back. Rated warn,
  not block: the cycle is module-internal and is the residue of the god-file
  split (which reduced a fully tangled file), not a new module-graph edge.
  **Action:** move `SvgDocument` (and `Geometry.svgBounds`) up beside
  `SvgRenderEngine`; svg/ becomes a leaf-primitive package. No behavior change.
  *(Verified.)*
- **[SD-B-1] engines/render/…/svg/SvgDocument.java:215** · static · Per-notation
  ArchiMate drawing (grouping icon path, junction circle, rectangle variants,
  the `"3 2"` dash default declared twice in the same file) lives inline in the
  svg emission package while `node/archimate` is chartered for exactly this.
  **Action:** move emission to `ArchimateShapes`/`ArchimateIcons`; keep
  dispatch pure.

### Comment-pinned cross-module invariants

- **[SD-S-2] core/src/main/java/dev/dediren/core/quality/LayoutQuality.java:26**
  · static · Core hard-codes renderer drawing conventions
  (`GROUP_LABEL_BAND_HEIGHT = 24.0`, char-width/line-height estimates,
  `onLifelineAxis` stem convention) kept equal to render by comment. The repo
  fixed this exact smell once (`ir.quality.LayoutTolerances`). **Action:** move
  shared geometry conventions to `LayoutTolerances`-style constants both
  modules consume.
- **[SD-S-2] semantics-uml/…/UmlLayoutSizing.java:277** · static · UML member-line
  format + compartment metrics are byte-for-byte twins of render
  `UmlDecorators` (15.0/14.0/28.0, stereotype char counts 13/11/10 hardcoded);
  a one-side edit silently skews sizing vs drawing; nothing pins them.
  **Action:** uml core owns "member as text"; sizing measures the owner's
  strings; pin metrics with a dist-tool consistency test
  (`ARCHIMATE_LABEL_ICON_RESERVE` pattern).
- **[SD-S-1] uml/src/main/java/dev/dediren/uml/UmlSequenceValidation.java:121**
  · graph · Execution/destruction well-formedness is declared in uml
  (validator, throws) and semantics-uml (`UmlSequenceConstraints`, silently
  skips), synced only by "mirror exactly" comments commemorating a past exit-2
  bug. **Action:** export one extraction predicate from uml (semantics-uml
  already depends on it), or add the pass-implies-span test.
- **[SD-S-2] engines/archimate-oef-export/…/OefExportEngine.java:939** · graph ·
  Cross-engine export mechanics duplicated with no converging force (engines
  may not depend on each other): `slug()` byte-identical to `XmiHelpers.slug`;
  escaping re-implemented; the *shared* `DEDIREN_SCHEMA_CACHE_DIR` env name and
  `"curl"` fetcher command declared independently in both engines — renaming
  one silently splits the common cache. **Action:** shared mechanics move down
  (escaping/slug/id-collision beside `engine-api`'s `XmlText`; env name +
  fetcher + remediation prose into `schema-cache`, which already hosts
  `productRootRelativeEnv` for this reason).
- **[SD-S-2] engines/render/…/RenderInputValidator.java:241** · static ·
  Regression of the resolved §6 vocabulary debt: the combined-fragment
  operand-arity switch (`opt/loop→1, alt/par→≥2, default→false`) is a verbatim
  copy of uml's private `validateOperandCount`; a new operator in the core set
  makes render reject valid metadata with a misleading error. **Action:**
  export `supportsOperandCount(operator, count)` from `UmlSequenceValidation`;
  delete the copy. *(Verified verbatim.)*
- **[SD-S-2] engines/render/…/node/uml/UmlSequenceRenderer.java:932** · static ·
  Private `SvgBox` duplicates `svg/SvgBounds` and has diverged: its
  `margin()==null`/`page()==null` fallbacks are dead branches contradicting
  `RenderInputValidator.validateRenderPolicy`'s guarantee, and would mask a
  future validation regression. **Action:** reuse `SvgBounds` (or at minimum
  delete the dead fallbacks).

### ELK lane

- **[SD-C-2] engines/elk-layout/…/LayoutIntentNormalizer.java:317** · graph ·
  `distinctColumnXSlots` rebuilds lifeline columns post-ELK to fix same-layer
  x-collapse — but ELK Layered partitioning
  (`PARTITIONING_PARTITION`/`ACTIVATE`), which this module already wires in
  `ElkLayeredOptions.applyNodeHints`, guarantees exactly the missing property.
  Unlike port sizing there is no trialled-and-rejected record. **Action:**
  trial partition-per-lifeline in sequence mode with render evidence; keep the
  rebuild only as documented fallback or record it as litigated debt.
- **[SD-E-1] engines/elk-layout/…/LayoutJson.java:106** · static · Adding one
  preference value touches 4 sites; a new preference *field* touches ~6
  (schema, contracts record, hand-listed null-rejection paths, hand-maintained
  wire-string sets, options mapper, hand-listed layered-only gate) — none
  tripwired against the enums. **Action:** derive accepted-value sets from the
  contract enums' `@JsonProperty` wire names; the duplicated vocabulary
  deletes.
- **[SD-S-2] engines/elk-layout/…/LayoutJson.java:28** · static ·
  `validatePreferences(LayoutRequest)` re-serializes a typed request and
  re-runs wire-string checks — provably a no-op today (typed enums can only
  serialize to accepted strings) and a false-rejection bomb the day an enum
  outgrows the hand-maintained set. **Action:** delete the call
  (ElkEngine.java:62) and method, or make it enum-derived so it is tautological
  by construction. *(Verified.)*
- **[SD-S-1] engines/elk-layout/…/LayoutIntentNormalizer.java:33** · static ·
  Notation-neutral name over all-UML-sequence content, and the implicit "X band
  = lifelines, Y band = messages" protocol of the ir `OrderedBand(Axis)` seam
  lives only in comments (leaking back into ir's javadoc, which claims
  notation-freedom). **Action:** rename (`SequenceIntentNormalizer`) and state
  the axis↔band convention normatively on `LayoutIntent.OrderedBand`.

### Documentation drift (standing rule: no silent drift)

- **[SD-S-2] docs/architecture-guidelines.md §8** · static/history · §8 still
  cites `cli` at 446 LOC as honoring thinness (854 today — and the growth *is*
  the block finding above) and presents the resolved Main god-file split as
  "the active divergence" (§12 marks it resolved). Two stale javadocs found in
  passing: `SequenceInvariants` claims quality wiring is "a later task" (it is
  wired via `CoreCommands.sequenceInvariantDiagnostics`);
  `SequenceConstraint`'s `@link UmlSequenceConstraints#of` names a deleted
  method and a completed "Task 5 cutover" as pending. **Action:** refresh §8
  with current numbers and the new god-class watchlist (ElkLayoutEngine,
  OefExportEngine, Uml); fix the two javadocs.

## Info (compact)

Core/cli — `CoreCommands` env params threaded but unread on 3 of 4 commands
(javadoc names overloads that no longer exist); piped-envelope unwrap rule
duplicated (`JsonInput` vs `CoreCommands.layoutRequestData`); the
warning-severity predicate spelled 3× (BuildCommand ×2, EngineDispatch);
`LayoutQuality` dual hand-enumerated verdict lists (10-term conjunction vs 10
`addQualityWarning` calls) must be edited in tandem; `policyName` published as
the bare literal `"draft"` with no policy concept behind it;
`ProvenanceCheck.readHead` swallows `IOException` to `""` so unreadable ≡
UNSTAMPED; core's ir mappers run *inside* the dispatched lambda so a core
mapping bug is published as `DEDIREN_ENGINE_FAILED` against the engine id;
`DocumentValidator.SCHEMA_FILES` NPEs on a family registered without the map
edit (fail closed with a diagnostic instead); `SourceValidator` re-resolves
product root + rebuilds `SchemaValidator` per fragment;
`System.getProperty("dediren.version")` read independently in core and cli.

mcp-server — validate dispatch decision (profile→semantic vs document) made
independently in cli and mcp; `VIEW_ID_PATTERN` third declaration of the id
shape (acknowledged defence-in-depth — record or lift to contracts);
`stringListArg` dedup now redundant with `BuildRequest`'s canonical dedup;
resource lane surfaces `UncheckedIOException`/initializer errors as raw
JSON-RPC errors while the tool lane maps everything (record the asymmetry or
catch).

schema-cache/exports — OEF `productRootRelativeEnv` pass-through binds nothing
(unlike the sibling); `SchemaValidation`'s two-arg overload in src/main
silently resolves cwd (the exact behavior Decision 9 removed) for tests only —
move to test tree; uml-xmi phase split leaks two sequence-scope validators
into `write.interaction.InteractionWriter` (move to build/schema).

elk-layout — `configuredRoot` has zero production callers (tests groping for
seams the SD-B-1 extraction will supply); "self-message" has two non-identical
definitions either side of the ELK run (id-equality vs band-index) with no
shared predicate; `relationshipTypePortSuffix` keys merge-port identity on
`String.hashCode` (collision → silently shared port); two cross-module
geometry invariants held by comment (LayoutQuality overlap predicate, renderer
stemX convention) — candidates for the e2e boundary-case pin;
`MERGE_EDGES`/`MERGE_HIERARCHY_EDGES` writes likely inert (every edge attaches
to an explicit port) — verify with a render diff, drop or justify;
`validateRoutingPreferences` is an empty scaffold (`if null return;` and
nothing else) — delete.

render — `MessageAppearance.from` free-string sort→marker mapping unpinned to
`messageSorts()` (new sort silently renders as default arrow); per-decorator
data split across 3 parallel tables with divergent miss behavior (silent
fallback vs `IllegalArgumentException`→`DEDIREN_ENGINE_FAILED`);
`StyleResolver` 17-arg positional merges + null-padded defaults (insertion at
wrong position compiles and silently shifts fields) — field-wise merge or a
field-order pin test; `EdgeRenderer`↔`Geometry` mutual statics inside svg/
(move pure predicates to Geometry); vestigial ignored `type` params on
`nodePaint`/`edgePaint`.

contracts/notation — `viewKindName` and `SemanticProfiles.wireName` hand-mirror
`@JsonProperty` names (`EnvelopeStatus.wire()` is the in-house pattern; add
`wire()` to the enums); Lifeline/Interaction role map re-declared verbatim in
graph vs uml profile front ends (subset consistency test or record);
`LayoutNodeRole.ALL` has zero consumers; `Archimate.relationshipTypes()`
uncalled and two sibling accessors test-only (surface without demand — the
inverse of the 2026-07-13 lesson); `LayoutPreferences` telescoping ctors used
by tests only (move convenience to test-support); `KnownSchemaVersions` has an
entry gate but no recorded exit policy for prior versions (decide keep-forever
vs sunset in guidelines); `umlMessageSequence` NPEs on a missing property while
sibling scanners are deliberately null-tolerant (third comment-free ordering
invariant); `SemanticValidationResult` lives in `contracts.layout` but is
analysis-shaped (internal move); `LayoutResult.warnings` vs `diagnostics`
naming split across result families (record or align at next breaking bump).

## What verified clean

Module DAG exactly per §2 (all 17 poms) · EngineWiring single construction
point · EngineDispatch five-outcome taxonomy with envelope preservation ·
mcp tool lane: thin handlers, envelopes verbatim, `PendingRequests`/
`FrameSplitter`/`StdoutIntegrity` ownership sound · marker/arrow geometry
single-owner (`EdgeMarkers`, past refX defect stayed fixed) ·
`messageSorts()`/`combinedFragmentOperators()` consumed as intended · sequence
band math legitimately render-owned (documented derive-don't-mutate) · no
mutable statics outside the accepted `JsonSupport` singleton and the flagged
`InJvmXmlValidator` · elk module stateless, self-loop determinism fix intact,
cross-group back-edge reversal justified · `ContractCollections` uniform across
101 files · `KnownSchemaVersions` integrity checks · SourceValidator fragment
confinement fail-closed · archimate deny-list javadoc intact, no new polarity
asymmetries.

## Footer

```text
Project assimilation:
  Reused: contract-first module DAG + ArchUnit/Enforcer enforcement (§2/§11);
    EngineWiring composition root; envelope/diagnostic taxonomy; notation-core
    export surface; LayoutTolerances and ARCHIMATE_LABEL_ICON_RESERVE as the
    in-repo patterns for cross-module invariants.
  Legacy debt: all §12-registered items honored and excluded; new findings
    above are classified, none migrated in this review.
  Migrations performed: none (Review mode — findings only).
Delegations: pinning/starvation/lane-agreement tests → test-quality-audit;
  curl child env inheritance + subprocess posture → devsecops-audit;
  no docs/architecture dediren package exists → no architecture-design pairing.
Limits: runtime and human layers absent; Maven not run (read-only review);
  hang/race/performance claims are static inference. Severity calibration on
  SD-C-1 (svg↔node) deliberately warn-not-block: module-internal, residue of a
  split that reduced tangling, cheap mechanical fix.
```
