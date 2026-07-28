# 2026-07-28 Audit remediation — second-pass workaround audit (47 findings)

Remediation plan for the 2026-07-28 second-pass workaround/tech-debt audit (47 verified
findings — 1 block / 24 warn / 22 info — measured at `d23fe57`, the same HEAD as the
2026-07-24 baseline audit). Findings are split by **regression probability**, not only
severity:

- **Group 1 (low regression risk — execute now, this plan's active scope):** docs/dead-code,
  purely additive diagnostics/validation, changes byte-identical for currently-valid input,
  and all fixes to the **unreleased package lane** (#63 merged after the v2026.07.27 tag, so
  its contract can still be corrected without breaking any consumer).
- **Group 2 (high regression risk — DEFERRED, recorded below):** geometry/rendered-byte
  changes (goldens + self-model regeneration), released-contract tightenings, and items
  needing a design decision or compatibility probe first.

Constraint that shaped sequencing: land Group 1 **before** the release that first ships #63.

## Audit gates

Per `CLAUDE.md ## Audit Gates`, before calling this work complete run:

- `test-quality-audit` — **quick**, scoped to the tests added/changed by Group 1.
- `devsecops-audit` — **quick**, scoped to the trust-boundary diff (ProvenanceCheck bounds +
  confinement, JsonSupport pin removal, release.yml edit, threat-model updates).

Fix block findings; fix or explicitly accept warn/info in the handoff.

## Group 1 — executed by this plan

Wave/agent split (disjoint file ownership; single central build afterward):

**A. contracts/engine-api/archimate docs + seam guards**
- `LayoutNodeRole` javadoc: role IS enum-constrained (2ecaa1d); only the direct
  `dediren layout` lane bypasses schema validation. [info]
- `NotationSemantics.layoutRole` javadoc: enumerate all five roles. [info]
- `RelationshipLegality`/`Archimate` javadocs: qualify "never rejects a valid combination"
  with the documented §5-contradicted exception set (bc1b997). [info]
- Remove the `FAIL_ON_NULL_FOR_PRIMITIVES=false` pin in `JsonSupport` (justifying fixtures
  provably absent); add a test pinning the structured failure for null-primitive geometry. [warn]
- Extend `SchemaCongruenceTest` to guard the role-enum + group-provenance defs between
  layout-request and layout-result. [info]

**B. package lane (unreleased — contract fixes are free)**
- Apply the `CLASS_FAMILY_KINDS` gate when assembling XMI view layouts for model exports in
  `PackageBuildCommand`, mirroring `BuildCommand:270` (restores the §12 UMLDI containment
  premise; removes package reachability of two export defects). [warn]
- Provenance-stamp package artifacts in `writeOutput`, mirroring the twin's stamp semantics
  (svg → `stampSvg`, xml/xmi → `stampXml`). [warn]
- Add duplicate-id validation across models/views/exports id spaces
  (`DEDIREN_PACKAGE_DUPLICATE_ID`) + Repair Rules entry. [warn]
- Add `migration` to `package-build-result.schema.json`'s diagnostic def (mirror
  build-result); fixture carrying a migration diagnostic + stale-policy package test. [warn]
- Exit-code alignment with the build twin: artifact-write failure and policy-read failure →
  `INPUT_ERROR`; keep package's `SCHEMA_INVALID + INPUT_ERROR` for malformed policy (build's
  `PLUGIN_ERROR` there is commented legacy) and document the deliberate divergence. [info]

**C. orchestration trust posture**
- `ProvenanceCheck.modelSha`: bounded read (`BoundedReads` + `SourceLimits`; over-limit → not
  indexed). [warn]
- Confinement root plumbed through `statusCommand` → `ProvenanceCheck` (mirror
  `verifyCommand`); fix `AnalysisCommands`' false "status loads no source" javadoc; align
  threat-model wording. [warn]
- `dediren mcp --help`: describe the real 7-tool surface + read-only set; sweep the baseline
  "three tools" javadocs (`DedirenTools`, `ToolSchemas`). [info]
- `Provenance.stampXml`: correct the false "cannot occur" comment (escape behavior kept). [info]

**D. elk-layout packed mode**
- Warning diagnostic when `mode:packed` drops layered-only preferences / per-node hints;
  delete the dead `applyNodeHints`/`activatePartitioning` writes in `layoutPacked`. Hard
  gate deliberately NOT added (Group 2 candidate). [info]

**E. export engines (mechanical/additive)**
- `ActivityWriter`: type + activity-membership edge filter (mirror sibling writers);
  two-activity regression test. [warn]
- `StateMachineWriter`: adopt the sibling endpoint-presence guard. [warn]
- `XmiHelpers`: mint multiplicity `-lower`/`-upper` ids through the shared unique/claim
  mechanism (byte-identical for non-colliding input). [info]
- `XmiBuilder`: info diagnostic when a classifier label is claimed twice (homonym typing). [info]
- `OefExportEngine.exportModel`: compute declared-vs-supplied views and emit
  `OEF_VIEWS_OMITTED` instead of hardcoding "nothing omitted". [info]

