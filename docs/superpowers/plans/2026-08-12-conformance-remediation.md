# 2026-08-12 Conformance remediation — UML 2.5.1 / ArchiMate 3.2 (124 findings)

Remediation plan for the 2026-08-12 notation conformance register
(`2026-08-12-notation-conformance-register.md` — 124 distinct findings, **44 block / 50 warn /
24 info**, measured at `ac0175f`). The register is diagnose-only; this plan is the execution
record.

Findings are split by **emitting surface**, not by severity, because the goldens and the
re-baselining cost cluster per surface: one render-golden regeneration, one XMI-golden
regeneration, one hand-edited OEF golden. Doing them per severity would re-baseline the same
files three times.

The register's organising result shapes the order. The two notations fail in **opposite
directions**:

- **ArchiMate is too permissive**, and the leaks are not the declared ones — `RelationshipLegality`
  carries an explicit "sound under-approximation" charter (may accept invalid, must never reject
  valid), and both `block` findings fall *outside* it: a hand-written bidirectional rule, and a
  blanket `UNIVERSAL` short-circuit that is the *first* statement in the endpoint check, so it also
  bypasses the engine's own declared five-triple carve-out.
- **UML is too restrictive**, because legality tracks what the XMI writer supports rather than what
  the spec permits — including one of the repo's own committed, bundle-shipped fixtures.

So the ArchiMate fixes *narrow* what validates and the UML fixes *widen* it. They are sequenced into
separate phases for that reason: a single central build mixing both makes a red suite ambiguous.

**Scope decision (user, 2026-08-12):** close the register's own Groups 1–6, and give every remaining
finding an explicit written disposition. **No new modelling capability** — the vocabulary gaps
(`AM-VOCAB-11/12`, `UML-VOCAB-2/3/4`) are documented capability boundaries, not work items.

Constraint that shaped sequencing: documentation (Phase 6) lands **after** the behaviour it
describes, so no claim is written ahead of the code. `AgentUsageDocConsistencyTest` enforces this
mechanically in one direction — a documented `DEDIREN_*` token that does not exist in source fails
the build.

## Audit gates

Per `CLAUDE.md ## Audit Gates` (OEF / UML-XMI export row), before calling this work complete run:

- `test-quality-audit` — **deep**, scoped to export tests and fixtures. Load-bearing here: the
  register's own sizing was wrong three times because a *test-side* claim went unverified (see
  § Sizing corrections), so this gate is checking the thing that already failed once.
- `devsecops-audit` — **quick**, scoped to the export boundary.

Fix block findings; fix or explicitly accept warn/info in the handoff.

## Sizing corrections found before execution

Recorded because they change the plan, and because each is a class of mistake rather than a one-off.
A register measures the *code*; its remediation cost is mostly a claim about the *test suite*, and
that half was never cited the way the findings were.

- **A test can *permit* a bug without *requiring* it.** `ArchimateRelationshipLegalityConformanceTest:244-248`
  asserts a necessary condition over `Set.of(s, t)` — **unordered** — so it reads as pinning
  direction-blind specialization while actually tolerating either behaviour. `AM-SEM-1` is therefore
  two deleted lines and a new directional test, not a test rewrite. Its neighbour at `:256-271`
  *does* hard-assert the `UNIVERSAL` short-circuit over 11 × 60 × 2 × 2 and is the real blocker.
  Same file, same lane, opposite answers: read "pinned by the test" per assertion, never per file.
- **A rejected construct may already be implemented.** `UML-SEM-29` looked like missing emitter
  support; `InteractionWriter.writeMessageOccurrence:399-401` already emits
  `uml:DestructionOccurrenceSpecification`, derived from `messageSort`. Only its two gatekeepers
  (`:477-479` and `:503-506`) disagree with the core. "Does not support X" and "refuses X" are
  different claims.
- **A fixture read as its own oracle proves nothing.** `engines/render/.../MainTest.java:3733-3848`
  iterates `fixtures/render-policy/archimate-svg.json` and asserts the emitted SVG matches *that same
  fixture*, with a branch for `dashed` and **none for `dotted`** — so the register's cheapest,
  "zero risk" data-only fix would have shipped with its new line style entirely unasserted. The
  give-away is a per-value branch that does not enumerate the value set.

## Phase 0 — base

- Commit the register unchanged as the cited evidence base (`d560c55`). [n/a]
- This plan. Worktree `.worktrees/notation-conformance`, branch `notation-conformance`. [n/a]

## Phase 1 — ArchiMate notation

Five of eleven relationship types collapse into two visual forms. Every value needed already exists
in the `render-policy.schema.json` enums (`lineStyle` = solid/dashed/dotted; `filled_arrow`).

- **RED first:** add the missing `dotted` arm to `assertRelationshipPolicyCoverage`
  (`stroke-dasharray == "1 3"`, hardcoded at `Svg.java:75-76`) and pairwise-distinctness assertions
  (Serving ≠ Triggering ≠ Flow; Access ≠ Influence). Without this the phase is unverified. [block]
- `fixtures/render-policy/archimate-svg.json:110-122` — Triggering → `filled_arrow`; Flow →
  `dashed` + `filled_arrow`; Access → `dotted`; Realization → `dotted`; Assignment target →
  `filled_arrow`. Closes `AM-NOT-1/2/4/5` and most of `-3`. [block ×2, warn ×3]
- `AM-NOT-6` — give the `archimate` profile notation defaults in `StyleResolver.java:37` so a partial
  policy cannot fall through to the generic `FILLED_ARROW` + solid. This is the recurrence guard, and
  it corrects `docs/architecture/dediren.dediren/render-policy.json` (one `Serving` row today) for
  free. [warn]
- `AM-NOT-7` — `SvgDocument.java:410-413` returns early unless the decorator is `ARCHIMATE_GROUPING`,
  so a semantic container of any other type draws as an untyped grey box. Honour it or diagnose it;
  not documented as declined anywhere. [warn]
- `AM-NOT-11` — Service corner icon is a rounded rectangle, not the spec's pill; ApplicationService's
  differs in height/y from the other two. [info]

**Golden churn:** `archimate-oef-basic__archimate-svg.svg` (`./scripts/regen-render-goldens.sh`).
The Realization `dashed → dotted` edit is the **only** change in this whole plan that touches a
raster golden (`archimate-decorators.png`, SHA-256 pinned) — that fixture's single relationship is a
Realization.

## Phase 2 — UML notation

- `UML-NOT-2` — `UmlSequenceRenderer.java:642-643` keys line style on `"reply"` alone, so
  `createMessage` renders solid and reads as an asynchronous signal. RED target is
  `MainTest.java:1998-2002`, which currently asserts the *absence* of `stroke-dasharray`. [block]
- `UML-NOT-3/4` — `UmlShapes.java:274-282`: `junction` shares `choice`'s diamond (static drawn as
  dynamic); `terminate` shares `exitPoint`'s circled X (**opposite semantics** — "halts" drawn as
  "leaves through here"). `junction` reuses the existing `umlFilledCircleShape`; `terminate` needs a
  new bare-cross helper. **No fixture uses `junction`, `terminate`, `exitPoint` or `entryPoint`** —
  add render-metadata coverage or the fix ships untested. [block ×2]
