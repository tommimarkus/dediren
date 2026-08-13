package dev.dediren.schemacache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpSchemaFetcherTest {
  @TempDir Path tempDir;

  private static final String SCHEMA_XML = "<schema/>";
  private static final String SCHEMA_SHA256 =
      "65a8fcf0cf2a47e9dd2136cdbaee048f965cbb3830443622ff866637b7c8ed0d";

  @Test
  void injectedTransportFetchesHttpsSchemasWithoutAProcessBoundary() throws Exception {
    AtomicBoolean called = new AtomicBoolean();
    HttpTransport transport =
        url -> {
          called.set(true);
          assertThat(url).isEqualTo(URI.create("https://schemas.example.test/schema.xsd"));
          return new HttpTransport.Response(
              200, new ByteArrayInputStream(SCHEMA_XML.getBytes(UTF_8)));
        };
    Path destination = tempDir.resolve("schema.xsd");

    SchemaCacheModule.ensureCachedSchemaFile(
        destination,
        URI.create("https://schemas.example.test/schema.xsd"),
        "test schema",
        SCHEMA_SHA256,
        SchemaCacheModule.httpFetcher(transport));

    assertThat(called).isTrue();
    assertThat(destination).hasContent(SCHEMA_XML);
  }

  @Test
  void httpFetcherFailsClosedBeforeGivingAnHttpUrlToTheTransport() {
    AtomicBoolean called = new AtomicBoolean();
    HttpTransport transport =
        url -> {
          called.set(true);
          return new HttpTransport.Response(200, InputStream.nullInputStream());
        };

    assertThatThrownBy(
            () ->
                SchemaCacheModule.httpFetcher(transport)
                    .fetch(
                        URI.create("http://schemas.example.test/schema.xsd"),
                        tempDir.resolve("schema.xsd")))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining("https");

    assertThat(called).isFalse();
  }

  @Test
  void injectedTransportRejectsResponsesLargerThanEightMiBAndPreservesTheOldCacheFile()
      throws Exception {
    Path destination = tempDir.resolve("schema.xsd");
    Files.writeString(destination, "old cache", UTF_8);
    HttpTransport transport =
        url -> new HttpTransport.Response(200, repeatedBytes(8 * 1024 * 1024 + 1));

    assertThatThrownBy(
            () ->
                SchemaCacheModule.ensureCachedSchemaFile(
                    destination,
                    URI.create("https://schemas.example.test/schema.xsd"),
                    "test schema",
                    SCHEMA_SHA256,
                    SchemaCacheModule.httpFetcher(transport)))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining("8 MiB");

    assertThat(destination).hasContent("old cache");
    try (var files = Files.list(tempDir)) {
      assertThat(
              files
                  .map(path -> path.getFileName().toString())
                  .filter(name -> name.contains("schema-"))
                  .toList())
          .isEmpty();
    }
  }

  @Test
  void httpClientHasTwentySecondConnectTimeout() {
    assertThat(SchemaCacheModule.httpClient(Map.of()).connectTimeout())
        .contains(SchemaCacheModule.HTTP_CONNECT_TIMEOUT);
  }

  @Test
  void schemaRequestsHaveASixtySecondTimeout() {
    assertThat(SchemaCacheModule.HTTP_REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void streamedBodyConsumptionIsBoundedAfterResponseHeadersArrive() {
    InputStream stalledBody =
        new InputStream() {
          @Override
          public int read() throws java.io.IOException {
            try {
              Thread.sleep(Duration.ofSeconds(10));
              return -1;
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new java.io.IOException("body read cancelled", interrupted);
            }
          }
        };
    SchemaFetcher fetcher =
        SchemaCacheModule.httpFetcher(
            url ->
                new HttpTransport.Response(200, stalledBody, Duration.ofMillis(25)));

    assertThatThrownBy(
            () ->
                fetcher.fetch(
                    URI.create("https://schemas.example.test/schema.xsd"),
                    tempDir.resolve("schema.xsd")))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining("timed out");
  }

  @Test
  void httpClientUsesNormalRedirectsToRejectHttpsDowngrades() {
    assertThat(SchemaCacheModule.httpClient(Map.of()).followRedirects())
        .isEqualTo(java.net.http.HttpClient.Redirect.NORMAL);
  }

  @Test
  void nonSuccessfulHttpStatusBecomesAStructuredFetchFailure() throws Exception {
    SchemaFetchResult result =
        SchemaCacheModule.httpFetcher(
                url -> new HttpTransport.Response(503, InputStream.nullInputStream()))
            .fetch(
                URI.create("https://schemas.example.test/schema.xsd"),
                tempDir.resolve("schema.xsd"));

    assertThat(result.succeeded()).isFalse();
    assertThat(result.exitCode()).isEqualTo(503);
    assertThat(new String(result.stderr(), UTF_8)).isEqualTo("HTTP status 503");
  }

  @Test
  void proxySelectionPrefersLowercaseAndBypassesNoProxyHosts() {
    java.net.ProxySelector selector =
        SchemaCacheModule.proxySelector(
            Map.of(
                "https_proxy", "http://lower.example.test:8443",
                "HTTPS_PROXY", "http://upper.example.test:9443",
                "HTTP_PROXY", "http://fallback.example.test:8080",
                "ALL_PROXY", "http://all.example.test:1080",
                "NO_PROXY", "internal.example.test,.bypass.example.test"));

    assertThat(proxyAddress(selector.select(URI.create("https://schemas.example.test/schema.xsd"))))
        .isEqualTo("lower.example.test:8443");
    assertThat(selector.select(URI.create("https://internal.example.test/schema.xsd")))
        .containsExactly(Proxy.NO_PROXY);
    assertThat(selector.select(URI.create("https://host.bypass.example.test/schema.xsd")))
        .containsExactly(Proxy.NO_PROXY);
  }

  @Test
  void proxySelectionUsesHttpsThenDocumentedHttpThenAllProxyFallbacks() {
    assertThat(
            proxyAddress(
                SchemaCacheModule.proxySelector(
                        Map.of("HTTPS_PROXY", "http://https.example.test:443"))
                    .select(URI.create("https://schemas.example.test/schema.xsd"))))
        .isEqualTo("https.example.test:443");
    assertThat(
            proxyAddress(
                SchemaCacheModule.proxySelector(
                        Map.of("HTTP_PROXY", "http://http.example.test:8080"))
                    .select(URI.create("https://schemas.example.test/schema.xsd"))))
        .isEqualTo("http.example.test:8080");
    assertThat(
            proxyAddress(
                SchemaCacheModule.proxySelector(Map.of("ALL_PROXY", "http://all.example.test:1080"))
                    .select(URI.create("https://schemas.example.test/schema.xsd"))))
        .isEqualTo("all.example.test:1080");
  }

  @Test
  void invalidProxyFailsWithoutDisclosingCredentials() {
    SchemaFetcher fetcher =
        SchemaCacheModule.httpFetcher(
            Map.of("HTTPS_PROXY", "http://user:not-for-log-or-assertion@bad host"));

    Throwable failure =
        catchThrowable(
            () ->
                fetcher.fetch(
                    URI.create("https://schemas.example.test/schema.xsd"),
                    tempDir.resolve("schema.xsd")));

    assertThat(failure).isInstanceOf(SchemaCacheException.class);
    assertThat(failure.getMessage()).doesNotMatch(".*://[^/\\s]+@.*");
  }

  @Test
  void unsupportedSecureOrCredentialBearingProxyUrisFailClosed() {
    URI schema = URI.create("https://schemas.example.test/schema.xsd");

    assertThatThrownBy(
            () ->
                SchemaCacheModule.proxySelector(
                        Map.of("HTTPS_PROXY", "https://proxy.example.test:8443"))
                    .select(schema))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid configured schema proxy");
    assertThatThrownBy(
            () ->
                SchemaCacheModule.proxySelector(
                        Map.of("HTTPS_PROXY", "http://user:secret@proxy.example.test:8080"))
                    .select(schema))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid configured schema proxy")
        .hasMessageNotContaining("secret");
  }

  private static final java.nio.charset.Charset UTF_8 = StandardCharsets.UTF_8;

  private static InputStream repeatedBytes(int length) {
    return new InputStream() {
      private int remaining = length;

      @Override
      public int read() {
        if (remaining == 0) {
          return -1;
        }
        remaining--;
        return 'x';
      }
    };
  }

  private static String proxyAddress(List<Proxy> proxies) {
    InetSocketAddress address = (InetSocketAddress) proxies.getFirst().address();
    return address.getHostString() + ":" + address.getPort();
  }
}
