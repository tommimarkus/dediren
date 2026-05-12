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
    let mut guard = built
        .lock()
        .expect("binary build registry should not be poisoned");
    if guard.contains(&key) {
        return target_binary(binary);
    }

    let status = Command::new("cargo")
        .current_dir(workspace_root())
        .args(["build", "--locked", "-p", package, "--bin", binary])
        .status()
        .expect("cargo build should start for test binary dependency");
    assert!(status.success(), "{package}:{binary} should build");

    guard.insert(key);
    target_binary(binary)
}

#[allow(dead_code)]
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
