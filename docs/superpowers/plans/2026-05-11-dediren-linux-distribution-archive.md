# Linux Distribution Archive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a repo-local Linux archive that can be unpacked anywhere and run `dediren` with bundled first-party plugins, schemas, fixtures, and the built ELK Java helper.

**Architecture:** Keep the core contract-first: `dediren-core` owns plugin manifest discovery and executable resolution, while first-party plugins stay process-boundary executables. The `elk-layout` plugin owns helper-runtime lookup relative to its installed `bin/` location and still accepts explicit runtime overrides. Shell scripts assemble and smoke-test the archive without moving runtime policy into the CLI.

**Tech Stack:** Rust 2021 Cargo workspace, Bash scripts, Gradle Java application distribution, JSON schemas/fixtures, `assert_cmd`, `tempfile`.

---

## File Structure

- Modify: `crates/dediren-core/src/plugins.rs`
  - Add installed-bundle manifest discovery from the bundle root `plugins/` directory.
  - Resolve manifest binary names from the bundle root `bin/` directory when running from an installed bundle.
  - Keep explicit plugin executable environment overrides and source-checkout lookup behavior.
- Modify: `crates/dediren-core/src/commands.rs`
  - Preserve the installed-bundle registry in layout command orchestration.
  - Preserve the narrow ELK runtime environment allowlist and add `PATH` so the installed Java helper can find `java`.
- Modify: `crates/dediren-core/tests/plugin_runtime.rs`
  - Add installed-bundle registry and binary-resolution coverage.
- Modify: `crates/dediren-plugin-elk-layout/src/main.rs`
  - Add bundled helper lookup after fixture and explicit command overrides.
  - Add structured Java-runtime diagnostics for bundled helper execution.
