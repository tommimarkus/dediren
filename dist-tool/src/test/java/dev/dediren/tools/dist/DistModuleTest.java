package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class DistModuleTest {
  @Test
  void bundleNameUsesVersionOnlyForJavaArchive() {
    assertThat(DistTool.bundleName("2026.06.0")).isEqualTo("dediren-agent-bundle-2026.06.0");
  }

  @Test
  void bundleMetadataTargetIsJavaForSchemaCompatibility() {
    assertThat(DistTool.bundleMetadataTarget()).isEqualTo("java");
  }

  @Test
  void retiredTargetOptionFailsClearly(@TempDir Path root) {
    assertRetiredTargetFails(
        new String[] {
          "smoke",
          "--root",
          root.toString(),
          "--version",
          "2026.06.0",
          "--target",
          "x86_64-unknown-linux-gnu"
        });
    assertRetiredTargetFails(
        new String[] {
          "build",
          "--root",
          root.toString(),
          "--version",
          "2026.06.0",
          "--notices",
          root.resolve("THIRD-PARTY-NOTICES.md").toString(),
          "--target",
          "x86_64-unknown-linux-gnu"
        });
  }

  private static void assertRetiredTargetFails(String[] args) {
    assertThatThrownBy(() -> DistTool.run(args))
        .hasMessageContaining("--target is no longer supported");
  }

  @Test
  void buildProducesVersionOnlyJavaArchive(@TempDir Path root) throws Exception {
    writeMinimalDistributionRoot(root);
    Path notices = root.resolve("THIRD-PARTY-NOTICES.md");
    Files.writeString(notices, "# Notices\n");
    Path staleBundle = root.resolve("dist/dediren-agent-bundle-2026.06.0-x86_64-unknown-linux-gnu");
    Path staleArchive =
        root.resolve("dist/dediren-agent-bundle-2026.06.0-x86_64-unknown-linux-gnu.tar.gz");
    Files.createDirectories(staleBundle);
    Files.writeString(staleArchive, "stale archive");

    // Calls the build seam directly (fake shrinker; text-fixture jars must never reach the real
    // ProGuard pass). CLI-arg dispatch for `build` is covered end-to-end by -Pdist-smoke.
    DistTool.build(
        root,
        "2026.06.0",
        notices,
        bundleDir -> {},
        (stagedJars, mergedJar) -> Files.writeString(mergedJar, "merged jar"));

    Path bundle = root.resolve("dist/dediren-agent-bundle-2026.06.0");
    assertThat(bundle).isDirectory();
    Path archive = root.resolve("dist/dediren-agent-bundle-2026.06.0.tar.xz");
    assertThat(archive).isRegularFile();
    assertThat(staleBundle).doesNotExist();
    assertThat(staleArchive).doesNotExist();

    String archiveMetadata =
        readArchiveEntry(archive, "dediren-agent-bundle-2026.06.0/bundle.json");
    JsonNode metadata = JsonSupport.objectMapper().readTree(archiveMetadata);
    assertThat(metadata.path("bundle_schema_version").asText())
        .isEqualTo(DistTool.BUNDLE_SCHEMA_VERSION);
    assertThat(metadata.path("version").asText()).isEqualTo("2026.06.0");
    assertThat(metadata.path("target").asText()).isEqualTo("java");
    // Single-launcher cutover: the descriptor no longer advertises a process-plugin surface.
    assertThat(metadata.has("plugins"))
        .describedAs("bundle.schema.v2 dropped the plugins[] array")
        .isFalse();
    assertThat(metadata.has("elk_helper"))
        .describedAs("bundle.schema.v2 dropped the elk_helper launcher pointer")
        .isFalse();

    // No bundled plugin-manifest directory is staged any more.
    assertThat(archiveEntries(archive))
        .noneMatch(entry -> entry.startsWith("dediren-agent-bundle-2026.06.0/plugins/"));

    // The shipped agent-facing doc rides in the bundle.
    assertThat(readArchiveEntry(archive, "dediren-agent-bundle-2026.06.0/docs/agent-usage.md"))
        .contains("# Agent usage");
  }

  private static java.util.List<String> archiveEntries(Path archive) throws Exception {
    Process process =
        new ProcessBuilder("tar", "-tf", archive.toString()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as("tar -tf %s%n%s", archive, output).isZero();
    return output.lines().toList();
  }

  @Test
  void noticesIdentifyLicencesAndEmbedTexts(@TempDir Path root) throws Exception {
    Path lib = root.resolve("cli/target/appassembler/lib");
    Files.createDirectories(lib);
    for (String jar :
        java.util.List.of(
            "cli-2026.07.1.jar",
            "guava-33.6.0-jre.jar",
            "org.eclipse.elk.core-0.11.0.jar",
            "org.eclipse.emf.ecore-2.12.0.jar",
            "slf4j-api-2.0.17.jar",
            "xml-apis-1.4.01.jar")) {
      Files.writeString(lib.resolve(jar), "");
    }
    Path output = root.resolve("THIRD-PARTY-NOTICES.md");

    DistTool.run(
        new String[] {"notices", "--root", root.toString(), "--output", output.toString()});

    // Redistribution compliance: every third-party jar carries a licence identification,
    // EPL source availability is stated, and the referenced licence texts ride in the file.
    assertThat(Files.readString(output))
        .contains("- cli-2026.07.1.jar")
        .contains("- guava-33.6.0-jre.jar (Google Guava, Apache-2.0)")
        .contains("- org.eclipse.elk.core-0.11.0.jar (Eclipse Layout Kernel (ELK), EPL-2.0)")
        .contains("- org.eclipse.emf.ecore-2.12.0.jar (Eclipse Modeling Framework (EMF), EPL-1.0)")
        .contains("- slf4j-api-2.0.17.jar (SLF4J, MIT (SLF4J))")
        .contains(
            "- xml-apis-1.4.01.jar (Apache XML Commons xml-apis, Apache-2.0 / SAX (public domain)"
                + " / W3C Software License)")
        .contains("## Source Code Availability")
        .contains("Apache License")
        .contains("Eclipse Public License - v 2.0")
        .contains("Eclipse Public License - v 1.0")
        .contains("W3C® SOFTWARE NOTICE AND LICENSE")
        .contains("QOS.ch")
        // The bundle ships one shrink-merged jar, so the notices must describe modified
        // redistribution and point at the relocated embedded licence files.
        .contains("modified object form")
        .contains("shrink-only")
        .contains("`META-INF/third-party/<jar-name>/`")
        .doesNotContain("unmodified object form");
  }

  @Test
  void noticesFailOnUnattributedThirdPartyJar(@TempDir Path root) throws Exception {
    Path lib = root.resolve("cli/target/appassembler/lib");
    Files.createDirectories(lib);
    Files.writeString(lib.resolve("mystery-1.0.jar"), "");
    Path output = root.resolve("THIRD-PARTY-NOTICES.md");

    assertThatThrownBy(
            () ->
                DistTool.run(
                    new String[] {
                      "notices", "--root", root.toString(), "--output", output.toString()
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mystery-1.0.jar");
  }

  @Test
  void withMergedClasspathReplacesClasspathLineWithSingleJar() {
    String script =
        "#!/bin/sh\n"
            + "CLASSPATH=\"$BASEDIR\"/etc:\"$REPO\"/a-1.jar:\"$REPO\"/b-2.jar\n"
            + "exec java\n";

    String rewritten = DistTool.withMergedClasspath(script, "dediren-bundle-2026.06.0.jar");

    assertThat(rewritten)
        .contains("CLASSPATH=\"$BASEDIR\"/etc:\"$REPO\"/dediren-bundle-2026.06.0.jar")
        .doesNotContain("a-1.jar")
        .doesNotContain("b-2.jar")
        .contains("exec java");
  }

  @Test
  void withMergedClasspathFailsWithoutClasspathLine() {
    assertThatThrownBy(() -> DistTool.withMergedClasspath("#!/bin/sh\nexec java\n", "x.jar"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CLASSPATH");
  }

  @Test
  void mergedJarNameCarriesTheProductVersion() {
    assertThat(DistTool.mergedJarName("2026.06.0")).isEqualTo("dediren-bundle-2026.06.0.jar");
  }

  @Test
  void launcherScriptExportsBundleRootFromAppHome() {
    String script =
        """
            #!/bin/sh
            APP_HOME=$( cd -P "${APP_HOME:-./}.." > /dev/null && printf '%s\\n' "$PWD" ) || exit

            DEFAULT_JVM_OPTS='"-Ddediren.version=2026.06.0"'
            """;

    String rewritten = DistTool.withBundleRootExport(script);

    assertThat(rewritten)
        .contains("DEDIREN_BUNDLE_ROOT=\"${DEDIREN_BUNDLE_ROOT:-$APP_HOME}\"")
        .contains("export DEDIREN_BUNDLE_ROOT")
        .containsSubsequence("APP_HOME=$(", "DEDIREN_BUNDLE_ROOT=");
  }

  @Test
  void mavenLauncherScriptExportsBundleRootFromBasedir() {
    String script =
        """
            #!/bin/sh
            BASEDIR=$(dirname "$0")/..
            exec "$JAVACMD" "$@"
            """;

    String rewritten = DistTool.withBundleRootExport(script);

    assertThat(rewritten)
        .contains("DEDIREN_BUNDLE_ROOT=\"${DEDIREN_BUNDLE_ROOT:-$BASEDIR}\"")
        .contains("export DEDIREN_BUNDLE_ROOT")
        .containsSubsequence("BASEDIR=$(", "DEDIREN_BUNDLE_ROOT=");
  }

  @Test
  void launcherInstallDirsCollapseToTheSingleCliLauncher() {
    // Cutover B: the five per-plugin appassembler launchers are gone; only the cli launcher (which
    // now carries the five engine jars on its classpath) is staged into the bundle.
    assertThat(DistTool.launcherInstallDirs()).containsExactly("cli/target/appassembler");
  }

  @Test
  void releaseWorkflowPublishesSingleJavaArchive() throws Exception {
    // Workflow YAML linting (step text) is out of scope here; this test guards the
    // single-platform-neutral-archive invariants only.
    String workflow = Files.readString(workspaceRoot().resolve(".github/workflows/release.yml"));
    String buildJob = workflowJob(workflow, "build");
    String publishJob = workflowJob(workflow, "publish");
    String buildStep =
        workflowStepContaining(buildJob, "./mvnw -pl dist-tool -am verify -Pdist-smoke");
    String uploadStep = workflowStepWithUses(buildJob, "uses: actions/upload-artifact@");
    String downloadStep = workflowStepWithUses(publishJob, "uses: actions/download-artifact@");
    String verifyStep = workflowStepContaining(publishJob, "tar -xOf");
    String publishReleaseStep = workflowStepContaining(publishJob, "gh release create");

    assertThat(workflow)
        .doesNotContain(
            "matrix.target",
            "DEDIREN_DIST_TARGET",
            "x86_64-unknown-linux-gnu",
            "aarch64-unknown-linux-gnu",
            "aarch64-apple-darwin",
            "expected_targets");

    assertThat(buildJob).doesNotContain("strategy:", "matrix:", "${{ matrix.");
    assertThat(buildStep).doesNotContain("DEDIREN_DIST_TARGET", "${{ matrix.");
    assertThat(countWorkflowStepsWithUses(buildJob, "uses: actions/upload-artifact@")).isEqualTo(1);
    assertThat(uploadStep).doesNotContain("*", "${{ matrix.");

    assertThat(countWorkflowStepsWithUses(publishJob, "uses: actions/download-artifact@"))
        .isEqualTo(1);
    assertThat(downloadStep).doesNotContain("pattern:", "merge-multiple:");

    assertThat(verifyStep)
        .doesNotContain(
            "for target in",
            "--arg target",
            "dediren-agent-bundle-${VERSION}-",
            "dediren-agent-bundle-*-jvm",
            "expected_targets");

    assertThat(publishReleaseStep)
        .doesNotContain(
            "release-assets/*.tar.xz",
            "release-assets/dediren-agent-bundle-*.tar.xz",
            "release-assets/dediren-agent-bundle-${VERSION}-");
  }

  private static Path workspaceRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }

  private static String workflowJob(String workflow, String jobName) {
    return yamlBlock(workflow, "  " + jobName + ":");
  }

  private static String workflowStepWithUses(String job, String usesLinePrefix) {
    String[] lines = job.split("\\R", -1);
    for (int index = 0; index < lines.length; index++) {
      if (lines[index].startsWith("      - ")) {
        String step = yamlBlock(lines, index);
        if (hasTrimmedLineStartingWith(step, usesLinePrefix)) {
          return step;
        }
      }
    }
    throw new AssertionError("workflow step with " + usesLinePrefix + " not found");
  }

  private static int countWorkflowStepsWithUses(String job, String usesLinePrefix) {
    int count = 0;
    String[] lines = job.split("\\R", -1);
    for (int index = 0; index < lines.length; index++) {
      if (lines[index].startsWith("      - ")) {
        String step = yamlBlock(lines, index);
        if (hasTrimmedLineStartingWith(step, usesLinePrefix)) {
          count++;
        }
      }
    }
    return count;
  }

  private static String workflowStepContaining(String job, String requiredText) {
    String[] lines = job.split("\\R", -1);
    for (int index = 0; index < lines.length; index++) {
      if (lines[index].startsWith("      - ")) {
        String step = yamlBlock(lines, index);
        if (step.contains(requiredText)) {
          return step;
        }
      }
    }
    throw new AssertionError("workflow step containing " + requiredText + " not found");
  }

  private static String yamlBlock(String yaml, String firstLine) {
    String[] lines = yaml.split("\\R", -1);
    for (int index = 0; index < lines.length; index++) {
      if (lines[index].equals(firstLine)) {
        return yamlBlock(lines, index);
      }
    }
    throw new AssertionError("workflow block " + firstLine + " not found");
  }

  private static String yamlBlock(String[] lines, int start) {
    int indent = leadingSpaces(lines[start]);
    StringBuilder block = new StringBuilder(lines[start]);
    for (int index = start + 1; index < lines.length; index++) {
      String line = lines[index];
      if (!line.isBlank() && leadingSpaces(line) <= indent) {
        break;
      }
      block.append('\n').append(line);
    }
    return block.toString();
  }

  private static int leadingSpaces(String line) {
    int spaces = 0;
    while (spaces < line.length() && line.charAt(spaces) == ' ') {
      spaces++;
    }
    return spaces;
  }

  private static boolean hasTrimmedLineStartingWith(String text, String prefix) {
    for (String line : text.split("\\R")) {
      if (line.trim().startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static void writeMinimalDistributionRoot(Path root) throws Exception {
    writeLauncher(root, "cli/target/appassembler", "cli");
    Files.createDirectories(root.resolve("schemas"));
    Files.createDirectories(root.resolve("fixtures/source"));
    Files.createDirectories(root.resolve("docs"));
    Files.writeString(root.resolve("docs/agent-usage.md"), "# Agent usage\n");
    Files.writeString(root.resolve("LICENSE"), "test license\n");
  }

  private static String readArchiveEntry(Path archive, String entry) throws Exception {
    Process process =
        new ProcessBuilder("tar", "-xOf", archive.toString(), entry)
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as("tar -xOf %s %s%n%s", archive, entry, output).isZero();
    return output;
  }

  @Test
  void withJvmLoggingOptsInjectsLogRoutingAfterBundleRootExport() {
    String base =
        "#!/bin/sh\n"
            + "BASEDIR=$(dirname \"$0\")/..\n"
            + "DEDIREN_BUNDLE_ROOT=\"${DEDIREN_BUNDLE_ROOT:-$BASEDIR}\"\n"
            + "export DEDIREN_BUNDLE_ROOT\n"
            + "exec \"$JAVACMD\" $JAVA_OPTS -classpath \"$CLASSPATH\" dev.dediren.Main \"$@\"\n";
    String rewritten = DistTool.withJvmLoggingOpts(base);
    org.assertj.core.api.Assertions.assertThat(rewritten)
        // JVM log output must be routed OFF stdout and onto stderr so stdout stays JSON-pure
        // (agents decide success from stdout alone). all=off:stdout clears the default stdout sink;
        // stderr keeps warnings so a VM diagnostic (e.g. os,container under cgroups) still
        // surfaces.
        .contains("-Xlog:all=off:stdout -Xlog:all=warning:stderr:uptime,level,tags")
        // DEDIREN_LOG_LEVEL is mapped through the injection-safe allowlist before the JVM starts.
        .contains("case \"${DEDIREN_LOG_LEVEL:-}\" in")
        // AppCDS was removed: no archive setup should be injected anymore.
        .doesNotContain("-XX:+AutoCreateSharedArchive")
        .doesNotContain("DEDIREN_CDS_DIR")
        .doesNotContain(".jsa");
    // injected block sits after the BUNDLE_ROOT export and before the exec line
    org.assertj.core.api.Assertions.assertThat(rewritten.indexOf("-Xlog:all=off:stdout"))
        .isGreaterThan(rewritten.indexOf("export DEDIREN_BUNDLE_ROOT"))
        .isLessThan(rewritten.indexOf("exec "));
  }

  @Test
  void withJvmLoggingOptsIsIdempotent() {
    String base = "#!/bin/sh\nexport DEDIREN_BUNDLE_ROOT\nexec x\n";
    String once = DistTool.withJvmLoggingOpts(base);
    String twice = DistTool.withJvmLoggingOpts(once);
    org.assertj.core.api.Assertions.assertThat(twice).isEqualTo(once);
  }

  @Test
  void stdoutEnablingXlogAllowsTheRoutingButRejectsAnyStdoutSink() {
    // The sanctioned routing (all off on stdout, warnings on stderr) is clean.
    assertThat(
            DistTool.stdoutEnablingXlog(
                "JAVA_OPTS=\"$JAVA_OPTS -Xlog:all=off:stdout"
                    + " -Xlog:all=warning:stderr:uptime,level,tags\""))
        .isNull();
    // Off-only or non-stdout directives a maintainer might legitimately add are fine.
    assertThat(DistTool.stdoutEnablingXlog("-Xlog:gc:stderr")).isNull();
    assertThat(DistTool.stdoutEnablingXlog("-Xlog:gc:file=/tmp/gc.log")).isNull();
    assertThat(DistTool.stdoutEnablingXlog("-Xlog:all=off")).isNull();
    assertThat(DistTool.stdoutEnablingXlog("-Xlog:gc=off:stdout")).isNull();
    assertThat(DistTool.stdoutEnablingXlog("-Xlog:disable")).isNull();
    // A later edit that re-opens the stdout sink -- explicitly, by omitting the output (stdout is
    // -Xlog's default), via decorators-only, or as a bare -Xlog -- must be caught by name.
    assertThat(DistTool.stdoutEnablingXlog("exec java -Xlog:gc:stdout x"))
        .isEqualTo("-Xlog:gc:stdout");
    assertThat(DistTool.stdoutEnablingXlog("exec java -Xlog:gc x")).isEqualTo("-Xlog:gc");
    assertThat(DistTool.stdoutEnablingXlog("exec java -Xlog:safepoint::uptime x"))
        .isEqualTo("-Xlog:safepoint::uptime");
    assertThat(DistTool.stdoutEnablingXlog("exec java -Xlog:all=info:stdout x"))
        .isEqualTo("-Xlog:all=info:stdout");
    assertThat(DistTool.stdoutEnablingXlog("exec java -Xlog x")).isEqualTo("-Xlog");
  }

  private static void writeLauncher(Path root, String installDir, String sourceScript)
      throws Exception {
    Path install = root.resolve(installDir);
    Files.createDirectories(install.resolve("bin"));
    Files.createDirectories(install.resolve("lib"));
    Files.writeString(
        install.resolve("bin").resolve(sourceScript),
        """
            #!/bin/sh
            BASEDIR=$(dirname "$0")/..
            REPO="$BASEDIR"/lib
            CLASSPATH="$BASEDIR"/etc:"$REPO"/cli-2026.06.0.jar
            exec "$JAVACMD" -classpath "$CLASSPATH" "$@"
            """);
    Files.writeString(install.resolve("lib/cli-2026.06.0.jar"), "module jar");
  }
}
