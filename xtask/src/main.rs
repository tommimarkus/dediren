use std::fs::{self, File, OpenOptions};
use std::io;
use std::os::fd::AsRawFd;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, bail, Context, Result};
use clap::{Parser, Subcommand};
use serde_json::json;

const DIST_TARGET: &str = "x86_64-unknown-linux-gnu";
const MIN_JAVA_MAJOR: u32 = 21;
const PLUGIN_BINARIES: &[&str] = &[
    "dediren",
    "dediren-plugin-generic-graph",
    "dediren-plugin-elk-layout",
    "dediren-plugin-svg-render",
    "dediren-plugin-archimate-oef-export",
];
const BUNDLE_PLUGINS: &[&str] = &["generic-graph", "elk-layout", "svg-render", "archimate-oef"];
const CLEAN_ENV: &[&str] = &[
    "DEDIREN_PLUGIN_DIRS",
    "DEDIREN_PLUGIN_GENERIC_GRAPH",
    "DEDIREN_PLUGIN_ELK_LAYOUT",
    "DEDIREN_PLUGIN_SVG_RENDER",
    "DEDIREN_ELK_COMMAND",
    "DEDIREN_ELK_RESULT_FIXTURE",
];

#[derive(Debug, Parser)]
#[command(name = "xtask")]
struct Cli {
    #[arg(long, global = true, hide = true, value_name = "PATH")]
    workspace_root: Option<PathBuf>,
    #[command(subcommand)]
    command: Commands,
}

#[derive(Debug, Subcommand)]
enum Commands {
    Version,
    Dist {
        #[command(subcommand)]
        command: DistCommand,
    },
}

#[derive(Debug, Subcommand)]
enum DistCommand {
    Build,
    Smoke { archive: Option<PathBuf> },
}

fn main() {
    if let Err(error) = run() {
        eprintln!("{error:#}");
        std::process::exit(1);
    }
}

fn run() -> Result<()> {
    let cli = Cli::parse();
    let root = cli
        .workspace_root
        .unwrap_or(std::env::current_dir().context("read current directory")?);

    match cli.command {
        Commands::Version => {
            println!("{}", workspace_version());
            Ok(())
        }
        Commands::Dist { command } => match command {
            DistCommand::Build => build_dist(&root),
            DistCommand::Smoke { archive } => {
                let Some(archive) = archive else {
                    eprintln!(
                        "usage: cargo xtask dist smoke dist/{}.tar.gz",
                        bundle_name(workspace_version(), DIST_TARGET)
                    );
                    std::process::exit(2);
                };
                smoke_dist(&root, &archive)
            }
        },
    }
}

fn build_dist(root: &Path) -> Result<()> {
    let target = std::env::var("DEDIREN_DIST_TARGET").unwrap_or_else(|_| DIST_TARGET.to_string());
    let lock_path = root.join(".cache/locks/build-dist.lock");
    let _lock = FileLock::acquire(
        &lock_path,
        format!(
            "another distribution build is running; waiting for {}",
            lock_path.display()
        ),
    )?;

    if target != DIST_TARGET {
        bail!(
            "cargo xtask dist build supports only DEDIREN_DIST_TARGET={DIST_TARGET}; got: {target}"
        );
    }
    if std::env::consts::OS != "linux" || std::env::consts::ARCH != "x86_64" {
        bail!("cargo xtask dist build currently supports Linux x86_64 only");
    }

    let cargo_target_dir = root.join("target");
    let bin_dir = cargo_target_dir.join(&target).join("release");
    let dist_dir = root.join("dist");
    let bundle_name = bundle_name(workspace_version(), &target);
    let bundle_dir = dist_dir.join(&bundle_name);
    let archive = dist_dir.join(format!("{bundle_name}.tar.gz"));

    println!("checking first-party plugin manifest versions");
    run_status(
        Command::new("cargo")
            .current_dir(root)
            .arg("test")
            .arg("-p")
            .arg("dediren-contracts")
            .arg("--test")
            .arg("schema_contracts")
            .arg("first_party_plugin_manifest_versions_match_workspace_version")
            .arg("--locked"),
    )?;

    println!("building Rust release binaries");
    let mut cargo_build = Command::new("cargo");
    cargo_build
        .current_dir(root)
        .env("CARGO_TARGET_DIR", &cargo_target_dir)
        .env_remove("CARGO_BUILD_TARGET")
        .arg("build")
        .arg("--release")
        .arg("--locked")
        .arg("--target")
        .arg(&target);
    for binary in PLUGIN_BINARIES {
        cargo_build.arg("-p").arg(binary);
    }
    run_status(&mut cargo_build)?;

    println!("building ELK Java helper");
    run_status(&mut Command::new(root.join(
        "crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh",
    )))?;

    println!("assembling {}", bundle_dir.display());
    remove_dir_if_exists(&bundle_dir)?;
    remove_file_if_exists(&archive)?;
    fs::create_dir_all(bundle_dir.join("bin")).context("create bundle bin directory")?;
    fs::create_dir_all(bundle_dir.join("plugins")).context("create bundle plugins directory")?;
    fs::create_dir_all(bundle_dir.join("runtimes")).context("create bundle runtimes directory")?;

    for binary in PLUGIN_BINARIES {
        install_executable(
            &bin_dir.join(binary),
            &bundle_dir.join("bin").join(binary),
            binary,
        )?;
    }

    copy_manifest_files(&root.join("fixtures/plugins"), &bundle_dir.join("plugins"))?;
    copy_dir_recursive(&root.join("schemas"), &bundle_dir.join("schemas"))?;
    copy_fixture_dirs(root, &bundle_dir.join("fixtures"))?;
    copy_dir_recursive(
        &root.join("crates/dediren-plugin-elk-layout/java/build/install/dediren-elk-layout-java"),
        &bundle_dir.join("runtimes/elk-layout-java"),
    )?;
    write_bundle_metadata(&bundle_dir, &target)?;

    println!("creating {}", archive.display());
    run_status(
        Command::new("tar")
            .arg("--owner=0")
            .arg("--group=0")
            .arg("--numeric-owner")
            .arg("-C")
            .arg(&dist_dir)
            .arg("-czf")
            .arg(&archive)
            .arg(&bundle_name),
    )?;

    println!("{}", archive.display());
    Ok(())
}

