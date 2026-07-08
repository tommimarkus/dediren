package dev.dediren.plugins.umlxmi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guard: the whole engine (post-parse export orchestration and schema env-path resolution) reads
 * only the env map threaded through its typed seam. The only {@code System.getenv} in the module's
 * main source tree is the one {@code Main.main(String[])} uses to seed that map from the process
 * environment; no engine, schema, or helper class may reach for the ambient environment directly.
 * This keeps the future in-memory build path able to supply the product root and env explicitly.
 */
class NoGetenvOutsideMainTest {

  @Test
  void systemGetenvAppearsOnlyInMainMethod() throws IOException {
    Path mainSrc = Path.of(System.getProperty("user.dir")).resolve("src/main/java");
    List<Path> offenders = new ArrayList<>();
    List<Path> mainFilesWithStrayGetenv = new ArrayList<>();

    try (Stream<Path> files = Files.walk(mainSrc)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String content = Files.readString(file);
        if (!content.contains("System.getenv")) {
          continue;
        }
        if (!file.getFileName().toString().equals("Main.java")) {
          offenders.add(file);
          continue;
        }
        if (getenvAppearsOutsideMainMethod(content)) {
          mainFilesWithStrayGetenv.add(file);
        }
      }
    }

    assertThat(offenders)
        .describedAs("System.getenv is only allowed inside Main.main(String[])")
        .isEmpty();
    assertThat(mainFilesWithStrayGetenv)
        .describedAs("System.getenv must stay inside Main.main(String[])")
        .isEmpty();
  }

  private static boolean getenvAppearsOutsideMainMethod(String content) {
    int signature = content.indexOf("public static void main(String[]");
    if (signature < 0) {
      return true;
    }
    int bodyStart = content.indexOf('{', signature);
    int bodyEnd = matchingBrace(content, bodyStart);
    for (int index = content.indexOf("System.getenv");
        index >= 0;
        index = content.indexOf("System.getenv", index + 1)) {
      if (index < bodyStart || index > bodyEnd) {
        return true;
      }
    }
    return false;
  }

  private static int matchingBrace(String content, int openBrace) {
    int depth = 0;
    for (int index = openBrace; index < content.length(); index++) {
      char character = content.charAt(index);
      if (character == '{') {
        depth++;
      } else if (character == '}') {
        depth--;
        if (depth == 0) {
          return index;
        }
      }
    }
    return content.length();
  }
}