- Modify: `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`
  - Add tests for helper lookup precedence and bundled-helper path calculation.
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`
  - Add a version guard proving first-party manifest versions match the workspace package version.
- Create: `scripts/build-dist.sh`
  - Build Rust release binaries, build the ELK helper, assemble `dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/`, write `bundle.json`, and create the `.tar.gz`.
- Create: `scripts/smoke-dist.sh`
  - Unpack a built archive into a temporary directory and run `project -> layout -> render` from the unpacked `bin/dediren`.
- Modify: `.gitignore`
  - Ignore `/dist/` so generated archives and bundle trees are not staged.
- Modify: `README.md`
  - Document manual Linux dist build prerequisites, build command, unpack command, runtime Java requirement, and smoke-test command.

---

### Task 1: Installed Bundle Plugin Discovery

**Files:**
- Modify: `crates/dediren-core/src/plugins.rs`
- Modify: `crates/dediren-core/tests/plugin_runtime.rs`

- [ ] **Step 1: Add failing installed-bundle registry test**

Add these imports near the top of `crates/dediren-core/tests/plugin_runtime.rs`:

```rust
#[cfg(unix)]
use std::os::unix::fs::PermissionsExt;
```

Add this test near the other registry/execution tests:

```rust
#[test]
fn installed_bundle_registry_finds_manifest_and_binary_next_to_cli() {
    let temp = TempDir::new().unwrap();
    let bundle = temp.path().join("dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu");
    let bin_dir = bundle.join("bin");
    let plugin_dir = bundle.join("plugins");
    std::fs::create_dir_all(&bin_dir).unwrap();
    std::fs::create_dir_all(&plugin_dir).unwrap();

    let plugin_binary = bin_dir.join(if cfg!(windows) {
        "dediren-plugin-runtime-testbed.exe"
    } else {
        "dediren-plugin-runtime-testbed"
    });
    std::fs::copy(testbed_binary(), &plugin_binary).unwrap();
    #[cfg(unix)]
    {
        let mut permissions = std::fs::metadata(&plugin_binary).unwrap().permissions();
        permissions.set_mode(0o755);
        std::fs::set_permissions(&plugin_binary, permissions).unwrap();
    }

    write_manifest(
        &plugin_dir,
        "runtime-testbed",
        "dediren-plugin-runtime-testbed",
        &["layout"],
    );

    let fake_cli = bin_dir.join("dediren");
    let registry = PluginRegistry::for_executable(&fake_cli);
    let outcome = run_plugin_for_capability_with_registry(
        &registry,
        "runtime-testbed",
        "layout",
        &["layout"],
        "{}",
        PluginRunOptions::default(),
    )
    .expect("installed bundle registry should find plugin manifest and binary");

    assert_eq!(outcome.exit_code, 0);
    assert!(outcome.stdout.contains("\"layout_result_schema_version\""));
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
cargo test -p dediren-core --test plugin_runtime installed_bundle_registry_finds_manifest_and_binary_next_to_cli --locked
```

Expected: FAIL to compile because `PluginRegistry::for_executable` does not exist.

- [ ] **Step 3: Implement installed-bundle registry ownership**

In `crates/dediren-core/src/plugins.rs`, change the registry structs to carry the binary directory:

```rust
#[derive(Debug, Clone)]
pub struct PluginRegistry {
    manifest_dirs: Vec<PathBuf>,
    binary_dir: Option<PathBuf>,
}

#[derive(Debug, Clone)]
struct LoadedPluginManifest {
    manifest: PluginManifest,
    path: PathBuf,
    binary_dir: Option<PathBuf>,
}
```

Replace the existing `PluginRegistry` constructor block with:

```rust
impl PluginRegistry {
    pub fn bundled() -> Self {
        match std::env::current_exe() {
            Ok(executable) => Self::for_executable(&executable),
            Err(_) => Self::from_dirs(Self::fallback_manifest_dirs()),
        }
    }

    pub fn for_executable(executable: &Path) -> Self {
        let mut manifest_dirs = Vec::new();
        let mut binary_dir = None;

        if let Some(root) = installed_bundle_root(executable) {
            manifest_dirs.push(root.join("plugins"));
            binary_dir = executable.parent().map(Path::to_path_buf);
        }

        manifest_dirs.extend(Self::fallback_manifest_dirs());
        Self {
            manifest_dirs,
            binary_dir,
        }
    }

    pub fn bundled_dirs() -> Vec<PathBuf> {
        Self::bundled().manifest_dirs
    }

    fn fallback_manifest_dirs() -> Vec<PathBuf> {
        let mut manifest_dirs = vec![
            PathBuf::from("fixtures/plugins"),
            PathBuf::from(".dediren/plugins"),
        ];
        if let Ok(configured) = std::env::var("DEDIREN_PLUGIN_DIRS") {
            manifest_dirs.extend(std::env::split_paths(&configured));
        }
        manifest_dirs
    }

    pub fn from_dirs(manifest_dirs: Vec<PathBuf>) -> Self {
        Self {
            manifest_dirs,
            binary_dir: None,
        }
    }

    pub fn load_manifest(&self, plugin_id: &str) -> anyhow::Result<PluginManifest> {
        Ok(self.load_manifest_with_path(plugin_id)?.manifest)
    }

    fn load_manifest_with_path(
        &self,
        plugin_id: &str,
    ) -> Result<LoadedPluginManifest, PluginExecutionError> {
        for dir in &self.manifest_dirs {
            let path = dir.join(format!("{plugin_id}.manifest.json"));
            if path.exists() {
                let text =
                    std::fs::read_to_string(&path).map_err(|error| PluginExecutionError::Io {
                        plugin_id: plugin_id.to_string(),
                        message: error.to_string(),
                    })?;
                let value: serde_json::Value = serde_json::from_str(&text).map_err(|error| {
                    PluginExecutionError::ManifestInvalid {
                        plugin_id: plugin_id.to_string(),
                        message: error.to_string(),
                    }
                })?;
                validate_value_against_schema(
                    include_str!("../../../schemas/plugin-manifest.schema.json"),
                    &value,
                )
                .map_err(|message| PluginExecutionError::ManifestInvalid {
                    plugin_id: plugin_id.to_string(),
                    message,
                })?;
                let manifest: PluginManifest = serde_json::from_value(value).map_err(|error| {
                    PluginExecutionError::ManifestInvalid {
                        plugin_id: plugin_id.to_string(),
                        message: error.to_string(),
                    }
                })?;
                if manifest.id == plugin_id {
                    return Ok(LoadedPluginManifest {
                        manifest,
                        path,
                        binary_dir: self.binary_dir.clone(),
                    });
                }
                return Err(PluginExecutionError::ManifestInvalid {
                    plugin_id: plugin_id.to_string(),
                    message: format!("manifest id '{}' did not match requested id", manifest.id),
                });
            }
        }
        Err(PluginExecutionError::UnknownPlugin {
            plugin_id: plugin_id.to_string(),
        })
    }
}

fn installed_bundle_root(executable: &Path) -> Option<PathBuf> {
    let bin_dir = executable.parent()?;
    if bin_dir.file_name()? != "bin" {
        return None;
    }
    let root = bin_dir.parent()?;
    if root.join("plugins").is_dir() {
        Some(root.to_path_buf())
    } else {
        None
    }
}
```

Update `executable_path` so binary-name executables resolve through the installed bundle binary directory:

```rust
fn executable_path(loaded: &LoadedPluginManifest) -> Result<PathBuf, PluginExecutionError> {
    let env_name = format!(
        "DEDIREN_PLUGIN_{}",
        loaded.manifest.id.to_ascii_uppercase().replace('-', "_")
    );
    if let Ok(path) = std::env::var(env_name) {
        return Ok(PathBuf::from(path));
    }

    let executable = PathBuf::from(&loaded.manifest.executable);
    if executable.is_absolute() {
        return Ok(executable);
    }
    if executable.components().count() > 1 {
        return Ok(loaded
            .path
            .parent()
            .unwrap_or_else(|| Path::new("."))
            .join(executable));
    }
    if let Some(binary_dir) = &loaded.binary_dir {
        return Ok(binary_dir.join(&loaded.manifest.executable));
    }
    Ok(std::env::current_exe()
        .map_err(|error| PluginExecutionError::Io {
            plugin_id: loaded.manifest.id.clone(),
            message: error.to_string(),
        })?
        .with_file_name(&loaded.manifest.executable))
}
```

- [ ] **Step 4: Run the installed-bundle registry test**

Run:

```bash
cargo test -p dediren-core --test plugin_runtime installed_bundle_registry_finds_manifest_and_binary_next_to_cli --locked
```

Expected: PASS.

- [ ] **Step 5: Run core plugin runtime regression tests**

Run:

```bash
cargo test -p dediren-core --test plugin_runtime --locked
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add crates/dediren-core/src/plugins.rs crates/dediren-core/tests/plugin_runtime.rs
git commit -m "feat: discover installed bundle plugins"
```

---

### Task 2: Layout Runtime Environment And Bundled ELK Helper

**Files:**
- Modify: `crates/dediren-core/src/commands.rs`
- Modify: `crates/dediren-core/tests/commands.rs`
- Modify: `crates/dediren-plugin-elk-layout/src/main.rs`
- Modify: `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`

- [ ] **Step 1: Preserve the installed-bundle registry for layout commands**

In `crates/dediren-core/src/commands.rs`, change `LayoutCommandInput` to carry a registry instead of only manifest directories:

```rust
pub struct LayoutCommandInput<'a> {
    pub plugin: &'a str,
    pub input_text: &'a str,
    pub registry: PluginRegistry,
    pub env: Vec<(String, String)>,
}
```

In `layout_command`, remove the local `PluginRegistry::from_dirs(input.plugin_dirs)` construction and call the plugin runtime with `&input.registry`:

```rust
run_plugin_for_capability_with_registry(
    &input.registry,
    input.plugin,
    "layout",
    &["layout"],
    &serde_json::to_string(&request).map_err(command_input_error("layout"))?,
    options,
)
```

In `layout_command_from_env`, use `PluginRegistry::bundled()` so installed-bundle binary resolution is retained:

```rust
layout_command(LayoutCommandInput {
    plugin,
    input_text,
    registry: PluginRegistry::bundled(),
    env,
})
```

In `crates/dediren-core/tests/commands.rs`, add this import:

```rust
use dediren_core::plugins::PluginRegistry;
```

Update the existing `layout_command_owns_elk_runtime_environment_allowlist` test input to use a registry:

```rust
let output = layout_command(LayoutCommandInput {
    plugin: "elk-layout",
    input_text: &request,
    registry: PluginRegistry::from_dirs(vec![plugin_dir.path().to_path_buf()]),
    env: vec![(
        "DEDIREN_ELK_RESULT_FIXTURE".to_string(),
        fixture.display().to_string(),
    )],
})
.expect("layout command orchestration should run through core");
```

- [ ] **Step 2: Implement narrow layout environment allowlist**

In `crates/dediren-core/src/commands.rs`, replace the loop that pushes ELK environment values with:

```rust
for (name, value) in input.env {
    if matches!(
        name.as_str(),
        "DEDIREN_ELK_COMMAND"
            | "DEDIREN_ELK_RESULT_FIXTURE"
            | "PATH"
    ) {
        options.allowed_env.push((name, value));
    }
}
```

Update the environment capture in `layout_command_from_env` so it captures `PATH` when available:

```rust
let mut env = Vec::new();
for name in ["DEDIREN_ELK_COMMAND", "DEDIREN_ELK_RESULT_FIXTURE", "PATH"] {
    if let Ok(value) = std::env::var(name) {
        env.push((name.to_string(), value));
    }
}
```

- [ ] **Step 3: Run core command tests**

Run:

```bash
cargo test -p dediren-core --test commands --locked
```

Expected: PASS.

- [ ] **Step 4: Add failing ELK helper lookup tests**

Add these tests to `crates/dediren-plugin-elk-layout/src/main.rs` in a `#[cfg(test)]` module at the end of the file:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn bundled_elk_command_is_relative_to_installed_bin() {
        let executable = PathBuf::from(
            "/tmp/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/bin/dediren-plugin-elk-layout",
        );

        let helper = bundled_elk_command_for_executable(&executable)
            .expect("installed plugin executable should produce helper path");

        assert_eq!(
            helper,
            PathBuf::from(
                "/tmp/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/runtimes/elk-layout-java/bin/dediren-elk-layout-java"
            )
        );
    }

    #[test]
    fn target_debug_plugin_executable_has_no_bundled_helper_path() {
        let executable = PathBuf::from("/tmp/repo/target/debug/dediren-plugin-elk-layout");

        assert!(bundled_elk_command_for_executable(&executable).is_none());
    }

    #[test]
    fn runtime_command_prefers_fixture_then_explicit_command_then_bundle() {
        let root = std::env::temp_dir().join(format!(
            "dediren-elk-bundle-test-{}",
            std::process::id()
        ));
        let bin_dir = root.join("bin");
        let helper = root.join("runtimes/elk-layout-java/bin/dediren-elk-layout-java");
        std::fs::create_dir_all(&bin_dir).unwrap();
        std::fs::create_dir_all(helper.parent().unwrap()).unwrap();
        std::fs::write(&helper, "").unwrap();

        let executable = bin_dir.join("dediren-plugin-elk-layout");
        let env = vec![
            ("DEDIREN_ELK_RESULT_FIXTURE".to_string(), "fixture.json".to_string()),
            ("DEDIREN_ELK_COMMAND".to_string(), "explicit-helper".to_string()),
        ];

        assert!(matches!(
            runtime_command_from_env(&env, &executable),
            RuntimeCommand::Fixture(_)
        ));

        let env = vec![("DEDIREN_ELK_COMMAND".to_string(), "explicit-helper".to_string())];
        assert!(matches!(
            runtime_command_from_env(&env, &executable),
            RuntimeCommand::Explicit(_)
        ));

        let env = Vec::new();
        assert!(matches!(
            runtime_command_from_env(&env, &executable),
            RuntimeCommand::Bundled(_)
        ));

        std::fs::remove_dir_all(root).unwrap();
    }
}
```

- [ ] **Step 5: Run the failing ELK helper lookup tests**

Run:

```bash
cargo test -p dediren-plugin-elk-layout --bin dediren-plugin-elk-layout --locked
```

Expected: FAIL because `RuntimeCommand`, `runtime_command_from_env`, and `bundled_elk_command_for_executable` do not exist.

- [ ] **Step 6: Implement ELK runtime command selection**

In `crates/dediren-plugin-elk-layout/src/main.rs`, add this enum below the imports:

```rust
#[derive(Debug, Clone, PartialEq, Eq)]
enum RuntimeCommand {
    Fixture(String),
    Explicit(String),
    Bundled(std::path::PathBuf),
    Missing,
}
```

Replace the fixture and `DEDIREN_ELK_COMMAND` branch in `main` with:

```rust
let env = std::env::vars().collect::<Vec<_>>();
let executable = std::env::current_exe()?;
match runtime_command_from_env(&env, &executable) {
    RuntimeCommand::Fixture(fixture) => {
        let text = std::fs::read_to_string(fixture)?;
        let result: LayoutResult = serde_json::from_str(&text)?;
        println!("{}", serde_json::to_string(&CommandEnvelope::ok(result))?);
        Ok(())
    }
    RuntimeCommand::Explicit(command_line) => {
        let output = run_external_elk(&command_line, &input).unwrap_or_else(|error| {
            exit_with_diagnostic(
                "DEDIREN_ELK_RUNTIME_FAILED",
                &format!("failed to run ELK runtime command: {error}"),
            );
        });
        emit_external_output(output)
    }
    RuntimeCommand::Bundled(command) => {
        if !command.exists() {
            exit_with_diagnostic(
                "DEDIREN_ELK_RUNTIME_UNAVAILABLE",
                &format!("bundled ELK helper is missing: {}", command.display()),
            );
        }
        if !java_runtime_available() {
            exit_with_diagnostic(
                "DEDIREN_ELK_JAVA_UNAVAILABLE",
                "Java runtime is required on PATH for the bundled ELK helper",
            );
        }
        let command_line = command.display().to_string();
        let output = run_external_elk(&command_line, &input).unwrap_or_else(|error| {
            exit_with_diagnostic(
                "DEDIREN_ELK_RUNTIME_FAILED",
                &format!("failed to run bundled ELK helper: {error}"),
            );
        });
        emit_external_output(output)
    }
    RuntimeCommand::Missing => {
        exit_with_diagnostic(
            "DEDIREN_ELK_RUNTIME_UNAVAILABLE",
            "ELK runtime is not configured; set DEDIREN_ELK_COMMAND or run from a bundle with runtimes/elk-layout-java",
        );
    }
}
```

Add these helpers above `run_external_elk`:

```rust
fn runtime_command_from_env(env: &[(String, String)], executable: &std::path::Path) -> RuntimeCommand {
    if let Some((_, fixture)) = env
        .iter()
        .find(|(name, _)| name == "DEDIREN_ELK_RESULT_FIXTURE")
    {
        return RuntimeCommand::Fixture(fixture.clone());
    }
    if let Some((_, command)) = env.iter().find(|(name, _)| name == "DEDIREN_ELK_COMMAND") {
        return RuntimeCommand::Explicit(command.clone());
    }
    if let Some(command) = bundled_elk_command_for_executable(executable) {
        return RuntimeCommand::Bundled(command);
    }
    RuntimeCommand::Missing
}

