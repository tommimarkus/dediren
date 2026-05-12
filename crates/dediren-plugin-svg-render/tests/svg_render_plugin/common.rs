use assert_cmd::Command;
use std::path::PathBuf;

pub fn render_content(input: serde_json::Value) -> String {
    render_ok_data(input)["content"]
        .as_str()
        .expect("render data should contain SVG content")
        .to_string()
}

pub fn render_success_envelope(input: serde_json::Value) -> serde_json::Value {
    let mut cmd = Command::cargo_bin("dediren-plugin-svg-render")
        .expect("svg render plugin binary should be built by Cargo");
    let output = cmd
        .arg("render")
        .write_stdin(input.to_string())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    serde_json::from_slice(&output).expect("render stdout should be JSON")
}

pub fn render_failure_envelope(input: serde_json::Value) -> serde_json::Value {
    let mut cmd = Command::cargo_bin("dediren-plugin-svg-render")
        .expect("svg render plugin binary should be built by Cargo");
    let output = cmd
        .arg("render")
        .write_stdin(input.to_string())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();
    serde_json::from_slice(&output).expect("render stdout should be JSON")
}

pub fn render_ok_data(input: serde_json::Value) -> serde_json::Value {
    let envelope = render_success_envelope(input);
    assert_eq!(envelope["envelope_schema_version"], "envelope.schema.v1");
    assert_eq!(envelope["status"], "ok", "render should return ok envelope");
    assert!(
        envelope["diagnostics"]
            .as_array()
            .expect("diagnostics should be an array")
            .is_empty(),
        "ok envelope should not include diagnostics"
    );
    envelope["data"].clone()
}

pub fn render_error(input: serde_json::Value, expected_code: &str) -> serde_json::Value {
    let envelope = render_failure_envelope(input);
    assert_eq!(envelope["envelope_schema_version"], "envelope.schema.v1");
    assert_eq!(
        envelope["status"], "error",
        "render should return error envelope"
    );
    let codes: Vec<&str> = envelope["diagnostics"]
        .as_array()
        .expect("diagnostics should be an array")
        .iter()
        .map(|diagnostic| {
            diagnostic["code"]
                .as_str()
                .expect("diagnostic code should be a string")
        })
        .collect();
    assert!(
        codes.iter().any(|code| *code == expected_code),
        "expected diagnostic code {expected_code}, got {codes:?}"
    );
    envelope
}

pub fn archimate_style_input() -> serde_json::Value {
    serde_json::json!({
        "layout_result": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/layout-result/archimate-oef-basic.json")).unwrap()
        ).unwrap(),
        "render_metadata": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-metadata/archimate-basic.json")).unwrap()
        ).unwrap(),
        "policy": serde_json::from_str::<serde_json::Value>(
            &std::fs::read_to_string(workspace_file("fixtures/render-policy/archimate-svg.json")).unwrap()
        ).unwrap()
    })
}

pub fn archimate_render_input(
    policy: serde_json::Value,
    nodes: serde_json::Value,
    edges: serde_json::Value,
    metadata_nodes: serde_json::Value,
    metadata_edges: serde_json::Value,
) -> serde_json::Value {
    serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "archimate-coverage",
            "nodes": nodes,
            "edges": edges,
            "groups": [],
            "warnings": []
        },
        "render_metadata": {
            "render_metadata_schema_version": "render-metadata.schema.v1",
            "semantic_profile": "archimate",
            "nodes": metadata_nodes,
            "edges": metadata_edges
        },
        "policy": policy
    })
}

pub fn styled_inline_input(
    groups: serde_json::Value,
    nodes: serde_json::Value,
    edges: serde_json::Value,
    style: serde_json::Value,
) -> serde_json::Value {
    serde_json::json!({
        "layout_result": {
            "layout_result_schema_version": "layout-result.schema.v1",
            "view_id": "inline-test",
            "nodes": nodes,
            "edges": edges,
            "groups": groups,
            "warnings": []
        },
        "policy": {
            "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
            "page": { "width": 640, "height": 360 },
            "margin": { "top": 16, "right": 16, "bottom": 16, "left": 16 },
            "style": style
        }
    })
}

pub fn svg_doc(content: &str) -> roxmltree::Document<'_> {
    roxmltree::Document::parse(content).unwrap()
}

pub fn semantic_group<'a, 'input>(
    doc: &'a roxmltree::Document<'input>,
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
) -> roxmltree::Node<'a, 'input> {
    parent
        .children()
        .find(|node| node.has_tag_name("g") && node.attribute(attr_name) == Some(attr_value))
        .unwrap_or_else(|| panic!("missing child group with {attr_name}={attr_value}"))
}

pub fn child_node_shape<'a, 'input>(
    node: roxmltree::Node<'a, 'input>,
) -> roxmltree::Node<'a, 'input> {
    node.children()
        .find(|child| child.attribute("data-dediren-node-shape").is_some())
        .unwrap_or_else(|| panic!("missing primary node shape"))
}

pub fn child_elements<'a, 'input>(
    parent: roxmltree::Node<'a, 'input>,
    name: &'static str,
) -> impl Iterator<Item = roxmltree::Node<'a, 'input>> {
    parent
        .children()
        .filter(move |node| node.has_tag_name(name))
}

pub fn expected_archimate_icon_kind(node_type: &str) -> &'static str {
    match node_type {
        "BusinessInterface" | "ApplicationInterface" | "TechnologyInterface" => "interface",
        "BusinessCollaboration" | "ApplicationCollaboration" | "TechnologyCollaboration" => {
            "collaboration"
        }
        "BusinessActor" => "actor",
        "BusinessRole" => "role",
        "BusinessService" | "ApplicationService" | "TechnologyService" => "service",
        "BusinessInteraction" | "ApplicationInteraction" | "TechnologyInteraction" => "interaction",
        "BusinessFunction" | "ApplicationFunction" | "TechnologyFunction" => "function",
        "BusinessProcess" | "ApplicationProcess" | "TechnologyProcess" => "process",
        "BusinessEvent" | "ApplicationEvent" | "TechnologyEvent" | "ImplementationEvent" => "event",
        "BusinessObject" | "DataObject" => "object",
        "ApplicationComponent" => "component",
        "Contract" => "contract",
        "Product" => "product",
        "Representation" => "representation",
        "Location" => "location",
        "Grouping" => "grouping",
        "Stakeholder" => "stakeholder",
        "Driver" => "driver",
        "Assessment" => "assessment",
        "Goal" => "goal",
        "Outcome" => "outcome",
        "Value" => "value",
        "Meaning" => "meaning",
        "Constraint" => "constraint",
        "Requirement" => "requirement",
        "Principle" => "principle",
        "CourseOfAction" => "course_of_action",
        "Resource" => "resource",
        "ValueStream" => "value_stream",
        "Capability" => "capability",
        "Plateau" => "plateau",
        "WorkPackage" => "work_package",
        "Deliverable" => "deliverable",
        "Gap" => "gap",
        "Artifact" => "artifact",
        "SystemSoftware" => "system_software",
        "Device" => "device",
        "Facility" => "facility",
        "Equipment" => "equipment",
        "Node" => "node",
        "Material" => "material",
        "CommunicationNetwork" => "network",
        "DistributionNetwork" => "distribution_network",
        "Path" => "path",
        other => panic!("missing expected icon kind for {other}"),
    }
}

pub fn expected_archimate_rectangular_node_shape(node_type: &str) -> &'static str {
    match node_type {
        "Stakeholder" | "Driver" | "Assessment" | "Goal" | "Outcome" | "Value" | "Meaning"
        | "Constraint" | "Requirement" | "Principle" => "archimate_cut_corner_rectangle",
        "WorkPackage"
        | "ImplementationEvent"
        | "CourseOfAction"
        | "ValueStream"
        | "Capability"
        | "BusinessService"
        | "BusinessFunction"
        | "BusinessProcess"
        | "BusinessEvent"
        | "ApplicationService"
        | "ApplicationFunction"
        | "ApplicationProcess"
        | "ApplicationEvent"
        | "TechnologyService"
        | "TechnologyFunction"
        | "TechnologyProcess"
        | "TechnologyEvent" => "archimate_rounded_rectangle",
        _ => "archimate_rectangle",
    }
}

