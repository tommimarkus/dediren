# Drop PNG/Raster Support Implementation Plan

> **Agentic worker:** Execute this plan with the
> `superpowers:subagent-driven-development` skill (or `superpowers:executing-plans`
> if run in a separate session). Work task-by-task, TDD-first, committing each task
> on `main` (direct-main is allowed here) with explicit-path staging. This plan
> contains **no product version bump** — the CalVer bump is a separate follow-on
> commit governed by `souroldgeezer-policy:release-policy`, not part of any task
> here.

## Goal

Remove PNG/raster rendering from dediren entirely. The `render` plugin will emit
only `svg` and `html` artifacts; SVG→PNG conversion is delegated to external CLI
tools (`rsvg-convert`, `resvg`, ImageMagick `convert`/`magick`, Inkscape). This
deletes the Apache Batik / XML Graphics dependency family (21 jars, 4.2M raw,
~1.74M ≈ 20% of the stored-xz download) from the render plugin and shrinks both
the download and the render plugin's XML/SVG-parsing attack surface. Because it
removes fields and enum members from two published schemas, it is an
**intentional contract-family change**: `render-policy.schema` bumps `v1→v2` and
`render-result.schema` bumps `v3→v4`, communicated through release notes (CalVer
carries no compatibility signal — see `docs/architecture-guidelines.md` §4, §10).

## Architecture

