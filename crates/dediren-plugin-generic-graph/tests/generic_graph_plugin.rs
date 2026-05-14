mod common;

#[test]
fn generic_graph_projects_basic_view() {
    let input = std::fs::read_to_string(common::workspace_file("fixtures/source/valid-basic.json"))
        .unwrap();
    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    assert_eq!(
        data["layout_request_schema_version"],
        "layout-request.schema.v1"
    );
    assert_eq!(data["view_id"], "main");
    assert_eq!(
        data["nodes"]
            .as_array()
            .expect("nodes should be an array")
            .len(),
        2
    );
    assert_eq!(
        data["edges"]
            .as_array()
            .expect("edges should be an array")
            .len(),
        1
    );
    assert_eq!(data["edges"][0]["relationship_type"], "generic.calls");
}

#[test]
fn generic_graph_projects_rich_view_groups() {
    let input = std::fs::read_to_string(common::workspace_file(
        "fixtures/source/valid-pipeline-rich.json",
    ))
    .unwrap();
    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    let groups = data["groups"].as_array().unwrap();

    assert_eq!(groups.len(), 2);
    assert_eq!(groups[0]["id"], "application-services");
    assert_eq!(groups[0]["label"], "Application Services");
    assert_eq!(
        groups[0]["members"],
        serde_json::json!(["web-app", "orders-api", "worker"])
    );
    assert_eq!(
        groups[0]["provenance"],
        serde_json::json!({ "semantic_backed": { "source_id": "application-services" } })
    );

    assert_eq!(groups[1]["id"], "external-dependencies");
    assert_eq!(groups[1]["label"], "External Dependencies");
    assert_eq!(
        groups[1]["members"],
        serde_json::json!(["payments", "database"])
    );
}

#[test]
fn generic_graph_projects_group_roles_into_provenance() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "nodes": [
            { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
            { "id": "domain-group", "type": "Grouping", "label": "Domain Group", "properties": {} }
        ],
        "relationships": [],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["client", "api"],
                        "relationships": [],
                        "groups": [
                            {
                                "id": "domain-boundary",
                                "label": "Domain Boundary",
                                "members": ["client", "api"],
                                "role": "semantic-boundary",
                                "semantic_source_id": "domain-group"
                            },
                            {
                                "id": "visual-column",
                                "label": "Visual Column",
                                "members": ["api"],
                                "role": "layout-only"
                            }
                        ]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    let groups = data["groups"].as_array().unwrap();
    assert_eq!(
        groups[0]["provenance"],
        serde_json::json!({ "semantic_backed": { "source_id": "domain-group" } })
    );
    assert_eq!(
        groups[1]["provenance"],
        serde_json::json!({ "visual_only": true })
    );
}

#[test]
fn generic_graph_rejects_group_semantic_source_id_that_is_not_a_source_node() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "nodes": [
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
        ],
        "relationships": [],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["api"],
                        "relationships": [],
                        "groups": [
                            {
                                "id": "bad-group",
                                "label": "Bad Group",
                                "members": ["api"],
                                "role": "semantic-boundary",
                                "semantic_source_id": "missing-grouping-node"
                            }
                        ]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    cmd.args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .stderr(predicates::str::contains(
            "group bad-group semantic_source_id references missing node",
        ));
}

#[test]
fn generic_graph_projects_group_render_metadata_for_semantic_source_id() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "nodes": [
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
            { "id": "domain-group", "type": "Grouping", "label": "Domain Group", "properties": {} }
        ],
        "relationships": [],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["api"],
                        "relationships": [],
                        "groups": [
                            {
                                "id": "domain-boundary",
                                "label": "Domain Boundary",
                                "members": ["api"],
                                "role": "semantic-boundary",
                                "semantic_source_id": "domain-group"
                            },
                            {
                                "id": "visual-column",
                                "label": "Visual Column",
                                "members": ["api"],
                                "role": "layout-only"
                            }
                        ]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    assert_eq!(
        data["groups"]["domain-boundary"],
        serde_json::json!({ "type": "Grouping", "source_id": "domain-group" })
    );
    assert!(data["groups"].get("visual-column").is_none());
}