pub fn assert_archimate_rectangular_node_shape(
    node_type: &str,
    node_shape: roxmltree::Node<'_, '_>,
) {
    match expected_archimate_rectangular_node_shape(node_type) {
        "archimate_cut_corner_rectangle" => {
            assert!(
                node_shape.has_tag_name("path"),
                "{node_type} should use the cut-corner motivation rectangle"
            );
        }
        "archimate_rounded_rectangle" => {
            assert!(
                node_shape.has_tag_name("rect"),
                "{node_type} should use a rounded rectangle"
            );
            assert_ne!(node_shape.attribute("rx"), Some("0"));
        }
        "archimate_rectangle" => {
            assert!(
                node_shape.has_tag_name("rect"),
                "{node_type} should use a sharp-corner rectangle"
            );
            assert_eq!(node_shape.attribute("rx"), Some("0"));
        }
        other => panic!("unexpected shape expectation {other}"),
    }
}

pub fn assert_archimate_icon_morphology(node_type: &str, decorator: roxmltree::Node<'_, '_>) {
    match node_type {
        "BusinessInterface" | "ApplicationInterface" | "TechnologyInterface" => {
            assert_eq!(
                child_elements(decorator, "ellipse").count(),
                1,
                "{node_type} interface icon should use a lollipop ellipse"
            );
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} interface icon should include a lollipop stem"
            );
        }
        "BusinessActor" => {
            assert_eq!(
                child_elements(decorator, "ellipse").count(),
                1,
                "{node_type} actor icon should use a head ellipse"
            );
            assert!(
                child_elements(decorator, "path").count() >= 1,
                "{node_type} actor icon should include body strokes"
            );
        }
        "BusinessInteraction" | "ApplicationInteraction" | "TechnologyInteraction" => {
            assert_eq!(
                child_elements(decorator, "ellipse").count(),
                0,
                "{node_type} interaction icon should not use one circular body"
            );
            assert_eq!(
                child_elements(decorator, "path")
                    .filter(
                        |path| path.attribute("data-dediren-icon-part") == Some("interaction-half")
                    )
                    .count(),
                2,
                "{node_type} interaction icon should use two open half-circle arcs"
            );
            assert!(
                child_elements(decorator, "path")
                    .all(|path| path.attribute("data-dediren-icon-part")
                        != Some("interaction-divider")),
                "{node_type} interaction icon should not use a center divider line"
            );
            assert_interaction_icon_uses_separate_open_half_circles(node_type, decorator);
        }
        "BusinessService" | "ApplicationService" | "TechnologyService" => {
            assert_eq!(
                child_elements(decorator, "rect").count(),
                1,
                "{node_type} service icon should use one rounded service capsule"
            );
        }
        "BusinessFunction" | "ApplicationFunction" | "TechnologyFunction" => {
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} function icon should use one bookmark path"
            );
            let bookmark = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("function-bookmark"));
            assert!(
                bookmark,
                "{node_type} function icon should expose the bookmark shape"
            );
            assert_function_icon_uses_bottom_notched_bookmark(node_type, decorator);
        }
        "BusinessProcess" | "ApplicationProcess" | "TechnologyProcess" => {
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} should use one process arrow path"
            );
            let process_arrow = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("process-arrow"));
            assert!(
                process_arrow,
                "{node_type} should use the ArchiMate process arrow shape"
            );
        }
        "BusinessEvent" | "ApplicationEvent" | "TechnologyEvent" | "ImplementationEvent" => {
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} should use one event path"
            );
            let event_pill = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("event-pill"));
            assert!(
                event_pill,
                "{node_type} should use the ArchiMate event pill shape"
            );
        }
        "ValueStream" => {
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} should use one value stream path"
            );
            let chevron = child_elements(decorator, "path").any(|path| {
                path.attribute("data-dediren-icon-part") == Some("value-stream-chevron")
            });
            assert!(
                chevron,
                "{node_type} should use the ArchiMate value stream chevron"
            );
            assert_value_stream_icon_has_left_notch(node_type, decorator);
        }
        "BusinessRole" => {
            assert_eq!(
                child_elements(decorator, "ellipse").count(),
                1,
                "{node_type} icon should use the side-cylinder end ellipse"
            );
            assert!(
                child_elements(decorator, "path")
                    .any(|path| path.attribute("data-dediren-icon-part") == Some("side-cylinder")),
                "{node_type} icon should use the right-facing side-cylinder body"
            );
            assert_side_cylinder_has_left_arc(node_type, decorator);
            assert_eq!(
                child_elements(decorator, "rect").count(),
                0,
                "{node_type} icon should not use a generic service pill"
            );
        }
        "BusinessCollaboration" | "ApplicationCollaboration" | "TechnologyCollaboration" => {
            assert_eq!(
                child_elements(decorator, "circle").count(),
                4,
                "{node_type} icon should use filled Venn circles plus outline overlays"
            );
            assert!(
                child_elements(decorator, "circle")
                    .all(|circle| circle.attribute("data-dediren-icon-part")
                        == Some("collaboration-circles")),
                "{node_type} icon should expose collaboration circle primitives"
            );
            assert_collaboration_icon_uses_side_by_side_overlap(node_type, decorator);
        }
        "Product" => {
            let tab = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("product-tab"));
            assert!(tab, "{node_type} icon should include a product tab");
        }
        "Contract" => {
            let contract = child_elements(decorator, "path").any(|path| {
                path.attribute("data-dediren-icon-part") == Some("contract-document-body")
            });
            assert!(
                contract,
                "{node_type} icon should use a business-object-like document body"
            );
            let lines = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("contract-lines"));
            assert!(
                lines,
                "{node_type} icon should include two contract line marks"
            );
            assert_contract_icon_uses_document_with_two_lines(node_type, decorator);
        }
        "Representation" => {
            let wavy = child_elements(decorator, "path").any(|path| {
                path.attribute("data-dediren-icon-part") == Some("wavy-representation")
            });
            assert!(
                wavy,
                "{node_type} icon should use a wavy representation shape"
            );
        }
        "BusinessObject" | "DataObject" => {
            let body = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("document-body"));
            assert!(
                body,
                "{node_type} icon should include an unfolded document body"
            );
            let header = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("document-header"));
            assert!(
                header,
                "{node_type} icon should include a document header line"
            );
        }
        "Artifact" => {
            let artifact = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("artifact-document"));
            assert!(
                artifact,
                "{node_type} icon should use the folded artifact document"
            );
            let header = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("document-header"));
            assert!(
                !header,
                "{node_type} icon should not include the generic document header"
            );
            assert_artifact_icon_uses_folded_document(node_type, decorator);
        }
        "Deliverable" => {
            let wavy_document = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("wavy-document"));
            assert!(
                wavy_document,
                "{node_type} icon should use the wavy deliverable document"
            );
        }
        "ApplicationComponent" => {
            assert_eq!(
                child_elements(decorator, "rect").count(),
                3,
                "{node_type} icon should use the component rectangle plus two side tabs"
            );
            assert_application_component_tabs_extend_from_body(node_type, decorator);
        }
        "Stakeholder" => {
            assert!(
                child_elements(decorator, "ellipse").count() >= 1,
                "{node_type} icon should use the right cylinder end ellipse"
            );
            assert!(
                child_elements(decorator, "path")
                    .any(|path| path.attribute("data-dediren-icon-part") == Some("side-cylinder")),
                "{node_type} icon should use a side-cylinder body"
            );
            assert_side_cylinder_has_left_arc(node_type, decorator);
        }
        "Assessment" => {
            assert_eq!(
                child_elements(decorator, "ellipse").count(),
                1,
                "{node_type} icon should use a magnifier lens"
            );
            let handle = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("assessment-handle"));
            assert!(handle, "{node_type} icon should include a magnifier handle");
        }
        "Goal" => {
            assert!(
                child_elements(decorator, "ellipse").count() >= 3,
                "{node_type} icon should use three concentric target rings"
            );
        }
        "Constraint" | "Requirement" => {
            let expected_part = if node_type == "Constraint" {
                "constraint-parallelogram"
            } else {
                "requirement-parallelogram"
            };
            assert!(
                child_elements(decorator, "path")
                    .any(|path| path.attribute("data-dediren-icon-part") == Some(expected_part)),
                "{node_type} icon should use its ArchiMate parallelogram variant"
            );
            if node_type == "Constraint" {
                assert!(
                    child_elements(decorator, "path")
                        .any(|path| path.attribute("data-dediren-icon-part")
                            == Some("constraint-left-line")),
                    "{node_type} icon should include the extra left-side line"
                );
                assert_constraint_icon_uses_inner_slanted_line(node_type, decorator);
            }
            assert_eq!(
                child_elements(decorator, "rect").count(),
                0,
                "{node_type} icon should not render as a rectangle"
            );
        }
        "Outcome" => {
            assert!(
                child_elements(decorator, "ellipse").count() >= 3,
                "{node_type} icon should use a target symbol"
            );
            let arrow = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("target-arrow"));
            assert!(arrow, "{node_type} icon should include the target arrow");
            assert_outcome_arrow_points_out_from_target_center(node_type, decorator);
        }
        "CourseOfAction" => {
            assert!(
                child_elements(decorator, "ellipse").count() >= 3,
                "{node_type} icon should use a target symbol"
            );
            assert!(
                child_elements(decorator, "path").any(|path| {
                    path.attribute("data-dediren-icon-part") == Some("course-of-action-handle")
                }),
                "{node_type} icon should include the target handle"
            );
            assert!(
                !child_elements(decorator, "path")
                    .any(|path| path.attribute("data-dediren-icon-part") == Some("target-arrow")),
                "{node_type} icon should not use an arrowhead"
            );
            assert_course_of_action_handle_extends_from_target_to_bottom_left(node_type, decorator);
        }
        "Driver" => {
            assert!(
                child_elements(decorator, "ellipse").count() >= 2,
                "{node_type} icon should use a circled driver symbol with a center mark"
            );
            let spokes = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("driver-spokes"));
            assert!(spokes, "{node_type} icon should include radial spokes");
        }
        "Capability" => {
            assert!(
                child_elements(decorator, "rect").count() >= 6,
                "{node_type} icon should use a multi-square stair grid"
            );
            assert!(
                child_elements(decorator, "rect").all(|rect| rect
                    .attribute("data-dediren-icon-part")
                    == Some("capability-step")),
                "{node_type} icon should expose each capability stair block"
            );
            assert_capability_icon_uses_square_stair_grid(node_type, decorator);
        }
        "Resource" => {
            assert_eq!(
                child_elements(decorator, "rect").count(),
                2,
                "{node_type} icon should use a rounded capsule and a small right tab"
            );
            let bars = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("resource-bars"));
            let handle = child_elements(decorator, "rect")
                .any(|rect| rect.attribute("data-dediren-icon-part") == Some("resource-tab"));
            assert!(bars, "{node_type} icon should include resource bars");
            assert!(
                handle,
                "{node_type} icon should include the right resource tab"
            );
            assert_resource_icon_uses_horizontal_capsule(node_type, decorator);
        }
        "WorkPackage" => {
            let corner = child_elements(decorator, "path").any(|path| {
                path.attribute("data-dediren-icon-part") == Some("work-package-loop-arrow")
            });
            assert!(
                corner,
                "{node_type} icon should include the circular loop arrow"
            );
        }
        "Gap" => {
            assert_eq!(
                child_elements(decorator, "ellipse").count(),
                1,
                "{node_type} icon should render a single gap marker ellipse"
            );
            let gap_marker = child_element(decorator, "ellipse");
            let gap_lines = child_elements(decorator, "path")
                .find(|path| path.attribute("data-dediren-icon-part") == Some("gap-lines"))
                .unwrap_or_else(|| panic!("{node_type} icon should include gap guide lines"));
            assert_gap_marker_is_centered_on_guide_lines(gap_marker, gap_lines);
        }
        "SystemSoftware" => {
            assert!(
                child_elements(decorator, "ellipse").count() >= 2,
                "{node_type} icon should use overlapping software ellipses"
            );
            assert!(
                child_elements(decorator, "ellipse")
                    .all(|ellipse| ellipse.attribute("data-dediren-icon-part")
                        == Some("system-software-disks")),
                "{node_type} icon should expose system software disk primitives"
            );
        }
        "Node" => {
            assert_eq!(
                child_elements(decorator, "rect").count(),
                1,
                "{node_type} icon should include one 3D node face"
            );
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} icon should include 3D node edges"
            );
            let node_edges = child_element(decorator, "path");
            assert_eq!(
                node_edges.attribute("data-dediren-icon-part"),
                Some("node-3d-edges"),
                "{node_type} icon should expose the 3D edge path"
            );
            assert!(
                node_edges
                    .attribute("d")
                    .unwrap_or("")
                    .matches(" L ")
                    .count()
                    >= 6,
                "{node_type} icon should include the right-side rear vertical edge"
            );
        }
        "Path" => {
            let dashed = child_elements(decorator, "path").any(|path| {
                path.attribute("data-dediren-icon-part") == Some("path-line")
                    && path.attribute("stroke-dasharray").is_some()
            });
            let arrowheads = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("path-arrowheads"));
            assert!(dashed, "{node_type} icon should include a dashed path line");
            assert!(
                arrowheads,
                "{node_type} icon should include separate arrowheads"
            );
        }
        "Device" => {
            let stand = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("device-stand"));
            assert!(stand, "{node_type} icon should include a device stand");
        }
        "Facility" => {
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} icon should use one factory silhouette"
            );
            let factory = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("factory-silhouette"));
            assert!(
                factory,
                "{node_type} icon should include the factory silhouette"
            );
            assert_facility_icon_uses_factory_silhouette(node_type, decorator);
        }
        "Equipment" => {
            assert_eq!(
                child_elements(decorator, "path")
                    .filter(|path| matches!(
                        path.attribute("data-dediren-icon-part"),
                        Some("equipment-gear-large" | "equipment-gear-small")
                    ))
                    .count(),
                2,
                "{node_type} icon should render two toothed gear outlines"
            );
            assert_eq!(
                child_elements(decorator, "circle")
                    .filter(|circle| circle.attribute("data-dediren-icon-part")
                        == Some("equipment-gear-hole"))
                    .count(),
                2,
                "{node_type} icon should render gear center holes"
            );
            assert_equipment_icon_uses_offset_gears(node_type, decorator);
        }
        "Material" => {
            let material = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("material-hexagon"));
            assert!(
                material,
                "{node_type} icon should expose the material hexagon"
            );
            let lines = child_elements(decorator, "path")
                .any(|path| path.attribute("data-dediren-icon-part") == Some("material-lines"));
            assert!(
                lines,
                "{node_type} icon should include inner material lines"
            );
            assert_material_icon_uses_inner_side_lines(node_type, decorator);
        }
        "CommunicationNetwork" => {
            assert!(
                child_elements(decorator, "circle").count() >= 4,
                "{node_type} icon should use connected network nodes"
            );
            assert_eq!(
                child_elements(decorator, "path").count(),
                1,
                "{node_type} icon should include network connectors"
            );
        }
        "DistributionNetwork" => {
            assert_eq!(
                child_elements(decorator, "circle").count(),
                0,
                "{node_type} icon should not use connected network nodes"
            );
            let arrows = child_elements(decorator, "path").any(|path| {
                path.attribute("data-dediren-icon-part") == Some("distribution-network-arrows")
            });
            assert!(
                arrows,
                "{node_type} icon should use a bidirectional distribution arrow"
            );
            assert_distribution_network_icon_uses_horizontal_bidirectional_arrow(
                node_type, decorator,
            );
        }
        _ => {}
    }
}

