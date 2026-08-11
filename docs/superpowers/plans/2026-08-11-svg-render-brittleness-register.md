# 2026-08-11 SVG render brittleness register

Diagnostic register for the SVG production lane (`engines/render`), measured at `da86bcd`.
**No code was changed.** This document exists so the decision about what to fix is made against
evidence rather than against the feeling that the module is unpleasant to work in.

Findings are grouped by the three axes in scope, then re-cut by **regression probability** in
`## Suggested groupings`, following the Group 1 / Group 2 shape of
`docs/superpowers/plans/2026-07-28-audit-remediation.md`.

## Scope

| Axis | In scope | Prefix |
| --- | --- | --- |
| A | Output-correctness drift — mechanisms that silently produce wrong pixels | `SVG-DRIFT-n` |
| B | Code structure — shapes that make the module unsafe to change | `SVG-SMELL-n` |
| D | Input trust — unconstrained contract fields reaching emitted SVG | `SVG-TRUST-n` |

**Deliberately out of scope: test-suite quality.** It is not audited here, but it prices every
fix in this document — see `## Blast-radius multiplier`.

Findings already carried by `docs/architecture-guidelines.md` §12 or by the 2026-07-24 /
2026-07-28 audits are marked and cited rather than re-raised as new. Accepted decisions — notably
the `lean-audit:dup-intentional` parallel icon builders (`ArchimateIcons.java:15`,
`UmlShapes.java:15`) — are **not** flagged.

## Correction to the initiating triage

The triage that opened this work claimed the two bounds passes in `SvgDocument` were a latent
divergence in both the edge and node lanes. That was wrong in one direction and understated in
the other, and the register supersedes it:

- **The edge lane agrees today.** Both passes (`SvgDocument.java:161-193` and `:390-411`) iterate
  the same list in the same order, seed `placedLabelBoxes` empty, append label then adornment
  boxes in the same sequence, and derive the same font size. Given a deterministic
  `StyleResolver` they produce identical results. The risk there is latent, not live.
- **The node lane has already drifted.** `SVG-DRIFT-2` below is a real, reachable bug.

So the honest characterisation of the duplication is *"one arm already broken, the other latent"*.

## A — Output-correctness drift

Severity is about the *output*, not the code: `block` = reachable, visibly wrong output on
plausible input.

