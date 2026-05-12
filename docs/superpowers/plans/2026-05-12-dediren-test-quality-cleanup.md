# Dediren Test Quality Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strengthen the first test cleanup slice by replacing weak CLI/string assertions with parsed contract assertions and by removing repeated recursive Cargo builds from integration-test helpers.

**Architecture:** Keep the product behavior unchanged. Add test-only support modules that express the public JSON envelope, diagnostic, SVG, and plugin-binary expectations once, then migrate the highest-noise tests to those helpers. Treat broad pipeline tests as smoke coverage and narrow CLI/plugin tests as the authoritative proof for command envelopes and diagnostics.

**Tech Stack:** Rust 1.93, Cargo workspace, `assert_cmd`, `assert_fs`, `serde_json`, `roxmltree`, first-party process-boundary plugins, existing `docs/superpowers` audit gates.

---

## Scope

This plan covers the first cleanup slice only:

- Convert CLI and plugin compatibility tests from substring-first assertions to parsed JSON envelope assertions.
- Centralize test binary lookup/build setup so one test process does not run `cargo build` repeatedly in parallel.
- Keep existing public CLI, plugin, schema, fixture, and README behavior unchanged.
- Keep SVG renderer mega-file splitting, ArchiMate rule oracle expansion, real ELK lane clarification, and full gap-matrix work for follow-up slices.

## Audit Gates

Run these before calling implementation complete:

```bash
cargo fmt --all -- --check
cargo test -p dediren --test cli_validate --locked
cargo test -p dediren --test plugin_compat --locked
cargo test -p dediren --test cli_project --locked
cargo test -p dediren --test cli_layout --locked
cargo test -p dediren --test cli_export --locked
cargo test -p dediren --test cli_render --locked
cargo test -p dediren-core --test plugin_runtime --locked
cargo test -p dediren-core --test commands --locked
cargo test --workspace --locked
git diff --check
```

Also perform a `souroldgeezer-audit:test-quality-audit` quick review over changed test files. A `devsecops-audit` quick review is not required for this slice unless implementation changes shell scripts, plugin process logic, environment allowlists, distribution artifacts, or runtime dependency behavior.

## File Structure

- Create: `crates/dediren-cli/tests/common/mod.rs`
  - Test-only helpers for workspace paths, one-time plugin binary prebuild, command envelope parsing, diagnostic assertions, SVG parsing, and render artifact paths.
- Modify: `crates/dediren-cli/tests/cli_validate.rs`
  - Use parsed envelope assertions for validate success and failure diagnostics.
- Modify: `crates/dediren-cli/tests/plugin_compat.rs`
  - Use parsed envelope assertions for unknown-plugin and preserved plugin-error behavior.
- Modify: `crates/dediren-cli/tests/cli_project.rs`
  - Use parsed layout-request data assertions instead of stdout substring checks.
- Modify: `crates/dediren-cli/tests/cli_layout.rs`
  - Use shared plugin setup and parsed layout-result / quality envelope assertions.
- Modify: `crates/dediren-cli/tests/cli_export.rs`
  - Use parsed export-result and diagnostic assertions.
- Modify: `crates/dediren-cli/tests/cli_render.rs`
  - Use shared SVG helpers and parsed render-result assertions.
- Create: `crates/dediren-core/tests/common/mod.rs`
  - Test-only helper for one-time workspace binary builds.
- Modify: `crates/dediren-core/tests/plugin_runtime.rs`
  - Replace repeated `cargo build -p dediren-plugin-runtime-testbed` calls with a shared `OnceLock` helper.
- Modify: `crates/dediren-core/tests/commands.rs`
  - Replace repeated `cargo build -p dediren-plugin-elk-layout` calls with the shared helper.

---

### Task 1: Add CLI Test Contract Helpers

**Files:**
- Create: `crates/dediren-cli/tests/common/mod.rs`

- [ ] **Step 1: Create the helper module**

Add this file:

