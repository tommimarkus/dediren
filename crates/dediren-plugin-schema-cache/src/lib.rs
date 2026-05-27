use std::path::{Path, PathBuf};
use std::process::{Command, ExitStatus};

pub fn non_empty_env_path(name: &str) -> Option<PathBuf> {
    std::env::var_os(name)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
}

pub fn is_non_empty_file(path: &Path) -> bool {
    path.metadata()
        .map(|metadata| metadata.is_file() && metadata.len() > 0)
        .unwrap_or(false)
}

pub fn schema_cache_base_dir(cache_dir_env: &str, fallback_env: &str) -> Result<PathBuf, String> {
    if let Some(configured) = non_empty_env_path(cache_dir_env) {
        return Ok(configured);
    }
    if let Some(configured) = non_empty_env_path("XDG_CACHE_HOME") {
        return Ok(configured.join("dediren").join("schemas"));
    }
    if let Some(configured) = non_empty_env_path("LOCALAPPDATA") {
        return Ok(configured.join("dediren").join("schemas"));
    }
    if let Some(home) = non_empty_env_path("HOME") {
        return Ok(home.join(".cache").join("dediren").join("schemas"));
    }
    Err(format!(
        "cannot determine schema cache directory; set {cache_dir_env} or {fallback_env}"
    ))
}

pub fn ensure_cached_schema_file(
    schema_path: &Path,
    url: &str,
    description: &str,
    fetcher: &str,
) -> Result<(), String> {
    if is_non_empty_file(schema_path) {
        return Ok(());
    }

    let parent = schema_path.parent().ok_or_else(|| {
        format!(
            "schema cache path {} has no parent directory",
            schema_path.display()
        )
    })?;
    std::fs::create_dir_all(parent).map_err(|error| {
        format!(
            "failed to create schema cache directory {}: {error}",
            parent.display()
        )
    })?;
    let temp = tempfile::NamedTempFile::new_in(parent).map_err(|error| {
        format!(
            "failed to prepare temporary {description} download in {}: {error}",
            parent.display()
        )
    })?;

    let output = Command::new(fetcher)
        .args([
            "--location",
            "--fail",
            "--silent",
            "--show-error",
            url,
            "--output",
        ])
        .arg(temp.path())
        .output()
        .map_err(|error| {
            format!("failed to start {fetcher} to download {description} from {url}: {error}")
        })?;
    if !output.status.success() {
        return Err(format!(
            "failed to download {description} from {url}: {}",
            command_output_details(&output.stdout, &output.stderr, fetcher, output.status)
        ));
    }
    if !is_non_empty_file(temp.path()) {
        return Err(format!("downloaded {description} from {url} was empty"));
    }

    match temp.persist(schema_path) {
        Ok(_) => Ok(()),
        Err(_) if is_non_empty_file(schema_path) => Ok(()),
        Err(error) => Err(format!(
            "failed to store {description} in {}: {}",
            schema_path.display(),
            error.error
        )),
    }
}

pub fn command_output_details(
    stdout: &[u8],
    stderr: &[u8],
    fallback_command: &str,
    status: ExitStatus,
) -> String {
    let mut details = String::from_utf8_lossy(stderr).trim().to_string();
    let stdout = String::from_utf8_lossy(stdout);
    let stdout = stdout.trim();
    if !stdout.is_empty() {
        if !details.is_empty() {
            details.push('\n');
        }
        details.push_str(stdout);
    }
    if details.is_empty() {
        details = format!("{fallback_command} exited with status {status}");
    }
    details
}
