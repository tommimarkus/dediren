package dev.dediren.schemacache;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;

public final class SchemaCacheModule {
  private SchemaCacheModule() {}

  public static String moduleName() {
    return "schema-cache";
  }

  public static Optional<Path> nonEmptyEnvPath(String name) {
    return nonEmptyEnvPath(System.getenv(), name);
  }

  public static Optional<Path> nonEmptyEnvPath(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(Path.of(value));
  }

  public static boolean isNonEmptyFile(Path path) {
    try {
      return Files.isRegularFile(path) && Files.size(path) > 0;
    } catch (IOException error) {
      return false;
    }
  }

  public static Path schemaCacheBaseDir(String cacheDirEnv, String fallbackEnv)
      throws SchemaCacheException {
    return schemaCacheBaseDir(System.getenv(), cacheDirEnv, fallbackEnv);
  }

  public static Path schemaCacheBaseDir(
      Map<String, String> env, String cacheDirEnv, String fallbackEnv) throws SchemaCacheException {
    Optional<Path> cacheDir = nonEmptyEnvPath(env, cacheDirEnv);
    if (cacheDir.isPresent()) {
      return cacheDir.get();
    }
    Optional<Path> xdgCacheHome = nonEmptyEnvPath(env, "XDG_CACHE_HOME");
    if (xdgCacheHome.isPresent()) {
      return xdgCacheHome.get().resolve("dediren").resolve("schemas");
    }
    Optional<Path> localAppData = nonEmptyEnvPath(env, "LOCALAPPDATA");
    if (localAppData.isPresent()) {
      return localAppData.get().resolve("dediren").resolve("schemas");
    }
    Optional<Path> home = nonEmptyEnvPath(env, "HOME");
    if (home.isPresent()) {
      return home.get().resolve(".cache").resolve("dediren").resolve("schemas");
    }
    throw new SchemaCacheException(
        "cannot determine schema cache directory; set " + cacheDirEnv + " or " + fallbackEnv);
  }

  public static void ensureCachedSchemaFile(
      Path schemaPath, URI url, String description, SchemaFetcher fetcher)
      throws SchemaCacheException {
    if (isNonEmptyFile(schemaPath)) {
      return;
    }

    Path parent = schemaPath.getParent();
    if (parent == null) {
      throw new SchemaCacheException(
          "schema cache path " + schemaPath + " has no parent directory");
    }
    try {
      Files.createDirectories(parent);
    } catch (IOException error) {
      throw new SchemaCacheException(
          "failed to create schema cache directory " + parent + ": " + error.getMessage(), error);
    }

    Path tempFile;
    try {
      tempFile = Files.createTempFile(parent, ".dediren-schema-", ".tmp");
    } catch (IOException error) {
      throw new SchemaCacheException(
          "failed to prepare temporary "
              + description
              + " download in "
              + parent
              + ": "
              + error.getMessage(),
          error);
    }

    try {
      SchemaFetchResult result = fetcher.fetch(url, tempFile);
      if (!result.succeeded()) {
        throw new SchemaCacheException(
            "failed to download "
                + description
                + " from "
                + url
                + ": "
                + commandOutputDetails(
                    result.command(), result.exitCode(), result.stdout(), result.stderr()));
      }
      if (!isNonEmptyFile(tempFile)) {
        throw new SchemaCacheException("downloaded " + description + " from " + url + " was empty");
      }
      try {
        Files.move(
            tempFile,
            schemaPath,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException atomicMoveError) {
        Files.move(tempFile, schemaPath, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (SchemaCacheException error) {
      throw error;
    } catch (Exception error) {
      throw new SchemaCacheException(
          "failed to download " + description + " from " + url + ": " + error.getMessage(), error);
    } finally {
      try {
        Files.deleteIfExists(tempFile);
      } catch (IOException ignored) {
        // Best-effort cleanup for failed or raced cache writes.
      }
    }
  }

  public static SchemaFetcher curlFetcher(String command) {
    return (url, destination) -> {
      Process process =
          new ProcessBuilder(
                  command,
                  "--location",
                  "--fail",
                  "--silent",
                  "--show-error",
                  url.toString(),
                  "--output",
                  destination.toString())
              .start();
      byte[] stdout = process.getInputStream().readAllBytes();
      byte[] stderr = process.getErrorStream().readAllBytes();
      int exitCode = process.waitFor();
      return new SchemaFetchResult(exitCode == 0, command, exitCode, stdout, stderr);
    };
  }

  public static String commandOutputDetails(
      String fallbackCommand, int exitCode, byte[] stdout, byte[] stderr) {
    String details = new String(stderr, StandardCharsets.UTF_8).trim();
    String stdoutText = new String(stdout, StandardCharsets.UTF_8).trim();
    if (!stdoutText.isEmpty()) {
      if (!details.isEmpty()) {
        details += "\n";
      }
      details += stdoutText;
    }
    if (details.isEmpty()) {
      return fallbackCommand + " exited with status " + exitCode;
    }
    return details;
  }
}