#[test]
fn generic_graph_projects_archimate_junctions_as_small_layout_nodes() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            { "id": "generic-graph", "version": "0.8.2" },
            { "id": "archimate-oef", "version": "0.8.2" }
        ],
        "nodes": [
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
            { "id": "flow-junction", "type": "AndJunction", "label": "", "properties": {} },
            { "id": "orders", "type": "ApplicationService", "label": "Orders", "properties": {} },
            { "id": "billing", "type": "ApplicationService", "label": "Billing", "properties": {} }
        ],
        "relationships": [
            { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "flow-junction", "label": "", "properties": {} },
            { "id": "junction-to-orders", "type": "Flow", "source": "flow-junction", "target": "orders", "label": "", "properties": {} },
            { "id": "junction-to-billing", "type": "Flow", "source": "flow-junction", "target": "billing", "label": "", "properties": {} }
        ],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["api", "flow-junction", "orders", "billing"],
                        "relationships": ["api-to-junction", "junction-to-orders", "junction-to-billing"]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    let junction = data["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .find(|node| node["id"] == "flow-junction")
        .expect("projected layout request should contain the junction node");
    assert_eq!(junction["width_hint"], serde_json::json!(28.0));
    assert_eq!(junction["height_hint"], serde_json::json!(28.0));
}

#[test]
fn generic_graph_projects_archimate_junction_render_metadata() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            { "id": "generic-graph", "version": "0.8.2" },
            { "id": "archimate-oef", "version": "0.8.2" }
        ],
        "nodes": [
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
            { "id": "flow-junction", "type": "OrJunction", "label": "", "properties": {} },
            { "id": "orders", "type": "ApplicationService", "label": "Orders", "properties": {} }
        ],
        "relationships": [
            { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "flow-junction", "label": "", "properties": {} },
            { "id": "junction-to-orders", "type": "Flow", "source": "flow-junction", "target": "orders", "label": "", "properties": {} }
        ],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["api", "flow-junction", "orders"],
                        "relationships": ["api-to-junction", "junction-to-orders"]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    assert_eq!(
        data["nodes"]["flow-junction"],
        serde_json::json!({ "type": "OrJunction", "source_id": "flow-junction" })
    );
}

#[test]
fn generic_graph_rejects_archimate_junction_with_mixed_relationship_types() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            { "id": "generic-graph", "version": "0.8.2" },
            { "id": "archimate-oef", "version": "0.8.2" }
        ],
        "nodes": [
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
            { "id": "junction", "type": "AndJunction", "label": "", "properties": {} },
            { "id": "orders", "type": "ApplicationService", "label": "Orders", "properties": {} }
        ],
        "relationships": [
            { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "junction", "label": "", "properties": {} },
            { "id": "junction-to-orders", "type": "Serving", "source": "junction", "target": "orders", "label": "", "properties": {} }
        ],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["api", "junction", "orders"],
                        "relationships": ["api-to-junction", "junction-to-orders"]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["validate", "--profile", "archimate"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope =
        common::assert_error_code(&output, "DEDIREN_ARCHIMATE_JUNCTION_RELATIONSHIP_MIXED");
    let message = envelope["diagnostics"][0]["message"].as_str().unwrap();
    assert!(message.contains("junction"));
    assert!(message.contains("Flow"));
    assert!(message.contains("Serving"));
}

#[test]
fn generic_graph_rejects_archimate_junction_with_invalid_effective_endpoint() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            { "id": "generic-graph", "version": "0.8.2" },
            { "id": "archimate-oef", "version": "0.8.2" }
        ],
        "nodes": [
            { "id": "service", "type": "ApplicationService", "label": "Service", "properties": {} },
            { "id": "junction", "type": "AndJunction", "label": "", "properties": {} },
            { "id": "component", "type": "ApplicationComponent", "label": "Component", "properties": {} }
        ],
        "relationships": [
            { "id": "service-to-junction", "type": "Realization", "source": "service", "target": "junction", "label": "", "properties": {} },
            { "id": "junction-to-component", "type": "Realization", "source": "junction", "target": "component", "label": "", "properties": {} }
        ],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["service", "junction", "component"],
                        "relationships": ["service-to-junction", "junction-to-component"]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["validate", "--profile", "archimate"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(
        &output,
        "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
    );
    let message = envelope["diagnostics"][0]["message"].as_str().unwrap();
    assert!(message.contains("ApplicationService"));
    assert!(message.contains("Realization"));
    assert!(message.contains("ApplicationComponent"));
}

