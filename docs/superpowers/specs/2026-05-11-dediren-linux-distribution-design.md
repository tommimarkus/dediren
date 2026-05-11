# Dediren Linux Distribution Design

Date: 2026-05-11

## Purpose

`dediren` needs a first distribution path that is useful to agents without
requiring a source checkout at runtime. The first slice is a repo-local Linux
distribution archive that can be built manually from the repository, unpacked
anywhere, and run from its own `bin/` directory.

The archive keeps the existing contract-first product shape: agents interact
with the CLI, plugin manifests, command envelopes, JSON schemas, runtime
capability output, and structured diagnostics. Distribution must not collapse
first-party plugins into hidden in-process implementation details.

## Primary Decisions

- Distribution kind: repo-local Linux archive.
- Initial target: `x86_64-unknown-linux-gnu`.
- Runtime goal: fully functional project, layout, render, and export paths from
  an unpacked archive.
- Java runtime: required externally on `PATH`; do not bundle a JRE.
- Rust, SDKMAN, and Gradle: build-time prerequisites only.
- Archive install model: unpack the tarball and run `bin/dediren`.
- Release automation, macOS, Windows, signing, checksums, and package-manager
  installs are deferred.

## Archive Shape

The distribution workflow creates a clean bundle directory and a versioned
archive under `dist/`:

```text
dist/
  dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/
    bin/
      dediren
      dediren-plugin-generic-graph
      dediren-plugin-elk-layout
      dediren-plugin-svg-render
      dediren-plugin-archimate-oef-export
    plugins/
      generic-graph.manifest.json
      elk-layout.manifest.json
      svg-render.manifest.json
      archimate-oef.manifest.json
    schemas/
    fixtures/
    runtimes/
      elk-layout-java/
        bin/
        lib/
    bundle.json
  dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu.tar.gz
```

`bundle.json` records the product version, target triple, build timestamp,
included first-party plugin ids and versions, schema ids present in the bundle,
and the ELK helper distribution path. It is metadata for inspection and smoke
tests, not a replacement for plugin runtime capability output.

## Build Workflow

The first implementation should add a repo-local script such as
`scripts/build-dist.sh`.

The script should:

1. read the workspace package version;
2. build release Rust binaries for the current Linux host;
3. build the ELK Java helper using the existing Java build script;
4. create a clean bundle directory under `dist/`;
5. copy release binaries into `bin/`;
6. copy first-party plugin manifests into `plugins/`;
7. copy public schemas and useful fixtures into `schemas/` and `fixtures/`;
8. copy the installed ELK helper distribution into
   `runtimes/elk-layout-java/`;
9. write `bundle.json`;
10. create the `.tar.gz` archive.

The script should fail loudly when a build step, copy step, version check, or
archive creation step fails.

## Runtime Lookup

The unpacked archive must not require source-checkout paths.

`dediren` should derive an installed bundle root from its executable path. When
running from an archive layout, it should load bundled first-party plugin
manifests from `<bundle-root>/plugins`. Project plugin directories and
user-configured plugin directories remain supported as explicit later lookup
sources.

Manifest executables that are binary names should resolve from
`<bundle-root>/bin` in installed-bundle mode. Explicit environment overrides,
such as `DEDIREN_PLUGIN_SVG_RENDER`, still take precedence.

The source-checkout fixture manifest behavior remains useful for development
and tests, but installed-bundle lookup should be the runtime path proven by the
distribution smoke tests.

## ELK Runtime

The archive includes the built ELK Java helper distribution under
`runtimes/elk-layout-java/`. It does not include Java itself.

The `elk-layout` plugin lookup order should be:

1. `DEDIREN_ELK_RESULT_FIXTURE` for deterministic tests;
2. explicit `DEDIREN_ELK_COMMAND`;
3. bundled helper under
   `<bundle-root>/runtimes/elk-layout-java/bin/dediren-elk-layout-java`.

