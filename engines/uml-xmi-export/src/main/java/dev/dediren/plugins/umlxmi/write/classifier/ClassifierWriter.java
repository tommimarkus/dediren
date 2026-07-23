package dev.dediren.plugins.umlxmi.write.classifier;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.*;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.plugins.umlxmi.build.IdentifierMap;
import dev.dediren.plugins.umlxmi.build.TypeResolver;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class ClassifierWriter {

  private ClassifierWriter() {}

  public static void writeClassifier(
      StringBuilder xml,
      IdentifierMap ids,
      TypeResolver types,
      String umlType,
      SourceNode node,
      String elementId,
      List<String[]> generalizations) {
    xml.append("<packagedElement xmi:type=\"")
        .append(umlType)
        .append("\" xmi:id=\"")
        .append(attr(elementId))
        .append("\" name=\"")
        .append(attr(node.label()))
        .append("\">");
    for (JsonNode attribute : umlArray(node, "attributes")) {
      writeOwnedAttribute(xml, ids, types, node, attribute);
    }
    for (JsonNode operation : umlArray(node, "operations")) {
      writeOwnedOperation(xml, ids, types, node, operation);
    }
    // UML 2.5.1 §9.2: a Generalization is owned by the specific Classifier
    // (Classifier::generalization
    // subsets ownedElement) — it nests here, not as a standalone packagedElement. Each entry is
    // {generalization xmi:id, general classifier xmi:id}.
    for (String[] generalization : generalizations) {
      xml.append("<generalization xmi:type=\"uml:Generalization\" xmi:id=\"")
          .append(attr(generalization[0]))
          .append("\" general=\"")
          .append(attr(generalization[1]))
          .append("\"/>");
    }
    xml.append("</packagedElement>");
  }

  public static void writeOwnedAttribute(
      StringBuilder xml,
      IdentifierMap ids,
      TypeResolver types,
      SourceNode node,
      JsonNode attribute) {
    String name = textField(attribute, "name", "attribute");
    String id = ids.xmiId(node.id() + "-" + name);
    String typeId = types.resolve(textField(attribute, "type", "String"));
    String visibility = textField(attribute, "visibility", "public");
    String[] bounds = multiplicityBounds(textField(attribute, "multiplicity", "1"));
    xml.append("<ownedAttribute xmi:id=\"")
        .append(attr(id))
        .append("\" name=\"")
        .append(attr(name))
        .append("\" type=\"")
        .append(attr(typeId))
        .append("\" visibility=\"")
        .append(attr(visibility))
        .append("\">");
    writeMultiplicityValues(xml, id, bounds);
    xml.append("</ownedAttribute>");
  }

  /**
   * Emits an {@code ownedOperation} carrying its full UML signature: one {@code ownedParameter} per
   * source parameter (default {@code direction="in"}, type resolved to an in-document id) and,
   * unless the return type is absent or {@code void}, one {@code ownedParameter
   * direction="return"}. UML 2.5.1 §9.4: an Operation owns its Parameters via {@code
   * ownedParameter}, and the return is a Parameter with {@code
   * direction=ParameterDirectionKind::return}.
   */
  public static void writeOwnedOperation(
      StringBuilder xml,
      IdentifierMap ids,
      TypeResolver types,
      SourceNode node,
      JsonNode operation) {
    String name = textField(operation, "name", "operation");
    String operationKey = node.id() + "-" + name;
    String id = ids.xmiId(operationKey);
    String visibility = textField(operation, "visibility", "public");
    JsonNode parameters = operation.get("parameters");
    boolean hasParameters = parameters != null && parameters.isArray() && !parameters.isEmpty();
    String returnType = textField(operation, "return_type", null);
    boolean hasReturn = returnType != null && !returnType.isBlank() && !returnType.equals("void");
    xml.append("<ownedOperation xmi:id=\"")
        .append(attr(id))
        .append("\" name=\"")
        .append(attr(name))
        .append("\" visibility=\"")
        .append(attr(visibility))
        .append("\"");
    if (!hasParameters && !hasReturn) {
      xml.append("/>");
      return;
    }
    xml.append(">");
    if (hasParameters) {
      for (JsonNode parameter : parameters) {
        String parameterName = textField(parameter, "name", "param");
        String parameterId = ids.xmiId(operationKey + "-param-" + parameterName);
        String typeId = types.resolve(textField(parameter, "type", "String"));
        xml.append("<ownedParameter xmi:id=\"")
            .append(attr(parameterId))
            .append("\" name=\"")
            .append(attr(parameterName))
            .append("\" direction=\"in\" type=\"")
            .append(attr(typeId))
            .append("\"/>");
      }
    }
    if (hasReturn) {
      String returnId = ids.xmiId(operationKey + "-return");
      xml.append("<ownedParameter xmi:id=\"")
          .append(attr(returnId))
          .append("\" direction=\"return\" type=\"")
          .append(attr(types.resolve(returnType)))
          .append("\"/>");
    }
    xml.append("</ownedOperation>");
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