- `UML-NOT-5` / `UML-VOCAB-1` — `visibility` is unvalidated free text: `"Private"` / `"protected "`
  build `ok`, render `+`, and export the typo verbatim, so the two artifacts disagree on one model
  with no diagnostic. Root fix is a `VisibilityKind` allow-list in `Uml.java` (a `DEDIREN_UML_*`
  code — already a documented family). Leave `UmlDecorators.java:256`'s `default -> "+"`; throwing
  in the renderer would fail a model that already validated. [block ×2]
- `UML-NOT-1` — six edge kinds (`Usage`, `Dependency`, `Include`, `Extend`, `Manifestation`,
  `Deployment`) render byte-identical because the renderer has **no edge-keyword surface**; the six
  guillemet literals that exist are node-level only. Derivable from the relationship type, so no new
  authored vocabulary — but a new render capability. §11.6.4 makes it load-bearing: the expandable
  interface-rectangle form dediren already uses is permitted **only if** the arrows carry keywords.
  Largest item in this phase. [block]
- `UML-NOT-7` — Package name drawn twice. **See § Coordination — the 2026-07-28 backlog recorded a
  contrary approach with evidence; settle it before editing.** [warn]
- `UML-NOT-8/9` — `DeploymentSpecification` uses the Artifact dog-ear; the interaction frame pentagon
  omits its `sd` tag; «device»/«executionEnvironment» straddles the 3-D fold. [warn]
- `UML-NOT-12` — hollow markers and the final-state ring hardcode `#ffffff`, so a dark UML policy
  would read shared aggregation as composition. Latent (no dark UML policy ships). **Coordinates with
  the 2026-07-28 dark-policy render wave.** [warn]

**Golden churn:** `uml-sequence-lifecycle__uml-svg.svg`, `uml-basic__uml-svg.svg`. **Zero raster
regeneration** — no raster fixture contains a `createMessage` or a Package node.

## Phase 3 — XMI interchange

### 3a — emitted bytes

- `UML-XMI-12/13` — `DiagramWriter.java:62,109` emits the **abstract** `umldi:UMLDiagram`; UML 2.5.1
  **Annex B is normative and is the UMLDI metamodel** (it was assumed out-of-corpus and is not).
  Concrete `UMLClassDiagram` exists. Separately `isFrame` has zero occurrences in the module, so it
  defaults `true`, making `heading : UMLLabel` mandatory and every emitted diagram a normative
  invariant violation. `WholeModelXmiExportTest.java:61,102` pins attribute **adjacency** — append
  after `name`, never between `xmi:id` and `name`. [block ×2]
- `UML-XMI-21` — every `uml:Port` omits `aggregation`, violating `port_aggregation`. The existing
  `M-PORT` invariant checks the *derived* properties correctly and is simply silent on the clause
  next door. Add `M-PORT-AGG` + one line to the non-vacuity proof at
  `XmiMetamodelInvariantsTest.java:204-248`. [block]
- `UML-XMI-18` — `XmiBuilder.openRoot():205-216` never fills `xmi:Documentation`, a schema-**valid**
  slot the pinned XSD would actually verify, so the artifact asserts nothing about what produced it.
  Emit `exporter`/`exporterVersion` only — **`timestamp` would destroy determinism and churn all 11
  byte-exact goldens on every run.** [warn]
- `UML-VOCAB-7` / `UML-XMI-5` — `uml:Realization` emitted where `uml:ComponentRealization` and
  `uml:InterfaceRealization` belong. Caveat: `componentRelationshipXmiType:216` is a family-wide
  switch and endpoint typing is decided at `:200-212`; verify against the golden before flipping. [warn ×2]
- `UML-XMI-2` — the tolerated-gap filter keys on Xerces message wording with **no test pinning it**,
  so a JDK upgrade that rewords `cvc-complex-type.2.4.c` bricks 100% of XMI exports as a runtime mass
  failure rather than a red suite. Pin the wordings. [warn]
- `UML-XMI-20` — add a UMLDI golden. Zero of 11 goldens declare `xmlns:umldi`, which is precisely
  where `UML-XMI-12/13/14` were hiding. [warn]
- `UML-XMI-4` — `xmi:version` ban is XSD-correct but unconditional, with no policy escape hatch and
  no version marker in the document. [warn]

Re-baseline all 11 goldens **once**, at the end of 3a:
`./mvnw -pl engines/uml-xmi-export -am test -Dtest=GoldenExportRegenerator -Ddediren.regen.xmi=true -Dsurefire.failIfNoSpecifiedTests=false`

### 3b — assurance says what happened

`UmlXmiAssurance.java:89,91` sets `level` and `status` as unconditional literals. The outcome *is*
computed (`XmiExportEngine.java:97`/`:165`) and *is* in scope at the assurance call sites
(`:115`/`:183`) — it is simply never passed. So `status="validated"` is emitted on the path where the
schema **rejected** the content, a user supplying a real UML schema is **under**-credited, and the
schema's `not-validated` value is unreachable.

- Return a structured outcome from `SchemaValidation.validateXmiToAvailableStandards:63-67` (today
  all three outcomes collapse into one free-text `Diagnostic`) and thread it through `forView` /
  `forModel` / `assurance`. **Do not string-match the diagnostic message** — that reproduces exactly
  the brittleness `UML-XMI-2` flags. RED target: `XmiAssuranceTest.java:170-171`. [warn]
- Add the `allOf` conditional guard at the `xmi-envelope-only` rung of
  `schemas/uml-xmi-assurance.schema.json`; the guards that prevent this failure already exist one
  rung up, for the stronger levels. Closes `DOC-25`/`DOC-26`. [block ×2]
- `AM-OEF-11` — OEF's entire disclosure is one inline free-text INFO string while UML/XMI has a
  machine-readable contract, and **OEF's claim is the weaker one**. New `OefAssurance` +
  `schemas/oef-assurance.schema.json` mirroring the XMI pair; bump `ContractVersions` (generated
  engine-seam schema — no `KnownSchemaVersions` family, so no migration entry) and add the row to
  `docs/features/contracts-and-schemas.md` (closes `DOC-24`). [warn, info]

## Phase 4 — OEF clamp-and-guard

Five findings share one shape: **input valid under dediren's own published JSON contract produces a
document the exchange schema rejects.** The failure is loud, not silent — but three of the five are
produced by dediren's *own* ELK layout, so they are unrepairable by editing source JSON. That is what
makes `DOC-7` a block: `validate` ok → `layout` ok → `validate-layout` ok → `export` dies.

- **Verify the XSD first:** are `<elements>`/`<relationships>` optional in `ModelType`, or only their
  children required? If the wrapper is optional, `AM-OEF-1/2` are two `if (!isEmpty())` guards around
  `OefExportEngine.java:609-620,622-637` (`writePropertyDefinitions:884-886` is the precedent). If
  not, the fix is an early reject naming the cause. [block ×2]
