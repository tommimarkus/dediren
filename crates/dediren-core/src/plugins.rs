use dediren_contracts::{PluginManifest, RuntimeCapabilities};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::time::{Duration, Instant};

#[derive(Debug, Clone)]
pub struct PluginRegistry {
    manifest_dirs: Vec<PathBuf>,
}

#[derive(Debug, Clone)]
pub struct PluginRunOptions {
    pub timeout: Duration,
    pub allowed_env: Vec<(String, String)>,
}

impl Default for PluginRunOptions {
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(10),
            allowed_env: Vec::new(),
        }
    }
}

impl PluginRegistry {
    pub fn bundled() -> Self {
        let mut manifest_dirs = vec![
            PathBuf::from("fixtures/plugins"),
            PathBuf::from(".dediren/plugins"),
        ];
        if let Ok(configured) = std::env::var("DEDIREN_PLUGIN_DIRS") {
            manifest_dirs.extend(std::env::split_paths(&configured));
        }
        Self { manifest_dirs }
    }

    pub fn load_manifest(&self, plugin_id: &str) -> anyhow::Result<PluginManifest> {
        for dir in &self.manifest_dirs {
            let path = dir.join(format!("{plugin_id}.manifest.json"));
            if path.exists() {
                let text = std::fs::read_to_string(path)?;
                let manifest: PluginManifest = serde_json::from_str(&text)?;
                if manifest.id == plugin_id {
                    return Ok(manifest);
                }
            }
        }
        anyhow::bail!("unknown plugin id: {plugin_id}")
    }
}

pub fn run_plugin(plugin_id: &str, args: &[&str], input: &str) -> anyhow::Result<String> {
    run_plugin_with_options(plugin_id, args, input, PluginRunOptions::default())
}

pub fn run_plugin_with_options(
    plugin_id: &str,
    args: &[&str],
    input: &str,
    options: PluginRunOptions,
) -> anyhow::Result<String> {
    let registry = PluginRegistry::bundled();
    let manifest = registry.load_manifest(plugin_id)?;
    let executable = executable_path(&manifest)?;
    let capabilities = probe_capabilities(&executable, &options)?;
    if capabilities.id != manifest.id {
        anyhow::bail!(
            "plugin capability id '{}' did not match manifest id '{}'",
            capabilities.id,
            manifest.id
        );
    }

    let output = run_executable_with_timeout(&executable, args, input, &options)?;
    if !output.status.success() {
        return Err(anyhow::anyhow!(
            "plugin {plugin_id} exited with status {:?}: stdout={} stderr={}",
            output.status.code(),
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        ));
    }

    Ok(String::from_utf8(output.stdout)?)
}

fn run_executable_with_timeout(
    executable: &Path,
    args: &[&str],
    input: &str,
    options: &PluginRunOptions,
) -> anyhow::Result<Output> {
    let mut child = Command::new(executable)
        .args(args)
        .env_clear()
        .envs(options.allowed_env.iter().cloned())
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;

    if let Some(stdin) = child.stdin.as_mut() {
        stdin.write_all(input.as_bytes())?;
    }
    drop(child.stdin.take());

    let deadline = Instant::now() + options.timeout;
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            anyhow::bail!("plugin process timed out after {:?}", options.timeout);
        }
        std::thread::sleep(Duration::from_millis(10));
    };

    let mut stdout = Vec::new();
    if let Some(mut pipe) = child.stdout.take() {
        pipe.read_to_end(&mut stdout)?;
    }
    let mut stderr = Vec::new();
    if let Some(mut pipe) = child.stderr.take() {
        pipe.read_to_end(&mut stderr)?;
    }

    Ok(Output {
        status,
        stdout,
        stderr,
    })
}

fn executable_path(manifest: &PluginManifest) -> anyhow::Result<PathBuf> {
    let env_name = format!(
        "DEDIREN_PLUGIN_{}",
        manifest.id.to_ascii_uppercase().replace('-', "_")
    );
    if let Ok(path) = std::env::var(env_name) {
        return Ok(PathBuf::from(path));
    }
    Ok(std::env::current_exe()?.with_file_name(&manifest.executable))
}

fn probe_capabilities(
    executable: &Path,
    options: &PluginRunOptions,
) -> anyhow::Result<RuntimeCapabilities> {
    let output = run_executable_with_timeout(executable, &["capabilities"], "", options)?;
    if !output.status.success() {
        anyhow::bail!(
            "plugin capability probe failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
    Ok(serde_json::from_slice(&output.stdout)?)
}
