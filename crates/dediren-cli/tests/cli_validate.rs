use assert_cmd::Command;
use predicates::prelude::*;
use std::path::PathBuf;

#[test]
fn validate_accepts_valid_source_from_file() {
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.arg("validate")
        .arg("--input")
        .arg(workspace_file("fixtures/source/valid-basic.json"));
    cmd.assert()
        .success()
        .stdout(predicate::str::contains("\"status\":\"ok\""));
}

#[test]
fn validate_rejects_authored_geometry() {
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.arg("validate").arg("--input").arg(workspace_file(
        "fixtures/source/invalid-absolute-geometry.json",
    ));
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_SCHEMA_INVALID"));
}

#[test]
fn validate_rejects_duplicate_ids() {
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.arg("validate")
        .arg("--input")
        .arg(workspace_file("fixtures/source/invalid-duplicate-id.json"));
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_DUPLICATE_ID"));
}

#[test]
fn validate_rejects_dangling_relationship_endpoint() {
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.arg("validate").arg("--input").arg(workspace_file(
        "fixtures/source/invalid-dangling-relationship.json",
    ));
    cmd.assert()
        .failure()
        .stdout(predicate::str::contains("DEDIREN_DANGLING_ENDPOINT"));
}

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}