pub fn assert_side_cylinder_has_left_arc(node_type: &str, decorator: roxmltree::Node<'_, '_>) {
    let side_cylinder = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("side-cylinder"))
        .unwrap_or_else(|| panic!("{node_type} should include side-cylinder path"));
    let data = side_cylinder.attribute("d").unwrap_or("");
    assert!(
        data.contains(" A "),
        "{node_type} side-cylinder path should include the left-side connecting arc"
    );
}

pub fn assert_value_stream_icon_has_left_notch(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let chevron = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("value-stream-chevron"))
        .unwrap_or_else(|| panic!("{node_type} should include value stream chevron path"));
    let points = svg_path_points(chevron.attribute("d").unwrap());
    let min_x = points.iter().map(|(x, _)| *x).fold(f64::INFINITY, f64::min);
    let max_x = points
        .iter()
        .map(|(x, _)| *x)
        .fold(f64::NEG_INFINITY, f64::max);
    let min_y = points.iter().map(|(_, y)| *y).fold(f64::INFINITY, f64::min);
    let max_y = points
        .iter()
        .map(|(_, y)| *y)
        .fold(f64::NEG_INFINITY, f64::max);
    let mid_y = (min_y + max_y) / 2.0;
    let left_notch = points.iter().any(|(x, y)| {
        *x > min_x + (max_x - min_x) * 0.18
            && *x < min_x + (max_x - min_x) * 0.5
            && (*y - mid_y).abs() <= 1.0
    });
    assert!(
        left_notch,
        "{node_type} value stream chevron should have an inward notch on the left edge"
    );
}

