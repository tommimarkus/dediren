package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the shipped, agent-facing bundle doc ({@code docs/agent-usage.md}) against drift: every
 * {@code DEDIREN_*} token it references must exist in the product source, and every CalVer version
 * string in it must match the product version. Converts the CLAUDE.md "Files That Move Together"
 * prose discipline into an automated check, so a renamed diagnostic code or a missed version bump
 * fails CI instead of silently shipping a wrong agent contract.
 */
class AgentUsageDocConsistencyTest {
  private static final List<String> SHIPPED_DOCS = List.of("docs/agent-usage.md");
  private static final Pattern TOKEN = Pattern.compile("DEDIREN_[A-Z_]+");
  private static final Pattern CALVER = Pattern.compile("\\b\\d{4}\\.\\d{2}\\.\\d+\\b");

  @Test
  void shippedDocDiagnosticTokensExistInSource() throws IOException {
    Path repoRoot = repoRoot();
    Set<String> universe = sourceTokens(repoRoot);

    for (String docPath : SHIPPED_DOCS) {
      String doc = Files.readString(repoRoot.resolve(docPath), StandardCharsets.UTF_8);
      Set<String> unknown = new TreeSet<>();
      Matcher matcher = TOKEN.matcher(doc);
      while (matcher.find()) {
        String token = matcher.group();
        if (isFamilyPrefix(doc, matcher)) {
          // A genuine trailing-wildcard family declaration (e.g. DEDIREN_LAYOUT_*); accept it
          // when some real token starts with it.
          if (universe.stream().noneMatch(known -> known.startsWith(token))) {
            unknown.add(token);
          }
        } else if (!token.endsWith("_")) {
          if (!universe.contains(token)) {
            unknown.add(token);
          }
        }
        // else: the match stops at a '_' that is not followed by a genuine trailing '*' (for
        // example prose shorthand like DEDIREN_DRAWIO_*_LIMIT_EXCEEDED, where the '*' sits inside
        // a literal code rather than closing a family declaration). That is not a real token, so
        // it neither passes nor fails this check on its own.
      }

      assertThat(unknown)
          .as(
              docPath
                  + " references DEDIREN_* tokens that exist in no .java source "
                  + "(likely a renamed diagnostic code or env var)")
          .isEmpty();
    }
  }

  @Test
  void sourceTokensAreDocumentedIndividuallyOrByFamily() throws IOException {
    Path repoRoot = repoRoot();
    Set<String> documented = new TreeSet<>();
    Set<String> documentedPrefixes = new TreeSet<>();
    for (String docPath : List.of("docs/agent-usage.md", "README.md")) {
      collectDocumentedTokens(
          Files.readString(repoRoot.resolve(docPath), StandardCharsets.UTF_8),
          documented,
          documentedPrefixes);
    }

    Set<String> undocumented = new TreeSet<>();
    // Production-only universe: an agent operating the shipped product can only ever encounter a
    // DEDIREN_* code that production (src/main) code actually emits. Scanning test sources here
    // would force test-fixture tokens (DEDIREN_FAKE_*, DEDIREN_TEST, DEDIREN_X, ...) into the
    // shipped guide just to satisfy this check, wasting tokens on codes no agent will ever see.
    // The forward test above intentionally stays broad (all .java sources): it guards against doc
    // drift (a documented token that no longer exists anywhere), not against under-documentation.
    for (String token : sourceTokens(repoRoot, /* mainOnly= */ true)) {
      if (!isDocumented(token, documented, documentedPrefixes)) {
        undocumented.add(token);
      }
    }

    assertThat(undocumented)
        .as(
            "every DEDIREN_* token in production source (src/main) must be documented in"
                + " docs/agent-usage.md or README.md, either individually or via a documented"
                + " family prefix (e.g. DEDIREN_ELK_*) — add the code to '## Repair Rules' or"
                + " extend the internal-families paragraph")
        .isEmpty();
  }

