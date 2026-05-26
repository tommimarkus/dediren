# GitHub Release CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a tag-driven GitHub release workflow that publishes native Dediren agent bundles for Linux x86_64, Linux arm64, and macOS arm64.

**Architecture:** Keep Dediren distribution behavior in `xtask`; make `xtask dist build` target-aware and keep `xtask dist smoke` as the archive runtime gate. GitHub Actions should only provision tools, restore caches, run the target-aware dist commands, upload artifacts, and publish all expected archives to a `v*` tag release.

**Tech Stack:** Rust Cargo workspace, `xtask`, Bash, Gradle Java helper, GitHub Actions, GitHub CLI, `tar`, `jq`, `xmllint`, `actions/cache`, `gradle/actions/setup-gradle`.

---

## Scope And Commit Strategy

This plan implements the approved spec at `docs/superpowers/specs/2026-05-26-dediren-github-release-ci-design.md`.

The implementation changes shipped release behavior and public artifact locations, so it requires a patch bump from `0.14.9` to `0.14.10`. Because `AGENTS.md` requires version bumps in the same commit as the content change that requires them, do not create a commit that changes release behavior without the matching versioned surfaces. Use red/green checkpoints while working, then commit the completed feature and synchronized version surfaces together.

Audit gates for this plan:

- `souroldgeezer-audit:devsecops-audit` quick review for `.github/workflows/release.yml`, the helper process boundary, cache scope, release permissions, and artifact publication.
- `souroldgeezer-audit:test-quality-audit` quick review for the changed `xtask` and release-surface tests.

## File Structure

- Modify: `xtask/src/main.rs`
  - Add supported distribution target metadata.
  - Add `cargo xtask dist build --target <triple>`.
  - Keep `DEDIREN_DIST_TARGET` as a compatibility fallback.
  - Validate host OS and architecture before building.
  - Use portable archive creation flags that work on Linux and macOS.
- Modify: `xtask/tests/dist.rs`
  - Add target selection and host-mismatch coverage.
  - Update fake repo release binary paths to support the current host target.
  - Keep existing bundle content, lock, prune, and smoke coverage.
- Modify: `crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh`
  - Replace hard `flock` dependency with a portable directory lock.
  - Support CI builds without SDKMAN when Java and Gradle are already on `PATH`.
  - Preserve SDKMAN as the local default.
