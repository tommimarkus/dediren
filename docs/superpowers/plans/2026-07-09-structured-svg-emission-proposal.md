# Proposal: structured SVG emission for the render engine

Status: proposal (not scheduled). Raised while broadening render styling
(generic shapes, colour, opacity, lines, typography), which repeatedly added
attributes to hand-built SVG strings.

## Problem

The render engine composes SVG entirely by hand — `String.format` / `StringBuilder`
with manual XML escaping (`Svg.attr` / `Svg.text`) — across `SvgDocument`,
`EdgeRenderer`, `UmlShapes`, `ArchimateIcons`, `ArchimateShapes`, `GenericShapes`,
`NodeLabels`, and `UmlSequenceRenderer`. Consequences:

- **Escaping is a manual invariant, not a guarantee.** `LabelInjectionTest`
  exists precisely because untrusted label text is escaped by hand; the colour
  grammar had to become a security boundary because `interaction.highlight_stroke`
  is concatenated into a CSS `<style>` block where XML escaping is inert.
- **Format-string fragility.** Every new style field risks a `%s`/arg-count
  mismatch; adding optional attributes means threading `"...%s"` slots and
  `opacityAttr(...)`-style helpers through many format strings (esp. the ~20 in
  `UmlShapes`, several multi-element).
- **No structural validation.** Nothing guarantees a well-formed element until a
  test parses the output (`SvgAudit.auditStructure`).

## Current mitigations (already in place)

Small typed helpers centralise attribute formatting and escaping:
`Svg.attr/text`, `opacityAttr`, `enumAttr`, `stringAttr`, `dashArrayAttr`. The
generic node opacity/dash also uses a *wrap-group* trick (`withNodeStrokeStyle`)
so one attribute set reaches every notation's shape without editing each builder.
These reduce, but do not remove, the hand-rolled-string exposure.

## Options

1. **Typed element/attribute builder (incremental, recommended).** A tiny
   `El(tag).attr(name, value).child(...)` builder that owns escaping and
   self-closing, `toString()`-ing to SVG. Migrate new/error-prone hotspots first
   (labels, edge paths, the CSS `<style>` block), leaving the rest until touched.
   Escaping becomes structural; arity errors disappear. Medium effort.
2. **DOM + serializer.** Build a `org.w3c.dom.Document` and serialize. Strongest
   correctness guarantee, but the serializer must reproduce byte-identical output
   or every exact-string assertion (and `RenderDeterminismTest`) breaks — likely
   forcing a custom serializer anyway. Large effort, high test-churn.
3. **Status quo + discipline.** Keep string-building; keep adding typed helpers
   and injection tests. Lowest effort, unchanged risk profile.

## Hard constraint: determinism

`RenderDeterminismTest` pins byte-for-byte output and dozens of tests assert exact
attribute substrings. Any builder/serializer MUST reproduce identical bytes:
attribute order, number formatting (`styleNumber`/`labelNumber`), and
self-closing style. This is the dominant cost and risk of options 1–2, and the
reason a wholesale swap was **not** folded into the styling work.

## Recommendation

Option 1, incremental, as a separate initiative — starting with the sinks where
escaping is a security or correctness boundary (labels, the interaction CSS
block, edge/label text), byte-matched against the current output. Defer options
2/3. Sequence-renderer unification (see the styling plan's Slice 5) is a natural
companion, since it re-implements its own `attr`/`styleNumber`.