**F. render (byte-identical under defaults)**
- `UmlDecorators`: honor `label_opacity` on compartment/stereotype/tab/actor text
  (font-family/weight/style deferred — metric side effects). [info, split]
- `SvgDocument` grouping-node lane: user dash first, `"3 2"` fallback, shared constant with
  the group lane. [info]
- `UmlSequenceRenderer`: document the sort-derived styling narrowing (boxDash precedent);
  prune the dead override rows from `fixtures/render-policy/uml-svg.json`. [info, split]

**G. build/dist/infra**
- Fix `fixtures/source` `required_plugins` drift (22 entries → product version) **and add a
  dist-tool guard test** so the manual sweep can never silently fail again. [warn]
- Dist-smoke harness: drain child stdout/stderr concurrently at all three capture sites
  (pipe-deadlock hazard). [warn]
- pom: correct the orphaned `quality.fail` / "CI runs report-only" prose (property stays as a
  local-only toggle); add `archimateoef`/`mcp`/`schemacache` packages to the PIT
  `targetClasses`. [info ×2]
- Delete dead `findAnyLibJar`; fix the stale xmllint keep-rule rationale in
  `ProGuardLibShrinker`; drop the unused jq install from the release build job. [info ×3]

**H. semantics/notation**
- Router-side structural validation for the four ghost-reference classes (view node id, view
  relationship id, group member outside view, unknown `semantic_source_id`) as
  `structuralFailure` envelopes mirroring `SceneProjection`'s exact throw conditions —
  converts today's `DEDIREN_ENGINE_FAILED` (exit 3) into structured input errors (exit 2);
  fix the router javadoc's false "engine defects" framing; per-check tests + a lane-agreement
  test. Only already-failing inputs change classification. [warn]
- Delete the dead FinalState-source duplicate in `Uml.validateTransitionRegionConsistency`
  (shadowed by the endpoint-type table on every shipped path). [info]

**Docs integration (after A–H):** `docs/agent-usage.md` (Repair Rules for every new
`DEDIREN_*` token — `AgentUsageDocConsistencyTest` gates both directions — package-section
scoping for the DI gate and stamps, OEF aggregate claim, RelationshipLegality qualification)
and `docs/threat-model.md` (status confinement/bounded rows). Same-change rule per
`CLAUDE.md ## Files That Move Together`.

**Verification:** `./mvnw test`; `-Pquality verify` (spotless:apply first);
`-pl dist-tool -am verify -Pdist-smoke`; targeted lanes for elk/render/export modules; then
the two audit gates above.

## Group 2 — DEFERRED backlog (high regression risk)

Do these as separate evidence-driven waves. Each wave regenerates affected goldens and, where
output changes, the self-model (`docs/architecture/dediren.dediren`; new SVGs need
`git add -f`). Severity in brackets; **coordination notes bind waves to related baseline
(2026-07-24) findings** so shared surgery happens once.