#[test]
fn generic_graph_rejects_archimate_junction_without_incoming_and_outgoing_relationships() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            { "id": "generic-graph", "version": "0.8.2" },
            { "id": "archimate-oef", "version": "0.8.2" }
        ],
        "nodes": [
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
            { "id": "junction", "type": "AndJunction", "label": "", "properties": {} }
        ],
        "relationships": [
            { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "junction", "label": "", "properties": {} }
        ],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["api", "junction"],
                        "relationships": ["api-to-junction"]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["validate", "--profile", "archimate"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope =
        common::assert_error_code(&output, "DEDIREN_ARCHIMATE_JUNCTION_DIRECTION_INCOMPLETE");
    let message = envelope["diagnostics"][0]["message"].as_str().unwrap();
    assert!(message.contains("at least one incoming"));
    assert!(message.contains("at least one outgoing"));
}

#[test]
fn generic_graph_rejects_archimate_junction_chain_with_invalid_effective_endpoint() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            { "id": "generic-graph", "version": "0.8.2" },
            { "id": "archimate-oef", "version": "0.8.2" }
        ],
        "nodes": [
            { "id": "service", "type": "ApplicationService", "label": "Service", "properties": {} },
            { "id": "join", "type": "AndJunction", "label": "", "properties": {} },
            { "id": "split", "type": "AndJunction", "label": "", "properties": {} },
            { "id": "component", "type": "ApplicationComponent", "label": "Component", "properties": {} }
        ],
        "relationships": [
            { "id": "service-to-join", "type": "Realization", "source": "service", "target": "join", "label": "", "properties": {} },
            { "id": "join-to-split", "type": "Realization", "source": "join", "target": "split", "label": "", "properties": {} },
            { "id": "split-to-component", "type": "Realization", "source": "split", "target": "component", "label": "", "properties": {} }
        ],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["service", "join", "split", "component"],
                        "relationships": ["service-to-join", "join-to-split", "split-to-component"]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["validate", "--profile", "archimate"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(
        &output,
        "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
    );
    let message = envelope["diagnostics"][0]["message"].as_str().unwrap();
    assert!(message.contains("ApplicationService"));
    assert!(message.contains("Realization"));
    assert!(message.contains("ApplicationComponent"));
}

#[test]
fn generic_graph_allows_archimate_junction_containment_relationship() {
    let input = serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            { "id": "generic-graph", "version": "0.8.2" },
            { "id": "archimate-oef", "version": "0.8.2" }
        ],
        "nodes": [
            { "id": "group", "type": "Grouping", "label": "Group", "properties": {} },
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
            { "id": "junction", "type": "AndJunction", "label": "", "properties": {} },
            { "id": "orders", "type": "ApplicationService", "label": "Orders", "properties": {} }
        ],
        "relationships": [
            { "id": "group-contains-junction", "type": "Composition", "source": "group", "target": "junction", "label": "", "properties": {} },
            { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "junction", "label": "", "properties": {} },
            { "id": "junction-to-orders", "type": "Flow", "source": "junction", "target": "orders", "label": "", "properties": {} }
        ],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["group", "api", "junction", "orders"],
                        "relationships": ["group-contains-junction", "api-to-junction", "junction-to-orders"]
                    }
                ]
            }
        }
    });

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "layout-request", "--view", "main"])
        .write_stdin(serde_json::to_string(&input).unwrap())
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    assert_eq!(
        data["nodes"]
            .as_array()
            .expect("nodes should be an array")
            .len(),
        4
    );
}

#[test]
fn generic_graph_projects_render_metadata() {
    let input = std::fs::read_to_string(common::workspace_file(
        "fixtures/source/valid-archimate-oef.json",
    ))
    .unwrap();
    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    assert_eq!(
        data["render_metadata_schema_version"],
        "render-metadata.schema.v1"
    );
    assert_eq!(data["semantic_profile"], "archimate");
    assert_eq!(
        data["nodes"]["orders-component"]["type"],
        "ApplicationComponent"
    );
    assert_eq!(
        data["nodes"]["orders-service"]["type"],
        "ApplicationService"
    );
    assert_eq!(
        data["edges"]["orders-realizes-service"]["type"],
        "Realization"
    );
}

