package dev.dediren.uml;

import static dev.dediren.uml.UmlProperties.optionalArrayProperty;
import static dev.dediren.uml.UmlProperties.optionalProperty;
import static dev.dediren.uml.UmlProperties.propertyValue;
import static dev.dediren.uml.UmlProperties.readTextProperty;
import static dev.dediren.uml.UmlProperties.requireNodeType;
import static dev.dediren.uml.UmlProperties.requiredTextArrayEntry;
import static dev.dediren.uml.UmlProperties.requiredTextProperty;

import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class Uml {
  private static final List<String> STRUCTURAL_TYPES =
      List.of("Package", "Class", "Interface", "DataType", "Enumeration", "Component");
  private static final List<String> ACTIVITY_TYPES =
      List.of(
          "Activity",
          "Action",
          "InitialNode",
          "ActivityFinalNode",
          "DecisionNode",
          "MergeNode",
          "ForkNode",
          "JoinNode",
          "ObjectNode");
  private static final List<String> COMPACT_ACTIVITY_NODE_TYPES =
      List.of(
          "InitialNode", "ActivityFinalNode", "DecisionNode", "MergeNode", "ForkNode", "JoinNode");
  private static final Set<String> SEQUENCE_TYPES =
      Set.of(
          "Interaction",
          "Lifeline",
          "ExecutionSpecification",
          "Gate",
          "DestructionOccurrenceSpecification",
          "CombinedFragment",
          "InteractionOperand");
  private static final Set<String> STATE_MACHINE_TYPES =
      Set.of("StateMachine", "Region", "State", "FinalState", "Pseudostate");
  private static final Set<String> USE_CASE_TYPES = Set.of("Actor", "UseCase", "ExtensionPoint");
  private static final Set<String> COMPONENT_TYPES = Set.of("Port");
  private static final Set<String> DEPLOYMENT_TYPES =
      Set.of("Node", "Device", "ExecutionEnvironment", "Artifact", "DeploymentSpecification");
  private static final Set<String> STATE_VERTEX_TYPES =
      Set.of("State", "FinalState", "Pseudostate");
  private static final Set<String> TRANSITION_SOURCE_TYPES = Set.of("State", "Pseudostate");
  private static final Set<String> TRANSITION_TARGET_TYPES =
      Set.of("State", "FinalState", "Pseudostate");
  private static final Set<String> PSEUDOSTATE_KINDS =
      Set.of(
          "initial",
          "deepHistory",
          "shallowHistory",
          "join",
          "fork",
          "junction",
          "choice",
          "entryPoint",
          "exitPoint",
          "terminate");
  private static final Set<String> TRANSITION_KINDS = Set.of("internal", "local", "external");
  private static final List<String> RELATIONSHIP_TYPES =
      List.of(
          "Association",
          "Composition",
          "Aggregation",
          "Generalization",
          "Realization",
          "Dependency",
          "ControlFlow",
          "ObjectFlow",
          "Message",
          "Transition",
          "Include",
          "Extend",
          "Usage",
          "Deployment",
          "Manifestation",
          "CommunicationPath");
  private static final List<String> STRUCTURAL_RELATIONSHIP_TYPES =
      List.of(
          "Association",
          "Composition",
          "Aggregation",
          "Generalization",
          "Realization",
          "Dependency");
  private static final List<String> ACTIVITY_FLOW_TYPES = List.of("ControlFlow", "ObjectFlow");

  private Uml() {}

  public static List<String> relationshipTypes() {
    return RELATIONSHIP_TYPES;
  }

  public static boolean isCompactActivityNodeType(String value) {
    return COMPACT_ACTIVITY_NODE_TYPES.contains(value);
  }

  public static void validateSource(SourceDocument source, GenericGraphPluginData pluginData)
      throws UmlValidationException {
    var nodeTypes = new HashMap<String, String>();
    var nodeUmlProperties = new HashMap<String, JsonNode>();
    var nodePaths = new HashMap<String, String>();
    for (int nodeIndex = 0; nodeIndex < source.nodes().size(); nodeIndex++) {
      SourceNode node = source.nodes().get(nodeIndex);
      nodeTypes.put(node.id(), node.type());
      nodeUmlProperties.put(node.id(), node.properties().get("uml"));
      nodePaths.put(node.id(), "$.nodes[" + nodeIndex + "]");
    }

    var relationshipTypes = new HashMap<String, String>();
    var relationshipUmlProperties = new HashMap<String, JsonNode>();
    var relationshipSources = new HashMap<String, String>();
    var relationshipTargets = new HashMap<String, String>();
    for (SourceRelationship relationship : source.relationships()) {
      relationshipTypes.put(relationship.id(), relationship.type());
      relationshipUmlProperties.put(relationship.id(), relationship.properties().get("uml"));
      relationshipSources.put(relationship.id(), relationship.source());
      relationshipTargets.put(relationship.id(), relationship.target());
    }
    var context =
        new ValidationContext(
            nodeTypes,
            nodeUmlProperties,
            nodePaths,
            relationshipTypes,
            relationshipUmlProperties,
            relationshipSources,
            relationshipTargets);

    for (int nodeIndex = 0; nodeIndex < source.nodes().size(); nodeIndex++) {
      SourceNode node = source.nodes().get(nodeIndex);
      validateElementType(node.type(), "$.nodes[" + nodeIndex + "].type");
      validateNodeMultiplicities(nodeIndex, node.properties().get("uml"));
    }

    for (int relationshipIndex = 0;
        relationshipIndex < source.relationships().size();
        relationshipIndex++) {
      SourceRelationship relationship = source.relationships().get(relationshipIndex);
      validateRelationshipType(
          relationship.type(), "$.relationships[" + relationshipIndex + "].type");
      validateRelationshipMultiplicities(relationshipIndex, relationship.properties().get("uml"));
      validateRelationshipProperties(
          relationship.type(),
          relationship.properties().get("uml"),
          "$.relationships[" + relationshipIndex + "]");
      String sourceType = nodeTypes.get(relationship.source());
      String targetType = nodeTypes.get(relationship.target());
      if (sourceType == null || targetType == null) {
        continue;
      }
      validateRelationshipEndpointTypes(
          relationship.type(),
          sourceType,
          targetType,
          "$.relationships[" + relationshipIndex + "]");
    }
    UmlSequenceValidation.validateMessageSequenceUniqueness(source.relationships());

    for (int nodeIndex = 0; nodeIndex < source.nodes().size(); nodeIndex++) {
      SourceNode node = source.nodes().get(nodeIndex);
      validateUmlNodeProperties(
          node.id(),
          node.type(),
          node.properties().get("uml"),
          "$.nodes[" + nodeIndex + "]",
          context);
    }
    validateTransitionRegionConsistency(source.relationships(), context);
    validateUseCaseRelationships(source.relationships(), context);
    UmlSequenceValidation.validateCombinedFragmentNesting(source.nodes(), context);
    UmlSequenceValidation.validateInteractionOperandOwnerSelection(source.nodes(), context);
    UmlSequenceValidation.validateInteractionFragmentOwnership(source.nodes(), context);
    UmlSequenceValidation.validateCombinedFragmentSequenceContiguity(source.nodes(), context);

    for (int viewIndex = 0; viewIndex < pluginData.views().size(); viewIndex++) {
      var view = pluginData.views().get(viewIndex);
      if (view.kind() == null) {
        continue;
      }
      for (int nodeIndex = 0; nodeIndex < view.nodes().size(); nodeIndex++) {
        String nodeType = nodeTypes.get(view.nodes().get(nodeIndex));
        if (nodeType == null) {
          continue;
        }
        validateViewNodeType(
            view.kind(),
            nodeType,
            "$.plugins.generic-graph.views[" + viewIndex + "].nodes[" + nodeIndex + "]");
      }
      if (view.kind() == GenericGraphViewKind.UML_SEQUENCE) {
        validateUmlSequenceViewProperties(viewIndex, view, context);
      }
      if (view.kind() == GenericGraphViewKind.UML_STATE_MACHINE) {
        validateUmlStateMachineViewProperties(viewIndex, view, context);
      }
      if (view.kind() == GenericGraphViewKind.UML_USE_CASE) {
        validateUmlUseCaseViewProperties(viewIndex, view, context);
      }
      if (view.kind() == GenericGraphViewKind.UML_COMPONENT) {
        validateUmlComponentViewProperties(viewIndex, view, context);
      }
      if (view.kind() == GenericGraphViewKind.UML_DEPLOYMENT) {
        validateUmlDeploymentViewProperties(viewIndex, view, context);
      }
    }
  }

  private static void validateUmlNodeProperties(
      String nodeId,
      String nodeType,
      JsonNode umlProperties,
      String path,
      ValidationContext context)
      throws UmlValidationException {
    if ("CombinedFragment".equals(nodeType)) {
      UmlSequenceValidation.validateCombinedFragmentProperties(
          nodeId, umlProperties, path, context);
    } else if ("InteractionOperand".equals(nodeType)) {
      UmlSequenceValidation.validateInteractionOperandProperties(umlProperties, path, context);
    } else if ("Region".equals(nodeType)) {
      validateRegionProperties(umlProperties, path, context);
    } else if (isStateVertexType(nodeType)) {
      validateStateVertexProperties(nodeType, umlProperties, path, context);
    } else if ("UseCase".equals(nodeType)) {
      validateUseCaseProperties(umlProperties, path, context);
    } else if ("ExtensionPoint".equals(nodeType)) {
      validateExtensionPointProperties(umlProperties, path, context);
    } else if ("Port".equals(nodeType)) {
      validatePortProperties(umlProperties, path, context);
    } else if ("ExecutionEnvironment".equals(nodeType)) {
      validateExecutionEnvironmentProperties(umlProperties, path, context);
    }
  }

  private static void validateUmlSequenceViewProperties(
      int viewIndex, GenericGraphView view, ValidationContext context)
      throws UmlValidationException {
    var selectedNodeIds = new HashSet<>(view.nodes());
    var selectedRelationshipIds = new HashSet<>(view.relationships());
    for (int nodeIndex = 0; nodeIndex < view.nodes().size(); nodeIndex++) {
      String nodeId = view.nodes().get(nodeIndex);
      String nodeType = context.nodeTypes().get(nodeId);
      JsonNode umlProperties = context.nodeUmlProperties().get(nodeId);
      String sourceUmlPath = context.nodePaths().get(nodeId) + ".properties.uml";
      if ("CombinedFragment".equals(nodeType)) {
        UmlSequenceValidation.validateSelectedCombinedFragmentProperties(
            umlProperties, sourceUmlPath, selectedNodeIds);
      } else if ("InteractionOperand".equals(nodeType)) {
        UmlSequenceValidation.validateSelectedInteractionOperandProperties(
            nodeId,
            umlProperties,
            sourceUmlPath,
            selectedNodeIds,
            selectedRelationshipIds,
            context);
      }
    }
  }

  private static void validateUmlStateMachineViewProperties(
      int viewIndex, GenericGraphView view, ValidationContext context)
      throws UmlValidationException {
    var selectedNodeIds = new HashSet<>(view.nodes());
    for (int relationshipIndex = 0;
        relationshipIndex < view.relationships().size();
        relationshipIndex++) {
      String relationshipId = view.relationships().get(relationshipIndex);
      if (!"Transition".equals(context.relationshipTypes().get(relationshipId))) {
        continue;
      }
      String source = context.relationshipSources().get(relationshipId);
      String target = context.relationshipTargets().get(relationshipId);
      if (!selectedNodeIds.contains(source) || !selectedNodeIds.contains(target)) {
        throw new UmlValidationException(
            UmlTypeKind.RELATIONSHIP_ENDPOINT,
            relationshipId,
            "$.plugins.generic-graph.views["
                + viewIndex
                + "].relationships["
                + relationshipIndex
                + "]");
      }
    }
  }

  private static void validateUmlUseCaseViewProperties(
      int viewIndex, GenericGraphView view, ValidationContext context)
      throws UmlValidationException {
    var selectedNodeIds = new HashSet<>(view.nodes());
    for (int relationshipIndex = 0;
        relationshipIndex < view.relationships().size();
        relationshipIndex++) {
      String relationshipId = view.relationships().get(relationshipIndex);
      String relationshipType = context.relationshipTypes().get(relationshipId);
      if (!isUseCaseRelationshipType(relationshipType)) {
        continue;
      }
      String source = context.relationshipSources().get(relationshipId);
      String target = context.relationshipTargets().get(relationshipId);
      if (!selectedNodeIds.contains(source) || !selectedNodeIds.contains(target)) {
        throw new UmlValidationException(
            UmlTypeKind.RELATIONSHIP_ENDPOINT,
            relationshipId,
            "$.plugins.generic-graph.views["
                + viewIndex
                + "].relationships["
                + relationshipIndex
                + "]");
      }
    }
  }

  private static void validateUmlComponentViewProperties(
      int viewIndex, GenericGraphView view, ValidationContext context)
      throws UmlValidationException {
    var selectedNodeIds = new HashSet<>(view.nodes());
    for (var group : view.groups()) {
      if (group.semanticSourceId() != null) {
        selectedNodeIds.add(group.semanticSourceId());
      }
    }
    for (int relationshipIndex = 0;
        relationshipIndex < view.relationships().size();
        relationshipIndex++) {
      String relationshipId = view.relationships().get(relationshipIndex);
      String relationshipType = context.relationshipTypes().get(relationshipId);
      if (!isComponentRelationshipType(relationshipType)) {
        continue;
      }
      String source = context.relationshipSources().get(relationshipId);
      String target = context.relationshipTargets().get(relationshipId);
      if (!selectedNodeIds.contains(source) || !selectedNodeIds.contains(target)) {
        throw new UmlValidationException(
            UmlTypeKind.RELATIONSHIP_ENDPOINT,
            relationshipId,
            "$.plugins.generic-graph.views["
                + viewIndex
                + "].relationships["
                + relationshipIndex
                + "]");
      }
    }
  }

  private static void validateUmlDeploymentViewProperties(
      int viewIndex, GenericGraphView view, ValidationContext context)
      throws UmlValidationException {
    var selectedNodeIds = new HashSet<>(view.nodes());
    for (var group : view.groups()) {
      if (group.semanticSourceId() != null) {
        selectedNodeIds.add(group.semanticSourceId());
      }
    }
    for (int relationshipIndex = 0;
        relationshipIndex < view.relationships().size();
        relationshipIndex++) {
      String relationshipId = view.relationships().get(relationshipIndex);
      String relationshipType = context.relationshipTypes().get(relationshipId);
      if (!isDeploymentRelationshipType(relationshipType)) {
        continue;
      }
      String source = context.relationshipSources().get(relationshipId);
      String target = context.relationshipTargets().get(relationshipId);
      if (!selectedNodeIds.contains(source) || !selectedNodeIds.contains(target)) {
        throw new UmlValidationException(
            UmlTypeKind.RELATIONSHIP_ENDPOINT,
            relationshipId,
            "$.plugins.generic-graph.views["
                + viewIndex
                + "].relationships["
                + relationshipIndex
                + "]");
      }
    }
  }

  public static void validateElementType(String value, String path) throws UmlValidationException {
    if (!isStructuralType(value)
        && !isActivityType(value)
        && !isSequenceType(value)
        && !isStateMachineType(value)
        && !isUseCaseType(value)
        && !isComponentType(value)
        && !isDeploymentType(value)) {
      throw new UmlValidationException(UmlTypeKind.ELEMENT, value, path);
    }
  }

  public static void validateRelationshipType(String value, String path)
      throws UmlValidationException {
    if (!RELATIONSHIP_TYPES.contains(value)) {
      throw new UmlValidationException(UmlTypeKind.RELATIONSHIP, value, path);
    }
  }

  public static void validateRelationshipEndpointTypes(
      String relationshipType, String sourceType, String targetType, String path)
      throws UmlValidationException {
    boolean endpointsSupported;
    if (STRUCTURAL_RELATIONSHIP_TYPES.contains(relationshipType)) {
      endpointsSupported =
          isStructuralType(sourceType) && isStructuralType(targetType)
              || "Association".equals(relationshipType)
                  && isActorUseCasePair(sourceType, targetType);
    } else if (ACTIVITY_FLOW_TYPES.contains(relationshipType)) {
      endpointsSupported = isActivityType(sourceType) && isActivityType(targetType);
    } else if ("Message".equals(relationshipType)) {
      endpointsSupported = isMessageEndpoint(sourceType, targetType);
    } else if ("Transition".equals(relationshipType)) {
      endpointsSupported =
          TRANSITION_SOURCE_TYPES.contains(sourceType)
              && TRANSITION_TARGET_TYPES.contains(targetType);
    } else if ("Include".equals(relationshipType) || "Extend".equals(relationshipType)) {
      endpointsSupported = "UseCase".equals(sourceType) && "UseCase".equals(targetType);
    } else if ("Usage".equals(relationshipType)) {
      endpointsSupported = isComponentUsageSource(sourceType) && isStructuralType(targetType);
    } else if ("Deployment".equals(relationshipType)) {
      endpointsSupported = isDeployedArtifactType(sourceType) && isDeploymentTargetType(targetType);
    } else if ("Manifestation".equals(relationshipType)) {
      endpointsSupported = isDeployedArtifactType(sourceType) && isStructuralType(targetType);
    } else if ("CommunicationPath".equals(relationshipType)) {
      endpointsSupported = isDeploymentTargetType(sourceType) && isDeploymentTargetType(targetType);
    } else {
      endpointsSupported = false;
    }
    if (!endpointsSupported) {
      throw new UmlValidationException(
          UmlTypeKind.RELATIONSHIP_ENDPOINT,
          relationshipType + ": " + sourceType + " -> " + targetType,
          path);
    }
  }

  public static void validateRelationshipProperties(
      String relationshipType, JsonNode umlProperties, String path) throws UmlValidationException {
    if ("Message".equals(relationshipType)) {
      UmlSequenceValidation.validateMessageProperties(umlProperties, path);
    } else if ("Transition".equals(relationshipType)) {
      validateTransitionProperties(umlProperties, path);
    }
  }

  public static void validateMultiplicity(String value, String path) throws UmlValidationException {
    if (!isValidMultiplicity(value)) {
      throw new UmlValidationException(UmlTypeKind.MULTIPLICITY, value, path);
    }
  }

  private static void validateNodeMultiplicities(int nodeIndex, JsonNode umlProperties)
      throws UmlValidationException {
    JsonNode attributes = umlProperties == null ? null : umlProperties.get("attributes");
    if (attributes == null || !attributes.isArray()) {
      return;
    }
    for (int attributeIndex = 0; attributeIndex < attributes.size(); attributeIndex++) {
      JsonNode multiplicity = attributes.get(attributeIndex).get("multiplicity");
      if (multiplicity == null) {
        continue;
      }
      validateMultiplicityValue(
          multiplicity,
          "$.nodes["
              + nodeIndex
              + "].properties.uml.attributes["
              + attributeIndex
              + "].multiplicity");
    }
  }

  private static void validateRelationshipMultiplicities(
      int relationshipIndex, JsonNode umlProperties) throws UmlValidationException {
    if (umlProperties == null || !umlProperties.isObject()) {
      return;
    }
    for (String field : List.of("source_multiplicity", "target_multiplicity")) {
      JsonNode multiplicity = umlProperties.get(field);
      if (multiplicity == null) {
        continue;
      }
      validateMultiplicityValue(
          multiplicity, "$.relationships[" + relationshipIndex + "].properties.uml." + field);
    }
  }

  private static void validateMultiplicityValue(JsonNode value, String path)
      throws UmlValidationException {
    if (!value.isTextual()) {
      throw new UmlValidationException(UmlTypeKind.MULTIPLICITY, value.toString(), path);
    }
    validateMultiplicity(value.asText(), path);
  }

  private static void validateRegionProperties(
      JsonNode umlProperties, String path, ValidationContext context)
      throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    String stateMachine =
        requiredTextProperty(umlProperties, "state_machine", "Region.state_machine", umlPath);
    requireNodeType(stateMachine, "StateMachine", context.nodeTypes(), umlPath + ".state_machine");
  }

  private static void validateStateVertexProperties(
      String nodeType, JsonNode umlProperties, String path, ValidationContext context)
      throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    String region = requiredTextProperty(umlProperties, "region", nodeType + ".region", umlPath);
    requireNodeType(region, "Region", context.nodeTypes(), umlPath + ".region");
    if ("Pseudostate".equals(nodeType)) {
      String kind = requiredTextProperty(umlProperties, "kind", "Pseudostate.kind", umlPath);
      if (!PSEUDOSTATE_KINDS.contains(kind)) {
        throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, kind, umlPath + ".kind");
      }
    }
  }

  private static void validateUseCaseProperties(
      JsonNode umlProperties, String path, ValidationContext context)
      throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    JsonNode subject = optionalProperty(umlProperties, "subject");
    if (subject == null) {
      return;
    }
    if (!subject.isTextual()
        || !isUseCaseSubjectClassifier(context.nodeTypes().get(subject.asText()))) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY,
          propertyValue(subject, "UseCase.subject"),
          umlPath + ".subject");
    }
  }

  private static void validateExtensionPointProperties(
      JsonNode umlProperties, String path, ValidationContext context)
      throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    String useCase =
        requiredTextProperty(umlProperties, "use_case", "ExtensionPoint.use_case", umlPath);
    requireNodeType(useCase, "UseCase", context.nodeTypes(), umlPath + ".use_case");
  }

  private static void validatePortProperties(
      JsonNode umlProperties, String path, ValidationContext context)
      throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    String component = requiredTextProperty(umlProperties, "component", "Port.component", umlPath);
    requireNodeType(component, "Component", context.nodeTypes(), umlPath + ".component");
    for (String field : List.of("provided", "required")) {
      JsonNode interfaces = optionalArrayProperty(umlProperties, field, umlPath);
      if (interfaces == null) {
        continue;
      }
      for (int interfaceIndex = 0; interfaceIndex < interfaces.size(); interfaceIndex++) {
        String interfaceId =
            requiredTextArrayEntry(interfaces, interfaceIndex, umlPath + "." + field);
        requireNodeType(
            interfaceId,
            "Interface",
            context.nodeTypes(),
            umlPath + "." + field + "[" + interfaceIndex + "]");
      }
    }
  }

  private static void validateExecutionEnvironmentProperties(
      JsonNode umlProperties, String path, ValidationContext context)
      throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    JsonNode parent = optionalProperty(umlProperties, "node");
    if (parent == null) {
      return;
    }
    if (!parent.isTextual()
        || !isDeploymentParentTargetType(context.nodeTypes().get(parent.asText()))) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY,
          propertyValue(parent, "ExecutionEnvironment.node"),
          umlPath + ".node");
    }
  }

  private static void validateTransitionProperties(JsonNode umlProperties, String path)
      throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    if (umlProperties == null || !umlProperties.isObject()) {
      throw new UmlValidationException(
          UmlTypeKind.RELATIONSHIP_PROPERTY, "Transition.region", umlPath + ".region");
    }
    JsonNode region = umlProperties.get("region");
    if (region == null || !region.isTextual()) {
      throw new UmlValidationException(
          UmlTypeKind.RELATIONSHIP_PROPERTY, "Transition.region", umlPath + ".region");
    }

    JsonNode kind = umlProperties.get("kind");
    if (kind != null && (!kind.isTextual() || !TRANSITION_KINDS.contains(kind.asText()))) {
      throw new UmlValidationException(
          UmlTypeKind.RELATIONSHIP_PROPERTY,
          propertyValue(kind, "Transition.kind"),
          umlPath + ".kind");
    }

    for (String field : List.of("trigger", "guard", "effect")) {
      JsonNode value = umlProperties.get(field);
      if (value != null && !value.isTextual()) {
        throw new UmlValidationException(
            UmlTypeKind.RELATIONSHIP_PROPERTY, value.toString(), umlPath + "." + field);
      }
    }
  }

  private static void validateTransitionRegionConsistency(
      List<SourceRelationship> relationships, ValidationContext context)
      throws UmlValidationException {
    for (int relationshipIndex = 0; relationshipIndex < relationships.size(); relationshipIndex++) {
      SourceRelationship relationship = relationships.get(relationshipIndex);
      if (!"Transition".equals(relationship.type())) {
        continue;
      }
      String path = "$.relationships[" + relationshipIndex + "]";
      String region =
          readTextProperty(context.relationshipUmlProperties().get(relationship.id()), "region");
      requireNodeType(region, "Region", context.nodeTypes(), path + ".properties.uml.region");

      String sourceType = context.nodeTypes().get(relationship.source());
      String targetType = context.nodeTypes().get(relationship.target());
      if ("FinalState".equals(sourceType)) {
        throw new UmlValidationException(
            UmlTypeKind.RELATIONSHIP_ENDPOINT, "Transition: FinalState -> " + targetType, path);
      }
      if ("Pseudostate".equals(targetType)
          && "initial"
              .equals(
                  readTextProperty(
                      context.nodeUmlProperties().get(relationship.target()), "kind"))) {
        throw new UmlValidationException(
            UmlTypeKind.RELATIONSHIP_ENDPOINT, "Transition target initial Pseudostate", path);
      }

      for (String endpoint : List.of(relationship.source(), relationship.target())) {
        String endpointRegion =
            readTextProperty(context.nodeUmlProperties().get(endpoint), "region");
        if (!region.equals(endpointRegion)) {
          throw new UmlValidationException(
              UmlTypeKind.RELATIONSHIP_PROPERTY, region, path + ".properties.uml.region");
        }
      }
    }
  }

  private static void validateUseCaseRelationships(
      List<SourceRelationship> relationships, ValidationContext context)
      throws UmlValidationException {
    for (int relationshipIndex = 0; relationshipIndex < relationships.size(); relationshipIndex++) {
      SourceRelationship relationship = relationships.get(relationshipIndex);
      if (!"Extend".equals(relationship.type())) {
        continue;
      }
      String extensionPoint =
          readTextProperty(
              context.relationshipUmlProperties().get(relationship.id()), "extension_point");
      if (extensionPoint == null) {
        continue;
      }
      String path = "$.relationships[" + relationshipIndex + "].properties.uml.extension_point";
      if (!"ExtensionPoint".equals(context.nodeTypes().get(extensionPoint))
          || !relationship
              .target()
              .equals(
                  readTextProperty(context.nodeUmlProperties().get(extensionPoint), "use_case"))) {
        throw new UmlValidationException(UmlTypeKind.RELATIONSHIP_PROPERTY, extensionPoint, path);
      }
    }
  }

  private static void validateViewNodeType(
      GenericGraphViewKind viewKind, String nodeType, String path) throws UmlValidationException {
    boolean supported =
        switch (viewKind) {
          case GENERIC, ARCHIMATE -> true;
          case UML_CLASS, UML_DATA -> isStructuralType(nodeType);
          case UML_ACTIVITY -> isActivityType(nodeType);
          case UML_SEQUENCE -> isSequenceType(nodeType);
          case UML_STATE_MACHINE -> isStateVertexType(nodeType);
          case UML_COMPONENT -> isComponentViewNodeType(nodeType);
          case UML_USE_CASE -> isUseCaseViewNodeType(nodeType);
          case UML_DEPLOYMENT -> isDeploymentViewNodeType(nodeType);
        };
    if (!supported) {
      throw new UmlValidationException(
          UmlTypeKind.VIEW_KIND, nodeType + " in " + viewKindName(viewKind), path);
    }
  }

  private static boolean isValidMultiplicity(String value) {
    if (value.equals("*") || isNonNegativeInteger(value)) {
      return true;
    }
    int split = value.indexOf("..");
    if (split < 0) {
      return false;
    }
    String lower = value.substring(0, split);
    String upper = value.substring(split + 2);
    if (!isNonNegativeInteger(lower)) {
      return false;
    }
    if (upper.equals("*")) {
      return true;
    }
    return isNonNegativeInteger(upper) && numericStringLte(lower, upper);
  }

  private static boolean isNonNegativeInteger(String value) {
    return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
  }

  private static boolean numericStringLte(String lower, String upper) {
    String normalizedLower = normalizeNumericString(lower);
    String normalizedUpper = normalizeNumericString(upper);
    return normalizedLower.length() < normalizedUpper.length()
        || (normalizedLower.length() == normalizedUpper.length()
            && normalizedLower.compareTo(normalizedUpper) <= 0);
  }

  private static String normalizeNumericString(String value) {
    String normalized = value.replaceFirst("^0+", "");
    return normalized.isEmpty() ? "0" : normalized;
  }

  private static boolean isStructuralType(String value) {
    return STRUCTURAL_TYPES.contains(value);
  }

  private static boolean isActivityType(String value) {
    return ACTIVITY_TYPES.contains(value);
  }

  private static boolean isSequenceType(String value) {
    return SEQUENCE_TYPES.contains(value);
  }

  private static boolean isStateMachineType(String value) {
    return STATE_MACHINE_TYPES.contains(value);
  }

  private static boolean isUseCaseType(String value) {
    return USE_CASE_TYPES.contains(value);
  }

  private static boolean isComponentType(String value) {
    return COMPONENT_TYPES.contains(value);
  }

  private static boolean isDeploymentType(String value) {
    return DEPLOYMENT_TYPES.contains(value);
  }

  private static boolean isStateVertexType(String value) {
    return STATE_VERTEX_TYPES.contains(value);
  }

  private static boolean isComponentViewNodeType(String value) {
    return isStructuralType(value) || isComponentType(value);
  }

  private static boolean isUseCaseViewNodeType(String value) {
    return USE_CASE_TYPES.contains(value);
  }

  private static boolean isDeploymentViewNodeType(String value) {
    return isDeploymentType(value) || isStructuralType(value);
  }

  private static boolean isUseCaseRelationshipType(String value) {
    return "Association".equals(value) || "Include".equals(value) || "Extend".equals(value);
  }

  private static boolean isComponentRelationshipType(String value) {
    return STRUCTURAL_RELATIONSHIP_TYPES.contains(value) || "Usage".equals(value);
  }

  private static boolean isDeploymentRelationshipType(String value) {
    return "Deployment".equals(value)
        || "Manifestation".equals(value)
        || "CommunicationPath".equals(value);
  }

  private static boolean isComponentUsageSource(String value) {
    return "Component".equals(value) || "Port".equals(value);
  }

  private static boolean isDeployedArtifactType(String value) {
    return "Artifact".equals(value) || "DeploymentSpecification".equals(value);
  }

  private static boolean isDeploymentTargetType(String value) {
    return "Node".equals(value) || "Device".equals(value) || "ExecutionEnvironment".equals(value);
  }

  private static boolean isDeploymentParentTargetType(String value) {
    return "Node".equals(value) || "Device".equals(value);
  }

  private static boolean isActorUseCasePair(String sourceType, String targetType) {
    return "Actor".equals(sourceType) && "UseCase".equals(targetType)
        || "UseCase".equals(sourceType) && "Actor".equals(targetType);
  }

  private static boolean isUseCaseSubjectClassifier(String value) {
    return "Class".equals(value)
        || "Interface".equals(value)
        || "DataType".equals(value)
        || "Enumeration".equals(value);
  }

  private static boolean isMessageEndpoint(String sourceType, String targetType) {
    return "Lifeline".equals(sourceType)
        && ("Lifeline".equals(targetType)
            || "DestructionOccurrenceSpecification".equals(targetType));
  }

  private static String viewKindName(GenericGraphViewKind kind) {
    return switch (kind) {
      case GENERIC -> "generic";
      case ARCHIMATE -> "archimate";
      case UML_CLASS -> "uml-class";
      case UML_DATA -> "uml-data";
      case UML_ACTIVITY -> "uml-activity";
      case UML_SEQUENCE -> "uml-sequence";
      case UML_STATE_MACHINE -> "uml-state-machine";
      case UML_COMPONENT -> "uml-component";
      case UML_USE_CASE -> "uml-use-case";
      case UML_DEPLOYMENT -> "uml-deployment";
    };
  }
}
