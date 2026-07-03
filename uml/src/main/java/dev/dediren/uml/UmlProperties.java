package dev.dediren.uml;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Generic JSON property-reader helpers shared by the UML validation surface. Behavior is
 * intentionally identical to the inlined helpers that previously lived in {@link Uml}; these only
 * operate on Jackson nodes and node-type maps.
 */
final class UmlProperties {

  private UmlProperties() {}

  static JsonNode requiredProperty(
      JsonNode umlProperties, String field, String requiredValue, String umlPath)
      throws UmlValidationException {
    if (umlProperties == null || !umlProperties.isObject()) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, requiredValue, umlPath + "." + field);
    }
    JsonNode value = umlProperties.get(field);
    if (value == null) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, requiredValue, umlPath + "." + field);
    }
    return value;
  }

  static BigInteger requiredPositiveIntegerProperty(
      JsonNode umlProperties, String field, String requiredValue, String path)
      throws UmlValidationException {
    if (umlProperties == null || !umlProperties.isObject()) {
      throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, requiredValue, path);
    }
    JsonNode value = umlProperties.get(field);
    if (value == null) {
      throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, requiredValue, path);
    }
    if (!value.isIntegralNumber() || value.bigIntegerValue().signum() < 1) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, propertyValue(value, requiredValue), path);
    }
    return value.bigIntegerValue();
  }

  static String requiredTextProperty(
      JsonNode umlProperties, String field, String requiredValue, String umlPath)
      throws UmlValidationException {
    JsonNode value = requiredProperty(umlProperties, field, requiredValue, umlPath);
    if (!value.isTextual()) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, value.toString(), umlPath + "." + field);
    }
    return value.asText();
  }

  static JsonNode requiredNonEmptyArrayProperty(
      JsonNode umlProperties, String field, String requiredValue, String umlPath)
      throws UmlValidationException {
    JsonNode value = requiredProperty(umlProperties, field, requiredValue, umlPath);
    if (!value.isArray() || value.isEmpty()) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, value.toString(), umlPath + "." + field);
    }
    return value;
  }

  static JsonNode optionalProperty(JsonNode umlProperties, String field) {
    if (umlProperties == null || !umlProperties.isObject()) {
      return null;
    }
    return umlProperties.get(field);
  }

  static JsonNode optionalArrayProperty(JsonNode umlProperties, String field, String umlPath)
      throws UmlValidationException {
    JsonNode value = optionalProperty(umlProperties, field);
    if (value == null) {
      return null;
    }
    if (!value.isArray()) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, value.toString(), umlPath + "." + field);
    }
    return value;
  }

  static String readTextProperty(JsonNode umlProperties, String field) {
    JsonNode value = optionalProperty(umlProperties, field);
    if (value == null || !value.isTextual()) {
      return null;
    }
    return value.asText();
  }

  static String requiredTextArrayEntry(JsonNode values, int index, String path)
      throws UmlValidationException {
    JsonNode value = values.get(index);
    if (!value.isTextual()) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, value.toString(), path + "[" + index + "]");
    }
    return value.asText();
  }

  static boolean containsTextValue(JsonNode values, String expected) {
    for (JsonNode value : values) {
      if (value.isTextual() && expected.equals(value.asText())) {
        return true;
      }
    }
    return false;
  }

  static Set<String> textValueSet(JsonNode values) {
    var set = new HashSet<String>();
    for (JsonNode value : values) {
      if (value.isTextual()) {
        set.add(value.asText());
      }
    }
    return set;
  }

  static void requireNodeType(
      String id, String expectedType, Map<String, String> nodeTypes, String path)
      throws UmlValidationException {
    if (!expectedType.equals(nodeTypes.get(id))) {
      throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, id, path);
    }
  }

  static String propertyValue(JsonNode value, String fallback) {
    if (value == null) {
      return fallback;
    }
    return value.isTextual() ? value.asText() : value.toString();
  }
}
