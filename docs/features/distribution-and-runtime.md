# Distribution & Runtime

Dediren ships as a platform-neutral agent bundle of one launch script, jars,
schemas, fixtures, and docs — no embedded JRE. This page covers the bundle, its
launcher, the runtime environment, startup optimization, release artifacts, and
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
    dediren       the single launcher (hosts all five engines in-process)
  lib/
  schemas/
  fixtures/
  docs/agent-usage.md
  LICENSE
  THIRD-PARTY-NOTICES.md
  bundle.json
```

The Java archive is platform-neutral (not tied to CPU architecture) and contains
launch scripts and jars, not a JRE.

## The Launcher

The single `bin/dediren` launcher sets `DEDIREN_BUNDLE_ROOT` from its
installation root, so commands locate bundled `schemas/`, `fixtures/`, and
`bin/` regardless of the caller's working directory. It runs every engine
in-process; there is no per-engine launcher, standalone executable, or
`capabilities` probe, and nothing is ever looked up from `PATH`.

## Environment Variables

The engines run inside the CLI process; the export engines receive the CLI's
environment explicitly for the schema-path variables below and read nothing
else.

| Variable | Purpose |
| --- | --- |
| `DEDIREN_BUNDLE_ROOT` | Bundle/repo root for schemas and fixtures. Set automatically by the packaged launcher; override only for custom launch setups or tests. |
| `DEDIREN_OEF_SCHEMA_DIR` | Local OEF schema directory (offline export validation). |
| `DEDIREN_XMI_SCHEMA_PATH` | Local XMI schema file (offline export validation). |
| `DEDIREN_SCHEMA_CACHE_DIR` | Cache directory for schema downloads. |
| `DEDIREN_LOG_LEVEL` | Debug logging on stderr for one run: `trace`/`debug`/`info`/`warn`/`error`/`off` (default `off`). Values outside that set are rejected — the launcher interpolates this into `JAVA_OPTS`, so an allowlist is what stops JVM-argument injection. |

## Startup Optimization

The `bin/dediren` launcher applies lightweight JVM startup flags
(`-XX:TieredStopAtLevel=1 -XX:+UseSerialGC`) suited to a short-lived, run-once
process. It also passes
`-Xlog:all=off:stdout -Xlog:all=warning:stderr:uptime,level,tags`, which clears
the JVM's default stdout log sink so no VM warning (cgroup resource limits, for
one) can ever land on top of the command envelope, while still routing warnings
to stderr for humans.

## Release Artifacts & Verification

A GitHub Release publishes one platform-neutral Java archive, a `SHA256SUMS`
checksum file, and CycloneDX SBOMs. The archive carries a GitHub artifact
attestation, so a consumer can verify provenance before unpacking:

```bash
gh attestation verify dediren-agent-bundle-<version>.tar.xz --repo tommimarkus/dediren
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

- [Engine Runtime](engine-runtime.md) — the engine contract and diagnostics.
- [Contracts & Schemas](contracts-and-schemas.md) — `bundle.json` and schema ids.
- [Exports (OEF & XMI)](exports.md) — schema cache/offline env vars.