fn bundled_elk_command_for_executable(executable: &std::path::Path) -> Option<std::path::PathBuf> {
    let bin_dir = executable.parent()?;
    if bin_dir.file_name()? != "bin" {
        return None;
    }
    let root = bin_dir.parent()?;
    Some(root.join("runtimes/elk-layout-java/bin/dediren-elk-layout-java"))
}

fn java_runtime_available() -> bool {
    Command::new("java")
        .arg("-version")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|status| status.success())
        .unwrap_or(false)
}
```

- [ ] **Step 7: Run ELK plugin unit and integration tests**

Run:

```bash
cargo test -p dediren-plugin-elk-layout --bin dediren-plugin-elk-layout --locked
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin --locked
```

Expected: PASS.

- [ ] **Step 8: Commit Task 2**

```bash
git add crates/dediren-core/src/commands.rs crates/dediren-core/tests/commands.rs crates/dediren-plugin-elk-layout/src/main.rs crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs
git commit -m "feat: locate bundled elk runtime"
```

---

### Task 3: Manifest Version Guard

**Files:**
- Modify: `crates/dediren-contracts/tests/schema_contracts.rs`

- [ ] **Step 1: Add failing manifest version guard**

Add this test near `plugin_manifest_matches_schema` in `crates/dediren-contracts/tests/schema_contracts.rs`:

```rust
#[test]
fn first_party_plugin_manifest_versions_match_workspace_version() {
    for path in [
        "fixtures/plugins/archimate-oef.manifest.json",
        "fixtures/plugins/elk-layout.manifest.json",
        "fixtures/plugins/generic-graph.manifest.json",
        "fixtures/plugins/svg-render.manifest.json",
    ] {
        let text = std::fs::read_to_string(workspace_file(path)).unwrap();
        let manifest: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert_eq!(
            manifest["version"].as_str(),
            Some(env!("CARGO_PKG_VERSION")),
            "{path} version should match workspace package version"
        );
    }
}
```

- [ ] **Step 2: Run the version guard**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts first_party_plugin_manifest_versions_match_workspace_version --locked
```

