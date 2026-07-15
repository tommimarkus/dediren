# Plan: retire interactive-svg (SVG-only render output)

Status: complete — shipped in 2026.07.15 (render-policy.schema.v3; the worktree-stacking note below is historical).

## Why

The render engine embeds a click-to-highlight JS `<script>` and its companion
`<style>` block into interactive SVGs, and packages HTML/both artifacts to host
them. Consumers build their own galleries, so the built-in interactivity and
HTML packaging are redundant. Removing them:

- **Deletes a security boundary.** `style.interaction.highlight_stroke` is the
  one value emitted into a CSS `<style>` block, where XML escaping is inert —
  the sole reason `validateColor` had to be a strict, metacharacter-free CSS
  grammar. Gone, colours only ever land in XML-escaped attributes.
- **Removes the only two raw (non-escaped) emission sinks** (`<script>` JS and
  the CSS `<style>` block), which are exactly what blocks a clean move to a
  structured (StAX) emitter. This change is the enabler for that refactor.
- Collapses render output to a single SVG artifact.

## Decisions (locked with the user)

1. **SVG only.** Remove the JS/CSS interactivity *and* the HTML/both artifact
   packaging. `SvgRenderEngine` always returns one `svg` artifact.
2. **Schema id bumps `render-policy.schema.v2` → `v3`.** Removing the
   `interactive` field and the `interaction` style object is a subtractive,
   breaking contract-family change; per `CLAUDE.md §Versioning` the id must
   move. (Additive styling work stayed v2; this is the opposite.)
3. **Stacked on the styling branch**, not merged-first. Retirement re-reverts
   part of the styling branch's own `highlight_stroke` CSS-injection hardening
   within one branch lineage; messier history accepted.

## Scope (Phase 1)

Contract + schema (v3):
- `contracts/.../render/RenderPolicy.java` — remove `interactive` field.
- `contracts/.../render/SvgStylePolicy.java` — remove `interaction` field.
- delete `contracts/.../render/SvgInteractionStyle.java`.
- `contracts/.../ContractVersions.java` — `RENDER_POLICY_SCHEMA_VERSION = v3`.
- `schemas/render-policy.schema.json` — drop `interactive` property + the
  `interaction` `$defs` object + its `$ref`; update the version const/field.
- `fixtures/render-policy/*.json` (default, generic-shapes, archimate, uml,
  rich, dark) + `docs/assets/pipeline.render-policy.json` — bump version to v3.
- delete `fixtures/render-policy/interactive-svg.json` and its consumers.

Engine / emitter:
- `engines/render/.../svg/SvgDocument.java` — remove `interactionStyleBlock`,
  `interactionScriptBlock`, both `if (interactive)` guards + the `interactive`
  bool, `interactiveMode`, `buildArtifacts`, `htmlWrap`, and the
  `DEFAULT_HIGHLIGHT_STROKE*` constants.
- `engines/render/.../SvgRenderEngine.java` — emit a single `svg` artifact.
- `engines/render/.../node/uml/RenderInputValidator.java` — remove the
  interactive / `highlight_stroke` validation.

Tests:
- Rewrite/trim: `SvgRenderEngineTest`, `ColorGrammarTest` (drop the
  CSS-injection-via-`highlight_stroke` case — that sink is gone), render +
  cli `MainTest`, `CliLayoutRenderCommandTest`, `EngineEnvelopeContractTest`,
  `ContractRoundTripTest`, `SchemaValidatorTest`, `ContractVersionsTest` (v3),
  `SequenceStylingTest` (fixture version).
- Add: a negative test asserting a policy that sets `interactive` is now
  rejected (unknown property under `additionalProperties:false`).
- `RenderDeterminismTest` goldens refresh (no script/style blocks anywhere).

Docs: `README.md` (if it mentions interactivity/html output),
`docs/features/svg-render.md`, `docs/agent-usage.md`,
`docs/features/engine-runtime.md`, `docs/features/README.md`,
`docs/features/pipeline-and-commands.md`, and `docs/threat-model.md` (remove the
CSS-injection boundary section — a real threat-surface reduction).

## Verification

- `./mvnw -pl contracts -am test` (round-trip, schema, version).
- `./mvnw -pl engines/render,cli -am test`.
- `./mvnw test`, then `./mvnw -Pquality spotless:apply` + `./mvnw -Pquality verify`.
- `./mvnw -pl dist-tool -am verify -Pdist-smoke` (agent-usage tokens change →
  keep `AgentUsageDocConsistencyTest` green).
- Audit gates (SVG render): **quick** `test-quality-audit` + **quick**
  `devsecops-audit`; confirm the injection boundary is removed, not orphaned.

## Release note (breaking)

Removed built-in interactive SVG (click-to-highlight) and HTML/both artifact
packaging; render emits a single SVG artifact. `render-policy` schema id moves
to `render-policy.schema.v3`; policies using `interactive` or
`style.interaction` no longer validate.

## Phase 2 (separate change)

With the raw script/style sinks gone, migrate the emitter
(`SvgDocument`/`EdgeRenderer`/shape builders/`NodeLabels`/`UmlSequenceRenderer`)
to a JDK `XMLStreamWriter`-backed structured emission, byte-matched, no raw-write
exceptions. Zero new dependency (`java.xml`).