| id | finding | evidence | sev | symptom | trigger |
| --- | --- | --- | --- | --- | --- |
| `SVG-DRIFT-1` | **Bounds computed twice.** Render and bounds passes independently re-resolve styles, re-place edge labels and re-derive adornments. Latent in the edge lane, already broken in the node lane. | `SvgDocument.java:159-193` vs `:369-416` | warn | — (see `SVG-DRIFT-2` for the live arm) | any edit to one pass |
| `SVG-DRIFT-2` | **`label_align` is applied when rendering but ignored when computing bounds.** `nodeLabel` moves `textX` to the node edge and switches `text-anchor`; `nodeLabelBox` unconditionally centres on `position.x()` with `minX = x - width/2`. **Verified by hand.** | `node/NodeLabels.java:46-53` vs `:242-246`, consumed at `SvgDocument.java:384` | **block** | Bounds box sits centred while ink sits flush left/right. On an edge-most node with an overflowing label the viewBox grows on the wrong side and the text is clipped. | `nodes: { label_align: "end" }` on the right-most labelled node |
| `SVG-DRIFT-3` | **Line jumps have no minimum-spacing guard.** The per-segment filter is a window check only; each jump emits `L (y−6) Q … (y+6)`, so two jumps closer than 12px make the pen travel backwards. **Verified by hand.** | `svg/EdgeRenderer.java:223-243`, `svg/LineJump.java:7-20` | **block** | Visible zigzag knot instead of clean hops. | one orthogonal edge crossing ≥2 parallel edges spaced <12px (ELK Layered default edge spacing ≈10px) |
| `SVG-DRIFT-4` | **`NODE_LABEL_MIN_FONT_SIZE = 9.0` defeats the fit-to-box shrink, then `textLength` pins the overflow exactly.** The clamp discards the computed fit; the pin makes the overflow deterministic rather than approximate. | `node/NodeLabels.java:100-111`, `:90-95`, `:199-204` | warn | Label overhangs the node border both sides and runs under the ArchiMate corner icon. | 60×30 node labelled with one long unbreakable all-caps token |
| `SVG-DRIFT-5` | **Labels are emitted at `font-weight: 600` but measured against a Helvetica *regular* advance table.** Bold runs ~3-6% wider. | `svg/Svg.java:139-231` vs `svg/EdgeRenderer.java:34,148,160,171`, `UmlSequenceRenderer.java:244,481` | warn | Bold text pokes past both ends of its own `background` backing plate (5px padding, consumed at ~4% of a 250px label). | any edge with `label_presentation: "background"` and a label ≳40 chars |
| `SVG-DRIFT-6` | **The width estimate is pinned for node labels and unpinned everywhere else.** Only `NodeLabels.java:93` emits `textLength`; edge labels, adornments, group titles, message labels, lifeline names and fragment operators do not. | `node/NodeLabels.java:90-95` vs `svg/EdgeRenderer.java:142-174`, `SvgDocument.java:145-156`, `UmlSequenceRenderer.java:277-284,473-484` | warn | Where Inter/Arial are absent the substituted font (DejaVu Sans is materially wider) grows unpinned labels past their computed boxes while node labels in the *same* file stay put — visibly inconsistent drift. | rendering on a host without Inter/Arial, e.g. a stock Fedora container |
| `SVG-DRIFT-7` | **Group title text is excluded from `svgBounds` entirely** — only the group rect is included, and `label_size` goes up to 96. | `SvgDocument.java:372-374` vs `:145-156`; `schemas/render-policy.schema.json:351-354` | warn | The right-most group's title runs off the viewBox and is clipped mid-word. | `groups: { label_size: 40 }` on a group ELK sized for the default |
| `SVG-DRIFT-8` | **Sequence-lane operand separators collapse when operand content boxes are empty**, and guard text is positioned from the collapsed separator. | `UmlSequenceRenderer.java:784-795`, consumed at `:406-437` | warn | Multiple separators drawn at identical y and guards overprinted — the fragment appears to have fewer operands than the model declares. Silently lossy. | an `alt` fragment with operands that carry a guard but no messages |
| `SVG-DRIFT-9` | **Lifeline-head labels get none of the generic lane's label machinery** — no wrap, no fit-shrink, no pin — and `bounds()` contributes only the head rect. | `UmlSequenceRenderer.java:277-284`, `:547-549` vs `node/NodeLabels.java:97-138` | warn | Long lifeline names overflow the head box, overprint the neighbour, and clip at the viewBox on the outermost lifeline. | a lifeline named e.g. `AuthenticationTokenService` on a 120px head |
| `SVG-DRIFT-10` | **Dark-policy paint cluster** — hollow markers hardcode `#ffffff`; edge-label halo uses the page background even inside a group; the line-jump erase mask assumes a flat opaque backdrop. | `svg/EdgeMarkers.java:30`; `SvgDocument.java:178,186`; `svg/EdgeRenderer.java:62,72-86` | warn | White blobs and halo plates on dark canvases; opaque patches over gradient/translucent groups. | built-in dark policy + UML aggregation; or a `fill_gradient` group crossed by two edges |
| `SVG-DRIFT-11` | **`width`/`height` emitted at `%.0f` while `viewBox` is `%.1f`**, independently per axis, so the declared aspect ratio differs from the viewBox's. | `SvgDocument.java:80-90`; same at `UmlSequenceRenderer.java:85-95` | info | Default `preserveAspectRatio` letterboxes; the background rect scales with the content, leaving an unpainted hairline — a visible light seam on dark policies. | any diagram whose padded bounds land on fractional sizes |
| `SVG-DRIFT-12` | **End adornments land on opposite sides of the line at the two ends of the same edge**, because the perpendicular is derived from each end's inward vector. | `svg/EdgeEndAdornments.java:114-119` with `:134-153` | info | Source multiplicity below the line, target multiplicity above it. Reads as a layout bug. | a horizontal UML association with both multiplicities set |
| `SVG-DRIFT-13` | **Node labels placed *outside* the node box are invisible to the edge-label obstacle model** — `nodeObstacleBoxes` emits node rects only. | `svg/Geometry.java:26-33` vs `node/NodeLabels.java:175-194` | info | An edge label placed "clear" prints on top of a junction or decision-node label. Nothing is clipped, just illegible. | a UML activity view with a labelled decision node and a labelled incoming edge |
| `SVG-DRIFT-14` | **A diagonal segment contributes its whole axis-aligned bbox as a label obstacle**, unlike the 12px strip used for orthogonal segments. | `svg/Geometry.java:74-81` | info | One long diagonal blanks a large canvas region for every other label search, pushing labels to the ±140px far offsets — the "walk the label off the diagram" failure the hug-first ordering at `EdgeRenderer.java:37-41` was added to prevent. | any view with a long diagonal/spline route plus several labelled edges |
| `SVG-DRIFT-15` | **`<marker>` omits `markerUnits`**, so the SVG default `strokeWidth` applies and markers scale with stroke width — contradicting the class comment's fixed "10x10" box. | `svg/EdgeMarkers.java:47-54`, comment at `:17-22` | info | `stroke_width: 24` (schema max) yields a 240-unit arrowhead engulfing the target node; at `0.5` markers nearly vanish. Unmodelled in bounds. | `edges: { stroke_width: 6 }` with `marker_end: "filled_arrow"` |
| `SVG-DRIFT-16` | **`svgBounds` includes raw geometry only** — no stroke half-width, no marker extents, no jump-arc bulge, no rounded-corner overshoot. `margin` has `minimum: 0`. | `SvgDocument.java:372-381`, `svg/SvgBounds.java:33-43`, `schemas/render-policy.schema.json:29-32` | info | At zero margin the outermost stroke is shaved to half width and a boundary jump apex is clipped flat. | `"margin": {"top":0,...}` on a diagram whose extreme element is stroked |
| `SVG-DRIFT-17` | **`labelNumber` floors while `f1` rounds**, and the floored font size is paired with a `textLength` computed from the unfloored size. | `svg/Svg.java:81-85` vs `:37-39`; `node/NodeLabels.java:60,71,91` vs `:236` | info | Auto-shrunk labels read as slightly loose-tracked; floored `dy` drifts ~0.05px/line against the bounds model. | a node whose fit-shrink yields a non-round size with a 5+ line label |
| `SVG-DRIFT-18` | **Junction circle radius has a `max(4.0, …)` floor that can exceed the node half-extent**, but bounds include only the node rect. | `node/NodeShapeSupport.java:99-101` vs `SvgDocument.java:295-306,381` | info | The circle overhangs its own node box; at `margin: 0` an edge-most junction clips to a flat-sided disc. | an ArchiMate `and_junction` laid out at 6×6 |