pub fn assert_outcome_arrow_points_out_from_target_center(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let arrow = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("target-arrow"))
        .unwrap_or_else(|| panic!("{node_type} should include target arrow path"));
    let points = svg_path_points(arrow.attribute("d").unwrap());
    let target = child_elements(decorator, "ellipse")
        .next()
        .unwrap_or_else(|| panic!("{node_type} should include target rings"));
    let center_x = target.attribute("cx").unwrap().parse::<f64>().unwrap();
    let center_y = target.attribute("cy").unwrap().parse::<f64>().unwrap();
    let outer = target.attribute("rx").unwrap().parse::<f64>().unwrap();
    let (target_x, target_y) = points[0];
    let (tail_x, tail_y) = points[1];
    assert!(
        (target_x - center_x).abs() <= outer * 0.55 && (target_y - center_y).abs() <= outer * 0.55,
        "{node_type} target arrow should point into the bullseye center"
    );
    assert!(
        tail_x > center_x + outer * 0.7 && tail_y < center_y - outer * 0.7,
        "{node_type} target arrow tail should sit at the top-right"
    );
    let tail_segments: Vec<_> = points.chunks(2).skip(1).collect();
    let tail_vertical = tail_segments.iter().any(|segment| {
        let length = (segment[1].1 - segment[0].1).abs();
        (segment[0].0 - tail_x).abs() <= 0.1
            && (segment[1].0 - tail_x).abs() <= 0.1
            && segment[0].1 < tail_y
            && segment[1].1 > tail_y
            && length > outer * 0.2
            && length < outer * 0.35
    });
    let tail_slash = tail_segments.iter().any(|segment| {
        segment[0].0 < tail_x
            && segment[1].0 > tail_x
            && segment[1].1 < segment[0].1
            && (segment[0].0 - segment[1].0).abs() < outer * 0.35
    });
    let tail_upper_feather = tail_segments.iter().any(|segment| {
        segment[0].0 >= tail_x
            && segment[1].0 > segment[0].0
            && segment[1].1 < segment[0].1
            && (segment[0].0 - segment[1].0).abs() < outer * 0.3
    });
    assert!(
        tail_vertical && tail_slash && tail_upper_feather,
        "{node_type} target arrow should use a compact three-stroke tail fletching"
    );
}

pub fn assert_course_of_action_handle_extends_from_target_to_bottom_left(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let handle = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("course-of-action-handle"))
        .unwrap_or_else(|| panic!("{node_type} should include course of action handle path"));
    let points = svg_path_points(handle.attribute("d").unwrap());
    let target = child_elements(decorator, "ellipse")
        .next()
        .unwrap_or_else(|| panic!("{node_type} should include target rings"));
    let center_x = target.attribute("cx").unwrap().parse::<f64>().unwrap();
    let center_y = target.attribute("cy").unwrap().parse::<f64>().unwrap();
    let outer = target.attribute("rx").unwrap().parse::<f64>().unwrap();
    let (start_x, start_y) = points[0];
    let (end_x, end_y) = points[1];
    assert!(
        start_x < center_x - outer * 0.4 && start_y > center_y + outer * 0.4,
        "{node_type} handle should start on the lower-left side of the target"
    );
    assert!(
        end_x < start_x && end_y > start_y,
        "{node_type} handle should extend down-left away from the target"
    );
}

pub fn assert_capability_icon_uses_square_stair_grid(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let rects: Vec<_> = child_elements(decorator, "rect").collect();
    let first_width = rects[0].attribute("width").unwrap().parse::<f64>().unwrap();
    let first_height = rects[0]
        .attribute("height")
        .unwrap()
        .parse::<f64>()
        .unwrap();
    assert!(
        (first_width - first_height).abs() <= 0.1,
        "{node_type} capability blocks should be square"
    );
    for rect in &rects {
        let width = rect.attribute("width").unwrap().parse::<f64>().unwrap();
        let height = rect.attribute("height").unwrap().parse::<f64>().unwrap();
        assert!(
            (width - first_width).abs() <= 0.1 && (height - first_height).abs() <= 0.1,
            "{node_type} capability stair blocks should share one square size"
        );
    }

    let mut columns: Vec<(f64, usize)> = Vec::new();
    for rect in &rects {
        let x = rect.attribute("x").unwrap().parse::<f64>().unwrap();
        if let Some((_, count)) = columns
            .iter_mut()
            .find(|(column_x, _)| (*column_x - x).abs() <= 0.1)
        {
            *count += 1;
        } else {
            columns.push((x, 1));
        }
    }
    columns.sort_by(|(left_x, _), (right_x, _)| left_x.total_cmp(right_x));
    let heights: Vec<usize> = columns.iter().map(|(_, count)| *count).collect();
    assert!(
        heights.windows(2).all(|pair| pair[0] < pair[1]),
        "{node_type} capability stair columns should rise from left to right, got {heights:?}"
    );
}

pub fn assert_resource_icon_uses_horizontal_capsule(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let capsule = child_elements(decorator, "rect")
        .find(|rect| rect.attribute("data-dediren-icon-part") == Some("resource-capsule"))
        .unwrap_or_else(|| panic!("{node_type} should include resource capsule"));
    let tab = child_elements(decorator, "rect")
        .find(|rect| rect.attribute("data-dediren-icon-part") == Some("resource-tab"))
        .unwrap_or_else(|| panic!("{node_type} should include resource tab"));
    let capsule_x = capsule.attribute("x").unwrap().parse::<f64>().unwrap();
    let capsule_y = capsule.attribute("y").unwrap().parse::<f64>().unwrap();
    let capsule_width = capsule.attribute("width").unwrap().parse::<f64>().unwrap();
    let capsule_height = capsule.attribute("height").unwrap().parse::<f64>().unwrap();
    let capsule_rx = capsule.attribute("rx").unwrap().parse::<f64>().unwrap();
    let tab_x = tab.attribute("x").unwrap().parse::<f64>().unwrap();
    let tab_y = tab.attribute("y").unwrap().parse::<f64>().unwrap();
    let tab_height = tab.attribute("height").unwrap().parse::<f64>().unwrap();
    assert!(
        capsule_width > capsule_height * 1.8,
        "{node_type} resource capsule should be horizontal"
    );
    assert!(
        capsule_rx >= capsule_height * 0.25,
        "{node_type} resource capsule should have rounded ends"
    );
    assert!(
        tab_x >= capsule_x + capsule_width - 0.1
            && tab_y > capsule_y
            && tab_y + tab_height < capsule_y + capsule_height,
        "{node_type} resource tab should attach to the right side within the capsule height"
    );
}

