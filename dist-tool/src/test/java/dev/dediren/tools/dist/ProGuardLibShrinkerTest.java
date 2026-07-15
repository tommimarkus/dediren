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

    assertThat(args)
        .containsSubsequence(
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
        .contains("!META-INF/*.SF")
        .contains("!META-INF/LICENSE*")
        .contains("!META-INF/NOTICE*")
        .contains("!META-INF/versions/**")
        .contains("!images/**")
        .contains("!model/**")
        .contains("!schema/**")
        .contains("!**.melk")
        .contains("!**._trace")
        .contains("!module-info.class");
  }
}
