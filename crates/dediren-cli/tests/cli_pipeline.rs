use assert_cmd::Command;
use assert_fs::prelude::*;
use std::path::PathBuf;

#[test]
fn full_pipeline_produces_svg_and_oef() {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = temp.child("request.json");
    let result = temp.child("result.json");
    let svg = temp.child("diagram.svg");
    let generic_plugin = workspace_binary(
        "dediren-plugin-generic-graph",
        "dediren-plugin-generic-graph",
    );
    let elk_plugin = workspace_binary("dediren-plugin-elk-layout", "dediren-plugin-elk-layout");
    let svg_plugin = workspace_binary("dediren-plugin-svg-render", "dediren-plugin-svg-render");
    let oef_plugin = workspace_binary(
        "dediren-plugin-archimate-oef-export",
        "dediren-plugin-archimate-oef-export",
    );
    let elk_fixture = workspace_file("fixtures/layout-result/pipeline-rich.json");

    let project_output = Command::cargo_bin("dediren")
        .unwrap()
        .current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
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
        .arg(workspace_file("fixtures/source/valid-pipeline-rich.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    request.write_binary(&project_output).unwrap();
    let project_envelope: serde_json::Value = serde_json::from_slice(&project_output).unwrap();
    assert_eq!(
        project_envelope["data"]["nodes"]
            .as_array()
            .expect("projected nodes should be an array")
            .len(),
        6
    );
    assert_eq!(
        project_envelope["data"]["edges"]
            .as_array()
            .expect("projected edges should be an array")
            .len(),
        6
    );

    let layout_output = Command::cargo_bin("dediren")
        .unwrap()
        .current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", &elk_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .env("DEDIREN_ELK_RESULT_FIXTURE", elk_fixture)
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    result.write_binary(&layout_output).unwrap();

    Command::cargo_bin("dediren")
        .unwrap()
        .current_dir(workspace_root())
        .args(["validate-layout", "--input"])
        .arg(result.path())
        .assert()
        .success();

    let render_output = Command::cargo_bin("dediren")
        .unwrap()
        .current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_SVG_RENDER", &svg_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/default-svg.json"))
        .arg("--input")
        .arg(result.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let render_envelope: serde_json::Value = serde_json::from_slice(&render_output).unwrap();
    let svg_content = render_envelope["data"]["content"].as_str().unwrap();
    svg.write_str(svg_content).unwrap();
    write_render_artifact("full_pipeline_produces_svg_and_oef", svg_content);

    let svg_text = std::fs::read_to_string(svg.path()).unwrap();
    assert!(svg_text.contains("<svg"));
    assert!(svg_text.contains("Client"));
    assert!(svg_text.contains("Web App"));
    assert!(svg_text.contains("Orders API"));
    assert!(svg_text.contains("PostgreSQL"));
    assert!(svg_text.contains("Payments Provider"));
    assert!(svg_text.contains("Application Services"));
    assert!(svg_text.contains("data-dediren-group-id=\"application-services\""));
    assert!(svg_text.contains("submits order"));
    assert!(svg_text.contains("authorizes payment"));
    assert_reasonable_svg_aspect(&svg_text, 2.8);

    let export_output = Command::cargo_bin("dediren")
        .unwrap()
        .current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_ARCHIMATE_OEF", &oef_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["export", "--plugin", "archimate-oef", "--policy"])
        .arg(workspace_file("fixtures/export-policy/default-oef.json"))
        .arg("--source")
        .arg(workspace_file("fixtures/source/valid-archimate-oef.json"))
        .arg("--layout")
        .arg(workspace_file(
            "fixtures/layout-result/archimate-oef-basic.json",
        ))
        .output()
        .unwrap();
    assert!(export_output.status.success());

    let export_envelope: serde_json::Value = serde_json::from_slice(&export_output.stdout).unwrap();
    assert_eq!(export_envelope["status"], "ok");
    assert_eq!(
        export_envelope["data"]["artifact_kind"],
        "archimate-oef+xml"
    );
    assert!(export_envelope["data"]["content"]
        .as_str()
        .unwrap()
        .contains("xsi:type=\"Diagram\""));
}

#[test]
#[ignore = "requires built Java ELK helper"]
fn real_elk_pipeline_renders_rich_source() {
    let temp = assert_fs::TempDir::new().unwrap();
    let request = temp.child("rich-layout-request.json");
    let result = temp.child("rich-layout-result.json");
    let svg = temp.child("rich.svg");
    let generic_plugin = workspace_binary(
        "dediren-plugin-generic-graph",
        "dediren-plugin-generic-graph",
    );
    let elk_plugin = workspace_binary("dediren-plugin-elk-layout", "dediren-plugin-elk-layout");
    let svg_plugin = workspace_binary("dediren-plugin-svg-render", "dediren-plugin-svg-render");
    let elk_helper = workspace_file("crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh");

    let project_output = Command::cargo_bin("dediren")
        .unwrap()
        .current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_GENERIC_GRAPH", &generic_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
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
        .arg(workspace_file("fixtures/source/valid-pipeline-rich.json"))
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    request.write_binary(&project_output).unwrap();
    let project_envelope: serde_json::Value = serde_json::from_slice(&project_output).unwrap();
    assert_eq!(
        project_envelope["data"]["groups"]
            .as_array()
            .expect("projected groups should be an array")
            .len(),
        2
    );

    let layout_output = Command::cargo_bin("dediren")
        .unwrap()
        .current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_ELK_LAYOUT", &elk_plugin)
        .env("DEDIREN_ELK_COMMAND", elk_helper)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["layout", "--plugin", "elk-layout", "--input"])
        .arg(request.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    result.write_binary(&layout_output).unwrap();
    let layout_envelope: serde_json::Value = serde_json::from_slice(&layout_output).unwrap();
    assert_eq!(
        layout_envelope["data"]["nodes"]
            .as_array()
            .expect("laid out nodes should be an array")
            .len(),
        6
    );
    assert_eq!(
        layout_envelope["data"]["edges"]
            .as_array()
            .expect("laid out edges should be an array")
            .len(),
        6
    );
    assert_eq!(
        layout_envelope["data"]["groups"]
            .as_array()
            .expect("laid out groups should be an array")
            .len(),
        2
    );

    let render_output = Command::cargo_bin("dediren")
        .unwrap()
        .current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_SVG_RENDER", &svg_plugin)
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .args(["render", "--plugin", "svg-render", "--policy"])
        .arg(workspace_file("fixtures/render-policy/rich-svg.json"))
        .arg("--input")
        .arg(result.path())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let render_envelope: serde_json::Value = serde_json::from_slice(&render_output).unwrap();
    let content = render_envelope["data"]["content"].as_str().unwrap();
    assert!(content.contains("data-dediren-group-id=\"application-services\""));
    assert!(content.contains("data-dediren-group-id=\"external-dependencies\""));
    assert!(content.contains("viewBox=\"-"));
    assert_reasonable_svg_aspect(content, 3.2);
    svg.write_str(content).unwrap();
    write_render_artifact("real_elk_pipeline_renders_rich_source", content);
}

fn parse_view_box(content: &str) -> [f64; 4] {
    let doc = roxmltree::Document::parse(content).unwrap();
    let values: Vec<f64> = doc
        .root_element()
        .attribute("viewBox")
        .unwrap()
        .split_whitespace()
        .map(|value| value.parse::<f64>().unwrap())
        .collect();
    assert_eq!(values.len(), 4);
    [values[0], values[1], values[2], values[3]]
}

fn assert_reasonable_svg_aspect(content: &str, max_aspect: f64) {
    let view_box = parse_view_box(content);
    let aspect = view_box[2] / view_box[3];
    assert!(
        aspect <= max_aspect,
        "rendered SVG aspect ratio should be <= {max_aspect}, got {aspect} from viewBox {:?}",
        view_box
    );
}

fn write_render_artifact(test_name: &str, content: &str) -> PathBuf {
    let path = workspace_file(&format!(
        ".test-output/renders/cli-pipeline/{test_name}.svg"
    ));
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(&path, content).unwrap();
    path
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