If the bundled helper is missing, the plugin should return a structured
missing-runtime diagnostic. If the helper exists but Java is missing or
incompatible, the plugin should return a structured Java-runtime diagnostic.
If the helper starts and returns a valid error envelope, the CLI should preserve
that envelope and exit non-zero.

## Versioning Guidance

Use pragmatic pre-1.0 versioning for this slice:

- The bundle version is the Cargo workspace package version.
- First-party Rust crates stay on the shared workspace version.
- First-party plugin manifest versions must match the workspace version.
- The distribution script fails if bundled manifest versions drift from the
  workspace version.
- Public schema ids remain contract-family versions such as `model.schema.v1`
  or `layout-result.schema.v1`.
- Schema ids change only when the contract family intentionally changes; they
  are not tied to every `0.x.y` product release.
- Runtime capability output remains the source of truth for which schema
  versions a plugin actually supports.
- Archive names use `<product>-<version>-<target>.tar.gz`.
- ELK helper third-party dependency versions stay pinned by Gradle locking and
  are build inputs for the `elk-layout` plugin, not product version numbers.

Strict SemVer compatibility promises, plugin marketplace version negotiation,
signing, provenance, and cross-version support are deferred until after the
first Linux archive works.

## README Requirements

`README.md` should document the manual Linux distribution workflow when the
script is implemented.

The README should distinguish:

- build prerequisites: Rust, SDKMAN, Gradle, and the Java build environment
  needed by the existing ELK helper build;
- runtime prerequisites: Java available on `PATH`;
- archive creation command;
- unpack command;
- `bin/dediren --help` smoke test;
- a full project, layout, and render smoke test from the unpacked archive.

The README should keep the existing local `cargo install` development path if
it remains useful, but it should not imply that `cargo install` alone produces
the agent-ready distribution archive.

## Error Handling

Distribution and runtime failures should stay structured and inspectable:

- Missing bundled plugin binary produces a plugin executable diagnostic.
- Missing bundled manifest produces a plugin discovery or configuration
  diagnostic.
- Missing Java runtime produces an ELK missing-runtime diagnostic.
- ELK helper failures preserve valid helper envelopes where possible.
- Dist assembly failures stop the build script at the failed step.

Agents should be able to decide runtime success or failure from stdout JSON for
CLI/plugin execution. Shell script build failures may use stderr because they
are human-operated build-time failures, not runtime command envelopes.

## Verification

The implementation plan should include focused checks for the distribution
lane:

- The dist script creates the expected directory layout.
- The dist script creates the versioned `.tar.gz`.
- The archive can be unpacked into a temporary directory.
- `bin/dediren --help` runs from the unpacked archive.
- The unpacked CLI discovers bundled plugins without source checkout fixtures.
- A full smoke test runs `project -> layout -> render` using the bundled ELK
  helper and external Java.
- A version guard fails when first-party plugin manifest versions drift from
  the workspace version.
- README commands match the implemented script and output names.

Because this work changes runtime packaging and public workflow documentation,
the implementation should also run the relevant Rust plugin runtime and CLI
compatibility tests, plus a quick DevSecOps posture review for process
boundaries and bundled artifacts.

## Deferred Scope

This design deliberately defers:

- GitHub release automation;
- macOS archives;
- Windows archives;
- bundled JRE;
- checksums, signing, and provenance;
- Homebrew or package-manager installs;
- third-party plugin publishing;
- third-party plugin signing;
- plugin marketplace version negotiation;
- strict compatibility matrices;
- cross-version support promises.

## Validation Layers For This Design

- Static: current repository inspection shows a Rust workspace, first-party
  executable plugins, explicit plugin manifests, checked-in schemas and
  fixtures, and a Gradle-built ELK Java helper.
- Human: decisions in this document come from the distribution brainstorming
  conversation on 2026-05-11.
- Memory-derived: prior `dediren` work informed the need to keep plugin
  process boundaries, JSON command envelopes, and README-visible workflow
  changes explicit.

## Limits

This design is not an implementation plan. It defines the first Linux
distribution artifact, runtime lookup expectations, versioning guidance, and
validation boundaries. The next step is a separate implementation plan after
review approval.
