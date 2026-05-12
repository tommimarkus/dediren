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
    assert_eq!(
        data["layout_request_schema_version"],
        "layout-request.schema.v1"
    );
    assert_eq!(data["view_id"], "main");
    assert_eq!(
        data["nodes"]
            .as_array()
            .expect("layout request nodes should be an array")
            .len(),
        2
    );
}
