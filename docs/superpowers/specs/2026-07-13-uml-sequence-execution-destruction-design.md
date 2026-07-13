# UML Sequence: ExecutionSpecification, Destruction, and Delete-Messages — Design

**Status:** approved 2026-07-13. Fixes three live, user-facing defects (one of them a hard build failure).

## Problem

`ExecutionSpecification`, `DestructionOccurrenceSpecification`, and `Gate` are a **facade**. They are:

- in the UML sequence allowed-type set (`uml/.../Uml.java`),
- given sizing hints (`UmlLayoutSizing`: ExecutionSpecification 16×72, Gate/Destruction 24×24),
- **fully drawn by the renderer** (`UmlSequenceModel` routes them to `executions`/`gates`/`destructions`;
  `UmlSequenceRenderer` paints them from `node.x/y/width/height`),

but they are **never positioned**. `UmlNotationSemantics.layoutRole` returns a role only for `Lifeline`
and `Interaction`, so these nodes get `role = null`, the sequence normalizer ignores them, and ELK —
seeing a disconnected node — parks the activation bar at the canvas origin. The renderer then
faithfully draws it there.

**No source fixture exercises any of them**, and the jqwik generator emits none of these node types, so
nothing ever caught it. (This is the same coverage hole that hid the self-message defect; see
`2026-07-13-uml-sequence-self-message-design.md`.)

### Reproduced (one legal model; `validate --profile uml` → `ok`)

A three-lifeline interaction with an `ExecutionSpecification` on `service`, a
`DestructionOccurrenceSpecification` for `worker`, a `createMessage`, and a `deleteMessage`:

```
BUILD EXIT=2   status: error
DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER
  edge 'm3' first route point is not on source node 'service' perimeter
```

Actual layout:

| node | role | placed at | should be |
| --- | --- | --- | --- |
| `exec-service` (activation bar) | `null` | **x=12, y=12** — canvas corner | on the `service` stem (x=407), spanning its call |
| `worker-destroyed` (the ✕) | `null` | x=411, y=412 — between lifelines | on the `worker` stem (x=643) |
| `m3` (delete-message) | — | `(242,412) … (410,412)` — raw ELK route | stem → the ✕ |

Three defects:

1. **ExecutionSpecification is never placed** — lands at the origin, detached from every lifeline. This
   is Defect A's signature (a disconnected node ELK parks at (12,12)).
2. **DestructionOccurrence is never anchored** to the lifeline it terminates.
3. **A delete-message hard-fails the build (exit 2).** Its target is not a `Lifeline`, so it has no
   lifeline index and `normalizedMessagePoints` falls through to ELK's raw route — the **identical root
   cause** as the self-message defect (self-messages were the *other* endpoint case that fell through).

### The root gap: the model cannot say which lifeline an occurrence covers

There is no property linking an `ExecutionSpecification` or `DestructionOccurrenceSpecification` to a
lifeline. The renderer places them purely from layout geometry, and layout has no idea. So this is not
a geometry bug to patch — the feature was never wired end to end.

Fortunately `uml.covered` is an established convention (`CombinedFragment` carries
`"covered": ["customer", "service", "inventory"]`), and `sourceNode.properties` is
`additionalProperties: true` in `schemas/model.schema.json` — so **carrying it needs no schema change**.

## Design

### Model conventions (free-form `uml.*`; NO schema change, NO schema-id bump)

- **`ExecutionSpecification`**: `uml.covered: "<lifelineId>"` (the lifeline the bar sits on),
  `uml.start: "<messageId>"`, `uml.finish: "<messageId>"` — the bar spans from the `start` message's row
  to the `finish` message's row. This mirrors the real UML metamodel, where
  `ExecutionSpecification.start` / `.finish` point at `OccurrenceSpecification`s. Explicit and
  agent-authorable; layout simply looks up the two message rows.
- **`DestructionOccurrenceSpecification`**: `uml.covered: "<lifelineId>"` (the lifeline it terminates).
  Its row is the row of the **message that targets it** (the delete-message) — that is how UML expresses
  it, so nothing extra is authored. `Uml.isMessageEndpoint` already permits
  `Lifeline → DestructionOccurrenceSpecification`.
  - **Orphan case** (a destruction no message targets): anchor it one `MESSAGE_Y_STEP` below the last
    message row, so it is still deterministically placed rather than dumped at the origin.

### Semantics (`semantics-uml`)

- `UmlNotationSemantics.layoutRole` gains `"execution"` (ExecutionSpecification) and `"destruction"`
  (DestructionOccurrenceSpecification). `Gate` stays `null` (out of scope).
- New sealed `SequenceConstraint` variants: `ExecutionSpan(execId, coveredLifelineId, startMessageId,
  finishMessageId)` and `DestructionAnchor(destructionId, coveredLifelineId, anchorMessageId)` (the
  anchor message resolved from the relationship targeting it, or null for the orphan case).
- Both lower to the one new neutral `LayoutIntent` variant below.

### Neutral IR (`ir`) — one new sealed variant

```java
record StemSpan(String nodeId, String bandMemberId, String fromMemberId, String toMemberId)
```

