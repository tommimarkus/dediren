# Contracts & Schemas

Dediren is contract-first: the machine-readable surface *is* the product. This
page maps that surface — the public JSON schemas, the command envelope, the
diagnostics, and the version/compatibility signals.

[← Back to feature index](README.md)

## Public Schemas

All schemas live in [`schemas/`](../../schemas) and are redistributed in the
bundle. Shared protocol records and schema-version constants live in the
`contracts` module.

| Schema | Describes |
| --- | --- |
| [`model.schema.json`](../../schemas/model.schema.json) | Authored source graph. |
| [`layout-request.schema.json`](../../schemas/layout-request.schema.json) | Generated layout request (output of `project --target layout-request`). |
| [`layout-result.schema.json`](../../schemas/layout-result.schema.json) | Generated geometry and routes (output of `layout`). |
| [`render-metadata.schema.json`](../../schemas/render-metadata.schema.json) | Generated notation metadata (output of `project --target render-metadata`). |
| [`render-result.schema.json`](../../schemas/render-result.schema.json) | `render` envelope with `artifacts[]`. |
| [`export-request.schema.json`](../../schemas/export-request.schema.json) / [`export-result.schema.json`](../../schemas/export-result.schema.json) | `export` request/result. The result base keeps an open `artifact_kind` pattern (not a closed list) by design, for a future non-bundled export engine. |
| [`export-result.first-party.schema.json`](../../schemas/export-result.first-party.schema.json) | Stricter `export` result contract enforced for the two bundled first-party export engines (closed `artifact_kind` enum). |
| [`render-policy.schema.json`](../../schemas/render-policy.schema.json) | SVG presentation policy. |
| [`oef-export-policy.schema.json`](../../schemas/oef-export-policy.schema.json) / [`uml-xmi-export-policy.schema.json`](../../schemas/uml-xmi-export-policy.schema.json) | Export policies. |
| [`semantic-validation-result.schema.json`](../../schemas/semantic-validation-result.schema.json) | Engine semantic-validation result. |
| [`build-result.schema.json`](../../schemas/build-result.schema.json) | `build` command result: `.status`, `.views[]` (each with `.artifacts[]`/`.diagnostics[]`). Unlike every other command, `build`'s stdout **is** this document directly — it is not wrapped in `envelope.schema.json`'s `.data`. |
| [`envelope.schema.json`](../../schemas/envelope.schema.json) | The command envelope wrapping every other command's stdout. |
| [`bundle.schema.json`](../../schemas/bundle.schema.json) | The bundle's `bundle.json` metadata. |
| [`plugin-manifest.schema.json`](../../schemas/plugin-manifest.schema.json) / [`runtime-capability.schema.json`](../../schemas/runtime-capability.schema.json) | **Orphaned, pending contract cleanup.** Shipped from the retired process-plugin runtime (deleted; see [Engine Runtime](engine-runtime.md)); no live code path constructs, discovers, or reads a manifest or a capability probe today. Round-tripped from inline JSON, not a live fixture, to keep the deferred cleanup visible (`ContractRoundTripTest`). |

### Who authors what

| Artifact | Authored by agent? |
| --- | --- |
| Source model | Yes |
| SVG / OEF / UML-XMI policies | Usually reuse a fixture |
| Layout request, render metadata | Usually generated |
| Layout result, render/export result, build result | No (generated) |

## Command Envelope

Every command writes a JSON envelope to stdout
([`envelope.schema.json`](../../schemas/envelope.schema.json)) for both success
and failure, carrying `.status`, `.data`, and `.diagnostics[]`. Downstream
commands accept either a full envelope or the raw `.data` artifact.

- Agents decide success/failure from stdout JSON — **never from stderr**.
- A valid plugin error envelope is preserved with a non-zero CLI exit.

## Diagnostics

Failures are deterministic, coded diagnostics agents can branch on. Representative
codes (full repair guidance in
[`docs/agent-usage.md`](../agent-usage.md#repair-rules)):

| Code | Cause |
| --- | --- |
| `DEDIREN_SCHEMA_INVALID` | Source fails `model.schema.json` (often authored geometry). |
| `DEDIREN_DUPLICATE_ID` | Non-unique node/relationship/view/group id. |
| `DEDIREN_DANGLING_ENDPOINT` | Relationship `source`/`target` does not resolve. |
| `DEDIREN_COMMAND_INPUT_INVALID` | CLI could not read/parse a command input file. |
| `DEDIREN_PLUGIN_UNKNOWN` / `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY` / `DEDIREN_ENGINE_FAILED` | Engine lookup/dispatch failures (see [Engine Runtime](engine-runtime.md)). |
| `DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE` | ArchiMate junction detached from its incident edge routes. |

## Compatibility Signals

- **Schema ids** (e.g. `model.schema.v1`) are the durable compatibility signal.
  They change only when the contract family intentionally changes.
- **CalVer version** (`YYYY.0M.MICRO`) encodes the release date, **not**
  compatibility. Backwards-incompatible changes are communicated through release
  notes and schema-id changes — never inferred from the version number. See
  [Distribution & Runtime → Version Compatibility Signal](distribution-and-runtime.md#version-compatibility-signal).

## Module Boundaries

- `contracts` — shared protocol records and schema-version constants only.
- `core` — orchestration, validation, engine dispatch, backend-neutral
  quality checks.
- `cli` — thin: parse args, assemble requests, call `core`, print envelopes;
  constructs the engines in its single `EngineWiring` class.
- `engines/*` — notation, layout, render, export (may depend on
  `engine-api` and `contracts`, never on `core`).

## Related Pages

- [Source Model & Views](source-model.md) — the authored contract.
- [Engine Runtime](engine-runtime.md) — engine and capability contracts.
- [Pipeline & Commands](pipeline-and-commands.md) — which command emits which artifact.
