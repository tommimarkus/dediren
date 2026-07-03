package dev.dediren.plugins.umlxmi.write.classifier;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.*;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.plugins.umlxmi.build.IdentifierMap;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class ClassifierWriter {

  private ClassifierWriter() {}

  public static void writeClassifier(
      StringBuilder xml, IdentifierMap ids, String umlType, SourceNode node, String elementId) {
    xml.append("<packagedElement xmi:type=\"")
        .append(umlType)
        .append("\" xmi:id=\"")
        .append(attr(elementId))
        .append("\" name=\"")
        .append(attr(node.label()))
        .append("\">");
    for (JsonNode attribute : umlArray(node, "attributes")) {
      writeOwnedAttribute(xml, ids, node, attribute);
    }
    for (JsonNode operation : umlArray(node, "operations")) {
      writeOwnedOperation(xml, ids, node, operation);
    }
    xml.append("</packagedElement>");
  }

  public static void writeOwnedAttribute(
      StringBuilder xml, IdentifierMap ids, SourceNode node, JsonNode attribute) {
    String name = textField(attribute, "name", "attribute");
    String id = ids.xmiId(node.id() + "-" + name);
    String type = textField(attribute, "type", "String");
    String visibility = textField(attribute, "visibility", "public");
    String[] bounds = multiplicityBounds(textField(attribute, "multiplicity", "1"));
    xml.append("<ownedAttribute xmi:id=\"")
        .append(attr(id))
        .append("\" name=\"")
        .append(attr(name))
        .append("\" type=\"")
        .append(attr(type))
        .append("\" visibility=\"")
        .append(attr(visibility))
        .append("\" lowerValue=\"")
        .append(attr(bounds[0]))
        .append("\" upperValue=\"")
        .append(attr(bounds[1]))
        .append("\"/>");
  }

  public static void writeOwnedOperation(
      StringBuilder xml, IdentifierMap ids, SourceNode node, JsonNode operation) {
    String name = textField(operation, "name", "operation");
    String id = ids.xmiId(node.id() + "-" + name);
    String visibility = textField(operation, "visibility", "public");
    xml.append("<ownedOperation xmi:id=\"")
        .append(attr(id))
        .append("\" name=\"")
        .append(attr(name))
        .append("\" visibility=\"")
        .append(attr(visibility))
        .append("\"/>");
  }

  public static void writeEnumeration(
      StringBuilder xml, IdentifierMap ids, SourceNode node, String elementId) {
    xml.append("<packagedElement xmi:type=\"uml:Enumeration\" xmi:id=\"")
        .append(attr(elementId))
        .append("\" name=\"")
        .append(attr(node.label()))
        .append("\">");
    for (JsonNode literal : umlArray(node, "literals")) {
      if (!literal.isTextual()) {
        continue;
      }
      String name = literal.asText();
      String id = ids.xmiId(node.id() + "-" + name);
      xml.append("<ownedLiteral xmi:id=\"")
          .append(attr(id))
          .append("\" name=\"")
          .append(attr(name))
          .append("\"/>");
    }
    xml.append("</packagedElement>");
  }
}
