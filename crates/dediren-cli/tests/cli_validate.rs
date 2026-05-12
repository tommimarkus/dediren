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
        .arg(workspace_file(
            "fixtures/source/invalid-absolute-geometry.json",
        ))
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
        .arg(workspace_file(
            "fixtures/source/invalid-dangling-relationship.json",
        ))
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    assert_error_code(&output, "DEDIREN_DANGLING_ENDPOINT");
}