**Already registered:** `SVG-DRIFT-10` is `2026-07-28-audit-remediation.md:134` (Group 2 row 5,
deferred). `SVG-DRIFT-7` is *partly* registered — row 10 (`:139`) covers the group-title
**obstacle** strip vs `label_size`; the **bounds/clipping** side recorded here is not.

**Ruled out** after tracing (recorded so they are not re-investigated): the edge-lane arm of
`SVG-DRIFT-1`; `ARCHIMATE_ICON_TOP_INSET` as a drift surface (both sites import the one constant,
and the cross-module `ARCHIMATE_LABEL_ICON_RESERVE` sibling *is* test-enforced by
`ArchimateLabelReserveConsistencyTest`); actor-icon overrun of the label reserve; `backdropFillAt`
group iteration order (correctly reverse-matched to paint order); and `SvgWriter` numeric emission
(all formatting is caller-side). `renderCombinedFragments`' explicit total order
(`UmlSequenceRenderer.java:396-407`) is the counter-example to the order-dependence pattern and is
the fix template for it.

## B — Code structure

| id | finding | evidence | sev | what breaks on a routine change |
| --- | --- | --- | --- | --- |
| `SVG-SMELL-1` | **`renderSvg` is a ~160-line god-procedure** with three inline loops mixing bounds math, style resolution, gradient wiring and markup. | `SvgDocument.java:69-228` | block | Any single concern requires reading and re-verifying the whole method; an edge-label change risks the unrelated group-decorator block three loops away. |
| `SVG-SMELL-2` | **The two pipelines duplicate and have already diverged.** `UmlSequenceRenderer.pathData` independently reimplements `EdgeRenderer.roundedPathData` **but drops corner rounding and line-jump masking entirely**. **Verified by hand.** | `UmlSequenceRenderer.java:448-469,886-898` vs `svg/EdgeRenderer.java:93-119,195-294` | block | A route-rendering fix applied to `EdgeRenderer` silently does not reach sequence messages, and nothing says so. `SVG-DRIFT-3`'s fix, for instance, would not apply there. |
| `SVG-SMELL-3` | **The two lanes disagree on `RenderPolicy` nullability for the same field.** `SvgBounds.padded` dereferences `policy.margin()` unguarded; `SvgBox.padded` defaults every side to `16.0`. **Verified by hand.** | `svg/SvgBounds.java:45-51` vs `UmlSequenceRenderer.java:956-962`; nullable at `contracts/…/RenderPolicy.java:7`, schema-required at `schemas/render-policy.schema.json:6` | warn | One of the two is wrong *today*. Relax the schema, or construct a `RenderPolicy` in-process, and pipeline A throws NPE while pipeline B silently pads 16px. No comment or test declares which posture is intended. |
| `SVG-SMELL-4` | **Wrapper-`<g>` style precedence is a comment-only rule with two structurally different implementations.** Pipeline A wraps the shape in a `<g>`; pipeline B puts the same fields directly on the shape element. | `SvgDocument.java:61-65,204-218` vs `UmlSequenceRenderer.java:123-135,161,179,224,242,324,344` | warn | A contributor "fixing" the documented precedence in `SvgDocument` cannot discover the second mechanism encoding the same policy fields differently. No test signals the divergence. |
| `SVG-SMELL-5` | **`EdgeEndAdornments` is UML-specific but lives in the notation-neutral `svg` package and is called unconditionally for every profile** — no `semantic_profile` guard. | `svg/EdgeEndAdornments.java:15-16,55-59`, called at `SvgDocument.java:183-190` | warn | A generic or ArchiMate render whose edge properties happen to carry `source_role` silently gets UML association adornments. Adding an ArchiMate equivalent has no natural home. |
| `SVG-SMELL-6` | **Path geometry is printf strings** — 89–90 `String.format` sites in `src/main`, 42 in `ArchimateIcons`; `event-pill` threads 22 positional doubles through one format string. | `node/archimate/ArchimateIcons.java:200-230` and throughout | warn | Editing a glyph's proportions means recomputing positional arithmetic inline; a dropped or reordered `%.1f` compiles cleanly and draws a wrong shape, catchable only by visual diff. |
| `SVG-SMELL-7` | **`ResolvedNodeStyle` is a 17-field positional record** with several same-typed adjacent fields, constructed positionally in three places. | `style/ResolvedNodeStyle.java:13-30,36-55`; `style/StyleResolver.java:148-170` | info | Adding or reordering a field means updating every positional call site by counted position; transposing two same-typed neighbours (`fill`/`labelFill`, `strokeWidth`/`rx`) compiles and only shows as a wrong-looking render. |
| `SVG-SMELL-8` | **Number formatting is not centralized** despite `svg/Svg.java` existing for it — the SVG-root `width`/`height`/`viewBox` formatting is hand-written in *three* places. | `SvgDocument.java:80-90,246-255,276-279`; `UmlSequenceRenderer.java:86-104` | info | A precision or locale change must be hunted inline. This is also the mechanism behind `SVG-DRIFT-11`. |

