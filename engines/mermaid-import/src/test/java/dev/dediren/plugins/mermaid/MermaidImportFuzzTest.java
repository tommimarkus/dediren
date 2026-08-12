package dev.dediren.plugins.mermaid;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.engine.EngineException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MermaidImportFuzzTest {
  private final MermaidImportEngine engine = new MermaidImportEngine();

  @Test
  void arbitraryBoundedTextEitherImportsAtomicallyOrReturnsOnePublishedInputFailure()
      throws Exception {
    Random random = new Random(0xD3D1_7EEL);
    for (int sample = 0; sample < 2_000; sample++) {
      byte[] bytes = new byte[random.nextInt(4096)];
      random.nextBytes(bytes);
      assertAtomic(bytes);
    }
  }

  private void assertAtomic(byte[] bytes) throws Exception {
    String source = new String(bytes, StandardCharsets.UTF_8);
    try {
      var result = engine.importSource(source);
      assertThat(result.value()).isNotNull();
      assertThat(result.value().modelSchemaVersion()).isEqualTo("model.schema.v1");
    } catch (EngineException rejected) {
      assertThat(rejected.exitCode()).isEqualTo(2);
      assertThat(rejected.diagnostics()).hasSize(1);
      assertThat(rejected.diagnostics().get(0).code()).startsWith("DEDIREN_MERMAID_");
    }
  }
}
