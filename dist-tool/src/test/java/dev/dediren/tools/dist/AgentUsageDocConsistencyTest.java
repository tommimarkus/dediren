package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the shipped, agent-facing {@code docs/agent-usage.md} against drift: every {@code
 * DEDIREN_*} token it references must exist in the product source, and every CalVer version string
 * in it must match the product version. Converts the CLAUDE.md "Files That Move Together" prose
 * discipline into an automated check, so a renamed diagnostic code or a missed version bump fails
 * CI instead of silently shipping a wrong agent contract.
 */
class AgentUsageDocConsistencyTest {
  private static final Pattern TOKEN = Pattern.compile("DEDIREN_[A-Z_]+");
  private static final Pattern CALVER = Pattern.compile("\\b\\d{4}\\.\\d{2}\\.\\d+\\b");

  @Test
  void agentUsageDiagnosticTokensExistInSource() throws IOException {
    Path repoRoot = repoRoot();
    String doc = Files.readString(repoRoot.resolve("docs/agent-usage.md"), StandardCharsets.UTF_8);
    Set<String> universe = sourceTokens(repoRoot);

    Set<String> unknown = new TreeSet<>();
    Matcher matcher = TOKEN.matcher(doc);
    while (matcher.find()) {
      String token = matcher.group();
      // A token ending in '_' is a documented prefix/wildcard (e.g. DEDIREN_PLUGIN_<PLUGIN_ID>,
      // DEDIREN_PLUGIN_OUTPUT_INVALID_*); accept it when some real token starts with it.
      boolean ok =
          token.endsWith("_")
              ? universe.stream().anyMatch(known -> known.startsWith(token))
              : universe.contains(token);
      if (!ok) {
        unknown.add(token);
      }
    }

    assertThat(unknown)
        .as(
            "docs/agent-usage.md references DEDIREN_* tokens that exist in no .java source "
                + "(likely a renamed diagnostic code or env var)")
        .isEmpty();
  }

  @Test
  void agentUsageVersionStringsMatchProductVersion() throws IOException {
    Path repoRoot = repoRoot();
    String expected = productVersion(repoRoot);
    String doc = Files.readString(repoRoot.resolve("docs/agent-usage.md"), StandardCharsets.UTF_8);

    Set<String> mismatched = new TreeSet<>();
    Matcher matcher = CALVER.matcher(doc);
    while (matcher.find()) {
      if (!matcher.group().equals(expected)) {
        mismatched.add(matcher.group());
      }
    }

    assertThat(mismatched)
        .as(
            "docs/agent-usage.md contains version strings that do not match the product version "
                + expected)
        .isEmpty();
  }

  private static Set<String> sourceTokens(Path repoRoot) throws IOException {
    Set<String> tokens = new TreeSet<>();
    Files.walkFileTree(
        repoRoot,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            // Skip dotdirs (.git/.claude/.idea — unreadable or irrelevant) and build output. Do NOT
            // skip by the name "dist": that is also the leaf of package dev.dediren.tools.dist.
            if (name.startsWith(".") || name.equals("target")) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (file.toString().endsWith(".java")) {
              try {
                Matcher matcher = TOKEN.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                  tokens.add(matcher.group());
                }
              } catch (IOException ignored) {
                // Unreadable source file: skip; the guard is best-effort over the universe.
              }
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        });
    return tokens;
  }

  private static String productVersion(Path repoRoot) throws IOException {
    String pom = Files.readString(repoRoot.resolve("pom.xml"), StandardCharsets.UTF_8);
    Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(pom);
    if (!matcher.find()) {
      throw new IllegalStateException("no <version> element in root pom.xml");
    }
    return matcher.group(1).trim();
  }

  private static Path repoRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}