**Not flagged (accepted):** the `lean-audit:dup-intentional` parallel icon/shape builders
(`ArchimateIcons.java:15`, `UmlShapes.java:15`) and the structurally identical style in
`ArchimateShapes`/`GenericShapes`. `ARCHIMATE_LABEL_ICON_RESERVE`'s cross-module coupling is
comment-documented **and** test-enforced, unlike `SVG-SMELL-3`/`-4`'s comment-only rules — that
contrast is the point, not a finding.

### Dependency shape

```
SvgRenderEngine.render()
  ├─ RenderInputValidator.validate()
  └─ SvgDocument.renderSvg()                     ← pipeline A (generic / ArchiMate / UML non-sequence)
       └─ if UmlSequenceRenderer.isSequence(metadata) → pipeline B, returns at SvgDocument.java:71-73

shared by both:  style.StyleResolver · svg.SvgWriter · svg.SvgAccessibleName · svg.EdgeMarkers · svg.Svg (partly)
A only:          svg.EdgeRenderer · svg.Geometry · svg.SvgBounds · svg.EdgeEndAdornments
                 node.NodeLabels · node.NodeShapeSupport
                 node.archimate.* · node.uml.{UmlShapes,UmlDecorators} · node.generic.GenericShapes
B only:          node.uml.UmlSequenceModel + private reimplementations of
                 pathData (SVG-SMELL-2) · boxAttrs (SVG-SMELL-4) · SvgBox (SVG-SMELL-3)
```

