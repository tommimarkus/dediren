use clap::{Parser, Subcommand};

#[derive(Debug, Parser)]
#[command(name = "dediren")]
#[command(version)]
#[command(
    after_help = "Agent authoring guide: docs/agent-usage.md\nUse it for source JSON shape, generated artifact handoff, fragments, and repair diagnostics."
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Debug, Subcommand)]
enum Commands {
    Validate {
        #[arg(long)]
        plugin: Option<String>,
        #[arg(long)]
        profile: Option<String>,
        #[arg(long)]
        input: Option<String>,
    },
    Project {
        #[arg(long)]
        target: String,
        #[arg(long)]
        plugin: String,
        #[arg(long)]
        view: String,
        #[arg(long)]
        input: Option<String>,
    },
    Layout {
        #[arg(long)]
        plugin: String,
        #[arg(long)]
        input: Option<String>,
    },
    ValidateLayout {
        #[arg(long)]
        input: Option<String>,
    },
    Render {
        #[arg(long)]
        plugin: String,
        #[arg(long)]
        policy: String,
        #[arg(long)]
        metadata: Option<String>,
        #[arg(long)]
        input: Option<String>,
    },
    Export {
        #[arg(long)]
        plugin: String,
        #[arg(long)]
        policy: String,
        #[arg(long)]
        source: String,
        #[arg(long)]
        layout: String,
    },
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    match cli.command {
        Some(Commands::Validate {
            plugin,
            profile,
            input,
        }) => {
            let text = read_json_input_or_exit(input.as_deref(), "input")?;
            let base_dir = input_base_dir(input.as_deref());
            match (plugin, profile) {
                (Some(plugin), Some(profile)) => print_plugin_result(
                    dediren_core::commands::semantic_validate_command_with_base(
                        &plugin,
                        &profile,
                        &text,
                        base_dir.as_deref(),
                    ),
                ),
                (Some(_), None) => print_usage_error(
                    "DEDIREN_VALIDATE_PROFILE_REQUIRED",
                    "validate --plugin requires --profile",
                ),
                (None, Some(_)) => print_usage_error(
                    "DEDIREN_VALIDATE_PLUGIN_REQUIRED",
                    "validate --profile requires --plugin",
                ),
                (None, None) => {
                    let (code, envelope) = dediren_core::validate::validate_source_json_with_base(
                        &text,
                        base_dir.as_deref(),
                    );
                    println!("{}", serde_json::to_string(&envelope)?);
                    std::process::exit(code);
                }
            }
        }
        Some(Commands::Project {
            target,
            plugin,
            view,
            input,
        }) => {
            let text = read_json_input_or_exit(input.as_deref(), "input")?;
            let base_dir = input_base_dir(input.as_deref());
            print_plugin_result(dediren_core::commands::project_command_with_base(
                &plugin,
                &target,
                &view,
                &text,
                base_dir.as_deref(),
            ))
        }
        Some(Commands::Layout { plugin, input }) => {
            let text = read_json_input_or_exit(input.as_deref(), "input")?;
            print_plugin_result(dediren_core::commands::layout_command_from_env(
                &plugin, &text,
            ))
        }
        Some(Commands::ValidateLayout { input }) => {
            let text = read_json_input_or_exit(input.as_deref(), "input")?;
            let result: dediren_contracts::LayoutResult =
                dediren_core::io::parse_command_data(&text)?;
            let report = dediren_core::quality::validate_layout(&result);
            let envelope = dediren_contracts::CommandEnvelope::ok(report);
            println!("{}", serde_json::to_string(&envelope)?);
            Ok(())
        }
        Some(Commands::Render {
            plugin,
            policy,
            metadata,
            input,
        }) => {
            let layout_text = read_json_input_or_exit(input.as_deref(), "input")?;
            let policy_text = read_file_or_exit(&policy, "policy")?;
            let metadata_text = metadata
                .as_deref()
                .map(|path| read_file_or_exit(path, "metadata"))
                .transpose()?;
            print_plugin_result(dediren_core::commands::render_command(
                &plugin,
                &policy_text,
                metadata_text.as_deref(),
                &layout_text,
            ))
        }
        Some(Commands::Export {
            plugin,
            policy,
            source,
            layout,
        }) => {
            let source_text = read_file_or_exit(&source, "source")?;
            let source_base_dir = input_base_dir(Some(&source));
            let policy_text = read_file_or_exit(&policy, "policy")?;
            let layout_text = read_file_or_exit(&layout, "layout")?;

            print_plugin_result(dediren_core::commands::export_command_with_base(
                &plugin,
                &policy_text,
                &source_text,
                source_base_dir.as_deref(),
                &layout_text,
            ))
        }
        None => {
            println!("dediren {}", dediren_core::version());
            Ok(())
        }
    }
}

fn input_base_dir(path: Option<&str>) -> Option<std::path::PathBuf> {
    path.map(|path| {
        std::path::Path::new(path)
            .parent()
            .unwrap_or_else(|| std::path::Path::new("."))
            .to_path_buf()
    })
}

fn read_json_input_or_exit(path: Option<&str>, label: &str) -> anyhow::Result<String> {
    match dediren_core::io::read_json_input(path) {
        Ok(text) => Ok(text),
        Err(error) => print_command_input_error(label, path, &error),
    }
}

fn read_file_or_exit(path: &str, label: &str) -> anyhow::Result<String> {
    match std::fs::read_to_string(path) {
        Ok(text) => Ok(text),
        Err(error) => print_command_input_error(label, Some(path), &error),
    }
}

fn print_command_input_error(
    label: &str,
    path: Option<&str>,
    error: &dyn std::fmt::Display,
) -> anyhow::Result<String> {
    let diagnostic_path = path
        .map(|path| format!("{label}:{path}"))
        .unwrap_or_else(|| label.to_string());
    let envelope = dediren_contracts::CommandEnvelope::<serde_json::Value>::error(vec![
        dediren_contracts::Diagnostic {
            code: "DEDIREN_COMMAND_INPUT_INVALID".to_string(),
            severity: dediren_contracts::DiagnosticSeverity::Error,
            message: format!("failed to read {label}: {error}"),
            path: Some(diagnostic_path),
        },
    ]);
    println!("{}", serde_json::to_string(&envelope)?);
    std::process::exit(2);
}

fn print_usage_error(code: &str, message: &str) -> anyhow::Result<()> {
    let envelope = dediren_contracts::CommandEnvelope::<serde_json::Value>::error(vec![
        dediren_contracts::Diagnostic {
            code: code.to_string(),
            severity: dediren_contracts::DiagnosticSeverity::Error,
            message: message.to_string(),
            path: None,
        },
    ]);
    println!("{}", serde_json::to_string(&envelope)?);
    std::process::exit(2);
}

fn print_plugin_result(
    result: Result<
        dediren_core::plugins::PluginRunOutcome,
        dediren_core::plugins::PluginExecutionError,
    >,
) -> anyhow::Result<()> {
    match result {
        Ok(outcome) => {
            print!("{}", outcome.stdout);
            if outcome.exit_code == 0 {
                Ok(())
            } else {
                std::process::exit(outcome.exit_code);
            }
        }
        Err(error) => {
            let envelope = dediren_contracts::CommandEnvelope::<serde_json::Value>::error(vec![
                error.diagnostic(),
            ]);
            println!("{}", serde_json::to_string(&envelope)?);
            std::process::exit(3);
        }
    }
}