pub fn assert_interaction_icon_uses_separate_open_half_circles(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let mut halves: Vec<_> = child_elements(decorator, "path")
        .filter(|path| path.attribute("data-dediren-icon-part") == Some("interaction-half"))
        .collect();
    halves.sort_by(|left, right| {
        let left_points = svg_path_points(left.attribute("d").unwrap());
        let right_points = svg_path_points(right.attribute("d").unwrap());
        let (left_min_x, left_max_x, _, _) = point_bounds(&left_points);
        let (right_min_x, right_max_x, _, _) = point_bounds(&right_points);
        ((left_min_x + left_max_x) / 2.0).total_cmp(&((right_min_x + right_max_x) / 2.0))
    });
    assert_eq!(halves.len(), 2);

    let left_points = svg_path_points(halves[0].attribute("d").unwrap());
    let right_points = svg_path_points(halves[1].attribute("d").unwrap());
    let (left_min_x, left_max_x, left_min_y, left_max_y) = point_bounds(&left_points);
    let (right_min_x, right_max_x, right_min_y, right_max_y) = point_bounds(&right_points);
    assert!(
        left_max_x < right_min_x,
        "{node_type} interaction half circles should be separate with a center gap"
    );
    let center_gap = right_min_x - left_max_x;
    assert!(
        center_gap >= 3.0,
        "{node_type} interaction half circles should leave a wider center gap, got {center_gap:.1}"
    );
    assert!(
        (left_min_y - right_min_y).abs() <= 0.1 && (left_max_y - right_max_y).abs() <= 0.1,
        "{node_type} interaction half circles should be vertically aligned"
    );
    assert!(
        (left_max_y - left_min_y) > (left_max_x - left_min_x)
            && (right_max_y - right_min_y) > (right_max_x - right_min_x),
        "{node_type} interaction halves should be vertical semicircles"
    );
    for points in [&left_points, &right_points] {
        assert!(
            points.len() >= 3,
            "{node_type} interaction half circles should include the flat vertical side, got only {points:?}"
        );
        let flat_side = points.windows(2).any(|pair| {
            (pair[0].0 - pair[1].0).abs() <= 0.1 && (pair[0].1 - pair[1].1).abs() >= 8.0
        });
        assert!(
            flat_side,
            "{node_type} interaction half circles should close each half with a vertical side"
        );
    }
    assert!(
        halves
            .iter()
            .all(|path| path.attribute("fill") == Some("none")),
        "{node_type} interaction half circles should be open outline arcs"
    );
    assert!(
        halves
            .iter()
            .all(|path| !path.attribute("d").unwrap_or("").contains(" Z")),
        "{node_type} interaction half circles should not be closed filled wedges"
    );
}

pub fn assert_application_component_tabs_extend_from_body(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let mut rects: Vec<_> = child_elements(decorator, "rect").collect();
    rects.sort_by(|left, right| {
        let left_width = left.attribute("width").unwrap().parse::<f64>().unwrap();
        let right_width = right.attribute("width").unwrap().parse::<f64>().unwrap();
        right_width.total_cmp(&left_width)
    });
    let body = rects[0];
    let tabs = &rects[1..];
    let body_x = body.attribute("x").unwrap().parse::<f64>().unwrap();

    for tab in tabs {
        let tab_x = tab.attribute("x").unwrap().parse::<f64>().unwrap();
        let tab_width = tab.attribute("width").unwrap().parse::<f64>().unwrap();
        let tab_right = tab_x + tab_width;
        assert!(
            tab_x < body_x,
            "{node_type} side tabs should extend out from the body left edge"
        );
        assert!(
            tab_right > body_x,
            "{node_type} side tabs should overlap the body instead of sitting outside it"
        );
        assert!(
            (body_x - tab_x - tab_width / 2.0).abs() <= 1.0,
            "{node_type} side tabs should protrude about halfway from the body left edge"
        );
    }
}

pub fn assert_collaboration_icon_uses_side_by_side_overlap(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let mut circles: Vec<_> = child_elements(decorator, "circle").collect();
    assert_eq!(
        circles
            .iter()
            .filter(|circle| circle.attribute("fill").is_some_and(|fill| fill != "none"))
            .count(),
        2,
        "{node_type} collaboration icon should include two filled circles"
    );
    assert_eq!(
        circles
            .iter()
            .filter(|circle| circle.attribute("fill") == Some("none"))
            .count(),
        2,
        "{node_type} collaboration icon should include two outline overlays"
    );
    circles.sort_by(|left, right| {
        let left_x = left.attribute("cx").unwrap().parse::<f64>().unwrap();
        let right_x = right.attribute("cx").unwrap().parse::<f64>().unwrap();
        left_x.total_cmp(&right_x)
    });
    assert_eq!(
        circles.len(),
        4,
        "{node_type} collaboration icon should use exactly four circle primitives"
    );
    let left = circles[0];
    let right = circles[2];
    let left_cx = left.attribute("cx").unwrap().parse::<f64>().unwrap();
    let left_cy = left.attribute("cy").unwrap().parse::<f64>().unwrap();
    let left_r = left.attribute("r").unwrap().parse::<f64>().unwrap();
    let right_cx = right.attribute("cx").unwrap().parse::<f64>().unwrap();
    let right_cy = right.attribute("cy").unwrap().parse::<f64>().unwrap();
    let right_r = right.attribute("r").unwrap().parse::<f64>().unwrap();
    let center_gap = right_cx - left_cx;
    assert!(
        (left_r - right_r).abs() <= 0.1 && (left_cy - right_cy).abs() <= 0.1,
        "{node_type} collaboration circles should be same-sized and horizontally aligned"
    );
    assert!(
        center_gap > left_r * 0.85 && center_gap < left_r * 1.5,
        "{node_type} collaboration circles should partially overlap side-by-side, got center gap {center_gap} and radius {left_r}"
    );
}

pub fn assert_function_icon_uses_bottom_notched_bookmark(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let bookmark = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("function-bookmark"))
        .unwrap_or_else(|| panic!("{node_type} should include function bookmark"));
    let points = svg_path_points(bookmark.attribute("d").unwrap());
    assert_eq!(
        points.len(),
        6,
        "{node_type} function bookmark should use six outline points"
    );
    let min_x = points.iter().map(|(x, _)| *x).fold(f64::INFINITY, f64::min);
    let max_x = points
        .iter()
        .map(|(x, _)| *x)
        .fold(f64::NEG_INFINITY, f64::max);
    let min_y = points.iter().map(|(_, y)| *y).fold(f64::INFINITY, f64::min);
    let max_y = points
        .iter()
        .map(|(_, y)| *y)
        .fold(f64::NEG_INFINITY, f64::max);
    let center_x = (min_x + max_x) / 2.0;
    let bottom_notch = points.iter().any(|(x, y)| {
        (*x - center_x).abs() <= (max_x - min_x) * 0.12
            && *y > min_y + (max_y - min_y) * 0.55
            && *y < max_y
    });
    assert!(
        bottom_notch,
        "{node_type} function bookmark should have a centered bottom notch"
    );
    assert!(
        points[0].1 < min_y + (max_y - min_y) * 0.35
            && points[1].1 < min_y + (max_y - min_y) * 0.35,
        "{node_type} function bookmark should start with a top edge"
    );
}

pub fn assert_distribution_network_icon_uses_horizontal_bidirectional_arrow(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let arrows = child_elements(decorator, "path")
        .find(|path| {
            path.attribute("data-dediren-icon-part") == Some("distribution-network-arrows")
        })
        .unwrap_or_else(|| panic!("{node_type} should include distribution network arrows"));
    assert!(
        arrows.attribute("fill").is_some_and(|fill| fill != "none"),
        "{node_type} distribution arrow should be a filled double-headed arrow shape"
    );
    let points = svg_path_points(arrows.attribute("d").unwrap());
    assert!(
        points.len() >= 10,
        "{node_type} distribution arrow should include a double-headed arrow polygon"
    );
    let min_x = points.iter().map(|(x, _)| *x).fold(f64::INFINITY, f64::min);
    let max_x = points
        .iter()
        .map(|(x, _)| *x)
        .fold(f64::NEG_INFINITY, f64::max);
    let min_y = points.iter().map(|(_, y)| *y).fold(f64::INFINITY, f64::min);
    let max_y = points
        .iter()
        .map(|(_, y)| *y)
        .fold(f64::NEG_INFINITY, f64::max);
    assert!(
        max_x - min_x > (max_y - min_y) * 2.0,
        "{node_type} distribution arrow should be horizontal"
    );
    let height = max_y - min_y;
    let mid_y = (min_y + max_y) / 2.0;
    let left_tip = points
        .iter()
        .any(|(x, y)| (*x - min_x).abs() <= 0.1 && (*y - mid_y).abs() <= height * 0.1);
    let right_tip = points
        .iter()
        .any(|(x, y)| (*x - max_x).abs() <= 0.1 && (*y - mid_y).abs() <= height * 0.1);
    assert!(
        left_tip && right_tip,
        "{node_type} distribution arrow should have centered arrow tips at both ends"
    );
    let shaft_edges = points
        .windows(2)
        .filter(|pair| {
            (pair[0].1 - pair[1].1).abs() <= 0.1
                && (pair[1].0 - pair[0].0).abs() > (max_x - min_x) * 0.35
                && (pair[0].1 - mid_y).abs() > height * 0.18
        })
        .count();
    assert!(
        shaft_edges >= 2,
        "{node_type} distribution arrow should include two parallel shaft edges"
    );
}

