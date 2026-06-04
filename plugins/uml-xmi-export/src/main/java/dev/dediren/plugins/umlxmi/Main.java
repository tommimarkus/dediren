package dev.dediren.plugins.umlxmi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.export.UmlXmiExportPolicy;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.schemacache.SchemaCacheException;
import dev.dediren.schemacache.SchemaCacheModule;
import dev.dediren.uml.Uml;
import dev.dediren.uml.UmlValidationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

public final class Main {
    private static final String XMI_NS = "http://www.omg.org/spec/XMI/20131001";
    private static final String UML_NS = "http://www.omg.org/spec/UML/20161101";
    private static final String XMI_VERSION = "2.5.1";
    private static final String UML_VERSION = "2.5.1";
    private static final String XMI_SCHEMA_VALIDATOR = "xmllint";
    private static final String OMG_XMI_SCHEMA_URL = "https://www.omg.org/spec/XMI/20131001/XMI.xsd";
    private static final String XMI_SCHEMA_PATH_ENV = "DEDIREN_XMI_SCHEMA_PATH";
    private static final String SCHEMA_CACHE_DIR_ENV = "DEDIREN_SCHEMA_CACHE_DIR";
    private static final String SCHEMA_FETCHER = "curl";
    private static final String UNSUPPORTED_SEQUENCE_MESSAGE_ENDPOINT =
            "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_ENDPOINT_UNSUPPORTED";
    private static final String MISSING_SEQUENCE_MESSAGE_INTERACTION =
            "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_MISSING";
    private static final String UNSUPPORTED_SEQUENCE_MESSAGE_INTERACTION =
            "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_UNSUPPORTED";
    private static final String UNSUPPORTED_SEQUENCE_NODE = "DEDIREN_UML_XMI_SEQUENCE_NODE_UNSUPPORTED";

    private Main() {
    }

    public static String moduleName() {
        return "uml-xmi-export";
    }