fn smoke_dist(root: &Path, archive: &Path) -> Result<()> {
    let archive = if archive.is_absolute() {
        archive.to_path_buf()
    } else {
        root.join(archive)
    };
    if !archive.is_file() {
        bail!("archive not found: {}", archive.display());
    }
    ensure_java_runtime()?;

    let temp = TempDir::new("dediren-dist-smoke")?;
    run_status(
        Command::new("tar")
            .arg("-xzf")
            .arg(&archive)
            .arg("-C")
            .arg(temp.path()),
    )?;
    let bundle_dir = find_bundle_dir(temp.path())?;
    if bundle_dir.join("fixtures/plugins").exists() {
        bail!("archive must not include source fixture plugin manifests under fixtures/plugins");
    }

    let bin = bundle_dir.join("bin/dediren");
    run_bundle_command(&bin, &bundle_dir, ["--help"], None)?;

    let request = temp.path().join("request.json");
    let layout = temp.path().join("layout.json");
    let render = temp.path().join("render.json");

    let source_fixture = bundle_dir.join("fixtures/source/valid-pipeline-rich.json");
    let source_fixture = source_fixture
        .to_str()
        .ok_or_else(|| anyhow!("bundle path is not valid UTF-8"))?;
    let request_output = run_bundle_command(
        &bin,
        &bundle_dir,
        [
            "project",
            "--target",
            "layout-request",
            "--plugin",
            "generic-graph",
            "--view",
            "main",
            "--input",
            source_fixture,
        ],
        None,
    )?;
    fs::write(&request, &request_output.stdout).context("write smoke layout request")?;

    let request_path = request
        .to_str()
        .ok_or_else(|| anyhow!("request path is not valid UTF-8"))?;
    let layout_output = run_bundle_command(
        &bin,
        &bundle_dir,
        ["layout", "--plugin", "elk-layout", "--input", request_path],
        None,
    )?;
    fs::write(&layout, &layout_output.stdout).context("write smoke layout result")?;

    let render_policy = bundle_dir.join("fixtures/render-policy/rich-svg.json");
    let render_policy = render_policy
        .to_str()
        .ok_or_else(|| anyhow!("policy path is not valid UTF-8"))?;
    let layout_path = layout
        .to_str()
        .ok_or_else(|| anyhow!("layout path is not valid UTF-8"))?;
    let render_output = run_bundle_command(
        &bin,
        &bundle_dir,
        [
            "render",
            "--plugin",
            "svg-render",
            "--policy",
            render_policy,
            "--input",
            layout_path,
        ],
        None,
    )?;
    fs::write(&render, &render_output.stdout).context("write smoke render result")?;
    assert_render_output(&render_output.stdout)?;

    println!("distribution smoke test passed: {}", archive.display());
    Ok(())
}