Expected: PASS because the current manifests already use `0.1.0`.

- [ ] **Step 3: Commit Task 3**

```bash
git add crates/dediren-contracts/tests/schema_contracts.rs
git commit -m "test: guard first party plugin versions"
```

---

### Task 4: Build Distribution Archive Script

**Files:**
- Create: `scripts/build-dist.sh`
- Modify: `.gitignore`

- [ ] **Step 1: Create the script with the full assembly workflow**

Create `scripts/build-dist.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
TARGET=${DEDIREN_DIST_TARGET:-x86_64-unknown-linux-gnu}

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) ;;
  *)
    echo "scripts/build-dist.sh currently supports Linux x86_64 only" >&2
    exit 2
    ;;
esac

VERSION=$(awk -F '"' '/^version = / { print $2; exit }' "$ROOT/Cargo.toml")
if [[ -z "$VERSION" ]]; then
  echo "could not read workspace package version from Cargo.toml" >&2
  exit 2
fi

BUNDLE_NAME="dediren-agent-bundle-${VERSION}-${TARGET}"
DIST_DIR="$ROOT/dist"
BUNDLE_DIR="$DIST_DIR/$BUNDLE_NAME"
ARCHIVE="$DIST_DIR/${BUNDLE_NAME}.tar.gz"
BUILD_TIME=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

echo "checking first-party plugin manifest versions"
cargo test -p dediren-contracts --test schema_contracts first_party_plugin_manifest_versions_match_workspace_version --locked

echo "building Rust release binaries"
cargo build --release --locked \
  -p dediren \
  -p dediren-plugin-generic-graph \
  -p dediren-plugin-elk-layout \
  -p dediren-plugin-svg-render \
  -p dediren-plugin-archimate-oef-export

echo "building ELK Java helper"
"$ROOT/crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh"

echo "assembling $BUNDLE_DIR"
rm -rf "$BUNDLE_DIR" "$ARCHIVE"
mkdir -p "$BUNDLE_DIR/bin" "$BUNDLE_DIR/plugins" "$BUNDLE_DIR/runtimes"

install -m 755 "$ROOT/target/release/dediren" "$BUNDLE_DIR/bin/dediren"
install -m 755 "$ROOT/target/release/dediren-plugin-generic-graph" "$BUNDLE_DIR/bin/dediren-plugin-generic-graph"
install -m 755 "$ROOT/target/release/dediren-plugin-elk-layout" "$BUNDLE_DIR/bin/dediren-plugin-elk-layout"
install -m 755 "$ROOT/target/release/dediren-plugin-svg-render" "$BUNDLE_DIR/bin/dediren-plugin-svg-render"
install -m 755 "$ROOT/target/release/dediren-plugin-archimate-oef-export" "$BUNDLE_DIR/bin/dediren-plugin-archimate-oef-export"

cp "$ROOT"/fixtures/plugins/*.manifest.json "$BUNDLE_DIR/plugins/"
cp -R "$ROOT/schemas" "$BUNDLE_DIR/schemas"
cp -R "$ROOT/fixtures" "$BUNDLE_DIR/fixtures"
cp -R "$ROOT/crates/dediren-plugin-elk-layout/java/build/install/dediren-elk-layout-java" \
  "$BUNDLE_DIR/runtimes/elk-layout-java"

cat > "$BUNDLE_DIR/bundle.json" <<JSON
{
  "bundle_schema_version": "dediren-bundle.schema.v1",
  "product": "dediren",
  "version": "$VERSION",
  "target": "$TARGET",
  "built_at_utc": "$BUILD_TIME",
  "plugins": [
    { "id": "generic-graph", "version": "$VERSION" },
    { "id": "elk-layout", "version": "$VERSION" },
    { "id": "svg-render", "version": "$VERSION" },
    { "id": "archimate-oef", "version": "$VERSION" }
  ],
  "schemas_dir": "schemas",
  "fixtures_dir": "fixtures",
  "elk_helper": "runtimes/elk-layout-java/bin/dediren-elk-layout-java"
}
JSON

echo "creating $ARCHIVE"
tar -C "$DIST_DIR" -czf "$ARCHIVE" "$BUNDLE_NAME"

echo "$ARCHIVE"
```

