package dev.dediren.core.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.json.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRuntimeTest {
    @TempDir
    Path temp;

    @Test
    void missingExecutableReturnsTypedDiagnostic() throws Exception {
        writeManifest(temp, "runtime-testbed", temp.resolve("missing-binary").toString(), List.of("render"));

        assertThatThrownBy(() -> runWithMode("ok", "render", List.of("render")))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo("DEDIREN_PLUGIN_MISSING_EXECUTABLE");
    }

    @Test
    void unsupportedCapabilityIsRejectedBeforeCommandExecution() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));

        assertThatThrownBy(() -> runWithMode("ok", "layout", List.of("layout")))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo("DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY");
    }

    @Test
    void invalidRuntimeCapabilityJsonIsStructured() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));

        assertThatThrownBy(() -> runWithMode("capabilities-invalid-json", "render", List.of("render")))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo("DEDIREN_PLUGIN_CAPABILITY_INVALID_JSON");
    }

    @Test
    void runtimeIdMismatchIsStructured() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));
        var options = PluginRunOptions.defaults()
                .withCandidateEnv(Map.of("DEDIREN_TEST_PLUGIN_ID", "different-plugin"));

        assertThatThrownBy(() -> PluginRunner.runForCapabilityWithRegistry(
                PluginRegistry.fromDirs(List.of(temp)),
                "runtime-testbed",
                "render",
                List.of("render"),
                "{}",
                options))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo("DEDIREN_PLUGIN_ID_MISMATCH");
    }

    @Test
    void invalidSuccessOutputIsStructured() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));

        assertThatThrownBy(() -> runWithMode("invalid-json", "render", List.of("render")))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo("DEDIREN_PLUGIN_OUTPUT_INVALID_JSON");
    }

    @Test
    void invalidSuccessEnvelopeIsStructured() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));

        assertThatThrownBy(() -> runWithMode("invalid-envelope", "render", List.of("render")))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo("DEDIREN_PLUGIN_OUTPUT_INVALID_ENVELOPE");
    }

    @Test
    void successfulPluginDataMustMatchCapabilitySchema() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));

        assertThatThrownBy(() -> runWithMode("invalid-data", "render", List.of("render")))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo("DEDIREN_PLUGIN_OUTPUT_INVALID_DATA");
    }

    @Test
    void structuredPluginErrorEnvelopeIsPreservedAndReportedNonZero() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));

        PluginRunOutcome outcome = runWithMode("error-envelope-zero", "render", List.of("render"));

        assertThat(outcome.exitCode()).isEqualTo(3);
        assertThat(outcome.stdout()).contains("DEDIREN_TESTBED_ERROR");
    }

    @Test
    void manifestAllowedEnvIsPassedToCapabilityProbeAndCommand() throws Exception {
        writeManifest(
                temp,
                "runtime-testbed",
                testbedExecutable().toString(),
                List.of("layout"),
                List.of("DEDIREN_TEST_PLUGIN_CAPABILITIES"));
        var options = PluginRunOptions.defaults()
                .withCandidateEnv(Map.of("DEDIREN_TEST_PLUGIN_CAPABILITIES", "layout"));

        PluginRunOutcome outcome = PluginRunner.runForCapabilityWithRegistry(
                PluginRegistry.fromDirs(List.of(temp)),
                "runtime-testbed",
                "layout",
                List.of("layout"),
                "{}",
                options);

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.stdout()).contains("\"layout_result_schema_version\"");
    }

    @Test
    void explicitEnvWithoutManifestAllowlistIsNotPassedToPlugin() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"), List.of());
        var options = PluginRunOptions.defaults()
                .withCandidateEnv(Map.of("DEDIREN_TEST_PLUGIN_MODE", "invalid-json"));

        PluginRunOutcome outcome = PluginRunner.runForCapabilityWithRegistry(
                PluginRegistry.fromDirs(List.of(temp)),
                "runtime-testbed",
                "render",
                List.of("render"),
                "{}",
                options);

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.stdout()).contains("\"render_result_schema_version\"");
    }

    @Test
    void bundledRegistryLoadsDistributionPluginManifests() throws Exception {
        Path bundleRoot = temp.resolve("bundle-root");
        Path schemas = bundleRoot.resolve("schemas");
        Path plugins = bundleRoot.resolve("plugins");
        Files.createDirectories(schemas);
        Files.createDirectories(plugins);
        writePermissiveSchemas(schemas, "model.schema.json", "plugin-manifest.schema.json");
        writeManifest(plugins, "runtime-testbed", testbedExecutable().toString(), List.of("render"), List.of());
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", bundleRoot.toString());
        try {
            LoadedPluginManifest manifest = PluginRegistry.bundled().loadManifest("runtime-testbed");

            assertThat(manifest.manifest().id()).isEqualTo("runtime-testbed");
            assertThat(manifest.path()).isEqualTo(plugins.resolve("runtime-testbed.manifest.json"));
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void bundledRegistryUsesExplicitBundleRootOutsideCurrentWorkingDirectory() throws Exception {
        Path bundleRoot = temp.resolve("runtime-bundle");
        Path schemas = bundleRoot.resolve("schemas");
        Path plugins = bundleRoot.resolve("plugins");
        Path bin = bundleRoot.resolve("bin");
        Path outsideBundle = temp.resolve("outside-bundle");
        Files.createDirectories(schemas);
        Files.createDirectories(plugins);
        Files.createDirectories(bin);
        Files.createDirectories(outsideBundle);
        writePermissiveSchemas(
                schemas,
                "model.schema.json",
                "plugin-manifest.schema.json",
                "runtime-capability.schema.json",
                "envelope.schema.json",
                "render-result.schema.json");
        writeTestbedExecutable(bin.resolve("runtime-testbed"));
        writeManifest(plugins, "runtime-testbed", "runtime-testbed", List.of("render"), List.of());
        String originalUserDir = System.getProperty("user.dir");
        String originalBundleRoot = System.getProperty("dediren.bundle.root");
        System.setProperty("user.dir", outsideBundle.toString());
        System.setProperty("dediren.bundle.root", bundleRoot.toString());
        try {
            PluginRunOutcome outcome = PluginRunner.runForCapabilityWithRegistry(
                    PluginRegistry.bundled(),
                    "runtime-testbed",
                    "render",
                    List.of("render"),
                    "{}",
                    PluginRunOptions.defaults());

            assertThat(outcome.exitCode()).isZero();
            assertThat(outcome.stdout()).contains("\"render_result_schema_version\"");
        } finally {
            restoreProperty("user.dir", originalUserDir);
            restoreProperty("dediren.bundle.root", originalBundleRoot);
        }
    }

    @Test
    void bundledRegistryResolvesDistributionExecutablesFromBundleBin() throws Exception {
        Path bundleRoot = temp.resolve("runtime-bundle");
        Path schemas = bundleRoot.resolve("schemas");
        Path plugins = bundleRoot.resolve("plugins");
        Path bin = bundleRoot.resolve("bin");
        Files.createDirectories(schemas);
        Files.createDirectories(plugins);
        Files.createDirectories(bin);
        writePermissiveSchemas(
                schemas,
                "model.schema.json",
                "plugin-manifest.schema.json",
                "runtime-capability.schema.json",
                "envelope.schema.json",
                "render-result.schema.json");
        writeTestbedExecutable(bin.resolve("runtime-testbed"));
        writeManifest(plugins, "runtime-testbed", "runtime-testbed", List.of("render"), List.of());
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", bundleRoot.toString());
        try {
            PluginRunOutcome outcome = PluginRunner.runForCapabilityWithRegistry(
                    PluginRegistry.bundled(),
                    "runtime-testbed",
                    "render",
                    List.of("render"),
                    "{}",
                    PluginRunOptions.defaults());

            assertThat(outcome.exitCode()).isZero();
            assertThat(outcome.stdout()).contains("\"render_result_schema_version\"");
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private PluginRunOutcome runWithMode(String mode, String capability, List<String> args) throws Exception {
        var options = PluginRunOptions.defaults()
                .withCandidateEnv(Map.of("DEDIREN_TEST_PLUGIN_MODE", mode));
        return PluginRunner.runForCapabilityWithRegistry(
                PluginRegistry.fromDirs(List.of(temp)),
                "runtime-testbed",
                capability,
                args,
                "{}",
                options);
    }

    private Path testbedExecutable() throws IOException {
        Path script = temp.resolve("runtime-testbed.sh");
        writeTestbedExecutable(script);
        return script;
    }

    private static void writeTestbedExecutable(Path script) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Files.writeString(script, """
                #!/bin/sh
                exec "%s" -cp "%s" dev.dediren.testbeds.pluginruntime.Main "$@"
                """.formatted(java, classpath));
        script.toFile().setExecutable(true);
    }

    private static void writePermissiveSchemas(Path dir, String... names) throws IOException {
        for (String name : names) {
            Files.writeString(dir.resolve(name), "{}");
        }
    }

    private static void writeManifest(Path dir, String id, String executable, List<String> capabilities)
            throws IOException {
        writeManifest(dir, id, executable, capabilities, List.of(
                "DEDIREN_TEST_PLUGIN_CAPABILITIES",
                "DEDIREN_TEST_PLUGIN_ID",
                "DEDIREN_TEST_PLUGIN_MODE"));
    }

    private static void writeManifest(
            Path dir,
            String id,
            String executable,
            List<String> capabilities,
            List<String> allowedEnv) throws IOException {
        var manifest = JsonSupport.objectMapper().createObjectNode();
        manifest.put("plugin_manifest_schema_version", "plugin-manifest.schema.v1");
        manifest.put("id", id);
        manifest.put("version", "0.1.0");
        manifest.put("executable", executable);
        var capabilityArray = manifest.putArray("capabilities");
        capabilities.forEach(capabilityArray::add);
        var envArray = manifest.putArray("allowed_env");
        allowedEnv.forEach(envArray::add);
        Files.writeString(dir.resolve(id + ".manifest.json"), JsonSupport.objectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(manifest));
    }
}
