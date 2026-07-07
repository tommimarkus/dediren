# Release notes fragment — Drop PNG/raster support

> **For the release author:** this repository generates its GitHub release body
> with `gh release create … --generate-notes` (see `.github/workflows/release.yml`)
> and keeps no `CHANGELOG`. Fold the text below into the `v<next>` release body
> by hand (paste it above or below the auto-generated notes). This fragment is
> not itself shipped in the bundle; delete or archive it once folded in.

## Breaking — public schema contract family bumped

CalVer does **not** encode compatibility; the schema-id change below is the
compatibility signal. Two published render schemas change id in place:

- `render-policy.schema.v1` → **`render-policy.schema.v2`** — the top-level
  `raster` object is removed.
- `render-result.schema.v3` → **`render-result.schema.v4`** — `png` is removed
  from the `artifact_kind` enum; artifacts are now `svg` / `html` only.

## Removed

- PNG rasterization from the `render` plugin and the
  `DEDIREN_SVG_RASTERIZE_FAILED` diagnostic code.
- The Apache Batik / XML Graphics dependency family (21 jars), shrinking the
  stored-xz download by ~1.74M (≈ 20%) and reducing the render plugin's
  XML/SVG-parsing attack surface.

## Migration

1. **Render policies:** delete any `raster` block (for example
   `"raster": { "scale": 2 }`) from render-policy JSON, and update the pinned
   `render_policy_schema_version` to `render-policy.schema.v2`.
2. **Result consumers:** stop reading `png` artifacts; expect
   `render_result_schema_version` `render-result.schema.v4` with `artifact_kind`
   of `svg` or `html` only.
3. **PNG output:** dediren emits SVG; convert it with an external tool. For
   example, from an extracted `diagram.svg`:
   - `rsvg-convert diagram.svg -o diagram.png`
   - `resvg diagram.svg diagram.png`
   - `magick convert diagram.svg diagram.png` (ImageMagick)
   - `inkscape diagram.svg --export-type=png` (Inkscape)
