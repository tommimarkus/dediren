package dev.dediren.plugins.umlxmi.build;

import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutGroups;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.XmlText;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class XmiHelpers {

  private XmiHelpers() {}

  public static final String XMI_NS = "http://www.omg.org/spec/XMI/20131001";
  public static final String UML_NS = "http://www.omg.org/spec/UML/20161101";
  public static final String XMI_VERSION = "2.5.1";
  public static final String UML_VERSION = "2.5.1";

  public static GenericGraphPluginData genericGraphPluginData(ExportRequest request) {
    JsonNode value = request.source().plugins().get("generic-graph");
    if (value == null) {
      throw new IllegalArgumentException("source is missing plugins.generic-graph");
    }
    return JsonSupport.objectMapper().convertValue(value, GenericGraphPluginData.class);
  }

  public static void writeEmptyPackagedElement(
      StringBuilder xml, String umlType, SourceNode node, String elementId) {
    xml.append("<packagedElement xmi:type=\"")
        .append(umlType)
        .append("\" xmi:id=\"")
        .append(attr(elementId))
        .append("\" name=\"")
        .append(attr(node.label()))
        .append("\"/>");
  }

  public static void writeReferencedClassifierIds(
      StringBuilder xml, String attribute, List<String> sourceIds, Map<String, String> nodeIds) {
    List<String> referencedIds =
        sourceIds.stream().map(nodeIds::get).filter(value -> value != null).toList();
    if (!referencedIds.isEmpty()) {
      xml.append(" ")
          .append(attribute)
          .append("=\"")
          .append(attr(String.join(" ", referencedIds)))
          .append("\"");
    }
  }

  public static List<JsonNode> umlArray(SourceNode node, String field) {
    JsonNode value = node.properties().get("uml");
    value = value == null ? null : value.get(field);
    if (value == null || !value.isArray()) {
      return List.of();
    }
    var values = new java.util.ArrayList<JsonNode>();
    value.forEach(values::add);
    return values;
  }

  public static String umlString(SourceNode node, String field) {
    JsonNode value = node.properties().get("uml");
    value = value == null ? null : value.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  public static List<String> umlTextArray(SourceNode node, String field) {
    JsonNode value = node.properties().get("uml");
    value = value == null ? null : value.get(field);
    if (value == null || !value.isArray()) {
      return List.of();
    }
    var values = new java.util.ArrayList<String>();
    for (JsonNode item : value) {
      if (item.isTextual()) {
        values.add(item.asText());
      }
    }
    return values;
  }

  public static int umlPositiveInt(SourceNode node, String field, int fallback) {
    JsonNode value = node.properties().get("uml");
    value = value == null ? null : value.get(field);
    if (value == null || !value.isIntegralNumber() || value.intValue() < 1) {
      return fallback;
    }
    return value.intValue();
  }

  public static String umlString(SourceRelationship relationship, String field) {
    JsonNode value = relationship.properties().get("uml");
    value = value == null ? null : value.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  public static BigInteger umlSequence(SourceRelationship relationship) {
    JsonNode value = relationship.properties().get("uml");
    value = value == null ? null : value.get("sequence");
    return value != null && value.isIntegralNumber()
        ? value.bigIntegerValue()
        : BigInteger.valueOf(Long.MAX_VALUE);
  }

  public static String textField(JsonNode value, String field, String fallback) {
    JsonNode fieldValue = value.get(field);
    return fieldValue != null && fieldValue.isTextual() ? fieldValue.asText() : fallback;
  }

  public static String[] multiplicityBounds(String value) {
    if (value.contains("..")) {
      return value.split("\\.\\.", 2);
    }
    if (value.equals("*")) {
      return new String[] {"0", "*"};
    }
    return new String[] {value, value};
  }

  /**
   * Appends a multiplicity as UML 2.5.1 canonical owned {@code ValueSpecification} children: {@code
   * lowerValue} as a {@code uml:LiteralInteger} and {@code upperValue} as a {@code
   * uml:LiteralUnlimitedNatural} (with {@code *} denoting unlimited). Owner elements that carry a
   * multiplicity ({@code ownedAttribute}, association {@code ownedEnd}) must be written as open
   * elements so these children can nest inside them, rather than serializing bounds as XML
   * attributes, which UML importers ignore or reject (issue #33 defect 2).
   */
  public static void writeMultiplicityValues(StringBuilder xml, String ownerId, String[] bounds) {
    xml.append("<lowerValue xmi:type=\"uml:LiteralInteger\" xmi:id=\"")
        .append(attr(ownerId + "-lower"))
        .append("\" value=\"")
        .append(attr(bounds[0]))
        .append("\"/>");
    xml.append("<upperValue xmi:type=\"uml:LiteralUnlimitedNatural\" xmi:id=\"")
        .append(attr(ownerId + "-upper"))
        .append("\" value=\"")
        .append(attr(bounds[1]))
        .append("\"/>");
  }

  public static boolean isXmlId(String value) {
    if (value.isEmpty()) {
      return false;
    }
    char first = value.charAt(0);
    if (!(first == '_' || first < 128 && Character.isAlphabetic(first))) {
      return false;
    }
    for (char character : value.toCharArray()) {
      if (!(character == '_'
          || character == '-'
          || character == '.'
          || character < 128 && Character.isLetterOrDigit(character))) {
        return false;
      }
    }
    return true;
  }

  public static String semanticGroupSourceId(LaidOutGroup group) {
    return LaidOutGroups.semanticSourceId(group);
  }

  public static String attr(String value) {
    return XmlText.scrub(value == null ? "" : value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  public static String text(String value) {
    return XmlText.scrub(value == null ? "" : value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  public static String slug(String value) {
    StringBuilder result = new StringBuilder();
    boolean previousDash = false;
    for (char character : value.toCharArray()) {
      if (Character.isLetterOrDigit(character) && character < 128) {
        result.append(Character.toLowerCase(character));
        previousDash = false;
      } else if (!previousDash) {
        result.append("-");
        previousDash = true;
      }
    }
    String trimmed = result.toString().replaceAll("^-+|-+$", "");
    return trimmed.isEmpty() ? "item" : trimmed;
  }
}
