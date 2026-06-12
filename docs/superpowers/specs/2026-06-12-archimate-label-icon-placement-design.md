# ArchiMate node label / icon placement fix

Date: 2026-06-12
Status: Approved (pending spec review)
Area: `plugins/generic-graph` (sizing), `plugins/svg-render` (rendering)

## Problem

ArchiMate node labels collide with the top-right type icon and crowd the node
border. The most visible cases are the two-line element families — every
`Application *`, `Technology *`, several `Business *`, plus
`Communication Network`, `Distribution Network`, `Implementation Event`,
`Course Of Action`, `System Software`, `Representation`. Junctions are worse:
`And Junction` / `Or Junction` labels overflow the small circle, and
`And Junction` draws its label in the same colour as its black fill, making it
invisible.

Evidence: `.test-output/renders/svg-render-plugin/svg_renderer_covers_each_archimate_node_type.svg`.

### What ArchiMate actually does

The ArchiMate 3.2 specification (local extract,
`~/Documents/Archimate 3.2/extracted/`) states the standard notation is "a box
with an icon in the upper-right corner" (full text line 994). In the rendered
examples (e.g. Figure 74, "Application Interface", page image `page-081.png`)
the element **name is centered both horizontally and vertically**, the small
type icon is tucked in the upper-right corner, and the box is **sized so the two
coexist** — the centered name's right edge stays left of the icon. The name is
never pushed off-center to dodge the icon.

So centered-in-the-middle is the ArchiMate default and must be preserved. The
correct lever is **box size**, not label position.

### Root cause

1. ArchiMate node sizing is flat. `GenericGraphLayoutSizing.widthHint` /
   `heightHint` return a fixed **160×80** for every non-connector ArchiMate node
   regardless of label length (only UML nodes get label-aware sizing). A long
   name therefore has no more room than a short one.
2. The synthetic coverage test (`MainTest.coversEachArchimateNodeTypeFromPolicy`)
   forces an even smaller **128×68** on all 60 node types, producing the worst
   overlaps in the artifact.
3. The renderer's label width budget (`nodeLabelMaxWidth = width - 20`) reserves
   no room for the corner icon, and its font shrink considers only width, so a
   centered line can extend under the icon's column.
4. Junctions are tiny (28×28 in real layout); their label is sized to the full
   box and drawn in the foreground colour even when that equals the fill.

This is **pre-existing behaviour** from `ac5c2c6 "Restore SVG node decorators
and label wrapping"`, not a regression from the recent interactive-SVG series.

## Goals

- Keep ArchiMate names centered horizontally and vertically (ArchiMate default).
- Size ArchiMate boxes so a centered name coexists with the corner icon, the way
  the ArchiMate examples do — growing width/height "a bit" for long names.
- Junction labels are readable and do not overflow the circle.
- No public schema, contract, or render-policy change.

## Non-goals

- No off-center / pushed-down / top-left label layout. Centered stays centered.
- No change to ELK layout options or routing; only node size hints change.
- No change to UML node sizing or rendering.

## Design

### 1. Label stays centered; renderer reserves the icon column (`plugins/svg-render`)

- Revert any "push the label below the icon" idea. The label block is centered
  horizontally and vertically in the node box, as today's non-colliding nodes
  already are.
- Reserve the corner-icon column in the wrap/shrink budget so a centered line
  clears the icon. For icon-bearing ArchiMate nodes the effective label width
  becomes `width - 2 × ICON_RESERVE` (the icon sits at `[width-28, width-6]`;
  reserving it symmetrically keeps the centered text's right edge left of the
  icon's left edge). Short names are unaffected — they never reach the icon.
- Because the centered text column is kept left of the icon's column, a two-line
  label that vertically shares the icon's band still does not collide (different
  x range) — exactly the Figure 74 arrangement.
- Keep a height-aware term in the existing font shrink as a safety net for any
  undersized box: if `lineCount × lineHeight` exceeds the box height, scale the
  font down (floored at the current `9.0`).

