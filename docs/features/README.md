# Dediren Features

`dediren` is a contract-first diagram pipeline for agentic tools. It turns a
semantic, plugin-typed JSON model into generated layout geometry, rendered SVG,
ArchiMate 3.2 OEF XML, or UML 2.5.1 XMI XML through explicit CLI commands and
process-boundary plugins.

This directory is **feature reference documentation**: a capability-by-capability
description of what Dediren does, organized so a reader can find a feature
without reading the full build/runbook surface in [`README.md`](../../README.md)
or the token-efficient authoring guide in
[`docs/agent-usage.md`](../agent-usage.md).

> These pages are *derived* documentation. The canonical truth is, in order:
> live code and tests, the public JSON schemas in [`schemas/`](../../schemas),
> the root [`README.md`](../../README.md), and
> [`docs/agent-usage.md`](../agent-usage.md). When this directory disagrees with
> those, they win — fix this directory. See
> [Keeping This Documentation Current](#keeping-this-documentation-current).

## What Dediren Is

- **Contract-first.** The stable product surface is machine-readable: public
  JSON schemas, fixtures, JSON command envelopes on stdout, first-party plugin
  manifests, and deterministic diagnostics. Agents decide success or failure
  from stdout JSON alone, never from stderr.
- **Semantic source, generated geometry.** Authored source JSON is semantic and
  plugin-typed. It must not contain absolute positions, sizes, colors, fonts, or
  SVG shape choices. Geometry is *generated* into derived layout result JSON;
  presentation lives in render policy.
- **Pluggable at a process boundary.** Layout, rendering, semantic validation,
  and export are performed by external executable plugins that communicate over
  JSON stdin/stdout. The core owns orchestration, validation, diagnostics, and
  plugin discovery — not notation or styling.
- **Multi-notation, multi-format output.** One semantic model can drive several
  notations — generic graphs, ArchiMate 3.2, and UML 2.5.1 — and produce SVG
  (static or interactive), ArchiMate OEF XML, or UML XMI XML.

## Feature Pages

| Page | Covers |
| --- | --- |
| [Pipeline & Commands](pipeline-and-commands.md) | The end-to-end pipeline and every CLI command (`validate`, `project`, `layout`, `validate-layout`, `render`, `export`, `capabilities`, `--version`) |
| [Source Model & Views](source-model.md) | The semantic source graph, namespaced properties, views/projection, semantic profiles, and fragments |
| [Plugin Runtime](plugin-runtime.md) | The plugin protocol, the five first-party plugins, discovery, capability probing, and runtime diagnostics |
| [Layout (ELK)](layout.md) | The official Java ELK plugin, layout modes (`flow`/`packed`/`auto`), and `validate-layout` quality metrics |
| [SVG Rendering](svg-render.md) | The render plugin, render policies, interactive modes, PNG rasterization, and the `artifacts[]` result shape |
| [Exports (OEF & XMI)](exports.md) | ArchiMate 3.2 OEF and UML 2.5.1 XMI export, plus the supported UML notation coverage |
| [Contracts & Schemas](contracts-and-schemas.md) | Public schemas, command envelopes, diagnostics, and version/compatibility signals |
| [Distribution & Runtime](distribution-and-runtime.md) | The agent bundle, launchers, environment variables, Class-Data-Sharing, supply-chain artifacts, and versioning |

## At a Glance

```text
validate -> project --target layout-request -> layout -> validate-layout -> render
validate -> project --target layout-request -> layout -> validate-layout -> export
```

| Notation | Source profile | View kinds | Export plugin | Export format |
| --- | --- | --- | --- | --- |
| Generic graph | (default) | `generic` | — | SVG only |
| ArchiMate | `archimate` | `archimate` | `archimate-oef` | OEF XML + SVG |
| UML | `uml` | `uml-class`, `uml-data`, `uml-activity`, `uml-sequence`, `uml-state-machine`, `uml-use-case`, `uml-component` | `uml-xmi` | XMI XML + SVG |

## Keeping This Documentation Current

This directory has no automated consistency gate of its own, so it must be
maintained deliberately. Treat it as part of the "files that move together" set
described in [`CLAUDE.md`](../../CLAUDE.md).

**Update a page in the same change that alters the feature it describes:**

- **New or changed CLI command, flag, or workflow** → update
  [Pipeline & Commands](pipeline-and-commands.md) alongside `README.md` and
  `docs/agent-usage.md`.
- **Source model / schema shape change** → update
  [Source Model & Views](source-model.md) and
  [Contracts & Schemas](contracts-and-schemas.md) alongside `schemas/`,
  `contracts`, and fixtures.
- **Plugin protocol, discovery, or a first-party plugin's capabilities** →
  update [Plugin Runtime](plugin-runtime.md).
- **ELK layout behavior, a new layout mode, or a new `validate-layout` metric**
  → update [Layout (ELK)](layout.md).
- **Render policy field, interaction mode, PNG rasterization, or styling behavior** → update
  [SVG Rendering](svg-render.md) alongside `schemas/render-policy.schema.json`.
- **OEF/XMI export semantics or expanded UML notation coverage** → update
  [Exports (OEF & XMI)](exports.md).
- **Bundle layout, launcher behavior, environment variable, or release/version
  policy** → update [Distribution & Runtime](distribution-and-runtime.md).

**Version strings.** Pages cite the current product version (`2026.06.7` at the
time of writing) only in illustrative bundle paths and `required_plugins`
examples. When the product version bumps (see [Versioning in
`CLAUDE.md`](../../CLAUDE.md) and the `release-policy`), include this directory
in the stale-version search:

```bash
rg "<old-version>" docs/features
```

**When in doubt, link, don't copy.** Prefer linking to the schema or fixture
that is the real contract over restating its fields here, so this directory
stays a map rather than a second source of truth that can rot.

**Quick check after editing docs here:**

```bash
git diff --check
```
