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
fn build_dist_serializes_parallel_invocations() {
    let repo = FakeDistRepo::new();
    let first = repo.run_build_dist();
    repo.wait_for_helper_build();
    let second = repo.run_build_dist();
    repo.release_helper_build();

    let first = first.wait_with_output().unwrap();
    let second = second.wait_with_output().unwrap();

    assert!(
        first.status.success() && second.status.success(),
        "parallel build-dist runs should both succeed\nfirst status: {:?}\nfirst stdout:\n{}\nfirst stderr:\n{}\nsecond status: {:?}\nsecond stdout:\n{}\nsecond stderr:\n{}",
        first.status.code(),
        String::from_utf8_lossy(&first.stdout),
        String::from_utf8_lossy(&first.stderr),
        second.status.code(),
        String::from_utf8_lossy(&second.stdout),
        String::from_utf8_lossy(&second.stderr),
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

    fn run_build_dist(&self) -> std::process::Child {
        let path = format!(
            "{}:{}",
            self.stub_bin.display(),
            std::env::var("PATH").unwrap_or_default()
        );
        Command::new(self.root.path().join("scripts/build-dist.sh"))
            .current_dir(self.root.path())
            .env("PATH", path)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap()
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

    fn write_tree(&self) {
        fs::create_dir_all(self.root.path().join("scripts")).unwrap();
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

        fs::copy(
            workspace_root().join("scripts/build-dist.sh"),
            self.root.path().join("scripts/build-dist.sh"),
        )
        .unwrap();
        make_executable(&self.root.path().join("scripts/build-dist.sh"));

        fs::write(self.root.path().join("Cargo.toml"), "version = \"0.1.0\"\n").unwrap();
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
            fs::write(
                self.root
                    .path()
                    .join("target/x86_64-unknown-linux-gnu/release")
                    .join(binary),
                "#!/usr/bin/env bash\n",
            )
            .unwrap();
        }

        self.write_executable(
            "stub-bin/cargo",
            r#"#!/usr/bin/env bash
exit 0
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
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).unwrap();
        }
        fs::write(&path, content).unwrap();
        make_executable(&path);
    }
}

#[cfg(unix)]
fn make_executable(path: &Path) {
    let mut permissions = fs::metadata(path).unwrap().permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(path, permissions).unwrap();
}

#[cfg(unix)]
fn workspace_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}
