# ArchiMate node label / icon placement fix

Date: 2026-06-12
Status: Approved (pending spec review)
Area: `plugins/svg-render`

## Problem

In the SVG renderer, ArchiMate node labels collide with the top-right type
icon and, in some cases, crowd the node border. The most visible cases are the
two-line element families — every `Application *`, `Technology *`, several
`Business *`, plus `Communication Network`, `Distribution Network`,
`Implementation Event`, `Course Of Action`, `System Software`, and
`Representation`. The junction nodes are worse: `And Junction` /
`Or Junction` labels overflow the small circle, and `And Junction` draws its
label in the same colour as its black fill, making it invisible.

Evidence: `.test-output/renders/svg-render-plugin/svg_renderer_covers_each_archimate_node_type.svg`.

### Root cause

Three compounding facts in `Main.java`:

1. The type icon is drawn in the top-right corner at
   `x = node.x + width - ICON_SIZE - 6`, `y = node.y + 9`, `size = 22`
   (`archimateNodeDecorator`), so it occupies the vertical band
   `node.y + 9 … node.y + 31`.
2. The label block is centered in the **whole** node box
   (`nodeLabelFirstLineY` / `nodeLabelPosition`). For a two-line label the first
   line lands inside that icon band, and a wide first word (e.g.
   "Application") extends horizontally under the icon.
3. The font shrink in `nodeLabelLinesAndSize` only considers **width**, never
   the available height, so it cannot compensate for a tighter vertical region.

The junction branch in `nodeShape` draws the label through the normal centered
path, sized to the full node box rather than the circle, and uses the
foreground colour even when the circle fill equals that colour.

This is **pre-existing behaviour** dating to `ac5c2c6 "Restore SVG node
decorators and label wrapping"`. It is not a regression from the recent
interactive-SVG series; those commits did not touch the label, wrap, or
junction code paths.

## Goals

- Icon-bearing ArchiMate labels never overlap the top-right type icon.
- Two-line labels fit cleanly within the node, shrinking font when needed.
- Junction labels are readable and do not overflow the circle.
- No change to public schemas, render policy, contracts, or non-ArchiMate
  (UML) node rendering.

## Non-goals

- No change to ELK layout or node sizing.
- No switch to a top-left / left-aligned label style (centered is retained).
- No recalibration of the per-character width factor unless the verification
  render shows residual border overflow (treated as a guarded follow-up, not a
  guessed constant).

## Design

All changes are local to `plugins/svg-render/.../Main.java`.

### 1. Icon-bearing labels sit below the icon band

When a node carries a top-right ArchiMate type icon, center the label block
within the sub-region **below** the icon rather than the full node box:

- `iconBandBottom = node.y + ICON_TOP_INSET + ICON_SIZE` (= `node.y + 9 + 22`).
- `regionTop = iconBandBottom + gap` (small gap, ~2px → `node.y + 33`).
- `regionBottom = node.y + height - pad` (~`node.y + height - 4`).
- The label block is vertically centered within `[regionTop, regionBottom]`.

Horizontal centering and the existing `nodeLabelMaxWidth = width - 20` budget
are unchanged. Because the label now lives entirely below the icon's vertical
extent, it can use the full width with no horizontal collision with the icon.

The shift is **uniform**: single-line icon-bearing labels also center in the
lower region (they move down ~11px from true-center). Nodes with no top-right
icon (and junctions, handled in §3) keep full-box centering.

Gating predicate: the node has a non-null ArchiMate decorator that draws a
corner icon — i.e. not a junction and not a non-ArchiMate (UML) decorator.

Before (today) — the first line lands in the icon band and the wide first word
runs under the icon:

```
        +------------------------+
        |                  +----+ |  <- icon band: node.y+9 … node.y+31
        |     Applicati·· (|icon|)|     "Application" overlaps the icon
        |        Component  +----+ |
        |                        |
        +------------------------+
```

