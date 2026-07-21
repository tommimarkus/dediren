package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.DiagnosticCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every published {@code DEDIREN_*} diagnostic code must be a named constant, not a string literal.
 *
 * <p>The code string is the wire contract — it is what agents branch on — yet two thirds of the
 * vocabulary used to be free text scattered through the engines, with {@link DiagnosticCode} owning
 * only the {@code core}/{@code cli} family and its own javadoc calling the rest "the remaining
 * family to migrate". A typo'd or renamed literal compiled cleanly and shipped a wrong code, and
 * finding every emitter of a code was a grep exercise.
 *
 * <p>This test is what makes the migration stick: introduce a new raw {@code "DEDIREN_..."} code
 * anywhere in main source and it fails, forcing the code into the enum where the compiler can check
 * it. Without a guard the vocabulary would simply drift apart again.
 */
class DiagnosticCodeOwnershipTest {

  private static final Pattern DEDIREN_LITERAL = Pattern.compile("\"(DEDIREN_[A-Z0-9_]+)\"");

  /**
   * {@code DEDIREN_*} environment-variable names are a separate vocabulary from diagnostic codes
   * (DiagnosticCode's javadoc says so). They are configuration keys, not wire diagnostics.
   */
  private static final Set<String> ENVIRONMENT_VARIABLES =
      Set.of(
          "DEDIREN_OEF_SCHEMA_DIR",
          "DEDIREN_XMI_SCHEMA_PATH",
          "DEDIREN_SCHEMA_CACHE_DIR",
          "DEDIREN_XMI_SCHEMA_VALIDATOR",
          "DEDIREN_BUNDLE_ROOT",
          "DEDIREN_LOG_LEVEL");

  /**
   * {@code archimate} is deliberately standalone — §2 grants it no internal dependencies — so it
   * cannot reach {@code contracts} to use the enum. It owns its six codes locally instead, each
   * emitted from a single switch, which is the same "one named place per wire string" property.
   */
  private static final String STANDALONE_NOTATION_CORE = "archimate";

  @Test
  void noModuleEmitsARawDiagnosticCodeLiteral() throws IOException {
    Set<String> owned =
        Arrays.stream(DiagnosticCode.values())
            .map(DiagnosticCode::code)
            .collect(Collectors.toSet());

    List<String> offenders = new ArrayList<>();
    for (Path source : mainSources()) {
      if (source.endsWith(Path.of("contracts", "DiagnosticCode.java"))) {
        continue; // the owner declares them, by definition
      }
      String module = moduleOf(source);
      String text = Files.readString(source, StandardCharsets.UTF_8);
      Matcher matcher = DEDIREN_LITERAL.matcher(text);
      while (matcher.find()) {
        String literal = matcher.group(1);
        if (ENVIRONMENT_VARIABLES.contains(literal)) {
          continue;
        }
        if (STANDALONE_NOTATION_CORE.equals(module) && literal.startsWith("DEDIREN_ARCHIMATE_")) {
          continue;
        }
        offenders.add(
            "%s emits the raw literal \"%s\"%s"
                .formatted(
                    source, literal, owned.contains(literal) ? " (use DiagnosticCode)" : " (NEW)"));
      }
    }

    assertThat(offenders)
        .as(
            "A DEDIREN_* diagnostic code is a published wire contract. Declare it in"
                + " contracts.DiagnosticCode and emit DiagnosticCode.X.code(), so the compiler"
                + " checks it and every emitter is findable. (Environment-variable names are a"
                + " separate vocabulary; add them to ENVIRONMENT_VARIABLES.)")
        .isEmpty();
  }

  private static List<Path> mainSources() throws IOException {
    Path root = workspaceRoot();
    try (Stream<Path> paths = Files.walk(root)) {
      return paths
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> path.toString().contains("/src/main/"))
          .filter(path -> !path.toString().contains("/target/"))
          .filter(path -> !isUnderDotDirectory(root, path))
          .toList();
    }
  }

  /**
   * A nested git worktree ({@code .worktrees/<name>}, this repo's convention and gitignored) is a
   * full second checkout. Walking into one scans every module a second time under a path whose
   * first segment is {@code .worktrees}, so {@link #moduleOf} reports {@code .worktrees} instead of
   * the real module — and the standalone-{@code archimate} exemption below stops matching, turning
   * a developer's own worktree into a wall of false violations. Skip dotdirs, as
   * AgentUsageDocConsistencyTest's walk already does.
   */
  private static boolean isUnderDotDirectory(Path root, Path path) {
    for (Path segment : root.relativize(path)) {
      if (segment.toString().startsWith(".")) {
        return true;
      }
    }
    return false;
  }

  private static String moduleOf(Path source) {
    Path relative = workspaceRoot().relativize(source);
    String first = relative.getName(0).toString();
    return "engines".equals(first) ? relative.getName(1).toString() : first;
  }

  private static Path workspaceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("schemas/model.schema.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from user.dir");
  }
}