Add `/dist/` to `.gitignore`:

```gitignore
/dist/
```

- [ ] **Step 2: Syntax-check the script**

Run:

```bash
bash -n scripts/build-dist.sh
```

Expected: PASS with no output.

- [ ] **Step 3: Run the dist build**

Run:

```bash
scripts/build-dist.sh
```

Expected: PASS and prints `dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu.tar.gz`.

- [ ] **Step 4: Inspect generated layout**

Run:

```bash
tar -tzf dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu.tar.gz | sed -n '1,40p'
```

Expected output includes:

```text
dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/bin/dediren
dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/plugins/elk-layout.manifest.json
dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/schemas/model.schema.json
dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/fixtures/source/valid-pipeline-rich.json
dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/runtimes/elk-layout-java/bin/dediren-elk-layout-java
dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/bundle.json
```

- [ ] **Step 5: Verify generated artifacts are ignored**

Run:

```bash
git status --short --ignored dist
```

Expected output begins with ignored `dist/` entries, and `git status --short` does not show `dist/` as untracked.

- [ ] **Step 6: Commit Task 4**

```bash
git add .gitignore scripts/build-dist.sh
git commit -m "build: assemble linux distribution archive"
```

---

### Task 5: Distribution Smoke Script

**Files:**
- Create: `scripts/smoke-dist.sh`