fn workspace_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

fn bundle_name(version: &str, target: &str) -> String {
    format!("dediren-agent-bundle-{version}-{target}")
}

fn run_status(command: &mut Command) -> Result<()> {
    let status = command
        .status()
        .with_context(|| format!("start command: {command:?}"))?;
    if !status.success() {
        bail!("command failed with status {status}: {command:?}");
    }
    Ok(())
}

fn run_bundle_command<const N: usize>(
    bin: &Path,
    bundle_dir: &Path,
    args: [&str; N],
    stdin_file: Option<&Path>,
) -> Result<Output> {
    let mut command = Command::new(bin);
    command.current_dir(bundle_dir).args(args);
    for name in CLEAN_ENV {
        command.env_remove(name);
    }
    if let Some(stdin_file) = stdin_file {
        command.stdin(File::open(stdin_file)?);
    }
    command.stdout(Stdio::piped()).stderr(Stdio::piped());

    let output = command
        .output()
        .with_context(|| format!("start bundled command: {command:?}"))?;
    if !output.status.success() {
        bail!(
            "bundled command failed with status {:?}\nstdout:\n{}\nstderr:\n{}",
            output.status.code(),
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
    }
    Ok(output)
}

fn ensure_java_runtime() -> Result<()> {
    let output = Command::new("java")
        .arg("-version")
        .output()
        .context("java is required on PATH for the distribution smoke test")?;
    if !output.status.success() {
        bail!("java -version failed");
    }
    let text = format!(
        "{}\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let Some(major) = parse_java_major(&text) else {
        bail!("could not parse java -version output");
    };
    if major < MIN_JAVA_MAJOR {
        bail!("Java {MIN_JAVA_MAJOR} or newer is required on PATH for the bundled ELK helper");
    }
    Ok(())
}

fn parse_java_major(text: &str) -> Option<u32> {
    let version = text.split('"').nth(1).or_else(|| {
        text.split_whitespace()
            .find(|part| part.starts_with(|c: char| c.is_ascii_digit()))
    })?;
    let mut parts = version.split('.');
    let first = parts.next()?.parse::<u32>().ok()?;
    if first == 1 {
        parts.next()?.parse::<u32>().ok()
    } else {
        Some(first)
    }
}

fn find_bundle_dir(temp: &Path) -> Result<PathBuf> {
    let mut matches = fs::read_dir(temp)
        .context("read extracted archive directory")?
        .filter_map(|entry| entry.ok())
        .filter_map(|entry| {
            let file_type = entry.file_type().ok()?;
            if !file_type.is_dir() {
                return None;
            }
            let name = entry.file_name();
            let name = name.to_string_lossy();
            name.starts_with("dediren-agent-bundle-")
                .then(|| entry.path())
        })
        .collect::<Vec<_>>();
    matches.sort();
    matches
        .pop()
        .ok_or_else(|| anyhow!("archive did not contain a dediren-agent-bundle directory"))
}

fn assert_render_output(stdout: &[u8]) -> Result<()> {
    let value: serde_json::Value =
        serde_json::from_slice(stdout).context("render smoke output should be JSON")?;
    if value["status"] != "ok" {
        bail!("render smoke output status should be ok");
    }
    if value["data"]["artifact_kind"] != "svg" {
        bail!("render smoke output artifact_kind should be svg");
    }
    let content = value["data"]["content"]
        .as_str()
        .ok_or_else(|| anyhow!("render smoke output content should be a string"))?;
    if !content.contains("<svg") {
        bail!("render smoke output should contain SVG content");
    }
    Ok(())
}

fn install_executable(source: &Path, destination: &Path, name: &str) -> Result<()> {
    fs::copy(source, destination)
        .with_context(|| format!("install release binary {name} from {}", source.display()))?;
    let mut permissions = fs::metadata(destination)?.permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(destination, permissions)?;
    Ok(())
}

fn copy_manifest_files(source_dir: &Path, destination_dir: &Path) -> Result<()> {
    for entry in fs::read_dir(source_dir)
        .with_context(|| format!("read plugin manifests from {}", source_dir.display()))?
    {
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name.ends_with(".manifest.json") {
            fs::copy(entry.path(), destination_dir.join(name.as_ref()))
                .with_context(|| format!("copy plugin manifest {name}"))?;
        }
    }
    Ok(())
}

fn copy_fixture_dirs(root: &Path, destination: &Path) -> Result<()> {
    fs::create_dir_all(destination).context("create bundle fixtures directory")?;
    for entry in fs::read_dir(root.join("fixtures")).context("read fixtures directory")? {
        let entry = entry?;
        if entry.file_name() == std::ffi::OsStr::new("plugins") {
            continue;
        }
        let source = entry.path();
        let target = destination.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir_recursive(&source, &target)?;
        } else {
            fs::copy(&source, &target)
                .with_context(|| format!("copy fixture {}", source.display()))?;
        }
    }
    Ok(())
}