pub fn assert_material_icon_uses_inner_side_lines(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let hexagon = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("material-hexagon"))
        .unwrap_or_else(|| panic!("{node_type} should include material hexagon"));
    let lines = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("material-lines"))
        .unwrap_or_else(|| panic!("{node_type} should include material inner lines"));
    let hex_points = svg_path_points(hexagon.attribute("d").unwrap());
    let points = svg_path_points(lines.attribute("d").unwrap());
    assert_eq!(
        points.len(),
        6,
        "{node_type} material icon should use three inner side-line segments"
    );
    let expected_slopes = [
        segment_slope(hex_points[0], hex_points[1]),
        segment_slope(hex_points[2], hex_points[3]),
        segment_slope(hex_points[4], hex_points[5]),
    ];
    for expected_slope in expected_slopes {
        assert!(
            points.chunks(2).any(|segment| {
                (segment_slope(segment[0], segment[1]) - expected_slope).abs() <= 0.05
            }),
            "{node_type} material inner lines should be parallel to alternating hexagon edges"
        );
    }
}

pub fn assert_facility_icon_uses_factory_silhouette(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let factory = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("factory-silhouette"))
        .unwrap_or_else(|| panic!("{node_type} should include factory silhouette"));
    let points = svg_path_points(factory.attribute("d").unwrap());
    assert!(
        points.len() >= 10,
        "{node_type} factory silhouette should include stack, base, and saw-tooth roof"
    );
    let min_x = points.iter().map(|(x, _)| *x).fold(f64::INFINITY, f64::min);
    let max_y = points
        .iter()
        .map(|(_, y)| *y)
        .fold(f64::NEG_INFINITY, f64::max);
    let stack = points.windows(2).any(|pair| {
        (pair[0].0 - min_x).abs() <= 0.1
            && (pair[1].0 - min_x).abs() <= 0.1
            && (pair[1].1 - pair[0].1).abs() > 8.0
    });
    let base = points.windows(2).any(|pair| {
        (pair[0].1 - max_y).abs() <= 0.1
            && (pair[1].1 - max_y).abs() <= 0.1
            && (pair[1].0 - pair[0].0).abs() > 8.0
    });
    let roof_valleys = points
        .iter()
        .filter(|(x, y)| *x > min_x && *y < max_y - 3.0)
        .count();
    assert!(
        stack,
        "{node_type} factory silhouette should have a tall left stack"
    );
    assert!(
        base,
        "{node_type} factory silhouette should have a flat base"
    );
    assert!(
        roof_valleys >= 4,
        "{node_type} factory silhouette should include repeated roof teeth"
    );
}

pub fn assert_equipment_icon_uses_offset_gears(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let large = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("equipment-gear-large"))
        .unwrap_or_else(|| panic!("{node_type} should include the large equipment gear"));
    let small = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("equipment-gear-small"))
        .unwrap_or_else(|| panic!("{node_type} should include the small equipment gear"));

    let large_points = svg_path_points(large.attribute("d").unwrap());
    let small_points = svg_path_points(small.attribute("d").unwrap());
    assert!(
        large_points.len() >= 16 && small_points.len() >= 16,
        "{node_type} equipment gears should use toothed outlines"
    );

    let (large_min_x, large_max_x, large_min_y, large_max_y) = point_bounds(&large_points);
    let (small_min_x, small_max_x, small_min_y, small_max_y) = point_bounds(&small_points);
    let large_center_x = (large_min_x + large_max_x) / 2.0;
    let large_center_y = (large_min_y + large_max_y) / 2.0;
    let small_center_x = (small_min_x + small_max_x) / 2.0;
    let small_center_y = (small_min_y + small_max_y) / 2.0;

    assert!(
        (large_max_x - large_min_x) > (small_max_x - small_min_x),
        "{node_type} equipment icon should make the lower-left gear larger"
    );
    assert!(
        large_center_x < small_center_x && large_center_y > small_center_y,
        "{node_type} equipment icon should place the smaller gear above and to the right"
    );
}

pub fn assert_artifact_icon_uses_folded_document(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let artifact = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("artifact-document"))
        .unwrap_or_else(|| panic!("{node_type} should include artifact document path"));
    assert_folded_document_icon(node_type, artifact);
}

pub fn assert_constraint_icon_uses_inner_slanted_line(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let body = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("constraint-parallelogram"))
        .unwrap_or_else(|| panic!("{node_type} should include constraint parallelogram"));
    let line = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("constraint-left-line"))
        .unwrap_or_else(|| panic!("{node_type} should include constraint inner line"));
    let body_points = svg_path_points(body.attribute("d").unwrap());
    let line_points = svg_path_points(line.attribute("d").unwrap());
    assert_eq!(line_points.len(), 2);

    let left_top = body_points[0];
    let left_bottom = body_points[3];
    let line_top = line_points[0];
    let line_bottom = line_points[1];
    let body_slope = (left_bottom.0 - left_top.0) / (left_bottom.1 - left_top.1);
    let line_slope = (line_bottom.0 - line_top.0) / (line_bottom.1 - line_top.1);
    assert!(
        (body_slope - line_slope).abs() <= 0.05,
        "{node_type} inner line should be parallel to the parallelogram side"
    );
    assert!(
        (line_top.1 - left_top.1).abs() <= 0.1 && (line_bottom.1 - left_bottom.1).abs() <= 0.1,
        "{node_type} inner line should span the full parallelogram height"
    );

    for (x, y) in line_points {
        let left_edge_x = left_top.0 + body_slope * (y - left_top.1);
        assert!(
            x > left_edge_x + 1.0,
            "{node_type} inner line should sit inside the parallelogram, got x={x} left_edge_x={left_edge_x}"
        );
    }
}

pub fn assert_contract_icon_uses_document_with_two_lines(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    let body = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("contract-document-body"))
        .unwrap_or_else(|| panic!("{node_type} should include contract document body"));
    let lines = child_elements(decorator, "path")
        .find(|path| path.attribute("data-dediren-icon-part") == Some("contract-lines"))
        .unwrap_or_else(|| panic!("{node_type} should include contract line marks"));
    let body_points = svg_path_points(body.attribute("d").unwrap());
    assert_eq!(
        body_points.len(),
        4,
        "{node_type} contract body should match the unfolded document outline"
    );
    let (min_x, max_x, min_y, max_y) = point_bounds(&body_points);
    assert!(
        (max_y - min_y) < (max_x - min_x),
        "{node_type} contract document should be wider than tall like BusinessObject"
    );

    let line_points = svg_path_points(lines.attribute("d").unwrap());
    assert_eq!(
        line_points.len(),
        4,
        "{node_type} contract icon should have exactly two horizontal line segments"
    );
    for segment in line_points.chunks(2) {
        let (start_x, start_y) = segment[0];
        let (end_x, end_y) = segment[1];
        assert!(
            (start_y - end_y).abs() <= 0.1,
            "{node_type} contract lines should be horizontal"
        );
        assert!(
            (start_x - min_x).abs() <= 0.1 && (end_x - max_x).abs() <= 0.1,
            "{node_type} contract lines should run across the document body"
        );
        assert!(
            start_y > min_y && start_y < max_y,
            "{node_type} contract lines should sit inside the document body"
        );
    }
}

pub fn assert_folded_document_icon(node_type: &str, document: roxmltree::Node<'_, '_>) {
    let points = svg_path_points(document.attribute("d").unwrap());
    assert!(
        points.len() >= 8,
        "{node_type} document should include body and folded-corner segments"
    );

    let (min_x, max_x, min_y, max_y) = point_bounds(&points);
    assert!(
        (max_y - min_y) > (max_x - min_x),
        "{node_type} document should be portrait-oriented"
    );
    assert!(
        points
            .iter()
            .any(|(x, y)| (*x - max_x).abs() <= 0.1 && *y > min_y)
            && points
                .windows(2)
                .any(|pair| (pair[0].0 - pair[1].0).abs() <= 0.1
                    && (pair[0].1 - min_y).abs() <= 0.1
                    && pair[1].1 > min_y),
        "{node_type} document should expose a top-right folded corner"
    );
}

