# Render plugin rename + PNG parity

Date: 2026-06-18

## Summary

Rename the `svg-render` plugin to `render` (full depth: module, Maven
coordinates, Java package, plugin id, executable, policy schema family) and let
that same plugin emit a PNG artifact alongside its SVG. PNG is rasterized from
the generated SVG with Apache Batik and sized by a scale factor. PNG output is
opt-in per render policy.

## Goal

Wherever a caller gets an SVG today, they can also get a PNG of the same
diagram from one `render` invocation. "Parity with SVG" — same plugin, same
command, PNG available on demand.

## Non-goals

- No new standalone raster plugin (rejected: PNG lives in the renamed `render`
  plugin).
- No external rasterization recipe (rejected: the product emits PNG itself).
- No pixel-exact rendering guarantees. Batik text metrics/fonts may differ from
  a browser; tests assert structural properties, not exact pixels.
- The version bump and `v<version>` tag are not part of this feature change.
  They are a separate follow-on commit sequenced after integration per
  `release-policy`.

## Decisions (from brainstorming)

- Rasterization lives **inside the renamed `render` plugin** (Batik).
- Plugin input for rasterization is the **plugin's own generated SVG** — no
  re-render, no cross-plugin dependency.
- Resolution is controlled by a **scale factor** in the policy.
- The **policy schema family is renamed** `svg-render-policy` → `render-policy`
  (intentional contract-family rename, communicated via the schema-id change).
- The rename **cuts to the full internal depth** (module dir, Maven artifactId,
  Java package), not just the public surface.
- PNG is **opt-in**: emitted only when the policy carries a `raster` block.

## Rename map

| Surface | From | To |
|---|---|---|
| Module dir | `plugins/svg-render/` | `plugins/render/` |
| Root `pom.xml` `<module>` | `plugins/svg-render` | `plugins/render` |
| Maven `artifactId` | `svg-render` | `render` |
| Java package | `dev.dediren.plugins.svgrender` | `dev.dediren.plugins.render` |
| jdeps `<param>` in plugin `pom.xml` | `dev.dediren.plugins.svgrender.*` | `dev.dediren.plugins.render.*` |
| Plugin id (manifest) | `svg-render` | `render` |
| Executable / appassembler program | `dediren-plugin-svg-render` | `dediren-plugin-render` |
| Manifest fixture file | `fixtures/plugins/svg-render.manifest.json` | `fixtures/plugins/render.manifest.json` |
| Source-fixture `required_plugins[].id` | `svg-render` | `render` |
| Per-plugin env override token | `DEDIREN_PLUGIN_SVG_RENDER` | `DEDIREN_PLUGIN_RENDER` |
| dist-tool `Launcher` entry + smoke refs | `svg-render` / `dediren-plugin-svg-render` | `render` / `dediren-plugin-render` |
| README / `docs/agent-usage.md` / `docs/features/*` | `--plugin svg-render`, bundle paths, env token | `--plugin render`, new paths, new token |

The env override token is derived from the plugin id
(`DEDIREN_PLUGIN_<ID uppercased, '-'→'_'>`), so renaming the id changes it to
`DEDIREN_PLUGIN_RENDER`.

Files under `docs/superpowers/plans/` keep the old names: plans are
implementation history, not live truth, and must not be rewritten.

## Policy schema: `render-policy.schema.v1` + `raster` block

- Rename `schemas/svg-render-policy.schema.json` →
  `schemas/render-policy.schema.json`.
- Update `$id` to `https://dediren.dev/schemas/render-policy.schema.json`.
- Rename the discriminator field `svg_render_policy_schema_version` →
  `render_policy_schema_version`; its `const` becomes `render-policy.schema.v1`.
- `contracts` constant `SVG_RENDER_POLICY_SCHEMA_VERSION` →
  `RENDER_POLICY_SCHEMA_VERSION` with value `render-policy.schema.v1`.
- Update all 5 fixtures under `fixtures/render-policy/` (`default-svg.json`,
  `uml-svg.json`, `rich-svg.json`, `archimate-svg.json`, `interactive-svg.json`):
  field name and value. Fixture filenames are kept as-is (renaming them is
  unrequested churn).
- The plugin's `RenderInputValidator` and any policy-parsing code accept the new
  field name and reject the old one.

New optional top-level `raster` block in the policy:

```json
"raster": {
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "scale":      { "type": "number", "exclusiveMinimum": 0, "maximum": 8 },
    "background": { "$ref": "#/$defs/color" }
  }
}
```

