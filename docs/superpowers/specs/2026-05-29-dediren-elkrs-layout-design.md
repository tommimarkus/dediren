# Dediren Rust ELK Layout Design

Date: 2026-05-29

## Purpose

Replace the Java-backed ELK layout helper with the Rust `elkrs` layout
libraries from `https://github.com/tommimarkus/elkrs`, then remove Java from
the active Dediren product, release workflow, distribution archive, docs, and
tests.

The work must happen in an isolated worktree. The implementation worktree for
this design is:

```text
.worktrees/elkrs-layout-replacement
```

## Primary Decisions

- Keep the public plugin id `elk-layout`.
- Keep `layout-request.schema.v1` and `layout-result.schema.v1` unchanged.
- Keep `dediren-cli` and `dediren-core` backend-neutral.
- Keep ELK interpretation inside `crates/dediren-plugin-elk-layout`.
- Replace Java helper execution with in-process Rust calls to `elkrs-layered`.
- Pin `elkrs` Cargo dependencies to the exact upstream commit currently tagged
  `v1.0.0`: `aeba4e35a0648b57caa88b8588099a3bb48021ae`.
- Keep `DEDIREN_ELK_RESULT_FIXTURE` for deterministic repair and test loops.
- Remove `DEDIREN_ELK_COMMAND`; no active product path should invoke Java.
- Remove Java/Gradle setup from release CI and distribution assembly.
- Treat the behavior/runtime change as a product/plugin versioned release
  change, not a docs-only cleanup.

## Architecture

`dediren-plugin-elk-layout` becomes a pure Rust first-party process plugin. It
still receives JSON over stdin and emits JSON command envelopes on stdout. The
plugin process boundary stays the Dediren runtime contract; only the internal
layout backend changes.

The plugin maps a Dediren `LayoutRequest` into `elkrs-core` graph types, runs
`elkrs_layered::LayeredLayout`, then maps generated geometry back into a
Dediren `LayoutResult`. Mapping code remains plugin-owned because Dediren core
must not own ELK-specific interpretation, semantic vocabularies, SVG styling,
or OEF export semantics.

Runtime capabilities should report an in-process Rust backend. The current
`external-elk` runtime kind and bundled Java helper availability checks should
be removed or replaced with a non-Java capability shape that reflects the live
implementation.

## Dependency And Supply Chain

The committed dependency shape should use exact-revision Git dependencies:

```toml
elkrs-core = { git = "https://github.com/tommimarkus/elkrs", rev = "aeba4e35a0648b57caa88b8588099a3bb48021ae" }
elkrs-layered = { git = "https://github.com/tommimarkus/elkrs", rev = "aeba4e35a0648b57caa88b8588099a3bb48021ae" }
```

`elkrs-json` may be used if it reduces mapping risk without forcing Dediren to
round-trip through string JSON internally. If it is used, pin it to the same
revision.

No committed release code should depend on `/home/souroldgeezer/repos/elkrs`
or any other local path. Cargo.lock is the release evidence for the exact
upstream revision. The implementation plan should prefer `rev` over `tag`
because the repo already pins GitHub Actions to commit SHAs, and exact source
identity is the stronger supply-chain boundary.

This migration should preserve the existing release provenance posture:
GitHub Actions remain SHA-pinned, jobs keep explicit minimum permissions, and
release archive provenance attestation stays in place.

## Data Flow

The plugin entrypoint should remain thin:

1. Parse `capabilities` or `layout`.
2. Read stdin JSON.
3. Validate the Dediren layout request shape.
4. Delegate layout behavior to a Rust adapter module.
5. Emit a `CommandEnvelope` with either layout data or structured diagnostics.

The Rust adapter owns four internal phases:

1. `LayoutRequest -> elkrs_core::ElkGraph`

   Map nodes to ELK nodes. Width and height hints become initial node sizes.
   Groups become compound nodes when the current `elkrs` model can represent
   the request faithfully. Edges become ELK edges. Layout preferences are
   mapped only through supported `elkrs` options, including direction, spacing,
   and orthogonal routing where available.

2. Run `elkrs_layered::LayeredLayout`

   Treat `elkrs` as the layout engine. Dediren should not add route-point
   rewriting to recover Java output. If `elkrs` lacks a feature, the plugin
   should use best-effort graph intent or return an explicit warning or
   diagnostic.

3. `elkrs_core::ElkGraph -> LayoutResult`

   Convert generated node positions, sizes, and edge sections to Dediren layout
   result objects. Preserve Dediren ids, source ids, projection ids, labels,
   group provenance, and warnings.

4. Dediren-only enrichment

   Keep contract translation, group result bounds, dangling endpoint warnings,
   empty or missing group warnings, provenance, and schema compatibility in
   Dediren. Keep routing hints only if the Rust backend can justify them from
   request semantics and generated graph shape.