pub fn assert_archimate_icon_primitives_fit_standard_box(
    node_type: &str,
    decorator: roxmltree::Node<'_, '_>,
) {
    for rect in child_elements(decorator, "rect") {
        let width = rect.attribute("width").unwrap().parse::<f64>().unwrap();
        let height = rect.attribute("height").unwrap().parse::<f64>().unwrap();
        assert!(
            width <= 22.0 && height <= 22.0,
            "{node_type} icon rect primitive should fit in the standard icon box, got {width}x{height}"
        );
    }
    for circle in child_elements(decorator, "circle") {
        let radius = circle.attribute("r").unwrap().parse::<f64>().unwrap();
        assert!(
            radius <= 11.0,
            "{node_type} icon circle primitive should fit in the standard icon box, got radius {radius}"
        );
    }
    for ellipse in child_elements(decorator, "ellipse") {
        let rx = ellipse.attribute("rx").unwrap().parse::<f64>().unwrap();
        let ry = ellipse.attribute("ry").unwrap().parse::<f64>().unwrap();
        assert!(
            rx <= 11.0 && ry <= 11.0,
            "{node_type} icon ellipse primitive should fit in the standard icon box, got {rx}x{ry}"
        );
    }
}

pub fn assert_archimate_icon_primitives_stay_in_standard_box(
    node_type: &str,
    node: roxmltree::Node<'_, '_>,
    decorator: roxmltree::Node<'_, '_>,
) {
    let shape = child_node_shape(node);
    let (_, node_y, node_right, _) = svg_node_shape_bounds(shape);
    let icon_size = decorator
        .attribute("data-dediren-icon-size")
        .unwrap()
        .parse::<f64>()
        .unwrap();
    let expected_bounds = (
        node_right - icon_size - 6.0,
        node_y + 6.0,
        node_right - 6.0,
        node_y + 6.0 + icon_size,
    );

    for bounds in svg_primitive_bounds(decorator) {
        assert!(
            bounds.0 >= expected_bounds.0 - 0.1
                && bounds.1 >= expected_bounds.1 - 0.1
                && bounds.2 <= expected_bounds.2 + 0.1
                && bounds.3 <= expected_bounds.3 + 0.1,
            "{node_type} icon primitive should stay inside the standard icon box, got bounds {:?} expected {:?}",
            bounds,
            expected_bounds
        );
    }
}

pub fn assert_archimate_icon_primitives_are_centered_in_standard_box(
    node_type: &str,
    node: roxmltree::Node<'_, '_>,
    decorator: roxmltree::Node<'_, '_>,
) {
    let shape = child_node_shape(node);
    let (_, node_y, node_right, _) = svg_node_shape_bounds(shape);
    let icon_size = decorator
        .attribute("data-dediren-icon-size")
        .unwrap()
        .parse::<f64>()
        .unwrap();
    let expected_center = (
        node_right - icon_size / 2.0 - 6.0,
        node_y + 6.0 + icon_size / 2.0,
    );
    let bounds = combined_bounds(&svg_primitive_bounds(decorator));
    let actual_center = ((bounds.0 + bounds.2) / 2.0, (bounds.1 + bounds.3) / 2.0);
    let delta_x = actual_center.0 - expected_center.0;
    let delta_y = actual_center.1 - expected_center.1;
    assert!(
        delta_x.abs() <= 2.0 && delta_y.abs() <= 2.0,
        "{node_type} icon primitives should be visually centered in the standard icon box, got delta ({delta_x:.1}, {delta_y:.1}) from bounds {:?}",
        bounds
    );
}

pub fn svg_node_shape_bounds(shape: roxmltree::Node<'_, '_>) -> (f64, f64, f64, f64) {
    if shape.has_tag_name("rect") {
        let x = shape.attribute("x").unwrap().parse::<f64>().unwrap();
        let y = shape.attribute("y").unwrap().parse::<f64>().unwrap();
        let width = shape.attribute("width").unwrap().parse::<f64>().unwrap();
        let height = shape.attribute("height").unwrap().parse::<f64>().unwrap();
        return (x, y, x + width, y + height);
    }

    let points = svg_path_points(shape.attribute("d").unwrap_or(""));
    let (min_x, max_x, min_y, max_y) = point_bounds(&points);
    (min_x, min_y, max_x, max_y)
}

pub fn combined_bounds(bounds: &[(f64, f64, f64, f64)]) -> (f64, f64, f64, f64) {
    assert!(
        !bounds.is_empty(),
        "expected at least one SVG primitive bound"
    );
    let min_x = bounds
        .iter()
        .map(|(min_x, _, _, _)| *min_x)
        .fold(f64::INFINITY, f64::min);
    let min_y = bounds
        .iter()
        .map(|(_, min_y, _, _)| *min_y)
        .fold(f64::INFINITY, f64::min);
    let max_x = bounds
        .iter()
        .map(|(_, _, max_x, _)| *max_x)
        .fold(f64::NEG_INFINITY, f64::max);
    let max_y = bounds
        .iter()
        .map(|(_, _, _, max_y)| *max_y)
        .fold(f64::NEG_INFINITY, f64::max);
    (min_x, min_y, max_x, max_y)
}

pub fn svg_primitive_bounds(decorator: roxmltree::Node<'_, '_>) -> Vec<(f64, f64, f64, f64)> {
    let mut bounds = Vec::new();
    for rect in child_elements(decorator, "rect") {
        let x = rect.attribute("x").unwrap().parse::<f64>().unwrap();
        let y = rect.attribute("y").unwrap().parse::<f64>().unwrap();
        let width = rect.attribute("width").unwrap().parse::<f64>().unwrap();
        let height = rect.attribute("height").unwrap().parse::<f64>().unwrap();
        bounds.push((x, y, x + width, y + height));
    }
    for circle in child_elements(decorator, "circle") {
        let cx = circle.attribute("cx").unwrap().parse::<f64>().unwrap();
        let cy = circle.attribute("cy").unwrap().parse::<f64>().unwrap();
        let radius = circle.attribute("r").unwrap().parse::<f64>().unwrap();
        bounds.push((cx - radius, cy - radius, cx + radius, cy + radius));
    }
    for ellipse in child_elements(decorator, "ellipse") {
        let cx = ellipse.attribute("cx").unwrap().parse::<f64>().unwrap();
        let cy = ellipse.attribute("cy").unwrap().parse::<f64>().unwrap();
        let rx = ellipse.attribute("rx").unwrap().parse::<f64>().unwrap();
        let ry = ellipse.attribute("ry").unwrap().parse::<f64>().unwrap();
        bounds.push((cx - rx, cy - ry, cx + rx, cy + ry));
    }
    for path in child_elements(decorator, "path") {
        let points = svg_path_points(path.attribute("d").unwrap_or(""));
        if !points.is_empty() {
            let (min_x, max_x, min_y, max_y) = point_bounds(&points);
            bounds.push((min_x, min_y, max_x, max_y));
        }
    }
    bounds
}

pub fn assert_marker(
    doc: &roxmltree::Document<'_>,
    path: roxmltree::Node<'_, '_>,
    edge_id: &str,
    position: &str,
    expected_marker: Option<&str>,
    relationship_type: &str,
) {
    let attr_name = format!("marker-{position}");
    let id = format!("marker-{position}-{edge_id}");
    match expected_marker {
        Some("none") | None => assert_eq!(
            path.attribute(attr_name.as_str()),
            None,
            "{relationship_type} should not render a {position} marker"
        ),
        Some(marker) => {
            assert_eq!(
                path.attribute(attr_name.as_str()),
                Some(format!("url(#{id})").as_str()),
                "{relationship_type} should reference its {position} marker"
            );
            doc.descendants()
                .find(|node| {
                    node.has_tag_name("marker")
                        && node.attribute("id") == Some(id.as_str())
                        && node.attribute(format!("data-dediren-edge-marker-{position}").as_str())
                            == Some(marker)
                })
                .unwrap_or_else(|| {
                    panic!("expected {relationship_type} to emit {position} marker {marker}")
                });
        }
    }
}

