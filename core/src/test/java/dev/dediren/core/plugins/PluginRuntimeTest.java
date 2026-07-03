package dev.dediren.core.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.json.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PluginRuntimeTest {
  @TempDir Path temp;

  @Test
  void missingExecutableReturnsTypedDiagnostic() throws Exception {
    writeManifest(
        temp, "runtime-testbed", temp.resolve("missing-binary").toString(), List.of("render"));

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
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(Map.of("DEDIREN_TEST_PLUGIN_ID", "different-plugin"));

    assertThatThrownBy(
            () ->
                PluginRunner.runForCapabilityWithRegistry(
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
  void failedCapabilityProbeIsStructured() throws Exception {
    // A plugin (or launcher) that cannot start its runtime exits non-zero during the probe,
    // which core normalizes into a structured diagnostic rather than leaking a raw failure.
    // This is the path a missing runtime dependency (e.g. ELK without Java) takes.
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));

    assertThatThrownBy(() -> runWithMode("capabilities-nonzero", "render", List.of("render")))
        .isInstanceOf(PluginExecutionException.class)
        .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
        .isEqualTo("DEDIREN_PLUGIN_CAPABILITY_PROBE_FAILED");
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
  void thirdPartyExportArtifactKindIsAcceptedFromUntrustedManifest() throws Exception {
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("export"));
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(
                Map.of(
                    "DEDIREN_TEST_PLUGIN_CAPABILITIES", "export",
                    "DEDIREN_TEST_PLUGIN_ARTIFACT_KIND", "ticket-stats+json"));

    PluginRunOutcome outcome =
        PluginRunner.runForCapabilityWithRegistry(
            PluginRegistry.fromDirs(List.of(temp)),
            "runtime-testbed",
            "export",
            List.of("export"),
            "{}",
            options);

    assertThat(outcome.exitCode()).isZero();
    assertThat(outcome.stdout()).contains("\"ticket-stats+json\"");
  }

  @Test
  void bundledManifestExportOutputKeepsFirstPartyArtifactKindEnum() throws Exception {
    // A manifest discovered in the trusted bundled first-party directory keeps the exact
    // first-party artifact_kind enum, so a drifting first-party export is still caught.
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("export"));
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(
                Map.of(
                    "DEDIREN_TEST_PLUGIN_CAPABILITIES", "export",
                    "DEDIREN_TEST_PLUGIN_ARTIFACT_KIND", "ticket-stats+json"));

    assertThatThrownBy(
            () ->
                PluginRunner.runForCapabilityWithRegistry(
                    PluginRegistry.fromDirs(List.of(temp), List.of(temp)),
                    "runtime-testbed",
                    "export",
                    List.of("export"),
                    "{}",
                    options))
        .isInstanceOf(PluginExecutionException.class)
        .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
        .isEqualTo("DEDIREN_PLUGIN_OUTPUT_INVALID_DATA");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Archimate-OEF+xml", // uppercase leading/id characters
        "ticket-stats", // missing +suffix
        "", // empty string
        "ticket-stats+yaml", // unknown suffix
        "tïcket+json" // non-ASCII id character
      })
  void thirdPartyExportArtifactKindMustStillMatchTheBasePattern(String artifactKind)
      throws Exception {
    // Distinct rejection partitions for ^[a-z0-9][a-z0-9.-]*\+(xml|json|text)$, each independently
    // caught as DEDIREN_PLUGIN_OUTPUT_INVALID_DATA against the relaxed base export-result schema.
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("export"));
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(
                Map.of(
                    "DEDIREN_TEST_PLUGIN_CAPABILITIES",
                    "export",
                    "DEDIREN_TEST_PLUGIN_ARTIFACT_KIND",
                    artifactKind));

    assertThatThrownBy(
            () ->
                PluginRunner.runForCapabilityWithRegistry(
                    PluginRegistry.fromDirs(List.of(temp)),
                    "runtime-testbed",
                    "export",
                    List.of("export"),
                    "{}",
                    options))
        .isInstanceOf(PluginExecutionException.class)
        .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
        .isEqualTo("DEDIREN_PLUGIN_OUTPUT_INVALID_DATA");
  }

  @Test
  void firstPartyIdFromUntrustedDirectoryStillGetsRelaxedBaseSchema() throws Exception {
    // Identity-spoofing guard (P0b): trust — and therefore the closed first-party artifact_kind
    // enum — is keyed on the discovery directory, not on the id a manifest claims. A manifest that
    // claims a real first-party id ("archimate-oef") but is discovered in an UNTRUSTED directory is
    // validated against the relaxed base export-result schema, so a pattern-valid, non-enum kind
    // ("archimate-oef+text") is accepted. A spoofed id cannot borrow first-party trust.
    writeManifest(temp, "archimate-oef", testbedExecutable().toString(), List.of("export"));
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(
                Map.of(
                    "DEDIREN_TEST_PLUGIN_ID", "archimate-oef",
                    "DEDIREN_TEST_PLUGIN_CAPABILITIES", "export",
                    "DEDIREN_TEST_PLUGIN_ARTIFACT_KIND", "archimate-oef+text"));

    PluginRunOutcome outcome =
        PluginRunner.runForCapabilityWithRegistry(
            PluginRegistry.fromDirs(List.of(temp)),
            "archimate-oef",
            "export",
            List.of("export"),
            "{}",
            options);

    assertThat(outcome.exitCode()).isZero();
    assertThat(outcome.stdout()).contains("\"archimate-oef+text\"");
  }

  @Test
  void structuredPluginErrorEnvelopeIsPreservedAndReportedNonZero() throws Exception {
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));

    PluginRunOutcome outcome = runWithMode("error-envelope-zero", "render", List.of("render"));

    assertThat(outcome.exitCode()).isEqualTo(3);
    assertThat(outcome.stdout()).contains("DEDIREN_TESTBED_ERROR");
  }

  @Test
  void pluginRunsFromDeterministicProductRootWorkingDirectory() throws Exception {
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));

    PluginRunOutcome outcome = runWithMode("report-cwd", "render", List.of("render"));

    // The testbed reports its own working directory in the error-envelope message; core must
    // launch it from the product root, not the caller's inherited working directory. Assert
    // equality (not substring): the inherited test-fork cwd is a child of the product root, so
    // a substring check would pass even without the fix.
    String reportedCwd =
        JsonSupport.objectMapper().readTree(outcome.stdout()).at("/diagnostics/0/message").asText();
    assertThat(outcome.exitCode()).isEqualTo(3);
    assertThat(reportedCwd).isEqualTo(dev.dediren.core.DedirenPaths.productRoot().toString());
    assertThat(reportedCwd).isNotEqualTo(System.getProperty("user.dir"));
  }

  @Test
  void manifestAllowedEnvIsPassedToCapabilityProbeAndCommand() throws Exception {
    writeManifest(
        temp,
        "runtime-testbed",
        testbedExecutable().toString(),
        List.of("layout"),
        List.of("DEDIREN_TEST_PLUGIN_CAPABILITIES"));
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(Map.of("DEDIREN_TEST_PLUGIN_CAPABILITIES", "layout"));

    PluginRunOutcome outcome =
        PluginRunner.runForCapabilityWithRegistry(
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
    writeManifest(
        temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"), List.of());
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(Map.of("DEDIREN_TEST_PLUGIN_MODE", "invalid-json"));

    PluginRunOutcome outcome =
        PluginRunner.runForCapabilityWithRegistry(
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
    writeManifest(
        plugins, "runtime-testbed", testbedExecutable().toString(), List.of("render"), List.of());
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
  void bundledRegistryDiscoversConfiguredDirectoriesFromEnvAndMarksThemUntrusted()
      throws Exception {
    Path bundleRoot = temp.resolve("bundle-root");
    Path schemas = bundleRoot.resolve("schemas");
    Path configured = temp.resolve("user-plugins");
    Files.createDirectories(schemas);
    Files.createDirectories(bundleRoot.resolve("plugins"));
    Files.createDirectories(configured);
    writePermissiveSchemas(schemas, "model.schema.json", "plugin-manifest.schema.json");
    writeManifest(
        configured,
        "runtime-testbed",
        testbedExecutable().toString(),
        List.of("render"),
        List.of());
    String originalBundleRoot = System.getProperty("dediren.bundle.root");
    System.setProperty("dediren.bundle.root", bundleRoot.toString());
    try {
      PluginRegistry registry =
          PluginRegistry.bundled(Map.of("DEDIREN_PLUGIN_DIRS", configured.toString()));
      LoadedPluginManifest manifest = registry.loadManifest("runtime-testbed");

      assertThat(manifest.path()).isEqualTo(configured.resolve("runtime-testbed.manifest.json"));
      assertThat(manifest.trusted()).isFalse();
    } finally {
      restoreProperty("dediren.bundle.root", originalBundleRoot);
    }
  }

  @Test
  void bundledRegistryDiscoversProjectPluginsFromCallerWorkingDirectory() throws Exception {
    // `.dediren/plugins` is a project-level registration directory: it resolves against the
    // CLI process's own working directory, not the bundle root. It is discovered only when the
    // caller opts in with DEDIREN_ALLOW_PROJECT_PLUGINS (here "1"), and it stays untrusted.
    Path bundleRoot = temp.resolve("bundle-root");
    Path schemas = bundleRoot.resolve("schemas");
    Path project = temp.resolve("project");
    Path projectPlugins = project.resolve(".dediren/plugins");
    Files.createDirectories(schemas);
    Files.createDirectories(bundleRoot.resolve("plugins"));
    Files.createDirectories(projectPlugins);
    writePermissiveSchemas(schemas, "model.schema.json", "plugin-manifest.schema.json");
    writeManifest(
        projectPlugins,
        "runtime-testbed",
        testbedExecutable().toString(),
        List.of("export"),
        List.of());
    String originalUserDir = System.getProperty("user.dir");
    String originalBundleRoot = System.getProperty("dediren.bundle.root");
    System.setProperty("user.dir", project.toString());
    System.setProperty("dediren.bundle.root", bundleRoot.toString());
    try {
      LoadedPluginManifest manifest =
          PluginRegistry.bundled(Map.of("DEDIREN_ALLOW_PROJECT_PLUGINS", "1"))
              .loadManifest("runtime-testbed");

      assertThat(manifest.path())
          .isEqualTo(projectPlugins.resolve("runtime-testbed.manifest.json"));
      assertThat(manifest.trusted()).isFalse();
    } finally {
      restoreProperty("user.dir", originalUserDir);
      restoreProperty("dediren.bundle.root", originalBundleRoot);
    }
  }

  @Test
  void projectPluginsInCallerWorkingDirectoryAreNotDiscoveredWithoutOptIn() throws Exception {
    // Security gate (PB-1): with DEDIREN_ALLOW_PROJECT_PLUGINS unset, a manifest present only in
    // the caller-cwd `.dediren/plugins` directory is not discovered at all. Invoking an executable
    // registered in an untrusted cloned repo is arbitrary code execution with the caller's
    // privileges, so cwd project-plugin discovery is opt-in and closed by default.
    Path bundleRoot = temp.resolve("bundle-root");
    Path schemas = bundleRoot.resolve("schemas");
    Path project = temp.resolve("project");
    Path projectPlugins = project.resolve(".dediren/plugins");
    Files.createDirectories(schemas);
    Files.createDirectories(bundleRoot.resolve("plugins"));
    Files.createDirectories(projectPlugins);
    writePermissiveSchemas(schemas, "model.schema.json", "plugin-manifest.schema.json");
    writeManifest(
        projectPlugins,
        "runtime-testbed",
        testbedExecutable().toString(),
        List.of("export"),
        List.of());
    String originalUserDir = System.getProperty("user.dir");
    String originalBundleRoot = System.getProperty("dediren.bundle.root");
    System.setProperty("user.dir", project.toString());
    System.setProperty("dediren.bundle.root", bundleRoot.toString());
    try {
      assertThatThrownBy(() -> PluginRegistry.bundled(Map.of()).loadManifest("runtime-testbed"))
          .isInstanceOf(PluginExecutionException.class)
          .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
          .isEqualTo(DiagnosticCode.PLUGIN_UNKNOWN.code());
    } finally {
      restoreProperty("user.dir", originalUserDir);
      restoreProperty("dediren.bundle.root", originalBundleRoot);
    }
  }

  @Test
  void projectPluginsInCallerWorkingDirectoryAreDiscoveredWhenOptInIsTrue() throws Exception {
    // The gate also opens for the case-insensitive "true" spelling, not just "1".
    Path bundleRoot = temp.resolve("bundle-root");
    Path schemas = bundleRoot.resolve("schemas");
    Path project = temp.resolve("project");
    Path projectPlugins = project.resolve(".dediren/plugins");
    Files.createDirectories(schemas);
    Files.createDirectories(bundleRoot.resolve("plugins"));
    Files.createDirectories(projectPlugins);
    writePermissiveSchemas(schemas, "model.schema.json", "plugin-manifest.schema.json");
    writeManifest(
        projectPlugins,
        "runtime-testbed",
        testbedExecutable().toString(),
        List.of("export"),
        List.of());
    String originalUserDir = System.getProperty("user.dir");
    String originalBundleRoot = System.getProperty("dediren.bundle.root");
    System.setProperty("user.dir", project.toString());
    System.setProperty("dediren.bundle.root", bundleRoot.toString());
    try {
      LoadedPluginManifest manifest =
          PluginRegistry.bundled(Map.of("DEDIREN_ALLOW_PROJECT_PLUGINS", "true"))
              .loadManifest("runtime-testbed");

      assertThat(manifest.path())
          .isEqualTo(projectPlugins.resolve("runtime-testbed.manifest.json"));
      assertThat(manifest.trusted()).isFalse();
    } finally {
      restoreProperty("user.dir", originalUserDir);
      restoreProperty("dediren.bundle.root", originalBundleRoot);
    }
  }

  @Test
  void projectPluginDirectoryIsOrderedAfterBundledAndBeforeConfiguredDirs() throws Exception {
    Path bundleRoot = temp.resolve("bundle-root");
    Path schemas = bundleRoot.resolve("schemas");
    Path bundledPlugins = bundleRoot.resolve("plugins");
    Path project = temp.resolve("project");
    Path projectPlugins = project.resolve(".dediren/plugins");
    Path configured = temp.resolve("user-plugins");
    Files.createDirectories(schemas);
    Files.createDirectories(bundledPlugins);
    Files.createDirectories(projectPlugins);
    Files.createDirectories(configured);
    writePermissiveSchemas(schemas, "model.schema.json", "plugin-manifest.schema.json");
    String executable = testbedExecutable().toString();
    writeManifest(bundledPlugins, "runtime-testbed", executable, List.of("export"), List.of());
    writeManifest(projectPlugins, "runtime-testbed", executable, List.of("export"), List.of());
    writeManifest(projectPlugins, "other-plugin", executable, List.of("export"), List.of());
    writeManifest(configured, "other-plugin", executable, List.of("export"), List.of());
    String originalUserDir = System.getProperty("user.dir");
    String originalBundleRoot = System.getProperty("dediren.bundle.root");
    System.setProperty("user.dir", project.toString());
    System.setProperty("dediren.bundle.root", bundleRoot.toString());
    try {
      PluginRegistry registry =
          PluginRegistry.bundled(
              Map.of(
                  "DEDIREN_PLUGIN_DIRS",
                  configured.toString(),
                  "DEDIREN_ALLOW_PROJECT_PLUGINS",
                  "1"));

      assertThat(registry.loadManifest("runtime-testbed").path())
          .isEqualTo(bundledPlugins.resolve("runtime-testbed.manifest.json"));
      assertThat(registry.loadManifest("other-plugin").path())
          .isEqualTo(projectPlugins.resolve("other-plugin.manifest.json"));
    } finally {
      restoreProperty("user.dir", originalUserDir);
      restoreProperty("dediren.bundle.root", originalBundleRoot);
    }
  }

  @Test
  void bundledManifestWinsWhenSameIdIsRegisteredInEveryDiscoveryTier() throws Exception {
    // P2(i): with the same id present in the bundled, caller-cwd project (opt-in on), and
    // DEDIREN_PLUGIN_DIRS tiers at once, the trusted bundled manifest wins by discovery order.
    Path bundleRoot = temp.resolve("bundle-root");
    Path schemas = bundleRoot.resolve("schemas");
    Path bundledPlugins = bundleRoot.resolve("plugins");
    Path project = temp.resolve("project");
    Path projectPlugins = project.resolve(".dediren/plugins");
    Path configured = temp.resolve("user-plugins");
    Files.createDirectories(schemas);
    Files.createDirectories(bundledPlugins);
    Files.createDirectories(projectPlugins);
    Files.createDirectories(configured);
    writePermissiveSchemas(schemas, "model.schema.json", "plugin-manifest.schema.json");
    String executable = testbedExecutable().toString();
    writeManifest(bundledPlugins, "runtime-testbed", executable, List.of("export"), List.of());
    writeManifest(projectPlugins, "runtime-testbed", executable, List.of("export"), List.of());
    writeManifest(configured, "runtime-testbed", executable, List.of("export"), List.of());
    String originalUserDir = System.getProperty("user.dir");
    String originalBundleRoot = System.getProperty("dediren.bundle.root");
    System.setProperty("user.dir", project.toString());
    System.setProperty("dediren.bundle.root", bundleRoot.toString());
    try {
      PluginRegistry registry =
          PluginRegistry.bundled(
              Map.of(
                  "DEDIREN_PLUGIN_DIRS",
                  configured.toString(),
                  "DEDIREN_ALLOW_PROJECT_PLUGINS",
                  "1"));
      LoadedPluginManifest manifest = registry.loadManifest("runtime-testbed");

      assertThat(manifest.path())
          .isEqualTo(bundledPlugins.resolve("runtime-testbed.manifest.json"));
      assertThat(manifest.trusted()).isTrue();
    } finally {
      restoreProperty("user.dir", originalUserDir);
      restoreProperty("dediren.bundle.root", originalBundleRoot);
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
      PluginRunOutcome outcome =
          PluginRunner.runForCapabilityWithRegistry(
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
      PluginRunOutcome outcome =
          PluginRunner.runForCapabilityWithRegistry(
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

  @Test
  void manifestTrustSkipsProbeAndBypassesRuntimeIdCheck() throws Exception {
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("layout"));
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(
                Map.of(
                    "DEDIREN_TEST_PLUGIN_MODE", "ok",
                    "DEDIREN_TEST_PLUGIN_CAPABILITIES", "layout",
                    "DEDIREN_TEST_PLUGIN_ID", "different-plugin",
                    "DEDIREN_TRUST_MANIFEST_CAPABILITIES", "1"));

    PluginRunOutcome outcome =
        PluginRunner.runForCapabilityWithRegistry(
            PluginRegistry.fromDirs(List.of(temp), List.of(temp)),
            "runtime-testbed",
            "layout",
            List.of("layout"),
            "{}",
            options);

    // Probe is skipped, so the mismatched runtime id is never inspected and the work command runs.
    assertThat(outcome.exitCode()).isZero();
    assertThat(outcome.stdout()).contains("\"layout_result_schema_version\"");
  }

  @Test
  void manifestTrustIsIgnoredForUntrustedDiscoveryDirectory() throws Exception {
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("layout"));
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(
                Map.of(
                    "DEDIREN_TEST_PLUGIN_MODE", "ok",
                    "DEDIREN_TEST_PLUGIN_CAPABILITIES", "layout",
                    "DEDIREN_TEST_PLUGIN_ID", "different-plugin",
                    "DEDIREN_TRUST_MANIFEST_CAPABILITIES", "1"));

    // A manifest from an untrusted directory cannot use trust mode to skip the probe, so the
    // runtime id mismatch is still caught even though trust is requested.
    assertThatThrownBy(
            () ->
                PluginRunner.runForCapabilityWithRegistry(
                    PluginRegistry.fromDirs(List.of(temp)),
                    "runtime-testbed",
                    "layout",
                    List.of("layout"),
                    "{}",
                    options))
        .isInstanceOf(PluginExecutionException.class)
        .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
        .isEqualTo("DEDIREN_PLUGIN_ID_MISMATCH");
  }

  @Test
  void manifestTrustStillValidatesWorkOutput() throws Exception {
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("layout"));
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(
                Map.of(
                    "DEDIREN_TEST_PLUGIN_MODE", "invalid-data",
                    "DEDIREN_TEST_PLUGIN_CAPABILITIES", "layout",
                    "DEDIREN_TRUST_MANIFEST_CAPABILITIES", "true"));

    assertThatThrownBy(
            () ->
                PluginRunner.runForCapabilityWithRegistry(
                    PluginRegistry.fromDirs(List.of(temp), List.of(temp)),
                    "runtime-testbed",
                    "layout",
                    List.of("layout"),
                    "{}",
                    options))
        .isInstanceOf(PluginExecutionException.class)
        .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
        .isEqualTo("DEDIREN_PLUGIN_OUTPUT_INVALID_DATA");
  }

  @Test
  void manifestTrustDoesNotShortCircuitExplicitCapabilitiesCommand() throws Exception {
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("layout"));
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(
                Map.of(
                    "DEDIREN_TRUST_MANIFEST_CAPABILITIES", "1",
                    "DEDIREN_TEST_PLUGIN_CAPABILITIES", "layout"));

    PluginRunOutcome outcome =
        PluginRunner.runForCapabilityWithRegistry(
            PluginRegistry.fromDirs(List.of(temp)),
            "runtime-testbed",
            "capabilities",
            List.of("capabilities"),
            "",
            options);

    assertThat(outcome.exitCode()).isZero();
    assertThat(outcome.stdout()).contains("\"id\"");
  }

  @Test
  void exceededWorkCommandTimeoutReturnsTypedDiagnostic() throws Exception {
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));
    var options =
        PluginRunOptions.defaults()
            .withCandidateEnv(Map.of("DEDIREN_TEST_PLUGIN_MODE", "sleep"))
            .withTimeout(Duration.ofMillis(200));

    assertThatThrownBy(
            () ->
                PluginRunner.runForCapabilityWithRegistry(
                    PluginRegistry.fromDirs(List.of(temp)),
                    "runtime-testbed",
                    "render",
                    List.of("render"),
                    "{}",
                    options))
        .isInstanceOf(PluginExecutionException.class)
        .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
        .isEqualTo(DiagnosticCode.PLUGIN_TIMEOUT.code());
  }

  @Test
  void okEnvelopeWithNonZeroExitIsStructuredProcessFailure() throws Exception {
    writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));

    assertThatThrownBy(() -> runWithMode("ok-envelope-nonzero", "render", List.of("render")))
        .isInstanceOf(PluginExecutionException.class)
        .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
        .isEqualTo(DiagnosticCode.PLUGIN_PROCESS_FAILED.code());
  }

  @Test
  void unknownPluginIdIsStructured() throws Exception {
    assertThatThrownBy(() -> PluginRegistry.fromDirs(List.of(temp)).loadManifest("does-not-exist"))
        .isInstanceOf(PluginExecutionException.class)
        .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
        .isEqualTo(DiagnosticCode.PLUGIN_UNKNOWN.code());
  }

  @Test
  void schemaInvalidManifestIsStructured() throws Exception {
    // A manifest missing the required "id"/"executable"/"capabilities" fields must fail the
    // plugin-manifest schema.
    Files.writeString(
        temp.resolve("broken.manifest.json"),
        "{\"plugin_manifest_schema_version\":\"plugin-manifest.schema.v1\"}");

    assertThatThrownBy(() -> PluginRegistry.fromDirs(List.of(temp)).loadManifest("broken"))
        .isInstanceOf(PluginExecutionException.class)
        .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
        .isEqualTo(DiagnosticCode.PLUGIN_MANIFEST_INVALID.code());
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  private PluginRunOutcome runWithMode(String mode, String capability, List<String> args)
      throws Exception {
    var options =
        PluginRunOptions.defaults().withCandidateEnv(Map.of("DEDIREN_TEST_PLUGIN_MODE", mode));
    return PluginRunner.runForCapabilityWithRegistry(
        PluginRegistry.fromDirs(List.of(temp)), "runtime-testbed", capability, args, "{}", options);
  }

  private Path testbedExecutable() throws IOException {
    Path script = temp.resolve("runtime-testbed.sh");
    writeTestbedExecutable(script);
    return script;
  }

  private static void writeTestbedExecutable(Path script) throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("java.class.path");
    Files.writeString(
        script,
        """
                #!/bin/sh
                exec "%s" -cp "%s" dev.dediren.testbeds.pluginruntime.Main "$@"
                """
            .formatted(java, classpath));
    script.toFile().setExecutable(true);
  }

  private static void writePermissiveSchemas(Path dir, String... names) throws IOException {
    for (String name : names) {
      Files.writeString(dir.resolve(name), "{}");
    }
  }

  private static void writeManifest(
      Path dir, String id, String executable, List<String> capabilities) throws IOException {
    writeManifest(
        dir,
        id,
        executable,
        capabilities,
        List.of(
            "DEDIREN_TEST_PLUGIN_ARTIFACT_KIND",
            "DEDIREN_TEST_PLUGIN_CAPABILITIES",
            "DEDIREN_TEST_PLUGIN_ID",
            "DEDIREN_TEST_PLUGIN_MODE"));
  }

  private static void writeManifest(
      Path dir, String id, String executable, List<String> capabilities, List<String> allowedEnv)
      throws IOException {
    var manifest = JsonSupport.objectMapper().createObjectNode();
    manifest.put("plugin_manifest_schema_version", "plugin-manifest.schema.v1");
    manifest.put("id", id);
    manifest.put("version", "0.1.0");
    manifest.put("executable", executable);
    var capabilityArray = manifest.putArray("capabilities");
    capabilities.forEach(capabilityArray::add);
    var envArray = manifest.putArray("allowed_env");
    allowedEnv.forEach(envArray::add);
    Files.writeString(
        dir.resolve(id + ".manifest.json"),
        JsonSupport.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
  }
}