- `scale` defaults to `1` when omitted (PNG at the SVG's intrinsic pixel size).
- `background` defaults to transparent when omitted.
- The presence of the `raster` block is what enables PNG output.

## `render-result` schema: PNG artifact

- Add `"png"` to the `artifact_kind` enum.
- Add an optional `encoding` field: `enum ["utf-8", "base64"]`, default
  `"utf-8"`. PNG artifacts set `encoding: "base64"` and carry base64-encoded PNG
  bytes in `content`. `svg` and `html` artifacts are unchanged (no `encoding`,
  treated as `utf-8`).
- This is an intentional contract change: bump the schema version
  `render-result.schema.v2` → `render-result.schema.v3` and
  `contracts` constant `RENDER_RESULT_SCHEMA_VERSION` to match.

## Rasterization

- Add `org.apache.xmlgraphics:batik-transcoder` and
  `org.apache.xmlgraphics:batik-codec` to the `render` plugin's `pom.xml` only.
  No other module gains a Batik dependency.
- New `SvgRasterizer` unit in the `render` plugin:
  - Input: the SVG string the plugin already produces, plus the resolved
    `raster` config.
  - Computes target pixel width/height as the SVG's intrinsic size × `scale`,
    preserving aspect ratio, via `PNGTranscoder` transcoding hints.
  - Applies `background` when set; otherwise produces a transparent PNG.
  - Output: PNG bytes, then base64-encoded for the envelope.
- Plugin flow: render SVG as today; if the policy has a `raster` block, rasterize
  the static SVG and append a `png` artifact (`encoding: "base64"`) after the
  `svg` artifact. Interactive `html` output, when present, is untouched and is
  not the rasterization source — PNG always rasterizes the static SVG.

## Components and boundaries

- `render` plugin (renamed): SVG generation (unchanged logic), policy parsing
  with the new field name and `raster` block, and the new `SvgRasterizer`.
  Depends on `contracts` and Batik; not on `core`.
- `contracts`: schema-version constants only (`RENDER_RESULT_SCHEMA_VERSION`,
  `RENDER_POLICY_SCHEMA_VERSION`).
- `core`/`cli`: capability is still `render`; the `render` command is unchanged
  in shape. Only the plugin id string in examples/tests changes.
- `schemas/`: `render-policy.schema.json`, `render-result.schema.json`.
- `dist-tool`: launcher entry, env token, and dist-smoke references.

## Error handling

- Rasterization failure (Batik transcode error, unreadable intrinsic size)
  becomes a structured plugin error envelope with a non-zero CLI exit, per the
  plugin runtime rules. It must not crash the process or emit a partial PNG.
- Invalid `raster.scale` (≤0 or >8) is rejected by schema validation before
  execution.
- Old policy field `svg_render_policy_schema_version` is rejected as an
  unknown/again-mismatched policy, surfacing a clear schema-mismatch diagnostic.

## Testing

Render plugin (`plugins/render`):
- `SvgRasterizer` produces valid PNG: magic-byte header (`\x89PNG`), decoded
  dimensions equal intrinsic × scale (for representative scales incl. 1 and 2),
  background fill applied vs. transparent.
- Policy with no `raster` block yields SVG only (no `png` artifact); policy with
  `raster` yields SVG + PNG in order.
- `scale` bound enforcement (0 and >8 rejected).
- Base64 content decodes to the same PNG bytes.
- Assertions are structural/tolerant, not pixel-exact (Batik font metrics).

Rename regression:
- Manifest id/capability, CLI `render --plugin render` happy path and error
  envelopes.
- `contracts` round-trip with `render-result.schema.v3` and
  `render-policy.schema.v1`.
- `AgentUsageDocConsistencyTest` green: `DEDIREN_PLUGIN_RENDER` token and CalVer
  strings present and consistent across source and `docs/agent-usage.md`.
- dist-smoke launches `dediren-plugin-render` and renders via `--plugin render`.

## Verification

Run with the Maven sandbox disabled (JUnit `@TempDir` needs writable temp):

```bash
./mvnw -pl plugins/render,cli -am test
./mvnw -pl contracts -am test
./mvnw -pl dist-tool -am verify -Pdist-smoke
git diff --check
```

A full `./mvnw test` before the change is called complete, since the change
crosses contracts, a plugin, CLI examples, and public docs.

## Files that move together

Per CLAUDE.md, the following change in one coordinated set:
- Public JSON shape: `schemas/render-result.schema.json`,
  `schemas/render-policy.schema.json`, `contracts`, render-policy fixtures,
  source fixtures, plugin mapping/validation code, schema/round-trip tests.
- Plugin runtime/rename: manifest, `dist-tool` launcher + env token, CLI
  behavior/tests, README notes, compatibility tests.
- User-facing/agent docs: `README.md` and `docs/agent-usage.md` (and
  `docs/features/*` references) in the same change.
