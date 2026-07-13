package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import java.util.List;

final class EnvelopeWriter {
  private EnvelopeWriter() {}

  static String ok(LayoutResult result) {
    return JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(result));
  }

  static String error(String code, String message) {
    Diagnostic diagnostic = new Diagnostic(code, DiagnosticSeverity.ERROR, message, null);
    return JsonSupport.objectMapper()
        .writeValueAsString(CommandEnvelope.error(List.of(diagnostic)));
  }

  static String error(List<Diagnostic> diagnostics) {
    return JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(diagnostics));
  }
}
