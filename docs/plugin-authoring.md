# Dediren Plugin Authoring

This guide is the shipped contract for building a third-party Dediren plugin:
an executable plus a manifest, speaking JSON over stdin/stdout. It documents
everything the core runtime does to your plugin process — discovery,
invocation, environment, output validation, and failure signaling. The
companion consumer guide is `docs/agent-usage.md`.

A plugin is any executable (any language) that:

1. answers a `capabilities` probe with a JSON capability document, and
2. answers each work command with exactly one JSON command envelope on stdout.

Preserve this guide alongside `docs/agent-usage.md` when redistributing a
Dediren archive.

## Registration and Discovery

Discovery is explicit, in this order; the first directory containing
`<plugin-id>.manifest.json` wins:

1. Bundled first-party plugins: `<bundle>/plugins/`.
2. Project plugins: `.dediren/plugins/` under the directory the CLI is run
   from (your project), then `.dediren/plugins/` under the bundle root.
3. `DEDIREN_PLUGIN_DIRS`: extra manifest directories, separated with the
   platform path separator.

Plugins are never discovered from `PATH`. To register a plugin for one
project, place the manifest and executable in `<project>/.dediren/plugins/`
and run the CLI from the project directory — no environment variables needed.

## Manifest

`<plugin-id>.manifest.json`, validated against
`schemas/plugin-manifest.schema.json`:

```json
{
  "plugin_manifest_schema_version": "plugin-manifest.schema.v1",
  "id": "ticket-stats",
  "version": "0.1.0",
  "executable": "ticket-stats",
  "capabilities": ["export"],
  "allowed_env": ["PATH"]
}
```

- `id` must match the file name prefix and the `id` your executable reports
  from the `capabilities` probe.
