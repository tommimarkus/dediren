#![allow(dead_code)]

use assert_cmd::Command;
use roxmltree::Document;
use serde_json::Value;
use std::path::PathBuf;
use std::process::Command as StdCommand;
use std::sync::OnceLock;

static PLUGIN_BINARIES: OnceLock<()> = OnceLock::new();
static TEST_SCHEMA_DIR: OnceLock<PathBuf> = OnceLock::new();

const TEST_OEF_SCHEMA: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
           targetNamespace="http://www.opengroup.org/xsd/archimate/3.0/"
           xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
           elementFormDefault="qualified">
  <xs:element name="model">
    <xs:complexType>
      <xs:sequence>
        <xs:any minOccurs="0" maxOccurs="unbounded" processContents="skip"/>
      </xs:sequence>
      <xs:attribute name="identifier" type="xs:ID" use="required"/>
      <xs:anyAttribute processContents="skip"/>
    </xs:complexType>
  </xs:element>
</xs:schema>
"#;

const TEST_XMI_SCHEMA: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
            targetNamespace="http://www.omg.org/spec/XMI/20131001"
            xmlns="http://www.omg.org/spec/XMI/20131001"
            elementFormDefault="qualified">
  <xsd:element name="XMI">
    <xsd:complexType>
      <xsd:choice minOccurs="0" maxOccurs="unbounded">
        <xsd:any processContents="lax"/>
      </xsd:choice>
      <xsd:anyAttribute processContents="lax"/>
    </xsd:complexType>
  </xsd:element>
</xsd:schema>
"#;

pub fn workspace_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

pub fn workspace_file(path: &str) -> PathBuf {
    workspace_root().join(path)
}

pub fn dediren_command() -> Command {
    let mut cmd = Command::cargo_bin("dediren").expect("dediren binary should be built by Cargo");
    cmd.current_dir(workspace_root())
        .env("DEDIREN_PLUGIN_DIRS", workspace_file("fixtures/plugins"))
        .env("DEDIREN_OEF_SCHEMA_DIR", test_oef_schema_dir())
        .env("DEDIREN_XMI_SCHEMA_PATH", test_xmi_schema_path());
    cmd
}

fn test_schema_root() -> PathBuf {
    TEST_SCHEMA_DIR
        .get_or_init(|| {
            let root = std::env::temp_dir()
                .join(format!("dediren-cli-test-schemas-{}", std::process::id()));
            let oef_dir = root.join("opengroup").join("archimate").join("3.1");
            std::fs::create_dir_all(&oef_dir).expect("test OEF schema dir should be created");
            for file_name in [
                "archimate3_Model.xsd",
                "archimate3_View.xsd",
                "archimate3_Diagram.xsd",
            ] {
                std::fs::write(oef_dir.join(file_name), TEST_OEF_SCHEMA)
                    .expect("test OEF schema should be written");
            }
            let xmi_dir = root.join("omg").join("xmi").join("2.5.1");
            std::fs::create_dir_all(&xmi_dir).expect("test XMI schema dir should be created");
            std::fs::write(xmi_dir.join("XMI.xsd"), TEST_XMI_SCHEMA)
                .expect("test XMI schema should be written");
            root
        })
        .clone()
}

fn test_oef_schema_dir() -> PathBuf {
    test_schema_root()
        .join("opengroup")
        .join("archimate")
        .join("3.1")
}

fn test_xmi_schema_path() -> PathBuf {
    test_schema_root()
        .join("omg")
        .join("xmi")
        .join("2.5.1")
        .join("XMI.xsd")
}

pub fn ensure_plugin_binaries() {
    PLUGIN_BINARIES.get_or_init(|| {
        let status = StdCommand::new("cargo")
            .current_dir(workspace_root())
            .args([
                "build",
                "--locked",
                "-p",
                "dediren-plugin-generic-graph",
                "-p",
                "dediren-plugin-elk-layout",
                "-p",
                "dediren-plugin-svg-render",
                "-p",
                "dediren-plugin-archimate-oef-export",
                "-p",
                "dediren-plugin-uml-xmi-export",
            ])
            .status()
            .expect("cargo build should start for first-party plugin binaries");
        assert!(status.success(), "first-party plugin binaries should build");
    });
}

pub fn plugin_binary(binary: &str) -> PathBuf {
    ensure_plugin_binaries();
    workspace_root()
        .join("target/debug")
        .join(if cfg!(windows) {
            format!("{binary}.exe")
        } else {
            binary.to_string()
        })
}

