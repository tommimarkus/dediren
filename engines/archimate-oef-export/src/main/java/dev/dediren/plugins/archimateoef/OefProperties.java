package dev.dediren.plugins.archimateoef;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Decides the exchange data type of each property key and renders its values, disclosing whatever
 * the format cannot carry.
 *
 * <p>The exchange format models properties by reference: a key is declared once as a model-level
 * {@code <propertyDefinition>} carrying a {@code DataType}, and every value points back at that
 * definition. {@code DataType} offers six types, of which JSON can distinguish three — the rest
 * ({@code currency}, {@code date}, {@code time}) have no JSON representation to detect, and
 * guessing one from the shape of a string would be worse than declaring text.
 *
 * <p>Because definitions are keyed by name alone, a key carrying a boolean on one concept and text
 * on another has no single narrow type; {@code string} is the widest of the six and the only one
 * that represents both.
 *
 * <p>A {@code <value>} is text, so an object or array value can only be carried as its JSON
 * rendering — well-formed, valid, and unrecoverable on round-trip. That loss is inherent to the
 * format; leaving it undeclared is not, so each one is reported.
 */
final class OefProperties {

  private static final String STRING = "string";
  private static final String BOOLEAN = "boolean";
  private static final String NUMBER = "number";

  private final Map<String, String> dataTypes = new HashMap<>();
  private final List<Diagnostic> diagnostics = new ArrayList<>();

  OefProperties(SourceDocument source) {
    for (SourceNode node : source.nodes()) {
      node.properties().forEach(this::observe);
    }
    for (SourceRelationship relationship : source.relationships()) {
      relationship.properties().forEach(this::observe);
    }
  }

  /** The {@code DataType} to declare for {@code key}. */
  String dataType(String key) {
    return dataTypes.getOrDefault(key, STRING);
  }

  /**
   * The {@code <value>} text for one property, recording a warning when a non-scalar value had to
   * be flattened to its JSON rendering to fit.
   */
  String valueText(String key, JsonNode value, String path) {
    if (value == null || value.isNull()) {
      return "";
    }
    if (value.isValueNode()) {
      return value.asText();
    }
    diagnostics.add(
        new Diagnostic(
            DiagnosticCode.OEF_PROPERTY_FLATTENED.code(),
            DiagnosticSeverity.WARNING,
            "property '"
                + key
                + "' holds a "
                + (value.isArray() ? "list" : "structured")
                + " value; the ArchiMate exchange format carries property values as text, so it was"
                + " flattened to its JSON rendering and cannot be recovered as structure on import",
            path + "." + key));
    return value.toString();
  }

  /** Every flattening reported so far, in emission order. */
  List<Diagnostic> diagnostics() {
    return List.copyOf(diagnostics);
  }

  private void observe(String key, JsonNode value) {
    String observed = typeOf(value);
    String known = dataTypes.get(key);
    dataTypes.put(key, known == null || known.equals(observed) ? observed : STRING);
  }

  private static String typeOf(JsonNode value) {
    if (value == null || value.isNull()) {
      return STRING;
    }
    if (value.isBoolean()) {
      return BOOLEAN;
    }
    if (value.isNumber()) {
      return NUMBER;
    }
    return STRING;
  }
}