#[test]
fn generic_graph_validates_archimate_source_semantics() {
    let input = std::fs::read_to_string(common::workspace_file(
        "fixtures/source/valid-archimate-oef.json",
    ))
    .unwrap();
    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["validate", "--profile", "archimate"])
        .write_stdin(input)
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();

    let data = common::ok_data(&output);
    assert_eq!(
        data["semantic_validation_result_schema_version"],
        "semantic-validation-result.schema.v1"
    );
    assert_eq!(data["semantic_profile"], "archimate");
    assert_eq!(data["node_count"], 2);
    assert_eq!(data["relationship_count"], 1);
}

#[test]
fn generic_graph_rejects_unknown_archimate_node_type_for_render_metadata() {
    let mut source = archimate_source();
    source["nodes"][0]["type"] = serde_json::json!("TechnologyNode");

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&source).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(&output, "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("TechnologyNode"));
}

#[test]
fn generic_graph_rejects_unknown_archimate_relationship_type_for_render_metadata() {
    let mut source = archimate_source();
    source["relationships"][0]["type"] = serde_json::json!("ConnectsTo");

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&source).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope =
        common::assert_error_code(&output, "DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("ConnectsTo"));
}

#[test]
fn generic_graph_rejects_invalid_archimate_relationship_endpoint_for_render_metadata() {
    let mut source = archimate_source();
    source["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    source["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    source["relationships"][0]["type"] = serde_json::json!("Realization");

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["project", "--target", "render-metadata", "--view", "main"])
        .write_stdin(serde_json::to_string(&source).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(
        &output,
        "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
    );
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("ApplicationService"));
    assert!(message.contains("Realization"));
    assert!(message.contains("ApplicationComponent"));
}

#[test]
fn generic_graph_rejects_invalid_archimate_relationship_endpoint_for_semantic_validation() {
    let mut source = archimate_source();
    source["nodes"][0]["type"] = serde_json::json!("ApplicationService");
    source["nodes"][1]["type"] = serde_json::json!("ApplicationComponent");
    source["relationships"][0]["type"] = serde_json::json!("Realization");

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["validate", "--profile", "archimate"])
        .write_stdin(serde_json::to_string(&source).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(
        &output,
        "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED",
    );
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("ApplicationService"));
    assert!(message.contains("Realization"));
    assert!(message.contains("ApplicationComponent"));
}

#[test]
fn generic_graph_rejects_archimate_junction_as_source_node_for_semantic_validation() {
    let mut source = archimate_source();
    source["nodes"][0]["type"] = serde_json::json!("Junction");

    let mut cmd = common::plugin_command();
    let output = cmd
        .args(["validate", "--profile", "archimate"])
        .write_stdin(serde_json::to_string(&source).unwrap())
        .assert()
        .failure()
        .get_output()
        .stdout
        .clone();

    let envelope = common::assert_error_code(&output, "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
    let message = envelope["diagnostics"][0]["message"]
        .as_str()
        .expect("diagnostic message should be a string");
    assert!(message.contains("Junction"));
}

fn archimate_source() -> serde_json::Value {
    serde_json::json!({
        "model_schema_version": "model.schema.v1",
        "required_plugins": [
            {
                "id": "generic-graph",
                "version": "0.8.2"
            },
            {
                "id": "archimate-oef",
                "version": "0.8.2"
            }
        ],
        "nodes": [
            {
                "id": "orders-component",
                "type": "ApplicationComponent",
                "label": "Orders Component",
                "properties": {}
            },
            {
                "id": "orders-service",
                "type": "ApplicationService",
                "label": "Orders Service",
                "properties": {}
            }
        ],
        "relationships": [
            {
                "id": "orders-realizes-service",
                "type": "Realization",
                "source": "orders-component",
                "target": "orders-service",
                "label": "realizes",
                "properties": {}
            }
        ],
        "plugins": {
            "generic-graph": {
                "views": [
                    {
                        "id": "main",
                        "label": "Main",
                        "nodes": ["orders-component", "orders-service"],
                        "relationships": ["orders-realizes-service"]
                    }
                ]
            }
        }
    })
}