    public static void main(String[] args) throws Exception {
        int exitCode = execute(args, System.in, System.out, System.err, System.getenv());
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static PluginResult executeForTesting(String[] args, String stdin, Map<String, String> env)
            throws Exception {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = execute(
                args,
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                env);
        return new PluginResult(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static int execute(
            String[] args,
            InputStream stdin,
            PrintStream stdout,
            PrintStream stderr,
            Map<String, String> env) throws Exception {
        if (args.length > 0 && args[0].equals("capabilities")) {
            stdout.println(capabilitiesJson());
            return 0;
        }
        if (args.length > 0 && args[0].equals("export")) {
            return exportFromStdin(stdin, stdout, env);
        }
        stderr.println("expected command: capabilities or export");
        return 2;
    }

    private static String capabilitiesJson() throws IOException {
        ObjectNode root = JsonSupport.objectMapper().createObjectNode();
        root.put("plugin_protocol_version", ContractVersions.PLUGIN_PROTOCOL_VERSION);
        root.put("id", "uml-xmi");
        root.set("capabilities", JsonSupport.objectMapper().createArrayNode().add("export"));
        ObjectNode runtime = root.putObject("runtime");
        runtime.put("artifact_kind", "uml-xmi+xml");
        runtime.put("uml_version", UML_VERSION);
        runtime.put("xmi_version", XMI_VERSION);
        ObjectNode schemaValidation = runtime.putObject("schema_validation");
        schemaValidation.put("kind", "omg-xmi-xsd-partial");
        schemaValidation.put("schema_version", XMI_VERSION);
        schemaValidation.put("validator", XMI_SCHEMA_VALIDATOR);
        schemaValidation.put("available", commandAvailable(XMI_SCHEMA_VALIDATOR));
        schemaValidation.put("schema_source", "DEDIREN_XMI_SCHEMA_PATH or runtime cache download");
        schemaValidation.put("schema_path_env", XMI_SCHEMA_PATH_ENV);
        schemaValidation.put("cache_dir_env", SCHEMA_CACHE_DIR_ENV);
        schemaValidation.put("fetcher", SCHEMA_FETCHER);
        schemaValidation.put("limitation", "UML 2.5.1 is published as an XMI metamodel, not an importable XML Schema");
        return JsonSupport.objectMapper().writeValueAsString(root);
    }

    private static boolean commandAvailable(String command) {
        try {
            return new ProcessBuilder(command, "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static int exportFromStdin(InputStream stdin, PrintStream stdout, Map<String, String> env)
            throws Exception {
        ExportRequest request = JsonSupport.objectMapper().readValue(stdin.readAllBytes(), ExportRequest.class);
        UmlXmiExportPolicy policy;
        try {
            validatePolicy(request.policy());
            policy = JsonSupport.objectMapper().treeToValue(request.policy(), UmlXmiExportPolicy.class);
        } catch (IllegalArgumentException error) {
            return exitWithDiagnostic(stdout, "DEDIREN_UML_XMI_POLICY_INVALID", error.getMessage(), "policy");
        }

        GenericGraphPluginData pluginData;
        try {
            pluginData = genericGraphPluginData(request);
            Uml.validateSource(request.source(), pluginData);
        } catch (UmlValidationException error) {
            return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
        }
        try {
            validateExportableSequenceScope(request);
        } catch (XmiExportException error) {
            return exitWithDiagnostic(stdout, error.code(), error.getMessage(), error.path());
        }

        String content = buildXmi(request, policy);
        try {
            validateXmiToAvailableStandards(content, env);
        } catch (XmiValidationException error) {
            return exitWithDiagnostic(stdout, error.code(), error.getMessage(), "content");
        }

        stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(
                new ExportResult(
                        ContractVersions.EXPORT_RESULT_SCHEMA_VERSION,
                        "uml-xmi+xml",
                        content))));
        return 0;
    }

    private static GenericGraphPluginData genericGraphPluginData(ExportRequest request) {
        JsonNode value = request.source().plugins().get("generic-graph");
        if (value == null) {
            throw new IllegalArgumentException("source is missing plugins.generic-graph");
        }
        return JsonSupport.objectMapper().convertValue(value, GenericGraphPluginData.class);
    }

    private static void validatePolicy(JsonNode policy) {
        if (policy == null || !policy.isObject()) {
            throw new IllegalArgumentException("policy must be an object");
        }
        for (String field : List.of("uml_xmi_export_policy_schema_version", "model_identifier", "model_name")) {
            if (!policy.hasNonNull(field) || !policy.get(field).isTextual()) {
                throw new IllegalArgumentException("policy missing required string field " + field);
            }
        }
        if (policy.has("xmi_version") && !policy.get("xmi_version").asText().equals(XMI_VERSION)) {
            throw new IllegalArgumentException("xmi_version must be " + XMI_VERSION);
        }
        if (policy.has("uml_version") && !policy.get("uml_version").asText().equals(UML_VERSION)) {
            throw new IllegalArgumentException("uml_version must be " + UML_VERSION);
        }
    }

    private static String buildXmi(ExportRequest request, UmlXmiExportPolicy policy) {
        var ids = new IdentifierMap(policy.modelIdentifier());
        ExportScope scope = ExportScope.fromRequest(request);
        var selectedNodes = request.source().nodes().stream()
                .filter(node -> scope.nodeIds().contains(node.id()))
                .toList();
        var selectedRelationships = request.source().relationships().stream()
                .filter(relationship -> scope.relationshipIds().contains(relationship.id()))
                .toList();
        var nodeIds = new HashMap<String, String>();
        selectedNodes.forEach(node -> nodeIds.put(node.id(), ids.xmiId(node.id())));
        var relationshipIds = new HashMap<String, String>();
        selectedRelationships.forEach(relationship -> relationshipIds.put(relationship.id(), ids.xmiId(relationship.id())));

        StringBuilder xml = new StringBuilder();
        xml.append("<xmi:XMI xmlns:xmi=\"").append(XMI_NS).append("\" xmlns:uml=\"").append(UML_NS).append("\">");
        xml.append("<uml:Model xmi:id=\"").append(attr(policy.modelIdentifier())).append("\" name=\"")
                .append(attr(policy.modelName())).append("\">");
        for (SourceNode node : selectedNodes) {
            String elementId = nodeIds.get(node.id());
            switch (node.type()) {
                case "Package" -> writeEmptyPackagedElement(xml, "uml:Package", node, elementId);
                case "Class" -> writeClassifier(xml, ids, "uml:Class", node, elementId);
                case "Interface" -> writeClassifier(xml, ids, "uml:Interface", node, elementId);
                case "DataType" -> writeClassifier(xml, ids, "uml:DataType", node, elementId);
                case "Enumeration" -> writeEnumeration(xml, ids, node, elementId);
                case "Activity" -> writeActivity(xml, node, elementId, request.source().nodes(), selectedRelationships, nodeIds, relationshipIds);
                case "Interaction" -> writeInteraction(
                        xml,
                        ids,
                        node,
                        elementId,
                        request.source().nodes(),
                        selectedRelationships,
                        nodeIds,
                        relationshipIds);
                default -> {
                }
            }
        }
        xml.append("</uml:Model></xmi:XMI>\n");
        return xml.toString();
    }

    private static void writeEmptyPackagedElement(StringBuilder xml, String umlType, SourceNode node, String elementId) {
        xml.append("<packagedElement xmi:type=\"").append(umlType).append("\" xmi:id=\"").append(attr(elementId))
                .append("\" name=\"").append(attr(node.label())).append("\"/>");
    }

    private static void writeClassifier(StringBuilder xml, IdentifierMap ids, String umlType, SourceNode node, String elementId) {
        xml.append("<packagedElement xmi:type=\"").append(umlType).append("\" xmi:id=\"").append(attr(elementId))
                .append("\" name=\"").append(attr(node.label())).append("\">");
        for (JsonNode attribute : umlArray(node, "attributes")) {
            writeOwnedAttribute(xml, ids, node, attribute);
        }
        for (JsonNode operation : umlArray(node, "operations")) {
            writeOwnedOperation(xml, ids, node, operation);
        }
        xml.append("</packagedElement>");
    }

    private static void writeOwnedAttribute(StringBuilder xml, IdentifierMap ids, SourceNode node, JsonNode attribute) {
        String name = textField(attribute, "name", "attribute");
        String id = ids.xmiId(node.id() + "-" + name);
        String type = textField(attribute, "type", "String");
        String visibility = textField(attribute, "visibility", "public");
        String[] bounds = multiplicityBounds(textField(attribute, "multiplicity", "1"));
        xml.append("<ownedAttribute xmi:id=\"").append(attr(id))
                .append("\" name=\"").append(attr(name))
                .append("\" type=\"").append(attr(type))
                .append("\" visibility=\"").append(attr(visibility))
                .append("\" lowerValue=\"").append(attr(bounds[0]))
                .append("\" upperValue=\"").append(attr(bounds[1]))
                .append("\"/>");
    }

    private static void writeOwnedOperation(StringBuilder xml, IdentifierMap ids, SourceNode node, JsonNode operation) {
        String name = textField(operation, "name", "operation");
        String id = ids.xmiId(node.id() + "-" + name);
        String visibility = textField(operation, "visibility", "public");
        xml.append("<ownedOperation xmi:id=\"").append(attr(id))
                .append("\" name=\"").append(attr(name))
                .append("\" visibility=\"").append(attr(visibility))
                .append("\"/>");
    }

    private static void writeEnumeration(StringBuilder xml, IdentifierMap ids, SourceNode node, String elementId) {
        xml.append("<packagedElement xmi:type=\"uml:Enumeration\" xmi:id=\"").append(attr(elementId))
                .append("\" name=\"").append(attr(node.label())).append("\">");
        for (JsonNode literal : umlArray(node, "literals")) {
            if (!literal.isTextual()) {
                continue;
            }
            String name = literal.asText();
            String id = ids.xmiId(node.id() + "-" + name);
            xml.append("<ownedLiteral xmi:id=\"").append(attr(id))
                    .append("\" name=\"").append(attr(name)).append("\"/>");
        }
        xml.append("</packagedElement>");
    }

    private static void writeInteraction(
            StringBuilder xml,
            IdentifierMap ids,
            SourceNode interaction,
            String interactionId,
            List<SourceNode> sourceNodes,
            List<SourceRelationship> selectedRelationships,
            Map<String, String> nodeIds,
            Map<String, String> relationshipIds) {
        xml.append("<packagedElement xmi:type=\"uml:Interaction\" xmi:id=\"").append(attr(interactionId))
                .append("\" name=\"").append(attr(interaction.label())).append("\">");
        List<MessageExport> messages = sequenceMessages(
                ids,
                interaction,
                selectedRelationships,
                nodeIds,
                relationshipIds);
        var messageEndpointIds = new HashSet<String>();
        for (MessageExport message : messages) {
            messageEndpointIds.add(message.sourceNodeId());
            messageEndpointIds.add(message.targetNodeId());
        }
        for (SourceNode node : sourceNodes) {
            String nodeId = nodeIds.get(node.id());
            if (nodeId != null
                    && node.type().equals("Lifeline")
                    && (interaction.id().equals(umlString(node, "interaction"))
                            || messageEndpointIds.contains(nodeId))) {
                xml.append("<lifeline xmi:id=\"").append(attr(nodeId))
                        .append("\" name=\"").append(attr(node.label())).append("\"/>");
            }
        }
        for (MessageExport message : messages) {
            writeMessageOccurrence(xml, message, "send", message.sourceEventId(), message.sourceNodeId());
            writeMessageOccurrence(xml, message, "receive", message.receiveEventId(), message.targetNodeId());
        }
        for (MessageExport message : messages) {
            writeSequenceMessage(xml, message);
        }
        xml.append("</packagedElement>");
    }

    private static List<MessageExport> sequenceMessages(
            IdentifierMap ids,
            SourceNode interaction,
            List<SourceRelationship> selectedRelationships,
            Map<String, String> nodeIds,
            Map<String, String> relationshipIds) {
        var sourceOrder = new HashMap<String, Integer>();
        for (int index = 0; index < selectedRelationships.size(); index++) {
            sourceOrder.put(selectedRelationships.get(index).id(), index);
        }
        return selectedRelationships.stream()
                .filter(relationship -> relationship.type().equals("Message"))
                .filter(relationship -> interaction.id().equals(umlString(relationship, "interaction")))
                .filter(relationship -> relationshipIds.containsKey(relationship.id()))
                .map(relationship -> {
                    String sourceNodeId = nodeIds.get(relationship.source());
                    String targetNodeId = nodeIds.get(relationship.target());
                    if (sourceNodeId == null || targetNodeId == null) {
                        return null;
                    }
                    return new MessageExport(
                            relationship,
                            relationshipIds.get(relationship.id()),
                            sourceNodeId,
                            targetNodeId,
                            ids.xmiId(relationship.id() + "-send-event"),
                            ids.xmiId(relationship.id() + "-receive-event"),
                            umlSequence(relationship),
                            sourceOrder.get(relationship.id()),
                            Optional.ofNullable(umlString(relationship, "message_sort")).orElse("synchCall"));
                })
                .filter(message -> message != null)
                .sorted(Comparator.comparing(MessageExport::sequence)
                        .thenComparingInt(MessageExport::sourceOrder))
                .toList();
    }

    private static void writeMessageOccurrence(
            StringBuilder xml,
            MessageExport message,
            String kind,
            String eventId,
            String coveredNodeId) {
        xml.append("<fragment xmi:type=\"uml:MessageOccurrenceSpecification\" xmi:id=\"")
                .append(attr(eventId))
                .append("\" name=\"").append(attr(message.relationship().label())).append(" ").append(kind)
                .append("\" covered=\"").append(attr(coveredNodeId))
                .append("\" message=\"").append(attr(message.messageId()))
                .append("\"/>");
    }

    private static void writeSequenceMessage(StringBuilder xml, MessageExport message) {
        xml.append("<message xmi:id=\"").append(attr(message.messageId()))
                .append("\" name=\"").append(attr(message.relationship().label()))
                .append("\" messageSort=\"").append(attr(message.messageSort()))
                .append("\" sendEvent=\"").append(attr(message.sourceEventId()))
                .append("\" receiveEvent=\"").append(attr(message.receiveEventId()))
                .append("\"/>");
    }

    private static void writeActivity(
            StringBuilder xml,
            SourceNode activity,
            String activityId,
            List<SourceNode> sourceNodes,
            List<SourceRelationship> selectedRelationships,
            Map<String, String> nodeIds,
            Map<String, String> relationshipIds) {
        xml.append("<packagedElement xmi:type=\"uml:Activity\" xmi:id=\"").append(attr(activityId))
                .append("\" name=\"").append(attr(activity.label())).append("\">");
        for (SourceNode node : sourceNodes) {
            if (nodeIds.containsKey(node.id()) && activity.id().equals(umlString(node, "activity"))) {
                writeActivityNode(xml, node, nodeIds.get(node.id()));
            }
        }
        for (SourceRelationship relationship : selectedRelationships) {
            String sourceId = nodeIds.get(relationship.source());
            String targetId = nodeIds.get(relationship.target());
            String relationshipId = relationshipIds.get(relationship.id());
            if (sourceId != null && targetId != null && relationshipId != null) {
                writeActivityEdge(xml, relationship, relationshipId, sourceId, targetId);
            }
        }
        xml.append("</packagedElement>");
    }

    private static void writeActivityNode(StringBuilder xml, SourceNode node, String nodeId) {
        xml.append("<node xmi:type=\"").append(activityNodeXmiType(node.type()))
                .append("\" xmi:id=\"").append(attr(nodeId)).append("\"");
        if (!node.label().isEmpty()) {
            xml.append(" name=\"").append(attr(node.label())).append("\"");
        }
        String objectType = umlString(node, "type");
        if (objectType != null) {
            xml.append(" type=\"").append(attr(objectType)).append("\"");
        }
        xml.append("/>");
    }

    private static void writeActivityEdge(
            StringBuilder xml,
            SourceRelationship relationship,
            String relationshipId,
            String sourceId,
            String targetId) {
        xml.append("<edge xmi:type=\"").append(activityEdgeXmiType(relationship.type()))
                .append("\" xmi:id=\"").append(attr(relationshipId)).append("\"");
        if (!relationship.label().isEmpty()) {
            xml.append(" name=\"").append(attr(relationship.label())).append("\"");
        }
        xml.append(" source=\"").append(attr(sourceId))
                .append("\" target=\"").append(attr(targetId))
                .append("\"/>");
    }

    private static void validateXmiToAvailableStandards(String content, Map<String, String> env)
            throws XmiValidationException {
        validateXmiDocumentAndIds(content);
        validateOmgXmiSchema(content, env);
    }

    private static void validateXmiDocumentAndIds(String content) throws XmiValidationException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            Element root = document.getDocumentElement();
            if (!XMI_NS.equals(root.getNamespaceURI()) || !"XMI".equals(root.getLocalName())) {
                throw new XmiValidationException(
                        "DEDIREN_XMI_SCHEMA_INVALID",
                        "generated UML/XMI XML root must be xmi:XMI in the OMG XMI namespace");
            }
            if (root.hasAttributeNS(XMI_NS, "version")) {
                throw new XmiValidationException(
                        "DEDIREN_XMI_SCHEMA_INVALID",
                        "generated UML/XMI XML uses xmi:version, which OMG XMI.xsd does not allow");
            }
            Set<String> ids = new HashSet<>();
            var elements = document.getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                String id = element.getAttributeNS(XMI_NS, "id");
                if (id.isEmpty()) {
                    continue;
                }
                if (!isXmlId(id)) {
                    throw new XmiValidationException(
                            "DEDIREN_XMI_ID_INVALID",
                            "generated UML/XMI XML contains invalid xmi:id " + id);
                }
                if (!ids.add(id)) {
                    throw new XmiValidationException(
                            "DEDIREN_XMI_ID_INVALID",
                            "generated UML/XMI XML contains duplicate xmi:id " + id);
                }
            }
        } catch (XmiValidationException error) {
            throw error;
        } catch (Exception error) {
            throw new XmiValidationException(
                    "DEDIREN_XMI_XML_INVALID",
                    "generated UML/XMI XML is not well-formed: " + error.getMessage());
        }
    }

    private static void validateOmgXmiSchema(String content, Map<String, String> env)
            throws XmiValidationException {
        Path schemaPath = resolveOmgXmiSchemaPath(env);
        Process process;
        try {
            process = new ProcessBuilder(
                            XMI_SCHEMA_VALIDATOR,
                            "--nonet",
                            "--noout",
                            "--schema",
                            schemaPath.toString(),
                            "-")
                    .start();
        } catch (IOException error) {
            throw new XmiValidationException(
                    "DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE",
                    "failed to run OMG XMI schema validator " + XMI_SCHEMA_VALIDATOR + ": " + error.getMessage());
        }
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new XmiValidationException(
                    "DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE",
                    "failed to write UML/XMI XML to OMG XMI schema validator " + XMI_SCHEMA_VALIDATOR + ": "
                            + error.getMessage());
        }
        try {
            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return;
            }
            String details = SchemaCacheModule.commandOutputDetails(XMI_SCHEMA_VALIDATOR, exitCode, stdout, stderr);
            if (xmiSchemaErrorsAreOnlyUnavailableUmlSchema(details)) {
                return;
            }
            throw new XmiValidationException(
                    "DEDIREN_XMI_SCHEMA_INVALID",
                    "generated UML/XMI XML does not validate against OMG XMI.xsd: " + details);
        } catch (IOException error) {
            throw new XmiValidationException(
                    "DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE",
                    "failed to read OMG XMI schema validator output: " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new XmiValidationException(
                    "DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE",
                    "OMG XMI schema validator interrupted");
        }
    }

