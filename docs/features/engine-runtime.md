# Engine Runtime

Dediren's notation, layout, rendering, and export logic live in **first-party
engines**: in-tree library modules behind the typed `engine-api` interfaces,
compiled into the CLI and dispatched in a single JVM. The core orchestrates
them but owns none of their domain logic.

[← Back to feature index](README.md)

## Engine Contract

- Every command prints a JSON command envelope on stdout. An agent can decide
  success or failure from stdout JSON alone.
- A structured engine failure becomes the engine's published **error
  envelope**, preserved verbatim and surfaced with a non-zero CLI exit.
- The core resolves engine lookups in memory: an unknown engine id yields
  `DEDIREN_PLUGIN_UNKNOWN`; an id bound only under another capability yields
  `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`; an unexpected in-memory engine
  failure is normalized to `DEDIREN_ENGINE_FAILED`.
- A **missing runtime dependency** is reported by the engine that owns it, as a
  structured error envelope core preserves (the export engines emit
  `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` /
  `DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE` when `xmllint` is absent).
- **stderr is for human debugging only.**

## First-Party Engines

All five are compile-time library modules. They may depend on `engine-api`,
`contracts`, and the notation cores they need, but must not depend on `core`;
only the CLI's `EngineWiring` class constructs them. There is no engine
discovery of any kind — no `PATH` lookup, no manifest directories, no
executable overrides, and no per-engine launcher. The **engine id** is simply
the value you pass to `--plugin`.

| Engine id (`--plugin`) | Role |
| --- | --- |
| `generic-graph` | Semantic vocabulary, semantic validation, view projection (layout request + render metadata). Owns the `generic`, `archimate`, and `uml` profiles. |
| `elk-layout` | Layout geometry and edge routing via official Eclipse ELK Java libraries. See [Layout (ELK)](layout.md). |
| `render` | SVG rendering. See [SVG Rendering](svg-render.md). |
| `archimate-oef` | ArchiMate 3.2 Open Exchange Format XML. See [Exports](exports.md). |
| `uml-xmi` | UML 2.5.1 XMI XML. See [Exports](exports.md). |

All five are hosted in-process by the single `bin/dediren` launcher; there is
no per-engine launcher, standalone executable, or `capabilities` probe to run
against. The `plugin-manifest.schema.json` and `runtime-capability.schema.json`
schemas remain in `schemas/` from the retired process-plugin runtime; they are
unused by any live code path and are pending contract cleanup — see
[Contracts & Schemas](contracts-and-schemas.md#public-schemas).

## Runtime Diagnostics

Common engine diagnostics (full list and repair guidance in
[`docs/agent-usage.md`](../agent-usage.md#repair-rules)):

| Code | Meaning |
| --- | --- |
| `DEDIREN_PLUGIN_UNKNOWN` | Unknown engine id; the bundled set is the five ids above. |
| `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY` | The engine id exists, but not for the requested stage. |
| `DEDIREN_ENGINE_FAILED` | Unexpected in-memory engine failure; pipeline stops. |
| `DEDIREN_COMMAND_INPUT_INVALID` | CLI could not read/parse a command input file. |

## Related Pages

- [Pipeline & Commands](pipeline-and-commands.md) — which command calls which engine.
- [Distribution & Runtime](distribution-and-runtime.md) — the launcher, env vars, CDS.
- [Contracts & Schemas](contracts-and-schemas.md) — envelope and diagnostic schemas.
