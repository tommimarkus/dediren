use std::path::PathBuf;

use dediren_contracts::{LayoutRequest, OefExportInput, OefExportPolicy, SourceDocument};

use crate::plugins::{
    run_plugin_for_capability_with_registry, PluginExecutionError, PluginRegistry,
    PluginRunOptions, PluginRunOutcome,
};

pub struct LayoutCommandInput<'a> {
    pub plugin: &'a str,
    pub input_text: &'a str,
    pub plugin_dirs: Vec<PathBuf>,
    pub env: Vec<(String, String)>,
}

pub fn layout_command(
    input: LayoutCommandInput<'_>,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    let request: LayoutRequest =
        crate::io::parse_command_data(input.input_text).map_err(|error| {
            PluginExecutionError::CommandInputInvalid {
                command: "layout".to_string(),
                message: error.to_string(),
            }
        })?;
    let registry = PluginRegistry::from_dirs(input.plugin_dirs);
    let mut options = PluginRunOptions::default();
    for (name, value) in input.env {
        if name == "DEDIREN_ELK_COMMAND" || name == "DEDIREN_ELK_RESULT_FIXTURE" {
            options.allowed_env.push((name, value));
        }
    }
    run_plugin_for_capability_with_registry(
        &registry,
        input.plugin,
        "layout",
        &["layout"],
        &serde_json::to_string(&request).map_err(command_input_error("layout"))?,
        options,
    )
}

pub fn layout_command_from_env(
    plugin: &str,
    input_text: &str,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    let mut env = Vec::new();
    for name in ["DEDIREN_ELK_COMMAND", "DEDIREN_ELK_RESULT_FIXTURE"] {
        if let Ok(value) = std::env::var(name) {
            env.push((name.to_string(), value));
        }
    }
    layout_command(LayoutCommandInput {
        plugin,
        input_text,
        plugin_dirs: PluginRegistry::bundled_dirs(),
        env,
    })
}

pub fn project_command(
    plugin: &str,
    target: &str,
    view: &str,
    input_text: &str,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    crate::plugins::run_plugin_for_capability(
        plugin,
        "projection",
        &["project", "--target", target, "--view", view],
        input_text,
    )
}

pub fn render_command(
    plugin: &str,
    policy_text: &str,
    layout_text: &str,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    let layout_result: dediren_contracts::LayoutResult = crate::io::parse_command_data(layout_text)
        .map_err(|error| PluginExecutionError::CommandInputInvalid {
            command: "render".to_string(),
            message: error.to_string(),
        })?;
    let render_input = serde_json::json!({
        "layout_result": layout_result,
        "policy": serde_json::from_str::<serde_json::Value>(policy_text)
            .map_err(command_input_error("render"))?
    });
    crate::plugins::run_plugin_for_capability(
        plugin,
        "render",
        &["render"],
        &serde_json::to_string(&render_input).map_err(command_input_error("render"))?,
    )
}

pub fn export_command(
    plugin: &str,
    policy_text: &str,
    source_text: &str,
    layout_text: &str,
) -> Result<PluginRunOutcome, PluginExecutionError> {
    let source: SourceDocument =
        serde_json::from_str(source_text).map_err(command_input_error("export"))?;
    let layout_result: dediren_contracts::LayoutResult = crate::io::parse_command_data(layout_text)
        .map_err(|error| PluginExecutionError::CommandInputInvalid {
            command: "export".to_string(),
            message: error.to_string(),
        })?;
    let policy: OefExportPolicy =
        serde_json::from_str(policy_text).map_err(command_input_error("export"))?;
    let export_input = OefExportInput {
        export_request_schema_version: dediren_contracts::EXPORT_REQUEST_SCHEMA_VERSION.to_string(),
        source,
        layout_result,
        policy,
    };
    crate::plugins::run_plugin_for_capability(
        plugin,
        "export",
        &["export"],
        &serde_json::to_string(&export_input).map_err(command_input_error("export"))?,
    )
}

fn command_input_error(
    command: &'static str,
) -> impl FnOnce(serde_json::Error) -> PluginExecutionError {
    move |error| PluginExecutionError::CommandInputInvalid {
        command: command.to_string(),
        message: error.to_string(),
    }
}
