# Distribution & Runtime

Dediren ships as a platform-neutral agent bundle of launch scripts, jars,
schemas, fixtures, and docs — no embedded JRE. This page covers the bundle, its
launchers, the runtime environment, startup optimization, release artifacts, and
how versions signal compatibility.

[← Back to feature index](README.md)

## Runtime Prerequisites

To run a bundle (building from source is covered in
[`README.md`](../../README.md)):

- **Java 21 or newer** on `PATH` as `java`.
- `xmllint` on `PATH` for OEF/XMI standards validation.
- `curl` on `PATH` only when export validation must download a standards schema
  (offline runs supply schemas via env vars instead).

## Bundle Layout

The `dist-build` profile produces an agent-ready archive under `dist/`:

```text
dediren-agent-bundle-<version>/
  bin/
    dediren
    dediren-plugin-generic-graph
    dediren-plugin-elk-layout
    dediren-plugin-render
    dediren-plugin-archimate-oef-export
    dediren-plugin-uml-xmi-export
  lib/
  plugins/        first-party plugin manifests
  schemas/
  fixtures/
  docs/agent-usage.md
  LICENSE
  THIRD-PARTY-NOTICES.md
  bundle.json
  cds/            generated at runtime — not a tracked artifact
```

The Java archive is platform-neutral (not tied to CPU architecture) and contains
launch scripts and jars, not a JRE.

## Launchers

Each `bin/dediren*` launcher sets `DEDIREN_BUNDLE_ROOT` from its installation
root, so commands locate bundled `schemas/`, `plugins/`, and `bin/` regardless of
the caller's working directory. Manifest executable names resolve to these
bundled launchers. Project plugin directories and `DEDIREN_PLUGIN_DIRS` remain
explicit later lookup sources; plugins are never discovered implicitly from
`PATH`.

## Environment Variables

Plugin child processes receive only the variables listed in their manifests.

| Variable | Purpose |
| --- | --- |
| `DEDIREN_BUNDLE_ROOT` | Bundle/repo root for schemas, manifests, launchers. Set automatically by packaged launchers; override only for custom launchers or tests. |
| `DEDIREN_PLUGIN_DIRS` | Additional manifest directories (platform path separator). |
| `DEDIREN_PLUGIN_<PLUGIN_ID>` | Per-plugin executable override, e.g. `DEDIREN_PLUGIN_RENDER`. |
| `DEDIREN_OEF_SCHEMA_DIR` | Local OEF schema directory (offline export validation). |
| `DEDIREN_XMI_SCHEMA_PATH` | Local XMI schema file (offline export validation). |
| `DEDIREN_SCHEMA_CACHE_DIR` | Cache directory for schema downloads. |
| `DEDIREN_CDS_DIR` | Relocate Class-Data-Sharing archives (see below). |
| `DEDIREN_TRUST_MANIFEST_CAPABILITIES` | Opt-in: trust static manifest capabilities, skip the per-call runtime probe (one fewer JVM start per op); bypasses the id-mismatch pre-check. Use only with trusted bundles. Default keeps the probe. |

## Startup Optimization (Class-Data-Sharing)

Each `bin/dediren*` launcher auto-creates a Class-Data-Sharing archive
(`-XX:+AutoCreateSharedArchive`) on first invocation to speed JVM startup on
subsequent calls (one `.jsa` per launcher). Archives are written to
`<bundle>/cds/` by default, falling back to
`${XDG_CACHE_HOME:-$HOME/.cache}/dediren/cds` if the bundle directory is
read-only. Set `DEDIREN_CDS_DIR` to relocate them. The feature degrades silently
if the archive directory is unwritable — startup continues at normal speed.

## Release Artifacts & Verification

A GitHub Release publishes one platform-neutral Java archive, a `SHA256SUMS`
checksum file, and CycloneDX SBOMs. The archive carries a GitHub artifact
attestation, so a consumer can verify provenance before unpacking:

```bash
gh attestation verify dediren-agent-bundle-<version>.tar.gz --repo tommimarkus/dediren
```

## Version Compatibility Signal

The product uses **CalVer** with the shape `YYYY.0M.MICRO` (four-digit year,
zero-padded month, within-month micro counter — e.g. `2026.06.0`, then
`2026.06.1`). The version encodes the **release date, not compatibility**:
backwards-incompatible product or plugin contract changes are signaled through
release notes and schema-id changes (see
[Contracts & Schemas](contracts-and-schemas.md#compatibility-signals)), never
inferred from the version number.

The bump procedure and the full set of surfaces that move with a version are
release runbook, not a feature — they live in the **Release** section of
[`README.md`](../../README.md) and the `release-policy` rules in
[`CLAUDE.md`](../../CLAUDE.md). After a bump, include this directory in the
stale-version search (see
[the maintenance guidance](README.md#keeping-this-documentation-current)).

## Related Pages

- [Plugin Runtime](plugin-runtime.md) — discovery, manifests, capability probe.
- [Contracts & Schemas](contracts-and-schemas.md) — `bundle.json` and schema ids.
- [Exports (OEF & XMI)](exports.md) — schema cache/offline env vars.
