# Dediren GitHub Release CI Design

Date: 2026-05-26

## Purpose

`dediren` currently produces an agent-ready distribution archive through a
local `cargo xtask dist build` workflow. The next release slice moves that
distribution path to GitHub CI/CD so tagged releases produce downloadable
archives without relying on a developer workstation.

The first CI release targets are:

- `x86_64-unknown-linux-gnu`
- `aarch64-unknown-linux-gnu`
- `aarch64-apple-darwin`

The archive remains the product artifact. GitHub Actions is orchestration, not
a new runtime contract.

## Primary Decisions

- Release trigger: pushing a `v*` tag is the canonical publish path.
- Rehearsal trigger: `workflow_dispatch` runs the build and smoke matrix but
  does not publish release assets unless explicitly extended later.
- Build strategy: native GitHub-hosted runner matrix, not Linux
  cross-compilation.
- Initial runners:
  - `ubuntu-24.04` for `x86_64-unknown-linux-gnu`
  - `ubuntu-24.04-arm` for `aarch64-unknown-linux-gnu`
  - `macos-15` for `aarch64-apple-darwin`
- Runtime Java stays external. The release archives do not bundle a JRE.
- macOS signing, notarization, Homebrew, Windows, provenance attestations, and
  package-manager publishing are deferred.
- Build caching is required for the first workflow, but cached content is only
  acceleration. Fresh archive assembly and archive smoke tests remain
  mandatory on every release job.

## Architecture

The release lane has two ownership boundaries:

1. `xtask` owns Dediren distribution semantics.
2. GitHub Actions owns CI orchestration and release publication.

`xtask` should become target-aware instead of assuming a single Linux x86_64
host. It should expose a small supported-target table that describes the target
triple, expected host OS and architecture, archive naming, executable suffixes,
and platform-specific prerequisites. The supported table is also the source for
release-surface tests and README examples.

The GitHub workflow should stay thin. It checks out source, provisions Rust,
Java, Gradle, and platform tools, restores caches, calls `cargo xtask dist
build --target <triple>`, calls `cargo xtask dist smoke <archive>`, uploads the
archive as a workflow artifact, and publishes all expected archives to the
tagged GitHub Release.

## Target-Aware Distribution Build

`cargo xtask dist build` should accept an explicit target argument:

```bash
cargo xtask dist build --target x86_64-unknown-linux-gnu
cargo xtask dist build --target aarch64-unknown-linux-gnu
cargo xtask dist build --target aarch64-apple-darwin
```

For compatibility, omitting `--target` may keep using the current local default
target when that host is supported. Unsupported targets or host/target
mismatches should fail early with an actionable message. The first slice should
not introduce cross-compilation, because the real archive must include native
Rust executables and pass a runtime smoke test on the target OS/architecture.

Each archive keeps the current bundle shape:

```text
dediren-agent-bundle-<version>-<target>/
  bin/
  plugins/
  schemas/
  fixtures/
  docs/
  runtimes/elk-layout-java/
  LICENSE
  bundle.json
```

`bundle.json` should continue reporting the product version and target triple.
The schema does not need a family-version change for adding new target values,
because `target` is already a string and the archive shape is compatible.

## Java Helper Build

The ELK Java helper build should work in both local and CI contexts.

Local builds may continue to use SDKMAN and `.sdkmanrc`. CI builds should be
able to use a GitHub-provisioned Java 25 toolchain and Gradle 9.5.0 without
installing SDKMAN. The helper still emits Java 21-compatible bytecode and the
runtime archive still requires Java 21 or newer on `PATH`.

The helper build script should fail if the actual Java or Gradle version is
outside the supported range. It should keep repo-local Gradle caches under
`.cache/gradle/` for local builds, while the GitHub workflow may override
`GRADLE_USER_HOME` or use the Gradle action cache path when appropriate.

## GitHub Workflow

Add `.github/workflows/release.yml` with these triggers:

```yaml
on:
  push:
    tags:
      - "v*"
  workflow_dispatch:
```

Recommended jobs:

- `build`: matrix over the three supported targets. Uses read-only repository
  permissions. Produces and smokes one archive per target, then uploads the
  `.tar.gz` and any metadata needed by the publish job.
- `publish`: runs only for tag pushes. Uses `contents: write`, downloads all
  matrix artifacts, verifies the expected archive set, writes checksum files,
  creates or updates the GitHub Release for the tag, and uploads the release
  assets.

The workflow should use current maintained action majors at implementation
time. As of this design, official docs and action READMEs show:

- `actions/checkout@v6`
- `actions/setup-java@v5`
- `gradle/actions/setup-gradle@v6`
- `actions/cache@v5`
- `actions/upload-artifact@v7`

Implementation should recheck these versions before committing workflow YAML if
the work happens later.

## Caching

Caching is required to keep release builds practical, but cache hits must never
replace verification.

Use separate cache lanes:

- Cargo registry and git dependency cache.
- Cargo target output cache scoped by runner OS, runner architecture, Rust
  version, target triple, `Cargo.lock`, and relevant source or build-script
  hashes.
- Gradle distribution, dependency, and local build cache scoped by runner OS,
  runner architecture, Java version, Gradle version, `.sdkmanrc`,
  `build.gradle.kts`, `settings.gradle.kts`, and `gradle.lockfile`.