- `AM-OEF-3/4/5` — `formatNumber:947-949` is the single clamp point but is shared by x/y
  (`nonNegativeInteger`) and w/h (`positiveInteger`); split into two formatters. **Each clamp must
  emit a `warn` diagnostic naming value and path** — silently moving geometry would recreate the
  exact "declared, not silently dropped" falsehood `DOC-9` is about. [block ×3]
- `AM-OEF-6` / `DOC-9` — grouping containment is discarded although `LaidOutGroup.members()` is in
  scope in `writeViewBody` (grep: **zero** `.members()` calls in the engine) and the XSD's `Container`
  declares a nested `<node>` child. Nodes are emitted self-closing at `:692`/`:707`, so this means
  open/close pairs plus skipping already-emitted members. [warn, block]
- `AM-OEF-8/9` — every property is `type="string"` though the XSD offers six datatypes; non-scalar
  values flatten to escaped JSON via `toString()`. The definition map (`:870-880`) is keyed by name
  only and must carry a type, with reconciliation when one key is boolean on one node and string on
  another. [warn ×2]
- `AM-VOCAB-4` — `viewpoint` is a required free string copied verbatim; `"Layred"` exports clean
  because the XSD type is a union with `xs:string`. Add a **warn-on-unknown** check against the 25
  spec viewpoint names — not a hard enum, because the union genuinely permits any string. [warn]
- `AM-OEF-10` — no `<style>` is emitted, so render policy does not cross into OEF. Defensible, but
  currently an unstated stance: state it. [warn]
- `AM-OEF-12` — the real-XSD lane pins exactly one happy-path fixture; every block above sits outside
  that shape. Extend `RealSchemaConformanceTest`. [info]

**Golden churn:** `fixtures/export/oef-basic.xml` is compared with exact-string `isEqualTo` at
`MainTest.java:50` and **there is no OEF regenerator** (the UML lane has one; this lane does not) —
hand-edit it. The ~40 `.contains()` assertions in that file are the primary oracle.

## Phase 5 — legality models

### 5a — ArchiMate stops leaking

- `AM-SEM-1` — cross-type specialization accepted in both directions; §5.4.1's role names are
  `specializes`/`specialized by`. Delete `RelationshipLegality.java:171,173`; add a directional test.
  (The existing conformance assertion permits but does not require the bug — see § Sizing corrections.) [block]
- `AM-SEM-2` — `UNIVERSAL = {Grouping, Location}` returns `true` at `:137-139` *before* the category
  lookup and the switch, so it bypasses the engine's own carve-out: `BusinessObject -[Assignment]->
  Grouping` and `Goal -[Flow]-> Location` pass, which are exactly the combinations
  `isSpecContradictedByFive` claims are always rejected. §B.6 grants grouping universality only
  **conditionally**; location only for aggregation/composition. **Rewrite the test at `:256-271`
  first (RED).** Note `isSpecContradictedByFive` is test-only (`:340`) while
  `RelationshipLegality.java:30` names it in prose — the two files move in lockstep. [block]
- `AM-SEM-3/4` — composition/aggregation decided on *category* equality rather than element-type
  equality; Product and Plateau folded into `COMP`, and because `COMP ∈ DYNAMIC` the leak extends to
  Serving/Flow/Access/Realization. [warn ×2]
- `AM-SEM-5/6/12` — junction containment carve-out is bidirectional where §5.5.1 is one-way (delete
  the first disjunct at `Archimate.java:275` — consumed at three call sites, so all three checks
  change together); element↔connector edges bypass endpoint legality entirely at `:136-138`; a
  junction cycle terminates the walk with zero endpoint pairs checked. [warn ×2, info]
- `AM-SEM-10/11` — the null-category branch and the unknown-relationship `default` arm both **fail
  open**. Make them fail closed, and pin `RelationshipLegality.CATEGORY` from the test, which today
  checks only its own hand-copied sets. [info ×2]
- `AM-SEM-8` — the carve-out's cited justification is wrong: §5.1.3's framework sentence explicitly
  sanctions behavior→passive, which `ASSIGNMENT_TARGETS[BEH] == {BEH}` rejects. Fix the citation;
  decide whether the rule follows. [info]
- `AM-VOCAB-5` — seven copies of the element list, **zero drift today, zero test**. A 63rd element
  added to `Archimate.java` alone compiles, passes, and silently renders undecorated. Add the
  cross-copy consistency test (`UmlCompartmentMetricsConsistencyTest` /
  `ArchimateLabelReserveConsistencyTest` are the pattern). This guard is what stops the class. [warn]
- `AM-VOCAB-9` — the §12.1-deprecated `WorkPackage -[Realization]-> Deliverable` is accepted with no
  diagnostic. [info]

### 5b — UML stops over-rejecting

Every over-permissive **and** over-restrictive failure traces to one predicate:
`Uml.java:752-754`'s `isStructuralType` (6 names) stands in for three spec type systems — `Type`
(association ends), `Classifier` (generalization ends), `NamedElement` (dependency ends). It is
simultaneously **too wide** (admits `Package`) and **too narrow** (excludes Actor, UseCase, Artifact,
Node — all Classifiers).

- Split it into the three sets and break the flat arm at `:426-430` into per-relationship branches.
  Closes `UML-SEM-1/2/4/5` at once and most of `-3`. New predicates belong beside `:810-836`. Blast
  radius is the UML fixture suite, not the exporters. [block ×4, warn]
- `UML-SEM-8` — `UseCase.subject` is narrower than `Classifier` and single-valued; §18.2.5.4 gives
  `[0..*]` and the spec's own Figure 18.2 uses a **Component**. Check `semantics-uml` and render
  before committing to the array form. [block]
- **State-machine cluster** — the largest single body of work and the one that most changes what
  users can model. `:661-668` requires a transition to share a region with *both* endpoints, so
  external transitions crossing a composite-state boundary are rejected and entry/exit-point
  transitions are structurally impossible; §14.5.11.8 requires only a shared
  `containingStateMachine()`. The relaxation needs a region → state-machine lookup that
  `ValidationContext` may not expose — **check before sizing**. Plus: no pseudostate degree
  constraints at all (§14.5.6.7 defines nine), no transition-kind semantics, no region cardinality.
  [`UML-SEM-13/15/16/17`, block ×4]
- `UML-SEM-19` — `supportsOperandCount:747` demands `alt`/`par` ≥ 2; §17.12.3.6 gives every operator
  a floor of one and the only arity constraint in clause 17 covers opt/loop/break/assert/neg. One
  token, propagating to render automatically via `RenderInputValidator.java:218`. Breaks two pinned
  tests; **zero operands must stay illegal**. [block]
- `UML-SEM-29` / `UML-XMI-9` — core allows `Lifeline -> DestructionOccurrenceSpecification`
  (asserted at `UmlValidationTest.java:82-83`); the exporter demands `Lifeline -> Lifeline` **and**
  rejects the node outright. `valid-uml-sequence-lifecycle.json` — committed and bundle-shipped —
  validates, renders, then hard-fails at export, and is the only UML fixture with no XMI golden, so
  the golden-iterating invariants structurally cannot see it. Relax both validators together; no
  emitter change needed. [block, warn]
- `UML-SEM-24` — nothing enforces `no_occurrence_specifications_below`, so a message arrives on a
  destroyed lifeline and the arrow renders below the destruction cross. New pass; the ordering
  machinery it needs already exists. [block]
- `UML-SEM-22/23` — two Dediren house rules enforced as UML legality, rejecting spec-legal
  interleavings (`critical` exists *because* ordinary fragments are not protected from interleaving).
  **Proposal: demote to `warn`**, keeping the layout guarantee without claiming it is UML. [warn ×2]
- `UML-SEM-12` — multiplicity bounds restricted to integer literals; §7.5.4.1 makes both bounds
  ValueSpecifications. Undocumented in schema or guide. [warn]
- `UML-SEM-9/10/25/26/31` — inert `Gate`; `Extend.extensionLocation` cardinality;
  `ExecutionSpecification.same_lifeline`; fragment-ownership uniqueness scoped per-interaction;
  `must_have_name`. [warn ×5]
- `UML-VOCAB-8/10` — `CombinedFragment` and `InteractionOperand` have no decorator at all, and the
  cross-copy check is **not even mechanically available** because `Uml.java` exposes no accessor for
  its seven element-kind sets — that absence is *how* the gaps arose. [warn ×2]
- `UML-SEM-11` — accepting `0..0` and bare `0` is **spec-correct** (§7.5.3.2). **Do not "fix" this.** [info]

## Phase 6 — documentation

Runs last, so every claim describes the shipped build. `docs/agent-usage.md` ships inside the bundle
and is the only document an agent consumer sees.

- `DOC-1` — the exchange-format version is 3.2 in five places and 3.1 in three, and `README.md`
  contradicts itself (`:7` vs `:295-296`). The Open Group publishes OEF at **3.1**. Fix the *format*
  noun phrase only — **the ArchiMate 3.2 vocabulary claims are verified accurate and must not be
  swept up.** [block]
- `DOC-12/13/19/25/26` — the conformance claims, rewritten after Phases 3–5 make them true.
  "Provisional" materially understates UMLDI: it framed a definite violation of a normative annex as
  an open question. [block ×5]
- `DOC-7/9` — the "what is lost without a diagnostic" subsection: Grouping containment,
  Access/Influence/Association attributes, `visibility`, parameter direction. Much smaller after
  Phase 4; write what remains. Place as a `###` under `## Repair Rules` — a new `##` heading needs a
  `GuideCatalog` topic or `GuideCatalogTest` fails. **The single highest-value doc remediation**,
  because the guide's current structure implies that list is empty. [block ×2]
