# SVG Rendering

The `render` plugin turns a layout result into a deterministic SVG artifact. All
SVG styling lives in the render policy and the plugin — never in source JSON.
The plugin and ordinary CLI emit no PNG; convert the SVG with an external tool
(`rsvg-convert`, `resvg`, ImageMagick, or Inkscape) when a raster artifact is
required. The MCP adapter can separately negotiate an optional PNG response
attachment from the SVG; that protocol adaptation does not widen the render
policy or result schema.

[← Back to feature index](README.md)

Plugin: `render` ·
Policy schema: [`schemas/render-policy.schema.json`](../../schemas/render-policy.schema.json) ·
Result schema: [`schemas/render-result.schema.json`](../../schemas/render-result.schema.json)

## Result Shape: `artifacts[]`

`render` returns a `.data.artifacts[]` list holding a single entry with
`artifact_kind` `svg` and `content`:

```bash
jq -r '.data.artifacts[] | select(.artifact_kind=="svg") | .content' \
  render-result.json > diagram.svg
```

For `dediren_import` and `dediren_build` over MCP, `output: "image"` plus
`accepted_image_types` negotiates a response attachment while preserving this
SVG-only artifact contract. The JSON envelope stays first; `image/svg+xml` has
fixed priority over `image/png`, and PNG is attempted only through the optional
startup-resolved `--resvg-command`. Any unavailable or failed conversion falls
back to JSON only. See the
[MCP Server](../agent-usage.md#mcp-server) contract for validation and limits.

## Render Policies

The policy owns presentation. Reuse a shipped policy unless custom style is
needed:

| Policy | Use |
| --- | --- |
| [`fixtures/render-policy/default-svg.json`](../../fixtures/render-policy/default-svg.json) | General-purpose default. |
| [`fixtures/render-policy/archimate-svg.json`](../../fixtures/render-policy/archimate-svg.json) | ArchiMate notation styling. |
| [`fixtures/render-policy/uml-svg.json`](../../fixtures/render-policy/uml-svg.json) | UML notation styling. |
| [`fixtures/render-policy/rich-svg.json`](../../fixtures/render-policy/rich-svg.json) | Richer styling example. |
| [`fixtures/render-policy/generic-shapes-svg.json`](../../fixtures/render-policy/generic-shapes-svg.json) | Generic node shapes example. |

### Node shapes (generic graphs)

Generic (non-notation) nodes can pick a shape with `style.node.shape` or a
per-node / per-type `shape` override. Supported values: `rectangle`,
`rounded_rectangle` (the historical default), `ellipse`, `circle`, `diamond`,
`hexagon`, `parallelogram`, `stadium`, `cylinder`, `triangle`. Shapes exist for
notations that do not fix geometry, so a `shape` is rejected
(`DEDIREN_SVG_POLICY_INVALID`) when it sits alongside a notation `decorator`, or
under the `archimate` / `uml` semantic profiles — those keep the shapes and icons
their notation fixes, and a policy cannot override them.

**Node** shapes and icons follow the specification. **Edges** do not, everywhere.
UML edge keywords are not rendered: `Usage`, `Dependency`, `Include`, `Extend`,
`Manifestation` and `Deployment` all draw as the same dashed open-arrow line,
because the keyword («use», «include», …) that distinguishes them has no render
surface. An Include and an Extend between the same two use cases are
indistinguishable in the SVG, so a diagram cannot always be read back to the
model it came from. The XMI export carries the distinction correctly; if the
edge kind matters, that is the artifact to read.

### Colour & opacity

Every colour value (`fill`, `stroke`, `label_fill`, `background.fill`) accepts
`#RGB` / `#RGBA` / `#RRGGBB` / `#RRGGBBAA` hex, `rgb()` / `rgba()`, or a CSS
colour keyword (including `none` and `transparent`). Colours land only in
XML-escaped SVG attributes; the grammar additionally admits no CSS metacharacters
as defense-in-depth.
Set `fill_opacity` / `stroke_opacity` (0–1) on nodes and groups,
`stroke_opacity` on edges, and `background.fill_opacity` for a translucent page.
Node and group fills can also be gradients via `fill_gradient` — `{ "type":
"linear" | "radial", "angle": <deg>, "stops": [ { "offset": 0–1, "color": …,
"opacity": 0–1 } ] }` — rendered as an inline `<linearGradient>` /
`<radialGradient>` referenced by a deterministic id.

### Line style

Edges and node/group borders take `line_style` (`solid`, `dashed`, `dotted`)
and a custom `dash_pattern` — an array of 1–8 positive lengths (e.g. `[4, 2]`)
that overrides the preset. The ArchiMate grouping border keeps its dashed
default unless a `line_style` or `dash_pattern` is given.

### Typography

The global `font` gains `weight` (`normal`/`bold`) and `style` (`normal`/
`italic`). Node and group labels take per-element `font_weight`, `font_style`,
`font_family`, `label_align` (`start`/`middle`/`end`), and `label_opacity`; edge
labels take `label_opacity`. The deterministic label-width pin is unchanged, so
bold/italic labels stay reproducible (a real browser may render bold slightly
tighter than the pinned width).

### UML sequence diagrams

The sequence renderer is a separate pipeline, so it honours a focused subset: box
**fill/stroke opacity** and a box **`line_style`** preset (lifelines, activations,
combined fragments, interaction frame, gate), plus **opacity** on messages and
labels. A message's line style stays notation-driven (reply → dashed); custom
`dash_pattern`, per-element label fonts, and gradients are not applied on
sequence views.

### Edge label presentation

Edge labels default to **outlined text**. Set
`style.edge.label_presentation` to `background` when a filled label backing is
preferred.

Edge label placement tries candidate positions that avoid node boxes, group
title/border bands, other edge route segments, and labels already placed earlier
in the SVG, then falls back to the preferred route position when no clear
candidate is available. Line-jump masks use the local group fill when the jump
occurs inside a group, falling back to the page background outside groups.

## Notation Rendering & Render Metadata

For UML notation views (sequence, state machine, use case, component,
deployment), pass generated **render metadata** with `--metadata` so the
renderer receives the notation semantics (e.g. for sequence: lifelines,
interaction, message order, message sort, and combined-fragment structure):

```bash
dediren project --target render-metadata --plugin generic-graph \
  --view sequence-view --input source.json > render-metadata.json

dediren render --plugin render \
  --policy fixtures/render-policy/uml-svg.json \
  --metadata render-metadata.json \
  --input layout-result.json > render-result.json
```

Render metadata schema:
[`schemas/render-metadata.schema.json`](../../schemas/render-metadata.schema.json).

The **render policy** must declare the matching `semantic_profile` for that
notation to be drawn. Metadata alone does not select notation shapes: rendering
`uml` or `archimate` metadata under a policy that declares no
`semantic_profile` succeeds but drops every notation shape, decorator, and
label placement, and warns with
`DEDIREN_RENDER_METADATA_PROFILE_NOT_APPLIED`. It matters most for the
notation's fixed-size symbols — a UML `DecisionNode` or `Port` is laid out as a
~32px glyph whose label belongs outside it, so painted generically its label
lands over a symbol far too small to hold it. The pairing stays legal (a
deliberately generic rendering of a notation view is a reasonable thing to
ask for), so the artifact is still produced.

## Generated SVG 2 Subset

Normal tests enforce Dediren's exercised SVG 2 subset across standard and
sequence render scenarios, every generic shape, group and linear/radial
gradients, provenance stamping, package builds, and a real wired CLI build. The
test-only `Svg2SubsetAssertions` checker independently defines the permitted
namespace, elements and content models, attributes and values, same-document
references, points, and the emitted `M`/`L`/`H`/`V`/`C`/`Q`/`A`/`Z` path
grammar. It also accepts valid lowercase `data-*` attributes on SVG elements.

The checker uses secure JDK XML parsing and local grammar checks only. It does
not download schemas, invoke system tools, or add a validation dependency. The
guarantee is deliberately narrower than full SVG 2 certification: it covers
the supported generated-output corpus, not unused SVG features, arbitrary
inputs, or hand-authored documentation SVGs. Runtime rendering remains
warn-first for layout-quality problems and does not run this test assertion.
The subset follows SVG 2's
[restricted-feature-set conformance model](https://www.w3.org/TR/SVG2/conform.html)
and [custom data attribute rules](https://www.w3.org/TR/SVG2/struct.html#DataAttributes).

## Chromium Render-Paint Verification

The opt-in `render-paint` Maven profile validates behavior that byte-exact SVG
goldens and XML inspection do not cover. The repository wrapper installs only
Chromium headless shell 149.0.7827.55 (revision 1228) into
`.cache/playwright`, then runs the Playwright Java 1.61.0 lane:

```bash
./scripts/test-render-paint.sh
```

Direct Maven callers may use `./mvnw -Prender-paint -pl engines/render -am test`
only after the pinned shell is present and `PLAYWRIGHT_BROWSERS_PATH` points to
that cache.

For a narrow rerun, select the decorated-paint audit or raster backstop:

```bash
./scripts/test-render-paint.sh -Dtest='SvgPaintAudit*Test'
./scripts/test-render-paint.sh -Dtest='RasterDiffTest,RasterGoldenTest'
```

The browser DOM supplies transformed geometry, computed styles, text metrics,
marker/filter paint, and pixel masks; ImageIO owns raster comparison. Before an
SVG is loaded, the harness rejects scripts, event handlers, external URLs, and
active animation elements. Each build uses Playwright `browserType.launch()`
with a temporary profile and one disposable browser, context, and page. The
context is offline, blocks service workers, and registers a catch-all route
that records and aborts every page request.

The exact additional Chromium arguments are
`--disable-background-networking`, `--disable-breakpad`,
`--disable-client-side-phishing-detection`, `--disable-component-update`,
`--disable-crash-reporter`, `--disable-default-apps`,
`--disable-domain-reliability`, `--disable-extensions`, `--disable-sync`,
`--disable-translate`, `--no-default-browser-check`, `--no-first-run`, and
`--disable-features=MediaRouter,OptimizationHints,Translate`. Together they
disable background networking, updates, sync, crash telemetry/reporting,
extensions/default apps, first-run/default-browser services, translation,
media routing, and optimization lookups. Locale, UTC timezone, reduced motion,
device scale factor 1, transparent background, and fixed padding around an
SVG-derived viewport keep the result deterministic. The suite checks inline
SVG and `<img>` behavior but Chromium is its only blocking browser. Playwright
and browser components exist only in this opt-in test profile: the default
reactor and shipped runtime do not resolve or bundle them, and renderer output
and ELK geometry are unchanged.

The harness loads the repository's existing Liberation Sans test file through
a data `@font-face` and waits for `document.fonts.ready`. It does not load system
fonts or fetch fallback fonts for blocking text checks. When the bundled font
cannot display a glyph, the result is advisory `font_missing`; the audit does
not invent a bound. The adjacent OFL-1.1-RFN licence, font digest, and
provenance stay recorded in the raster manifest. No new font is introduced by
this lane.

Repository-owned built-in themes gate on numeric label-contrast baselines of
4.5:1 for normal text and 3:1 for large text. These values are contrast
baselines, not claims of WCAG conformance. User-supplied themes remain
non-blocking. Gradients and other compositions whose effective background
cannot be established produce an advisory `not_measurable` result rather than
a fabricated ratio.

Four reviewable PNG goldens provide a small raster backstop: the rich standard
graph in light and dark themes, an ArchiMate diagram with decorators and
markers, and a UML sequence diagram with fragment chrome. Regenerate
deliberately with
`./scripts/test-render-paint.sh -Ddediren.render.paint.regenerate-goldens=true`,
then review every tracked PNG and manifest change. A failed
comparison writes the actual image, changed-pixel mask, and overlay under
`.test-output/render-paint/`. Equal dimensions are required; RGBA channel
differences of 8 or less are ignored, and every remaining changed pixel fails.
The manifest pins Playwright, Chromium, the CI container digest, Eclipse
Temurin 21.0.10+7, viewport rules, DPR, font digest, scenarios, and comparator
threshold.

Regeneration is gated on a **calibration probe**, not on the environment's
identity: before writing any golden, the lane rasterizes the static
`engines/render/src/paint-test/resources/raster-calibration/calibration.svg` and
requires it to reproduce the committed `calibration.png` under the same
comparator the goldens use. An environment that agrees with the calibration
pixels may mint baselines; one that does not is refused, wherever it runs. The
calibration SVG is deliberately not produced by the render engine — a rendered
fixture would move with the renderer and stop probing the environment alone.
Keep the Playwright and Chromium versions, viewport rules, DPR, font digest,
calibration pair, and raster manifest synchronized when the canonical golden
environment changes.

Maven state stays in `.cache/maven`; the browser shell stays in
`.cache/playwright`. These repository-local caches are ignored, recoverable,
and never shipped; commit no native binary, browser cache, or new font. The
Thursday 06:00 UTC/manual job runs in the digest-pinned Playwright Java 1.61.0
Noble image, which remains a good environment but is no longer *required* to
regenerate — determinism comes from the repository, not the image: Playwright
downloads a pinned Chromium and the font is embedded as a data URI, so the host
supplies neither the browser nor the glyphs. Its image and browser names are
provenance for external test tools, not bundled marks or Dediren branding.
References to the retired paint implementation are allowed only in historical
plans/reviews under `docs/superpowers` and in threat-model history so those
records remain truthful; they are not current implementation guidance. Do not
rewrite those records or copy their terminology into active guidance.

## Related Pages

- [Pipeline & Commands](pipeline-and-commands.md) — the `render` command.
- [Layout (ELK)](layout.md) — produces the layout result that render consumes.
- [Exports (OEF & XMI)](exports.md) — the non-SVG output paths.
