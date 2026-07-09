package dev.dediren.plugins.render.node.uml;

import dev.dediren.archimate.Archimate;
import dev.dediren.archimate.ArchimateTypeValidationException;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.SvgBackgroundStyle;
import dev.dediren.contracts.render.SvgEdgeStyle;
import dev.dediren.contracts.render.SvgFontStyle;
import dev.dediren.contracts.render.SvgGroupStyle;
import dev.dediren.contracts.render.SvgInteractionStyle;
import dev.dediren.contracts.render.SvgNodeStyle;
import dev.dediren.contracts.render.SvgStylePolicy;
import dev.dediren.uml.Uml;
import dev.dediren.uml.UmlValidationException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class RenderInputValidator {
  private static final Set<String> UML_SEQUENCE_MESSAGE_SORTS =
      Set.of("synchCall", "asynchCall", "asynchSignal", "reply", "createMessage", "deleteMessage");
  private static final Set<String> UML_SEQUENCE_COMBINED_FRAGMENT_OPERATORS =
      Set.of("alt", "opt", "loop", "par");

  private RenderInputValidator() {}

  public static void validate(LayoutResult layout, RenderMetadata metadata, RenderPolicy policy)
      throws PolicyValidationException,
          RenderMetadataUsageException,
          ArchimateTypeValidationException,
          UmlValidationException {
    validateRenderPolicy(policy);
    validateGenericShapePolicy(policy);
    validateRenderMetadataUsage(policy, metadata);
    validateArchimatePolicyTypes(policy);
    validateArchimateRenderMetadata(layout, metadata);
    validateUmlPolicyTypes(policy);
    validateUmlRenderMetadata(layout, metadata);
  }

  private static void validateRenderMetadataUsage(RenderPolicy policy, RenderMetadata metadata)
      throws RenderMetadataUsageException {
    SvgStylePolicy style = policy.style();
    boolean usesTypeOverrides =
        style != null
            && (!style.nodeTypeOverrides().isEmpty()
                || !style.edgeTypeOverrides().isEmpty()
                || !style.groupTypeOverrides().isEmpty());
    if (!usesTypeOverrides) {
      return;
    }
    if (policy.semanticProfile() == null) {
      throw new RenderMetadataUsageException(
          "DEDIREN_RENDER_METADATA_PROFILE_REQUIRED",
          "semantic_profile",
          "type-aware SVG render policies must declare semantic_profile");
    }
    if (metadata == null) {
      throw new RenderMetadataUsageException(
          "DEDIREN_RENDER_METADATA_REQUIRED",
          "render_metadata",
          "type-aware SVG render policy requires render metadata");
    }
    if (!policy.semanticProfile().equals(metadata.semanticProfile())) {
      throw new RenderMetadataUsageException(
          "DEDIREN_RENDER_METADATA_PROFILE_MISMATCH",
          "render_metadata.semantic_profile",
          "render metadata profile "
              + metadata.semanticProfile()
              + " does not match policy profile "
              + policy.semanticProfile());
    }
  }

  private static void validateArchimatePolicyTypes(RenderPolicy policy)
      throws ArchimateTypeValidationException {
    if (!"archimate".equals(policy.semanticProfile()) || policy.style() == null) {
      return;
    }
    for (String type : policy.style().nodeTypeOverrides().keySet()) {
      Archimate.validateElementType(type, "policy.style.node_type_overrides." + type);
    }
    for (String type : policy.style().edgeTypeOverrides().keySet()) {
      Archimate.validateRelationshipType(type, "policy.style.edge_type_overrides." + type);
    }
    for (String type : policy.style().groupTypeOverrides().keySet()) {
      Archimate.validateElementType(type, "policy.style.group_type_overrides." + type);
    }
  }

  private static void validateArchimateRenderMetadata(LayoutResult layout, RenderMetadata metadata)
      throws ArchimateTypeValidationException {
    if (metadata == null || !"archimate".equals(metadata.semanticProfile())) {
      return;
    }
    for (Map.Entry<String, RenderMetadataSelector> entry : metadata.nodes().entrySet()) {
      Archimate.validateElementType(
          entry.getValue().type(), "render_metadata.nodes." + entry.getKey() + ".type");
    }
    for (Map.Entry<String, RenderMetadataSelector> entry : metadata.edges().entrySet()) {
      Archimate.validateRelationshipType(
          entry.getValue().type(), "render_metadata.edges." + entry.getKey() + ".type");
    }
    for (Map.Entry<String, RenderMetadataSelector> entry : metadata.groups().entrySet()) {
      Archimate.validateElementType(
          entry.getValue().type(), "render_metadata.groups." + entry.getKey() + ".type");
    }
    for (LaidOutEdge edge : layout.edges()) {
      RenderMetadataSelector edgeSelector = metadata.edges().get(edge.id());
      RenderMetadataSelector sourceSelector = metadata.nodes().get(edge.source());
      RenderMetadataSelector targetSelector = metadata.nodes().get(edge.target());
      if (edgeSelector != null && sourceSelector != null && targetSelector != null) {
        Archimate.validateRelationshipEndpointTypes(
            edgeSelector.type(),
            sourceSelector.type(),
            targetSelector.type(),
            "render_metadata.edges." + edge.id());
      }
    }
  }

  private static void validateUmlPolicyTypes(RenderPolicy policy) throws UmlValidationException {
    if (!"uml".equals(policy.semanticProfile()) || policy.style() == null) {
      return;
    }
    for (String type : policy.style().nodeTypeOverrides().keySet()) {
      Uml.validateElementType(type, "policy.style.node_type_overrides." + type);
    }
    for (String type : policy.style().edgeTypeOverrides().keySet()) {
      Uml.validateRelationshipType(type, "policy.style.edge_type_overrides." + type);
    }
    for (String type : policy.style().groupTypeOverrides().keySet()) {
      Uml.validateElementType(type, "policy.style.group_type_overrides." + type);
    }
  }

  private static void validateUmlRenderMetadata(LayoutResult layout, RenderMetadata metadata)
      throws UmlValidationException, RenderMetadataUsageException {
    if (metadata == null || !"uml".equals(metadata.semanticProfile())) {
      return;
    }
    for (Map.Entry<String, RenderMetadataSelector> entry : metadata.nodes().entrySet()) {
      RenderMetadataSelector selector = entry.getValue();
      Uml.validateElementType(selector.type(), "render_metadata.nodes." + entry.getKey() + ".type");
      if ("CombinedFragment".equals(selector.type())) {
        validateUmlCombinedFragmentRenderMetadata(
            selector.properties(), "render_metadata.nodes." + entry.getKey() + ".properties");
      } else if ("InteractionOperand".equals(selector.type())) {
        validateUmlInteractionOperandRenderMetadata(
            selector.properties(), "render_metadata.nodes." + entry.getKey() + ".properties");
      }
    }
    for (Map.Entry<String, RenderMetadataSelector> entry : metadata.edges().entrySet()) {
      RenderMetadataSelector selector = entry.getValue();
      Uml.validateRelationshipType(
          selector.type(), "render_metadata.edges." + entry.getKey() + ".type");
      if ("Message".equals(selector.type())) {
        validateUmlMessageRenderMetadata(
            selector.properties(), "render_metadata.edges." + entry.getKey() + ".properties");
      }
    }
    for (Map.Entry<String, RenderMetadataSelector> entry : metadata.groups().entrySet()) {
      Uml.validateElementType(
          entry.getValue().type(), "render_metadata.groups." + entry.getKey() + ".type");
    }
    validateUmlSequenceFragmentRenderMetadataReferences(layout, metadata);
    for (LaidOutEdge edge : layout.edges()) {
      RenderMetadataSelector edgeSelector = metadata.edges().get(edge.id());
      RenderMetadataSelector sourceSelector = metadata.nodes().get(edge.source());
      RenderMetadataSelector targetSelector = metadata.nodes().get(edge.target());
      if (edgeSelector != null && sourceSelector != null && targetSelector != null) {
        Uml.validateRelationshipEndpointTypes(
            edgeSelector.type(),
            sourceSelector.type(),
            targetSelector.type(),
            "render_metadata.edges." + edge.id());
      }
    }
  }

  private static void validateUmlSequenceFragmentRenderMetadataReferences(
      LayoutResult layout, RenderMetadata metadata) throws RenderMetadataUsageException {
    Set<String> layoutEdgeIds = new HashSet<>();
    for (LaidOutEdge edge : layout.edges()) {
      layoutEdgeIds.add(edge.id());
    }
    for (Map.Entry<String, RenderMetadataSelector> entry : metadata.nodes().entrySet()) {
      RenderMetadataSelector selector = entry.getValue();
      String path = "render_metadata.nodes." + entry.getKey() + ".properties";
      if ("CombinedFragment".equals(selector.type())) {
        validateCombinedFragmentOperandReferences(
            entry.getKey(), selector.properties(), metadata, path);
        validateCombinedFragmentCoveredReferences(selector.properties(), metadata, path);
      } else if ("InteractionOperand".equals(selector.type())) {
        validateInteractionOperandFragmentReferences(
            selector.properties(), metadata, layoutEdgeIds, path);
      }
    }
  }

  private static void validateCombinedFragmentOperandReferences(
      String combinedFragmentId, JsonNode properties, RenderMetadata metadata, String path)
      throws RenderMetadataUsageException {
    String operator = metadataProperty(properties, "operator").asText();
    JsonNode operands = metadataProperty(properties, "operands");
    if (!hasSupportedCombinedFragmentOperandCount(operator, operands.size())) {
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_COMBINED_FRAGMENT_METADATA_INVALID",
          path + ".operands",
          "UML CombinedFragment render metadata operand count does not match operator " + operator);
    }
    for (JsonNode operandId : operands) {
      RenderMetadataSelector operand = metadata.nodes().get(operandId.asText());
      JsonNode owner =
          operand == null ? null : metadataProperty(operand.properties(), "combined_fragment");
      if (operand == null
          || !"InteractionOperand".equals(operand.type())
          || owner == null
          || !owner.isTextual()
          || !combinedFragmentId.equals(owner.asText())) {
        throw new RenderMetadataUsageException(
            "DEDIREN_UML_COMBINED_FRAGMENT_METADATA_INVALID",
            path + ".operands",
            "UML CombinedFragment render metadata operands must reference owned InteractionOperand metadata");
      }
    }
  }

  private static boolean hasSupportedCombinedFragmentOperandCount(String operator, int count) {
    return switch (operator) {
      case "opt", "loop" -> count == 1;
      case "alt", "par" -> count >= 2;
      default -> false;
    };
  }

  private static void validateCombinedFragmentCoveredReferences(
      JsonNode properties, RenderMetadata metadata, String path)
      throws RenderMetadataUsageException {
    JsonNode covered = metadataProperty(properties, "covered");
    if (covered == null) {
      return;
    }
    for (JsonNode coveredId : covered) {
      RenderMetadataSelector lifeline = metadata.nodes().get(coveredId.asText());
      if (lifeline == null || !"Lifeline".equals(lifeline.type())) {
        throw new RenderMetadataUsageException(
            "DEDIREN_UML_COMBINED_FRAGMENT_METADATA_INVALID",
            path + ".covered",
            "UML CombinedFragment render metadata covered ids must reference Lifeline metadata");
      }
    }
  }

  private static void validateInteractionOperandFragmentReferences(
      JsonNode properties, RenderMetadata metadata, Set<String> layoutEdgeIds, String path)
      throws RenderMetadataUsageException {
    JsonNode combinedFragment = metadataProperty(properties, "combined_fragment");
    RenderMetadataSelector owner = metadata.nodes().get(combinedFragment.asText());
    if (owner == null || !"CombinedFragment".equals(owner.type())) {
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_INTERACTION_OPERAND_METADATA_INVALID",
          path + ".combined_fragment",
          "UML InteractionOperand render metadata combined_fragment must reference CombinedFragment metadata");
    }

    JsonNode fragments = metadataProperty(properties, "fragments");
    for (JsonNode fragmentId : fragments) {
      String id = fragmentId.asText();
      RenderMetadataSelector message = metadata.edges().get(id);
      if (message != null && "Message".equals(message.type()) && layoutEdgeIds.contains(id)) {
        continue;
      }
      RenderMetadataSelector nestedFragment = metadata.nodes().get(id);
      if (nestedFragment != null && "CombinedFragment".equals(nestedFragment.type())) {
        continue;
      }
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_INTERACTION_OPERAND_METADATA_INVALID",
          path + ".fragments",
          "UML InteractionOperand render metadata fragments must reference laid out Message edges "
              + "or CombinedFragment metadata");
    }
  }

  private static void validateUmlMessageRenderMetadata(JsonNode properties, String path)
      throws RenderMetadataUsageException {
    JsonNode sequence = metadataProperty(properties, "sequence");
    if (sequence == null
        || !sequence.isIntegralNumber()
        || sequence.bigIntegerValue().signum() < 1) {
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_MESSAGE_METADATA_INVALID",
          path + ".sequence",
          "UML Message render metadata sequence must be a positive integer");
    }

    JsonNode messageSort = metadataProperty(properties, "message_sort");
    if (messageSort != null
        && (!messageSort.isTextual()
            || !UML_SEQUENCE_MESSAGE_SORTS.contains(messageSort.asText()))) {
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_MESSAGE_METADATA_INVALID",
          path + ".message_sort",
          "UML Message render metadata message_sort, when present, must be one of "
              + UML_SEQUENCE_MESSAGE_SORTS);
    }
  }

  private static void validateUmlCombinedFragmentRenderMetadata(JsonNode properties, String path)
      throws RenderMetadataUsageException {
    JsonNode operator = metadataProperty(properties, "operator");
    if (operator == null
        || !operator.isTextual()
        || !UML_SEQUENCE_COMBINED_FRAGMENT_OPERATORS.contains(operator.asText())) {
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_COMBINED_FRAGMENT_METADATA_INVALID",
          path + ".operator",
          "UML CombinedFragment render metadata operator must be one of "
              + UML_SEQUENCE_COMBINED_FRAGMENT_OPERATORS);
    }

    JsonNode operands = metadataProperty(properties, "operands");
    if (!isNonEmptyTextArray(operands)) {
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_COMBINED_FRAGMENT_METADATA_INVALID",
          path + ".operands",
          "UML CombinedFragment render metadata operands must be a non-empty array of text ids");
    }

    JsonNode covered = metadataProperty(properties, "covered");
    if (covered != null && !isTextArray(covered)) {
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_COMBINED_FRAGMENT_METADATA_INVALID",
          path + ".covered",
          "UML CombinedFragment render metadata covered, when present, must be an array of text ids");
    }
  }

  private static void validateUmlInteractionOperandRenderMetadata(JsonNode properties, String path)
      throws RenderMetadataUsageException {
    JsonNode combinedFragment = metadataProperty(properties, "combined_fragment");
    if (combinedFragment == null || !combinedFragment.isTextual()) {
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_INTERACTION_OPERAND_METADATA_INVALID",
          path + ".combined_fragment",
          "UML InteractionOperand render metadata combined_fragment must be text");
    }

    JsonNode order = metadataProperty(properties, "order");
    if (order == null || !order.isIntegralNumber() || order.bigIntegerValue().signum() < 1) {
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_INTERACTION_OPERAND_METADATA_INVALID",
          path + ".order",
          "UML InteractionOperand render metadata order must be a positive integer");
    }

    JsonNode fragments = metadataProperty(properties, "fragments");
    if (!isNonEmptyTextArray(fragments)) {
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_INTERACTION_OPERAND_METADATA_INVALID",
          path + ".fragments",
          "UML InteractionOperand render metadata fragments must be a non-empty array of text ids");
    }

    JsonNode guard = metadataProperty(properties, "guard");
    if (guard != null && !guard.isTextual()) {
      throw new RenderMetadataUsageException(
          "DEDIREN_UML_INTERACTION_OPERAND_METADATA_INVALID",
          path + ".guard",
          "UML InteractionOperand render metadata guard, when present, must be text");
    }
  }

  private static JsonNode metadataProperty(JsonNode properties, String name) {
    return properties == null || !properties.isObject() ? null : properties.get(name);
  }

  private static boolean isTextArray(JsonNode value) {
    if (value == null || !value.isArray()) {
      return false;
    }
    for (JsonNode item : value) {
      if (!item.isTextual()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isNonEmptyTextArray(JsonNode value) {
    return value != null && value.isArray() && !value.isEmpty() && isTextArray(value);
  }

  private static void validateRenderPolicy(RenderPolicy policy) throws PolicyValidationException {
    String interactive = policy.interactive();
    if (interactive != null
        && !interactive.equals("none")
        && !interactive.equals("svg")
        && !interactive.equals("html")
        && !interactive.equals("both")) {
      throw new PolicyValidationException(
          "interactive", "SVG render policy interactive must be one of none, svg, html, both");
    }
    SvgStylePolicy style = policy.style();
    if (style == null) {
      return;
    }
    validateBackgroundStyle(style.background(), "style.background");
    validateFontStyle(style.font(), "style.font");
    validateNodeStyle(style.node(), "style.node");
    validateEdgeStyle(style.edge(), "style.edge");
    validateGroupStyle(style.group(), "style.group");
    validateInteractionStyle(style.interaction(), "style.interaction");
    for (Map.Entry<String, SvgNodeStyle> entry : style.nodeTypeOverrides().entrySet()) {
      validateNodeStyle(entry.getValue(), "style.node_type_overrides." + entry.getKey());
    }
    for (Map.Entry<String, SvgEdgeStyle> entry : style.edgeTypeOverrides().entrySet()) {
      validateEdgeStyle(entry.getValue(), "style.edge_type_overrides." + entry.getKey());
    }
    for (Map.Entry<String, SvgGroupStyle> entry : style.groupTypeOverrides().entrySet()) {
      validateGroupStyle(entry.getValue(), "style.group_type_overrides." + entry.getKey());
    }
    for (Map.Entry<String, SvgNodeStyle> entry : style.nodeOverrides().entrySet()) {
      validateNodeStyle(entry.getValue(), "style.node_overrides." + entry.getKey());
    }
    for (Map.Entry<String, SvgEdgeStyle> entry : style.edgeOverrides().entrySet()) {
      validateEdgeStyle(entry.getValue(), "style.edge_overrides." + entry.getKey());
    }
    for (Map.Entry<String, SvgGroupStyle> entry : style.groupOverrides().entrySet()) {
      validateGroupStyle(entry.getValue(), "style.group_overrides." + entry.getKey());
    }
  }

  private static void validateGenericShapePolicy(RenderPolicy policy)
      throws PolicyValidationException {
    String profile = policy.semanticProfile();
    if (!"archimate".equals(profile) && !"uml".equals(profile)) {
      return;
    }
    SvgStylePolicy style = policy.style();
    if (style == null) {
      return;
    }
    rejectShape(style.node(), "style.node", profile);
    for (Map.Entry<String, SvgNodeStyle> entry : style.nodeTypeOverrides().entrySet()) {
      rejectShape(entry.getValue(), "style.node_type_overrides." + entry.getKey(), profile);
    }
    for (Map.Entry<String, SvgNodeStyle> entry : style.nodeOverrides().entrySet()) {
      rejectShape(entry.getValue(), "style.node_overrides." + entry.getKey(), profile);
    }
  }

  private static void rejectShape(SvgNodeStyle style, String path, String profile)
      throws PolicyValidationException {
    if (style != null && style.shape() != null) {
      throw new PolicyValidationException(
          path + ".shape",
          "SVG render policy "
              + path
              + " shape is not allowed under the "
              + profile
              + " profile; node shapes are for generic graphs");
    }
  }

  private static void validateBackgroundStyle(SvgBackgroundStyle style, String path)
      throws PolicyValidationException {
    if (style != null) {
      validateColor(style.fill(), path + ".fill");
    }
  }

  private static void validateInteractionStyle(SvgInteractionStyle style, String path)
      throws PolicyValidationException {
    if (style != null) {
      validateColor(style.highlightStroke(), path + ".highlight_stroke");
      validateNumber(
          style.highlightStrokeWidth(), path + ".highlight_stroke_width", Bound.MIN, 0.0, 24.0);
    }
  }

  private static void validateFontStyle(SvgFontStyle style, String path)
      throws PolicyValidationException {
    if (style == null) {
      return;
    }
    if (style.family() != null) {
      int length = style.family().codePointCount(0, style.family().length());
      if (length < 1 || length > 120) {
        throw new PolicyValidationException(
            path + ".family",
            "SVG render policy " + path + ".family length is outside the allowed range");
      }
    }
    validateNumber(style.size(), path + ".size", Bound.EXCLUSIVE_MIN, 0.0, 96.0);
  }

  private static void validateNodeStyle(SvgNodeStyle style, String path)
      throws PolicyValidationException {
    if (style == null) {
      return;
    }
    validateColor(style.fill(), path + ".fill");
    validateColor(style.stroke(), path + ".stroke");
    validateNumber(style.strokeWidth(), path + ".stroke_width", Bound.MIN, 0.0, 24.0);
    validateNumber(style.rx(), path + ".rx", Bound.MIN, 0.0, 80.0);
    validateColor(style.labelFill(), path + ".label_fill");
    if (style.shape() != null && style.decorator() != null) {
      throw new PolicyValidationException(
          path + ".shape", "SVG render policy " + path + " cannot set both shape and decorator");
    }
  }

  private static void validateEdgeStyle(SvgEdgeStyle style, String path)
      throws PolicyValidationException {
    if (style == null) {
      return;
    }
    validateColor(style.stroke(), path + ".stroke");
    validateNumber(style.strokeWidth(), path + ".stroke_width", Bound.MIN, 0.0, 24.0);
    validateColor(style.labelFill(), path + ".label_fill");
  }

  private static void validateGroupStyle(SvgGroupStyle style, String path)
      throws PolicyValidationException {
    if (style == null) {
      return;
    }
    validateColor(style.fill(), path + ".fill");
    validateColor(style.stroke(), path + ".stroke");
    validateNumber(style.strokeWidth(), path + ".stroke_width", Bound.MIN, 0.0, 24.0);
    validateNumber(style.rx(), path + ".rx", Bound.MIN, 0.0, 80.0);
    validateColor(style.labelFill(), path + ".label_fill");
    validateNumber(style.labelSize(), path + ".label_size", Bound.EXCLUSIVE_MIN, 0.0, 96.0);
  }

  private static void validateColor(String value, String path) throws PolicyValidationException {
    if (value == null) {
      return;
    }
    boolean valid =
        value.length() == 7
            && value.charAt(0) == '#'
            && value
                .substring(1)
                .chars()
                .allMatch(character -> Character.digit(character, 16) >= 0);
    if (!valid) {
      throw new PolicyValidationException(
          path, "SVG render policy " + path + " must be a #RRGGBB hex color");
    }
  }

  private static void validateNumber(
      Double value, String path, Bound lowerBoundKind, double minimum, double maximum)
      throws PolicyValidationException {
    if (value == null) {
      return;
    }
    boolean lowerOk = lowerBoundKind == Bound.MIN ? value >= minimum : value > minimum;
    if (!Double.isFinite(value) || !lowerOk || value > maximum) {
      throw new PolicyValidationException(
          path, "SVG render policy " + path + " is outside the allowed range");
    }
  }

  private enum Bound {
    MIN,
    EXCLUSIVE_MIN
  }

  public static final class PolicyValidationException extends Exception {
    private final String path;

    private PolicyValidationException(String path, String message) {
      super(message);
      this.path = path;
    }

    public String path() {
      return path;
    }
  }

  public static final class RenderMetadataUsageException extends Exception {
    private final String code;
    private final String path;

    private RenderMetadataUsageException(String code, String path, String message) {
      super(message);
      this.code = code;
      this.path = path;
    }

    public String code() {
      return code;
    }

    public String path() {
      return path;
    }
  }
}