The `svg` package is a genuine leaf — no file in it imports `node.*` (`Geometry.java:14-17`
states the rule). `SVG-SMELL-5` does **not** break that stated rule; it breaks the package's
cohesion and lacks a profile guard, which is why it is recorded as a guard defect rather than an
architecture violation.

## D — Input trust

### The headline: the render lane validates nothing about its layout input

This axis was opened expecting the finding to be *"`layout-result.schema.json` forgot the id
pattern that `model`/`layout-request` both have"*. That is true (`SVG-TRUST-2`) but largely
beside the point, because of `SVG-TRUST-1`:

> **No command anywhere validates a layout result against `layout-result.schema.json`.**
> `layout-result` is not a `KnownSchemaVersions` family (`KnownSchemaVersions.java:167-169`) and
> has no `DocumentValidator.SCHEMA_FILES` entry (`DocumentValidator.java:34-39`).
> `CoreCommands.renderCommand` (`:338`) binds the JSON with Jackson and runs the policy *version*
> gate — nothing else. **Verified by hand.**

So every constraint the schema *does* carry — `width`/`height` `exclusiveMinimum: 0`, the `role`
enum, `source_pointer: "^/"`, the point shape — is unenforced on the lane that turns a layout
result into an artifact. Only Jackson's `FAIL_ON_UNKNOWN_PROPERTIES` survives.

The asymmetry is the sharp part. The geometry check **exists**, is exposed as its own
`dediren validate-layout` command (`Main.java:385`), and runs on every lane that produces layout
*internally* — `build` (`BuildCommand.java:431`) and `build --package`
(`PackageBuildCommand.java:268`), both via `CoreCommands.validateLayout`. It is absent from
`render`, **the only lane that accepts a layout result from outside and renders it**. That is
backwards: the lane with the untrusted input is the lane with no check.

`docs/threat-model.md:49-51` already concedes the layout result is "agent-suppliable" and lists
`validate-layout`/`build` as the lanes that check it. The omission of `render` is not documented.

### Findings

