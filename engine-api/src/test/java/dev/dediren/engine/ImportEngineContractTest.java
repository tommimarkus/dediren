package dev.dediren.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.source.SourceDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
          public EngineResult<SourceDocument> importSource(String source) {
            return new EngineResult<>(
                new SourceDocument(
                    "model.schema.v1", List.of(), List.of(), List.of(), List.of(), Map.of()),
                List.of());
          }
        };

    Engines engines = Engines.of(List.of(), List.of(), List.of(), List.of(), List.of(importer));

    assertThat(engines.importEngine("mermaid")).contains(importer);
    assertThat(engines.importEngine("unknown")).isEmpty();
    assertThat(engines.semanticsEngine("unknown")).isEmpty();
    assertThat(engines.layoutEngine("unknown")).isEmpty();
    assertThat(engines.renderEngine("unknown")).isEmpty();
    assertThat(engines.exportEngine("unknown")).isEmpty();

    EngineResult<SourceDocument> imported = importer.importSource("flowchart TD\nA\n");
    assertThat(imported.value().modelSchemaVersion()).isEqualTo("model.schema.v1");
    assertThat(imported.diagnostics()).isEmpty();
  }

  @Test
  void existingFourCapabilityFactoryRemainsSourceCompatible() {
    Engines factory = Engines.of(List.of(), List.of(), List.of(), List.of());
    Engines constructor = new Engines(Map.of(), Map.of(), Map.of(), Map.of());

    assertThat(factory.importers()).isEmpty();
    assertThat(constructor.importers()).isEmpty();
  }

  @Test
  void importerFailuresCanUseThePublishedStructuralDiagnosticHelper() {
    EngineException failure =
        EngineException.structuralFailure("DEDIREN_TEST", "malformed", "line 2, column 3");

    assertThat(failure.exitCode()).isEqualTo(2);
    assertThat(failure.diagnostics())
        .singleElement()
        .satisfies(
            diagnostic -> {
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.ERROR);
              assertThat(diagnostic.path()).isEqualTo("line 2, column 3");
            });
  }
}