- Create: `.github/workflows/release.yml`
  - Add validation, native matrix build, smoke, artifact upload, checksum, and release publish jobs.
  - Scope release write permission to the publish job only.
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`
  - Validate all supported bundle target examples and workflow release policy.
  - Keep Cargo.lock/package version and release-surface guards.
- Modify: `README.md`
  - Document GitHub Release archives, supported targets, local target-aware build commands, runtime prerequisites, and smoke commands.
- Modify: `docs/agent-usage.md`
  - Make bundle examples target-aware while preserving deterministic version checks.
- Modify: `Cargo.toml`, `Cargo.lock`, `fixtures/plugins/*.manifest.json`, and `fixtures/source/*.json`
  - Bump synchronized release surfaces from `0.14.9` to `0.14.10`.

---

### Task 1: Add Failing Target-Aware Dist Tests

**Files:**
- Modify: `xtask/tests/dist.rs`

- [ ] **Step 1: Add target helper constants and functions to the test file**

Add these constants and helpers after the imports in `xtask/tests/dist.rs`:

```rust
#[cfg(unix)]
const SUPPORTED_TEST_TARGETS: &[&str] = &[
    "x86_64-unknown-linux-gnu",
    "aarch64-unknown-linux-gnu",
    "aarch64-apple-darwin",
];

#[cfg(unix)]
fn host_dist_target() -> &'static str {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("linux", "x86_64") => "x86_64-unknown-linux-gnu",
        ("linux", "aarch64") => "aarch64-unknown-linux-gnu",
        ("macos", "aarch64") => "aarch64-apple-darwin",
        _ => "unsupported-host",
    }
}

#[cfg(unix)]
fn other_supported_target_for_host() -> Option<&'static str> {
    SUPPORTED_TEST_TARGETS
        .iter()
        .copied()
        .find(|target| *target != host_dist_target())
}

#[cfg(unix)]
fn current_bundle_name() -> String {
    format!(
        "dediren-agent-bundle-{}-{}",
        env!("CARGO_PKG_VERSION"),
        host_dist_target()
    )
}
```

- [ ] **Step 2: Add failing tests for explicit targets and host mismatch**

Add these tests after `dist_build_serializes_parallel_invocations`:

```rust
#[cfg(unix)]
#[test]
fn dist_build_accepts_explicit_host_target() {
    if host_dist_target() == "unsupported-host" {
        return;
    }

    let repo = FakeDistRepo::new();
    repo.release_helper_build();
    let output = repo
        .xtask_command(["dist", "build", "--target", host_dist_target()])
        .output()
        .unwrap();

    assert!(
        output.status.success(),
        "dist build should pass for explicit host target\nstatus: {:?}\nstdout:\n{}\nstderr:\n{}",
        output.status.code(),
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(
        repo.root
            .path()
            .join("dist")
            .join(format!("{}.tar.gz", current_bundle_name()))
            .exists(),
        "dist build should create the explicit host-target archive"
    );
}

#[cfg(unix)]
#[test]
fn dist_build_rejects_unsupported_target() {
    let repo = FakeDistRepo::new();
    let output = repo
        .xtask_command(["dist", "build", "--target", "riscv64gc-unknown-linux-gnu"])
        .output()
        .unwrap();

    assert!(
        !output.status.success(),
        "unsupported target should fail"
    );
    assert!(
        String::from_utf8_lossy(&output.stderr).contains("unsupported distribution target"),
        "stderr should explain unsupported target\nstderr:\n{}",
        String::from_utf8_lossy(&output.stderr),
    );
}

#[cfg(unix)]
#[test]
fn dist_build_rejects_supported_target_for_wrong_host() {
    let Some(target) = other_supported_target_for_host() else {
        return;
    };
    let repo = FakeDistRepo::new();
    let output = repo
        .xtask_command(["dist", "build", "--target", target])
        .output()
        .unwrap();

    assert!(
        !output.status.success(),
        "wrong-host target should fail"
    );
    assert!(
        String::from_utf8_lossy(&output.stderr).contains("must be built on"),
        "stderr should explain host/target mismatch\nstderr:\n{}",
        String::from_utf8_lossy(&output.stderr),
    );
}
```

- [ ] **Step 3: Update existing test path expectations to use the current host target**

Replace hard-coded current-version bundle names in existing tests with `current_bundle_name()`.

For example, replace:

```rust
let bundle = repo.root.path().join(format!(
    "dist/dediren-agent-bundle-{}-x86_64-unknown-linux-gnu",
    env!("CARGO_PKG_VERSION")
));
```

with:

```rust
let bundle = repo.root.path().join("dist").join(current_bundle_name());
```

Replace:

```rust
let bundle_name = format!(
    "dediren-agent-bundle-{}-x86_64-unknown-linux-gnu",
    env!("CARGO_PKG_VERSION")
);
```

with:

```rust
let bundle_name = current_bundle_name();
```

- [ ] **Step 4: Update the fake release binary tree to support all initial targets**

In `FakeDistRepo::write_tree`, replace the single target release directory creation:

```rust
fs::create_dir_all(
    self.root
        .path()
        .join("target/x86_64-unknown-linux-gnu/release"),
)
.unwrap();
```

with:

```rust
for target in SUPPORTED_TEST_TARGETS {
    fs::create_dir_all(self.root.path().join("target").join(target).join("release")).unwrap();
}
```

Then replace the binary fixture loop that writes only to `target/x86_64-unknown-linux-gnu/release` with:

```rust
for target in SUPPORTED_TEST_TARGETS {
    for binary in [
        "dediren",
        "dediren-plugin-generic-graph",
        "dediren-plugin-elk-layout",
        "dediren-plugin-svg-render",
        "dediren-plugin-archimate-oef-export",
        "dediren-plugin-uml-xmi-export",
    ] {
        self.write_executable_at(
            &self
                .root
                .path()
                .join("target")
                .join(target)
                .join("release")
                .join(binary),
            "#!/usr/bin/env bash\n",
        );
    }
}
```

- [ ] **Step 5: Run the focused tests and verify the expected failure**

Run:

```bash
cargo test -p xtask --test dist dist_build_accepts_explicit_host_target --locked
cargo test -p xtask --test dist dist_build_rejects_unsupported_target --locked
cargo test -p xtask --test dist dist_build_rejects_supported_target_for_wrong_host --locked
```

Expected: at least the first command fails because `cargo xtask dist build --target` is not supported yet. The unsupported-target test may fail with a clap parsing error before the production behavior exists.

---

### Task 2: Implement Target-Aware `xtask dist build`

**Files:**
- Modify: `xtask/src/main.rs`
- Modify: `xtask/tests/dist.rs`

- [ ] **Step 1: Replace the single target constant with supported target metadata**

In `xtask/src/main.rs`, replace:

```rust
const DIST_TARGET: &str = "x86_64-unknown-linux-gnu";
```

with:

```rust
const DEFAULT_DIST_TARGET: &str = "x86_64-unknown-linux-gnu";

#[derive(Debug, Clone, Copy)]
struct DistTarget {
    triple: &'static str,
    host_os: &'static str,
    host_arch: &'static str,
}

const DIST_TARGETS: &[DistTarget] = &[
    DistTarget {
        triple: "x86_64-unknown-linux-gnu",
        host_os: "linux",
        host_arch: "x86_64",
    },
    DistTarget {
        triple: "aarch64-unknown-linux-gnu",
        host_os: "linux",
        host_arch: "aarch64",
    },
    DistTarget {
        triple: "aarch64-apple-darwin",
        host_os: "macos",
        host_arch: "aarch64",
    },
];
```

- [ ] **Step 2: Add the `--target` option to the dist build command**

Replace:

```rust
enum DistCommand {
    Build,
    Smoke { archive: Option<PathBuf> },
}
```

with:

```rust
enum DistCommand {
    Build {
        #[arg(long, value_name = "TRIPLE")]
        target: Option<String>,
    },
    Smoke { archive: Option<PathBuf> },
}
```

Update the match arm:

```rust
DistCommand::Build => build_dist(&root),
```

to:

```rust
DistCommand::Build { target } => build_dist(&root, target.as_deref()),
```

Update the smoke usage target in the same match arm from `DIST_TARGET` to `DEFAULT_DIST_TARGET`.

- [ ] **Step 3: Resolve and validate the requested target**

Change the `build_dist` signature:

```rust
fn build_dist(root: &Path) -> Result<()> {
```

to:

```rust
fn build_dist(root: &Path, requested_target: Option<&str>) -> Result<()> {
```

Replace the target selection and Linux-only guard at the top of `build_dist` with:

```rust
let env_target = std::env::var("DEDIREN_DIST_TARGET").ok();
let requested_target = requested_target.or(env_target.as_deref());
let target = resolve_dist_target(requested_target)?;
ensure_host_can_build(target)?;
```

Then replace every `&target` argument that expects a string with `target.triple`.

For example, replace:

```rust
let bin_dir = cargo_target_dir.join(&target).join("release");
let bundle_name = bundle_name(workspace_version(), &target);
```

with:

```rust
let bin_dir = cargo_target_dir.join(target.triple).join("release");
let bundle_name = bundle_name(workspace_version(), target.triple);
```

Replace:

```rust
.arg(&target);
```

with:

```rust
.arg(target.triple);
```

Replace:

```rust
write_bundle_metadata(&bundle_dir, &target)?;
```

with:

```rust
write_bundle_metadata(&bundle_dir, target.triple)?;
```

- [ ] **Step 4: Add target helper functions**

Add these helpers below `bundle_name`:

```rust
fn resolve_dist_target(requested: Option<&str>) -> Result<&'static DistTarget> {
    let requested = requested.unwrap_or(DEFAULT_DIST_TARGET);
    DIST_TARGETS
        .iter()
        .find(|target| target.triple == requested)
        .ok_or_else(|| {
            let supported = DIST_TARGETS
                .iter()
                .map(|target| target.triple)
                .collect::<Vec<_>>()
                .join(", ");
            anyhow!("unsupported distribution target: {requested}; supported targets: {supported}")
        })
}

fn ensure_host_can_build(target: &DistTarget) -> Result<()> {
    let host_os = std::env::consts::OS;
    let host_arch = std::env::consts::ARCH;
    if host_os == target.host_os && host_arch == target.host_arch {
        return Ok(());
    }

    bail!(
        "{} must be built on {} {}; current host is {} {}",
        target.triple,
        target.host_os,
        target.host_arch,
        host_os,
        host_arch
    )
}

fn release_binary_path(bin_dir: &Path, binary: &str, _target: &DistTarget) -> PathBuf {
    bin_dir.join(binary)
}
```

Then update the binary install loop:

```rust
for binary in PLUGIN_BINARIES {
    install_executable(
        &bin_dir.join(binary),
        &bundle_dir.join("bin").join(binary),
        binary,
    )?;
}
```

to:

```rust
for binary in PLUGIN_BINARIES {
    install_executable(
        &release_binary_path(&bin_dir, binary, target),
        &bundle_dir.join("bin").join(binary),
        binary,
    )?;
}
```

- [ ] **Step 5: Make archive creation portable across Linux and macOS**

Replace the `tar` invocation:

```rust
Command::new("tar")
    .arg("--owner=0")
    .arg("--group=0")
    .arg("--numeric-owner")
    .arg("-C")
    .arg(&dist_dir)
    .arg("-czf")
    .arg(&archive)
    .arg(&bundle_name),
```

with:

```rust
Command::new("tar")
    .arg("-C")
    .arg(&dist_dir)
    .arg("-czf")
    .arg(&archive)
    .arg(&bundle_name),
```

- [ ] **Step 6: Run the target-aware tests again**

Run:

```bash
cargo test -p xtask --test dist dist_build_accepts_explicit_host_target --locked
cargo test -p xtask --test dist dist_build_rejects_unsupported_target --locked
cargo test -p xtask --test dist dist_build_rejects_supported_target_for_wrong_host --locked
```

Expected: all three tests pass on supported Linux x86_64, Linux arm64, or macOS arm64 hosts. On any unsupported host, the explicit-host test exits early and the two rejection tests still pass.

---

### Task 3: Make The ELK Helper Build Script CI-Friendly

**Files:**
- Modify: `crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh`

- [ ] **Step 1: Replace the helper script with portable locking and optional SDKMAN**

Replace the full content of `crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh` with:

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
PROJECT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd -P)
REPO_ROOT=$(cd -- "$PROJECT_DIR/../../.." && pwd -P)
LOCK_DIR="$REPO_ROOT/.cache/locks"
LOCK_PATH="$LOCK_DIR/elk-layout-java-build.lock.d"

mkdir -p "$LOCK_DIR"
while ! mkdir "$LOCK_PATH" 2>/dev/null; do
  echo "another ELK Java helper build is running; waiting for $LOCK_PATH" >&2
  sleep 1
done
trap 'rmdir "$LOCK_PATH"' EXIT

cd "$PROJECT_DIR"

SDKMAN_MODE="${DEDIREN_ELK_BUILD_USE_SDKMAN:-auto}"
if [[ "$SDKMAN_MODE" != "0" ]]; then
  if [[ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]]; then
    set +u
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    sdk env
    set -u
  elif [[ "$SDKMAN_MODE" == "1" ]]; then
    echo "SDKMAN is required: install SDKMAN, then run sdk env install from $PROJECT_DIR" >&2
    exit 2
  else
    echo "SDKMAN not found; using java and gradle from PATH" >&2
  fi
fi

if ! command -v java >/dev/null 2>&1; then
  echo "java is required on PATH to build the ELK Java helper" >&2
  exit 2
fi
if ! command -v gradle >/dev/null 2>&1; then
  echo "gradle is required on PATH to build the ELK Java helper" >&2
  exit 2
fi

JAVA_MAJOR=$(
  java -version 2>&1 |
    awk -F '"' '/version/ { split($2, parts, "."); if (parts[1] == "1") print parts[2]; else print parts[1]; exit }'
)
if [[ "$JAVA_MAJOR" != "25" ]]; then
  echo "Java 25 is required to build the ELK Java helper; found Java ${JAVA_MAJOR:-unknown}" >&2
  exit 2
fi

GRADLE_VERSION=$(gradle --version | awk '/^Gradle / { print $2; exit }')
if [[ "$GRADLE_VERSION" != "9.5.0" ]]; then
  echo "Gradle 9.5.0 is required to build the ELK Java helper; found Gradle ${GRADLE_VERSION:-unknown}" >&2
  exit 2
fi

if [[ -z "${GRADLE_USER_HOME:-}" ]]; then
  export GRADLE_USER_HOME="$REPO_ROOT/.cache/gradle/user-home"
fi
PROJECT_CACHE_DIR="${DEDIREN_ELK_GRADLE_PROJECT_CACHE_DIR:-$REPO_ROOT/.cache/gradle/project-cache/elk-layout-java}"
mkdir -p "$GRADLE_USER_HOME" "$PROJECT_CACHE_DIR"

gradle \
  --project-cache-dir "$PROJECT_CACHE_DIR" \
  -p "$PROJECT_DIR" \
  clean test installDist
```

- [ ] **Step 2: Run a syntax check**

Run:

```bash
bash -n crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: no output and exit code 0.

- [ ] **Step 3: Run a CI-mode smoke check with stub tools**

Run:

```bash
TMP=$(mktemp -d)
mkdir -p "$TMP/bin"
cat >"$TMP/bin/java" <<'EOF'
#!/usr/bin/env bash
echo 'openjdk version "25.0.3"'
EOF
cat >"$TMP/bin/gradle" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "--version" ]]; then
  echo 'Gradle 9.5.0'
  exit 0
fi
mkdir -p build/install/dediren-elk-layout-java/bin
printf '#!/usr/bin/env bash\n' > build/install/dediren-elk-layout-java/bin/dediren-elk-layout-java
EOF
chmod +x "$TMP/bin/java" "$TMP/bin/gradle"
PATH="$TMP/bin:$PATH" \
  DEDIREN_ELK_BUILD_USE_SDKMAN=0 \
  GRADLE_USER_HOME="$TMP/gradle-user-home" \
  DEDIREN_ELK_GRADLE_PROJECT_CACHE_DIR="$TMP/project-cache" \
  crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

Expected: script exits 0 and creates `crates/dediren-plugin-elk-layout/java/build/install/dediren-elk-layout-java/bin/dediren-elk-layout-java`. Do not stage the generated Java build directory.

---

### Task 4: Add Workflow Release-Surface Tests

**Files:**
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`

- [ ] **Step 1: Add supported release targets to schema contract tests**

Near the existing constants at the top of `crates/dediren-contracts/tests/schema_contracts.rs`, add:

```rust
const RELEASE_TARGETS: &[&str] = &[
    "x86_64-unknown-linux-gnu",
    "aarch64-unknown-linux-gnu",
    "aarch64-apple-darwin",
];
```

- [ ] **Step 2: Update release-surface README checks to require all archive names**

In `live_release_surfaces_match_workspace_version`, replace the single target setup:

```rust
let target = "x86_64-unknown-linux-gnu";
let bundle_name = format!("dediren-agent-bundle-{version}-{target}");
let archive_name = format!("{bundle_name}.tar.gz");
```

and the two single-name assertions with:

```rust
for target in RELEASE_TARGETS {
    let bundle_name = format!("dediren-agent-bundle-{version}-{target}");
    let archive_name = format!("{bundle_name}.tar.gz");
    assert!(
        readme.contains(&bundle_name),
        "README.md should mention {bundle_name}"
    );
    assert!(
        readme.contains(&archive_name),
        "README.md should mention {archive_name}"
    );
}
```

- [ ] **Step 3: Add workflow policy coverage**

Add this test after `live_release_surfaces_match_workspace_version`:

```rust
#[test]
fn release_workflow_matches_supported_targets_and_permissions() {
    let workflow = std::fs::read_to_string(workspace_file(".github/workflows/release.yml"))
        .expect("release workflow should exist");

    assert!(
        workflow.contains("tags:") && workflow.contains("\"v*\""),
        "release workflow should run for v* tags"
    );
    assert!(
        workflow.contains("workflow_dispatch:"),
        "release workflow should support manual rehearsal runs"
    );
    assert!(
        workflow.contains("contents: read"),
        "release workflow should default to read-only content permission"
    );
    assert!(
        workflow.contains("contents: write"),
        "release workflow publish job should request contents: write"
    );
    assert!(
        workflow.contains("cache-provider: basic"),
        "release workflow should use the basic Gradle cache provider"
    );
    assert!(
        workflow.contains("actions/cache@v5"),
        "release workflow should cache Cargo dependencies and build outputs"
    );
    assert!(
        !workflow.contains("--clobber"),
        "release workflow should not overwrite release assets by default"
    );

    for target in RELEASE_TARGETS {
        assert!(
            workflow.contains(target),
            "release workflow should include target {target}"
        );
    }
}
```

- [ ] **Step 4: Update bundle metadata schema test to cover all targets**

Replace `bundle_metadata_matches_schema` with:

```rust
#[test]
fn bundle_metadata_matches_schema() {
    for target in RELEASE_TARGETS {
        assert_json_valid(
            "schemas/bundle.schema.json",
            json!({
                "bundle_schema_version": "dediren-bundle.schema.v1",
                "product": "dediren",
                "version": env!("CARGO_PKG_VERSION"),
                "target": target,
                "built_at_utc": "2026-05-13T00:00:00Z",
                "plugins": [
                    { "id": "generic-graph", "version": env!("CARGO_PKG_VERSION") },
                    { "id": "elk-layout", "version": env!("CARGO_PKG_VERSION") },
                    { "id": "svg-render", "version": env!("CARGO_PKG_VERSION") },
                    { "id": "archimate-oef", "version": env!("CARGO_PKG_VERSION") },
                    { "id": "uml-xmi", "version": env!("CARGO_PKG_VERSION") }
                ],
                "schemas_dir": "schemas",
                "fixtures_dir": "fixtures",
                "docs_dir": "docs",
                "elk_helper": "runtimes/elk-layout-java/bin/dediren-elk-layout-java"
            }),
        );
    }
}
```

- [ ] **Step 5: Run the focused schema tests and verify the expected failure**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts release_workflow_matches_supported_targets_and_permissions --locked
cargo test -p dediren-contracts --test schema_contracts live_release_surfaces_match_workspace_version --locked
```

Expected: the workflow test fails because `.github/workflows/release.yml` does not exist. The release-surface test fails until README mentions all three target archives.

---

### Task 5: Add The GitHub Release Workflow

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create `.github/workflows/release.yml`**

Create the workflow with this content:

```yaml
name: Release

on:
  push:
    tags:
      - "v*"
  workflow_dispatch:

permissions:
  contents: read

env:
  CARGO_TERM_COLOR: always
  DEDIREN_ELK_BUILD_USE_SDKMAN: "0"
  SEGMENT_DOWNLOAD_TIMEOUT_MINS: "5"

jobs:
  validate:
    name: Validate release surfaces
    runs-on: ubuntu-24.04
    steps:
      - name: Checkout
        uses: actions/checkout@v6

      - name: Install Rust
        run: |
          rustup toolchain install stable --profile minimal
          rustup default stable

      - name: Restore Cargo cache
        uses: actions/cache@v5
        with:
          path: |
            ~/.cargo/registry
            ~/.cargo/git
            target
          key: cargo-validate-${{ runner.os }}-${{ runner.arch }}-${{ hashFiles('Cargo.lock') }}
          restore-keys: |
            cargo-validate-${{ runner.os }}-${{ runner.arch }}-

      - name: Check formatting
        run: cargo fmt --all -- --check

      - name: Check release surfaces
        run: cargo test -p dediren-contracts --test schema_contracts --locked

      - name: Check dist tests
        run: cargo test -p xtask --test dist --locked

      - name: Check whitespace
        run: git diff --check

  build:
    name: Build ${{ matrix.target }}
    needs: validate
    strategy:
      fail-fast: false
      matrix:
        include:
          - target: x86_64-unknown-linux-gnu
            runner: ubuntu-24.04
          - target: aarch64-unknown-linux-gnu
            runner: ubuntu-24.04-arm
          - target: aarch64-apple-darwin
            runner: macos-15
    runs-on: ${{ matrix.runner }}
    permissions:
      contents: read
    steps:
      - name: Checkout
        uses: actions/checkout@v6

      - name: Install Rust
        run: |
          rustup toolchain install stable --profile minimal
          rustup default stable
          rustup target add ${{ matrix.target }}

      - name: Set up Java
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "25"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v6
        with:
          gradle-version: "9.5.0"
          cache-provider: basic

      - name: Install Linux runtime tools
        if: runner.os == 'Linux'
        run: |
          sudo apt-get update
          sudo apt-get install -y libxml2-utils jq

      - name: Install macOS runtime tools
        if: runner.os == 'macOS'
        run: |
          brew install libxml2 jq
          echo "/opt/homebrew/opt/libxml2/bin" >> "$GITHUB_PATH"

      - name: Restore Cargo cache
        uses: actions/cache@v5
        with:
          path: |
            ~/.cargo/registry
            ~/.cargo/git
            target
          key: cargo-release-${{ runner.os }}-${{ runner.arch }}-${{ matrix.target }}-${{ hashFiles('Cargo.lock') }}
          restore-keys: |
            cargo-release-${{ runner.os }}-${{ runner.arch }}-${{ matrix.target }}-
            cargo-release-${{ runner.os }}-${{ runner.arch }}-

      - name: Build distribution archive
        run: cargo xtask dist build --target ${{ matrix.target }}

      - name: Smoke distribution archive
        run: cargo xtask dist smoke "dist/dediren-agent-bundle-$(cargo xtask version)-${{ matrix.target }}.tar.gz"

      - name: Upload archive artifact
        uses: actions/upload-artifact@v7
        with:
          name: dediren-agent-bundle-${{ matrix.target }}
          path: dist/dediren-agent-bundle-*-${{ matrix.target }}.tar.gz
          if-no-files-found: error
          retention-days: 14
          archive: false

  publish:
    name: Publish GitHub release
    needs: build
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-24.04
    permissions:
      contents: write
    steps:
      - name: Checkout
        uses: actions/checkout@v6

      - name: Install Rust
        run: |
          rustup toolchain install stable --profile minimal
          rustup default stable

      - name: Install release tools
        run: |
          sudo apt-get update
          sudo apt-get install -y jq

      - name: Download archives
        uses: actions/download-artifact@v7
        with:
          path: release-artifacts
          merge-multiple: true

      - name: Verify release assets
        run: |
          set -euo pipefail
          VERSION="${GITHUB_REF_NAME#v}"
          WORKSPACE_VERSION="$(cargo xtask version)"
          if [[ "$VERSION" != "$WORKSPACE_VERSION" ]]; then
            echo "tag version $VERSION does not match workspace version $WORKSPACE_VERSION" >&2
            exit 1
          fi

          mkdir -p release-assets
          cp release-artifacts/*.tar.gz release-assets/

          expected_targets=(
            x86_64-unknown-linux-gnu
            aarch64-unknown-linux-gnu
            aarch64-apple-darwin
          )
          for target in "${expected_targets[@]}"; do
            archive="release-assets/dediren-agent-bundle-${VERSION}-${target}.tar.gz"
            bundle="dediren-agent-bundle-${VERSION}-${target}"
            if [[ ! -f "$archive" ]]; then
              echo "missing release archive: $archive" >&2
              exit 1
            fi
            tar -tzf "$archive" "$bundle/LICENSE" >/dev/null
            tar -tzf "$archive" "$bundle/docs/agent-usage.md" >/dev/null
            tar -xOf "$archive" "$bundle/bundle.json" |
              jq -e --arg version "$VERSION" --arg target "$target" \
                '.version == $version and .target == $target' >/dev/null
          done

          count=$(find release-assets -maxdepth 1 -name '*.tar.gz' | wc -l)
          if [[ "$count" -ne "${#expected_targets[@]}" ]]; then
            echo "expected ${#expected_targets[@]} archives, found $count" >&2
            find release-assets -maxdepth 1 -type f -print >&2
            exit 1
          fi

          (cd release-assets && sha256sum *.tar.gz > SHA256SUMS)

      - name: Publish release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -euo pipefail
          cat > release-notes.md <<EOF
          Dediren ${GITHUB_REF_NAME}

          Native agent bundle archives:

          - Linux x86_64
          - Linux arm64
          - macOS arm64
          EOF

          if gh release view "$GITHUB_REF_NAME" >/dev/null 2>&1; then
            gh release upload "$GITHUB_REF_NAME" release-assets/*.tar.gz release-assets/SHA256SUMS
          else
            gh release create "$GITHUB_REF_NAME" \
              --verify-tag \
              --title "$GITHUB_REF_NAME" \
              --notes-file release-notes.md \
              release-assets/*.tar.gz \
              release-assets/SHA256SUMS
          fi
```

- [ ] **Step 2: Run the workflow-surface test**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts release_workflow_matches_supported_targets_and_permissions --locked
```

Expected: PASS.

---

### Task 6: Bump Version And Update Public Release Surfaces

**Files:**
- Modify: `Cargo.toml`
- Modify: `Cargo.lock`
- Modify: `fixtures/plugins/archimate-oef.manifest.json`
- Modify: `fixtures/plugins/elk-layout.manifest.json`
- Modify: `fixtures/plugins/generic-graph.manifest.json`
- Modify: `fixtures/plugins/svg-render.manifest.json`
- Modify: `fixtures/plugins/uml-xmi.manifest.json`
- Modify: `fixtures/source/valid-archimate-oef.json`
- Modify: `fixtures/source/valid-basic.json`
- Modify: `fixtures/source/valid-pipeline-archimate.json`
- Modify: `fixtures/source/valid-pipeline-rich.json`
- Modify: `fixtures/source/valid-uml-basic.json`
- Modify: `fixtures/source/valid-uml-complex.json`
- Modify: `README.md`
- Modify: `docs/agent-usage.md`
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`

- [ ] **Step 1: Bump encoded release versions from `0.14.9` to `0.14.10`**

Run:

```bash
perl -0pi -e 's/0\.14\.9/0.14.10/g' \
  Cargo.toml \
  fixtures/plugins/*.manifest.json \
  fixtures/source/*.json \
  README.md \
  docs/agent-usage.md
cargo update -w
```

Expected: `Cargo.toml`, `Cargo.lock`, first-party plugin manifests, required-plugin source fixtures, README examples, and agent guide examples move to `0.14.10`.

- [ ] **Step 2: Replace the README install section with release-first wording**

In `README.md`, replace the current `### Linux Distribution Archive` section through the paragraph ending with `Do not rely on this source repository README being present at runtime.` with:

````markdown
### GitHub Release Archives

For an agent-ready installation, download a release archive from GitHub
Releases. The first release targets are:

```text
dediren-agent-bundle-0.14.10-x86_64-unknown-linux-gnu.tar.gz
dediren-agent-bundle-0.14.10-aarch64-unknown-linux-gnu.tar.gz
dediren-agent-bundle-0.14.10-aarch64-apple-darwin.tar.gz
```

Runtime prerequisite:

- Java 21 or newer available as `java` on `PATH`.
- `xmllint` on `PATH` for ArchiMate OEF and UML/XMI export standards
  validation.
- `curl` on `PATH` if export validation needs to populate the standards schema
  cache automatically. Offline runs can provide schema files through
  `DEDIREN_OEF_SCHEMA_DIR` and `DEDIREN_XMI_SCHEMA_PATH`.

Unpack and run the archive:

```bash
VERSION=0.14.10
TARGET=x86_64-unknown-linux-gnu
mkdir -p /tmp/dediren-dist
tar -xzf "dediren-agent-bundle-${VERSION}-${TARGET}.tar.gz" -C /tmp/dediren-dist
"/tmp/dediren-dist/dediren-agent-bundle-${VERSION}-${TARGET}/bin/dediren" --help
```

Maintainers can build the same archive shape from a source checkout. Run the
matching command on a native host for the target:

```bash
cargo xtask dist build --target x86_64-unknown-linux-gnu
cargo xtask dist build --target aarch64-unknown-linux-gnu
cargo xtask dist build --target aarch64-apple-darwin
```

Local build prerequisites:

- A host matching the requested target.
- Rust and Cargo matching the workspace toolchain.
- Java 25 and Gradle 9.5.0, either through SDKMAN with
  `crates/dediren-plugin-elk-layout/java/.sdkmanrc` or already available on
  `PATH` with `DEDIREN_ELK_BUILD_USE_SDKMAN=0`.

Run the smoke test from a shell where `java -version` resolves to Java 21 or
newer and `xmllint --version` succeeds. Export validation also needs either
cached standards schemas, configured local schema paths, or `curl` network
access to populate the cache:

```bash
cargo xtask dist smoke dist/dediren-agent-bundle-0.14.10-x86_64-unknown-linux-gnu.tar.gz
```

Concurrent `cargo xtask dist build` invocations serialize on a repo-local lock
under `.cache/locks/` because release binaries, the ELK helper build, and
`dist/` artifacts are shared generated outputs.
A successful build leaves only the current `dediren-agent-bundle-*` directory
and `.tar.gz` archive in `dist/`; stale bundle versions are pruned.

For a full unpacked-bundle JSON authoring and project/layout/render smoke
workflow, use the `JSON Authoring Loop` and `Bundle Smoke Workflow` sections in
`docs/agent-usage.md`.

The archive includes the root MIT `LICENSE` notice, first-party plugin
manifests under `plugins/`, first-party plugin binaries under `bin/`, schemas,
fixtures, `docs/agent-usage.md`, and the built ELK Java helper under
`runtimes/elk-layout-java/`. It does not bundle a JRE.
Skill packages that bundle Dediren should preserve the archive's
`docs/agent-usage.md` file or embed the same JSON authoring contract in the
skill guidance. Do not rely on this source repository README being present at
runtime.
````

- [ ] **Step 3: Update README development checks for target-aware dist commands**

In `README.md`, replace the distribution check block with:

```bash
cargo xtask dist build --target x86_64-unknown-linux-gnu
cargo xtask dist smoke dist/dediren-agent-bundle-0.14.10-x86_64-unknown-linux-gnu.tar.gz
```

Then add this sentence after the block:

```markdown
On GitHub Actions, the release workflow runs the equivalent build and smoke
checks for `x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu`, and
`aarch64-apple-darwin` on native runners.
```

- [ ] **Step 4: Make agent guide bundle examples target-aware**

In `docs/agent-usage.md`, replace bundle example assignments like:

```bash
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-0.14.10-x86_64-unknown-linux-gnu
```

with:

```bash
VERSION=0.14.10
TARGET=x86_64-unknown-linux-gnu
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}-${TARGET}
```

- [ ] **Step 5: Update agent guide version extraction test for variable-style examples**

In `crates/dediren-contracts/tests/schema_contracts.rs`, update `agent_usage_example_versions` so it also captures `VERSION=...` lines:

```rust
fn agent_usage_example_versions(guide: &str) -> Vec<&str> {
    let mut versions = Vec::new();
    for line in guide.lines() {
        if let Some(version) = line
            .split(r#""version": ""#)
            .nth(1)
            .and_then(|value| value.split('"').next())
        {
            versions.push(version);
        }
        if let Some(version) = line
            .strip_prefix("VERSION=")
            .filter(|value| value.chars().next().is_some_and(|c| c.is_ascii_digit()))
        {
            versions.push(version);
        }
        if let Some(version) = line
            .split("dediren-agent-bundle-")
            .nth(1)
            .and_then(|value| {
                RELEASE_TARGETS.iter().find_map(|target| {
                    value
                        .split_once(&format!("-{target}"))
                        .map(|(version, _)| version)
                })
            })
        {
            versions.push(version);
        }
    }
    versions
}
```

- [ ] **Step 6: Run focused version and release-surface tests**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts live_release_surfaces_match_workspace_version --locked
cargo test -p dediren-contracts --test schema_contracts agent_usage_versioned_examples_match_workspace_version --locked
cargo test -p dediren-contracts --test schema_contracts first_party_plugin_manifest_versions_match_workspace_version --locked
cargo test -p dediren-contracts --test schema_contracts source_fixture_required_plugin_versions_match_first_party_manifests --locked
```

Expected: all pass.

- [ ] **Step 7: Run stale-version search**

Run:

```bash
rg -n '0\.14\.9|0\.14\.10' Cargo.toml Cargo.lock README.md docs/agent-usage.md fixtures/plugins fixtures/source
```

Expected: no `0.14.9` matches. `0.14.10` appears only in the intended current release surfaces.

---

### Task 7: Verify The Whole Release Slice And Commit

**Files:**
- Review all files changed by Tasks 1-6.

- [ ] **Step 1: Run formatting and static checks**

Run:

```bash
cargo fmt --all -- --check
git diff --check
```

Expected: both pass.

- [ ] **Step 2: Run focused Rust test lanes**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts --locked
cargo test -p xtask --test dist --locked
```

Expected: both pass.

- [ ] **Step 3: Run current-host dist build and smoke**

On the current host, run the matching command. On Linux x86_64:

```bash
cargo xtask dist build --target x86_64-unknown-linux-gnu
cargo xtask dist smoke dist/dediren-agent-bundle-0.14.10-x86_64-unknown-linux-gnu.tar.gz
```

On Linux arm64:

```bash
cargo xtask dist build --target aarch64-unknown-linux-gnu
cargo xtask dist smoke dist/dediren-agent-bundle-0.14.10-aarch64-unknown-linux-gnu.tar.gz
```

On macOS arm64:

```bash
cargo xtask dist build --target aarch64-apple-darwin
cargo xtask dist smoke dist/dediren-agent-bundle-0.14.10-aarch64-apple-darwin.tar.gz
```

Expected: build and smoke pass for the host target. Do not stage `dist/`, `.cache/gradle/`, Java `build/`, or other generated outputs.

- [ ] **Step 4: Run broader workspace tests if time and environment allow**

Run:

```bash
cargo test --workspace --locked
```

Expected: pass. If this is skipped because the release-lane checks already cover the changed files and the environment cannot run the full workspace, record the reason in the handoff.

- [ ] **Step 5: Run audit gates**

Run a quick DevSecOps audit on:

```text
.github/workflows/release.yml
xtask/src/main.rs
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
README.md
```

Run a quick test-quality audit on:

```text
xtask/tests/dist.rs
crates/dediren-contracts/tests/schema_contracts.rs
```

Expected: block findings are fixed before completion. Warn/info findings are either fixed or explicitly accepted in the handoff with rationale.

- [ ] **Step 6: Review exact diffs before staging**

Run:

```bash
git diff -- Cargo.toml Cargo.lock README.md docs/agent-usage.md \
  fixtures/plugins/archimate-oef.manifest.json \
  fixtures/plugins/elk-layout.manifest.json \
  fixtures/plugins/generic-graph.manifest.json \
  fixtures/plugins/svg-render.manifest.json \
  fixtures/plugins/uml-xmi.manifest.json \
  fixtures/source/valid-archimate-oef.json \
  fixtures/source/valid-basic.json \
  fixtures/source/valid-pipeline-archimate.json \
  fixtures/source/valid-pipeline-rich.json \
  fixtures/source/valid-uml-basic.json \
  fixtures/source/valid-uml-complex.json \
  xtask/src/main.rs xtask/tests/dist.rs \
  crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh \
  crates/dediren-contracts/tests/schema_contracts.rs \
  .github/workflows/release.yml
```

Expected: only intentional changes for this release slice.

- [ ] **Step 7: Stage only intentional tracked files and commit**

Run:

```bash
git add Cargo.toml Cargo.lock README.md docs/agent-usage.md \
  fixtures/plugins/archimate-oef.manifest.json \
  fixtures/plugins/elk-layout.manifest.json \
  fixtures/plugins/generic-graph.manifest.json \
  fixtures/plugins/svg-render.manifest.json \
  fixtures/plugins/uml-xmi.manifest.json \
  fixtures/source/valid-archimate-oef.json \
  fixtures/source/valid-basic.json \
  fixtures/source/valid-pipeline-archimate.json \
  fixtures/source/valid-pipeline-rich.json \
  fixtures/source/valid-uml-basic.json \
  fixtures/source/valid-uml-complex.json \
  xtask/src/main.rs xtask/tests/dist.rs \
  crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh \
  crates/dediren-contracts/tests/schema_contracts.rs \
  .github/workflows/release.yml
git commit -m "build: publish release archives from github actions"
```

Expected: one scoped commit containing the target-aware dist behavior, release workflow, synchronized patch version bump, docs, and tests.

- [ ] **Step 8: Finish with status**

Run:

```bash
git status --short --branch
```

Expected: branch is ahead by the implementation commit and no unrelated files are staged. Ignored generated outputs may exist locally and should be reported, not committed.

## Self-Review Notes

Spec coverage:

- Tag-driven release path: Task 5.
- Native target matrix: Tasks 1, 2, 4, 5.
- CI-friendly Java helper without bundled JRE: Task 3.
- Build caching: Task 5.
- Release publication and checksum verification: Task 5.
- README and agent guide updates: Task 6.
- Patch version bump and stale-version search: Task 6.
- Runtime/archive smoke verification: Tasks 2, 5, and 7.
- Audit gates: Task 7.

Implementation limits:

- The first GitHub tag run remains the only full proof for all three native archives.
- Local execution can prove only the current host target unless the engineer has matching native hardware or uses GitHub-hosted runners.