| id | finding | evidence | sev | what a bad value produces |
| --- | --- | --- | --- | --- |
| `SVG-TRUST-1` | **The render lane applies no JSON Schema and no geometry check to the layout result.** | `CoreCommands.java:338`; `KnownSchemaVersions.java:167-169`; `DocumentValidator.java:34-39`; contrast `BuildCommand.java:431`, `PackageBuildCommand.java:268` | **block** | every existing layout-result constraint is unenforced on `render` |
| `SVG-TRUST-2` | **`layout-result` ids carry no charset and no uniqueness constraint**, while `model.schema.json:40` and `layout-request.schema.json:22` both pin `^[A-Za-z0-9][A-Za-z0-9._-]*$`. Moot in practice until `SVG-TRUST-1` is fixed. | `schemas/layout-result.schema.json:21,38,59` | warn | — (enabling condition for `-3` and `-4`) |
| `SVG-TRUST-3` | **Duplicate ids are possible and produce duplicate SVG element ids.** No uniqueness check in schema, `RenderInputValidator`, or `LayoutQuality`; `SourceValidator`'s `DUPLICATE_ID` guards the *source model* only. `markerEnd` defaults to `FILLED_ARROW` (`StyleResolver.java:38`), so **every** edge mints an id — the sink is not opt-in. | `RenderInputValidator.java:39-51`; `SourceValidator.java:277`; `svg/EdgeMarkers.java:48` | warn | two edges sharing `e1` → two `<marker id="marker-end-e1">`; a UA binds both refs to the first, so the second edge silently paints the first's arrowhead. Well-formed XML, invalid SVG. |
| `SVG-TRUST-4` | **A merely sloppy id silently breaks the `url(#…)` reference.** `XmlText.scrub` + XML escaping make the document well-formed; neither makes an id a resolvable reference target. | mint `svg/EdgeMarkers.java:48`; refs `svg/EdgeRenderer.java:110-118`, `SvgDocument.java:117,202`, `UmlSequenceRenderer.java:465-468` | warn | edge id `a)b` → `url(#marker-end-a)b)`; the CSS url token closes at the first `)`, resolving a non-existent `#marker-end-a` → **marker silently dropped, no diagnostic**. An id with a space fails the same way. |
| `SVG-TRUST-5` | **Non-finite geometry reaches SVG attributes on `render`.** `LayoutQuality.java:138-177` has exactly this check and its comment names exactly this vector — but `renderCommand` never calls it. | `svg/Svg.java:37,47,82` emit `"Infinity"`/`"NaN"` | warn | `"width": 1e400` → `width="Infinity"`, `viewBox="… Infinity …"`, `textLength="NaN"`. `SvgAudit.assertFiniteGeometry:171-190` asserts this cannot happen; production does not enforce it here. |
| `SVG-TRUST-6` | **The render-policy schema is never applied on any rendering lane** — `parsePolicy` runs the version gate only — and `accessibility.*` has no runtime validation at all. | `CoreCommands.java:439-447`; `RenderInputValidator.java:399-439` never visits `accessibility`; sink `svg/SvgAccessibleName.java:39-40` | warn | `"dir": "rtl;x"` or a 10 kB `lang` reaches `direction=`/`xml:lang=`. **Contradicts `docs/threat-model.md:290-296`**, which claims these are "bounded before emission, by the schema" — true only for the package lane (`package.schema.json:60,65`). |
| `SVG-TRUST-7` | **The corpus never varies ids, so the asserted properties have never been tested.** `RenderFuzzTest` fuzzes labels across 8 adversarial values but hard-codes ids as `n0..n8`/`e0..`/`g0`. `SvgAudit.assertUniqueIds` and `assertReferencesResolve` have therefore never seen a duplicate or hostile id. | `RenderFuzzTest.java:24-33` vs `:61-62,72-75,86-87`; `SvgAudit.java:113-119,122-169` | warn | the textbook shape of this axis: properties *asserted* over a corpus chosen to satisfy them, with zero production enforcement |
| `SVG-TRUST-8` | **`SchemaCongruenceTest` twins `role` and `provenance` between layout-request and layout-result but not `id`** — the one test positioned to catch `SVG-TRUST-2` declines to look at it. | `SchemaCongruenceTest.java:59-65`; twinning added by `2026-07-28-audit-remediation.md:39-40` | info | the divergence stays green forever |
| `SVG-TRUST-9` | **Schema and runtime colour grammars diverge, schema-looser.** `dediren validate` accepts colours `dediren render` rejects. Opposite wrinkle: schema `maxLength` counts UTF-16 units, `validateFontFamily:600` counts code points. | `schemas/render-policy.schema.json:41` vs `RenderInputValidator.java:557-561` | info | `"fill": "rgb()"` validates, then fails at render with `DEDIREN_SVG_POLICY_INVALID` |
| `SVG-TRUST-10` | **Zero/negative `width`/`height` reach `rect`.** The schema's `exclusiveMinimum: 0` is unenforced (`SVG-TRUST-1`) and `LayoutQuality` checks finiteness but never positivity — so this survives even the `build` lane. | `LayoutQuality.java:141-177`; sinks `SvgDocument.java:126-127,335-336` | info | `width="-50.0"` is a spec error → element not rendered, and the computed `viewBox` is skewed |
| `SVG-TRUST-11` | **`render-metadata.schema.json` is enforced by nothing** — no family, no `SCHEMA_FILES` entry — and `selector.sourceId` reaches an attribute unguarded. | `SvgDocument.java:111` | info | arbitrary text in `data-dediren-group-source-id`; escaped, so consumer-confusion only |
| `SVG-TRUST-12` | **`font_family` has a length guard but no charset guard** in either layer. | `schemas/render-policy.schema.json:70-74`; `RenderInputValidator.java:595-605` | info | `Arial; fill:red` lands in the `font-family` presentation attribute. Per the CSS attribute-parsing rule the `;` invalidates the single property rather than creating a second declaration — **spec-inferred, not runtime-verified** |

