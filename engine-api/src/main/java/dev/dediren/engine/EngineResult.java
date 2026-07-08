package dev.dediren.engine;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.Diagnostic;
import java.util.List;

public record EngineResult<T>(T value, List<Diagnostic> diagnostics) {
  public EngineResult {
    diagnostics = listOrEmpty(diagnostics);
  }
}
