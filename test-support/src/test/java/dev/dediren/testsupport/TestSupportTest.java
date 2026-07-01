package dev.dediren.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TestSupportTest {
  @Test
  void resolvesRepositoryRootContainingSchemaSentinel() {
    Path root = TestSupport.workspaceRoot();

    assertThat(root.resolve("schemas/model.schema.json")).exists();
    assertThat(Files.isDirectory(root)).isTrue();
  }

  @Test
  void returnsAbsoluteNormalizedPath() {
    Path root = TestSupport.workspaceRoot();

    assertThat(root).isAbsolute();
    assertThat(root).isEqualTo(root.normalize());
  }
}