**Already registered:** unbounded label text and unbounded CLI stdin are explicitly accepted at
`docs/threat-model.md:357`; not re-raised.

**Sound, checked, no gap** (recorded so the next audit does not re-walk them): colours
(`validateColor` is anchored and admits no `url(`, `;`, or quote), gradients, dash patterns,
numeric policy fields, and all policy enums (unknown literals fail at Jackson deserialization
before validation). `source_id`/`projection_id` are unconstrained but **provably never reach the
SVG** on this lane — they are live inputs for the export lanes, which this axis did not cover.
`view_id` is unconstrained but reaches a text node only, where escaping genuinely is sufficient.

### Threat-model verdict: needs one amended row and one corrected sentence

1. **Row `docs/threat-model.md:358`** ("Inject markup into a rendered SVG via model labels/**ids**")
   offers `SvgWriter` escaping as its control, and §"SVG output escaping" (`:271-272`) repeats
   "labels and ids … every value is XML-escaped". That control is correct and complete for labels
   and for ids used as text or opaque attribute content. But ids are *also* used as **SVG
   identifiers and reference targets**, a use for which XML escaping is not the relevant property.
   The realistic failure is not breakout — it is silent wrongness (`SVG-TRUST-3`, `-4`). The
   residual column should say so.
2. **`docs/threat-model.md:290-296`** states `accessibility.lang`/`.dir` are "bounded before
   emission, by the schema". That is **factually wrong for the `render` and `build` lanes**
   (`SVG-TRUST-6`) and true only for the package lane. Correct or narrow it.
3. **`docs/threat-model.md:49-51`** lists `validate-layout`/`build` as the lanes that check the
   agent-suppliable layout result. Add the explicit statement that `render` does not
   (`SVG-TRUST-1`).

## Blast-radius multiplier — the excluded test axis

Not audited, but it prices everything above. `MainTest.java` is **4431 lines** with ~300
assertions, many substring-matching raw SVG (`contains(">Draft<")`,
`contains("data-dediren-edge-marker-start=\"filled_diamond\"")`), over **16 byte-identical
goldens** in `engines/render/src/test/resources/golden/`.

Consequences for sequencing:

- Any fix that changes emitted bytes — `SVG-DRIFT-2`, `-3`, `-7`, `-11`, most of `SVG-SMELL-1..4`
  — regenerates goldens and cascades into substring assertions. The *diff review* is the cost,
  not the edit.
