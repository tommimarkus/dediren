package dev.dediren.schemacache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SchemaCacheModuleTest {
  @TempDir Path tempDir;

  private static final String SCHEMA_XML = "<schema/>";
  // sha256sum of the literal bytes of SCHEMA_XML.
  private static final String SCHEMA_SHA256 =
      "65a8fcf0cf2a47e9dd2136cdbaee048f965cbb3830443622ff866637b7c8ed0d";
  private static final String WRONG_SHA256 =
      "0000000000000000000000000000000000000000000000000000000000000000";

  @Test
  void aFetcherFloodingStderrDoesNotDeadlock() throws Exception {
    // Same defect class as the schema validator: curlFetcher drained stdout to EOF before reading
    // stderr, so a fetcher that fills the ~64 KiB stderr pipe blocks in write(2) and never exits.
    // A verbose curl failure (redirect chain, TLS diagnostics) reaches that volume.
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.isExecutable(Path.of("/bin/sh")), "a POSIX shell is required for the fake fetcher");
    Path fetcher = tempDir.resolve("noisy-curl");
    Files.writeString(
        fetcher,
        "#!/bin/sh\n"
            + "i=0\n"
            + "while [ $i -lt 4000 ]; do\n"
            + "  echo \"line $i: curl could not resolve host\" >&2\n"
            + "  i=$((i+1))\n"
            + "done\n"
            + "exit 6\n",
        StandardCharsets.UTF_8);
    java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
        new java.util.HashSet<>(Files.getPosixFilePermissions(fetcher));
    permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
    Files.setPosixFilePermissions(fetcher, permissions);

    SchemaFetchResult result =
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(20),
            () ->
                SchemaCacheModule.curlFetcher(fetcher.toString())
                    .fetch(URI.create("https://example.invalid/x.xsd"), tempDir.resolve("out")));

    assertThat(result.succeeded()).isFalse();
    assertThat(result.exitCode()).isEqualTo(6);
    assertThat(new String(result.stderr(), StandardCharsets.UTF_8)).contains("line 3999");
  }

  @Test
  void aRelativeSchemaPathResolvesAgainstTheProductRootNotTheJvmCwd() {
    // Decision 9: both export engines resolve a relative schema/cache env path against the product
    // root so an in-memory build can supply it explicitly. The rule is shared, so it lives here
    // once rather than in a copy per engine.
    Path productRoot = Path.of("/x/y");

    Map<String, String> resolved =
        SchemaCacheModule.productRootRelativeEnv(
            Map.of("DEDIREN_OEF_SCHEMA_DIR", "schemas-oef"), productRoot, "DEDIREN_OEF_SCHEMA_DIR");

    assertThat(resolved.get("DEDIREN_OEF_SCHEMA_DIR"))
        .isEqualTo(productRoot.resolve("schemas-oef").toString());
  }

  @Test
  void anAbsoluteSchemaPathIsUnchangedByProductRootResolution() {
    Path productRoot = Path.of("/x/y");
    String absolute = tempDir.resolve("oef-schemas").toString();

    Map<String, String> resolved =
        SchemaCacheModule.productRootRelativeEnv(
            Map.of("DEDIREN_OEF_SCHEMA_DIR", absolute), productRoot, "DEDIREN_OEF_SCHEMA_DIR");

    assertThat(resolved.get("DEDIREN_OEF_SCHEMA_DIR")).isEqualTo(absolute);
  }

  @Test
  void aConfiguredValidatorOverridesTheDefaultCommandUnlessItIsBlank() {
    assertThat(SchemaCacheModule.configuredValidator(Map.of(), "DEDIREN_X_VALIDATOR", "xmllint"))
        .isEqualTo("xmllint");
    assertThat(
            SchemaCacheModule.configuredValidator(
                Map.of("DEDIREN_X_VALIDATOR", "  "), "DEDIREN_X_VALIDATOR", "xmllint"))
        .isEqualTo("xmllint");
    assertThat(
            SchemaCacheModule.configuredValidator(
                Map.of("DEDIREN_X_VALIDATOR", "/opt/xmllint"), "DEDIREN_X_VALIDATOR", "xmllint"))
        .isEqualTo("/opt/xmllint");
  }

  @Test
  void ignoresEmptyEnvironmentValuesWhenResolvingPaths() {
    assertThat(SchemaCacheModule.nonEmptyEnvPath(Map.of("TEST_PATH", ""), "TEST_PATH")).isEmpty();
    assertThat(
            SchemaCacheModule.nonEmptyEnvPath(Map.of("TEST_PATH", tempDir.toString()), "TEST_PATH"))
        .contains(tempDir);
  }

  @Test
  void resolvesCacheDirectoryFromExplicitOrPlatformFallbacks() throws Exception {
    assertThat(
            SchemaCacheModule.schemaCacheBaseDir(
                Map.of("DEDIREN_SCHEMA_CACHE_DIR", tempDir.resolve("explicit").toString()),
                "DEDIREN_SCHEMA_CACHE_DIR",
                "DEDIREN_XMI_SCHEMA_PATH"))
        .isEqualTo(tempDir.resolve("explicit"));
    assertThat(
            SchemaCacheModule.schemaCacheBaseDir(
                Map.of("XDG_CACHE_HOME", tempDir.resolve("xdg").toString()),
                "DEDIREN_SCHEMA_CACHE_DIR",
                "DEDIREN_XMI_SCHEMA_PATH"))
        .isEqualTo(tempDir.resolve("xdg").resolve("dediren").resolve("schemas"));
    assertThat(
            SchemaCacheModule.schemaCacheBaseDir(
                Map.of("LOCALAPPDATA", tempDir.resolve("local").toString()),
                "DEDIREN_SCHEMA_CACHE_DIR",
                "DEDIREN_XMI_SCHEMA_PATH"))
        .isEqualTo(tempDir.resolve("local").resolve("dediren").resolve("schemas"));
    assertThat(
            SchemaCacheModule.schemaCacheBaseDir(
                Map.of("HOME", tempDir.resolve("home").toString()),
                "DEDIREN_SCHEMA_CACHE_DIR",
                "DEDIREN_XMI_SCHEMA_PATH"))
        .isEqualTo(tempDir.resolve("home").resolve(".cache").resolve("dediren").resolve("schemas"));
  }

  @Test
  void explicitCacheDirTakesPrecedenceOverAllPlatformFallbacks() throws Exception {
    // All four env vars present — explicit DEDIREN_SCHEMA_CACHE_DIR must win
    Path result =
        SchemaCacheModule.schemaCacheBaseDir(
            Map.of(
                "DEDIREN_SCHEMA_CACHE_DIR", tempDir.resolve("explicit").toString(),
                "XDG_CACHE_HOME", tempDir.resolve("xdg").toString(),
                "LOCALAPPDATA", tempDir.resolve("local").toString(),
                "HOME", tempDir.resolve("home").toString()),
            "DEDIREN_SCHEMA_CACHE_DIR",
            "DEDIREN_XMI_SCHEMA_PATH");

    assertThat(result).isEqualTo(tempDir.resolve("explicit"));
  }

  @Test
  void xdgCacheHomeTakesPrecedenceOverLocalAppDataAndHome() throws Exception {
    // XDG_CACHE_HOME, LOCALAPPDATA and HOME present — XDG_CACHE_HOME must win
    Path result =
        SchemaCacheModule.schemaCacheBaseDir(
            Map.of(
                "XDG_CACHE_HOME", tempDir.resolve("xdg").toString(),
                "LOCALAPPDATA", tempDir.resolve("local").toString(),
                "HOME", tempDir.resolve("home").toString()),
            "DEDIREN_SCHEMA_CACHE_DIR",
            "DEDIREN_XMI_SCHEMA_PATH");

    assertThat(result).isEqualTo(tempDir.resolve("xdg").resolve("dediren").resolve("schemas"));
  }

  @Test
  void localAppDataTakesPrecedenceOverHome() throws Exception {
    // Both LOCALAPPDATA and HOME present — LOCALAPPDATA must win
    Path result =
        SchemaCacheModule.schemaCacheBaseDir(
            Map.of(
                "LOCALAPPDATA", tempDir.resolve("local").toString(),
                "HOME", tempDir.resolve("home").toString()),
            "DEDIREN_SCHEMA_CACHE_DIR",
            "DEDIREN_XMI_SCHEMA_PATH");

    assertThat(result).isEqualTo(tempDir.resolve("local").resolve("dediren").resolve("schemas"));
  }

  @Test
  void reportsMissingCacheDirectoryConfiguration() {
    assertThatThrownBy(
            () ->
                SchemaCacheModule.schemaCacheBaseDir(
                    Map.of(), "DEDIREN_SCHEMA_CACHE_DIR", "DEDIREN_XMI_SCHEMA_PATH"))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessage(
            "cannot determine schema cache directory; set DEDIREN_SCHEMA_CACHE_DIR or DEDIREN_XMI_SCHEMA_PATH");
  }

  @Test
  void detectsNonEmptyFiles() throws Exception {
    Path missing = tempDir.resolve("missing.xsd");
    Path empty = Files.createFile(tempDir.resolve("empty.xsd"));
    Path populated =
        Files.writeString(tempDir.resolve("schema.xsd"), "<schema/>", StandardCharsets.UTF_8);

    assertThat(SchemaCacheModule.isNonEmptyFile(missing)).isFalse();
    assertThat(SchemaCacheModule.isNonEmptyFile(empty)).isFalse();
    assertThat(SchemaCacheModule.isNonEmptyFile(populated)).isTrue();
  }

  @Test
  void skipsDownloadWhenCachedSchemaAlreadyExists() throws Exception {
    Path schema =
        Files.writeString(tempDir.resolve("schema.xsd"), SCHEMA_XML, StandardCharsets.UTF_8);

    SchemaCacheModule.ensureCachedSchemaFile(
        schema,
        URI.create("https://example.test/schema.xsd"),
        "test schema",
        SCHEMA_SHA256,
        (url, destination) -> {
          throw new AssertionError("fetcher should not be called for a matching cached schema");
        });

    assertThat(schema).hasContent(SCHEMA_XML);
  }

  @Test
  void createsParentDirectoryAndStoresDownloadedSchema() throws Exception {
    Path schema = tempDir.resolve("nested").resolve("schema.xsd");

    SchemaCacheModule.ensureCachedSchemaFile(
        schema,
        URI.create("https://example.test/schema.xsd"),
        "test schema",
        SCHEMA_SHA256,
        (url, destination) -> {
          Files.writeString(destination, SCHEMA_XML, StandardCharsets.UTF_8);
          return SchemaFetchResult.success();
        });

    assertThat(schema).hasContent(SCHEMA_XML);
  }

  @Test
  void rejectsDownloadedSchemaWithMismatchingHash() throws Exception {
    Path schema = tempDir.resolve("nested").resolve("schema.xsd");

    assertThatThrownBy(
            () ->
                SchemaCacheModule.ensureCachedSchemaFile(
                    schema,
                    URI.create("https://example.test/schema.xsd"),
                    "test schema",
                    WRONG_SHA256,
                    (url, destination) -> {
                      Files.writeString(destination, SCHEMA_XML, StandardCharsets.UTF_8);
                      return SchemaFetchResult.success();
                    }))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining("https://example.test/schema.xsd")
        .hasMessageContaining(schema.toString())
        .hasMessageContaining(WRONG_SHA256)
        .hasMessageContaining(SCHEMA_SHA256);

    assertThat(schema).doesNotExist();
    assertThat(leftoverTempFiles(schema.getParent())).isEmpty();
  }

  @Test
  void replacesCorruptCachedSchemaWhenReFetchMatches() throws Exception {
    Path schema = tempDir.resolve("nested").resolve("schema.xsd");
    Files.createDirectories(schema.getParent());
    Files.writeString(schema, "<corrupt/>", StandardCharsets.UTF_8);

    SchemaCacheModule.ensureCachedSchemaFile(
        schema,
        URI.create("https://example.test/schema.xsd"),
        "test schema",
        SCHEMA_SHA256,
        (url, destination) -> {
          Files.writeString(destination, SCHEMA_XML, StandardCharsets.UTF_8);
          return SchemaFetchResult.success();
        });

    assertThat(schema).hasContent(SCHEMA_XML);
    assertThat(leftoverTempFiles(schema.getParent())).isEmpty();
  }

  @Test
  void rejectsCorruptCachedSchemaWhenReFetchStillMismatches() throws Exception {
    Path schema = tempDir.resolve("nested").resolve("schema.xsd");
    Files.createDirectories(schema.getParent());
    Files.writeString(schema, "<corrupt/>", StandardCharsets.UTF_8);

    assertThatThrownBy(
            () ->
                SchemaCacheModule.ensureCachedSchemaFile(
                    schema,
                    URI.create("https://example.test/schema.xsd"),
                    "test schema",
                    SCHEMA_SHA256,
                    (url, destination) -> {
                      Files.writeString(destination, "<stillbad/>", StandardCharsets.UTF_8);
                      return SchemaFetchResult.success();
                    }))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining(SCHEMA_SHA256);

    // The re-fetch never validated, so the corrupt cache file is not overwritten with the bad
    // download and never treated as valid (the method throws). No temp file is left behind.
    assertThat(schema).hasContent("<corrupt/>");
    assertThat(leftoverTempFiles(schema.getParent())).isEmpty();
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void curlFetcherUsesExpectedDownloadArguments() throws Exception {
    Path fakeCurl = tempDir.resolve("fake-curl.sh");
    Files.writeString(
        fakeCurl,
        """
                #!/usr/bin/env sh
                args_text=""
                output=""
                while [ "$#" -gt 0 ]; do
                  args_text="${args_text}${1}
                "
                  if [ "$1" = "--output" ]; then
                    shift
                    args_text="${args_text}${1}
                "
                    output="$1"
                  fi
                  shift
                done
                printf '<schema/>' > "$output"
                printf '%s' "$args_text" > "$output.args"
                """,
        StandardCharsets.UTF_8);
    assertThat(fakeCurl.toFile().setExecutable(true)).isTrue();
    Path schema = tempDir.resolve("curl").resolve("schema.xsd");

    SchemaCacheModule.ensureCachedSchemaFile(
        schema,
        URI.create("https://example.test/schema.xsd"),
        "test schema",
        SCHEMA_SHA256,
        SchemaCacheModule.curlFetcher(fakeCurl.toString()));

    assertThat(schema).hasContent(SCHEMA_XML);
    Path argsFile =
        Files.list(schema.getParent())
            .filter(path -> path.getFileName().toString().endsWith(".args"))
            .findFirst()
            .orElseThrow();
    assertThat(argsFile)
        .content()
        .contains(
            "--proto",
            "=https",
            "--location",
            "--fail",
            "--silent",
            "--show-error",
            "https://example.test/schema.xsd",
            "--output");
  }

  @Test
  void reportsDownloadFailuresWithCommandOutputDetails() {
    Path schema = tempDir.resolve("schema.xsd");

    assertThatThrownBy(
            () ->
                SchemaCacheModule.ensureCachedSchemaFile(
                    schema,
                    URI.create("https://example.test/schema.xsd"),
                    "test schema",
                    SCHEMA_SHA256,
                    (url, destination) ->
                        new SchemaFetchResult(
                            false,
                            "curl",
                            22,
                            "body error\n".getBytes(StandardCharsets.UTF_8),
                            "http 404\n".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining("failed to download test schema from https://example.test/schema.xsd")
        .hasMessageContaining("http 404")
        .hasMessageContaining("body error");
  }

  @Test
  void rejectsEmptyDownloadedSchema() {
    Path schema = tempDir.resolve("schema.xsd");

    assertThatThrownBy(
            () ->
                SchemaCacheModule.ensureCachedSchemaFile(
                    schema,
                    URI.create("https://example.test/schema.xsd"),
                    "test schema",
                    SCHEMA_SHA256,
                    (url, destination) -> {
                      return SchemaFetchResult.success();
                    }))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessage("downloaded test schema from https://example.test/schema.xsd was empty");
  }

  private static List<Path> leftoverTempFiles(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (var entries = Files.list(directory)) {
      return entries
          .filter(path -> path.getFileName().toString().startsWith(".dediren-schema-"))
          .toList();
    }
  }

  @Test
  void formatsCommandOutputDetails() {
    assertThat(
            SchemaCacheModule.commandOutputDetails(
                "curl",
                22,
                "body\n".getBytes(StandardCharsets.UTF_8),
                "error\n".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("error\nbody");
    assertThat(SchemaCacheModule.commandOutputDetails("curl", 7, new byte[0], new byte[0]))
        .isEqualTo("curl exited with status 7");
  }
}
