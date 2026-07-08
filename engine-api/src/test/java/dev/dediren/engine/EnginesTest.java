package dev.dediren.engine;

import static org.assertj.core.api.Assertions.assertThat;

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
    public EngineResult<LayoutResult> layout(LayoutRequest request) {
      return new EngineResult<>(
          new LayoutResult("layout-result.schema.v1", request.viewId(), null, null, null, null),
          List.of());
    }
  }
}