fn copy_dir_recursive(source: &Path, destination: &Path) -> Result<()> {
    fs::create_dir_all(destination)
        .with_context(|| format!("create directory {}", destination.display()))?;
    for entry in
        fs::read_dir(source).with_context(|| format!("read directory {}", source.display()))?
    {
        let entry = entry?;
        let file_type = entry.file_type()?;
        let source_path = entry.path();
        let destination_path = destination.join(entry.file_name());
        if file_type.is_dir() {
            copy_dir_recursive(&source_path, &destination_path)?;
        } else if file_type.is_file() {
            fs::copy(&source_path, &destination_path).with_context(|| {
                format!(
                    "copy {} to {}",
                    source_path.display(),
                    destination_path.display()
                )
            })?;
        }
    }
    Ok(())
}

fn write_bundle_metadata(bundle_dir: &Path, target: &str) -> Result<()> {
    let plugins = BUNDLE_PLUGINS
        .iter()
        .map(|id| json!({ "id": id, "version": workspace_version() }))
        .collect::<Vec<_>>();
    let metadata = json!({
        "bundle_schema_version": "dediren-bundle.schema.v1",
        "product": "dediren",
        "version": workspace_version(),
        "target": target,
        "built_at_utc": build_time_utc()?,
        "plugins": plugins,
        "schemas_dir": "schemas",
        "fixtures_dir": "fixtures",
        "elk_helper": "runtimes/elk-layout-java/bin/dediren-elk-layout-java"
    });
    fs::write(
        bundle_dir.join("bundle.json"),
        format!("{}\n", serde_json::to_string_pretty(&metadata)?),
    )
    .context("write bundle metadata")
}

fn build_time_utc() -> Result<String> {
    let output = Command::new("date")
        .arg("-u")
        .arg("+%Y-%m-%dT%H:%M:%SZ")
        .output()
        .context("run date for bundle build timestamp")?;
    if !output.status.success() {
        bail!("date command failed");
    }
    Ok(String::from_utf8(output.stdout)
        .context("date output should be UTF-8")?
        .trim()
        .to_string())
}

fn remove_dir_if_exists(path: &Path) -> Result<()> {
    match fs::remove_dir_all(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error).with_context(|| format!("remove directory {}", path.display())),
    }
}

fn remove_file_if_exists(path: &Path) -> Result<()> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error).with_context(|| format!("remove file {}", path.display())),
    }
}

struct FileLock {
    file: File,
}

impl FileLock {
    fn acquire(path: &Path, waiting_message: String) -> Result<Self> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)
                .with_context(|| format!("create lock directory {}", parent.display()))?;
        }
        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .open(path)
            .with_context(|| format!("open lock file {}", path.display()))?;
        let fd = file.as_raw_fd();
        let nonblocking = unsafe { libc::flock(fd, libc::LOCK_EX | libc::LOCK_NB) };
        if nonblocking == 0 {
            return Ok(Self { file });
        }

        let error = io::Error::last_os_error();
        if !matches!(
            error.raw_os_error(),
            Some(code) if code == libc::EWOULDBLOCK || code == libc::EAGAIN
        ) {
            return Err(error).with_context(|| format!("lock {}", path.display()));
        }

        eprintln!("{waiting_message}");
        let blocking = unsafe { libc::flock(fd, libc::LOCK_EX) };
        if blocking != 0 {
            return Err(io::Error::last_os_error())
                .with_context(|| format!("lock {}", path.display()));
        }
        Ok(Self { file })
    }
}

impl Drop for FileLock {
    fn drop(&mut self) {
        let _ = unsafe { libc::flock(self.file.as_raw_fd(), libc::LOCK_UN) };
    }
}

struct TempDir {
    path: PathBuf,
}

impl TempDir {
    fn new(prefix: &str) -> Result<Self> {
        let pid = std::process::id();
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .context("system time is before UNIX_EPOCH")?
            .as_nanos();
        let path = std::env::temp_dir().join(format!("{prefix}-{pid}-{nanos}"));
        fs::create_dir(&path)
            .with_context(|| format!("create temp directory {}", path.display()))?;
        Ok(Self { path })
    }

    fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}