## Java Cleanup Scope

The implementation should remove all Java-owned active product surfaces:

- Delete tracked files under `crates/dediren-plugin-elk-layout/java/**`.
- Do not touch unrelated untracked user work such as a pre-existing
  `crates/dediren-plugin-elk-layout/java/bin/` directory in another checkout
  unless the user explicitly asks.
- Remove Java and Gradle setup from `.github/workflows/release.yml`.
- Remove Gradle cache setup and Gradle project-cache keys.
- Remove Java helper build invocation from `cargo xtask dist build`.
- Remove Java runtime checks from `cargo xtask dist smoke`.
- Stop copying `runtimes/elk-layout-java/` into distribution archives.
- Remove Java helper notices from third-party notice generation.
- Replace stale Java notices with Rust dependency notices that reflect what
  ships.
- Remove `bundle.json.elk_helper`, or replace it with a backend/runtime field
  that does not name a separate helper.
- Remove Java-specific diagnostics from docs and tests, including
  `DEDIREN_ELK_JAVA_UNAVAILABLE` and `DEDIREN_ELK_JAVA_UNSUPPORTED`.
- Update `fixtures/plugins/elk-layout.manifest.json` allowed env to drop
  `DEDIREN_ELK_COMMAND` and `PATH`, keeping `DEDIREN_ELK_RESULT_FIXTURE` if
  fixture mode remains.
- Update `README.md` and `docs/agent-usage.md` so users no longer install
  Java, Gradle, SDKMAN, or a helper runtime.

`DEDIREN_ELK_COMMAND` should no longer be documented, allowed, or honored. If a
caller sets it, the plugin should ignore it because the manifest allowlist
should not forward it into the plugin.

## Error Handling

All plugin failures must remain machine-readable command envelopes. Invalid
input JSON, schema-shaped request errors, and unsupported request values should
produce non-zero exits with `CommandEnvelope::error` diagnostics.

`elkrs` layout failures should be normalized into a stable Dediren diagnostic,
for example `DEDIREN_ELK_LAYOUT_FAILED`, with the underlying message included
as diagnostic context. The plugin must not expose panics, Rust debug dumps, or
stderr-only failure information as the primary agent signal.

Fixture mode should retain clear diagnostics for missing fixture files, invalid
fixture JSON, and fixture data that fails the Dediren layout-result contract.

## Behavior Compatibility

The migration should preserve the Dediren contract surface, not Java coordinate
parity. Tests should assert stable contract behavior, validity, route quality
metrics, and renderability. They should not require pixel-perfect equality with
the old Java helper.

Known Java-era behaviors need explicit decisions during implementation:

- Relationship-type shared endpoint merging should be preserved only if it can
  be represented as graph intent through ports or supported `elkrs` behavior.
- Grouped layouts should use compound nodes where `elkrs` supports the required
  hierarchy. Any unsupported grouping behavior should produce warnings or
  documented limitations rather than custom geometry repair.
- Layout preferences should be mapped only where `elkrs` supports the option.
  Unsupported preferences should be covered by tests and docs.

## Test Strategy

The implementation should use focused tests at the right layer:

- Unit tests for adapter mapping from Dediren requests into `elkrs` graphs:
  direction, spacing, node size hints, groups, compound nodes, dangling edges,
  and unsupported preference handling.
- Unit tests for `elkrs` graph output mapping into Dediren layout results:
  node geometry, edge points, group bounds, provenance, warnings, labels, and
  routing hints.
- Plugin process tests proving real Rust layout works without any configured
  runtime helper.
- Fixture-mode tests proving deterministic repair loops still work.
- CLI integration tests proving `dediren layout --plugin elk-layout` invokes
  the Rust-backed plugin and returns valid layout results.
- Distribution tests proving archive build and smoke paths do not require
  Java or copy Java runtimes.
- Documentation and manifest tests proving Java-specific user instructions and
  allowed env are gone.

Test-quality acceptance criteria from `test-quality-audit`:

- Unit tests should be requirement-derived, assert public behavior, and avoid
  expected values computed by duplicating the adapter pipeline (`HC-2`,
  `rust.HC-1`).
- Integration tests should name the seam they exercise: plugin process,
  CLI-to-plugin, or distribution archive (`I-POS-1`, `rust.I-POS-2`).
- Tests should assert observable outputs: command envelope status, diagnostic
  codes, contract fields, route points, layout quality metrics, and archive
  contents. Success-only tests with no behavioral oracle are not enough
  (`HC-1`, `I-HC-A10`, `rust.I-LC-2`).
- Ignored Java-era real-helper tests should be removed or converted to normal
  Rust-backend tests. New ignored tests need a documented opt-in command and
  resource reason (`rust.LC-2`).
