package dev.dediren.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnginesTest {

  @Test
  void fakeLayoutEngineRegistersAndIsFoundById() {
    var fake = new FakeLayoutEngine();
    var engines = Engines.of(List.of(), List.of(fake), List.of(), List.of());

    assertThat(engines.layoutEngine("fake-layout")).contains(fake);
  }

  @Test
  void unknownIdLookupIsEmpty() {
    var engines = Engines.of(List.of(), List.of(new FakeLayoutEngine()), List.of(), List.of());

    assertThat(engines.layoutEngine("does-not-exist")).isEmpty();
  }

  @Test
  void duplicateIdWithinCapabilityIsRejected() {
    // Engines.of indexes each capability by id() and rejects a duplicate id within the same
    // capability (documented in the Engines Javadoc), so two engines sharing "fake-layout" fail.
    assertThatThrownBy(
            () ->
                Engines.of(
                    List.of(),
                    List.of(new FakeLayoutEngine(), new FakeLayoutEngine()),
                    List.of(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fake-layout");
  }

  @Test
  void engineExceptionRoundTripsDiagnosticsAndExitCode() {
    List<Diagnostic> diagnostics =
        List.of(new Diagnostic("DEDIREN_TEST", DiagnosticSeverity.ERROR, "boom", null));

    var exception = new EngineException(diagnostics, 3);

    assertThat(exception.diagnostics()).isEqualTo(diagnostics);
    assertThat(exception.exitCode()).isEqualTo(3);
  }

  private static final class FakeLayoutEngine implements LayoutEngine {
    @Override
    public String id() {
      return "fake-layout";
    }

    @Override
    public LayoutRequest parseRequest(byte[] input) {
      return new LayoutRequest(
          ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION, "main", null, null, null, null, null);
    }

    @Override
    public EngineResult<LayoutResult> layout(LayoutRequest request) {
      return new EngineResult<>(
          new LayoutResult(
              ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
              request.viewId(),
              null,
              null,
              null,
              null),
          List.of());
    }
  }
}
