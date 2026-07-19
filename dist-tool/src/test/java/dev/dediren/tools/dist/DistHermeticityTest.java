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
 * appassembler launcher classpath, and fail loudly on any divergence (stale or missing staged
 * jars).
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
    assertThat(root.resolve("dist/dediren-agent-bundle-" + VERSION + ".tar.xz")).doesNotExist();
  }

  @Test
  void buildFailsOnLauncherClasspathJarMissingFromStagedLib(@TempDir Path root) throws Exception {
    writeDistributionRoot(root);
    Files.delete(root.resolve("cli/target/appassembler/lib/render-module-" + VERSION + ".jar"));

    assertThatThrownBy(() -> runBuild(root))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("render-module-" + VERSION + ".jar")
        .hasMessageContaining("cli/target/appassembler");
    assertThat(root.resolve("dist/dediren-agent-bundle-" + VERSION + ".tar.xz")).doesNotExist();
  }

  @Test
  void buildPackagesSingleShrunkJar(@TempDir Path root) throws Exception {
    writeDistributionRoot(root);
    List<Path> shrunk = new java.util.ArrayList<>();
    LibShrinker fakeShrinker =
        (stagedJars, mergedJar) -> {
          shrunk.addAll(stagedJars);
          Files.writeString(mergedJar, "merged jar");
        };
    DistTool.build(
        root, VERSION, root.resolve("THIRD-PARTY-NOTICES.md"), bundle -> {}, fakeShrinker);

    Path archive = root.resolve("dist/dediren-agent-bundle-" + VERSION + ".tar.xz");
    assertThat(archive).isRegularFile();
    List<String> entries = archiveEntries(archive);
    List<String> libJars =
        entries.stream()
            .filter(entry -> entry.startsWith("dediren-agent-bundle-" + VERSION + "/lib/"))
            .filter(entry -> !entry.endsWith("/"))
            .map(entry -> entry.substring(entry.lastIndexOf('/') + 1))
            .sorted()
            .toList();
    // The packaged lib/ holds exactly the shrunk merged jar; the staged inputs never ship.
    assertThat(libJars).containsExactly("dediren-bundle-" + VERSION + ".jar");
    // The shrinker saw every staged jar, in launcher classpath order.
    assertThat(shrunk)
        .extracting(path -> path.getFileName().toString())
        .containsExactly(
            "dep-alpha-1.0.0.jar",
            "cli-module-" + VERSION + ".jar",
            "generic-graph-module-" + VERSION + ".jar",
            "elk-layout-module-" + VERSION + ".jar",
            "render-module-" + VERSION + ".jar",
            "archimate-oef-export-module-" + VERSION + ".jar",
            "uml-xmi-export-module-" + VERSION + ".jar");
    // The shipped launcher classpath is the single jar.
    String script = readArchiveEntry(archive, "dediren-agent-bundle-" + VERSION + "/bin/dediren");
    assertThat(script)
        .contains("CLASSPATH=\"$BASEDIR\"/etc:\"$REPO\"/dediren-bundle-" + VERSION + ".jar")
        .doesNotContain("dep-alpha-1.0.0.jar");
  }

  @Test
  void buildFailsWhenShrinkerLeavesUnexpectedLibEntries(@TempDir Path root) throws Exception {
    writeDistributionRoot(root);
    // A misbehaving shrinker that drops residue next to the merged jar must trip the packaged-lib
    // exact-match guard (verifyPackagedLib) — the post-shrink half of the hermeticity contract.
    LibShrinker leakyShrinker =
        (stagedJars, mergedJar) -> {
          Files.writeString(mergedJar, "merged jar");
          Files.writeString(mergedJar.resolveSibling("stray-residue.jar"), "residue");
        };

    assertThatThrownBy(
            () ->
                DistTool.build(
                    root,
                    VERSION,
                    root.resolve("THIRD-PARTY-NOTICES.md"),
                    bundle -> {},
                    leakyShrinker))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stray-residue.jar");
    assertThat(root.resolve("dist/dediren-agent-bundle-" + VERSION + ".tar.xz")).doesNotExist();
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
    writeCliLauncher(root);
    Files.createDirectories(root.resolve("schemas"));
    Files.createDirectories(root.resolve("fixtures/source"));
    Files.createDirectories(root.resolve("docs"));
    Files.writeString(root.resolve("docs/agent-usage.md"), "# Agent usage\n");
    Files.writeString(root.resolve("LICENSE"), "test license\n");
    Files.writeString(root.resolve("THIRD-PARTY-NOTICES.md"), "# Notices\n");
  }

  /** The engine module jars the collapsed cli launcher now carries on its classpath. */
  private static final List<String> ENGINE_MODULE_JARS =
      List.of("generic-graph", "elk-layout", "render", "archimate-oef-export", "uml-xmi-export");

  /**
   * Writes the single appassembler-shaped cli launcher whose CLASSPATH declares its own module jar,
   * a shared dependency jar, and the five engine module jars (the collapsed per-plugin classpaths),
   * and stages exactly those jars in {@code lib/}. The packaged {@code lib/} must equal this set.
   */
  private static void writeCliLauncher(Path root) throws Exception {
    Path install = root.resolve("cli/target/appassembler");
    Files.createDirectories(install.resolve("bin"));
    Files.createDirectories(install.resolve("lib"));
    List<String> moduleJars = new java.util.ArrayList<>();
    moduleJars.add("cli-module-" + VERSION + ".jar");
    for (String engine : ENGINE_MODULE_JARS) {
      moduleJars.add(engine + "-module-" + VERSION + ".jar");
    }
    StringBuilder classpath = new StringBuilder("\"$BASEDIR\"/etc:\"$REPO\"/dep-alpha-1.0.0.jar");
    for (String jar : moduleJars) {
      classpath.append(":\"$REPO\"/").append(jar);
    }
    Files.writeString(
        install.resolve("bin/cli"),
        """
            #!/bin/sh
            BASEDIR=$(dirname "$0")/..
            REPO="$BASEDIR"/lib
            CLASSPATH=%s
            exec "$JAVACMD" -classpath "$CLASSPATH" "$@"
            """
            .formatted(classpath));
    Files.writeString(install.resolve("lib/dep-alpha-1.0.0.jar"), "shared dependency jar");
    for (String jar : moduleJars) {
      Files.writeString(install.resolve("lib").resolve(jar), "module jar");
    }
  }

  private static List<String> archiveEntries(Path archive) throws Exception {
    Process process =
        new ProcessBuilder("tar", "-tf", archive.toString()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as("tar -tf %s%n%s", archive, output).isZero();
    return output.lines().toList();
  }
}
