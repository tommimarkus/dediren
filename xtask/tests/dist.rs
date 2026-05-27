#[cfg(unix)]
use std::fs;
#[cfg(unix)]
use std::os::unix::fs::PermissionsExt;
#[cfg(unix)]
use std::path::{Path, PathBuf};
#[cfg(unix)]
use std::process::{Command, Stdio};
#[cfg(unix)]
use std::time::{Duration, Instant};

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
fn is_supported_host() -> bool {
    host_dist_target() != "unsupported-host"
}

#[cfg(unix)]
fn other_supported_target_for_host() -> Option<&'static str> {
    let host = host_dist_target();
    SUPPORTED_TEST_TARGETS
        .iter()
        .copied()
        .find(|target| *target != host)
}

#[cfg(unix)]
fn current_bundle_name() -> String {
    format!(
        "dediren-agent-bundle-{}-{}",
        env!("CARGO_PKG_VERSION"),
        host_dist_target()
    )
}

#[cfg(unix)]
#[test]
fn dist_build_serializes_parallel_invocations() {
    if !is_supported_host() {
        return;
    }

    let repo = FakeDistRepo::new();
    let first = repo.run_xtask(["dist", "build"]);
    repo.wait_for_helper_build();
    let second = repo.run_xtask(["dist", "build"]);
    repo.release_helper_build();

    let first = first.wait_with_output().unwrap();
    let second = second.wait_with_output().unwrap();

    assert!(
        first.status.success() && second.status.success(),
        "parallel dist builds should both succeed\nfirst status: {:?}\nfirst stdout:\n{}\nfirst stderr:\n{}\nsecond status: {:?}\nsecond stdout:\n{}\nsecond stderr:\n{}",
        first.status.code(),
        String::from_utf8_lossy(&first.stdout),
        String::from_utf8_lossy(&first.stderr),
        second.status.code(),
        String::from_utf8_lossy(&second.stdout),
        String::from_utf8_lossy(&second.stderr),
    );
}

#[cfg(unix)]
#[test]
fn dist_build_accepts_explicit_host_target() {
    if !is_supported_host() {
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
        "dist build should accept the explicit host target\nstatus: {:?}\nstdout:\n{}\nstderr:\n{}",
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
        "dist build should create the host-targeted archive"
    );
}

#[cfg(unix)]
#[test]
fn dist_build_explicit_target_wins_over_env_fallback() {
    let host_target = host_dist_target();
    if !is_supported_host() {
        return;
    }
    let Some(env_target) = other_supported_target_for_host() else {
        return;
    };

    let repo = FakeDistRepo::new();
    repo.release_helper_build();
    let output = repo
        .xtask_command(["dist", "build", "--target", host_target])
        .env("DEDIREN_DIST_TARGET", env_target)
        .output()
        .unwrap();

    assert!(
        output.status.success(),
        "explicit dist target should win over DEDIREN_DIST_TARGET\nstatus: {:?}\nstdout:\n{}\nstderr:\n{}",
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
        "dist build should create the explicit host-targeted archive"
    );
}

#[cfg(unix)]
#[test]
fn dist_build_rejects_unsupported_target() {
    let repo = FakeDistRepo::new();
    repo.release_helper_build();
    let output = repo
        .xtask_command(["dist", "build", "--target", "riscv64gc-unknown-linux-gnu"])
        .output()
        .unwrap();

    assert!(
        !output.status.success(),
        "dist build should reject unsupported targets\nstdout:\n{}\nstderr:\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(
        String::from_utf8_lossy(&output.stderr).contains("unsupported distribution target"),
        "dist build should explain unsupported targets\nstderr:\n{}",
        String::from_utf8_lossy(&output.stderr),
    );
}

