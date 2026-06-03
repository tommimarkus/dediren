package dev.dediren.plugins.genericgraph;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.archimate.Archimate;
import dev.dediren.archimate.ArchimateJunctionValidationException;
import dev.dediren.archimate.ArchimateTypeValidationException;
import dev.dediren.archimate.JunctionValidationNode;
import dev.dediren.archimate.JunctionValidationRelationship;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutLabel;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.SemanticValidationResult;
import dev.dediren.contracts.plugin.RuntimeCapabilities;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewGroup;
import dev.dediren.contracts.source.GenericGraphViewGroupRole;
import dev.dediren.contracts.source.PluginRequirement;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.uml.Uml;
import dev.dediren.uml.UmlValidationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Main {
    private static final double UML_STRUCTURAL_MIN_WIDTH = 220.0;
    private static final double UML_STRUCTURAL_MIN_HEIGHT = 120.0;
    private static final double UML_TEXT_CHAR_WIDTH = 8.0;
    private static final double UML_TEXT_HORIZONTAL_PADDING = 32.0;
    private static final double UML_TITLE_ROW_HEIGHT = 15.0;
    private static final double UML_TITLE_PADDING = 8.0;
    private static final double UML_MEMBER_ROW_HEIGHT = 14.0;
    private static final double UML_COMPARTMENT_PADDING = 8.0;
    private static final double UML_OPERATION_COMPARTMENT_EXTRA = 14.0;

    private Main() {
    }

    public static String moduleName() {
        return "generic-graph";
    }

    public static void main(String[] args) throws Exception {
        int exitCode = execute(args, System.in, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static PluginResult executeForTesting(String[] args, String stdin) throws Exception {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = execute(
                args,
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new PluginResult(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static int execute(String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr)
            throws Exception {
        if (args.length > 0 && args[0].equals("capabilities")) {
            stdout.println(JsonSupport.objectMapper().writeValueAsString(new RuntimeCapabilities(
                    ContractVersions.PLUGIN_PROTOCOL_VERSION,
                    "generic-graph",
                    List.of("semantic-validation", "projection"),
                    null)));
            return 0;
        }
        if (args.length == 0) {
            stderr.println("expected command: validate or project");
            return 2;
        }
        return switch (args[0]) {
            case "validate" -> validateFromStdin(args, stdin, stdout);
            case "project" -> projectFromStdin(args, stdin, stdout, stderr);
            default -> {
                stderr.println("expected command: validate or project");
                yield 2;
            }
        };
    }

    private static int validateFromStdin(String[] args, InputStream stdin, PrintStream stdout) throws Exception {
        String profile = valueAfter(args, "--profile");
        if (profile == null) {
            return exitWithDiagnostic(
                    stdout,
                    "DEDIREN_SEMANTIC_PROFILE_REQUIRED",
                    "semantic validation requires --profile",
                    null);
        }
        SourceDocument source = readSource(stdin);
        GenericGraphPluginData pluginData = genericGraphPluginData(source);
        GenericGraphValidationError graphError = validateGenericGraphPluginData(pluginData);
        if (graphError != null) {
            return exitWithDiagnostic(stdout, graphError.code(), graphError.message(), graphError.path());
        }

        switch (profile) {
            case "archimate" -> {
                int validationExit = validateArchimateSource(source, stdout);
                if (validationExit != 0) {
                    return validationExit;
                }
            }
            case "uml" -> {
                try {
                    Uml.validateSource(source, pluginData);
                } catch (UmlValidationException error) {
                    return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
                }
            }
            default -> {
                return exitWithDiagnostic(
                        stdout,
                        "DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED",
                        "unsupported semantic profile: " + profile,
                        "profile");
            }
        }

        stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(
                new SemanticValidationResult(
                        ContractVersions.SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION,
                        profile,
                        source.nodes().size(),
                        source.relationships().size()))));
        return 0;
    }

    private static int projectFromStdin(String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr)
            throws Exception {
        String target = valueAfter(args, "--target");
        String view = valueAfter(args, "--view");
        if (!Objects.equals(target, "layout-request") && !Objects.equals(target, "render-metadata")) {
            stderr.println("unsupported target: " + target);
            return 2;
        }
        if (view == null) {
            stderr.println("missing --view");
            return 2;
        }

        SourceDocument source = readSource(stdin);
        GenericGraphPluginData pluginData = genericGraphPluginData(source);
        GenericGraphValidationError graphError = validateGenericGraphPluginData(pluginData);
        if (graphError != null) {
            return exitWithDiagnostic(stdout, graphError.code(), graphError.message(), graphError.path());
        }
        GenericGraphView selectedView = pluginData.views().stream()
                .filter(candidate -> candidate.id().equals(view))
                .findFirst()
                .orElse(null);
        if (selectedView == null) {
            stderr.println("missing generic-graph view " + view);
            return 2;
        }

        String semanticProfile = sourceSemanticProfile(source, pluginData);
        if (semanticProfile.equals("archimate")) {
            int validationExit = validateArchimateSource(source, stdout);
            if (validationExit != 0) {
                return validationExit;
            }
        } else if (semanticProfile.equals("uml")) {
            try {
                Uml.validateSource(source, pluginData);
            } catch (UmlValidationException error) {
                return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
            }
        }

        if (target.equals("render-metadata")) {
            stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(
                    projectRenderMetadata(source, selectedView, semanticProfile))));
            return 0;
        }

        stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(
                projectLayoutRequest(source, selectedView, semanticProfile))));
        return 0;
    }

    private static SourceDocument readSource(InputStream stdin) throws IOException {
        return JsonSupport.objectMapper().readValue(stdin.readAllBytes(), SourceDocument.class);
    }

    private static GenericGraphPluginData genericGraphPluginData(SourceDocument source) throws IOException {
        JsonNode pluginValue = source.plugins().get("generic-graph");
        if (pluginValue == null) {
            throw new IOException("missing plugins.generic-graph");
        }
        return JsonSupport.objectMapper().treeToValue(pluginValue, GenericGraphPluginData.class);
    }

    private static GenericGraphValidationError validateGenericGraphPluginData(GenericGraphPluginData pluginData) {
        var viewIds = new java.util.TreeSet<String>();
        for (int viewIndex = 0; viewIndex < pluginData.views().size(); viewIndex++) {
            GenericGraphView view = pluginData.views().get(viewIndex);
            if (!viewIds.add(view.id())) {
                return new GenericGraphValidationError(
                        "DEDIREN_GENERIC_GRAPH_DUPLICATE_VIEW_ID",
                        "duplicate generic-graph view id '" + view.id() + "'",
                        "$.plugins.generic-graph.views[" + viewIndex + "].id");
            }

            var groupIds = new java.util.TreeSet<String>();
            for (int groupIndex = 0; groupIndex < view.groups().size(); groupIndex++) {
                GenericGraphViewGroup group = view.groups().get(groupIndex);
                if (!groupIds.add(group.id())) {
                    return new GenericGraphValidationError(
                            "DEDIREN_GENERIC_GRAPH_DUPLICATE_GROUP_ID",
                            "duplicate generic-graph group id '" + group.id() + "' in view '" + view.id() + "'",
                            "$.plugins.generic-graph.views[" + viewIndex + "].groups[" + groupIndex + "].id");
                }
            }
        }
        return null;
    }

    private static int validateArchimateSource(SourceDocument source, PrintStream stdout) throws Exception {
        try {
            validateArchimateSourceTypes(source);
            validateArchimateJunctionSemantics(source);
            return 0;
        } catch (ArchimateTypeValidationException error) {
            return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
        } catch (ArchimateJunctionValidationException error) {
            return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
        }
    }

    private static void validateArchimateSourceTypes(SourceDocument source)
            throws ArchimateTypeValidationException {
        var nodeTypes = new LinkedHashMap<String, String>();
        for (int nodeIndex = 0; nodeIndex < source.nodes().size(); nodeIndex++) {
            SourceNode node = source.nodes().get(nodeIndex);
            Archimate.validateElementType(node.type(), "$.nodes[" + nodeIndex + "].type");
            nodeTypes.put(node.id(), node.type());
        }
        for (int relationshipIndex = 0; relationshipIndex < source.relationships().size(); relationshipIndex++) {
            SourceRelationship relationship = source.relationships().get(relationshipIndex);
            Archimate.validateRelationshipType(
                    relationship.type(),
                    "$.relationships[" + relationshipIndex + "].type");
            String sourceType = nodeTypes.get(relationship.source());
            String targetType = nodeTypes.get(relationship.target());
            if (sourceType == null || targetType == null) {
                continue;
            }
            Archimate.validateRelationshipEndpointTypes(
                    relationship.type(),
                    sourceType,
                    targetType,
                    "$.relationships[" + relationshipIndex + "]");
        }
    }

    private static void validateArchimateJunctionSemantics(SourceDocument source)
            throws ArchimateJunctionValidationException {
        var nodes = new ArrayList<JunctionValidationNode>();
        for (int index = 0; index < source.nodes().size(); index++) {
            SourceNode node = source.nodes().get(index);
            nodes.add(new JunctionValidationNode(node.id(), node.type(), "$.nodes[" + index + "]"));
        }
        var relationships = source.relationships().stream()
                .map(relationship -> new JunctionValidationRelationship(
                        relationship.type(),
                        relationship.source(),
                        relationship.target()))
                .toList();
        Archimate.validateJunctionRelationshipSemantics(nodes, relationships);
    }

    private static RenderMetadata projectRenderMetadata(
            SourceDocument source,
            GenericGraphView selectedView,
            String semanticProfile) throws IOException {
        var nodes = new LinkedHashMap<String, RenderMetadataSelector>();
        for (String id : selectedView.nodes()) {
            SourceNode sourceNode = source.nodes().stream()
                    .filter(node -> node.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IOException("view references missing node " + id));
            nodes.put(sourceNode.id(), new RenderMetadataSelector(
                    sourceNode.type(),
                    sourceNode.id(),
                    semanticProfile.equals("uml") ? sourceNode.properties().get("uml") : null));
        }

        var edges = new LinkedHashMap<String, RenderMetadataSelector>();
        for (String id : selectedView.relationships()) {
            SourceRelationship relationship = source.relationships().stream()
                    .filter(candidate -> candidate.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IOException("view references missing relationship " + id));
            edges.put(relationship.id(), new RenderMetadataSelector(
                    relationship.type(),
                    relationship.id(),
                    null));
        }

        var groups = new LinkedHashMap<String, RenderMetadataSelector>();
        for (GenericGraphViewGroup group : selectedView.groups()) {
            if (group.role() != GenericGraphViewGroupRole.SEMANTIC_BOUNDARY || group.semanticSourceId() == null) {
                continue;
            }
            SourceNode sourceNode = source.nodes().stream()
                    .filter(node -> node.id().equals(group.semanticSourceId()))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "group " + group.id() + " references missing semantic source " + group.semanticSourceId()));
            groups.put(group.id(), new RenderMetadataSelector(sourceNode.type(), sourceNode.id(), null));
        }

        return new RenderMetadata(
                ContractVersions.RENDER_METADATA_SCHEMA_VERSION,
                semanticProfile,
                nodes,
                edges,
                groups);
    }

    private static LayoutRequest projectLayoutRequest(
            SourceDocument source,
            GenericGraphView selectedView,
            String semanticProfile) throws IOException {
        var nodes = new ArrayList<LayoutNode>();
        for (String id : selectedView.nodes()) {
            SourceNode sourceNode = source.nodes().stream()
                    .filter(node -> node.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IOException("view references missing node " + id));
            nodes.add(new LayoutNode(
                    sourceNode.id(),
                    sourceNode.label(),
                    sourceNode.id(),
                    layoutWidthHint(semanticProfile, sourceNode),
                    layoutHeightHint(semanticProfile, sourceNode)));
        }

        var edges = new ArrayList<LayoutEdge>();
        for (String id : selectedView.relationships()) {
            SourceRelationship relationship = source.relationships().stream()
                    .filter(candidate -> candidate.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IOException("view references missing relationship " + id));
            edges.add(new LayoutEdge(
                    relationship.id(),
                    relationship.source(),
                    relationship.target(),
                    relationship.label(),
                    relationship.id(),
                    relationship.type()));
        }

        var sourceNodeIds = source.nodes().stream().map(SourceNode::id).collect(java.util.stream.Collectors.toSet());
        var groups = new ArrayList<LayoutGroup>();
        for (GenericGraphViewGroup group : selectedView.groups()) {
            for (String member : group.members()) {
                if (!selectedView.nodes().contains(member)) {
                    throw new IOException("group " + group.id() + " references node outside view: " + member);
                }
            }
            GroupProvenance provenance;
            if (group.role() == GenericGraphViewGroupRole.LAYOUT_ONLY) {
                provenance = GroupProvenance.visualOnlyGroup();
            } else {
                String sourceId = group.semanticSourceId() == null ? group.id() : group.semanticSourceId();
                if (group.semanticSourceId() != null && !sourceNodeIds.contains(sourceId)) {
                    throw new IOException("group " + group.id() + " semantic_source_id references missing node: " + sourceId);
                }
                provenance = GroupProvenance.semanticBacked(sourceId);
            }
            groups.add(new LayoutGroup(group.id(), group.label(), group.members(), provenance));
        }

        var labels = nodes.stream()
                .map(node -> new LayoutLabel(node.id(), node.label()))
                .toList();
        return new LayoutRequest(
                ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
                selectedView.id(),
                nodes,
                edges,
                groups,
                labels,
                List.of(),
                selectedView.layoutPreferences());
    }

    private static String sourceSemanticProfile(SourceDocument source, GenericGraphPluginData pluginData) {
        if (pluginData.semanticProfile() != null) {
            return profileName(pluginData.semanticProfile());
        }
        boolean archimateRequired = source.requiredPlugins().stream()
                .map(PluginRequirement::id)
                .anyMatch("archimate-oef"::equals);
        if (archimateRequired || source.plugins().containsKey("archimate-oef")) {
            return "archimate";
        }
        return "generic-graph";
    }

    private static String profileName(GenericGraphSemanticProfile profile) {
        return switch (profile) {
            case GENERIC_GRAPH -> "generic-graph";
            case ARCHIMATE -> "archimate";
            case UML -> "uml";
        };
    }

    private static double layoutWidthHint(String semanticProfile, SourceNode sourceNode) {
        if (semanticProfile.equals("archimate") && Archimate.isRelationshipConnectorType(sourceNode.type())) {
            return 28.0;
        }
        if (semanticProfile.equals("uml") && Uml.isCompactActivityNodeType(sourceNode.type())) {
            return 32.0;
        }
        if (semanticProfile.equals("uml") && isLargeUmlStructuralNodeType(sourceNode.type())) {
            return umlStructuralWidthHint(sourceNode);
        }
        return 160.0;
    }

    private static double layoutHeightHint(String semanticProfile, SourceNode sourceNode) {
        if (semanticProfile.equals("archimate") && Archimate.isRelationshipConnectorType(sourceNode.type())) {
            return 28.0;
        }
        if (semanticProfile.equals("uml") && Uml.isCompactActivityNodeType(sourceNode.type())) {
            return 32.0;
        }
        if (semanticProfile.equals("uml") && isLargeUmlStructuralNodeType(sourceNode.type())) {
            return umlStructuralHeightHint(sourceNode);
        }
        return 80.0;
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
                    .map(Main::umlAttributeLine)
                    .map(String::length)
                    .forEach(lengths::add);
            umlArrayValues(properties, "operations").stream()
                    .map(Main::umlOperationLine)
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
                .map(Main::umlParameterText)
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

    private static int exitWithDiagnostic(PrintStream stdout, String code, String message, String path)
            throws IOException {
        var diagnostic = new Diagnostic(code, DiagnosticSeverity.ERROR, message, path);
        stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
        return 3;
    }

    private static String valueAfter(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return null;
    }

    private record GenericGraphValidationError(String code, String message, String path) {
    }
}
