# UML Sequence Self-Message Geometry — Design

**Status:** approved 2026-07-13. Fixes a live, user-facing defect.

## Problem

**Dediren cannot build a UML sequence diagram that contains a self-message.** A self-call
(`source` lifeline == `target` lifeline) is a common, legal UML construct — `validate --profile uml`
returns `ok` — but `build` hard-fails with exit 2 and `status: error`.

Reproduced on a three-lifeline source whose middle message is `service → service`:

```
DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER  edge 'm2' first route point is not on source node 'service' perimeter
DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER  edge 'm2' last route point is not on target node 'service' perimeter
DEDIREN_LAYOUT_SEQUENCE_INVARIANT_VIOLATED        'message_endpoints_on_lifeline_axis' violated by 'm2':
                                                  first route point x=252.0 is not within 1.5 of source
                                                  lifeline 'service' center-x=476.0
```

### Root cause

`LayoutIntentNormalizer.normalizedMessagePoints` straightens every *cross-lifeline* message to a
two-point stem-to-stem segment, but **bails out when `sourceIndex.equals(targetIndex)`**, returning
`pointsAtY(edge.points(), y)` — i.e. it keeps ELK's raw x-coordinates and merely flattens them to a
common y. Those x's sit nowhere near the lifeline, so the endpoints land off both the node perimeter
and the lifeline centre-x axis.

Self-messages are the **only** message kind still deferring to ELK's route. Every other piece of
sequence geometry (columns, head band, y-lattice, stem anchoring, frame enclosure) is already
computed by the normalizer.

### This is pre-existing, not a Plan B P5 regression

Verified empirically: building the same source at `eee0636` (the P4 merge, before P5) fails
identically — exit 2, `status: error`, the same two `ROUTE_ENDPOINT_OFF_NODE_PERIMETER` errors. P5
did not cause this. P5's invariant wiring only made it **legible**, turning two vague perimeter
errors into a precise diagnosis naming the exact axis miss. The reason it went unnoticed is the
W1 coverage gap: no fixture and no property-test case ever exercised a self-message.

## ELK-first analysis (CLAUDE.md rule)

CLAUDE.md forbids hand-rolling layout/routing that ELK already provides. That rule is **satisfied**
here, on evidence:

**ELK ships no sequence-diagram algorithm.** Probed directly against the pinned version: at ELK
`0.11.0`, `org.eclipse.elk.alg.sequence` **is not published**. The algorithms that do exist are all
general graph layouts:

| Algorithm | Fit for sequence diagrams |
| --- | --- |
| `layered` (Sugiyama) | What we use — the only one whose layer/ordering semantics map onto columns at all |
| `force`, `spore` (stress) | Physics-based, no ordering guarantees — cannot produce a strict column/row lattice |
| `mrtree` | Tree layout; a sequence diagram is not a tree |
| `radial` | Radial placement — no |
| `disco` | Disconnected-component packing (a meta-algorithm, not a router) |
| `rectpacking`, `topdownpacking` | Box packing; no edge-routing semantics |

(ELK's ancestor KIELER had a sequence-diagram layouter, but it was never published as an ELK
algorithm artifact.) This project ships only `alg.layered` + `alg.rectpacking`, and the existing
algorithm-gate slice deliberately keeps the public surface `layered`-only.

**Deeper reason: a sequence diagram is not a graph-layout problem.** Its geometry is fully
determined by *semantics* — declared lifeline order gives the columns, declared `uml.sequence`
numbers give the rows. There is nothing for a layout algorithm to optimize: no layer assignment, no
crossing minimization. Positions are dictated, not computed. That is precisely why the existing
design uses ELK only to obtain initial boxes and then normalizes all sequence geometry itself.

So anchoring self-messages duplicates no ELK capability — it finishes a normalization that already
exists for every other message.

### Approaches considered

- **A — stem-anchored hook in the normalizer (CHOSEN).** Deterministic, satisfies every existing
  check by construction, consistent with the normalizer that already invents all other sequence
  geometry.
- **B — keep ELK's self-loop route, snap its endpoints to the stem.** Rejected: ELK shapes that loop
  for a layered RIGHT-direction graph, so the result is arbitrary in a sequence context, and the
  y-lattice and frame still need custom fix-up. More fragile, less predictable.
- **C — drive ELK with self-loop options/ports.** Rejected: the normalizer overwrites message
  geometry regardless (it must, to hit the stems and the y-lattice), so ELK's route would be
  discarded anyway — effort with no payoff.

## Design

### Geometry

A self-message becomes a four-point hook anchored on the lifeline stem, extending to the **right**
(the conventional UML self-call):