- The paint lane (`engines/render/src/paint-test/`, `./scripts/test-render-paint.sh`) is the
  right oracle for the geometry findings and is already built; `SvgAudit` already implements
  `assertUniqueIds`, `assertReferencesResolve`, `assertFiniteGeometry`, and
  `assertGeometryWithinViewBox`. **The assertions for most of axis D already exist — only the
  inputs are missing.**

## Suggested groupings

By regression probability, following the 2026-07-28 shape. **No recommendation on what to ship.**

**Group 1 — additive, byte-identical for currently-valid input.** Lowest risk; no golden churn.
`SVG-TRUST-1`, `-3`, `-4`, `-5`, `-10` (validation and diagnostics that only reject input that is
already broken) · `SVG-TRUST-7`, `-8` (test inputs that make the gaps executable) · the three
threat-model corrections · `SVG-SMELL-5`'s missing `semantic_profile` guard.

The cheapest confirming experiment for the whole of axis D: add an `IDS` list to `RenderFuzzTest`
mirroring its existing `LABELS` list (`a)b`, `a b`, `a"b`, a repeated id, an empty id) and run
`./mvnw -pl engines/render -am test -Dtest=RenderFuzzTest`. `SVG-TRUST-3` and `-4` should surface
as `assertUniqueIds` / `assertReferencesResolve` failures **without touching production code**.

**Group 2 — changes rendered bytes.** Golden regeneration plus self-model regeneration, and the
paint lane as the oracle. `SVG-DRIFT-2`, `-3`, `-4`, `-5`, `-7`, `-8`, `-9`, `-11` ·
`SVG-DRIFT-10` (already deferred at `2026-07-28-audit-remediation.md:134`) · `SVG-SMELL-2`
(unifying the path builder *is* a rendered-byte change for sequence messages — it adds the
rounding and jump masking they currently lack).

**Group 3 — needs a decision before it can be implemented.** `SVG-SMELL-3` (which nullability
posture is correct — one of the two lanes is wrong today, and nothing declares which) ·
`SVG-DRIFT-6` (pin every text run, or drop the pin and widen bounds — a document-wide policy
choice) · `SVG-DRIFT-15` (is the fixed marker box the intent, or is stroke-scaling?) ·
`SVG-SMELL-1` and the pipeline unification behind `SVG-SMELL-2`/`-4`, which are refactors whose
scope depends on how much of Group 2 is being done anyway.

## Method and honesty notes

- Measured at `da86bcd` by three parallel read-only investigations (drift, structure, trust),
  synthesized and spot-verified centrally. No production code was read into a change.
- **Hand-verified by the synthesizer, not just reported:** `SVG-DRIFT-2`, `SVG-DRIFT-3`,
  `SVG-SMELL-2`, `SVG-SMELL-3`, `SVG-SMELL-5`, `SVG-TRUST-1`, and the `LayoutQuality` call graph
  behind `SVG-TRUST-5`. Both cross-references into `2026-07-28-audit-remediation.md` were checked
  against the cited rows.
- **Not runtime-verified — no code was executed for this register.** `SVG-DRIFT-3`'s zigzag is
  derived by hand-evaluating the emitted path string. `SVG-TRUST-4`'s dropped marker is reasoned
  from the CSS url-token rule (corroborated by `SvgAudit.java:44` encoding the same rule).
  `SVG-DRIFT-5`'s bold-advance magnitude is from typographic general knowledge, not measured —
  the `-Prender-paint` Java2D oracle could measure it directly. `SVG-TRUST-12` is spec-inferred.
  Treat these as high-confidence but unexecuted.
- One correction absorbed during synthesis: an investigator reported `LayoutQuality` as called
  directly from `BuildCommand`/`PackageBuildCommand`; it is reached via
  `CoreCommands.validateLayout`. The conclusion stands, the mechanism differs.
- **Line numbers will drift.** Findings are anchored to `da86bcd`; re-resolve before acting on
  any single citation.
