# Plugin Runtime

Dediren's notation, layout, rendering, and export logic all live in
**process-boundary plugins**: external executables that speak JSON over
stdin/stdout. The core orchestrates them but owns none of their domain logic.

[← Back to feature index](README.md)

## Plugin Protocol

- Plugins communicate with JSON stdin/stdout and command envelopes. An agent can
  decide success or failure from a plugin's stdout JSON alone.
- A valid plugin **error envelope** is preserved and surfaced with a non-zero CLI
  exit.
- The core **normalizes boundary failures** into structured diagnostics:
  missing executable, timeout, invalid JSON, schema mismatch, unsupported
  capability, id mismatch, and missing runtime dependency.
- **stderr is for human debugging only.**

Manifest schema: [`schemas/plugin-manifest.schema.json`](../../schemas/plugin-manifest.schema.json) ·
Capability schema: [`schemas/runtime-capability.schema.json`](../../schemas/runtime-capability.schema.json)

## First-Party Plugins

All five are official, executable plugins. They may depend on `contracts` but
must not depend on `core`.

The **plugin id** is the value you pass to `--plugin`; it is not always the
launcher name. For the export plugins the two differ (id `archimate-oef` →
launcher `dediren-plugin-archimate-oef-export`).

| Plugin id (`--plugin`) | Launcher | Role |
| --- | --- | --- |
| `generic-graph` | `dediren-plugin-generic-graph` | Semantic vocabulary, semantic validation, view projection (layout request + render metadata). Owns the `generic`, `archimate`, and `uml` profiles. |
| `elk-layout` | `dediren-plugin-elk-layout` | Layout geometry and edge routing via official Eclipse ELK Java libraries. See [Layout (ELK)](layout.md). |
| `render` | `dediren-plugin-render` | SVG (and optional interactive HTML or PNG) rendering. See [SVG Rendering](svg-render.md). |
| `archimate-oef` | `dediren-plugin-archimate-oef-export` | ArchiMate 3.2 Open Exchange Format XML. See [Exports](exports.md). |
| `uml-xmi` | `dediren-plugin-uml-xmi-export` | UML 2.5.1 XMI XML. See [Exports](exports.md). |

## Discovery

Plugin discovery is **explicit and ordered** — never implicit from `PATH`:

1. bundled first-party plugins (manifests under the bundle's `plugins/`);
2. project plugin directories such as `.dediren/plugins`;
3. user-configured directories from `DEDIREN_PLUGIN_DIRS` (platform path
   separator).

Manifest executable names resolve to bundled launchers under `bin/`. A per-plugin
override `DEDIREN_PLUGIN_<PLUGIN_ID>` (e.g. `DEDIREN_PLUGIN_RENDER`) points a
single plugin at a custom executable.

## Capability Probing

By default, before each plugin operation the core runs a runtime `capabilities`
probe and checks the reported plugin `id` against the manifest (an id-mismatch
pre-flight). This costs one JVM start per operation.

Set `DEDIREN_TRUST_MANIFEST_CAPABILITIES=1` (or `true`) to trust each plugin's
**static manifest** capabilities and skip the probe, removing that JVM start.
This bypasses the id-mismatch pre-check, so use it only with trusted,
integrity-checked bundles. Default (unset) keeps the probe and all integrity
checks.

## Process Controls

The core controls plugin child processes: timeout, working-directory handling,
and an **environment allowlist** — a plugin child receives only the environment
variables listed in its manifest. See
[Distribution & Runtime](distribution-and-runtime.md#environment-variables) for
the variables that matter.

## Runtime Diagnostics

Common plugin-boundary diagnostics (full list and repair guidance in
[`docs/agent-usage.md`](../agent-usage.md#repair-rules)):

| Code | Meaning |
| --- | --- |
| `DEDIREN_PLUGIN_UNKNOWN` | No manifest matched the requested plugin id. |
| `DEDIREN_PLUGIN_MISSING_EXECUTABLE` | Manifest executable not found. |
| `DEDIREN_PLUGIN_OUTPUT_INVALID_*` | Plugin stdout was not valid/expected JSON; pipeline stops. |
| `DEDIREN_COMMAND_INPUT_INVALID` | CLI could not read/parse a command input file. |

## Related Pages

- [Pipeline & Commands](pipeline-and-commands.md) — which command calls which plugin.
- [Distribution & Runtime](distribution-and-runtime.md) — launchers, env vars, CDS.
- [Contracts & Schemas](contracts-and-schemas.md) — envelope and capability schemas.