Gating predicate: a non-null ArchiMate decorator that draws a corner icon — i.e.
not a junction and not a UML decorator.

```
ArchiMate-faithful (Figure 74): name centered, icon top-right, box fits both

        +----------------------------+
        |                     +----+  |  icon in upper-right corner
        |     Application     |icon|  |  name centered H+V,
        |      Component      +----+  |  right edge clears the icon column
        |                            |
        +----------------------------+
                 ^ reserved   ^ icon column (label never enters it)
```

### 2. Label-aware ArchiMate node sizing (`plugins/generic-graph`)

Replace the flat `160×80` for non-connector ArchiMate nodes with label-aware
hints, mirroring the existing UML structural sizing (`umlStructuralWidthHint` /
`HeightHint`):

- **Width** grows from a `160` floor to fit the label's longest wrapped token
  plus horizontal padding **and** the corner-icon reserve, so the centered name
  clears the icon. (Char-width / padding constants determined in implementation,
  reusing the UML structural pattern of `chars × charWidth + padding`, rounded
  up to a step.)
- **Height** grows from an `80` floor so names that wrap to two lines get a
  taller box, keeping the centered name clear of the top-corner icon, plus
  vertical padding.
- Connector/junction types keep their existing `28×28`.

Net effect: long-named elements get slightly larger boxes (the "increase the
height a bit" lever), short ones keep the `160×80` floor, and every centered
name coexists with its icon — matching the ArchiMate examples.

### 3. Junction labels move outside the circle (`plugins/svg-render`)

Junctions are ~28px circles; a label cannot fit inside. Add
`archimateJunctionLabelOutside(decorator)` (mirroring the existing
`umlCompactControlNodeLabelOutside`). For And/Or junctions:

- Render the name centered horizontally at the node center, **below** the circle
  (`y = cy + radius + gap + fontSize`), `text-anchor="middle"`.
- Fill with `style.labelFill()` (foreground) on the page background, so the
  `And Junction` name is readable below its black dot instead of vanishing into
  the fill.

The circle shape, radius, and fill are unchanged.

```
   before                          after

   And Ju###unction                    ( ● )
        (###)                       And Junction
   (hidden in black fill,        (dark text on white,
    overflows the dot)            below the dot)
```

### 4. Tests (TDD — written first)

**`plugins/generic-graph` sizing:**
- A long-label ArchiMate node yields a larger width (and, when it wraps, height)
  than a short-label node of the same type.
- The `160×80` floor is respected for short labels; connectors stay `28×28`.
- The produced width leaves room for the longest token plus the icon reserve.

**`plugins/svg-render`:**
- For an icon-bearing two-line node, the label is centered vertically (block
  straddles the node's vertical center — not pushed down) and its widest line's
  horizontal extent stays left of the icon's column.
- Junction label `y` is below `cy + radius`, and its `fill` differs from the
  circle's fill.
- The synthetic coverage test sizes nodes representatively (label-aware or
  per-node sizes, not a flat 128×68) so the artifact mirrors real proportions.

All assertions are geometry-relative inequalities, not pinned pixel coordinates.

## Verification

- `./mvnw -pl plugins/generic-graph,plugins/svg-render,cli -am test`
  (run with the sandbox disabled; JUnit `@TempDir` needs a writable `/tmp`).
- Re-rasterize the artifact and visually confirm the Application, Technology,
  and junction rows against the ArchiMate examples
  (`magick -density 300 <svg> -crop … png` → inspect).
- `git diff --check`.

Audit gate (per CLAUDE.md): quick `test-quality-audit` on the changed
generic-graph + svg-render tests; quick `devsecops-audit` on the diff (no new
dependencies or process boundaries expected).

## Files that move together

This touches the generic-graph sizing module and its tests, the svg-render
renderer and its tests, and the synthetic coverage test artifact expectations.
No schema, contract, fixture, render-policy, README, or version-assertion
surface changes, because the public render contract and policy are unaffected
and node size hints are an internal layout detail.
