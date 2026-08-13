package dev.dediren.schemacache;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SchemaCacheModule {
  // debug/trace only, by architecture rule: a cache failure an agent must act on is a
  // SchemaCacheException, not a log line. See ArchitectureRulesTest.
  private static final Logger LOG = LoggerFactory.getLogger(SchemaCacheModule.class);

  /**
   * The env var naming the shared schema-cache directory. Both export engines fetch into the same
   * cache, so the name lives here (the seam both already depend on): a rename in an engine-local
   * copy would silently split the common cache in two.
   */
  public static final String SCHEMA_CACHE_DIR_ENV = "DEDIREN_SCHEMA_CACHE_DIR";

  private static final long MAX_SCHEMA_BYTES = 8L * 1024 * 1024;
  static final java.time.Duration HTTP_CONNECT_TIMEOUT = java.time.Duration.ofSeconds(20);
  static final java.time.Duration HTTP_REQUEST_TIMEOUT = java.time.Duration.ofSeconds(60);

  /**
   * The proxy half of the download remediation (issue #35), shared verbatim by both export engines;
   * each appends its own offline-placement tail (schema dir vs schema path).
   */
  public static final String PROXY_REMEDIATION =
      "To download through a proxy, expose HTTPS_PROXY, HTTP_PROXY as its documented fallback,"
          + " ALL_PROXY, and NO_PROXY (or their lowercase forms) to this process.";

  private SchemaCacheModule() {}

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

  public static boolean isNonEmptyFile(Path path) {
    try {
      return Files.isRegularFile(path) && Files.size(path) > 0;
    } catch (IOException error) {
      return false;
    }
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
        SchemaCacheException.Kind.FETCH,
        "cannot determine schema cache directory; set " + cacheDirEnv + " or " + fallbackEnv);
  }

  public static void ensureCachedSchemaFile(
      Path schemaPath, URI url, String description, String expectedSha256, SchemaFetcher fetcher)
      throws SchemaCacheException {
    // A cached file is trusted only when its bytes still match the pinned SHA-256 (audit finding
    // F2). A mismatch means the cache is corrupt, stale, or poisoned, so we re-fetch rather than
    // serve it.
    if (isNonEmptyFile(schemaPath) && fileMatchesSha256(schemaPath, expectedSha256)) {
      LOG.debug("schema cache hit: {} at {}", description, schemaPath);
      return;
    }
    // Worth a line: a silent re-fetch here is the observable difference between a warm cache and a
    // corrupt/poisoned one, and the envelope has no room to say which.
    LOG.debug("schema cache miss, fetching: {} from {} into {}", description, url, schemaPath);

    Path parent = schemaPath.getParent();
    if (parent == null) {
      throw new SchemaCacheException(
          SchemaCacheException.Kind.FETCH,
          "schema cache path " + schemaPath + " has no parent directory");
    }
    try {
      Files.createDirectories(parent);
    } catch (IOException error) {
      throw new SchemaCacheException(
          SchemaCacheException.Kind.FETCH,
          "failed to create schema cache directory " + parent + ": " + error.getMessage(),
          error);
    }

    Path tempFile;
    try {
      tempFile = Files.createTempFile(parent, ".dediren-schema-", ".tmp");
    } catch (IOException error) {
      throw new SchemaCacheException(
          SchemaCacheException.Kind.FETCH,
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
            SchemaCacheException.Kind.FETCH,
            "failed to download "
                + description
                + " from "
                + url
                + ": "
                + commandOutputDetails(
                    result.command(), result.exitCode(), result.stdout(), result.stderr()));
      }
      if (!isNonEmptyFile(tempFile)) {
        throw new SchemaCacheException(
            SchemaCacheException.Kind.FETCH,
            "downloaded " + description + " from " + url + " was empty");
      }
      if (Files.size(tempFile) > MAX_SCHEMA_BYTES) {
        throw new SchemaCacheException(
            SchemaCacheException.Kind.FETCH, "downloaded schema exceeds the 8 MiB limit");
      }
      String actualSha256 = sha256Hex(tempFile);
      if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
        throw new SchemaCacheException(
            SchemaCacheException.Kind.FETCH,
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
          SchemaCacheException.Kind.FETCH,
          "failed to download " + description + " from " + url + ": " + error.getMessage(),
          error);
    } finally {
      try {
        Files.deleteIfExists(tempFile);
      } catch (IOException ignored) {
        // Best-effort cleanup for failed or raced cache writes.
      }
    }
  }

  public static SchemaFetcher httpFetcher(Map<String, String> env) {
    String invalidProxyConfiguration = invalidProxyConfiguration(env);
    if (invalidProxyConfiguration != null) {
      return (url, destination) -> {
        throw new SchemaCacheException(SchemaCacheException.Kind.FETCH, invalidProxyConfiguration);
      };
    }
    return httpFetcher(httpTransport(httpClient(env)));
  }

  static SchemaFetcher httpFetcher(HttpTransport transport) {
    return (url, destination) -> {
      requireHttps(url);
      HttpTransport.Response response = transport.fetch(url);
      try (InputStream body = response.body()) {
        if (response.statusCode() < HttpURLConnection.HTTP_OK
            || response.statusCode() >= HttpURLConnection.HTTP_MULT_CHOICE) {
          return new SchemaFetchResult(
              false,
              "HTTP",
              response.statusCode(),
              new byte[0],
              ("HTTP status " + response.statusCode()).getBytes(StandardCharsets.UTF_8));
        }
        copyBounded(body, destination, response.deadlineNanos());
        return SchemaFetchResult.success();
      }
    };
  }

  static HttpClient httpClient(Map<String, String> env) {
    return HttpClient.newBuilder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT)
        // NORMAL refuses HTTPS-to-HTTP redirects. The response URI is checked again below so a
        // transport implementation cannot turn a redirect chain into a downgrade.
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(proxySelector(env))
        .build();
  }

  static ProxySelector proxySelector(Map<String, String> env) {
    String noProxy = proxyEnvironmentValue(env, "NO_PROXY");
    String proxy = firstProxyValue(env);
    if (proxy == null || proxy.isEmpty()) {
      return new ConfiguredProxySelector(null, noProxy, null);
    }
    try {
      URI proxyUri = URI.create(proxy);
      if (!"http".equalsIgnoreCase(proxyUri.getScheme())
          || proxyUri.getHost() == null
          || proxyUri.getRawUserInfo() != null
          || proxyUri.getPort() < -1
          || proxyUri.getRawPath() != null && !proxyUri.getRawPath().isEmpty()
          || proxyUri.getRawQuery() != null
          || proxyUri.getRawFragment() != null) {
        throw new IllegalArgumentException();
      }
      int port = proxyUri.getPort();
      if (port == -1) {
        port = 80;
      }
      return new ConfiguredProxySelector(
          new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxyUri.getHost(), port)),
          noProxy,
          null);
    } catch (IllegalArgumentException error) {
      return new ConfiguredProxySelector(null, noProxy, "invalid configured schema proxy");
    }
  }

  private static String invalidProxyConfiguration(Map<String, String> env) {
    return ((ConfiguredProxySelector) proxySelector(env)).invalidConfiguration;
  }

  private static HttpTransport httpTransport(HttpClient client) {
    return url -> {
      long deadlineNanos = deadlineAfter(HTTP_REQUEST_TIMEOUT);
      HttpRequest request = schemaRequest(url);
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try {
        requireHttps(response.uri());
      } catch (SchemaCacheException downgrade) {
        try {
          response.body().close();
        } catch (IOException closeFailure) {
          downgrade.addSuppressed(closeFailure);
        }
        throw downgrade;
      }
      return new HttpTransport.Response(response.statusCode(), response.body(), deadlineNanos);
    };
  }

  static HttpRequest schemaRequest(URI url) throws SchemaCacheException {
    requireHttps(url);
    return HttpRequest.newBuilder(url).GET().timeout(HTTP_REQUEST_TIMEOUT).build();
  }

  private static long deadlineAfter(java.time.Duration timeout) {
    long now = System.nanoTime();
    long delay = timeout.toNanos();
    return delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
  }

  private static void requireHttps(URI url) throws SchemaCacheException {
    if (!"https".equalsIgnoreCase(url.getScheme())) {
      throw new SchemaCacheException(
          SchemaCacheException.Kind.FETCH, "schema downloads and redirects must use https");
    }
  }

  private static void copyBounded(InputStream input, Path destination, long deadlineNanos)
      throws IOException, SchemaCacheException {
    if (deadlineNanos == Long.MAX_VALUE) {
      copyBounded(input, destination);
      return;
    }

    FutureTask<Void> transfer =
        new FutureTask<>(
            () -> {
              copyBounded(input, destination);
              return null;
            });
    Thread worker = Thread.ofVirtual().name("dediren-schema-body").start(transfer);
    try {
      long remainingNanos = deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0) {
        throw new TimeoutException();
      }
      transfer.get(remainingNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException error) {
      cancelBodyRead(input, transfer, worker);
      throw new SchemaCacheException(
          SchemaCacheException.Kind.FETCH,
          "schema response body timed out after " + HTTP_REQUEST_TIMEOUT.toSeconds() + " seconds",
          error);
    } catch (InterruptedException error) {
      cancelBodyRead(input, transfer, worker);
      Thread.currentThread().interrupt();
      throw new SchemaCacheException(
          SchemaCacheException.Kind.FETCH, "schema response body read was interrupted", error);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof SchemaCacheException schemaError) {
        throw schemaError;
      }
      if (cause instanceof IOException ioError) {
        throw ioError;
      }
      throw new IOException("failed to read schema response body", cause);
    }
  }

  private static void cancelBodyRead(InputStream input, FutureTask<Void> transfer, Thread worker) {
    transfer.cancel(true);
    try {
      input.close();
    } catch (IOException ignored) {
      // Cancellation is best-effort; the original timeout/interruption remains authoritative.
    }
    try {
      worker.join(1000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static void copyBounded(InputStream input, Path destination)
      throws IOException, SchemaCacheException {
    long total = 0;
    byte[] buffer = new byte[8192];
    try (var output = Files.newOutputStream(destination)) {
      for (int count; (count = input.read(buffer)) != -1; ) {
        total += count;
        if (total > MAX_SCHEMA_BYTES) {
          throw new SchemaCacheException(
              SchemaCacheException.Kind.FETCH, "downloaded schema exceeds the 8 MiB limit");
        }
        output.write(buffer, 0, count);
      }
    }
  }

  private static String firstProxyValue(Map<String, String> env) {
    for (String name : List.of("HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY")) {
      String value = proxyEnvironmentValue(env, name);
      if (value != null && !value.isEmpty()) {
        return value;
      }
    }
    return null;
  }

  private static String proxyEnvironmentValue(Map<String, String> env, String uppercaseName) {
    String lowercaseName = uppercaseName.toLowerCase(java.util.Locale.ROOT);
    return env.containsKey(lowercaseName) ? env.get(lowercaseName) : env.get(uppercaseName);
  }

  private static final class ConfiguredProxySelector extends ProxySelector {
    private final Proxy proxy;
    private final List<String> noProxyHosts;
    private final String invalidConfiguration;

    ConfiguredProxySelector(Proxy proxy, String noProxy, String invalidConfiguration) {
      this.proxy = proxy;
      this.noProxyHosts =
          noProxy == null || noProxy.isEmpty() ? List.of() : List.of(noProxy.split(","));
      this.invalidConfiguration = invalidConfiguration;
    }

    @Override
    public List<Proxy> select(URI uri) {
      if (invalidConfiguration != null) {
        throw new IllegalArgumentException(invalidConfiguration);
      }
      if (proxy == null || bypassesProxy(uri.getHost())) {
        return List.of(Proxy.NO_PROXY);
      }
      return List.of(proxy);
    }

    @Override
    public void connectFailed(URI uri, java.net.SocketAddress address, IOException failure) {
      // Schema cache proxy failures are represented by the export's structured fetch diagnostic.
    }

    private boolean bypassesProxy(String host) {
      if (host == null) {
        return false;
      }
      String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
      return noProxyHosts.stream()
          .map(String::trim)
          .anyMatch(
              entry ->
                  "*".equals(entry)
                      || normalizedHost.equalsIgnoreCase(entry)
                      || (entry.startsWith(".")
                          && normalizedHost.endsWith(entry.toLowerCase(java.util.Locale.ROOT))));
    }
  }

  private static boolean fileMatchesSha256(Path path, String expectedSha256) {
    try {
      long size = Files.size(path);
      return size > 0
          && size <= MAX_SCHEMA_BYTES
          && sha256Hex(path).equalsIgnoreCase(expectedSha256);
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
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[8192];
      for (int count; (count = input.read(buffer)) != -1; ) {
        digest.update(buffer, 0, count);
      }
    }
    byte[] hash = digest.digest();
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
