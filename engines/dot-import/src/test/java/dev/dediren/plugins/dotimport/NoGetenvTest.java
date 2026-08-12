package dev.dediren.plugins.dotimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guard: this module is only a lexer, parser, and AST — it has no {@code Main} and no
 * process-environment seam yet (a later step owns wiring this engine to the ambient environment),
 * so {@code System.getenv} has no legitimate call site here at all. Mirrors {@code
 * archimateoef.NoGetenvOutsideMainTest}'s intent for a module that has not yet grown a {@code
 * Main.main(String[])} to confine the call to.
 */
class NoGetenvTest {

  @Test
  void systemGetenvDoesNotAppearAnywhereInMainSource() throws IOException {
    Path mainSrc = Path.of(System.getProperty("user.dir")).resolve("src/main/java");
    List<Path> offenders = new ArrayList<>();

    try (Stream<Path> files = Files.walk(mainSrc)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        if (Files.readString(file).contains("System.getenv")) {
          offenders.add(file);
        }
      }
    }

    assertThat(offenders)
        .describedAs(
            "this module has no Main yet, so System.getenv has no legitimate call site here")
        .isEmpty();
  }
}