pub fn text_box_from_svg(label: roxmltree::Node<'_, '_>, font_size: f64) -> (f64, f64, f64, f64) {
    let x = label.attribute("x").unwrap().parse::<f64>().unwrap();
    let y = label.attribute("y").unwrap().parse::<f64>().unwrap();
    let text = label.text().unwrap_or("");
    let half_width = text.chars().count() as f64 * font_size * 0.62 / 2.0;
    let width = half_width * 2.0;
    match label.attribute("text-anchor") {
        Some("end") => (x - width, y - font_size, x, y + font_size * 0.4),
        Some("start") => (x, y - font_size, x + width, y + font_size * 0.4),
        _ => (
            x - half_width,
            y - font_size,
            x + half_width,
            y + font_size * 0.4,
        ),
    }
}

pub fn text_lines_from_svg(label: roxmltree::Node<'_, '_>) -> Vec<String> {
    let tspan_lines: Vec<String> = label
        .children()
        .filter(|node| node.has_tag_name("tspan"))
        .filter_map(|node| node.text().map(ToString::to_string))
        .collect();
    if tspan_lines.is_empty() {
        vec![label.text().unwrap_or("").to_string()]
    } else {
        tspan_lines
    }
}

pub fn estimated_svg_text_width(text: &str, font_size: f64) -> f64 {
    text.chars().count() as f64 * font_size * 0.62
}

pub fn box_contains_point(bounds: (f64, f64, f64, f64), x: f64, y: f64) -> bool {
    x >= bounds.0 && x <= bounds.2 && y >= bounds.1 && y <= bounds.3
}

pub fn box_center_y(bounds: (f64, f64, f64, f64)) -> f64 {
    (bounds.1 + bounds.3) / 2.0
}

pub fn horizontal_gap_to_x(bounds: (f64, f64, f64, f64), x: f64) -> f64 {
    if bounds.2 < x {
        x - bounds.2
    } else if bounds.0 > x {
        bounds.0 - x
    } else {
        0.0
    }
}

pub fn boxes_overlap(left: (f64, f64, f64, f64), right: (f64, f64, f64, f64)) -> bool {
    left.0 < right.2 && left.2 > right.0 && left.1 < right.3 && left.3 > right.1
}

pub fn box_intersects_horizontal_segment(
    bounds: (f64, f64, f64, f64),
    start_x: f64,
    end_x: f64,
    y: f64,
) -> bool {
    bounds.0 < end_x && bounds.2 > start_x && bounds.1 < y && bounds.3 > y
}

pub fn write_render_artifact(test_name: &str, content: &str) -> PathBuf {
    let path = workspace_file(&format!(
        ".test-output/renders/svg-render-plugin/{test_name}.svg"
    ));
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(&path, content).unwrap();
    path
}

pub fn current_test_name() -> String {
    std::thread::current()
        .name()
        .unwrap_or("unknown-test")
        .rsplit("::")
        .next()
        .unwrap_or("unknown-test")
        .to_string()
}

pub fn assert_gap_marker_is_centered_on_guide_lines(
    marker: roxmltree::Node<'_, '_>,
    lines: roxmltree::Node<'_, '_>,
) {
    let marker_x = marker.attribute("cx").unwrap().parse::<f64>().unwrap();
    let line_coords = parse_svg_path_numbers(lines.attribute("d").unwrap());
    assert_eq!(line_coords.len(), 8);
    let top_left_x = line_coords[0];
    let top_right_x = line_coords[2];
    let bottom_left_x = line_coords[4];
    let bottom_right_x = line_coords[6];
    let expected_center = (top_left_x + top_right_x) / 2.0;
    assert!(
        (marker_x - expected_center).abs() <= 0.1,
        "gap marker should be centered on guide lines, got marker_x={marker_x} expected_center={expected_center}"
    );
    assert!(
        (marker_x - ((bottom_left_x + bottom_right_x) / 2.0)).abs() <= 0.1,
        "gap marker should also be centered on the second guide line"
    );
}

pub fn parse_svg_path_numbers(data: &str) -> Vec<f64> {
    data.split_whitespace()
        .filter_map(|token| token.parse::<f64>().ok())
        .collect()
}

pub fn path_data_contains_point(data: &str, x: f64, y: f64) -> bool {
    parse_svg_path_numbers(data)
        .chunks_exact(2)
        .any(|point| (point[0] - x).abs() <= 0.1 && (point[1] - y).abs() <= 0.1)
}

pub fn point_bounds(points: &[(f64, f64)]) -> (f64, f64, f64, f64) {
    let min_x = points.iter().map(|(x, _)| *x).fold(f64::INFINITY, f64::min);
    let max_x = points
        .iter()
        .map(|(x, _)| *x)
        .fold(f64::NEG_INFINITY, f64::max);
    let min_y = points.iter().map(|(_, y)| *y).fold(f64::INFINITY, f64::min);
    let max_y = points
        .iter()
        .map(|(_, y)| *y)
        .fold(f64::NEG_INFINITY, f64::max);
    (min_x, max_x, min_y, max_y)
}

pub fn segment_slope(start: (f64, f64), end: (f64, f64)) -> f64 {
    (end.1 - start.1) / (end.0 - start.0)
}

pub fn svg_path_points(data: &str) -> Vec<(f64, f64)> {
    let tokens: Vec<&str> = data.split_whitespace().collect();
    let mut points = Vec::new();
    let mut index = 0;
    while index < tokens.len() {
        match tokens[index] {
            "M" | "L" => {
                if index + 2 < tokens.len() {
                    if let (Some(x), Some(y)) = (
                        svg_path_number(tokens[index + 1]),
                        svg_path_number(tokens[index + 2]),
                    ) {
                        points.push((x, y));
                    }
                }
                index += 3;
            }
            "C" => {
                if index + 6 < tokens.len() {
                    for offset in [1, 3, 5] {
                        if let (Some(x), Some(y)) = (
                            svg_path_number(tokens[index + offset]),
                            svg_path_number(tokens[index + offset + 1]),
                        ) {
                            points.push((x, y));
                        }
                    }
                }
                index += 7;
            }
            "A" => {
                if index + 7 < tokens.len() {
                    if let (Some(x), Some(y)) = (
                        svg_path_number(tokens[index + 6]),
                        svg_path_number(tokens[index + 7]),
                    ) {
                        points.push((x, y));
                    }
                }
                index += 8;
            }
            _ => index += 1,
        }
    }
    points
}

pub fn svg_path_number(token: &str) -> Option<f64> {
    token.trim_end_matches(',').parse::<f64>().ok()
}

pub const ARCHIMATE_NODE_TYPES: &[&str] = &[
    "Plateau",
    "WorkPackage",
    "Deliverable",
    "ImplementationEvent",
    "Gap",
    "Grouping",
    "Location",
    "Stakeholder",
    "Driver",
    "Assessment",
    "Goal",
    "Outcome",
    "Value",
    "Meaning",
    "Constraint",
    "Requirement",
    "Principle",
    "CourseOfAction",
    "Resource",
    "ValueStream",
    "Capability",
    "BusinessInterface",
    "BusinessCollaboration",
    "BusinessActor",
    "BusinessRole",
    "BusinessService",
    "BusinessInteraction",
    "BusinessFunction",
    "BusinessProcess",
    "BusinessEvent",
    "Product",
    "BusinessObject",
    "Contract",
    "Representation",
    "ApplicationInterface",
    "ApplicationCollaboration",
    "ApplicationComponent",
    "ApplicationService",
    "ApplicationInteraction",
    "ApplicationFunction",
    "ApplicationProcess",
    "ApplicationEvent",
    "DataObject",
    "TechnologyInterface",
    "TechnologyCollaboration",
    "Node",
    "SystemSoftware",
    "Device",
    "Facility",
    "Equipment",
    "Path",
    "TechnologyService",
    "TechnologyInteraction",
    "TechnologyFunction",
    "TechnologyProcess",
    "TechnologyEvent",
    "Artifact",
    "Material",
    "CommunicationNetwork",
    "DistributionNetwork",
];

pub const ARCHIMATE_RELATIONSHIP_TYPES: &[&str] = &[
    "Composition",
    "Aggregation",
    "Assignment",
    "Realization",
    "Specialization",
    "Serving",
    "Access",
    "Influence",
    "Association",
    "Triggering",
    "Flow",
];

pub fn workspace_file(path: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .join(path)
}