```rust
use assert_cmd::Command;
use roxmltree::Document;
use serde_json::Value;
use std::path::PathBuf;
use std::process::Command as StdCommand;
use std::sync::OnceLock;

static PLUGIN_BINARIES: OnceLock<()> = OnceLock::new();

pub fn workspace_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

pub fn workspace_file(path: &str) -> PathBuf {
    workspace_root().join(path)
}

pub fn dediren_command() -> Command {
    let mut cmd = Command::cargo_bin("dediren").expect("dediren binary should be built by Cargo");
    cmd.current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"));
    cmd
}

pub fn ensure_plugin_binaries() {
    PLUGIN_BINARIES.get_or_init(|| {
        let status = StdCommand::new("cargo")
            .current_dir(workspace_root())
            .args([
                "build",
                "-p",
                "dediren-plugin-generic-graph",
                "-p",
                "dediren-plugin-elk-layout",
                "-p",
                "dediren-plugin-svg-render",
                "-p",
                "dediren-plugin-archimate-oef-export",
            ])
            .status()
            .expect("cargo build should start for first-party plugin binaries");
        assert!(status.success(), "first-party plugin binaries should build");
    });
}

pub fn plugin_binary(binary: &str) -> PathBuf {
    ensure_plugin_binaries();
    workspace_root()
        .join("target/debug")
        .join(if cfg!(windows) {
            format!("{binary}.exe")
        } else {
            binary.to_string()
        })
}

pub fn stdout_json(output: &[u8]) -> Value {
    serde_json::from_slice(output).expect("stdout should be a JSON command envelope")
}

pub fn ok_data(output: &[u8]) -> Value {
    let envelope = stdout_json(output);
    assert_eq!(envelope["status"], "ok", "command should return ok envelope");
    assert!(
        envelope["diagnostics"]
            .as_array()
            .expect("diagnostics should be an array")
            .is_empty(),
        "ok envelope should not carry diagnostics"
    );
    envelope["data"].clone()
}

pub fn error_codes(output: &[u8]) -> Vec<String> {
    let envelope = stdout_json(output);
    assert_eq!(envelope["status"], "error", "command should return error envelope");
    envelope["diagnostics"]
        .as_array()
        .expect("diagnostics should be an array")
        .iter()
        .map(|diagnostic| {
            diagnostic["code"]
                .as_str()
                .expect("diagnostic code should be a string")
                .to_string()
        })
        .collect()
}

pub fn assert_error_code(output: &[u8], expected_code: &str) {
    let codes = error_codes(output);
    assert!(
        codes.iter().any(|code| code == expected_code),
        "expected diagnostic code {expected_code}, got {codes:?}"
    );
}

pub fn svg_doc(content: &str) -> Document<'_> {
    Document::parse(content).expect("render result content should be valid SVG XML")
}

pub fn semantic_group<'a, 'input>(
    doc: &'a Document<'input>,
    data_attr: &str,
    id: &str,
) -> roxmltree::Node<'a, 'input> {
    doc.descendants()
        .find(|node| node.has_tag_name("g") && node.attribute(data_attr) == Some(id))
        .unwrap_or_else(|| panic!("expected SVG to contain <g {data_attr}=\"{id}\">"))
}

pub fn child_element<'a, 'input>(
    node: roxmltree::Node<'a, 'input>,
    tag_name: &str,
) -> roxmltree::Node<'a, 'input> {
    node.children()
        .find(|child| child.has_tag_name(tag_name))
        .unwrap_or_else(|| {
            panic!(
                "expected <{}> to contain <{}>",
                node.tag_name().name(),
                tag_name
            )
        })
}

pub fn child_group_with_attr<'a, 'input>(
    parent: roxmltree::Node<'a, 'input>,
    attr_name: &str,
    attr_value: &str,
) -> Option<roxmltree::Node<'a, 'input>> {
    parent
        .children()
        .find(|child| child.has_tag_name("g") && child.attribute(attr_name) == Some(attr_value))
}

pub fn write_render_artifact(group: &str, test_name: &str, content: &str) -> PathBuf {
    let path = workspace_file(&format!(".test-output/renders/{group}/{test_name}.svg"));
    std::fs::create_dir_all(path.parent().expect("artifact path should have parent"))
        .expect("render artifact directory should be writable");
    std::fs::write(&path, content).expect("render artifact should be writable");
    path
}
```

- [ ] **Step 2: Run the affected test package**

Run:

```bash
cargo test -p dediren --test cli_validate --locked
```

Expected: PASS. The helper module is not imported yet, so this verifies the new file did not disturb the package.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-05-12-dediren-test-quality-cleanup.md crates/dediren-cli/tests/common/mod.rs
git commit -m "test: add CLI contract test helpers"
```

---

### Task 2: Convert Validate CLI Tests To Parsed Envelope Assertions

**Files:**
- Modify: `crates/dediren-cli/tests/cli_validate.rs`
- Test: `crates/dediren-cli/tests/cli_validate.rs`

- [ ] **Step 1: Rewrite the test file**

Replace the current file with:

```rust
mod common;

