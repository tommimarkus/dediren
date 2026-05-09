use assert_cmd::Command;
use predicates::prelude::*;
use std::path::PathBuf;

#[test]
fn render_invokes_svg_plugin() {
    let plugin = workspace_binary("dediren-plugin-svg-render", "dediren-plugin-svg-render");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_SVG_RENDER", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/default-svg.json"))
        .arg("--input")
        .arg(workspace_file("fixtures/layout-result/basic.json"));
    let output = cmd
        .assert()
        .success()
        .stdout(predicate::str::contains("\"render_result_schema_version\""))
        .stdout(predicate::str::contains("<svg"))
        .stdout(predicate::str::contains("Client"))
        .get_output()
        .stdout
        .clone();
    let envelope: serde_json::Value = serde_json::from_slice(&output).unwrap();
    write_render_artifact(
        "render_invokes_svg_plugin",
        envelope["data"]["content"].as_str().unwrap(),
    );
}

#[test]
fn render_invokes_svg_plugin_with_rich_policy() {
    let plugin = workspace_binary("dediren-plugin-svg-render", "dediren-plugin-svg-render");
    let mut cmd = Command::cargo_bin("dediren").unwrap();
    cmd.env("DEDIREN_PLUGIN_SVG_RENDER", plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/rich-svg.json"))
        .args(["--input"])
        .arg(workspace_file("fixtures/layout-result/basic.json"));
    let output = cmd.assert().success().get_output().stdout.clone();
    let envelope: serde_json::Value = serde_json::from_slice(&output).unwrap();
    let content = envelope["data"]["content"].as_str().unwrap();
    let artifact = render_artifact_path("render_invokes_svg_plugin_with_rich_policy");
    let _ = std::fs::remove_file(&artifact);
    write_render_artifact("render_invokes_svg_plugin_with_rich_policy", content);
    assert!(envelope["data"]
        .get("render_result_schema_version")
        .is_some());
    let doc = svg_doc(content);

    let api_node = semantic_group(&doc, "data-dediren-node-id", "api");
    let api_rect = child_element(api_node, "rect");
    assert_eq!(api_rect.attribute("fill"), Some("#ecfeff"));

    let calls_edge = semantic_group(&doc, "data-dediren-edge-id", "client-calls-api");
    let calls_path = child_element(calls_edge, "path");
    assert_eq!(calls_path.attribute("stroke"), Some("#7c3aed"));
    assert!(artifact.exists());
}

fn svg_doc(content: &str) -> roxmltree::Document<'_> {
    roxmltree::Document::parse(content).unwrap()
}

fn semantic_group<'a, 'input>(
    doc: &'a roxmltree::Document<'input>,
    data_attr: &str,
    id: &str,
) -> roxmltree::Node<'a, 'input> {
    doc.descendants()
        .find(|node| node.has_tag_name("g") && node.attribute(data_attr) == Some(id))
        .unwrap_or_else(|| panic!("expected SVG to contain <g {data_attr}=\"{id}\">"))
}

fn child_element<'a, 'input>(
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

fn write_render_artifact(test_name: &str, content: &str) -> PathBuf {
    let path = render_artifact_path(test_name);
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(&path, content).unwrap();
    path
}

fn render_artifact_path(test_name: &str) -> PathBuf {
    workspace_file(&format!(".test-output/renders/cli-render/{test_name}.svg"))
}

fn workspace_binary(package: &str, binary: &str) -> PathBuf {
    let status = std::process::Command::new("cargo")
        .current_dir(workspace_root())
        .args(["build", "-p", package, "--bin", binary])
        .status()
        .unwrap();
    assert!(status.success());
    let executable = if cfg!(windows) {
        format!("{binary}.exe")
    } else {
        binary.to_string()
    };
    workspace_root().join("target/debug").join(executable)
}

fn workspace_file(path: &str) -> PathBuf {
    workspace_root().join(path)
}

fn workspace_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}