    private static Path resolveOmgXmiSchemaPath(Map<String, String> env) throws XmiValidationException {
        Optional<Path> configured = SchemaCacheModule.nonEmptyEnvPath(env, XMI_SCHEMA_PATH_ENV);
        if (configured.isPresent()) {
            if (SchemaCacheModule.isNonEmptyFile(configured.get())) {
                return configured.get();
            }
            throw new XmiValidationException(
                    "DEDIREN_XMI_SCHEMA_UNAVAILABLE",
                    "OMG XMI schema file " + configured.get()
                            + " is missing or empty; provide the official XMI.xsd or unset "
                            + XMI_SCHEMA_PATH_ENV + " to allow cache download");
        }
        Path schemaPath;
        try {
            schemaPath = SchemaCacheModule.schemaCacheBaseDir(env, SCHEMA_CACHE_DIR_ENV, XMI_SCHEMA_PATH_ENV)
                    .resolve("omg")
                    .resolve("xmi")
                    .resolve("2.5.1")
                    .resolve("XMI.xsd");
            SchemaCacheModule.ensureCachedSchemaFile(
                    schemaPath,
                    URI.create(OMG_XMI_SCHEMA_URL),
                    "OMG XMI schema",
                    SchemaCacheModule.curlFetcher(SCHEMA_FETCHER));
        } catch (SchemaCacheException error) {
            throw new XmiValidationException("DEDIREN_XMI_SCHEMA_UNAVAILABLE", error.getMessage());
        }
        return schemaPath;
    }