- Fixture and render tests should derive expected values from schemas,
  fixtures, and domain invariants, not from pasted live output snapshots
  (`HC-3`, `I-HC-B1`).
- Tests that mutate process environment should restore it or isolate the
  mutation (`rust.I-HC-A2`).

## Verification

The implementation plan should start with narrow checks for changed areas, then
run broader gates because this change crosses plugin behavior, release archive
contents, CI, docs, manifests, and version surfaces.

Required checks:

```bash
cargo fmt --all -- --check
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin --locked
cargo test -p dediren --test cli_layout --locked
cargo test -p dediren --test real_elk_render --locked
cargo test -p dediren-contracts --test schema_contracts --locked
cargo test -p dediren-contracts --test contract_roundtrip --locked
cargo test --workspace --locked
git diff --check
```

Release and cleanup checks:

```bash
cargo xtask dist build --target x86_64-unknown-linux-gnu
cargo xtask dist smoke "dist/dediren-agent-bundle-$(cargo xtask version)-x86_64-unknown-linux-gnu.tar.gz"
rg -n 'Java|java|Gradle|SDKMAN|DEDIREN_ELK_COMMAND|DEDIREN_ELK_JAVA|elk-layout-java|runtimes/elk-layout-java' README.md docs .github fixtures crates xtask Cargo.toml Cargo.lock
```

The version search must use the actual previous version and actual bumped
version chosen during implementation.

If full distribution checks are too expensive in the implementation turn, the
handoff must say which checks were skipped and why. Skipped release checks are
not equivalent to passing release evidence.

## Audit Gates

Use `devsecops-audit` in Quick mode for the implementation diff before calling
the work complete. The audit should cover:

- Git dependency pinning and Cargo.lock evidence.
- Release workflow permissions and action pinning.
- Removal of decorative Java-era CI controls.
- Third-party notice accuracy.
- Release artifact provenance and bundle metadata.

Use `test-quality-audit` in Deep mode for the bounded layout/plugin test suite,
or Quick mode for each changed test file if Deep mode is too expensive. The
audit should look for weak or characterization-style tests around:

- adapter mapping;
- plugin process envelopes;
- CLI layout behavior;
- distribution smoke behavior;
- stale ignored tests.

Block findings must be fixed. Warn/info findings should be fixed or explicitly
accepted in the handoff with reasoning.

## Versioning

This migration changes shipped product/plugin behavior, first-party plugin
semantics, runtime capability output, manifest allowed env, and distribution
archive contents. It therefore requires a product/plugin version bump in the
same commit as the implementation and the matching annotated tag for the
bumped version, for example `v0.17.0`, before pushing.

Use a pre-1.0 minor bump unless implementation discovers that only patch-level
behavior changed. Update all encoded release surfaces required by `AGENTS.md`:

- `Cargo.toml`;
- `Cargo.lock`;
- `fixtures/plugins/*.manifest.json`;
- `fixtures/source/*.json` `required_plugins[].version`;
- README bundle examples;
- `docs/agent-usage.md` examples;
- distribution `xtask` usage text;
- tests or fixtures that assert version strings.

After the bump, run stale-version searches for both the previous version and
any skipped patch versions.

## Documentation Requirements

`README.md` remains the main user-facing document. It should explain:

- `elk-layout` is Rust-backed through pinned `elkrs` crates.
- Java, Gradle, SDKMAN, and `DEDIREN_ELK_COMMAND` are no longer required.
- Fixture mode still exists for deterministic repair and test loops.
- Distribution archives no longer contain `runtimes/elk-layout-java`.
- Any temporary `elkrs` parity limitations that affect real user output.

`docs/agent-usage.md` must match the shipped bundle behavior. It should remove
Java runtime troubleshooting and replace it with Rust-backed layout behavior,
fixture-mode guidance, and current diagnostic codes.

## Non-Goals

- Do not add authored coordinates or presentation styling to source graph JSON.
- Do not move ELK interpretation into `dediren-core`.
- Do not add a second long-lived backend selector.
- Do not keep Java as a fallback runtime.
- Do not claim full ELK Layered parity for `elkrs`.
- Do not rewrite route points after `elkrs` layout as a compatibility shim.
- Do not use local path dependencies in committed release code.

## Open Risks

- `elkrs` v1.0.0 is intentionally bounded and may not cover all Java helper
  behavior Dediren currently exercises.
- Grouped layouts, relationship-type endpoint merging, and route-quality render
  expectations are the highest migration-risk areas.
- Distribution notice generation must be checked carefully so Java dependency
  reports are not left behind while new Rust dependencies are omitted.
- Existing Java-named tests may contain useful route-quality assertions that
  should be preserved after renaming, not simply deleted.
