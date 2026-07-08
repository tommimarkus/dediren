package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.LayoutEngine;
import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * First-party {@link LayoutEngine} backed by Eclipse ELK. Wraps {@link ElkLayoutEngine} plus the
 * published error mapping ({@code DEDIREN_ELK_LAYOUT_FAILED} / exit 3) and owns the typed
 * input-parse entry point ({@link #parseRequest}) that maps a stream parse failure to elk-layout's
 * published parse-failure envelope ({@code DEDIREN_ELK_INPUT_INVALID_JSON} / exit 3).
 */
public final class ElkEngine implements LayoutEngine {

  @Override
  public String id() {
    return "elk-layout";
  }

  /**
   * Converts request bytes to a typed {@link LayoutRequest}. Preserves {@code Main.run}'s published
   * parse behavior: a layout-preference validation failure maps to {@code
   * DEDIREN_ELK_LAYOUT_FAILED} (carrying the preference validation {@link
   * LayoutJson#readLayoutRequest} performs while reading), and any other parse failure maps to
   * {@code DEDIREN_ELK_INPUT_INVALID_JSON}.
   */
  public LayoutRequest parseRequest(byte[] input) throws EngineException {
    try {
      return LayoutJson.readLayoutRequest(new ByteArrayInputStream(input));
    } catch (LayoutJson.LayoutPreferenceValidationException error) {
      throw layoutFailed(error.getMessage());
    } catch (Exception error) {
      throw new EngineException(
          List.of(
              diagnostic(
                  "DEDIREN_ELK_INPUT_INVALID_JSON",
                  "layout request JSON is invalid: " + error.getMessage())),
          3);
    }
  }

  @Override
  public EngineResult<LayoutResult> layout(LayoutRequest request) throws EngineException {
    try {
      LayoutJson.validatePreferences(request);
      return new EngineResult<>(new ElkLayoutEngine().layout(request), List.of());
    } catch (Exception error) {
      throw layoutFailed(error.getMessage());
    }
  }

  private static EngineException layoutFailed(String message) {
    return new EngineException(
        List.of(diagnostic("DEDIREN_ELK_LAYOUT_FAILED", "ELK layout failed: " + message)), 3);
  }

  private static Diagnostic diagnostic(String code, String message) {
    return new Diagnostic(code, DiagnosticSeverity.ERROR, message, null);
  }
}
