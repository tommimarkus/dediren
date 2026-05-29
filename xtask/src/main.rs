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

const DEFAULT_DIST_TARGET: &str = "x86_64-unknown-linux-gnu";
struct DistTarget {
    triple: &'static str,
    host_os: &'static str,
    host_arch: &'static str,
}
const DIST_TARGETS: &[DistTarget] = &[
    DistTarget {
        triple: "x86_64-unknown-linux-gnu",
        host_os: "linux",
        host_arch: "x86_64",
    },
    DistTarget {
        triple: "aarch64-unknown-linux-gnu",
        host_os: "linux",
        host_arch: "aarch64",
    },
    DistTarget {
        triple: "aarch64-apple-darwin",
        host_os: "macos",
        host_arch: "aarch64",
    },
];
const PLUGIN_BINARIES: &[&str] = &[
    "dediren",
    "dediren-plugin-generic-graph",
    "dediren-plugin-elk-layout",
    "dediren-plugin-svg-render",
    "dediren-plugin-archimate-oef-export",
    "dediren-plugin-uml-xmi-export",
];
const BUNDLE_PLUGINS: &[&str] = &[
    "generic-graph",
    "elk-layout",
    "svg-render",
    "archimate-oef",
    "uml-xmi",
];
const CLEAN_ENV: &[&str] = &[
    "DEDIREN_PLUGIN_DIRS",
    "DEDIREN_PLUGIN_GENERIC_GRAPH",
    "DEDIREN_PLUGIN_ELK_LAYOUT",
    "DEDIREN_PLUGIN_SVG_RENDER",
    "DEDIREN_PLUGIN_ARCHIMATE_OEF",
    "DEDIREN_PLUGIN_UML_XMI",
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
    Build {
        #[arg(long, value_name = "TRIPLE")]
        target: Option<String>,
    },
    Smoke {
        archive: Option<PathBuf>,
    },
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
            DistCommand::Build { target } => build_dist(&root, target.as_deref()),
            DistCommand::Smoke { archive } => {
                let Some(archive) = archive else {
                    eprintln!(
                        "usage: cargo xtask dist smoke dist/{}.tar.gz",
                        bundle_name(workspace_version(), DEFAULT_DIST_TARGET)
                    );
                    std::process::exit(2);
                };
                smoke_dist(&root, &archive)
            }
        },
    }
}

fn build_dist(root: &Path, requested_target: Option<&str>) -> Result<()> {
    let target = resolve_dist_target(requested_target)?;
    ensure_host_can_build(target)?;

    let lock_path = root.join(".cache/locks/build-dist.lock");
    let _lock = FileLock::acquire(
        &lock_path,
        format!(
            "another distribution build is running; waiting for {}",
            lock_path.display()
        ),
    )?;

    let cargo_target_dir = root.join("target");
    let bin_dir = cargo_target_dir.join(target.triple).join("release");
    let dist_dir = root.join("dist");
    let bundle_name = bundle_name(workspace_version(), target.triple);
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
        .arg(target.triple);
    for binary in PLUGIN_BINARIES {
        cargo_build.arg("-p").arg(binary);
    }
    run_status(&mut cargo_build)?;

    println!("generating Rust third-party notices");
    let rust_notice = generate_rust_third_party_notices(root, target.triple)?;

    println!("assembling {}", bundle_dir.display());
    remove_dir_if_exists(&bundle_dir)?;
    remove_file_if_exists(&archive)?;
    fs::create_dir_all(bundle_dir.join("bin")).context("create bundle bin directory")?;
    fs::create_dir_all(bundle_dir.join("plugins")).context("create bundle plugins directory")?;
    fs::create_dir_all(bundle_dir.join("docs")).context("create bundle docs directory")?;

    for binary in PLUGIN_BINARIES {
        install_executable(
            &release_binary_path(&bin_dir, binary, target),
            &bundle_dir.join("bin").join(binary),
            binary,
        )?;
    }

    copy_manifest_files(&root.join("fixtures/plugins"), &bundle_dir.join("plugins"))?;
    copy_dir_recursive(&root.join("schemas"), &bundle_dir.join("schemas"))?;
    copy_fixture_dirs(root, &bundle_dir.join("fixtures"))?;
    copy_agent_docs(root, &bundle_dir.join("docs"))?;
    copy_license_notice(root, &bundle_dir)?;
    write_third_party_notices(root, &bundle_dir, &rust_notice)?;
    write_bundle_metadata(&bundle_dir, target)?;

    println!("creating {}", archive.display());
    run_status(
        Command::new("tar")
            .arg("-C")
            .arg(&dist_dir)
            .arg("-czf")
            .arg(&archive)
            .arg(&bundle_name),
    )?;
    prune_stale_dist_artifacts(&dist_dir, &bundle_name)?;

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

fn resolve_dist_target(requested: Option<&str>) -> Result<&'static DistTarget> {
    if let Some(requested) = requested {
        return find_dist_target(requested);
    }
    if let Ok(requested) = std::env::var("DEDIREN_DIST_TARGET") {
        return find_dist_target(&requested);
    }

    Ok(current_host_dist_target().unwrap_or_else(|| {
        DIST_TARGETS
            .iter()
            .find(|target| target.triple == DEFAULT_DIST_TARGET)
            .expect("DEFAULT_DIST_TARGET must be listed in DIST_TARGETS")
    }))
}