- `DOC-16` — the whole `properties.uml.attributes[]`/`operations[]` surface, `visibility` included,
  has **zero occurrences** across README, agent-usage, features *and* schemas, yet is a live authored
  field used by shipped fixtures. [block]
- `DOC-14` — `external` advertised as supported while the case that defines it is rejected. [block]
- `DOC-20` — "specification-mandated shapes and icons" is false while six edge kinds render
  identically. [block]
- `DOC-22` — after `UML-SEM-29`, add `valid-uml-sequence-lifecycle.json` to
  `docs/features/source-model.md:130-132`, which omits it. [block]
- `DOC-23` — the UML/XMI export contract is filed under `## ArchiMate Handoff`, so `dediren_guide`
  misdelivers it, and `uml-class`/`uml-data` — the only family with UMLDI output — has no topic.
  Needs a new `##` section **and** a `GuideCatalog.topicMap()` entry; `GuideCatalogTest` pins both
  directions. [warn]
- `DOC-3/4/5/6/8/10/11/15/17/21` — the under-approximation disclosure attributes permissiveness to
  the wrong cause (closure, not the rule gaps that actually leak); the 25 %-miss design point appears
  in no user-facing document; `docs/features/` presents ArchiMate validation unqualified where
  `agent-usage.md` qualifies it. [warn ×10]

**Hard constraint:** any CalVer-shaped string written into `docs/agent-usage.md` must equal the root
`pom.xml` version exactly, or `AgentUsageDocConsistencyTest` fails the build.

## Phase 7 — external material and disposition

- `UML-XMI-14/15` — DD namespace major version, and `di:waypoint` / `dc:Bounds` element naming.
  **Not locally decidable**: UML 2.5.1 normatively cites DD **1.1** while the pinned `20100524`
  datestamp is DD 1.0 vintage with zero corpus occurrences; and the corpus is *silent, not
  contradicting*, on the geometry element names. Needs DD 1.1 + the MOF 2 XMI Mapping locally
  (network fetch — confirm before fetching). Two observations need no external spec and land
  regardless: the dialect mixes a type-named element with a property-named one, so one convention
  must be wrong; and the corpus attests lowercase `bounds`. [warn, info]
- `AM-SEM-9` — `CATCH_RATE_FLOOR = 0.75` has no recorded provenance anywhere (the only figure ever
  measured, ~78 %, exists solely in a sibling commit message) and **no §12 debt entry**. Record the
  derivation; decide whether the floor rises. Note the test is `assumeTrue`-skipped without a
  user-supplied oracle, so in normal CI this constant gates nothing. [warn]

## Coordination with the 2026-07-28 Group 2 backlog

Four of that plan's deferred waves touch the same code this one does. Landing them blind would mean
double surgery on the same files, or worse — reversing a decision that was already made with
evidence.

| 2026-07-28 item | This plan | Resolution |
|---|---|---|
| 1 — [block] UML package node double label; recorded approach: **suppress the TAB text (body-only name)** rather than adding `UML_PACKAGE` to the supplies-list, "because the tab label already overflows the 14.4px tab" | `UML-NOT-7` (Phase 2) | **The earlier decision wins unless render evidence overturns it.** It was reasoned from a measured overflow; this register only observed the duplication. Settle with real-render evidence before editing, and record which form §12.2.4 actually requires. |
| 5 — [warn ×3] render dark-policy cluster, incl. hollow markers `#ffffff` | `UML-NOT-12` (Phase 2) | Same defect, same line. Do it **once**, in the earlier plan's render wave shape (markers fill from resolved background), and add the dark-policy golden coverage that let it survive. |
| 8 — [warn] `exportModel` drops `validateExportableSequenceScope` | `UML-SEM-29` (Phase 5b) | Interacting: this plan **relaxes** the scope validator while that item **extends** its reach to the model lane. Relax first, then extend — extending the current rule would propagate the defect. |
| 9 — [warn ×2] XMI id plumbing, incl. `DiagramWriter.java:64` diagram id bypassing `IdentifierMap` | `UML-XMI-12/13` (Phase 3a) | Same method. Fold the id-integrity fix into the `UMLClassDiagram`/`isFrame` edit rather than touching `DiagramWriter` twice. |

## Disposition — findings not fixed by this plan

Filled in as phases land. Destination is either a `docs/architecture-guidelines.md` §12 row (with a
`**trigger:**` clause) or this table.