- [ ] **Step 1: Create the archive smoke-test script**

Create `scripts/smoke-dist.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

ARCHIVE=${1:-}
if [[ -z "$ARCHIVE" ]]; then
  echo "usage: scripts/smoke-dist.sh dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu.tar.gz" >&2
  exit 2
fi
if [[ ! -f "$ARCHIVE" ]]; then
  echo "archive not found: $ARCHIVE" >&2
  exit 2
fi
if ! command -v java >/dev/null 2>&1; then
  echo "java is required on PATH for the distribution smoke test" >&2
  exit 2
fi

TMP=$(mktemp -d)
cleanup() {
  rm -rf "$TMP"
}
trap cleanup EXIT

tar -xzf "$ARCHIVE" -C "$TMP"
BUNDLE_DIR=$(find "$TMP" -maxdepth 1 -type d -name 'dediren-agent-bundle-*' | sort | tail -n 1)
if [[ -z "$BUNDLE_DIR" ]]; then
  echo "archive did not contain a dediren-agent-bundle directory" >&2
  exit 2
fi

BIN="$BUNDLE_DIR/bin/dediren"
REQUEST="$TMP/request.json"
LAYOUT="$TMP/layout.json"
RENDER="$TMP/render.json"

"$BIN" --help >/dev/null
"$BIN" project \
  --target layout-request \
  --plugin generic-graph \
  --view main \
  --input "$BUNDLE_DIR/fixtures/source/valid-pipeline-rich.json" \
  > "$REQUEST"
"$BIN" layout \
  --plugin elk-layout \
  --input "$REQUEST" \
  > "$LAYOUT"
"$BIN" render \
  --plugin svg-render \
  --policy "$BUNDLE_DIR/fixtures/render-policy/rich-svg.json" \
  --input "$LAYOUT" \
  > "$RENDER"

grep -q '"status":"ok"' "$RENDER"
grep -q '"artifact_kind":"svg"' "$RENDER"
grep -q '<svg' "$RENDER"

echo "distribution smoke test passed: $ARCHIVE"
```

