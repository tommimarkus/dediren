package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SEED-1 regression coverage: the dist build must package exactly the jars declared on each
 * appassembler launcher classpath, fail loudly on any divergence (stale or missing staged jars),
 * and never ship runtime-generated CDS content ({@code cds/}, {@code *.jsa}) in the archive.
 */
class DistHermeticityTest {
  private static final String VERSION = "2026.06.0";

  @Test
  void buildFailsOnStagedJarNotOnLauncherClasspath(@TempDir Path root) throws Exception {
    writeDistributionRoot(root);
    Files.writeString(
        root.resolve("cli/target/appassembler/lib/fake-stale-0.0.1.jar"), "stale jar");

    assertThatThrownBy(() -> runBuild(root))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fake-stale-0.0.1.jar")
        .hasMessageContaining("cli/target/appassembler");
    assertThat(root.resolve("dist/dediren-agent-bundle-" + VERSION + ".tar.gz")).doesNotExist();
  }

  @Test
  void buildFailsOnLauncherClasspathJarMissingFromStagedLib(@TempDir Path root) throws Exception {
    writeDistributionRoot(root);
    Files.delete(root.resolve("plugins/render/target/appassembler/lib/dep-alpha-1.0.0.jar"));

    assertThatThrownBy(() -> runBuild(root))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dep-alpha-1.0.0.jar")
        .hasMessageContaining("plugins/render/target/appassembler");
    assertThat(root.resolve("dist/dediren-agent-bundle-" + VERSION + ".tar.gz")).doesNotExist();
  }

  @Test
  void buildPackagesExactlyDeclaredJarsAndExcludesRuntimeGeneratedCdsContent(@TempDir Path root)
      throws Exception {
    writeDistributionRoot(root);
    // A launcher writes CDS archives at first run to DEDIREN_CDS_DIR, which defaults to
    // $DEDIREN_BUNDLE_ROOT/cds — i.e. <bundle-staging-root>/cds, a sibling of lib/. That residue
    // only exists AFTER staging, so plant it via the staging seam (after staging, before tar) at
    // the REAL runtime location. This is what makes the tar --exclude=cds / --exclude=*.jsa flags
    // load-bearing: injecting it inside cli/target/appassembler/lib would never reach the archive
    // because copyDeclaredJars only copies allowlisted classpath jars, so the flags would never
    // fire. A stray top-level *.jsa is added too, so each exclude flag is independently required.
    DistTool.build(
        root,
        VERSION,
        root.resolve("THIRD-PARTY-NOTICES.md"),
        bundle -> {
          try {
            Files.createDirectories(bundle.resolve("cds"));
            Files.writeString(bundle.resolve("cds/cli.jsa"), "cds archive");
            Files.writeString(bundle.resolve("cds/elk-layout.jsa"), "cds archive");
            Files.writeString(bundle.resolve("stray.jsa"), "cds archive");
          } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
          }
        });

    Path archive = root.resolve("dist/dediren-agent-bundle-" + VERSION + ".tar.gz");
    assertThat(archive).isRegularFile();
    List<String> entries = archiveEntries(archive);
    assertThat(entries)
        .noneMatch(entry -> entry.endsWith(".jsa"))
        .noneMatch(entry -> entry.contains("/cds"));
    List<String> libJars =
        entries.stream()
            .filter(entry -> entry.startsWith("dediren-agent-bundle-" + VERSION + "/lib/"))
            .filter(entry -> !entry.endsWith("/"))
            .map(entry -> entry.substring(entry.lastIndexOf('/') + 1))
            .sorted()
            .toList();
    assertThat(libJars)
        .containsExactly(
            "archimate-oef-export-module-" + VERSION + ".jar",
            "cli-module-" + VERSION + ".jar",
            "dep-alpha-1.0.0.jar",
            "elk-layout-module-" + VERSION + ".jar",
            "generic-graph-module-" + VERSION + ".jar",
            "render-module-" + VERSION + ".jar",
            "uml-xmi-export-module-" + VERSION + ".jar");
  }

  private static void runBuild(Path root) throws Exception {
    DistTool.run(
        new String[] {
          "build",
          "--root",
          root.toString(),
          "--version",
          VERSION,
          "--notices",
          root.resolve("THIRD-PARTY-NOTICES.md").toString()
        });
  }

  private static void writeDistributionRoot(Path root) throws Exception {
    writeLauncher(root, "cli/target/appassembler", "cli");
    writeLauncher(root, "plugins/generic-graph/target/appassembler", "generic-graph");
    writeLauncher(root, "plugins/elk-layout/target/appassembler", "elk-layout");
    writeLauncher(root, "plugins/render/target/appassembler", "render");
    writeLauncher(root, "plugins/archimate-oef-export/target/appassembler", "archimate-oef-export");
    writeLauncher(root, "plugins/uml-xmi-export/target/appassembler", "uml-xmi-export");
    Files.createDirectories(root.resolve("fixtures/plugins"));
    Files.createDirectories(root.resolve("schemas"));
    Files.createDirectories(root.resolve("fixtures/source"));
    Files.createDirectories(root.resolve("docs"));
    Files.writeString(root.resolve("docs/agent-usage.md"), "# Agent usage\n");
    Files.writeString(root.resolve("docs/plugin-authoring.md"), "# Plugin authoring\n");
    Files.writeString(root.resolve("LICENSE"), "test license\n");
    Files.writeString(root.resolve("THIRD-PARTY-NOTICES.md"), "# Notices\n");
  }

  /**
   * Writes an appassembler-shaped launcher whose CLASSPATH declares a module jar plus a shared
   * dependency jar, and stages exactly those jars in {@code lib/}.
   */
  private static void writeLauncher(Path root, String installDir, String sourceScript)
      throws Exception {
    Path install = root.resolve(installDir);
    Files.createDirectories(install.resolve("bin"));
    Files.createDirectories(install.resolve("lib"));
    String moduleJar = sourceScript + "-module-" + VERSION + ".jar";
    Files.writeString(
        install.resolve("bin").resolve(sourceScript),
        """
            #!/bin/sh
            BASEDIR=$(dirname "$0")/..
            REPO="$BASEDIR"/lib
            CLASSPATH="$BASEDIR"/etc:"$REPO"/dep-alpha-1.0.0.jar:"$REPO"/%s
            exec "$JAVACMD" -classpath "$CLASSPATH" "$@"
            """
            .formatted(moduleJar));
    Files.writeString(install.resolve("lib/dep-alpha-1.0.0.jar"), "shared dependency jar");
    Files.writeString(install.resolve("lib").resolve(moduleJar), "module jar");
  }

  private static List<String> archiveEntries(Path archive) throws Exception {
    Process process =
        new ProcessBuilder("tar", "-tzf", archive.toString()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as("tar -tzf %s%n%s", archive, output).isZero();
    return output.lines().toList();
  }
}
