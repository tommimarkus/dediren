package dev.dediren.uml;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.HashMap;
import java.util.List;

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
    private static final List<String> RELATIONSHIP_TYPES = List.of(
            "Association",
            "Composition",
            "Aggregation",
            "Generalization",
            "Realization",
            "Dependency",
            "ControlFlow",
            "ObjectFlow");
    private static final List<String> STRUCTURAL_RELATIONSHIP_TYPES = List.of(
            "Association",
            "Composition",
            "Aggregation",
            "Generalization",
            "Realization",
            "Dependency");
    private static final List<String> ACTIVITY_FLOW_TYPES = List.of("ControlFlow", "ObjectFlow");

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
        for (int nodeIndex = 0; nodeIndex < source.nodes().size(); nodeIndex++) {
            SourceNode node = source.nodes().get(nodeIndex);
            validateElementType(node.type(), "$.nodes[" + nodeIndex + "].type");
            validateNodeMultiplicities(nodeIndex, node.properties().get("uml"));
        }

        var nodeTypes = new HashMap<String, String>();
        for (SourceNode node : source.nodes()) {
            nodeTypes.put(node.id(), node.type());
        }

        for (int relationshipIndex = 0; relationshipIndex < source.relationships().size(); relationshipIndex++) {
            SourceRelationship relationship = source.relationships().get(relationshipIndex);
            validateRelationshipType(relationship.type(), "$.relationships[" + relationshipIndex + "].type");
            validateRelationshipMultiplicities(relationshipIndex, relationship.properties().get("uml"));
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

        for (int viewIndex = 0; viewIndex < pluginData.views().size(); viewIndex++) {
            var view = pluginData.views().get(viewIndex);
            if (view.kind() == null) {
                continue;
            }
            validateViewKind(
                    view.kind(),
                    "$.plugins.generic-graph.views[" + viewIndex + "].kind");
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
        }
    }

    public static void validateElementType(String value, String path) throws UmlValidationException {
        if (!isStructuralType(value) && !isActivityType(value)) {
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

    private static void validateViewKind(GenericGraphViewKind viewKind, String path) throws UmlValidationException {
        if (viewKind == GenericGraphViewKind.UML_SEQUENCE) {
            throw new UmlValidationException(
                    UmlTypeKind.VIEW_KIND,
                    viewKindName(viewKind),
                    path);
        }
    }

    private static void validateViewNodeType(GenericGraphViewKind viewKind, String nodeType, String path)
            throws UmlValidationException {
        boolean supported = switch (viewKind) {
            case GENERIC, ARCHIMATE -> true;
            case UML_CLASS, UML_DATA -> isStructuralType(nodeType);
            case UML_ACTIVITY -> isActivityType(nodeType);
            case UML_SEQUENCE -> false;
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
}
