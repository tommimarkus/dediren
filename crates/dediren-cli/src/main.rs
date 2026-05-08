use clap::{Parser, Subcommand};

#[derive(Debug, Parser)]
#[command(name = "dediren")]
#[command(version)]
struct Cli {
    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Debug, Subcommand)]
enum Commands {
    Validate {
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
        Some(Commands::Validate { input }) => {
            let text = dediren_core::io::read_json_input(input.as_deref())?;
            let (code, envelope) = dediren_core::validate::validate_source_json(&text);
            println!("{}", serde_json::to_string(&envelope)?);
            std::process::exit(code);
        }
        Some(Commands::Project {
            target,
            plugin,
            view,
            input,
        }) => {
            let text = dediren_core::io::read_json_input(input.as_deref())?;
            print_plugin_result(dediren_core::plugins::run_plugin_for_capability(
                &plugin,
                "projection",
                &["project", "--target", &target, "--view", &view],
                &text,
            ))
        }
        Some(Commands::Layout { plugin, input }) => {
            let text = dediren_core::io::read_json_input(input.as_deref())?;
            let request: dediren_contracts::LayoutRequest =
                dediren_core::io::parse_command_data(&text)?;
            let mut options = dediren_core::plugins::PluginRunOptions::default();
            for name in ["DEDIREN_ELK_COMMAND", "DEDIREN_ELK_RESULT_FIXTURE"] {
                if let Ok(value) = std::env::var(name) {
                    options.allowed_env.push((name.to_string(), value));
                }
            }
            print_plugin_result(
                dediren_core::plugins::run_plugin_for_capability_with_options(
                    &plugin,
                    "layout",
                    &["layout"],
                    &serde_json::to_string(&request)?,
                    options,
                ),
            )
        }
        Some(Commands::ValidateLayout { input }) => {
            let text = dediren_core::io::read_json_input(input.as_deref())?;
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
            input,
        }) => {
            let layout_text = dediren_core::io::read_json_input(input.as_deref())?;
            let policy_text = std::fs::read_to_string(policy)?;
            let layout_result: dediren_contracts::LayoutResult =
                dediren_core::io::parse_command_data(&layout_text)?;
            let render_input = serde_json::json!({
                "layout_result": layout_result,
                "policy": serde_json::from_str::<serde_json::Value>(&policy_text)?
            });
            print_plugin_result(dediren_core::plugins::run_plugin_for_capability(
                &plugin,
                "render",
                &["render"],
                &serde_json::to_string(&render_input)?,
            ))
        }
        Some(Commands::Export {
            plugin,
            policy,
            source,
            layout,
        }) => {
            let source_text = std::fs::read_to_string(source)?;
            let policy_text = std::fs::read_to_string(policy)?;
            let layout_text = std::fs::read_to_string(layout)?;

            let source_doc: dediren_contracts::SourceDocument = serde_json::from_str(&source_text)?;
            let layout_result: dediren_contracts::LayoutResult =
                dediren_core::io::parse_command_data(&layout_text)?;
            let policy: dediren_contracts::OefExportPolicy = serde_json::from_str(&policy_text)?;

            let export_input = dediren_contracts::OefExportInput {
                export_request_schema_version: dediren_contracts::EXPORT_REQUEST_SCHEMA_VERSION
                    .to_string(),
                source: source_doc,
                layout_result,
                policy,
            };

            print_plugin_result(dediren_core::plugins::run_plugin_for_capability(
                &plugin,
                "export",
                &["export"],
                &serde_json::to_string(&export_input)?,
            ))
        }
        None => {
            println!("dediren {}", dediren_core::version());
            Ok(())
        }
    }
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
