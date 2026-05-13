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
#[test]
fn dist_build_serializes_parallel_invocations() {
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
fn dist_smoke_runs_bundle_pipeline_with_clean_environment() {
    let repo = FakeDistRepo::new();
    let archive = repo.write_smoke_archive();

    let output = repo
        .xtask_command(["dist", "smoke", archive.to_str().unwrap()])
        .env("DEDIREN_PLUGIN_DIRS", "ambient-plugin-dir")
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", "ambient-generic")
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", "ambient-elk")
        .env("DEDIREN_PLUGIN_SVG_RENDER", "ambient-svg")
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
for name in DEDIREN_PLUGIN_DIRS DEDIREN_PLUGIN_GENERIC_GRAPH DEDIREN_PLUGIN_ELK_LAYOUT DEDIREN_PLUGIN_SVG_RENDER DEDIREN_ELK_COMMAND DEDIREN_ELK_RESULT_FIXTURE; do
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
        fs::create_dir_all(self.root.path().join("schemas")).unwrap();
        fs::create_dir_all(
            self.root
                .path()
                .join("crates/dediren-plugin-elk-layout/java/scripts"),
        )
        .unwrap();
        fs::create_dir_all(
            self.root
                .path()
                .join("target/x86_64-unknown-linux-gnu/release"),
        )
        .unwrap();

        fs::write(
            self.root
                .path()
                .join("fixtures/plugins/generic-graph.manifest.json"),
            "{}",
        )
        .unwrap();
        fs::write(self.root.path().join("fixtures/source/basic.json"), "{}").unwrap();
        fs::write(self.root.path().join("schemas/schema.json"), "{}").unwrap();

        for binary in [
            "dediren",
            "dediren-plugin-generic-graph",
            "dediren-plugin-elk-layout",
            "dediren-plugin-svg-render",
            "dediren-plugin-archimate-oef-export",
        ] {
            self.write_executable_at(
                &self
                    .root
                    .path()
                    .join("target/x86_64-unknown-linux-gnu/release")
                    .join(binary),
                "#!/usr/bin/env bash\n",
            );
        }

        self.write_executable(
            "stub-bin/cargo",
            r#"#!/usr/bin/env bash
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
