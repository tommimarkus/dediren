package dev.dediren.plugins.genericgraph;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.archimate.Archimate;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.uml.Uml;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GenericGraphLayoutSizing {
    private static final double UML_STRUCTURAL_MIN_WIDTH = 220.0;
    private static final double UML_STRUCTURAL_MIN_HEIGHT = 120.0;
    private static final double UML_TEXT_CHAR_WIDTH = 8.0;
    private static final double UML_TEXT_HORIZONTAL_PADDING = 32.0;
    private static final double UML_TITLE_ROW_HEIGHT = 15.0;
    private static final double UML_TITLE_PADDING = 8.0;
    private static final double UML_MEMBER_ROW_HEIGHT = 14.0;
    private static final double UML_COMPARTMENT_PADDING = 8.0;
    private static final double UML_OPERATION_COMPARTMENT_EXTRA = 14.0;

    private static final double ARCHIMATE_MIN_WIDTH = 160.0;
    private static final double ARCHIMATE_MIN_HEIGHT = 80.0;
    private static final double ARCHIMATE_TEXT_CHAR_WIDTH = 8.7;
    // Must equal ARCHIMATE_LABEL_ICON_RESERVE in plugins/svg-render Main: per-side
    // room reserved so a centered label clears the upper-right type icon.
    private static final double ARCHIMATE_LABEL_ICON_RESERVE = 34.0;
    private static final double ARCHIMATE_LINE_HEIGHT = 18.0;
    private static final double ARCHIMATE_VERTICAL_PADDING = 28.0;

    private GenericGraphLayoutSizing() {
    }

    static double widthHint(String semanticProfile, SourceNode sourceNode) {
        if (semanticProfile.equals("archimate") && Archimate.isRelationshipConnectorType(sourceNode.type())) {
            return 28.0;
        }
        if (semanticProfile.equals("archimate")) {
            return archimateWidthHint(sourceNode);
        }
        if (semanticProfile.equals("uml") && Uml.isCompactActivityNodeType(sourceNode.type())) {
            return 32.0;
        }
        Double umlSequenceHint = umlSequenceWidthHint(sourceNode.type());
        if (semanticProfile.equals("uml") && umlSequenceHint != null) {
            return umlSequenceHint;
        }
        Double umlStateMachineHint = umlStateMachineWidthHint(sourceNode.type());
        if (semanticProfile.equals("uml") && umlStateMachineHint != null) {
            return umlStateMachineHint;
        }
        Double umlUseCaseHint = umlUseCaseWidthHint(sourceNode.type());
        if (semanticProfile.equals("uml") && umlUseCaseHint != null) {
            return umlUseCaseHint;
        }
        Double umlComponentHint = umlComponentWidthHint(sourceNode.type());
        if (semanticProfile.equals("uml") && umlComponentHint != null) {
            return umlComponentHint;
        }
        Double umlDeploymentHint = umlDeploymentWidthHint(sourceNode.type());
        if (semanticProfile.equals("uml") && umlDeploymentHint != null) {
            return umlDeploymentHint;
        }
        if (semanticProfile.equals("uml") && isLargeUmlStructuralNodeType(sourceNode.type())) {
            return umlStructuralWidthHint(sourceNode);
        }
        return 160.0;
    }

    static double heightHint(String semanticProfile, SourceNode sourceNode) {
        if (semanticProfile.equals("archimate") && Archimate.isRelationshipConnectorType(sourceNode.type())) {
            return 28.0;
        }
        if (semanticProfile.equals("archimate")) {
            return archimateHeightHint(sourceNode);
        }
        if (semanticProfile.equals("uml") && Uml.isCompactActivityNodeType(sourceNode.type())) {
            return 32.0;
        }
        Double umlSequenceHint = umlSequenceHeightHint(sourceNode.type());
        if (semanticProfile.equals("uml") && umlSequenceHint != null) {
            return umlSequenceHint;
        }
        Double umlStateMachineHint = umlStateMachineHeightHint(sourceNode.type());
        if (semanticProfile.equals("uml") && umlStateMachineHint != null) {
            return umlStateMachineHint;
        }
        Double umlUseCaseHint = umlUseCaseHeightHint(sourceNode.type());
        if (semanticProfile.equals("uml") && umlUseCaseHint != null) {
            return umlUseCaseHint;
        }
        Double umlComponentHint = umlComponentHeightHint(sourceNode.type());
        if (semanticProfile.equals("uml") && umlComponentHint != null) {
            return umlComponentHint;
        }
        Double umlDeploymentHint = umlDeploymentHeightHint(sourceNode.type());
        if (semanticProfile.equals("uml") && umlDeploymentHint != null) {
            return umlDeploymentHint;
        }
        if (semanticProfile.equals("uml") && isLargeUmlStructuralNodeType(sourceNode.type())) {
            return umlStructuralHeightHint(sourceNode);
        }
        return 80.0;
    }

    private static double archimateWidthHint(SourceNode sourceNode) {
        double content = archimateLongestTokenChars(sourceNode.label()) * ARCHIMATE_TEXT_CHAR_WIDTH
                + 2.0 * ARCHIMATE_LABEL_ICON_RESERVE;
        return roundUp(Math.max(content, ARCHIMATE_MIN_WIDTH), 10.0);
    }

    private static double archimateHeightHint(SourceNode sourceNode) {
        double width = archimateWidthHint(sourceNode);
        double widthBudget = width - 2.0 * ARCHIMATE_LABEL_ICON_RESERVE;
        double content = archimateEstimatedLineCount(sourceNode.label(), widthBudget) * ARCHIMATE_LINE_HEIGHT
                + ARCHIMATE_VERTICAL_PADDING;
        return roundUp(Math.max(content, ARCHIMATE_MIN_HEIGHT), 10.0);
    }

    private static int archimateLongestTokenChars(String label) {
        int longest = 0;
        for (String token : label.trim().split("\\s+")) {
            longest = Math.max(longest, token.length());
        }
        return Math.max(longest, 1);
    }

    private static int archimateEstimatedLineCount(String label, double widthBudget) {
        if (widthBudget <= 0.0) {
            return 1;
        }
        double total = label.trim().length() * ARCHIMATE_TEXT_CHAR_WIDTH;
        return Math.max(1, (int) Math.ceil(total / widthBudget));
    }

    private static Double umlSequenceWidthHint(String nodeType) {
        return switch (nodeType) {
            case "Interaction" -> 360.0;
            case "Lifeline" -> 140.0;
            case "ExecutionSpecification" -> 16.0;
            case "Gate", "DestructionOccurrenceSpecification" -> 24.0;
            default -> null;
        };
    }

    private static Double umlSequenceHeightHint(String nodeType) {
        return switch (nodeType) {
            case "Interaction" -> 260.0;
            case "Lifeline" -> 48.0;
            case "ExecutionSpecification" -> 72.0;
            case "Gate", "DestructionOccurrenceSpecification" -> 24.0;
            default -> null;
        };
    }

    private static Double umlStateMachineWidthHint(String nodeType) {
        return switch (nodeType) {
            case "State" -> 150.0;
            case "FinalState", "Pseudostate" -> 36.0;
            default -> null;
        };
    }

    private static Double umlStateMachineHeightHint(String nodeType) {
        return switch (nodeType) {
            case "State" -> 72.0;
            case "FinalState", "Pseudostate" -> 36.0;
            default -> null;
        };
    }

    private static Double umlUseCaseWidthHint(String nodeType) {
        return switch (nodeType) {
            case "Actor" -> 80.0;
            case "UseCase" -> 160.0;
            case "ExtensionPoint" -> 140.0;
            default -> null;
        };
    }

    private static Double umlUseCaseHeightHint(String nodeType) {
        return switch (nodeType) {
            case "Actor" -> 120.0;
            case "UseCase" -> 72.0;
            case "ExtensionPoint" -> 40.0;
            default -> null;
        };
    }

    private static Double umlComponentWidthHint(String nodeType) {
        return switch (nodeType) {
            case "Component" -> 180.0;
            case "Port" -> 32.0;
            default -> null;
        };
    }

    private static Double umlComponentHeightHint(String nodeType) {
        return switch (nodeType) {
            case "Component" -> 96.0;
            case "Port" -> 32.0;
            default -> null;
        };
    }

    private static Double umlDeploymentWidthHint(String nodeType) {
        return switch (nodeType) {
            case "Device", "Node" -> 200.0;
            case "ExecutionEnvironment" -> 180.0;
            case "Artifact" -> 150.0;
            case "DeploymentSpecification" -> 190.0;
            default -> null;
        };
    }

    private static Double umlDeploymentHeightHint(String nodeType) {
        return switch (nodeType) {
            case "Device", "Node" -> 120.0;
            case "ExecutionEnvironment" -> 96.0;
            case "Artifact", "DeploymentSpecification" -> 70.0;
            default -> null;
        };
    }

    private static boolean isLargeUmlStructuralNodeType(String nodeType) {
        return nodeType.equals("Class")
                || nodeType.equals("Interface")
                || nodeType.equals("DataType")
                || nodeType.equals("Enumeration");
    }

    private static double umlStructuralWidthHint(SourceNode sourceNode) {
        JsonNode properties = sourceNode.properties().get("uml");
        int maxChars = umlClassifierLineLengths(sourceNode.type(), sourceNode.label(), properties).stream()
                .max(Comparator.naturalOrder())
                .orElse(sourceNode.label().length());
        return roundUp(Math.max(maxChars * UML_TEXT_CHAR_WIDTH + UML_TEXT_HORIZONTAL_PADDING,
                UML_STRUCTURAL_MIN_WIDTH), 20.0);
    }

    private static double umlStructuralHeightHint(SourceNode sourceNode) {
        JsonNode properties = sourceNode.properties().get("uml");
        double titleHeight = umlTitleHeight(sourceNode.type());
        int attributeCount = sourceNode.type().equals("Enumeration")
                ? umlArrayLen(properties, "literals")
                : umlArrayLen(properties, "attributes");
        int operationCount = sourceNode.type().equals("Enumeration") ? 0 : umlArrayLen(properties, "operations");
        double operationExtra = operationCount > 0 ? UML_OPERATION_COMPARTMENT_EXTRA : 0.0;
        return roundUp(Math.max(
                titleHeight
                        + umlCompartmentHeight(attributeCount)
                        + umlCompartmentHeight(operationCount)
                        + operationExtra,
                UML_STRUCTURAL_MIN_HEIGHT), 10.0);
    }

    private static List<Integer> umlClassifierLineLengths(String nodeType, String label, JsonNode properties) {
        var lengths = new ArrayList<Integer>();
        Integer stereotypeLength = umlStereotypeCharCount(nodeType);
        if (stereotypeLength != null) {
            lengths.add(stereotypeLength);
        }
        lengths.add(label.length());
        if (nodeType.equals("Enumeration")) {
            umlArrayValues(properties, "literals").stream()
                    .filter(JsonNode::isTextual)
                    .map(value -> value.asText().length())
                    .forEach(lengths::add);
        } else {
            umlArrayValues(properties, "attributes").stream()
                    .map(GenericGraphLayoutSizing::umlAttributeLine)
                    .map(String::length)
                    .forEach(lengths::add);
            umlArrayValues(properties, "operations").stream()
                    .map(GenericGraphLayoutSizing::umlOperationLine)
                    .map(String::length)
                    .forEach(lengths::add);
        }
        return lengths;
    }

    private static double umlTitleHeight(String nodeType) {
        double titleLines = umlStereotypeCharCount(nodeType) == null ? 1.0 : 2.0;
        return Math.max(titleLines * UML_TITLE_ROW_HEIGHT + UML_TITLE_PADDING, 28.0);
    }

    private static Integer umlStereotypeCharCount(String nodeType) {
        return switch (nodeType) {
            case "Enumeration" -> 13;
            case "Interface" -> 11;
            case "DataType" -> 10;
            default -> null;
        };
    }

    private static double umlCompartmentHeight(int rowCount) {
        return rowCount == 0 ? 0.0 : rowCount * UML_MEMBER_ROW_HEIGHT + UML_COMPARTMENT_PADDING;
    }

    private static int umlArrayLen(JsonNode properties, String key) {
        return umlArrayValues(properties, key).size();
    }

    private static List<JsonNode> umlArrayValues(JsonNode properties, String key) {
        JsonNode value = properties == null ? null : properties.get(key);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        var values = new ArrayList<JsonNode>();
        value.forEach(values::add);
        return values;
    }

    private static String umlAttributeLine(JsonNode attribute) {
        String visibility = umlVisibilitySymbol(attribute.path("visibility").asText(null));
        String name = attribute.path("name").asText("");
        String attributeType = attribute.path("type").asText("");
        return attributeType.isEmpty() ? visibility + " " + name : visibility + " " + name + " : " + attributeType;
    }

    private static String umlOperationLine(JsonNode operation) {
        String visibility = umlVisibilitySymbol(operation.path("visibility").asText(null));
        String name = operation.path("name").asText("");
        String returnType = operation.path("return_type").asText("");
        String parameters = umlArrayValues(operation, "parameters").stream()
                .map(GenericGraphLayoutSizing::umlParameterText)
                .collect(java.util.stream.Collectors.joining(", "));
        return returnType.isEmpty()
                ? visibility + " " + name + "(" + parameters + ")"
                : visibility + " " + name + "(" + parameters + ") : " + returnType;
    }

    private static String umlParameterText(JsonNode parameter) {
        String name = parameter.path("name").asText("");
        String parameterType = parameter.path("type").asText("");
        if (parameterType.isEmpty()) {
            return name;
        }
        if (name.isEmpty()) {
            return parameterType;
        }
        return name + " : " + parameterType;
    }

    private static String umlVisibilitySymbol(String visibility) {
        return switch (visibility == null ? "" : visibility) {
            case "private" -> "-";
            case "protected" -> "#";
            case "package" -> "~";
            default -> "+";
        };
    }

    private static double roundUp(double value, double step) {
        return Math.ceil(value / step) * step;
    }
}
