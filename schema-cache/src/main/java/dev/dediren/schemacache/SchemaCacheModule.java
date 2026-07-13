package dev.dediren.schemacache;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
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

  /**
   * Decision 9 resolution site: rewrites the named relative schema/cache env values so they resolve
   * against {@code productRoot} rather than the JVM cwd. {@link Path#resolve(Path)} returns an
   * absolute value unchanged, so a caller that supplies the child cwd as the product root gets
   * byte-identical behavior to a bare {@code Path.of(value)}.
   *
   * <p>Shared by both export engines. Engines may not depend on each other, so this lives in the
   * schema-cache seam they both already depend on rather than in a copy apiece.
   */
  public static Map<String, String> productRootRelativeEnv(
      Map<String, String> env, Path productRoot, String... pathEnvNames) {
    Map<String, String> resolved = new LinkedHashMap<>(env);
    for (String name : pathEnvNames) {
      String value = env.get(name);
      if (value != null && !value.isEmpty()) {
        resolved.put(name, productRoot.resolve(value).toString());
      }
    }
    return resolved;
  }

  /** Returns the validator command an env override names, or {@code defaultCommand} if unset. */
  public static String configuredValidator(
      Map<String, String> env, String validatorEnvName, String defaultCommand) {
    String configured = env.get(validatorEnvName);
    return configured == null || configured.isBlank() ? defaultCommand : configured;
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
      Path schemaPath, URI url, String description, String expectedSha256, SchemaFetcher fetcher)
      throws SchemaCacheException {
    // A cached file is trusted only when its bytes still match the pinned SHA-256 (audit finding
    // F2). A mismatch means the cache is corrupt, stale, or poisoned, so we re-fetch rather than
    // serve it.
    if (isNonEmptyFile(schemaPath) && fileMatchesSha256(schemaPath, expectedSha256)) {
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
      String actualSha256 = sha256Hex(tempFile);
      if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
        throw new SchemaCacheException(
            "downloaded "
                + description
                + " from "
                + url
                + " does not match the pinned sha-256 for "
                + schemaPath
                + ": expected "
                + expectedSha256
                + " but got "
                + actualSha256);
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
                  // Forbid protocol downgrade when following redirects: only https is allowed for
                  // the initial request and every redirect hop (audit finding F2).
                  "--proto",
                  "=https",
                  "--location",
                  "--fail",
                  "--silent",
                  "--show-error",
                  url.toString(),
                  "--output",
                  destination.toString())
              .start();
      // Both pipes are drained concurrently: a verbose fetch failure that fills the ~64 KiB stderr
      // pipe would otherwise block the child in write(2) forever while this thread waited on a
      // stdout EOF that can never arrive.
      StreamDrain stdout = StreamDrain.start(process.getInputStream());
      StreamDrain stderr = StreamDrain.start(process.getErrorStream());
      int exitCode = process.waitFor();
      return new SchemaFetchResult(
          exitCode == 0, command, exitCode, stdout.await(), stderr.await());
    };
  }

  private static boolean fileMatchesSha256(Path path, String expectedSha256) {
    try {
      return sha256Hex(path).equalsIgnoreCase(expectedSha256);
    } catch (IOException error) {
      return false;
    }
  }

  private static String sha256Hex(Path path) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 message digest is required but unavailable", error);
    }
    byte[] hash = digest.digest(Files.readAllBytes(path));
    StringBuilder hex = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16));
      hex.append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
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
