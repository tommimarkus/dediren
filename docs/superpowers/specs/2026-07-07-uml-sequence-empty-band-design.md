# UML Sequence Empty-Band Fix Design

Date: 2026-07-07

## Purpose

Remove the large empty vertical band that appears above the lifeline heads in
every UML sequence diagram. Today the interaction frame reserves roughly the top
half of the canvas as blank space and pushes the lifeline heads and all messages
into the bottom ~40%.

The defect was confirmed in the shipped `2026.07.8` bundle by rendering the
`uljas` architecture package (`seq-submission`, `seq-change`, `seq-balance`,
`seq-maksukielto`) through the full projection -> ELK -> render pipeline. This
design covers only that empty-band defect. The separate combined-fragment
collision defect (operand separators/guards overlapping message labels inside
`alt`/`opt`/`loop`/`par`) is intentionally out of scope and will get its own
design.

## Root Cause

`GenericGraphProjection` emits the `Interaction` as a flat, fixed-size
(360x260) layout node with **no edges** connecting it to the lifelines
(messages only connect lifelines/executions). In ELK's `Direction.RIGHT`
layered layout the interaction node is therefore a **disconnected component**
that ELK stacks at the top (`y=12`), while the connected lifeline+message
component lands far below (`y~=312-324`).

Observed real ELK output for `seq-submission`:

```
ix-sub (Interaction)  x=12  y=12   w=360 h=260   <- stacked alone at the top
ll-sub-hakija         x=77  y=324  w=140 h=48    <- lifelines land at y=324
ll-sub-relay          x=374 y=324
ll-sub-uljas          x=703 y=324
```

`SequenceLayoutConstraints.normalize` already rewrites the lifeline columns and
message rows onto a synthetic grid, but it never touches the interaction node.
`UmlSequenceRenderer.calculateInteractionFrame` then anchors the frame top to
the stray node via `Math.min(node.y(), content.minY() - INTERACTION_TOP_PADDING)`
(= `min(12, 324-40) = 12`), producing the ~300px empty band. The lifelines and
messages themselves are laid out correctly.

## Decisions

- The layout-normalize pass owns the interaction node's geometry, alongside the
  lifeline and message geometry it already owns. Fix the geometry in the
  `layout_result`, not in one renderer, so every consumer (layout-quality
  checks, XMI export, any renderer) sees a faithful interaction box.
- Keep `elk-layout` type-agnostic at the ELK level: ELK still runs unchanged;
  correction happens in the existing post-ELK `SequenceLayoutConstraints`
  normalize pass that already synthesizes sequence geometry.
- Do not model the interaction as an ELK compound parent and do not give
  fragments ELK nodes (the rejected "ELK-first hierarchy" direction). The
  normalize pass overwrites child geometry anyway, and ELK-layered does not
  model sequence time-ordering, so a hierarchy buys little for real risk.
- Identify the interaction node explicitly by `role`, not by heuristics.
- Do not change `UmlSequenceRenderer`. Its content-clamping frame math already
  produces a tight frame once the interaction node is sane.
- Single interaction per view is the supported case. Multiple interactions per
  view remain unsupported; they degrade to sharing one wrapped band, which is no
  worse than today's stray boxes.

## Changes

### 1. Projection: tag the interaction node with a role

`GenericGraphProjection.layoutRole` currently returns `"lifeline"` for
`Lifeline` and `null` for everything else (including `Interaction`). Add:
`Interaction` -> `"interaction"`. This is consistent with the existing comment
that roles are carried so backend-neutral, role-aware geometry rules can find
the relevant nodes. No schema change: `role` is an existing optional field on
layout nodes.

### 2. Normalize: wrap the interaction node around the content band

In `SequenceLayoutConstraints.normalize` (only when `active()`), after the
lifeline band and message Y-slots are computed, reposition every
`role="interaction"` node so it encloses the content band:

- `x` = leftmost lifeline column `x` minus a horizontal margin.
- `width` = (rightmost lifeline column `x` + width + margin) minus the new `x`.
- `y` = lifeline band top (`bandY`).
- `height` = (last message row Y + bottom slack) minus `y`; when there are no
  messages, fall back to the lifeline head band height.

All inputs are already computed inside `normalize` for the lifeline/message
grid; this reuses them. The interaction node keeps its id/label/role; only its
box changes. Non-interaction, non-lifeline nodes still pass through untouched.

### 3. Render: no change

`UmlSequenceRenderer.calculateInteractionFrame` keeps clamping the frame to
content (`min(node.y, content.minY - INTERACTION_TOP_PADDING)`, etc.). With the
interaction node now inside the content band, the frame hugs the lifelines with
the intended `INTERACTION_TOP_PADDING`, and the empty band disappears.

## Testing

- Extend `ElkLayoutEngineTest` with a real-engine sequence case (interaction +
  ordered lifelines + ordered messages + the lifeline-order/message-order
  constraints). Assert the normalized interaction node's top is within a small
  tolerance above the lifeline heads (no ~300px gap) and that it horizontally
  spans all lifelines. This is the failing test that reproduces the defect
  before the fix.
- Oracle already exists: golden `fixtures/layout-result/uml-sequence-basic.json`
  encodes the desired shape (interaction `y=32`, lifelines `y=72`). After the
  fix, real ELK output through normalize should match that shape.
- Reconcile `SequenceFragmentAlignmentTest` and `SvgAppearanceAuditTest` if they
  assert current (buggy) interaction geometry. Regenerate any sequence
  layout-result fixtures that captured the stray box, using
  `-Ddediren.elk.render-artifacts=true` where applicable.
- Verification lanes (per CLAUDE.md): `./mvnw -pl plugins/elk-layout -am test`
  for the normalize change, `./mvnw -pl plugins/render,cli -am test` for render
  reconciliation, and a manual re-render of the four `uljas` sequence views to
  confirm the band is gone.

## Files That Move Together

`plugins/generic-graph` (role), `plugins/elk-layout` (normalize +
`ElkLayoutEngineTest`), and any affected `fixtures/layout-result/uml-sequence-*`
and render appearance tests. No public schema or contract change.

## Out of Scope

- Combined-fragment chrome collisions inside `alt`/`opt`/`loop`/`par` (separate
  design): messages sit on a uniform 24px pitch with no reserved vertical budget
  for fragment headers, operand separators, or guards.
- Any change to ELK options, routing, or the message/lifeline grid itself.
- Multiple-interaction-per-view support.
