# UML Sequence Combined-Fragment Chrome Spacing Design

Date: 2026-07-07

## Purpose

Stop combined-fragment chrome (the operator header, operand separator lines, and
operand guards) from colliding with message labels inside `alt`/`opt`/`loop`/`par`
fragments in UML sequence diagrams.

This is "Defect C" from the poc-uljas investigation, deliberately deferred while
"Defect A" (the empty band) was fixed. The empty-band fix
(`docs/superpowers/specs/2026-07-07-uml-sequence-empty-band-design.md`) did not
change the fragment-internal geometry, so these collisions persist.

## Root Cause

Messages are placed on a uniform 24px vertical lattice by
`SequenceLayoutConstraints.normalizedMessageYSlots`
(`MESSAGE_HEAD_GAP=24`, `MESSAGE_Y_STEP=24`). Combined fragments are excluded from
the ELK graph (`GenericGraphProjection.isSourceOnlySequenceFragment`) and their
frame, operator tab, operand separator lines, and guard labels are synthesized at
render time (`UmlSequenceRenderer.renderCombinedFragments` /
`renderOperandSeparatorsAndGuards`) from the laid-out message geometry. Because the
message lattice reserves no vertical budget for that chrome, the chrome lands on top
of the messages.

Observed on the real (fixed-A) uljas `seq-balance` `alt`:

| element | y | collides with |
|---|---|---|
| `alt` operator label | 407 | "GET saldotiedustelu" message (400) |
| guard `[saldo ennallaan]` | 428 | "PUT saldotiedusteluvastaus" message (424) |
| operand separator line | 468 | "PUT muutoserä" message (472) — the divider strikes the label |
| guard `[saldo muuttunut]` | 488 | ~16px from neighbours (tight) |

## Decisions

- The layout-normalize pass reserves the vertical room the render chrome needs at
  fragment/operand boundaries, so the message-lattice fix lands in the same geometry
  owner as the empty-band fix.
- The renderer's vertical chrome spacing participates too. The header band and the
  first-operand guard are anchored to the frame top (`content.minY - vertical padding
  - header`), so a gap reserved only *between messages* does not by itself separate
  the operand-0 guard from the fragment's first message. The renderer's
  `FRAGMENT_VERTICAL_PADDING` (and, if the render loop shows it, the guard vertical
  offsets) grow in coordination with the normalize gaps. The frame/separator/guard
  *computation approach* and all horizontal geometry stay unchanged; only the vertical
  spacing constants move.
- The projection tells the layout which messages open a fragment or a non-first
  operand, via new layout constraints. The data already exists in the source
  `CombinedFragment`/`InteractionOperand` nodes; the projection reads it even though
  those nodes are excluded from the layout node set.
- Reserve a targeted gap at fragment/operand boundaries only. Do not widen the
  global message pitch — simple fragment-free diagrams must not grow.
- Single-level fragments are the guaranteed-covered case; the first-message
  resolver recurses through a nested fragment when an operand's first member is a
  fragment rather than a message.
- No public schema or contract change: `LayoutConstraint(id, kind, subjects)` is an
  existing shape; two new `kind` values are added.

## Changes

### 1. Projection: emit fragment-boundary message constraints

In `GenericGraphProjection.projectLayoutConstraints` (UML_SEQUENCE branch, which
already emits `uml.sequence.lifeline-order` and `uml.sequence.message-order`), also
emit:

- `uml.sequence.fragment-open` — subjects = message ids that are the first message
  of a combined fragment (the first message of the fragment's first operand, by
  operand `order`).
- `uml.sequence.operand-open` — subjects = message ids that are the first message of
  each non-first operand.

