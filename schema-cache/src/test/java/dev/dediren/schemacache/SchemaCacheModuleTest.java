package dev.dediren.schemacache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class SchemaCacheModuleTest {
    @TempDir
    Path tempDir;

    @Test
    void moduleLoads() {
        assertThat(SchemaCacheModule.moduleName()).isEqualTo("schema-cache");
    }

    @Test
    void ignoresEmptyEnvironmentValuesWhenResolvingPaths() {
        assertThat(SchemaCacheModule.nonEmptyEnvPath(Map.of("TEST_PATH", ""), "TEST_PATH"))
                .isEmpty();
        assertThat(SchemaCacheModule.nonEmptyEnvPath(Map.of("TEST_PATH", tempDir.toString()), "TEST_PATH"))
                .contains(tempDir);
    }

    @Test
    void resolvesCacheDirectoryFromExplicitOrPlatformFallbacks() throws Exception {
        assertThat(SchemaCacheModule.schemaCacheBaseDir(
                        Map.of("DEDIREN_SCHEMA_CACHE_DIR", tempDir.resolve("explicit").toString()),
                        "DEDIREN_SCHEMA_CACHE_DIR",
                        "DEDIREN_XMI_SCHEMA_PATH"))
                .isEqualTo(tempDir.resolve("explicit"));
        assertThat(SchemaCacheModule.schemaCacheBaseDir(
                        Map.of("XDG_CACHE_HOME", tempDir.resolve("xdg").toString()),
                        "DEDIREN_SCHEMA_CACHE_DIR",
                        "DEDIREN_XMI_SCHEMA_PATH"))
                .isEqualTo(tempDir.resolve("xdg").resolve("dediren").resolve("schemas"));
        assertThat(SchemaCacheModule.schemaCacheBaseDir(
                        Map.of("LOCALAPPDATA", tempDir.resolve("local").toString()),
                        "DEDIREN_SCHEMA_CACHE_DIR",
                        "DEDIREN_XMI_SCHEMA_PATH"))
                .isEqualTo(tempDir.resolve("local").resolve("dediren").resolve("schemas"));
        assertThat(SchemaCacheModule.schemaCacheBaseDir(
                        Map.of("HOME", tempDir.resolve("home").toString()),
                        "DEDIREN_SCHEMA_CACHE_DIR",
                        "DEDIREN_XMI_SCHEMA_PATH"))
                .isEqualTo(tempDir.resolve("home").resolve(".cache").resolve("dediren").resolve("schemas"));
    }

    @Test
    void explicitCacheDirTakesPrecedenceOverAllPlatformFallbacks() throws Exception {
        // All four env vars present — explicit DEDIREN_SCHEMA_CACHE_DIR must win
        Path result = SchemaCacheModule.schemaCacheBaseDir(
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
        Path result = SchemaCacheModule.schemaCacheBaseDir(
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
        Path result = SchemaCacheModule.schemaCacheBaseDir(
                Map.of(
                        "LOCALAPPDATA", tempDir.resolve("local").toString(),
                        "HOME", tempDir.resolve("home").toString()),
                "DEDIREN_SCHEMA_CACHE_DIR",
                "DEDIREN_XMI_SCHEMA_PATH");

        assertThat(result).isEqualTo(tempDir.resolve("local").resolve("dediren").resolve("schemas"));
    }

    @Test
    void reportsMissingCacheDirectoryConfiguration() {
        assertThatThrownBy(() -> SchemaCacheModule.schemaCacheBaseDir(
                        Map.of(),
                        "DEDIREN_SCHEMA_CACHE_DIR",
                        "DEDIREN_XMI_SCHEMA_PATH"))
                .isInstanceOf(SchemaCacheException.class)
                .hasMessage("cannot determine schema cache directory; set DEDIREN_SCHEMA_CACHE_DIR or DEDIREN_XMI_SCHEMA_PATH");
    }

    @Test
    void detectsNonEmptyFiles() throws Exception {
        Path missing = tempDir.resolve("missing.xsd");
        Path empty = Files.createFile(tempDir.resolve("empty.xsd"));
        Path populated = Files.writeString(tempDir.resolve("schema.xsd"), "<schema/>", StandardCharsets.UTF_8);

        assertThat(SchemaCacheModule.isNonEmptyFile(missing)).isFalse();
        assertThat(SchemaCacheModule.isNonEmptyFile(empty)).isFalse();
        assertThat(SchemaCacheModule.isNonEmptyFile(populated)).isTrue();
    }

    @Test
    void skipsDownloadWhenCachedSchemaAlreadyExists() throws Exception {
        Path schema = Files.writeString(tempDir.resolve("schema.xsd"), "<schema/>", StandardCharsets.UTF_8);

        SchemaCacheModule.ensureCachedSchemaFile(
                schema,
                URI.create("https://example.test/schema.xsd"),
                "test schema",
                (url, destination) -> {
                    throw new AssertionError("fetcher should not be called for a populated schema");
                });

        assertThat(schema).hasContent("<schema/>");
    }

    @Test
    void createsParentDirectoryAndStoresDownloadedSchema() throws Exception {
        Path schema = tempDir.resolve("nested").resolve("schema.xsd");

        SchemaCacheModule.ensureCachedSchemaFile(
                schema,
                URI.create("https://example.test/schema.xsd"),
                "test schema",
                (url, destination) -> {
                    Files.writeString(destination, "<schema/>", StandardCharsets.UTF_8);
                    return SchemaFetchResult.success();
                });

        assertThat(schema).hasContent("<schema/>");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void curlFetcherUsesExpectedDownloadArguments() throws Exception {
        Path fakeCurl = tempDir.resolve("fake-curl.sh");
        Files.writeString(fakeCurl, """
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
                """, StandardCharsets.UTF_8);
        assertThat(fakeCurl.toFile().setExecutable(true)).isTrue();
        Path schema = tempDir.resolve("curl").resolve("schema.xsd");

        SchemaCacheModule.ensureCachedSchemaFile(
                schema,
                URI.create("https://example.test/schema.xsd"),
                "test schema",
                SchemaCacheModule.curlFetcher(fakeCurl.toString()));

        assertThat(schema).hasContent("<schema/>");
        Path argsFile = Files.list(schema.getParent())
                .filter(path -> path.getFileName().toString().endsWith(".args"))
                .findFirst()
                .orElseThrow();
        assertThat(argsFile)
                .content()
                .contains(
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

        assertThatThrownBy(() -> SchemaCacheModule.ensureCachedSchemaFile(
                        schema,
                        URI.create("https://example.test/schema.xsd"),
                        "test schema",
                        (url, destination) -> new SchemaFetchResult(
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

        assertThatThrownBy(() -> SchemaCacheModule.ensureCachedSchemaFile(
                        schema,
                        URI.create("https://example.test/schema.xsd"),
                        "test schema",
                        (url, destination) -> {
                            return SchemaFetchResult.success();
                        }))
                .isInstanceOf(SchemaCacheException.class)
                .hasMessage("downloaded test schema from https://example.test/schema.xsd was empty");
    }

    @Test
    void formatsCommandOutputDetails() {
        assertThat(SchemaCacheModule.commandOutputDetails(
                        "curl",
                        22,
                        "body\n".getBytes(StandardCharsets.UTF_8),
                        "error\n".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("error\nbody");
        assertThat(SchemaCacheModule.commandOutputDetails(
                        "curl",
                        7,
                        new byte[0],
                        new byte[0]))
                .isEqualTo("curl exited with status 7");
    }
}