- `executable` resolution, in order:
  1. A `DEDIREN_PLUGIN_<PLUGIN_ID>` environment variable overrides the
     manifest entirely. The variable name is the plugin id uppercased with
     every `-` replaced by `_` (id `elk-layout` reads
     `DEDIREN_PLUGIN_ELK_LAYOUT`).
  2. An absolute path is used as-is.
  3. A relative path resolves against the directory containing the manifest
     (bundled first-party manifests are the one exception: a bare name there
     resolves to the bundle's `bin/` launchers).
  There is no `PATH` lookup. A missing file fails with
  `DEDIREN_PLUGIN_MISSING_EXECUTABLE`.
- `capabilities` gates dispatch: a command for a capability not listed here
  fails with `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY` before your executable
  runs.
- `allowed_env` is an allowlist. The child process environment is cleared and
  only these variables (taken from the CLI's environment) are forwarded.
  Anything you need — `PATH`, `HOME`, `JAVA_HOME`, your own variables — must
  be listed.

## Process Contract

Every invocation is a fresh process:

```
<executable> <command> [args...]   # request JSON on stdin, one JSON document on stdout
```

- Working directory: the bundle root (product root), never the caller's
  directory. Resolve any relative paths you receive against absolute inputs,
  and treat paths you emit as absolute.
- Environment: cleared, then populated from manifest `allowed_env` only.
- stdin: the full request JSON (empty for `capabilities`), then closed.
- stdout: exactly one JSON document. Nothing else — no logs, no banners.
- stderr: free-form, for human debugging only; core ignores it except in
  failure messages.
- Timeout: 10 seconds per invocation, after which the process is killed and
  the command fails with `DEDIREN_PLUGIN_TIMEOUT`.

### The capabilities probe

Before every work command, core runs `<executable> capabilities` and expects
a raw JSON capability document (not an envelope) matching
`schemas/runtime-capability.schema.json`:

```json
{
  "plugin_protocol_version": "plugin.protocol.v1",
  "id": "ticket-stats",
  "capabilities": ["export"],
  "runtime": {"language": "python"}
}
```

- `id` must equal the manifest id or the command fails with
  `DEDIREN_PLUGIN_ID_MISMATCH`.
- The requested capability must be listed or the command fails with
  `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`.
- A non-zero probe exit fails with `DEDIREN_PLUGIN_CAPABILITY_PROBE_FAILED`;
  non-JSON output with `DEDIREN_PLUGIN_CAPABILITY_INVALID_JSON`; JSON that
  misses the schema with `DEDIREN_PLUGIN_CAPABILITY_SCHEMA_INVALID`.

The probe is mandatory for every non-bundled manifest on every call.
`DEDIREN_TRUST_MANIFEST_CAPABILITIES` skips it only for bundled first-party
plugins; manifests from `.dediren/plugins` or `DEDIREN_PLUGIN_DIRS` are always
probed.

### Work commands

The first argv token names the capability's command; some capabilities add
flags:

| Capability | Invocation | Request on stdin | `ok` data validated against |
| --- | --- | --- | --- |
| `export` | `<executable> export` | `schemas/export-request.schema.json` | `schemas/export-result.schema.json` |
| `layout` | `<executable> layout` | `schemas/layout-request.schema.json` | `schemas/layout-result.schema.json` |
| `render` | `<executable> render` | layout result + policy (+ metadata) | `schemas/render-result.schema.json` |
| `semantic-validation` | `<executable> validate --profile <profile>` | source model | `schemas/semantic-validation-result.schema.json` |
| `projection` | `<executable> project --target <target> --view <view>` | source model | `schemas/layout-request.schema.json` or `schemas/render-metadata.schema.json` |

### Envelope and failure signaling

Work-command stdout must be a command envelope
(`schemas/envelope.schema.json`):

```json
{
  "envelope_schema_version": "envelope.schema.v1",
  "status": "ok",
  "data": { },
  "diagnostics": []
}
```

How core interprets your process result:

- `status: ok` + exit 0: success. `data` is validated against the
  capability's result schema; a mismatch fails with
  `DEDIREN_PLUGIN_OUTPUT_INVALID_DATA`.
- `status: error` (any exit code): your envelope is preserved verbatim on CLI
  stdout and the CLI exits non-zero (your exit code, or 3 if you exited 0).
  This is the correct way to report any failure you can describe — including
  a missing runtime dependency of your own — as `diagnostics[]` entries with
  your own codes.
- `status: ok` + non-zero exit: contradiction, fails with
  `DEDIREN_PLUGIN_PROCESS_FAILED`.
- stdout not JSON: `DEDIREN_PLUGIN_OUTPUT_INVALID_JSON`. Not an envelope:
  `DEDIREN_PLUGIN_OUTPUT_INVALID_ENVELOPE`.

Agents decide success or failure from stdout JSON alone; make your
`diagnostics[]` codes stable and your messages actionable.

## Export Plugins

The most common third-party capability. The request
(`schemas/export-request.schema.json`) carries three parts:

- `source`: the validated source model.
- `layout_result`: computed geometry and routes.
- `policy`: the caller's `--policy` document, forwarded verbatim. You own its
  shape and its validation; publish your own policy schema.

The successful result (`schemas/export-result.schema.json`) is a single
artifact:

```json
{
  "export_result_schema_version": "export-result.schema.v1",
  "artifact_kind": "ticket-stats+json",
  "content": "…the artifact as one string…"
}
```

- `artifact_kind` names your format and matches
  `^[a-z0-9][a-z0-9.-]*\+(xml|json|text)$` — declare your own kind honestly.
  (Bundled first-party export plugins are additionally pinned to
  `schemas/export-result.first-party.schema.json`, a closed enum of
  `archimate-oef+xml` and `uml-xmi+xml`.)
- `content` is the whole artifact as one string. Note the shape difference
  from `render`, which returns `.data.artifacts[]`.

Extract the artifact from the CLI stdout envelope:

```bash
dediren export --plugin ticket-stats --policy policy.json \
  --source model.json --layout layout-result.json > out.json
jq -r '.data.artifact_kind' out.json   # e.g. ticket-stats+json
jq -r '.data.content' out.json > stats.json
```

## Checklist for a New Plugin

1. Write the executable: handle `capabilities` (raw JSON) and your work
   command (envelope on stdout, request on stdin).
2. Write `<plugin-id>.manifest.json` next to it; list every environment
   variable you need in `allowed_env`.
3. Register: drop both into `<project>/.dediren/plugins/`.
4. Verify: run your executable directly with `capabilities` and check the
   output against `schemas/runtime-capability.schema.json`, then run the real
   CLI command from the project directory and decide from stdout `.status`
   and `.diagnostics[]`.
5. On failure, match the diagnostic code against the taxonomy above; repair
   rules for consumers live in `docs/agent-usage.md`.
