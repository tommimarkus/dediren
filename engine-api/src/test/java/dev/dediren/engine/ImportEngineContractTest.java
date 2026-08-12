package dev.dediren.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.Diagnostic;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ImportEngineContractTest {
  @Test
  void importEnginesRegisterAndAreFoundByIdWithoutChangingOtherCapabilities() throws Exception {
    ImportEngine importer =
        new ImportEngine() {
          @Override
          public String id() {
            return "mermaid";
          }

          @Override
          public EngineResult<JsonNode> importSource(String source) {
            return new EngineResult<>(null, List.<Diagnostic>of());
          }
        };

    Engines engines = Engines.of(List.of(), List.of(), List.of(), List.of(), List.of(importer));

    assertThat(engines.importEngine("mermaid")).contains(importer);
    assertThat(engines.importEngine("unknown")).isEmpty();
    assertThat(engines.semantics()).isEmpty();
    assertThat(engines.layouts()).isEmpty();
  }
}