This work implements idea **I4 (radicalized)** from
`docs/superpowers/reviews/2026-07-08-runtime-size-speed-challenge.md` (the I4
ledger and the same-day follow-up decision "drop PNG support altogether instead
of an optional raster pack"). No standalone spec exists; that review plus this
plan's Global Constraints are the authoritative design source. The change is a
schema-id bump (`docs/architecture-guidelines.md` §10 "Break a public schema
vN→vN+1": big-bang, one schema file per family replaced in place, release notes
carry the old→new mapping) combined with a dependency removal from a single leaf
plugin (`render`) and its bundling in `dist-tool`. It touches nothing in the
plugin process boundary, discovery, envelope, or schema-cache mechanics; the
render plugin keeps emitting a valid `ok` envelope, only without the `png`
artifact branch.

## Tech Stack

Java 21+, Maven Wrapper (`./mvnw`), JUnit 6 (Jupiter API) + AssertJ, Jackson 3 (`tools.jackson`
databind; `com.fasterxml.jackson.annotation` annotations) / Jackson 3
(`tools.jackson`, used in some render tests), JSON
Schema (Draft 2020-12) under `schemas/`, appassembler-bundled render launcher,
`dist-tool` bundle + `-Pdist-smoke`, Spotless (google-java-format) + SpotBugs
under `-Pquality`.

## Global Constraints

- **Authoritative source.** The I4 ledger + follow-up in
  `docs/superpowers/reviews/2026-07-08-runtime-size-speed-challenge.md` and this
  plan's decisions govern. Key rulings, restated inline:
  - Drop PNG/raster **entirely** — no optional raster add-on pack.
  - SVG→PNG is delegated to external CLI tools; the docs must name
    `rsvg-convert`, `resvg`, ImageMagick, and Inkscape.
  - This is an intentional contract change: `render-policy.schema.v1→v2` and
    `render-result.schema.v3→v4`.
- **Schema-id bump ≠ version bump.** The two `*.schema.vN` id changes are part
  of this work (they are the compatibility signal, §4). The product CalVer in
  root `pom.xml` is **not** touched by this plan; its bump is a separate commit
  under `release-policy`. Do not edit any of the CalVer version-assertion
  surfaces (`MainTest`, `ContractRoundTripTest` product-version asserts,
  manifests, `DistModuleTest`, `.github/workflows/release.yml`) except where a
  test literally asserts a `render-*.schema.vN` id, which does change here.
- **Files that move together (schema).** Per `CLAUDE.md`, a public JSON shape
  change updates `schemas/`, `contracts` records + schema-version constants,
  fixtures, plugin mapping code, and schema/round-trip tests **in the same
  change**. Task 1 keeps that set atomic.
- **Files that move together (render + docs).** Render-policy/result changes
  also pull `plugins/render`, CLI render tests, `README.md`, and
  `docs/agent-usage.md`; user-facing render behavior changes update `README.md`
  and `docs/agent-usage.md` together.
- **THIRD-PARTY-NOTICES.md is generated.** It is a protected surface — never
  hand-edit. Removing the Batik deps + the `DistTool` attribution entries makes
  the next `dist-tool` build regenerate it without Batik. Do not stage a
  hand-edited copy; let the build produce it (and only stage it if it is a
  tracked generated artifact the repo already commits — verify with
  `git status` before staging).
- **AgentUsageDocConsistencyTest.** `docs/agent-usage.md` must keep every
  `DEDIREN_*` token and CalVer string matching source. `DEDIREN_SVG_RASTERIZE_FAILED`
  is **not** referenced in `docs/agent-usage.md` today (only in an old plan
  doc), so its removal is safe; verify with grep before and after.
- **Verification lanes** (sandbox disabled for all `./mvnw` — JUnit `@TempDir`
  fails on read-only `/tmp` under the sandbox; the `JsonSupportFuzzTest` also
  only passes sandbox-disabled):
  - contracts/schema: `./mvnw -pl contracts -am test`
  - render + CLI: `./mvnw -pl plugins/render,cli -am test`
  - distribution: `./mvnw test` then
    `./mvnw -pl dist-tool -am verify -Pdist-smoke` then `git diff --check`
  - full gate before final commit: `./mvnw -Pquality spotless:apply` then
    `./mvnw -Pquality verify`
- **Audit gates (SVG render row).** `test-quality-audit`: Quick over the changed
  contract/plugin/CLI tests. `devsecops-audit`: Quick over schema, renderer,
  README, and **dependency posture** (this change removes a whole dependency
  family and alters bundle contents — call the devsecops pass out explicitly in
  the handoff).
- **Git.** Direct commits to `main` allowed; one task per commit; explicit-path
  staging only (no `git add -A`); do not stage generated/ignored outputs
  (`dist/`, `target/`, generated `*.svg`) unless the repo already tracks them.
  Start and end each task with `git status --short --branch`.
- **No `./mvnw` during authoring by parallel agents** does not apply to the
  executor — the executor MUST run the lanes above; it just must run them
  sandbox-disabled and never in parallel with another Maven build sharing
  `target/`.

---

## Task 1 — Contract-family change: drop `raster` from render-policy (v1→v2) and `png` from render-result (v3→v4)

This is the atomic "files that move together" set: schemas + `contracts`
records/constants + fixtures + round-trip/contract tests. Everything in this task
lands in one commit so the round-trip suite is green at the commit boundary.

**Files**

- Modify: `schemas/render-policy.schema.json`
  - `render_policy_schema_version` const `render-policy.schema.v1` →
    `render-policy.schema.v2`.
  - Remove the `"raster": { "$ref": "#/$defs/raster" }` property (line ~40) and
    the entire `raster` entry under `$defs` (lines ~317–324).
- Modify: `schemas/render-result.schema.json`
  - `render_result_schema_version` const `render-result.schema.v3` →
    `render-result.schema.v4`.
  - `artifact_kind` enum `["svg", "html", "png"]` → `["svg", "html"]`.
  - **Decision (state in commit body):** leave the `encoding` enum
    (`["utf-8","base64"]`) unchanged. `png` was `base64`'s only first-party
    producer, but keeping `base64` valid-but-unused avoids a second, unrelated
    ripple and keeps `RenderArtifact` unchanged. Removing `base64` is out of
    scope (see Open Questions).
- Delete: `contracts/src/main/java/dev/dediren/contracts/render/RasterPolicy.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/render/RenderPolicy.java`
  - Remove the `RasterPolicy raster` record component (line 10). Resulting record
    has `renderPolicySchemaVersion, semanticProfile, page, margin, style,
    interactive, accessibility`.
- Modify: `contracts/src/main/java/dev/dediren/contracts/ContractVersions.java`
  - `RENDER_RESULT_SCHEMA_VERSION` → `"render-result.schema.v4"`.
  - `RENDER_POLICY_SCHEMA_VERSION` → `"render-policy.schema.v2"`.
- Modify (fixtures — grep-driven): every fixture and asset carrying the two
  version strings. Known: `fixtures/render-policy/*.json`
  (`render_policy_schema_version`), any `fixtures/render-metadata` /
  `fixtures/source` files that embed a render policy, and
  `docs/assets/pipeline.render-policy.json`. Run
  `grep -rln 'render-policy.schema.v1\|render-result.schema.v3' fixtures docs schemas`
  and bump each `v1→v2` / `v3→v4`. Remove any `"raster"` block found in a
  fixture (none known — confirm with `grep -rn '"raster"' fixtures`).
- Test (modify): `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`
  - Update the render-policy fixture JSON string literals'
    `render_policy_schema_version` to `render-policy.schema.v2` (lines ~498,
    ~542) and the assertion at ~483 (`policy.renderPolicySchemaVersion()`
    expected value).
  - Update the `render-result.schema.v3` assertion (line ~536) to
    `render-result.schema.v4`.
  - Confirm no reference to `RasterPolicy` remains.
- Test (modify): `contracts/src/test/java/dev/dediren/contracts/render/RenderArtifactTest.java`
  - Remove `pngArtifactCarriesBase64Encoding` (constructs an `artifact_kind`
    `"png"`, now schema-invalid). Keep `textArtifactOmitsEncoding`. The
    `RenderArtifact` record and its `encoding` field are unchanged.

**Interfaces**

- `RenderPolicy` loses `RasterPolicy raster()`. Any caller of `.raster()` is
  handled in Task 2 (render plugin). No other module reads it (verified: only
  `plugins/render/Main.java` and `RenderInputValidator.java`).
- `ContractVersions.RENDER_POLICY_SCHEMA_VERSION` / `RENDER_RESULT_SCHEMA_VERSION`
  keep their names; only values change, so `Main.java`'s
  `RenderResult(ContractVersions.RENDER_RESULT_SCHEMA_VERSION, …)` automatically
  emits `v4`.

**Steps (TDD)**

- [ ] Write the failing state into `ContractRoundTripTest` first: change the
      expected schema-id assertions to `v2`/`v4`. Run
      `./mvnw -pl contracts -am test` (sandbox disabled) — expect failures (const
      still v1/v3, and `RasterPolicy` still compiles).
- [ ] Bump `ContractVersions` constants; delete `RasterPolicy.java`; drop the
      `raster` component from `RenderPolicy`. This will break compilation of
      `plugins/render` (out of scope for this module test) but `contracts` compiles.
- [ ] Update the two schema JSON files (const bumps + remove `raster` property/$def
      + remove `png` enum member).
- [ ] Bump the fixture version strings (grep-driven) and remove `RenderArtifactTest`'s
      png test.
- [ ] Run `./mvnw -pl contracts -am test` — expect green.
- [ ] `./mvnw -Pquality spotless:apply` on touched Java; review
      `git diff -- schemas contracts fixtures docs/assets` for only-intentional
      changes; stage explicit paths; commit
      (`feat(contracts,schemas)!: drop raster from render-policy v2 and png from render-result v4`).

---

## Task 2 — Render plugin: remove rasterizer, wiring, validation, tests, and Batik deps

**Files**

- Delete: `plugins/render/src/main/java/dev/dediren/plugins/render/SvgRasterizer.java`
- Delete: `plugins/render/src/test/java/dev/dediren/plugins/render/SvgRasterizerTest.java`
- Delete: `plugins/render/src/test/java/dev/dediren/plugins/render/RasterBorderScanTest.java`
  (the pixel-level Batik raster crop check; its geometry-level sibling
  `SvgAuditTest` stays and is font/raster-independent).
- Modify: `plugins/render/src/main/java/dev/dediren/plugins/render/Main.java`
  - Remove the raster block (lines ~99–108: the `if (input.policy().raster() != null)`
    branch, the `png` `RenderArtifact`, and the `DEDIREN_SVG_RASTERIZE_FAILED`
    catch). `artifacts` becomes just `buildArtifacts(interactiveMode(...), svg)`;
    if the `new ArrayList<>(…)` wrapper and `ArrayList` import are now unused,
    remove them. Remove the now-unused `SvgRasterizer` reference.
- Modify: `plugins/render/src/main/java/dev/dediren/plugins/render/node/uml/RenderInputValidator.java`
  - Remove `import dev.dediren.contracts.render.RasterPolicy;` (line 7) and the
    `raster.scale` / `raster.background` validation block (lines ~404–417).
- Modify: `plugins/render/src/test/java/dev/dediren/plugins/render/MainTest.java`
  - Remove `rejectsRasterScaleAboveMax`, `rejectsInvalidRasterBackground`,
    `emitsPngArtifactWhenRasterRequested`.
  - Replace `omitsPngArtifactWithoutRaster` with a permanent regression guard
    (e.g. `neverEmitsPngArtifact`) that renders a normal policy and asserts the
    stdout `doesNotContain("\"artifact_kind\":\"png\"")` — this locks the removal.
  - Remove now-unused imports (`ImageIO`, `ByteArrayInputStream`, `Base64`,
    `BufferedImage` if present).
- Modify: `plugins/render/pom.xml`
  - Remove the `org.apache.xmlgraphics:batik-transcoder` and
    `org.apache.xmlgraphics:batik-codec` dependencies (lines ~32–39). These are
    the only two declared Batik deps; the other 16 batik-* jars are transitive
    and drop out automatically.
- Modify (optional, non-load-bearing): `plugins/render/src/test/java/dev/dediren/plugins/render/SvgAuditTest.java`
  - Line ~115 comment mentions "rasterizer noise"; reword to avoid a dangling
    reference. Not required for correctness.
- Modify: `docs/threat-model.md`
  - Add a short note (in the render/plugin-runtime section, wherever XML/parser
    surface is discussed) that the `render` plugin no longer bundles Apache Batik
    / XML Graphics; SVG is emitted directly and no longer round-tripped through an
    SVG DOM/transcoder, so the render plugin's XML-parsing attack surface is
    reduced. PNG conversion is now an out-of-process, user-chosen external tool
    outside the dediren trust boundary. (Per `CLAUDE.md` "Files That Move
    Together", XML-parser-hardening-adjacent changes update
    `docs/threat-model.md` in the same commit as the change that removes the
    parsing library.)

**Interfaces**

- Render plugin stdout envelope: unchanged shape, but `artifacts[]` can no longer
  contain a `png` element. Emits `render-result.schema.v4` via the bumped
  constant.
- `DEDIREN_SVG_RASTERIZE_FAILED` diagnostic code is removed from the product.
  Confirm it is not asserted anywhere else: `grep -rn RASTERIZE plugins core cli`
  should be empty after this task.

**Steps (TDD)**

- [ ] Add/replace the `neverEmitsPngArtifact` guard in `MainTest` and delete the
      three raster tests. Run `./mvnw -pl plugins/render,cli -am test` (sandbox
      disabled) — expect a compile failure (still references `SvgRasterizer` /
      `RasterPolicy` in `Main`/validator) or, once those compile, the new guard is
      green. Watch it fail against the current raster-emitting code path first if
      feasible, else rely on the compile break as the red state.
- [ ] Delete `SvgRasterizer.java`, `SvgRasterizerTest.java`, `RasterBorderScanTest.java`.
- [ ] Strip the raster branch from `Main.java` and the raster validation from
      `RenderInputValidator.java`; fix unused imports.
- [ ] Remove the two Batik deps from `plugins/render/pom.xml`.
- [ ] Run `./mvnw -pl plugins/render,cli -am test` — expect green (CLI render e2e
      still passes; it never asserted png in the default policy).
- [ ] Add the threat-model note to `docs/threat-model.md` (render/plugin-runtime
      section) in the established voice: no more bundled Batik / XML Graphics,
      reduced XML-parsing attack surface, PNG conversion now external to the trust
      boundary.
- [ ] `./mvnw -Pquality spotless:apply`; review `git diff`; stage explicit paths
      (including `docs/threat-model.md`); commit
      (`feat(render)!: remove PNG rasterizer, raster policy validation, and Batik
      deps; note the Batik removal in the threat model`).

---

## Task 3 — dist-tool: drop Batik attributions, the raster smoke path, and regenerate notices

**Files**

- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
  - Remove all 18 `batik-*` entries from `THIRD_PARTY_ATTRIBUTIONS`
    (lines ~109–126). Leave `commons-io` / `commons-logging` — verify they are
    still bundled by other plugins before removing anything beyond the batik-*
    set (they are Apache Commons, not Batik; keep unless the dist-smoke notice
    check proves them orphaned).
  - Remove the "Second render: raster-enabled policy (PNG / Batik path)" block
    (lines ~514–541: `rasterPolicyJson`, `rasterPolicyFile`, the second
    `runBundleCommand`, and the `assertPngRenderOutput(pngRenderOutput)` call).
  - Remove the `assertPngRenderOutput` method (lines ~966–994).
  - Remove now-unused imports (e.g. `java.util.Base64`, `ObjectNode` if no longer
    referenced elsewhere in the file — check before deleting).
- Regenerate (do NOT hand-edit): `THIRD-PARTY-NOTICES.md`. After the dep + map
  removal, run the `dist-tool` build so the generator drops the Batik lines.
  Confirm whether the repo tracks this file (`git status`); if tracked, stage the
  regenerated copy; if generated-under-`target/`, do not stage it.

**Interfaces**

- Bundle `lib/` loses the 21-jar Batik/xmlgraphics family (~4.2M raw), shrinking
  the download (the I4 win). No launcher or manifest changes — render's
  appassembler program is unchanged.
- `-Pdist-smoke` now asserts SVG (and interactive HTML) render only; the PNG
  smoke assertion is gone.

**Steps (TDD)**

- [ ] Remove the raster smoke block + `assertPngRenderOutput` first, then run
      `./mvnw -pl dist-tool -am verify -Pdist-smoke` (sandbox disabled) — it will
      fail while the batik attribution map still expects jars that are no longer
      bundled, or while the notice generator lists absent jars. Use that as the red
      state.
- [ ] Remove the 18 batik-* attribution entries and unused imports.
- [ ] Re-run `./mvnw test` then `./mvnw -pl dist-tool -am verify -Pdist-smoke` and
      `git diff --check` — expect green and a Batik-free bundle.
- [ ] Inspect the regenerated `THIRD-PARTY-NOTICES.md` diff: it should only lose
      Batik/xmlgraphics attribution lines. `spotless:apply`; stage explicit paths
      (include the notices file only if tracked); commit
      (`build(dist)!: drop Batik attributions and PNG raster smoke from the bundle`).

---

## Task 4 — Docs: README, agent-usage (external SVG→PNG tools), and feature docs

**Files**

- Modify: `README.md`
  - Line 6: `rendered SVG/PNG` → `rendered SVG`.
  - Line 21: pipeline image alt text `… SVG/PNG via render …` → `… SVG via
    render …` (and see the pipeline-diagram note below).
  - Line 24: `render   (SVG / PNG)` → `render   (SVG)`.
  - Line 104: rework "For PNG output, ArchiMate/UML notations, …" — remove "PNG
    output" from the feature list; if a sentence is needed, point PNG seekers at
    external converters.
- Modify: `docs/agent-usage.md`
  - Line ~218: remove the paragraph about the base64 `png` artifact.
  - Line ~223: the "Render Policy Options" intro lists `raster` (PNG); remove the
    `raster` mention.
  - **Add** a short "PNG output" note: dediren emits SVG; convert with an external
    tool — name `rsvg-convert`, `resvg`, ImageMagick (`magick convert diagram.svg
    diagram.png`), and Inkscape (`inkscape diagram.svg --export-type=png`).
  - Run `grep -n 'render-policy.schema\|render-result.schema' docs/agent-usage.md`
    and bump any cited schema id `v1→v2` / `v3→v4`.
  - Keep `AgentUsageDocConsistencyTest` green: verify no removed `DEDIREN_*` token
    (there is none — `DEDIREN_SVG_RASTERIZE_FAILED` is not in this file) and no
    stale CalVer.
- Modify: `docs/features/svg-render.md` (lines 4, 16 — drop PNG from the
  artifact-kind list and the "optionally … a PNG raster image" clause; add the
  external-tool pointer).
- Modify: `docs/features/pipeline-and-commands.md` (lines 94, 96 — drop the
  "optionally … PNG" clauses and the `png` artifact_kind).
- Modify: `docs/features/contracts-and-schemas.md` (line 24 — "SVG/PNG
  presentation policy" → "SVG presentation policy").
- Modify (check): `docs/features/README.md`, `docs/features/plugin-runtime.md` —
  grep for `png`/`raster` and remove stale mentions.
- Pipeline diagram (`docs/assets/pipeline.svg` + `docs/assets/pipeline.render-policy.json`):
  the SVG is a dogfooded generated asset. If its embedded text or the alt text
  references PNG, regenerate it from the (now v2) `pipeline.render-policy.json`
  rather than hand-editing the SVG. If it contains no PNG text, only the README
  alt text (Task 4, line 21) needs changing. Confirm with
  `grep -n 'PNG\|png\|raster' docs/assets/pipeline.svg docs/assets/pipeline.render-policy.json`.

**Interfaces** — none (docs only).

**Steps**

- [ ] Edit README + agent-usage + feature docs; add the external-tool guidance.
- [ ] `grep -rn 'png\|PNG\|raster\|Raster\|SVG/PNG' README.md docs/agent-usage.md docs/features`
      — confirm only intentional, non-stale mentions remain (e.g. external-tool
      guidance).
- [ ] Verify docs lane: `git diff --check`. Run
      `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest
      -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — expect green.
- [ ] Stage explicit doc paths; commit
      (`docs: drop PNG/raster; point PNG output at external SVG converters`).

---

## Task 5 — Release-notes deliverable: contract-family change + external converters

Per `docs/architecture-guidelines.md` §10 (big-bang schema break), the bump ships
with release notes carrying an explicit old→new mapping agents can apply
mechanically. This task authors that text as a deliverable; it does **not** bump
CalVer or tag (that is the separate release-policy commit).

**Files**

- Create/append the release-notes fragment where the repo keeps release-note
  content. First inspect for an existing home
  (`grep -ril 'release notes\|changelog' README.md docs` and check
  `.github/workflows/release.yml` for a notes source). If a conventional
  location exists (e.g. a `CHANGELOG` or a `docs/release-notes/` fragment), add
  there; otherwise create a single new fragment file under the plan's own
  directory (`docs/superpowers/plans/`) named for this change and note in the
  handoff that the release author must fold it into the `v<next>` release body.
  Do not invent a new top-level release-notes convention.
- The fragment must state:
  - **Breaking (contract-id):** `render-policy.schema.v1 → v2` (removed
    top-level `raster` object) and `render-result.schema.v3 → v4` (removed `png`
    from `artifact_kind`; artifacts are now `svg`/`html` only).
  - **Removed:** PNG rasterization and the `DEDIREN_SVG_RASTERIZE_FAILED`
    diagnostic; the Apache Batik / XML Graphics dependency family (download
    shrinks ~1.74M).
  - **Migration:** delete any `raster` block from render policies; stop reading
    `png` artifacts. For PNG output, convert the emitted SVG with `rsvg-convert`,
    `resvg`, ImageMagick, or Inkscape (give one example command each).
  - **Note:** CalVer does not encode this break — the schema-id change is the
    compatibility signal.

**Interfaces** — none.

**Steps**

- [ ] Locate the release-notes home; author the fragment with the old→new mapping.
- [ ] `git diff --check`; stage the explicit path; commit
      (`docs: add release notes for the render PNG/raster removal (schema v2/v4)`).

---

## Final verification (before handoff, not a separate commit unless fixes are needed)

- [ ] `./mvnw -pl contracts -am test`
- [ ] `./mvnw -pl plugins/render,cli -am test`
- [ ] `./mvnw test` then `./mvnw -pl dist-tool -am verify -Pdist-smoke` then
      `git diff --check`
- [ ] `./mvnw -Pquality spotless:apply` then `./mvnw -Pquality verify` (full gate:
      format + SpotBugs + tests)
- [ ] All `./mvnw` runs sandbox-disabled.
- [ ] Run the SVG-render audit gates: `test-quality-audit` (Quick — changed
      contract/plugin/CLI tests) and `devsecops-audit` (Quick — schema, renderer,
      README, and the Batik dependency-removal / bundle-content change). Fix block
      findings; accept or fix warn/info and record the decision in the handoff.

---

## Self-Review

**Spec coverage** (against the I4 measured removal surface):

- [ ] `contracts/RasterPolicy.java` deleted; `RenderPolicy.raster` field removed (Task 1).
- [ ] `render-policy.schema.json` raster `$defs` + property removed; id `v1→v2` (Task 1).
- [ ] `render-result.schema.json` `png` removed from `artifact_kind`; id `v3→v4` (Task 1).
- [ ] `ContractVersions` both constants bumped; round-trip + `RenderArtifactTest`
      updated; fixtures + `docs/assets/pipeline.render-policy.json` version strings bumped (Task 1).
- [ ] `SvgRasterizer.java` + `Main.java` raster wiring + `RenderInputValidator`
      raster validation removed; `MainTest` raster tests replaced with a
      no-png guard; `SvgRasterizerTest` + `RasterBorderScanTest` deleted;
      batik deps removed from `plugins/render/pom.xml` (Task 2).
- [ ] `dist-tool` batik attributions + raster dist-smoke + `assertPngRenderOutput`
      removed; `THIRD-PARTY-NOTICES.md` regenerated by build, not hand-edited (Task 3).
- [ ] `README.md` (4 mentions), `docs/agent-usage.md` (2 mentions + external-tool
      guidance), and `docs/features/*` PNG/raster mentions removed;
      `AgentUsageDocConsistencyTest` green (Task 4).
- [ ] `docs/threat-model.md` notes the Batik/XML-surface reduction (Task 2).
- [ ] Release notes document the intentional `v2`/`v4` change + external SVG→PNG
      tools with old→new mapping (Task 5).

**Placeholder scan:** No `TODO`, `FIXME`, stubbed method, or "implement later"
left in any task. Every deleted symbol (`RasterPolicy`, `SvgRasterizer`,
`DEDIREN_SVG_RASTERIZE_FAILED`, batik entries) has a confirming grep step.

**Type-consistency checks:**

- `RenderPolicy` component removal compiles because the only readers
  (`plugins/render/Main.java`, `RenderInputValidator.java`) are updated in the
  same slice (Task 2). Confirm with
  `grep -rn 'RasterPolicy\|\.raster()' --include=*.java` returning empty
  post-Task-2.
- `RenderResult` still constructs from `ContractVersions.RENDER_RESULT_SCHEMA_VERSION`,
  which now yields `v4` — no call-site edit needed in `Main.java`.
- `RenderArtifact` record and its `encoding` field are unchanged; `base64`
  remains a schema-valid encoding with no first-party producer (deliberate — see
  Open Questions), so no code path is left referencing a removed enum member.
- Schema-id strings appear only in `ContractVersions`, the two schema files,
  fixtures, `ContractRoundTripTest`, and possibly `docs/agent-usage.md`; a
  repo-wide `grep -rn 'render-policy.schema.v1\|render-result.schema.v3'` must be
  empty at the end.

## Open Questions

- **`base64` encoding enum:** PNG was its only first-party producer. This plan
  keeps `base64` valid in `render-result.schema.v4` to avoid a second ripple; a
  reviewer may prefer pruning it (and simplifying `RenderArtifact`) as part of
  the same v4 break. Decide before Task 1 commit.
- **Release-notes home:** the repo has no obvious `CHANGELOG`. If none is found,
  the fragment lands under `docs/superpowers/plans/` for the release author to
  fold in — confirm that handoff path is acceptable, or point to the real
  release-notes surface.
- **Pipeline SVG regeneration:** if `docs/assets/pipeline.svg` embeds "PNG" text,
  it must be regenerated from the v2 policy through the render pipeline rather
  than hand-edited; confirm the regeneration command/dogfooding step in the
  assets README.