Computed from the source: for each `CombinedFragment` in the view, take its
`operands` (sorted by the operand's `order`); the first operand's first message is a
fragment-open; every later operand's first message is an operand-open.
"First message of an operand" = the first entry in `InteractionOperand.fragments`
that is a message relationship in the view; if that entry is a nested fragment id,
recurse into that fragment's first operand.

### 2. Normalize: reserve leading gaps for marked messages

`SequenceLayoutConstraints` reads the two new constraints (alongside lifeline-order
and message-order). `normalizedMessageYSlots` changes from
`headBottom + MESSAGE_HEAD_GAP + MESSAGE_Y_STEP*index` to an accumulator that adds,
before a marked message, an extra leading gap:

- `FRAGMENT_OPEN_GAP` before a fragment-open message (room for the 24px header band
  + the first operand's guard).
- `OPERAND_OPEN_GAP` before an operand-open message (room for the separator line +
  that operand's guard).

Starting constants (pinned by the render-verification loop below):
`FRAGMENT_OPEN_GAP ≈ 30`, `OPERAND_OPEN_GAP ≈ 40`. Rationale: the header band (24px,
anchored `content.minY - FRAGMENT_VERTICAL_PADDING(18) - FRAGMENT_HEADER_HEIGHT(24)`)
must clear the previous message (gap > ~18), and an operand guard placed at
`separatorY + fontSize + 6` must fit between the operand separator midpoint and the
operand's first message (gap > ~32).

### 3. Renderer: vertical chrome spacing tuned to match the reserved gaps

`UmlSequenceRenderer` keeps its frame/tab/separator/guard *computation approach* and
all horizontal geometry. Its vertical spacing constants are tuned so the chrome fits
the reserved room:

- `FRAGMENT_VERTICAL_PADDING` (currently 18) grows so the header band + operand-0
  guard clear the fragment's first message. With the current `frame.y =
  content.minY - FRAGMENT_VERTICAL_PADDING - FRAGMENT_HEADER_HEIGHT(24)` and the
  operand-0 guard at `frame.y + FRAGMENT_HEADER_HEIGHT + fontSize`, the guard lands
  at `content.minY - FRAGMENT_VERTICAL_PADDING - 24 + 24 + 14 = content.minY -
  FRAGMENT_VERTICAL_PADDING + 14`; the first message label is at `content.minY - 8`,
  so the guard clears the label only when `FRAGMENT_VERTICAL_PADDING` exceeds ~24
  with margin (start ~34).
- The normalize `FRAGMENT_OPEN_GAP` is then sized so the taller header band clears
  the previous message (gap must exceed `FRAGMENT_VERTICAL_PADDING + a small
  margin`).

Exact values for `FRAGMENT_VERTICAL_PADDING`, `FRAGMENT_OPEN_GAP`, and
`OPERAND_OPEN_GAP` are pinned together by the render-verification loop below (they are
coupled). No new render code paths — only constant/offset changes.

## Testing

- Layout test (`ElkLayoutEngineTest`): a sequence request carrying
  `uml.sequence.fragment-open` / `uml.sequence.operand-open` constraints — assert the
  Y jump before a marked message exceeds the plain `MESSAGE_Y_STEP` by the reserved
  gap, and unmarked messages keep the plain step.
- Regenerate the golden `fixtures/layout-result/uml-sequence-fragments.json` from the
  real engine + fix so downstream render tests see realistic spacing. Keep its
  `render-metadata` and source fixture consistent.
- Collision test (`SequenceFragmentAlignmentTest`): for the fragments fixture, assert
  no `data-dediren-sequence-operand-separator` line and no
  `data-dediren-sequence-operand-guard` label falls within a tolerance band of any
  `data-dediren-sequence-message-label` Y. This is the check the golden fixture
  previously could not make.
- Real-render verification: re-render uljas `seq-balance` (the `alt` view) and the
  repo fragments fixture; confirm no separator/guard/header overlaps a message label.
- Verification lanes: `./mvnw -pl plugins/generic-graph -am test` (projection),
  `./mvnw -pl plugins/elk-layout -am test` (normalize), and
  `./mvnw -pl plugins/render,cli -am test` (collision + fixture regen), plus a full
  `./mvnw test` before completion.

## Files That Move Together

`plugins/generic-graph` (constraints + test), `plugins/elk-layout`
(`SequenceLayoutConstraints` + `ElkLayoutEngineTest`), `plugins/render`
(`UmlSequenceRenderer` vertical spacing constants + `SequenceFragmentAlignmentTest`),
and the regenerated `fixtures/layout-result/uml-sequence-fragments.json` (+ its
metadata/source if they drift). No public schema or contract change.

## Out of Scope

- The empty-band defect (already fixed).
- The fragment closing border vs. the next message (~2px near-miss, not a hard
  collision) — add a close-gap only if the render-verification loop shows a real
  overlap.
- Deep multi-level fragment nesting beyond first-message resolution — single-level is
  guaranteed; deeper nesting is a follow-up if evidence shows a gap.