| # | [Sev] Item | Disposition | Why |
|---|---|---|---|
| 1 | [warn] `AM-VOCAB-11` — Access/Influence/Association carry none of their spec-defined attributes, though the OEF XSD defines `AccessTypeEnum` and `InfluenceStrengthEnum` | §12 row + disclosed in `## Export` | Contract-expanding: needs `model.schema.json` + `contracts` records + a breaking schema-version bump. Out of the agreed scope (no new modelling capability). |
| 2 | [warn] `AM-VOCAB-12` — relationships cannot be relationship endpoints (§5.2.4, §4.5.1/B.6) | §12 row | Same: `SourceValidator` requires node ids on both ends; widening it is a source-contract change. |
| 3 | [warn] `AM-VOCAB-4` (viewpoint *vocabulary*) — zero of 25 example viewpoints modelled, no per-viewpoint content restriction (§13.4) | Partially fixed (warn-on-unknown, Phase 4); the content restrictions get a §12 row | Modelling the 25 viewpoints and their allowed-content rules is a feature, not a conformance fix. |
| 4 | [info] `AM-VOCAB-6` — `Node` decorator token is `archimate_technology_node`, breaking the mechanical snake_case rule for 1 of 62 | Document in `agent-usage.md` | Renaming the token is a breaking render-policy change for one cosmetic asymmetry. |
| 5 | [info] `AM-NOT-8/9/10/12` — Influence ± modifier, Access read/read-write variants, directed Association half-arrowhead, per-layer letter cue | §12 row | Optional in spec, or blocked by `AM-VOCAB-11` (the data cannot be modelled either). The half-arrow has no marker vocabulary and substituting `open_arrow` would read as Serving. |
| 6 | [warn] `UML-VOCAB-2` — `ParameterDirectionKind` is 2 of 4; `out`/`inout` silently export as `in` | §12 row | Needs a source surface for parameter direction. Silent wrongness is the real defect — **disclose it in Phase 6** even though the fix is deferred. |
| 7 | [info] `UML-VOCAB-3/4` — `InteractionOperatorKind` 4 of 12; no Profile/Stereotype/Extension | Already disclosed honestly; §12 row | Bounded, enforced as a closed allow-set, so unsupported operators are rejected rather than silently dropped. |
| 8 | [warn] `UML-XMI-10` — every Association is non-navigable in **both** directions (an active assertion, not an omission) | Needs a decision | No source surface for navigability exists. Emitting both ends navigable changes semantics; emitting neither is what we do now and is wrong. Decide in Phase 3. |
| 9 | [warn] `UML-XMI-17` — the coverage partition is id-presence only, so a wrong-metaclass export counts as "represented" and all attribute loss is invisible | Likely §12 row | Making Coverage metaclass-aware is an architecture-scale change to a released contract. |
| 10 | [info] `UML-NOT-14/15/16/17` — classifier names not boldface while edge labels are; `deleteMessage` arrowhead; abstract italics, GeneralizationSets, FlowFinalNode unauthorable; navigability adornments | §12 row | Coverage gaps, not mis-renders; several are optional in spec. |
| 11 | [info] `UML-XMI-3` — two tolerated prefixes and one matched wording are dead code | Precision fix to §12 row 676 + `agent-usage.md:271-273` | Net outcome identical; only the stated *mechanism* is inaccurate. Cheap, so fold into Phase 6. |
| 12 | [block] `UML-NOT-1` — six edge kinds (`Usage`, `Dependency`, `Include`, `Extend`, `Manifestation`, `Deployment`) render byte-identically because there is no edge-keyword surface | **Attempted in Phase 2 and reverted.** Compose the keyword *before layout*, not in the renderer — see the note below. | A render-time composition is architecturally misplaced, and the paint oracle proved it rather than the reasoning: see § The UML-NOT-1 reversal. |
| 13 | [warn] `UML-NOT-12` — hollow markers and the final-state ring hardcode `#ffffff`, so a dark UML policy would read shared aggregation as composition | Do it once, in the 2026-07-28 plan's dark-policy render wave (item 5), which owns the same `EdgeMarkers` line plus two neighbouring sites and the missing dark-policy golden coverage | Latent (no dark UML policy ships), and fixing one of the wave's three sites piecemeal would leave the design half-made. Already bound in § Coordination. |
| 14 | [warn] `UML-XMI-5` / `UML-VOCAB-7` — a class→interface `Realization` emits `uml:Realization` where §10.5.6 requires `uml:InterfaceRealization`, and component realization likewise never emits `uml:ComponentRealization` | Not the metaclass rename it looks like. §10.5.6 makes an InterfaceRealization a **nested** child of the implementing classifier naming its `contract`, which is exactly what `ComponentWriter` already does — while `ClassRelationshipWriter` emits a flat `packagedElement` with `client`/`supplier`. The class lane appends classifiers to the buffer before it reaches relationships, so nesting requires it to write realizations *while* writing each classifier, as the component lane does. | A writer restructure with golden churn across the class family, not a two-token change. Sizing it as a rename is what the register's "one-line" framing invites. |
| 15 | [warn] `AM-OEF-11` — OEF has no machine-readable assurance contract while UML/XMI has one, and OEF's claim is the weaker of the two | Mirror the XMI pair: a new `OefAssurance` (~150 LOC) plus `schemas/oef-assurance.schema.json`, a `ContractVersions` bump, envelope plumbing and a `contracts-and-schemas.md` row (also closing `DOC-24`) | A new public schema surface, not a conformance repair. Belongs in its own slice with the contract change reviewed on its own terms rather than folded into a notation phase. |

## UML-XMI-2 is refuted

The finding says the tolerated-gap filter's Xerces wordings are pinned by "no test", so a JDK
reword "would surface as mass export failure, not a test failure". Measured by corrupting both
literals in `SchemaValidation` and running the suite: **two tests in `SchemaValidationTest` fail**.
They validate against strict stub schemas whose `uml:`/`di:` children have no declaration, so they
traverse the tolerated path by construction, and a wording that no longer matches turns the pass
into a throw.

The mechanism the finding describes is real — the filter *is* string-matched against Xerces prose —
but the consequence it predicts is not: the suite catches a reword before any user does. No change
made; recorded so the next reader does not re-add a redundant pin.

## UML-XMI-18 is refuted, not deferred

The register calls `xmi:Documentation` "a schema-**valid** slot the engine never fills" and "the
cheapest high-value fix in the lane and one of the few things the pinned XSD would actually verify".
The first clause is wrong, and the pinned XSD says so directly.

Emitting `<xmi:Documentation exporter="dediren" …/>` as the first child of `xmi:XMI` fails
validation:

```
cvc-complex-type.2.4.a: Invalid content was found starting with element
'{"http://www.omg.org/spec/XMI/20131001":Documentation}'.
One of '{WC[##other:"http://www.omg.org/spec/XMI/20131001"]}' is expected.
```

`xmi:XMI`'s content model is a wildcard that **excludes its own namespace**, so no `xmi:`-namespaced
child is admissible there — `xmi:Documentation` is a declared element with no valid position under
the root. The finding's supporting observation stands (the artifact carries no in-band provenance,
and that is a real loss), but its proposed fix does not validate, and it would have been shipped on
the strength of "the schema declares the element" alone.