- [ ] **Step 2: Syntax-check the smoke script**

Run:

```bash
bash -n scripts/smoke-dist.sh
```

Expected: PASS with no output.

- [ ] **Step 3: Run the smoke script**

Run:

```bash
scripts/smoke-dist.sh dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu.tar.gz
```

Expected: PASS and prints `distribution smoke test passed: dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu.tar.gz`.

- [ ] **Step 4: Commit Task 5**

```bash
git add scripts/smoke-dist.sh
git commit -m "test: smoke linux distribution archive"
```

---

### Task 6: README Distribution Instructions

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the local install section**

In `README.md`, replace the `## Local Install` section with:

````markdown
## Local Install

For development from a source checkout:

```bash
cargo install --path crates/dediren-cli
cargo install --path crates/dediren-plugin-generic-graph
cargo install --path crates/dediren-plugin-elk-layout
cargo install --path crates/dediren-plugin-svg-render
cargo install --path crates/dediren-plugin-archimate-oef-export
```

This installs Rust binaries only. It does not create the agent-ready archive or
bundle the ELK Java helper distribution.
````

- [ ] **Step 2: Add Linux distribution archive instructions**

Add this section after `## Local Install`:

````markdown
## Linux Distribution Archive

The repo-local Linux distribution workflow builds an agent-ready archive under
`dist/`. The archive can be unpacked anywhere and run from its own `bin/`
directory without a source checkout.

Build prerequisites:

- Rust and Cargo matching the workspace toolchain.
- SDKMAN and the Java/Gradle setup used by
  `crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh`.
- Linux x86_64 host.

Runtime prerequisite:

- `java` available on `PATH`. The archive includes the built ELK helper and its
  dependency jars, but it does not bundle a JRE.

Build the archive:

```bash
scripts/build-dist.sh
```

The script creates:

```text
dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/
dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu.tar.gz
```

Smoke-test the archive:

```bash
scripts/smoke-dist.sh dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu.tar.gz
```

Unpack and run manually:

```bash
mkdir -p /tmp/dediren-dist
tar -xzf dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu.tar.gz -C /tmp/dediren-dist
/tmp/dediren-dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu/bin/dediren --help
```

The unpacked CLI discovers bundled first-party plugin manifests from
`plugins/`, resolves first-party plugin binaries from `bin/`, and uses the
bundled ELK helper under `runtimes/elk-layout-java/` when `DEDIREN_ELK_COMMAND`
is not set.
````

- [ ] **Step 3: Update plugin lookup wording**

In `README.md`, update the `## Plugin Lookup` numbered list to:

```markdown
The CLI discovers plugins explicitly:

1. bundled manifests under the installation root `plugins/` directory when
   running from an unpacked distribution archive;
2. repo fixture manifests in `fixtures/plugins` when running from the source
   checkout;
3. project plugin directories such as `.dediren/plugins`;
4. user-configured plugin directories from `DEDIREN_PLUGIN_DIRS`.
```

- [ ] **Step 4: Update ELK runtime wording**

In `README.md`, add this paragraph at the start of `## ELK Runtime`:

```markdown
When running from a Linux distribution archive, the `elk-layout` plugin uses the
bundled helper at `runtimes/elk-layout-java/bin/dediren-elk-layout-java` if
`DEDIREN_ELK_COMMAND` is not set. Java must be available on `PATH`.
```

- [ ] **Step 5: Check README formatting**

