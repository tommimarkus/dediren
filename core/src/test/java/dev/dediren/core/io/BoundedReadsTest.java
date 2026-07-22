package dev.dediren.core.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedReadsTest {
  @TempDir Path temp;

  @Test
  void aFileUnderTheCeilingIsReadWhole() throws Exception {
    Path file = Files.writeString(temp.resolve("small.json"), "{}");

    assertThat(BoundedReads.readString(file, 10)).isEqualTo("{}");
  }

  @Test
  void aFileExactlyAtTheCeilingIsReadWhole() throws Exception {
    Path file = Files.writeString(temp.resolve("exact.json"), "12345");

    assertThat(BoundedReads.readString(file, 5)).isEqualTo("12345");
  }

  @Test
  void aFileOverTheCeilingIsRejectedWithBothByteCounts() throws Exception {
    Path file = Files.writeString(temp.resolve("big.json"), "123456");

    // Both numbers in the message: an agent repairing from the envelope needs the actual size and
    // the ceiling, and call sites on the MCP trust boundary rely on the message carrying no path.
    assertThatThrownBy(() -> BoundedReads.readString(file, 5))
        .isInstanceOf(BoundedReads.FileTooLargeException.class)
        .hasMessageContaining("6 bytes")
        .hasMessageContaining("ceiling is 5 bytes")
        .hasMessageNotContaining(temp.toString());
  }
}