#[cfg(unix)]
#[test]
fn dist_build_rejects_supported_target_for_wrong_host() {
    let Some(other_target) = other_supported_target_for_host() else {
        return;
    };

    let repo = FakeDistRepo::new();
    repo.release_helper_build();
    let output = repo
        .xtask_command(["dist", "build", "--target", other_target])
        .output()
        .unwrap();

    assert!(
        !output.status.success(),
        "dist build should reject a supported target on the wrong host\nstdout:\n{}\nstderr:\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(
        String::from_utf8_lossy(&output.stderr).contains("must be built on"),
        "dist build should explain host-target mismatches\nstderr:\n{}",
        String::from_utf8_lossy(&output.stderr),
    );
}

#[cfg(unix)]
#[test]
fn dist_build_prunes_stale_bundle_artifacts() {
    if !is_supported_host() {
        return;
    }

    let repo = FakeDistRepo::new();
    let stale_dir = repo
        .root
        .path()
        .join("dist/dediren-agent-bundle-0.0.1-x86_64-unknown-linux-gnu");
    let stale_archive = repo
        .root
        .path()
        .join("dist/dediren-agent-bundle-0.0.1-x86_64-unknown-linux-gnu.tar.gz");
    let unrelated = repo.root.path().join("dist/keep-me.txt");
    fs::create_dir_all(&stale_dir).unwrap();
    fs::write(&stale_archive, "stale archive").unwrap();
    fs::write(&unrelated, "unrelated").unwrap();

    repo.release_helper_build();
    let output = repo.xtask_command(["dist", "build"]).output().unwrap();

    assert!(
        output.status.success(),
        "dist build should pass\nstatus: {:?}\nstdout:\n{}\nstderr:\n{}",
        output.status.code(),
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(
        !stale_dir.exists(),
        "dist build should remove stale bundle directories"
    );
    assert!(
        !stale_archive.exists(),
        "dist build should remove stale bundle archives"
    );
    assert!(
        unrelated.exists(),
        "dist build should leave unrelated dist files alone"
    );
}

#[cfg(unix)]
#[test]
fn dist_build_includes_agent_usage_docs() {
    if !is_supported_host() {
        return;
    }

    let repo = FakeDistRepo::new();
    repo.release_helper_build();
    let output = repo.xtask_command(["dist", "build"]).output().unwrap();

    assert!(
        output.status.success(),
        "dist build should pass\nstatus: {:?}\nstdout:\n{}\nstderr:\n{}",
        output.status.code(),
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );

    let bundle = repo.root.path().join("dist").join(current_bundle_name());
    let guide = bundle.join("docs/agent-usage.md");
    assert!(
        guide.exists(),
        "dist build should include docs/agent-usage.md"
    );
    let metadata: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(bundle.join("bundle.json")).unwrap()).unwrap();
    assert_eq!(
        metadata["docs_dir"].as_str(),
        Some("docs"),
        "bundle metadata should advertise docs_dir"
    );
}

#[cfg(unix)]
#[test]
fn dist_build_includes_license_notice() {
    if !is_supported_host() {
        return;
    }

    let repo = FakeDistRepo::new();
    repo.release_helper_build();
    let output = repo.xtask_command(["dist", "build"]).output().unwrap();

    assert!(
        output.status.success(),
        "dist build should pass\nstatus: {:?}\nstdout:\n{}\nstderr:\n{}",
        output.status.code(),
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );

    let bundle_name = current_bundle_name();
    let bundle = repo.root.path().join("dist").join(&bundle_name);
    let source_license = fs::read_to_string(repo.root.path().join("LICENSE")).unwrap();
    let bundled_license_path = bundle.join("LICENSE");
    assert!(
        bundled_license_path.exists(),
        "dist build should include LICENSE at the bundle root"
    );
    let bundled_license = fs::read_to_string(bundled_license_path).unwrap();
    assert_eq!(
        bundled_license, source_license,
        "dist build should copy the root LICENSE into the bundle root"
    );

    let archive = repo
        .root
        .path()
        .join("dist")
        .join(format!("{bundle_name}.tar.gz"));
    let archive_listing = Command::new("tar")
        .arg("-tzf")
        .arg(&archive)
        .output()
        .unwrap();
    assert!(
        archive_listing.status.success(),
        "test tar listing should pass\nstatus: {:?}\nstderr:\n{}",
        archive_listing.status.code(),
        String::from_utf8_lossy(&archive_listing.stderr),
    );
    assert!(
        String::from_utf8_lossy(&archive_listing.stdout)
            .lines()
            .any(|path| path == format!("{bundle_name}/LICENSE")),
        "dist archive should include the bundle root LICENSE\nstdout:\n{}",
        String::from_utf8_lossy(&archive_listing.stdout),
    );
}