use common::{assert_error_code, ok_data, workspace_file};

#[test]
fn validate_accepts_valid_source_from_file() {
    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(workspace_file("fixtures/source/valid-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["model_schema_version"], "model.schema.v1");
}

#[test]
fn validate_rejects_authored_geometry() {
    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(workspace_file("fixtures/source/invalid-absolute-geometry.json"))
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_SCHEMA_INVALID");
}

#[test]
fn validate_rejects_duplicate_ids() {
    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(workspace_file("fixtures/source/invalid-duplicate-id.json"))
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_DUPLICATE_ID");
}

#[test]
fn validate_rejects_dangling_relationship_endpoint() {
    let output = common::dediren_command()
        .arg("validate")
        .arg("--input")
        .arg(workspace_file("fixtures/source/invalid-dangling-relationship.json"))
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_DANGLING_ENDPOINT");
}
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
cargo test -p dediren --test cli_validate --locked
```

Expected: PASS with 4 tests.

- [ ] **Step 3: Commit**

```bash
git add crates/dediren-cli/tests/cli_validate.rs
git commit -m "test: assert validate CLI envelopes"
```

---

### Task 3: Convert Plugin Compatibility Tests

**Files:**
- Modify: `crates/dediren-cli/tests/plugin_compat.rs`
- Test: `crates/dediren-cli/tests/plugin_compat.rs`

- [ ] **Step 1: Rewrite the test file**

Replace the current file with:

```rust
mod common;

use common::{assert_error_code, ok_data, plugin_binary, workspace_file};
use predicates::prelude::*;
use std::process::Command;

#[test]
fn first_party_plugins_report_capabilities() {
    for binary in [
        "dediren-plugin-generic-graph",
        "dediren-plugin-elk-layout",
        "dediren-plugin-svg-render",
        "dediren-plugin-archimate-oef-export",
    ] {
        let output = Command::new(plugin_binary(binary))
            .arg("capabilities")
            .output()
            .expect("plugin capabilities command should run");
        assert!(
            output.status.success(),
            "{binary} capabilities should succeed, stderr: {}",
            String::from_utf8_lossy(&output.stderr)
        );

        let data = ok_data(&output.stdout);
        assert_eq!(data["protocol_version"], "plugin.protocol.v1");
        assert!(
            data["capabilities"]
                .as_array()
                .expect("capabilities should be an array")
                .iter()
                .any(|capability| capability.as_str().is_some()),
            "{binary} should report at least one capability"
        );
    }
}

#[test]
fn unknown_plugin_failure_is_structured_by_cli() {
    let output = common::dediren_command()
        .arg("layout")
        .arg("--plugin")
        .arg("missing-plugin")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-request/basic.json"))
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_PLUGIN_UNKNOWN");
}

#[test]
fn plugin_error_envelope_is_preserved_by_cli() {
    let output = common::dediren_command()
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", plugin_binary("dediren-plugin-elk-layout"))
        .env_remove("DEDIREN_ELK_COMMAND")
        .env_remove("DEDIREN_ELK_RESULT_FIXTURE")
        .arg("layout")
        .arg("--plugin")
        .arg("elk-layout")
        .arg("--input")
        .arg(workspace_file("fixtures/layout-request/basic.json"))
        .assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_PLUGIN_ERROR").not())
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_ELK_RUNTIME_UNAVAILABLE");
}
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
cargo test -p dediren --test plugin_compat --locked
```

Expected: PASS with 3 tests. The output should no longer contain repeated `Blocking waiting for file lock on artifact directory` messages from parallel per-test helper builds.

- [ ] **Step 3: Commit**

```bash
git add crates/dediren-cli/tests/plugin_compat.rs
git commit -m "test: assert plugin compatibility envelopes"
```

---

### Task 4: Convert Project, Layout, Export, And Render CLI Tests

**Files:**
- Modify: `crates/dediren-cli/tests/cli_project.rs`
- Modify: `crates/dediren-cli/tests/cli_layout.rs`
- Modify: `crates/dediren-cli/tests/cli_export.rs`
- Modify: `crates/dediren-cli/tests/cli_render.rs`
- Test: `crates/dediren-cli/tests/cli_project.rs`
- Test: `crates/dediren-cli/tests/cli_layout.rs`
- Test: `crates/dediren-cli/tests/cli_export.rs`
- Test: `crates/dediren-cli/tests/cli_render.rs`

- [ ] **Step 1: Update `cli_project.rs`**

Use `common::dediren_command()`, remove the local `workspace_binary`, `workspace_file`, and `workspace_root` helpers, and assert parsed data:

```rust
mod common;

use common::{ok_data, plugin_binary, workspace_file};

#[test]
fn project_invokes_generic_graph_plugin() {
    let output = common::dediren_command()
        .env(
            "DEDIREN_PLUGIN_GENERIC_GRAPH",
            plugin_binary("dediren-plugin-generic-graph"),
        )
        .args([
            "project",
            "--target",
            "layout-request",
            "--plugin",
            "generic-graph",
            "--view",
            "main",
            "--input",
        ])
        .arg(workspace_file("fixtures/source/valid-basic.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = ok_data(&output);
    assert_eq!(data["layout_request_schema_version"], "layout-request.schema.v1");
    assert_eq!(data["view_id"], "main");
    assert_eq!(
        data["nodes"]
            .as_array()
            .expect("layout request nodes should be an array")
            .len(),
        2
    );
}
```

- [ ] **Step 2: Update `cli_layout.rs` assertions without changing real-helper ignores**

Use parsed data for fixture/external helper success and quality output. Keep `#[ignore = "requires SDKMAN Java helper build"]` on the real-helper tests.

For `validate_layout_reports_quality`, assert:

```rust
let output = common::dediren_command()
    .arg("validate-layout")
    .arg("--input")
    .arg(workspace_file("fixtures/layout-result/basic.json"))
    .assert()
    .success()
    .get_output()
    .stdout
    .clone();

let data = common::ok_data(&output);
assert_eq!(data["overlap_count"], 0);
assert_eq!(data["connector_through_node_count"], 0);
assert_eq!(data["status"], "ok");
```

- [ ] **Step 3: Update `cli_export.rs`**

Use parsed envelopes for both success and invalid endpoint failure. Assert `export_result_schema_version`, `artifact_kind`, and diagnostic code rather than only string containment.

- [ ] **Step 4: Update `cli_render.rs`**

Use `common::ok_data`, `common::svg_doc`, `common::semantic_group`, `common::child_element`, `common::child_group_with_attr`, and `common::write_render_artifact`. Keep the current semantic SVG assertions, but remove local duplicate helper functions after their call sites are migrated.

- [ ] **Step 5: Run focused CLI tests**

Run:

```bash
cargo test -p dediren --test cli_project --locked
cargo test -p dediren --test cli_layout --locked
cargo test -p dediren --test cli_export --locked
cargo test -p dediren --test cli_render --locked
```

Expected: PASS. Ignored Java-helper tests remain ignored.

- [ ] **Step 6: Commit**

```bash
git add crates/dediren-cli/tests/cli_project.rs crates/dediren-cli/tests/cli_layout.rs crates/dediren-cli/tests/cli_export.rs crates/dediren-cli/tests/cli_render.rs
git commit -m "test: assert CLI command contracts structurally"
```

---

### Task 5: Add Core Test Binary Build Helper

**Files:**
- Create: `crates/dediren-core/tests/common/mod.rs`
- Modify: `crates/dediren-core/tests/plugin_runtime.rs`
- Modify: `crates/dediren-core/tests/commands.rs`
- Test: `crates/dediren-core/tests/plugin_runtime.rs`
- Test: `crates/dediren-core/tests/commands.rs`

- [ ] **Step 1: Create the helper module**

Add:

```rust
use std::collections::BTreeSet;
use std::path::PathBuf;
use std::process::Command;
use std::sync::{Mutex, OnceLock};

static BUILT_PACKAGES: OnceLock<Mutex<BTreeSet<String>>> = OnceLock::new();

pub fn workspace_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

pub fn workspace_binary(package: &str, binary: &str) -> PathBuf {
    let key = format!("{package}:{binary}");
    let built = BUILT_PACKAGES.get_or_init(|| Mutex::new(BTreeSet::new()));
    {
        let guard = built.lock().expect("binary build registry should not be poisoned");
        if guard.contains(&key) {
            return target_binary(binary);
        }
    }

    let status = Command::new("cargo")
        .current_dir(workspace_root())
        .args(["build", "-p", package, "--bin", binary])
        .status()
        .expect("cargo build should start for test binary dependency");
    assert!(status.success(), "{package}:{binary} should build");

    let mut guard = built.lock().expect("binary build registry should not be poisoned");
    guard.insert(key);
    target_binary(binary)
}

pub fn workspace_file(path: &str) -> PathBuf {
    workspace_root().join(path)
}

fn target_binary(binary: &str) -> PathBuf {
    workspace_root()
        .join("target/debug")
        .join(if cfg!(windows) {
            format!("{binary}.exe")
        } else {
            binary.to_string()
        })
}
```

- [ ] **Step 2: Update `plugin_runtime.rs`**

Add `mod common;` at the top. Replace the local `workspace_root()` helper with `common::workspace_root()` where needed. Replace `testbed_binary()` with:

```rust
fn testbed_binary() -> PathBuf {
    common::workspace_binary(
        "dediren-plugin-runtime-testbed",
        "dediren-plugin-runtime-testbed",
    )
}
```

- [ ] **Step 3: Update `commands.rs`**

Add `mod common;` at the top. Replace local `workspace_root()` and `workspace_file()` helpers with `common::workspace_root()` and `common::workspace_file()`. Replace `workspace_binary(...)` with:

```rust
fn workspace_binary(package: &str, binary: &str) -> String {
    common::workspace_binary(package, binary)
        .display()
        .to_string()
}
```

- [ ] **Step 4: Run focused core tests**

Run:

```bash
cargo test -p dediren-core --test plugin_runtime --locked
cargo test -p dediren-core --test commands --locked
```

Expected: PASS. The `plugin_runtime` test binary should not start a separate Cargo build per test case.

- [ ] **Step 5: Commit**

```bash
git add crates/dediren-core/tests/common/mod.rs crates/dediren-core/tests/plugin_runtime.rs crates/dediren-core/tests/commands.rs
git commit -m "test: share core integration binary setup"
```

---

### Task 6: Final Verification And Handoff

**Files:**
- No additional code files.

- [ ] **Step 1: Run formatter check**

Run:

```bash
cargo fmt --all -- --check
```

Expected: PASS.

- [ ] **Step 2: Run focused suite**

Run:

```bash
cargo test -p dediren --test cli_validate --locked
cargo test -p dediren --test plugin_compat --locked
cargo test -p dediren --test cli_project --locked
cargo test -p dediren --test cli_layout --locked
cargo test -p dediren --test cli_export --locked
cargo test -p dediren --test cli_render --locked
cargo test -p dediren-core --test plugin_runtime --locked
cargo test -p dediren-core --test commands --locked
```

Expected: PASS. Java-helper tests remain ignored unless explicitly run with `-- --ignored` after the helper build.

- [ ] **Step 3: Run full workspace**

Run:

```bash
cargo test --workspace --locked
```

Expected: PASS with the same ignored real-helper lane as before.

- [ ] **Step 4: Run diff hygiene**

Run:

```bash
git diff --check
git status --short --branch
```

Expected: no whitespace errors. Status should show only intentional test-plan and test-file changes before final staging.

- [ ] **Step 5: Run test-quality audit gate**

Use `souroldgeezer-audit:test-quality-audit` in quick mode over changed test files. Required acceptance:

- no block findings;
- warning findings either fixed or explicitly accepted in the handoff;
- no new string-only assertion used as the primary proof for a JSON envelope command.

- [ ] **Step 6: Commit final verification note if needed**

If Task 6 produces only verification and no file changes, do not create a commit. If a small follow-up test cleanup is required, stage only the test files named by this plan that changed in the follow-up:

```bash
git add crates/dediren-cli/tests/common/mod.rs crates/dediren-cli/tests/cli_validate.rs crates/dediren-cli/tests/plugin_compat.rs crates/dediren-cli/tests/cli_project.rs crates/dediren-cli/tests/cli_layout.rs crates/dediren-cli/tests/cli_export.rs crates/dediren-cli/tests/cli_render.rs crates/dediren-core/tests/common/mod.rs crates/dediren-core/tests/plugin_runtime.rs crates/dediren-core/tests/commands.rs
git commit -m "test: finish first test quality cleanup slice"
```