Reverted and reclassified. Anyone picking this up needs to establish **where** XMI 2.5.1 admits
`Documentation` — the likely answer is under `xmi:Extension`, which is `##other`-compatible — and
verify it against the pinned XSD before writing an emitter. The one thing already settled: no
`timestamp`, ever, or every export becomes non-deterministic and all eleven goldens churn per run.

## The UML-NOT-1 reversal

Worth writing down, because the finding is a genuine `block` and the obvious fix is wrong in a way
that only emitted pixels reveal.

Deriving the keyword from the relationship type and prefixing it to the edge label works, is cheap,
and needs no authored vocabulary — the model already says which kind each edge is. All three
keyworded fixtures rendered their keywords (`«use»`, `«include»`, `«extend»`, `«manifest»`,
`«deploy»`), with `Dependency` correctly left bare as the unkeyworded base case (§7.7.4).

Then `SvgPaintAuditCorpusTest` failed on `uml-deployment-basic` with an
`edge_label_node_collision`: the `«manifest» manifests` label painting over the
`artifact-orders-service` node.

The cause is not the placement algorithm. **Layout had already reserved space for the author's
`manifests`** — the label is in the layout result — and the renderer then widened it. Every
keyworded edge that carries an author label is under-reserved by exactly the width of its keyword,
so this is general, not one crowded fixture. `CLAUDE.md`'s ELK-first rule names the correct
response: express the intent in the layout graph rather than repairing geometry after the fact.

So the keyword has to be composed **upstream of layout** — in projection, where the render metadata
is built and the layout request still carries edge labels — so ELK reserves the real width. That is
a pipeline-seam change (`SceneProjection` → layout request → render), not a renderer change, and it
belongs in its own slice with layout-fixture regeneration.

Two things this cost nothing to learn and would have been expensive to ship: the paint oracle earns
its runtime (a golden diff would have looked like a correct keyword appearing), and a finding that
names a defect still does not name where the fix belongs — the second time this register has shown
that, after `UML-NOT-7`.

## UML-XMI-14 and UML-XMI-15 are refuted

Group 6's two findings were the only ones the plan said could not be decided locally: the register
recorded the corpus as *silent, not contradicting*, and asked for the OMG DD 1.1 and MOF 2 XMI
Mapping specifications. Fetched 2026-08-12 with the user's approval. Both findings are wrong, and
acting on either would have broken interoperability.

**`UML-XMI-15` — the "mixed convention".** The finding observed that `dc:Bounds` is capitalised like
a type name while `di:waypoint` is lowercase like a property name, and reasoned that since DD/DI
serialises properties, at most one can be right. The premise is sound and the conclusion is wrong,
because the two elements are declared differently: `DC.xsd` declares `<xsd:element name="Bounds"
type="dc:Bounds"/>` as a **global** element — global elements are conventionally named after their
type — while `waypoint` is a **local** element inside `DI.xsd`'s `Edge`, typed `dc:Point`, and
`DI.xsd` sets `elementFormDefault="qualified"`, which is what gives it the `di:` prefix. Both
spellings are the schemas' own. The lowercase `bounds` the corpus attests is the *metamodel*
property name, which is a different artifact from the serialization.

**`UML-XMI-14` — the namespace dates.** The finding read `20100524` as a stale DD 1.0 reference,
with DD 1.1 the current version. DD 1.1 is indeed current and its machine-readable files are stamped
`20131001` — but that stamp is on the **metamodel XMI**, and never became an XML namespace. The
DD *serialization* schemas keep `targetNamespace="http://www.omg.org/spec/DD/20100524/DI"` (and
`.../DC`), and every deployed DD-based dialect — BPMN DI most visibly — serializes into them.
Changing the constants to `20131001` would emit documents no DD-aware tool can read.

Both are now pinned by `DiagramWriterConformanceTest`, with the reasoning in the test rather than
only in a comment, because "1.1 looks newer than 1.0" is exactly the inference a future reader will
make unprompted — this register made it.

What remains genuinely open is unchanged and is a different question: no UML tool has been observed
*rendering* the dialect. That is the Papyrus/EA import probe already recorded in §12.

## Post-plan followups

Work deliberately carried out of this plan and recorded so it is not lost. `F1` is a tooling defect
surfaced *by* the remediation; the rest are register findings whose fix needs a surface this plan
does not build, and each names that surface rather than the finding.

The distinction from `## Disposition` below: that table holds findings whose answer is "not this way"
or "not at all". These are findings whose answer is "yes, but it needs a prerequisite".

| # | Item | Approach | Why deferred |
|---|---|---|---|
| ~~F1~~ | **CLOSED — decided.** `RasterGoldenTest.updateManifest` rewrote the whole tracked `raster-golden/manifest.json` to change one SHA, because the writer's style and the file's did not match. | **The regenerator owns the format.** Verified 2026-08-12: the tracked file is now in `writerWithDefaultPrettyPrinter` style throughout (2-space indent, `" : "` field separator), so writer and file agree and a one-value regeneration lands as a one-line diff — which is the review signal the golden discipline depends on. The alternative (teaching the writer the old hand-authored style) was rejected: the same `writerWithDefaultPrettyPrinter` pattern is used by `LayoutFixtureRegenerator`, `Bench` and `DistTool`, so the repo-wide convention should win over one file's history. | Nothing left to do — the one-time churn was spent by `1d09b75`. |
| ~~F2~~ | **CLOSED.** A notation-semantics diagnostic channel. | `NotationSemantics.validate` now returns `List<Diagnostic>`; the router already returned `EngineResult` with a diagnostics list and was passing `List.of()`, so only the seam was closed. `AM-VOCAB-9` reports the §12.1-deprecated `WorkPackage -[Realization]-> Deliverable` as `info`, and `UML-SEM-22`/`-23` drop from errors to `warn` — both were dediren's layout rules enforced as UML legality, rejecting the partial-order interleavings §17.1.3 permits. | Closed 2026-08-12 rather than deferred. The deferral reasoning — "an engine-contract change across three modules for one info and two warns" — measured the blast radius without checking it: the diagnostics list already existed one layer up. |
| F3 | **The §B.4 domain dimension for ArchiMate legality.** `AM-SEM-3` (composition/aggregation decided on category rather than element type), `AM-SEM-4` (`Product` and `Plateau` share the composite category, so `Product -[Composition]-> BusinessActor` passes) and `AM-SEM-7` (Strategy and Implementation-&-Migration elements are classified structurally, so no rule can see a §B.4 domain crossing) are one finding wearing three faces: the model carries category and no layer. | Classify the 60 elements by layer/domain and add the crossing rules §B.4 names. `AM-SEM-3`'s stated fix — tightening `s == t` to element-type equality — is wrong as written: it would reject a business process composed of business functions, which is legal. | Every one of these narrows what validates, and the only oracle that can prove a narrowing introduces no false rejection is the Appendix-B triples file, which is copyrighted and never committed. Narrowing the model with that gate unavailable is how the class's "never rejects a valid combination" promise gets broken quietly. Run it with `-Ddediren.archimate-oracle` in hand. |
| ~~F4~~ | **CLOSED — no change warranted.** `AM-SEM-6` — which relationship types may pass through a junction. | Investigated 2026-08-12 rather than deferred. Two findings: the register's reproduction (`BusinessProcess -[Specialization]-> OrJunction -[Specialization]-> BusinessProcess`) is not an endpoint leak at all — the junction walk resolves the through-pair and validates it, and `BusinessProcess -[Specialization]-> BusinessProcess` is legal — so the cited symptom does not demonstrate the claim. And the exchange schema models `RelationshipConnectorType` as a plain `ElementType` with no relationship-type restriction. | The §5 intro / Table 21 prose that could support a restriction is behind The Open Group's SSO and was not obtainable. Adding one on this evidence would invent a rejection. **Trigger:** if the spec text becomes available and names a restricted set, add it at the connector boundary in `Archimate.validateRelationshipEndpointTypes`, where element↔connector edges return early today. |
| ~~F5~~ | **CLOSED.** `ExecutionSpecification` export, and the `DOC-22` residue it left. With `UML-SEM-29` closed, `valid-uml-sequence-lifecycle.json` still cannot be exported whole: it also carries an `ExecutionSpecification`, which `unsupportedSequenceNode` refuses as a declared MVP limitation. So a `valid-`-prefixed, bundle-shipped fixture — the richest sequence template an agent can reach via `dediren://fixture/source/` — still validates, renders, and dies at export. | Emits `uml:BehaviorExecutionSpecification` with its `covered` lifeline and its `start`/`finish` occurrences, each resolved to the occurrence *on that lifeline* — the receive event when the message arrives there, the send event when it leaves, since the source names the bounds by message rather than by occurrence. Written after the message occurrences so every reference is already declared. `Gate` remains the one unsupported sequence node, having no source surface to attach a message to. | Closed 2026-08-12 rather than deferred: deferring it meant shipping a documented trap. `valid-uml-sequence-lifecycle.json` now exports whole, has an XMI golden — so the golden-iterating structural invariants finally cover it, which is precisely why `UML-SEM-29` could hide there — and `docs/features/source-model.md` says every `valid-` fixture survives the whole pipeline, because now it does. |