After — the whole label block is centered in the region below the icon band, so
it clears the icon both vertically and horizontally:

```
        +------------------------+
        |                  +----+ |  icon band (unchanged)
        |                  |icon| |
        |  - - - - - - - - +----+ |  <- regionTop = node.y+33
        |       Application       |     label block centered in
        |        Component        |     [regionTop, node.y+height-4]
        +------------------------+
```

Single-line icon-bearing nodes shift down the same way (uniform rule):

```
   before                 after
+--------------+      +--------------+
|        +---+ |      |        +---+ |
|   Driv(|icn|)|      |        |icn| |
|        +---+ |      |   Driver     |   centered in lower band
+--------------+      +--------------+
```

### 2. Height-aware font shrink

Extend `nodeLabelLinesAndSize` so the chosen font respects the available
height, not only width:

- `regionHeight = regionBottom - regionTop` for icon-bearing nodes (full node
  height otherwise).
- `blockHeight = lineCount * nodeLabelLineHeight(fontSize)`.
- If `blockHeight > regionHeight`, scale the font down by
  `regionHeight / blockHeight`, combined with the existing width-based scale,
  floored at the current `9.0` minimum.

This is what lets "Application / Component" fit the ~31px lower band.

### 3. Junction labels move outside the circle

Add `archimateJunctionLabelOutside(decorator)` mirroring the existing
`umlCompactControlNodeLabelOutside`. For And/Or junctions:

- Render the name centered horizontally at the node center.
- Place it **below** the circle: `y = cy + radius + gap + fontSize`.
- `text-anchor="middle"`, no center baseline.
- Fill with `style.labelFill()` (foreground) on the page background. For
  `And Junction` the circle is black but the label now sits on the white
  background below it, so the dark foreground colour is readable.

The circle shape, radius, and fill are unchanged.

Before — the label is sized to the full node box, so it overflows the small
circle; `And Junction`'s text is the same colour as its black fill and
disappears:

```
   And Ju███unction          Or Jun(   )ction
        (███)                       (   )
   (text hidden in the        (text overflows the
    black fill, spills         white circle on both
    out both sides)            sides)
```

After — the name sits centered below the circle in the foreground colour:

```
         ( ● )                      (   )
      And Junction               Or Junction
   (dark text on white,       (centered under the dot,
    below the black dot)       no overflow)
```

### 4. Tests (TDD — written first)

Add assertions to `MainTest` against the existing archimate-node-type render
(`coversEachArchimateNodeTypeFromPolicy`) and a focused junction case. All
assertions are geometry-relative inequalities, not pinned pixel coordinates:

- **No icon overlap:** for an icon-bearing two-line node (e.g. Application
  Component), the first line's `y` is below the icon band
  (`> node.y + ICON_TOP_INSET + ICON_SIZE`).
- **Height fit:** the label block (lineCount × lineHeight at the chosen font)
  fits within the node height below the icon.
- **Within bounds:** the widest line's estimated horizontal extent stays inside
  the node border.
- **Junction outside + readable:** the junction label `y` is below
  `cy + radius`, and its `fill` differs from the circle's fill.

## Verification

- `./mvnw -pl plugins/svg-render,cli -am test` (run with the sandbox disabled;
  JUnit `@TempDir` needs a writable `/tmp`).
- Re-rasterize the artifact and visually confirm
  (`magick -density 300 <svg> -crop … png` → inspect) for the Application,
  Technology, and junction rows.
- `git diff --check`.

Audit gate (per CLAUDE.md, SVG render row): quick `test-quality-audit` on the
changed contract/plugin/CLI tests and quick `devsecops-audit` on the renderer
diff.

## Files that move together

Per CLAUDE.md "SVG render policy changes": this change touches only the
renderer and its tests (`plugins/svg-render`), with no schema, contract,
fixture, policy, or README change because the public render contract and policy
are unaffected. If the verification render forces a width-factor change, that
remains inside the renderer.