| # | [Sev] Item | Approach | Why deferred |
|---|---|---|---|
| 1 | [block] UML package node double label — `NodeShapeSupport.java:39` | Decide the UML 12.2.4 form first: likely suppress the TAB text (body-only name) rather than adding UML_PACKAGE to the supplies-list, because the tab label already overflows the 14.4px tab. Real-render evidence, regenerate goldens + self-model. | Visual change + a small design decision; blind one-liner risks an uglier result |
| 2 | [warn] Merge-port role-discriminator flip on back-edge reversal — `ElkLayoutEngine.java:594` | One **PortPlan wave** together with baseline merge-port warns (`:905` hashCode keying → key by the type string; `:917` maxIndex+1 vs descending WEST). §12 SD-B-1 already names PortPlan extraction as the trigger. Needs a back-edge+merge test (none exists). | Port identity/geometry changes; three interacting defects fixed together or not at all |
| 3 | [warn] Explicit `partition` vs band-ordinal collision — `ElkLayoutEngine.java:283` | Decide semantics (reject explicit partitions in banded views, or offset ordinals past them, or defined precedence + diagnostic); document in `docs/features/layout.md`. Do in the **banded-lane wave** with the baseline block (`:311` back-edge reversal reuse) — same code region, one surgery. | Layout behavior change over open model input |
| 4 | [warn ×2] Normalizer-vs-core must-agree pairs — `LayoutIntentNormalizer.java:520` (execution-bar endpoints vs perimeter check) and `:229` (frame x-extents vs enclosure invariant) | Pick a side per pair: near-edge landing for execution anchors (marker-twin precedent at `:499-510`) or role-aware acceptance in `LayoutQuality`; x-sweep or hook-aware margin for the frame. Layout-request fixtures + invariant tests. If parked long, register both as ONE §12 pair. | Sequence geometry changes; hand-authorable lane needs fixtures that don't exist yet |
| 5 | [warn ×3] Render dark-policy cluster — hollow markers `#ffffff` (`EdgeMarkers.java:30`), label-halo page background in groups (`SvgDocument.java:171`), erase-mask flat-opaque assumption (`EdgeRenderer.java:82`) | One render wave: markers fill from resolved background (per-edge markers exist, so backdrop-aware fill is reachable); label/adornment lane adopts `backdropFillAt` (9e28237 precedent); mask-vs-gradient needs design (sample / disable jumps / path knockout). Add the missing dark-policy golden coverage that let all three survive. Regenerate self-model. | Visible byte changes incl. light-fixture goldens (halo); mask-gradient genuinely hard |
| 6 | [warn] Directional `marker_start` renders backwards — `EdgeMarkers.java:54` | Probe `auto-start-reverse` support in the rasterizers/viewers relied on (SvgAudit lane); fall back to mirrored start geometry. Latent (shipped fixtures symmetric). | Viewer-compatibility unknown; visual change |
| 7 | [warn] Fragment-open gap under-reserve for both-sets rows — `UmlSequenceConstraints.java:168` | `max(FRAGMENT_OPEN_GAP, OPERAND_OPEN_GAP)` (or sum) for rows in both sets; add the missing nested-in-non-first-operand fixture; render evidence (rows shift ~22px). | Sequence golden churn; needs the fixture to prove pixels |
| 8 | [warn] `exportModel` drops `validateExportableSequenceScope` — `XmiExportEngine.java:142` | Run the scope validation per view in the model lane (post-Group-1 the package path is class-family-gated, but model-level sequence content still flows). Release-notes-worthy tightening: inputs that silently corrupted now reject. | Contract tightening on a released lane |
| 9 | [warn ×2] XMI id plumbing — diagram id bypasses IdentifierMap (`DiagramWriter.java:64`, + validate policy `views[]` overrides) and lifeline dual-emission on interaction mismatch (`InteractionWriter.java:69`, needs a validation-vs-emit-once decision) | One id-integrity wave; also improve `DEDIREN_XMI_ID_INVALID` attribution (blames generated XML, hampers agent self-repair — noted by the audit verifier). | Id semantics + new policy rejections need deciding together |
| 10 | [info] Group-title obstacle strip 24px vs `label_size` ≤ 96 — `Geometry.java:24` | Either thread label size into the obstacle computation (breaks Geometry's policy-free charter — design) or register in §12 with a trigger. | Seam design decision |
| 11 | [info] Honor sequence message style overrides — `UmlSequenceRenderer.java:911` (second half) | Decide precedence (sort-derived vs user override) then implement; visual change. | Notation-correctness vs user-intent tradeoff |
| 12 | [info] Compartment font-family/weight/style — `UmlDecorators.java:144` (second half) | Only with the metric interplay solved: bold/family changes text extent against the pinned cross-module compartment sizing (`UmlCompartmentMetricsConsistencyTest`). | Metric side effects on fixed compartments |
| 13 | [info] `mode:packed` hard gate — `ElkLayoutEngine.java:1644` | Only if the Group-1 warning diagnostic proves insufficient; a hard reject narrows a released lane. Correct the algorithm-gate plan's "mode is algorithm-agnostic" premise when touched. | Contract narrowing |
| 14 | [info] `stampXml` `--` round-trip — `Provenance.java:66` (behavioral half) | Optional: teach `extract` to unescape `&#45;`; nothing reads stamped `view_id` today. | Artifact-byte change for near-zero consumer value |

Baseline (2026-07-24) findings are otherwise out of this plan's scope; their block
(`layoutFlatBanded` back-edge reversal) is expected to ride wave 3's banded-lane surgery.

## Status

- Group 1: **landed** (branch `audit-remediation`, 2026-07-28). Full `-Pquality verify` and
  `-Pdist-smoke` green. Both audit gates ran (quick): the shared high-tier finding (no
  regression test on the bounded status read) was fixed with a discriminating over-ceiling
  test (verified to fail against the pre-fix read); the devsecops warn (threat-model rows for
  package stamps + duplicate-id gate) was fixed, and the package id charset was aligned with
  the schema family while the lane is unreleased. **Accepted findings (info):** no dedicated
  DistTool drain test (a reintroduced sequential drain hangs `-Pdist-smoke` loudly — the
  opposite of silent false confidence); render `label_opacity` test covers the classifier
  site only (actor/package-tab/stereotype sites unexercised — fold into the Group 2 render
  wave); DistTool stderr-drain thread swallows non-IOException death (tooling, low). One
  residual code-choice wrinkle for wave 2/9: a model-scoped uml-xmi export over zero
  class-family views surfaces as `DEDIREN_ENGINE_FAILED` (pre-existing package-lane
  pattern) rather than a structured input error.
- Group 2: deferred — waves 1–9 are fix-ready with approaches above; 10–14 need decisions.
- Release: cut the next release only after Group 1 lands (first release shipping #63) —
  Group 1 is now landed, so the next release may proceed.