## Status

_Written past-tense as phases land._

- Phase 0: **landed** — register committed (`d560c55`), worktree `.worktrees/notation-conformance`
  on branch `notation-conformance`, this plan written.
- Phase 1: **landed** — `AM-NOT-1/-2/-3/-4/-5/-6/-7/-11` closed. Render+cli lane green (1444 tests)
  and the full paint lane green (`RasterGoldenTest`, `RasterDiffTest`, all three `SvgPaintAudit*`).
  Both goldens re-baselined once each and read before committing: the SVG golden moved by exactly
  two things (the Realization dash pattern, and the ApplicationService icon becoming a pill), and
  the raster manifest by one SHA.
  **What the RED steps showed, because it is the reusable part:** every one of these defects was
  invisible to a green suite, and each for a different reason. `AM-NOT-1/-2` — the coverage test read
  the policy fixture and asserted the SVG matched *that same fixture*, so it could only confirm the
  fixture agreed with itself, and it had no `dotted` arm at all. `AM-NOT-6` — stripping the policy's
  edge table failed on **Flow alone**, because the generic house default is already a filled arrow,
  so Triggering was passing by coincidence. `AM-NOT-11` — no test compared the three service icons
  to *each other*, so the application layer's differing y and height sat unnoticed beside two that
  agreed.
  **Accepted (info):** `AM-NOT-12`'s per-layer letter cue stays unimplemented — optional in spec,
  and colour already carries the layer. Deferred to the disposition table: `AM-NOT-8/9/10`.
- Phase 2: **landed** — `UML-NOT-2/-3/-4/-5/-7/-8`, `UML-VOCAB-1` and the `sd` half of `UML-NOT-9`
  closed. Render+cli+uml+uml-xmi-export green; full paint lane green. Goldens: four sequence SVGs,
  one class SVG, one deployment SVG, one raster manifest SHA — each diff read before committing.
  **Two findings did not land, both deliberately:** `UML-NOT-1` was implemented, proven wrong by the
  paint oracle, and reverted (§ The UML-NOT-1 reversal); `UML-NOT-12` is bound to the 2026-07-28
  dark-policy wave, which owns two further sites and the missing golden coverage.
  **Accepted (info):** `UML-NOT-9`'s `entryPoint` named circle stays unimplemented — the recorded
  `ARCH-L-004` label won't-fix means a pseudostate name never renders, so a "named circle" cannot be
  produced without reopening that decision.
- Phase 3: **landed** — `UML-XMI-12/-13/-16/-21` and `DOC-25/-26` closed. Full `./mvnw test` green.
  The assurance now reports what the schema actually said: `SchemaValidation` returns a structured
  `SchemaOutcome` instead of collapsing three results into one free-text diagnostic, and
  `status="not-validated"` is emitted on the path where the schema rejected the content and the
  export rode the no-normative-UML-XSD gap. The schema gained the missing `xmi-envelope-only`
  guard, so the rung actually in use is no longer the only one carrying no constraint.
  **Three findings were refuted rather than fixed** — `UML-XMI-2` (its wordings *are* pinned),
  `UML-XMI-18` (its "schema-valid slot" is not valid there), and the sizing of
  `UML-XMI-5`/`UML-VOCAB-7` (a writer restructure, not a rename). Each has its own section or
  disposition row. **Deferred:** `AM-OEF-11` as a contract slice of its own; `UML-XMI-20`'s UMLDI
  golden and `UML-XMI-4`'s version escape hatch, both of which want the UMLDI dialect settled first
  and are now cheaper because `-12`/`-13` are fixed.
  **Method note.** The new `not-validated` branch was initially unasserted: the assurance test
  drives a *lax* stub schema, which accepts everything and so never reaches the gap path. Adding a
  strict stub — what the real pinned XSD does — was the missing half, and it was verified to fail
  against the old constant before being kept. A test can exercise the right code and still miss the
  branch that matters.

  **Method note.** Where Phase 1's defects were all invisible-to-a-green-suite, Phase 2's were
  invisible in a second way: two of them had *existing tests asserting the defect*. The deployment
  test pinned the Artifact dog-ear on a DeploymentSpecification, and the sequence test pinned the
  absence of a dash on a createMessage. Both had to move with the fix. A test that encodes current
  behaviour is not evidence that the behaviour is correct — and it reads exactly like one.