*"Place `nodeId` on the axis of band member `bandMemberId`, spanning from ordered member `fromMemberId`
to ordered member `toMemberId`."* It talks about **bands and members, not UML** — so it stays
notation-free, and `elk-layout` never learns what an ExecutionSpecification is. A destruction is the
degenerate `fromMemberId == toMemberId` case.

This extends the sealed family P5 introduced — exactly the "a future notation re-adds a variant when it
needs one" path that design anticipated (P5 pruned `PortSideHint`/`Encloses` precisely because nothing
emitted them; this one *is* emitted and consumed).

**Wire encoding** (fits the existing `{id, kind, subjects: string[]}` shape — **no schema bump**):
kind `"stem-span"`, subjects `[nodeId, bandMemberId, fromMemberId, toMemberId]`. The `ir`
`LayoutIntentCodec` gains encode/decode for it and stays notation-free.

### Layout (`elk-layout` / `LayoutIntentNormalizer`)

1. **Endpoint resolution.** Build a map from every anchored node (a destruction) to its covered
   lifeline's column index, and merge it into the endpoint lookup. A message whose endpoint is a
   destruction then resolves to that lifeline's stem — **this is what fixes the exit-2 failure**, and it
   is the same shape as the self-message fix (both were endpoints that fell through to ELK's raw route).
2. **`StemSpan` placement.** Centre the node on the covered lifeline's stem x
   (`x = stemX - width/2`); derive y from the referenced message rows:
   - ExecutionSpecification: `y = rowOf(start)`, `height = rowOf(finish) - rowOf(start)`, clamped to a
     minimum height so a same-row span is still visible.
   - Destruction (`from == to`): centred on the row — `y = row - height/2`.
3. **Delete-message termination.** A message targeting a destruction ends on the **destruction's left
   edge** (`x = destruction.x`), not its centre — deliberately, so the pre-existing
   `LayoutQuality.pointOnNodePerimeter` check passes **without weakening it**.
4. **Interaction frame.** Extend the frame bbox to enclose the execution/destruction nodes.

### Quality-check consequences (extension of existing precedent — nothing is weakened)

Messages legitimately terminate on a lifeline stem where an activation bar sits, and the bar sits on the
stem by construction. So `role="execution"` / `"destruction"` must be treated as **sequence chrome** in:

- `core` `LayoutQuality.countOverlaps` / `countConnectorThroughNodes` (which already exempt
  `role="interaction"` — an exemption added for Defect A), and
- the property test's `assertNoNodeRectsOverlap` (which already exempts `interaction`).

This **extends an existing, documented exemption to two more sequence-chrome roles**. It is not a
loosening invented to make this change pass: without it, correct UML geometry would be reported as a
defect. The hard-error lane (`validateLayoutDiagnostics`, incl. `pointOnNodePerimeter`) is **untouched**
and remains the guard — which is why the delete-message terminates on the ✕'s perimeter rather than its
centre.

`SequenceInvariants.messageEndpointsOnLifelineAxis` only considers lifeline↔lifeline messages, so a
delete-message is skipped by it. Left as-is deliberately rather than stretched; the perimeter check
guards it.

## Testing

- **Unit** (`LayoutIntentNormalizerTest`): pin the activation bar's placement (centred on the stem, y
  spanning start→finish rows) and the ✕'s placement (centred on the stem at the delete-message row);
  pin that the delete-message routes stem → the ✕'s left edge.
- **Codec** (`LayoutIntentCodecTest`): `StemSpan` round-trips losslessly.
- **Fixture**: `fixtures/source/valid-uml-sequence-lifecycle.json` (exec spec + destruction + create- and
  delete-messages) + its **real-engine** `fixtures/layout-result/` counterpart via the regenerator.
- **End-to-end**: the exit-2 repro asserted to build `status: ok`.
- **Render**: the bar and the ✕ are drawn on the lifeline stem (assert against real emitted SVG).
- **Property test**: extend the generator to emit execution specs, destructions, and delete-messages —
  closing the coverage hole for real this time. 300/300 with all invariants green.

## Byte-stability guard

No existing fixture contains any of these node types, so **every current `fixtures/layout-result/*.json`
must stay byte-identical**. A diff there means the change leaked into ordinary sequence geometry —
debug it, do not re-baseline.

## Non-goals

- **`createMessage` head-lowering** — a lifeline created mid-diagram should have its head box at the
  create-message's row rather than pinned to the top band. Real UML behaviour, currently not honoured,
  and it would change head-band alignment that every existing sequence fixture depends on. Tracked.
- **`Gate` placement** — gates also get `role = null` and float. Rare; tracked.
- No change to the `layout-request` wire shape or any schema id.

## Risks

- **R1 — the quality-check exemptions.** Extending the sequence-chrome exemption to `execution` /
  `destruction` could mask a genuine overlap defect involving those nodes. Mitigated by keeping the
  hard-error lane untouched, and by the property test's invariants still applying to lifelines/messages.
- **R2 — regression into ordinary sequence geometry.** Mitigated by the byte-identical fixture guard and
  the 300-trial property test.
- **R3 — nested/overlapping activation bars** (two ExecutionSpecifications on the same lifeline with
  overlapping spans) would be drawn on top of each other. Out of scope for this slice; the model can
  express it, so note it as a known limitation rather than pretend it works.