Run:

```bash
git diff --check -- README.md
```

Expected: PASS with no output.

- [ ] **Step 6: Commit Task 6**

```bash
git add README.md
git commit -m "docs: document linux distribution archive"
```

---

### Task 7: Full Verification And Audit Gates

**Files:**
- No new files.

- [ ] **Step 1: Run formatting and whitespace checks**

Run:

```bash
cargo fmt --all -- --check
git diff --check
bash -n scripts/build-dist.sh scripts/smoke-dist.sh
```

Expected: all commands PASS.

- [ ] **Step 2: Run focused Rust tests**

Run:

```bash
cargo test -p dediren-contracts --test schema_contracts first_party_plugin_manifest_versions_match_workspace_version --locked
cargo test -p dediren-core --test commands --locked
cargo test -p dediren-core --test plugin_runtime --locked
cargo test -p dediren-plugin-elk-layout --bin dediren-plugin-elk-layout --locked
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin --locked
cargo test -p dediren --test plugin_compat --locked
```

Expected: all commands PASS.

- [ ] **Step 3: Run workspace regression tests**

Run:

```bash
cargo test --workspace --locked
```

Expected: PASS.

- [ ] **Step 4: Build and smoke-test the distribution archive**

Run:

```bash
scripts/build-dist.sh
scripts/smoke-dist.sh dist/dediren-agent-bundle-0.1.0-x86_64-unknown-linux-gnu.tar.gz
```

Expected: both commands PASS. The smoke script proves the unpacked archive can run `project -> layout -> render` without source-checkout plugin fixture discovery or plugin executable overrides.

- [ ] **Step 5: Run audit validation required by this plan**

Run `souroldgeezer-audit:test-quality-audit` in Deep mode over:

```text
crates/dediren-core/tests/plugin_runtime.rs
crates/dediren-core/tests/commands.rs
crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs
crates/dediren-contracts/tests/schema_contracts.rs
scripts/smoke-dist.sh
```

Expected: no block findings. Fix warn/info findings or document accepted residual risk in the handoff.

Run `souroldgeezer-audit:devsecops-audit` in Quick mode over:

```text
scripts/build-dist.sh
scripts/smoke-dist.sh
crates/dediren-core/src/plugins.rs
crates/dediren-core/src/commands.rs
crates/dediren-plugin-elk-layout/src/main.rs
README.md
```

Expected: no block findings. Fix warn/info findings or document accepted residual risk in the handoff.

- [ ] **Step 6: Final git hygiene check**

Run:

```bash
git status --short --branch
git diff --stat HEAD
```

Expected: working tree contains only intentional changes or is clean. Generated `dist/`, `.cache/gradle/`, Java `build/`, and render outputs must not be staged.

- [ ] **Step 7: Close out audit fixes**

If audit fixes changed tracked files, review each changed path with `git diff -- path/to/file`, stage only intentional tracked files by exact path, and commit with a message that names the corrected distribution risk. If no tracked files changed after audit validation, do not create an empty commit.

---

## Spec Coverage

- Archive shape: Task 4 creates the `dist/` bundle tree and `.tar.gz`.
- Runtime lookup: Task 1 implements installed plugin manifest and binary lookup.
- ELK runtime: Task 2 implements bundled helper lookup and Java diagnostics.
- Versioning guidance: Task 3 enforces first-party manifest version drift checks; Task 4 writes versioned `bundle.json` and archive names.
- README requirements: Task 6 updates build, install, lookup, and ELK runtime docs.
- Error handling: Task 1 preserves core plugin diagnostics; Task 2 adds bundled ELK runtime diagnostics; Task 4 fails shell build steps loudly.
- Verification: Task 7 covers Rust tests, dist build, archive smoke test, and audit gates.
- Deferred scope remains deferred: no GitHub releases, macOS, Windows, bundled JRE, checksums, signing, Homebrew, third-party plugin publishing, or strict compatibility matrix.

## Software-Design Notes

- Mode: Build.
- Extensions: Rust, Java, shell-script.
- Reference path: `software-design` core sections 2-7 and 9, plus Rust, Java, and shell-script extensions.
- Layers: static and human-approved spec.
- Assimilation: `dediren-core` keeps orchestration and plugin discovery; first-party plugins remain executable process-boundary adapters; the Java helper stays owned by the ELK plugin; scripts own build-time archive assembly.
- Delegations: test confidence goes to `souroldgeezer-audit:test-quality-audit`; process boundary, command execution, and artifact posture go to `souroldgeezer-audit:devsecops-audit`.
- Limits: this plan does not design release automation, package manager installs, cross-platform archives, or plugin marketplace negotiation.
