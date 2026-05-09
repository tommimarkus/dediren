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
