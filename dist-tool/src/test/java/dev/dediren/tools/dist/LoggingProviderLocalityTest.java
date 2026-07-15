package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the documented SLF4J posture: only cli binds a provider at runtime, test-support and
 * schema-cache bind slf4j-simple for tests, and no module anywhere pulls a different backend. The
 * rule was prose-only until 2026-07-15; this makes drift a build failure.
 */
class LoggingProviderLocalityTest {

  /** module dir path (relative to repo root) -> the only scope slf4j-simple may have there. */
  private static final Map<String, String> SIMPLE_ALLOWED =
      Map.of("cli", "compile", "test-support", "compile", "schema-cache", "test");

  private static final List<String> BANNED_BACKENDS =
      List.of("logback-classic", "slf4j-jdk14", "slf4j-log4j12", "log4j-slf4j2-impl", "slf4j-nop");

  @Test
  void slf4jProvidersLiveOnlyWhereDocumented() throws IOException {
    Path repoRoot = repoRoot();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> poms = Files.walk(repoRoot)) {
      for (Path pom :
          poms.filter(p -> p.getFileName().toString().equals("pom.xml"))
              .filter(p -> !p.toString().contains("target"))
              .filter(p -> !hasDotSegment(repoRoot.relativize(p)))
              .toList()) {
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        Path moduleDir = repoRoot.relativize(pom).getParent();
        String module = moduleDir == null ? "" : moduleDir.toString().replace('\\', '/');
        if (module.isEmpty()) {
          // The repo root pom.xml is not itself a module; its dependencyManagement block only
          // pins versions for children to inherit, so it has no binding of its own to check.
          continue;
        }
        for (String backend : BANNED_BACKENDS) {
          if (xml.contains("<artifactId>" + backend + "</artifactId>")) {
            violations.add(module + " declares banned backend " + backend);
          }
        }
        int at = xml.indexOf("<artifactId>slf4j-simple</artifactId>");
        if (at >= 0) {
          String allowedScope = SIMPLE_ALLOWED.get(module);
          // Bound the scope search by this <dependency>'s own closing tag rather than a fixed
          // character count: several of these bindings carry long explanatory comments between
          // <artifactId> and <scope> (schema-cache's alone is 686 chars), so a short fixed window
          // misses the real tag, and a large enough one risks reading the *next* dependency's
          // <scope> instead (cli's slf4j-simple is immediately followed by an assertj-core
          // dependency at test scope).
          int close = xml.indexOf("</dependency>", at);
          String window = xml.substring(at, close >= 0 ? close : xml.length());
          boolean testScoped = window.contains("<scope>test</scope>");
          if (allowedScope == null) {
            violations.add(module + " binds slf4j-simple but is not an allowed binding site");
          } else if (allowedScope.equals("test") != testScoped) {
            violations.add(module + " binds slf4j-simple at the wrong scope");
          }
        }
      }
    }
    assertThat(violations)
        .as(
            "SLF4J provider bindings must match the documented posture (CLAUDE.md Engine Runtime Rules)")
        .isEmpty();
  }

  /**
   * True when any element of a repo-relative path starts with '.'. The main checkout keeps sibling
   * git worktrees under .worktrees/, and dot-caches (.cache, .git, ...) also live at the repo root;
   * none of those are modules this guard should walk into.
   */
  private static boolean hasDotSegment(Path relativePath) {
    for (int i = 0; i < relativePath.getNameCount(); i++) {
      if (relativePath.getName(i).toString().startsWith(".")) {
        return true;
      }
    }
    return false;
  }

  private static Path repoRoot() {
    // Copy AgentUsageDocConsistencyTest's private repoRoot() body verbatim.
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}