fn ensure_host_can_build(target: &DistTarget) -> Result<()> {
    let host_os = std::env::consts::OS;
    let host_arch = std::env::consts::ARCH;
    if host_os == target.host_os && host_arch == target.host_arch {
        return Ok(());
    }

    bail!(
        "distribution target {} must be built on {} {}; current host is {} {}",
        target.triple,
        target.host_os,
        target.host_arch,
        host_os,
        host_arch
    )
}

fn release_binary_path(bin_dir: &Path, binary: &str, _target: &DistTarget) -> PathBuf {
    bin_dir.join(binary)
}

fn current_host_dist_target() -> Option<&'static DistTarget> {
    let host_os = std::env::consts::OS;
    let host_arch = std::env::consts::ARCH;
    DIST_TARGETS
        .iter()
        .find(|target| target.host_os == host_os && target.host_arch == host_arch)
}

fn find_dist_target(requested: &str) -> Result<&'static DistTarget> {
    DIST_TARGETS
        .iter()
        .find(|target| target.triple == requested)
        .ok_or_else(|| {
            anyhow!(
                "unsupported distribution target: {requested}; supported targets: {}",
                supported_dist_targets()
            )
        })
}

fn supported_dist_targets() -> String {
    DIST_TARGETS
        .iter()
        .map(|target| target.triple)
        .collect::<Vec<_>>()
        .join(", ")
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

fn copy_agent_docs(root: &Path, destination: &Path) -> Result<()> {
    fs::copy(
        root.join("docs/agent-usage.md"),
        destination.join("agent-usage.md"),
    )
    .context("copy agent usage guide")?;
    Ok(())
}

fn copy_license_notice(root: &Path, bundle_dir: &Path) -> Result<()> {
    fs::copy(root.join("LICENSE"), bundle_dir.join("LICENSE")).context("copy LICENSE")?;
    Ok(())
}

fn generate_rust_third_party_notices(root: &Path, target: &str) -> Result<PathBuf> {
    let output_dir = root.join("target/dediren-third-party-notices");
    fs::create_dir_all(&output_dir).with_context(|| format!("create {}", output_dir.display()))?;
    let output = output_dir.join("rust-dependencies.md");
    run_status(
        Command::new("cargo")
            .current_dir(root)
            .arg("about")
            .arg("generate")
            .arg("--workspace")
            .arg("--locked")
            .arg("--offline")
            .arg("--fail")
            .arg("--config")
            .arg(root.join(".cargo/about.toml"))
            .arg("--target")
            .arg(target)
            .arg("--output-file")
            .arg(&output)
            .arg(root.join(".cargo/about.hbs")),
    )
    .context("generate Rust third-party notices with cargo-about")?;
    Ok(output)
}

fn write_third_party_notices(_root: &Path, bundle_dir: &Path, rust_notice: &Path) -> Result<()> {
    let rust_notice = fs::read_to_string(rust_notice)
        .with_context(|| format!("read {}", rust_notice.display()))?;

    let combined = format!(
        "# Third-Party Notices\n\n\
         Dediren's own source and binaries are covered by the root LICENSE file. \
         The sections below are generated reports for third-party dependencies \
         redistributed in or compiled into the distribution bundle.\n\n\
         {rust_notice}"
    );
    fs::write(bundle_dir.join("THIRD-PARTY-NOTICES.md"), combined)
        .context("write THIRD-PARTY-NOTICES.md")?;
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

fn write_bundle_metadata(bundle_dir: &Path, target: &DistTarget) -> Result<()> {
    let plugins = BUNDLE_PLUGINS
        .iter()
        .map(|id| json!({ "id": id, "version": workspace_version() }))
        .collect::<Vec<_>>();
    let metadata = json!({
        "bundle_schema_version": "dediren-bundle.schema.v1",
        "product": "dediren",
        "version": workspace_version(),
        "target": target.triple,
        "built_at_utc": build_time_utc()?,
        "plugins": plugins,
        "schemas_dir": "schemas",
        "fixtures_dir": "fixtures",
        "docs_dir": "docs"
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

fn prune_stale_dist_artifacts(dist_dir: &Path, current_bundle_name: &str) -> Result<()> {
    let current_archive_name = format!("{current_bundle_name}.tar.gz");
    for entry in fs::read_dir(dist_dir)
        .with_context(|| format!("read dist directory {}", dist_dir.display()))?
    {
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if !name.starts_with("dediren-agent-bundle-") {
            continue;
        }

        let file_type = entry.file_type()?;
        let path = entry.path();
        if file_type.is_dir() && name != current_bundle_name {
            fs::remove_dir_all(&path)
                .with_context(|| format!("remove stale bundle directory {}", path.display()))?;
        } else if file_type.is_file() && name.ends_with(".tar.gz") && name != current_archive_name {
            fs::remove_file(&path)
                .with_context(|| format!("remove stale bundle archive {}", path.display()))?;
        }
    }
    Ok(())
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
            .truncate(false)
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