pub fn stdout_json(output: &[u8]) -> Value {
    serde_json::from_slice(output).expect("stdout should be a JSON command envelope")
}

pub fn ok_data(output: &[u8]) -> Value {
    let envelope = stdout_json(output);
    assert_eq!(envelope["envelope_schema_version"], "envelope.schema.v1");
    assert_eq!(
        envelope["status"], "ok",
        "command should return ok envelope"
    );
    assert!(
        envelope["diagnostics"]
            .as_array()
            .expect("diagnostics should be an array")
            .is_empty(),
        "ok envelope should not carry diagnostics"
    );
    envelope["data"].clone()
}

pub fn error_codes(output: &[u8]) -> Vec<String> {
    let envelope = stdout_json(output);
    assert_eq!(envelope["envelope_schema_version"], "envelope.schema.v1");
    assert_eq!(
        envelope["status"], "error",
        "command should return error envelope"
    );
    envelope["diagnostics"]
        .as_array()
        .expect("diagnostics should be an array")
        .iter()
        .map(|diagnostic| {
            diagnostic["code"]
                .as_str()
                .expect("diagnostic code should be a string")
                .to_string()
        })
        .collect()
}

pub fn assert_error_code(output: &[u8], expected_code: &str) {
    let codes = error_codes(output);
    assert!(
        codes.iter().any(|code| code == expected_code),
        "expected diagnostic code {expected_code}, got {codes:?}"
    );
}

pub fn svg_doc(content: &str) -> Document<'_> {
    Document::parse(content).expect("render result content should be valid SVG XML")
}

pub fn semantic_group<'a, 'input>(
    doc: &'a Document<'input>,
    data_attr: &str,
    id: &str,
) -> roxmltree::Node<'a, 'input> {
    doc.descendants()
        .find(|node| node.has_tag_name("g") && node.attribute(data_attr) == Some(id))
        .unwrap_or_else(|| panic!("expected SVG to contain <g {data_attr}=\"{id}\">"))
}

pub fn child_element<'a, 'input>(
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

pub fn child_group_with_attr<'a, 'input>(
    parent: roxmltree::Node<'a, 'input>,
    attr_name: &str,
    attr_value: &str,
) -> Option<roxmltree::Node<'a, 'input>> {
    parent
        .children()
        .find(|child| child.has_tag_name("g") && child.attribute(attr_name) == Some(attr_value))
}

pub fn parse_svg_view_box(content: &str) -> [f64; 4] {
    let doc = svg_doc(content);
    let svg = doc.root_element();
    let view_box = svg.attribute("viewBox").expect("SVG should have viewBox");
    let values: Vec<f64> = view_box
        .split_whitespace()
        .map(|value| {
            value
                .parse::<f64>()
                .expect("viewBox should contain numbers")
        })
        .collect();
    assert_eq!(values.len(), 4, "viewBox should contain four numbers");
    [values[0], values[1], values[2], values[3]]
}

pub fn assert_reasonable_svg_aspect(content: &str, max_aspect: f64) {
    let [_x, _y, width, height] = parse_svg_view_box(content);
    assert!(width > 0.0, "viewBox width should be positive");
    assert!(height > 0.0, "viewBox height should be positive");
    let aspect = width.max(height) / width.min(height);
    assert!(
        aspect <= max_aspect,
        "expected SVG aspect ratio <= {max_aspect}, got {aspect} from {width}x{height}"
    );
}

pub fn svg_texts(doc: &Document<'_>) -> Vec<String> {
    doc.descendants()
        .filter(|node| node.has_tag_name("text"))
        .filter_map(|node| node.text())
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(ToOwned::to_owned)
        .collect()
}

pub fn assert_svg_texts_include(doc: &Document<'_>, expected: &[&str]) {
    let actual = svg_texts(doc);
    for expected_text in expected {
        assert!(
            actual.iter().any(|text| text == expected_text),
            "expected SVG text {expected_text:?}, got {actual:?}"
        );
    }
}

pub fn write_render_artifact(group: &str, test_name: &str, content: &str) -> PathBuf {
    let path = workspace_file(&format!(".test-output/renders/{group}/{test_name}.svg"));
    std::fs::create_dir_all(path.parent().expect("artifact path should have parent"))
        .expect("render artifact directory should be writable");
    std::fs::write(&path, content).expect("render artifact should be writable");
    path
}
