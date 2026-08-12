package dev.dediren.plugins.mermaid;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ImportEngine;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Native importer for Dediren's Mermaid 11.16.1 flowchart subset. */
public final class MermaidImportEngine implements ImportEngine {
  @Override
  public String id() {
    return "mermaid";
  }

  @Override
  public EngineResult<SourceDocument> importSource(String source) throws EngineException {
    ParsedDiagram parsed = new MermaidParser().parse(source);
    List<Diagnostic> diagnostics = warning(parsed.ignoredHints());
    return new EngineResult<>(new MermaidMapper().map(parsed), diagnostics);
  }

  private static List<Diagnostic> warning(Map<String, Integer> hints) {
    if (hints.isEmpty()) {
      return List.of();
    }
    String detail =
        hints.entrySet().stream()
            .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
            .collect(Collectors.joining(", "));
    return List.of(
        new Diagnostic(
            DiagnosticCode.MERMAID_HINT_IGNORED.code(),
            DiagnosticSeverity.WARNING,
            "ignored Mermaid presentation or layout hints: " + detail,
            "$"));
  }
}