  @Test
  void embeddedWildcardInsideALiteralCodeIsNotTreatedAsADocumentedFamilyPrefix() {
    // The same prose shorthand as docs/agent-usage.md's draw.io limits paragraph: the '*' stands
    // in for one of several suffixes and is embedded inside a single literal code, not a
    // standalone trailing-wildcard family declaration like `DEDIREN_ELK_*`.
    String doc =
        "value above it is rejected with a `DEDIREN_DRAWIO_*_LIMIT_EXCEEDED` (or"
            + " `INPUT_TOO_LARGE`) diagnostic.";

    Set<String> documented = new TreeSet<>();
    Set<String> documentedPrefixes = new TreeSet<>();
    collectDocumentedTokens(doc, documented, documentedPrefixes);

    assertThat(documentedPrefixes)
        .as(
            "an embedded '*' inside a literal code must not be read as a genuine family-prefix"
                + " declaration that then authorizes every DEDIREN_DRAWIO_ code")
        .doesNotContain("DEDIREN_DRAWIO_");
    assertThat(
            isDocumented(
                "DEDIREN_DRAWIO_SOMETHING_UNDOCUMENTED", documented, documentedPrefixes))
        .as(
            "a synthetic, wholly undocumented DEDIREN_DRAWIO_ code must not be silently"
                + " authorized by the *_LIMIT_EXCEEDED prose shorthand")
        .isFalse();
  }

  @Test
  void shippedDocVersionStringsMatchProductVersion() throws IOException {
    Path repoRoot = repoRoot();
    String expected = productVersion(repoRoot);

    for (String docPath : SHIPPED_DOCS) {
      String doc = Files.readString(repoRoot.resolve(docPath), StandardCharsets.UTF_8);
      Set<String> mismatched = new TreeSet<>();
      Matcher matcher = CALVER.matcher(doc);
      while (matcher.find()) {
        if (!matcher.group().equals(expected)) {
          mismatched.add(matcher.group());
        }
      }

      assertThat(mismatched)
          .as(
              docPath
                  + " contains version strings that do not match the product version "
                  + expected)
          .isEmpty();
    }
  }

  private static void collectDocumentedTokens(
      String doc, Set<String> documented, Set<String> documentedPrefixes) {
    Matcher matcher = TOKEN.matcher(doc);
    while (matcher.find()) {
      String token = matcher.group();
      if (isFamilyPrefix(doc, matcher)) {
        documentedPrefixes.add(token);
      } else if (!token.endsWith("_")) {
        documented.add(token);
      }
    }
  }

  private static boolean isDocumented(
      String token, Set<String> documented, Set<String> documentedPrefixes) {
    return documented.contains(token) || documentedPrefixes.stream().anyMatch(token::startsWith);
  }

  /**
   * A {@link #TOKEN} match ending in '_' is only a genuine family-prefix declaration (e.g. {@code
   * DEDIREN_ELK_*}) when the source text immediately continues with a literal {@code *} that
   * itself closes the identifier. Prose shorthand that embeds a wildcard inside one literal code
   * (e.g. {@code DEDIREN_DRAWIO_*_LIMIT_EXCEEDED}, where {@code *} stands in for one of several
   * suffixes) is not a family declaration and must not blanket-authorize the whole namespace.
   */
  private static boolean isFamilyPrefix(String doc, Matcher matcher) {
    if (!matcher.group().endsWith("_")) {
      return false;
    }
    int end = matcher.end();
    if (end >= doc.length() || doc.charAt(end) != '*') {
      return false;
    }
    int afterStar = end + 1;
    return afterStar >= doc.length() || !isWordChar(doc.charAt(afterStar));
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private static Set<String> sourceTokens(Path repoRoot) throws IOException {
    return sourceTokens(repoRoot, /* mainOnly= */ false);
  }

  private static Set<String> sourceTokens(Path repoRoot, boolean mainOnly) throws IOException {
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
            String path = file.toString();
            // mainOnly restricts the walk to shipped (src/main) code: see the comment at this
            // method's reverse-direction caller (sourceTokensAreDocumentedIndividuallyOrByFamily)
            // for why that universe must stay production-only rather than all sources.
            if (path.endsWith(".java") && (!mainOnly || path.contains("/src/main/"))) {
              try {
                Matcher matcher = TOKEN.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                  String token = matcher.group();
                  // A real DiagnosticCode/env-var literal never ends in '_': a match that does is
                  // always truncated by a following non-letter character, most often a Javadoc
                  // family reference such as `DEDIREN_DRAWIO_*` (mirroring the doc-side wildcard
                  // shorthand). It is not itself a code that needs documenting, so skip it here
                  // rather than let it falsely demand its own '## Repair Rules' entry.
                  if (!token.endsWith("_")) {
                    tokens.add(token);
                  }
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
