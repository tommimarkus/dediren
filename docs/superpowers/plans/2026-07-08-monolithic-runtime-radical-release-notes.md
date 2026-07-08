# Release notes fragment — Monolithic compiler restructure (Phase 1)

> **For the release author:** this repository generates its GitHub release body
> with `gh release create … --generate-notes` (see `.github/workflows/release.yml`)
> and keeps no `CHANGELOG`. Fold the text below into the `v2026.07.14` release
> body by hand (paste it above the auto-generated notes). This fragment is not
> itself shipped in the bundle; delete or archive it once folded in.

CalVer encodes the release date, **not** compatibility. This is a
backwards-incompatible product change; the schema-id bumps below
(`dediren-bundle.schema.v2`, new `build-result.schema.v1`) are the compatibility
signal.

## Breaking — the process-plugin protocol is deleted

dediren is now a single-JVM diagram **compiler**. The five first-party plugins
(generic-graph, elk-layout, render, archimate-oef-export, uml-xmi-export) are
in-process library engines behind a typed `engine-api`. There are no external
plugin executables, no manifests, and no capability probes — and no plugin
discovery of any kind (`PATH`, project dirs, or user config).

The CLI stdout envelope contract is unchanged: same envelope schema, same
statuses, same diagnostic values, same exit codes. The per-stage subcommands
(`validate`, `project`, `layout`, `validate-layout`, `render`, `export`) and
their schema'd stage artifacts remain the public debug/interop surface.

## New — one-shot `dediren build`

`dediren build model.json --out dist/` produces all views and all requested
artifact kinds in a single invocation. Its stdout envelope `data` validates the
new **`build-result.schema.v1`** (`schemas/build-result.schema.json`):
per-view outcomes with artifact records, aggregate status, non-zero exit if any
view fails. Warm one-shot build measured ~0.38 s vs the ~1.9 s /
13–15 JVM-spawn five-invocation flow it replaces (~5×).

## Retired environment variables / config knobs

Gone (had no effect after the protocol deletion):

- `DEDIREN_PLUGIN_DIRS`
- `DEDIREN_ALLOW_PROJECT_PLUGINS`
- `DEDIREN_PLUGIN_<PLUGIN_ID>` (per-plugin executable overrides)
- `DEDIREN_TRUST_MANIFEST_CAPABILITIES`

Unchanged and still honoured: `DEDIREN_BUNDLE_ROOT`, `DEDIREN_CDS_DIR`,
`DEDIREN_OEF_SCHEMA_DIR`, `DEDIREN_XMI_SCHEMA_PATH`, `DEDIREN_SCHEMA_CACHE_DIR`,
`HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY`.

## Retired diagnostics

Plugin-runtime boundary codes core can no longer emit (no child processes
remain):

`DEDIREN_PLUGIN_MISSING_EXECUTABLE`, `DEDIREN_PLUGIN_TIMEOUT`,
`DEDIREN_PLUGIN_PROCESS_FAILED`, `DEDIREN_PLUGIN_ID_MISMATCH`,
`DEDIREN_PLUGIN_IO_ERROR`, `DEDIREN_PLUGIN_MANIFEST_INVALID`,
`DEDIREN_PLUGIN_CAPABILITY_PROBE_FAILED`,
`DEDIREN_PLUGIN_CAPABILITY_INVALID_JSON`,
`DEDIREN_PLUGIN_CAPABILITY_SCHEMA_INVALID`,
`DEDIREN_PLUGIN_OUTPUT_INVALID_JSON`,
`DEDIREN_PLUGIN_OUTPUT_INVALID_ENVELOPE`,
`DEDIREN_PLUGIN_OUTPUT_INVALID_DATA`.

New: **`DEDIREN_ENGINE_FAILED`** — an unexpected in-process engine failure
(successor to the process-crash category).

Surviving wire strings (still emitted; a rename is deferred to the contract
cleanup follow-up): `DEDIREN_PLUGIN_UNKNOWN` (now "unknown engine id"),
`DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`.

## Bundle / distribution changes

- **Single launcher.** The five per-plugin appassembler launchers
  (`bin/dediren-plugin-*`) collapse to one `bin/dediren`; one runtime CDS
  archive instead of five.
- **Bundle descriptor bump — `dediren-bundle.schema.v1` → `v2`**
  (`bundle.json`): the `plugins[]` array and `elk_helper` pointer (both
  process-plugin surfaces) are removed.

## Doc / surface removals

- `docs/plugin-authoring.md` is removed — no external plugin-authoring surface
  remains. The third-party plugin roadmap is reversed.
- `required_plugins[]` in source models is still accepted and shape-validated,
  now informational (its entries name bundled engines); field retirement is
  deferred to the contract-cleanup follow-up.

## Not covered here (already shipped)

The PNG/raster removal (`render-policy.schema.v2`, `render-result.schema.v4`,
Batik drop) landed in released `2026.07.13` and has its own fragment.
