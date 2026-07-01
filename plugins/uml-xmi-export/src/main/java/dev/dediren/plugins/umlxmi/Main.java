package dev.dediren.plugins.umlxmi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.export.UmlXmiExportPolicy;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
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
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
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
  private static final String UNSUPPORTED_SEQUENCE_FRAGMENT_OPERATOR =
      "DEDIREN_UML_XMI_SEQUENCE_FRAGMENT_OPERATOR_UNSUPPORTED";
  private static final String MISSING_SEQUENCE_MESSAGE_INTERACTION =
      "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_MISSING";
  private static final String UNSUPPORTED_SEQUENCE_MESSAGE_INTERACTION =
      "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_UNSUPPORTED";
  private static final String UNSUPPORTED_SEQUENCE_NODE =
      "DEDIREN_UML_XMI_SEQUENCE_NODE_UNSUPPORTED";
  private static final Set<String> SUPPORTED_SEQUENCE_FRAGMENT_OPERATORS =
      Set.of("alt", "opt", "loop", "par");

  private Main() {}

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
    int exitCode =
        execute(
            args,
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8),
            env);
    return new PluginResult(
        exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
  }

  private static int execute(
      String[] args,
      InputStream stdin,
      PrintStream stdout,
      PrintStream stderr,
      Map<String, String> env)
      throws Exception {
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
    schemaValidation.put(
        "limitation", "UML 2.5.1 is published as an XMI metamodel, not an importable XML Schema");
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
    ExportRequest request =
        JsonSupport.objectMapper().readValue(stdin.readAllBytes(), ExportRequest.class);
    UmlXmiExportPolicy policy;
    try {
      validatePolicy(request.policy());
      policy = JsonSupport.objectMapper().treeToValue(request.policy(), UmlXmiExportPolicy.class);
    } catch (IllegalArgumentException error) {
      return exitWithDiagnostic(
          stdout, "DEDIREN_UML_XMI_POLICY_INVALID", error.getMessage(), "policy");
    }

    GenericGraphPluginData pluginData;
    try {
      pluginData = genericGraphPluginData(request);
      validateSelectedCombinedFragmentOperators(request, pluginData);
      Uml.validateSource(request.source(), pluginData);
    } catch (UmlValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
    } catch (XmiExportException error) {
      return exitWithDiagnostic(stdout, error.code(), error.getMessage(), error.path());
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

    stdout.println(
        JsonSupport.objectMapper()
            .writeValueAsString(
                CommandEnvelope.ok(
                    new ExportResult(
                        ContractVersions.EXPORT_RESULT_SCHEMA_VERSION, "uml-xmi+xml", content))));
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
    for (String field :
        List.of("uml_xmi_export_policy_schema_version", "model_identifier", "model_name")) {
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
    var selectedNodes =
        request.source().nodes().stream()
            .filter(node -> scope.nodeIds().contains(node.id()))
            .toList();
    var selectedRelationships =
        request.source().relationships().stream()
            .filter(relationship -> scope.relationshipIds().contains(relationship.id()))
            .toList();
    var sourceNodesById =
        request.source().nodes().stream().collect(Collectors.toMap(SourceNode::id, node -> node));
    var nodeIds = new HashMap<String, String>();
    selectedNodes.forEach(node -> nodeIds.put(node.id(), ids.xmiId(node.id())));
    var relationshipIds = new HashMap<String, String>();
    selectedRelationships.forEach(
        relationship -> relationshipIds.put(relationship.id(), ids.xmiId(relationship.id())));

    StringBuilder xml = new StringBuilder();
    xml.append("<xmi:XMI xmlns:xmi=\"")
        .append(XMI_NS)
        .append("\" xmlns:uml=\"")
        .append(UML_NS)
        .append("\">");
    xml.append("<uml:Model xmi:id=\"")
        .append(attr(policy.modelIdentifier()))
        .append("\" name=\"")
        .append(attr(policy.modelName()))
        .append("\">");
    for (SourceNode node : selectedNodes) {
      String elementId = nodeIds.get(node.id());
      switch (node.type()) {
        case "Package" -> writeEmptyPackagedElement(xml, "uml:Package", node, elementId);
        case "Component" -> writeComponent(xml, node, elementId, selectedNodes, nodeIds);
        case "Class" -> writeClassifier(xml, ids, "uml:Class", node, elementId);
        case "Interface" -> writeClassifier(xml, ids, "uml:Interface", node, elementId);
        case "DataType" -> writeClassifier(xml, ids, "uml:DataType", node, elementId);
        case "Enumeration" -> writeEnumeration(xml, ids, node, elementId);
        case "Node" -> writeEmptyPackagedElement(xml, "uml:Node", node, elementId);
        case "Device" -> writeEmptyPackagedElement(xml, "uml:Device", node, elementId);
        case "ExecutionEnvironment" ->
            writeEmptyPackagedElement(xml, "uml:ExecutionEnvironment", node, elementId);
        case "Artifact" -> writeEmptyPackagedElement(xml, "uml:Artifact", node, elementId);
        case "DeploymentSpecification" ->
            writeEmptyPackagedElement(xml, "uml:DeploymentSpecification", node, elementId);
        case "Actor" -> writeEmptyPackagedElement(xml, "uml:Actor", node, elementId);
        case "UseCase" ->
            writeUseCase(
                xml,
                node,
                elementId,
                selectedNodes,
                selectedRelationships,
                nodeIds,
                relationshipIds);
        case "Activity" ->
            writeActivity(
                xml,
                node,
                elementId,
                request.source().nodes(),
                selectedRelationships,
                nodeIds,
                relationshipIds);
        case "Interaction" ->
            writeInteraction(
                xml,
                ids,
                node,
                elementId,
                request.source().nodes(),
                selectedRelationships,
                nodeIds,
                relationshipIds);
        case "StateMachine" ->
            writeStateMachine(
                xml,
                ids,
                node,
                elementId,
                selectedNodes,
                selectedRelationships,
                nodeIds,
                relationshipIds);
        default -> {}
      }
    }
    writeComponentRelationships(
        xml, selectedRelationships, sourceNodesById, nodeIds, relationshipIds);
    writeDeploymentRelationships(
        xml, selectedRelationships, sourceNodesById, nodeIds, relationshipIds);
    xml.append("</uml:Model></xmi:XMI>\n");
    return xml.toString();
  }

  private static void writeEmptyPackagedElement(
      StringBuilder xml, String umlType, SourceNode node, String elementId) {
    xml.append("<packagedElement xmi:type=\"")
        .append(umlType)
        .append("\" xmi:id=\"")
        .append(attr(elementId))
        .append("\" name=\"")
        .append(attr(node.label()))
        .append("\"/>");
  }

  private static void writeClassifier(
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

  private static void writeOwnedAttribute(
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

  private static void writeOwnedOperation(
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

  private static void writeEnumeration(
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

  private static void writeComponent(
      StringBuilder xml,
      SourceNode component,
      String componentId,
      List<SourceNode> selectedNodes,
      Map<String, String> nodeIds) {
    xml.append("<packagedElement xmi:type=\"uml:Component\" xmi:id=\"")
        .append(attr(componentId))
        .append("\" name=\"")
        .append(attr(component.label()))
        .append("\">");
    selectedNodes.stream()
        .filter(node -> node.type().equals("Port"))
        .filter(node -> component.id().equals(umlString(node, "component")))
        .filter(node -> nodeIds.containsKey(node.id()))
        .forEach(port -> writeOwnedPort(xml, port, nodeIds));
    xml.append("</packagedElement>");
  }

  private static void writeOwnedPort(
      StringBuilder xml, SourceNode port, Map<String, String> nodeIds) {
    xml.append("<ownedAttribute xmi:type=\"uml:Port\" xmi:id=\"")
        .append(attr(nodeIds.get(port.id())))
        .append("\" name=\"")
        .append(attr(port.label()))
        .append("\"");
    writeReferencedClassifierIds(xml, "provided", umlTextArray(port, "provided"), nodeIds);
    writeReferencedClassifierIds(xml, "required", umlTextArray(port, "required"), nodeIds);
    xml.append("/>");
  }

  private static void writeReferencedClassifierIds(
      StringBuilder xml, String attribute, List<String> sourceIds, Map<String, String> nodeIds) {
    List<String> referencedIds =
        sourceIds.stream().map(nodeIds::get).filter(value -> value != null).toList();
    if (!referencedIds.isEmpty()) {
      xml.append(" ")
          .append(attribute)
          .append("=\"")
          .append(attr(String.join(" ", referencedIds)))
          .append("\"");
    }
  }

  private static void writeComponentRelationships(
      StringBuilder xml,
      List<SourceRelationship> selectedRelationships,
      Map<String, SourceNode> sourceNodesById,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    for (SourceRelationship relationship : selectedRelationships) {
      if (!isComponentExportRelationship(relationship, sourceNodesById)
          || !relationshipIds.containsKey(relationship.id())
          || !nodeIds.containsKey(relationship.source())
          || !nodeIds.containsKey(relationship.target())) {
        continue;
      }
      xml.append("<packagedElement xmi:type=\"")
          .append(componentRelationshipXmiType(relationship.type()))
          .append("\" xmi:id=\"")
          .append(attr(relationshipIds.get(relationship.id())))
          .append("\" name=\"")
          .append(attr(relationship.label()))
          .append("\" client=\"")
          .append(attr(nodeIds.get(relationship.source())))
          .append("\" supplier=\"")
          .append(attr(nodeIds.get(relationship.target())))
          .append("\"/>");
    }
  }

  private static boolean isComponentExportRelationship(
      SourceRelationship relationship, Map<String, SourceNode> sourceNodesById) {
    if (!relationship.type().equals("Dependency")
        && !relationship.type().equals("Realization")
        && !relationship.type().equals("Usage")) {
      return false;
    }
    SourceNode source = sourceNodesById.get(relationship.source());
    SourceNode target = sourceNodesById.get(relationship.target());
    return isComponentEndpoint(source) || isComponentEndpoint(target);
  }

  private static boolean isComponentEndpoint(SourceNode node) {
    return node != null && (node.type().equals("Component") || node.type().equals("Port"));
  }

  private static String componentRelationshipXmiType(String type) {
    return switch (type) {
      case "Realization" -> "uml:Realization";
      case "Usage" -> "uml:Usage";
      default -> "uml:Dependency";
    };
  }

  private static void writeDeploymentRelationships(
      StringBuilder xml,
      List<SourceRelationship> selectedRelationships,
      Map<String, SourceNode> sourceNodesById,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    for (SourceRelationship relationship : selectedRelationships) {
      if (!isDeploymentExportRelationship(relationship, sourceNodesById)
          || !relationshipIds.containsKey(relationship.id())
          || !nodeIds.containsKey(relationship.source())
          || !nodeIds.containsKey(relationship.target())) {
        continue;
      }
      switch (relationship.type()) {
        case "Deployment" -> writeDeployment(xml, relationship, nodeIds, relationshipIds);
        case "Manifestation" -> writeManifestation(xml, relationship, nodeIds, relationshipIds);
        case "CommunicationPath" ->
            writeCommunicationPath(xml, relationship, nodeIds, relationshipIds);
        default -> {}
      }
    }
  }

  private static boolean isDeploymentExportRelationship(
      SourceRelationship relationship, Map<String, SourceNode> sourceNodesById) {
    SourceNode source = sourceNodesById.get(relationship.source());
    SourceNode target = sourceNodesById.get(relationship.target());
    return switch (relationship.type()) {
      case "Deployment" -> isDeployedArtifactType(source) && isDeploymentTargetType(target);
      case "Manifestation" -> isDeployedArtifactType(source) && isManifestedElementType(target);
      case "CommunicationPath" -> isDeploymentTargetType(source) && isDeploymentTargetType(target);
      default -> false;
    };
  }

  private static void writeDeployment(
      StringBuilder xml,
      SourceRelationship deployment,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    String artifactId = nodeIds.get(deployment.source());
    String locationId = nodeIds.get(deployment.target());
    xml.append("<packagedElement xmi:type=\"uml:Deployment\" xmi:id=\"")
        .append(attr(relationshipIds.get(deployment.id())))
        .append("\" name=\"")
        .append(attr(deployment.label()))
        .append("\" client=\"")
        .append(attr(artifactId))
        .append("\" supplier=\"")
        .append(attr(locationId))
        .append("\" deployedArtifact=\"")
        .append(attr(artifactId))
        .append("\" location=\"")
        .append(attr(locationId))
        .append("\"/>");
  }

  private static void writeManifestation(
      StringBuilder xml,
      SourceRelationship manifestation,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    String artifactId = nodeIds.get(manifestation.source());
    String utilizedElementId = nodeIds.get(manifestation.target());
    xml.append("<packagedElement xmi:type=\"uml:Manifestation\" xmi:id=\"")
        .append(attr(relationshipIds.get(manifestation.id())))
        .append("\" name=\"")
        .append(attr(manifestation.label()))
        .append("\" client=\"")
        .append(attr(artifactId))
        .append("\" supplier=\"")
        .append(attr(utilizedElementId))
        .append("\" utilizedElement=\"")
        .append(attr(utilizedElementId))
        .append("\"/>");
  }

  private static void writeCommunicationPath(
      StringBuilder xml,
      SourceRelationship communicationPath,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    xml.append("<packagedElement xmi:type=\"uml:CommunicationPath\" xmi:id=\"")
        .append(attr(relationshipIds.get(communicationPath.id())))
        .append("\" name=\"")
        .append(attr(communicationPath.label()))
        .append("\" endType=\"")
        .append(
            attr(
                nodeIds.get(communicationPath.source())
                    + " "
                    + nodeIds.get(communicationPath.target())))
        .append("\"/>");
  }

  private static boolean isDeployedArtifactType(SourceNode node) {
    return node != null
        && (node.type().equals("Artifact") || node.type().equals("DeploymentSpecification"));
  }

  private static boolean isDeploymentTargetType(SourceNode node) {
    return node != null
        && (node.type().equals("Node")
            || node.type().equals("Device")
            || node.type().equals("ExecutionEnvironment"));
  }

  private static boolean isManifestedElementType(SourceNode node) {
    return node != null
        && (node.type().equals("Package")
            || node.type().equals("Class")
            || node.type().equals("Interface")
            || node.type().equals("DataType")
            || node.type().equals("Enumeration")
            || node.type().equals("Component"));
  }

  private static void writeUseCase(
      StringBuilder xml,
      SourceNode useCase,
      String useCaseId,
      List<SourceNode> selectedNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    xml.append("<packagedElement xmi:type=\"uml:UseCase\" xmi:id=\"")
        .append(attr(useCaseId))
        .append("\" name=\"")
        .append(attr(useCase.label()))
        .append("\"");
    String subject = umlString(useCase, "subject");
    if (subject != null && nodeIds.containsKey(subject)) {
      xml.append(" subject=\"").append(attr(nodeIds.get(subject))).append("\"");
    }
    List<SourceNode> extensionPoints =
        selectedNodes.stream()
            .filter(node -> node.type().equals("ExtensionPoint"))
            .filter(node -> useCase.id().equals(umlString(node, "use_case")))
            .filter(node -> nodeIds.containsKey(node.id()))
            .toList();
    List<SourceRelationship> includes =
        selectedRelationships.stream()
            .filter(relationship -> relationship.type().equals("Include"))
            .filter(relationship -> useCase.id().equals(relationship.source()))
            .filter(relationship -> relationshipIds.containsKey(relationship.id()))
            .filter(relationship -> nodeIds.containsKey(relationship.target()))
            .toList();
    List<SourceRelationship> extendsRelationships =
        selectedRelationships.stream()
            .filter(relationship -> relationship.type().equals("Extend"))
            .filter(relationship -> useCase.id().equals(relationship.source()))
            .filter(relationship -> relationshipIds.containsKey(relationship.id()))
            .filter(relationship -> nodeIds.containsKey(relationship.target()))
            .toList();
    if (extensionPoints.isEmpty() && includes.isEmpty() && extendsRelationships.isEmpty()) {
      xml.append("/>");
      return;
    }
    xml.append(">");
    for (SourceNode extensionPoint : extensionPoints) {
      writeExtensionPoint(xml, extensionPoint, nodeIds.get(extensionPoint.id()));
    }
    for (SourceRelationship include : includes) {
      writeInclude(xml, include, relationshipIds.get(include.id()), nodeIds);
    }
    for (SourceRelationship extend : extendsRelationships) {
      writeExtend(xml, extend, relationshipIds.get(extend.id()), nodeIds);
    }
    xml.append("</packagedElement>");
  }

  private static void writeExtensionPoint(
      StringBuilder xml, SourceNode extensionPoint, String extensionPointId) {
    xml.append("<extensionPoint xmi:id=\"")
        .append(attr(extensionPointId))
        .append("\" name=\"")
        .append(attr(extensionPoint.label()))
        .append("\"/>");
  }

  private static void writeInclude(
      StringBuilder xml,
      SourceRelationship include,
      String includeId,
      Map<String, String> nodeIds) {
    xml.append("<include xmi:id=\"")
        .append(attr(includeId))
        .append("\" name=\"")
        .append(attr(include.label()))
        .append("\" addition=\"")
        .append(attr(nodeIds.get(include.target())))
        .append("\"/>");
  }

  private static void writeExtend(
      StringBuilder xml, SourceRelationship extend, String extendId, Map<String, String> nodeIds) {
    xml.append("<extend xmi:id=\"")
        .append(attr(extendId))
        .append("\" name=\"")
        .append(attr(extend.label()))
        .append("\" extendedCase=\"")
        .append(attr(nodeIds.get(extend.target())))
        .append("\"");
    String extensionPointId = umlString(extend, "extension_point");
    if (extensionPointId != null && nodeIds.containsKey(extensionPointId)) {
      xml.append(" extensionLocation=\"").append(attr(nodeIds.get(extensionPointId))).append("\"");
    }
    xml.append("/>");
  }

  private static void writeStateMachine(
      StringBuilder xml,
      IdentifierMap ids,
      SourceNode stateMachine,
      String stateMachineId,
      List<SourceNode> selectedNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    xml.append("<packagedElement xmi:type=\"uml:StateMachine\" xmi:id=\"")
        .append(attr(stateMachineId))
        .append("\" name=\"")
        .append(attr(stateMachine.label()))
        .append("\">");
    for (SourceNode region : selectedNodes) {
      if (region.type().equals("Region")
          && stateMachine.id().equals(umlString(region, "state_machine"))) {
        writeStateMachineRegion(
            xml, ids, region, selectedNodes, selectedRelationships, nodeIds, relationshipIds);
      }
    }
    xml.append("</packagedElement>");
  }

  private static void writeStateMachineRegion(
      StringBuilder xml,
      IdentifierMap ids,
      SourceNode region,
      List<SourceNode> selectedNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    xml.append("<region xmi:id=\"")
        .append(attr(nodeIds.get(region.id())))
        .append("\" name=\"")
        .append(attr(region.label()))
        .append("\">");
    for (SourceNode vertex : selectedNodes) {
      if (region.id().equals(umlString(vertex, "region"))) {
        writeStateMachineVertex(xml, vertex, nodeIds.get(vertex.id()));
      }
    }
    for (SourceRelationship transition : selectedRelationships) {
      if (transition.type().equals("Transition")
          && region.id().equals(umlString(transition, "region"))) {
        writeTransition(xml, ids, transition, relationshipIds.get(transition.id()), nodeIds);
      }
    }
    xml.append("</region>");
  }

  private static void writeStateMachineVertex(
      StringBuilder xml, SourceNode vertex, String vertexId) {
    switch (vertex.type()) {
      case "State" ->
          xml.append("<subvertex xmi:type=\"uml:State\" xmi:id=\"")
              .append(attr(vertexId))
              .append("\" name=\"")
              .append(attr(vertex.label()))
              .append("\"/>");
      case "FinalState" ->
          xml.append("<subvertex xmi:type=\"uml:FinalState\" xmi:id=\"")
              .append(attr(vertexId))
              .append("\" name=\"")
              .append(attr(vertex.label()))
              .append("\"/>");
      case "Pseudostate" ->
          xml.append("<subvertex xmi:type=\"uml:Pseudostate\" xmi:id=\"")
              .append(attr(vertexId))
              .append("\" name=\"")
              .append(attr(vertex.label()))
              .append("\" kind=\"")
              .append(attr(umlString(vertex, "kind")))
              .append("\"/>");
      default -> {}
    }
  }

  private static void writeTransition(
      StringBuilder xml,
      IdentifierMap ids,
      SourceRelationship transition,
      String transitionId,
      Map<String, String> nodeIds) {
    xml.append("<transition xmi:id=\"")
        .append(attr(transitionId))
        .append("\" name=\"")
        .append(attr(transition.label()))
        .append("\" source=\"")
        .append(attr(nodeIds.get(transition.source())))
        .append("\" target=\"")
        .append(attr(nodeIds.get(transition.target())))
        .append("\" kind=\"")
        .append(attr(Optional.ofNullable(umlString(transition, "kind")).orElse("external")))
        .append("\">");
    String guard = umlString(transition, "guard");
    if (guard != null && !guard.isBlank()) {
      String guardId = ids.xmiId(transition.id() + "-guard");
      String specificationId = ids.xmiId(transition.id() + "-guard-specification");
      xml.append("<guard xmi:type=\"uml:Constraint\" xmi:id=\"")
          .append(attr(guardId))
          .append("\" name=\"")
          .append(attr(guard))
          .append("\">")
          .append("<specification xmi:type=\"uml:OpaqueExpression\" xmi:id=\"")
          .append(attr(specificationId))
          .append("\">")
          .append("<body>")
          .append(text(guard))
          .append("</body>")
          .append("</specification></guard>");
    }
    xml.append("</transition>");
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
    xml.append("<packagedElement xmi:type=\"uml:Interaction\" xmi:id=\"")
        .append(attr(interactionId))
        .append("\" name=\"")
        .append(attr(interaction.label()))
        .append("\">");
    List<MessageExport> messages =
        sequenceMessages(ids, interaction, selectedRelationships, nodeIds, relationshipIds);
    List<CombinedFragmentExport> combinedFragments =
        combinedFragments(interaction, sourceNodes, nodeIds);
    var combinedFragmentsById =
        combinedFragments.stream()
            .collect(Collectors.toMap(fragment -> fragment.node().id(), fragment -> fragment));
    var messagesBySourceId = messagesBySourceId(messages);
    Set<String> nestedCombinedFragmentIds = nestedCombinedFragmentIds(combinedFragments);
    Set<String> operandOwnedMessageIds = operandOwnedMessageIds(combinedFragments, messages);
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
        xml.append("<lifeline xmi:id=\"")
            .append(attr(nodeId))
            .append("\" name=\"")
            .append(attr(node.label()))
            .append("\"/>");
      }
    }
    writeTopLevelInteractionFragments(
        xml,
        ids,
        combinedFragments,
        combinedFragmentsById,
        messages,
        messagesBySourceId,
        nestedCombinedFragmentIds,
        operandOwnedMessageIds);
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
        .map(
            relationship -> {
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
        .sorted(
            Comparator.comparing(MessageExport::sequence)
                .thenComparingInt(MessageExport::sourceOrder))
        .toList();
  }

  private static List<CombinedFragmentExport> combinedFragments(
      SourceNode interaction, List<SourceNode> sourceNodes, Map<String, String> nodeIds) {
    var sourceOrder = new HashMap<String, Integer>();
    var operandsById = new HashMap<String, OperandExport>();
    for (int index = 0; index < sourceNodes.size(); index++) {
      SourceNode node = sourceNodes.get(index);
      sourceOrder.put(node.id(), index);
      if (nodeIds.containsKey(node.id())
          && node.type().equals("InteractionOperand")
          && interaction.id().equals(umlString(node, "interaction"))) {
        operandsById.put(
            node.id(),
            new OperandExport(
                node,
                nodeIds.get(node.id()),
                umlPositiveInt(node, "order", Integer.MAX_VALUE),
                umlString(node, "guard"),
                umlTextArray(node, "fragments"),
                index));
      }
    }

    var combinedFragments = new java.util.ArrayList<CombinedFragmentExport>();
    for (SourceNode node : sourceNodes) {
      if (!nodeIds.containsKey(node.id())
          || !node.type().equals("CombinedFragment")
          || !interaction.id().equals(umlString(node, "interaction"))) {
        continue;
      }
      List<String> operandIds = umlTextArray(node, "operands");
      var operandListOrder = new HashMap<String, Integer>();
      for (int index = 0; index < operandIds.size(); index++) {
        operandListOrder.put(operandIds.get(index), index);
      }
      List<OperandExport> operands =
          operandIds.stream()
              .map(operandsById::get)
              .filter(operand -> operand != null)
              .sorted(
                  Comparator.comparingInt(
                          (OperandExport operand) ->
                              operandListOrder.getOrDefault(operand.node().id(), Integer.MAX_VALUE))
                      .thenComparingInt(OperandExport::order)
                      .thenComparingInt(OperandExport::sourceOrder))
              .toList();
      List<String> coveredNodeIds =
          umlTextArray(node, "covered").stream()
              .map(nodeIds::get)
              .filter(coveredNodeId -> coveredNodeId != null)
              .toList();
      combinedFragments.add(
          new CombinedFragmentExport(
              node,
              nodeIds.get(node.id()),
              umlString(node, "operator"),
              operands,
              coveredNodeIds,
              sourceOrder.get(node.id())));
    }
    return combinedFragments.stream()
        .sorted(Comparator.comparingInt(CombinedFragmentExport::sourceOrder))
        .toList();
  }

  private static Map<String, MessageExport> messagesBySourceId(List<MessageExport> messages) {
    return messages.stream()
        .collect(Collectors.toMap(message -> message.relationship().id(), message -> message));
  }

  private static Set<String> nestedCombinedFragmentIds(
      List<CombinedFragmentExport> combinedFragments) {
    Set<String> combinedFragmentIds =
        combinedFragments.stream()
            .map(fragment -> fragment.node().id())
            .collect(Collectors.toSet());
    var nestedIds = new HashSet<String>();
    for (CombinedFragmentExport combinedFragment : combinedFragments) {
      for (OperandExport operand : combinedFragment.operands()) {
        for (String fragmentId : operand.fragmentIds()) {
          if (combinedFragmentIds.contains(fragmentId)) {
            nestedIds.add(fragmentId);
          }
        }
      }
    }
    return nestedIds;
  }

  private static Set<String> operandOwnedMessageIds(
      List<CombinedFragmentExport> combinedFragments, List<MessageExport> messages) {
    Set<String> messageIds =
        messages.stream().map(message -> message.relationship().id()).collect(Collectors.toSet());
    var ownedMessageIds = new HashSet<String>();
    for (CombinedFragmentExport combinedFragment : combinedFragments) {
      for (OperandExport operand : combinedFragment.operands()) {
        for (String fragmentId : operand.fragmentIds()) {
          if (messageIds.contains(fragmentId)) {
            ownedMessageIds.add(fragmentId);
          }
        }
      }
    }
    return ownedMessageIds;
  }

  private static void writeTopLevelInteractionFragments(
      StringBuilder xml,
      IdentifierMap ids,
      List<CombinedFragmentExport> combinedFragments,
      Map<String, CombinedFragmentExport> combinedFragmentsById,
      List<MessageExport> messages,
      Map<String, MessageExport> messagesBySourceId,
      Set<String> nestedCombinedFragmentIds,
      Set<String> operandOwnedMessageIds) {
    var fragments = new java.util.ArrayList<TopLevelInteractionFragment>();
    for (CombinedFragmentExport combinedFragment : combinedFragments) {
      if (nestedCombinedFragmentIds.contains(combinedFragment.node().id())) {
        continue;
      }
      fragments.add(
          new TopLevelInteractionFragment(
              combinedFragmentSequence(combinedFragment, combinedFragmentsById, messagesBySourceId),
              combinedFragment.sourceOrder(),
              combinedFragment,
              null));
    }
    for (MessageExport message : messages) {
      if (operandOwnedMessageIds.contains(message.relationship().id())) {
        continue;
      }
      fragments.add(
          new TopLevelInteractionFragment(
              message.sequence(), message.sourceOrder(), null, message));
    }
    fragments.sort(
        Comparator.comparing(TopLevelInteractionFragment::sequence)
            .thenComparingInt(TopLevelInteractionFragment::sourceOrder));
    for (TopLevelInteractionFragment fragment : fragments) {
      if (fragment.combinedFragment() != null) {
        writeCombinedFragment(
            xml, ids, fragment.combinedFragment(), combinedFragmentsById, messagesBySourceId);
      } else if (fragment.message() != null) {
        MessageExport message = fragment.message();
        writeMessageOccurrence(
            xml, message, "send", message.sourceEventId(), message.sourceNodeId());
        writeMessageOccurrence(
            xml, message, "receive", message.receiveEventId(), message.targetNodeId());
      }
    }
  }

  private static BigInteger combinedFragmentSequence(
      CombinedFragmentExport combinedFragment,
      Map<String, CombinedFragmentExport> combinedFragmentsById,
      Map<String, MessageExport> messagesBySourceId) {
    return combinedFragmentSequence(
        combinedFragment, combinedFragmentsById, messagesBySourceId, new HashSet<>());
  }

  private static BigInteger combinedFragmentSequence(
      CombinedFragmentExport combinedFragment,
      Map<String, CombinedFragmentExport> combinedFragmentsById,
      Map<String, MessageExport> messagesBySourceId,
      Set<String> visitedCombinedFragmentIds) {
    if (!visitedCombinedFragmentIds.add(combinedFragment.node().id())) {
      return BigInteger.valueOf(Integer.MAX_VALUE);
    }

    BigInteger firstSequence = null;
    for (OperandExport operand : combinedFragment.operands()) {
      for (String fragmentId : operand.fragmentIds()) {
        MessageExport message = messagesBySourceId.get(fragmentId);
        BigInteger sequence = message == null ? null : message.sequence();
        if (sequence == null) {
          CombinedFragmentExport nestedCombinedFragment = combinedFragmentsById.get(fragmentId);
          sequence =
              nestedCombinedFragment == null
                  ? null
                  : combinedFragmentSequence(
                      nestedCombinedFragment,
                      combinedFragmentsById,
                      messagesBySourceId,
                      visitedCombinedFragmentIds);
        }
        if (sequence != null && (firstSequence == null || sequence.compareTo(firstSequence) < 0)) {
          firstSequence = sequence;
        }
      }
    }
    return firstSequence == null ? BigInteger.valueOf(Integer.MAX_VALUE) : firstSequence;
  }

  private static void writeCombinedFragment(
      StringBuilder xml,
      IdentifierMap ids,
      CombinedFragmentExport combinedFragment,
      Map<String, CombinedFragmentExport> combinedFragmentsById,
      Map<String, MessageExport> messagesBySourceId) {
    xml.append("<fragment xmi:type=\"uml:CombinedFragment\" xmi:id=\"")
        .append(attr(combinedFragment.fragmentId()))
        .append("\" name=\"")
        .append(attr(combinedFragment.node().label()))
        .append("\" interactionOperator=\"")
        .append(attr(combinedFragment.operator()))
        .append("\"");
    if (!combinedFragment.coveredNodeIds().isEmpty()) {
      xml.append(" covered=\"")
          .append(attr(String.join(" ", combinedFragment.coveredNodeIds())))
          .append("\"");
    }
    xml.append(">");
    for (OperandExport operand : combinedFragment.operands()) {
      writeOperand(xml, ids, operand, combinedFragmentsById, messagesBySourceId);
    }
    xml.append("</fragment>");
  }

  private static void writeOperand(
      StringBuilder xml,
      IdentifierMap ids,
      OperandExport operand,
      Map<String, CombinedFragmentExport> combinedFragmentsById,
      Map<String, MessageExport> messagesBySourceId) {
    xml.append("<operand xmi:id=\"")
        .append(attr(operand.operandId()))
        .append("\" name=\"")
        .append(attr(operand.node().label()))
        .append("\">");
    if (operand.guard() != null) {
      String guardId = ids.xmiId(operand.node().id() + "-guard");
      String specificationId = ids.xmiId(operand.node().id() + "-guard-specification");
      xml.append("<guard xmi:type=\"uml:InteractionConstraint\" xmi:id=\"")
          .append(attr(guardId))
          .append("\" name=\"")
          .append(attr(operand.guard()))
          .append("\">")
          .append("<specification xmi:type=\"uml:OpaqueExpression\" xmi:id=\"")
          .append(attr(specificationId))
          .append("\">")
          .append("<body>")
          .append(text(operand.guard()))
          .append("</body>")
          .append("</specification></guard>");
    }
    for (String fragmentId : operand.fragmentIds()) {
      MessageExport message = messagesBySourceId.get(fragmentId);
      if (message != null) {
        writeMessageOccurrence(
            xml, message, "send", message.sourceEventId(), message.sourceNodeId());
        writeMessageOccurrence(
            xml, message, "receive", message.receiveEventId(), message.targetNodeId());
        continue;
      }
      CombinedFragmentExport nestedCombinedFragment = combinedFragmentsById.get(fragmentId);
      if (nestedCombinedFragment != null) {
        writeCombinedFragment(
            xml, ids, nestedCombinedFragment, combinedFragmentsById, messagesBySourceId);
      }
    }
    xml.append("</operand>");
  }

  private static void writeMessageOccurrence(
      StringBuilder xml, MessageExport message, String kind, String eventId, String coveredNodeId) {
    xml.append("<fragment xmi:type=\"uml:MessageOccurrenceSpecification\" xmi:id=\"")
        .append(attr(eventId))
        .append("\" name=\"")
        .append(attr(message.relationship().label()))
        .append(" ")
        .append(kind)
        .append("\" covered=\"")
        .append(attr(coveredNodeId))
        .append("\" message=\"")
        .append(attr(message.messageId()))
        .append("\"/>");
  }

  private static void writeSequenceMessage(StringBuilder xml, MessageExport message) {
    xml.append("<message xmi:id=\"")
        .append(attr(message.messageId()))
        .append("\" name=\"")
        .append(attr(message.relationship().label()))
        .append("\" messageSort=\"")
        .append(attr(message.messageSort()))
        .append("\" sendEvent=\"")
        .append(attr(message.sourceEventId()))
        .append("\" receiveEvent=\"")
        .append(attr(message.receiveEventId()))
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
    xml.append("<packagedElement xmi:type=\"uml:Activity\" xmi:id=\"")
        .append(attr(activityId))
        .append("\" name=\"")
        .append(attr(activity.label()))
        .append("\">");
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
    xml.append("<node xmi:type=\"")
        .append(activityNodeXmiType(node.type()))
        .append("\" xmi:id=\"")
        .append(attr(nodeId))
        .append("\"");
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
    xml.append("<edge xmi:type=\"")
        .append(activityEdgeXmiType(relationship.type()))
        .append("\" xmi:id=\"")
        .append(attr(relationshipId))
        .append("\"");
    if (!relationship.label().isEmpty()) {
      xml.append(" name=\"").append(attr(relationship.label())).append("\"");
    }
    xml.append(" source=\"")
        .append(attr(sourceId))
        .append("\" target=\"")
        .append(attr(targetId))
        .append("\"/>");
  }

  private static void validateXmiToAvailableStandards(String content, Map<String, String> env)
      throws XmiValidationException {
    validateXmiDocumentAndIds(content);
    validateOmgXmiSchema(content, env);
  }

  private static void validateXmiDocumentAndIds(String content) throws XmiValidationException {
    try {
      var factory = secureXmiDocumentBuilderFactory();
      var document =
          factory
              .newDocumentBuilder()
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
              "DEDIREN_XMI_ID_INVALID", "generated UML/XMI XML contains invalid xmi:id " + id);
        }
        if (!ids.add(id)) {
          throw new XmiValidationException(
              "DEDIREN_XMI_ID_INVALID", "generated UML/XMI XML contains duplicate xmi:id " + id);
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

  // Defense in depth: this validator only ever parses Dediren's own generated
  // XMI, which never contains a DOCTYPE. Hardening the factory keeps XXE and
  // entity-expansion classes off the table regardless of what the parser is
  // ever pointed at. Package-private so the same-package test can exercise it.
  static DocumentBuilderFactory secureXmiDocumentBuilderFactory()
      throws ParserConfigurationException {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory;
  }

  private static void validateOmgXmiSchema(String content, Map<String, String> env)
      throws XmiValidationException {
    Path schemaPath = resolveOmgXmiSchemaPath(env);
    String validator = xmiSchemaValidator(env);
    Process process;
    try {
      process =
          new ProcessBuilder(
                  validator, "--nonet", "--noout", "--schema", schemaPath.toString(), "-")
              .start();
    } catch (IOException error) {
      throw new XmiValidationException(
          DiagnosticCode.XMI_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "failed to run OMG XMI schema validator " + validator + ": " + error.getMessage());
    }
    try (OutputStream stdin = process.getOutputStream()) {
      stdin.write(content.getBytes(StandardCharsets.UTF_8));
    } catch (IOException error) {
      throw new XmiValidationException(
          DiagnosticCode.XMI_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "failed to write UML/XMI XML to OMG XMI schema validator "
              + validator
              + ": "
              + error.getMessage());
    }
    try {
      byte[] stdout = process.getInputStream().readAllBytes();
      byte[] stderr = process.getErrorStream().readAllBytes();
      int exitCode = process.waitFor();
      if (exitCode == 0) {
        return;
      }
      String details = SchemaCacheModule.commandOutputDetails(validator, exitCode, stdout, stderr);
      if (xmiSchemaErrorsAreOnlyUnavailableUmlSchema(details)) {
        return;
      }
      throw new XmiValidationException(
          "DEDIREN_XMI_SCHEMA_INVALID",
          "generated UML/XMI XML does not validate against OMG XMI.xsd: " + details);
    } catch (IOException error) {
      throw new XmiValidationException(
          DiagnosticCode.XMI_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "failed to read OMG XMI schema validator output: " + error.getMessage());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new XmiValidationException(
          DiagnosticCode.XMI_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "OMG XMI schema validator interrupted");
    }
  }

  private static String xmiSchemaValidator(Map<String, String> env) {
    String configured = env.get("DEDIREN_XMI_SCHEMA_VALIDATOR");
    return configured == null || configured.isBlank() ? XMI_SCHEMA_VALIDATOR : configured;
  }

  private static Path resolveOmgXmiSchemaPath(Map<String, String> env)
      throws XmiValidationException {
    Optional<Path> configured = SchemaCacheModule.nonEmptyEnvPath(env, XMI_SCHEMA_PATH_ENV);
    if (configured.isPresent()) {
      if (SchemaCacheModule.isNonEmptyFile(configured.get())) {
        return configured.get();
      }
      throw new XmiValidationException(
          "DEDIREN_XMI_SCHEMA_UNAVAILABLE",
          "OMG XMI schema file "
              + configured.get()
              + " is missing or empty; provide the official XMI.xsd or unset "
              + XMI_SCHEMA_PATH_ENV
              + " to allow cache download");
    }
    Path schemaPath;
    try {
      schemaPath =
          SchemaCacheModule.schemaCacheBaseDir(env, SCHEMA_CACHE_DIR_ENV, XMI_SCHEMA_PATH_ENV)
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
      if (line.contains(UML_NS)
          && line.contains("No matching global element declaration available")) {
        sawUmlSchemaGap = true;
        continue;
      }
      return false;
    }
    return sawUmlSchemaGap;
  }

  private static int exitWithDiagnostic(
      PrintStream stdout, String code, String message, String path) throws IOException {
    var diagnostic = new Diagnostic(code, DiagnosticSeverity.ERROR, message, path);
    stdout.println(
        JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
    return 3;
  }

  private static void validateSelectedCombinedFragmentOperators(
      ExportRequest request, GenericGraphPluginData pluginData) throws XmiExportException {
    ExportScope scope = ExportScope.fromRequest(request, pluginData);
    for (int index = 0; index < request.source().nodes().size(); index++) {
      SourceNode node = request.source().nodes().get(index);
      if (!scope.nodeIds().contains(node.id()) || !node.type().equals("CombinedFragment")) {
        continue;
      }
      String operator = umlString(node, "operator");
      if (operator != null && !SUPPORTED_SEQUENCE_FRAGMENT_OPERATORS.contains(operator)) {
        throw new XmiExportException(
            UNSUPPORTED_SEQUENCE_FRAGMENT_OPERATOR,
            "UML/XMI sequence export supports CombinedFragment operators only: alt, opt, loop, par",
            "$.nodes[" + index + "].properties.uml.operator");
      }
    }
  }

  private static void validateExportableSequenceScope(ExportRequest request)
      throws XmiExportException {
    ExportScope scope = ExportScope.fromRequest(request);
    var sourceNodesById =
        request.source().nodes().stream().collect(Collectors.toMap(SourceNode::id, node -> node));

    for (int index = 0; index < request.source().relationships().size(); index++) {
      SourceRelationship relationship = request.source().relationships().get(index);
      if (!scope.relationshipIds().contains(relationship.id())
          || !relationship.type().equals("Message")) {
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
                + source.type()
                + " -> "
                + target.type(),
            "$.relationships[" + index + "]");
      }
    }

    for (int index = 0; index < request.source().nodes().size(); index++) {
      SourceNode node = request.source().nodes().get(index);
      if (scope.nodeIds().contains(node.id()) && unsupportedSequenceNode(node.type())) {
        throw new XmiExportException(
            UNSUPPORTED_SEQUENCE_NODE,
            "UML/XMI sequence export does not support selected "
                + node.type()
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

  private static List<String> umlTextArray(SourceNode node, String field) {
    JsonNode value = node.properties().get("uml");
    value = value == null ? null : value.get(field);
    if (value == null || !value.isArray()) {
      return List.of();
    }
    var values = new java.util.ArrayList<String>();
    for (JsonNode item : value) {
      if (item.isTextual()) {
        values.add(item.asText());
      }
    }
    return values;
  }

  private static int umlPositiveInt(SourceNode node, String field, int fallback) {
    JsonNode value = node.properties().get("uml");
    value = value == null ? null : value.get(field);
    if (value == null || !value.isIntegralNumber() || value.intValue() < 1) {
      return fallback;
    }
    return value.intValue();
  }

  private static String umlString(SourceRelationship relationship, String field) {
    JsonNode value = relationship.properties().get("uml");
    value = value == null ? null : value.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  private static BigInteger umlSequence(SourceRelationship relationship) {
    JsonNode value = relationship.properties().get("uml");
    value = value == null ? null : value.get("sequence");
    return value != null && value.isIntegralNumber()
        ? value.bigIntegerValue()
        : BigInteger.valueOf(Long.MAX_VALUE);
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
      return new String[] {"0", "*"};
    }
    return new String[] {value, value};
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

  private static String text(String value) {
    return (value == null ? "" : value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  private record ExportScope(Set<String> nodeIds, Set<String> relationshipIds) {
    static ExportScope fromRequest(ExportRequest request) {
      return fromRequest(request, genericGraphPluginData(request));
    }

    static ExportScope fromRequest(ExportRequest request, GenericGraphPluginData pluginData) {
      var sourceNodesById =
          request.source().nodes().stream().collect(Collectors.toMap(SourceNode::id, node -> node));
      var nodeIds =
          request.layoutResult().nodes().stream()
              .map(node -> node.sourceId())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      var relationshipIds =
          request.layoutResult().edges().stream()
              .map(edge -> edge.sourceId())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      String viewId = request.layoutResult().viewId();
      Optional<GenericGraphView> sourceView =
          viewId == null
              ? Optional.empty()
              : pluginData.views().stream().filter(view -> viewId.equals(view.id())).findFirst();
      sourceView.ifPresent(
          view ->
              addSelectedSourceOnlySequenceFragmentScope(
                  nodeIds,
                  relationshipIds,
                  view,
                  sourceNodesById,
                  request.source().relationships()));
      for (LaidOutGroup group : request.layoutResult().groups()) {
        String sourceId = semanticGroupSourceId(group);
        if (sourceId != null) {
          nodeIds.add(sourceId);
        }
      }
      var activityIds =
          nodeIds.stream()
              .map(sourceNodesById::get)
              .filter(node -> node != null)
              .map(node -> umlString(node, "activity"))
              .filter(value -> value != null)
              .toList();
      nodeIds.addAll(activityIds);
      for (SourceRelationship relationship : request.source().relationships()) {
        if (!relationshipIds.contains(relationship.id())
            || !relationship.type().equals("Message")) {
          continue;
        }
        addMessageLifelineEndpoint(nodeIds, sourceNodesById, relationship.source());
        addMessageLifelineEndpoint(nodeIds, sourceNodesById, relationship.target());
        String interactionId = umlString(relationship, "interaction");
        if (interactionId != null) {
          nodeIds.add(interactionId);
        }
      }
      var interactionIds =
          nodeIds.stream()
              .map(sourceNodesById::get)
              .filter(node -> node != null)
              .map(node -> umlString(node, "interaction"))
              .filter(value -> value != null)
              .toList();
      nodeIds.addAll(interactionIds);
      return new ExportScope(nodeIds, relationshipIds);
    }

    private static void addSelectedSourceOnlySequenceFragmentScope(
        Set<String> nodeIds,
        Set<String> relationshipIds,
        GenericGraphView view,
        Map<String, SourceNode> sourceNodesById,
        List<SourceRelationship> sourceRelationships) {
      if (view.kind() != GenericGraphViewKind.UML_SEQUENCE) {
        return;
      }
      Set<String> viewRelationshipIds = new HashSet<>(view.relationships());
      var sourceRelationshipsById =
          sourceRelationships.stream()
              .collect(Collectors.toMap(SourceRelationship::id, relationship -> relationship));
      for (String nodeId : view.nodes()) {
        SourceNode node = sourceNodesById.get(nodeId);
        if (node == null || !isSourceOnlySequenceFragmentNode(node)) {
          continue;
        }
        nodeIds.add(node.id());
        if (node.type().equals("InteractionOperand")) {
          addSelectedOperandMessageFragments(
              relationshipIds, viewRelationshipIds, sourceRelationshipsById, node);
        }
      }
    }

    private static boolean isSourceOnlySequenceFragmentNode(SourceNode node) {
      return node.type().equals("CombinedFragment") || node.type().equals("InteractionOperand");
    }

    private static void addSelectedOperandMessageFragments(
        Set<String> relationshipIds,
        Set<String> viewRelationshipIds,
        Map<String, SourceRelationship> sourceRelationshipsById,
        SourceNode operand) {
      for (String fragmentId : umlTextArray(operand, "fragments")) {
        SourceRelationship relationship = sourceRelationshipsById.get(fragmentId);
        if (relationship != null
            && relationship.type().equals("Message")
            && viewRelationshipIds.contains(fragmentId)) {
          relationshipIds.add(fragmentId);
        }
      }
    }

    private static void addMessageLifelineEndpoint(
        Set<String> nodeIds, Map<String, SourceNode> sourceNodesById, String endpointId) {
      SourceNode endpoint = sourceNodesById.get(endpointId);
      if (endpoint != null && endpoint.type().equals("Lifeline")) {
        nodeIds.add(endpoint.id());
      }
    }
  }

  private record CombinedFragmentExport(
      SourceNode node,
      String fragmentId,
      String operator,
      List<OperandExport> operands,
      List<String> coveredNodeIds,
      int sourceOrder) {}

  private record OperandExport(
      SourceNode node,
      String operandId,
      int order,
      String guard,
      List<String> fragmentIds,
      int sourceOrder) {}

  private record TopLevelInteractionFragment(
      BigInteger sequence,
      int sourceOrder,
      CombinedFragmentExport combinedFragment,
      MessageExport message) {}

  private record MessageExport(
      SourceRelationship relationship,
      String messageId,
      String sourceNodeId,
      String targetNodeId,
      String sourceEventId,
      String receiveEventId,
      BigInteger sequence,
      int sourceOrder,
      String messageSort) {}

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