    private static boolean xmiSchemaErrorsAreOnlyUnavailableUmlSchema(String details) {
        boolean sawUmlSchemaGap = false;
        for (String rawLine : details.lines().toList()) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.endsWith("fails to validate")) {
                continue;
            }
            if (line.contains(UML_NS) && line.contains("No matching global element declaration available")) {
                sawUmlSchemaGap = true;
                continue;
            }
            return false;
        }
        return sawUmlSchemaGap;
    }

    private static int exitWithDiagnostic(PrintStream stdout, String code, String message, String path)
            throws IOException {
        var diagnostic = new Diagnostic(code, DiagnosticSeverity.ERROR, message, path);
        stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
        return 3;
    }

    private static void validateExportableSequenceScope(ExportRequest request) throws XmiExportException {
        ExportScope scope = ExportScope.fromRequest(request);
        var sourceNodesById = request.source().nodes().stream()
                .collect(Collectors.toMap(SourceNode::id, node -> node));

        for (int index = 0; index < request.source().relationships().size(); index++) {
            SourceRelationship relationship = request.source().relationships().get(index);
            if (!scope.relationshipIds().contains(relationship.id()) || !relationship.type().equals("Message")) {
                continue;
            }
            String interactionId = umlString(relationship, "interaction");
            if (interactionId == null) {
                throw new XmiExportException(
                        MISSING_SEQUENCE_MESSAGE_INTERACTION,
                        "UML/XMI sequence export requires selected Message relationships to define textual properties.uml.interaction",
                        "$.relationships[" + index + "].properties.uml.interaction");
            }
            SourceNode interaction = sourceNodesById.get(interactionId);
            if (interaction == null || !interaction.type().equals("Interaction")) {
                throw new XmiExportException(
                        UNSUPPORTED_SEQUENCE_MESSAGE_INTERACTION,
                        "UML/XMI sequence export requires selected Message properties.uml.interaction to resolve to an Interaction node",
                        "$.relationships[" + index + "].properties.uml.interaction");
            }
            SourceNode source = sourceNodesById.get(relationship.source());
            SourceNode target = sourceNodesById.get(relationship.target());
            if (source != null
                    && target != null
                    && (!source.type().equals("Lifeline") || !target.type().equals("Lifeline"))) {
                throw new XmiExportException(
                        UNSUPPORTED_SEQUENCE_MESSAGE_ENDPOINT,
                        "UML/XMI sequence export supports selected Message endpoints only between Lifeline nodes in this MVP: "
                                + source.type() + " -> " + target.type(),
                        "$.relationships[" + index + "]");
            }
        }

        for (int index = 0; index < request.source().nodes().size(); index++) {
            SourceNode node = request.source().nodes().get(index);
            if (scope.nodeIds().contains(node.id()) && unsupportedSequenceNode(node.type())) {
                throw new XmiExportException(
                        UNSUPPORTED_SEQUENCE_NODE,
                        "UML/XMI sequence export does not support selected " + node.type()
                                + " nodes in this MVP",
                        "$.nodes[" + index + "]");
            }
        }
    }

    private static boolean unsupportedSequenceNode(String type) {
        return type.equals("ExecutionSpecification")
                || type.equals("Gate")
                || type.equals("DestructionOccurrenceSpecification");
    }

    private static List<JsonNode> umlArray(SourceNode node, String field) {
        JsonNode value = node.properties().get("uml");
        value = value == null ? null : value.get(field);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        var values = new java.util.ArrayList<JsonNode>();
        value.forEach(values::add);
        return values;
    }

    private static String umlString(SourceNode node, String field) {
        JsonNode value = node.properties().get("uml");
        value = value == null ? null : value.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String umlString(SourceRelationship relationship, String field) {
        JsonNode value = relationship.properties().get("uml");
        value = value == null ? null : value.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static BigInteger umlSequence(SourceRelationship relationship) {
        JsonNode value = relationship.properties().get("uml");
        value = value == null ? null : value.get("sequence");
        return value != null && value.isIntegralNumber() ? value.bigIntegerValue() : BigInteger.valueOf(Long.MAX_VALUE);
    }

    private static String textField(JsonNode value, String field, String fallback) {
        JsonNode fieldValue = value.get(field);
        return fieldValue != null && fieldValue.isTextual() ? fieldValue.asText() : fallback;
    }

    private static String[] multiplicityBounds(String value) {
        if (value.contains("..")) {
            return value.split("\\.\\.", 2);
        }
        if (value.equals("*")) {
            return new String[]{"0", "*"};
        }
        return new String[]{value, value};
    }

    private static String activityNodeXmiType(String nodeType) {
        return switch (nodeType) {
            case "Action" -> "uml:OpaqueAction";
            case "InitialNode" -> "uml:InitialNode";
            case "ActivityFinalNode" -> "uml:ActivityFinalNode";
            case "DecisionNode" -> "uml:DecisionNode";
            case "MergeNode" -> "uml:MergeNode";
            case "ForkNode" -> "uml:ForkNode";
            case "JoinNode" -> "uml:JoinNode";
            case "ObjectNode" -> "uml:CentralBufferNode";
            default -> "uml:OpaqueAction";
        };
    }

    private static String activityEdgeXmiType(String relationshipType) {
        return relationshipType.equals("ObjectFlow") ? "uml:ObjectFlow" : "uml:ControlFlow";
    }

    private static boolean isXmlId(String value) {
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

    private static String semanticGroupSourceId(LaidOutGroup group) {
        if (group.provenance() == null) {
            return group.sourceId();
        }
        if (group.provenance().visualOnly()) {
            return null;
        }
        String sourceId = group.provenance().semanticSourceId();
        return sourceId == null ? group.sourceId() : sourceId;
    }

    private static String attr(String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record ExportScope(Set<String> nodeIds, Set<String> relationshipIds) {
        static ExportScope fromRequest(ExportRequest request) {
            var sourceNodesById = request.source().nodes().stream()
                    .collect(Collectors.toMap(SourceNode::id, node -> node));
            var nodeIds = request.layoutResult().nodes().stream()
                    .map(node -> node.sourceId())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (LaidOutGroup group : request.layoutResult().groups()) {
                String sourceId = semanticGroupSourceId(group);
                if (sourceId != null) {
                    nodeIds.add(sourceId);
                }
            }
            var activityIds = nodeIds.stream()
                    .map(sourceNodesById::get)
                    .filter(node -> node != null)
                    .map(node -> umlString(node, "activity"))
                    .filter(value -> value != null)
                    .toList();
            nodeIds.addAll(activityIds);
            var relationshipIds = request.layoutResult().edges().stream()
                    .map(edge -> edge.sourceId())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (SourceRelationship relationship : request.source().relationships()) {
                if (!relationshipIds.contains(relationship.id()) || !relationship.type().equals("Message")) {
                    continue;
                }
                addMessageLifelineEndpoint(nodeIds, sourceNodesById, relationship.source());
                addMessageLifelineEndpoint(nodeIds, sourceNodesById, relationship.target());
                String interactionId = umlString(relationship, "interaction");
                if (interactionId != null) {
                    nodeIds.add(interactionId);
                }
            }
            var interactionIds = nodeIds.stream()
                    .map(sourceNodesById::get)
                    .filter(node -> node != null)
                    .map(node -> umlString(node, "interaction"))
                    .filter(value -> value != null)
                    .toList();
            nodeIds.addAll(interactionIds);
            return new ExportScope(nodeIds, relationshipIds);
        }

        private static void addMessageLifelineEndpoint(
                Set<String> nodeIds,
                Map<String, SourceNode> sourceNodesById,
                String endpointId) {
            SourceNode endpoint = sourceNodesById.get(endpointId);
            if (endpoint != null && endpoint.type().equals("Lifeline")) {
                nodeIds.add(endpoint.id());
            }
        }
    }

    private record MessageExport(
            SourceRelationship relationship,
            String messageId,
            String sourceNodeId,
            String targetNodeId,
            String sourceEventId,
            String receiveEventId,
            BigInteger sequence,
            int sourceOrder,
            String messageSort) {
    }

    private static final class IdentifierMap {
        private final Set<String> used = new HashSet<>();

        private IdentifierMap(String reserved) {
            used.add(reserved);
        }

        String xmiId(String value) {
            String base = "id-" + slug(value);
            if (used.add(base)) {
                return base;
            }
            int suffix = 2;
            while (true) {
                String candidate = base + "-" + suffix;
                if (used.add(candidate)) {
                    return candidate;
                }
                suffix++;
            }
        }
    }

    private static String slug(String value) {
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

    private static final class XmiValidationException extends Exception {
        private final String code;

        private XmiValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private static final class XmiExportException extends Exception {
        private final String code;
        private final String path;

        private XmiExportException(String code, String message, String path) {
            super(message);
            this.code = code;
            this.path = path;
        }

        String code() {
            return code;
        }

        String path() {
            return path;
        }
    }
}