- Phase 4: **landed** — `AM-OEF-1/-2/-3/-4/-5/-6/-8/-9/-10/-12`, `AM-VOCAB-4` and `DOC-9` closed,
  in three commits (`5329be8` clamp-and-guard, `38249c0` containment, `18df646` property types and
  viewpoints). Full `./mvnw test` green after each.
  **The XSD check the phase was gated on settled it in one read:** `ModelType` declares both
  `<elements>` and `<relationships>` `minOccurs="0"`, and only their *children* are mandatory. So
  `AM-OEF-1/2` were the guard-the-wrapper branch, not the early-reject branch — dediren was
  emitting an empty container the format never asked for.
  Geometry is rounded and clamped by a named `OefGeometry` collaborator that owns both the
  transformation and its disclosure, so a clamp cannot be added without a `warn` naming the value
  and its `$.layout_result...` path. `OefProperties` does the same for the property lane: it decides
  each definition's `DataType` from the values the key carries and reports every non-scalar it has
  to flatten. Containment became a tree walk rather than a single level, because
  `LaidOutGroup.members()` can name another group and ELK does lay out group hierarchies; ownership
  resolves to the *nearest semantic* ancestor, so visual-only bands are transparent to it.
  **The gate that mattered was not the default suite.** The stub XSDs the default lane uses are
  fully permissive `xs:any` wrappers — they cannot reject anything, so none of the five block
  findings was expressible there. Each fix is pinned twice: on emitted bytes in the default lane,
  and against the real pinned Open Group XSD set in `RealSchemaConformanceTest`, which grew from one
  happy-path fixture to six structural cases. Reverting the emitter under the real lane reproduced
  the register's own errors verbatim (`cvc-complex-type.2.4.b` on both wrappers,
  `cvc-minInclusive-valid` on the coordinate), which is what proves the new cases are not vacuous.
  **Accepted (info):** `AM-OEF-13` — the 3.1 XSD types relationship endpoints as bare `xs:IDREF`,
  so it is endpoint-blind and can never corroborate ArchiMate legality; nothing to fix in the
  emitter. **Deferred:** `AM-OEF-11` (own contract slice, carried from Phase 3b).
  **Method note.** Phase 2's "existing test asserting the defect" recurred once more, in a milder
  form: `preservesNodeAndRelationshipPropertiesViaOefPropertyDefinitions` pinned `type="string"` for
  a `confidence` of `0.4`. It was not asserting the defect on purpose — it was asserting the *shape*
  of the definitions block and swept the type in with it. A byte-exact assertion pins everything it
  contains, including the parts nobody chose.
- Phase 5: **landed** — both ArchiMate blocks and nine of the UML findings closed across six
  commits (`c26d754`, `751dc24`, `5c8f27f`, `0ff1dbd`, `fb15355`, `42f19dd`). Full `./mvnw test`
  green after each, plus `-Pdist-smoke` and two rounds of end-to-end probes through a rebuilt
  bundle.
  **5a — ArchiMate:** `AM-SEM-1/-2/-5/-10/-11/-12`, `AM-VOCAB-5`, and the citation half of
  `AM-SEM-8`. The `UNIVERSAL` short-circuit turned out to be worse than the register described: it
  ran before the category lookup, so it also bypassed the engine's *own* §5-contradicted carve-out —
  the combinations `isSpecContradictedByFive` names as always-rejected were passing. §B.6's
  universality is conditional, so the rule now asks whether any element could stand in the
  composite's place, which keeps the class's promise never to reject a valid combination: an edge is
  refused only when it is impossible in that direction for *every* element there is.
  **5b — UML:** `UML-SEM-1/-2/-3/-4/-5` (one six-name predicate standing in for three spec type
  systems, wrong in both directions at once), `-8`, `-13`, `-15`, `-16`, `-17`, `-19`, `-24`, `-29`.
  **Sizing corrections, continuing the pattern.** `UML-SEM-29` was sized as relaxing two validators
  with no emitter change, because `writeMessageOccurrence` already emits the destruction subtype. It
  does — but `covered` was read from the message's *target*, which for a deletion is the marker
  node, not a lifeline. `AM-SEM-3`'s stated fix (tightening category equality to element-type
  equality) is wrong as written: it would reject a business process composed of business functions.
  `AM-SEM-6`'s reproduction is not an endpoint leak at all — the junction walk *does* resolve and
  validate the through-pair, and the pair in question is legal.
  **The "existing test asserting the defect" count reached six**, and one of them
  (`acceptsJunctionAsContainmentSourceInGrouping`) asserted it deliberately, in a comment. Two more
  contradicted a §5 invariant asserted elsewhere in the same suite.
  **What was verified rather than reasoned about.** Both ArchiMate leaks and three UML rules were
  probed through a rebuilt bundle, not just a green suite. The self-model was *not* usable as
  evidence: it uses visual-only layout bands and contains zero `Grouping` or `Location` nodes, so it
  exercises none of the changed rules — a reminder that "the dogfood model still validates" can be
  true and mean nothing.
  **Deferred, each naming the surface it needs:** `F2` (a notation-semantics diagnostic channel,
  carrying `AM-VOCAB-9` and the `UML-SEM-22`/`-23` demotion), `F3` (the §B.4 domain dimension,
  carrying `AM-SEM-3/-4/-7`), `F4` (`AM-SEM-6`), `F5` (`ExecutionSpecification` export and the
  `DOC-22` residue). **Not approximated:** the `local`/`external` transition kinds, the fork/join
  segment guard rules and the entry/exit/terminate degree constraints all turn on composite States
  owning Regions, which the vocabulary cannot express — a Region names a StateMachine, never a
  State.
- Phase 6: **landed** — `DOC-1`, `-3`, `-4`, `-5`, `-6`, `-7`, `-8`, `-10`, `-11`, `-12`, `-13`,
  `-14`, `-15`, `-16`, `-17`, `-19`, `-20`, `-21`, `-23`, `-24` closed (`a420f59`). Written last so
  each claim describes what actually landed. `DOC-9`, `-25` and `-26` were closed by the phases that
  fixed them.
  The two structural changes: `visibility` and the whole `attributes[]`/`operations[]` surface had
  **zero occurrences** across README, agent-usage, features and schemas despite being live authored
  fields, so a new `## UML Class Handoff` gives them a home; and the UML export contract was filed
  under `## ArchiMate Handoff`, where `dediren_guide` misdelivered it, so it becomes
  `## UML Export Contract` with matching `uml-class` / `uml-export` topics.
  A new `### What a green command can still cost you` collects the diagnostics that report loss
  rather than something to fix, and names the two losses that have no diagnostic at all.
  **`DOC-22` is stated, not closed:** the lifecycle fixture is listed with the reason it cannot be
  exported. Adding it silently — which is what this plan originally proposed — would have made the
  finding worse.
- Phase 7: **landed** — `AM-SEM-9` given the provenance it never had (in the test, beside the
  constant, including that the test is `assumeTrue`-skipped so in an ordinary build it gates
  nothing); `UML-XMI-14`/`-15` **refuted** against the OMG's published DD schemas, fetched with the
  user's approval, and pinned so the refutation cannot be undone by a plausible-looking upgrade; and
  a §12 block recording the seven accepted remainders, each with the condition that reopens it.
  **The fetch changed the outcome.** Both Group 6 findings looked right and were wrong, and both
  "fixes" would have emitted documents no DD-aware tool can read. That is the strongest case in this
  whole remediation for checking a source rather than reasoning from a plausible premise — and it is
  the sixth register finding to survive as a real observation with a wrong conclusion attached.