#[cfg(unix)]
#[test]
fn dist_build_includes_third_party_notices() {
    if !is_supported_host() {
        return;
    }

    let repo = FakeDistRepo::new();
    repo.release_helper_build();
    let output = repo.xtask_command(["dist", "build"]).output().unwrap();

    assert!(
        output.status.success(),
        "dist build should pass\nstatus: {:?}\nstdout:\n{}\nstderr:\n{}",
        output.status.code(),
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );

    let bundle_name = current_bundle_name();
    let bundle = repo.root.path().join("dist").join(&bundle_name);
    let notice_path = bundle.join("THIRD-PARTY-NOTICES.md");
    assert!(
        notice_path.exists(),
        "dist build should include third-party notices at the bundle root"
    );
    let notice = fs::read_to_string(&notice_path).unwrap();
    for expected in [
        "Rust Binary Dependencies",
        "Generated by cargo-about",
        "serde",
        "Dependency License Report for dediren-elk-layout-java",
        "org.eclipse.elk",
        "Eclipse Public License",
        "jackson-databind",
        "Apache-2.0",
        "checker-qual",
        "MIT License",
    ] {
        assert!(
            notice.contains(expected),
            "third-party notices should mention {expected:?}\n{notice}"
        );
    }
    assert!(
        !notice.contains("static source notice"),
        "dist build should copy the generated Gradle license report, not a static source notice"
    );

    let archive = repo
        .root
        .path()
        .join("dist")
        .join(format!("{bundle_name}.tar.gz"));
    let archive_listing = Command::new("tar")
        .arg("-tzf")
        .arg(&archive)
        .output()
        .unwrap();
    assert!(
        archive_listing.status.success(),
        "test tar listing should pass\nstatus: {:?}\nstderr:\n{}",
        archive_listing.status.code(),
        String::from_utf8_lossy(&archive_listing.stderr),
    );
    assert!(
        String::from_utf8_lossy(&archive_listing.stdout)
            .lines()
            .any(|path| path == format!("{bundle_name}/THIRD-PARTY-NOTICES.md")),
        "dist archive should include the bundle root third-party notices\nstdout:\n{}",
        String::from_utf8_lossy(&archive_listing.stdout),
    );
}

#[cfg(unix)]
#[test]
fn dist_build_includes_uml_profile_bundle_artifacts() {
    if !is_supported_host() {
        return;
    }

    let repo = FakeDistRepo::new();
    repo.release_helper_build();
    let output = repo.xtask_command(["dist", "build"]).output().unwrap();

    assert!(
        output.status.success(),
        "dist build should pass\nstatus: {:?}\nstdout:\n{}\nstderr:\n{}",
        output.status.code(),
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );

    let bundle = repo.root.path().join("dist").join(current_bundle_name());
    assert!(
        bundle.join("bin/dediren-plugin-uml-xmi-export").exists(),
        "dist build should include the UML/XMI export binary"
    );
    assert!(
        bundle.join("plugins/uml-xmi.manifest.json").exists(),
        "dist build should include the UML/XMI plugin manifest"
    );
    assert!(
        bundle.join("fixtures/source/valid-uml-basic.json").exists(),
        "dist build should include the UML source fixture"
    );
    assert!(
        bundle.join("fixtures/render-policy/uml-svg.json").exists(),
        "dist build should include the UML SVG render policy fixture"
    );
    assert!(
        bundle.join("fixtures/layout-result/uml-data.json").exists(),
        "dist build should include the UML data layout fixture"
    );
    assert!(
        bundle
            .join("fixtures/layout-result/uml-activity.json")
            .exists(),
        "dist build should include the UML activity layout fixture"
    );
    assert!(
        bundle
            .join("fixtures/render-metadata/uml-data.json")
            .exists(),
        "dist build should include the UML data render metadata fixture"
    );
    assert!(
        bundle
            .join("fixtures/render-metadata/uml-activity.json")
            .exists(),
        "dist build should include the UML activity render metadata fixture"
    );
    assert!(
        bundle
            .join("fixtures/source/valid-uml-complex.json")
            .exists(),
        "dist build should include the complex UML source fixture"
    );
    assert!(
        bundle
            .join("fixtures/layout-result/uml-complex-class.json")
            .exists(),
        "dist build should include the complex UML class layout fixture"
    );
    assert!(
        bundle
            .join("fixtures/render-metadata/uml-complex-class.json")
            .exists(),
        "dist build should include the complex UML class render metadata fixture"
    );

    let metadata: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(bundle.join("bundle.json")).unwrap()).unwrap();
    assert!(
        metadata["plugins"]
            .as_array()
            .unwrap()
            .iter()
            .any(|plugin| {
                plugin["id"] == "uml-xmi" && plugin["version"] == env!("CARGO_PKG_VERSION")
            }),
        "bundle metadata should advertise the UML/XMI plugin"
    );
}

