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
            print_plugin_result(dediren_core::commands::project_command(
                &plugin, &target, &view, &text,
            ))
        }
        Some(Commands::Layout { plugin, input }) => {
            let text = dediren_core::io::read_json_input(input.as_deref())?;
            print_plugin_result(dediren_core::commands::layout_command_from_env(
                &plugin, &text,
            ))
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
            metadata,
            input,
        }) => {
            let layout_text = dediren_core::io::read_json_input(input.as_deref())?;
            let policy_text = std::fs::read_to_string(policy)?;
            let metadata_text = metadata
                .as_deref()
                .map(std::fs::read_to_string)
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
            let source_text = std::fs::read_to_string(source)?;
            let policy_text = std::fs::read_to_string(policy)?;
            let layout_text = std::fs::read_to_string(layout)?;

            print_plugin_result(dediren_core::commands::export_command(
                &plugin,
                &policy_text,
                &source_text,
                &layout_text,
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
