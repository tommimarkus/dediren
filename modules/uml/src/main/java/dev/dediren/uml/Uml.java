package dev.dediren.uml;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Uml {
    private static final List<String> STRUCTURAL_TYPES = List.of(
            "Package",
            "Class",
            "Interface",
            "DataType",
            "Enumeration");
    private static final List<String> ACTIVITY_TYPES = List.of(
            "Activity",
            "Action",
            "InitialNode",
            "ActivityFinalNode",
            "DecisionNode",
            "MergeNode",
            "ForkNode",
            "JoinNode",
            "ObjectNode");
    private static final List<String> COMPACT_ACTIVITY_NODE_TYPES = List.of(
            "InitialNode",
            "ActivityFinalNode",
            "DecisionNode",
            "MergeNode",
            "ForkNode",
            "JoinNode");
    private static final Set<String> SEQUENCE_TYPES = Set.of(
            "Interaction",
            "Lifeline",
            "ExecutionSpecification",
            "Gate",
            "DestructionOccurrenceSpecification",
            "CombinedFragment",
            "InteractionOperand");
    private static final List<String> RELATIONSHIP_TYPES = List.of(
            "Association",
            "Composition",
            "Aggregation",
            "Generalization",
            "Realization",
            "Dependency",
            "ControlFlow",
            "ObjectFlow",
            "Message");
    private static final List<String> STRUCTURAL_RELATIONSHIP_TYPES = List.of(
            "Association",
            "Composition",
            "Aggregation",
            "Generalization",
            "Realization",
            "Dependency");
    private static final List<String> ACTIVITY_FLOW_TYPES = List.of("ControlFlow", "ObjectFlow");
    private static final Set<String> MESSAGE_SORTS = Set.of(
            "synchCall",
            "asynchCall",
            "asynchSignal",
            "reply",
            "createMessage",
            "deleteMessage");
    private static final Set<String> COMBINED_FRAGMENT_OPERATORS = Set.of(
            "alt",
            "opt",
            "loop",
            "par");

    private Uml() {
    }

    public static List<String> structuralTypes() {
        return STRUCTURAL_TYPES;
    }

    public static List<String> activityTypes() {
        return ACTIVITY_TYPES;
    }

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
        var context = new ValidationContext(
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

        for (int relationshipIndex = 0; relationshipIndex < source.relationships().size(); relationshipIndex++) {
            SourceRelationship relationship = source.relationships().get(relationshipIndex);
            validateRelationshipType(relationship.type(), "$.relationships[" + relationshipIndex + "].type");
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

        for (int nodeIndex = 0; nodeIndex < source.nodes().size(); nodeIndex++) {
            SourceNode node = source.nodes().get(nodeIndex);
            validateSequenceNodeProperties(
                    node.id(),
                    node.type(),
                    node.properties().get("uml"),
                    "$.nodes[" + nodeIndex + "]",
                    context);
        }
        validateCombinedFragmentNesting(source.nodes(), context);
        validateInteractionFragmentOwnership(source.nodes(), context);

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
        }
    }

    private static void validateSequenceNodeProperties(
            String nodeId,
            String nodeType,
            JsonNode umlProperties,
            String path,
            ValidationContext context) throws UmlValidationException {
        if ("CombinedFragment".equals(nodeType)) {
            validateCombinedFragmentProperties(
                    nodeId,
                    umlProperties,
                    path,
                    context);
        } else if ("InteractionOperand".equals(nodeType)) {
            validateInteractionOperandProperties(
                    umlProperties,
                    path,
                    context);
        }
    }

    private static void validateUmlSequenceViewProperties(
            int viewIndex,
            GenericGraphView view,
            ValidationContext context) throws UmlValidationException {
        var selectedNodeIds = new HashSet<>(view.nodes());
        var selectedRelationshipIds = new HashSet<>(view.relationships());
        for (int nodeIndex = 0; nodeIndex < view.nodes().size(); nodeIndex++) {
            String nodeId = view.nodes().get(nodeIndex);
            String nodeType = context.nodeTypes().get(nodeId);
            JsonNode umlProperties = context.nodeUmlProperties().get(nodeId);
            String sourceUmlPath = context.nodePaths().get(nodeId) + ".properties.uml";
            if ("CombinedFragment".equals(nodeType)) {
                validateSelectedCombinedFragmentProperties(
                        umlProperties,
                        sourceUmlPath,
                        selectedNodeIds);
            } else if ("InteractionOperand".equals(nodeType)) {
                validateSelectedInteractionOperandProperties(
                        nodeId,
                        umlProperties,
                        sourceUmlPath,
                        selectedNodeIds,
                        selectedRelationshipIds,
                        context);
            }
        }
    }

    private static void validateSelectedCombinedFragmentProperties(
            JsonNode umlProperties,
            String path,
            Set<String> selectedNodeIds) throws UmlValidationException {
        JsonNode operands = optionalProperty(umlProperties, "operands");
        if (operands != null && operands.isArray()) {
            for (int operandIndex = 0; operandIndex < operands.size(); operandIndex++) {
                String operandId = requiredTextArrayEntry(operands, operandIndex, path + ".operands");
                if (!selectedNodeIds.contains(operandId)) {
                    throw new UmlValidationException(
                            UmlTypeKind.ELEMENT_PROPERTY,
                            operandId,
                            path + ".operands[" + operandIndex + "]");
                }
            }
        }

        JsonNode covered = optionalProperty(umlProperties, "covered");
        if (covered != null && covered.isArray()) {
            for (int coveredIndex = 0; coveredIndex < covered.size(); coveredIndex++) {
                String lifelineId = requiredTextArrayEntry(covered, coveredIndex, path + ".covered");
                if (!selectedNodeIds.contains(lifelineId)) {
                    throw new UmlValidationException(
                            UmlTypeKind.ELEMENT_PROPERTY,
                            lifelineId,
                            path + ".covered[" + coveredIndex + "]");
                }
            }
        }
    }

    private static void validateSelectedInteractionOperandProperties(
            String nodeId,
            JsonNode umlProperties,
            String path,
            Set<String> selectedNodeIds,
            Set<String> selectedRelationshipIds,
            ValidationContext context) throws UmlValidationException {
        String combinedFragment = requiredTextProperty(
                umlProperties,
                "combined_fragment",
                "InteractionOperand.combined_fragment",
                path);
        if (!selectedNodeIds.contains(combinedFragment)) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    combinedFragment,
                    path + ".combined_fragment");
        }
        JsonNode ownerOperands = optionalProperty(context.nodeUmlProperties().get(combinedFragment), "operands");
        if (ownerOperands == null || !ownerOperands.isArray() || !containsTextValue(ownerOperands, nodeId)) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    combinedFragment,
                    path + ".combined_fragment");
        }

        JsonNode fragments = optionalProperty(umlProperties, "fragments");
        if (fragments == null || !fragments.isArray()) {
            return;
        }
        for (int fragmentIndex = 0; fragmentIndex < fragments.size(); fragmentIndex++) {
            String fragmentId = requiredTextArrayEntry(fragments, fragmentIndex, path + ".fragments");
            boolean selected = isMessageRelationship(fragmentId, context)
                    ? selectedRelationshipIds.contains(fragmentId)
                    : selectedNodeIds.contains(fragmentId);
            if (!selected) {
                throw new UmlValidationException(
                        UmlTypeKind.ELEMENT_PROPERTY,
                        fragmentId,
                        path + ".fragments[" + fragmentIndex + "]");
            }
        }
    }

    public static void validateElementType(String value, String path) throws UmlValidationException {
        if (!isStructuralType(value) && !isActivityType(value) && !isSequenceType(value)) {
            throw new UmlValidationException(UmlTypeKind.ELEMENT, value, path);
        }
    }

    public static void validateRelationshipType(String value, String path) throws UmlValidationException {
        if (!RELATIONSHIP_TYPES.contains(value)) {
            throw new UmlValidationException(UmlTypeKind.RELATIONSHIP, value, path);
        }
    }

    public static void validateRelationshipEndpointTypes(
            String relationshipType,
            String sourceType,
            String targetType,
            String path) throws UmlValidationException {
        boolean endpointsSupported;
        if (STRUCTURAL_RELATIONSHIP_TYPES.contains(relationshipType)) {
            endpointsSupported = isStructuralType(sourceType) && isStructuralType(targetType);
        } else if (ACTIVITY_FLOW_TYPES.contains(relationshipType)) {
            endpointsSupported = isActivityType(sourceType) && isActivityType(targetType);
        } else if ("Message".equals(relationshipType)) {
            endpointsSupported = isMessageEndpoint(sourceType, targetType);
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

    public static void validateRelationshipProperties(String relationshipType, JsonNode umlProperties, String path)
            throws UmlValidationException {
        if (!"Message".equals(relationshipType)) {
            return;
        }
        validateMessageProperties(umlProperties, path);
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
                    "$.nodes[" + nodeIndex + "].properties.uml.attributes[" + attributeIndex
                            + "].multiplicity");
        }
    }

    private static void validateRelationshipMultiplicities(int relationshipIndex, JsonNode umlProperties)
            throws UmlValidationException {
        if (umlProperties == null || !umlProperties.isObject()) {
            return;
        }
        for (String field : List.of("source_multiplicity", "target_multiplicity")) {
            JsonNode multiplicity = umlProperties.get(field);
            if (multiplicity == null) {
                continue;
            }
            validateMultiplicityValue(
                    multiplicity,
                    "$.relationships[" + relationshipIndex + "].properties.uml." + field);
        }
    }

    private static void validateMultiplicityValue(JsonNode value, String path) throws UmlValidationException {
        if (!value.isTextual()) {
            throw new UmlValidationException(UmlTypeKind.MULTIPLICITY, value.toString(), path);
        }
        validateMultiplicity(value.asText(), path);
    }

    private static void validateCombinedFragmentProperties(
            String nodeId,
            JsonNode umlProperties,
            String path,
            ValidationContext context) throws UmlValidationException {
        String umlPath = path + ".properties.uml";
        String interaction = requiredTextProperty(
                umlProperties,
                "interaction",
                "CombinedFragment.interaction",
                umlPath);
        requireNodeType(
                interaction,
                "Interaction",
                context.nodeTypes(),
                umlPath + ".interaction");

        String operator = requiredTextProperty(
                umlProperties,
                "operator",
                "CombinedFragment.operator",
                umlPath);
        if (!COMBINED_FRAGMENT_OPERATORS.contains(operator)) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    operator,
                    umlPath + ".operator");
        }

        JsonNode operands = requiredNonEmptyArrayProperty(
                umlProperties,
                "operands",
                "CombinedFragment.operands",
                umlPath);
        validateOperandCount(operator, operands, umlPath + ".operands");
        var operandOrders = new HashSet<BigInteger>();
        for (int operandIndex = 0; operandIndex < operands.size(); operandIndex++) {
            String operandId = requiredTextArrayEntry(
                    operands,
                    operandIndex,
                    umlPath + ".operands");
            requireNodeType(
                    operandId,
                    "InteractionOperand",
                    context.nodeTypes(),
                    umlPath + ".operands[" + operandIndex + "]");
            JsonNode operandUmlProperties = context.nodeUmlProperties().get(operandId);
            String operandUmlPath = context.nodePaths().get(operandId) + ".properties.uml";
            String owner = requiredTextProperty(
                    operandUmlProperties,
                    "combined_fragment",
                    "InteractionOperand.combined_fragment",
                    operandUmlPath);
            if (!nodeId.equals(owner)) {
                throw new UmlValidationException(
                        UmlTypeKind.ELEMENT_PROPERTY,
                        operandId,
                        umlPath + ".operands[" + operandIndex + "]");
            }
            String operandInteraction = requiredTextProperty(
                    operandUmlProperties,
                    "interaction",
                    "InteractionOperand.interaction",
                    operandUmlPath);
            if (!interaction.equals(operandInteraction)) {
                throw new UmlValidationException(
                        UmlTypeKind.ELEMENT_PROPERTY,
                        operandId,
                        umlPath + ".operands[" + operandIndex + "]");
            }
            BigInteger order = requiredPositiveIntegerProperty(
                    operandUmlProperties,
                    "order",
                    "InteractionOperand.order",
                    operandUmlPath + ".order");
            if (!operandOrders.add(order) || !order.equals(BigInteger.valueOf(operandIndex + 1L))) {
                throw new UmlValidationException(
                        UmlTypeKind.ELEMENT_PROPERTY,
                        operandId,
                        umlPath + ".operands[" + operandIndex + "]");
            }
        }

        JsonNode covered = optionalArrayProperty(
                umlProperties,
                "covered",
                umlPath);
        if (covered != null) {
            for (int coveredIndex = 0; coveredIndex < covered.size(); coveredIndex++) {
                String lifelineId = requiredTextArrayEntry(
                        covered,
                        coveredIndex,
                        umlPath + ".covered");
                requireNodeType(
                        lifelineId,
                        "Lifeline",
                        context.nodeTypes(),
                        umlPath + ".covered[" + coveredIndex + "]");
                String lifelineInteraction = readTextProperty(
                        context.nodeUmlProperties().get(lifelineId),
                        "interaction");
                if (!interaction.equals(lifelineInteraction)) {
                    throw new UmlValidationException(
                            UmlTypeKind.ELEMENT_PROPERTY,
                            lifelineId,
                            umlPath + ".covered[" + coveredIndex + "]");
                }
            }
        }
    }

    private static void validateInteractionOperandProperties(
            JsonNode umlProperties,
            String path,
            ValidationContext context) throws UmlValidationException {
        String umlPath = path + ".properties.uml";
        String interaction = requiredTextProperty(
                umlProperties,
                "interaction",
                "InteractionOperand.interaction",
                umlPath);
        requireNodeType(
                interaction,
                "Interaction",
                context.nodeTypes(),
                umlPath + ".interaction");

        String combinedFragment = requiredTextProperty(
                umlProperties,
                "combined_fragment",
                "InteractionOperand.combined_fragment",
                umlPath);
        requireNodeType(
                combinedFragment,
                "CombinedFragment",
                context.nodeTypes(),
                umlPath + ".combined_fragment");

        requiredPositiveIntegerProperty(
                umlProperties,
                "order",
                "InteractionOperand.order",
                umlPath + ".order");

        JsonNode fragments = requiredNonEmptyArrayProperty(
                umlProperties,
                "fragments",
                "InteractionOperand.fragments",
                umlPath);
        for (int fragmentIndex = 0; fragmentIndex < fragments.size(); fragmentIndex++) {
            String fragmentId = requiredTextArrayEntry(
                    fragments,
                    fragmentIndex,
                    umlPath + ".fragments");
            String fragmentPath = umlPath + ".fragments[" + fragmentIndex + "]";
            if ("Message".equals(context.relationshipTypes().get(fragmentId))) {
                String messageInteraction = readTextProperty(
                        context.relationshipUmlProperties().get(fragmentId),
                        "interaction");
                if (!interaction.equals(messageInteraction)) {
                    throw new UmlValidationException(
                            UmlTypeKind.ELEMENT_PROPERTY,
                            fragmentId,
                            fragmentPath);
                }
                validateFragmentCoverage(combinedFragment, fragmentId, fragmentPath, context);
            } else if ("CombinedFragment".equals(context.nodeTypes().get(fragmentId))) {
                String fragmentInteraction = readTextProperty(
                        context.nodeUmlProperties().get(fragmentId),
                        "interaction");
                if (!interaction.equals(fragmentInteraction)) {
                    throw new UmlValidationException(
                            UmlTypeKind.ELEMENT_PROPERTY,
                            fragmentId,
                            fragmentPath);
                }
                validateFragmentCoverage(combinedFragment, fragmentId, fragmentPath, context);
            } else {
                throw new UmlValidationException(
                        UmlTypeKind.ELEMENT_PROPERTY,
                        fragmentId,
                        fragmentPath);
            }
        }

        JsonNode guard = optionalProperty(
                umlProperties,
                "guard");
        if (guard != null && !guard.isTextual()) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    guard.toString(),
                    umlPath + ".guard");
        }
    }

    private static void validateMessageProperties(JsonNode umlProperties, String path)
            throws UmlValidationException {
        String umlPath = path + ".properties.uml";
        if (umlProperties == null || !umlProperties.isObject()) {
            throw new UmlValidationException(
                    UmlTypeKind.RELATIONSHIP_PROPERTY,
                    "Message.sequence",
                    umlPath + ".sequence");
        }

        JsonNode sequence = umlProperties.get("sequence");
        if (sequence == null) {
            throw new UmlValidationException(
                    UmlTypeKind.RELATIONSHIP_PROPERTY,
                    "Message.sequence",
                    umlPath + ".sequence");
        }
        if (!sequence.isIntegralNumber() || sequence.bigIntegerValue().signum() < 1) {
            throw new UmlValidationException(
                    UmlTypeKind.RELATIONSHIP_PROPERTY,
                    sequence.toString(),
                    umlPath + ".sequence");
        }

        JsonNode messageSort = umlProperties.get("message_sort");
        if (messageSort != null
                && (!messageSort.isTextual() || !MESSAGE_SORTS.contains(messageSort.asText()))) {
            throw new UmlValidationException(
                    UmlTypeKind.RELATIONSHIP_PROPERTY,
                    messageSort.isTextual() ? messageSort.asText() : messageSort.toString(),
                    umlPath + ".message_sort");
        }
    }

    private static void validateFragmentCoverage(
            String ownerCombinedFragment,
            String fragmentId,
            String fragmentPath,
            ValidationContext context) throws UmlValidationException {
        JsonNode ownerCovered = optionalProperty(
                context.nodeUmlProperties().get(ownerCombinedFragment),
                "covered");
        if (ownerCovered == null || !ownerCovered.isArray()) {
            return;
        }

        Set<String> ownerCoveredIds = textValueSet(ownerCovered);
        if (isMessageRelationship(fragmentId, context)) {
            String source = context.relationshipSources().get(fragmentId);
            String target = context.relationshipTargets().get(fragmentId);
            if (isUncoveredLifeline(source, ownerCoveredIds, context)
                    || isUncoveredLifeline(target, ownerCoveredIds, context)) {
                throw new UmlValidationException(
                        UmlTypeKind.ELEMENT_PROPERTY,
                        fragmentId,
                        fragmentPath);
            }
            return;
        }

        JsonNode nestedCovered = optionalProperty(
                context.nodeUmlProperties().get(fragmentId),
                "covered");
        if (nestedCovered == null || !nestedCovered.isArray()) {
            return;
        }
        for (JsonNode lifeline : nestedCovered) {
            if (lifeline.isTextual() && !ownerCoveredIds.contains(lifeline.asText())) {
                throw new UmlValidationException(
                        UmlTypeKind.ELEMENT_PROPERTY,
                        fragmentId,
                        fragmentPath);
            }
        }
    }

    private static boolean isUncoveredLifeline(
            String nodeId,
            Set<String> coveredLifelines,
            ValidationContext context) {
        return "Lifeline".equals(context.nodeTypes().get(nodeId)) && !coveredLifelines.contains(nodeId);
    }

    private static void validateCombinedFragmentNesting(
            List<SourceNode> nodes,
            ValidationContext context) throws UmlValidationException {
        for (SourceNode node : nodes) {
            if (!"InteractionOperand".equals(context.nodeTypes().get(node.id()))) {
                continue;
            }
            JsonNode umlProperties = context.nodeUmlProperties().get(node.id());
            String ownerCombinedFragment = readTextProperty(umlProperties, "combined_fragment");
            JsonNode fragments = optionalProperty(umlProperties, "fragments");
            if (ownerCombinedFragment == null || fragments == null || !fragments.isArray()) {
                continue;
            }
            String fragmentsPath = context.nodePaths().get(node.id()) + ".properties.uml.fragments";
            for (int fragmentIndex = 0; fragmentIndex < fragments.size(); fragmentIndex++) {
                JsonNode fragment = fragments.get(fragmentIndex);
                if (!fragment.isTextual()) {
                    continue;
                }
                String nestedCombinedFragment = fragment.asText();
                if (!"CombinedFragment".equals(context.nodeTypes().get(nestedCombinedFragment))) {
                    continue;
                }
                if (ownerCombinedFragment.equals(nestedCombinedFragment)
                        || hasCombinedFragmentPath(
                                nestedCombinedFragment,
                                ownerCombinedFragment,
                                context,
                                new HashSet<>())) {
                    throw new UmlValidationException(
                            UmlTypeKind.ELEMENT_PROPERTY,
                            nestedCombinedFragment,
                            fragmentsPath + "[" + fragmentIndex + "]");
                }
            }
        }
    }

    private static boolean hasCombinedFragmentPath(
            String currentCombinedFragment,
            String targetCombinedFragment,
            ValidationContext context,
            Set<String> visitedCombinedFragments) {
        if (!visitedCombinedFragments.add(currentCombinedFragment)) {
            return false;
        }
        JsonNode operands = optionalProperty(
                context.nodeUmlProperties().get(currentCombinedFragment),
                "operands");
        if (operands == null || !operands.isArray()) {
            return false;
        }

        for (JsonNode operand : operands) {
            if (!operand.isTextual()) {
                continue;
            }
            JsonNode operandFragments = optionalProperty(
                    context.nodeUmlProperties().get(operand.asText()),
                    "fragments");
            if (operandFragments == null || !operandFragments.isArray()) {
                continue;
            }
            for (JsonNode fragment : operandFragments) {
                if (!fragment.isTextual()) {
                    continue;
                }
                String nestedCombinedFragment = fragment.asText();
                if (!"CombinedFragment".equals(context.nodeTypes().get(nestedCombinedFragment))) {
                    continue;
                }
                if (targetCombinedFragment.equals(nestedCombinedFragment)
                        || hasCombinedFragmentPath(
                                nestedCombinedFragment,
                                targetCombinedFragment,
                                context,
                                visitedCombinedFragments)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void validateInteractionFragmentOwnership(
            List<SourceNode> nodes,
            ValidationContext context) throws UmlValidationException {
        var ownedFragmentsByInteraction = new HashMap<String, Set<String>>();
        for (SourceNode node : nodes) {
            if (!"InteractionOperand".equals(context.nodeTypes().get(node.id()))) {
                continue;
            }
            JsonNode umlProperties = context.nodeUmlProperties().get(node.id());
            String interaction = readTextProperty(umlProperties, "interaction");
            JsonNode fragments = optionalProperty(umlProperties, "fragments");
            if (interaction == null || fragments == null || !fragments.isArray()) {
                continue;
            }
            Set<String> ownedFragments = ownedFragmentsByInteraction.computeIfAbsent(
                    interaction,
                    key -> new HashSet<>());
            String fragmentsPath = context.nodePaths().get(node.id()) + ".properties.uml.fragments";
            for (int fragmentIndex = 0; fragmentIndex < fragments.size(); fragmentIndex++) {
                JsonNode fragment = fragments.get(fragmentIndex);
                if (!fragment.isTextual()) {
                    continue;
                }
                String fragmentId = fragment.asText();
                if (!isOwnedFragmentReference(fragmentId, context)) {
                    continue;
                }
                if (!ownedFragments.add(fragmentId)) {
                    throw new UmlValidationException(
                            UmlTypeKind.ELEMENT_PROPERTY,
                            fragmentId,
                            fragmentsPath + "[" + fragmentIndex + "]");
                }
            }
        }
    }

    private static JsonNode requiredProperty(
            JsonNode umlProperties,
            String field,
            String requiredValue,
            String umlPath) throws UmlValidationException {
        if (umlProperties == null || !umlProperties.isObject()) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    requiredValue,
                    umlPath + "." + field);
        }
        JsonNode value = umlProperties.get(field);
        if (value == null) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    requiredValue,
                    umlPath + "." + field);
        }
        return value;
    }

    private static BigInteger requiredPositiveIntegerProperty(
            JsonNode umlProperties,
            String field,
            String requiredValue,
            String path) throws UmlValidationException {
        if (umlProperties == null || !umlProperties.isObject()) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    requiredValue,
                    path);
        }
        JsonNode value = umlProperties.get(field);
        if (value == null) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    requiredValue,
                    path);
        }
        if (!value.isIntegralNumber() || value.bigIntegerValue().signum() < 1) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    propertyValue(value, requiredValue),
                    path);
        }
        return value.bigIntegerValue();
    }

    private static String requiredTextProperty(
            JsonNode umlProperties,
            String field,
            String requiredValue,
            String umlPath) throws UmlValidationException {
        JsonNode value = requiredProperty(umlProperties, field, requiredValue, umlPath);
        if (!value.isTextual()) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    value.toString(),
                    umlPath + "." + field);
        }
        return value.asText();
    }

    private static JsonNode requiredNonEmptyArrayProperty(
            JsonNode umlProperties,
            String field,
            String requiredValue,
            String umlPath) throws UmlValidationException {
        JsonNode value = requiredProperty(umlProperties, field, requiredValue, umlPath);
        if (!value.isArray() || value.isEmpty()) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    value.toString(),
                    umlPath + "." + field);
        }
        return value;
    }

    private static JsonNode optionalProperty(JsonNode umlProperties, String field) {
        if (umlProperties == null || !umlProperties.isObject()) {
            return null;
        }
        return umlProperties.get(field);
    }

    private static JsonNode optionalArrayProperty(
            JsonNode umlProperties,
            String field,
            String umlPath) throws UmlValidationException {
        JsonNode value = optionalProperty(umlProperties, field);
        if (value == null) {
            return null;
        }
        if (!value.isArray()) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    value.toString(),
                    umlPath + "." + field);
        }
        return value;
    }

    private static String readTextProperty(JsonNode umlProperties, String field) {
        JsonNode value = optionalProperty(umlProperties, field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private static String requiredTextArrayEntry(JsonNode values, int index, String path)
            throws UmlValidationException {
        JsonNode value = values.get(index);
        if (!value.isTextual()) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    value.toString(),
                    path + "[" + index + "]");
        }
        return value.asText();
    }

    private static boolean containsTextValue(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (value.isTextual() && expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> textValueSet(JsonNode values) {
        var set = new HashSet<String>();
        for (JsonNode value : values) {
            if (value.isTextual()) {
                set.add(value.asText());
            }
        }
        return set;
    }

    private static void requireNodeType(
            String id,
            String expectedType,
            Map<String, String> nodeTypes,
            String path) throws UmlValidationException {
        if (!expectedType.equals(nodeTypes.get(id))) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    id,
                    path);
        }
    }

    private static void validateOperandCount(String operator, JsonNode operands, String path)
            throws UmlValidationException {
        boolean supported = switch (operator) {
            case "opt", "loop" -> operands.size() == 1;
            case "alt", "par" -> operands.size() >= 2;
            default -> false;
        };
        if (!supported) {
            throw new UmlValidationException(
                    UmlTypeKind.ELEMENT_PROPERTY,
                    "CombinedFragment." + operator + ".operands",
                    path);
        }
    }

    private static String propertyValue(JsonNode value, String fallback) {
        if (value == null) {
            return fallback;
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private static void validateViewNodeType(GenericGraphViewKind viewKind, String nodeType, String path)
            throws UmlValidationException {
        boolean supported = switch (viewKind) {
            case GENERIC, ARCHIMATE -> true;
            case UML_CLASS, UML_DATA -> isStructuralType(nodeType);
            case UML_ACTIVITY -> isActivityType(nodeType);
            case UML_SEQUENCE -> isSequenceType(nodeType);
        };
        if (!supported) {
            throw new UmlValidationException(
                    UmlTypeKind.VIEW_KIND,
                    nodeType + " in " + viewKindName(viewKind),
                    path);
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

    private static boolean isMessageEndpoint(String sourceType, String targetType) {
        return "Lifeline".equals(sourceType)
                && ("Lifeline".equals(targetType) || "DestructionOccurrenceSpecification".equals(targetType));
    }

    private static boolean isMessageRelationship(String id, ValidationContext context) {
        return "Message".equals(context.relationshipTypes().get(id));
    }

    private static boolean isOwnedFragmentReference(String id, ValidationContext context) {
        return isMessageRelationship(id, context) || "CombinedFragment".equals(context.nodeTypes().get(id));
    }

    private static String viewKindName(GenericGraphViewKind kind) {
        return switch (kind) {
            case GENERIC -> "generic";
            case ARCHIMATE -> "archimate";
            case UML_CLASS -> "uml-class";
            case UML_DATA -> "uml-data";
            case UML_ACTIVITY -> "uml-activity";
            case UML_SEQUENCE -> "uml-sequence";
        };
    }

    private record ValidationContext(
            Map<String, String> nodeTypes,
            Map<String, JsonNode> nodeUmlProperties,
            Map<String, String> nodePaths,
            Map<String, String> relationshipTypes,
            Map<String, JsonNode> relationshipUmlProperties,
            Map<String, String> relationshipSources,
            Map<String, String> relationshipTargets) {
    }
}