```
p0 = (stemX,              y)
p1 = (stemX + LOOP_WIDTH, y)
p2 = (stemX + LOOP_WIDTH, y + LOOP_HEIGHT)
p3 = (stemX,              y + LOOP_HEIGHT)
```

where `stemX = node.x() + node.width()/2` (the existing helper) and:

- `SELF_MESSAGE_LOOP_WIDTH  = 40.0`
- `SELF_MESSAGE_LOOP_HEIGHT = 24.0` (matches `MESSAGE_Y_STEP`)

Both endpoints (`p0`, `p3`) sit on the stem. The hook is small enough not to reach the neighbouring
column (columns are at least a head-box width + `LIFELINE_COLUMN_GAP` = 96.0 apart).

These are **neutral band constants owned by `elk-layout`**, exactly like `LIFELINE_COLUMN_GAP` (96.0)
and `MESSAGE_Y_STEP` (24.0). The notation emits no new data.

### Y-lattice

`normalizedMessageYSlots` currently advances `MESSAGE_Y_STEP` per message. A self-message occupies
`y .. y + LOOP_HEIGHT`, so the slot **after** a self-message must additionally clear `LOOP_HEIGHT`,
or the next message would collide with the hook's lower leg.

### Why every existing check then passes

- `LayoutQuality.endpointAccepted` → passes via its `onLifelineAxis` branch (endpoints on stem centre-x).
- `SequenceInvariants.messageEndpointsOnLifelineAxis` → passes for the same reason.
- `SequenceInvariants.messageYStrictlyIncreasing` → holds; the representative y is the first route
  point (`p0.y` = the slot), and slots remain strictly increasing.
- `SequenceInvariants.interactionFrameEnclosesLifelines` → holds; the frame bbox already spans every
  message route point, so it auto-expands over the hook.
- `LayoutQuality`'s degenerate-self-loop hard error → the hook has four distinct points, so it is not
  degenerate. **Verify this explicitly during implementation.**

### Blast radius

- **`engines/elk-layout`** — `LayoutIntentNormalizer`: the self-branch of `normalizedMessagePoints`,
  the loop-height reservation in `normalizedMessageYSlots`, two new constants. This is the whole fix.
- **No contract change** — no `LayoutIntent` variant, no wire/codec change, no schema-id bump, no new
  diagnostic code.
- **`engines/render`** — `UmlSequenceRenderer.edgePath` already emits `pathData(edge.points())`, so a
  four-point hook draws as a path with no change. The **one open item** is the arrowhead: the
  `marker-end` is auto-oriented by SVG along the final segment (which points *west*, back at the
  stem), but its `refX` is side-aware from an earlier arrowhead fix. Verify against a real render;
  a small renderer fix may be needed. Treat this as in-scope.

### Testing

- **Unit** — `LayoutIntentNormalizerTest`: pin the four-point hook (exact coordinates) and assert both
  endpoints are on the stem; pin that the message after a self-message clears `LOOP_HEIGHT`.
- **Fixture** — new `fixtures/source/valid-uml-sequence-self-message.json` + its real-engine
  `fixtures/layout-result/` counterpart (generated by the existing regenerator, not hand-authored).
- **Property test** — extend the `SequenceLayoutPropertyTest` generator to sometimes emit a
  self-message (a message whose source and target are the same lifeline), closing that slice of the
  W1 gap so this cannot silently regress again. All three invariants + no-overlap must stay green at
  300/300.
- **End-to-end** — a CLI test asserting the self-message source now builds with `status: ok` (the
  exact repro that fails today).
- **Render** — assert the self-message renders (path with the hook points) and the arrowhead lands on
  the stem, not floating.

### Byte-stability guard

No existing fixture contains a self-message, so **every current `layout-result` fixture must stay
byte-identical**. Any diff there means the change leaked into cross-lifeline geometry and is a
regression — debug it, do not re-baseline.

## Non-goals

- ExecutionSpecification / DestructionOccurrence / create-delete-message geometry (the remainder of
  the W1 gap) — a separate slice.
- Nested/overlapping self-messages, or self-messages inside combined-fragment operands beyond what
  the existing fragment machinery already handles.
- Any change to the neutral `LayoutIntent` vocabulary or the layout-request wire.

## Risks

- **R1 — hook collides with the neighbouring column** when ELK's own columns are kept (the
  "trust-ELK" branch) and the gap happens to be tight. Mitigated by the modest 40.0 width and by
  columns being at least a head-box width apart; the property test's no-overlap assertion is the net.
- **R2 — arrowhead placement** on the westward return segment (see render note). Verified by a real
  render, not by assertion alone.
- **R3 — regression into cross-lifeline geometry.** Mitigated by the byte-identical fixture guard and
  the 300-trial property test.
