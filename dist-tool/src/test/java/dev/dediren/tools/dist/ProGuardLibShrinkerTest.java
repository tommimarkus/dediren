package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProGuardLibShrinkerTest {

  @Test
  void proGuardArgsListInjarsInClasspathOrderThenOutjarLibraryjarsAndKeepRules() {
    List<Path> staged = List.of(Path.of("/lib/dep-alpha-1.0.0.jar"), Path.of("/lib/cli-1.jar"));

    List<String> args =
        ProGuardLibShrinker.proGuardArgs(staged, Path.of("/out/merged.jar"), Path.of("/keep.pro"));

    // Exhaustive: an extra or duplicated ProGuard argument silently changes shrink behavior,
    // so the full argument vector is pinned, not just the relative order.
    assertThat(args)
        .containsExactly(
            "-injars",
            "/lib/dep-alpha-1.0.0.jar" + ProGuardLibShrinker.INJAR_FILTER,
            "-injars",
            "/lib/cli-1.jar" + ProGuardLibShrinker.INJAR_FILTER,
            "-outjars",
            "/out/merged.jar",
            "-libraryjars",
            "<java.home>/jmods(!**.jar;!module-info.class)",
            "@/keep.pro");
  }

  @Test
  void injarFilterStripsSignaturesEclipseResourcesAndEmbeddedLicences() {
    // Licence files are stripped from the ProGuard input because MergedJarPostProcessor
    // re-adds them collision-free under META-INF/third-party/<jar>/.
    assertThat(ProGuardLibShrinker.INJAR_FILTER)
        // 45 input jars each carry a manifest; a classpath jar needs none, and excluding them
        // keeps the merge deterministic instead of first-wins.
        .contains("!META-INF/MANIFEST.MF")
        .contains("!META-INF/*.SF")
        .contains("!META-INF/*.RSA")
        .contains("!META-INF/*.DSA")
        .contains("!META-INF/LICENSE*")
        .contains("!META-INF/NOTICE*")
        // about.html/about_files must stay out of the ProGuard pass: MergedJarPostProcessor
        // re-adds them collision-free from the originals under META-INF/third-party/<jar>/.
        .contains("!about.html")
        .contains("!about_files/**")
        .contains("!META-INF/versions/**")
        .contains("!images/**")
        .contains("!model/**")
        .contains("!schema/**")
        .contains("!**.melk")
        .contains("!**._trace")
        .contains("!module-info.class");
  }
}
