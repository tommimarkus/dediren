package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistModuleTest {
    @Test
    void moduleLoads() {
        assertThat(DistModule.moduleName()).isEqualTo("dist");
    }

    @Test
    void bundleNameUsesVersionOnlyForJavaArchive() {
        assertThat(DistTool.bundleName("0.22.1"))
            .isEqualTo("dediren-agent-bundle-0.22.1");
    }

    @Test
    void bundleMetadataTargetIsJavaForSchemaCompatibility() {
        assertThat(DistTool.bundleMetadataTarget()).isEqualTo("java");
    }

    @Test
    void retiredTargetOptionFailsClearly(@TempDir Path root) {
        assertRetiredTargetFails(new String[] {
            "smoke",
            "--root", root.toString(),
            "--version", "0.22.1",
            "--target", "x86_64-unknown-linux-gnu"
        });
        assertRetiredTargetFails(new String[] {
            "build",
            "--root", root.toString(),
            "--version", "0.22.1",
            "--notices", root.resolve("THIRD-PARTY-NOTICES.md").toString(),
            "--target", "x86_64-unknown-linux-gnu"
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
        Path staleBundle = root.resolve("dist/dediren-agent-bundle-0.22.1-x86_64-unknown-linux-gnu");
        Path staleArchive = root.resolve("dist/dediren-agent-bundle-0.22.1-x86_64-unknown-linux-gnu.tar.gz");
        Files.createDirectories(staleBundle);
        Files.writeString(staleArchive, "stale archive");

        DistTool.run(new String[] {
            "build",
            "--root", root.toString(),
            "--version", "0.22.1",
            "--notices", notices.toString()
        });

        Path bundle = root.resolve("dist/dediren-agent-bundle-0.22.1");
        assertThat(bundle).isDirectory();
        Path archive = root.resolve("dist/dediren-agent-bundle-0.22.1.tar.gz");
        assertThat(archive).isRegularFile();
        assertThat(staleBundle).doesNotExist();
        assertThat(staleArchive).doesNotExist();

        String archiveMetadata = readArchiveEntry(archive, "dediren-agent-bundle-0.22.1/bundle.json");
        JsonNode metadata = JsonSupport.objectMapper().readTree(archiveMetadata);
        assertThat(metadata.path("version").asText()).isEqualTo("0.22.1");
        assertThat(metadata.path("target").asText()).isEqualTo("java");
    }

    @Test
    void launcherScriptExportsBundleRootFromAppHome() {
        String script = """
            #!/bin/sh
            APP_HOME=$( cd -P "${APP_HOME:-./}.." > /dev/null && printf '%s\\n' "$PWD" ) || exit

            DEFAULT_JVM_OPTS='"-Ddediren.version=0.22.1"'
            """;

        String rewritten = DistTool.withBundleRootExport(script);

        assertThat(rewritten)
            .contains("DEDIREN_BUNDLE_ROOT=\"${DEDIREN_BUNDLE_ROOT:-$APP_HOME}\"")
            .contains("export DEDIREN_BUNDLE_ROOT")
            .containsSubsequence("APP_HOME=$(", "DEDIREN_BUNDLE_ROOT=");
    }

    @Test
    void mavenLauncherScriptExportsBundleRootFromBasedir() {
        String script = """
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
    void bundledPluginIdsAreDerivedFromPluginLaunchers() {
        assertThat(DistTool.bundledPluginIds())
            .containsExactly(
                "generic-graph",
                "elk-layout",
                "svg-render",
                "archimate-oef",
                "uml-xmi");
    }

    @Test
    void launcherInstallDirsUseMavenReactorModulePaths() {
        assertThat(DistTool.launcherInstallDirs())
            .containsExactly(
                "cli/target/appassembler",
                "plugins/generic-graph/target/appassembler",
                "plugins/elk-layout/target/appassembler",
                "plugins/svg-render/target/appassembler",
                "plugins/archimate-oef-export/target/appassembler",
                "plugins/uml-xmi-export/target/appassembler");
    }

    @Test
    void releaseWorkflowPublishesSingleJavaArchive() throws Exception {
        String workflow = Files.readString(workspaceRoot().resolve(".github/workflows/release.yml"));
        String buildJob = workflowJob(workflow, "build");
        String publishJob = workflowJob(workflow, "publish");
        String buildStep = workflowStepContaining(buildJob, "./mvnw -pl dist-tool -am verify -Pdist-smoke");
        String captureStep = workflowStepContaining(buildJob, "mapfile -t archives");
        String uploadStep = workflowStepWithUses(buildJob, "uses: actions/upload-artifact@");
        String downloadStep = workflowStepWithUses(publishJob, "uses: actions/download-artifact@");
        String verifyStep = workflowStepContaining(publishJob, "tar -xOf");
        String publishReleaseStep = workflowStepContaining(publishJob, "gh release create");

        assertThat(workflow).doesNotContain(
            "matrix.target",
            "DEDIREN_DIST_TARGET",
            "x86_64-unknown-linux-gnu",
            "aarch64-unknown-linux-gnu",
            "aarch64-apple-darwin",
            "expected_targets");

        assertLine(buildJob, "runs-on: ubuntu-24.04");
        assertThat(buildJob).doesNotContain("strategy:", "matrix:", "${{ matrix.");
        assertLine(buildStep, "run: ./mvnw -pl dist-tool -am verify -Pdist-smoke");
        assertThat(buildStep).doesNotContain("DEDIREN_DIST_TARGET", "${{ matrix.");
        assertLine(captureStep, "id: archive");
        assertLine(captureStep, "shell: bash");
        assertChecksExactlyOneJavaArchive(captureStep);
        assertThat(countWorkflowStepsWithUses(buildJob, "uses: actions/upload-artifact@")).isEqualTo(1);
        assertLine(uploadStep, "name: dediren-agent-bundle");
        assertLine(uploadStep, "path: ${{ steps.archive.outputs.path }}");
        assertThat(uploadStep).doesNotContain("*", "${{ matrix.");

        assertThat(countWorkflowStepsWithUses(publishJob, "uses: actions/download-artifact@")).isEqualTo(1);
        assertLine(downloadStep, "name: dediren-agent-bundle");
        assertLine(downloadStep, "path: release-artifacts");
        assertThat(downloadStep).doesNotContain("pattern:", "merge-multiple:");

        assertThat(verifyStep)
            .contains("bundle=\"dediren-agent-bundle-${VERSION}\"")
            .contains("archive=\"release-assets/dediren-agent-bundle-${VERSION}.tar.gz\"")
            .contains(".version == $version and .target == \"java\"")
            .doesNotContain(
                "for target in",
                "--arg target",
                "dediren-agent-bundle-${VERSION}-",
                "dediren-agent-bundle-*-jvm",
                "expected_targets");
        assertChecksExactlyOneTarArchive(verifyStep);

        assertThat(publishReleaseStep)
            .contains("release-assets/dediren-agent-bundle-${VERSION}.tar.gz")
            .doesNotContain(
                "release-assets/*.tar.gz",
                "release-assets/dediren-agent-bundle-*.tar.gz",
                "release-assets/dediren-agent-bundle-${VERSION}-");
    }

    private static Path workspaceRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".github/workflows/release.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("workspace root not found");
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

    private static void assertLine(String text, String expected) {
        assertThat(text.split("\\R"))
            .as("line %s", expected)
            .anyMatch(line -> line.trim().equals(expected));
    }

    private static void assertChecksExactlyOneTarArchive(String verifyStep) {
        assertLineSequence(
            verifyStep,
            "tar_count=$(find release-artifacts -maxdepth 1 -type f -name '*.tar.gz' | wc -l)",
            "if [[ \"$tar_count\" -ne 1 ]]; then",
            "echo \"Expected exactly one release archive, found $tar_count\" >&2",
            "exit 1",
            "fi");
    }

    private static void assertChecksExactlyOneJavaArchive(String captureStep) {
        assertLineSequence(
            captureStep,
            "mapfile -t archives < <(find dist -maxdepth 1 -type f -name 'dediren-agent-bundle-*.tar.gz' | sort)",
            "if [[ \"${#archives[@]}\" -ne 1 ]]; then",
            "echo \"Expected exactly one Java archive, found ${#archives[@]}\" >&2",
            "find dist -maxdepth 1 -type f -name 'dediren-agent-bundle-*.tar.gz' -print >&2",
            "exit 1",
            "fi",
            "echo \"path=${archives[0]}\" >> \"$GITHUB_OUTPUT\"");
    }

    private static void assertLineSequence(String text, String... expectedLines) {
        String[] actual = text.lines()
            .map(String::trim)
            .toArray(String[]::new);
        for (int start = 0; start <= actual.length - expectedLines.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < expectedLines.length; offset++) {
                if (!actual[start + offset].equals(expectedLines[offset])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return;
            }
        }
        assertThat(actual).as("contiguous line sequence").containsSequence(expectedLines);
    }

    private static void writeMinimalDistributionRoot(Path root) throws Exception {
        writeLauncher(root, "cli/target/appassembler", "cli");
        writeLauncher(root, "plugins/generic-graph/target/appassembler", "generic-graph");
        writeLauncher(root, "plugins/elk-layout/target/appassembler", "elk-layout");
        writeLauncher(root, "plugins/svg-render/target/appassembler", "svg-render");
        writeLauncher(root, "plugins/archimate-oef-export/target/appassembler", "archimate-oef-export");
        writeLauncher(root, "plugins/uml-xmi-export/target/appassembler", "uml-xmi-export");
        Files.createDirectories(root.resolve("fixtures/plugins"));
        Files.createDirectories(root.resolve("schemas"));
        Files.createDirectories(root.resolve("fixtures/source"));
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/agent-usage.md"), "# Agent usage\n");
        Files.writeString(root.resolve("LICENSE"), "test license\n");
    }

    private static String readArchiveEntry(Path archive, String entry) throws Exception {
        Process process = new ProcessBuilder("tar", "-xOf", archive.toString(), entry)
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor())
            .as("tar -xOf %s %s%n%s", archive, entry, output)
            .isZero();
        return output;
    }

    private static void writeLauncher(Path root, String installDir, String sourceScript) throws Exception {
        Path install = root.resolve(installDir);
        Files.createDirectories(install.resolve("bin"));
        Files.createDirectories(install.resolve("lib"));
        Files.writeString(install.resolve("bin").resolve(sourceScript), """
            #!/bin/sh
            BASEDIR=$(dirname "$0")/..
            exec "$JAVACMD" "$@"
            """);
    }
}