#[cfg(unix)]
#[test]
fn dist_smoke_runs_bundle_pipeline_with_clean_environment() {
    let repo = FakeDistRepo::new();
    let archive = repo.write_smoke_archive();

    let output = repo
        .xtask_command(["dist", "smoke", archive.to_str().unwrap()])
        .env("DEDIREN_PLUGIN_DIRS", "ambient-plugin-dir")
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", "ambient-generic")
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", "ambient-elk")
        .env("DEDIREN_PLUGIN_SVG_RENDER", "ambient-svg")
        .env("DEDIREN_PLUGIN_ARCHIMATE_OEF", "ambient-oef")
        .env("DEDIREN_PLUGIN_UML_XMI", "ambient-xmi")
        .env("DEDIREN_ELK_COMMAND", "ambient-elk-command")
        .env("DEDIREN_ELK_RESULT_FIXTURE", "ambient-elk-fixture")
        .output()
        .unwrap();

    assert!(
        output.status.success(),
        "dist smoke should pass\nstatus: {:?}\nstdout:\n{}\nstderr:\n{}",
        output.status.code(),
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(
        String::from_utf8_lossy(&output.stdout).contains("distribution smoke test passed:"),
        "dist smoke should report the smoked archive\nstdout:\n{}",
        String::from_utf8_lossy(&output.stdout),
    );
}

#[cfg(unix)]
struct FakeDistRepo {
    root: assert_fs::TempDir,
    stub_bin: PathBuf,
}

#[cfg(unix)]
impl FakeDistRepo {
    fn new() -> Self {
        let root = assert_fs::TempDir::new().unwrap();
        let stub_bin = root.path().join("stub-bin");
        fs::create_dir_all(&stub_bin).unwrap();
        let repo = Self { root, stub_bin };
        repo.write_tree();
        repo
    }

    fn run_xtask<const N: usize>(&self, args: [&str; N]) -> std::process::Child {
        self.xtask_command(args)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap()
    }

    fn xtask_command<const N: usize>(&self, args: [&str; N]) -> Command {
        let path = format!(
            "{}:{}",
            self.stub_bin.display(),
            std::env::var("PATH").unwrap_or_default()
        );
        let mut command = Command::new(env!("CARGO_BIN_EXE_xtask"));
        command
            .current_dir(self.root.path())
            .env("PATH", path)
            .arg("--workspace-root")
            .arg(self.root.path());
        command.args(args);
        command
    }

    fn wait_for_helper_build(&self) {
        let marker = self.root.path().join(".cache/elk-helper-running");
        let deadline = Instant::now() + Duration::from_secs(5);
        while !marker.exists() {
            assert!(
                Instant::now() < deadline,
                "stub ELK helper build did not start"
            );
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn release_helper_build(&self) {
        fs::create_dir_all(self.root.path().join(".cache")).unwrap();
        fs::write(self.root.path().join(".cache/release-elk-helper"), "").unwrap();
    }

    fn write_smoke_archive(&self) -> PathBuf {
        let bundle_name = "dediren-agent-bundle-test-x86_64-unknown-linux-gnu";
        let bundle = self.root.path().join(bundle_name);
        fs::create_dir_all(bundle.join("bin")).unwrap();
        fs::create_dir_all(bundle.join("fixtures/source")).unwrap();
        fs::create_dir_all(bundle.join("fixtures/render-policy")).unwrap();
        fs::write(
            bundle.join("fixtures/source/valid-pipeline-rich.json"),
            "{}",
        )
        .unwrap();
        fs::write(bundle.join("fixtures/render-policy/rich-svg.json"), "{}").unwrap();
        self.write_executable_at(
            &bundle.join("bin/dediren"),
            r#"#!/usr/bin/env bash
set -euo pipefail
for name in DEDIREN_PLUGIN_DIRS DEDIREN_PLUGIN_GENERIC_GRAPH DEDIREN_PLUGIN_ELK_LAYOUT DEDIREN_PLUGIN_SVG_RENDER DEDIREN_PLUGIN_ARCHIMATE_OEF DEDIREN_PLUGIN_UML_XMI DEDIREN_ELK_COMMAND DEDIREN_ELK_RESULT_FIXTURE; do
  if [[ -n "${!name:-}" ]]; then
    echo "ambient environment leaked: $name" >&2
    exit 77
  fi
done
case "${1:-}" in
  --help)
    exit 0
    ;;
  project|layout)
    printf '{"status":"ok","data":{}}\n'
    ;;
  render)
    printf '{"status":"ok","data":{"artifact_kind":"svg","content":"<svg></svg>"}}\n'
    ;;
  *)
    echo "unexpected command: ${1:-}" >&2
    exit 78
    ;;
esac
"#,
        );

        let archive = self.root.path().join(format!("{bundle_name}.tar.gz"));
        let status = Command::new("tar")
            .arg("-czf")
            .arg(&archive)
            .arg(bundle_name)
            .current_dir(self.root.path())
            .status()
            .unwrap();
        assert!(status.success(), "test tar archive should be created");
        archive
    }

    fn write_tree(&self) {
        fs::create_dir_all(self.root.path().join("fixtures/plugins")).unwrap();
        fs::create_dir_all(self.root.path().join("fixtures/source")).unwrap();
        fs::create_dir_all(self.root.path().join("fixtures/layout-result")).unwrap();
        fs::create_dir_all(self.root.path().join("fixtures/render-metadata")).unwrap();
        fs::create_dir_all(self.root.path().join("fixtures/render-policy")).unwrap();
        fs::create_dir_all(self.root.path().join("docs")).unwrap();
        fs::create_dir_all(self.root.path().join("schemas")).unwrap();
        fs::create_dir_all(
            self.root
                .path()
                .join("crates/dediren-plugin-elk-layout/java/scripts"),
        )
        .unwrap();
        for target in SUPPORTED_TEST_TARGETS {
            fs::create_dir_all(self.root.path().join("target").join(target).join("release"))
                .unwrap();
        }

        for manifest in [
            "generic-graph.manifest.json",
            "elk-layout.manifest.json",
            "svg-render.manifest.json",
            "archimate-oef.manifest.json",
            "uml-xmi.manifest.json",
        ] {
            fs::write(
                self.root.path().join("fixtures/plugins").join(manifest),
                "{}",
            )
            .unwrap();
        }
        fs::write(
            self.root.path().join("LICENSE"),
            "MIT License\n\nFake Dediren license fixture.\n",
        )
        .unwrap();
        fs::write(
            self.root.path().join("THIRD-PARTY-NOTICES.md"),
            "# Third-Party Notices\n\nstatic source notice\n",
        )
        .unwrap();
        fs::write(self.root.path().join("fixtures/source/basic.json"), "{}").unwrap();
        fs::write(
            self.root
                .path()
                .join("fixtures/source/valid-uml-basic.json"),
            "{}",
        )
        .unwrap();
        fs::write(
            self.root
                .path()
                .join("fixtures/source/valid-uml-complex.json"),
            "{}",
        )
        .unwrap();
        fs::write(
            self.root.path().join("fixtures/render-policy/uml-svg.json"),
            "{}",
        )
        .unwrap();
        for path in [
            "fixtures/layout-result/uml-data.json",
            "fixtures/layout-result/uml-activity.json",
            "fixtures/layout-result/uml-complex-class.json",
            "fixtures/render-metadata/uml-data.json",
            "fixtures/render-metadata/uml-activity.json",
            "fixtures/render-metadata/uml-complex-class.json",
        ] {
            fs::write(self.root.path().join(path), "{}").unwrap();
        }
        fs::write(
            self.root.path().join("docs/agent-usage.md"),
            "# Dediren Agent Usage\n\nBundle-visible agent guide.\n",
        )
        .unwrap();
        fs::write(self.root.path().join("schemas/schema.json"), "{}").unwrap();

        for binary in [
            "dediren",
            "dediren-plugin-generic-graph",
            "dediren-plugin-elk-layout",
            "dediren-plugin-svg-render",
            "dediren-plugin-archimate-oef-export",
            "dediren-plugin-uml-xmi-export",
        ] {
            for target in SUPPORTED_TEST_TARGETS {
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

        self.write_executable(
            "stub-bin/cargo",
            r#"#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "about" && "${2:-}" == "generate" ]]; then
  output=
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --output-file)
        output="$2"
        shift 2
        ;;
      *)
        shift
        ;;
    esac
  done
  if [[ -z "$output" ]]; then
    echo "missing cargo-about --output-file" >&2
    exit 78
  fi
  mkdir -p "$(dirname "$output")"
  cat > "$output" <<'REPORT'
# Rust Binary Dependencies

Generated by cargo-about for Dediren's Rust workspace dependency graph.

## License Overview

- Apache License 2.0 (1)

## Apache License 2.0

Used by:
- serde 1.0.0

```text
Apache-2.0
```
REPORT
fi
exit 0
"#,
        );
        self.write_executable(
            "stub-bin/java",
            r#"#!/usr/bin/env bash
echo 'openjdk version "21.0.1"'
"#,
        );
        self.write_executable(
            "crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh",
            r#"#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
REPO_ROOT=$(cd -- "$PROJECT_DIR/../../.." && pwd -P)
MARKER="$REPO_ROOT/.cache/elk-helper-running"
RELEASE="$REPO_ROOT/.cache/release-elk-helper"
mkdir -p "$REPO_ROOT/.cache"
if ! mkdir "$MARKER"; then
  echo "ELK helper build overlapped" >&2
  exit 77
fi
trap 'rmdir "$MARKER"' EXIT
while [[ ! -f "$RELEASE" ]]; do
  sleep 0.01
done
mkdir -p "$PROJECT_DIR/build/install/dediren-elk-layout-java/bin"
printf '#!/usr/bin/env bash\n' > "$PROJECT_DIR/build/install/dediren-elk-layout-java/bin/dediren-elk-layout-java"
mkdir -p "$PROJECT_DIR/build/reports/dependency-license"
cat > "$PROJECT_DIR/build/reports/dependency-license/THIRD-PARTY-NOTICES.md" <<'REPORT'
Dependency License Report for dediren-elk-layout-java

ELK Java helper runtime

- org.eclipse.elk: Eclipse Public License
- jackson-databind: Apache-2.0
- checker-qual: MIT License
REPORT
"#,
        );
    }

    fn write_executable(&self, relative: &str, content: &str) {
        let path = self.root.path().join(relative);
        self.write_executable_at(&path, content);
    }

    fn write_executable_at(&self, path: &Path, content: &str) {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).unwrap();
        }
        fs::write(path, content).unwrap();
        make_executable(path);
    }
}

#[cfg(unix)]
fn make_executable(path: &Path) {
    let mut permissions = fs::metadata(path).unwrap().permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(path, permissions).unwrap();
}