Prefer Gradle's GitHub action with the basic cache provider unless the
implementation deliberately accepts the enhanced provider's licensing and data
flow. For Cargo, use an explicit `actions/cache` setup or a maintained Rust
cache action only after checking its current support for Linux arm64 and macOS
arm64 runners.

Caches must not include generated release archives as reusable inputs. Every
matrix job must assemble a fresh `dist/dediren-agent-bundle-*` tree and run the
archive smoke test against that freshly assembled tarball.

## Release Publication

The publish job should treat the tag as the release identity. Before upload it
must verify:

- the tag name starts with `v`;
- the tag version matches `Cargo.toml` workspace package version after removing
  the leading `v`;
- all expected archive names are present exactly once;
- each archive contains a `bundle.json` with matching `version` and `target`;
- each archive contains `LICENSE` and `docs/agent-usage.md`;
- checksums were generated from the exact files being uploaded.

Release upload may use GitHub CLI:

```bash
gh release create "$GITHUB_REF_NAME" --verify-tag --title "$GITHUB_REF_NAME" --notes-file release-notes.md
gh release upload "$GITHUB_REF_NAME" dist/*.tar.gz dist/SHA256SUMS
```

If the release already exists, the implementation may use an update path, but
it should avoid `--clobber` by default. Replacing an existing release asset
should be an explicit rerun policy, not an accidental side effect.

## Documentation Requirements

`README.md` should move from Linux-x86_64-only local distribution wording to a
release-first install section:

- GitHub Releases provide the current agent-ready archives.
- Supported first targets are Linux x86_64, Linux arm64, and macOS arm64.
- Local `cargo xtask dist build --target <triple>` remains available for
  maintainers.
- Runtime prerequisites remain Java 21+, `xmllint` for standards validation,
  and `curl` only when schema caches need network population.
- Release archive examples should include all three target archive names.

`docs/agent-usage.md` should stay focused on bundle-local use. It only needs
updates if examples or bundle paths become target-specific.

Because this implementation changes shipped release behavior, public artifact
locations, and distribution artifact contents, it requires a patch version bump
when the workflow and target-aware build changes land. A spec-only commit does
not require a product version bump.

## Testing And Verification

The implementation plan should include focused tests before behavior changes:

- `xtask` rejects unsupported targets.
- `xtask` maps supported targets to the expected archive names.
- `xtask` fails early on host/target mismatches instead of silently
  cross-compiling.
- bundle metadata validates for all three target triples.
- release-surface tests require README examples for all supported targets.
- the workflow file has the expected matrix targets and release permissions.

CI verification should include:

```bash
cargo fmt --all -- --check
git diff --check
cargo test -p dediren-contracts --test schema_contracts
cargo test -p xtask --test dist
cargo xtask dist build --target <target>
cargo xtask dist smoke dist/dediren-agent-bundle-<version>-<target>.tar.gz
```

The tag workflow itself is the authoritative proof for the three native
archives. Local verification can cover the current host target only unless the
developer has matching hardware or runners.

## Error Handling

Release failures should stop before publishing if any target build or smoke
test fails. The publish job should never publish a partial target set.

Common failure classes should have clear messages:

- unsupported target;
- target requested on the wrong runner architecture;
- Java or Gradle unavailable or wrong version;
- missing platform tool such as `xmllint`;
- missing expected archive;
- `bundle.json` version or target mismatch;
- release tag version mismatch.

Runtime command failures inside the archive continue to use Dediren command
envelopes. CI setup and publish failures may use normal GitHub Actions stderr.

## Deferred Scope

This design deliberately defers:

- Windows archives;
- Linux musl/static archives;
- x86_64 macOS archives;
- bundled JREs;
- code signing and notarization;
- Homebrew, npm, package-manager, or marketplace publishing;
- SBOMs and provenance attestations;
- automatic changelog generation beyond minimal release notes;
- cross-compilation.

## Validation Layers For This Design

- Static: current repository inspection shows `xtask` owns local distribution
  build and smoke behavior, `README.md` documents the current Linux x86_64
  archive, and `.github/workflows/` does not yet exist.
- Upstream docs: GitHub-hosted runner docs list `ubuntu-24.04-arm` and arm64
  macOS runner labels; workflow syntax docs support tag filters; workflow
  permission docs require `contents: write` for release creation; current
  action READMEs document maintained major versions and cache behavior.
- Human: release trigger, native runner matrix, first target set, and caching
  requirement were approved in the design discussion.

## Source References

- GitHub-hosted runners reference:
  https://docs.github.com/actions/reference/runners/github-hosted-runners
- GitHub Actions workflow syntax:
  https://docs.github.com/actions/reference/workflows-and-actions/workflow-syntax
- GitHub CLI release upload:
  https://cli.github.com/manual/gh_release_upload
- `actions/checkout` repository checkout:
  https://github.com/actions/checkout
- `actions/setup-java` cache support:
  https://github.com/actions/setup-java
- `gradle/actions/setup-gradle` cache and Gradle-version support:
  https://github.com/gradle/actions/blob/main/docs/setup-gradle.md
- `actions/cache` dependency and build-output cache behavior:
  https://github.com/actions/cache
- `actions/upload-artifact` artifact behavior:
  https://github.com/actions/upload-artifact
