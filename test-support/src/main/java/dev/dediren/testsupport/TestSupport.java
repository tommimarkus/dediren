package dev.dediren.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

public final class TestSupport {
  private static final String REPOSITORY_ROOT_SENTINEL = "schemas/model.schema.json";

  private TestSupport() {}

  /**
   * Resolves the repository root by walking up from the current working directory (the module
   * directory under Maven) until the schema sentinel is found.
   */
  public static Path workspaceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve(REPOSITORY_ROOT_SENTINEL))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "Could not locate repository root containing " + REPOSITORY_ROOT_SENTINEL);
  }
}
