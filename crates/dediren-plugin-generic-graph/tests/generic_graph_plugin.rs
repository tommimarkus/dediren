use assert_cmd::Command;
use predicates::prelude::*;
use std::path::PathBuf;

#[test]
fn generic_graph_projects_basic_view() {
    let input =
        std::fs::read_to_string(workspace_file("fixtures/source/valid-basic.json")).unwrap();
    let mut cmd = Command::cargo_bin("dediren-plugin-generic-graph").unwrap();
    cmd.args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(input);
    cmd.assert()
        .success()
        .stdout(predicate::str::contains(
            "\"layout_request_schema_version\"",
        ))
        .stdout(predicate::str::contains("\"view_id\":\"main\""));
}

fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}
